package com.constructioncost.nativeapp

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.backup.BackupReport
import com.constructioncost.nativeapp.data.backup.LegacyArchiveReader
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.Money
import com.constructioncost.nativeapp.platform.CatalogSeeder
import com.constructioncost.nativeapp.platform.LegacyImportMapper
import com.constructioncost.nativeapp.platform.UiActions
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.platform.pdf.PdfScreen
import com.constructioncost.nativeapp.ui.BuildLedgerApp
import com.constructioncost.nativeapp.ui.ledgerPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    internal val repository get() = (application as ConstructionApplication).repository
    private val documents by lazy { AttachmentStore(applicationContext, repository) }
    private val backups by lazy { BackupService(applicationContext, repository, documents) }
    private var currentProject = "default-project"
    private var revisionParentId: String? = null
    private var documentToExportId: String? = null
    private var exportProject = "default-project"
    private var pendingLegacyPassword = ""
    private var pendingBackupPassword: String? = null
    private var pendingBackupEncrypted = false
    private var pendingPhotoName: String? = null
    private var pendingPhotoProject: String? = null
    private var viewedPdf by mutableStateOf<AttachmentInfo?>(null)
    private var confirmTitle by mutableStateOf("")
    private var confirmMessage by mutableStateOf("")
    private var confirmAction by mutableStateOf<(() -> Unit)?>(null)
    private var activeOperations by mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ReminderScheduler.configure(applicationContext, true)
        else message("Enable notification permission to receive reminders")
    }

    private val chooseDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            launchSafe {
                val parent = revisionParentId?.let { repository.attachment(it) ?: error("Revision parent no longer exists") }
                val info = documents.importDocument(uri, parent?.projectId ?: currentProject, parent?.let { it.parentId ?: it.id })
                message(if (parent == null) "Document imported" else "Revision imported: ${info.name}")
            }
            revisionParentId = null
        }
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = documentToExportId.also { documentToExportId = null }
        if (uri != null && id != null) lifecycleScope.launch { launchSafe { documents.exportDocument(id, uri); message("Document exported") } }
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = pendingBackupPassword.also { pendingBackupPassword = null }
        val encrypted = pendingBackupEncrypted.also { pendingBackupEncrypted = false }
        if (uri != null && encrypted && password == null) { message("Re-enter the backup password after rotation or restart"); return@registerForActivityResult }
        if (uri != null) lifecycleScope.launch { launchSafe { val result = backups.create(uri, backupSettings(), password); message("Backup saved: ${result.counts["expenses"] ?: 0} expenses, ${result.fileCount} files") } }
    }
    private val restoreBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = pendingBackupPassword.also { pendingBackupPassword = null }
        val encrypted = pendingBackupEncrypted.also { pendingBackupEncrypted = false }
        if (uri != null && encrypted && password == null) { message("Re-enter the backup password after rotation or restart"); return@registerForActivityResult }
        if (uri != null) lifecycleScope.launch { launchSafe {
            val report = backups.inspect(uri, password)
            confirm("Replace local ledger?", restoreSummary(report)) {
                lifecycleScope.launch { launchSafe { val report = backups.restore(uri, password); applyBackupSettings(report.settings); message("Backup restored and verified"); restartAfterRestore() } }
            }
        } }
    }
    private val restoreLegacy = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = pendingLegacyPassword.also { pendingLegacyPassword = "" }
        if (uri != null && password.isBlank()) { message("Re-enter the legacy recovery password after rotation or restart"); return@registerForActivityResult }
        if (uri != null) lifecycleScope.launch { launchSafe {
            val archive = LegacyArchiveReader(applicationContext).read(uri, password)
            try {
                val mapped = withContext(Dispatchers.IO) { LegacyImportMapper(applicationContext).map(archive.state) }
                val report = backups.inspectPrepared(mapped.records)
                val checksum = archive.metadata.getJSONObject("database").getString("sha256")
                archive.close()
                confirm("Import legacy project?", restoreSummary(report)) {
                    lifecycleScope.launch { launchSafe {
                        LegacyArchiveReader(applicationContext).read(uri, password).use { candidate ->
                            require(candidate.metadata.getJSONObject("database").getString("sha256") == checksum) { "Selected legacy backup changed during confirmation" }
                            backups.restorePrepared(mapped.records, candidate.files)
                        }
                        applyBackupSettings(JSONObject().put("project", "legacy-project"))
                        message("Legacy project imported and verified")
                        restartAfterRestore()
                    } }
                }
            } catch (failure: Throwable) { archive.close(); throw failure }
        } }
    }
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) lifecycleScope.launch { launchSafe { LedgerExports.csv(contentResolver, uri, repository, exportProject); message("CSV exported") } }
    }
    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) lifecycleScope.launch { launchSafe {
            val summary = repository.summary(exportProject).first()
            val name = repository.projects.first().firstOrNull { it.id == exportProject }?.name ?: "Local project"
            LedgerExports.pdf(contentResolver, uri, summary, name); message("PDF report exported")
        } }
    }
    private val chooseDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch { launchSafe {
            val imported = documents.importDocuments(uris, currentProject)
            message("${imported.size} ${if (imported.size == 1) "document" else "documents"} imported")
        } }
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val name = pendingPhotoName.also { pendingPhotoName = null }
        val project = pendingPhotoProject.also { pendingPhotoProject = null }
        if (name == null) return@registerForActivityResult
        val temporary = try { captureFile(name) } catch (failure: Exception) {
            message(failure.message ?: "Camera photo could not be recovered")
            return@registerForActivityResult
        }
        if (!captured || project == null) {
            temporary.delete()
            if (!captured) message("Photo capture cancelled; no document was added")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                launchSafe {
                    require(temporary.isFile && temporary.length() > 0) { "Camera did not save a photo" }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", temporary)
                    documents.importDocument(uri, project)
                    message("Photo imported as a document")
                }
            } finally { temporary.delete() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentProject = savedInstanceState?.getString("documentProject") ?: currentProject
        exportProject = savedInstanceState?.getString("exportProject") ?: exportProject
        revisionParentId = savedInstanceState?.getString("revisionParent")
        documentToExportId = savedInstanceState?.getString("exportDocument")
        pendingBackupEncrypted = savedInstanceState?.getBoolean("backupEncrypted") ?: false
        pendingPhotoName = savedInstanceState?.getString("photoName")
        pendingPhotoProject = savedInstanceState?.getString("photoProject")
        val startupPhotoName = pendingPhotoName
        val captureCleanupBefore = System.currentTimeMillis()
        lifecycleScope.launch { launchSafe {
            withContext(Dispatchers.IO) { DocumentPolicy.cleanupCameraFiles(File(cacheDir, "exports"), startupPhotoName, captureCleanupBefore) }
            repository.ensureDefaultProject()
            val seeder = CatalogSeeder(applicationContext, repository)
            repository.projects.first().forEach { seeder.seed(it.id) }
            documents.reconcile()
            savedInstanceState?.getString("viewedPdf")?.let { viewedPdf = repository.attachment(it) }
        } }
        val actions = UiActions(
            importDocument = { project -> currentProject = project; revisionParentId = null; chooseDocuments.launch(arrayOf("*/*")) },
            capturePhoto = { project -> capturePhoto(project) },
            viewDocument = { info -> if (info.mimeType == "application/pdf") viewedPdf = info else lifecycleScope.launch { launchSafe { viewExternal(info) } } },
            shareDocument = { id -> lifecycleScope.launch { launchSafe { startActivity(Intent.createChooser(documents.shareIntent(id), "Share document")) } } },
            exportDocument = { info -> documentToExportId = info.id; exportDocument.launch(info.name) },
            renameDocument = { info, name -> lifecycleScope.launch { launchSafe { documents.rename(info.id, name, info.revision); message("Document renamed") } } },
            deleteDocument = { info -> confirm("Delete document?", "Remove ${info.name} from private app storage? Linked originals cannot be deleted until their links are removed.") {
                lifecycleScope.launch { launchSafe { documents.delete(info.id); message("Document deleted") } }
            } },
            newRevision = { info -> currentProject = info.projectId; revisionParentId = info.id; chooseDocument.launch(arrayOf("*/*")) },
            createBackup = { password -> pendingBackupPassword = password; pendingBackupEncrypted = password != null; createBackup.launch(if (password == null) "BuildLedger-backup.zip" else "BuildLedger-backup.blbackup") },
            restoreBackup = { password -> pendingBackupPassword = password; pendingBackupEncrypted = password != null; restoreBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
            importLegacy = { password -> pendingLegacyPassword = password; restoreLegacy.launch(arrayOf("application/zip", "application/octet-stream")) },
            exportCsv = { project -> exportProject = project; exportCsv.launch("BuildLedger-ledger.csv") },
            exportPdf = { project -> exportProject = project; exportPdf.launch("BuildLedger-report.pdf") },
            notifyMessage = ::message,
            setReminders = { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ReminderScheduler.configure(applicationContext, enabled)
            }
        )
        setContent {
            MaterialTheme {
                val pdf = viewedPdf
                if (pdf == null) BuildLedgerApp(repository, actions)
                else PdfScreen(pdf, documents) { viewedPdf = null }
                val action = confirmAction
                if (action != null) AlertDialog(onDismissRequest = { confirmAction = null },
                    title = { Text(confirmTitle) }, text = {
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(confirmMessage)
                        }
                    },
                    confirmButton = { TextButton({ confirmAction = null; action() }) { Text("Continue") } },
                    dismissButton = { TextButton({ confirmAction = null }) { Text("Cancel") } })
                if (activeOperations > 0) AlertDialog(onDismissRequest = {}, confirmButton = {},
                        title = { Text("Working on local data") },
                        text = { Column { LinearProgressIndicator(); Text("Please wait until this operation finishes.") } })
            }
        }
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        confirmTitle = title; confirmMessage = message; confirmAction = action
    }
    private fun restartAfterRestore() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
    private fun restoreSummary(report: BackupReport): String {
        val labels = mapOf(
            "projects" to "Projects", "budgets" to "Budgets", "classifications" to "Categories",
            "vendors" to "Vendors", "commitments" to "Commitments", "instalments" to "Instalments",
            "expenses" to "Expenses", "expense_lines" to "Expense lines",
            "classification_allocations" to "Expense classifications", "planned_classifications" to "Plan classifications",
            "payments" to "Cash transactions", "payment_allocations" to "Payment allocations",
            "attachments" to "Documents", "activity_entries" to "Work logs",
            "attachment_links" to "Document links", "reminders" to "Reminders",
            "audit_records" to "Change log entries", "import_records" to "Import records"
        )
        val counts = report.counts.entries.joinToString("\n") { (table, count) ->
            "${labels[table] ?: table.replace('_', ' ')}: $count"
        }
        return "Verified ${if (report.format == "BuildLedgerLegacy") "legacy project" else "backup"} contains:\n" +
            "$counts\nOriginal files: ${report.fileCount}\n\n" +
            "Candidate totals across all projects:\n" +
            "Recorded cost: ${Money.format(report.financials.recordedPaise)}\n" +
            "Net cash paid: ${Money.format(report.financials.paidPaise)}\n" +
            "Outstanding cost: ${Money.format(report.financials.outstandingPaise)}\n\n" +
            "This replaces the current local ledger."
    }
    private fun captureFile(name: String): File {
        require(name.matches(Regex("photo-[0-9a-fA-F-]{36}\\.jpg"))) { "Invalid camera file name" }
        val directory = File(cacheDir, "exports")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot prepare private camera storage" }
        return File(directory, name)
    }
    private fun capturePhoto(project: String) {
        if (pendingPhotoName != null) { message("Finish the current photo capture first"); return }
        val name = "photo-${UUID.randomUUID()}.jpg"
        var temporary: File? = null
        try {
            require(project.isNotBlank()) { "Choose a project before taking a photo" }
            val file = captureFile(name)
            temporary = file
            check(file.createNewFile()) { "Cannot prepare photo file" }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            pendingPhotoName = name
            pendingPhotoProject = project
            takePhoto.launch(uri)
        } catch (failure: Exception) {
            pendingPhotoName = null
            pendingPhotoProject = null
            temporary?.delete()
            message(if (failure is ActivityNotFoundException) "No camera app is available; no document was added"
                else failure.message ?: "Could not start camera; no document was added")
        }
    }
    private suspend fun launchSafe(action: suspend () -> Unit) {
        activeOperations++
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message(failure.message ?: "Operation failed") }
        finally { activeOperations-- }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("documentProject", currentProject); outState.putString("exportProject", exportProject)
        outState.putString("revisionParent", revisionParentId); outState.putString("exportDocument", documentToExportId)
        outState.putString("viewedPdf", viewedPdf?.id)
        outState.putBoolean("backupEncrypted", pendingBackupEncrypted)
        outState.putString("photoName", pendingPhotoName)
        outState.putString("photoProject", pendingPhotoProject)
        super.onSaveInstanceState(outState)
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private suspend fun backupSettings(): JSONObject {
        val preferences = applicationContext.ledgerPreferences.data.first()
        val saved = JSONObject(preferences[stringPreferencesKey("settings")] ?: "{}")
        return JSONObject().put("project", preferences[stringPreferencesKey("project")] ?: repository.projects.first().first().id)
            .put("reminders", saved.optBoolean("reminders")).put("showArchived", saved.optBoolean("showArchived"))
    }
    private suspend fun applyBackupSettings(settings: JSONObject) {
        val projects = repository.projects.first()
        val selected = settings.optString("project").takeIf { id -> projects.any { it.id == id } }
            ?: projects.firstOrNull { !it.archived }?.id ?: projects.firstOrNull()?.id.orEmpty()
        applicationContext.ledgerPreferences.edit { preferences ->
            preferences[stringPreferencesKey("project")] = selected
            preferences[stringPreferencesKey("settings")] = JSONObject().put("reminders", settings.optBoolean("reminders"))
                .put("showArchived", settings.optBoolean("showArchived")).toString()
        }
        ReminderScheduler.configure(applicationContext, settings.optBoolean("reminders"))
    }
    private fun viewExternal(info: AttachmentInfo) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", documents.fileFor(info))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, info.mimeType)
            clipData = ClipData.newRawUri(info.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { message("No installed app can view this file type") }
    }
}
