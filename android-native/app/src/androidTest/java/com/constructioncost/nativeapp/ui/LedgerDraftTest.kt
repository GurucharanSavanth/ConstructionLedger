package com.constructioncost.nativeapp.ui

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerDraftTest {
    @Test fun invoiceChargesApportionOnceAndRepeatedEditingDoesNotDrift() {
        val first = LedgerViewModel.withLineClasses(ExpenseLineInput("material", "Material", 700000), mapOf("WORK" to "foundation"))
        val second = LedgerViewModel.withLineClasses(ExpenseLineInput("labour", "Labour", 300000), mapOf("WORK" to "walls"))
        val draft = EntryDraft.fresh("project").with("description", "Construction invoice").with("amount", "10000")
            .with("invoiceLines", org.json.JSONArray(listOf(first, second).map(LedgerViewModel::lineJson)).toString())
            .with("invoiceCharges", "true").with("discountPercent", "10").with("taxPercent", "18").with("transport", "500")
        val invoice = LedgerViewModel.expense(draft)
        assertEquals(1112000L, invoice.amountPaise)
        assertEquals(listOf(778400L, 333600L), invoice.lines.map { it.amountPaise })
        assertEquals(listOf(ClassificationPart("foundation", "WORK", 778400), ClassificationPart("walls", "WORK", 333600)), invoice.classifications)
        assertEquals("10000.00", invoice.calculation!!.rate)
        var edited = invoice.copy(revision = 0)
        repeat(5) { edited = LedgerViewModel.expense(LedgerViewModel.draftForExpense(edited).with("note", "Checked")) }
        assertEquals(invoice.amountPaise, edited.amountPaise)
        assertEquals(invoice.lines.map { it.amountPaise }, edited.lines.map { it.amountPaise })
        assertEquals(invoice.classifications, edited.classifications)
        val direct = EntryDraft.fresh("project").with("description", "Cement").with("amount", "18500").with("taxPercent", "18")
        assertEquals(1850000L, LedgerViewModel.expense(direct).amountPaise)
    }

    @Test fun invoiceLineClassificationsReconcileAndKeepLegacyEvidence() {
        val first = LedgerViewModel.withLineClasses(ExpenseLineInput(id = "a", description = "Cement", amountPaise = 6000, legacyJson = "{\"original\":\"receipt\"}"), mapOf("WORK" to "foundation", "COST" to "materials"))
        val second = LedgerViewModel.withLineClasses(ExpenseLineInput(id = "b", description = "Mason", amountPaise = 4000), mapOf("WORK" to "walls", "COST" to "labour"))
        assertEquals("receipt", org.json.JSONObject(first.legacyJson).getString("original"))
        val draft = EntryDraft.fresh("project").with("description", "Mixed invoice").with("amount", "100")
            .with("PLACE", "ground-floor").with("invoiceLines", org.json.JSONArray(listOf(first, second).map(LedgerViewModel::lineJson)).toString())
        val expense = LedgerViewModel.expense(draft)
        assertEquals(10000L, expense.amountPaise)
        assertEquals(listOf(ClassificationPart("foundation", "WORK", 6000), ClassificationPart("walls", "WORK", 4000),
            ClassificationPart("materials", "COST", 6000), ClassificationPart("labour", "COST", 4000), ClassificationPart("ground-floor", "PLACE", 10000)), expense.classifications)
        val unclassified = second.copy(legacyJson = "")
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { LedgerViewModel.invoiceClassifications(listOf(first, unclassified), EntryDraft()) }
    }

    @Test fun savingARunningWorkTimerKeepsItsDurationAndNotes() {
        val draft = EntryDraft(mapOf("timerStarted" to "10000", "timerSeconds" to "7", "note" to "Brick work"))
        val finished = LedgerViewModel.finishTimer(draft, 15500)
        assertEquals("12", finished.get("timerSeconds"))
        assertEquals("", finished.get("timerStarted"))
        assertEquals("Brick work\nWork duration: 12 seconds", finished.get("note"))
        assertEquals(finished, LedgerViewModel.finishTimer(finished, 20000))
    }

    @Test fun insightSharesUseIntegerPaiseAndHandleAnEmptyLedger() {
        assertEquals("12.5", insightSharePercent(125, 1_000))
        assertEquals("0", insightSharePercent(0, 0))
        assertEquals("50", insightSharePercent(Long.MAX_VALUE / 2, Long.MAX_VALUE - 1))
    }

    @Test fun commitmentEditKeepsInstalmentIdsRevisionsAndPaidHistory() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveClassification(ClassificationInfo("stage", "project", "Foundation", "STAGE"))
            repository.saveCommitment(CommitmentInput("agreement", "project", "Masonry", 100_000, "2026-01-01",
                schedules = listOf(
                    InstalmentInput("part-1", "project", "First", 50_000, "2026-02-01", "agreement"),
                    InstalmentInput("part-2", "project", "Second", 50_000, "2026-03-01", "agreement")
                ), classificationIds = listOf("stage")))
            repository.savePayment(PaymentInput("cash", projectId = "project", amountPaise = 20_000,
                date = "2026-01-15", instalmentId = "part-1"))
            val original = repository.commitments("project").first().single()
            val plans = repository.instalments("project").first()
            val draft = LedgerViewModel.draftForCommitment(original, plans,
                repository.plannedClassifications("COMMITMENT", "agreement"), repository.classifications("project").first())
            val titleEdit = LedgerViewModel.commitment(draft.with("description", "Masonry phase"))
            assertTrue(titleEdit.schedules.isEmpty())
            assertEquals(null, titleEdit.classificationIds)
            repository.saveCommitment(titleEdit)
            assertEquals(listOf("stage"), repository.plannedClassifications("COMMITMENT", "agreement"))
            assertEquals(listOf("part-1", "part-2"), repository.instalments("project").first().map { it.id })
            assertEquals(plans.map { it.revision }, repository.instalments("project").first().map { it.revision })

            // A schedule change must carry the existing IDs and optimistic revisions.
            val schedule = org.json.JSONArray(draft.get("originalSchedules"))
            schedule.getJSONObject(0).put("date", "2026-02-05")
            val dateEdit = LedgerViewModel.commitment(draft.with("revision", "1").with("schedules", schedule.toString()))
            assertEquals("part-1", dateEdit.schedules.first().id)
            assertEquals(plans.first().revision, dateEdit.schedules.first().revision)
            repository.saveCommitment(dateEdit)
            val after = repository.instalments("project").first().single { it.id == "part-1" }
            assertEquals("2026-02-05", after.dueDate)
            assertEquals(20_000L, after.paidPaise)
            assertEquals("part-1", repository.payments("project").first().single().instalmentId)
        } finally { repository.close() }
    }

    @Test fun restoredProjectSelectionRetainsAndExplicitlyMovesDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val preferences = TestPreferences()
        val models = androidx.lifecycle.ViewModelStore()
        try {
            repository.saveProject(ProjectInfo("before", "Before"))
            repository.saveProject(ProjectInfo("after", "After"))
            val old = EntryDraft.fresh("before").with("description", "Cement").with("amount", "18500")
                .with("vendor", "old-vendor").with("attachments", "old-file")
            val model = LedgerViewModel(context, repository,
                SavedStateHandle(mapOf("project" to "before", "draft" to old.json())), preferences.store)
            models.put("draft", model)
            withTimeout(5_000) { model.state.first { it.ready && it.projectId == "before" } }
            val records = repository.exportRecords()
            val projects = records.getJSONArray("projects")
            (projects.length() - 1 downTo 0).forEach { index ->
                if (projects.getJSONObject(index).getString("id") == "before") projects.remove(index)
            }
            repository.replaceRecords(records)
            val restored = withTimeout(5_000) { model.state.first { it.projectId == "after" } }
            assertEquals("before", restored.draft.projectId)
            assertEquals("Cement", restored.draft.get("description"))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.reassignDraftToCurrentProject() }
            val moved = model.state.value.draft
            assertEquals("after", moved.projectId)
            assertEquals("Cement", moved.get("description"))
            assertEquals("18500", moved.get("amount"))
            assertEquals("", moved.get("vendor"))
            assertEquals("", moved.get("attachments"))
            assertNotEquals(old.id, moved.id)
            assertNotEquals(old.get("key"), moved.get("key"))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            preferences.close()
            repository.close()
        }
    }

    @Test fun expenseEditKeepsCalculationAndRescalesClassification() {
        val original = ExpenseInput("cost", projectId = "project", description = "Steel", amountPaise = 10_000,
            date = "2026-01-01", mode = "CALCULATED", revision = 1,
            calculation = DetailedAmount("2", "50"),
            classifications = listOf(ClassificationPart("head", "COST", 10_000)))
        val draft = LedgerViewModel.draftForExpense(original)
        assertEquals("2", draft.get("quantity"))
        val edited = LedgerViewModel.expense(draft.with("quantity", "4"))
        assertEquals(20_000L, edited.amountPaise)
        assertEquals(20_000L, edited.classifications.single().amountPaise)
        assertEquals("4", edited.calculation?.quantity)
        val material = ExpenseLineInput(description = "Steel", amountPaise = 10_000, nature = "Material")
        assertEquals("Material", LedgerViewModel.readLines(org.json.JSONArray(listOf(LedgerViewModel.lineJson(material))).toString()).single().nature)
    }

    @Test fun calculatedNoteEditAndDirectAmountEditPersist() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveExpense(ExpenseInput("calculated", projectId = "project", description = "Stone",
                amountPaise = 10_000, date = "2026-01-01", mode = "CALCULATED",
                calculation = DetailedAmount("2", "50")))
            val calculated = repository.expense("calculated")!!
            repository.saveExpense(LedgerViewModel.expense(LedgerViewModel.draftForExpense(calculated).with("note", "Verified invoice")))
            val afterNote = repository.expense("calculated")!!
            assertEquals(10_000L, afterNote.amountPaise)
            assertEquals("CALCULATED", afterNote.mode)
            assertEquals("Verified invoice", afterNote.note)
            assertEquals(calculated.calculation, afterNote.calculation)

            repository.saveExpense(ExpenseInput("direct", projectId = "project", description = "Fasteners",
                amountPaise = 18_500, date = "2026-01-01"))
            val direct = repository.expense("direct")!!
            repository.saveExpense(LedgerViewModel.expense(LedgerViewModel.draftForExpense(direct).with("amount", "200")))
            assertEquals(20_000L, repository.expense("direct")!!.amountPaise)
        } finally { repository.close() }
    }
}
