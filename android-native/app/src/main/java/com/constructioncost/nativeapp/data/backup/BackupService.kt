package com.constructioncost.nativeapp.data.backup

import android.content.Context
import android.net.Uri
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.data.repository.SnapshotFinancials
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.availablePrivateBytes
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.platform.OperationProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BackupReport(val counts: Map<String, Int>, val fileCount: Int, val totalBytes: Long, val createdAt: Long,
                        val format: String = "BuildLedgerNative", val settings: JSONObject = JSONObject(),
                        val financials: SnapshotFinancials, val archiveSha256: String? = null,
                        val warnings: List<String> = emptyList(), val phaseMillis: Map<String, Long> = emptyMap())

class BackupService(private val context: Context, private val repository: LedgerRepository, private val store: AttachmentStore) {
    companion object {
        internal const val MAX_DOCUMENTS = ArchiveIO.MAX_FILES
        // Reserve the maximum records/manifest payload plus ZIP and encryption overhead.
        internal const val MAX_IMPORTED_DOCUMENT_BYTES = ArchiveIO.MAX_ARCHIVE_BYTES - ArchiveIO.MAX_JSON_BYTES - ArchiveIO.MAX_MANIFEST_BYTES - 2L * 1024 * 1024
        internal fun checkImportCapacity(count: Int, bytes: Long) {
            require(count in 0..MAX_DOCUMENTS) { "This import would exceed the ledger's 1,000-document backup limit. No documents were imported" }
            require(bytes in 0..MAX_IMPORTED_DOCUMENT_BYTES) { "This import would exceed the ledger's ${MAX_IMPORTED_DOCUMENT_BYTES / 1024 / 1024} MiB document budget reserved for backups. No documents were imported" }
        }
    }
    internal var restoreCheckpoint: (String) -> Unit = {}
    suspend fun create(destination: Uri, settings: JSONObject = JSONObject(), password: String? = null, onProgress: (String) -> Unit = {}): BackupReport = withContext(Dispatchers.IO) {
        AttachmentStore.operationMutex.withLock {
            DocumentPolicy.checkExportDestination(context, destination)
            val progress = OperationProgress("Backup", onProgress)
            val savedSettings = checkedSettings(settings)
            val directory = staging()
            try {
                progress.phase("Reading ledger records")
                val recordsFile = File(directory, "records.json")
                val snapshot = repository.exportRecordsTo(recordsFile)
                val counts = snapshot.counts
                val rows = snapshot.attachments
                require(counts.values.sumOf { it.toLong() } <= ArchiveIO.MAX_RECORDS) { "Ledger contains too many records" }
                require(rows.size <= MAX_DOCUMENTS) { "Too many documents for one archive" }
                require(recordsFile.length() <= ArchiveIO.MAX_JSON_BYTES) { "Ledger exceeds the 256 MiB JSON backup limit" }
                val inventory = JSONArray()
                var bytes = 0L
                progress.phase("Verifying original documents")
                for ((index, info) in rows.withIndex()) {
                    require(info.id.matches(Regex("[A-Za-z0-9_-]+"))) { "Unsafe document ID" }
                    val file = store.fileFor(info)
                    require(file.isFile && file.length() == info.size && DocumentPolicy.sha256(file) == info.sha256) { "Missing or damaged original document: ${info.name}" }
                    DocumentPolicy.validate(file, info.mimeType)
                    bytes += file.length()
                    require(bytes + recordsFile.length() <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Archive exceeds 850 MiB" }
                    inventory.put(JSONObject().put("id", info.id).put("path", "files/${info.id}").put("size", info.size).put("sha256", info.sha256))
                    progress.count(index + 1, rows.size)
                }
                val time = System.currentTimeMillis()
                val manifest = JSONObject().put("product", "BuildLedgerNative").put("format", 1).put("schema", LedgerRepository.SNAPSHOT_VERSION).put("createdAt", time)
                    .put("counts", JSONObject(counts)).put("files", inventory).put("recordsSha256", DocumentPolicy.sha256(recordsFile)).put("settings", savedSettings)
                val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)
                require(manifestBytes.size <= ArchiveIO.MAX_MANIFEST_BYTES) { "Backup manifest exceeds 1 MiB" }
                require(bytes + recordsFile.length() + manifestBytes.size <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Archive expanded size exceeds 850 MiB" }
                val archive = File(directory, "backup.zip")
                progress.phase("Writing backup archive")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    fun entry(name: String, file: File, expectedHash: String? = null) {
                        zip.putNextEntry(ZipEntry(name))
                        val digest = MessageDigest.getInstance("SHA-256")
                        DigestInputStream(file.inputStream(), digest).use { DocumentPolicy.copy(it, zip, if (name == "records.json") ArchiveIO.MAX_JSON_BYTES else DocumentPolicy.MAX_FILE_BYTES) }
                        require(expectedHash == null || digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "Document changed while creating backup" }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifestBytes); zip.closeEntry()
                    entry("records.json", recordsFile)
                    for ((index, info) in rows.withIndex()) { entry("files/${info.id}", store.fileFor(info), info.sha256); progress.count(index + 1, rows.size) }
                }
                require(archive.length() <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Archive exceeds 850 MiB" }
                if (password != null) progress.phase("Encrypting backup")
                val exported = if (password == null) archive else File(directory, "backup.blbackup").also { NativeBackupEncryption.encrypt(archive, it, password) }
                progress.phase("Saving backup file")
                if (destination.scheme != "file" || File(requireNotNull(destination.path)).exists()) {
                    val empty = context.contentResolver.openInputStream(destination)?.use { it.read() == -1 } ?: false
                    require(empty) { "Choose a new, empty backup destination. Existing files are preserved" }
                }
                context.contentResolver.openOutputStream(destination, "wt")?.use { output -> exported.inputStream().use { DocumentPolicy.copy(it, output, ArchiveIO.MAX_ARCHIVE_BYTES) } }
                    ?: error("Cannot write backup destination")
                progress.phase("Verifying saved backup")
                val digest = MessageDigest.getInstance("SHA-256")
                val savedSize = context.contentResolver.openInputStream(destination)?.use { input ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(read > 0) { "Saved backup provider stopped returning data" }
                        size = Math.addExact(size, read.toLong())
                        require(size <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Saved backup exceeds the supported size" }
                        digest.update(buffer, 0, read)
                    }
                    size
                } ?: error("Backup written but the saved file could not be verified. Choose local device storage and retry")
                val savedHash = digest.digest().joinToString("") { "%02x".format(it) }
                require(savedSize == exported.length() && savedHash == DocumentPolicy.sha256(exported)) { "Saved backup differs from the verified archive. Retry to a local destination" }
                val warnings = try { store.markRecoveryBackupVerifiedLocked(savedHash, rows); emptyList() }
                    catch (_: Exception) { listOf("Backup verified; retained-original cleanup could not be enabled. Keep those originals and create another backup before cleanup.") }
                BackupReport(counts, rows.size, bytes, time, settings = savedSettings,
                    financials = requireNotNull(snapshot.financials) { "Backup financial summary is unavailable" }, warnings = warnings, phaseMillis = progress.finish())
            } finally { directory.deleteRecursively() }
        }
    }
    suspend fun inspect(source: Uri, password: String? = null, onProgress: (String) -> Unit = {}): BackupReport = withContext(Dispatchers.IO) {
        val directory = staging()
        val progress = OperationProgress("Inspect backup", onProgress)
        try { read(source, directory, password, progress = progress).report.copy(phaseMillis = progress.finish()) } finally { directory.deleteRecursively() }
    }
    /** Inspect mapped legacy records in an isolated database before replacing the current ledger. */
    suspend fun inspectPrepared(records: JSONObject): BackupReport = withContext(Dispatchers.IO) {
        val directory = staging()
        try {
            val recordsFile = File(directory, "records.json").apply { writeText(records.toString()) }
            require(recordsFile.length() <= ArchiveIO.MAX_JSON_BYTES) { "Mapped ledger exceeds the 256 MiB JSON limit" }
            val snapshot = repository.inspectRecordsFile(recordsFile, LedgerRepository.SNAPSHOT_VERSION)
            BackupReport(snapshot.counts, snapshot.attachments.size,
                snapshot.attachments.fold(0L) { total, file -> Math.addExact(total, file.size) },
                System.currentTimeMillis(), "BuildLedgerLegacy",
                financials = requireNotNull(snapshot.financials) { "Mapped ledger financial summary is unavailable" })
        } finally { directory.deleteRecursively() }
    }
    suspend fun restore(source: Uri, password: String? = null, expectedArchiveSha256: String? = null, onProgress: (String) -> Unit = {}): BackupReport {
        var committedReport: BackupReport? = null
        return try { withContext(Dispatchers.IO) {
            AttachmentStore.operationMutex.withLock {
                val directory = staging()
                val progress = OperationProgress("Restore", onProgress)
                try {
                    restoreCheckpoint("before-validation")
                    val candidate = read(source, directory, password, restoring = true, progress = progress)
                    require(expectedArchiveSha256 == null || candidate.report.archiveSha256 == expectedArchiveSha256) { "Selected backup changed during confirmation. Choose the file again and review it before restoring" }
                    install(candidate.recordsFile, candidate.schema, candidate.attachments, candidate.entries.filterKeys { it.startsWith("files/") }
                        .mapKeys { (path, _) -> path.removePrefix("files/") }, candidate.report, progress) { committedReport = it }
                } finally { directory.deleteRecursively() }
            }
        } } catch (failure: Exception) { committedReport ?: throw failure }
    }
    /** The legacy mapper supplies native table rows and authenticated original files by attachment ID. */
    suspend fun restorePrepared(records: JSONObject, files: Map<String, File>, onProgress: (String) -> Unit = {}): BackupReport {
        var committedReport: BackupReport? = null
        return try { withContext(Dispatchers.IO) {
            AttachmentStore.operationMutex.withLock {
                val directory = staging()
                val progress = OperationProgress("Legacy restore", onProgress)
                try {
                    progress.phase("Validating mapped ledger")
                    restoreCheckpoint("before-validation")
                    val recordsFile = File(directory, "records.json").apply { writeText(records.toString()) }
                    require(recordsFile.length() <= ArchiveIO.MAX_JSON_BYTES) { "Mapped ledger exceeds the 256 MiB JSON limit" }
                    val snapshot = repository.inspectRecordsFile(recordsFile, LedgerRepository.SNAPSHOT_VERSION) { progress.count(it) }
                    require(snapshot.counts.values.sumOf { it.toLong() } <= ArchiveIO.MAX_RECORDS) { "Mapped ledger contains too many records" }
                    require(snapshot.attachments.size == files.size && snapshot.attachments.size <= ArchiveIO.MAX_FILES) { "Document inventory does not match mapped records" }
                    var bytes = 0L
                    progress.phase("Verifying restored documents")
                    for ((index, info) in snapshot.attachments.withIndex()) {
                        val file = files[info.id] ?: error("Mapped document is missing: ${info.name}")
                        require(file.isFile && file.length() == info.size && DocumentPolicy.sha256(file) == info.sha256) { "Mapped document checksum or size mismatch: ${info.name}" }
                        DocumentPolicy.validate(file, info.mimeType)
                        bytes += info.size
                        require(bytes + recordsFile.length() <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Mapped archive exceeds 850 MiB" }
                        progress.count(index + 1, snapshot.attachments.size)
                    }
                    install(recordsFile, LedgerRepository.SNAPSHOT_VERSION, snapshot.attachments, files,
                        BackupReport(snapshot.counts, files.size, bytes, System.currentTimeMillis(), "BuildLedgerLegacy",
                            financials = requireNotNull(snapshot.financials) { "Mapped ledger financial summary is unavailable" }), progress) { committedReport = it }
                } finally { directory.deleteRecursively() }
            }
        } } catch (failure: Exception) { committedReport ?: throw failure }
    }
    private suspend fun install(recordsFile: File, schema: Int, attachments: List<AttachmentInfo>, files: Map<String, File>, report: BackupReport,
                                progress: OperationProgress, committedOutcome: (BackupReport) -> Unit): BackupReport {
        restoreCheckpoint("before-install")
        val mayReconcile = LedgerRecoveryStateStore(context).isLedgerValidated()
        require(attachments.size == files.size) { "Document inventory does not match records" }
        require(availablePrivateBytes(context, store.root) > report.totalBytes + 1024 * 1024) { "Not enough private storage to restore while retaining current documents" }
        val installed = mutableListOf<File>()
        val storageNames = mutableMapOf<String, String>()
        var committed = false
        progress.phase("Retaining previous originals")
        val recoverySet = if (mayReconcile) store.retainRestoreOriginalsLocked() else null
        try {
            return withContext(NonCancellable) {
                progress.phase("Installing original documents")
                for ((index, info) in attachments.withIndex()) {
                    val sourceFile = files[info.id] ?: error("Original document is missing")
                    val destination = DocumentPolicy.safeFile(store.root, "${UUID.randomUUID()}.bin")
                    installed.add(destination)
                    FileOutputStream(destination).use { output -> sourceFile.inputStream().use { DocumentPolicy.copy(it, output) }; output.fd.sync() }
                    require(destination.length() == info.size && DocumentPolicy.sha256(destination) == info.sha256) { "Original document changed during restore" }
                    storageNames[info.id] = destination.name
                    restoreCheckpoint("after-copy:${info.id}")
                    progress.count(index + 1, attachments.size)
                }
                progress.phase("Replacing ledger records")
                restoreCheckpoint("before-database-replace")
                repository.replaceInspectedRecordsFile(recordsFile, schema, storageNames) { progress.count(it, report.counts.values.sum()) }
                committed = true
                var result = report
                try {
                    restoreCheckpoint("after-commit")
                    progress.phase("Checking restored originals")
                    if (mayReconcile) store.reconcileLocked()
                    restoreCheckpoint("before-completion")
                    result = result.copy(phaseMillis = progress.finish())
                } catch (failure: Exception) {
                    result = result.copy(warnings = result.warnings + "Ledger restored; follow-up did not finish: ${failure.message ?: "reopen the ledger and review Settings"}")
                } finally { committedOutcome(result) }
                result
            }
        } finally { if (!committed) { installed.forEach { it.delete() }; recoverySet?.let(store::discardRecoverySetLocked) } }
    }
    private data class Candidate(val recordsFile: File, val schema: Int, val attachments: List<AttachmentInfo>, val entries: Map<String, File>, val report: BackupReport)
    private suspend fun read(source: Uri, directory: File, password: String?, restoring: Boolean = false, progress: OperationProgress): Candidate {
        progress.phase("Reading backup file")
        val staged = context.contentResolver.openInputStream(source)?.use { ArchiveIO.stage(it, directory) } ?: error("Cannot open selected backup")
        val archive = if (NativeBackupEncryption.isEncrypted(staged)) {
            progress.phase("Decrypting backup")
            require(password != null) { "Enter the encrypted backup password before choosing its file" }
            File(directory, "decrypted.zip").also { file ->
                try { NativeBackupEncryption.decrypt(staged, file, password) }
                catch (failure: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("Incorrect backup password or damaged encrypted archive", failure) }
            }
        } else staged
        progress.phase("Extracting backup")
        val entries = ArchiveIO.extract(archive, directory) { availablePrivateBytes(context, directory) }
        if (restoring) restoreCheckpoint("after-extraction")
        val manifest = JSONObject(entries["manifest.json"]?.readText() ?: error("Backup manifest is missing"))
        val schema = manifest.get("schema")
        require(manifest.get("product") == "BuildLedgerNative" && manifest.get("format") == 1 && schema is Int && schema in 1..LedgerRepository.SNAPSHOT_VERSION) { "Unsupported native backup format" }
        val version = schema
        val settings = if (manifest.has("settings")) checkedSettings(manifest.getJSONObject("settings")) else {
            require(schema == 1) { "Backup settings are missing" }
            JSONObject()
        }
        val recordsFile = entries["records.json"] ?: error("Backup records are missing")
        require(DocumentPolicy.sha256(recordsFile) == manifest.getString("recordsSha256")) { "Ledger checksum mismatch" }
        progress.phase("Validating ledger records")
        val snapshot = repository.inspectRecordsFile(recordsFile, version) { progress.count(it) }
        val counts = snapshot.counts
        require(counts.values.sumOf { it.toLong() } <= ArchiveIO.MAX_RECORDS) { "Archive contains too many records" }
        val expected = manifest.getJSONObject("counts")
        require(expected.keys().asSequence().toSet() == counts.keys && counts.all { (name, count) -> expected.get(name) == count }) { "Backup record counts do not match" }
        val rows = snapshot.attachments
        val files = manifest.getJSONArray("files")
        require(files.length() == rows.size && files.length() <= ArchiveIO.MAX_FILES) { "Backup document inventory does not match" }
        val byId = mutableMapOf<String, JSONObject>()
        for (index in 0 until files.length()) {
            val file = files.getJSONObject(index)
            val id = file.getString("id")
            require(id.matches(Regex("[A-Za-z0-9_-]+")) && byId.put(id, file) == null && file.getString("path") == "files/$id") { "Unsafe or duplicate document inventory" }
        }
        val seen = mutableSetOf<String>()
        var bytes = 0L
        progress.phase("Verifying restored documents")
        for ((index, info) in rows.withIndex()) {
            require(seen.add(info.id)) { "Duplicate document ID" }
            store.fileFor(info)
            val meta = byId[info.id] ?: error("Document is absent from backup inventory")
            val file = entries["files/${info.id}"] ?: error("Original document is missing from backup: ${info.name}")
            val size = meta.get("size")
            require((size is Int || size is Long) && meta.getLong("size") == info.size && file.length() == info.size && meta.getString("sha256") == info.sha256 && DocumentPolicy.sha256(file) == info.sha256) { "Document checksum or size mismatch: ${info.name}" }
            DocumentPolicy.validate(file, info.mimeType)
            bytes += info.size
            progress.count(index + 1, rows.size)
        }
        require(entries.keys == seen.map { "files/$it" }.toSet() + setOf("manifest.json", "records.json")) { "Backup contains unexpected documents" }
        val time = manifest.get("createdAt")
        require(time is Long || time is Int) { "Invalid archive creation time" }
        require((time as Number).toLong() >= 0) { "Invalid archive creation time" }
        return Candidate(recordsFile, version, rows, entries, BackupReport(counts, seen.size, bytes, time.toLong(),
            settings = settings, financials = requireNotNull(snapshot.financials) { "Backup financial summary is unavailable" },
            archiveSha256 = DocumentPolicy.sha256(staged)))
    }
    private fun checkedSettings(value: JSONObject): JSONObject {
        val allowed = setOf("project", "reminders", "showArchived")
        require(value.keys().asSequence().all { key -> key in allowed && when (key) {
            "project" -> value.get(key) is String
            else -> value.get(key) is Boolean
        } }) { "Invalid backup settings" }
        val copy = JSONObject(value.toString())
        require(copy.toString().toByteArray(Charsets.UTF_8).size <= 16 * 1024) { "Backup settings exceed 16 KiB" }
        return copy
    }
    private fun staging(): File = File(context.cacheDir, "backup-${UUID.randomUUID()}").apply { check(mkdirs()) { "Cannot create private backup staging directory" } }
}
