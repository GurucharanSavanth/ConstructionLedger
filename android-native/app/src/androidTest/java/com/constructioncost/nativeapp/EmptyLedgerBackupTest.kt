package com.constructioncost.nativeapp

import android.net.Uri
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.ui.ledgerPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.UUID

@SeparateVerification
class EmptyLedgerBackupTest {
    @Test fun emptyLedgerBackupPreservesZeroProjectsAndOriginals() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ConstructionApplication
        check(app.packageName == "com.constructioncost.nativeapp.verification")
        val repository = app.repository
        repository.validateStorage()
        val before = repository.exportRecords()
        val balances = repository.projects.first().associate { it.id to repository.summary(it.id).first() }
        val preferences = app.ledgerPreferences.data.first()
        val store = AttachmentStore(app, repository)
        val originalRows = repository.projects.first().flatMap { repository.attachments(it.id).first() }
        val originals = originalRows.associate { row -> store.fileFor(row) to DocumentPolicy.sha256(store.fileFor(row)) }
        val archive = File(app.cacheDir, "empty-ledger-${UUID.randomUUID()}.zip")
        val operations = LedgerOperations(app)
        try {
            val empty = org.json.JSONObject(before.toString())
            for (key in empty.keys().asSequence().toList()) if (empty.opt(key) is JSONArray) empty.put(key, JSONArray())
            repository.replaceRecords(empty)
            app.ledgerPreferences.edit { it.remove(stringPreferencesKey("project")) }
            instrumentation.runOnMainSync { operations.createBackup(Uri.fromFile(archive), "empty-ledger-password") }
            val result = withTimeout(30_000) { operations.state.first { !it.busy && it.message != null } }
            assertEquals("Backup saved: 0 expenses, 0 files", result.message)
            val service = BackupService(app, repository, store)
            val report = service.inspect(Uri.fromFile(archive), "empty-ledger-password")
            assertEquals(0, report.counts.getValue("projects"))
            assertEquals(0, report.fileCount)
            assertEquals("", report.settings.getString("project"))
            assertEquals(BigInteger.ZERO, report.financials.recordedPaise)
            assertEquals(BigInteger.ZERO, report.financials.paidPaise)
            assertEquals(BigInteger.ZERO, report.financials.outstandingPaise)
            val sandbox = File(app.cacheDir, "empty-restore-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val isolated = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
                override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
                override fun getDatabasePath(name: String) = File(sandbox, "databases/$name").apply { parentFile!!.mkdirs() }
            }
            val restored = LedgerRepository(isolated)
            try {
                BackupService(isolated, restored, AttachmentStore(isolated, restored))
                    .restore(Uri.fromFile(archive), "empty-ledger-password")
                assertTrue(restored.projects.first().isEmpty())
                val records = restored.exportRecords()
                for (key in records.keys()) if (records.opt(key) is JSONArray) assertEquals(0, records.getJSONArray(key).length())
                restored.validateStorage()
            } finally {
                restored.close()
                check(sandbox.canonicalFile.parentFile == app.cacheDir.canonicalFile)
                sandbox.deleteRecursively()
            }
            assertTrue(repository.projects.first().isEmpty())
            repository.validateStorage()
        } finally {
            withContext(NonCancellable) { withTimeout(60_000) { operations.state.first { !it.busy } } }
            repository.replaceRecords(before)
            app.ledgerPreferences.updateData { preferences }
            for ((project, balance) in balances) assertEquals(balance, repository.summary(project).first())
            for (row in originalRows) {
                val restored = requireNotNull(repository.attachment(row.id))
                assertEquals(listOf(row.projectId, row.storageName, row.size, row.sha256),
                    listOf(restored.projectId, restored.storageName, restored.size, restored.sha256))
            }
            for ((file, hash) in originals) assertEquals(hash, DocumentPolicy.sha256(file))
            repository.validateStorage()
            operations.acknowledge()
            archive.delete()
        }
    }
}
