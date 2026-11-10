package com.constructioncost.nativeapp

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.app.Application
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.constructioncost.nativeapp.ui.LedgerViewModel
import com.constructioncost.nativeapp.ui.TestPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.ui.ledgerPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderDeliveryTest {
    @Test fun notificationsReconcilePaidEditedCancelledAndRemovedSchedules() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val sandbox = File(base.cacheDir, "reminder-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        var failNotifications = false
        val context = object : Application() {
            init { attachBaseContext(base) }
            override fun getSystemService(name: String): Any? {
                if (failNotifications && name == Context.NOTIFICATION_SERVICE) throw IllegalStateException("Synthetic notification failure")
                return super.getSystemService(name)
            }
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(sandbox, name)
            // Android 8 opens through Context instead of resolving getDatabasePath first.
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        }
        val settingsKey = stringPreferencesKey("settings")
        val previousSettings = base.ledgerPreferences.data.first()[settingsKey]
        val repository = LedgerRepository(context)
        val manager = base.getSystemService(NotificationManager::class.java)
        val previousNotifications = manager.activeNotifications.filter { it.notification.channelId == "local_instalments" }
        val remindersWereEnabled = JSONObject(previousSettings ?: "{}").optBoolean("reminders")
        val preferences = TestPreferences()
        val viewModels = ViewModelStore()
        lateinit var vm: LedgerViewModel
        suspend fun command(action: suspend () -> Unit) {
            withContext(Dispatchers.Main) { vm.command("Reminder mutation saved", action) }
            withTimeout(5_000) { while (vm.state.value.saving) delay(50) }
            assertNull(vm.state.value.error)
            assertEquals("Reminder mutation saved", vm.state.value.message)
        }
        val prefix = "reminder-${UUID.randomUUID()}"
        fun posted() = manager.activeNotifications.filter { it.tag?.startsWith(prefix) == true }
        suspend fun awaitPosted(expected: Set<String>) {
            try { withTimeout(5_000) { while (posted().mapNotNull { it.tag }.toSet() != expected) delay(50) } }
            catch (failure: TimeoutCancellationException) {
                throw AssertionError("Expected notifications $expected; found ${posted().map { it.tag }}", failure)
            }
        }
        var stage = "setup"
        try {
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(base.packageName, Manifest.permission.POST_NOTIFICATIONS)
            base.ledgerPreferences.edit { it[settingsKey] = JSONObject(previousSettings ?: "{}").put("reminders", true).toString() }
            repository.saveProject(ProjectInfo("project", "Synthetic reminder house"))
            assertTrue("Reminder fixture must use its disposable database", File(sandbox, "ledger.db").isFile)
            withContext(Dispatchers.Main) {
                vm = LedgerViewModel(context, repository, SavedStateHandle(), preferences.store)
                viewModels.put("ledger", vm)
            }
            stage = "view model ready"
            withTimeout(5_000) { while (!vm.state.value.ready) delay(50) }
            val today = LocalDate.now().toString()
            for (suffix in listOf("paid", "edited", "removed", "cancelled")) repository.saveInstalment(InstalmentInput(
                id = "$prefix-$suffix", projectId = "project", title = "Milestone $suffix", amountPaise = 10_000,
                dueDate = today, remind = true))
            ReminderScheduler.notifyDue(context, repository)
            stage = "initial notifications posted"
            awaitPosted(setOf("$prefix-paid", "$prefix-edited", "$prefix-removed", "$prefix-cancelled"))
            assertEquals(4, posted().size)
            assertTrue(posted().all { it.notification.extras.getString(Notification.EXTRA_TITLE) == "Construction payment reminder" })
            assertTrue(posted().all { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains(today) })
            ReminderScheduler.notifyDue(context, repository)
            assertEquals(4, posted().size)
            val cancelled = repository.instalments("project").first().single { it.id == "$prefix-cancelled" }
            stage = "cancel command complete"
            command { repository.cancelInstalment(cancelled.id, cancelled.revision) }
            awaitPosted(setOf("$prefix-paid", "$prefix-edited", "$prefix-removed"))
            stage = "new schedule command complete"
            command { repository.saveInstalment(InstalmentInput(id = "$prefix-new", projectId = "project",
                title = "New due plan", amountPaise = 100, dueDate = today, remind = true)) }
            assertFalse("A save must not issue a new reminder", posted().any { it.tag == "$prefix-new" })
            delay(1_200) // Android may drop rapid updates to an existing notification.
            stage = "partial payment command complete"
            command { repository.savePayment(PaymentInput(projectId = "project", amountPaise = 4_000, date = today, instalmentId = "$prefix-paid")) }
            assertEquals(6_000L, repository.instalments("project").first().single { it.id == "$prefix-paid" }.outstandingPaise)
            stage = "partial payment notification refreshed"
            try {
                withTimeout(5_000) { while (!posted().single { it.tag == "$prefix-paid" }.notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT).toString().contains(Money.format(6_000))) delay(50) }
            } catch (failure: TimeoutCancellationException) {
                throw AssertionError("Partial-payment reminder text was ${posted().single { it.tag == "$prefix-paid" }.notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT)}", failure)
            }
            stage = "full payment command complete"
            command { repository.savePayment(PaymentInput(projectId = "project", amountPaise = 6_000, date = today, instalmentId = "$prefix-paid")) }
            awaitPosted(setOf("$prefix-edited", "$prefix-removed"))
            assertFalse(posted().any { it.tag == "$prefix-paid" })
            val edited = repository.instalments("project").first().single { it.id == "$prefix-edited" }
            stage = "edited date command complete"
            command { repository.saveInstalment(InstalmentInput(edited.id, "project", edited.title, edited.amountPaise,
                LocalDate.now().plusDays(30).toString(), remind = true, revision = edited.revision)) }
            awaitPosted(setOf("$prefix-removed"))
            assertFalse(posted().any { it.tag == edited.id })
            val current = repository.instalments("project").first().single { it.id == edited.id }
            stage = "second cancellation command complete"
            command { repository.cancelInstalment(current.id, current.revision) }
            assertEquals(listOf("$prefix-removed"), posted().map { it.tag })
            val records = repository.exportRecords()
            for (table in listOf("reminders", "instalments")) {
                val rows = records.getJSONArray(table)
                for (index in rows.length() - 1 downTo 0) if (rows.getJSONObject(index).getString("id") == "$prefix-removed") rows.remove(index)
            }
            stage = "restore command complete"
            command { repository.replaceRecords(records) }
            awaitPosted(emptySet())
            assertTrue("Removed restored schedules must not leave a notification", posted().isEmpty())
            failNotifications = true
            stage = "notification failure command complete"
            command { repository.saveProject(ProjectInfo("committed", "Committed despite notification failure")) }
            assertTrue(repository.projects.first().any { it.id == "committed" })
            failNotifications = false
            ReminderScheduler.configure(base, true)
            ReminderScheduler.configure(base, true)
            val jobs = base.getSystemService(JobScheduler::class.java).allPendingJobs.filter { it.id == 451021 }
            assertEquals(1, jobs.size)
            assertTrue(jobs.single().isPersisted)
            assertEquals(24L * 60 * 60 * 1000, jobs.single().intervalMillis)
            ReminderScheduler.configure(base, false)
            assertNull(base.getSystemService(JobScheduler::class.java).getPendingJob(451021))
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError("Reminder test timed out during $stage", failure)
        } finally {
            posted().forEach { manager.cancel(it.tag, it.id) }
            ReminderScheduler.configure(base, false)
            base.ledgerPreferences.edit { if (previousSettings == null) it.remove(settingsKey) else it[settingsKey] = previousSettings }
            withContext(Dispatchers.Main) { viewModels.clear() }
            preferences.close()
            ReminderScheduler.configure(base, JSONObject(previousSettings ?: "{}").optBoolean("reminders"))
            if (remindersWereEnabled) previousNotifications.forEach { manager.notify(it.tag, it.id, it.notification) }
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }
}
