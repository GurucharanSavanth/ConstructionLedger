package com.constructioncost.nativeapp.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.domain.ActivityNote
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProjectDeletionTest {
    @Test fun retainedRevisionZeroEditCannotRecreateADeletedProject() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val retained = ProjectInfo("deleted-project", "First house", budgetPaise = 12_345)
            repository.saveProject(retained)
            repository.saveProject(ProjectInfo("kept-project", "Other house", budgetPaise = 98_765))
            repository.beginProjectDeletion(retained.id)
            repository.markProjectDeletionQuarantined(retained.id)
            repository.deleteProjectRows(retained.id)
            repository.completeProjectDeletion(retained.id)
            val before = repository.exportRecords().toString()

            val failure = runCatching {
                repository.saveProject(retained.copy(name = "Stale edit", budgetPaise = 1), requireExisting = true)
            }.exceptionOrNull()

            assertEquals("Project no longer exists", failure?.message)
            assertEquals(before, repository.exportRecords().toString())
            assertEquals(listOf("kept-project"), repository.projects.first().map { it.id })
            assertEquals(98_765L, repository.projects.first().single().budgetPaise)
        } finally { repository.close() }
    }

    @Test fun journalBlocksWritesDeletesOnlyOwnedRowsAndAllowsAnEmptyLedger() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        try {
            repository.saveProject(ProjectInfo("delete-project", "North wing"))
            repository.saveProject(ProjectInfo("keep-project", "South wing"))
            repository.saveExpense(ExpenseInput(id = "north-bill", projectId = "delete-project", description = "North invoice", amountPaise = 15005, date = "2026-10-23"))
            repository.saveExpense(ExpenseInput(id = "south-bill", projectId = "keep-project", description = "South invoice", amountPaise = 99005, date = "2026-10-23"))
            repository.insertAttachment(AttachmentInfo("north-file", "delete-project", "receipt.pdf", "application/pdf", 4, "a".repeat(64), "north.bin", 1))
            val inventory = repository.projectDeletionInventory("delete-project")
            assertEquals("North wing", inventory.summary.projectName)
            assertEquals(1L, inventory.summary.recordCount)
            assertEquals(1L, inventory.summary.attachmentCount)
            assertEquals(4L, inventory.summary.attachmentBytes)

            assertEquals("PREPARED", repository.beginProjectDeletion("delete-project").phase)
            assertFalse(repository.projects.first().any { it.id == "delete-project" })
            val error = runCatching {
                repository.saveNote(ActivityNote("blocked-note", "delete-project", "No write", "2026-10-23", ""))
            }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("deletion is in progress"))
            val expenses = repository.exportRecords().getJSONArray("expenses")
            assertEquals(listOf("North invoice", "South invoice"), (0 until expenses.length()).map { expenses.getJSONObject(it).getString("description") })

            repository.markProjectDeletionQuarantined("delete-project")
            repository.deleteProjectRows("delete-project")
            assertEquals("ROWS_DELETED", repository.projectDeletionJournals().single().phase)
            repository.completeProjectDeletion("delete-project")
            assertEquals(listOf("keep-project"), repository.projects.first().map { it.id })

            repository.beginProjectDeletion("keep-project")
            repository.markProjectDeletionQuarantined("keep-project")
            repository.deleteProjectRows("keep-project")
            repository.completeProjectDeletion("keep-project")
            assertTrue(repository.projects.first().isEmpty())
        } finally { repository.close() }
    }

    @Test fun restartFinishesPreparedAndRowsDeletedQuarantineWithoutTouchingOtherProjects() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "project-delete-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
            override fun getDatabasePath(name: String) = File(sandbox, "database/$name").apply { parentFile?.mkdirs() }
        }
        var repository: LedgerRepository? = null
        try {
            repository = LedgerRepository(context)
            LedgerRecoveryStateStore(context).markLedgerValidated()
            val store = AttachmentStore(context, repository)
            val preparedBytes = "prepared source".toByteArray()
            val rowsDeletedBytes = "already deleted rows".toByteArray()
            val keepBytes = "other project's original".toByteArray()
            val prepared = storeFile(store, "prepared", "prepared-project", preparedBytes, repository)
            val rowsDeleted = storeFile(store, "rows-deleted", "rows-project", rowsDeletedBytes, repository)
            val keep = storeFile(store, "keep", "keep-project", keepBytes, repository)
            assertEquals(preparedBytes.size.toLong(), store.projectDeletionSummary("prepared-project").attachmentBytes)

            repository.beginProjectDeletion("prepared-project")
            quarantine(context, "prepared-project", prepared.storageName).also { staged ->
                check(prepared.let(store::fileFor).renameTo(staged))
            }
            repository.beginProjectDeletion("rows-project")
            val rowsStage = quarantine(context, "rows-project", rowsDeleted.storageName)
            check(rowsDeleted.let(store::fileFor).renameTo(rowsStage))
            repository.markProjectDeletionQuarantined("rows-project")
            repository.deleteProjectRows("rows-project")
            repository.close()

            repository = LedgerRepository(context)
            val retry = AttachmentStore(context, repository)
            assertEquals(emptyList<String>(), retry.retryProjectDeletions())
            assertEquals(listOf("keep-project"), repository.projects.first().map { it.id })
            assertTrue(!File(context.filesDir, "attachments/${prepared.storageName}").exists())
            assertTrue(!File(context.filesDir, "attachments/${rowsDeleted.storageName}").exists())
            assertTrue(keep.let(retry::fileFor).readBytes().contentEquals(keepBytes))
            assertTrue(repository.projectDeletionJournals().isEmpty())
        } finally {
            repository?.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("project-delete-"))
            sandbox.deleteRecursively()
        }
    }

    private suspend fun storeFile(store: AttachmentStore, id: String, projectId: String, bytes: ByteArray, repository: LedgerRepository): AttachmentInfo {
        val name = "${UUID.randomUUID()}.bin"
        val file = DocumentPolicy.safeFile(store.root, name)
        file.writeBytes(bytes)
        val info = AttachmentInfo(id, projectId, "$id.txt", "text/plain", bytes.size.toLong(), DocumentPolicy.sha256(file), name, 1)
        repository.saveProject(ProjectInfo(projectId, projectId))
        repository.insertAttachment(info)
        return info
    }

    private fun quarantine(context: Context, projectId: String, name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
        val directory = File(context.filesDir, "project-delete-$digest").apply { check(mkdirs() || isDirectory) }
        return DocumentPolicy.safeFile(directory, name)
    }
}
