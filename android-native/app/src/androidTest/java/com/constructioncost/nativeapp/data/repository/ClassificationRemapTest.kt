package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.ui.EntryDraft
import com.constructioncost.nativeapp.ui.LedgerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClassificationRemapTest {
    private fun repository() = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)

    private suspend fun seed(repository: LedgerRepository) {
        repository.saveProject(ProjectInfo("project", "Build"))
        repository.saveClassification(ClassificationInfo("A", "project", "Materials", "COST_HEAD"))
        repository.saveClassification(ClassificationInfo("B", "project", "Labour", "COST_HEAD"))
    }

    private fun chargedInvoice(): ExpenseInput {
        val material = LedgerViewModel.withLineClasses(ExpenseLineInput("material", "Material", 700000,
            legacyJson = "{\"originalCategoryId\":\"A\",\"original\":{\"amount\":7000}}"), mapOf("COST" to "A"))
        val labour = LedgerViewModel.withLineClasses(ExpenseLineInput("labour", "Labour", 300000), mapOf("COST" to "B"))
        return LedgerViewModel.expense(EntryDraft.fresh("project").with("id", "bill").with("key", "bill")
            .with("description", "Charged invoice").with("amount", "10000")
            .with("invoiceLines", JSONArray(listOf(material, labour).map(LedgerViewModel::lineJson)).toString())
            .with("invoiceCharges", "true").with("discountPercent", "10").with("taxPercent", "18").with("transport", "500"))
    }

    @Test fun chargedInvoiceMergePreservesSharesCashEvidenceAndRejectsStaleEditing() = runBlocking<Unit> {
        val repository = repository()
        try {
            seed(repository)
            repository.saveExpense(chargedInvoice())
            repository.savePayment(PaymentInput(id = "cash", projectId = "project", amountPaise = 400000,
                date = "2026-01-01", allocations = listOf(PaymentAllocationInput("bill", 400000))))
            val before = repository.expense("bill")!!
            val cash = repository.payments("project").first()
            val snapshot = repository.exportRecords()
            assertEquals(1112000L, before.amountPaise)
            assertEquals(listOf(778400L, 333600L), before.lines.map { it.amountPaise }.sortedDescending())

            repository.mergeClassification("A", "B")

            val merged = repository.expense("bill")!!
            assertEquals(1L, merged.revision)
            assertEquals(before.lines.associate { it.id to it.amountPaise }, merged.lines.associate { it.id to it.amountPaise })
            assertEquals(before.calculation, merged.calculation)
            assertEquals(listOf(ClassificationPart("B", "COST", 1112000)), merged.classifications)
            assertEquals(cash, repository.payments("project").first())
            assertEquals(1112000L, repository.summary("project").first().recordedPaise)
            assertEquals(400000L, repository.summary("project").first().paidPaise)
            assertEquals(712000L, repository.summary("project").first().outstandingPaise)
            assertEquals(snapshot.getJSONArray("payment_allocations").toString(), repository.exportRecords().getJSONArray("payment_allocations").toString())
            val material = merged.lines.single { it.id == "material" }
            val evidence = JSONObject(material.legacyJson)
            assertEquals("A", evidence.getString("originalCategoryId"))
            assertEquals(7000, evidence.getJSONObject("original").getInt("amount"))
            assertEquals(700000L, evidence.getLong("_nativeInvoiceBasePaise"))
            assertEquals("B", evidence.getJSONObject("_nativeClassifications").getString("COST"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveExpense(before.copy(note = "Stale")) } }

            val reopened = LedgerViewModel.expense(LedgerViewModel.draftForExpense(merged).with("note", "Reviewed"))
            assertEquals(1112000L, reopened.amountPaise)
            assertEquals(listOf(ClassificationPart("B", "COST", 1112000)), reopened.classifications)
            repository.saveExpense(reopened)
            assertEquals(2L, repository.expense("bill")!!.revision)
            assertEquals(cash, repository.payments("project").first())
            val audits = repository.exportRecords().getJSONArray("audit_records")
            val deleted = (0 until audits.length()).map(audits::getJSONObject).single {
                it.getString("recordType") == "classification_allocations" && it.getString("action") == "DELETE"
            }
            val removed = JSONObject(deleted.getString("beforeJson"))
            assertEquals("A", removed.getString("classificationId"))
            assertEquals("bill", removed.getString("expenseId"))
            assertEquals(778400L, removed.getLong("amountPaise"))
            val maps = repository.audits("A", "classifications", "project").first().filter { it.action == "MAP" }
            assertEquals(778400L, JSONObject(maps.single().beforeJson).getLong("amountPaise"))
        } finally { repository.close() }
    }

    @Test fun mergeDeduplicatesForecastClassificationsAndBumpsEachOwnerOnlyOnce() = runBlocking<Unit> {
        val repository = repository()
        try {
            seed(repository)
            repository.saveCommitment(CommitmentInput(id = "agreement", projectId = "project", title = "Forecast",
                amountPaise = 2500000, date = "2026-01-01", classificationIds = listOf("A", "B"), schedules = listOf(
                    InstalmentInput(id = "first", projectId = "project", title = "First", amountPaise = 1000000,
                        dueDate = "2026-02-01", classificationIds = listOf("A", "B")),
                    InstalmentInput(id = "second", projectId = "project", title = "Second", amountPaise = 1500000,
                        dueDate = "2026-03-01", classificationIds = listOf("A")))))
            val schedules = repository.instalments("project").first()
            repository.mergeClassification("A", "B")
            assertEquals(listOf("B"), repository.plannedClassifications("COMMITMENT", "agreement"))
            assertEquals(listOf("B"), repository.plannedClassifications("INSTALMENT", "first"))
            assertEquals(listOf("B"), repository.plannedClassifications("INSTALMENT", "second"))
            assertEquals(1L, repository.commitments("project").first().single().revision)
            assertEquals(schedules.map { it.copy(revision = 1) }, repository.instalments("project").first())
            assertEquals(2500000L, repository.summary("project").first().commitmentPaise)
            assertEquals(0L, repository.summary("project").first().recordedPaise)
            assertEquals(0L, repository.summary("project").first().paidPaise)
            assertEquals(3, repository.plannedInsights("project", "B").first().size)
            assertEquals(0, repository.plannedInsights("project", "A").first().size)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repository.saveInstalment(InstalmentInput(id = "first", projectId = "project", title = "Stale", amountPaise = 1000000,
                    dueDate = "2026-02-01", commitmentId = "agreement", revision = 0))
            } }
        } finally { repository.close() }
    }

    @Test fun reassignChangesOnlyRecordedCostsAndLeavesPlannedClassificationsAndSourceActive() = runBlocking<Unit> {
        val repository = repository()
        try {
            seed(repository)
            repository.saveExpense(chargedInvoice())
            repository.saveCommitment(CommitmentInput(id = "agreement", projectId = "project", title = "Forecast",
                amountPaise = 2500000, date = "2026-01-01", classificationIds = listOf("A")))
            repository.saveInstalment(InstalmentInput(id = "future", projectId = "project", title = "Future",
                amountPaise = 2500000, dueDate = "2026-02-01", classificationIds = listOf("A")))
            val before = repository.summary("project").first()
            repository.reassignClassification("A", "B")
            assertEquals(before, repository.summary("project").first())
            assertEquals(listOf(ClassificationPart("B", "COST", 1112000)), repository.expense("bill")!!.classifications)
            assertEquals(1L, repository.expense("bill")!!.revision)
            assertEquals("B", LedgerViewModel.lineClasses(repository.expense("bill")!!.lines.single { it.id == "material" })["COST"])
            assertEquals(listOf("A"), repository.plannedClassifications("COMMITMENT", "agreement"))
            assertEquals(listOf("A"), repository.plannedClassifications("INSTALMENT", "future"))
            assertEquals(0L, repository.commitments("project").first().single().revision)
            assertEquals(0L, repository.instalments("project").first().single().revision)
            assertFalse(repository.classifications("project").first().single { it.id == "A" }.archived)
        } finally { repository.close() }
    }

    @Test fun parentMergeAndArchivedTargetRejectWithoutAnyPartialWritesButParentReassignWorks() = runBlocking<Unit> {
        val repository = repository()
        try {
            seed(repository)
            repository.saveClassification(ClassificationInfo("child", "project", "Archived child", "COST_SUBHEAD", parentId = "A", archived = true))
            repository.saveClassification(ClassificationInfo("archived", "project", "Archived target", "COST_HEAD", archived = true))
            repository.saveExpense(ExpenseInput(id = "direct", projectId = "project", description = "Parent direct cost", amountPaise = 1850000,
                date = "2026-01-01", classifications = listOf(ClassificationPart("A", "COST", 1850000))))
            val before = repository.exportRecords().toString()
            val parentFailure = assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.mergeClassification("A", "B") } }
            assertTrue(parentFailure.message!!.contains("Reparent"))
            assertEquals(before, repository.exportRecords().toString())
            listOf(true, false).forEach { merge ->
                val failure = assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    if (merge) repository.mergeClassification("B", "archived") else repository.reassignClassification("B", "archived")
                } }
                assertEquals("Target classification is archived", failure.message)
                assertEquals(before, repository.exportRecords().toString())
            }
            repository.reassignClassification("A", "B")
            assertEquals(listOf(ClassificationPart("B", "COST", 1850000)), repository.expense("direct")!!.classifications)
            assertEquals("A", repository.classifications("project").first().single { it.id == "child" }.parentId)
            assertFalse(repository.classifications("project").first().single { it.id == "A" }.archived)
        } finally { repository.close() }
    }

    @Test fun boundedBatchesUpdateEveryRecordAndRepairLineOnlyReferencesWithEscapedIds() = runBlocking<Unit> {
        val repository = repository()
        try {
            seed(repository)
            val escaped = "Quoted\"source"
            repository.saveClassification(ClassificationInfo(escaped, "project", "Quoted source", "COST_HEAD"))
            repeat(65) { index ->
                val line = LedgerViewModel.withLineClasses(ExpenseLineInput("line-$index", "Line", 100), mapOf("COST" to "A"))
                repository.saveExpense(ExpenseInput(id = "bill-$index", projectId = "project", description = "Bill $index", amountPaise = 100,
                    date = "2026-01-01", lines = listOf(line), classifications = listOf(ClassificationPart("A", "COST", 100))))
            }
            val staleLine = LedgerViewModel.withLineClasses(ExpenseLineInput("line-only", "Prior remap", 100,
                legacyJson = "{\"evidence\":\"Quoted\\\"source\"}"), mapOf("COST" to escaped))
            repository.saveExpense(ExpenseInput(id = "line-only", projectId = "project", description = "Line only", amountPaise = 100,
                date = "2026-01-01", lines = listOf(staleLine), classifications = listOf(ClassificationPart("B", "COST", 100))))
            repository.mergeClassification("A", "B")
            repeat(65) { index ->
                val expense = repository.expense("bill-$index")!!
                assertEquals(1L, expense.revision)
                assertEquals(listOf(ClassificationPart("B", "COST", 100)), expense.classifications)
                assertEquals("B", LedgerViewModel.lineClasses(expense.lines.single())["COST"])
            }
            assertEquals(0L, repository.expense("line-only")!!.revision)
            repository.mergeClassification(escaped, "B")
            val repaired = repository.expense("line-only")!!
            assertEquals(1L, repaired.revision)
            assertEquals("B", LedgerViewModel.lineClasses(repaired.lines.single())["COST"])
            assertEquals(escaped, JSONObject(repaired.lines.single().legacyJson).getString("evidence"))
            assertEquals(6600L, repository.summary("project").first().recordedPaise)
            assertEquals(0L, repository.summary("project").first().paidPaise)
        } finally { repository.close() }
    }
}
