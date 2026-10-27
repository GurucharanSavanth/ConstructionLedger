package com.constructioncost.nativeapp

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.constructioncost.nativeapp.data.backup.BackupReport
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.DocumentImportDetails
import com.constructioncost.nativeapp.domain.Money
import com.constructioncost.nativeapp.platform.CatalogSeeder
import com.constructioncost.nativeapp.platform.UiActions
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.CameraCaptures
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.platform.files.DocumentPreviewScreen
import com.constructioncost.nativeapp.platform.files.supportsLocalPreview
import androidx.activity.compose.BackHandler
import com.constructioncost.nativeapp.platform.pdf.PdfScreen
import com.constructioncost.nativeapp.ui.BuildLedgerApp
import com.constructioncost.nativeapp.ui.WebTheme
import com.constructioncost.nativeapp.ui.RecoveryScreen
import com.constructioncost.nativeapp.ui.StartupScreen
import com.constructioncost.nativeapp.ui.LedgerViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory by lazy {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                if (!modelClass.isAssignableFrom(LedgerViewModel::class.java)) return super.create(modelClass, extras)
                @Suppress("UNCHECKED_CAST")
                return LedgerViewModel(applicationContext, repository, extras.createSavedStateHandle()) as T
            }
        }
    }
    internal val repository get() = (application as ConstructionApplication).repository
    internal val operations get() = (application as ConstructionApplication).operations
    private val documents get() = AttachmentStore(applicationContext, repository)
    private var startupStatus by mutableStateOf<LedgerRecoveryState?>(null)
    private var startupError by mutableStateOf<String?>(null)
    private var initializing = false
    private var currentProject = ""
    private var pendingDocumentDetails: DocumentImportDetails? = null
    private var revisionParentId: String? = null
    private var documentToExportId: String? = null
    private var exportProject = ""
    private var pendingLegacyPassword = ""
    private var pendingBackupPassword: String? = null
    private var pendingBackupEncrypted = false
    private var pendingPhotoName: String? = null
    private var pendingPhotoProject: String? = null
    private var reminderChangePending = false
    private var viewedPdf by mutableStateOf<AttachmentInfo?>(null)
    private var confirmTitle by mutableStateOf("")
    private var confirmMessage by mutableStateOf("")
    private var confirmAction by mutableStateOf<(() -> Unit)?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        handleReminderPermissionResult(granted)
    }

    private val chooseDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val parent = revisionParentId.also { revisionParentId = null }
        if (uri != null) operations.importRevision(uri, currentProject, parent)
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val id = documentToExportId.also { documentToExportId = null }
        if (uri != null && id != null) operations.exportDocument(id, uri)
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = pendingBackupPassword.also { pendingBackupPassword = null }
        val encrypted = pendingBackupEncrypted.also { pendingBackupEncrypted = false }
        if (uri != null && encrypted && password == null) { message("Re-enter the backup password after rotation or restart"); return@registerForActivityResult }
        if (uri != null) operations.createBackup(uri, password)
    }
    private val restoreBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = pendingBackupPassword.also { pendingBackupPassword = null }
        val encrypted = pendingBackupEncrypted.also { pendingBackupEncrypted = false }
        if (uri != null && encrypted && password == null) { message("Re-enter the backup password after rotation or restart"); return@registerForActivityResult }
        if (uri != null) operations.inspectRestore(uri, password)
    }
    private val exportDiagnostic = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val app = application as ConstructionApplication
                val database = getDatabasePath(LedgerRecoveryStateStore.DATABASE_NAME)
                val lines = buildList {
                    add("BuildLedger recovery diagnostic")
                    add("State: ${app.recoveryState.state()}")
                    add("Detailed errors are omitted to protect financial information.")
                    listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                        val file = File(database.path + suffix)
                        add("${file.name}: exists=${file.exists()}, bytes=${file.length()}")
                    }
                    add("Attachments directory exists: ${File(filesDir, "attachments").exists()}")
                }
                DocumentPolicy.checkExportDestination(this@MainActivity, uri)
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(lines.joinToString("\n")) }
                    ?: error("Cannot write diagnostic file")
                message("Diagnostic information exported")
            } catch (failure: Exception) { message(failure.message ?: "Diagnostic export failed") }
        }
    }
    private val restoreLegacy = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = pendingLegacyPassword.also { pendingLegacyPassword = "" }
        if (uri != null && password.isBlank()) { message("Re-enter the legacy recovery password after rotation or restart"); return@registerForActivityResult }
        if (uri != null) operations.inspectLegacy(uri, password)
    }
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) operations.exportCsv(uri, exportProject)
    }
    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) operations.exportPdf(uri, exportProject)
    }
    private val exportProjectPackage = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) operations.exportProject(uri, exportProject)
    }
    private val chooseDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val details = pendingDocumentDetails.also { pendingDocumentDetails = null }
        if (uris.isNotEmpty()) operations.importDocuments(uris, currentProject, details)
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
            if (temporary.length() == 0L) temporary.delete()
            message(if (temporary.isFile) "No document was added. Recover the saved photo from Files > Kept camera photos."
                else "Photo capture cancelled; no document was added")
            return@registerForActivityResult
        }
        operations.importPhoto(temporary, project)
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
        pendingDocumentDetails = savedInstanceState?.getStringArrayList("documentDetails")?.takeIf { it.size == 7 }?.let {
            DocumentImportDetails(it[0], it[1], it[2], it[3], it[4], it[5], it[6].ifBlank { null })
        }
        if (!operations.state.value.busy) initializeLedger(savedInstanceState)
        val actions = UiActions(
            importDocument = { project -> currentProject = project; revisionParentId = null; pendingDocumentDetails = null; chooseDocuments.launch(arrayOf("*/*")) },
            importDetailedDocuments = { project, details -> currentProject = project; revisionParentId = null; pendingDocumentDetails = details; chooseDocuments.launch(arrayOf("*/*")) },
            capturePhoto = { project -> capturePhoto(project) },
            viewDocument = { info -> if (info.mimeType == "application/pdf" || supportsLocalPreview(info.mimeType)) viewedPdf = info
                else lifecycleScope.launch { launchSafe { viewExternal(info) } } },
            shareDocument = { id -> lifecycleScope.launch { launchSafe { startActivity(Intent.createChooser(documents.shareIntent(id), "Share document")) } } },
            exportDocument = { info -> documentToExportId = info.id; exportDocument.launch(info.name) },
            renameDocument = { info, name -> operations.renameDocument(info.id, name, info.revision) },
            deleteDocument = { info -> confirm("Delete document?", "Remove ${info.name} from private app storage? Linked originals cannot be deleted until their links are removed.") {
                operations.deleteDocument(info.id)
            } },
            newRevision = { info -> currentProject = info.projectId; revisionParentId = info.id; chooseDocument.launch(arrayOf("*/*")) },
            createBackup = { password -> pendingBackupPassword = password; pendingBackupEncrypted = password != null; createBackup.launch(if (password == null) "BuildLedger-backup.zip" else "BuildLedger-backup.blbackup") },
            restoreBackup = { password -> pendingBackupPassword = password; pendingBackupEncrypted = password != null; restoreBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
            importLegacy = { password -> pendingLegacyPassword = password; restoreLegacy.launch(arrayOf("application/zip", "application/octet-stream")) },
            exportCsv = { project -> exportProject = project; exportCsv.launch("BuildLedger-ledger.csv") },
            exportPdf = { project -> exportProject = project; exportPdf.launch("BuildLedger-report.pdf") },
            exportProject = { project -> exportProject = project; exportProjectPackage.launch("BuildLedger-${project.take(8)}.zip") },
            deleteProject = operations::deleteProject,
            manageRecoveryCopies = operations::reviewRecoveryCopies,
            manageCapturedPhotos = { project -> currentProject = project; operations.reviewCapturedPhotos() },
            notifyMessage = ::message,
            setReminders = ::requestReminders
        )
        setContent {
            WebTheme {
                val operation by operations.state.collectAsState()
                LaunchedEffect(operation.busy, operation.message, operation.refreshLedger) {
                    if (!operation.busy) {
                        operation.message?.let(::message)
                        val refresh = operation.refreshLedger
                        operations.acknowledge()
                        if (refresh) restartAfterRestore()
                        else if (startupStatus == null) initializeLedger(savedInstanceState)
                    }
                }
                val ledgerState = rememberSaveableStateHolder()
                val pdf = viewedPdf
                if (pdf == null) when (val state = startupStatus) {
                    null -> StartupScreen()
                    LedgerRecoveryState.HEALTHY_EXISTING_DATABASE -> ledgerState.SaveableStateProvider("ledger") {
                        BuildLedgerApp(repository, actions,
                            onRecoveryDiagnostic = { exportDiagnostic.launch("BuildLedger-diagnostic.txt") },
                            onRecoveryRetry = ::retryLedger)
                    }
                    else -> RecoveryScreen(state, startupError, ::retryLedger, { password ->
                        pendingBackupPassword = password
                        pendingBackupEncrypted = password != null
                        restoreBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                    }, { exportDiagnostic.launch("BuildLedger-diagnostic.txt") })
                }
                else if (pdf.mimeType == "application/pdf") {
                    BackHandler { viewedPdf = null }
                    PdfScreen(pdf, documents) { viewedPdf = null }
                } else DocumentPreviewScreen(pdf, documents, { viewedPdf = null }, {
                    lifecycleScope.launch { launchSafe { viewExternal(pdf) } }
                })
                val action = confirmAction
                if (action != null) AlertDialog(onDismissRequest = { confirmAction = null },
                    title = { Text(confirmTitle) }, text = {
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(confirmMessage)
                        }
                    },
                    confirmButton = { TextButton({ confirmAction = null; action() }) { Text(confirmTitle.removeSuffix("?")) } },
                    dismissButton = { TextButton({ confirmAction = null }) { Text("Cancel") } })
                operation.confirmation?.let { report -> AlertDialog(onDismissRequest = operations::dismissConfirmation,
                    title = { Text(if (operation.recovery) "Restore verified backup?" else "Replace local ledger?") },
                    text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { Text(restoreSummary(report)) } },
                    confirmButton = { TextButton(operations::confirmRestore) { Text("Restore") } },
                    dismissButton = { TextButton(operations::dismissConfirmation) { Text("Cancel") } }) }
                operation.recoveryCopies?.let { sets -> AlertDialog(onDismissRequest = operations::dismissConfirmation,
                    title = { Text("Retained originals") },
                    text = { Text("${sets.sumOf { it.totalBytes }} bytes in ${sets.size} known recovery sets. " +
                        "${sets.count { it.backedUp }} sets can be removed after a verified backup. " +
                        "Copies absent from that backup, unknown originals and database recovery files are retained.") },
                    confirmButton = { if (sets.any { it.backedUp }) TextButton(operations::cleanupRecoveryCopies) { Text("Remove backed-up copies") } },
                    dismissButton = { TextButton(operations::dismissConfirmation) { Text("Keep copies") } }) }
                operation.capturedPhotos?.takeIf { startupStatus == LedgerRecoveryState.HEALTHY_EXISTING_DATABASE }
                    ?.let { photos -> AlertDialog(onDismissRequest = operations::dismissConfirmation,
                    title = { Text("Kept camera photos") },
                    text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        Text("These photos were kept after a capture or interrupted import. Check saved documents before importing again into the selected project.")
                        if (photos.isEmpty()) Text("No kept camera photos.")
                        photos.forEachIndexed { index, photo ->
                            Text("Photo ${index + 1} · ${photo.length()} bytes")
                            TextButton({ lifecycleScope.launch { launchSafe { viewCapturedPhoto(photo) } } }) { Text("View photo ${index + 1}") }
                            TextButton({ operations.importPhoto(photo, currentProject) }) { Text("Import photo ${index + 1} into selected project") }
                            TextButton({
                                operations.dismissConfirmation()
                                confirm("Discard kept photo?", "Permanently delete this captured photo without importing it?") {
                                    operations.discardCapturedPhoto(photo)
                                }
                            }) { Text("Discard photo ${index + 1}") }
                        }
                    } },
                    confirmButton = { TextButton(operations::dismissConfirmation) { Text("Keep for later") } }) }
                if (operation.busy) AlertDialog(onDismissRequest = {}, confirmButton = {},
                        title = { Text(operation.phase) },
                        text = { Column { LinearProgressIndicator(); Text("Please wait until this operation finishes.") } })
            }
        }
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        confirmTitle = title; confirmMessage = message; confirmAction = action
    }
    private fun retryLedger() {
        val app = application as ConstructionApplication
        if (app.recoveryState.isRecoveryRequired() && !app.recoveryState.clearForSafeOpenRetry()) {
            message("Select a verified backup to continue recovery")
            return
        }
        initializeLedger(null)
    }

    private fun initializeLedger(savedInstanceState: Bundle?) {
        if (initializing) return
        initializing = true
        startupStatus = null
        startupError = null
        val app = application as ConstructionApplication
        val recovery = app.recoveryState
        lifecycleScope.launch {
            app.ledgerLifecycleMutex.withLock {
                recovery.beginStartup()
                try {
                    val initial = withContext(Dispatchers.IO) { recovery.inspectBeforeOpen() }
                    if (recovery.isRecoveryRequired()) {
                        startupStatus = initial
                        startupError = "Existing app data was found without a safely openable ledger."
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        repository.validateStorage()
                        recovery.markLedgerValidated()
                    }
                    withContext(Dispatchers.IO) {
                        DocumentPolicy.cleanupCameraFiles(File(cacheDir, "exports"), pendingPhotoName, System.currentTimeMillis())
                    }
                    val seeder = CatalogSeeder(applicationContext, repository)
                    repository.projects.first().forEach { seeder.seed(it.id) }
                    val pendingDeletions = documents.retryProjectDeletions()
                    if (pendingDeletions.isNotEmpty()) message("A project deletion is still pending cleanup. Its originals and recovery marker were kept.")
                    documents.reconcile()
                    savedInstanceState?.getString("viewedPdf")?.let { viewedPdf = repository.attachment(it) }
                    startupStatus = LedgerRecoveryState.HEALTHY_EXISTING_DATABASE
                    openReminderProject()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!recovery.isRecoveryRequired()) {
                        recovery.markRecoveryRequired(LedgerRecoveryState.DATABASE_OPEN_FAILURE,
                            failure.message ?: "The local database could not be safely opened.")
                    }
                    startupError = failure.message ?: "The local ledger could not be safely opened."
                    startupStatus = recovery.state()
                } finally { initializing = false }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            try {
                snapshotFlow { startupStatus }.first { it != null }
                if (startupStatus != LedgerRecoveryState.HEALTHY_EXISTING_DATABASE) {
                    ReminderScheduler.configure(applicationContext, false)
                    return@launch
                }
                if (ViewModelProvider(this@MainActivity)[LedgerViewModel::class.java].reconcileReminders()) {
                    message("Notification permission is off. Reminders cannot run; enable them again in Settings.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.w("BuildLedger", "Local reminder scheduling failed", failure)
                message("Local reminders could not be scheduled. Try again in Settings.")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (startupStatus == LedgerRecoveryState.HEALTHY_EXISTING_DATABASE) openReminderProject()
    }

    private fun openReminderProject() {
        val projectId = intent.getStringExtra(ReminderScheduler.PROJECT_EXTRA) ?: return
        intent.removeExtra(ReminderScheduler.PROJECT_EXTRA)
        lifecycleScope.launch {
            val model = ViewModelProvider(this@MainActivity)[LedgerViewModel::class.java]
            model.state.first { it.ready }
            if (repository.projects.first().any { it.id == projectId && !it.archived }) model.selectProject(projectId)
            else message("The reminder's project is no longer available.")
        }
    }

    private fun requestReminders(enabled: Boolean) {
        if (enabled && !ReminderScheduler.permissionGranted(this)) {
            if (reminderChangePending) return
            reminderChangePending = true
            try { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            catch (failure: Exception) {
                reminderChangePending = false
                message("Notification permission could not be requested. Try again in Settings.")
            }
        } else applyReminderState(enabled)
    }

    internal fun handleReminderPermissionResult(granted: Boolean) {
        reminderChangePending = false
        if (!granted || !ReminderScheduler.permissionGranted(this)) message("Enable notification permission to receive reminders")
        applyReminderState(granted)
    }

    private fun applyReminderState(enabled: Boolean) {
        ViewModelProvider(this)[LedgerViewModel::class.java].applyReminderPermissionResult(enabled)
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
        return CameraCaptures(this).resolveExisting(name)
    }
    private fun capturePhoto(project: String) {
        if (pendingPhotoName != null) { message("Finish the current photo capture first"); return }
        val name = "photo-${UUID.randomUUID()}.jpg"
        var temporary: File? = null
        try {
            require(project.isNotBlank()) { "Choose a project before taking a photo" }
            val file = CameraCaptures(this).create(name)
            temporary = file
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            pendingPhotoName = name
            pendingPhotoProject = project
            takePhoto.launch(uri)
        } catch (failure: Exception) {
            pendingPhotoName = null
            pendingPhotoProject = null
            temporary?.takeIf { it.length() == 0L }?.delete()
            message(if (failure is ActivityNotFoundException) "No camera app is available; no document was added"
                else failure.message ?: "Could not start camera; no document was added")
        }
    }
    private suspend fun launchSafe(action: suspend () -> Unit) {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { message(failure.message ?: "Operation failed") }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingDocumentDetails?.let { outState.putStringArrayList("documentDetails", arrayListOf(it.category, it.status, it.revisionLabel, it.work, it.place, it.notes, it.expenseId.orEmpty())) }
        outState.putString("documentProject", currentProject); outState.putString("exportProject", exportProject)
        outState.putString("revisionParent", revisionParentId); outState.putString("exportDocument", documentToExportId)
        outState.putString("viewedPdf", viewedPdf?.id)
        outState.putBoolean("backupEncrypted", pendingBackupEncrypted)
        outState.putString("photoName", pendingPhotoName)
        outState.putString("photoProject", pendingPhotoProject)
        super.onSaveInstanceState(outState)
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun viewCapturedPhoto(photo: File) {
        val file = CameraCaptures(this).checked(photo)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/jpeg")
            clipData = ClipData.newRawUri("Kept camera photo", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { message("No installed app can view this photo") }
    }
    private suspend fun viewExternal(info: AttachmentInfo) {
        val file = withContext(Dispatchers.IO) { documents.verifiedFile(info) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, info.mimeType)
            clipData = ClipData.newRawUri(info.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { message("No installed app can view this file type") }
    }
}
