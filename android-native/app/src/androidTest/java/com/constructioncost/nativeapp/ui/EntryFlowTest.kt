package com.constructioncost.nativeapp.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ExpenseFilter
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryFlowTest {
    @get:Rule(order = 0) val preferences = TestPreferencesRule()
    private var testRepository: LedgerRepository? = null
    @get:Rule(order = 1) val databaseCleanup = object : ExternalResource() {
        override fun after() { testRepository?.close(); testRepository = null }
    }
    @get:Rule(order = 2) val compose = createComposeRule()

    private fun chooseBoughtMaterials() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Choose what happened. Only the relevant fields will appear.", substring = false).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Bought materials", substring = false))
        compose.onNodeWithText("Bought materials", substring = false).performClick()
    }

    @Test fun directExpensePostsOnceFromThreeFields() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context).also { testRepository = it }
        runBlocking { repository.ensureDefaultProject() }
        val actions = UiActions(
            importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
            renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {}, restoreBackup = {},
            importLegacy = {}, exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {}
        )
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add Entry").performClick()
        chooseBoughtMaterials()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Material / item") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Material / item").performTextInput("Cement bags")
        compose.onNodeWithText("Final amount (₹)").performTextInput("18500")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Review and save"))
        compose.onNodeWithText("Review and save").performClick()
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(30_000) { runBlocking { repository.expenses("default-project", ExpenseFilter()).first().size == 1 } }
        val rows = runBlocking { repository.expenses("default-project").first() }
        assertEquals(1, rows.size)
        assertEquals(1_850_000L, rows.single().amountPaise)
    }

    @Test fun categoryCanBeCreatedFromPickerAndFoundBySearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context).also { testRepository = it }
        runBlocking { repository.ensureDefaultProject() }
        val actions = UiActions(
            importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
            renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {}, restoreBackup = {},
            importLegacy = {}, exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {}
        )
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add Entry").performClick()
        chooseBoughtMaterials()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription("Cost head: None").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Cost head: None").performScrollTo().performClick()
        compose.onNodeWithText("Create category…").performClick()
        compose.onNodeWithText("Category name").performTextInput("Pool equipment")
        compose.onNodeWithText("Create and select").performClick()
        compose.waitUntil(30_000) {
            runBlocking { repository.classifications("default-project").first().any { it.name == "Pool equipment" } }
        }
        compose.onNodeWithText("Find a category").performScrollTo().performTextInput("Pool equipment")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Pool equipment").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { repository.classifications("default-project").first().any { it.name == "Pool equipment" } })
    }
}
