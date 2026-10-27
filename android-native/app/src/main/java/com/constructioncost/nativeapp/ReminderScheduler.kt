package com.constructioncost.nativeapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import com.constructioncost.nativeapp.domain.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.constructioncost.nativeapp.ui.ledgerPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import org.json.JSONObject

object ReminderScheduler {
    private const val JOB_ID = 451021
    internal const val PROJECT_EXTRA = "reminderProjectId"
    private val notificationMutex = Mutex()
    private const val CHANNEL = "local_instalments"

    internal fun permissionGranted(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun configure(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled || !permissionGranted(context)) {
            scheduler.cancel(JOB_ID)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, InstalmentReminderService::class.java))
            .setPeriodic(24L * 60 * 60 * 1000)
            .setPersisted(true)
            .build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Could not schedule local reminders" }
    }

    internal suspend fun refreshPosted(context: Context, repository: LedgerRepository) {
        try {
            reconcile(context, repository, postedOnly = true)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            // The ledger is already committed; notification failures must not report a failed save.
            Log.w("ReminderScheduler", "Could not refresh posted reminders", error)
        }
    }

    internal suspend fun notifyDue(context: Context): Boolean {
        val app = context.applicationContext as? ConstructionApplication ?: return false
        return app.ledgerLifecycleMutex.withLock {
            val recovery = app.recoveryState
            try {
                recovery.inspectBeforeOpen()
                if (recovery.isRecoveryRequired()) return@withLock false
                val repository = app.repository
                try { repository.validateStorage() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (!recovery.isRecoveryRequired()) recovery.markRecoveryRequired(LedgerRecoveryState.DATABASE_OPEN_FAILURE,
                        failure.message ?: "The local database could not be safely opened.")
                    throw failure
                }
                recovery.markLedgerValidated()
                reconcile(context, repository, postedOnly = false)
                false
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.w("ReminderScheduler", "Local reminder check failed", failure); !recovery.isRecoveryRequired() }
        }
    }

    internal suspend fun notifyDue(context: Context, repository: LedgerRepository) {
        repository.validateStorage()
        reconcile(context, repository, postedOnly = false)
    }

    private suspend fun reconcile(context: Context, repo: LedgerRepository, postedOnly: Boolean) = notificationMutex.withLock {
        reconcileLocked(context, repo, postedOnly)
    }
    private suspend fun reconcileLocked(context: Context, repo: LedgerRepository, postedOnly: Boolean) {
        val settings = context.ledgerPreferences.data.first()[stringPreferencesKey("settings")]
        if (!JSONObject(settings ?: "{}").optBoolean("reminders")) { configure(context, false); return }
        if (!permissionGranted(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Instalment reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val active = manager.activeNotifications.filter { it.notification.channelId == CHANNEL }
        val postedIds = active.mapNotNull { it.tag }.toSet()
        if (postedOnly && postedIds.isEmpty()) return
        val liveIds = mutableSetOf<String>()
        val today = LocalDate.now()
        val deliveryKey = stringPreferencesKey("reminder_deliveries")
        val saved = JSONObject(context.ledgerPreferences.data.first()[deliveryKey] ?: "{}")
        val delivered = if (saved.optString("date") == today.toString()) saved.optJSONObject("entries") ?: JSONObject() else JSONObject()
        suspend fun persistDeliveries() { context.ledgerPreferences.edit {
            it[deliveryKey] = JSONObject().put("date", today.toString()).put("entries", delivered).toString()
        } }
        for (project in repo.projects.first().filter { !it.archived }) {
            for (schedule in repo.instalments(project.id).first()) {
                liveIds.add(schedule.id)
                if (!com.constructioncost.nativeapp.domain.reminderEligible(schedule, today)) {
                    manager.cancel(schedule.id, 0)
                    continue
                }
                if (postedOnly && schedule.id !in postedIds) continue
                val due = LocalDate.parse(schedule.dueDate)
                val text = "${project.name} — ${schedule.title}: ${Money.format(schedule.outstandingPaise)} ${if (due.isBefore(today)) "overdue" else "due ${schedule.dueDate}"}"
                val fingerprint = "${project.id}\n$text"
                if (active.firstOrNull { it.tag == schedule.id }?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT) == text) {
                    // An active notification also repairs history interrupted between notify and persistence.
                    if (delivered.optString(schedule.id) != fingerprint) {
                        delivered.put(schedule.id, fingerprint)
                        persistDeliveries()
                    }
                    continue
                }
                if (!postedOnly && delivered.optString(schedule.id) == fingerprint) continue
                val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                    .setData("buildledger://reminder/${Uri.encode(project.id)}/${Uri.encode(schedule.id)}".toUri())
                    .putExtra(PROJECT_EXTRA, project.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                manager.notify(schedule.id, 0, NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Construction payment reminder")
                    .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(intent).setOnlyAlertOnce(true).setAutoCancel(true).build())
                delivered.put(schedule.id, fingerprint)
                persistDeliveries()
            }
        }
        manager.activeNotifications.filter { it.notification.channelId == CHANNEL && it.tag !in liveIds }
            .forEach { manager.cancel(it.tag, it.id) }
        delivered.keys().asSequence().toList().filter { it !in liveIds }.forEach(delivered::remove)
        persistDeliveries()
    }
}

class InstalmentReminderService : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch {
            val retry = ReminderScheduler.notifyDue(applicationContext)
            jobFinished(params, retry)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
}
