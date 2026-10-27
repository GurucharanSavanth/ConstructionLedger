package com.constructioncost.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.domain.VendorInfo
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.CommitmentInput
import com.constructioncost.nativeapp.domain.InstalmentInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.platform.UiActions
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.time.LocalDate

class VendorNavigationTest {
    @get:Rule(order = 0) val preferences = TestPreferencesRule()
    private lateinit var repository: LedgerRepository
    @get:Rule(order = 1) val cleanup = object : ExternalResource() {
        override fun after() { if (::repository.isInitialized) repository.close() }
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<ComponentActivity>()
    private val actions = UiActions(importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
        renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {}, restoreBackup = {},
        importLegacy = {}, exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {})

    private fun openSettings() { compose.onNodeWithContentDescription("Project settings").performClick() }
    private fun openProjects() {
        if(compose.onAllNodesWithText("Project settings",substring=false).fetchSemanticsNodes().isEmpty()) openSettings()
        compose.onNodeWithText("▸ Android settings").performScrollTo().performClick()
        compose.onNodeWithText("Manage projects").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Projects", substring = false).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openSuppliers() {
        openSettings()
        compose.onNodeWithText("▸ Android settings").performScrollTo().performClick()
        compose.onNodeWithText("Manage suppliers").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Vendors", substring = false).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1
        }
    }

