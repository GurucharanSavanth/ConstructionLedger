package com.constructioncost.nativeapp.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerMigrationTest {
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
                val upgraded = Room.databaseBuilder(context, LedgerDatabase::class.java, name).addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4).build()
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
                } finally { upgraded.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
}
