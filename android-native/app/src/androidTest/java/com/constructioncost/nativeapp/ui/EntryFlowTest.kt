package com.constructioncost.nativeapp.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ExpenseFilter
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        compose.onNodeWithText("Add Entry").performClick()
        compose.onNodeWithText("Description").performTextInput("Cement bags")
        compose.onNodeWithText("Final amount (₹)").performTextInput("18500")
        compose.onNodeWithText("Review and save").performClick()
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(5_000) { runBlocking { repository.expenses("default-project", ExpenseFilter()).first().size == 1 } }
        val rows = runBlocking { repository.expenses("default-project").first() }
        assertEquals(1, rows.size)
        assertEquals(1_850_000L, rows.single().amountPaise)
    }
}
