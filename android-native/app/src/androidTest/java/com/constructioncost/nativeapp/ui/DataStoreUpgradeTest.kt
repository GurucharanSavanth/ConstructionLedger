package com.constructioncost.nativeapp.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DataStoreUpgradeTest {
    @Test fun oldSerializerDraftOwnershipAndSettingsSurviveWriteAndReopen() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "datastore-upgrade-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val file = File(directory, "ledger.preferences_pb")
        val repository = LedgerRepository.inMemory(context)
        var storeJob = SupervisorJob()
        var models = ViewModelStore()
        fun openPreferences(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + storeJob), produceFile = { file })
        try {
            val golden = instrumentation.context.assets.open("datastore-1.2.1-draft.preferences_pb").use { it.readBytes() }
            assertEquals(372, golden.size)
            assertEquals("8a1e4a9f91708b5ed3bae0da6b4c7d5f14aa9c41f2defb3f593e7f8ad5705423",
                MessageDigest.getInstance("SHA-256").digest(golden).joinToString("") { "%02x".format(it) })
            file.writeBytes(golden)
            repository.saveProject(ProjectInfo("process-reminder-project", "Old serializer owner"))
            repository.saveProject(ProjectInfo("process-reminder-project-other", "Other selected project"))
            var store = openPreferences()
            lateinit var model: LedgerViewModel
            instrumentation.runOnMainSync {
                model = LedgerViewModel(context, repository, SavedStateHandle(), store)
                models.put("upgraded-draft", model)
            }
            val restored = withTimeout(5_000) { model.state.first { it.ready } }
            assertEquals("process-reminder-project-other", restored.projectId)
            val original = restored.draft
            assertEquals("process-reminder-project", original.projectId)
            assertEquals("Plan", original.kind)
            assertEquals("Retained across process death", original.get("description"))
            assertEquals("123.45", original.get("amount"))
            assertEquals("2032-02-29", original.date)
            assertEquals("Exact input", original.get("note"))
            assertEquals(12345L, LedgerViewModel.instalment(original).amountPaise)
            assertTrue(restored.reminders)
            val draftKey = stringPreferencesKey("entry_draft")
            val settingsKey = stringPreferencesKey("settings")
            val projectKey = stringPreferencesKey("project")
            val before = store.data.first()
            assertTrue(JSONObject(requireNotNull(before[settingsKey])).getBoolean("reminders"))
            instrumentation.runOnMainSync { model.field("note", "Updated after DataStore upgrade") }
            val written = withTimeout(5_000) { store.data.first {
                val text = it[draftKey]
                text != null && EntryDraft.read(text).get("note") == "Updated after DataStore upgrade"
            } }
            val expectedFields = original.fields + ("note" to "Updated after DataStore upgrade")
            assertEquals(expectedFields, EntryDraft.read(requireNotNull(written[draftKey])).fields)
            assertEquals(before[settingsKey], written[settingsKey])
            assertEquals(before[projectKey], written[projectKey])
            // Wait for the queued update to complete before ending this simulated process.
            store.edit { }
            instrumentation.runOnMainSync { models.clear() }
            storeJob.cancelAndJoin()
            storeJob = SupervisorJob()
            models = ViewModelStore()
            store = openPreferences()
            assertEquals(written.asMap(), store.data.first().asMap())
            instrumentation.runOnMainSync {
                model = LedgerViewModel(context, repository, SavedStateHandle(), store)
                models.put("reopened-draft", model)
            }
            val reopened = withTimeout(5_000) { model.state.first { it.ready } }
            assertEquals("process-reminder-project-other", reopened.projectId)
            assertEquals("process-reminder-project", reopened.draft.projectId)
            assertEquals(expectedFields, reopened.draft.fields)
            assertTrue(reopened.reminders)
            assertEquals(12345L, LedgerViewModel.instalment(reopened.draft).amountPaise)
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            storeJob.cancelAndJoin()
            repository.close()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
