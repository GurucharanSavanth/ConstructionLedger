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
    private var lastSubmissionMillis = 0L
    private const val CHANNEL = "local_instalments"
    internal const val OVERFLOW_TAG = "instalment-overflow"
    internal const val INDIVIDUAL_LIMIT = 20

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
        // Android owns automatic group summaries; cancelling one can also remove its children.
        val active = manager.activeNotifications.filter {
            it.notification.channelId == CHANNEL &&
                (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 || it.tag == OVERFLOW_TAG)
        }
        val postedIds = active.mapNotNull { it.tag }.toSet()
        if (postedOnly && postedIds.isEmpty()) return
        val today = LocalDate.now()
        val deliveryKey = stringPreferencesKey("reminder_deliveries")
        val saved = JSONObject(context.ledgerPreferences.data.first()[deliveryKey] ?: "{}")
        val delivered = if (saved.optString("date") == today.toString()) saved.optJSONObject("entries") ?: JSONObject() else JSONObject()
        suspend fun persistDeliveries() { context.ledgerPreferences.edit {
            it[deliveryKey] = JSONObject().put("date", today.toString()).put("entries", delivered).toString()
        } }
        data class Reminder(val id: String, val projectId: String, val text: String, val contentKey: String = text)
        val eligible = mutableListOf<Reminder>()
        for (project in repo.projects.first().filter { !it.archived }) {
            for (schedule in repo.instalments(project.id).first()) {
                if (!com.constructioncost.nativeapp.domain.reminderEligible(schedule, today)) continue
                val due = LocalDate.parse(schedule.dueDate)
                val text = "${project.name} ? ${schedule.title}: ${Money.format(schedule.outstandingPaise)} ${if (due.isBefore(today)) "overdue" else "due ${schedule.dueDate}"}"
                eligible.add(Reminder(schedule.id, project.id, text))
            }
        }
        val ordered = eligible.sortedBy { it.id }
        val notifications = ordered.take(INDIVIDUAL_LIMIT).toMutableList()
        val overflow = ordered.drop(INDIVIDUAL_LIMIT)
        if (overflow.isNotEmpty()) {
            val projectCount = overflow.map { it.projectId }.distinct().size
            val text = "${overflow.size} more payment reminders across $projectCount project(s). " +
                "Open the app and review Calendar for each project.\n" + overflow.take(10).joinToString("\n") { it.text } +
                if (overflow.size > 10) "\nAnd ${overflow.size - 10} more schedules." else ""
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            overflow.forEach { digest.update("${it.id}\n${it.projectId}\n${it.text}\n".toByteArray(Charsets.UTF_8)) }
            val contentKey = digest.digest().joinToString("") { "%02x".format(it) }
            notifications.add(Reminder(OVERFLOW_TAG, overflow.first().projectId, text, contentKey))
        }
        val liveIds = notifications.map { it.id }.toSet()
        // Cancel old individual notifications before posting the bounded replacement set.
        active.filter { it.tag !in liveIds }.forEach { manager.cancel(it.tag, it.id) }
        for (reminder in notifications) {
            // An active digest already covers promoted members; keep that coverage when a slot opens.
            if (postedOnly && reminder.id !in postedIds && OVERFLOW_TAG !in postedIds) continue
            val fingerprint = "${reminder.projectId}\n${reminder.contentKey}"
            if (active.firstOrNull { it.tag == reminder.id }?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT) == reminder.text &&
                (reminder.id != OVERFLOW_TAG || delivered.optString(reminder.id) == fingerprint)) {
                // Repair history interrupted between notification submission and persistence.
                delivered.put(reminder.id, fingerprint)
                continue
            }
            if (!postedOnly && delivered.optString(reminder.id) == fingerprint) continue
            val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
                .setData("buildledger://reminder/${Uri.encode(reminder.projectId)}/${Uri.encode(reminder.id)}".toUri())
                .putExtra(PROJECT_EXTRA, reminder.projectId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // Stay below Android's per-package update rate, including the initial individual burst.
            val waitMillis = lastSubmissionMillis + 300L - android.os.SystemClock.elapsedRealtime()
            if (waitMillis > 0) kotlinx.coroutines.delay(waitMillis)
            manager.notify(reminder.id, 0, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (reminder.id == OVERFLOW_TAG) "More construction payment reminders" else "Construction payment reminder")
                .setContentText(reminder.text).setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
                .setContentIntent(intent).setOnlyAlertOnce(true).setAutoCancel(true).build())
            lastSubmissionMillis = android.os.SystemClock.elapsedRealtime()
            // This is submission history for duplicate suppression, not a delivery acknowledgement.
            delivered.put(reminder.id, fingerprint)
            persistDeliveries()
        }
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
