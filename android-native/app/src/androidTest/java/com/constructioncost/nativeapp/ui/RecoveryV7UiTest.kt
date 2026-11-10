package com.constructioncost.nativeapp.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RecoveryV7UiTest {
    private var repository: LedgerRepository? = null
    @get:Rule(order = 0) val cleanup = object : ExternalResource() {
        override fun after() { repository?.close() }
    }
    @get:Rule(order = 1) val compose = createComposeRule()

    private class SwitchablePreferences : DataStore<Preferences> {
        val failReads = AtomicBoolean(true)
        val failWrites = AtomicBoolean(true)
        val writeAttempts = AtomicInteger()
        @Volatile var saved: Preferences = preferencesOf()
        override val data: Flow<Preferences> = flow {
            if (failReads.get()) throw IOException("Synthetic preference read failure")
            emit(saved)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            writeAttempts.incrementAndGet()
            if (failWrites.get()) throw IOException("Synthetic preference write failure")
            return transform(saved).also { saved = it }
        }
    }

    @Test fun recoveryActionsAndInstructionsRemainReachableAtTwoHundredPercentFontWithoutLedger() {
        val retries = AtomicInteger()
        val diagnostics = AtomicInteger()
        val restores = AtomicInteger()
        var restoredPassword: String? = "not-called"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                WebTheme {
                    RecoveryScreen(LedgerRecoveryState.DATABASE_CORRUPTION,
                        "Disposable fixture: ledger and preferences are unavailable.",
                        { retries.incrementAndGet() },
                        { restoredPassword = it; restores.incrementAndGet() },
                        { diagnostics.incrementAndGet() })
                }
            }
        }
        compose.onNodeWithText("Recovery instructions").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Export diagnostic information").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Select backup to restore").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, retries.get())
            assertEquals(1, diagnostics.get())
            assertEquals(1, restores.get())
            assertEquals(null, restoredPassword)
        }
    }

    @Test fun homeShowsPreferenceFailureAndRetryPersistsCurrentDraftOnlyAfterSuccessfulWrite() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ledger = LedgerRepository.inMemory(context).also { repository = it }
        runBlocking { ledger.saveProject(ProjectInfo("v7-preferences", "Preference recovery fixture")) }
        val preferences = SwitchablePreferences()
        val actions = UiActions(importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
            renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {}, restoreBackup = {},
            importLegacy = {}, exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {})
        lateinit var model: LedgerViewModel
        compose.setContent {
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            BuildLedgerApp(ledger, actions, preferences)
            SideEffect { model = ViewModelProvider(owner)[LedgerViewModel::class.java] }
        }
        compose.waitUntil(10_000) {
            model.state.value.ready && compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Project overview").assertIsDisplayed()
        compose.onNodeWithText("Retry saving preferences").assertIsDisplayed()
        compose.runOnIdle {
            model.field("description", "Retained unsaved invoice")
            model.field("amount", "123.45")
            model.field("invoiceNumber", "V7-RETRY")
            model.field("attachments", "preserved-original")
            model.settings(showArchived = true)
        }
        compose.waitUntil(5_000) { preferences.writeAttempts.get() > 0 && model.state.value.error.orEmpty().contains("not saved") }
        val retained = model.state.value.draft
        compose.onNodeWithText("Retry saving preferences").performClick()
        compose.waitUntil(5_000) { model.state.value.error.orEmpty().startsWith("Draft and settings remain visible but were not saved.") }
        compose.onNodeWithText("Retry saving preferences").assertIsDisplayed()
        assertEquals(retained, model.state.value.draft)
        assertEquals(null, preferences.saved[stringPreferencesKey("entry_draft")])

        preferences.failReads.set(false)
        preferences.failWrites.set(false)
        compose.onNodeWithText("Retry saving preferences").performClick()
        compose.waitUntil(5_000) { model.state.value.recoveryState == LedgerRecoveryState.HEALTHY_EXISTING_DATABASE }
        compose.onNodeWithText("Retry saving preferences").assertDoesNotExist()
        assertEquals(retained, model.state.value.draft)
        assertEquals(retained, EntryDraft.read(checkNotNull(preferences.saved[stringPreferencesKey("entry_draft")])))
        assertEquals("v7-preferences", preferences.saved[stringPreferencesKey("project")])
        assertTrue(JSONObject(checkNotNull(preferences.saved[stringPreferencesKey("settings")])).getBoolean("showArchived"))
        assertFalse(model.state.value.recoveryRequired)
        assertTrue(runBlocking { ledger.expenses("v7-preferences").first().isEmpty() })
        compose.onNodeWithText("Project overview").assertIsDisplayed()
    }

    @Test fun backupRestoreClosesDialogBeforeLaunchingDocumentPicker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ledger = LedgerRepository.inMemory(context).also { repository = it }
        runBlocking { ledger.saveProject(ProjectInfo("v7-backup-route", "Backup route fixture")) }
        val preferences = SwitchablePreferences().apply { failReads.set(false); failWrites.set(false) }
        val restores = AtomicInteger()
        var restoredPassword: String? = "not-called"
        val actions = UiActions(importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
            renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {},
            restoreBackup = { restoredPassword = it; restores.incrementAndGet() }, importLegacy = {},
            exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {})

        compose.setContent { BuildLedgerApp(ledger, actions, preferences) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Backup and restore").performClick()
        compose.onNodeWithText("Backup & Restore").assertIsDisplayed()
        compose.onNodeWithText("Select backup to restore").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            restores.get() == 1 && compose.onAllNodesWithText("Backup & Restore").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, restores.get())
        assertEquals(null, restoredPassword)
        compose.onNodeWithText("Project overview").assertIsDisplayed()
        compose.onNodeWithText("Replace local ledger?").assertDoesNotExist()
    }
}
