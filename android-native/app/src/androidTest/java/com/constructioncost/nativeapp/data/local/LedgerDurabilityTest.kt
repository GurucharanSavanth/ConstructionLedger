package com.constructioncost.nativeapp.data.local

import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerDurabilityTest {
    @Test fun roomPragmasIntegrityAndCommittedRowsSurviveDatabaseReopen() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "durability-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(LedgerDatabase.DURABILITY_CALLBACK).build()
        var database = open()
        var repository = LedgerRepository(database, context)
        try {
            val sqlite = database.openHelper.writableDatabase
            fun value(pragma: String) = sqlite.query("PRAGMA $pragma").use { cursor ->
                check(cursor.moveToFirst()) { "PRAGMA $pragma returned no value" }
                cursor.getString(0)
            }
            val journalMode = value("journal_mode")
            val synchronous = database.withTransaction { value("synchronous").toInt() }
            val foreignKeys = value("foreign_keys").toInt()
            Log.i("BuildLedgerStorage", "journal_mode=$journalMode synchronous=$synchronous foreign_keys=$foreignKeys")
            assertEquals("wal", journalMode.lowercase())
            assertEquals(2, synchronous)
            assertEquals(1, foreignKeys)
            assertEquals("ok", value("integrity_check"))
            sqlite.query("PRAGMA foreign_key_check").use { assertTrue("Foreign-key violations found", !it.moveToFirst()) }

            repository.saveProject(ProjectInfo("durability-project", "Persistent house"))
            withContext(Dispatchers.IO) { repository.validateStorage() }
            assertTrue(context.getDatabasePath(name).isFile)
            repository.close()
            database = open()
            repository = LedgerRepository(database, context)
            withContext(Dispatchers.IO) { repository.validateStorage() }
            assertEquals("Persistent house", repository.projects.first().single().name)
        } finally {
            repository.close()
            context.deleteDatabase(name)
        }
    }
}
