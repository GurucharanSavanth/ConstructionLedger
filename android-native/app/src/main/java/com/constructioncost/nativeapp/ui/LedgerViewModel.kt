package com.constructioncost.nativeapp.ui

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal val Context.ledgerPreferences by preferencesDataStore("ledger_ui")

data class EntryDraft(val fields: Map<String, String> = emptyMap()) {
    fun get(key: String, fallback: String = "") = fields[key] ?: fallback
    fun with(key: String, value: String) = copy(fields = fields + (key to value))
    val kind get() = get("kind", "Direct cost")
    val id get() = get("id")
    val projectId get() = get("projectId")
    val date get() = get("date", LocalDate.now().toString())
    val hasContent get() = get("description").isNotBlank() || get("amount").isNotBlank() || get("note").isNotBlank()
    fun json(): String = JSONObject(fields).toString()

    companion object {
        fun fresh(projectId: String, kind: String = "Direct cost") = EntryDraft(mapOf(
            "id" to newId(), "key" to newId(), "projectId" to projectId, "kind" to kind,
            "date" to LocalDate.now().toString(), "mode" to "DIRECT_AMOUNT"
        ))
        fun read(text: String): EntryDraft {
            val json = JSONObject(text)
            return EntryDraft(json.keys().asSequence().associateWith { json.getString(it) })
        }
    }
}

data class LedgerUiState(
    val ready: Boolean = false, val projectId: String = "", val draft: EntryDraft = EntryDraft(),
    val saving: Boolean = false, val error: String? = null, val message: String? = null,
    val reminders: Boolean = false, val showArchived: Boolean = false
)

