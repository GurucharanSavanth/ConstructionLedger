package com.constructioncost.nativeapp.platform.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class AttachmentStore(private val context: Context, private val repository: LedgerRepository) {
    val root: File = File(context.filesDir, "attachments").apply { check(mkdirs() || isDirectory) }
    private val trash = File(context.filesDir, "attachment-trash").apply { check(mkdirs() || isDirectory) }
    companion object { val operationMutex = Mutex() }

    fun fileFor(info: AttachmentInfo): File = DocumentPolicy.safeFile(root, info.storageName)
    suspend fun importDocument(uri: Uri, projectId: String, parentId: String? = null): AttachmentInfo = importDocuments(listOf(uri), projectId, parentId).single()
    suspend fun importDocuments(uris: List<Uri>, projectId: String, parentId: String? = null): List<AttachmentInfo> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            require(uris.size in 1..1000 && (parentId == null || uris.size == 1)) { "Select 1–1,000 documents; a revision must contain one file" }
            reconcileLocked()
            val staged = mutableListOf<Pair<File, AttachmentInfo>>()
            val finalized = mutableListOf<File>()
            try {
                uris.forEach { uri ->
                    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    var name = "Document"
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0)?.take(240)?.trim().orEmpty().ifBlank { "Document" }
                    }
                    val id = UUID.randomUUID().toString()
                    val temporary = File.createTempFile("copy-", ".tmp", root)
                    try {
                        val size = context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(temporary).use { output -> DocumentPolicy.copy(input, output).also { output.fd.sync() } }
                        } ?: error("Cannot open selected document")
                        DocumentPolicy.validate(temporary, mime)
                        val info = AttachmentInfo(id, projectId, name, mime, size, DocumentPolicy.sha256(temporary), "$id.bin", System.currentTimeMillis(), parentId = parentId,
                            category = DocumentPolicy.category(name))
                        staged += temporary to info
                    } catch (failure: Throwable) { temporary.delete(); throw failure }
                }
                withContext(NonCancellable) {
                    try {
                        staged.forEach { (temporary, info) ->
                            val file = fileFor(info)
                            check(temporary.renameTo(file)) { "Cannot commit document to private storage" }
                            finalized += file
                        }
                        repository.insertAttachments(staged.map { it.second })
                    } catch (failure: Throwable) { finalized.forEach { it.delete() }; throw failure }
                }
                staged.map { it.second }
            } finally { staged.forEach { it.first.delete() } }
        }
    }
    suspend fun rename(id: String, name: String, revision: Long) = withContext(Dispatchers.IO) {
        operationMutex.withLock { repository.renameAttachment(id, name, revision) }
    }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            reconcileLocked()
            val info = repository.attachment(id) ?: error("Document no longer exists")
            require(info.referenceCount == 0) { "Document is still linked to a ledger record" }
            val file = fileFor(info)
            val staged = DocumentPolicy.safeFile(trash, info.storageName)
            withContext(NonCancellable) {
                if (file.exists()) check(file.renameTo(staged)) { "Cannot stage document deletion" }
                try { repository.deleteAttachmentMetadata(id) }
                catch (failure: Throwable) {
                    if (staged.exists()) check(staged.renameTo(file)) { "Document deletion rollback failed; private trash retains the original" }
                    throw failure
                }
                staged.delete()
            }
        }
    }
    suspend fun reconcile() = withContext(Dispatchers.IO) { operationMutex.withLock { reconcileLocked() } }
    internal suspend fun reconcileLocked() {
        val retained = repository.attachmentStorageNames()
        trash.listFiles()?.forEach { file ->
            if (file.name in retained) {
                val original = DocumentPolicy.safeFile(root, file.name)
                if (!original.exists()) check(file.renameTo(original)) { "Cannot recover interrupted document deletion" }
                else if (DocumentPolicy.sha256(file) == DocumentPolicy.sha256(original)) file.delete()
            } else file.delete()
        }
        root.listFiles()?.filter { it.isFile && it.name !in retained }?.forEach { it.delete() }
    }
    suspend fun exportDocument(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val info = repository.attachment(id) ?: error("Document no longer exists")
            val source = verifiedFile(info)
            context.contentResolver.openOutputStream(destination, "wt")?.use { output -> source.inputStream().use { DocumentPolicy.copy(it, output) } }
                ?: error("Cannot write selected destination")
        }
    }
    suspend fun shareIntent(id: String): Intent = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val info = repository.attachment(id) ?: error("Document no longer exists")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", verifiedFile(info), info.name)
            Intent(Intent.ACTION_SEND).apply {
                type = info.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(info.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
    fun openPdf(info: AttachmentInfo): ParcelFileDescriptor {
        require(info.mimeType == "application/pdf") { "This document is not a PDF" }
        return ParcelFileDescriptor.open(verifiedFile(info), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun verifiedFile(info: AttachmentInfo): File = fileFor(info).also {
        require(it.isFile && it.length() == info.size && DocumentPolicy.sha256(it) == info.sha256) { "Original document is missing or damaged" }
    }
}
