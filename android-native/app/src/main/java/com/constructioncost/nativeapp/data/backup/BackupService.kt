package com.constructioncost.nativeapp.data.backup

import android.content.Context
import android.net.Uri
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.data.repository.SnapshotFinancials
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
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
                        val financials: SnapshotFinancials)

class BackupService(private val context: Context, private val repository: LedgerRepository, private val store: AttachmentStore) {
    suspend fun create(destination: Uri, settings: JSONObject = JSONObject(), password: String? = null): BackupReport = withContext(Dispatchers.IO) {
        AttachmentStore.operationMutex.withLock {
            val savedSettings = checkedSettings(settings)
            store.reconcileLocked()
            val directory = staging()
            try {
                val recordsFile = File(directory, "records.json")
                val snapshot = repository.exportRecordsTo(recordsFile)
                val counts = snapshot.counts
                val rows = snapshot.attachments
                require(counts.values.sumOf { it.toLong() } <= ArchiveIO.MAX_RECORDS) { "Ledger contains too many records" }
                require(rows.size <= ArchiveIO.MAX_FILES) { "Too many documents for one archive" }
                require(recordsFile.length() <= ArchiveIO.MAX_JSON_BYTES) { "Ledger exceeds the 256 MiB JSON backup limit" }
                val inventory = JSONArray()
                var bytes = 0L
                for (info in rows) {
                    require(info.id.matches(Regex("[A-Za-z0-9_-]+"))) { "Unsafe document ID" }
                    val file = store.fileFor(info)
                    require(file.isFile && file.length() == info.size && DocumentPolicy.sha256(file) == info.sha256) { "Missing or damaged original document: ${info.name}" }
                    DocumentPolicy.validate(file, info.mimeType)
                    bytes += file.length()
                    require(bytes + recordsFile.length() <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Archive exceeds 850 MiB" }
                    inventory.put(JSONObject().put("id", info.id).put("path", "files/${info.id}").put("size", info.size).put("sha256", info.sha256))
                }
                val time = System.currentTimeMillis()
                val manifest = JSONObject().put("product", "BuildLedgerNative").put("format", 1).put("schema", LedgerRepository.SNAPSHOT_VERSION).put("createdAt", time)
                    .put("counts", JSONObject(counts)).put("files", inventory).put("recordsSha256", DocumentPolicy.sha256(recordsFile)).put("settings", savedSettings)
                val archive = File(directory, "backup.zip")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    fun entry(name: String, file: File, expectedHash: String? = null) {
                        zip.putNextEntry(ZipEntry(name))
                        val digest = MessageDigest.getInstance("SHA-256")
                        DigestInputStream(file.inputStream(), digest).use { DocumentPolicy.copy(it, zip, if (name == "records.json") ArchiveIO.MAX_JSON_BYTES else DocumentPolicy.MAX_FILE_BYTES) }
                        require(expectedHash == null || digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "Document changed while creating backup" }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                    entry("records.json", recordsFile)
                    for (info in rows) entry("files/${info.id}", store.fileFor(info), info.sha256)
                }
                require(archive.length() <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Archive exceeds 850 MiB" }
                val exported = if (password == null) archive else File(directory, "backup.blbackup").also { NativeBackupEncryption.encrypt(archive, it, password) }
                context.contentResolver.openOutputStream(destination, "wt")?.use { output -> exported.inputStream().use { DocumentPolicy.copy(it, output, ArchiveIO.MAX_ARCHIVE_BYTES) } }
                    ?: error("Cannot write backup destination")
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
                require(savedSize == exported.length() && digest.digest().joinToString("") { "%02x".format(it) } == DocumentPolicy.sha256(exported)) { "Saved backup differs from the verified archive. Retry to a local destination" }
                BackupReport(counts, rows.size, bytes, time, settings = savedSettings,
                    financials = requireNotNull(snapshot.financials) { "Backup financial summary is unavailable" })
            } finally { directory.deleteRecursively() }
        }
    }
    suspend fun inspect(source: Uri, password: String? = null): BackupReport = withContext(Dispatchers.IO) {
        val directory = staging()
        try { read(source, directory, password).report } finally { directory.deleteRecursively() }
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
    suspend fun restore(source: Uri, password: String? = null): BackupReport = withContext(Dispatchers.IO) {
        AttachmentStore.operationMutex.withLock {
            val directory = staging()
            try {
                val candidate = read(source, directory, password)
                install(candidate.recordsFile, candidate.schema, candidate.attachments, candidate.entries.filterKeys { it.startsWith("files/") }
                    .mapKeys { (path, _) -> path.removePrefix("files/") }, candidate.report)
            } finally { directory.deleteRecursively() }
        }
    }
    /** The legacy mapper supplies native table rows and authenticated original files by attachment ID. */
    suspend fun restorePrepared(records: JSONObject, files: Map<String, File>): BackupReport = withContext(Dispatchers.IO) {
        AttachmentStore.operationMutex.withLock {
            val directory = staging()
            try {
                val recordsFile = File(directory, "records.json").apply { writeText(records.toString()) }
                require(recordsFile.length() <= ArchiveIO.MAX_JSON_BYTES) { "Mapped ledger exceeds the 256 MiB JSON limit" }
                val snapshot = repository.inspectRecordsFile(recordsFile, LedgerRepository.SNAPSHOT_VERSION)
                require(snapshot.counts.values.sumOf { it.toLong() } <= ArchiveIO.MAX_RECORDS) { "Mapped ledger contains too many records" }
                require(snapshot.attachments.size == files.size && snapshot.attachments.size <= ArchiveIO.MAX_FILES) { "Document inventory does not match mapped records" }
                var bytes = 0L
                for (info in snapshot.attachments) {
                    val file = files[info.id] ?: error("Mapped document is missing: ${info.name}")
                    require(file.isFile && file.length() == info.size && DocumentPolicy.sha256(file) == info.sha256) { "Mapped document checksum or size mismatch: ${info.name}" }
                    DocumentPolicy.validate(file, info.mimeType)
                    bytes += info.size
                    require(bytes + recordsFile.length() <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Mapped archive exceeds 850 MiB" }
                }
                install(recordsFile, LedgerRepository.SNAPSHOT_VERSION, snapshot.attachments, files,
                    BackupReport(snapshot.counts, files.size, bytes, System.currentTimeMillis(), "BuildLedgerLegacy",
                        financials = requireNotNull(snapshot.financials) { "Mapped ledger financial summary is unavailable" }))
            } finally { directory.deleteRecursively() }
        }
    }
    private suspend fun install(recordsFile: File, schema: Int, attachments: List<AttachmentInfo>, files: Map<String, File>, report: BackupReport): BackupReport {
        require(attachments.size == files.size) { "Document inventory does not match records" }
        require(store.root.usableSpace > report.totalBytes + 1024 * 1024) { "Not enough private storage to restore while retaining current documents" }
        val installed = mutableListOf<File>()
        val storageNames = mutableMapOf<String, String>()
        var committed = false
        try {
            withContext(NonCancellable) {
                for (info in attachments) {
                    val sourceFile = files[info.id] ?: error("Original document is missing")
                    val destination = DocumentPolicy.safeFile(store.root, "${UUID.randomUUID()}.bin")
                    installed.add(destination)
                    FileOutputStream(destination).use { output -> sourceFile.inputStream().use { DocumentPolicy.copy(it, output) }; output.fd.sync() }
                    require(destination.length() == info.size && DocumentPolicy.sha256(destination) == info.sha256) { "Original document changed during restore" }
                    storageNames[info.id] = destination.name
                }
                repository.replaceRecordsFile(recordsFile, schema, storageNames)
                committed = true
                store.reconcileLocked()
            }
            return report
        } finally { if (!committed) installed.forEach { it.delete() } }
    }
    private data class Candidate(val recordsFile: File, val schema: Int, val attachments: List<AttachmentInfo>, val entries: Map<String, File>, val report: BackupReport)
    private suspend fun read(source: Uri, directory: File, password: String?): Candidate {
        val staged = context.contentResolver.openInputStream(source)?.use { ArchiveIO.stage(it, directory) } ?: error("Cannot open selected backup")
        val archive = if (NativeBackupEncryption.isEncrypted(staged)) {
            require(password != null) { "Enter the encrypted backup password before choosing its file" }
            File(directory, "decrypted.zip").also { file ->
                try { NativeBackupEncryption.decrypt(staged, file, password) }
                catch (failure: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("Incorrect backup password or damaged encrypted archive", failure) }
            }
        } else staged
        val entries = ArchiveIO.extract(archive, directory)
        val manifest = JSONObject(entries["manifest.json"]?.readText() ?: error("Backup manifest is missing"))
        val schema = manifest.get("schema")
        require(manifest.get("product") == "BuildLedgerNative" && manifest.get("format") == 1 && schema is Int && schema in 1..LedgerRepository.SNAPSHOT_VERSION) { "Unsupported native backup format" }
        val version = schema as Int
        val settings = if (manifest.has("settings")) checkedSettings(manifest.getJSONObject("settings")) else {
            require(schema == 1) { "Backup settings are missing" }
            JSONObject()
        }
        val recordsFile = entries["records.json"] ?: error("Backup records are missing")
        require(DocumentPolicy.sha256(recordsFile) == manifest.getString("recordsSha256")) { "Ledger checksum mismatch" }
        val snapshot = repository.inspectRecordsFile(recordsFile, version)
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
        for (info in rows) {
            require(seen.add(info.id)) { "Duplicate document ID" }
            store.fileFor(info)
            val meta = byId[info.id] ?: error("Document is absent from backup inventory")
            val file = entries["files/${info.id}"] ?: error("Original document is missing from backup: ${info.name}")
            val size = meta.get("size")
            require((size is Int || size is Long) && meta.getLong("size") == info.size && file.length() == info.size && meta.getString("sha256") == info.sha256 && DocumentPolicy.sha256(file) == info.sha256) { "Document checksum or size mismatch: ${info.name}" }
            DocumentPolicy.validate(file, info.mimeType)
            bytes += info.size
        }
        require(entries.keys == seen.map { "files/$it" }.toSet() + setOf("manifest.json", "records.json")) { "Backup contains unexpected documents" }
        val time = manifest.get("createdAt")
        require(time is Long || time is Int) { "Invalid archive creation time" }
        require((time as Number).toLong() >= 0) { "Invalid archive creation time" }
        return Candidate(recordsFile, version, rows, entries, BackupReport(counts, seen.size, bytes, time.toLong(),
            settings = settings, financials = requireNotNull(snapshot.financials) { "Backup financial summary is unavailable" }))
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
