package com.constructioncost.nativeapp.data.repository

import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerDatabase
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageFailureTest {
    @Test fun fullDatabaseRejectsWholeFinancialWriteAndCanRetryAfterSpaceReturns() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, context)
        try {
            repository.saveProject(ProjectInfo("p", "Disposable storage test"))
            repository.saveExpense(ExpenseInput("committed", projectId = "p", description = "Committed invoice",
                amountPaise = 10_000, paidPaise = 4_000, date = "2026-01-01"))
            val before = repository.exportRecords().toString()
            val sql = database.openHelper.writableDatabase
            val pages = sql.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
            // The pager limit triggers real SQLITE_FULL without filling emulator or host storage.
            sql.query("PRAGMA max_page_count=$pages").use { assertTrue(it.moveToFirst()); assertEquals(pages, it.getLong(0)) }
            val input = ExpenseInput("retry", projectId = "p", description = "New invoice", amountPaise = 20_000,
                paidPaise = 5_000, date = "2026-01-02", note = "Synthetic note ".repeat(20_000))
            val failure = runCatching { repository.saveExpense(input) }.exceptionOrNull()
            assertNotNull("A full database must reject the write", failure)
            assertEquals("Not enough storage space.", failure!!.message)
            assertTrue("Expected SQLiteFullException, got $failure", generateSequence(failure) { it.cause }.any { it is SQLiteFullException })
            assertEquals(before, repository.exportRecords().toString())
            assertEquals(10_000L, repository.summary("p").first().recordedPaise)
            assertEquals(4_000L, repository.summary("p").first().paidPaise)
            sql.query("PRAGMA max_page_count=2147483646").use { assertTrue(it.moveToFirst()); assertTrue(it.getLong(0) > pages) }
            repository.saveExpense(input)
            repository.saveExpense(input)
            assertEquals(30_000L, repository.summary("p").first().recordedPaise)
            assertEquals(9_000L, repository.summary("p").first().paidPaise)
            assertEquals(2, repository.expenses("p").first().size)
            repository.validateRecords(repository.exportRecords())
        } finally { repository.close() }
    }
}
