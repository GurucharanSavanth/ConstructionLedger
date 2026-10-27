package com.constructioncost.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.LedgerSummary
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
@SeparateVerification
class ProcessDeathIntegrityTest {
    private val campaign = "process-death-20261026"
    private val original = "BuildLedger process-death original\n".toByteArray(Charsets.UTF_8)
    private val originalHash = "ded5d0931fe4e39d006dcaf68dbd1bcabbd73dfc41d9833ebc61b24990a83952"

    @Test fun prepare() = runBlocking<Unit> {
        val (base, root) = guard("prepare")
        check(!root.exists()) { "An existing process-death campaign must be verified before replacement" }
        check(root.mkdir())
        val context = sandbox(base, File(root, "primary"))
        val repository = LedgerRepository(context)
        try {
            seed(context, repository, 12_345, original)
            assertOriginal(context, repository, root, compareSnapshot = false)
            durable(File(root, "baseline-records.json"), repository.exportRecords().toString().toByteArray(Charsets.UTF_8))
            val knownGood = File(root, "known-good.zip")
            BackupService(context, repository, AttachmentStore(context, repository)).create(Uri.fromFile(knownGood))
            durable(File(root, "known-good.sha256"), DocumentPolicy.sha256(knownGood).toByteArray(Charsets.US_ASCII))
        } finally { repository.close() }
        val candidateContext = sandbox(base, File(root, "candidate"))
        val candidate = LedgerRepository(candidateContext)
        try {
            seed(candidateContext, candidate, 22_222, "Candidate replacement original\n".toByteArray(Charsets.UTF_8))
            val archive = File(root, "candidate.zip")
            BackupService(candidateContext, candidate, AttachmentStore(candidateContext, candidate)).create(Uri.fromFile(archive))
            durable(File(root, "candidate.sha256"), DocumentPolicy.sha256(archive).toByteArray(Charsets.US_ASCII))
        } finally { candidate.close() }
        durable(File(root, "prepared.marker"), campaign.toByteArray(Charsets.US_ASCII))
    }

    @Test fun interruptTransaction() = runBlocking<Unit> {
        val (context, root) = prepared("interruptTransaction")
        val repository = LedgerRepository(context)
        try {
            assertOriginal(context, repository, root)
            repository.replacementCheckpoint = { if (it == "rows-cleared") blockForHost(root, "transaction") }
            val replacement = JSONObject(File(root, "baseline-records.json").readText()).apply {
                getJSONArray("projects").getJSONObject(0).put("name", "Uncommitted candidate project")
            }
            repository.replaceRecords(replacement)
            error("The host must force-stop at the transaction marker")
        } finally { repository.close() }
    }

    @Test fun interruptBackup() = runBlocking<Unit> {
        val (context, root) = prepared("interruptBackup")
        val repository = LedgerRepository(context)
        try {
            assertOriginal(context, repository, root)
            BackupService(context, repository, AttachmentStore(context, repository)).create(Uri.fromFile(File(root, "interrupted-backup.zip"))) {
                if (it == "Writing backup archive: 1 of 1") blockForHost(root, "backup")
            }
            error("The host must force-stop at the archive-writing marker")
        } finally { repository.close() }
    }

    @Test fun interruptRestore() = runBlocking<Unit> {
        val (context, root) = prepared("interruptRestore")
        val repository = LedgerRepository(context)
        try {
            assertOriginal(context, repository, root)
            val backups = BackupService(context, repository, AttachmentStore(context, repository))
            backups.restoreCheckpoint = { if (it == "before-database-replace") blockForHost(root, "restore") }
            backups.restore(Uri.fromFile(File(root, "candidate.zip")), expectedArchiveSha256 = File(root, "candidate.sha256").readText())
            error("The host must force-stop at the restore marker")
        } finally { repository.close() }
    }

    @Test fun verifyAfterRestart() = runBlocking<Unit> {
        val (context, root) = prepared("verifyAfterRestart", requireNoMarker = false)
        verify(context, root)
    }

    @Test fun verifyAfterRestartAndCleanup() = runBlocking<Unit> {
        val (context, root) = prepared("verifyAfterRestartAndCleanup", requireNoMarker = false)
        verify(context, root)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(root.canonicalFile.parentFile == base.filesDir.canonicalFile && root.name == campaign)
        check(root.deleteRecursively() && !root.exists()) { "The isolated campaign could not be removed" }
    }

    private suspend fun verify(context: Context, root: File) {
        val phase = File(root, "blocked.marker").readText()
        check(phase in setOf("transaction", "backup", "restore")) { "No interrupted operation marker exists" }
        val repository = LedgerRepository(context)
        try {
            LedgerRecoveryStateStore(context).beginStartup()
            assertOriginal(context, repository, root)
            assertEquals(File(root, "known-good.sha256").readText(), DocumentPolicy.sha256(File(root, "known-good.zip")))
            assertEquals(File(root, "candidate.sha256").readText(), DocumentPolicy.sha256(File(root, "candidate.zip")))
            if (phase == "backup") assertFalse("Destination must not become an apparently complete archive", File(root, "interrupted-backup.zip").exists())
            AttachmentStore(context, repository).reconcile()
            assertOriginal(context, repository, root)
            durable(File(root, "verified-$phase.marker"), phase.toByteArray(Charsets.US_ASCII))
            check(File(root, "blocked.marker").delete())
            println("PROCESS_DEATH_INTEGRITY phase=$phase projects=2 originalBytes=${original.size} originalSha256=$originalHash journal=wal synchronous=FULL records=unchanged")
        } finally { repository.close() }
    }

