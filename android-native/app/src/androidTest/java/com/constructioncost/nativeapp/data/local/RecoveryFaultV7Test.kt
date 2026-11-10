package com.constructioncost.nativeapp.data.local

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecoveryFaultV7Test {
    private enum class Fault(val expected: LedgerRecoveryState) {
        ROOM_METADATA(LedgerRecoveryState.DATABASE_OPEN_FAILURE),
        MIGRATION(LedgerRecoveryState.DATABASE_MIGRATION_FAILURE)
    }

    @Test fun roomMetadataMismatchPreservesRecordsOriginalsAndRestoresKnownBackup() = runBlocking<Unit> {
        exercise(Fault.ROOM_METADATA)
    }

    @Test fun failedMigrationPreservesRecordsOriginalsAndRestoresKnownBackup() = runBlocking<Unit> {
        exercise(Fault.MIGRATION)
    }

    private fun logical(records: JSONObject): Map<String, List<Map<String, String>>> =
        LedgerRepository.TABLES.associateWith { table ->
            val array = records.getJSONArray(table)
            (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                row.keys().asSequence().filter { key -> key != "revision" && !(table == "attachments" && key == "storageName") }
                    .associateWith { key -> row.get(key).toString() }
            }
        }

    private suspend fun exercise(fault: Fault) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "recovery-v7-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
            override fun getDatabasePath(name: String) = File(sandbox, "databases/$name").apply { parentFile?.mkdirs() }
        }
        val recovery = LedgerRecoveryStateStore(context)
        val databaseFile = context.getDatabasePath(LedgerRecoveryStateStore.DATABASE_NAME)
        var open: LedgerRepository? = null
        try {
            val healthy = LedgerRepository(context).also { open = it }
            healthy.validateStorage()
            recovery.markLedgerValidated()
            healthy.saveProject(ProjectInfo("project", "Fault recovery fixture"))
            healthy.saveExpense(ExpenseInput(id = "expense", projectId = "project", description = "Preserved invoice",
                amountPaise = 12_345, date = "2026-10-22"))
            healthy.savePayment(PaymentInput(id = "payment", projectId = "project", amountPaise = 4_321,
                date = "2026-10-22", allocations = listOf(PaymentAllocationInput("expense", 4_321))))
            val store = AttachmentStore(context, healthy)
            val originals = linkedMapOf<File, String>()
            val documents = listOf(
                "first" to "%PDF-1.4\nFirst synthetic original\n%%EOF\n".toByteArray(),
                "second" to "%PDF-1.4\nSecond synthetic original\n%%EOF\n".toByteArray(),
                "image" to Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRXkAAAAASUVORK5CYII="))
            val attachments = documents.map { (id, bytes) ->
                val file = File(store.root, "$id.bin").apply { writeBytes(bytes) }
                val hash = DocumentPolicy.sha256(file)
                originals[file] = hash
                AttachmentInfo(id, "project", if (id == "image") "Image.png" else "$id.pdf",
                    if (id == "image") "image/png" else "application/pdf", file.length(), hash, file.name, 1)
            }
            healthy.insertAttachments(attachments, "expense")
            val orphan = File(store.root, "unreferenced.bin").apply { writeText("Uncertain permanent original") }
            originals[orphan] = DocumentPolicy.sha256(orphan)
            val expected = logical(healthy.exportRecords())
            val summary = healthy.summary("project").first()
            val backup = File(sandbox, "external/known-good.zip").apply { parentFile?.mkdirs() }
            BackupService(context, healthy, store).create(Uri.fromFile(backup))
            val backupHash = DocumentPolicy.sha256(backup)
            val auditCount = healthy.exportRecords().getJSONArray("audit_records").length()
            healthy.close()
            open = null

            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                when (fault) {
                    Fault.ROOM_METADATA -> raw.execSQL("UPDATE room_master_table SET identity_hash='injected-v7-mismatch' WHERE id=42")
                    Fault.MIGRATION -> raw.version = 4
                }
            }
            recovery.beginStartup()
            val broken = LedgerRepository(context).also { open = it }
            val failure = runCatching { broken.validateStorage() }.exceptionOrNull()
            assertNotNull("$fault must fail closed", failure)
            assertEquals(fault.expected, recovery.state())
            assertTrue(recovery.isRecoveryRequired())
            assertFalse(recovery.isLedgerValidated())
            val blockedStore = AttachmentStore(context, broken)
            assertThrows(IllegalStateException::class.java) { runBlocking { blockedStore.reconcile() } }
            broken.close()
            open = null
            originals.forEach { (file, hash) -> assertEquals(file.name, hash, DocumentPolicy.sha256(file)) }
            assertEquals(backupHash, DocumentPolicy.sha256(backup))

            SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                fun value(sql: String): Long = raw.rawQuery(sql, null).use { cursor ->
                    assertTrue(cursor.moveToFirst()); cursor.getLong(0)
                }
                assertEquals(1L, value("SELECT COUNT(*) FROM projects WHERE id='project'"))
                assertEquals(12_345L, value("SELECT amountPaise FROM expenses WHERE id='expense'"))
                assertEquals(4_321L, value("SELECT amountPaise FROM payments WHERE id='payment'"))
                assertEquals(4_321L, value("SELECT amountPaise FROM payment_allocations WHERE paymentId='payment' AND expenseId='expense'"))
                assertEquals(auditCount.toLong(), value("SELECT COUNT(*) FROM audit_records"))
            }
            // Every database handle is closed before moving the complete available SQLite family.
            val quarantine = File(sandbox, "quarantine").apply { check(mkdirs()) }
            val family = listOf("", "-wal", "-shm", "-journal", ".restore-revision", ".restore-revision.bak", ".restore-revision.new")
                .map { File(databaseFile.path + it) }.filter { it.exists() }
            assertTrue(family.contains(databaseFile))
            family.forEach { source ->
                check(source.canonicalFile.parentFile == databaseFile.canonicalFile.parentFile)
                val target = File(quarantine, source.name)
                check(!target.exists() && source.renameTo(target))
            }
            recovery.markRecoveryRequired(LedgerRecoveryState.RESTORE_IN_PROGRESS, "Disposable fault recovery drill")
            val restored = LedgerRepository(context).also { open = it }
            val restoredStore = AttachmentStore(context, restored)
            val report = BackupService(context, restored, restoredStore).restore(Uri.fromFile(backup))
            assertEquals(3, report.fileCount)
            restored.validateStorage()
            recovery.markLedgerValidated()
            assertEquals(LedgerRecoveryState.HEALTHY_EXISTING_DATABASE, recovery.state())
            assertFalse(recovery.isRecoveryRequired())
            assertTrue(recovery.isLedgerValidated())
            assertEquals(summary, restored.summary("project").first())
            assertEquals(expected, logical(restored.exportRecords()))
            attachments.forEach { original ->
                val metadata = requireNotNull(restored.attachment(original.id))
                assertEquals(original.sha256, DocumentPolicy.sha256(restoredStore.fileFor(metadata)))
            }
            restoredStore.reconcile()
            originals.forEach { (file, hash) -> assertEquals(file.name, hash, DocumentPolicy.sha256(file)) }
            assertEquals(backupHash, DocumentPolicy.sha256(backup))
        } finally {
            open?.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("recovery-v7-"))
            sandbox.deleteRecursively()
        }
    }
}
