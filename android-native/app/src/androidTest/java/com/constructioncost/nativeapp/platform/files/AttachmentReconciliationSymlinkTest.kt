package com.constructioncost.nativeapp.platform.files

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerDatabase
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentReconciliationSymlinkTest {
    private fun isolatedContext(base: Context, sandbox: File) = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
        override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
    }

    private fun trustedRepository(context: Context): LedgerRepository {
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        database.openHelper.writableDatabase.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
        LedgerRecoveryStateStore(context).markLedgerValidated()
        return LedgerRepository(database, context)
    }

    @Test fun reconciliationPreservesTrashLinksDanglingDestinationsAndConflictingOriginals() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "attachment-links-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = isolatedContext(base, sandbox)
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        val trash = File(context.filesDir, "attachment-trash").apply { check(mkdirs()) }
        val outside = File(sandbox, "outside").apply { check(mkdirs()) }
        val external = File(outside, "original.txt").apply { writeText("external original") }
        val trashLink = File(trash, "linked.bin")
        val movedTrashLink = File(store.root, trashLink.name)
        val danglingDestination = File(store.root, "dangling.bin")
        val missingTarget = File(outside, "missing.txt")
        try {
            repository.saveProject(ProjectInfo("links-project", "Link preservation fixture"))
            Os.symlink(external.path, trashLink.path)
            Os.symlink(missingTarget.path, danglingDestination.path)
            val staged = File(trash, danglingDestination.name).apply { writeText("staged original") }
            val original = File(store.root, "conflict.bin").apply { writeText("retained original") }
            val conflicting = File(trash, original.name).apply { writeText("conflicting trash original") }
            for ((id, file, name) in listOf(
                Triple("linked", external, trashLink.name),
                Triple("dangling", staged, danglingDestination.name),
                Triple("conflict", original, original.name)
            )) repository.insertAttachment(AttachmentInfo(id, "links-project", name, "text/plain",
                file.length(), DocumentPolicy.sha256(file), name, 1))
            val hashes = listOf(external, staged, original, conflicting).associateWith(DocumentPolicy::sha256)
            val names = repository.attachmentStorageNames()

            repeat(2) { store.reconcile() }

            for ((file, hash) in hashes) assertEquals(hash, DocumentPolicy.sha256(file))
            assertEquals(names, repository.attachmentStorageNames())
            assertTrue(Files.isSymbolicLink(trashLink.toPath()))
            assertFalse(Files.exists(movedTrashLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isSymbolicLink(danglingDestination.toPath()))
            assertEquals(missingTarget.toPath(), Files.readSymbolicLink(danglingDestination.toPath()))
            assertFalse(missingTarget.exists())
        } finally {
            repository.close()
            Files.deleteIfExists(trashLink.toPath())
            Files.deleteIfExists(movedTrashLink.toPath())
            Files.deleteIfExists(danglingDestination.toPath())
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }

    @Test fun reconciliationRejectsSymlinkedOriginalAndTrashDirectories() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in listOf("attachments", "attachment-trash")) {
            val sandbox = File(base.cacheDir, "attachment-directory-link-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val context = isolatedContext(base, sandbox)
            val repository = trustedRepository(context)
            val outside = File(sandbox, "outside").apply { check(mkdirs()) }
            val external = File(outside, "retained.bin").apply { writeText("external directory original") }
            val directoryLink = File(context.filesDir, name)
            try {
                repository.saveProject(ProjectInfo("directory-project", "Directory boundary fixture"))
                repository.insertAttachment(AttachmentInfo("retained", "directory-project", external.name, "text/plain",
                    external.length(), DocumentPolicy.sha256(external), external.name, 1))
                val hash = DocumentPolicy.sha256(external)
                Os.symlink(outside.path, directoryLink.path)
                val store = AttachmentStore(context, repository)

                assertThrows(IllegalStateException::class.java) { runBlocking { store.reconcile() } }

                assertTrue(Files.isSymbolicLink(directoryLink.toPath()))
                assertEquals(outside.toPath(), Files.readSymbolicLink(directoryLink.toPath()))
                assertEquals(hash, DocumentPolicy.sha256(external))
                assertNotNull(repository.attachment("retained"))
            } finally {
                repository.close()
                Files.deleteIfExists(directoryLink.toPath())
                check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
                sandbox.deleteRecursively()
            }
        }
    }
}
