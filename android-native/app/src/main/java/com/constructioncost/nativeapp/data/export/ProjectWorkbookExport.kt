package com.constructioncost.nativeapp.data.export

import android.os.Trace
import android.os.Build
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.data.backup.ArchiveIO
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object ProjectWorkbookExport {
    suspend fun create(cache: File, repository: LedgerRepository, documents: AttachmentStore, projectId: String, onProgress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        AttachmentStore.operationMutex.withLock {
            val project = repository.projects.first().firstOrNull { it.id == projectId } ?: error("Project no longer exists")
            val attachments = repository.attachments(projectId).first()
            val tag = UUID.randomUUID().toString()
            val workbook = File(cache, "project-$tag.xlsx")
            val output = File(cache, "project-$tag.zip")
            val traceCookie = tag.hashCode()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.beginAsyncSection("BuildLedgerProjectXlsxExport", traceCookie)
            try {
                onProgress("Writing project workbook")
                ProjectWorkbookWriter.write(workbook, project, attachments) { consume -> repository.forEachExportRow(projectId, consume) }
                val entries = mutableListOf<ExportFile>()
                var packageFiles = emptyList<ExportFile>()
                ZipOutputStream(FileOutputStream(output)).use { zip ->
                    writeZip(zip, "workbook.xlsx", workbook)
                    entries += ExportFile("workbook.xlsx", "BuildLedger project workbook", workbook.length(), sha256(workbook))
                    attachments.forEachIndexed { index, info ->
                        onProgress("Verifying original ${index + 1} of ${attachments.size}")
                        val original = documents.verifiedFile(info)
                        val path = "attachments/attachment-${(index + 1).toString().padStart(6, '0')}.bin"
                        writeZip(zip, path, original)
                        entries += ExportFile(path, info.name, original.length(), sha256(original), info.id)
                    }
                    val manifestBytes = JSONObject().put("format", 1).put("product", "BuildLedger").put("project", JSONObject()
                        .put("id", project.id).put("name", project.name).put("currency", "INR").put("archived", project.archived).put("budgetPaise", project.budgetPaise ?: JSONObject.NULL))
                        .put("generatedAt", Instant.now().toString()).put("files", entriesJson(entries)).toString(2).toByteArray(Charsets.UTF_8)
                    writeZip(zip, "export-manifest.json", manifestBytes)
                    val attachmentManifestBytes = JSONObject().put("attachments", entriesJson(entries.filter { it.attachmentId != null })).toString(2).toByteArray(Charsets.UTF_8)
                    val readmeBytes = "BuildLedger project export\n\nThe workbook contains only project-scoped ledger records. Exact source paise are stored as text and are authoritative; INR values are rounded to paise. Original attachments are preserved byte for byte under generated safe paths.\n\nNo project data was changed by this export.\n".toByteArray(Charsets.UTF_8)
                    packageFiles = entries + listOf(
                        ExportFile("export-manifest.json", "", manifestBytes.size.toLong(), sha256(manifestBytes)),
                        ExportFile("attachment-manifest.json", "", attachmentManifestBytes.size.toLong(), sha256(attachmentManifestBytes)),
                        ExportFile("README.txt", "", readmeBytes.size.toLong(), sha256(readmeBytes))
                    )
                    writeZip(zip, "attachment-manifest.json", attachmentManifestBytes)
                    writeZip(zip, "README.txt", readmeBytes)
                    val checksums = packageFiles.joinToString("\n", postfix = "\n") { "${it.sha256}  ${it.path}" }
                    writeZip(zip, "SHA256SUMS.txt", checksums.toByteArray(Charsets.UTF_8))
                }
                require(output.length() <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Project export exceeds the 850 MiB package limit" }
                onProgress("Verifying completed project package")
                verifyPackage(output, entries, packageFiles)
                output
            } catch (failure: Throwable) {
                output.delete(); throw failure
            } finally {
                workbook.delete()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.endAsyncSection("BuildLedgerProjectXlsxExport", traceCookie)
            }
        }
    }

    private data class ExportFile(val path: String, val name: String, val size: Long, val sha256: String, val attachmentId: String? = null)
    private fun entriesJson(entries: List<ExportFile>) = JSONArray().also { array -> entries.forEach { file ->
        array.put(JSONObject().put("path", file.path).put("name", file.name).put("size", file.size).put("sha256", file.sha256).apply { file.attachmentId?.let { put("id", it) } })
    } }
    private fun writeZip(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path)); FileInputStream(file).use { input -> input.copyTo(zip, 64 * 1024) }; zip.closeEntry()
    }
    private fun writeZip(zip: ZipOutputStream, path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun verifyPackage(file: File, exportedFiles: List<ExportFile>, files: List<ExportFile>) {
        ZipFile(file).use { zip ->
            val expected = files.associateBy { it.path }
            require(zip.getEntry("workbook.xlsx") != null && zip.getEntry("export-manifest.json") != null && zip.getEntry("attachment-manifest.json") != null && zip.getEntry("SHA256SUMS.txt") != null) { "Project package is incomplete" }
            val paths = zip.entries().asSequence().map { it.name }.toSet()
            require(paths == expected.keys + "SHA256SUMS.txt") { "Project package contains unexpected files" }
            val sumLines = zip.getInputStream(zip.getEntry("SHA256SUMS.txt")).bufferedReader().use { it.readLines() }
            val sums = sumLines.associate { line ->
                val match = Regex("^([0-9a-f]{64})  ([A-Za-z0-9_./-]+)$").matchEntire(line) ?: error("Project checksum manifest is malformed")
                match.groupValues[2] to match.groupValues[1]
            }
            require(sumLines.size == expected.size && sums == expected.mapValues { it.value.sha256 }) { "Project checksum manifest does not match package files" }
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("export-manifest.json")).bufferedReader().use { it.readText() })
            require(manifest.getJSONArray("files").toString() == entriesJson(exportedFiles).toString()) { "Project export manifest does not match package files" }
            val attachmentManifest = JSONObject(zip.getInputStream(zip.getEntry("attachment-manifest.json")).bufferedReader().use { it.readText() })
            require(attachmentManifest.getJSONArray("attachments").toString() == entriesJson(exportedFiles.filter { it.attachmentId != null }).toString()) { "Attachment manifest does not match package files" }
            expected.forEach { (path, meta) ->
                val entry = zip.getEntry(path) ?: error("Project package is missing $path")
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(64 * 1024); var size = 0L
                    while (true) { val read = input.read(buffer); if (read < 0) break; size = Math.addExact(size, read.toLong()); digest.update(buffer, 0, read) }
                    require(size == meta.size) { "Project package size mismatch: $path" }
                }
                require(digest.digest().joinToString("") { "%02x".format(it) } == meta.sha256) { "Project package checksum mismatch: $path" }
            }
            val workbookEntry = zip.getEntry("workbook.xlsx")
            val workbookFile = File(file.parentFile, file.nameWithoutExtension + "-verify.xlsx")
            try {
                zip.getInputStream(workbookEntry).use { input -> FileOutputStream(workbookFile).use { output -> input.copyTo(output) } }
                ZipFile(workbookFile).use { workbook ->
                    listOf(
                        "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml",
                        "xl/tables/table1.xml", "xl/drawings/drawing1.xml", "xl/charts/chart1.xml", "xl/charts/chart2.xml",
                        "xl/charts/chart3.xml", "xl/styles.xml"
                    ).forEach { require(workbook.getEntry(it) != null) { "Workbook is missing $it" } }
                    val summary = workbook.getInputStream(workbook.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().use { it.readText() }
                    val analytics = workbook.getInputStream(workbook.getEntry("xl/worksheets/sheet3.xml")).bufferedReader().use { it.readText() }
                    val chart = workbook.getInputStream(workbook.getEntry("xl/charts/chart1.xml")).bufferedReader().use { it.readText() }
                    val contentTypes = workbook.getInputStream(workbook.getEntry("[Content_Types].xml")).bufferedReader().use { it.readText() }
                    require(
                        summary.contains("Financial position") && summary.contains("Monthly cash and bills") &&
                            analytics.contains("SUMIFS(ProjectRecords") && chart.contains("Analytics!") &&
                            contentTypes.contains("/xl/charts/chart3.xml") && contentTypes.contains("/xl/styles.xml")
                    ) { "Workbook dashboard formulas, styles, or charts are missing" }
                }
            } finally { workbookFile.delete() }
        }
    }
}

