package com.constructioncost.nativeapp.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LedgerDatabase::class.java)

    @Test fun everyMigrationEdgePreservesFinancialRelationshipsAndValidatesExportedSchema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((start, end) in listOf(1 to 2, 2 to 3, 3 to 4, 1 to 4, 4 to 5, 1 to 5, 5 to 6, 1 to 6)) {
            val name = "ledger-edge-$start-$end-${System.nanoTime()}.db"
            try {
                helper.createDatabase(name, start).use { db ->
                    db.execSQL("INSERT INTO projects (id,name,revision,archived) VALUES ('project','House',5,0)")
                    db.execSQL("INSERT INTO budgets (id,projectId,amountPaise) VALUES ('project','project',100000)")
                    db.execSQL("INSERT INTO vendors (id,projectId,name,note) VALUES ('vendor','project','Supplier','Phone')")
                    db.execSQL("INSERT INTO classifications (id,projectId,name,kind,parentId,sortOrder,archived,nature) VALUES ('stage','project','Foundation','STAGE',NULL,0,0,'')")
                    db.execSQL("INSERT INTO classifications (id,projectId,name,kind,parentId,sortOrder,archived,nature) VALUES ('child','project','Concrete','SUBSTAGE','stage',1,0,'')")
                    db.execSQL("INSERT INTO commitments (id,projectId,title,amountPaise,vendorId,date,revision,archived) VALUES ('agreement','project','Foundation',10000,'vendor','2026-01-01',2,0)")
                    db.execSQL("INSERT INTO instalments (id,projectId,title,amountPaise,dueDate,commitmentId,vendorId,cancelled,remind,revision) VALUES ('plan','project','Milestone',10000,'2026-02-01','agreement','vendor',0,1,3)")
                    db.execSQL("INSERT INTO reminders (id,instalmentId,enabled) VALUES ('plan','plan',1)")
                    db.execSQL("INSERT INTO expenses (id,projectId,description,amountPaise,date,vendorId,note,mode,calculationJson,revision,voided,commitmentId,instalmentId,idempotencyKey,requestJson) VALUES ('invoice','project','Invoice',10000,'2026-01-01','vendor','','DIRECT_AMOUNT','',4,0,'agreement','plan','invoice','{}')")
                    db.execSQL("INSERT INTO expense_lines (id,expenseId,description,amountPaise,mode,quantity,rate,multiplier,quantityUnit,rateUnit,legacyJson) VALUES ('line','invoice','Concrete',10000,'DIRECT_AMOUNT','2','50','1','kg','kg','')")
                    if (start >= 2) {
                        db.execSQL("UPDATE expense_lines SET nature='Material' WHERE id='line'")
                        db.execSQL("INSERT INTO planned_classifications (id,classificationId,commitmentId,instalmentId) VALUES ('planned','child',NULL,'plan')")
                    }
                    db.execSQL("INSERT INTO classification_allocations (id,expenseId,classificationId,dimension,amountPaise) VALUES ('cost','invoice','child','WORK',10000)")
                    db.execSQL("INSERT INTO payments (id,projectId,amountPaise,date,vendorId,reference,note,kind,originalPaymentId,instalmentId,idempotencyKey,requestJson) VALUES ('payment','project',4000,'2026-01-01','vendor','REF-1','','PAYMENT',NULL,'plan','payment','{}')")
                    db.execSQL("INSERT INTO payment_allocations (id,paymentId,expenseId,amountPaise) VALUES ('paid','payment','invoice',4000)")
                    db.execSQL("INSERT INTO attachments (id,projectId,name,mimeType,size,sha256,storageName,createdAt,revision,parentId) VALUES ('document','project','Receipt.pdf','application/pdf',123,'${"a".repeat(64)}','receipt.pdf',99,6,NULL)")
                    db.execSQL("INSERT INTO attachment_links (id,attachmentId,expenseId,paymentId,noteId) VALUES ('link','document','invoice',NULL,NULL)")
                    if (start >= 3) db.execSQL("UPDATE attachments SET category='Invoice',notes='Approved' WHERE id='document'")
                }
                helper.runMigrationsAndValidate(name, end, true, LedgerDatabase.MIGRATION_1_2,
                    LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4, LedgerDatabase.MIGRATION_4_5, LedgerDatabase.MIGRATION_5_6).use { db ->
                    db.query("SELECT e.amountPaise,p.amountPaise,a.amountPaise,e.revision,p.instalmentId,e.vendorId FROM expenses e JOIN payment_allocations a ON a.expenseId=e.id JOIN payments p ON p.id=a.paymentId").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(10000L, cursor.getLong(0)); assertEquals(4000L, cursor.getLong(1)); assertEquals(4000L, cursor.getLong(2))
                        assertEquals(4L, cursor.getLong(3)); assertEquals("plan", cursor.getString(4)); assertEquals("vendor", cursor.getString(5))
                    }
                    db.query("SELECT quantity,rate,quantityUnit,rateUnit,nature FROM expense_lines WHERE id='line'").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(listOf("2", "50", "kg", "kg", if (start >= 2) "Material" else ""), (0..4).map { cursor.getString(it) })
                    }
                    db.query("SELECT parentId FROM classifications WHERE id='child'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("stage", cursor.getString(0)) }
                    db.query("SELECT COUNT(*) FROM attachment_links WHERE attachmentId='document' AND expenseId='invoice'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0)) }
                    if (end >= 3) db.query("SELECT category,notes FROM attachments WHERE id='document'").use { cursor ->
                        assertTrue(cursor.moveToFirst()); assertEquals(if (start >= 3) "Invoice" else "", cursor.getString(0)); assertEquals(if (start >= 3) "Approved" else "", cursor.getString(1))
                    }
                    if (end >= 4) {
                        db.query("SELECT invoiceNumber FROM expenses WHERE id='invoice'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("", cursor.getString(0)) }
                        db.query("SELECT leadDays FROM reminders WHERE id='plan'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(3, cursor.getInt(0)) }
                    }
                    if (end == 5) {
                        db.query("SELECT note,calculationJson FROM commitments").use { cursor -> while (cursor.moveToNext()) { assertEquals("", cursor.getString(0)); assertEquals("", cursor.getString(1)) } }
                        db.query("SELECT note FROM instalments WHERE id='plan'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("", cursor.getString(0)) }
                    }
                    if (end == 6) db.query("SELECT COUNT(*) FROM project_deletions").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
                    db.query("PRAGMA foreign_key_check").use { assertTrue(!it.moveToFirst()) }
                }
                val migrated = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4, LedgerDatabase.MIGRATION_4_5, LedgerDatabase.MIGRATION_5_6)
                    .addCallback(LedgerDatabase.DURABILITY_CALLBACK).build()
                val repository = LedgerRepository(migrated, context)
                try {
                    runBlocking {
                        repository.validateStorage()
                        val project = repository.projects.first().single()
                        assertEquals("project", project.id)
                        assertEquals(100_000L, project.budgetPaise)
                        val summary = repository.summary(project.id).first()
                        assertEquals(10_000L, summary.recordedPaise)
                        assertEquals(4_000L, summary.paidPaise)
                        assertEquals(6_000L, summary.outstandingPaise)
                        assertEquals(0L, summary.advancePaise)
                        assertEquals(4L, repository.expense("invoice")!!.revision)
                        assertEquals("plan", repository.payments(project.id).first().single().instalmentId)
                    }
                } finally { repository.close() }
            } finally { context.deleteDatabase(name) }
        }
    }

    @Test fun versionOneThroughThreeRowsSurviveVersionFourMigration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (version in listOf(1, 2, 3)) {
            val name = "ledger-migration-$version-${System.nanoTime()}.db"
            val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("com.constructioncost.nativeapp.data.local.LedgerDatabase/$version.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            try {
                context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: JSONArray()
                        for (item in 0 until indices.length()) db.execSQL(indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES (42,?)", arrayOf(schema.getString("identityHash")))
                    db.execSQL("INSERT INTO projects (id,name,revision,archived) VALUES ('project','Build',0,0)")
                    db.execSQL("INSERT INTO expenses (id,projectId,description,amountPaise,date,vendorId,note,mode,calculationJson,revision,voided,commitmentId,instalmentId,idempotencyKey,requestJson) VALUES ('expense','project','Invoice',1850000,'2026-01-01',NULL,'','DIRECT_AMOUNT','',0,0,NULL,NULL,'expense','{}')")
                    db.execSQL("INSERT INTO expense_lines (id,expenseId,description,amountPaise,mode,quantity,rate,multiplier,quantityUnit,rateUnit,legacyJson) VALUES ('line','expense','Concrete',1850000,'DIRECT_AMOUNT',NULL,NULL,'1','unit','unit','')")
                    db.execSQL("INSERT INTO attachments (id,projectId,name,mimeType,size,sha256,storageName,createdAt,revision,parentId) VALUES ('document','project','Drawing','application/pdf',123,'${"a".repeat(64)}','drawing.pdf',99,2,NULL)")
                    db.execSQL("INSERT INTO instalments (id,projectId,title,amountPaise,dueDate,commitmentId,vendorId,cancelled,remind,revision) VALUES ('plan','project','Milestone',900000,'2026-02-01',NULL,NULL,0,1,0)")
                    db.execSQL("INSERT INTO reminders (id,instalmentId,enabled) VALUES ('plan','plan',1)")
                    db.version = version
                }
                val upgraded = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
                    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4, LedgerDatabase.MIGRATION_4_5, LedgerDatabase.MIGRATION_5_6)
                    .addCallback(LedgerDatabase.DURABILITY_CALLBACK).build()
                try {
                    upgraded.openHelper.writableDatabase.query("SELECT amountPaise,nature FROM expense_lines WHERE id='line'").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1_850_000L, cursor.getLong(0))
                        assertEquals("", cursor.getString(1))
                    }
                    upgraded.openHelper.writableDatabase.query("SELECT COUNT(*) FROM planned_classifications").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0L, cursor.getLong(0))
                    }
                    upgraded.openHelper.writableDatabase.query("SELECT amountPaise,invoiceNumber FROM expenses WHERE id='expense'").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1_850_000L, cursor.getLong(0))
                        assertEquals("", cursor.getString(1))
                    }
                    upgraded.openHelper.writableDatabase.query("SELECT enabled,leadDays FROM reminders WHERE id='plan'").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                        assertEquals(3, cursor.getInt(1))
                    }
                    upgraded.openHelper.writableDatabase.query("SELECT projectId,name,size,sha256,storageName,createdAt,revision,category,status,revisionLabel,work,place,notes FROM attachments WHERE id='document'").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("project", cursor.getString(0))
                        assertEquals("Drawing", cursor.getString(1))
                        assertEquals(123L, cursor.getLong(2))
                        assertEquals("a".repeat(64), cursor.getString(3))
                        assertEquals("drawing.pdf", cursor.getString(4))
                        assertEquals(99L, cursor.getLong(5))
                        assertEquals(2L, cursor.getLong(6))
                        for (index in 7..12) assertEquals("", cursor.getString(index))
                    }
                    val repository = LedgerRepository(upgraded, context)
                    try { runBlocking { repository.validateStorage() } } finally { repository.close() }
                } finally { upgraded.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
}
