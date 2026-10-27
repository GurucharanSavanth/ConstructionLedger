package com.constructioncost.nativeapp.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerDatabase
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.InstalmentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import com.constructioncost.nativeapp.platform.LegacyImportMapper
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    @Test fun backupWithoutRemovedOriginalDoesNotAuthorizeRecoveryCopyDeletion() = runBlocking<Unit> {
        val (context, sandbox) = isolatedContext()
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, File(sandbox, "uncovered.db").absolutePath)
            .addCallback(LedgerDatabase.DURABILITY_CALLBACK).build()
        val repository = trustedRepository(context, database)
        val store = AttachmentStore(context, repository)
        try {
            repository.saveProject(ProjectInfo("uncovered-project", "Uncovered fixture"))
            val original = File(store.root, "uncovered.bin").apply { writeText("old original absent from the restored ledger") }
            repository.insertAttachment(AttachmentInfo("uncovered", "uncovered-project", "Old.txt", "text/plain", original.length(),
                DocumentPolicy.sha256(original), original.name, 1))
            val service = BackupService(context, repository, store)
            val archive = Uri.fromFile(File(context.cacheDir, "new-ledger.zip"))
            service.create(archive)
            service.restore(archive)
            service.create(Uri.fromFile(File(context.cacheDir, "verified-ledger.zip")))
            assertTrue(store.recoveryCopies().single().backedUp)
            val replacement = repository.exportRecords().put("attachments", JSONArray())
            service.restorePrepared(replacement, emptyMap())
            assertEquals(0, service.create(Uri.fromFile(File(context.cacheDir, "without-original.zip"))).fileCount)
            val sets = store.recoveryCopies()
            assertEquals(2, sets.size)
            assertTrue("A latest backup without the old original must revoke cleanup permission", sets.none { it.backedUp })
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.cleanupRecoveryCopies(sets.map { it.id }.toSet()) } }
            assertEquals("old original absent from the restored ledger", original.readText())
            assertNull(repository.attachment("uncovered"))
        } finally { repository.close(); sandbox.deleteRecursively() }
    }
    @Test fun retainedRestoreCopiesNeedNewVerifiedBackupAndExplicitCleanup() = runBlocking<Unit> {
        val (context, sandbox) = isolatedContext()
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, File(sandbox, "cleanup.db").absolutePath)
            .addCallback(LedgerDatabase.DURABILITY_CALLBACK).build()
        val repository = trustedRepository(context, database)
        val store = AttachmentStore(context, repository)
        try {
            repository.saveProject(ProjectInfo("cleanup-project", "Cleanup fixture"))
            repository.saveExpense(ExpenseInput(id = "cleanup-expense", projectId = "cleanup-project", description = "Invoice", amountPaise = 1_000L, date = "2026-01-01"))
            val bytes = "known original evidence".toByteArray(Charsets.UTF_8)
            val original = File(store.root, "known.bin").apply { writeBytes(bytes) }
            val unknown = File(store.root, "unknown.bin").apply { writeText("unclassified recovery evidence") }
            repository.insertAttachment(AttachmentInfo("known", "cleanup-project", "Known.txt", "text/plain", bytes.size.toLong(),
                DocumentPolicy.sha256(original), original.name, 1))
            val service = BackupService(context, repository, store)
            val archive = File(context.cacheDir, "original.zip")
            service.create(Uri.fromFile(archive))
            service.restore(Uri.fromFile(archive))
            service.create(Uri.fromFile(File(context.cacheDir, "first-restored.zip")))
            service.restore(Uri.fromFile(archive))
            var sets = store.recoveryCopies()
            assertEquals(2, sets.size)
            assertEquals(listOf(1, 1), sets.map { it.fileCount })
            assertEquals(1, sets.count { it.backedUp })
            assertEquals(bytes.size.toLong() * 2, sets.sumOf { it.totalBytes })
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.cleanupRecoveryCopies(sets.map { it.id }.toSet()) } }
            assertArrayEquals(bytes, original.readBytes())
            service.create(Uri.fromFile(File(context.cacheDir, "verified-restored.zip")))
            sets = store.recoveryCopies()
            assertTrue(sets.all { it.backedUp })
            original.writeText("changed retained evidence")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.cleanupRecoveryCopies(sets.map { it.id }.toSet()) } }
            assertEquals(2, store.recoveryCopies().size)
            original.writeBytes(bytes)
            assertEquals(bytes.size.toLong() * 2, store.cleanupRecoveryCopies(sets.map { it.id }.toSet()))
            assertTrue(store.recoveryCopies().isEmpty())
            assertFalse(original.exists())
            assertEquals("unclassified recovery evidence", unknown.readText())
            assertArrayEquals(bytes, store.verifiedFile(repository.attachment("known")!!).readBytes())
            assertEquals(1_000L, repository.summary("cleanup-project").first().recordedPaise)
        } finally { repository.close(); sandbox.deleteRecursively() }
    }

    @Test fun cancellationAfterRestoreCommitReturnsCommittedReport() = runBlocking<Unit> {
        val (context, sandbox) = isolatedContext()
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        try {
            repository.saveProject(ProjectInfo("commit-project", "Committed"))
            repository.saveExpense(ExpenseInput(id = "commit-expense", projectId = "commit-project", description = "Invoice", amountPaise = 1_000L, date = "2026-01-01"))
            val records = repository.exportRecords()
            repository.saveExpense(ExpenseInput(id = "later-expense", projectId = "commit-project", description = "Later invoice", amountPaise = 2_000L, date = "2026-01-02"))
            val service = BackupService(context, repository, store)
            service.restoreCheckpoint = { if (it == "after-commit") throw CancellationException("Synthetic post-commit cancellation") }
            val report = service.restorePrepared(records, emptyMap())
            assertEquals(1_000L, repository.summary("commit-project").first().recordedPaise)
            assertNull(repository.expense("later-expense"))
            assertEquals(listOf("Ledger restored; follow-up did not finish: Synthetic post-commit cancellation"), report.warnings)
        } finally { repository.close(); sandbox.deleteRecursively() }
    }
    @Test fun existingValidBackupDestinationSurvivesRejectedOverwrite() = runBlocking<Unit> {
        val (context, sandbox) = isolatedContext()
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        try {
            repository.saveProject(ProjectInfo("destination-project", "Original backup"))
            val archive = File(context.cacheDir, "known-good.zip")
            val service = BackupService(context, repository, store)
            service.create(Uri.fromFile(archive))
            val originalHash = DocumentPolicy.sha256(archive)
            repository.saveProject(ProjectInfo("next-project", "New local work"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.create(Uri.fromFile(archive)) } }
            assertEquals(originalHash, DocumentPolicy.sha256(archive))
            assertEquals(1, service.inspect(Uri.fromFile(archive)).counts["projects"])
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }
    private fun trustedRepository(context: Context, database: LedgerDatabase = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()): LedgerRepository {
        val sql = database.openHelper.writableDatabase
        sql.query("PRAGMA integrity_check").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("ok", cursor.getString(0)) }
        sql.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        LedgerRecoveryStateStore(context).markLedgerValidated()
        return LedgerRepository(database, context)
    }

    @Test fun importCapacityIncludesExactBoundariesAndRejectsOverflow() {
        BackupService.checkImportCapacity(0, 0)
        BackupService.checkImportCapacity(1000, 591L * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) { BackupService.checkImportCapacity(1001, 1) }
        assertThrows(IllegalArgumentException::class.java) { BackupService.checkImportCapacity(1, 591L * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { BackupService.checkImportCapacity(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { BackupService.checkImportCapacity(1, -1) }
        assertThrows(IllegalArgumentException::class.java) { BackupService.checkImportCapacity(Int.MAX_VALUE, Long.MAX_VALUE) }
    }

    @Test fun recoveryRestoreRetainsOldOriginalsAfterRestoredLedgerPassesIntegrityValidation() = runBlocking<Unit> {
        val (context, sandbox) = isolatedContext()
        val database = Room.databaseBuilder(context, LedgerDatabase::class.java, File(sandbox, "recovery.db").absolutePath)
            .addCallback(LedgerDatabase.DURABILITY_CALLBACK).build()
        val repository = trustedRepository(context, database)
        val store = AttachmentStore(context, repository)
        try {
            repository.saveProject(ProjectInfo("recovery-project", "Recovery fixture"))
            val original = File(store.root, "retained.bin").apply { writeText("original recovery evidence") }
            repository.insertAttachment(AttachmentInfo("retained", "recovery-project", "original.txt", "text/plain",
                original.length(), DocumentPolicy.sha256(original), original.name, 1))
            val replacement = repository.exportRecords().put("attachments", JSONArray())
            val health = LedgerRecoveryStateStore(context)
            health.markRecoveryRequired(LedgerRecoveryState.RESTORE_IN_PROGRESS, "Synthetic recovery restore")
            val report = BackupService(context, repository, store).restorePrepared(replacement, emptyMap())
            assertEquals(0, report.fileCount)
            assertNull(repository.attachment("retained"))
            assertTrue(health.isRecoveryRequired())
            assertEquals("original recovery evidence", original.readText())
            assertThrows(IllegalStateException::class.java) { runBlocking { store.reconcile() } }
            repository.validateStorage()
            health.markLedgerValidated()
            store.reconcile()
            assertEquals("original recovery evidence", original.readText())
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile && sandbox.name.startsWith("backup-test-"))
            sandbox.deleteRecursively()
        }
    }

    @Test fun restoreCheckpointFailuresKeepWholeLedgerAndOriginalsAcrossReopen() = runBlocking<Unit> {
        val phases = listOf("before-validation", "after-extraction", "before-install", "after-copy:new-first",
            "after-copy:new-second", "before-database-replace", "rows-cleared", "rows-imported", "after-commit", "before-completion")
        for (phase in phases) for (ioFailure in listOf(true, false)) {
            val (context, sandbox) = isolatedContext()
            val (candidateContext, candidateSandbox) = isolatedContext()
            val candidate = trustedRepository(candidateContext)
            val candidateStore = AttachmentStore(candidateContext, candidate)
            val databaseFile = File(sandbox, "ledger.db")
            fun openRepository() = trustedRepository(context, Room.databaseBuilder(context, LedgerDatabase::class.java,
                databaseFile.absolutePath).build())
            var repository = openRepository()
            var store = AttachmentStore(context, repository)
            try {
                suspend fun seed(target: LedgerRepository, documents: AttachmentStore, prefix: String, amount: Long, paid: Long): Map<String, String> {
                    target.saveProject(ProjectInfo("project", "$prefix house"))
                    target.saveExpense(ExpenseInput(id = "$prefix-expense", projectId = "project", description = "$prefix invoice",
                        amountPaise = amount, date = "2026-01-01"))
                    target.savePayment(PaymentInput(id = "$prefix-payment", projectId = "project", amountPaise = paid,
                        date = "2026-01-02", allocations = listOf(PaymentAllocationInput("$prefix-expense", paid))))
                    return (if (prefix == "new") listOf("new-first", "new-second") else listOf("old-original")).associateWith { id ->
                        val file = File(documents.root, "$id.bin").apply { writeText("%PDF-1.4\n$id original bytes\n") }
                        val hash = DocumentPolicy.sha256(file)
                        target.insertAttachment(AttachmentInfo(id, "project", "$id.pdf", "application/pdf", file.length(), hash, file.name, 1))
                        hash
                    }
                }
                val oldHashes = seed(repository, store, "old", 1_000L, 400L)
                val newHashes = seed(candidate, candidateStore, "new", 3_000L, 900L)
                val archive = File(context.cacheDir, "candidate.zip")
                val newReport = BackupService(candidateContext, candidate, candidateStore).create(Uri.fromFile(archive))
                assertEquals(java.math.BigInteger.valueOf(3_000L), newReport.financials.recordedPaise)
                assertEquals(java.math.BigInteger.valueOf(900L), newReport.financials.paidPaise)
                assertEquals(java.math.BigInteger.valueOf(2_100L), newReport.financials.outstandingPaise)
                val service = BackupService(context, repository, store)
                val visited = mutableListOf<String>()
                val injected: RuntimeException? = if (ioFailure) null else IllegalStateException("Injected restore interruption at $phase")
                val failAt: (String) -> Unit = { checkpoint ->
                    visited.add(checkpoint)
                    if (checkpoint == phase) throw (injected ?: IOException("Injected restore storage failure at $phase"))
                }
                service.restoreCheckpoint = failAt
                repository.replacementCheckpoint = failAt
                val committed = phase in setOf("after-commit", "before-completion")
                if (committed) {
                    val report = service.restore(Uri.fromFile(archive))
                    assertEquals(listOf("Ledger restored; follow-up did not finish: ${if (ioFailure) "Injected restore storage failure" else "Injected restore interruption"} at $phase"), report.warnings)
                } else {
                    val failure = assertThrows(Exception::class.java) { runBlocking { service.restore(Uri.fromFile(archive)) } }
                    assertEquals(if (ioFailure) IOException::class.java else IllegalStateException::class.java, failure.javaClass)
                }
                assertEquals(phase, visited.last())
                val expectedHashes = if (committed) newHashes else oldHashes
                suspend fun assertWholeLedger() {
                    val summary = repository.summary("project").first()
                    assertEquals(if (committed) 3_000L else 1_000L, summary.recordedPaise)
                    assertEquals(if (committed) 900L else 400L, summary.paidPaise)
                    assertEquals(if (committed) 2_100L else 600L, summary.outstandingPaise)
                    assertEquals(expectedHashes.keys, repository.attachments("project").first().map { it.id }.toSet())
                    assertNotNull(repository.expense(if (committed) "new-expense" else "old-expense"))
                    assertNull(repository.expense(if (committed) "old-expense" else "new-expense"))
                    val payments = repository.exportRecords().getJSONArray("payments")
                    assertEquals(1, payments.length())
                    assertEquals(if (committed) "new-payment" else "old-payment", payments.getJSONObject(0).getString("id"))
                    for ((id, hash) in expectedHashes) assertEquals(hash, DocumentPolicy.sha256(store.fileFor(repository.attachment(id)!!)))
                }
                assertWholeLedger()
                repository.close()
                repository = openRepository()
                store = AttachmentStore(context, repository)
                store.reconcile()
                assertWholeLedger()
                assertTrue(store.root.listFiles().orEmpty().map { it.name }.toSet().containsAll(repository.attachmentStorageNames()))
                oldHashes.forEach { (id, hash) -> assertEquals(hash, DocumentPolicy.sha256(File(store.root, "$id.bin"))) }
                assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("backup-") })
                assertEquals(1, BackupService(context, repository, store).restore(Uri.fromFile(archive)).counts["projects"])
                assertEquals(3_000L, repository.summary("project").first().recordedPaise)
            } finally {
                repository.close(); candidate.close(); sandbox.deleteRecursively(); candidateSandbox.deleteRecursively()
            }
        }
    }

    @Test fun nativeRestoreRejectsChangedSourceBeforeReplacingCurrentLedger() = runBlocking<Unit> {
        for (password in listOf(null, "source-identity-password")) {
            val (context, sandbox) = isolatedContext()
            val repository = trustedRepository(context)
            val store = AttachmentStore(context, repository)
            val service = BackupService(context, repository, store)
            val source = File(context.cacheDir, "selected-backup")
            val replacement = File(context.cacheDir, "replacement-backup")
            val original = File(store.root, "retained-original.bin")
            try {
                repository.saveProject(ProjectInfo("identity-project", "Restore identity test"))
                repository.saveExpense(ExpenseInput(id = "approved-cost", projectId = "identity-project",
                    description = "Approved backup cost", amountPaise = 1_000, date = "2026-01-01"))
                original.writeText("original document retained after rejected restore")
                repository.insertAttachment(AttachmentInfo("retained-original", "identity-project", "Original.txt",
                    "text/plain", original.length(), DocumentPolicy.sha256(original), original.name, 1))
                service.create(Uri.fromFile(source), password = password)
                val approved = service.inspect(Uri.fromFile(source), password)
                assertEquals(java.math.BigInteger.valueOf(1_000L), approved.financials.recordedPaise)
                assertEquals(DocumentPolicy.sha256(source), approved.archiveSha256)

                repository.saveExpense(ExpenseInput(id = "unapproved-cost", projectId = "identity-project",
                    description = "Different valid backup cost", amountPaise = 2_000, date = "2026-01-02"))
                service.create(Uri.fromFile(replacement), password = password)
                repository.saveExpense(ExpenseInput(id = "current-cost", projectId = "identity-project",
                    description = "Current ledger must survive", amountPaise = 4_000, date = "2026-01-03"))
                val before = repository.exportRecords().toString()
                val financials = repository.summary("identity-project").first()
                val hash = DocumentPolicy.sha256(original)
                replacement.copyTo(source, overwrite = true)
                assertEquals(java.math.BigInteger.valueOf(3_000L), service.inspect(Uri.fromFile(source), password).financials.recordedPaise)

                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    service.restore(Uri.fromFile(source), password, expectedArchiveSha256 = approved.archiveSha256)
                } }
                assertEquals(before, repository.exportRecords().toString())
                assertEquals(financials, repository.summary("identity-project").first())
                assertEquals(hash, DocumentPolicy.sha256(original))
                assertEquals(setOf(original.name), repository.attachmentStorageNames())
                assertEquals(listOf(original.name), store.root.listFiles().orEmpty().map { it.name })
                val reviewed = service.inspect(Uri.fromFile(source), password)
                assertEquals(java.math.BigInteger.valueOf(3_000L), service.restore(Uri.fromFile(source), password,
                    expectedArchiveSha256 = reviewed.archiveSha256).financials.recordedPaise)
                assertEquals(3_000L, repository.summary("identity-project").first().recordedPaise)
                assertEquals(hash, DocumentPolicy.sha256(store.fileFor(repository.attachment("retained-original")!!)))
            } finally { repository.close(); sandbox.deleteRecursively() }
        }
    }

    @Test fun nativeBackupRestoresOriginalBytesAndRejectsTampering() = runBlocking {
        val (context, sandbox) = isolatedContext()
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        val id = UUID.randomUUID().toString()
        val projectId = UUID.randomUUID().toString()
        val original = File(store.root, "$id.bin")
        val backup = File(context.cacheDir, "native-$id.zip")
        val encrypted = File(context.cacheDir, "native-$id.blbackup")
        val older = File(context.cacheDir, "native-v1-$id.zip")
        val olderTwo = File(context.cacheDir, "native-v2-$id.zip")
        val olderThree = File(context.cacheDir, "native-v3-$id.zip")
        val badCounts = File(context.cacheDir, "native-v1-counts-$id.zip")
        val corrupted = File(context.cacheDir, "native-corrupt-$id.zip")
        val badSettings = File(context.cacheDir, "native-settings-$id.zip")
        try {
            repository.saveProject(ProjectInfo(projectId, "House"))
            repository.saveExpense(ExpenseInput(id = "invoice-$id", projectId = projectId, description = "Concrete invoice", amountPaise = 1_850_000, date = "2026-01-01", invoiceNumber = "INV-42"))
            repository.saveInstalment(InstalmentInput(id = "plan-$id", projectId = projectId, title = "Milestone", amountPaise = 900_000, dueDate = "2026-02-01", remind = true, reminderDays = 7))
            original.writeText("%PDF-1.4\noriginal native bytes\n")
            repository.insertAttachment(AttachmentInfo(id, projectId, "Receipt.pdf", "application/pdf", original.length(),
                DocumentPolicy.sha256(original), original.name, 1, category = "Invoice", status = "Approved", revisionLabel = "R2", work = "Concrete", place = "Level 1", notes = "Signed original"))
            val service = BackupService(context, repository, store)
            val settings = JSONObject().put("project", projectId).put("reminders", true).put("showArchived", false)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.create(Uri.fromFile(backup), JSONObject().put("password", "must-not-export"))
            } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.create(Uri.fromFile(backup), JSONObject().put("project", "x".repeat(16 * 1024)))
            } }
            val progress = mutableListOf<String>()
            val created = service.create(Uri.fromFile(backup), settings, onProgress = progress::add)
            assertTrue(progress.contains("Verifying original documents: 1 of 1"))
            assertTrue(progress.contains("Writing backup archive: 1 of 1"))
            assertEquals(setOf("Reading ledger records", "Verifying original documents", "Writing backup archive", "Saving backup file", "Verifying saved backup"), created.phaseMillis.keys)
            assertTrue(created.phaseMillis.values.all { it >= 0 })
            assertEquals(java.math.BigInteger.valueOf(1850000L), created.financials.recordedPaise)
            assertEquals(java.math.BigInteger.ZERO, created.financials.paidPaise)
            assertEquals(java.math.BigInteger.valueOf(1850000L), created.financials.outstandingPaise)
            assertEquals(created.financials, service.inspect(Uri.fromFile(backup)).financials)
            assertEquals(created.counts, service.create(Uri.fromFile(encrypted), settings, "correct-native-password").counts)
            assertTrue(NativeBackupEncryption.isEncrypted(encrypted))
            assertEquals(created.counts, service.inspect(Uri.fromFile(encrypted), "correct-native-password").counts)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.inspect(Uri.fromFile(encrypted)) } }
            assertThrows(Exception::class.java) { runBlocking { service.restore(Uri.fromFile(encrypted), "wrong-native-password") } }
            assertEquals("Invoice", repository.attachment(id)!!.category)
            assertEquals(1, created.fileCount)
            assertEquals(projectId, service.inspect(Uri.fromFile(backup)).settings.getString("project"))
            assertTrue(service.inspect(Uri.fromFile(backup)).settings.getBoolean("reminders"))
            assertEquals(created.counts, service.inspect(Uri.fromFile(backup)).counts)
            rewriteZip(backup, badSettings) { name, bytes ->
                if (name == "manifest.json") JSONObject(bytes.toString(Charsets.UTF_8))
                    .put("settings", JSONObject().put("password", "must-not-import"))
                    .toString().toByteArray(Charsets.UTF_8) else bytes
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.inspect(Uri.fromFile(badSettings)) } }
            downgradeToSchema1(backup, older)
            assertEquals(0, service.inspect(Uri.fromFile(older)).settings.length())
            downgradeToSchema2(backup, olderTwo)
            assertEquals(created.counts, service.inspect(Uri.fromFile(olderTwo)).counts)
            downgradeToSchema3(backup, olderThree)
            assertEquals(created.counts, service.inspect(Uri.fromFile(olderThree)).counts)
            rewriteZip(older, badCounts) { name, bytes ->
                if (name == "manifest.json") JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                    getJSONObject("counts").put("projects", 2)
                }.toString().toByteArray(Charsets.UTF_8) else bytes
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.inspect(Uri.fromFile(badCounts)) } }
            store.delete(id)
            assertNull(repository.attachment(id))
            rewriteZip(backup, corrupted) { name, bytes ->
                if (name == "files/$id") bytes.apply { this[0] = (this[0].toInt() xor 1).toByte() } else bytes
            }
            assertThrows(Exception::class.java) { runBlocking { service.restore(Uri.fromFile(corrupted)) } }
            assertNull(repository.attachment(id))
            assertEquals(0, service.restore(Uri.fromFile(older)).settings.length())
            assertEquals(0, repository.exportRecords().getJSONArray("planned_classifications").length())
            assertEquals("", repository.attachment(id)!!.category)
            assertEquals("", repository.expense("invoice-$id")!!.invoiceNumber)
            assertEquals(3, repository.instalments(projectId).first().single().reminderDays)
            store.delete(id)
            assertEquals(created.counts, service.restore(Uri.fromFile(olderTwo)).counts)
            assertEquals("", repository.attachment(id)!!.notes)
            assertEquals("", repository.expense("invoice-$id")!!.invoiceNumber)
            assertEquals(3, repository.instalments(projectId).first().single().reminderDays)
            store.delete(id)
            assertEquals(created.counts, service.restore(Uri.fromFile(olderThree)).counts)
            assertEquals("", repository.expense("invoice-$id")!!.invoiceNumber)
            assertEquals(3, repository.instalments(projectId).first().single().reminderDays)
            store.delete(id)
            val restored = service.restore(Uri.fromFile(backup))
            assertEquals(restored.counts, service.restore(Uri.fromFile(encrypted), "correct-native-password").counts)
            assertEquals(created.counts, restored.counts)
            assertEquals(projectId, restored.settings.getString("project"))
            assertTrue(restored.settings.getBoolean("reminders"))
            assertFalse(restored.settings.getBoolean("showArchived"))
            val info = repository.attachment(id)!!
            assertEquals("%PDF-1.4\noriginal native bytes\n", store.fileFor(info).readText())
            assertEquals(info.sha256, DocumentPolicy.sha256(store.fileFor(info)))
            assertEquals(listOf("Invoice", "Approved", "R2", "Concrete", "Level 1", "Signed original"), listOf(info.category, info.status, info.revisionLabel, info.work, info.place, info.notes))
            assertEquals("INV-42", repository.expense("invoice-$id")!!.invoiceNumber)
            assertEquals(7, repository.instalments(projectId).first().single().reminderDays)
        } finally {
            repository.attachment(id)?.let { store.fileFor(it).delete() }
            original.delete(); backup.delete(); older.delete(); olderTwo.delete(); olderThree.delete(); badCounts.delete(); corrupted.delete(); badSettings.delete(); repository.close(); sandbox.deleteRecursively()
        }
    }

    @Test fun webEncryptedFixtureImportsAndRejectsWrongPasswordAndUnsafePath() = runBlocking {
        val (context, sandbox) = isolatedContext()
        val source = File(context.cacheDir, "legacy-fixture-${UUID.randomUUID()}.zip")
        val unsafe = File(context.cacheDir, "legacy-unsafe-${UUID.randomUUID()}.zip")
        val damaged = File(context.cacheDir, "legacy-damaged-${UUID.randomUUID()}.zip")
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("legacy_web_v1.zip").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val reader = LegacyArchiveReader(context)
            assertThrows(Exception::class.java) { runBlocking { reader.read(Uri.fromFile(source), "wrong-password-123") } }
            rewriteZip(source, unsafe) { name, bytes ->
                if (name == "header.json") bytes.toString(Charsets.UTF_8).replace("objects/legacy-pdf-1.enc", "../outside.enc").toByteArray()
                else bytes
            }
            assertThrows(Exception::class.java) { runBlocking { reader.read(Uri.fromFile(unsafe), "fixture-password-123") } }
            rewriteZip(source, damaged) { name, bytes ->
                if (name == "objects/legacy-pdf-1.enc") bytes.apply { this[0] = (this[0].toInt() xor 1).toByte() } else bytes
            }
            assertThrows(Exception::class.java) { runBlocking { reader.read(Uri.fromFile(damaged), "fixture-password-123") } }
            reader.read(Uri.fromFile(source), "fixture-password-123").use { legacy ->
                assertEquals("Legacy fixture house", legacy.state.getJSONObject("project").getString("name"))
                assertEquals("%PDF-1.4\noriginal fixture\n", legacy.files.getValue("legacy-pdf-1").readText())
                val mapped = LegacyImportMapper(context).map(legacy.state)
                val report = BackupService(context, repository, store).restorePrepared(mapped.records, legacy.files)
                assertEquals("BuildLedgerLegacy", report.format)
                assertEquals(1, report.fileCount)
                assertEquals("%PDF-1.4\noriginal fixture\n", store.fileFor(repository.attachment("legacy-pdf-1")!!).readText())
            }
        } finally {
            repository.attachment("legacy-pdf-1")?.let { store.fileFor(it).delete() }
            source.delete(); unsafe.delete(); damaged.delete(); repository.close(); sandbox.deleteRecursively()
        }
    }

    @Test fun preparedRestoreKeepsOldOriginalOnFailureAndAfterCommit() = runBlocking {
        val (context, sandbox) = isolatedContext()
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        val oldId = UUID.randomUUID().toString()
        val newId = UUID.randomUUID().toString()
        val projectId = UUID.randomUUID().toString()
        val old = File(store.root, "$oldId.bin")
        val source = File(context.cacheDir, "candidate-$newId.pdf")
        try {
            repository.saveProject(ProjectInfo(projectId, "House"))
            old.writeText("old original")
            repository.insertAttachment(AttachmentInfo(oldId, projectId, "Old", "text/plain", old.length(), DocumentPolicy.sha256(old), old.name, 1))
            source.writeText("%PDF-1.4\nnew original")
            val records = repository.exportRecords()
            val attachments = records.getJSONArray("attachments")
            attachments.remove(0)
            attachments.put(JSONObject().put("id", newId).put("projectId", projectId).put("name", "New")
                .put("mimeType", "application/pdf").put("size", source.length())
                .put("sha256", DocumentPolicy.sha256(source)).put("storageName", "$newId.bin")
                .put("createdAt", 2).put("revision", 0).put("parentId", JSONObject.NULL)
                .put("category", "").put("status", "").put("revisionLabel", "")
                .put("work", "").put("place", "").put("notes", ""))
            val service = BackupService(context, repository, store)
            source.appendText("damage")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.restorePrepared(records, mapOf(newId to source)) } }
            assertEquals("old original", old.readText())
            assertNotNull(repository.attachment(oldId))
            source.writeText("%PDF-1.4\nnew original")
            val report = service.restorePrepared(records, mapOf(newId to source))
            assertEquals(1, report.fileCount)
            assertNull(repository.attachment(oldId))
            assertEquals("old original", old.readText())
            assertEquals(source.readText(), store.fileFor(repository.attachment(newId)!!).readText())
        } finally {
            source.delete()
            old.delete()
            repository.attachment(newId)?.let { store.fileFor(it).delete() }
            repository.close()
            sandbox.deleteRecursively()
        }
    }

    private fun isolatedContext(): Pair<Context, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "backup-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir(): File = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        } to sandbox
    }

    private fun rewriteZip(source: File, target: File, changed: (String, ByteArray) -> ByteArray) {
        ZipFile(source).use { input -> ZipOutputStream(target.outputStream()).use { output ->
            val entries = input.entries()
            while (entries.hasMoreElements()) {
                val original = entries.nextElement()
                val bytes = changed(original.name, input.getInputStream(original).use { it.readBytes() })
                val entry = ZipEntry(original.name)
                if (original.method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        } }
    }

    private fun downgradeToSchema1(source: File, target: File) {
        val oldRecords = ZipFile(source).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("records.json")).use { it.readBytes().toString(Charsets.UTF_8) })
        }.apply {
            remove("planned_classifications")
            getJSONArray("expense_lines").let { lines ->
                for (index in 0 until lines.length()) lines.getJSONObject(index).remove("nature")
            }
            removeDocumentDetails(this)
            removeInvoiceReminderDetails(this)
        }.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(oldRecords).joinToString("") { "%02x".format(it) }
        rewriteZip(source, target) { name, bytes -> when (name) {
            "records.json" -> oldRecords
            "manifest.json" -> JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                put("schema", 1)
                getJSONObject("counts").remove("planned_classifications")
                put("recordsSha256", hash)
                remove("settings")
            }.toString().toByteArray(Charsets.UTF_8)
            else -> bytes
        } }
    }

    private fun downgradeToSchema2(source: File, target: File) {
        val oldRecords = ZipFile(source).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("records.json")).use { it.readBytes().toString(Charsets.UTF_8) })
        }.apply { removeDocumentDetails(this); removeInvoiceReminderDetails(this) }.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(oldRecords).joinToString("") { "%02x".format(it) }
        rewriteZip(source, target) { name, bytes -> when (name) {
            "records.json" -> oldRecords
            "manifest.json" -> JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                put("schema", 2)
                put("recordsSha256", hash)
            }.toString().toByteArray(Charsets.UTF_8)
            else -> bytes
        } }
    }

    private fun removeDocumentDetails(records: JSONObject) {
        val attachments = records.getJSONArray("attachments")
        for (index in 0 until attachments.length()) {
            val row = attachments.getJSONObject(index)
            listOf("category", "status", "revisionLabel", "work", "place", "notes").forEach { row.remove(it) }
        }
    }
    private fun downgradeToSchema3(source: File, target: File) {
        val oldRecords = ZipFile(source).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("records.json")).use { it.readBytes().toString(Charsets.UTF_8) })
        }.apply { removeInvoiceReminderDetails(this) }.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(oldRecords).joinToString("") { "%02x".format(it) }
        rewriteZip(source, target) { name, bytes -> when (name) {
            "records.json" -> oldRecords
            "manifest.json" -> JSONObject(bytes.toString(Charsets.UTF_8)).apply { put("schema", 3); put("recordsSha256", hash) }.toString().toByteArray(Charsets.UTF_8)
            else -> bytes
        } }
    }
    private fun removeInvoiceReminderDetails(records: JSONObject) {
        for (table in listOf("commitments", "instalments")) {
            val rows = records.getJSONArray(table)
            repeat(rows.length()) { index -> rows.getJSONObject(index).remove("note")
                if (table == "commitments") rows.getJSONObject(index).remove("calculationJson") }
        }
        records.getJSONArray("expenses").let { items -> for (index in 0 until items.length()) items.getJSONObject(index).remove("invoiceNumber") }
        records.getJSONArray("reminders").let { items -> for (index in 0 until items.length()) items.getJSONObject(index).remove("leadDays") }
    }
}
