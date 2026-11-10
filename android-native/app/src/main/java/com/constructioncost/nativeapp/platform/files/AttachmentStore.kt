package com.constructioncost.nativeapp.platform.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.FileProvider
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.data.repository.ProjectDeletionJournal
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.DocumentImportDetails
import com.constructioncost.nativeapp.domain.ProjectDeletionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.UUID

data class RecoveryCopySet(val id: String, val createdAt: Long, val fileCount: Int, val totalBytes: Long, val backedUp: Boolean)

class AttachmentStore(private val context: Context, private val repository: LedgerRepository) {
    val root: File by lazy { File(context.filesDir, "attachments").apply { check(mkdirs() || isDirectory) } }
    private val trash by lazy { File(context.filesDir, "attachment-trash").apply { check(mkdirs() || isDirectory) } }
    companion object { val operationMutex = Mutex() }

    fun fileFor(info: AttachmentInfo): File = DocumentPolicy.safeFile(root, info.storageName)
    suspend fun projectDeletionSummary(projectId: String): ProjectDeletionSummary = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            verifyAttachmentRoot()
            val inventory = repository.projectDeletionInventory(projectId)
            var bytes = 0L
            inventory.files.forEach { info ->
                val file = verifiedFile(info)
                bytes = Math.addExact(bytes, file.length())
            }
            inventory.summary.copy(attachmentBytes = bytes)
        }
    }
    internal suspend fun deleteProject(projectId: String, onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            verifyAttachmentRoot()
            val recovery = LedgerRecoveryStateStore(context)
            check(!recovery.isRecoveryRequired() && recovery.isLedgerValidated()) { "Verify ledger integrity before deleting a project" }
            if (repository.projectDeletionJournals().none { it.projectId == projectId }) preserveRetiredQuarantine(projectId)
            deleteProjectLocked(repository.beginProjectDeletion(projectId), onProgress)
        }
    }
    private suspend fun deleteProjectLocked(journal: ProjectDeletionJournal, onProgress: (String) -> Unit) {
        val projectId = journal.projectId
        verifyAttachmentRoot()
        val recovery = LedgerRecoveryStateStore(context)
        check(!recovery.isRecoveryRequired() && recovery.isLedgerValidated()) { "Verify ledger integrity before deleting a project" }
        val quarantine = projectQuarantine(projectId)
        if (journal.phase != "ROWS_DELETED") {
            onProgress("Verifying and quarantining project originals")
            for (item in journal.files) {
                val source = DocumentPolicy.safeFile(root, item.storageName)
                val staged = DocumentPolicy.safeFile(quarantine, item.storageName)
                if (staged.exists()) {
                    verifyOwnedFile(staged, item.storageName, item.size, item.sha256)
                    if (source.exists()) {
                        verifyOwnedFile(source, item.storageName, item.size, item.sha256)
                        check(source.delete()) { "Cannot remove duplicate staged original" }
                    }
                } else {
                    verifyOwnedFile(source, item.storageName, item.size, item.sha256)
                    check(source.renameTo(staged)) { "Cannot quarantine original ${item.id}" }
                }
            }
            if (journal.phase == "PREPARED") repository.markProjectDeletionQuarantined(projectId)
            for (item in journal.files) verifyOwnedFile(DocumentPolicy.safeFile(quarantine, item.storageName), item.storageName, item.size, item.sha256)
            onProgress("Removing project records")
            repository.deleteProjectRows(projectId)
        }
        onProgress("Cleaning quarantined originals")
        for (item in journal.files) {
            val staged = DocumentPolicy.safeFile(quarantine, item.storageName)
            if (staged.exists()) {
                verifyOwnedFile(staged, item.storageName, item.size, item.sha256)
                check(staged.delete()) { "Cannot clean quarantined original ${item.id}" }
            }
        }
        if (quarantine.exists()) check(quarantine.delete() || quarantine.list()?.isEmpty() == true) { "Cannot clean project quarantine" }
        repository.completeProjectDeletion(projectId)
    }
    internal suspend fun retryProjectDeletions(onProgress: (String) -> Unit = {}): List<String> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val failures = mutableListOf<String>()
            for (journal in repository.projectDeletionJournals()) try { deleteProjectLocked(journal, onProgress) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failures += failure.message ?: "A project deletion is still pending" }
            failures
        }
    }
    private fun preserveRetiredQuarantine(projectId: String) {
        val directory = projectQuarantine(projectId)
        if (directory.list()?.isEmpty() == true) return
        val preserved = File(context.filesDir, "recovery-preserved")
        check(!Files.isSymbolicLink(preserved.toPath()) && preserved.canonicalFile.parentFile == context.filesDir.canonicalFile) { "Unsafe recovery directory" }
        check(preserved.mkdirs() || preserved.isDirectory) { "Cannot retain previous project originals" }
        // A restored project has no deletion journal; its old quarantine is recovery evidence.
        val destination = File(preserved, "${directory.name}-${UUID.randomUUID()}")
        check(directory.renameTo(destination)) { "Cannot retain previous project originals" }
    }
    private fun projectQuarantine(projectId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
        val directory = File(context.filesDir, "project-delete-$digest")
        check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == context.filesDir.canonicalFile) { "Unsafe project quarantine directory" }
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create project quarantine" }
        return directory
    }
    private fun verifyAttachmentRoot() {
        check(!Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile == context.filesDir.canonicalFile) { "Unsafe original document directory" }
    }
    private fun verifyOwnedFile(file: File, name: String, size: Long, sha256: String) {
        val parent = file.parentFile ?: error("Unsafe original document path")
        check(!Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(file.toPath()) && file.isFile && file.canonicalFile.parentFile == parent.canonicalFile &&
            file.length() == size && DocumentPolicy.sha256(file) == sha256) { "Original $name is missing or damaged; project deletion is paused" }
    }
    suspend fun importDocument(uri: Uri, projectId: String, parentId: String? = null): AttachmentInfo = importDocuments(listOf(uri), projectId, parentId).single()
    suspend fun importDocuments(uris: List<Uri>, projectId: String, parentId: String? = null, details: DocumentImportDetails? = null): List<AttachmentInfo> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            require(uris.size in 1..1000 && (parentId == null || uris.size == 1)) { "Select 1–1,000 documents; a revision must contain one file" }
            reconcileLocked()
            val retained = repository.attachmentStorageNames()
            var totalBytes = retained.fold(0L) { total, name ->
                val file = DocumentPolicy.safeFile(root, name)
                require(file.isFile) { "An original document is missing. Recover it before importing more documents" }
                Math.addExact(total, file.length())
            }
            BackupService.checkImportCapacity(Math.addExact(retained.size, uris.size), totalBytes)
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
                        totalBytes = Math.addExact(totalBytes, size)
                        BackupService.checkImportCapacity(Math.addExact(retained.size, uris.size), totalBytes)
                        DocumentPolicy.validate(temporary, mime)
                        val info = AttachmentInfo(id, projectId, name, mime, size, DocumentPolicy.sha256(temporary), "$id.bin", System.currentTimeMillis(), parentId = parentId,
                            category = details?.category ?: DocumentPolicy.category(name), status = details?.status.orEmpty(),
                            revisionLabel = details?.revisionLabel.orEmpty(), work = details?.work.orEmpty(), place = details?.place.orEmpty(), notes = details?.notes.orEmpty())
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
                        repository.insertAttachments(staged.map { it.second }, details?.expenseId)
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
        val recovery = LedgerRecoveryStateStore(context)
        check(!recovery.isRecoveryRequired() && recovery.isLedgerValidated()) { "Ledger integrity must be verified before cleaning up original documents. Open recovery before retrying" }
        val retained = repository.attachmentStorageNames()
        check(!recovery.isRecoveryRequired() && recovery.isLedgerValidated()) { "Ledger recovery interrupted document reconciliation" }
        for (directory in listOf(root, trash)) {
            check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == context.filesDir.canonicalFile) {
                "Document storage must remain inside private app storage"
            }
        }
        for (name in retained) {
            val file = File(trash, name)
            val original = File(root, name)
            if (file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                file.canonicalFile.parentFile == trash.canonicalFile &&
                !Files.exists(original.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                check(file.renameTo(DocumentPolicy.safeFile(root, name))) { "Cannot recover interrupted document deletion" }
            }
        }
        // Missing metadata is not proof that a permanent original or trash copy is safe to delete.
    }
    internal suspend fun retainRestoreOriginalsLocked(): String? {
        val files = JSONArray()
        for (name in repository.attachmentStorageNames()) for (area in listOf("attachments", "attachment-trash")) {
            val directory = File(context.filesDir, area)
            if (!directory.exists()) continue
            check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == context.filesDir.canonicalFile) { "Unsafe original document directory" }
            val file = DocumentPolicy.safeFile(directory, name)
            if (!file.isFile || Files.isSymbolicLink(File(directory, name).toPath())) continue
            files.put(JSONObject().put("area", area).put("name", name).put("size", file.length()).put("sha256", DocumentPolicy.sha256(file)))
        }
        if (files.length() == 0) return null
        val id = UUID.randomUUID().toString()
        val sets = readRecoverySets()
        sets.put(JSONObject().put("id", id).put("createdAt", System.currentTimeMillis()).put("files", files))
        writeRecoverySets(sets)
        return id
    }
    internal fun discardRecoverySetLocked(id: String) {
        val sets = readRecoverySets()
        writeRecoverySets(JSONArray((0 until sets.length()).map { sets.getJSONObject(it) }.filter { it.getString("id") != id }))
    }
    internal fun markRecoveryBackupVerifiedLocked(archiveSha256: String, inventory: List<AttachmentInfo>) {
        val recovery = LedgerRecoveryStateStore(context)
        if (recovery.isRecoveryRequired() || !recovery.isLedgerValidated()) return
        val backedUp = inventory.map { it.size to it.sha256 }.toSet()
        val sets = readRecoverySets()
        for (index in 0 until sets.length()) {
            val set = sets.getJSONObject(index)
            val files = set.getJSONArray("files")
            if ((0 until files.length()).all { row -> files.getJSONObject(row).let { (it.getLong("size") to it.getString("sha256")) in backedUp } }) {
                set.put("backupSha256", archiveSha256)
            } else set.remove("backupSha256")
        }
        if (sets.length() > 0) writeRecoverySets(sets)
    }
    suspend fun recoveryCopies(): List<RecoveryCopySet> = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val active = repository.attachmentStorageNames()
            val sets = readRecoverySets()
            (0 until sets.length()).mapNotNull { index ->
                val set = sets.getJSONObject(index)
                val files = recoveryFiles(set).filter { (meta, file) -> meta.getString("name") !in active && file.exists() }
                if (files.isEmpty()) null else RecoveryCopySet(set.getString("id"), set.getLong("createdAt"), files.size,
                    files.fold(0L) { sum, (_, file) -> Math.addExact(sum, file.length()) }, set.has("backupSha256"))
            }
        }
    }
    suspend fun cleanupRecoveryCopies(ids: Set<String>): Long = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val recovery = LedgerRecoveryStateStore(context)
            check(!recovery.isRecoveryRequired() && recovery.isLedgerValidated()) { "Verify ledger recovery before removing retained originals" }
            repository.validateStorage()
            val active = repository.attachmentStorageNames()
            val sets = readRecoverySets()
            val selected = (0 until sets.length()).map { sets.getJSONObject(it) }.filter { it.getString("id") in ids }
            require(ids.isNotEmpty() && selected.size == ids.size) { "Retained-original selection changed. Review it again" }
            require(selected.all { it.has("backupSha256") }) { "Create and verify a new backup before removing retained originals" }
            val files = selected.flatMap(::recoveryFiles).filter { (meta, file) -> meta.getString("name") !in active && file.exists() }
                .distinctBy { (_, file) -> file.absolutePath }
            for ((meta, file) in files) {
                require(file.isFile && file.length() == meta.getLong("size") && DocumentPolicy.sha256(file) == meta.getString("sha256")) { "A retained original changed. Keep it for manual recovery" }
            }
            check(!recovery.isRecoveryRequired() && recovery.isLedgerValidated()) { "Ledger recovery interrupted original cleanup" }
            var removed = 0L
            for ((_, file) in files) { val size = file.length(); check(file.delete()) { "Cannot remove retained original" }; removed = Math.addExact(removed, size) }
            writeRecoverySets(JSONArray((0 until sets.length()).map { sets.getJSONObject(it) }.filter { it.getString("id") !in ids }))
            removed
        }
    }
    private fun recoveryFiles(set: JSONObject): List<Pair<JSONObject, File>> {
        val files = set.getJSONArray("files")
        return (0 until files.length()).map { index ->
            val meta = files.getJSONObject(index)
            val area = meta.getString("area")
            require(area in setOf("attachments", "attachment-trash")) { "Unknown retained-original directory" }
            val directory = File(context.filesDir, area)
            val candidate = File(directory, meta.getString("name"))
            require(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == context.filesDir.canonicalFile &&
                !Files.isSymbolicLink(candidate.toPath())) { "Unsafe retained-original path" }
            meta to DocumentPolicy.safeFile(directory, meta.getString("name"))
        }
    }
    private fun recoveryRegistry(): AtomicFile {
        val file = File(context.filesDir, "attachment-recovery-sets.json")
        check(listOf("", ".bak", ".new").all { suffix -> !Files.isSymbolicLink(File(file.path + suffix).toPath()) } &&
            file.canonicalFile.parentFile == context.filesDir.canonicalFile) { "Unsafe retained-original registry" }
        return AtomicFile(file)
    }
    private fun readRecoverySets(): JSONArray {
        val file = recoveryRegistry()
        return if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) JSONArray(file.readFully().toString(Charsets.UTF_8)) else JSONArray()
    }
    private fun writeRecoverySets(sets: JSONArray) {
        val file = recoveryRegistry()
        val output = file.startWrite()
        try { output.write(sets.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
    suspend fun exportDocument(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            DocumentPolicy.checkExportDestination(context, destination)
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
    internal fun verifiedFile(info: AttachmentInfo): File = fileFor(info).also {
        require(it.isFile && it.length() == info.size && DocumentPolicy.sha256(it) == info.sha256) { "Original document is missing or damaged" }
    }
}
