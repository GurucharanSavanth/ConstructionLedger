package com.constructioncost.nativeapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseScreenTest {
    @get:Rule(order = 0) val preferences = TestPreferencesRule()
    private lateinit var repository: LedgerRepository
    private val models = ViewModelStore()
    @get:Rule(order = 1) val cleanup = object : ExternalResource() {
        override fun after() { models.clear(); if (::repository.isInitialized) repository.close() }
    }
    @get:Rule(order = 2) val compose = createComposeRule()
    private val actions = UiActions(importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
        renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {}, restoreBackup = {},
        importLegacy = {}, exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {})

    private fun model(): LedgerViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = LedgerRepository.inMemory(context)
        runBlocking { repository.ensureDefaultProject() }
        lateinit var vm: LedgerViewModel
        compose.runOnUiThread {
            vm = LedgerViewModel(context, repository, SavedStateHandle(), preferences.preferences.store)
            models.put("test", vm)
        }
        compose.waitUntil(5_000) { vm.state.value.ready }
        return vm
    }

    @Test fun reminderToggleWaitsForPlatformPermissionResult() {
        val vm = model()
        val requests = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { SettingsScreen(vm, actions.copy(setReminders = { requests += it })) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Android settings", substring = true))
        compose.onNodeWithText("Android settings", substring = true).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Local schedule reminders"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Local schedule reminders").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Local schedule reminders").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(true), requests)
            assertEquals(false, vm.state.value.reminders)
        }
    }

    @Test fun projectSettingsKeepUnsavedFieldsAfterRestorationAndResetOnProjectSwitch() {
        val vm = model()
        val original = runBlocking { repository.projects.first().single() }
        runBlocking { repository.saveProject(ProjectInfo("other", "Other project", budgetPaise = 25_000)) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { SettingsScreen(vm, actions) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Project name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Project name").performTextReplacement("Unsaved house name")
        compose.onNodeWithText("Project budget (₹)").performTextReplacement("1234.56")

        restoration.emulateSavedInstanceStateRestore()

        compose.waitUntil(10_000) { compose.onAllNodesWithText("Unsaved house name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Project name").assertTextContains("Unsaved house name")
        compose.onNodeWithText("Project budget (₹)").assertTextContains("1234.56")
        assertEquals(original, runBlocking { repository.projects.first().single { it.id == original.id } })
        compose.onNodeWithText("Save project settings").performClick()
        compose.waitUntil(5_000) {
            runBlocking { repository.projects.first().single { it.id == original.id }.name == "Unsaved house name" }
        }
        assertEquals(123_456L, runBlocking { repository.projects.first().single { it.id == original.id }.budgetPaise })

        compose.onNodeWithText("Project name").performTextReplacement("Discard on project switch")
        compose.runOnUiThread { vm.selectProject("other") }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Other project").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Project name").assertTextContains("Other project")
        compose.onNodeWithText("Project budget (₹)").assertTextContains("250.00")
        compose.runOnUiThread { vm.selectProject(original.id) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Unsaved house name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Project name").assertTextContains("Unsaved house name")
        compose.onNodeWithText("Project budget (₹)").assertTextContains("1234.56")
    }

    @Test fun settingsDraftCannotOverwriteSameProjectRestoredWithNewerRevision() {
        val vm = model()
        val project = runBlocking { repository.projects.first().single() }
        runBlocking {
            repository.saveExpense(ExpenseInput(id = "restored-cost", projectId = project.id,
                description = "Preserved invoice", amountPaise = 125_050, date = "2026-10-26"))
            repository.savePayment(PaymentInput(id = "restored-payment", projectId = project.id,
                amountPaise = 50_025, date = "2026-10-26", allocations = listOf(PaymentAllocationInput("restored-cost", 50_025))))
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { SettingsScreen(vm, actions) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Project name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Project name").performTextReplacement("Stale house edit")
        compose.onNodeWithText("Project budget (₹)").performTextReplacement("1234.56")

        runBlocking {
            val restored = repository.exportRecords()
            restored.getJSONArray("projects").getJSONObject(0).put("name", "Restored house")
            restored.put("budgets", JSONArray().put(JSONObject().put("id", project.id)
                .put("projectId", project.id).put("amountPaise", 250_000)))
            repository.replaceRecords(restored)
        }
        val restored = runBlocking { repository.projects.first().single() }
        compose.waitUntil(5_000) { vm.state.value.projects.single().revision == restored.revision }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Project name").assertTextContains("Stale house edit")
        compose.onNodeWithText("Save project settings").performScrollTo().performClick()
        compose.waitUntil(5_000) { !vm.state.value.saving && vm.state.value.actionError != null }

        assertTrue(vm.state.value.actionError.orEmpty().contains("Revision conflict"))
        assertEquals(restored, runBlocking { repository.projects.first().single() })
        assertEquals("Restored house", restored.name)
        assertEquals(250_000L, restored.budgetPaise)
        val summary = runBlocking { repository.summary(project.id).first() }
        assertEquals(125_050L, summary.recordedPaise)
        assertEquals(50_025L, summary.paidPaise)
        assertEquals(75_025L, summary.outstandingPaise)
    }

    @Test fun projectManagerDraftKeepsExpectedRevisionAcrossExternalBudgetChangeAndRecreation() {
        val vm = model()
        val project = runBlocking { repository.projects.first().single() }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { ProjectsScreen(vm, actions) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Edit", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Edit project", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Project name", substring = false).performTextReplacement("Stale manager edit")
        compose.onNodeWithText("Budget (₹, optional)").performTextReplacement("1234.56")
        runBlocking { repository.saveProject(project.copy(name = "Current house", budgetPaise = 275_000)) }
        val updated = runBlocking { repository.projects.first().single() }
        compose.waitUntil(5_000) { vm.state.value.projects.single().revision == updated.revision }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Save project", substring = false).performScrollTo().performClick()
        compose.waitUntil(5_000) { !vm.state.value.saving && vm.state.value.actionError != null }

        assertTrue(vm.state.value.actionError.orEmpty().contains("Revision conflict"))
        assertEquals(updated, runBlocking { repository.projects.first().single() })
        assertEquals("Current house", updated.name)
        assertEquals(275_000L, updated.budgetPaise)
        assertEquals(0L, runBlocking { repository.summary(project.id).first().recordedPaise })
        assertEquals(0L, runBlocking { repository.summary(project.id).first().paidPaise })
    }

    @Test fun zeroBudgetShowsUnsetStateWithoutNullPercentage() {
        model()
        runBlocking { repository.saveProject(repository.projects.first().single().copy(budgetPaise = 0)) }
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("PROJECT BUDGET"))
        compose.onNodeWithText("Set your budget").assertExists()
        compose.onNodeWithText("null%", substring = true).assertDoesNotExist()
    }

    @Test fun paymentEditCancellationAndConfirmedCorrectionPreserveHistory() {
        val vm = model()
        runBlocking {
            repository.saveExpense(ExpenseInput("bill", projectId = "default-project", description = "Cement", amountPaise = 10_000, date = "2026-01-01"))
            repository.savePayment(PaymentInput("cash", projectId = "default-project", amountPaise = 4_000, date = "2026-01-01",
                allocations = listOf(PaymentAllocationInput("bill", 4_000))))
        }
        var replacement = ""
        compose.setContent { MaterialTheme { DetailScreen(vm, "cash", "PAYMENT", actions, {}, { id, _ -> replacement = id }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Edit payment").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit payment").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Payment amount (₹)") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Payment amount (₹)").performTextReplacement("60")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Payment amount (₹)").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(1, runBlocking { repository.payments("default-project").first().size }) }
        compose.onNodeWithText("Edit payment").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Payment amount (₹)") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Payment amount (₹)").performTextReplacement("60")
        compose.onNodeWithText("Reason for correction").performScrollTo().performTextReplacement("Corrected receipt")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Review correction") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Review correction").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Save payment correction").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Save payment correction").performClick()
        compose.waitUntil(5_000) { replacement.isNotBlank() }
        assertEquals(6_000L, runBlocking { repository.summary("default-project").first().paidPaise })
        assertEquals(4_000L, runBlocking { repository.summary("default-project").first().outstandingPaise })
        assertEquals(3, runBlocking { repository.payments("default-project").first().size })
    }

    @Test fun paymentCorrectionRestoresFieldsOnlyForItsOriginalFingerprint() {
        val vm = model()
        runBlocking { repository.savePayment(PaymentInput("correction", projectId = "default-project",
            amountPaise = 10_000, date = "2026-01-01")) }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { DetailScreen(vm, "correction", "PAYMENT", actions, {}, { _, _ -> }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Edit payment").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit payment").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Payment amount (₹)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Payment amount (₹)").performTextReplacement("120")
        compose.onNodeWithText("Reason for correction").performScrollTo().performTextReplacement("Corrected invoice reference")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Payment amount (₹)").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Payment amount (₹)").assertTextContains("120")
        compose.onNodeWithText("Reason for correction").performScrollTo().assertTextContains("Corrected invoice reference")
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Edit payment").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Payment amount (₹)").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { repository.saveProject(repository.projects.first().single().copy(name = "Updated while editing")) }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { vm.state.value.actionError.orEmpty().contains("Payment changed") }
        compose.onNodeWithText("Payment amount (₹)").assertDoesNotExist()
        assertEquals(10_000L, runBlocking { repository.payments("default-project").first().single().amountPaise })
    }

    @Test fun reservedPaymentIdOpensDetailAndRejectedActionIsVisibleOverIt() {
        model()
        runBlocking { repository.savePayment(PaymentInput("payment/%?#", projectId = "default-project",
            amountPaise = 10_000, date = "2026-01-01", reference = "Reserved payment route")) }
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Activity").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Activity").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Reserved payment route", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Reserved payment route", substring = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Payment details").fetchSemanticsNodes().isNotEmpty() }
        val modalList = hasScrollToNodeAction() and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { compose.onAllNodes(modalList).fetchSemanticsNodes().size == 1 }
        compose.onNode(modalList).performScrollToNode(hasText("Reverse payment"))
        compose.onNode(hasText("Reverse payment") and hasAnyAncestor(isDialog())).performClick()
        compose.onAllNodesWithText("Reverse payment").onLast().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Action failed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Action failed").assertIsDisplayed()
        compose.onAllNodesWithText("Enter a reason for the reversal.").onLast().assertIsDisplayed()
        assertEquals(10_000L, runBlocking { repository.payments("default-project").first().single().amountPaise })
    }

    @Test fun categorySourceClearsWhenProjectChanges() {
        val vm = model()
        runBlocking {
            repository.saveProject(ProjectInfo("other", "Other project"))
            repository.saveClassification(ClassificationInfo("original", "default-project", "Original category", "STAGE"))
            repository.saveClassification(ClassificationInfo("replacement", "other", "Other category", "STAGE"))
        }
        compose.setContent { MaterialTheme { CategoriesScreen(vm) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Find category").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Merge or reassign expenses", substring = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Source: None").performScrollTo().performClick()
        compose.onNode(hasText("Original category") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithContentDescription("Source: Original category").assertExists()
        compose.runOnUiThread { vm.selectProject("other") }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Source: Original category").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Merge or reassign expenses", substring = true))
        if (compose.onAllNodesWithContentDescription("Source: None").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText("Merge or reassign expenses", substring = true).performClick()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Source: None").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Source: None").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Target: None").assertExists()
    }

    @Test fun advanceCanSettleOldUnpaidCostBeyondLatest500Expenses() {
        val vm = model()
        runBlocking {
            repository.saveExpense(ExpenseInput(id = "old", projectId = "default-project", description = "Old unpaid bill", amountPaise = 10_000, date = "2020-01-01"))
            repeat(501) { index ->
                repository.saveExpense(ExpenseInput(id = "recent-$index", projectId = "default-project", description = "Paid bill $index", amountPaise = 100, paidPaise = 100, date = "2026-01-01"))
            }
            repository.savePayment(PaymentInput(id = "advance", projectId = "default-project", amountPaise = 10_000, date = "2026-01-02"))
        }
        compose.setContent { MaterialTheme { PaymentsScreen(vm, {}, { _, _ -> }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Apply an advance", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Apply an advance", substring = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Payment with available advance: None").performClick()
        compose.onNode(hasText("2026-01-02", substring = true) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Loading unpaid expenses").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Unpaid cost: None").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Old unpaid bill", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Old unpaid bill", substring = true).performClick()
        compose.onNodeWithText("Amount to apply (₹)").performScrollTo().performTextInput("100")
        compose.onNodeWithText("Apply advance").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            runBlocking { repository.expenses("default-project", ExpenseFilter(query = "Old unpaid bill")).first().single().outstandingPaise == 0L }
        }
        assertEquals(60_100L, runBlocking { repository.summary("default-project").first().paidPaise })
    }

    @Test fun advanceCostSelectionSurvivesSearchAndPagination() {
        val vm = model()
        runBlocking {
            repeat(76) { index ->
                repository.saveExpense(ExpenseInput(id = "bill-%03d".format(index), projectId = "default-project",
                    description = "Unpaid bill %03d".format(index), amountPaise = 100, date = "2026-01-01"))
            }
        }
        compose.setContent { MaterialTheme { PaymentsScreen(vm, {}, { _, _ -> }) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Apply an advance", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Apply an advance", substring = true).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Next expenses") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Next expenses").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Unpaid cost: None").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Unpaid bill 075", substring = true) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unpaid bill 075", substring = true).performClick()
        compose.onNodeWithText("Previous expenses").performScrollTo().performClick()
        compose.onNodeWithText("Search unpaid expenses").performScrollTo().performTextInput("Unpaid bill 000")
        compose.onNodeWithContentDescription("Unpaid cost: Unpaid bill 075", substring = true).assertExists()
        compose.onNodeWithContentDescription("Unpaid cost: Unpaid bill 075", substring = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Unpaid bill 000", substring = true) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("Unpaid bill 075", substring = true) and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("Unpaid bill 000", substring = true) and hasAnyAncestor(isDialog())).assertExists()
    }

    @Test fun archivedCommitmentDetailKeepsTitleAndSchedules() {
        val vm = model()
        runBlocking {
            repository.saveCommitment(CommitmentInput(id = "contract", projectId = "default-project", title = "Foundation contract",
                amountPaise = 10_000, date = "2026-01-01", schedules = listOf(InstalmentInput(id = "schedule",
                    projectId = "default-project", title = "Foundation instalment", amountPaise = 10_000, dueDate = "2026-02-01"))))
            repository.archiveCommitment("contract", 0)
        }
        compose.setContent { MaterialTheme { DetailScreen(vm, "contract", "COMMITMENT", actions, {}, { _, _ -> }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Foundation contract").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Foundation contract").assertExists()
        compose.onNodeWithText("Archived", substring = true).assertExists()
        compose.onNodeWithText("Foundation instalment").performScrollTo().assertExists()
        compose.onNodeWithText("Edit commitment").assertDoesNotExist()
        compose.onNodeWithText("Archive commitment").assertDoesNotExist()
    }

    @Test fun deleteBillRefreshesVisibleStatusAndActions() {
        val vm = model()
        runBlocking { repository.saveExpense(ExpenseInput(id = "cost", projectId = "default-project", description = "Cost to void",
            amountPaise = 10_000, date = "2026-01-01")) }
        compose.setContent { MaterialTheme { DetailScreen(vm, "cost", "EXPENSE", actions, {}, { _, _ -> }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete bill").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Delete bill").performScrollTo().performClick()
        compose.onNode(hasText("Delete bill") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { runBlocking { repository.expenseRow("cost")?.voided == true } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Voided", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit expense").assertDoesNotExist()
        compose.onNodeWithText("Delete bill").assertDoesNotExist()
        compose.onNodeWithText("Record payment of", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cost to void").assertExists()
    }
}