/** Repository writes are the only authority; an unsuccessful save never clears the durable draft. */
class LedgerViewModel(
    private val context: Context,
    val repository: LedgerRepository,
    private val savedState: SavedStateHandle,
    private val preferenceStore: DataStore<Preferences> = context.ledgerPreferences
) : ViewModel() {
    private val mutable = MutableStateFlow(LedgerUiState())
    val state: StateFlow<LedgerUiState> = mutable.asStateFlow()
    private val writes = Mutex()
    private val draftKey = stringPreferencesKey("entry_draft")
    private val projectKey = stringPreferencesKey("project")
    private val settingsKey = stringPreferencesKey("settings")

    init {
        viewModelScope.launch {
            try {
                val preferences = preferenceStore.data.first()
                val projects = repository.projects.first { it.isNotEmpty() }
                val selected = savedState.get<String>("project") ?: preferences[projectKey]
                val projectId = projects.firstOrNull { it.id == selected }?.id ?: projects.firstOrNull { !it.archived }?.id.orEmpty()
                val draftText = savedState.get<String>("draft") ?: preferences[draftKey]
                val draft = draftText?.let { EntryDraft.read(it) } ?: EntryDraft.fresh(projectId)
                val settings = JSONObject(preferences[settingsKey] ?: "{}")
                mutable.value = LedgerUiState(true, projectId, draft,
                    reminders = settings.optBoolean("reminders"), showArchived = settings.optBoolean("showArchived"))
                repository.projects.collect { available ->
                    val selectedId = mutable.value.projectId
                    if (available.any { it.id == selectedId && !it.archived }) return@collect
                    val next = available.firstOrNull { !it.archived }?.id ?: available.firstOrNull()?.id.orEmpty()
                    if (next == selectedId) return@collect
                    val retained = mutable.value.draft.takeIf { it.hasContent } ?: EntryDraft.fresh(next, mutable.value.draft.kind)
                    mutable.value = mutable.value.copy(projectId = next, draft = retained)
                    savedState["project"] = next
                    savedState["draft"] = retained.json()
                    preferenceStore.edit { it[projectKey] = next; it[draftKey] = retained.json() }
                }
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(ready = true, error = "Unable to load local preferences: ${error.message}")
            }
        }
    }

    fun field(key: String, value: String) {
        if (mutable.value.saving) return
        val draft = mutable.value.draft.with(key, value)
        mutable.value = mutable.value.copy(draft = draft, error = null, message = null)
        savedState["draft"] = draft.json()
        viewModelScope.launch { persistDraft(draft) }
    }

    private suspend fun persistDraft(draft: EntryDraft) = writes.withLock {
        // A queued keystroke must never overwrite a newer draft or the cleared successful submission.
        if (mutable.value.draft == draft) preferenceStore.edit { it[draftKey] = draft.json() }
    }

    fun selectProject(id: String) {
        mutable.value = mutable.value.copy(projectId = id)
        savedState["project"] = id
        viewModelScope.launch { preferenceStore.edit { it[projectKey] = id } }
        if (!mutable.value.draft.hasContent) resetDraft()
    }

    fun resetDraft(kind: String = mutable.value.draft.kind) {
        if (mutable.value.saving) return
        val draft = EntryDraft.fresh(mutable.value.projectId, kind)
        mutable.value = mutable.value.copy(draft = draft, error = null)
        savedState["draft"] = draft.json()
        viewModelScope.launch { persistDraft(draft) }
    }

    fun reassignDraftToCurrentProject() {
        if (mutable.value.saving || mutable.value.projectId.isBlank()) return
        val draft = movedDraftToProject(mutable.value.draft, mutable.value.projectId)
        mutable.value = mutable.value.copy(draft = draft, error = null, message = "Draft moved to the current project. Review its links and schedule before saving.")
        savedState["draft"] = draft.json()
        viewModelScope.launch { persistDraft(draft) }
    }

    private fun movedDraftToProject(current: EntryDraft, projectId: String): EntryDraft {
        val fields = current.fields.toMutableMap()
        fields["id"] = newId(); fields["key"] = newId(); fields["projectId"] = projectId
        listOf("revision", "vendor", "WORK", "COST", "PLACE", "originalClasses", "classesChanged", "attachments",
            "commitment", "instalment", "expense", "lineId", "originalAmount", "originalSchedules", "schedules")
            .forEach(fields::remove)
        if (current.get("originalLines").isNotBlank()) {
            fields["invoiceLines"] = JSONArray(readLines(current.get("originalLines")).map { lineJson(it.copy(id = newId())) }).toString()
            fields["mode"] = "DIRECT_AMOUNT"
        } else if (current.get("invoiceLines").isNotBlank()) {
            fields["invoiceLines"] = JSONArray(readLines(current.get("invoiceLines")).map { lineJson(it.copy(id = newId())) }).toString()
        }
        fields.remove("originalLines")
        return EntryDraft(fields)
    }

    fun settings(reminders: Boolean = mutable.value.reminders, showArchived: Boolean = mutable.value.showArchived) {
        mutable.value = mutable.value.copy(reminders = reminders, showArchived = showArchived)
        viewModelScope.launch {
            preferenceStore.edit {
                it[settingsKey] = JSONObject().put("reminders", reminders).put("showArchived", showArchived).toString()
            }
        }
    }

    fun dismissMessage() { mutable.value = mutable.value.copy(message = null) }
    fun showError(message: String) { mutable.value = mutable.value.copy(error = message) }

    fun command(success: String, action: suspend () -> Unit) {
        if (mutable.value.saving) return
        mutable.value = mutable.value.copy(saving = true, error = null, message = null)
        viewModelScope.launch {
            try {
                action()
                mutable.value = mutable.value.copy(saving = false, message = success)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(saving = false, error = error.message ?: "Save failed. Your inputs are retained.")
            }
        }
    }

    fun editExpense(id: String, then: () -> Unit) {
        command("Expense loaded for editing") {
            val expense = repository.expense(id) ?: error("Expense no longer exists.")
            val draft = draftForExpense(expense)
            mutable.value = mutable.value.copy(draft = draft)
            savedState["draft"] = draft.json()
            persistDraft(draft)
            then()
        }
    }

    fun editInstalment(row: InstalmentRow, then: () -> Unit) {
        command("Schedule loaded for editing") {
            val selected = repository.plannedClassifications("INSTALMENT", row.id)
            val nodes = repository.classifications(row.projectId).first()
            val draft = EntryDraft(mapOf(
                "id" to row.id, "key" to newId(), "projectId" to row.projectId, "kind" to "Plan",
                "description" to row.title, "amount" to Money.input(row.amountPaise), "date" to row.dueDate,
                "vendor" to row.vendorId.orEmpty(), "commitment" to row.commitmentId.orEmpty(),
                "remind" to row.remind.toString(), "revision" to row.revision.toString(),
                "reminderDays" to row.reminderDays.toString()
            ) + plannedFields(selected, nodes))
            mutable.value = mutable.value.copy(draft = draft)
            savedState["draft"] = draft.json()
            persistDraft(draft)
            then()
        }
    }

    fun editCommitment(row: CommitmentInfo, then: () -> Unit) {
        command("Commitment loaded for editing") {
            val plans = repository.instalments(row.projectId).first().filter { it.commitmentId == row.id && !it.cancelled }
            val selected = repository.plannedClassifications("COMMITMENT", row.id)
            val nodes = repository.classifications(row.projectId).first()
            val draft = draftForCommitment(row, plans, selected, nodes)
            mutable.value = mutable.value.copy(draft = draft)
            savedState["draft"] = draft.json()
            persistDraft(draft)
            then()
        }
    }

    fun editNote(row: ActivityNote, then: () -> Unit) = loadDraft(mapOf(
        "id" to row.id, "key" to newId(), "projectId" to row.projectId, "kind" to "Work log",
        "description" to row.title, "date" to row.date, "note" to row.note,
        "revision" to row.revision.toString()
    ), then)

    private fun loadDraft(fields: Map<String, String>, then: () -> Unit) {
        if (mutable.value.saving) return
        val draft = EntryDraft(fields)
        mutable.value = mutable.value.copy(draft = draft, error = null)
        savedState["draft"] = draft.json()
        viewModelScope.launch { persistDraft(draft) }
        then()
    }

    fun preparePayment(expense: ExpenseRow? = null, instalment: InstalmentRow? = null) {
        resetDraft("Payment")
        expense?.let {
            field("amount", Money.input(it.outstandingPaise)); field("expense", it.id)
            field("vendor", it.vendorId.orEmpty()); field("description", "Payment for ${it.description}")
        }
        instalment?.let {
            field("amount", Money.input(it.outstandingPaise)); field("instalment", it.id)
            field("vendor", it.vendorId.orEmpty()); field("description", "Payment for ${it.title}")
        }
    }

    fun saveDraft(onSuccess: () -> Unit) {
        if (mutable.value.saving) return
        val draft = finishTimer(mutable.value.draft, System.currentTimeMillis())
        command("Saved successfully") {
            require(draft.projectId.isNotBlank()) { "Choose a project first." }
            LocalDate.parse(draft.date)
            persistDraft(draft)
            when (draft.kind) {
                "Payment" -> repository.savePayment(payment(draft))
                "Plan" -> repository.saveInstalment(instalment(draft))
                "Contract" -> repository.saveCommitment(commitment(draft))
                "Work log" -> repository.saveNote(ActivityNote(draft.id, draft.projectId,
                    draft.get("description").trim(), draft.date, draft.get("note"), draft.get("revision").toLongOrNull() ?: 0))
                "Allocate advance" -> error("Choose an advance and unpaid expense in Payments.")
                else -> repository.saveExpense(expense(draft))
            }
            val cleared = EntryDraft.fresh(mutable.value.projectId, draft.kind)
            mutable.value = mutable.value.copy(draft = cleared)
            savedState["draft"] = cleared.json()
            persistDraft(cleared)
            onSuccess()
        }
    }

    companion object {
        internal fun lineClasses(line: ExpenseLineInput): Map<String, String> {
            val evidence = runCatching { JSONObject(line.legacyJson) }.getOrNull() ?: return emptyMap()
            val nodes = evidence.optJSONObject("_nativeClassifications") ?: return emptyMap()
            require(nodes.keys().asSequence().all { it in setOf("WORK", "COST", "PLACE") }) { "Invalid invoice classification dimension" }
            return nodes.keys().asSequence().associateWith { key ->
                val id = nodes.get(key)
                require(id is String && id.isNotBlank() && id.length <= 200) { "Invalid invoice classification" }
                id
            }
        }

        internal fun withLineClasses(line: ExpenseLineInput, nodes: Map<String, String>): ExpenseLineInput {
            require(nodes.keys.all { it in setOf("WORK", "COST", "PLACE") }) { "Invalid invoice classification dimension" }
            val selected = nodes.filterValues { it.isNotBlank() }
            require(selected.values.all { it.length <= 200 }) { "Invalid invoice classification" }
            if (lineClasses(line) == selected) return line
            val evidence = if (line.legacyJson.isBlank()) JSONObject() else
                runCatching { JSONObject(line.legacyJson) }.getOrElse { JSONObject().put("_originalEvidence", line.legacyJson) }
            evidence.put("_nativeClassifications", JSONObject(selected))
            return line.copy(legacyJson = evidence.toString())
        }

        internal fun invoiceClassifications(lines: List<ExpenseLineInput>, draft: EntryDraft): List<ClassificationPart> =
            listOf("WORK", "COST", "PLACE").flatMap { dimension ->
                val selected = lines.map { line -> (lineClasses(line)[dimension] ?: draft.get(dimension).takeIf(String::isNotBlank)) to line.amountPaise }
                if (selected.all { it.first == null }) emptyList() else {
                    require(selected.all { it.first != null }) { "Choose $dimension for every invoice line or set an invoice classification" }
                    selected.groupBy { it.first!! }.map { (id, rows) -> ClassificationPart(id, dimension, Money.sum(rows.map { it.second })) }
                }
            }

        internal fun finishTimer(draft: EntryDraft, nowMillis: Long): EntryDraft {
            val started = draft.get("timerStarted").toLongOrNull() ?: return draft
            val seconds = Math.addExact(draft.get("timerSeconds").toLongOrNull() ?: 0,
                ((nowMillis - started) / 1000).coerceAtLeast(0))
            return EntryDraft(draft.fields + mapOf("timerStarted" to "", "timerSeconds" to seconds.toString(),
                "note" to draft.get("note").substringBefore("\nWork duration:") + "\nWork duration: $seconds seconds"))
        }

        private fun plannedFields(ids: List<String>, nodes: List<ClassificationInfo>): Map<String, String> = buildMap {
            listOf("WORK", "COST", "PLACE").forEach { dimension ->
                val selected = ids.filter { id -> nodes.any { it.id == id && kindDimension(it.kind) == dimension } }
                put("plannedOriginal$dimension", JSONArray(selected).toString())
                if (selected.size == 1) put(dimension, selected.single())
            }
        }

        private fun plannedIds(draft: EntryDraft): List<String>? {
            val dimensions = listOf("WORK", "COST", "PLACE")
            if (draft.get("revision").isNotBlank() && dimensions.none { draft.get("plannedChanged$it") == "true" }) return null
            return dimensions.flatMap { dimension ->
                if (draft.get("plannedChanged$dimension") == "true" || draft.get("revision").isBlank())
                    listOfNotNull(draft.get(dimension).takeIf { it.isNotBlank() })
                else JSONArray(draft.get("plannedOriginal$dimension", "[]")).let { array ->
                    List(array.length()) { array.getString(it) }
                }
            }
        }

        fun draftForCommitment(row: CommitmentInfo, plans: List<InstalmentRow>,
                               planned: List<String> = emptyList(), nodes: List<ClassificationInfo> = emptyList()) = EntryDraft(mapOf(
            "id" to row.id, "key" to newId(), "projectId" to row.projectId, "kind" to "Contract",
            "description" to row.title, "amount" to Money.input(row.amountPaise), "originalAmount" to row.amountPaise.toString(),
            "date" to row.date, "vendor" to row.vendorId.orEmpty(), "revision" to row.revision.toString(),
            "originalSchedules" to JSONArray(plans.map { JSONObject().put("id", it.id).put("title", it.title)
                .put("amount", Money.input(it.amountPaise)).put("date", it.dueDate).put("vendor", it.vendorId.orEmpty())
                .put("revision", it.revision).put("remind", it.remind).put("paid", it.paidPaise) }).toString()
        ) + plannedFields(planned, nodes))

        fun draftForExpense(expense: ExpenseInput): EntryDraft {
            val nativeInvoice = expense.lines.any { runCatching { JSONObject(it.legacyJson).has("_nativeInvoiceBasePaise") }.getOrDefault(false) }
            val baseLines = if (nativeInvoice) expense.lines.map { line ->
                val base = JSONObject(line.legacyJson).get("_nativeInvoiceBasePaise")
                require((base is Long || base is Int) && (base as Number).toLong() > 0) { "Invoice base evidence is invalid" }
                line.copy(amountPaise = (base as Number).toLong())
            } else expense.lines
            if (nativeInvoice) require(expense.calculation?.let { it.quantity == "1" && it.multiplier == "1" && Money.parse(it.rate) == Money.sum(baseLines.map { line -> line.amountPaise }) } == true) { "Invoice calculation and line bases differ" }
            val preserveLines = !nativeInvoice && (expense.lines.size > 1 || expense.lines.any { it.legacyJson.isNotEmpty() } && expense.calculation == null)
            val fields = linkedMapOf(
                "id" to expense.id, "key" to newId(), "projectId" to expense.projectId,
                "kind" to "Direct cost", "description" to expense.description,
                "amount" to Money.input(expense.amountPaise), "date" to expense.date,
                "mode" to if (nativeInvoice) "DIRECT_AMOUNT" else expense.mode, "vendor" to expense.vendorId.orEmpty(), "note" to expense.note,
                "revision" to expense.revision.toString(), "originalAmount" to expense.amountPaise.toString(),
                "originalLines" to if (preserveLines) JSONArray(expense.lines.map { lineJson(it) }).toString() else "",
                "originalClasses" to JSONArray(expense.classifications.map { JSONObject().put("id", it.classificationId).put("dimension", it.dimension).put("amount", it.amountPaise) }).toString(),
                "attachments" to expense.attachmentIds.joinToString(","), "commitment" to expense.commitmentId.orEmpty(),
                "instalment" to expense.instalmentId.orEmpty()
            )
            fields["invoiceNumber"] = expense.invoiceNumber
            if (nativeInvoice) {
                fields["invoiceLines"] = JSONArray(baseLines.map(::lineJson)).toString()
                fields["invoiceCharges"] = "true"
                fields["amount"] = Money.input(Money.sum(baseLines.map { it.amountPaise }))
            }
            expense.lines.singleOrNull()?.let { line ->
                fields["lineId"] = line.id; fields["quantity"] = line.quantity.orEmpty(); fields["rate"] = line.rate.orEmpty()
                fields["multiplier"] = line.multiplier; fields["quantityUnit"] = line.quantityUnit; fields["rateUnit"] = line.rateUnit
                fields["nature"] = line.nature
            }
            expense.calculation?.let { value ->
                fields.putAll(mapOf("quantity" to value.quantity, "rate" to value.rate, "multiplier" to value.multiplier,
                    "discountAmount" to value.discountAmount, "discountPercent" to value.discountPercent,
                    "taxAmount" to value.taxAmount, "taxPercent" to value.taxPercent, "transport" to value.transport,
                    "adjustment" to value.adjustment, "quantityUnit" to value.quantityUnit, "rateUnit" to value.rateUnit))
            }
            expense.classifications.groupBy { it.dimension }.forEach { (dimension, parts) ->
                if (parts.size == 1) fields[dimension] = parts.first().classificationId
            }
            return EntryDraft(fields)
        }
        internal fun invoiceDetails(draft: EntryDraft): DetailedAmount = DetailedAmount("1",
            Money.input(Money.sum(readLines(draft.get("invoiceLines")).map { it.amountPaise })),
            discountAmount = draft.get("discountAmount", "0").ifBlank { "0" }, discountPercent = draft.get("discountPercent", "0").ifBlank { "0" },
            taxAmount = draft.get("taxAmount", "0").ifBlank { "0" }, taxPercent = draft.get("taxPercent", "0").ifBlank { "0" },
            transport = draft.get("transport", "0").ifBlank { "0" }, adjustment = draft.get("adjustment", "0").ifBlank { "0" })

        fun amount(draft: EntryDraft): Long = when {
            draft.get("invoiceCharges") == "true" && draft.get("invoiceLines").isNotBlank() -> Money.calculate(invoiceDetails(draft)).totalPaise
            draft.get("mode", "DIRECT_AMOUNT") == "CALCULATED" && draft.get("originalLines").isBlank() -> calculation(draft).totalPaise
            else -> Money.parse(draft.get("amount"))
        }
        fun calculation(draft: EntryDraft) = Money.calculate(
            draft.get("quantity"), draft.get("rate"), draft.get("multiplier", "1"),
            draft.get("discountAmount", "0").ifBlank { "0" }, draft.get("discountPercent", "0").ifBlank { "0" },
            draft.get("taxAmount", "0").ifBlank { "0" }, draft.get("taxPercent", "0").ifBlank { "0" },
            draft.get("transport", "0").ifBlank { "0" }, draft.get("adjustment", "0").ifBlank { "0" },
            draft.get("quantityUnit", "unit"), draft.get("rateUnit", "unit")
        )

        fun expense(draft: EntryDraft): ExpenseInput {
            val total = amount(draft)
            val originalLines = draft.get("originalLines")
            val extraLines = draft.get("invoiceLines")
            val invoiceDetails = if (draft.get("invoiceCharges") == "true" && extraLines.isNotBlank()) invoiceDetails(draft) else null
            val lines = when {
                originalLines.isNotBlank() -> {
                    require(total == draft.get("originalAmount").toLong()) { "This preserved invoice has multiple calculation fields. Edit its individual lines before changing its total." }
                    readLines(originalLines)
                }
                extraLines.isNotBlank() -> readLines(extraLines).let { raw ->
                    if (invoiceDetails == null) raw.map { line ->
                        val evidence = runCatching { JSONObject(line.legacyJson) }.getOrNull()
                        if (evidence?.has("_nativeInvoiceBasePaise") == true) {
                            evidence.remove("_nativeInvoiceBasePaise")
                            line.copy(legacyJson = evidence.toString())
                        } else line
                    }.also { require(Money.sum(it.map { line -> line.amountPaise }) == total) { "Invoice line amounts must equal the final amount." } }
                    else {
                        val shares = Money.proportional(total, raw.map { it.amountPaise })
                        require(shares.all { it > 0 }) { "Invoice charges leave a zero-value line; combine it or reduce the discount" }
                        raw.mapIndexed { index, line ->
                            val evidence = if (line.legacyJson.isBlank()) JSONObject() else runCatching { JSONObject(line.legacyJson) }.getOrElse { JSONObject().put("_originalEvidence", line.legacyJson) }
                            line.copy(amountPaise = shares[index], legacyJson = evidence.put("_nativeInvoiceBasePaise", line.amountPaise).toString())
                        }
                    }
                }
                else -> listOf(ExpenseLineInput(
                    id = draft.get("lineId", draft.id + "-line"), description = draft.get("description").trim(), amountPaise = total,
                    mode = draft.get("mode", "DIRECT_AMOUNT"), quantity = draft.get("quantity").ifBlank { null },
                    rate = draft.get("rate").ifBlank { null }, multiplier = draft.get("multiplier", "1"),
                    quantityUnit = draft.get("quantityUnit", "unit"), rateUnit = draft.get("rateUnit", "unit"),
                    legacyJson = if (draft.get("mode") == "CALCULATED") draft.json() else "", nature = draft.get("nature")
                ))
            }
            val classifications = if (extraLines.isNotBlank() && lines.any { lineClasses(it).isNotEmpty() }) invoiceClassifications(lines, draft)
            else if (draft.get("originalClasses").isNotBlank() && draft.get("classesChanged") != "true") {
                val parts = JSONArray(draft.get("originalClasses"))
                List(parts.length()) { index -> parts.getJSONObject(index).let { ClassificationPart(it.getString("id"), it.getString("dimension"), it.getLong("amount")) } }
                    .groupBy { it.dimension }.flatMap { (_, rows) ->
                        val amounts = Money.proportional(total, rows.map { it.amountPaise })
                        rows.mapIndexed { index, row -> row.copy(amountPaise = amounts[index]) }.filter { it.amountPaise > 0 }
                    }
            } else listOf("WORK", "COST", "PLACE").mapNotNull { dimension ->
                draft.get(dimension).takeIf { it.isNotBlank() }?.let { ClassificationPart(it, dimension, total) }
            }
            val paid = draft.get("paid").takeIf { it.isNotBlank() }?.let { Money.parse(it, true) } ?: 0
            require(paid <= total) { "Paid amount exceeds the final cost." }
            return ExpenseInput(id = draft.id, idempotencyKey = draft.get("key"), projectId = draft.projectId,
                description = draft.get("description").trim(), amountPaise = total, date = draft.date,
                vendorId = draft.get("vendor").ifBlank { null }, note = draft.get("note"), mode = if (invoiceDetails != null) "CALCULATED" else draft.get("mode", "DIRECT_AMOUNT"),
                lines = lines, classifications = classifications, attachmentIds = draft.get("attachments").split(",").filter { it.isNotBlank() },
                paidPaise = paid, paymentReference = paymentReference(draft), commitmentId = draft.get("commitment").ifBlank { null },
                instalmentId = draft.get("instalment").ifBlank { null }, revision = draft.get("revision").toLongOrNull(),
                calculation = invoiceDetails ?: if (draft.get("mode") == "CALCULATED" && originalLines.isBlank() && extraLines.isBlank()) DetailedAmount(
                    draft.get("quantity"), draft.get("rate"), draft.get("multiplier", "1"), draft.get("discountAmount", "0").ifBlank { "0" },
                    draft.get("discountPercent", "0").ifBlank { "0" }, draft.get("taxAmount", "0").ifBlank { "0" },
                    draft.get("taxPercent", "0").ifBlank { "0" }, draft.get("transport", "0").ifBlank { "0" },
                    draft.get("adjustment", "0").ifBlank { "0" }, draft.get("quantityUnit", "unit"), draft.get("rateUnit", "unit")
                ) else null, invoiceNumber = draft.get("invoiceNumber").trim())
        }

        fun payment(draft: EntryDraft) = PaymentInput(id = draft.id, idempotencyKey = draft.get("key"), projectId = draft.projectId,
            amountPaise = Money.parse(draft.get("amount")), date = draft.date, vendorId = draft.get("vendor").ifBlank { null },
            reference = paymentReference(draft), note = listOf(draft.get("description").trim().takeIf { it.isNotBlank() }?.let { "Description: $it" },
                draft.get("note").trim().takeIf { it.isNotBlank() }).filterNotNull().joinToString("\n"), instalmentId = draft.get("instalment").ifBlank { null },
            allocations = draft.get("expense").takeIf { it.isNotBlank() }?.let { listOf(PaymentAllocationInput(it, Money.parse(draft.get("amount")))) }.orEmpty(),
            attachmentIds = draft.get("attachments").split(",").filter { it.isNotBlank() })

        private fun paymentReference(draft: EntryDraft): String = listOf(
            draft.get("method").trim().takeIf { it.isNotBlank() }?.let { "Method: $it" },
            draft.get("reference").trim().takeIf { it.isNotBlank() }?.let { "Reference: $it" }
        ).filterNotNull().joinToString(" · ")

        fun instalment(draft: EntryDraft) = InstalmentInput(id = draft.id, projectId = draft.projectId,
            title = draft.get("description").trim(), amountPaise = Money.parse(draft.get("amount")), dueDate = draft.date,
            commitmentId = draft.get("commitment").ifBlank { null }, vendorId = draft.get("vendor").ifBlank { null },
            remind = draft.get("remind") == "true", revision = draft.get("revision").toLongOrNull(),
            classificationIds = plannedIds(draft), reminderDays = draft.get("reminderDays", "3").toInt())

        fun commitment(draft: EntryDraft): CommitmentInput {
            val amount = Money.parse(draft.get("amount"))
            val editing = draft.get("revision").isNotBlank()
            val planned = plannedIds(draft)
            val custom = draft.get("schedules").ifBlank { if (editing && planned != null) draft.get("originalSchedules") else "" }
            if (editing && custom.isBlank()) require(amount == draft.get("originalAmount").toLong()) {
                "Edit instalment amounts when changing a commitment total."
            }
            val schedules = if (custom.isNotBlank()) {
                val json = JSONArray(custom)
                List(json.length()) { index -> json.getJSONObject(index).let {
                    val date = it.getString("date"); LocalDate.parse(date)
                    InstalmentInput(it.optString("id").ifBlank { newId() }, draft.projectId,
                        it.optString("title").ifBlank { "${draft.get("description")} ${index + 1}" },
                        Money.parse(it.getString("amount")), date, draft.id,
                        if (editing) it.optString("vendor").ifBlank { null } else draft.get("vendor").ifBlank { null },
                        if (editing) it.optBoolean("remind") else draft.get("remind") == "true",
                        if (editing) it.getLong("revision") else null,
                        classificationIds = planned)
                } }.also { require(Money.sum(it.map { row -> row.amountPaise }) == amount ||
                    editing && amount == draft.get("originalAmount").toLong() && Money.sum(it.map { row -> row.amountPaise }) < amount) {
                    "Schedule amounts must equal the commitment."
                } }
            } else if (editing) {
                emptyList()
            } else {
                val count = draft.get("count", "1").toInt()
                require(count in 1..120) { "Choose 1–120 instalments." }
                val dates = Money.scheduleDates(LocalDate.parse(draft.date), count, draft.get("frequency", "Monthly") == "Monthly")
                Money.divide(amount, count).mapIndexed { index, value ->
                    InstalmentInput(draft.id + "-schedule-" + index, draft.projectId, "${draft.get("description")} ${index + 1}",
                        value, dates[index].toString(), draft.id, draft.get("vendor").ifBlank { null }, draft.get("remind") == "true",
                        classificationIds = planned)
                }
            }
            return CommitmentInput(draft.id, draft.projectId, draft.get("description").trim(), amount, draft.date,
                draft.get("vendor").ifBlank { null }, schedules, draft.get("revision").toLongOrNull(),
                classificationIds = planned)
        }

        fun lineJson(line: ExpenseLineInput) = JSONObject().put("id", line.id).put("description", line.description)
            .put("amount", line.amountPaise).put("mode", line.mode).put("quantity", line.quantity.orEmpty())
            .put("rate", line.rate.orEmpty()).put("multiplier", line.multiplier).put("quantityUnit", line.quantityUnit)
            .put("rateUnit", line.rateUnit).put("legacyJson", line.legacyJson).put("nature", line.nature)

        fun readLines(text: String): List<ExpenseLineInput> {
            val json = JSONArray(text)
            return List(json.length()) { index -> json.getJSONObject(index).let {
                ExpenseLineInput(it.getString("id"), it.getString("description"), it.getLong("amount"),
                    it.optString("mode", "DIRECT_AMOUNT"), it.optString("quantity").ifBlank { null },
                    it.optString("rate").ifBlank { null }, it.optString("multiplier", "1"),
                    it.optString("quantityUnit", "unit"), it.optString("rateUnit", "unit"), it.optString("legacyJson"),
                    it.optString("nature"))
            } }
        }
    }
}
