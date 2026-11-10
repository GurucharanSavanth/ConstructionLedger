package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.Money
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordBoundariesTest {
    @Test fun crossProjectTotalsRemainValidThroughStartupAndBackupRestore() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun sandbox(label: String): Pair<Context, File> {
            val root = File(base.cacheDir, "$label-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(root, "files").apply { check(mkdirs() || isDirectory) }
                override fun getCacheDir() = File(root, "cache").apply { check(mkdirs() || isDirectory) }
                override fun getDatabasePath(name: String) = File(root, "databases/$name").apply { parentFile?.mkdirs() }
            }
            return context to root
        }
        val (sourceContext, sourceRoot) = sandbox("v7-cross-project-source")
        val (targetContext, targetRoot) = sandbox("v7-cross-project-target")
        var source: LedgerRepository? = null
        var target: LedgerRepository? = null
        try {
            val sourceRepo = LedgerRepository(sourceContext).also { source = it }
            sourceRepo.saveProject(ProjectInfo("max-project", "Maximum project"))
            sourceRepo.saveProject(ProjectInfo("small-project", "Small project"))
            sourceRepo.saveExpense(ExpenseInput(id = "max-expense", projectId = "max-project", description = "Maximum invoice",
                amountPaise = Long.MAX_VALUE, date = "2026-10-22"))
            sourceRepo.saveExpense(ExpenseInput(id = "small-expense", projectId = "small-project", description = "One paise invoice",
                amountPaise = 1, date = "2026-10-22"))
            sourceRepo.savePayment(PaymentInput(id = "max-payment", projectId = "max-project", amountPaise = Long.MAX_VALUE,
                date = "2026-10-22", allocations = listOf(PaymentAllocationInput("max-expense", Long.MAX_VALUE))))
            sourceRepo.savePayment(PaymentInput(id = "small-payment", projectId = "small-project", amountPaise = 1,
                date = "2026-10-22", allocations = listOf(PaymentAllocationInput("small-expense", 1))))

            val archive = File(sourceContext.cacheDir, "cross-project.blbackup")
            val sourceStore = AttachmentStore(sourceContext, sourceRepo)
            val sourceBackups = BackupService(sourceContext, sourceRepo, sourceStore)
            val validationFailure = runCatching { sourceRepo.validateStorage() }.exceptionOrNull()
            val backupAttempt = runCatching { sourceBackups.create(Uri.fromFile(archive)) }
            assertTrue("Valid per-project records must survive global checks and backup; validation=$validationFailure, backup=${backupAttempt.exceptionOrNull()}",
                validationFailure == null && backupAttempt.isSuccess)

            val expectedTotal = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)
            assertEquals(expectedTotal, backupAttempt.getOrThrow().financials.recordedPaise)
            assertEquals(expectedTotal, backupAttempt.getOrThrow().financials.paidPaise)
            assertEquals(BigInteger.ZERO, backupAttempt.getOrThrow().financials.outstandingPaise)
            assertTrue(Money.format(expectedTotal).endsWith("758.08"))
            val preview = sourceBackups.inspect(Uri.fromFile(archive))
            assertEquals(expectedTotal, preview.financials.recordedPaise)
            assertEquals(expectedTotal, preview.financials.paidPaise)

            val restoredRepo = LedgerRepository(targetContext).also { target = it }
            val restoredStore = AttachmentStore(targetContext, restoredRepo)
            val restoredReport = BackupService(targetContext, restoredRepo, restoredStore).restore(Uri.fromFile(archive))
            restoredRepo.validateStorage()
            assertEquals(expectedTotal, restoredReport.financials.recordedPaise)
            assertEquals(expectedTotal, restoredReport.financials.paidPaise)
            assertEquals(Long.MAX_VALUE, restoredRepo.summary("max-project").first().paidPaise)
            assertEquals(1L, restoredRepo.summary("small-project").first().paidPaise)
            assertEquals("Maximum invoice", restoredRepo.expense("max-expense")?.description)
            assertEquals("One paise invoice", restoredRepo.expense("small-expense")?.description)
        } finally {
            source?.close()
            target?.close()
            check(sourceRoot.canonicalFile.parentFile == base.cacheDir.canonicalFile && sourceRoot.name.startsWith("v7-cross-project-source-"))
            check(targetRoot.canonicalFile.parentFile == base.cacheDir.canonicalFile && targetRoot.name.startsWith("v7-cross-project-target-"))
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test fun maximumPaymentCanBeRefundedAndReversedWithoutOverflowingHistory() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (allocated in listOf(false, true)) {
            val repository = LedgerRepository.inMemory(context)
            val restored = LedgerRepository.inMemory(context)
            try {
                repository.saveProject(ProjectInfo("project", "House"))
                if (allocated) repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project",
                    description = "Maximum invoice", amountPaise = Long.MAX_VALUE, date = "2026-01-01"))
                repository.savePayment(PaymentInput(id = "cash", projectId = "project", amountPaise = Long.MAX_VALUE,
                    date = "2026-01-02", allocations = if (allocated) listOf(PaymentAllocationInput("invoice", Long.MAX_VALUE)) else emptyList()))
                assertEquals(Long.MAX_VALUE, repository.summary("project").first().paidPaise)
                if (allocated) {
                    repository.refundPayment("cash", 1, "One paise returned", "2026-01-03")
                    assertEquals(Long.MAX_VALUE - 1, repository.summary("project").first().paidPaise)
                    assertEquals(1L, repository.summary("project").first().outstandingPaise)
                }
                repository.reversePayment("cash", "Remaining payment reversed", "2026-01-04")
                val summary = repository.summary("project").first()
                assertEquals(0L, summary.paidPaise)
                assertEquals(0L, summary.advancePaise)
                assertEquals(if (allocated) Long.MAX_VALUE else 0L, summary.outstandingPaise)
                assertEquals(if (allocated) 3 else 2, repository.payments("project").first().size)
                restored.replaceRecords(repository.exportRecords())
                assertEquals(summary, restored.summary("project").first())
            } finally { repository.close(); restored.close() }
        }
    }

    @Test fun originalPaymentOverflowRollsBackWithoutBlockingReversal() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "House"))
            repository.savePayment(PaymentInput(id = "cash", projectId = "project", amountPaise = Long.MAX_VALUE, date = "2026-01-01"))
            val before = repository.exportRecords().toString()
            assertThrows(android.database.sqlite.SQLiteException::class.java) { runBlocking {
                repository.savePayment(PaymentInput(id = "overflow", projectId = "project", amountPaise = 1, date = "2026-01-02"))
            } }
            assertEquals(before, repository.exportRecords().toString())
            repository.reversePayment("cash", "Reverse maximum payment", "2026-01-03")
            assertEquals(0L, repository.summary("project").first().paidPaise)
            val corrected = repository.exportRecords().toString()
            assertThrows(android.database.sqlite.SQLiteException::class.java) { runBlocking {
                repository.savePayment(PaymentInput(id = "overflow", projectId = "project", amountPaise = 1, date = "2026-01-04"))
            } }
            assertEquals(corrected, repository.exportRecords().toString())
        } finally { repository.close() }
    }

    @Test fun extremeBackupRevisionCannotExhaustLaterRestores() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "restore-extreme-revision-${java.util.UUID.randomUUID()}.db"
        val repository = LedgerRepository(androidx.room.Room.databaseBuilder(context,
            com.constructioncost.nativeapp.data.local.LedgerDatabase::class.java, databaseName).build(), context)
        val marker = File(context.getDatabasePath(databaseName).path + ".restore-revision")
        try {
            repository.saveProject(ProjectInfo("project", "House"))
            val valid = repository.exportRecords()
            val extreme = org.json.JSONObject(valid.toString()).apply {
                getJSONArray("projects").getJSONObject(0).put("revision", Long.MAX_VALUE - 1)
            }
            repository.replaceRecords(extreme)
            assertTrue(marker.readText().toLong() < Long.MAX_VALUE)
            repository.replaceRecords(valid)
            assertEquals("House", repository.projects.first().single().name)
        } finally {
            repository.close(); context.deleteDatabase(databaseName)
            android.util.AtomicFile(marker).delete()
        }
    }

    @Test fun failedVerificationAfterWatermarkPublicationRetainsGenerationAcrossReopen() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (streamed in listOf(false, true)) {
            val databaseName = "restore-published-${java.util.UUID.randomUUID()}.db"
            fun open() = LedgerRepository(androidx.room.Room.databaseBuilder(context,
                com.constructioncost.nativeapp.data.local.LedgerDatabase::class.java, databaseName).build(), context)
            var repository = open()
            val marker = File(context.getDatabasePath(databaseName).path + ".restore-revision")
            val file = File.createTempFile("restore-published-", ".json", context.cacheDir)
            try {
                val empty = repository.exportRecords()
                repository.saveProject(ProjectInfo("project", "House"))
                repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Original",
                    amountPaise = 10_000, date = "2026-01-01"))
                val original = repository.exportRecords()
                suspend fun restore(records: org.json.JSONObject) {
                    if (streamed) {
                        file.writeText(records.toString())
                        repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
                    } else repository.replaceRecords(records)
                }
                restore(original)
                val stale = repository.expense("invoice")!!
                restore(empty)
                val previousMarker = marker.readText().toLong()
                repository.replacementCheckpoint = { phase ->
                    if (phase == "watermark-published") throw java.io.IOException("Injected verification failure")
                }
                assertThrows(java.io.IOException::class.java) { runBlocking { restore(original) } }
                assertTrue(marker.isFile)
                assertTrue(marker.readText().toLong() > previousMarker)
                assertEquals(empty.toString(), repository.exportRecords().toString())
                repository.close(); repository = open()
                restore(original)
                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    repository.saveExpense(stale.copy(note = "Stale edit"))
                } }
                assertEquals("", repository.expense("invoice")!!.note)
                assertEquals(10_000L, repository.summary("project").first().recordedPaise)
            } finally {
                repository.close(); file.delete(); context.deleteDatabase(databaseName)
                android.util.AtomicFile(marker).delete()
            }
        }
    }

    @Test fun malformedRestoreWatermarkRejectsReplacementWithoutChangingLiveLedger() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (streamed in listOf(false, true)) for (invalid in listOf("not-a-revision", "-1")) {
            val databaseName = "restore-invalid-watermark-${java.util.UUID.randomUUID()}.db"
            fun open() = LedgerRepository(androidx.room.Room.databaseBuilder(context,
                com.constructioncost.nativeapp.data.local.LedgerDatabase::class.java, databaseName).build(), context)
            var repository = open()
            val watermark = android.util.AtomicFile(File(context.getDatabasePath(databaseName).path + ".restore-revision"))
            val file = File.createTempFile("restore-invalid-watermark-", ".json", context.cacheDir)
            try {
                repository.saveProject(ProjectInfo("project", "House"))
                repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Original",
                    amountPaise = 10_000, date = "2026-01-01"))
                val before = repository.exportRecords().toString()
                val candidate = org.json.JSONObject(before)
                candidate.getJSONArray("expenses").getJSONObject(0).put("amountPaise", 20_000)
                repository.validateRecords(candidate)
                file.writeText(candidate.toString())
                watermark.baseFile.writeText(invalid)
                var replacementStarted = false
                repository.replacementCheckpoint = { replacementStarted = true }
                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    if (streamed) repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
                    else repository.replaceRecords(candidate)
                } }
                assertFalse(replacementStarted)
                assertEquals(before, repository.exportRecords().toString())
                assertEquals(invalid, watermark.baseFile.readText())
                repository.close(); repository = open()
                assertEquals(before, repository.exportRecords().toString())
                assertEquals(10_000L, repository.summary("project").first().recordedPaise)
            } finally {
                repository.close(); file.delete(); context.deleteDatabase(databaseName); watermark.delete()
            }
        }
    }

    @Test fun inaccessibleRestoreWatermarkCannotPublishOrReplaceLiveLedger() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (streamed in listOf(false, true)) {
            val databaseName = "restore-unwritable-watermark-${java.util.UUID.randomUUID()}.db"
            val blocker = File.createTempFile("restore-watermark-parent-", ".blocker", context.cacheDir)
            val unavailablePath = File(blocker, databaseName)
            val failingContext = object : android.content.ContextWrapper(context) {
                override fun getDatabasePath(name: String): File = if (name == databaseName) unavailablePath else super.getDatabasePath(name)
            }
            fun open(repositoryContext: android.content.Context) = LedgerRepository(androidx.room.Room.databaseBuilder(context,
                com.constructioncost.nativeapp.data.local.LedgerDatabase::class.java, databaseName).build(), repositoryContext)
            var repository = open(failingContext)
            val file = File.createTempFile("restore-unwritable-watermark-", ".json", context.cacheDir)
            try {
                repository.saveProject(ProjectInfo("project", "House"))
                repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Original",
                    amountPaise = 10_000, date = "2026-01-01"))
                val before = repository.exportRecords().toString()
                val candidate = org.json.JSONObject(before)
                candidate.getJSONArray("expenses").getJSONObject(0).put("amountPaise", 20_000)
                repository.validateRecords(candidate)
                file.writeText(candidate.toString())
                var replacementStarted = false
                repository.replacementCheckpoint = { replacementStarted = true }
                // A regular-file parent forces publication failure without relying on root or filesystem permissions.
                assertThrows(java.io.IOException::class.java) { runBlocking {
                    if (streamed) repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
                    else repository.replaceRecords(candidate)
                } }
                assertFalse(replacementStarted)
                assertEquals(before, repository.exportRecords().toString())
                assertFalse(File(unavailablePath.path + ".restore-revision").exists())
                assertTrue(blocker.isFile)
                repository.close(); repository = open(context)
                assertEquals(before, repository.exportRecords().toString())
                assertEquals(10_000L, repository.summary("project").first().recordedPaise)
            } finally {
                repository.close(); file.delete(); blocker.delete(); context.deleteDatabase(databaseName)
                android.util.AtomicFile(File(context.getDatabasePath(databaseName).path + ".restore-revision")).delete()
            }
        }
    }

    @Test fun restoredAllocationRejectsPreRestoreCorrection() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (streamed in listOf(false, true)) {
            val repository = LedgerRepository.inMemory(context)
            val file = File.createTempFile("restore-allocation-", ".json", context.cacheDir)
            try {
                repository.saveProject(ProjectInfo("project", "House"))
                repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Invoice",
                    amountPaise = 10_000, date = "2026-01-01"))
                repository.savePayment(PaymentInput(id = "cash", projectId = "project", amountPaise = 4_000,
                    date = "2026-01-01", allocations = listOf(PaymentAllocationInput("invoice", 4_000))))
                val stale = repository.paymentAllocationStates("cash").single()
                val records = repository.exportRecords()
                if (streamed) {
                    file.writeText(records.toString())
                    repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
                } else repository.replaceRecords(records)
                val before = repository.exportRecords().toString()
                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    repository.correctAllocation(stale.allocationId, stale.fingerprint, 1_000, null, "stale")
                } }
                assertEquals(before, repository.exportRecords().toString())
                val current = repository.paymentAllocationStates("cash").single()
                assertNotEquals(stale.fingerprint, current.fingerprint)
                repository.correctAllocation(current.allocationId, current.fingerprint, 1_000, null, "current")
                assertEquals(4_000L, repository.summary("project").first().paidPaise)
                assertEquals(1_000L, repository.summary("project").first().advancePaise)
            } finally { repository.close(); file.delete() }
        }
    }

    @Test fun sameRevisionRestoreRejectsPreRestoreEdit() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (streamed in listOf(false, true)) {
            val repository = LedgerRepository.inMemory(context)
            val file = File.createTempFile("restore-revision-", ".json", context.cacheDir)
            try {
                repository.saveProject(ProjectInfo("project", "House"))
                repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Original",
                    amountPaise = 10_000, date = "2026-01-01"))
                val stale = repository.expense("invoice")!!
                val records = repository.exportRecords()
                records.getJSONArray("expenses").getJSONObject(0).put("description", "Restored invoice")
                if (streamed) {
                    file.writeText(records.toString())
                    repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
                } else repository.replaceRecords(records)
                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    repository.saveExpense(stale.copy(note = "Stale edit"))
                } }
                assertEquals("Restored invoice", repository.expense("invoice")!!.description)
                assertEquals(10_000L, repository.summary("project").first().recordedPaise)
                val current = repository.expense("invoice")!!
                repository.saveExpense(current.copy(note = "Fresh edit"))
                assertEquals("Fresh edit", repository.expense("invoice")!!.note)
            } finally { repository.close(); file.delete() }
        }
    }

    @Test fun repeatedRestoreAfterEmptyLedgerRejectsRetainedEdit() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (streamed in listOf(false, true)) for (persistent in listOf(false, true)) {
            val databaseName = "restore-empty-${java.util.UUID.randomUUID()}.db"
            fun open() = if (persistent) LedgerRepository(androidx.room.Room.databaseBuilder(context,
                com.constructioncost.nativeapp.data.local.LedgerDatabase::class.java, databaseName).build(), context)
                else LedgerRepository.inMemory(context)
            var repository = open()
            val file = File.createTempFile("restore-empty-revision-", ".json", context.cacheDir)
            try {
                val empty = repository.exportRecords()
                repository.saveProject(ProjectInfo("project", "House"))
                repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Original",
                    amountPaise = 10_000, date = "2026-01-01"))
                val original = repository.exportRecords()
                suspend fun restore(records: org.json.JSONObject) {
                    if (streamed) {
                        file.writeText(records.toString())
                        repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
                    } else repository.replaceRecords(records)
                }
                restore(original)
                val stale = repository.expense("invoice")!!
                restore(empty)
                if (persistent) { repository.close(); repository = open() }
                restore(original)
                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    repository.saveExpense(stale.copy(note = "Stale edit"))
                } }
                assertEquals("", repository.expense("invoice")!!.note)
                assertEquals(10_000L, repository.summary("project").first().recordedPaise)
            } finally {
                repository.close(); file.delete()
                context.deleteDatabase(databaseName)
                android.util.AtomicFile(File(context.getDatabasePath(databaseName).path + ".restore-revision")).delete()
            }
        }
    }

    @Test fun wideUtf8RestoreAndOversizedAuditCannotCommit() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val file = File.createTempFile("oversized-record-", ".json", context.cacheDir)
        try {
            repository.saveProject(ProjectInfo("project", "House"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repository.saveExpense(ExpenseInput(id = "oversized", projectId = "project", description = "Invoice", amountPaise = 100, date = "2026-01-01", note = "\"".repeat(500_000)))
            } }
            assertTrue(repository.expenses("project").first().isEmpty())
            assertEquals(1, repository.exportRecords().getJSONArray("audit_records").length())
            repository.saveExpense(ExpenseInput(id = "retained", projectId = "project", description = "Original", amountPaise = 100, date = "2026-01-01"))
            val malicious = repository.exportRecords()
            malicious.getJSONArray("expenses").getJSONObject(0).put("note", "अ".repeat(400_000))
            file.writeText(malicious.toString())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION) } }
            assertEquals("Original", repository.expense("retained")!!.description)
            assertEquals(100L, repository.summary("project").first().recordedPaise)
        } finally { repository.close(); file.delete() }
    }
}
