package com.constructioncost.nativeapp.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LedgerPreferenceFailureTest {
    @Test fun explicitSettingsRepairReplacesMalformedJson() =
        verifyExplicitSettingsRepair(preferencesOf(stringPreferencesKey("settings") to "{"))

    @Test fun explicitSettingsRepairReplacesWrongPreferenceType() =
        verifyExplicitSettingsRepair(preferencesOf(intPreferencesKey("settings") to 42))

    @Test fun reminderPermissionResultRepairsMalformedSettings() =
        verifyExplicitSettingsRepair(preferencesOf(stringPreferencesKey("settings") to "{"), reminderPermissionResult = true)

    @Test fun reminderPermissionResultRepairsWrongPreferenceType() =
        verifyExplicitSettingsRepair(preferencesOf(intPreferencesKey("settings") to 42), reminderPermissionResult = true)

    private fun verifyExplicitSettingsRepair(invalid: Preferences, reminderPermissionResult: Boolean = false) = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settingsKey = stringPreferencesKey("settings")
        val draft = EntryDraft.fresh("settings-repair").with("description", "Retained draft")
        val initial = invalid.toMutablePreferences().apply {
            this[stringPreferencesKey("project")] = "settings-repair"
            this[stringPreferencesKey("entry_draft")] = draft.json()
        }.toPreferences()
        val preferences = SwitchablePreferencesStore(initial)
        val repository = LedgerRepository.inMemory(context)
        val models = ViewModelStore()
        try {
            repository.saveProject(ProjectInfo("settings-repair", "Settings repair fixture"))
            val model = LedgerViewModel(context, repository, SavedStateHandle(), preferences)
            models.put("settings-repair", model)
            val loaded = withTimeout(5_000) { model.state.first { it.ready } }
            assertEquals(LedgerRecoveryState.PREFERENCES_UNAVAILABLE, loaded.recoveryState)
            assertTrue(!loaded.recoveryRequired)
            assertEquals(draft, loaded.draft)
            assertEquals(initial.asMap(), preferences.saved.asMap())

            instrumentation.runOnMainSync {
                if (reminderPermissionResult) model.applyReminderPermissionResult(false)
                else model.settings(reminders = true, showArchived = true)
            }
            withTimeout(5_000) {
                while (preferences.saved.asMap()[settingsKey] !is String || preferences.saved.asMap()[settingsKey] == "{")
                    kotlinx.coroutines.delay(10)
            }
            val repaired = JSONObject(preferences.saved[settingsKey]!!)
            assertEquals(!reminderPermissionResult, repaired.get("reminders"))
            assertEquals(!reminderPermissionResult, repaired.get("showArchived"))
            assertEquals(draft.json(), preferences.saved[stringPreferencesKey("entry_draft")])
            instrumentation.runOnMainSync { model.retryPreferences() }
            withTimeout(5_000) { model.state.first { it.recoveryState == LedgerRecoveryState.HEALTHY_EXISTING_DATABASE } }
            assertEquals("Settings repair fixture", repository.projects.first().single().name)
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            repository.close()
        }
    }

    @Test fun invalidPreferenceTypesRemainPreferenceErrorsAndExplicitRepairsCanBeRetried() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val viewModels = ViewModelStore()
        val preferences = SwitchablePreferencesStore(preferencesOf(
            intPreferencesKey("project") to 42, intPreferencesKey("entry_draft") to 7,
            stringPreferencesKey("settings") to "{\"reminders\":\"invalid\"}"))
        try {
            repository.saveProject(ProjectInfo("invalid-preferences", "Invalid preference fixture"))
            val model = LedgerViewModel(context, repository, SavedStateHandle(), preferences)
            viewModels.put("invalid-preferences", model)
            val loaded = withTimeout(5_000) { model.state.first { it.ready } }
            assertEquals(LedgerRecoveryState.PREFERENCES_UNAVAILABLE, loaded.recoveryState)
            assertTrue(!loaded.recoveryRequired)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.retryPreferences() }
            withTimeout(5_000) { while (preferences.saved.asMap()[stringPreferencesKey("project")] != "invalid-preferences") kotlinx.coroutines.delay(10) }
            assertEquals(7, preferences.saved[intPreferencesKey("entry_draft")])
            assertEquals("{\"reminders\":\"invalid\"}", preferences.saved[stringPreferencesKey("settings")])
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.field("description", "Explicitly repaired draft")
                model.settings(reminders = false)
            }
            withTimeout(5_000) {
                while (preferences.saved.asMap()[stringPreferencesKey("entry_draft")] !is String ||
                    preferences.saved[stringPreferencesKey("settings")] == "{\"reminders\":\"invalid\"}") kotlinx.coroutines.delay(10)
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.retryPreferences() }
            val repaired = withTimeout(5_000) { model.state.first { it.recoveryState == LedgerRecoveryState.HEALTHY_EXISTING_DATABASE } }
            assertEquals("Explicitly repaired draft", repaired.draft.get("description"))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
            repository.close()
        }
    }
    private class SwitchablePreferencesStore(initial: Preferences) : DataStore<Preferences> {
        var failReads = false
        var failWrites = false
        @Volatile var saved = initial
        override val data: Flow<Preferences> = kotlinx.coroutines.flow.flow {
            if (failReads) throw IOException("synthetic preference read failure")
            emit(saved)
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("synthetic preference write failure")
            return transform(saved).also { saved = it }
        }
    }

    @Test fun retryRetainsLoadedDraftWhenPreferencesBecomeUnreadable() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val viewModels = ViewModelStore()
        val draft = EntryDraft.fresh("retry-project").with("description", "Loaded unsaved invoice")
            .with("invoiceLines", "[]").with("attachments", "original-id").with("invoiceNumber", "INV-RETRY")
        val preferences = SwitchablePreferencesStore(preferencesOf(stringPreferencesKey("entry_draft") to draft.json()))
        try {
            repository.saveProject(ProjectInfo("retry-project", "Retry fixture"))
            val model = LedgerViewModel(context, repository, SavedStateHandle(), preferences)
            viewModels.put("retry-preferences", model)
            assertEquals(draft, withTimeout(5_000) { model.state.first { it.ready } }.draft)
            preferences.failReads = true
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.retryInitialization() }
            val retried = withTimeout(5_000) { model.state.first { it.ready && it.recoveryState == LedgerRecoveryState.PREFERENCES_UNAVAILABLE } }
            assertEquals(draft, retried.draft)
            assertEquals("retry-project", retried.projectId)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModels.clear() }
            repository.close()
        }
    }

    private class FailingPreferencesStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = kotlinx.coroutines.flow.flow {
            throw IOException("synthetic preference read failure")
        }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("synthetic preference write failure")
    }

    @Test fun preferencesFailureKeepsLedgerUsableAndRetainsVisibleDraftOnWriteFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val store = ViewModelStore()
        try {
            repository.saveProject(ProjectInfo("preference-project", "Preference failure fixture"))
            val model = LedgerViewModel(context, repository, SavedStateHandle(), FailingPreferencesStore())
            store.put("preferences-failure", model)
            val loaded = withTimeout(5_000) { model.state.first { it.ready } }
            assertEquals("preference-project", loaded.projectId)
            assertEquals(LedgerRecoveryState.PREFERENCES_UNAVAILABLE, loaded.recoveryState)
            assertTrue(loaded.error.orEmpty().contains("Preferences are unavailable"))

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.field("description", "Kept in the form")
                model.settings(reminders = true)
            }
            val failed = withTimeout(5_000) { model.state.first { it.error.orEmpty().contains("not saved") } }
            assertEquals("Kept in the form", failed.draft.get("description"))
            assertEquals(LedgerRecoveryState.PREFERENCES_UNAVAILABLE, failed.recoveryState)
            assertTrue(failed.error.orEmpty().contains("not saved"))
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            repository.close()
        }
    }

    @Test fun rejectedActionDoesNotReplacePreferenceDiagnostic() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val store = ViewModelStore()
        try {
            repository.saveProject(ProjectInfo("action-project", "Action fixture"))
            val model = LedgerViewModel(context, repository, SavedStateHandle(), FailingPreferencesStore())
            store.put("action-error", model)
            val loaded = withTimeout(5_000) { model.state.first { it.ready } }
            val diagnostic = loaded.error
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model.command("Unreachable success") { throw IllegalArgumentException("Rejected correction") }
            }
            val rejected = withTimeout(5_000) { model.state.first { it.actionError == "Rejected correction" } }
            assertEquals(diagnostic, rejected.error)
            assertEquals(LedgerRecoveryState.PREFERENCES_UNAVAILABLE, rejected.recoveryState)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.dismissActionError() }
            assertEquals(null, model.state.value.actionError)
            assertEquals(diagnostic, model.state.value.error)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() }
            repository.close()
        }
    }
}
