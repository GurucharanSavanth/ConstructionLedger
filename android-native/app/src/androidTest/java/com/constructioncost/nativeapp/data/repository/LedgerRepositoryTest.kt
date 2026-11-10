package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.constructioncost.nativeapp.data.local.LedgerDatabase
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.ExpenseFilter
import com.constructioncost.nativeapp.domain.ActivityNote
import com.constructioncost.nativeapp.domain.ExpenseSummary
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ExpenseLineInput
import com.constructioncost.nativeapp.domain.ClassificationInfo
import com.constructioncost.nativeapp.domain.ClassificationPart
import com.constructioncost.nativeapp.domain.CommitmentInput
import com.constructioncost.nativeapp.domain.DetailedAmount
import com.constructioncost.nativeapp.domain.InstalmentInput
import com.constructioncost.nativeapp.domain.Money
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.domain.VendorInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    @Test fun attachmentLinksKeepTheirIdentityAndAuditActualRemovals() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            listOf("kept", "removed", "note-file").forEach { id ->
                repository.insertAttachment(AttachmentInfo(id, "project", "$id.txt", "text/plain", 1, "a".repeat(64), "$id.bin", 1))
            }
            val bill = ExpenseInput(id = "bill", projectId = "project", description = "Material", amountPaise = 10_000,
                date = "2026-01-01", attachmentIds = listOf("kept", "removed"))
            repository.saveExpense(bill)
            val linksBefore = repository.exportRecords().getJSONArray("attachment_links")
            val keptLinkId = (0 until linksBefore.length()).map { linksBefore.getJSONObject(it) }
                .single { it.getString("attachmentId") == "kept" }.getString("id")
            repository.saveExpense(bill.copy(revision = 0))
            val unchanged = repository.exportRecords().getJSONArray("attachment_links")
            assertEquals(keptLinkId, (0 until unchanged.length()).map { unchanged.getJSONObject(it) }
                .single { it.getString("attachmentId") == "kept" }.getString("id"))
            assertEquals(0, repository.audits("kept", "attachments", "project").first().count { it.action == "UNLINK" })
            repository.saveExpense(bill.copy(revision = 1, attachmentIds = listOf("kept")))
            assertEquals(keptLinkId, repository.exportRecords().getJSONArray("attachment_links").getJSONObject(0).getString("id"))
            val billRemoval = repository.audits("removed", "attachments", "project").first().single { it.action == "UNLINK" }
            assertEquals(true, billRemoval.beforeJson.contains("\"targetId\":\"bill\""))
            assertEquals(0, repository.attachment("removed")!!.referenceCount)
            repository.saveNote(ActivityNote("note", "project", "Site", "2026-01-01", ""))
            repository.linkAttachment("note-file", noteId = "note")
            repository.deleteNote("note", 0)
            assertEquals(1, repository.audits("note-file", "attachments", "project").first().count { it.action == "UNLINK" })
            assertEquals(0, repository.attachment("note-file")!!.referenceCount)
            repository.validateRecords(repository.exportRecords())
        } finally { repository.close() }
    }

    @Test fun deletingRefundedBillPreservesCorrectionEvidenceAndReleasesNetAdvance() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "refund-delete-${UUID.randomUUID()}.db"
        fun open() = LedgerRepository(Room.databaseBuilder(context, LedgerDatabase::class.java, name).build(), context)
        var repository = open()
        val restored = LedgerRepository.inMemory(context)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "bill", projectId = "project", description = "Material", amountPaise = 10_000, date = "2026-01-01"))
            repository.savePayment(PaymentInput(id = "cash", projectId = "project", amountPaise = 10_000, date = "2026-01-01",
                allocations = listOf(PaymentAllocationInput("bill", 10_000))))
            repository.refundPayment("cash", 1_000, "Returned", "2026-01-02")
            repository.deleteExpense("bill", 0)
            val summary = repository.summary("project").first()
            assertEquals(0L, summary.recordedPaise)
            assertEquals(9_000L, summary.paidPaise)
            assertEquals(0L, summary.outstandingPaise)
            assertEquals(9_000L, summary.advancePaise)
            val snapshot = repository.exportRecords()
            val allocations = snapshot.getJSONArray("payment_allocations")
            assertEquals(2, allocations.length())
            assertEquals(2_000L, (0 until allocations.length()).sumOf { allocations.getJSONObject(it).getLong("amountPaise") })
            val audit = snapshot.getJSONArray("audit_records")
            assertEquals(true, (0 until audit.length()).any {
                val event = audit.getJSONObject(it)
                event.getString("recordType") == "payment_allocations" && event.getString("action") == "RELEASE"
            })
            repository.close()
            repository = open()
            assertEquals(9_000L, repository.summary("project").first().advancePaise)
            repository.validateRecords(snapshot)
            restored.replaceRecords(snapshot)
            assertEquals(9_000L, restored.summary("project").first().advancePaise)
            restored.reversePayment("cash", "Remaining returned", "2026-01-03")
            restored.validateRecords(restored.exportRecords())
            assertEquals(0L, restored.summary("project").first().paidPaise)
            assertEquals(0L, restored.summary("project").first().advancePaise)
        } finally { repository.close(); restored.close(); context.deleteDatabase(name) }
    }

    @Test fun publicFileReplacementUsesOnePrivateValidatedCopy() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = LedgerRepository.inMemory(context)
        val restored = LedgerRepository.inMemory(context)
        val file = File.createTempFile("ledger-caller-", ".json", context.cacheDir)
        try {
            source.saveProject(ProjectInfo("project", "Build"))
            source.saveExpense(ExpenseInput(id = "bill", projectId = "project", description = "Material", amountPaise = 10_000, date = "2026-01-01"))
            source.exportRecordsTo(file)
            val validated = file.readBytes()
            restored.replacementCheckpoint = { phase -> if (phase == "rows-cleared") file.writeText("{}") }
            restored.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
            assertEquals(10_000L, restored.summary("project").first().recordedPaise)
            assertEquals("{}", file.readText())
            assertEquals(true, validated.isNotEmpty())
            restored.validateRecords(restored.exportRecords())
        } finally { restored.replacementCheckpoint = {}; source.close(); restored.close(); file.delete() }
    }

    @Test fun restoreCannotResurrectRemovedCostsAgreementsOrSchedulesFromStaleEditors() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            val earlier = repository.exportRecords()
            val cost = ExpenseInput(id = "removed-cost", projectId = "project", description = "Cement", amountPaise = 1850000, date = "2026-01-01")
            val agreement = CommitmentInput(id = "removed-agreement", projectId = "project", title = "Engineer", amountPaise = 10000, date = "2026-01-01")
            val schedule = InstalmentInput(id = "removed-plan", projectId = "project", title = "Milestone", amountPaise = 5000, dueDate = "2026-02-01")
            repository.saveExpense(cost)
            repository.saveCommitment(agreement)
            repository.saveInstalment(schedule)
            repository.replaceRecords(earlier)
            val beforeRetry = repository.exportRecords().toString()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveExpense(cost.copy(amountPaise = 1900000, revision = 0)) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveCommitment(agreement.copy(revision = 0)) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveInstalment(schedule.copy(revision = 0)) } }
            assertEquals(beforeRetry, repository.exportRecords().toString())
            assertEquals(0L, repository.summary("project").first().recordedPaise)
            assertEquals(0L, repository.summary("project").first().paidPaise)
            assertEquals(0L, repository.summary("project").first().commitmentPaise)
        } finally { repository.close() }
    }
    @Test fun drilldownIncludesDirectParentCostsAndExactDirectExpenseLinks() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveClassification(ClassificationInfo("stage", "project", "Foundation", "STAGE"))
            repository.saveClassification(ClassificationInfo("child", "project", "Concrete", "SUBSTAGE", "stage"))
            repository.saveExpense(ExpenseInput(id = "parent-cost", projectId = "project", description = "General foundation", amountPaise = 10000,
                date = "2026-01-01", classifications = listOf(ClassificationPart("stage", "WORK", 10000))))
            repository.saveExpense(ExpenseInput(id = "child-cost", projectId = "project", description = "Concrete", amountPaise = 5000,
                date = "2026-01-01", classifications = listOf(ClassificationPart("child", "WORK", 5000))))
            assertEquals(15000L, repository.insights("project").first().single { it.id == "stage" }.amountPaise)
            val drill = repository.insights("project", "stage").first()
            assertEquals(10000L, drill.single { it.kind == "DIRECT_ALLOCATION" }.amountPaise)
            assertEquals(5000L, drill.single { it.id == "child" }.amountPaise)
            assertEquals(15000L, drill.sumOf { it.amountPaise })
            val filter = ExpenseFilter(classificationId = "stage", directClassification = true)
            assertEquals(listOf("parent-cost"), repository.expenses("project", filter).first().map { it.id })
            assertEquals(10000L, repository.filteredExpenseSummary("project", filter).first().recordedPaise)
        } finally { repository.close() }
    }
    @Test fun allocationCorrectionChildHistoryCannotJoinAnotherProjectsPaymentIdentifier() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("A", "A"))
            repository.saveProject(ProjectInfo("B", "B"))
            repository.saveExpense(ExpenseInput(id = "invoice-A", projectId = "A", description = "Private cost", amountPaise = 100, date = "2026-01-01"))
            repository.savePayment(PaymentInput(id = "payment-A", projectId = "A", amountPaise = 100, date = "2026-01-01",
                allocations = listOf(PaymentAllocationInput("invoice-A", 100))))
            val allocation = repository.exportRecords().getJSONArray("payment_allocations").getJSONObject(0).getString("id")
            repository.savePayment(PaymentInput(id = allocation, projectId = "B", amountPaise = 200, date = "2026-01-01"))
            val state = repository.allocationCorrectionState(allocation)!!
            repository.correctAllocation(allocation, state.fingerprint, 50, null, "correction-owner-test")
            val a = mutableListOf<String>()
            val b = mutableListOf<String>()
            repository.forEachExportRow("A") { table, row -> if (table == "audit_records" && row.getString("recordType") == "payment_allocations" && row.getString("action") == "CORRECT") a += row.toString() }
            repository.forEachExportRow("B") { table, row -> if (table == "audit_records" && row.getString("recordType") == "payment_allocations") b += row.toString() }
            assertEquals(2, a.size)
            assertEquals(0, b.size)
            val correctionId = "allocation-correction:correction-owner-test"
            assertEquals(true, repository.projectAudits("A").first().any { it.id == correctionId })
            assertEquals(false, repository.projectAudits("B").first().any { it.id == correctionId })
            assertEquals(true, repository.journal("A", ExpenseFilter()).first().any { it.event.id == correctionId })
            assertEquals(50L, repository.summary("A").first().outstandingPaise)
            assertEquals(100L, repository.summary("A").first().paidPaise)
        } finally { repository.close() }
    }
    @Test fun deletedNoteAuditKeepsOriginalProjectWhenIdentifierIsReused() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("A", "A"))
            repository.saveProject(ProjectInfo("B", "B"))
            repository.saveNote(ActivityNote("note", "A", "Work", "2026-01-01", "A private"))
            repository.deleteNote("note", 0)
            repository.saveNote(ActivityNote("note", "B", "Work", "2026-01-02", "B current"))
            val detail = repository.audits("note", "activity_entries", "B").first()
            assertEquals(1, detail.size)
            assertEquals(false, detail.any { it.beforeJson.contains("A private") || it.afterJson.contains("A private") })
            fun auditTexts(project: String): List<String> = runBlocking {
                val results = mutableListOf<String>()
                repository.forEachExportRow(project) { table, row -> if (table == "audit_records" && row.getString("recordType") == "activity_entries") results += row.toString() }
                results
            }
            val a = auditTexts("A")
            assertEquals(2, a.size)
            assertEquals(true, a.all { it.contains("A private") })
            val b = auditTexts("B")
            assertEquals(1, b.size)
            assertEquals(false, b.any { it.contains("A private") })
        } finally { repository.close() }
    }
    @Test fun invoiceReferenceSearchReconcilesPagedRowsAndFullSummary() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "reference", projectId = "project", description = "Cement",
                amountPaise = 1850000, date = "2026-01-01", invoiceNumber = "INV-778"))
            val filter = ExpenseFilter(query = "inv-778")
            assertEquals(listOf("reference"), repository.expenses("project", filter).first().map { it.id })
            assertEquals(1850000L, repository.filteredExpenseSummary("project", filter).first().recordedPaise)
            assertEquals(1, repository.filteredExpenseSummary("project", filter).first().count)
        } finally { repository.close() }
    }
    @Test fun invoiceWarningIsBoundedScopedAndNeverBlocksLegitimatePartialBills() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "A"))
            repository.saveProject(ProjectInfo("other", "B"))
            repository.saveVendor(VendorInfo("supplier-a", "project", "A supplies"))
            repository.saveVendor(VendorInfo("supplier-b", "project", "B supplies"))
            repository.saveVendor(VendorInfo("supplier-other", "other", "Other supplies"))
            repeat(6) { index -> repository.saveExpense(ExpenseInput(id = "partial-$index", projectId = "project", description = "Partial invoice $index", amountPaise = 100, date = "2026-01-01", vendorId = "supplier-a", invoiceNumber = if (index % 2 == 0) "INV-7" else " inv-7 ")) }
            repository.saveExpense(ExpenseInput(id = "different-vendor", projectId = "project", description = "Another supplier", amountPaise = 100, date = "2026-01-01", vendorId = "supplier-b", invoiceNumber = "INV-7"))
            repository.saveExpense(ExpenseInput(id = "no-vendor", projectId = "project", description = "No supplier", amountPaise = 100, date = "2026-01-01", invoiceNumber = "INV-7"))
            repository.saveExpense(ExpenseInput(id = "different-project", projectId = "other", description = "Other project", amountPaise = 100, date = "2026-01-01", vendorId = "supplier-other", invoiceNumber = "INV-7"))
            assertEquals(5, repository.invoiceDuplicate("project", "iNv-7", "supplier-a").size)
            assertEquals(5, repository.invoiceDuplicate("project", "inv-7", "supplier-a", "partial-0").size)
            assertEquals(listOf("different-vendor"), repository.invoiceDuplicate("project", "inv-7", "supplier-b").map { it.id })
            assertEquals(listOf("no-vendor"), repository.invoiceDuplicate("project", "INV-7", null).map { it.id })
            assertEquals(emptyList<String>(), repository.invoiceDuplicate("project", "", "supplier-a").map { it.id })
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveExpense(ExpenseInput(id = "bad-invoice", projectId = "project", description = "Bad", amountPaise = 100, date = "2026-01-01", invoiceNumber = "x".repeat(201))) } }
            assertEquals(6, repository.expenses("project").first().count { it.vendorId == "supplier-a" })
        } finally { repository.close() }
    }

    @Test fun reminderLeadDaysSurviveCancellationAndInvalidEditsRollback() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            val plan = InstalmentInput(id = "plan", projectId = "project", title = "Due", amountPaise = 1_000, dueDate = "2026-02-01", remind = true, reminderDays = 7)
            repository.saveInstalment(plan)
            assertEquals(7, repository.instalments("project").first().single().reminderDays)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveInstalment(plan.copy(reminderDays = 366, revision = 0)) } }
            assertEquals(7, repository.instalments("project").first().single().reminderDays)
            repository.saveInstalment(plan.copy(reminderDays = 365, revision = 0))
            assertEquals(365, repository.instalments("project").first().single().reminderDays)
            repository.cancelInstalment("plan", 1)
            val cancelled = repository.instalments("project").first().single()
            assertEquals(true, cancelled.cancelled)
            assertEquals(false, cancelled.remind)
            assertEquals(365, cancelled.reminderDays)
        } finally { repository.close() }
    }

    @Test fun calculatedRestoreRejectsLegacySubstringWithoutCalculationEvidence() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "bill", projectId = "project", description = "Valid direct invoice", amountPaise = 100, date = "2026-01-01"))
            val malicious = repository.exportRecords()
            malicious.getJSONArray("expenses").getJSONObject(0).apply {
                put("mode", "CALCULATED")
                put("calculationJson", "")
                put("requestJson", "{\"description\":\"legacy cabinet\"}")
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replaceRecords(malicious) } }
            assertEquals("DIRECT_AMOUNT", repository.expense("bill")!!.mode)
            assertEquals(100L, repository.summary("project").first().recordedPaise)
        } finally { repository.close() }
    }

    @Test fun latePaymentReceiptCanBeUnlinkedWithoutDeletingOriginalOrMetadata() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        val id = UUID.randomUUID().toString()
        val original = File(store.root, "$id.bin")
        try {
            repository.saveProject(ProjectInfo("project-a", "A"))
            repository.saveProject(ProjectInfo("project-b", "B"))
            repository.saveExpense(ExpenseInput(id = "bill-a", projectId = "project-a", description = "Bill", amountPaise = 100, date = "2026-01-01"))
            repository.savePayment(PaymentInput(id = "cash-a", projectId = "project-a", amountPaise = 100, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("bill-a", 100))))
            repository.savePayment(PaymentInput(id = "cash-b", projectId = "project-b", amountPaise = 100, date = "2026-01-02"))
            original.writeText("receipt original")
            repository.insertAttachment(AttachmentInfo(id, "project-a", "Receipt", "text/plain", original.length(), DocumentPolicy.sha256(original), original.name, 123))
            repository.linkAttachment(id, paymentId = "cash-a")
            val linked = repository.attachment(id)!!
            assertEquals(1, linked.referenceCount)
            assertEquals(listOf(id), repository.paymentAttachmentIds("cash-a"))
            assertEquals(listOf("PAYMENT" to "cash-a"), repository.attachmentLinks(id))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.linkAttachment(id, paymentId = "cash-b") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.unlinkAttachment(id, "PAYMENT", "cash-b") } }
            assertEquals(1, repository.attachment(id)!!.referenceCount)
            assertEquals(0, repository.audits(id, "attachments", "project-a").first().count { it.action == "UNLINK" })
            repository.unlinkAttachment(id, "PAYMENT", "cash-a")
            assertEquals(linked.copy(referenceCount = 0), repository.attachment(id))
            assertEquals(emptyList<String>(), repository.paymentAttachmentIds("cash-a"))
            assertEquals(emptyList<Pair<String, String>>(), repository.attachmentLinks(id))
            assertEquals("receipt original", original.readText())
            assertEquals(1, repository.audits(id, "attachments", "project-a").first().count { it.action == "UNLINK" })
        } finally { original.delete(); repository.close() }
    }

    @Test fun activitySearchDateTypeAndRecordedOrderStayProjectScoped() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, context)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveProject(ProjectInfo("other", "Other"))
            repository.saveVendor(VendorInfo("vendor", "project", "Mason Supplies"))
            repository.saveExpense(ExpenseInput(id = "shared", projectId = "project", description = "Brick invoice", amountPaise = 100, date = "2025-01-01", vendorId = "vendor"))
            repository.savePayment(PaymentInput(id = "shared", projectId = "project", amountPaise = 100, date = "2026-01-02", vendorId = "vendor", reference = "Brick transfer"))
            repository.saveNote(ActivityNote("note", "project", "Site inspection", "2026-01-03", ""))
            repository.saveNote(ActivityNote("other-note", "other", "Site inspection", "2026-01-03", ""))
            val sql = database.openHelper.writableDatabase
            sql.execSQL("UPDATE audit_records SET createdAt=1800000000300 WHERE recordType='expenses' AND recordId='shared' AND action='SAVE'")
            sql.execSQL("UPDATE audit_records SET createdAt=1800000000100 WHERE recordType='payments' AND recordId='shared' AND action='SAVE'")
            sql.execSQL("UPDATE audit_records SET createdAt=1800000000200 WHERE recordType='activity_entries' AND recordId='note' AND action='SAVE'")
            assertEquals(listOf("NOTE", "PAYMENT", "EXPENSE"), repository.activity("project").first().map { it.type })
            assertEquals(listOf("EXPENSE", "NOTE", "PAYMENT"), repository.activity("project", sortByRecordedTime = true).first().map { it.type })
            assertEquals(listOf("PAYMENT", "EXPENSE"), repository.activity("project", query = "Mason").first().map { it.type })
            assertEquals(listOf("PAYMENT"), repository.activity("project", recordType = "PAYMENT").first().map { it.type })
            assertEquals(listOf("NOTE"), repository.activity("project", query = "note").first().map { it.type })
            assertEquals(listOf("NOTE", "PAYMENT"), repository.activity("project", fromDate = "2026-01-01", toDate = "2026-01-31").first().map { it.type })
            assertEquals(listOf("NOTE", "PAYMENT"), repository.activity("project", includeExpenses = false).first().map { it.type })
            repository.saveNote(ActivityNote("legacy-note", "project", "Legacy site note", "2024-01-01", ""))
            sql.execSQL("DELETE FROM audit_records WHERE recordType='activity_entries' AND recordId='legacy-note'")
            assertEquals("legacy-note", repository.activity("project", sortByRecordedTime = true).first().last().id)
            repeat(80) { index -> repository.saveNote(ActivityNote("review-$index", "project", "Review $index", "2026-02-01", "")) }
            assertEquals(75, repository.activity("project", query = "Review", recordType = "NOTE", limit = 75).first().size)
            assertEquals(5, repository.activity("project", query = "Review", recordType = "NOTE", limit = 75, offset = 75).first().size)
            assertEquals(0, repository.activity("project", query = "Review", recordType = "NOTE", fromDate = "2026-02-02").first().size)
        } finally { repository.close() }
    }

    @Test fun filteredExpenseSummaryUsesAllMatchingRowsAndSignedSettlement() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveClassification(ClassificationInfo("stage", "project", "Foundation", "STAGE"))
            repeat(80) { index ->
                repository.saveExpense(ExpenseInput(id = "bill-$index", projectId = "project", description = "Concrete $index", amountPaise = 100, date = "2026-01-01",
                    classifications = if (index < 10) listOf(ClassificationPart("stage", "WORK", 100)) else emptyList()))
            }
            repository.savePayment(PaymentInput(id = "cash-full", projectId = "project", amountPaise = 500, date = "2026-01-02",
                allocations = (0 until 5).map { PaymentAllocationInput("bill-$it", 100) }))
            repository.savePayment(PaymentInput(id = "cash-partial", projectId = "project", amountPaise = 250, date = "2026-01-02",
                allocations = (5 until 10).map { PaymentAllocationInput("bill-$it", 50) }))
            val concrete = ExpenseFilter(query = "Concrete")
            assertEquals(75, repository.expenses("project", concrete).first().size)
            val all = repository.filteredExpenseSummary("project", concrete).first()
            assertEquals(ExpenseSummary(80, 8_000L, 750L, 7_250L), all)
            val stage = repository.filteredExpenseSummary("project", concrete.copy(classificationId = "stage")).first()
            assertEquals(ExpenseSummary(10, 1_000L, 750L, 250L), stage)
            val open = repository.filteredExpenseSummary("project", concrete.copy(status = "OUTSTANDING")).first()
            assertEquals(ExpenseSummary(75, 7_500L, 250L, 7_250L), open)
            repository.voidExpense("bill-79", 0)
            val afterVoid = repository.filteredExpenseSummary("project", concrete).first()
            assertEquals(ExpenseSummary(80, 7_900L, 750L, 7_150L), afterVoid)
            val voided = repository.filteredExpenseSummary("project", concrete.copy(status = "VOIDED")).first()
            assertEquals(ExpenseSummary(1, 0L, 0L, 0L), voided)
        } finally { repository.close() }
    }

    @Test fun classificationSiblingNamesAndStaleEditsRollback() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            val stageA = ClassificationInfo("stage-a", "project", "Foundation", "STAGE")
            val stageB = ClassificationInfo("stage-b", "project", "Roof", "STAGE")
            val workA = ClassificationInfo("work-a", "project", "Excavation", "SUBSTAGE", stageA.id)
            val workB = ClassificationInfo("work-b", "project", "Excavation", "SUBSTAGE", stageB.id)
            val head = ClassificationInfo("head", "project", "Materials", "COST_HEAD")
            listOf(stageA, stageB, workA, workB, head).forEach { repository.saveClassification(it) }
            val baseline = repository.classifications("project").first()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveClassification(ClassificationInfo("stage-duplicate", "project", " foundation ", "STAGE")) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveClassification(ClassificationInfo("head-duplicate", "project", "materials", "COST_HEAD")) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveClassification(stageB.copy(name = "FOUNDATION"), stageB) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveClassification(workB.copy(parentId = stageA.id), workB) } }
            assertEquals(baseline, repository.classifications("project").first())
            repository.saveClassification(stageB.copy(sortOrder = 2), stageB)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveClassification(stageB.copy(name = "Roof revised"), stageB) } }
            assertEquals("Roof", repository.classifications("project").first().single { it.id == stageB.id }.name)
            repository.saveClassification(stageA.copy(archived = true), stageA)
            repository.saveClassification(ClassificationInfo("stage-reused", "project", "foundation", "STAGE"))
            assertEquals(6, repository.classifications("project").first().size)
        } finally { repository.close() }
    }

    @Test fun archivedAgreementKeepsLinkedFinancialHistoryAndRejectsStaleRevision() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveCommitment(CommitmentInput(id = "agreement", projectId = "project", title = "Masonry", amountPaise = 1_000, date = "2026-01-01",
                schedules = listOf(InstalmentInput(id = "milestone", projectId = "project", title = "First payment", amountPaise = 500, dueDate = "2026-02-01"))))
            repository.saveExpense(ExpenseInput(id = "bill", projectId = "project", description = "Masonry bill", amountPaise = 100, date = "2026-01-02", commitmentId = "agreement", instalmentId = "milestone"))
            repository.savePayment(PaymentInput(id = "cash", projectId = "project", amountPaise = 100, date = "2026-01-03", instalmentId = "milestone", allocations = listOf(PaymentAllocationInput("bill", 100))))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.archiveCommitment("agreement", 1) } }
            assertEquals(1, repository.commitments("project").first().size)
            repository.archiveCommitment("agreement", 0)
            assertEquals(emptyList<Any>(), repository.commitments("project").first())
            assertEquals(1, repository.instalments("project").first().size)
            assertEquals(listOf(100L, 100L), listOf(repository.summary("project").first().recordedPaise, repository.summary("project").first().paidPaise))
            assertEquals(true, repository.exportRecords().getJSONArray("commitments").getJSONObject(0).getBoolean("archived"))
            assertEquals(1, repository.audits("agreement", "commitments", "project").first().count { it.action == "ARCHIVE" })
        } finally { repository.close() }
    }

    @Test fun paymentOperationAuditCannotLeakThroughOtherProjectsAllocationId() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, context)
        try {
            repository.saveProject(ProjectInfo("project-a", "A"))
            repository.saveProject(ProjectInfo("project-b", "B"))
            repository.saveExpense(ExpenseInput(id = "expense-a", projectId = "project-a", description = "A", amountPaise = 100, date = "2026-01-01"))
            repository.saveExpense(ExpenseInput(id = "expense-b", projectId = "project-b", description = "B", amountPaise = 100, date = "2026-01-01"))
            repository.savePayment(PaymentInput(id = "shared", projectId = "project-a", amountPaise = 100, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("expense-a", 100))))
            repository.savePayment(PaymentInput(id = "payment-b", projectId = "project-b", amountPaise = 100, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("expense-b", 100))))
            val old = repository.paymentAllocationStates("payment-b").single()
            val sql = database.openHelper.writableDatabase
            sql.execSQL("DELETE FROM payment_allocations WHERE id=?", arrayOf(old.allocationId))
            sql.execSQL("INSERT INTO payment_allocations(id,paymentId,expenseId,amountPaise) VALUES('shared','payment-b','expense-b',100)")
            sql.execSQL("INSERT INTO audit_records(id,recordType,recordId,action,beforeJson,afterJson,createdAt) VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>("allocation-correction:operation-a", "payment_allocations", "shared", "CORRECT", "null", "null", 3L))
            sql.execSQL("INSERT INTO audit_records(id,recordType,recordId,action,beforeJson,afterJson,createdAt) VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>("row-b-delete", "payment_allocations", "shared", "DELETE", "null", "null", 4L))
            fun exportAuditIds(projectId: String): Set<String> {
                val ids = mutableSetOf<String>()
                runBlocking { repository.forEachExportRow(projectId) { table, row -> if (table == "audit_records") ids += row.getString("id") } }
                return ids
            }
            val a = exportAuditIds("project-a")
            val b = exportAuditIds("project-b")
            assertEquals(true, "allocation-correction:operation-a" in a)
            assertEquals(false, "allocation-correction:operation-a" in b)
            assertEquals(true, "row-b-delete" in b)
            assertEquals(false, "row-b-delete" in a)
        } finally { repository.close() }
    }

    @Test fun attachmentDetailsSaveAtomicallyAndRejectStaleOrOversizedEdits() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.insertAttachment(AttachmentInfo("document", "project", "Drawing", "application/pdf", 10, "a".repeat(64), "drawing.pdf", 1))
            repository.saveAttachmentDetails("document", 0, "Drawing", "Approved", "R3", "Foundation", "Ground floor", "Reviewed by engineer")
            val saved = repository.attachment("document")!!
            assertEquals(1L, saved.revision)
            assertEquals(listOf("Drawing", "Approved", "R3", "Foundation", "Ground floor", "Reviewed by engineer"), listOf(saved.category, saved.status, saved.revisionLabel, saved.work, saved.place, saved.notes))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveAttachmentDetails("document", 0, "Wrong", "", "", "", "", "") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveAttachmentDetails("document", 1, "x".repeat(201), "", "", "", "", "") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveAttachmentDetails("document", 1, "", "", "", "", "", "x".repeat(10_001)) } }
            assertEquals(saved, repository.attachment("document"))
            assertEquals(1, repository.audits("document", "attachments", "project").first().count { it.action == "DETAILS" })
        } finally { repository.close() }
    }

    @Test fun auditDetailRequiresTypedOwnerInSelectedProject() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project-a", "A"))
            repository.saveProject(ProjectInfo("project-b", "B"))
            repository.saveExpense(ExpenseInput(id = "shared", projectId = "project-a", description = "A invoice", amountPaise = 100, date = "2026-01-01"))
            repository.saveVendor(VendorInfo("shared", "project-b", "B supplier"))
            assertEquals(true, repository.audits("shared", "expenses", "project-a").first().any { it.recordType == "expenses" })
            assertEquals(listOf("vendors"), repository.audits("shared", "vendors", "project-b").first().map { it.recordType })
            assertEquals(0, repository.audits("shared", "expenses", "project-b").first().size)
            assertEquals(0, repository.audits("shared", "vendors", "project-a").first().size)
        } finally { repository.close() }
    }

    @Test fun allocationCorrectionTransfersReleasesAndBlocksInvalidOrCorrectedCash() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveProject(ProjectInfo("foreign", "Other"))
            repository.saveExpense(ExpenseInput(id = "bill-a", projectId = "project", description = "A", amountPaise = 5_000, date = "2026-01-01"))
            repository.saveExpense(ExpenseInput(id = "bill-b", projectId = "project", description = "B", amountPaise = 5_000, date = "2026-01-01"))
            repository.saveExpense(ExpenseInput(id = "small", projectId = "project", description = "Small", amountPaise = 500, date = "2026-01-01"))
            repository.saveExpense(ExpenseInput(id = "foreign-bill", projectId = "foreign", description = "Foreign", amountPaise = 5_000, date = "2026-01-01"))
            repository.savePayment(PaymentInput(id = "cash", projectId = "project", amountPaise = 4_000, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("bill-a", 4_000))))
            val original = repository.paymentAllocationStates("cash").single()
            val transfer = repository.correctAllocation(original.allocationId, original.fingerprint, 4_000, "bill-b", "transfer-one")
            assertEquals(transfer, repository.correctAllocation(original.allocationId, original.fingerprint, 4_000, "bill-b", "transfer-one"))
            assertEquals(4_000L, repository.summary("project").first().paidPaise)
            val afterTransfer = repository.expenses("project").first().associateBy { it.id }
            assertEquals(5_000L, afterTransfer.getValue("bill-a").outstandingPaise)
            assertEquals(1_000L, afterTransfer.getValue("bill-b").outstandingPaise)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.correctAllocation(original.allocationId, original.fingerprint, 1_000, "bill-b", "stale") } }
            val billB = repository.paymentAllocationStates("cash").single()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.correctAllocation(billB.allocationId, billB.fingerprint, 1_000, "foreign-bill", "foreign-key") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.correctAllocation(billB.allocationId, billB.fingerprint, 1_000, "small", "overpay") } }
            repository.correctAllocation(billB.allocationId, billB.fingerprint, 1_000, null, "release-one")
            assertEquals(4_000L, repository.summary("project").first().paidPaise)
            assertEquals(1_000L, repository.summary("project").first().advancePaise)
            assertEquals(3_000L, repository.paymentAllocationStates("cash").single().amountPaise)
            repository.refundPayment("cash", 1_000, "Returned settled amount", "2026-01-03")
            val correctedCash = repository.paymentAllocationStates("cash").single()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.correctAllocation(correctedCash.allocationId, correctedCash.fingerprint, 500, "bill-a", "after-refund") } }
            assertEquals(3_000L, repository.summary("project").first().paidPaise)
            assertEquals(3_000L, repository.paymentAllocationStates("cash").single().amountPaise)
        } finally { repository.close() }
    }

    @Test fun allocationFingerprintDetectsAllocationAba() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "bill-a", projectId = "project", description = "A", amountPaise = 5_000, date = "2026-01-01"))
            repository.saveExpense(ExpenseInput(id = "bill-b", projectId = "project", description = "B", amountPaise = 5_000, date = "2026-01-01"))
            repository.savePayment(PaymentInput(id = "cash", projectId = "project", amountPaise = 6_000, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("bill-a", 4_000), PaymentAllocationInput("bill-b", 2_000))))
            val initial = repository.paymentAllocationStates("cash").associateBy { it.expenseId }
            repository.correctAllocation(initial.getValue("bill-a").allocationId, initial.getValue("bill-a").fingerprint, 1_000, "bill-b", "move-out")
            val next = repository.paymentAllocationStates("cash").associateBy { it.expenseId }
            repository.correctAllocation(next.getValue("bill-b").allocationId, next.getValue("bill-b").fingerprint, 1_000, "bill-a", "move-back")
            val final = repository.paymentAllocationStates("cash").associateBy { it.expenseId }
            assertEquals(4_000L, final.getValue("bill-a").amountPaise)
            assertEquals(2_000L, final.getValue("bill-b").amountPaise)
            assertEquals(false, initial.getValue("bill-a").fingerprint == final.getValue("bill-a").fingerprint)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.correctAllocation(initial.getValue("bill-a").allocationId, initial.getValue("bill-a").fingerprint, 500, null, "stale-aba") } }
            assertEquals(6_000L, repository.summary("project").first().paidPaise)
        } finally { repository.close() }
    }

    @Test fun plannedAuditOwnerEnvelopePreventsCrossProjectIdCollisions() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, context)
        try {
            repository.saveProject(ProjectInfo("project-a", "A"))
            repository.saveProject(ProjectInfo("project-b", "B"))
            repository.saveClassification(ClassificationInfo("class-a", "project-a", "A stage", "STAGE"))
            repository.saveClassification(ClassificationInfo("class-b", "project-b", "B stage", "STAGE"))
            repository.saveClassification(ClassificationInfo("class-b-extra", "project-b", "B other stage", "STAGE"))
            repository.saveCommitment(CommitmentInput(id = "shared", projectId = "project-a", title = "A agreement", amountPaise = 100, date = "2026-01-01", classificationIds = listOf("class-a")))
            repository.saveInstalment(InstalmentInput(id = "shared", projectId = "project-b", title = "B schedule", amountPaise = 100, dueDate = "2026-01-02", classificationIds = listOf("class-b")))
            val sql = database.openHelper.writableDatabase
            sql.execSQL("INSERT INTO planned_classifications(id,classificationId,commitmentId,instalmentId) VALUES('shared','class-b-extra',NULL,'shared')")
            sql.execSQL("INSERT INTO audit_records(id,recordType,recordId,action,beforeJson,afterJson,createdAt) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>("legacy-ambiguous", "planned_classifications", "shared", "REPLACE", "{\"rows\":[]}", "{\"rows\":[]}", 2L))
            sql.execSQL("INSERT INTO audit_records(id,recordType,recordId,action,beforeJson,afterJson,createdAt) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>("actual-child", "planned_classifications", "shared", "CREATE", "null", "null", 3L))
            fun exported(projectId: String): List<org.json.JSONObject> {
                val audits = mutableListOf<org.json.JSONObject>()
                runBlocking { repository.forEachExportRow(projectId) { table, row -> if (table == "audit_records") audits += row } }
                return audits
            }
            val a = exported("project-a")
            val b = exported("project-b")
            fun plannedReplacement(rows: List<org.json.JSONObject>) = rows.filter { it.getString("recordType") == "planned_classifications" && it.getString("action") == "REPLACE" }
            assertEquals(listOf("COMMITMENT"), plannedReplacement(a).map { org.json.JSONObject(it.getString("afterJson")).getString("targetType") })
            assertEquals(listOf("INSTALMENT"), plannedReplacement(b).map { org.json.JSONObject(it.getString("afterJson")).getString("targetType") })
            assertEquals(false, a.any { it.getString("id") == "legacy-ambiguous" || it.getString("id") == "actual-child" })
            assertEquals(false, b.any { it.getString("id") == "legacy-ambiguous" })
            assertEquals(true, b.any { it.getString("id") == "actual-child" })
        } finally { repository.close() }
    }

    @Test fun streamingRejectsAttachmentCyclesAndOversizedTokens() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val file = File.createTempFile("ledger-invalid-", ".json", context.cacheDir)
        try {
            repository.saveProject(ProjectInfo("project", "Quoted \"project\""))
            repository.insertAttachment(AttachmentInfo("document-a", "project", "A", "text/plain", 1, "a".repeat(64), "a.bin", 1))
            repository.insertAttachment(AttachmentInfo("document-b", "project", "B", "text/plain", 1, "b".repeat(64), "b.bin", 1))
            repository.exportRecordsTo(file)
            assertEquals(2, repository.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION).counts.getValue("attachments"))
            val cyclic = org.json.JSONObject(file.readText())
            val documents = cyclic.getJSONArray("attachments")
            documents.getJSONObject(0).put("parentId", "document-b")
            documents.getJSONObject(1).put("parentId", "document-a")
            file.writeText(cyclic.toString())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION) } }
            assertEquals(2, repository.exportRecords().getJSONArray("attachments").length())
            documents.getJSONObject(1).put("parentId", org.json.JSONObject.NULL)
            documents.getJSONObject(0).put("parentId", "document-a")
            file.writeText(cyclic.toString())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION) } }
            file.writeText("{\"projects\":[{\"id\":\"${"x".repeat(1_000_001)}\"}]}")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION) } }
            file.writeText("{\"${"x".repeat(1_000_001)}\":[]}")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION) } }
            file.writeText("{\"projects\":[{\"id\":${"9".repeat(33)}}]}")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION) } }
        } finally { repository.close(); file.delete() }
    }

    @Test fun streamingSnapshotValidatesOnDiskAndRestoresAtomically() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = LedgerRepository.inMemory(context)
        val restored = LedgerRepository.inMemory(context)
        val file = File.createTempFile("ledger-snapshot-", ".json", context.cacheDir)
        val damaged = File.createTempFile("ledger-damaged-", ".json", context.cacheDir)
        try {
            source.saveProject(ProjectInfo("project", "Build"))
            source.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Invoice", amountPaise = 100_000, date = "2026-01-01"))
            source.savePayment(PaymentInput(id = "payment", projectId = "project", amountPaise = 25_000, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("invoice", 25_000))))
            val exported = source.exportRecordsTo(file)
            assertEquals(18, exported.counts.size)
            assertEquals(1, exported.counts.getValue("expenses"))
            assertEquals(exported, restored.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION))
            restored.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
            assertEquals(100_000L, restored.summary("project").first().recordedPaise)
            assertEquals(25_000L, restored.summary("project").first().paidPaise)
            assertEquals(75_000L, restored.summary("project").first().outstandingPaise)
            damaged.writeText(file.readText().replace("\"projects\":", "\"projects\":[],\"projects\":"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { restored.replaceRecordsFile(damaged, LedgerRepository.SNAPSHOT_VERSION) } }
            assertEquals(100_000L, restored.summary("project").first().recordedPaise)
        } finally { source.close(); restored.close(); file.delete(); damaged.delete() }
    }

    @Test fun streamingVersionOneCountsRemainOriginalAndProjectRowsStayScoped() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, context)
        val file = File.createTempFile("ledger-v1-", ".json", context.cacheDir)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveProject(ProjectInfo("other", "Other"))
            repository.saveProject(ProjectInfo("invoice", "ID collision in another table"))
            repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Invoice", amountPaise = 500, date = "2026-01-01", lines = listOf(ExpenseLineInput(id = "line", description = "Material", amountPaise = 500))))
            repository.saveExpense(ExpenseInput(id = "line", projectId = "other", description = "Other invoice", amountPaise = 100, date = "2026-01-01", lines = listOf(ExpenseLineInput(id = "invoice", description = "Other material", amountPaise = 100))))
            repository.insertAttachment(AttachmentInfo("document-v1", "project", "Old drawing", "application/pdf", 1, "a".repeat(64), "old.pdf", 1, category = "Drawing"))
            database.openHelper.writableDatabase.execSQL("INSERT INTO audit_records(id,recordType,recordId,action,beforeJson,afterJson,createdAt) VALUES(?,?,?,?,?,?,?)",
                arrayOf<Any?>("child-audit", "expense_lines", "invoice", "CREATE", "null", "null", 2L))
            val old = repository.exportRecords().apply {
                remove("planned_classifications")
                getJSONArray("expenses").also { rows -> for (index in 0 until rows.length()) rows.getJSONObject(index).remove("invoiceNumber") }
                getJSONArray("reminders").also { rows -> for (index in 0 until rows.length()) rows.getJSONObject(index).remove("leadDays") }
                getJSONArray("expense_lines").also { lines ->
                    for (index in 0 until lines.length()) lines.getJSONObject(index).remove("nature")
                }
                getJSONArray("attachments").also { attachments ->
                    for (index in 0 until attachments.length()) {
                        val attachment = attachments.getJSONObject(index)
                        listOf("category", "status", "revisionLabel", "work", "place", "notes").forEach { attachment.remove(it) }
                    }
                }
            }
            file.writeText(old.toString())
            val info = repository.inspectRecordsFile(file, 1)
            assertEquals(17, info.counts.size)
            assertEquals(false, info.counts.containsKey("planned_classifications"))
            assertEquals("", info.attachments.single().category)
            val seen = mutableListOf<Pair<String, org.json.JSONObject>>()
            repository.forEachExportRow("project") { table, row -> seen += table to row }
            assertEquals(listOf("project"), seen.filter { it.first == "projects" }.map { it.second.getString("id") })
            assertEquals(true, seen.single { it.first == "expenses" }.second.getBoolean("_hasLines"))
            assertEquals(false, seen.single { it.first == "expense_lines" }.second.getBoolean("_voided"))
            assertEquals(0L, seen.single { it.first == "expenses" }.second.getLong("_settledPaise"))
            assertEquals(true, seen.any { it.first == "audit_records" && it.second.getString("recordType") == "expense_lines" && it.second.getString("recordId") == "invoice" })
            assertEquals(false, seen.any { it.first == "audit_records" && it.second.getString("recordType") == "projects" && it.second.getString("recordId") == "invoice" })
            assertEquals(false, seen.any { it.first == "audit_records" && it.second.getString("recordType") == "expense_lines" && it.second.getString("recordId") == "line" && it.second.getString("action") == "REPLACE" })
            assertEquals(false, seen.any { it.first == "audit_records" && it.second.getString("id") == "child-audit" })
            val other = mutableListOf<Pair<String, org.json.JSONObject>>()
            repository.forEachExportRow("other") { table, row -> other += table to row }
            assertEquals(false, other.any { it.first == "audit_records" && it.second.getString("recordType") == "expense_lines" && it.second.getString("recordId") == "invoice" && it.second.getString("action") == "REPLACE" })
            assertEquals(true, other.any { it.first == "audit_records" && it.second.getString("id") == "child-audit" })
        } finally { repository.close(); file.delete() }
    }

    @Test fun costNatureUsesEachPostedLineOnceAndExcludesVoidedExpenses() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Mixed invoice", amountPaise = 100_000, date = "2026-01-01", lines = listOf(
                ExpenseLineInput(id = "material", description = "Cement", amountPaise = 60_000, nature = "Material"),
                ExpenseLineInput(id = "labour", description = "Mason", amountPaise = 40_000, nature = "Labour")
            )))
            repository.saveExpense(ExpenseInput(id = "plain", projectId = "project", description = "Unclassified bill", amountPaise = 5_000, date = "2026-01-02"))
            assertEquals(mapOf("Material" to 60_000L, "Labour" to 40_000L, "Unclassified / Legacy" to 5_000L), repository.costNature("project").first().associate { it.nature to it.amountPaise })
            assertEquals("Material", repository.expense("invoice")?.lines?.first { it.id == "material" }?.nature)
            repository.voidExpense("invoice", 0)
            assertEquals(mapOf("Unclassified / Legacy" to 5_000L), repository.costNature("project").first().associate { it.nature to it.amountPaise })
        } finally { repository.close() }
    }

    @Test fun plannedClassificationAndBatchScheduleEditsKeepForecastSeparate() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.seedClassifications("project", listOf(
                ClassificationInfo("stage", "project", "Foundation", "STAGE"),
                ClassificationInfo("child", "project", "Footing", "SUBSTAGE", "stage")
            ))
            val first = InstalmentInput(id = "first", projectId = "project", title = "First", amountPaise = 5_000, dueDate = "2026-02-01", classificationIds = listOf("child"))
            val second = InstalmentInput(id = "second", projectId = "project", title = "Second", amountPaise = 5_000, dueDate = "2026-03-01")
            val commitment = CommitmentInput(id = "agreement", projectId = "project", title = "Foundation", amountPaise = 10_000, date = "2026-01-01", schedules = listOf(first, second), classificationIds = listOf("stage"))
            repository.saveCommitment(commitment)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveInstalment(first.copy(amountPaise = 7_000, commitmentId = "agreement", revision = 0)) } }
            repository.saveCommitment(commitment.copy(revision = 0, classificationIds = null, schedules = listOf(
                first.copy(amountPaise = 7_000, revision = 0, classificationIds = null), second.copy(amountPaise = 3_000, revision = 0, classificationIds = listOf("stage"))
            )))
            assertEquals(listOf("stage"), repository.plannedClassifications("COMMITMENT", "agreement"))
            assertEquals(listOf("child"), repository.plannedClassifications("INSTALMENT", "first"))
            assertEquals(3, repository.plannedInsights("project", "stage").first().size)
            assertEquals(10_000L, repository.plannedInsights("project", "stage").first().single { it.type == "COMMITMENT" }.amountPaise)
            assertEquals("second", repository.activity("project", limit = 1, fromDate = "2026-02-01", toDate = "2026-03-01", includeExpenses = false).first().single().id)
            assertEquals("first", repository.activity("project", limit = 1, offset = 1, fromDate = "2026-02-01", toDate = "2026-03-01", includeExpenses = false).first().single().id)
            assertEquals(0L, repository.summary("project").first().recordedPaise)
            assertEquals(0L, repository.summary("project").first().paidPaise)
            repository.saveProject(ProjectInfo("other", "Other build"))
            repository.saveClassification(ClassificationInfo("foreign", "other", "Other stage", "STAGE"))
            val invalid = repository.exportRecords()
            invalid.getJSONArray("planned_classifications").getJSONObject(0).put("classificationId", "foreign")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replaceRecords(invalid) } }
            assertEquals(listOf("stage"), repository.plannedClassifications("COMMITMENT", "agreement"))
        } finally { repository.close() }
    }

    @Test fun plannedInsightsShowRemainingScheduleBalanceAndHideSettledOrCancelledRows() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveCommitment(CommitmentInput(id = "agreement", projectId = "project", title = "Agreement", amountPaise = 40_000, date = "2026-01-01", schedules = listOf(
                InstalmentInput(id = "partial", projectId = "project", title = "Partial", amountPaise = 10_000, dueDate = "2026-02-01"),
                InstalmentInput(id = "settled", projectId = "project", title = "Settled", amountPaise = 10_000, dueDate = "2026-02-02"),
                InstalmentInput(id = "cancelled", projectId = "project", title = "Cancelled", amountPaise = 10_000, dueDate = "2026-02-03"),
                InstalmentInput(id = "remaining", projectId = "project", title = "Remaining", amountPaise = 10_000, dueDate = "2026-02-04")
            )))
            repository.savePayment(PaymentInput(id = "partial-payment", projectId = "project", amountPaise = 4_000, date = "2026-01-02", instalmentId = "partial"))
            repository.savePayment(PaymentInput(id = "full-payment", projectId = "project", amountPaise = 10_000, date = "2026-01-02", instalmentId = "settled"))
            repository.cancelInstalment("cancelled", 0)
            val rows = repository.plannedInsights("project").first()
            assertEquals(6_000L, rows.single { it.id == "partial" }.amountPaise)
            assertEquals(setOf("partial", "remaining", "agreement"), rows.map { it.id }.toSet())
            assertEquals(40_000L, rows.single { it.type == "COMMITMENT" }.amountPaise)
        } finally { repository.close() }
    }

    @Test fun calendarDayCountsTrackEventsAndStayProjectScoped() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveProject(ProjectInfo("other", "Other"))
            repository.saveExpense(ExpenseInput(id = "expense", projectId = "project", description = "Cost", amountPaise = 100, date = "2026-03-04"))
            repository.saveInstalment(InstalmentInput(id = "plan", projectId = "project", title = "Due", amountPaise = 1_000, dueDate = "2026-03-04"))
            repository.saveExpense(ExpenseInput(id = "foreign", projectId = "other", description = "Foreign", amountPaise = 100, date = "2026-03-04"))
            assertEquals(2, repository.calendarDayCounts("project", "2026-03-01", "2026-03-31").first().single().eventCount)
            repository.savePayment(PaymentInput(id = "payment", projectId = "project", amountPaise = 500, date = "2026-03-04", instalmentId = "plan"))
            assertEquals(3, repository.calendarDayCounts("project", "2026-03-01", "2026-03-31").first().single().eventCount)
        } finally { repository.close() }
    }

    @Test fun versionOneSnapshotUpgradeKeepsInputUnchanged() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Invoice", amountPaise = 500, date = "2026-01-01", lines = listOf(ExpenseLineInput(id = "line", description = "Material", amountPaise = 500))))
            repository.insertAttachment(AttachmentInfo("old-document", "project", "Drawing", "application/pdf", 1, "a".repeat(64), "old.pdf", 1, category = "Drawing"))
            val old = repository.exportRecords().apply {
                remove("planned_classifications")
                getJSONArray("expenses").also { rows -> for (index in 0 until rows.length()) rows.getJSONObject(index).remove("invoiceNumber") }
                getJSONArray("reminders").also { rows -> for (index in 0 until rows.length()) rows.getJSONObject(index).remove("leadDays") }
                getJSONArray("expense_lines").getJSONObject(0).remove("nature")
                getJSONArray("attachments").getJSONObject(0).also { attachment ->
                    listOf("category", "status", "revisionLabel", "work", "place", "notes").forEach { attachment.remove(it) }
                }
            }
            val upgraded = LedgerRepository.upgradeRecordsV1(old)
            assertEquals(false, old.has("planned_classifications"))
            assertEquals(false, old.getJSONArray("expense_lines").getJSONObject(0).has("nature"))
            assertEquals("", upgraded.getJSONArray("expense_lines").getJSONObject(0).getString("nature"))
            assertEquals(false, old.getJSONArray("attachments").getJSONObject(0).has("category"))
            assertEquals("", upgraded.getJSONArray("attachments").getJSONObject(0).getString("category"))
            repository.validateRecords(upgraded)
        } finally { repository.close() }
    }

    @Test fun exactDirectAndCalculatedFixtures() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "direct", projectId = "project", description = "Direct", amountPaise = 1_850_000, date = "2026-01-01"))
            val fixtures = listOf(
                Triple("quantity-whole", DetailedAmount("10", "250"), 250_000L),
                Triple("quantity-decimal", DetailedAmount("2.5", "125.40"), 31_350L),
                Triple("half-up", DetailedAmount("2.5", "100.25"), 25_063L),
                Triple("discount", DetailedAmount("10", "1000", discountPercent = "10"), 900_000L),
                Triple("tax-transport", DetailedAmount("10", "1000", discountPercent = "10", taxPercent = "18", transport = "500"), 1_112_000L)
            )
            fixtures.forEach { (id, calculation, expected) ->
                assertEquals(expected, Money.calculate(calculation).totalPaise)
                repository.saveExpense(ExpenseInput(id = id, projectId = "project", description = id, amountPaise = expected, date = "2026-01-01", mode = "CALCULATED", calculation = calculation))
                assertEquals(calculation, repository.expense(id)?.calculation)
            }
            assertEquals(1_850_000L, repository.expense("direct")?.amountPaise)
            assertEquals(4_168_413L, repository.summary("project").first().recordedPaise)
            assertEquals(0L, repository.summary("project").first().paidPaise)
        } finally { repository.close() }
    }

    @Test fun partialFullAndFutureScheduleDoNotDoubleCountCost() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Invoice", amountPaise = 1_000_000, date = "2026-01-01"))
            repository.saveInstalment(InstalmentInput(id = "future", projectId = "project", title = "Next phase", amountPaise = 2_500_000, dueDate = LocalDate.now().plusDays(5).toString()))
            assertEquals(1_000_000L, repository.summary("project").first().recordedPaise)
            assertEquals(0L, repository.summary("project").first().paidPaise)
            assertEquals(2_500_000L, repository.summary("project").first().upcomingPaise)
            val first = PaymentInput(id = "first", idempotencyKey = "first-key", projectId = "project", amountPaise = 400_000, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("invoice", 400_000)))
            assertEquals("first", repository.savePayment(first))
            assertEquals("first", repository.savePayment(first))
            assertEquals(600_000L, repository.summary("project").first().outstandingPaise)
            assertEquals(400_000L, repository.summary("project").first().paidPaise)
            assertEquals(0L, repository.summary("project").first().advancePaise)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.savePayment(first.copy(amountPaise = 500_000)) } }
            repository.savePayment(PaymentInput(id = "second", projectId = "project", amountPaise = 600_000, date = "2026-01-03", allocations = listOf(PaymentAllocationInput("invoice", 600_000))))
            assertEquals(0L, repository.summary("project").first().outstandingPaise)
            assertEquals(1_000_000L, repository.summary("project").first().paidPaise)
            assertEquals(1_000_000L, repository.summary("project").first().recordedPaise)
        } finally { repository.close() }
    }

    @Test fun reversalAndRefundRestoreOnlyOriginalSettlements() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Invoice", amountPaise = 1_000_000, date = "2026-01-01"))
            repository.savePayment(PaymentInput(id = "wrong", projectId = "project", amountPaise = 400_000, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("invoice", 400_000))))
            val reversalId = repository.reversePayment("wrong", "Wrong transfer", "2026-01-03")
            assertEquals("REVERSAL", repository.payments("project").first().single { it.id == reversalId }.kind)
            assertEquals(1_000_000L, repository.summary("project").first().outstandingPaise)
            assertEquals(0L, repository.summary("project").first().paidPaise)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.reversePayment("wrong", "Again", "2026-01-04") } }
            repository.savePayment(PaymentInput(id = "correct", projectId = "project", amountPaise = 600_000, date = "2026-01-04", allocations = listOf(PaymentAllocationInput("invoice", 600_000))))
            repository.refundPayment("correct", 200_000, "Goods returned", "2026-01-05")
            assertEquals(600_000L, repository.summary("project").first().outstandingPaise)
            assertEquals(400_000L, repository.summary("project").first().paidPaise)
            assertEquals(0L, repository.summary("project").first().advancePaise)
        } finally { repository.close() }
    }

    @Test fun revisionAndInvalidAllocationRollBack() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveProject(ProjectInfo("other", "Other build"))
            val initial = ExpenseInput(id = "invoice", projectId = "project", description = "Invoice", amountPaise = 100_000, date = "2026-01-01")
            repository.saveExpense(initial)
            repository.saveExpense(initial.copy(amountPaise = 120_000, revision = 0))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveExpense(initial.copy(amountPaise = 200_000, revision = 0)) } }
            assertEquals(120_000L, repository.expense("invoice")?.amountPaise)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repository.savePayment(PaymentInput(id = "too-large", projectId = "project", amountPaise = 130_000, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("invoice", 130_000))))
            } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repository.savePayment(PaymentInput(id = "wrong-project", projectId = "other", amountPaise = 10_000, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("invoice", 10_000))))
            } }
            assertEquals(0, repository.payments("project").first().size)
            assertEquals(0L, repository.summary("project").first().paidPaise)
            assertEquals(120_000L, repository.summary("project").first().outstandingPaise)
        } finally { repository.close() }
    }

    @Test fun exportedRecordsRestoreIdenticalFinancialTotals() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = LedgerRepository.inMemory(context)
        val restored = LedgerRepository.inMemory(context)
        try {
            source.saveProject(ProjectInfo("project", "Build"))
            source.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Invoice", amountPaise = 1_000_000, date = "2026-01-01"))
            source.savePayment(PaymentInput(id = "payment", projectId = "project", amountPaise = 400_000, date = "2026-01-02", allocations = listOf(PaymentAllocationInput("invoice", 400_000))))
            source.saveInstalment(InstalmentInput(id = "future", projectId = "project", title = "Next phase", amountPaise = 2_500_000, dueDate = LocalDate.now().plusDays(5).toString()))
            val records = source.exportRecords()
            restored.replaceRecords(records)
            assertEquals(1_000_000L, restored.summary("project").first().recordedPaise)
            assertEquals(400_000L, restored.summary("project").first().paidPaise)
            assertEquals(600_000L, restored.summary("project").first().outstandingPaise)
            assertEquals(0L, restored.summary("project").first().advancePaise)
            assertEquals(2_500_000L, restored.summary("project").first().upcomingPaise)
            assertEquals(source.summary("project").first(), restored.summary("project").first())
            val copy = restored.exportRecords()
            LedgerRepository.TABLES.forEach { assertEquals(it, records.getJSONArray(it).length(), copy.getJSONArray(it).length()) }
        } finally { source.close(); restored.close() }
    }

    @Test fun allocationRetryAndInvalidRestoreKeepBalances() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput(id = "expense", projectId = "project", description = "Invoice", amountPaise = 1000, date = "2026-01-01"))
            repository.savePayment(PaymentInput(id = "payment", projectId = "project", amountPaise = 600, date = "2026-01-02"))
            repository.allocateAdvance("payment", "expense", 400, "allocation-one")
            repository.allocateAdvance("payment", "expense", 400, "allocation-one")
            assertEquals(1, repository.projectAudits("project").first().count { it.id == "allocation:allocation-one" })
            assertEquals(true, repository.journal("project", ExpenseFilter()).first().any { it.event.id == "allocation:allocation-one" })
            assertEquals(600L, repository.summary("project").first().outstandingPaise)
            assertEquals(200L, repository.summary("project").first().advancePaise)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.allocateAdvance("payment", "expense", 100, "allocation-one") }
            }
            val damaged = repository.exportRecords()
            damaged.getJSONArray("expenses").getJSONObject(0).put("amountPaise", 300)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replaceRecords(damaged) } }
            assertEquals(600L, repository.summary("project").first().outstandingPaise)
            repository.reversePayment("payment", "Duplicate bank transfer", "2026-01-03")
            assertEquals(1000L, repository.summary("project").first().outstandingPaise)
            assertEquals(0L, repository.summary("project").first().paidPaise)
        } finally { repository.close() }
    }

    @Test fun parentRollupCountsDirectAndChildAmountsOnce() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.seedClassifications("project", listOf(
                ClassificationInfo("child", "project", "Footing", "SUBSTAGE", "parent"),
                ClassificationInfo("parent", "project", "Foundation", "STAGE")
            ))
            repository.saveExpense(ExpenseInput(id = "expense", projectId = "project", description = "Concrete", amountPaise = 1000, date = "2026-01-01", classifications = listOf(
                ClassificationPart("parent", "WORK", 600), ClassificationPart("child", "WORK", 400)
            )))
            assertEquals(1000L, repository.insights("project", dimension = "WORK").first().single { it.id == "parent" }.amountPaise)
            assertEquals(400L, repository.insights("project", parentId = "parent", dimension = "WORK").first().single { it.id == "child" }.amountPaise)
        } finally { repository.close() }
    }
}