    @Test fun projectManagerCreatesSwitchesAndCancelsDeletionInAnEmptyLedger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = LedgerRepository.inMemory(context)
        LedgerRecoveryStateStore(context).markLedgerValidated()
        val store = AttachmentStore(context, repository)
        val projectActions = actions.copy(deleteProject = { projectId -> runBlocking { store.deleteProject(projectId) } })
        compose.setContent { BuildLedgerApp(repository, projectActions, preferences.preferences.store) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(CREATE_PROJECT_MESSAGE, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(CREATE_PROJECT_MESSAGE, substring = false).assertExists()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextInput("North house")
        compose.onNodeWithText("Create Project", substring = false).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Select project. Current project: North house").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("Select project. Current project: North house").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Projects", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add project", substring = true).performClick()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst().performTextInput("South house")
        compose.onNodeWithText("Save project", substring = false).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Select project. Current project: South house").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Select project. Current project: South house").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Projects", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("North house", substring = false).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Select project. Current project: North house").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("Select project. Current project: North house").performClick()
        compose.onAllNodesWithText("Delete", substring = false).onFirst().performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete project permanently?", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export Project as Excel", substring = false).assertExists()
        compose.onNodeWithText("Type Yes to confirm deletion", substring = false).assertExists()
        compose.onNodeWithText("Delete Permanently", substring = false).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextInput("No")
        compose.onNodeWithText("Delete Permanently", substring = false).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextReplacement("Yes")
        compose.onNodeWithText("Delete Permanently", substring = false).assertIsEnabled()
        compose.onNodeWithText("No", substring = false).performClick()
        compose.onNodeWithText("Delete project permanently?", substring = false).assertDoesNotExist()
        compose.onNodeWithContentDescription("Select project. Current project: North house").assertExists()

        val projects = runBlocking { repository.projects.first() }
        assertEquals(listOf("North house", "South house"), projects.map { it.name })
        val expenses = runBlocking { projects.map { repository.expenses(it.id).first() } }
        assertTrue(expenses.all { it.isEmpty() })

        compose.onAllNodesWithText("Delete", substring = false).onFirst().performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete project permanently?", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextInput("Yes")
        compose.onNodeWithText("Delete Permanently", substring = false).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Select project. Current project: South house").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("South house"), runBlocking { repository.projects.first() }.map { it.name })

        compose.onAllNodesWithText("Delete", substring = false).onFirst().performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Delete project permanently?", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).onLast().performTextInput("Yes")
        compose.onNodeWithText("Delete Permanently", substring = false).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(CREATE_PROJECT_MESSAGE, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { repository.projects.first() }.isEmpty())
        compose.onNodeWithContentDescription("Select project. Current project: No project selected").assertExists()
    }

    @Test fun vendorFilterDoesNotHideTheNextProjectsExpensesAfterNavigation() {
        repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            repository.ensureDefaultProject()
            repository.saveProject(ProjectInfo("other-house", "Other house"))
            repository.saveVendor(VendorInfo("original-vendor", "default-project", "Original supplier", ""))
            repository.saveExpense(ExpenseInput(projectId = "default-project", description = "First house cement",
                amountPaise = 10_000, date = "2026-01-01", vendorId = "original-vendor"))
            repository.saveExpense(ExpenseInput(projectId = "other-house", description = "Other house bricks",
                amountPaise = 20_000, date = "2026-01-02"))
        }
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Recorded cost").fetchSemanticsNodes().isNotEmpty() }
        openSuppliers()
        compose.onNodeWithText("Original supplier", substring = false).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Showing 1 records").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("No matching activity").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("First house cement"))
        compose.onNodeWithText("First house cement").assertExists()
        openProjects()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Other house", substring = false))
        compose.onNodeWithText("Other house", substring = false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Other house").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Showing ", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Other house bricks"))
        compose.onNodeWithText("Other house bricks").assertExists()
        compose.onNodeWithText("First house cement").assertDoesNotExist()
    }

    @Test fun projectSettingsDraftDoesNotOverwriteAnotherProjectAfterNavigation() {
        repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            repository.ensureDefaultProject()
            repository.saveProject(ProjectInfo("other-house", "Other house", budgetPaise = 25_000))
        }
        val original = runBlocking { repository.projects.first().single { it.id == "default-project" } }
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Recorded cost").fetchSemanticsNodes().isNotEmpty() }
        openSettings()
        compose.onNodeWithText("Project name", substring = false).performTextReplacement("Unsaved first house")
        compose.onNodeWithText("Project budget (₹)").performTextReplacement("1234.56")
        openProjects()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Other house", substring = false))
        compose.onNodeWithText("Other house", substring = false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Other house").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithContentDescription("Back").performClick()
        openSettings()
        compose.onNodeWithText("Project name", substring = false).assertTextContains("Other house")
        compose.onNodeWithText("Project budget (₹)").assertTextContains("250.00")
        compose.onNodeWithText("Save project settings").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { repository.projects.first().single { it.id == "other-house" }.revision > 0 } }
        val other = runBlocking { repository.projects.first().single { it.id == "other-house" } }
        assertEquals("Other house", other.name)
        assertEquals(25_000L, other.budgetPaise)
        assertEquals(original, runBlocking { repository.projects.first().single { it.id == original.id } })
    }

    @Test fun vendorEditDoesNotFollowNavigationIntoAnotherProject() {
        repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            repository.ensureDefaultProject()
            repository.saveProject(ProjectInfo("other-house", "Other house"))
            repository.saveVendor(VendorInfo("original-vendor", "default-project", "Original supplier", "Original note"))
        }
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Recorded cost").fetchSemanticsNodes().isNotEmpty() }
        openSuppliers()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Edit Original supplier"))
        compose.onNodeWithText("Edit Original supplier").performClick()
        compose.onNodeWithText("▸ Edit vendor").performScrollTo().performClick()
        compose.onNodeWithText("Vendor name").performTextReplacement("Unsaved supplier")
        openProjects()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Other house", substring = false))
        compose.onNodeWithText("Other house", substring = false).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Other house").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("▸ Add vendor").assertExists()
        compose.onNodeWithText("▸ Add vendor").performClick()
        compose.onNodeWithText("Vendor name").assertTextEquals("Vendor name", "")
        compose.onNodeWithText("Notes").assertTextEquals("Notes", "")
        compose.onNodeWithText("Vendor name").performTextReplacement("Other supplier")
        compose.onNodeWithText("Save vendor").performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { repository.vendors("other-house").first().size == 1 } }
        assertEquals("Other supplier", runBlocking { repository.vendors("other-house").first().single().name })
        assertEquals("Original supplier", runBlocking { repository.vendors("default-project").first().single().name })
        assertEquals("Original note", runBlocking { repository.vendors("default-project").first().single().note })
    }

    @Test fun relatedPlansAndCommitmentsOpenTheirDetailsFromInsights() {
        repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            repository.ensureDefaultProject()
            repository.saveCommitment(CommitmentInput(id = "agreement", projectId = "default-project", title = "Foundation agreement", amountPaise = 10_000, date = "2026-01-01",
                schedules = listOf(InstalmentInput(id = "schedule", projectId = "default-project", title = "Foundation milestone", amountPaise = 10_000, dueDate = "2026-02-01"))))
        }
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Recorded cost").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Explore").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Cost explorer", substring = false).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Planned payments and agreements"))
        compose.onNodeWithText("Planned payments and agreements").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Payments", substring = false).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Foundation milestone"))
        compose.onNodeWithText("Foundation milestone").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Edit schedule").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit schedule").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("Foundation milestone").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Foundation agreement"))
        compose.onNodeWithText("Foundation agreement").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Archive commitment").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Archive commitment").assertExists()
    }

    @Test fun calendarMarksEventDaysAndLabelsFullyPaidInstalments() {
        repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        val today = LocalDate.now().toString()
        runBlocking {
            repository.ensureDefaultProject()
            repository.saveExpense(ExpenseInput(id = "today-cost", projectId = "default-project", description = "Today cost", amountPaise = 100, date = today))
            repository.saveInstalment(InstalmentInput(id = "today-plan", projectId = "default-project", title = "Today schedule", amountPaise = 1_000, dueDate = today))
            repository.savePayment(PaymentInput(id = "today-payment", projectId = "default-project", amountPaise = 1_000, date = today, instalmentId = "today-plan"))
        }
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Recorded cost").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Payment calendar").performClick()
        val dayDescription = "$today, 3 events, selected"
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(dayDescription)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription(dayDescription)).assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Today schedule"))
        compose.onNodeWithText("Cost explorer").assertIsNotDisplayed()
        compose.onNodeWithText("Completed instalment").assertExists()
        compose.onNodeWithText("Today schedule").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Edit schedule").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Edit schedule").assertExists()
    }

    @Test fun calendarHeaderScrollsAndRemindersRemainReachableAtLargeFontInShortWindow() {
        repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking { repository.ensureDefaultProject() }
        var reminderRequests = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.height(480.dp)) {
                    BuildLedgerApp(repository, actions.copy(setReminders = { if (it) reminderRequests++ }), preferences.preferences.store)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Payment calendar").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Cost explorer").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Upcoming scheduled payments"))
        compose.onNodeWithText("Cost explorer").assertIsNotDisplayed()
        compose.onNodeWithText("Enable reminders").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, reminderRequests) }
    }
}
