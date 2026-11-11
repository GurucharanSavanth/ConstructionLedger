package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.ActivityNote
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkLogRevisionTest {
    @Test fun competingRevisionZeroEditsCommitOnlyOneNoteAndSaveAudit() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            val draft = ActivityNote("note", "project", "Site", "2026-01-01", "Original")
            repository.saveNote(draft)
            val start = CompletableDeferred<Unit>()
            val edits = listOf("First", "Second").map { value -> async(Dispatchers.Default) {
                start.await()
                try { repository.saveNote(draft.copy(note = value), expectedRevision = 0); true }
                catch (_: IllegalArgumentException) { false }
            } }
            start.complete(Unit)
            assertEquals(1, edits.awaitAll().count { it })
            val records = repository.exportRecords()
            val note = records.getJSONArray("activity_entries")
            assertEquals(1, note.length())
            assertEquals(1L, note.getJSONObject(0).getLong("revision"))
            assertEquals(true, note.getJSONObject(0).getString("note") in listOf("First", "Second"))
            val audits = records.getJSONArray("audit_records")
            assertEquals(2, (0 until audits.length()).map { audits.getJSONObject(it) }
                .count { it.getString("recordType") == "activity_entries" && it.getString("recordId") == "note" && it.getString("action") == "SAVE" })
        } finally { repository.close() }
    }

    @Test fun deletedAndRestoredAwayEditsCannotRecreateNotesOrLinksOrAudits() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.insertAttachment(AttachmentInfo("file", "project", "site.txt", "text/plain", 1, "a".repeat(64), "site.bin", 1))
            val withoutNote = repository.exportRecords()
            val draft = ActivityNote("note", "project", "Site", "2026-01-01", "Original")
            repository.saveNote(draft)
            repository.linkAttachment("file", noteId = "note")
            assertEquals(0L, repository.exportRecords().getJSONArray("activity_entries").getJSONObject(0).getLong("revision"))
            repository.saveNote(draft.copy(note = "Edited"), expectedRevision = 0)
            assertEquals("Edited", repository.exportRecords().getJSONArray("activity_entries").getJSONObject(0).getString("note"))
            assertEquals(1L, repository.exportRecords().getJSONArray("activity_entries").getJSONObject(0).getLong("revision"))
            suspend fun rejectsWithoutWrites(expected: Long?) {
                val before = repository.exportRecords().toString()
                var rejected = false
                try { repository.saveNote(draft.copy(note = "Stale"), expectedRevision = expected) }
                catch (_: IllegalArgumentException) { rejected = true }
                assertEquals(true, rejected)
                assertEquals(before, repository.exportRecords().toString())
            }
            rejectsWithoutWrites(0)
            rejectsWithoutWrites(null)
            repository.deleteNote("note", 1)
            rejectsWithoutWrites(0)
            rejectsWithoutWrites(1)
            assertEquals(0, repository.exportRecords().getJSONArray("activity_entries").length())
            assertEquals(0, repository.exportRecords().getJSONArray("attachment_links").length())
            repository.saveNote(draft)
            repository.linkAttachment("file", noteId = "note")
            repository.replaceRecords(withoutNote)
            rejectsWithoutWrites(0)
            assertEquals(0, repository.exportRecords().getJSONArray("activity_entries").length())
            assertEquals(0, repository.exportRecords().getJSONArray("attachment_links").length())
            repository.saveNote(draft.copy(id = "new-note"))
            assertEquals("new-note", repository.exportRecords().getJSONArray("activity_entries").getJSONObject(0).getString("id"))
            repository.validateRecords(repository.exportRecords())
        } finally { repository.close() }
    }
}
