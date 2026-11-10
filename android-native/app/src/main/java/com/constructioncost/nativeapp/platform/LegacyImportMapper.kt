package com.constructioncost.nativeapp.platform

import android.content.Context
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import org.json.JSONArray
import org.json.JSONObject
import com.constructioncost.nativeapp.domain.Money
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Converts the authenticated web state into a complete candidate Room snapshot. */
class LegacyImportMapper(private val context: Context) {
    data class Result(val records: JSONObject, val counts: Map<String, Int>, val recordedPaise: Long, val paidPaise: Long)

    fun map(state: JSONObject): Result {
        require(state.getInt("schema") == 1 && state.getJSONObject("project").optString("currency", "INR") == "INR") { "Unsupported legacy ledger or currency" }
        val sourceNames = listOf("bills", "payments", "allocations", "plans", "contracts", "entries", "files", "audit")
        val source = sourceNames.associateWith { name -> state.getJSONArray(name).objects() }
        source.forEach { (name, items) -> require(items.map { it.getString("id") }.distinct().size == items.size) { "Duplicate legacy $name ID" } }
        val records = JSONObject().also { all -> LedgerRepository.TABLES.forEach { all.put(it, JSONArray()) } }
        fun add(table: String, row: JSONObject) { records.getJSONArray(table).put(row) }
        fun record(table: String, id: String, vararg pairs: Pair<String, Any?>) = add(table, JSONObject().apply {
            put("id", id); pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        })
        fun evidence(kind: String, item: JSONObject) = record("import_records", "legacy:$kind:${item.getString("id")}",
            "source" to "BuildLedgerWeb/$kind", "originalJson" to item.toString(), "createdAt" to System.currentTimeMillis())
        val project = state.getJSONObject("project")
        val projectId = "legacy-project"
        record("projects", projectId, "name" to project.getString("name").ifBlank { "Imported construction project" }, "revision" to 0L, "archived" to false)
        val budget = if (project.has("budget")) integer(project, "budget") else 0L
        require(budget >= 0) { "Invalid legacy project budget" }
        if (budget > 0) record("budgets", projectId, "projectId" to projectId, "amountPaise" to budget)
        record("import_records", "legacy:project", "source" to "BuildLedgerWeb/project", "originalJson" to project.toString(), "createdAt" to System.currentTimeMillis())
        state.optJSONObject("settings")?.let { record("import_records", "legacy:settings", "source" to "BuildLedgerWeb/settings", "originalJson" to it.toString(), "createdAt" to System.currentTimeMillis()) }

        val classifications = mutableMapOf<String, String>()
        fun category(id: String, name: String, kind: String, parent: String? = null, order: Int = 0, archived: Boolean = false, nature: String = ""): String {
            val nativeId = "legacy:$id"
            if (classifications.putIfAbsent(nativeId, kind) == null) record("classifications", nativeId, "projectId" to projectId,
                "name" to name, "kind" to kind, "parentId" to parent, "sortOrder" to order, "archived" to archived, "nature" to nature)
            return nativeId
        }
        val defaultCatalog = JSONObject(context.assets.open("default_catalog.json").bufferedReader().use { it.readText() })
        val savedCatalog = state.optJSONObject("settings")?.optJSONArray("costCatalog")?.objects().orEmpty()
        val mergedCatalog = linkedMapOf<String, JSONObject>()
        defaultCatalog.getJSONArray("costCatalog").objects().forEach { mergedCatalog[it.getString("id")] = it }
        savedCatalog.forEach { mergedCatalog[it.getString("id")] = it }
        mergedCatalog.values.forEach { node ->
            val kind = when (node.getString("type")) { "head" -> "COST_HEAD"; "subhead" -> "COST_SUBHEAD"; "detail" -> "COST_DETAIL"; else -> error("Invalid cost category type") }
            category("cost:${node.getString("id")}", node.getString("label"), kind,
                node.optionalString("parentId")?.let { "legacy:cost:$it" },
                node.optInt("order"), !node.optBoolean("active", true))
        }
        val stages = defaultCatalog.getJSONArray("workStages").objectsOrStrings()
        val workStageByName = mutableMapOf<String, String>()
        stages.forEachIndexed { index, name -> workStageByName[name.trim().lowercase()] = category("stage:${index + 1}", name, "STAGE", order = index) }
        fun stage(name: String): String? = name.trim().takeIf(String::isNotEmpty)?.let {
            workStageByName.getOrPut(it.lowercase()) { category("stage:custom:${UUID.nameUUIDFromBytes(it.lowercase().toByteArray())}", it, "STAGE") }
        }
        fun workNode(work: String, substage: String, material: String): String? {
            val parent = stage(work) ?: return null
            if (substage.isBlank()) return parent
            val sub = category("sub:${UUID.nameUUIDFromBytes("$parent/${substage.lowercase()}".toByteArray())}", substage, "SUBSTAGE", parent)
            return if (material.isBlank()) sub else category("material:${UUID.nameUUIDFromBytes("$sub/${material.lowercase()}".toByteArray())}", material, "MATERIAL", sub)
        }
        state.optJSONObject("settings")?.optJSONArray("catalog")?.objects()?.forEach { workNode(it.optString("stage"), it.optString("substage"), "") }
        val vendors = mutableMapOf<String, String>()
        fun vendor(name: String): String? = name.trim().takeIf(String::isNotEmpty)?.let { clean ->
            vendors.getOrPut(clean.lowercase()) {
                val id = "legacy:vendor:${UUID.nameUUIDFromBytes(clean.lowercase().toByteArray())}"
                record("vendors", id, "projectId" to projectId, "name" to clean, "note" to "")
                id
            }
        }
        val contracts = source.getValue("contracts").associateBy { it.getString("id") }
        val plans = source.getValue("plans").associateBy { it.getString("id") }
        val bills = source.getValue("bills").associateBy { it.getString("id") }
        val payments = source.getValue("payments").associateBy { it.getString("id") }
        fun plannedClassification(type: String, item: JSONObject) {
            val id = item.getString("id")
            val rawCost = listOf("costDetailId", "costSubheadId", "costHeadId").firstNotNullOfOrNull { item.optionalString(it) }
            val cost = rawCost?.let { raw ->
                require(classifications.containsKey("legacy:cost:$raw")) { "Unknown legacy planning cost classification" }
                "legacy:cost:$raw"
            }
            val work = workNode(item.optString("work"), item.optString("substage"), "")
            val place = item.optionalString("place")?.let { category("place:${UUID.nameUUIDFromBytes(it.lowercase().toByteArray())}", it, "PLACE") }
            listOfNotNull(cost, work, place).forEach { classification ->
                record("planned_classifications", "legacy:planned:$type:$id:$classification",
                    "classificationId" to classification,
                    "commitmentId" to if (type == "contract") "legacy:contract:$id" else null,
                    "instalmentId" to if (type == "plan") "legacy:plan:$id" else null)
            }
        }
        source.getValue("contracts").forEach { c ->
            record("commitments", "legacy:contract:${c.getString("id")}", "projectId" to projectId, "title" to c.getString("title"),
                "amountPaise" to positive(c, "amount"), "vendorId" to vendor(c.optString("vendor")), "date" to date(c, "date", "createdAt"),
                "revision" to 0L, "archived" to false, "note" to c.optString("notes"),
                "calculationJson" to c.optJSONObject("calculation")?.let { legacy ->
                    JSONObject().put("quantity", legacy.getString("qty")).put("rate", legacy.getString("rate"))
                        .put("multiplier", legacy.optString("multiplier", "1"))
                        .put("quantityUnit", legacy.optionalString("quantityUnitId") ?: legacy.optionalString("unitId") ?: "unit")
                        .put("rateUnit", legacy.optionalString("unitId") ?: "unit")
                        .also { json -> listOf("discountAmount", "discountPercent", "taxAmount", "taxPercent", "transport", "adjustment").forEach { json.put(it, "0") } }.toString()
                }.orEmpty())
            plannedClassification("contract", c)
            evidence("contract", c)
        }
        source.getValue("plans").forEach { p ->
            val contractId = p.optionalString("contractId")
            require(contractId == null || contracts.containsKey(contractId)) { "Legacy instalment refers to a missing contract" }
            val reminder = if (p.has("remind")) p.get("remind") else 0
            require((reminder is Int || reminder is Long) && (reminder as Number).toLong() in -1..365) { "Invalid legacy reminder days" }
            val days = (reminder as Number).toInt()
            record("instalments", "legacy:plan:${p.getString("id")}", "projectId" to projectId, "title" to p.getString("title"),
                "amountPaise" to positive(p, "amount"), "dueDate" to date(p, "due"),
                "commitmentId" to contractId?.let { "legacy:contract:$it" }, "vendorId" to vendor(p.optString("vendor")),
                "cancelled" to p.optBoolean("cancelled"), "remind" to (days >= 0), "revision" to 0L, "note" to p.optString("notes"))
            record("reminders", "legacy:reminder:${p.getString("id")}", "instalmentId" to "legacy:plan:${p.getString("id")}", "enabled" to (days >= 0 && !p.optBoolean("cancelled")), "leadDays" to maxOf(0, days))
            plannedClassification("plan", p)
            evidence("plan", p)
        }

        var recorded = 0L
        source.getValue("bills").forEach { bill ->
            val legacyId = bill.getString("id")
            val total = positive(bill, "total")
            recorded = Math.addExact(recorded, total)
            val billId = "legacy:bill:$legacyId"
            val originalLines = bill.optJSONArray("lines")?.objects().orEmpty().ifEmpty { listOf(JSONObject().put("label", bill.optString("title")).put("value", total)) }
            val weights = originalLines.map { line -> (if (line.has("value")) integer(line, "value") else if (line.has("directAmountPaise")) integer(line, "directAmountPaise") else 0L).also { amount -> require(amount >= 0) { "Negative legacy line" } } }
            val amounts = Money.proportional(total, weights)
            val description = bill.optString("title").ifBlank { originalLines.first().optString("label", "Imported expense") }
            val request = JSONObject().put("legacy", true).put("sourceId", legacyId)
            record("expenses", billId, "projectId" to projectId, "description" to description,
                "amountPaise" to total, "date" to date(bill, "date", "recordedAt"), "vendorId" to vendor(bill.optString("vendor")),
                "note" to bill.optString("notes"), "mode" to "DIRECT_AMOUNT", "calculationJson" to "", "revision" to 0L,
                "voided" to false, "commitmentId" to null, "instalmentId" to null, "idempotencyKey" to billId, "requestJson" to request.toString(),
                "invoiceNumber" to bill.optString("invoice").also { require(it.length <= 200 && it.none(Char::isISOControl)) { "Invalid legacy invoice reference" } })
            val allocations = mutableMapOf<Pair<String, String>, Long>()
            originalLines.forEachIndexed { index, line ->
                val amount = amounts[index]
                val label = line.optString("label").ifBlank { description }
                record("expense_lines", "$billId:line:$index", "expenseId" to billId, "description" to label,
                    "amountPaise" to amount, "mode" to if (line.optString("mode") == "calculated") "CALCULATED" else "DIRECT_AMOUNT",
                    "quantity" to line.optString("qty").takeIf { it.isNotBlank() }, "rate" to line.optString("rate").takeIf { it.isNotBlank() },
                    "multiplier" to line.optString("multiplier", "1"), "quantityUnit" to (line.optionalString("quantityUnitId") ?: line.optionalString("unitId") ?: "unit"),
                    "rateUnit" to (line.optionalString("unitId") ?: "unit"), "legacyJson" to line.toString(),
                    "nature" to (line.optionalString("costNature") ?: bill.optionalString("costNature").orEmpty()))
                if (amount == 0L) return@forEachIndexed
                val work = workNode(line.optString("work", bill.optString("work")), line.optString("substage", bill.optString("substage")), label)
                val costRaw = listOf("costDetailId", "costSubheadId", "costHeadId").firstNotNullOfOrNull { key -> line.optionalString(key) }
                    ?: listOf("costDetailId", "costSubheadId", "costHeadId").firstNotNullOfOrNull { key -> bill.optionalString(key) }
                val cost = costRaw?.let { "legacy:cost:$it" }?.takeIf { classifications.containsKey(it) }
                val place = line.optString("place", bill.optString("place")).takeIf { it.isNotBlank() }?.let { category("place:${UUID.nameUUIDFromBytes(it.lowercase().toByteArray())}", it, "PLACE") }
                listOf("WORK" to work, "COST" to cost, "PLACE" to place).forEach { (dimension, id) ->
                    val target = id ?: category("unclassified:$dimension", "Unclassified / Legacy", when (dimension) { "WORK" -> "STAGE"; "COST" -> "COST_HEAD"; else -> "PLACE" })
                    allocations[dimension to target] = Math.addExact(allocations[dimension to target] ?: 0, amount)
                }
            }
            allocations.forEach { (key, amount) -> record("classification_allocations", "$billId:${key.first}:${key.second}",
                "expenseId" to billId, "classificationId" to key.second, "dimension" to key.first, "amountPaise" to amount) }
            evidence("bill", bill)
        }
        var paid = 0L
        source.getValue("payments").forEach { p ->
            val legacyId = p.getString("id")
            val amount = positive(p, "amount")
            paid = Math.addExact(paid, amount)
            val planId = p.optionalString("planId")
            require(planId == null || plans.containsKey(planId)) { "Legacy payment refers to a missing instalment" }
            record("payments", "legacy:payment:$legacyId", "projectId" to projectId, "amountPaise" to amount,
                "date" to date(p, "date", "recordedAt"), "vendorId" to vendor(p.optString("vendor")),
                "reference" to p.optString("reference"), "note" to p.optString("notes"), "kind" to "PAYMENT",
                "originalPaymentId" to null, "instalmentId" to planId?.let { "legacy:plan:$it" },
                "idempotencyKey" to "legacy:payment:$legacyId", "requestJson" to JSONObject().put("legacy", true).put("sourceId", legacyId).toString())
            evidence("payment", p)
        }
        val groupedAllocations = mutableMapOf<Pair<String, String>, Long>()
        source.getValue("allocations").forEach { a ->
            val paymentId = a.getString("paymentId"); val billId = a.getString("billId")
            require(payments.containsKey(paymentId) && bills.containsKey(billId)) { "Orphaned legacy payment allocation" }
            val key = paymentId to billId
            groupedAllocations[key] = Math.addExact(groupedAllocations[key] ?: 0, positive(a, "amount"))
            evidence("allocation", a)
        }
        groupedAllocations.forEach { (key, amount) -> record("payment_allocations", "legacy:allocation:${key.first}:${key.second}",
            "paymentId" to "legacy:payment:${key.first}", "expenseId" to "legacy:bill:${key.second}", "amountPaise" to amount) }
        source.getValue("entries").forEach { e ->
            record("activity_entries", "legacy:entry:${e.getString("id")}", "projectId" to projectId,
                "title" to e.optString("title", "Work note"), "date" to date(e, "date", "recordedAt"),
                "note" to e.optString("notes"), "revision" to 0L)
            evidence("entry", e)
        }
        val entryIds = source.getValue("entries").map { it.getString("id") }.toSet()
        fun fileMetadata(file: JSONObject, key: String, limit: Int): String {
            if (!file.has(key) || file.isNull(key)) return ""
            val value = file.get(key)
            require(value is String && value.length <= limit) { "Invalid legacy file $key" }
            return value
        }
        source.getValue("files").forEach { f ->
            val id = f.getString("id")
            val parent = f.optionalString("parentId")
            require(parent == null || source.getValue("files").any { it.getString("id") == parent }) { "Legacy document revision parent is missing" }
            record("attachments", id, "projectId" to projectId, "name" to f.optString("name", "Attachment"),
                "mimeType" to f.optString("type", "application/octet-stream"), "size" to positive(f, "size"),
                "sha256" to f.getString("sha256"), "storageName" to "$id.bin", "createdAt" to timestamp(f.optString("importedAt")),
                "revision" to 0L, "parentId" to parent,
                "category" to fileMetadata(f, "category", 200), "status" to fileMetadata(f, "status", 200),
                "revisionLabel" to fileMetadata(f, "revision", 200), "work" to fileMetadata(f, "work", 200),
                "place" to fileMetadata(f, "place", 200), "notes" to fileMetadata(f, "notes", 10_000))
            val link = f.optString("linkId")
            val target = when { bills.containsKey(link) -> "expenseId" to "legacy:bill:$link"; payments.containsKey(link) -> "paymentId" to "legacy:payment:$link"; link in entryIds -> "noteId" to "legacy:entry:$link"; else -> null }
            if (target != null) record("attachment_links", "legacy:link:$id", "attachmentId" to id,
                "expenseId" to if (target.first == "expenseId") target.second else null,
                "paymentId" to if (target.first == "paymentId") target.second else null,
                "noteId" to if (target.first == "noteId") target.second else null)
            evidence("file", f)
        }
        source.getValue("audit").forEach { a ->
            record("audit_records", "legacy:audit:${a.getString("id")}", "recordType" to "legacy",
                "recordId" to a.optString("targetId", a.getString("id")), "action" to a.optString("type", "LOG"),
                "beforeJson" to "null", "afterJson" to JSONObject(a.toString()).put("_auditProjectId", projectId).toString(), "createdAt" to timestamp(a.optString("at")))
            evidence("audit", a)
        }
        val counts = source.mapValues { it.value.size }
        return Result(records, counts, recorded, paid)
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.objectsOrStrings() = (0 until length()).map { getString(it) }
    private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key).takeIf { it.isNotBlank() }
    private fun integer(row: JSONObject, key: String): Long {
        val value = row.get(key)
        require(value is Int || value is Long) { "Invalid legacy $key" }
        return (value as Number).toLong().also { require(it in 0..9007199254740991L) { "Unsafe legacy $key" } }
    }
    private fun positive(row: JSONObject, key: String) = integer(row, key).also { require(it > 0) { "Invalid legacy $key" } }
    private fun date(row: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        row.optString(key).takeIf { it.length >= 10 }?.take(10)?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }
    } ?: error("Legacy record lacks a valid date")
    private fun timestamp(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
}
