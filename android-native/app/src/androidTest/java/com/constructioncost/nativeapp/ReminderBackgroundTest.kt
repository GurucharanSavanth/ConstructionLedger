package com.constructioncost.nativeapp

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import com.constructioncost.nativeapp.domain.InstalmentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.domain.newId
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.ui.ledgerPreferences
import com.constructioncost.nativeapp.ui.LedgerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@SeparateVerification
@RunWith(AndroidJUnit4::class)
class ReminderBackgroundTest {
    @Test fun productionJobValidatesWithoutActivityWaitsForRecoveryLockAndDoesNotDuplicate() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".verification")) { "Use the disposable verification application" }
        val app = context.applicationContext as ConstructionApplication
        val recovery = app.recoveryState
        check(!recovery.isRecoveryRequired()) { "Do not replace an existing recovery state" }
        val settingsKey = stringPreferencesKey("settings")
        val previousSettings = context.ledgerPreferences.data.first()[settingsKey]
        val retainedKeys = listOf("project", "entry_draft", "reminder_deliveries").map(::stringPreferencesKey)
        val previousPreferences = context.ledgerPreferences.data.first()
        val manager = context.getSystemService(NotificationManager::class.java)
        val previousNotifications = manager.activeNotifications.filter { it.notification.channelId == "local_instalments" }
        val projectId = "background-project-${newId()}"
        val otherProjectId = "background-other-${newId()}"
        val scheduleId = "background-schedule-${newId()}"
        fun notifications() = manager.activeNotifications.filter { it.tag == scheduleId }
        fun runProductionJob() {
            val output = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "cmd jobscheduler run -f ${context.packageName} 451021")).bufferedReader().use { it.readText() }
            assertTrue(output, output.contains("Running job"))
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            context.ledgerPreferences.edit { it[settingsKey] = JSONObject(previousSettings ?: "{}").put("reminders", true).toString() }
            app.ledgerLifecycleMutex.withLock {
                app.repository.validateStorage()
                recovery.markLedgerValidated()
                app.repository.saveProject(ProjectInfo(projectId, "Background reminder fixture"))
                app.repository.saveInstalment(InstalmentInput(id = scheduleId, projectId = projectId,
                    title = "Background-only milestone", amountPaise = 12345,
                    dueDate = LocalDate.now().toString(), remind = true))
                app.closeAndResetRepository()
                recovery.beginStartup()
                assertFalse(recovery.isLedgerValidated())
                ReminderScheduler.configure(context, true)
                runProductionJob()
                delay(500)
                assertTrue("The production job must wait for the restore/startup lock", notifications().isEmpty())
                assertFalse(recovery.isLedgerValidated())
            }
            withTimeout(15_000) { while (notifications().isEmpty()) delay(50) }
            assertTrue("The background entry point must establish validation without opening an Activity", recovery.isLedgerValidated())
            assertEquals(1, notifications().size)
            assertTrue(notifications().single().notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                .contains("Background-only milestone"))
            val ownerIntent = notifications().single().notification.contentIntent
            val firstPost = notifications().single().postTime
            // Simulate process interruption after notify succeeds but before delivery history commits.
            context.ledgerPreferences.edit { it.remove(stringPreferencesKey("reminder_deliveries")) }
            delay(500)
            runProductionJob()
            delay(1_000)
            assertEquals(1, notifications().size)
            assertEquals("An unchanged retry must not repost a notification", firstPost, notifications().single().postTime)
            manager.cancel(scheduleId, 0)
            app.ledgerLifecycleMutex.withLock {
                app.closeAndResetRepository()
                recovery.beginStartup()
            }
            runProductionJob()
            delay(1_500)
            assertTrue("Dismissed reminders must stay dismissed during the same day's fresh-process retry", notifications().isEmpty())
            val delivered = JSONObject(context.ledgerPreferences.data.first()[stringPreferencesKey("reminder_deliveries")] ?: "{}")
            assertEquals(LocalDate.now().toString(), delivered.getString("date"))
            assertTrue(delivered.getJSONObject("entries").has(scheduleId))
            app.repository.saveProject(ProjectInfo(otherProjectId, "Other reminder project"))
            // ActivityScenario matches lifecycle events by the launch intent's data, including after onNewIntent.
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .setData(Uri.parse("buildledger://reminder/${Uri.encode(projectId)}/${Uri.encode(scheduleId)}"))).use { scenario ->
                lateinit var model: LedgerViewModel
                scenario.onActivity { model = ViewModelProvider(it)[LedgerViewModel::class.java] }
                withTimeout(15_000) { model.state.first { it.ready } }
                instrumentation.runOnMainSync {
                    model.selectProject(otherProjectId)
                    model.resetDraft()
                    model.field("description", "Keep this other project's draft")
                }
                val retainedDraft = model.state.value.draft
                ownerIntent.send()
                withTimeout(15_000) { model.state.first { it.projectId == projectId } }
                assertEquals("Tapping a reminder must select its owner and preserve the other draft", retainedDraft, model.state.value.draft)
                assertEquals(otherProjectId, model.state.value.draft.projectId)
                instrumentation.waitForIdleSync()
            }
            app.ledgerLifecycleMutex.withLock {
                recovery.markRecoveryRequired(LedgerRecoveryState.RESTORE_IN_PROGRESS, "Synthetic background-reminder recovery guard")
            }
            runProductionJob()
            delay(1_000)
            assertTrue("Recovery-in-progress must block notifications", notifications().isEmpty())
            assertEquals(LedgerRecoveryState.RESTORE_IN_PROGRESS, recovery.state())
        } finally {
            ReminderScheduler.configure(context, false)
            app.ledgerLifecycleMutex.withLock {
                app.repository.validateStorage()
                recovery.markLedgerValidated()
                if (app.repository.projects.first().any { it.id == projectId }) AttachmentStore(context, app.repository).deleteProject(projectId)
                if (app.repository.projects.first().any { it.id == otherProjectId }) AttachmentStore(context, app.repository).deleteProject(otherProjectId)
            }
            context.ledgerPreferences.edit { preferences ->
                if (previousSettings == null) preferences.remove(settingsKey) else preferences[settingsKey] = previousSettings
                retainedKeys.forEach { key -> previousPreferences[key]?.let { preferences[key] = it } ?: preferences.remove(key) }
            }
            ReminderScheduler.configure(context, JSONObject(previousSettings ?: "{}").optBoolean("reminders"))
            previousNotifications.forEach { manager.notify(it.tag, it.id, it.notification) }
        }
    }
}