    private suspend fun seed(context: Context, repository: LedgerRepository, northAmount: Long, bytes: ByteArray) {
        repository.saveProject(ProjectInfo("north", "North house", budgetPaise = 50_000))
        repository.saveProject(ProjectInfo("south", "South house", budgetPaise = 150_000))
        repository.saveExpense(ExpenseInput(id = "north-bill", projectId = "north", description = "North invoice", amountPaise = northAmount, date = "2026-10-26"))
        repository.saveExpense(ExpenseInput(id = "south-bill", projectId = "south", description = "South invoice", amountPaise = 98_765, date = "2026-10-26"))
        repository.savePayment(PaymentInput(id = "north-cash", projectId = "north", amountPaise = 4_321, date = "2026-10-26",
            allocations = listOf(PaymentAllocationInput("north-bill", 4_000))))
        repository.savePayment(PaymentInput(id = "south-cash", projectId = "south", amountPaise = 9_876, date = "2026-10-26",
            allocations = listOf(PaymentAllocationInput("south-bill", 6_000))))
        val file = File(AttachmentStore(context, repository).root, "original.bin")
        durable(file, bytes)
        repository.insertAttachments(listOf(AttachmentInfo("original-file", "north", "original.txt", "text/plain",
            bytes.size.toLong(), DocumentPolicy.sha256(file), "original.bin", 1)), "north-bill")
        repository.validateStorage()
        LedgerRecoveryStateStore(context).markLedgerValidated()
    }

    private suspend fun assertOriginal(context: Context, repository: LedgerRepository, root: File, compareSnapshot: Boolean = true) {
        repository.validateStorage()
        assertEquals(setOf("north", "south"), repository.projects.first().map { it.id }.toSet())
        assertEquals(LedgerSummary(recordedPaise = 12_345, paidPaise = 4_321, outstandingPaise = 8_345, advancePaise = 321, budgetPaise = 50_000), repository.summary("north").first())
        assertEquals(LedgerSummary(recordedPaise = 98_765, paidPaise = 9_876, outstandingPaise = 92_765, advancePaise = 3_876, budgetPaise = 150_000), repository.summary("south").first())
        assertEquals(listOf("north-bill"), repository.expenses("north").first().map { it.id })
        assertEquals(listOf("south-bill"), repository.expenses("south").first().map { it.id })
        assertEquals(listOf("north-cash"), repository.payments("north").first().map { it.id })
        assertEquals(listOf("south-cash"), repository.payments("south").first().map { it.id })
        assertTrue(repository.attachments("south").first().isEmpty())
        val attachment = repository.attachments("north").first().single()
        assertEquals("original-file", attachment.id)
        assertEquals("original.bin", attachment.storageName)
        assertEquals(originalHash, attachment.sha256)
        assertEquals(original.size.toLong(), attachment.size)
        assertEquals(listOf("EXPENSE" to "north-bill"), repository.attachmentLinks("original-file"))
        val file = AttachmentStore(context, repository).fileFor(attachment)
        assertArrayEquals(original, file.readBytes())
        assertEquals(originalHash, DocumentPolicy.sha256(file))
        if (compareSnapshot) assertEquals(File(root, "baseline-records.json").readText(), repository.exportRecords().toString())
        LedgerRecoveryStateStore(context).markLedgerValidated()
    }

    private fun guard(method: String): Pair<Context, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "com.constructioncost.nativeapp.verification") { "Process-death tests require the disposable verification application" }
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Process-death tests require an emulator" }
        check(InstrumentationRegistry.getArguments().getString("class") == "${javaClass.name}#$method") { "Select exactly one process-death method for host coordination" }
        val root = File(base.filesDir, campaign)
        check(root.canonicalFile.parentFile == base.filesDir.canonicalFile && root.canonicalFile.name == campaign)
        return base to root
    }

    private fun prepared(method: String, requireNoMarker: Boolean = true): Pair<Context, File> {
        val (base, root) = guard(method)
        check(File(root, "prepared.marker").readText() == campaign) { "Prepare the isolated fixture first" }
        if (requireNoMarker) check(!File(root, "blocked.marker").exists()) { "Verify the previous interruption before another fault" }
        return sandbox(base, File(root, "primary")) to root
    }

    private fun sandbox(base: Context, root: File): Context {
        check(root.canonicalFile.parentFile == File(base.filesDir, campaign).canonicalFile)
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(root, "cache").apply { check(mkdirs() || isDirectory) }
            override fun getDatabasePath(name: String) = File(root, "databases/sandbox-$name").apply { check(parentFile!!.mkdirs() || parentFile!!.isDirectory) }
        }
    }

    private fun durable(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }

    private fun blockForHost(root: File, phase: String): Nothing {
        check(!File(root, "blocked.marker").exists())
        durable(File(root, "blocked.marker"), phase.toByteArray(Charsets.US_ASCII))
        CountDownLatch(1).await()
        error("The host must force-stop the disposable verification application")
    }
}
