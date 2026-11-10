package com.constructioncost.nativeapp.data.local

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@com.constructioncost.nativeapp.SeparateVerification
class LedgerSyncBenchmarkTest {
    @Test fun compareDefaultAndFullSyncForFiveHundredCommittedExpenses() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        suspend fun measure(fullSync: Boolean): Pair<Int, Long> {
            val name = "sync-${if (fullSync) "full" else "default"}-${UUID.randomUUID()}.db"
            var builder = Room.databaseBuilder(context, LedgerDatabase::class.java, name)
            if (fullSync) builder = builder.addCallback(LedgerDatabase.DURABILITY_CALLBACK)
            val database = builder.build()
            val repository = LedgerRepository(database, context)
            try {
                repository.saveProject(ProjectInfo("project", "Sync benchmark"))
                val synchronous = database.openHelper.writableDatabase.query("PRAGMA synchronous").use { it.moveToFirst(); it.getInt(0) }
                assertEquals(if (fullSync) 2 else 1, synchronous)
                val start = System.nanoTime()
                repeat(500) { index ->
                    repository.saveExpense(ExpenseInput(id = "expense-$index", projectId = "project", description = "Benchmark invoice $index",
                        amountPaise = 100, date = "2026-01-01"))
                }
                return synchronous to (System.nanoTime() - start) / 1_000_000L
            } finally {
                repository.close()
                context.deleteDatabase(name)
            }
        }
        val normal = measure(false)
        val full = measure(true)
        Log.i("BuildLedgerStorage", "500 committed expenses | NORMAL=${normal.second}ms | FULL=${full.second}ms")
    }
}
