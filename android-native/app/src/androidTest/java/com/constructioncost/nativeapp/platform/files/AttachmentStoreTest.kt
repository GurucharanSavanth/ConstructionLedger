package com.constructioncost.nativeapp.platform.files

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ActivityNote
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentStoreTest {
    @Test fun interruptedSafReadLeavesNoPartialOriginalOrMetadata() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val sandbox = File(base.filesDir, "attachments/test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        check(sandbox.canonicalFile.parentFile == File(base.filesDir, "attachments").canonicalFile)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir(): File = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        try {
            val projectId = UUID.randomUUID().toString()
            repository.saveProject(ProjectInfo(projectId, "Interrupted SAF import"))
            val originalId = UUID.randomUUID().toString()
            val original = File(store.root, "$originalId.bin").apply { writeText("preexisting original evidence") }
            val retained = AttachmentInfo(originalId, projectId, "retained.txt", "text/plain", original.length(),
                DocumentPolicy.sha256(original), original.name, System.currentTimeMillis())
            repository.insertAttachment(retained)

            // The provider lives in the separately installed test APK; the target app resolver must cross that UID.
            val uri = Uri.parse("content://${instrumentation.context.packageName}.fault/failed.pdf")
            assertEquals("application/pdf", context.contentResolver.getType(uri))
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("interrupted.pdf", cursor.getString(0))
                assertEquals(4096L, cursor.getLong(1))
            }
            assertThrows(IOException::class.java) { runBlocking { store.importDocument(uri, projectId) } }
            val good = FileProvider.getUriForFile(base, "${context.packageName}.files", original, "retained.txt")
            assertThrows(IOException::class.java) { runBlocking { store.importDocuments(listOf(good, uri), projectId) } }
            assertEquals(setOf(original.name), repository.attachmentStorageNames())
            assertEquals(originalId, repository.attachment(originalId)?.id)
            assertEquals(listOf(original.name), store.root.listFiles().orEmpty().map { it.name })
            assertEquals(retained.sha256, DocumentPolicy.sha256(original))
            assertTrue(File(context.filesDir, "attachment-trash").listFiles().orEmpty().isEmpty())
            val batch = store.importDocuments(listOf(good, good), projectId)
            assertEquals(2, batch.size)
            assertEquals(2, batch.map { it.id }.distinct().size)
            assertTrue(batch.all { it.sha256 == retained.sha256 && it.category == "Miscellaneous" })
            assertEquals(3, repository.attachmentStorageNames().size)
            assertEquals(retained.sha256, DocumentPolicy.sha256(original))
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == File(base.filesDir, "attachments").canonicalFile && sandbox.name.startsWith("test-"))
            sandbox.deleteRecursively()
        }
    }

    @Test fun importedPdfRendersSharesAndKeepsExternalSourceWhenDeleted() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.filesDir, "attachments/test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        check(sandbox.canonicalFile.parentFile == File(base.filesDir, "attachments").canonicalFile)
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir(): File = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        val source = File(context.cacheDir, "exports/source.pdf").apply { parentFile!!.mkdirs() }
        val damaged = File(context.cacheDir, "exports/damaged.pdf")
        try {
            val projectId = UUID.randomUUID().toString()
            repository.saveProject(ProjectInfo(projectId, "PDF test house"))
            val document = PdfDocument()
            try {
                repeat(120) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(320, 480, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawText("Page ${index + 1}", 24f, 48f, Paint().apply { color = Color.BLACK; textSize = 18f })
                    document.finishPage(page)
                }
                source.outputStream().use { document.writeTo(it) }
            } finally { document.close() }
            val uri = FileProvider.getUriForFile(base, "${context.packageName}.files", source)
            val info = store.importDocument(uri, projectId)
            assertEquals("application/pdf", info.mimeType)
            assertEquals(source.length(), info.size)
            assertEquals(DocumentPolicy.sha256(source), info.sha256)
            assertNotEquals(source.canonicalPath, store.fileFor(info).canonicalPath)
            assertEquals(info.sha256, DocumentPolicy.sha256(store.fileFor(info)))

            val descriptor = store.openPdf(info)
            descriptor.use { file -> PdfRenderer(file).use { renderer ->
                assertEquals(120, renderer.pageCount)
                for (index in listOf(0, 59, 119)) {
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
                        try {
                            assertTrue(bitmap.width.toLong() * bitmap.height <= 1_000_000L)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            assertTrue((30..50).any { y -> (24..150).any { x -> bitmap.getPixel(x, y) != Color.WHITE } })
                        } finally { bitmap.recycle() }
                    }
                }
            } }
            assertFalse(descriptor.fileDescriptor.valid())

            val share = store.shareIntent(info.id)
            assertEquals(Intent.ACTION_SEND, share.action)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val sharedUri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", sharedUri.scheme)
            context.contentResolver.query(sharedUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(info.name, cursor.getString(0))
            }
            val shared = File(context.cacheDir, "shared.pdf")
            context.contentResolver.openInputStream(sharedUri)!!.use { input -> shared.outputStream().use { input.copyTo(it) } }
            assertEquals(info.sha256, DocumentPolicy.sha256(shared))
            assertEquals(info.sha256, DocumentPolicy.sha256(source))

            val noteId = UUID.randomUUID().toString()
            repository.saveNote(ActivityNote(noteId, projectId, "Document evidence", "2026-01-02", ""))
            repository.linkAttachment(info.id, noteId = noteId)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.delete(info.id) } }
            assertTrue(store.fileFor(info).isFile)

            damaged.writeText("%PDF-1.4\nnot a complete PDF")
            val bad = store.importDocument(FileProvider.getUriForFile(base, "${context.packageName}.files", damaged), projectId)
            assertThrows(Exception::class.java) {
                store.openPdf(bad).use { file -> PdfRenderer(file).use { require(it.pageCount > 0) } }
            }
            store.delete(bad.id)
            assertFalse(store.fileFor(bad).exists())
            assertTrue(damaged.isFile)

            repository.deleteNote(noteId, 0)
            store.delete(info.id)
            assertFalse(store.fileFor(info).exists())
            assertEquals(info.sha256, DocumentPolicy.sha256(source))
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == File(base.filesDir, "attachments").canonicalFile && sandbox.name.startsWith("test-"))
            sandbox.deleteRecursively()
        }
    }
}
