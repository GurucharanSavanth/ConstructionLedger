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
import com.constructioncost.nativeapp.domain.ActivityNote
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkLogLifecycleTest {
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

    @Test fun deletedSourceKeepsEditedInputsThroughRestorationAndRejectedUiSave() = exercise(false)
    @Test fun restoredAwaySourceKeepsPersistedDraftThroughViewModelRestartAndRejectedUiSave() = exercise(true)

    private fun exercise(restore: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = LedgerRepository.inMemory(context)
        val original = ActivityNote("work-log", "project", "Site visit", "2026-11-11", "Original notes")
        val beforeNote = runBlocking {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.insertAttachment(AttachmentInfo("file", "project", "site.txt", "text/plain", 1, "a".repeat(64), "site.bin", 1))
            val snapshot = repository.exportRecords()
            repository.saveNote(original)
            repository.linkAttachment("file", noteId = original.id)
            snapshot
        }
        lateinit var vm: LedgerViewModel
        compose.runOnUiThread {
            vm = LedgerViewModel(context, repository, SavedStateHandle(), preferences.preferences.store)
            models.put("work-log", vm)
        }
        compose.waitUntil(10_000) { vm.state.value.ready }
        compose.runOnUiThread { vm.editNote(original) {} }
        val restoration = StateRestorationTester(compose)
        var successfulSaves = 0
        restoration.setContent { MaterialTheme { EntryScreen(vm, actions, { successfulSaves++ }, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Description").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Description").performTextReplacement("Retained site edit")
        val retained = vm.state.value.draft
        compose.waitUntil(10_000) {
            runBlocking { preferences.preferences.store.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("entry_draft")] == retained.json() }
        }
        runBlocking {
            if (restore) repository.replaceRecords(beforeNote) else repository.deleteNote(original.id, 0)
        }
        if (restore) {
            compose.runOnUiThread {
                models.clear()
                vm = LedgerViewModel(context, repository, SavedStateHandle(), preferences.preferences.store)
                models.put("restarted-work-log", vm)
            }
            compose.waitUntil(10_000) { vm.state.value.ready }
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Description").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Description").assertTextContains("Retained site edit")
        assertEquals(retained, vm.state.value.draft)
        val beforeSave = runBlocking { repository.exportRecords().toString() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Review changes"))
        compose.onNodeWithText("Review changes").performClick()
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(10_000) { !vm.state.value.saving && vm.state.value.actionError != null }
        assertEquals("Note changed or missing; reload this record", vm.state.value.actionError)
        compose.onNode(hasText("Note changed or missing; reload this record") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("Back to edit").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Description"))
        compose.onNodeWithText("Description").assertTextContains("Retained site edit")
        assertEquals(retained, vm.state.value.draft)
        assertEquals(0, successfulSaves)
        assertEquals(beforeSave, runBlocking { repository.exportRecords().toString() })
        assertEquals(0, runBlocking { repository.exportRecords().getJSONArray("activity_entries").length() })
        assertEquals(0, runBlocking { repository.exportRecords().getJSONArray("attachment_links").length() })
    }
}
