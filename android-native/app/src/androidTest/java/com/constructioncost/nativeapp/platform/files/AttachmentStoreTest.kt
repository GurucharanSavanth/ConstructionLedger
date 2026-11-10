package com.constructioncost.nativeapp.platform.files

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.local.LedgerDatabase
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ActivityNote
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.domain.DocumentImportDetails
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.platform.pdf.renderPdfPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentStoreTest {
    @Test fun exportingToTheOriginalUriPreservesBytesAndMetadata() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.filesDir, "attachments/test-export-original-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        val bytes = "Immutable original evidence\u0000\u0001".toByteArray()
        val original = File(store.root, "original.bin").apply { writeBytes(bytes) }
        val otherBytes = "Second immutable original".toByteArray()
        val other = File(store.root, "other.bin").apply { writeBytes(otherBytes) }
        val hash = DocumentPolicy.sha256(original)
        val otherHash = DocumentPolicy.sha256(other)
        val databaseDestination = context.getDatabasePath("export-guard-${UUID.randomUUID()}.bin")
        try {
            repository.saveProject(ProjectInfo("export-project", "Export project"))
            repository.insertAttachment(AttachmentInfo("original", "export-project", "original.bin", "application/octet-stream",
                bytes.size.toLong(), hash, original.name, 1))
            repository.insertAttachment(AttachmentInfo("other", "export-project", "other.bin", "application/octet-stream",
                otherBytes.size.toLong(), otherHash, other.name, 1))
            val shared = FileProvider.getUriForFile(base, "${base.packageName}.files", original)
            val destinations = listOf(Uri.fromFile(original), shared,
                shared.buildUpon().encodedAuthority("0@${base.packageName}.files").build(), Uri.fromFile(other),
                FileProvider.getUriForFile(base, "${base.packageName}.files", other), Uri.fromFile(databaseDestination))
            for (destination in destinations) {
                val failure = runCatching { store.exportDocument("original", destination) }.exceptionOrNull()
                assertArrayEquals(bytes, original.readBytes())
                assertArrayEquals(otherBytes, other.readBytes())
                assertEquals(hash, DocumentPolicy.sha256(original))
                assertEquals(otherHash, DocumentPolicy.sha256(other))
                assertEquals(hash, repository.attachment("original")!!.sha256)
                assertEquals(otherHash, repository.attachment("other")!!.sha256)
                assertFalse(databaseDestination.exists())
                assertTrue("Private destination must fail before opening: $destination", failure is IllegalArgumentException)
            }
            val copy = File(context.cacheDir, "copy.bin")
            store.exportDocument("original", Uri.fromFile(copy))
            assertArrayEquals(bytes, copy.readBytes())
            assertArrayEquals(bytes, original.readBytes())
        } finally {
            repository.close()
            databaseDestination.delete()
            check(sandbox.canonicalFile.parentFile == File(base.filesDir, "attachments").canonicalFile && sandbox.name.startsWith("test-export-original-"))
            sandbox.deleteRecursively()
        }
    }

    @Test fun importedDetailsAndInvoiceLinkCommitTogether() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val sandbox = File(base.cacheDir, "import-details-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        val source = Uri.parse("content://${instrumentation.context.packageName}.fault/unicode.txt")
        try {
            repository.saveProject(ProjectInfo("file-project", "File project"))
            repository.saveProject(ProjectInfo("other-project", "Other project"))
            repository.saveExpense(ExpenseInput(id="invoice",projectId="file-project",description="Invoice",amountPaise=10000,date="2026-10-22"))
            repository.saveExpense(ExpenseInput(id="other-invoice",projectId="other-project",description="Other invoice",amountPaise=20000,date="2026-10-22"))
            val details = DocumentImportDetails("Bills and proof", "For review", "R2", "Masonry", "Kitchen", "Original receipt", "invoice")
            val imported = store.importDocuments(listOf(source), "file-project", details=details).single()
            val saved = repository.attachment(imported.id)!!
            assertEquals("R2", saved.revisionLabel)
            assertEquals("Kitchen", saved.place)
            assertEquals("Original receipt", saved.notes)
            assertEquals("For review", saved.status)
            assertEquals(1, saved.referenceCount)
            val originals = store.root.listFiles()!!.map { it.name }.toSet()
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                store.importDocuments(listOf(source), "file-project", details=details.copy(expenseId="other-invoice"))
            } }
            assertEquals(originals, store.root.listFiles()!!.map { it.name }.toSet())
            assertTrue(store.fileFor(saved).isFile)
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("import-details-"))
            sandbox.deleteRecursively()
        }
    }
    private fun trustedRepository(context: Context): LedgerRepository {
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val sql = database.openHelper.writableDatabase
        sql.query("PRAGMA integrity_check").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("ok", cursor.getString(0)) }
        sql.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
        LedgerRecoveryStateStore(context).markLedgerValidated()
        return LedgerRepository(database, context)
    }

    @Test fun healthyReconciliationRetainsUnknownOriginalsAndConflictingTrash() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "retained-originals-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        try {
            repository.saveProject(ProjectInfo("retained-project", "Recovery evidence"))
            val trash = File(context.filesDir, "attachment-trash").apply { check(mkdirs() || isDirectory) }
            val permanent = File(store.root, "unreferenced.bin").apply { writeText("unreferenced original") }
            val unknown = File(store.root, "receipt.pdf").apply { writeText("%PDF-1.4\nunknown original") }
            val orphanTrash = File(trash, "orphan.bin").apply { writeText("unreferenced trash original") }
            val retained = File(trash, "retained.bin").apply { writeText("interrupted deletion original") }
            val duplicate = File(trash, "duplicate.bin").apply { writeText("conflicting trash original") }
            val existing = File(store.root, "duplicate.bin").apply { writeText("existing original") }
            for (file in listOf(retained, existing)) repository.insertAttachment(AttachmentInfo(file.name, "retained-project",
                file.name, "text/plain", file.length(), DocumentPolicy.sha256(file), file.name, 1))
            val originals = listOf(permanent, unknown, orphanTrash, duplicate, existing).associateWith(DocumentPolicy::sha256)
            val retainedHash = DocumentPolicy.sha256(retained)
            repeat(2) { store.reconcile() }
            for ((file, hash) in originals) {
                assertTrue("Original was removed: ${file.name}", file.isFile)
                assertEquals(hash, DocumentPolicy.sha256(file))
            }
            assertEquals(retainedHash, DocumentPolicy.sha256(File(store.root, retained.name)))
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }

    @Test fun unvalidatedLedgerPreservesOriginalsInsteadOfReconcilingAgainstUnknownMetadata() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "unvalidated-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        try {
            val original = File(store.root, "retained.bin").apply { writeText("retained evidence") }
            assertFalse(LedgerRecoveryStateStore(context).isLedgerValidated())
            assertThrows(IllegalStateException::class.java) { runBlocking { store.reconcile() } }
            assertEquals("retained evidence", original.readText())
            LedgerRecoveryStateStore(context).markRecoveryRequired(LedgerRecoveryState.DATABASE_CORRUPTION, "Synthetic recovery gate fixture")
            assertThrows(IllegalStateException::class.java) { runBlocking { store.reconcile() } }
            assertEquals("retained evidence", original.readText())
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("unvalidated-"))
            sandbox.deleteRecursively()
        }
    }

    @Test fun cumulativeCrossProjectImportsRespectBackupCapacityAndRetainPreviousBackup() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val sandbox = File(base.cacheDir, "capacity-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        val source = Uri.parse("content://${instrumentation.context.packageName}.fault/unicode.txt")
        try {
            repository.saveProject(ProjectInfo("capacity-first", "First project"))
            repository.saveProject(ProjectInfo("capacity-second", "Second project"))
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(65)).joinToString("") { "%02x".format(it) }
            repository.insertAttachments((0 until 999).map { index ->
                val file = File(store.root, "capacity-$index.bin").apply { writeBytes(byteArrayOf(65)) }
                AttachmentInfo("capacity-$index", if (index < 500) "capacity-first" else "capacity-second",
                    "small.txt", "text/plain", 1, hash, file.name, index.toLong())
            })
            val originalNames = repository.attachmentStorageNames()
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                store.importDocuments(listOf(source, source), "capacity-first")
            } }
            assertEquals(originalNames, repository.attachmentStorageNames())
            assertEquals(originalNames, store.root.listFiles().orEmpty().map { it.name }.toSet())
            val imported = store.importDocument(source, "capacity-second")
            assertEquals(1000, repository.attachmentStorageNames().size)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.importDocument(source, "capacity-first") } }
            assertEquals(imported.sha256, DocumentPolicy.sha256(store.fileFor(imported)))

            val archive = File(context.cacheDir, "previous-valid.zip")
            val service = BackupService(context, repository, store)
            val unrelated = File(store.root, "unrelated-original.bin").apply { writeText("preserve recovery evidence") }
            assertEquals(1000, service.create(Uri.fromFile(archive)).fileCount)
            assertEquals("preserve recovery evidence", unrelated.readText())
            val previousHash = DocumentPolicy.sha256(archive)
            val extra = File(store.root, "preexisting-extra.bin").apply { writeBytes(byteArrayOf(65)) }
            repository.insertAttachment(AttachmentInfo("preexisting-extra", "capacity-first", "small.txt", "text/plain",
                1, hash, extra.name, 1))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.create(Uri.fromFile(archive)) } }
            assertEquals(previousHash, DocumentPolicy.sha256(archive))
            assertEquals(1000, service.inspect(Uri.fromFile(archive)).fileCount)
            assertEquals(1001, repository.attachmentStorageNames().size)
            assertEquals(hash, DocumentPolicy.sha256(extra))
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("capacity-"))
            sandbox.deleteRecursively()
        }
    }

    @Test fun cumulativeDocumentBytesAcceptExactBudgetAndRejectNextImportWithoutPartialFiles() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val sandbox = File(base.cacheDir, "byte-capacity-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        val source = Uri.parse("content://${instrumentation.context.packageName}.fault/unicode.txt")
        try {
            repository.saveProject(ProjectInfo("byte-capacity", "Byte capacity fixture"))
            val incomingSize = context.contentResolver.openInputStream(source)!!.use { it.readBytes().size.toLong() }
            val chunk = 250L * 1024 * 1024
            val sizes = listOf(chunk, chunk, BackupService.MAX_IMPORTED_DOCUMENT_BYTES - 2 * chunk - incomingSize)
            repository.insertAttachments(sizes.mapIndexed { index, size ->
                val file = File(store.root, "sparse-$index.bin")
                // Sparse fixture lengths exercise the budget without allocating hundreds of MiB.
                java.io.RandomAccessFile(file, "rw").use { it.setLength(size) }
                AttachmentInfo("sparse-$index", "byte-capacity", file.name, "application/octet-stream",
                    size, "0".repeat(64), file.name, 1)
            })
            val imported = store.importDocument(source, "byte-capacity")
            assertEquals(BackupService.MAX_IMPORTED_DOCUMENT_BYTES, store.root.listFiles().orEmpty().sumOf { it.length() })
            val retained = repository.attachmentStorageNames()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.importDocument(source, "byte-capacity") } }
            assertEquals(retained, repository.attachmentStorageNames())
            assertEquals(retained, store.root.listFiles().orEmpty().map { it.name }.toSet())
            assertEquals(imported.sha256, DocumentPolicy.sha256(store.fileFor(imported)))
            sizes.forEachIndexed { index, size -> assertEquals(size, File(store.root, "sparse-$index.bin").length()) }
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("byte-capacity-"))
            sandbox.deleteRecursively()
        }
    }

    @Test fun verifiedFileRejectsSameLengthContentChanges() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "integrity-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        try {
            repository.saveProject(ProjectInfo("integrity-project", "Integrity fixture"))
            val id = UUID.randomUUID().toString()
            val file = DocumentPolicy.safeFile(store.root, "$id.bin").apply { writeText("receipt") }
            val info = AttachmentInfo(id, "integrity-project", "receipt.txt", "text/plain", file.length(),
                DocumentPolicy.sha256(file), file.name, System.currentTimeMillis())
            repository.insertAttachment(info)
            assertEquals(file, store.verifiedFile(info))
            file.writeText("damaged")
            assertThrows(IllegalArgumentException::class.java) { store.verifiedFile(info) }
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("integrity-"))
            sandbox.deleteRecursively()
        }
    }

    @Test fun providerMetadataFallbacksLargeStreamsAndDeniedBatchPreserveOriginals() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val sandbox = File(base.cacheDir, "provider-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = trustedRepository(context)
        val store = AttachmentStore(context, repository)
        fun uri(path: String) = Uri.parse("content://${instrumentation.context.packageName}.fault/$path")
        try {
            repository.saveProject(ProjectInfo("provider-fixture", "Provider boundary fixture"))
            context.contentResolver.query(uri("large"), arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertTrue(it.isNull(1))
            }
            val unicode = store.importDocument(uri("unicode.txt"), "provider-fixture")
            assertEquals("Site notes — ಮನೆ 01.txt", unicode.name)
            assertEquals("text/plain", unicode.mimeType)
            val unnamed = store.importDocument(uri("unnamed"), "provider-fixture")
            assertEquals("Document", unnamed.name)
            assertEquals("application/octet-stream", unnamed.mimeType)
            assertEquals("Synthetic provider document\n", store.fileFor(unnamed).readText())
            val large = store.importDocument(uri("large"), "provider-fixture")
            assertEquals(13L * 1024 * 1024, large.size)
            val expectedDigest = java.security.MessageDigest.getInstance("SHA-256").apply {
                val block = ByteArray(64 * 1024) { 42 }
                repeat(208) { update(block) }
            }.digest().joinToString("") { "%02x".format(it) }
            assertEquals(expectedDigest, large.sha256)
            val retained = listOf(unicode, unnamed, large)
            assertThrows(SecurityException::class.java) {
                runBlocking { store.importDocuments(listOf(uri("unicode.txt"), uri("denied")), "provider-fixture") }
            }
            assertEquals(retained.map { it.storageName }.toSet(), repository.attachmentStorageNames())
            assertEquals(retained.map { it.storageName }.toSet(), store.root.listFiles().orEmpty().map { it.name }.toSet())
            retained.forEach { assertEquals(it.sha256, DocumentPolicy.sha256(store.fileFor(it))) }
            assertEquals(1, store.importDocuments(listOf(uri("unicode.txt")), "provider-fixture").size)
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("provider-test-"))
            sandbox.deleteRecursively()
        }
    }

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
        val repository = trustedRepository(context)
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
        val repository = trustedRepository(context)
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
                    val bitmap = renderPdfPage(renderer, index, 128, 1f)
                    try {
                        assertEquals(320, bitmap.width)
                        assertEquals(480, bitmap.height)
                        assertTrue((30..50).any { y -> (24..150).any { x -> bitmap.getPixel(x, y) != Color.WHITE } })
                    } finally { bitmap.recycle() }
                }
                var cancelled = false
                launch {
                    currentCoroutineContext().cancel()
                    try { renderPdfPage(renderer, -1, 128, 1f) }
                    catch (_: CancellationException) { cancelled = true }
                }.join()
                assertTrue("Cancellation must be checked before accessing a stale page", cancelled)
                val zoomed = renderPdfPage(renderer, 119, 128, 3f)
                assertEquals(960, zoomed.width)
                assertTrue(zoomed.width.toLong() * zoomed.height <= 8_000_000L)
                zoomed.recycle()
            } }
            assertFalse(descriptor.fileDescriptor.valid())

            val share = store.shareIntent(info.id)
            assertEquals(Intent.ACTION_SEND, share.action)
            assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val sharedUri = requireNotNull(IntentCompat.getParcelableExtra(share, Intent.EXTRA_STREAM, Uri::class.java)) {
                "The share intent must contain the original document URI"
            }
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
