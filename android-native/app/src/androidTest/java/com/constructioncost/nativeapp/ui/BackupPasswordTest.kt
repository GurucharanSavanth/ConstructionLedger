package com.constructioncost.nativeapp.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPasswordTest {
    private val models = ViewModelStore()
    private var repositoryToClose: LedgerRepository? = null
    @get:Rule(order = 0) val preferences = TestPreferencesRule()
    @get:Rule(order = 1) val resources = object : ExternalResource() {
        override fun after() { models.clear(); repositoryToClose?.close() }
    }
    @get:Rule(order = 2) val compose = createComposeRule()

    @Test fun legacyImportPassesEnteredPasswordAndClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        repositoryToClose = repository
        runBlocking { repository.ensureDefaultProject() }
        val captured = mutableListOf<String>()
        val actions = UiActions(
            importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
            renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {}, restoreBackup = {},
            importLegacy = { captured += it }, exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {}
        )
        val model = ViewModelProvider(models, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LedgerViewModel(context, repository, SavedStateHandle(mapOf("project" to "default-project")),
                    preferences.preferences.store) as T
        })[LedgerViewModel::class.java]
        compose.setContent { BackupScreen(model, actions) }
        fun chooseExport() {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Choose legacy export"))
            compose.onNodeWithText("Choose legacy export").performClick()
        }
        compose.onNodeWithTag("legacyRecoveryPassword").performTextInput("short")
        chooseExport()
        assertEquals(emptyList<String>(), captured)
        compose.onNodeWithText("Enter at least 10 characters.").assertExists()

        compose.onNodeWithTag("legacyRecoveryPassword").performTextClearance()
        compose.onNodeWithTag("legacyRecoveryPassword").performTextInput("correct-handoff")
        chooseExport()
        assertEquals(listOf("correct-handoff"), captured)
        assertNotEquals("default-project", captured.single())

        compose.onNodeWithTag("legacyRecoveryPassword").performTextInput("second-handoff")
        chooseExport()
        assertEquals(listOf("correct-handoff", "second-handoff"), captured)
    }
}
