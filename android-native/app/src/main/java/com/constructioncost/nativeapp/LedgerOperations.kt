package com.constructioncost.nativeapp

import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.constructioncost.nativeapp.data.backup.BackupReport
import com.constructioncost.nativeapp.data.backup.ArchiveIO
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.backup.LegacyArchiveReader
import com.constructioncost.nativeapp.data.export.ProjectWorkbookExport
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.domain.DocumentImportDetails
import com.constructioncost.nativeapp.platform.LegacyImportMapper
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.CameraCaptures
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.platform.files.RecoveryCopySet
import com.constructioncost.nativeapp.ui.ledgerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class OperationState(
    val busy: Boolean = false,
    val phase: String = "",
    val message: String? = null,
    val refreshLedger: Boolean = false,
    val confirmation: BackupReport? = null,
    val recovery: Boolean = false,
    val recoveryCopies: List<RecoveryCopySet>? = null,
    val capturedPhotos: List<File>? = null,
)

/** Application ownership keeps file work and committed results alive across Activity recreation. */
class LedgerOperations(private val app: ConstructionApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val journal = app.getSharedPreferences("ledger_operations", 0)
    private val mutable = MutableStateFlow(OperationState(message = journal.getString("outcome", null)
        ?: journal.getString("operation", null)?.let {
            "A previous $it operation was interrupted. Review the ledger and destination files before retrying."
        }))
    val state = mutable.asStateFlow()
    private var confirmedAction: (() -> Unit)? = null
    private val repository get() = app.repository
    private val documents get() = AttachmentStore(app, repository)
    private val backups get() = BackupService(app, repository, documents)

    fun acknowledge() {
        if (!mutable.value.busy) {
            journal.edit().clear().apply()
            mutable.update { it.copy(message = null, refreshLedger = false) }
        }
    }

    fun dismissConfirmation() {
        confirmedAction = null
        mutable.update { it.copy(confirmation = null, recoveryCopies = null, capturedPhotos = null) }
    }

    fun confirmRestore() {
        val action = confirmedAction
        dismissConfirmation()
        action?.invoke()
    }

    internal fun run(operation: String, action: suspend LedgerOperations.() -> Unit) {
        if (mutable.value.busy) return
        mutable.value = OperationState(busy = true, phase = operation)
        scope.launch {
            try {
                check(withContext(Dispatchers.IO) { journal.edit().putString("operation", operation).remove("outcome").commit() }) {
                    "Cannot record operation status; no changes were started"
                }
                app.ledgerLifecycleMutex.withLock { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message(failure.message ?: "Operation failed")
            } finally {
                mutable.value.message?.let { recordOutcome(it) }
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun progress(phase: String) { mutable.update { it.copy(phase = phase) } }
    private fun message(text: String) { mutable.update { it.copy(message = text) } }
    private suspend fun recordOutcome(text: String) = withContext(Dispatchers.IO) {
        journal.edit().putString("outcome", text).commit()
    }

    fun createBackup(uri: Uri, password: String?) = run("Creating and verifying backup") {
        DocumentPolicy.checkExportDestination(app, uri)
        val report = backups.create(uri, backupSettings(), password, ::progress)
        message("Backup saved: ${report.counts["expenses"] ?: 0} expenses, ${report.fileCount} files" +
            report.warnings.joinToString(" ", prefix = if (report.warnings.isEmpty()) "" else ". "))
    }

    fun inspectRestore(uri: Uri, password: String?) = run("Checking backup") {
        val report = backups.inspect(uri, password, ::progress)
        confirmedAction = { restore(uri, password, report) }
        mutable.update { it.copy(confirmation = report, recovery = app.recoveryState.isRecoveryRequired()) }
    }

    private fun restore(uri: Uri, password: String?, report: BackupReport) = run("Validating and restoring backup") {
        if (app.recoveryState.isRecoveryRequired()) {
            app.recoveryState.markRecoveryRequired(LedgerRecoveryState.RESTORE_IN_PROGRESS, "A user-selected backup is being restored.")
            withContext(Dispatchers.IO) {
                AttachmentStore.operationMutex.withLock {
                    app.closeAndResetRepository()
                    preserveRecoveryArtifacts()
                }
            }
        }
        val restored = backups.restore(uri, password, expectedArchiveSha256 = report.archiveSha256, onProgress = ::progress)
        finishRestore(restored)
    }

    internal suspend fun finishRestore(
        report: BackupReport,
        settings: suspend () -> Unit = { applyBackupSettings(report.settings) },
        persistOutcome: suspend (String) -> Boolean = { recordOutcome(it) },
    ) = withContext(NonCancellable) {
        val warnings = report.warnings.toMutableList()
        val outcomeSaved = try { persistOutcome("Ledger restore committed. Check Settings and reminders after restart.") }
            catch (_: Exception) { false }
        if (!outcomeSaved) {
            warnings += "Completion status could not be saved. Verify the ledger after restart."
        }
        if (app.recoveryState.isRecoveryRequired()) {
            try {
                repository.validateStorage()
                app.recoveryState.markLedgerValidated()
            } catch (failure: Exception) {
                warnings += "Database verification needs attention; use recovery before entering more records."
            }
        }
        try { settings() }
        catch (failure: Exception) { warnings += "Settings or reminders could not be restored. Retry in Settings." }
        mutable.update { it.copy(refreshLedger = true, message =
            if (warnings.isEmpty()) "Backup restored and verified"
            else "Ledger restored. ${warnings.distinct().joinToString(" ")}") }
    }

    fun inspectLegacy(uri: Uri, password: String) = run("Checking legacy archive") {
        val archive = LegacyArchiveReader(app).read(uri, password)
        try {
            val mapped = withContext(Dispatchers.IO) { LegacyImportMapper(app).map(archive.state) }
            val report = backups.inspectPrepared(mapped.records)
            val checksum = archive.metadata.getJSONObject("database").getString("sha256")
            archive.close()
            confirmedAction = {
                run("Importing legacy project") {
                    val restored = LegacyArchiveReader(app).read(uri, password).use { candidate ->
                        require(candidate.metadata.getJSONObject("database").getString("sha256") == checksum) { "Selected legacy backup changed during confirmation" }
                        backups.restorePrepared(mapped.records, candidate.files, ::progress)
                    }
                    finishRestore(restored.copy(settings = JSONObject().put("project", "legacy-project")))
                }
            }
            mutable.update { it.copy(confirmation = report) }
        } catch (failure: Throwable) { archive.close(); throw failure }
    }

    fun importDocuments(uris: List<Uri>, project: String, details: DocumentImportDetails?) = run("Importing documents") {
        val imported = documents.importDocuments(uris, project, details = details)
        message("${imported.size} ${if (imported.size == 1) "document" else "documents"} imported")
    }

    fun importRevision(uri: Uri, project: String, parentId: String?) = run("Importing document") {
        val parent = parentId?.let { repository.attachment(it) ?: error("Revision parent no longer exists") }
        val info = documents.importDocument(uri, parent?.projectId ?: project, parent?.let { it.parentId ?: it.id })
        message(if (parent == null) "Document imported" else "Revision imported: ${info.name}")
    }

    fun importPhoto(file: File, project: String) = run("Importing photo") {
        val captures = CameraCaptures(app)
        try {
            val source = withContext(Dispatchers.IO) {
                captures.checked(file).also {
                    require(it.isFile && it.length() > 0) { "Camera did not save a photo" }
                    FileOutputStream(it, true).use { output -> output.fd.sync() }
                }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", source)
            documents.importDocument(uri, project)
            message(try { captures.discard(source); "Photo imported as a document" }
                catch (_: Exception) { "Photo imported; the retained capture could not be removed. Review captured photos." })
        } finally {
            val remaining = withContext(Dispatchers.IO) { captures.list() }
            mutable.update { it.copy(capturedPhotos = remaining.takeIf { photos -> photos.isNotEmpty() }) }
        }
    }

    fun reviewCapturedPhotos() = run("Checking captured photos") {
        val photos = withContext(Dispatchers.IO) { CameraCaptures(app).list() }
        mutable.update { it.copy(capturedPhotos = photos.takeIf { items -> items.isNotEmpty() }) }
        if (photos.isEmpty()) message("No captured photos need review")
    }

    fun discardCapturedPhoto(file: File) = run("Discarding captured photo") {
        val captures = CameraCaptures(app)
        val remaining = withContext(Dispatchers.IO) { captures.discard(file); captures.list() }
        mutable.update { it.copy(capturedPhotos = remaining.takeIf { photos -> photos.isNotEmpty() }) }
        message("Captured photo discarded")
    }

    fun exportDocument(id: String, uri: Uri) = run("Exporting document") {
        documents.exportDocument(id, uri)
        message("Document exported")
    }

    fun renameDocument(id: String, name: String, revision: Long) = run("Renaming document") {
        documents.rename(id, name, revision)
        message("Document renamed")
    }

    fun deleteDocument(id: String) = run("Deleting document") {
        documents.delete(id)
        message("Document deleted")
    }

    fun reviewRecoveryCopies() = run("Checking retained originals") {
        mutable.update { it.copy(recoveryCopies = documents.recoveryCopies()) }
    }

    fun cleanupRecoveryCopies() {
        val ids = mutable.value.recoveryCopies.orEmpty().filter { it.backedUp }.map { it.id }.toSet()
        dismissConfirmation()
        if (ids.isEmpty()) return
        run("Cleaning verified recovery copies") {
            val removed = documents.cleanupRecoveryCopies(ids)
            message("Removed $removed bytes of backed-up recovery originals")
        }
    }

    fun exportCsv(uri: Uri, project: String) = run("Exporting CSV") {
        DocumentPolicy.checkExportDestination(app, uri)
        LedgerExports.csv(app.contentResolver, uri, repository, project, ::progress)
        message("CSV exported")
    }

    fun exportPdf(uri: Uri, project: String) = run("Exporting PDF report") {
        DocumentPolicy.checkExportDestination(app, uri)
        val summary = repository.summary(project).first()
        val name = repository.projects.first().firstOrNull { it.id == project }?.name ?: "Local project"
        LedgerExports.pdf(app.contentResolver, uri, summary, name, ::progress)
        message("PDF report exported")
    }
    fun exportProject(uri: Uri, projectId: String) = run("Creating and verifying Excel project export") {
        DocumentPolicy.checkExportDestination(app, uri)
        val packageFile = ProjectWorkbookExport.create(app.cacheDir, repository, documents, projectId, ::progress)
        try {
            progress("Saving verified project package")
            val written = app.contentResolver.openOutputStream(uri, "wt")?.use { output -> packageFile.inputStream().use { DocumentPolicy.copy(it, output, ArchiveIO.MAX_ARCHIVE_BYTES) } }
                ?: error("Cannot create project export destination")
            check(written == packageFile.length()) { "Project export destination did not save the verified package completely" }
            message("Project Excel package exported")
        } finally { packageFile.delete() }
    }
    fun deleteProject(projectId: String) = run("Deleting project permanently") {
        documents.deleteProject(projectId, ::progress)
        message("Project deleted permanently")
    }

    private suspend fun backupSettings(): JSONObject {
        val preferences = app.ledgerPreferences.data.first()
        val saved = JSONObject(preferences[stringPreferencesKey("settings")] ?: "{}")
        return JSONObject().put("project", preferences[stringPreferencesKey("project")] ?: repository.projects.first().firstOrNull()?.id.orEmpty())
            .put("reminders", saved.optBoolean("reminders")).put("showArchived", saved.optBoolean("showArchived"))
    }

    private suspend fun applyBackupSettings(settings: JSONObject) {
        val projects = repository.projects.first()
        val selected = settings.optString("project").takeIf { id -> projects.any { it.id == id } }
            ?: projects.firstOrNull { !it.archived }?.id ?: projects.firstOrNull()?.id.orEmpty()
        val reminders = settings.optBoolean("reminders") && ReminderScheduler.permissionGranted(app)
        app.ledgerPreferences.edit { preferences ->
            preferences[stringPreferencesKey("project")] = selected
            preferences[stringPreferencesKey("settings")] = JSONObject().put("reminders", reminders)
                .put("showArchived", settings.optBoolean("showArchived")).toString()
        }
        ReminderScheduler.refreshPosted(app, repository)
        ReminderScheduler.configure(app, reminders)
    }

    private fun preserveRecoveryArtifacts() {
        val root = File(app.filesDir, "recovery-preserved").apply { check(isDirectory || mkdirs()) }
        val destination = File(root, "${System.currentTimeMillis()}-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val database = app.getDatabasePath(LedgerRecoveryStateStore.DATABASE_NAME)
        val databaseFiles = listOf("", "-wal", "-shm", "-journal", ".restore-revision", ".restore-revision.bak", ".restore-revision.new")
            .map { File(database.path + it) }.filter { it.isFile }
        val manifest = JSONArray()
        for (source in databaseFiles) {
            val target = File(destination, "copy-${source.name}")
            source.inputStream().use { input -> java.io.FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } }
            check(target.length() == source.length() && DocumentPolicy.sha256(target) == DocumentPolicy.sha256(source)) { "Recovery copy verification failed" }
            manifest.put(JSONObject().put("file", source.name).put("bytes", target.length()).put("sha256", DocumentPolicy.sha256(target)))
        }
        File(destination, "database-files.json").writeText(JSONObject().put("files", manifest).toString())
        for (source in databaseFiles) check(source.renameTo(File(destination, "live-${source.name}"))) {
            "Could not quarantine ${source.name}; the existing database remains in place"
        }
        listOf("attachments", "attachment-trash").forEach { name ->
            val source = File(app.filesDir, name)
            if (source.exists()) check(source.renameTo(File(destination, name))) { "Could not preserve $name originals" }
        }
    }
}
