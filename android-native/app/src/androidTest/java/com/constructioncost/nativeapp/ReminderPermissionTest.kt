package com.constructioncost.nativeapp

import android.Manifest
import android.app.job.JobScheduler
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.ui.LedgerViewModel
import com.constructioncost.nativeapp.ui.ledgerPreferences
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderPermissionTest {
    @Test fun queuedReminderWriteSurvivesActivityRecreation() = runBlocking<Unit> {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = stringPreferencesKey("settings")
        val originalSettings = context.ledgerPreferences.data.first()[key]
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var blocker: Job? = null
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            context.ledgerPreferences.edit {
                it[key] = JSONObject(originalSettings ?: "{}").put("reminders", false).toString()
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                withTimeout(30_000) {
                    while ((context.applicationContext as ConstructionApplication).recoveryState.state() != LedgerRecoveryState.HEALTHY_EXISTING_DATABASE) delay(25)
                }
                lateinit var model: LedgerViewModel
                scenario.onActivity { model = ViewModelProvider(it)[LedgerViewModel::class.java] }
                withTimeout(30_000) { while (!model.state.value.ready) delay(25) }
                blocker = CoroutineScope(Dispatchers.IO).launch {
                    context.ledgerPreferences.edit { entered.complete(Unit); release.await() }
                }
                withTimeout(10_000) { entered.await() }
                scenario.onActivity { it.handleReminderPermissionResult(true) }
                scenario.recreate()
                lateinit var recreated: LedgerViewModel
                scenario.onActivity { recreated = ViewModelProvider(it)[LedgerViewModel::class.java] }
                assertSame(model, recreated)
                release.complete(Unit)
                withTimeout(30_000) {
                    while (!recreated.state.value.reminders ||
                        !JSONObject(context.ledgerPreferences.data.first()[key] ?: "{}").optBoolean("reminders") ||
                        context.getSystemService(JobScheduler::class.java).getPendingJob(451021) == null) delay(25)
                }
            }
        } finally {
            release.complete(Unit)
            blocker?.join()
            context.ledgerPreferences.edit {
                if (originalSettings == null) it.remove(key) else it[key] = originalSettings
            }
            ReminderScheduler.configure(context, JSONObject(originalSettings ?: "{}").optBoolean("reminders"))
        }
    }

    @Test fun deniedPermissionResultTurnsRemindersOffAndGrantSurvivesRecreation() = runBlocking<Unit> {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val key = stringPreferencesKey("settings")
        val originalSettings = context.ledgerPreferences.data.first()[key]
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            context.ledgerPreferences.edit {
                it[key] = JSONObject(originalSettings ?: "{}").put("reminders", false).put("showArchived", true).toString()
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                withTimeout(30_000) {
                    while ((context.applicationContext as ConstructionApplication).recoveryState.state() != LedgerRecoveryState.HEALTHY_EXISTING_DATABASE) delay(25)
                }
                lateinit var model: LedgerViewModel
                scenario.onActivity { model = ViewModelProvider(it)[LedgerViewModel::class.java] }
                withTimeout(30_000) { while (!model.state.value.ready) delay(25) }
                scenario.onActivity { it.handleReminderPermissionResult(true) }
                withTimeout(10_000) {
                    while (!model.state.value.reminders ||
                        !JSONObject(context.ledgerPreferences.data.first()[key] ?: "{}").optBoolean("reminders")) delay(25)
                }
                scenario.onActivity { it.handleReminderPermissionResult(false) }
                withTimeout(30_000) {
                    while (model.state.value.reminders ||
                        JSONObject(context.ledgerPreferences.data.first()[key] ?: "{}").optBoolean("reminders")) delay(25)
                }
                assertFalse(model.state.value.reminders)
                assertTrue(JSONObject(context.ledgerPreferences.data.first()[key] ?: "{}").getBoolean("showArchived"))
                assertTrue(context.getSystemService(JobScheduler::class.java).getPendingJob(451021) == null)
                scenario.recreate()
                scenario.onActivity { model = ViewModelProvider(it)[LedgerViewModel::class.java] }
                assertFalse(model.state.value.reminders)

                scenario.onActivity { it.handleReminderPermissionResult(true) }
                withTimeout(10_000) {
                    while (!model.state.value.reminders ||
                        !JSONObject(context.ledgerPreferences.data.first()[key] ?: "{}").optBoolean("reminders") ||
                        context.getSystemService(JobScheduler::class.java).getPendingJob(451021) == null) delay(25)
                }
                assertTrue(JSONObject(context.ledgerPreferences.data.first()[key] ?: "{}").getBoolean("showArchived"))
                scenario.recreate()
                scenario.onActivity { model = ViewModelProvider(it)[LedgerViewModel::class.java] }
                assertTrue(model.state.value.reminders)
            }
        } finally {
            context.ledgerPreferences.edit {
                if (originalSettings == null) it.remove(key) else it[key] = originalSettings
            }
            // Revoking a runtime permission can kill the instrumentation process; this test runs on a disposable target.
            ReminderScheduler.configure(context, JSONObject(originalSettings ?: "{}").optBoolean("reminders"))
        }
    }
}
