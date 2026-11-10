package com.constructioncost.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@SeparateVerification
class ScheduleVolumeTest {
    @Test fun fiveHundredSchedulesKeepPartialCashSeparateAndRestoreExactly() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "schedule-volume-${UUID.randomUUID()}").apply { check(mkdirs()) }
        check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir(): File = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = LedgerRepository.inMemory(context)
        try {
            repository.saveProject(ProjectInfo("schedules", "Synthetic schedule house"))
            val due = LocalDate.now().plusDays(7).toString()
            val started = SystemClock.elapsedRealtime()
            repeat(5) { agreement ->
                repository.saveCommitment(CommitmentInput(id = "agreement-$agreement", projectId = "schedules",
                    title = "Agreement $agreement", amountPaise = 1000000, date = LocalDate.now().toString(),
                    schedules = List(100) { index -> InstalmentInput(id = "plan-$agreement-$index", projectId = "schedules",
                        title = "Milestone $agreement/$index", amountPaise = 10000, dueDate = due) }))
            }
            Log.i("BuildLedgerScheduleVolume", "500 schedules | create | ${SystemClock.elapsedRealtime() - started} ms")
            assertEquals(500, repository.instalments("schedules").first().size)
            val unpaid = repository.summary("schedules").first()
            assertEquals(0L, unpaid.recordedPaise)
            assertEquals(0L, unpaid.paidPaise)
            assertEquals(5000000L, unpaid.upcomingPaise)
            repository.savePayment(PaymentInput(id = "partial", projectId = "schedules", amountPaise = 4000,
                date = LocalDate.now().toString(), instalmentId = "plan-0-0"))
            val paid = repository.summary("schedules").first()
            assertEquals(0L, paid.recordedPaise)
            assertEquals(4000L, paid.paidPaise)
            assertEquals(4000L, paid.advancePaise)
            assertEquals(4996000L, paid.upcomingPaise)
            assertEquals(6000L, repository.instalments("schedules").first().single { it.id == "plan-0-0" }.outstandingPaise)
            val service = BackupService(context, repository, AttachmentStore(context, repository))
            val archive = Uri.fromFile(File(context.cacheDir, "schedule-backup.zip"))
            val backupStarted = SystemClock.elapsedRealtime()
            val report = service.create(archive)
            assertEquals(500, report.counts["instalments"])
            assertEquals(5, report.counts["commitments"])
            assertEquals(1, report.counts["payments"])
            assertEquals(java.math.BigInteger.ZERO, report.financials.recordedPaise)
            assertEquals(java.math.BigInteger.valueOf(4000L), report.financials.paidPaise)
            service.restore(archive)
            Log.i("BuildLedgerScheduleVolume", "500 schedules | backup and verified restore | ${SystemClock.elapsedRealtime() - backupStarted} ms")
            assertEquals(paid, repository.summary("schedules").first())
            assertEquals(500, repository.instalments("schedules").first().size)
            assertEquals(6000L, repository.instalments("schedules").first().single { it.id == "plan-0-0" }.outstandingPaise)
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("schedule-volume-"))
            sandbox.deleteRecursively()
        }
    }
}
