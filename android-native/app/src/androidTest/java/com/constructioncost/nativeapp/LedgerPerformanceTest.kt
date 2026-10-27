package com.constructioncost.nativeapp

import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.export.ProjectWorkbookExport
import com.constructioncost.nativeapp.data.local.LedgerDatabase
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.domain.ExpenseFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@SeparateVerification
class LedgerPerformanceTest {
    @Test fun tenThousandExpenses() = exercise(10000, backup = true)
    @Test fun hundredThousandExpenses() = exercise(100000, backup = true)

    private fun exercise(count: Int, backup: Boolean) = runBlocking(Dispatchers.IO) {
        val expectedPaise = when (count) { 10000 -> 1_000_000L; 100000 -> 10_000_000L; else -> error("Unknown fixture size") }
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(original.cacheDir, "stress-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(original) {
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
        }
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repo = LedgerRepository(database, context)
        try {
            val sql = database.openHelper.writableDatabase
            val started = SystemClock.elapsedRealtime()
            sql.beginTransaction()
            try {
                sql.execSQL("INSERT INTO projects VALUES('stress','Synthetic house',0,0)")
                repeat(1000) { index ->
                    sql.execSQL("INSERT INTO classifications VALUES(?, 'stress', ?, 'STAGE', NULL, ?, 0, '')",
                        arrayOf<Any?>("stage-$index", "Stage $index", index))
                }
                val expense = sql.compileStatement("INSERT INTO expenses(id,projectId,description,amountPaise,date,vendorId,note,mode,calculationJson,revision,voided,commitmentId,instalmentId,idempotencyKey,requestJson) VALUES(?, 'stress', ?, 100, '2026-01-01', NULL, '', 'DIRECT_AMOUNT', '', 0, 0, NULL, NULL, ?, '{}')")
                val line = sql.compileStatement("INSERT INTO expense_lines VALUES(?, ?, 'Synthetic item', 100, 'DIRECT_AMOUNT', NULL, NULL, '1', 'unit', 'unit', '', 'Material')")
                val allocation = sql.compileStatement("INSERT INTO classification_allocations VALUES(?, ?, ?, 'WORK', 100)")
                val audit = sql.compileStatement("INSERT INTO audit_records VALUES(?, 'expenses', ?, 'CREATE', 'null', '{\"amountPaise\":100,\"projectId\":\"stress\"}', 1)")
                try {
                    repeat(count) { index ->
                        val id = "expense-$index"
                        expense.bindString(1, id); expense.bindString(2, "Expense $index"); expense.bindString(3, id); expense.executeInsert()
                        line.bindString(1, "line-$index"); line.bindString(2, id); line.executeInsert()
                        allocation.bindString(1, "class-$index"); allocation.bindString(2, id); allocation.bindString(3, "stage-${index % 1000}"); allocation.executeInsert()
                        audit.bindString(1, "audit-$index"); audit.bindString(2, id); audit.executeInsert()
                    }
                } finally { expense.close(); line.close(); allocation.close(); audit.close() }
                sql.setTransactionSuccessful()
            } finally { sql.endTransaction() }
            Log.i("BuildLedgerPerformance", "$count expenses | insert | ${SystemClock.elapsedRealtime() - started} ms")
            suspend fun measure(label: String, action: suspend () -> Unit) {
                val time = SystemClock.elapsedRealtime(); action()
                Log.i("BuildLedgerPerformance", "$count expenses | $label | ${SystemClock.elapsedRealtime() - time} ms")
            }
            measure("dashboard") { val summary = repo.summary("stress").first(); assertEquals(expectedPaise, summary.recordedPaise); assertEquals(0L, summary.paidPaise); assertEquals(expectedPaise, summary.outstandingPaise); assertEquals(0L, summary.advancePaise) }
            measure("last history page") { assertEquals(75, repo.expenses("stress", limit = 75, offset = count - 75).first().size) }
            measure("search") { assertEquals("expense-${count - 1}", repo.expenses("stress", ExpenseFilter(query = "Expense ${count - 1}")).first().single().id) }
            measure("1000-node drilldown") { assertEquals(expectedPaise, repo.insights("stress").first().sumOf { it.amountPaise }) }
            var csvHash = ""
            measure("streamed CSV") {
                val file = File(context.cacheDir, "ledger.csv")
                LedgerExports.csv(context.contentResolver, Uri.fromFile(file), repo, "stress")
                assertTrue(file.length() > 0)
                csvHash = DocumentPolicy.sha256(file)
            }
            val repeatedCsv = File(context.cacheDir, "ledger-repeat.csv")
            LedgerExports.csv(context.contentResolver, Uri.fromFile(repeatedCsv), repo, "stress")
            assertEquals(csvHash, DocumentPolicy.sha256(repeatedCsv))
            if (backup) {
                val store = AttachmentStore(context, repo)
                val originals = mutableMapOf<String, String>()
                repeat(1000) { index ->
                    val file = File(store.root, "document-$index.bin").apply { writeText("Synthetic receipt $index") }
                    originals[file.name] = DocumentPolicy.sha256(file)
                    sql.execSQL("INSERT INTO attachments(id,projectId,name,mimeType,size,sha256,storageName,createdAt,revision,parentId) VALUES(?, 'stress', ?, 'text/plain', ?, ?, ?, 1, 0, NULL)",
                        arrayOf<Any?>("document-$index", "Receipt $index", file.length(), originals.getValue(file.name), file.name))
                }
                measure("verified project XLSX export with 1000 originals") {
                    val projectArchive = ProjectWorkbookExport.create(context.cacheDir, repo, store, "stress") {}
                    try {
                        val entries = ZipFile(projectArchive).use { zip -> zip.entries().asSequence().count() }
                        assertEquals(1005, entries)
                        Log.i("BuildLedgerPerformance", "$count expenses | project XLSX package | ${projectArchive.length()} bytes | $entries entries")
                    } finally { assertTrue(projectArchive.delete()) }
                }
                fun expenseAuditCount(): Long = sql.query("SELECT COUNT(*) FROM audit_records a JOIN expenses e ON e.id=a.recordId WHERE a.recordType='expenses' AND e.projectId='stress'").use { cursor ->
                    assertTrue(cursor.moveToFirst()); cursor.getLong(0)
                }
                assertEquals(count.toLong(), expenseAuditCount())
                val service = BackupService(context, repo, store)
                val archive = Uri.fromFile(File(context.cacheDir, "backup.zip"))
                measure("backup with1000 originals") { val report = service.create(archive); assertEquals(count, report.counts["expenses"]); assertEquals(1000, report.fileCount) }
                measure("verified restore with1000 originals") { val report = service.restore(archive); assertEquals(count, report.counts["expenses"]); assertEquals(1000, report.fileCount) }
                val restored = repo.summary("stress").first()
                assertEquals(expectedPaise, restored.recordedPaise)
                assertEquals(0L, restored.paidPaise)
                assertEquals(expectedPaise, restored.outstandingPaise)
                assertEquals(0L, restored.advancePaise)
                assertEquals(count.toLong(), expenseAuditCount())
                originals.forEach { (name, hash) -> assertEquals(name, hash, DocumentPolicy.sha256(File(store.root, name))) }
            }
        } finally { repo.close(); sandbox.deleteRecursively() }
    }
}
