package com.constructioncost.nativeapp

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.constructioncost.nativeapp.domain.InstalmentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.ui.ledgerPreferences
import com.constructioncost.nativeapp.ui.EntryDraft
import com.constructioncost.nativeapp.ui.LedgerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Two explicit phases allow the host to kill/reboot between real production job executions. */
@SeparateVerification
@RunWith(AndroidJUnit4::class)
class ReminderProcessTest {
    private val projectId = "process-reminder-project"
    private val scheduleId = "process-reminder-schedule"
    private fun application(): ConstructionApplication {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".verification"))
        check(InstrumentationRegistry.getArguments().getString("disposableReminder") == "true")
        return instrumentation.targetContext.applicationContext as ConstructionApplication
    }

    @Test fun prepareBackgroundFixture() = runBlocking<Unit> {
        val app = application()
        app.ledgerLifecycleMutex.withLock {
            app.repository.validateStorage()
            check(app.repository.projects.first().isEmpty()) { "Use a fresh disposable verification installation" }
            app.recoveryState.markLedgerValidated()
            app.repository.saveProject(ProjectInfo(projectId, "Process restart fixture"))
            app.repository.saveProject(ProjectInfo("$projectId-other", "Other draft selection"))
            app.repository.saveInstalment(InstalmentInput(id = scheduleId, projectId = projectId,
                title = "Fresh process reminder", amountPaise = 12345, dueDate = LocalDate.now().toString(), remind = true))
            val draft = EntryDraft.fresh(projectId, "Plan").with("description", "Retained across process death")
                .with("amount", "123.45").with("date", "2032-02-29").with("note", "Exact input")
            app.ledgerPreferences.edit {
                it[stringPreferencesKey("settings")] = "{\"reminders\":true}"
                it[stringPreferencesKey("project")] = "$projectId-other"
                it[stringPreferencesKey("entry_draft")] = draft.json()
            }
            if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
            ReminderScheduler.configure(app, true)
        }
    }

    @Test fun clearFirstDeliveryEvidence() = runBlocking<Unit> {
        val app = application()
        check(app.repository.projects.first().map { it.id }.toSet() == setOf(projectId, "$projectId-other"))
        check(app.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(451021) != null)
        app.getSystemService(NotificationManager::class.java).cancel(scheduleId, 0)
        app.ledgerPreferences.edit { it.remove(stringPreferencesKey("reminder_deliveries")) }
    }

    @Test fun prepareRebootFixture() = runBlocking<Unit> {
        val app = application()
        app.ledgerLifecycleMutex.withLock {
            app.repository.validateStorage()
            check(app.repository.projects.first().map { it.id }.toSet() == setOf(projectId, "$projectId-other"))
            app.recoveryState.markLedgerValidated()
            app.getSystemService(NotificationManager::class.java).cancel(scheduleId, 0)
            app.repository.saveInstalment(InstalmentInput(id = "$scheduleId-reboot", projectId = projectId,
                title = "Reboot only reminder", amountPaise = 23456, dueDate = LocalDate.now().toString(), remind = true))
            ReminderScheduler.configure(app, true)
        }
    }

    @Test fun verifyDeliveryAndRemoveFixture() = runBlocking<Unit> {
        val app = application()
        val delivered = JSONObject(app.ledgerPreferences.data.first()[stringPreferencesKey("reminder_deliveries")] ?: "{}")
        assertEquals(LocalDate.now().toString(), delivered.getString("date"))
        assertTrue(delivered.getJSONObject("entries").getString(scheduleId).contains("Process restart fixture"))
        assertTrue(delivered.getJSONObject("entries").getString("$scheduleId-reboot").contains("Reboot only reminder"))
        assertEquals(listOf(12345L, 23456L), app.repository.instalments(projectId).first().map { it.outstandingPaise }.sorted())
        val savedDraft = EntryDraft.read(requireNotNull(app.ledgerPreferences.data.first()[stringPreferencesKey("entry_draft")]))
        val models = ViewModelStore()
        try {
            val model = LedgerViewModel(app, app.repository, SavedStateHandle(), app.ledgerPreferences)
            models.put("fresh-process", model)
            val restored = withTimeout(5_000) { model.state.first { it.ready } }
            assertEquals("$projectId-other", restored.projectId)
            assertEquals(projectId, restored.draft.projectId)
            assertEquals(savedDraft.fields, restored.draft.fields)
            assertEquals("Retained across process death", restored.draft.get("description"))
            assertEquals("123.45", restored.draft.get("amount"))
            assertEquals("2032-02-29", restored.draft.date)
            assertEquals("Exact input", restored.draft.get("note"))
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() } }
        ReminderScheduler.configure(app, false)
        app.ledgerLifecycleMutex.withLock {
            app.repository.validateStorage()
            app.recoveryState.markLedgerValidated()
            AttachmentStore(app, app.repository).deleteProject(projectId)
            AttachmentStore(app, app.repository).deleteProject("$projectId-other")
        }
        app.ledgerPreferences.edit { it.clear() }
    }
}
