package com.constructioncost.nativeapp

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import com.constructioncost.nativeapp.data.backup.BackupReport
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.repository.SnapshotFinancials
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.domain.ClassificationInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.CameraCaptures
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.File
import java.math.BigInteger
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerOperationsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ConstructionApplication

    @Test fun reportExportsRejectPrivateOriginalDestinationsBeforeOpeningThem() = runBlocking<Unit> {
        val capture = CameraCaptures(app).create("photo-${UUID.randomUUID()}.jpg")
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 11, 22, 33)
        capture.writeBytes(bytes)
        val operations = LedgerOperations(app)
        try {
            val destinations = listOf(Uri.fromFile(capture),
                androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", capture))
            for (destination in destinations) for (format in listOf("csv", "pdf", "project", "backup")) {
                instrumentation.runOnMainSync {
                    when (format) {
                        "csv" -> operations.exportCsv(destination, "missing-project")
                        "pdf" -> operations.exportPdf(destination, "missing-project")
                        "project" -> operations.exportProject(destination, "missing-project")
                        else -> operations.createBackup(destination, null)
                    }
                }
                val result = withTimeout(30_000) { operations.state.first { !it.busy && it.message != null } }
                assertTrue("$format: ${result.message}", result.message.orEmpty().contains("outside BuildLedger's private storage"))
                assertArrayEquals(bytes, capture.readBytes())
            }
        } finally { operations.acknowledge(); capture.delete() }
    }

    @Test fun failedCameraImportRetainsBytesForRestartReviewAndSuccessfulRetry() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(30_000) { while (!app.recoveryState.isLedgerValidated()) kotlinx.coroutines.delay(20) }
            val repository = app.repository
            val project = "camera-retry-${UUID.randomUUID()}"
            repository.saveProject(ProjectInfo(project, "Camera retry fixture"))
            val captures = CameraCaptures(app)
            val photo = captures.create("photo-${UUID.randomUUID()}.jpg")
            val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)
            photo.writeBytes(bytes)
            val operations = LedgerOperations(app)
            try {
                instrumentation.runOnMainSync { operations.importPhoto(photo, "missing-${UUID.randomUUID()}") }
                val failed = withTimeout(30_000) { operations.state.first { state -> !state.busy && state.message != null } }
                assertTrue(failed.message.orEmpty().contains("Project missing"))
                assertArrayEquals(bytes, photo.readBytes())
                assertTrue(failed.capturedPhotos.orEmpty().contains(photo))

                val reopened = LedgerOperations(app)
                instrumentation.runOnMainSync { reopened.reviewCapturedPhotos() }
                val reviewed = withTimeout(30_000) { reopened.state.first { state -> !state.busy && state.capturedPhotos.orEmpty().contains(photo) } }
                assertTrue(reviewed.capturedPhotos.orEmpty().contains(photo))
                instrumentation.runOnMainSync { reopened.importPhoto(photo, project) }
                val imported = withTimeout(30_000) { reopened.state.first { state -> !state.busy && state.message.orEmpty().startsWith("Photo imported") } }
                assertFalse(imported.capturedPhotos.orEmpty().contains(photo))
                assertFalse(photo.exists())
                val document = withTimeout(30_000) { repository.attachments(project).first { rows -> rows.any { row -> row.name == photo.name } } }
                    .single { row -> row.name == photo.name }
                assertArrayEquals(bytes, AttachmentStore(app, repository).verifiedFile(document).readBytes())
                assertEquals(DocumentPolicy.sha256(AttachmentStore(app, repository).fileFor(document)), document.sha256)

                val discarded = captures.create("photo-${UUID.randomUUID()}.jpg").apply { writeBytes(bytes) }
                instrumentation.runOnMainSync { reopened.discardCapturedPhoto(discarded) }
                withTimeout(30_000) { reopened.state.first { state -> !state.busy && state.message == "Captured photo discarded" } }
                assertFalse(discarded.exists())
                reopened.acknowledge()
            } finally {
                photo.delete()
                repository.attachments(project).first().filter { row -> row.name == photo.name }.forEach { row ->
                    AttachmentStore(app, repository).delete(row.id)
                }
                AttachmentStore(app, repository).deleteProject(project)
                operations.acknowledge()
            }
        }
    }

    @Test fun activityRecreationRetainsRunningWorkAndCompletesItOnce() = runBlocking<Unit> {
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var completions = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val operations = app.operations
            scenario.onActivity {
                operations.run("Retained operation fixture") {
                    release.await()
                    completions++
                    finished.complete(Unit)
                }
            }
            try {
                withTimeout(30_000) { operations.state.first { it.busy } }
                scenario.recreate()
                scenario.onActivity {
                    assertSame(operations, it.operations)
                    assertTrue(it.operations.state.value.busy)
                }
            } finally { release.complete(Unit) }
            withTimeout(30_000) { finished.await(); operations.state.first { !it.busy } }
            assertEquals(1, completions)
            operations.acknowledge()
        }
    }

    @Test fun preferenceFailureAfterCommitRetainsSuccessWarningAndRefresh() = runBlocking<Unit> {
        val operations = LedgerOperations(app)
        val report = BackupReport(emptyMap(), 0, 0, 1,
            financials = SnapshotFinancials(BigInteger.valueOf(125050), BigInteger.valueOf(50025), BigInteger.valueOf(75025)))
        instrumentation.runOnMainSync {
            operations.run("Restore completion fixture") {
                finishRestore(report, settings = { throw IOException("synthetic preferences unavailable") })
            }
        }
        val complete = withTimeout(30_000) { operations.state.first { !it.busy && it.refreshLedger } }
        assertTrue(complete.message.orEmpty().startsWith("Ledger restored."))
        assertTrue(complete.message.orEmpty().contains("Settings or reminders could not be restored"))
        assertFalse(complete.message.orEmpty().contains("Restore did not complete"))
        val reopened = LedgerOperations(app)
        assertEquals(complete.message, reopened.state.value.message)
        operations.acknowledge()
    }

    @Test fun journalFailureAfterCommitStillRefreshesLedger() = runBlocking<Unit> {
        val operations = LedgerOperations(app)
        val report = BackupReport(emptyMap(), 0, 0, 1,
            financials = SnapshotFinancials(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO))
        instrumentation.runOnMainSync {
            operations.run("Restore completion fixture") {
                finishRestore(report, settings = {}, persistOutcome = { throw IOException("synthetic journal failure") })
            }
        }
        val complete = withTimeout(30_000) { operations.state.first { !it.busy && it.refreshLedger } }
        assertTrue(complete.message.orEmpty().startsWith("Ledger restored."))
        assertTrue(complete.message.orEmpty().contains("Completion status could not be saved"))
        operations.acknowledge()
    }

    @Test fun processRestartReportsUncertainWorkWithoutReplayingIt() {
        val journal = app.getSharedPreferences("ledger_operations", 0)
        try {
            assertTrue(journal.edit().clear().putString("operation", "restore").commit())
            val operations = LedgerOperations(app)
            assertFalse(operations.state.value.busy)
            assertTrue(operations.state.value.message.orEmpty().contains("Review the ledger"))
            assertFalse(operations.state.value.refreshLedger)
        } finally { journal.edit().clear().commit() }
    }

    @Test fun changedArchiveKeepsHealthyLedgerAndOriginalsInPlaceAfterConfirmation() = runBlocking<Unit> {
        val archive = File(app.cacheDir, "restore-outcome-${UUID.randomUUID()}.zip")
        val operations = LedgerOperations(app)
        val projectId = "changed-archive-${UUID.randomUUID()}"
        val repository = app.repository
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                withTimeout(30_000) { while (!app.recoveryState.isLedgerValidated()) kotlinx.coroutines.delay(20) }
                repository.saveProject(ProjectInfo(projectId, "Changed archive fixture"))
                repository.saveClassification(ClassificationInfo("$projectId-stage", projectId, "Stage", "STAGE"))
                val before = repository.exportRecords().toString()
                val preserved = File(app.filesDir, "recovery-preserved").listFiles().orEmpty().map { file -> file.name }.toSet()
                BackupService(app, repository, AttachmentStore(app, repository)).create(Uri.fromFile(archive))
                instrumentation.runOnMainSync { operations.inspectRestore(Uri.fromFile(archive), null) }
                withTimeout(30_000) { operations.state.first { state -> !state.busy && state.confirmation != null } }
                archive.writeBytes(byteArrayOf(1, 2, 3))
                instrumentation.runOnMainSync { operations.confirmRestore() }
                val failed = withTimeout(30_000) { operations.state.first { state -> !state.busy && state.message != null } }
                assertFalse(failed.refreshLedger)
                assertFalse(app.recoveryState.isRecoveryRequired())
                assertEquals(before, repository.exportRecords().toString())
                assertEquals(preserved, File(app.filesDir, "recovery-preserved").listFiles().orEmpty().map { file -> file.name }.toSet())
            } finally {
                operations.acknowledge()
                archive.delete()
                if (repository.projects.first().any { it.id == projectId }) AttachmentStore(app, repository).deleteProject(projectId)
            }
        }
    }
}
