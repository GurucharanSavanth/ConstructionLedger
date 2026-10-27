package com.constructioncost.nativeapp.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerDatabase
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReadModelsTest {
    @Test fun streamedInspectionAndReplacementReportBoundedRecordProgress() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, context)
        val file = File(context.cacheDir, "progress-${UUID.randomUUID()}.json")
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            database.openHelper.writableDatabase.apply {
                beginTransaction()
                try {
                    repeat(1001) { index -> execSQL("INSERT INTO activity_entries VALUES(?, 'project', 'Work', '2026-10-23', '', 0)", arrayOf("note-$index")) }
                    setTransactionSuccessful()
                } finally { endTransaction() }
            }
            repository.exportRecordsTo(file)
            val inspection = mutableListOf<Int>()
            repository.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION, inspection::add)
            assertEquals(listOf(1000, 1003), inspection)
            val replacement = mutableListOf<Int>()
            repository.replaceInspectedRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION, onProgress = replacement::add)
            assertEquals(listOf(1000, 1003), replacement)
            assertEquals(1001, repository.notes("project").first().size)
        } finally { repository.close(); file.delete() }
    }

    @Test fun journalUnicodeIdsKeepSqliteOrderWithoutRepeatingNextPage() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, context)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            val ids = listOf("\uE000", "\uD83D\uDE00")
            ids.forEach { repository.saveNote(ActivityNote(it, "project", "Work", "2026-10-22", "Fixture")) }
            database.openHelper.writableDatabase.execSQL("UPDATE audit_records SET createdAt=1000")
            val page = repository.journal("project", ExpenseFilter(), includeAudits = false, limit = 2).first()
            assertEquals(ids, page.map { it.event.id })
            val last = page.last().event
            val next = repository.journal("project", ExpenseFilter(), includeAudits = false, limit = 2,
                before = ActivityCursor(last.recordedAt, last.type, last.id)).first()
            assertTrue(next.isEmpty())
        } finally { repository.close() }
    }

    @Test fun upcomingWindowMovesWithoutAnyLedgerWrite() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveInstalment(InstalmentInput("today", "project", "Today", 1_000, "2026-10-22"))
            repository.saveInstalment(InstalmentInput("edge", "project", "Thirty days", 2_000, "2026-11-21"))
            repository.saveInstalment(InstalmentInput("tomorrow-edge", "project", "Next edge", 4_000, "2026-11-22"))
            assertEquals(3_000L, repository.summary("project", LocalDate.parse("2026-10-22")).first().upcomingPaise)
            assertEquals(6_000L, repository.summary("project", LocalDate.parse("2026-10-23")).first().upcomingPaise)
        } finally { repository.close() }
    }

    @Test fun journalPagesKeepCombinedTimestampTypeAndIdOrderAcrossTiesAndNewerInserts() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val repository = LedgerRepository(database, context)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveProject(ProjectInfo("other", "Other project"))
            repeat(9) { index ->
                val id = "record-$index"
                repository.saveNote(ActivityNote(id, "project", "Work $index", "2026-10-22", "Fixture"))
                repository.savePayment(PaymentInput(id = id, projectId = "project", amountPaise = 100, date = "2026-10-22"))
            }
            database.openHelper.writableDatabase.execSQL("UPDATE audit_records SET id='audit-'||recordType||'-'||recordId,createdAt=1000")
            val actual = mutableListOf<String>()
            var before: ActivityCursor? = null
            var pageCount = 0
            while (true) {
                val page = repository.journal("project", ExpenseFilter(), limit = 4, before = before).first()
                assertTrue(page.size <= 4)
                if (page.isEmpty()) break
                actual += page.map { "${it.event.type}:${it.event.id}" }
                val last = page.last().event
                before = ActivityCursor(last.recordedAt, last.type, last.id)
                if (pageCount++ == 0) repository.saveNote(ActivityNote("newer", "project", "New work", "2026-10-22", "After first page"))
                check(pageCount < 20) { "Journal cursor did not advance" }
            }
            val expected = (0..8).map { "AUDIT:audit-activity_entries-record-$it" } +
                (0..8).map { "AUDIT:audit-payments-record-$it" } + "AUDIT:audit-projects-project" +
                (0..8).map { "NOTE:record-$it" } + (0..8).map { "PAYMENT:record-$it" }
            assertEquals(37, actual.size)
            assertEquals(expected, actual)
            assertEquals(actual.size, actual.distinct().size)
            val filtered = repository.journal("project", ExpenseFilter(query = "Work 2"), recordType = "NOTE", includeAudits = false).first()
            assertEquals(listOf("record-2"), filtered.map { it.event.id })
        } finally { repository.close() }
    }
}