internal object ProjectWorkbookWriter {
    private val headers = listOf("Record type", "Source ID", "Date", "Description", "Supplier", "Exact amount paise", "Numeric INR", "Exact settled paise", "Settled INR", "Status", "Attachment link", "Audit and source evidence", "Category dimension", "Category", "Supplier ID", "Category ID")
    private val columns = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P")
    private fun xml(value: String): String {
        val safe = buildString(value.length) {
            var index = 0
            while (index < value.length) {
                val codePoint = value.codePointAt(index)
                index += Character.charCount(codePoint)
                appendCodePoint(if (codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD || codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD || codePoint in 0x10000..0x10FFFF) codePoint else 0xFFFD)
            }
        }
        return safe
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }
    private fun cell(ref: String, value: String): String {
        val suffix = " [Full content: xl/overflow-records.jsonl]"
        val display = if (value.length <= 32_767) value else {
            var end = 32_767 - suffix.length
            if (Character.isHighSurrogate(value[end - 1])) end--
            value.take(end) + suffix
        }
        return "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(display)}</t></is></c>"
    }
    private fun number(ref: String, value: String, style: Int = 0) = "<c r=\"$ref\"${if (style == 0) "" else " s=\"$style\""}><v>$value</v></c>"
    private fun amount(paise: Long?) = paise?.let { BigDecimal.valueOf(it, 2).setScale(2).toPlainString() }.orEmpty()
    private fun text(row: JSONObject, key: String) = if (row.isNull(key)) "" else row.optString(key, "")
    private fun excelDate(value: String): Long? = runCatching { LocalDate.parse(value).toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay() }.getOrNull()
    private fun label(ref: String, value: String, style: Int = 0) =
        "<c r=\"$ref\"${if (style == 0) "" else " s=\"$style\""} t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"
    private fun formula(ref: String, value: String, cachedPaise: Long, style: Int = 5) =
        "<c r=\"$ref\" s=\"$style\"><f>${xml(value)}</f><v>${amount(cachedPaise)}</v></c>"
    private fun formulaCriterion(value: String) = value.replace("~", "~~").replace("*", "~*").replace("?", "~?").replace("\"", "\"\"")
    private fun scheduleFormula(vararg criteria: String) = listOf("Schedule", "Due today schedule", "Overdue schedule").joinToString("+") { type ->
        "SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],\"$type\",${criteria.joinToString(",")})"
    }

    private data class MonthlyTotals(var billedPaise: Long = 0, var cashPaise: Long = 0)
    private data class CategoryTotals(val label: String, var paise: Long = 0)
    private data class SupplierTotals(val label: String, var billedPaise: Long = 0, var settledPaise: Long = 0)
    private data class Ranking(val label: String, val paise: Long, val criterion: String, val placeholder: Boolean = false)
    private data class ChartSeries(val label: String, val values: List<Long>, val color: String)

    private class DashboardAnalytics(private val today: LocalDate) {
        var billedPaise = 0L
        var cashPaise = 0L
        var settledPaise = 0L
        var commitmentPaise = 0L
        var futureSchedulePaise = 0L
        var overdueSchedulePaise = 0L
        var dueTodaySchedulePaise = 0L
        private val months = sortedMapOf<YearMonth, MonthlyTotals>()
        private val scheduledMonths = sortedMapOf<YearMonth, Long>()
        private val categories = mutableMapOf<String, CategoryTotals>()
        private val suppliers = mutableMapOf<String, SupplierTotals>()

        private fun add(left: Long, right: Long) = Math.addExact(left, right)
        private fun month(value: String) = runCatching { YearMonth.from(LocalDate.parse(value)) }.getOrNull()
        private fun monthly(value: YearMonth) = months.getOrPut(value) { MonthlyTotals() }
        fun record(table: String, row: JSONObject, rawAmount: Long?, settled: Long?) {
            when (table) {
                "expenses" -> if (!row.optBoolean("voided")) {
                    val amount = rawAmount ?: return
                    billedPaise = add(billedPaise, amount)
                    val paid = settled ?: 0L
                    settledPaise = add(settledPaise, paid)
                    val supplierId = text(row, "vendorId")
                    val supplier = text(row, "_vendorName")
                    suppliers.getOrPut(supplierId) { SupplierTotals(supplier) }.also {
                        it.billedPaise = add(it.billedPaise, amount)
                        it.settledPaise = add(it.settledPaise, paid)
                    }
                    month(text(row, "date"))?.let { monthly(it).billedPaise = add(monthly(it).billedPaise, amount) }
                }
                "payments" -> rawAmount?.let { amount ->
                    cashPaise = add(cashPaise, amount)
                    month(text(row, "date"))?.let { monthly(it).cashPaise = add(monthly(it).cashPaise, amount) }
                }
                "commitments" -> if (!row.optBoolean("archived")) rawAmount?.let { commitmentPaise = add(commitmentPaise, it) }
                "instalments" -> if (!row.optBoolean("cancelled")) rawAmount?.let { amount ->
                    val due = runCatching { LocalDate.parse(text(row, "dueDate")) }.getOrNull() ?: return@let
                    when {
                        due.isBefore(today) -> overdueSchedulePaise = add(overdueSchedulePaise, amount)
                        due == today -> dueTodaySchedulePaise = add(dueTodaySchedulePaise, amount)
                        else -> futureSchedulePaise = add(futureSchedulePaise, amount)
                    }
                    if (!due.isBefore(today)) {
                        val key = YearMonth.from(due)
                        scheduledMonths[key] = add(scheduledMonths[key] ?: 0L, amount)
                    }
                }
                "classification_allocations" -> if (!row.optBoolean("_voided") && text(row, "dimension") == "COST") {
                    rawAmount?.let { amount ->
                        val categoryId = text(row, "classificationId")
                        val category = text(row, "_classificationName")
                        if (categoryId.isNotBlank() && category.isNotBlank()) categories.getOrPut(categoryId) { CategoryTotals(category) }.also { it.paise = add(it.paise, amount) }
                    }
                }
            }
        }

        fun trendMonths(): List<Pair<YearMonth, MonthlyTotals>> {
            val end = YearMonth.from(today)
            return (11 downTo 0).map { offset ->
                val month = end.minusMonths(offset.toLong())
                month to (months[month] ?: MonthlyTotals())
            }
        }
        fun scheduledMonths(): List<Pair<YearMonth, Long>> {
            val start = YearMonth.from(today)
            return (0 until 12).map { month ->
                val key = start.plusMonths(month.toLong())
                key to (scheduledMonths[key] ?: 0L)
            }
        }
        fun categories() = categories.entries.sortedWith(compareByDescending<Map.Entry<String, CategoryTotals>> { it.value.paise }.thenBy { it.value.label }).take(10)
            .map { (id, total) -> Ranking(total.label, total.paise, id) }.ifEmpty { listOf(Ranking("No classified costs", 0L, "", true)) }
        fun suppliers() = suppliers.entries.map { (id, total) ->
            Ranking(total.label.ifBlank { "Unassigned supplier" }, Math.subtractExact(total.billedPaise, total.settledPaise), id)
        }
            .filter { it.paise != 0L }.sortedWith(compareByDescending<Ranking> { it.paise }.thenBy { it.label }).take(10)
            .ifEmpty { listOf(Ranking("No unpaid supplier bills", 0L, "", true)) }
    }

    suspend fun write(target: File, project: ProjectInfo, attachments: List<AttachmentInfo>, writeRows: suspend ((String, JSONObject) -> Unit) -> Unit) = withContext(Dispatchers.IO) {
        val attachmentPaths = attachments.mapIndexed { index, info -> info.id to "attachments/attachment-${(index + 1).toString().padStart(6, '0')}.bin" }.toMap()
        val links = mutableListOf<Pair<String, String>>()
        var rowCount = 0
        val today = LocalDate.now()
        val todayText = today.toString()
        val analytics = DashboardAnalytics(today)
        val overflow = File(target.parentFile, "${target.name}-overflow-${UUID.randomUUID()}.jsonl")
        val overflowWriter = overflow.bufferedWriter()
        try {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml"))
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"><pane xSplit=\"3\" ySplit=\"1\" topLeftCell=\"D2\" activePane=\"bottomRight\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/><cols><col min=\"1\" max=\"1\" width=\"25\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"26\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"14\" customWidth=\"1\"/><col min=\"4\" max=\"4\" width=\"32\" customWidth=\"1\"/><col min=\"5\" max=\"5\" width=\"24\" customWidth=\"1\"/><col min=\"6\" max=\"9\" width=\"18\" customWidth=\"1\"/><col min=\"10\" max=\"10\" width=\"22\" customWidth=\"1\"/><col min=\"11\" max=\"11\" width=\"30\" customWidth=\"1\"/><col min=\"12\" max=\"12\" width=\"60\" customWidth=\"1\"/><col min=\"13\" max=\"14\" width=\"22\" customWidth=\"1\"/></cols><sheetData><row r=\"1\">" + headers.mapIndexed { i, value -> cell("${columns[i]}1", value) }.joinToString("") + "</row>").toByteArray(Charsets.UTF_8))
            writeRows { table, row ->
                rowCount++
                require(rowCount <= 1_048_575) { "Excel supports at most 1,048,575 project rows per worksheet" }
                val excelRow = rowCount + 1
                val type = when (table) {
                    "expenses" -> if (row.optBoolean("voided")) "Voided expense" else "Expense"
                    "expense_lines" -> "Expense line"
                    "payments" -> "Payment"
                    "payment_allocations" -> "Settlement"
                    "classification_allocations" -> if (row.optBoolean("_voided")) "Voided classification allocation" else if (text(row, "dimension") == "COST") "Cost allocation" else "Classification allocation"
                    "instalments" -> when {
                        row.optBoolean("cancelled") -> "Cancelled schedule"
                        text(row, "dueDate") < todayText -> "Overdue schedule"
                        text(row, "dueDate") == todayText -> "Due today schedule"
                        else -> "Schedule"
                    }
                    "commitments" -> if (row.optBoolean("archived")) "Archived commitment" else "Commitment"
                    "attachments" -> "Attachment"
                    "audit_records" -> "Audit"
                    "projects" -> "Project"
                    "budgets" -> "Budget"
                    "activity_entries" -> "Activity note"
                    else -> table.replace('_', ' ').replaceFirstChar { it.uppercase() }
                }
                val rawAmount = when (table) {
                    "expenses", "expense_lines", "commitments", "budgets", "payment_allocations", "classification_allocations" -> if (row.isNull("amountPaise")) null else row.optLong("amountPaise")
                    "payments" -> if (row.isNull("amountPaise")) null else row.optLong("amountPaise") * if (text(row, "kind") == "PAYMENT") 1 else -1
                    "instalments" -> if (row.optBoolean("cancelled")) 0L else row.optLong("amountPaise") - row.optLong("_paidPaise")
                    else -> null
                }
                val settled = if (table == "expenses") row.optLong("_settledPaise") else null
                val categoryDimension = if (table == "classification_allocations") text(row, "dimension") else ""
                val category = if (table == "classification_allocations") text(row, "_classificationName") else ""
                analytics.record(table, row, rawAmount, settled)
                val filePath = if (table == "attachments") attachmentPaths[row.optString("id")] ?: error("Project attachment inventory changed") else ""
                val values = listOf(type, text(row, "id"), text(row, "date").ifBlank { text(row, "dueDate") }.ifBlank { text(row, "_expenseDate") },
                    text(row, "description").ifBlank { text(row, "_expenseDescription") }.ifBlank { text(row, "title") }.ifBlank { text(row, "name") }, text(row, "_vendorName"), rawAmount?.toString().orEmpty(),
                    amount(rawAmount), settled?.toString().orEmpty(), amount(settled), when {
                        table == "expenses" && row.optBoolean("voided") -> "Voided"
                        table == "instalments" && row.optBoolean("cancelled") -> "Cancelled"
                        table == "payments" -> text(row, "kind")
                        table == "classification_allocations" -> categoryDimension
                        else -> text(row, "status")
                    }, filePath, row.toString(), categoryDimension, category, text(row, "vendorId"), if (table == "classification_allocations") text(row, "classificationId") else "")
                if (values.any { it.length > 32_767 }) {
                    overflowWriter.append(JSONObject().put("table", table).put("record", row).toString()).append('\n')
                }
                zip.write(("<row r=\"$excelRow\">" + values.mapIndexed { index, value ->
                    if (index == 2) excelDate(value)?.let { number("${columns[index]}$excelRow", it.toString(), 7) } ?: cell("${columns[index]}$excelRow", value)
                    else if (index == 6 || index == 8) value.takeIf { it.isNotEmpty() }?.let { number("${columns[index]}$excelRow", it, 5) }.orEmpty()
                    else cell("${columns[index]}$excelRow", value)
                }.joinToString("") + "</row>").toByteArray(Charsets.UTF_8))
                if (filePath.isNotEmpty()) links += "K$excelRow" to filePath
            }
            val last = (rowCount + 1).coerceAtLeast(2)
            zip.write("</sheetData>".toByteArray(Charsets.UTF_8))
            if (links.isNotEmpty()) {
                zip.write("<hyperlinks>".toByteArray(Charsets.UTF_8))
                links.forEachIndexed { index, (ref, _) -> zip.write("<hyperlink ref=\"$ref\" r:id=\"rId${index + 2}\" display=\"Open original\"/>".toByteArray(Charsets.UTF_8)) }
                zip.write("</hyperlinks>".toByteArray(Charsets.UTF_8))
            }
            zip.write("<tableParts count=\"1\"><tablePart r:id=\"rId1\"/></tableParts></worksheet>".toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/_rels/sheet2.xml.rels"))
            val rels = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/table\" Target=\"../tables/table1.xml\"/>")
                links.forEachIndexed { index, (_, path) -> append("<Relationship Id=\"rId${index + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"../../${xml(path)}\" TargetMode=\"External\"/>") }
                append("</Relationships>")
            }
            zip.write(rels.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/tables/table1.xml"))
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><table xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" id=\"1\" name=\"ProjectRecords\" displayName=\"ProjectRecords\" ref=\"A1:P$last\" totalsRowShown=\"0\"><autoFilter ref=\"A1:P$last\"/><tableColumns count=\"${headers.size}\">" + headers.mapIndexed { i, name -> "<tableColumn id=\"${i + 1}\" name=\"${xml(name)}\"/>" }.joinToString("") + "</tableColumns><tableStyleInfo name=\"TableStyleMedium2\" showFirstColumn=\"0\" showLastColumn=\"0\" showRowStripes=\"1\" showColumnStripes=\"0\"/></table>").toByteArray(Charsets.UTF_8)); zip.closeEntry()
            val trend = analytics.trendMonths()
            val categories = analytics.categories()
            val suppliers = analytics.suppliers()
            val schedules = analytics.scheduledMonths()
            writeText(zip, "xl/worksheets/sheet1.xml", dashboardSheet(project, analytics, schedules))
            writeText(zip, "xl/worksheets/sheet3.xml", analyticsSheet(trend, categories, suppliers, schedules))
            writeText(zip, "xl/worksheets/_rels/sheet1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>")
            writeText(zip, "xl/drawings/drawing1.xml", dashboardDrawing())
            writeText(zip, "xl/drawings/_rels/drawing1.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chart1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chart2.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart\" Target=\"../charts/chart3.xml\"/></Relationships>")
            writeText(zip, "xl/styles.xml", dashboardStyles())
            writeText(zip, "xl/charts/chart1.xml", lineChart("Recorded bills and net cash (INR)", trend.map { it.first }, listOf(ChartSeries("Recorded bills", trend.map { it.second.billedPaise }, "147D78"), ChartSeries("Net cash posted", trend.map { it.second.cashPaise }, "0B70B9"))))
            writeText(zip, "xl/charts/chart2.xml", barChart("Classified bill allocation (INR)", "Classified allocation", categories, "147D78"))
            writeText(zip, "xl/charts/chart3.xml", barChart("Supplier unpaid bills (INR)", "Unpaid bill exposure", suppliers, "C66A20"))
            writeText(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Dashboard\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Analytics\" sheetId=\"2\" r:id=\"rId2\"/><sheet name=\"Records\" sheetId=\"3\" r:id=\"rId3\"/></sheets><calcPr calcId=\"191029\" calcMode=\"auto\" fullCalcOnLoad=\"1\" forceFullCalc=\"1\"/></workbook>")
            writeText(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet3.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/><Relationship Id=\"rId4\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
            writeText(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            overflowWriter.flush()
            if (overflow.length() > 0) {
                zip.putNextEntry(ZipEntry("xl/overflow-records.jsonl"))
                overflow.inputStream().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
            writeText(zip, "[Content_Types].xml", dashboardContentTypes())
        }
        } finally {
            try { overflowWriter.close() } finally { overflow.delete() }
        }
    }

    private fun writeText(zip: ZipOutputStream, path: String, value: String) { zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
    private fun dashboardSheet(project: ProjectInfo, analytics: DashboardAnalytics, schedules: List<Pair<YearMonth, Long>>): String {
        val rows = sortedMapOf<Int, MutableList<String>>()
        fun add(row: Int, value: String) { rows.getOrPut(row) { mutableListOf() } += value }
        fun card(column: String, labelText: String, formulaText: String, cachedPaise: Long, labelRow: Int, valueRow: Int) {
            add(labelRow, label("${column}${labelRow}", labelText, 8))
            add(valueRow, formula("${column}${valueRow}", formulaText, cachedPaise, 9))
        }
        val bills = "SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],\"Expense\")"
        val cash = "SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],\"Payment\")"
        val settled = "SUMIFS(ProjectRecords[Settled INR],ProjectRecords[Record type],\"Expense\")"
        val commitments = "SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],\"Commitment\")"
        val futureSchedules = scheduleFormula("ProjectRecords[Date],\">\"&TODAY()")
        val overdueSchedules = scheduleFormula("ProjectRecords[Date],\"<\"&TODAY()")
        val dueTodaySchedules = scheduleFormula("ProjectRecords[Date],\"=\"&TODAY()")
        add(1, label("A1", "${project.name} dashboard", 1))
        add(2, label("A2", "Project financial overview (INR) · Generated ${LocalDate.now()}", 10))
        add(4, label("A4", "Financial position", 6))
        card("A", "Recorded bill cost", bills, analytics.billedPaise, 5, 6)
        card("C", "Net cash posted", cash, analytics.cashPaise, 5, 6)
        card("E", "Unpaid bill cost", "${bills}-${settled}", Math.subtractExact(analytics.billedPaise, analytics.settledPaise), 5, 6)
        project.budgetPaise?.let { budget ->
            card("G", "Budget remaining", "SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],\"Budget\")-${bills}",
                Math.subtractExact(budget, analytics.billedPaise), 5, 6)
        } ?: run {
            add(5, label("G5", "Budget remaining", 8))
            add(6, label("G6", "n.a. — no budget set", 10))
        }
        card("A", "Active commitments", commitments, analytics.commitmentPaise, 9, 10)
        card("C", "Future schedules", futureSchedules, analytics.futureSchedulePaise, 9, 10)
        card("E", "Due today", dueTodaySchedules, analytics.dueTodaySchedulePaise, 9, 10)
        card("G", "Overdue schedules", overdueSchedules, analytics.overdueSchedulePaise, 9, 10)
        add(13, label("A13", "Monthly cash and bills", 6))
        add(13, label("I13", "Classified bill allocation", 6))
        add(33, label("A33", "Supplier unpaid bills", 6))
        add(33, label("I33", "Upcoming scheduled unpaid cash", 6))
        add(34, label("I34", "Due month", 2))
        add(34, label("J34", "Unpaid schedule", 2))
        schedules.forEachIndexed { index, (month, paise) ->
            val row = 35 + index
            add(row, label("I${row}", month.atDay(1).format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy")), 3))
            add(row, formula("J${row}", "Analytics!L${index + 3}", paise))
        }
        val xmlRows = rows.entries.joinToString("") { (row, cells) -> "<row r=\"${row}\">${cells.joinToString("")}</row>" }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"/></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/><cols><col min=\"1\" max=\"16\" width=\"15\" customWidth=\"1\"/></cols><sheetData>${xmlRows}</sheetData><drawing r:id=\"rId1\"/></worksheet>"
    }

    private fun analyticsSheet(
        trend: List<Pair<YearMonth, MonthlyTotals>>,
        categories: List<Ranking>,
        suppliers: List<Ranking>,
        schedules: List<Pair<YearMonth, Long>>
    ): String {
        val rows = sortedMapOf<Int, MutableList<String>>()
        fun add(row: Int, value: String) { rows.getOrPut(row) { mutableListOf() } += value }
        add(1, label("A1", "Analytics calculations", 1))
        listOf(
            "A2" to "Month", "B2" to "Recorded bills", "C2" to "Net cash posted",
            "E2" to "Cost category", "F2" to "Classified allocation",
            "H2" to "Supplier", "I2" to "Unpaid bill exposure",
            "K2" to "Due month", "L2" to "Scheduled unpaid cash"
        ).forEach { (ref, value) -> add(2, label(ref, value, 2)) }
        trend.forEachIndexed { index, (month, values) ->
            val row = index + 3
            add(row, number("A${row}", excelDate(month.atDay(1).toString()).toString(), 7))
            val criteria = "ProjectRecords[Date],\">=\"&A${row},ProjectRecords[Date],\"<\"&EDATE(A${row},1)"
            add(row, formula("B${row}", "SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],\"Expense\",${criteria})", values.billedPaise))
            add(row, formula("C${row}", "SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],\"Payment\",${criteria})", values.cashPaise))
        }
        categories.forEachIndexed { index, ranking ->
            val row = index + 3
            val criterion = formulaCriterion(ranking.criterion)
            add(row, label("E${row}", ranking.label, 3))
            val expression = if (ranking.placeholder) "0" else
                "SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],\"Cost allocation\",ProjectRecords[Category dimension],\"COST\",ProjectRecords[Category ID],\"${criterion}\")"
            add(row, formula("F${row}", expression, ranking.paise))
        }
        suppliers.forEachIndexed { index, ranking ->
            val row = index + 3
            val criterion = formulaCriterion(ranking.criterion)
            add(row, label("H${row}", ranking.label, 3))
            val expression = if (ranking.placeholder) "0" else
                "SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],\"Expense\",ProjectRecords[Supplier ID],\"${criterion}\")-SUMIFS(ProjectRecords[Settled INR],ProjectRecords[Record type],\"Expense\",ProjectRecords[Supplier ID],\"${criterion}\")"
            add(row, formula("I${row}", expression, ranking.paise))
        }
        schedules.forEachIndexed { index, (month, paise) ->
            val row = index + 3
            add(row, number("K${row}", excelDate(month.atDay(1).toString()).toString(), 7))
            val criteria = "ProjectRecords[Date],\">=\"&MAX(K${row},TODAY()),ProjectRecords[Date],\"<\"&EDATE(K${row},1)"
            add(row, formula("L${row}", scheduleFormula(criteria), paise))
        }
        val xmlRows = rows.entries.joinToString("") { (row, cells) -> "<row r=\"${row}\">${cells.joinToString("")}</row>" }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"><pane ySplit=\"2\" topLeftCell=\"A3\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"18\"/><cols><col min=\"1\" max=\"1\" width=\"14\" customWidth=\"1\"/><col min=\"2\" max=\"3\" width=\"20\" customWidth=\"1\"/><col min=\"5\" max=\"5\" width=\"30\" customWidth=\"1\"/><col min=\"6\" max=\"6\" width=\"20\" customWidth=\"1\"/><col min=\"8\" max=\"8\" width=\"30\" customWidth=\"1\"/><col min=\"9\" max=\"9\" width=\"20\" customWidth=\"1\"/><col min=\"11\" max=\"11\" width=\"14\" customWidth=\"1\"/><col min=\"12\" max=\"12\" width=\"20\" customWidth=\"1\"/></cols><sheetData>${xmlRows}</sheetData></worksheet>"
    }

    private fun dashboardDrawing() = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">${chartAnchor(2, "Monthly bills and net cash", 0, 13, 7, 31, "rId1")}${chartAnchor(3, "Classified bill allocation", 8, 13, 15, 31, "rId2")}${chartAnchor(4, "Supplier unpaid bills", 0, 34, 7, 52, "rId3")}</xdr:wsDr>"

    private fun chartAnchor(id: Int, name: String, fromColumn: Int, fromRow: Int, toColumn: Int, toRow: Int, relationship: String) =
        "<xdr:twoCellAnchor><xdr:from><xdr:col>${fromColumn}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${fromRow}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>${toColumn}</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${toRow}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"${id}\" name=\"${xml(name)}\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:id=\"${relationship}\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>"

    private fun chartTitle(title: String) = "<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>${xml(title)}</a:t></a:r></a:p></c:rich></c:tx><c:layout/></c:title>"
    private fun stringReference(formula: String, values: List<String>) =
        "<c:strRef><c:f>${xml(formula)}</c:f><c:strCache><c:ptCount val=\"${values.size}\"/>${values.mapIndexed { index, value -> "<c:pt idx=\"$index\"><c:v>${xml(value)}</c:v></c:pt>" }.joinToString("")}</c:strCache></c:strRef>"
    private fun numberReference(formula: String, values: List<Long>) =
        "<c:numRef><c:f>${xml(formula)}</c:f><c:numCache><c:formatCode>[$₹-en-IN] #,##0.00</c:formatCode><c:ptCount val=\"${values.size}\"/>${values.mapIndexed { index, value -> "<c:pt idx=\"$index\"><c:v>${amount(value)}</c:v></c:pt>" }.joinToString("")}</c:numCache></c:numRef>"
    private fun dateReference(formula: String, values: List<YearMonth>) =
        "<c:numRef><c:f>${xml(formula)}</c:f><c:numCache><c:formatCode>mmm yyyy</c:formatCode><c:ptCount val=\"${values.size}\"/>${values.mapIndexed { index, value -> "<c:pt idx=\"$index\"><c:v>${excelDate(value.atDay(1).toString())}</c:v></c:pt>" }.joinToString("")}</c:numCache></c:numRef>"

    private fun lineChart(title: String, months: List<YearMonth>, series: List<ChartSeries>): String {
        val last = months.size + 2
        val categories = dateReference("Analytics!A3:A${last}", months)
        val lines = series.mapIndexed { index, value ->
            "<c:ser><c:idx val=\"${index}\"/><c:order val=\"${index}\"/><c:tx><c:v>${xml(value.label)}</c:v></c:tx><c:spPr><a:ln w=\"28575\"><a:solidFill><a:srgbClr val=\"${value.color}\"/></a:solidFill></a:ln></c:spPr><c:marker><c:symbol val=\"none\"/></c:marker><c:cat>${categories}</c:cat><c:val>${numberReference("Analytics!${('B'.code + index).toChar()}3:${('B'.code + index).toChar()}${last}", value.values)}</c:val></c:ser>"
        }.joinToString("")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><c:lang val=\"en-IN\"/><c:chart>${chartTitle(title)}<c:plotArea><c:layout/><c:lineChart><c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>${lines}<c:axId val=\"10\"/><c:axId val=\"20\"/></c:lineChart><c:catAx><c:axId val=\"10\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:axPos val=\"b\"/><c:numFmt formatCode=\"mmm yyyy\" sourceLinked=\"0\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"20\"/><c:crosses val=\"autoZero\"/></c:catAx><c:valAx><c:axId val=\"20\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:axPos val=\"l\"/><c:majorGridlines/><c:numFmt formatCode=\"[$₹-en-IN] #,##0\" sourceLinked=\"0\"/><c:crossAx val=\"10\"/><c:crosses val=\"autoZero\"/></c:valAx></c:plotArea><c:legend><c:legendPos val=\"t\"/><c:layout/></c:legend><c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart></c:chartSpace>"
    }

    private fun barChart(title: String, seriesName: String, rankings: List<Ranking>, color: String): String {
        val last = rankings.size + 2
        val categoryColumn = if (title.startsWith("Classified")) "E" else "H"
        val valueColumn = if (title.startsWith("Classified")) "F" else "I"
        val categories = stringReference("Analytics!${categoryColumn}3:${categoryColumn}${last}", rankings.map { it.label })
        val values = numberReference("Analytics!${valueColumn}3:${valueColumn}${last}", rankings.map { it.paise })
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><c:lang val=\"en-IN\"/><c:chart>${chartTitle(title)}<c:plotArea><c:layout/><c:barChart><c:barDir val=\"bar\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/><c:ser><c:idx val=\"0\"/><c:order val=\"0\"/><c:tx><c:v>${xml(seriesName)}</c:v></c:tx><c:spPr><a:solidFill><a:srgbClr val=\"${color}\"/></a:solidFill></c:spPr><c:cat>${categories}</c:cat><c:val>${values}</c:val></c:ser><c:axId val=\"30\"/><c:axId val=\"40\"/></c:barChart><c:catAx><c:axId val=\"30\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:axPos val=\"l\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"40\"/><c:crosses val=\"autoZero\"/></c:catAx><c:valAx><c:axId val=\"40\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:axPos val=\"b\"/><c:majorGridlines/><c:numFmt formatCode=\"[$₹-en-IN] #,##0\" sourceLinked=\"0\"/><c:crossAx val=\"30\"/><c:crosses val=\"autoZero\"/></c:valAx></c:plotArea><c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart></c:chartSpace>"
    }

    private fun dashboardStyles() = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"[$₹-en-IN] #,##0.00;[Red]([$₹-en-IN] #,##0.00);-\"/><numFmt numFmtId=\"165\" formatCode=\"dd-mmm-yyyy\"/></numFmts><fonts count=\"4\"><font><sz val=\"10\"/><color rgb=\"FF243746\"/><name val=\"Aptos\"/></font><font><b/><sz val=\"16\"/><color rgb=\"FF17324D\"/><name val=\"Aptos Display\"/></font><font><b/><sz val=\"10\"/><color rgb=\"FFFFFFFF\"/><name val=\"Aptos\"/></font><font><b/><sz val=\"10\"/><color rgb=\"FF17324D\"/><name val=\"Aptos\"/></font></fonts><fills count=\"5\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF17324D\"/><bgColor indexed=\"64\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDDEFEF\"/><bgColor indexed=\"64\"/></patternFill></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEAF2F8\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border><border><bottom style=\"thin\"><color rgb=\"FFD8E1E8\"/></bottom></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"11\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyFont=\"1\"/><xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFill=\"1\" applyFont=\"1\"/><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\"/><xf numFmtId=\"164\" fontId=\"1\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyFont=\"1\"/><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFill=\"1\" applyFont=\"1\"/><xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\"/><xf numFmtId=\"0\" fontId=\"3\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyFont=\"1\"/><xf numFmtId=\"164\" fontId=\"1\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyFont=\"1\"/><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"

    private fun dashboardContentTypes() = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jsonl\" ContentType=\"application/x-ndjson\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/worksheets/sheet3.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/tables/table1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml\"/><Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/><Override PartName=\"/xl/charts/chart1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/><Override PartName=\"/xl/charts/chart2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/><Override PartName=\"/xl/charts/chart3.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>"

}
