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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

object ReminderScheduler {
    private const val JOB_ID = 451021
    private const val CHANNEL = "local_instalments"

    fun configure(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled) {
            scheduler.cancel(JOB_ID)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { manager.cancel(it.tag, it.id) }
            return
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, InstalmentReminderService::class.java))
            .setPeriodic(24L * 60 * 60 * 1000)
            .setPersisted(true)
            .build()
        check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Could not schedule local reminders" }
    }

    internal suspend fun notifyDue(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Instalment reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val repo = LedgerRepository(context)
        try {
            val today = LocalDate.now()
            val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            for (project in repo.projects.first()) {
                for (schedule in repo.instalments(project.id).first()) {
                    if (!com.constructioncost.nativeapp.domain.reminderEligible(schedule, today)) {
                        manager.cancel(schedule.id, 0)
                        continue
                    }
                    val due = LocalDate.parse(schedule.dueDate)
                    val text = "${schedule.title}: ${Money.format(schedule.outstandingPaise)} ${if (due.isBefore(today)) "overdue" else "due ${schedule.dueDate}"}"
                    manager.notify(schedule.id, 0, NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Construction payment reminder")
                        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setContentIntent(intent).setAutoCancel(true).build())
                }
            }
        } finally { repo.close() }
    }
}

class InstalmentReminderService : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch {
            try { ReminderScheduler.notifyDue(applicationContext) } finally { jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
}
