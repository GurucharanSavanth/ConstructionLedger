package com.constructioncost.nativeapp

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@SeparateVerification
@RunWith(AndroidJUnit4::class)
class LedgerRestoreRecreationTest {
    @Test fun committedRestoreSurvivesRecreationAndRefreshesExactFinancials() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Restore integration requires the disposable verification application",
            context.packageName == "com.constructioncost.nativeapp.verification")
        val app = context.applicationContext as ConstructionApplication
        val operations = app.operations
        val release = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val directory = File(context.cacheDir, "restore-recreation-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val initial = Uri.fromFile(File(directory, "initial.zip"))
        val candidate = Uri.fromFile(File(directory, "candidate.zip"))
        var backupReady = false
        var refreshedActivity: MainActivity? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                withTimeout(30_000) { while (!app.recoveryState.isLedgerValidated()) kotlinx.coroutines.delay(20) }
                val repository = app.repository
                val project = "restore-recreation-${UUID.randomUUID()}"
                val costId = "$project-cost"
                val cashId = "$project-cash"
                val laterId = "$project-later"
                val service = BackupService(context, repository, AttachmentStore(context, repository))
                app.ledgerLifecycleMutex.withLock {
                    service.create(initial)
                    backupReady = true
                    repository.saveProject(com.constructioncost.nativeapp.domain.ProjectInfo(project, "Restore recreation fixture"))
                    repository.saveExpense(ExpenseInput(id = costId, projectId = project, description = "Rotation invoice",
                        amountPaise = 125_050, date = "2026-10-23"))
                    repository.savePayment(PaymentInput(id = cashId, projectId = project, amountPaise = 50_025,
                        date = "2026-10-23", allocations = listOf(com.constructioncost.nativeapp.domain.PaymentAllocationInput(costId, 50_025))))
                    service.create(candidate)
                    repository.saveExpense(ExpenseInput(id = laterId, projectId = project, description = "Later invoice",
                        amountPaise = 22_222, date = "2026-10-23"))
                }
                scenario.onActivity {
                    operations.run("Restoring recreation fixture") {
                        val report = service.restore(candidate)
                        committed.complete(Unit)
                        release.await()
                        finishRestore(report)
                        finished.complete(Unit)
                    }
                }
                withTimeout(30_000) { committed.await() }
                scenario.recreate()
                lateinit var rotated: MainActivity
                scenario.onActivity { rotated = it; assertSame(operations, it.operations); assertTrue(it.operations.state.value.busy) }
                release.complete(Unit)
                withTimeout(30_000) { finished.await(); operations.state.first { !it.busy } }
                val summary = repository.summary(project).first()
                assertEquals(125_050L, summary.recordedPaise)
                assertEquals(50_025L, summary.paidPaise)
                assertEquals(75_025L, summary.outstandingPaise)
                assertNull(repository.expense(laterId))
                withTimeout(30_000) {
                    while (refreshedActivity == null) {
                        instrumentation.runOnMainSync {
                            refreshedActivity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<MainActivity>().firstOrNull { it !== rotated }
                        }
                        kotlinx.coroutines.delay(20)
                    }
                }
            } finally {
                release.complete(Unit)
                withTimeout(30_000) { operations.state.first { !it.busy } }
                instrumentation.runOnMainSync { refreshedActivity?.finish() }
                if (backupReady) app.ledgerLifecycleMutex.withLock {
                    BackupService(context, app.repository, AttachmentStore(context, app.repository)).restore(initial)
                }
                operations.acknowledge()
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                directory.deleteRecursively()
            }
        }
    }
}
