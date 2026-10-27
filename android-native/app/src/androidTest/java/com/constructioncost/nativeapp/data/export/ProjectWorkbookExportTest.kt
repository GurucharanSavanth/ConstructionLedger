package com.constructioncost.nativeapp.data.export

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.io.StringReader
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ProjectWorkbookExportTest {
    private fun assertWellFormedXml(value: String) {
        val parser = Xml.newPullParser().apply { setInput(StringReader(value)) }
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) { }
    }

    @Test fun workbookKeepsExactPaiseTextFormulaSafeRowsAndProjectReconciliation() = runBlocking<Unit> {
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "project-workbook-${UUID.randomUUID()}.xlsx")
        val attachment = AttachmentInfo("document", "project", "same name.pdf", "application/pdf", 4, "a".repeat(64), "doc.bin", 1)
        try {
            ProjectWorkbookWriter.write(target, ProjectInfo("project", "North <wing> =1+1", 12345), listOf(attachment)) { emit ->
                emit("expenses", JSONObject().put("id", "bill").put("description", "=HYPERLINK(\"bad\")\uFFFE\uFFFF 😀").put("vendorId", "supplier").put("vendorName", "Supplier")
                    .put("_vendorName", "Supplier").put("amountPaise", 12505).put("_settledPaise", 2500).put("date", "2026-10-23").put("voided", false))
                emit("classification_allocations", JSONObject().put("id", "cost-allocation").put("expenseId", "bill").put("classificationId", "materials").put("amountPaise", 12505)
                    .put("dimension", "COST").put("_classificationName", "Materials*").put("_expenseDate", "2026-10-23")
                    .put("_expenseDescription", "Invoice category detail").put("_vendorName", "Supplier").put("_voided", false))
                emit("expenses", JSONObject().put("id", "literal-label-bill").put("description", "Reserved label invoice").put("vendorId", "literal-supplier")
                    .put("_vendorName", "Unassigned supplier").put("amountPaise", 1000).put("_settledPaise", 0).put("date", "2026-10-23").put("voided", false))
                emit("classification_allocations", JSONObject().put("id", "literal-label-allocation").put("expenseId", "literal-label-bill").put("classificationId", "literal-category").put("amountPaise", 1000)
                    .put("dimension", "COST").put("_classificationName", "No classified costs").put("_expenseDate", "2026-10-23")
                    .put("_expenseDescription", "Reserved category detail").put("_vendorName", "Unassigned supplier").put("_voided", false))
                emit("expense_lines", JSONObject().put("id", "line").put("description", "Wire").put("amountPaise", 12505))
                emit("payments", JSONObject().put("id", "payment").put("amountPaise", 5000).put("kind", "PAYMENT").put("date", "2026-10-23"))
                emit("payments", JSONObject().put("id", "refund").put("amountPaise", 1000).put("kind", "REFUND").put("date", "2026-10-23"))
                emit("commitments", JSONObject().put("id", "commitment").put("title", "Agreement").put("amountPaise", 20000).put("archived", false))
                emit("instalments", JSONObject().put("id", "schedule").put("title", "Future milestone").put("amountPaise", 8000).put("_paidPaise", 1000).put("dueDate", "2099-10-23").put("cancelled", false))
                listOf(LocalDate.now().minusDays(1), LocalDate.now(), LocalDate.now().plusDays(1)).forEachIndexed { index, date ->
                    emit("instalments", JSONObject().put("id", "boundary-$index").put("title", "Boundary $index").put("amountPaise", 100).put("_paidPaise", 0).put("dueDate", date.toString()).put("cancelled", false))
                }
                emit("budgets", JSONObject().put("id", "budget").put("amountPaise", 12345))
                emit("attachments", JSONObject().put("id", "document").put("name", "same name.pdf"))
                emit("audit_records", JSONObject().put("id", "audit").put("afterJson", "=1+1"))
            }
            ZipFile(target).use { zip ->
                val summary = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).bufferedReader().use { it.readText() }
                val records = zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).bufferedReader().use { it.readText() }
                val analytics = zip.getInputStream(zip.getEntry("xl/worksheets/sheet3.xml")).bufferedReader().use { it.readText() }
                val table = zip.getInputStream(zip.getEntry("xl/tables/table1.xml")).bufferedReader().use { it.readText() }
                val links = zip.getInputStream(zip.getEntry("xl/worksheets/_rels/sheet2.xml.rels")).bufferedReader().use { it.readText() }
                val trendChart = zip.getInputStream(zip.getEntry("xl/charts/chart1.xml")).bufferedReader().use { it.readText() }
                val categoryChart = zip.getInputStream(zip.getEntry("xl/charts/chart2.xml")).bufferedReader().use { it.readText() }
                val supplierChart = zip.getInputStream(zip.getEntry("xl/charts/chart3.xml")).bufferedReader().use { it.readText() }
                val styles = zip.getInputStream(zip.getEntry("xl/styles.xml")).bufferedReader().use { it.readText() }
                val contentTypes = zip.getInputStream(zip.getEntry("[Content_Types].xml")).bufferedReader().use { it.readText() }
                assertWellFormedXml(summary)
                assertWellFormedXml(records)
                assertWellFormedXml(analytics)
                assertWellFormedXml(table)
                assertWellFormedXml(trendChart)
                assertWellFormedXml(categoryChart)
                assertWellFormedXml(supplierChart)
                assertWellFormedXml(styles)
                assertWellFormedXml(contentTypes)
                assertTrue(summary.contains("Financial position"))
                assertTrue(summary.contains("Monthly cash and bills"))
                assertTrue(summary.contains("Upcoming scheduled unpaid cash"))
                assertTrue(summary.contains("<c r=\"A6\" s=\"9\"><f>SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],&quot;Expense&quot;)</f><v>135.05</v></c>"))
                assertTrue(summary.contains("<c r=\"C6\" s=\"9\"><f>SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],&quot;Payment&quot;)</f><v>40.00</v></c>"))
                assertTrue(analytics.contains("SUMIFS(ProjectRecords[Numeric INR],ProjectRecords[Record type],&quot;Cost allocation&quot;,ProjectRecords[Category dimension],&quot;COST&quot;,ProjectRecords[Category ID],&quot;materials&quot;)"))
                assertTrue(analytics.contains("ProjectRecords[Category ID],&quot;literal-category&quot;"))
                assertTrue(analytics.contains("SUMIFS(ProjectRecords[Settled INR],ProjectRecords[Record type],&quot;Expense&quot;,ProjectRecords[Supplier ID],&quot;supplier&quot;)"))
                assertTrue(analytics.contains("ProjectRecords[Supplier ID],&quot;literal-supplier&quot;"))
                assertFalse(analytics.contains("ProjectRecords[Category],"))
                assertFalse(analytics.contains("ProjectRecords[Supplier],"))
                assertTrue(trendChart.contains("<c:numRef><c:f>Analytics!A3:A14</c:f><c:numCache><c:formatCode>mmm yyyy</c:formatCode>"))
                assertTrue(categoryChart.contains("Analytics!E3:E4") && categoryChart.contains("<c:strCache>"))
                assertTrue(supplierChart.contains("Analytics!H3:H4") && supplierChart.contains("<c:numCache>"))
                assertTrue(trendChart.indexOf("<c:spPr>") < trendChart.indexOf("<c:marker>"))
                assertTrue(trendChart.indexOf("<c:tickLblPos") < trendChart.indexOf("<c:crossAx"))
                assertTrue(categoryChart.indexOf("<c:tickLblPos") < categoryChart.indexOf("<c:crossAx"))
                assertTrue(supplierChart.indexOf("<c:tickLblPos") < supplierChart.indexOf("<c:crossAx"))
                assertTrue(contentTypes.contains("/xl/worksheets/sheet3.xml") && contentTypes.contains("/xl/charts/chart3.xml") && contentTypes.contains("/xl/styles.xml"))
                assertTrue(table.contains("name=\"Supplier ID\"") && table.contains("name=\"Category ID\""))
                assertTrue(records.contains("<c r=\"C2\" s=\"7\"><v>46318</v></c>"))
                assertTrue(records.contains("<t xml:space=\"preserve\">12505</t>"))
                assertTrue(records.contains("<v>125.05</v>"))
                assertTrue(records.contains("<c r=\"G3\" s=\"5\"><v>125.05</v></c>"))
                assertTrue(records.contains("Invoice category detail"))
                assertTrue(records.contains("<t xml:space=\"preserve\">=HYPERLINK(&quot;bad&quot;)"))
                assertFalse(records.contains("<f>=HYPERLINK"))
                assertTrue(records.contains("<pane xSplit=\"3\" ySplit=\"1\""))
                assertTrue(records.contains("<t xml:space=\"preserve\">Materials*</t>"))
                assertEquals(2, Regex("<t xml:space=\"preserve\">Schedule</t>").findAll(records).count())
                assertEquals(1, Regex("<t xml:space=\"preserve\">Overdue schedule</t>").findAll(records).count())
                assertEquals(1, Regex("<t xml:space=\"preserve\">Due today schedule</t>").findAll(records).count())
                assertTrue(summary.contains("ProjectRecords[Record type],&quot;Schedule&quot;,ProjectRecords[Date],&quot;&gt;&quot;&amp;TODAY()"))
                assertTrue(summary.contains("ProjectRecords[Record type],&quot;Due today schedule&quot;,ProjectRecords[Date],&quot;=&quot;&amp;TODAY()"))
                assertTrue(summary.contains("ProjectRecords[Record type],&quot;Overdue schedule&quot;,ProjectRecords[Date],&quot;&lt;&quot;&amp;TODAY()"))
                assertTrue(analytics.contains("MAX(K3,TODAY())"))
                assertTrue(records.contains("😀"))
                assertFalse(records.contains("\uFFFE") || records.contains("\uFFFF"))
                assertTrue(table.contains("<autoFilter ref=\"A1:P16\""))
                assertTrue(records.contains("attachments/attachment-000001.bin"))
                assertTrue(links.contains("Target=\"../../attachments/attachment-000001.bin\""))
                assertTrue(zip.getEntry("xl/charts/chart1.xml") != null)
                assertTrue(zip.getEntry("xl/drawings/_rels/drawing1.xml.rels") != null)
            }
        } finally { target.delete() }
    }

    @Test fun projectPackageContainsOnlyScopedRowsOriginalBytesAndVerifiedChecksums() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        val storageName = "${UUID.randomUUID()}.bin"
        val original = "original invoice bytes\u0000".toByteArray()
        val source = DocumentPolicy.safeFile(store.root, storageName)
        val packageFile = File(context.cacheDir, "project-package-${UUID.randomUUID()}.zip")
        var workbookFile: File? = null
        var generated: File? = null
        try {
            repository.saveProject(ProjectInfo("north-project", "North wing", 98765))
            repository.saveProject(ProjectInfo("south-project", "South wing"))
            repository.saveExpense(ExpenseInput(id = "north-bill", projectId = "north-project", description = "North invoice", amountPaise = 25005, date = "2026-10-23"))
            repository.saveExpense(ExpenseInput(id = "south-bill", projectId = "south-project", description = "Private south invoice", amountPaise = 99999, date = "2026-10-23"))
            source.writeBytes(original)
            repository.insertAttachment(AttachmentInfo("north-file", "north-project", "duplicate.pdf", "application/pdf", original.size.toLong(), DocumentPolicy.sha256(source), storageName, 1))
            generated = ProjectWorkbookExport.create(context.cacheDir, repository, store, "north-project") {}
            generated.copyTo(packageFile, overwrite = true)
            ZipFile(packageFile).use { archive ->
                assertTrue(archive.getEntry("workbook.xlsx") != null)
                val manifest = archive.getInputStream(archive.getEntry("export-manifest.json")).bufferedReader().use { it.readText() }
                assertTrue(manifest.contains("north-project"))
                assertFalse(manifest.contains("south-project"))
                val path = "attachments/attachment-000001.bin"
                val copied = archive.getInputStream(archive.getEntry(path)).use { it.readBytes() }
                assertTrue(original.contentEquals(copied))
                val checksum = MessageDigest.getInstance("SHA-256").digest(copied).joinToString("") { "%02x".format(it) }
                val checksums = archive.getInputStream(archive.getEntry("SHA256SUMS.txt")).bufferedReader().use { it.readText() }
                assertTrue(checksums.contains("$checksum  $path"))
                listOf("workbook.xlsx", "export-manifest.json", "attachment-manifest.json", "README.txt").forEach { entry ->
                    assertTrue(checksums.contains("  $entry"))
                }
                workbookFile = File(context.cacheDir, "workbook-check-${UUID.randomUUID()}.xlsx")
                archive.getInputStream(archive.getEntry("workbook.xlsx")).use { input -> workbookFile.outputStream().use { output -> input.copyTo(output) } }
            }
            ZipFile(workbookFile!!).use { workbook ->
                val records = workbook.getInputStream(workbook.getEntry("xl/worksheets/sheet2.xml")).bufferedReader().use { it.readText() }
                assertTrue(records.contains("North invoice"))
                assertFalse(records.contains("Private south invoice"))
            }
        } finally {
            source.delete(); packageFile.delete(); workbookFile?.delete(); generated?.delete(); repository.close()
        }
    }

    @Test fun longEvidenceUsesBoundedCellsAndPreservesTheCompleteSourceRecord() = runBlocking<Unit> {
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "long-workbook-${UUID.randomUUID()}.xlsx")
        val note = "x".repeat(32_766) + "\uD83D\uDE00" + " original tail\u0001\uFFFE"
        val original = JSONObject().put("id", "long-evidence").put("description", note).put("afterJson", note)
        try {
            ProjectWorkbookWriter.write(target, ProjectInfo("project", "Long evidence"), emptyList()) { emit -> emit("audit_records", original) }
            ZipFile(target).use { zip ->
                val records = zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).bufferedReader().use { it.readText() }
                assertWellFormedXml(records)
                assertTrue(records.contains("Full content: xl/overflow-records.jsonl"))
                val parser = Xml.newPullParser().apply { setInput(StringReader(records)) }
                while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "t") assertTrue(parser.nextText().length <= 32_767)
                }
                val evidence = zip.getInputStream(zip.getEntry("xl/overflow-records.jsonl")).bufferedReader().use { it.readLines() }
                assertEquals(1, evidence.size)
                val row = JSONObject(evidence.single())
                assertEquals("audit_records", row.getString("table"))
                assertEquals(note, row.getJSONObject("record").getString("description"))
                assertEquals(note, row.getJSONObject("record").getString("afterJson"))
            }
        } finally { target.delete() }
    }
}
