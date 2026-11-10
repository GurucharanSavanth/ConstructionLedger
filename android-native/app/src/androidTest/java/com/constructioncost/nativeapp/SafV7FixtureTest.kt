package com.constructioncost.nativeapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.DocumentImportDetails
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.platform.CatalogSeeder
import com.constructioncost.nativeapp.ui.ledgerPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly invoked fixture preparation; the SAF picker itself is exercised on the device. */
@RunWith(AndroidJUnit4::class)
@SeparateVerification
class SafV7FixtureTest {
    @Test fun prepareRepresentativeDisposableLedger() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "com.constructioncost.nativeapp.verification")
        assumeTrue(InstrumentationRegistry.getArguments().getString("v7Saf") == "prepare")
        val app = context.applicationContext as ConstructionApplication
        check(!app.recoveryState.isRecoveryRequired())
        val repository = app.repository
        check(repository.projects.first().all { it.id == "default-project" }) { "SAF drill requires a fresh disposable installation" }
        repository.validateStorage()
        app.recoveryState.markLedgerValidated()
        repository.ensureDefaultProject()
        repository.saveProject(ProjectInfo("v7-saf-project", "Disposable SAF house"))
        val seeder = CatalogSeeder(context, repository)
        repository.projects.first().forEach { seeder.seed(it.id) }
        context.ledgerPreferences.edit { it[stringPreferencesKey("project")] = "v7-saf-project" }
        repository.saveExpense(ExpenseInput(id = "v7-saf-expense", projectId = "v7-saf-project", description = "Concrete invoice",
            amountPaise = 125_050, date = "2026-10-22", invoiceNumber = "V7-SAF-001"))
        repository.savePayment(PaymentInput(id = "v7-saf-payment", projectId = "v7-saf-project", amountPaise = 50_025,
            date = "2026-10-22", allocations = listOf(PaymentAllocationInput("v7-saf-expense", 50_025))))
        val documents = AttachmentStore(context, repository)
        val hashes = JSONObject()
        val sources = File(context.cacheDir, "exports").apply { check(mkdirs() || isDirectory) }
        for (index in 1..2) {
            val source = File(sources, "v7-saf-receipt-$index.pdf")
            try {
                val pdf = PdfDocument()
                try {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 200, 1).create())
                    page.canvas.drawText("Disposable receipt $index", 20f, 50f, Paint().apply { color = Color.BLACK; textSize = 16f })
                    pdf.finishPage(page)
                    source.outputStream().use(pdf::writeTo)
                } finally { pdf.close() }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
                val saved = documents.importDocuments(listOf(uri), "v7-saf-project",
                    details = DocumentImportDetails("Bills and proof", "", "", "", "", "Disposable receipt", "v7-saf-expense")).single()
                hashes.put(saved.id, saved.sha256)
            } finally { source.delete() }
        }
        val source = File(sources, "v7-saf-photo.png")
        try {
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.BLUE)
                source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            val saved = documents.importDocument(FileProvider.getUriForFile(context, "${context.packageName}.files", source), "v7-saf-project")
            hashes.put(saved.id, saved.sha256)
        } finally { source.delete() }
        val orphan = File(documents.root, "v7-unreferenced-original.bin").apply { writeText("Preserve unreferenced disposable evidence") }
        documents.reconcile()
        check(orphan.isFile)
        val expected = JSONObject().put("syntheticOnly", true).put("records", repository.exportRecords())
            .put("attachmentHashes", hashes).put("unreferencedHash", DocumentPolicy.sha256(orphan))
        File(context.filesDir, "v7-saf-expected.json").writeText(expected.toString())
        println("V7_SAF_FIXTURE projects=2 expenses=1 payments=1 allocations=1 originalFiles=3 unreferencedFiles=1")
    }
}
