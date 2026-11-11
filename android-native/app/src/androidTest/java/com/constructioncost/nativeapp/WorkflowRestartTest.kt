package com.constructioncost.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.ui.EntryDraft
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@SeparateVerification
class WorkflowRestartTest {
    private val campaign = "workflow-restart-20261111"
    private val bytes = byteArrayOf(0, 1, 2, 10, 13, 42, 127, -1)
    private val draftKey = stringPreferencesKey("entry_draft")
    private val draft = EntryDraft.fresh("north", "Work log").with("id", "retained-draft")
        .with("description", "Unposted retained work").with("note", "Keep across process restart")

    @Test fun prepare() = runBlocking<Unit> {
        val (context, root) = guard("prepare")
        check(!root.exists()) { "Verify the existing workflow campaign before replacing it" }
        check(root.mkdir())
        val repository = LedgerRepository(context)
        try {
            val date = LocalDate.now().toString()
            durable(File(root, "date"), date.toByteArray())
            repository.saveProject(ProjectInfo("north", "North", budgetPaise = 50_000))
            repository.saveProject(ProjectInfo("south", "South"))
            repository.saveVendor(VendorInfo("supplier", "north", "Supplier"))
            repository.saveClassification(ClassificationInfo("work", "north", "Foundation", "STAGE"))
            repository.saveClassification(ClassificationInfo("place", "north", "Ground floor", "PLACE"))
            repository.saveCommitment(CommitmentInput(id = "agreement", projectId = "north", title = "Agreement",
                amountPaise = 20_000, date = date, vendorId = "supplier", classificationIds = listOf("work")))
            repository.saveInstalment(InstalmentInput(id = "schedule", projectId = "north", title = "Milestone",
                amountPaise = 10_000, dueDate = date, vendorId = "supplier", commitmentId = "agreement",
                remind = true, classificationIds = listOf("work", "place")))
            val invoice = ExpenseInput(id = "invoice", projectId = "north", description = "Concrete invoice",
                amountPaise = 10_000, date = date, vendorId = "supplier", invoiceNumber = "SYNTHETIC-1",
                lines = listOf(ExpenseLineInput("line", "Concrete", 10_000)),
                classifications = listOf(ClassificationPart("work", "WORK", 10_000), ClassificationPart("place", "PLACE", 10_000)))
            assertEquals("invoice", repository.saveExpense(invoice))
            assertEquals("invoice", repository.saveExpense(invoice))
            val payment = PaymentInput(id = "cash", projectId = "north", amountPaise = 6_000, date = date,
                vendorId = "supplier", instalmentId = "schedule", allocations = listOf(PaymentAllocationInput("invoice", 6_000)))
            assertEquals("cash", repository.savePayment(payment))
            assertEquals("cash", repository.savePayment(payment))
            repository.refundPayment("cash", 1_000, "Returned material", date)
            repository.savePayment(PaymentInput(id = "advance", projectId = "north", amountPaise = 2_000, date = date, vendorId = "supplier"))
            repository.savePayment(PaymentInput(id = "reversed", projectId = "north", amountPaise = 3_000, date = date))
            repository.reversePayment("reversed", "Correction", date)
            val note = ActivityNote("note", "north", "Inspection", date, "Original")
            repository.saveNote(note)
            repository.saveNote(note.copy(note = "Reviewed"), expectedRevision = 0)
            val original = File(AttachmentStore(context, repository).root, "original.bin")
            durable(original, bytes)
            repository.insertAttachments(listOf(AttachmentInfo("document", "north", "original.bin", "application/octet-stream",
                8, DocumentPolicy.sha256(original), "original.bin", 1)), "invoice")
            repository.linkAttachment("document", noteId = "note")
            assertLedger(context, repository, date)
            durable(File(root, "snapshot.json"), repository.exportRecords().toString().toByteArray())
            durable(File(root, "original.sha256"), DocumentPolicy.sha256(original).toByteArray())
            preferenceDraft(root, write = true)
            durable(File(root, "pid"), Process.myPid().toString().toByteArray())
        } finally { repository.close() }
    }

    @Test fun verifyAfterRestart() = runBlocking<Unit> {
        val (context, root) = guard("verifyAfterRestart")
        assertNotEquals("Host must restart the verification process", File(root, "pid").readText().toInt(), Process.myPid())
        val repository = LedgerRepository(context)
        try {
            assertLedger(context, repository, File(root, "date").readText())
            assertEquals(File(root, "snapshot.json").readText(), repository.exportRecords().toString())
            assertEquals(File(root, "original.sha256").readText(), DocumentPolicy.sha256(File(AttachmentStore(context, repository).root, "original.bin")))
            preferenceDraft(root, write = false)
            durable(File(root, "verified"), Process.myPid().toString().toByteArray())
            println("WORKFLOW_RESTART previousPid=${File(root, "pid").readText()} currentPid=${Process.myPid()} recorded=10000 paid=7000 outstanding=5000 advance=2000 originalBytes=8 snapshot=unchanged")
        } finally { repository.close() }
    }

    private suspend fun assertLedger(context: Context, repository: LedgerRepository, date: String) {
        repository.validateStorage()
        assertEquals(LedgerSummary(recordedPaise = 10_000, paidPaise = 7_000, outstandingPaise = 5_000,
            advancePaise = 2_000, upcomingPaise = 5_000, budgetPaise = 50_000, commitmentPaise = 20_000), repository.summary("north", LocalDate.parse(date)).first())
        assertEquals(0L, repository.summary("south").first().recordedPaise)
        assertEquals(listOf("invoice"), repository.expenses("north").first().map { it.id })
        assertEquals(5, repository.payments("north").first().size)
        assertEquals(5_000L, repository.instalments("north").first().single().outstandingPaise)
        assertEquals(1L, repository.exportRecords().getJSONArray("activity_entries").getJSONObject(0).getLong("revision"))
        assertEquals("Reviewed", repository.exportRecords().getJSONArray("activity_entries").getJSONObject(0).getString("note"))
        assertEquals(2, repository.attachmentLinks("document").size)
        assertArrayEquals(bytes, File(AttachmentStore(context, repository).root, "original.bin").readBytes())
        assertTrue(repository.expenses("south").first().isEmpty())
    }

    private suspend fun preferenceDraft(root: File, write: Boolean) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { File(root, "draft.preferences_pb") }
        try {
            if (write) {
                store.edit { it[draftKey] = draft.json() }
                durable(File(root, "draft.json"), draft.json().toByteArray())
            }
            val saved = requireNotNull(store.data.first()[draftKey])
            assertEquals(File(root, "draft.json").readText(), saved)
            val actual = EntryDraft.read(saved)
            assertEquals("retained-draft", actual.id)
            assertEquals("Unposted retained work", actual.get("description"))
            assertEquals("Keep across process restart", actual.get("note"))
        } finally { job.cancelAndJoin() }
    }

    private fun guard(method: String): Pair<Context, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName.endsWith(".verification"))
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Use a disposable emulator" }
        check(InstrumentationRegistry.getArguments().getString("class") == "${javaClass.name}#$method")
        val root = File(base.filesDir, campaign)
        check(root.canonicalFile.parentFile == base.filesDir.canonicalFile)
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(root, "cache").apply { check(mkdirs() || isDirectory) }
            override fun getDatabasePath(name: String) = File(root, "databases/sandbox-$name").apply { check(parentFile!!.mkdirs() || parentFile!!.isDirectory) }
        } to root
    }

    private fun durable(file: File, value: ByteArray) {
        FileOutputStream(file).use { it.write(value); it.fd.sync() }
    }
}
