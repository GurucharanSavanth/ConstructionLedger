package com.constructioncost.nativeapp

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.os.Process
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.InstalmentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.ui.ledgerPreferences
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

/** Run preparation, host `am kill`, then verification as separate instrumentation processes. */
@SeparateVerification
@RunWith(AndroidJUnit4::class)
class ReminderOverflowProcessTest {
    private val projectId = "overflow-process-project"
    private val receiptKey = stringPreferencesKey("overflow_process_receipt")
    private val settingsKey = stringPreferencesKey("settings")
    private val deliveriesKey = stringPreferencesKey("reminder_deliveries")

    private fun application(): ConstructionApplication {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName.endsWith(".verification"))
        check(InstrumentationRegistry.getArguments().getString("disposableReminder") == "true")
        return instrumentation.targetContext.applicationContext as ConstructionApplication
    }

    private fun notifications(app: ConstructionApplication) = app.getSystemService(NotificationManager::class.java)
        .activeNotifications.filter { it.tag?.startsWith("overflow-process-") == true || it.tag == ReminderScheduler.OVERFLOW_TAG }

    @Test fun prepareOverflowForProcessRestart() = runBlocking<Unit> {
        val app = application()
        app.ledgerLifecycleMutex.withLock {
            app.repository.validateStorage()
            check(app.repository.projects.first().none { it.id == projectId }) { "Finish the existing overflow fixture first" }
            val today = LocalDate.now()
            for (project in app.repository.projects.first().filter { !it.archived }) {
                check(app.repository.instalments(project.id).first().none {
                    com.constructioncost.nativeapp.domain.reminderEligible(it, today)
                }) { "Preserve existing eligible reminders; use a verification ledger without due schedules" }
            }
            check(app.getSystemService(NotificationManager::class.java).activeNotifications.none {
                it.notification.channelId == "local_instalments"
            }) { "Preserve existing reminder notifications" }
            check(app.ledgerPreferences.data.first()[receiptKey] == null) { "Finish the existing fixture before preparing another" }
            app.recoveryState.markLedgerValidated()
            val previous = app.ledgerPreferences.data.first()
            val receipt = JSONObject().put("pid", Process.myPid()).put("date", LocalDate.now().toString())
            previous[settingsKey]?.let { receipt.put("settings", it) }
            previous[deliveriesKey]?.let { receipt.put("deliveries", it) }
            app.ledgerPreferences.edit {
                it[receiptKey] = receipt.toString()
                it[settingsKey] = JSONObject(previous[settingsKey] ?: "{}").put("reminders", true).toString()
                it.remove(deliveriesKey)
            }
            app.repository.saveProject(ProjectInfo(projectId, "Overflow process fixture"))
            repeat(51) { index -> app.repository.saveInstalment(InstalmentInput(
                id = "overflow-process-%02d".format(index), projectId = projectId, title = "Restart schedule $index",
                amountPaise = 5_000, dueDate = LocalDate.now().toString(), remind = true)) }
            if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        assertFalse(ReminderScheduler.notifyDue(app))
        withTimeout(10_000) { while (notifications(app).size != 21) delay(50) }
        val receipt = JSONObject(app.ledgerPreferences.data.first()[receiptKey]!!)
        val posts = JSONObject()
        notifications(app).forEach { posts.put(it.tag, it.postTime) }
        receipt.put("posts", posts).put("history", app.ledgerPreferences.data.first()[deliveriesKey])
        app.ledgerPreferences.edit { it[receiptKey] = receipt.toString() }
    }

    @Test fun verifyOverflowAfterProcessRestartAndRemoveFixture() = runBlocking<Unit> {
        val app = application()
        val receipt = JSONObject(requireNotNull(app.ledgerPreferences.data.first()[receiptKey]))
        assertNotEquals("The host must end the preparation process", receipt.getInt("pid"), Process.myPid())
        assertEquals("Run both phases on the same day", receipt.getString("date"), LocalDate.now().toString())
        try {
            assertEquals(receipt.getString("history"), app.ledgerPreferences.data.first()[deliveriesKey])
            assertFalse(ReminderScheduler.notifyDue(app))
            val schedules = app.repository.instalments(projectId).first()
            assertEquals(51, schedules.size)
            assertTrue(schedules.all { it.amountPaise == 5_000L && it.outstandingPaise == 5_000L })
            assertEquals(21, notifications(app).size)
            val posts = receipt.getJSONObject("posts")
            notifications(app).forEach { assertEquals("Restart must not repost ${it.tag}", posts.getLong(it.tag), it.postTime) }
            val digest = notifications(app).single { it.tag == ReminderScheduler.OVERFLOW_TAG }
            assertTrue(digest.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
                .startsWith("31 more payment reminders"))
            val history = JSONObject(app.ledgerPreferences.data.first()[deliveriesKey]!!).getJSONObject("entries")
            assertEquals(21, history.length())
            assertFalse(history.has("overflow-process-50"))
        } finally {
            notifications(app).forEach { app.getSystemService(NotificationManager::class.java).cancel(it.tag, it.id) }
            app.ledgerLifecycleMutex.withLock { AttachmentStore(app, app.repository).deleteProject(projectId) }
            app.ledgerPreferences.edit {
                for ((key, name) in listOf(Pair(settingsKey, "settings"), Pair(deliveriesKey, "deliveries"))) {
                    if (receipt.has(name)) it[key] = receipt.getString(name) else it.remove(key)
                }
                it.remove(receiptKey)
            }
        }
    }
}
