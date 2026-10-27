package com.constructioncost.nativeapp.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreRefundRegressionTest {
    @Test fun replacementsRetireDeletionCommandsAndPreserveQuarantineAfterRestart() = runBlocking<Unit> {
        for (streamed in listOf(false, true)) for (phase in listOf("PREPARED", "QUARANTINED", "ROWS_DELETED")) {
            val context = sandbox()
            var repository = LedgerRepository(context)
            try {
                LedgerRecoveryStateStore(context).markLedgerValidated()
                val bytes = "Original before interrupted deletion".toByteArray()
                val store = AttachmentStore(context, repository)
                repository.saveProject(ProjectInfo("project", "Restored project"))
                repository.saveExpense(ExpenseInput(id = "bill", projectId = "project", description = "Restored invoice", amountPaise = 12345, date = "2026-10-24"))
                val original = File(store.root, "old.bin").apply { writeBytes(bytes) }
                repository.insertAttachment(AttachmentInfo("file", "project", "original.txt", "text/plain", bytes.size.toLong(), DocumentPolicy.sha256(original), "old.bin", 1))
                val snapshot = repository.exportRecords().also {
                    it.getJSONArray("attachments").getJSONObject(0).put("storageName", "restored.bin")
                }
                repository.beginProjectDeletion("project")
                val quarantined = quarantine(context, "project", "old.bin")
                check(original.renameTo(quarantined))
                if (phase != "PREPARED") repository.markProjectDeletionQuarantined("project")
                if (phase == "ROWS_DELETED") repository.deleteProjectRows("project")
                File(store.root, "restored.bin").writeBytes(bytes)

                replace(repository, context, snapshot, streamed)
                assertTrue(repository.projectDeletionJournals().isEmpty())
                assertEquals(listOf("project"), repository.projects.first().map { it.id })
                assertEquals(12345L, repository.summary("project").first().recordedPaise)
                assertThrows(IllegalStateException::class.java) { runBlocking { repository.markProjectDeletionQuarantined("project") } }
                assertThrows(IllegalStateException::class.java) { runBlocking { repository.deleteProjectRows("project") } }
                assertThrows(IllegalStateException::class.java) { runBlocking { repository.completeProjectDeletion("project") } }
                repository.close()
                repository = LedgerRepository(context)
                val reopenedStore = AttachmentStore(context, repository)
                repeat(2) {
                    assertEquals(emptyList<String>(), reopenedStore.retryProjectDeletions())
                    reopenedStore.reconcile()
                }
                assertEquals(12345L, repository.summary("project").first().recordedPaise)
                assertTrue(File(reopenedStore.root, "restored.bin").readBytes().contentEquals(bytes))
                assertTrue(quarantined.readBytes().contentEquals(bytes))
                replace(repository, context, snapshot, streamed)
                repository.validateStorage()
                assertTrue(repository.projectDeletionJournals().isEmpty())
                assertTrue(quarantined.readBytes().contentEquals(bytes))
                reopenedStore.deleteProject("project")
                assertTrue(repository.projects.first().isEmpty())
                assertTrue(repository.projectDeletionJournals().isEmpty())
                assertTrue(!File(reopenedStore.root, "restored.bin").exists())
                val retained = File(context.filesDir, "recovery-preserved").walkTopDown().filter { it.isFile }.toList()
                assertEquals(1, retained.size)
                assertEquals("old.bin", retained.single().name)
                assertTrue(retained.single().readBytes().contentEquals(bytes))
            } finally { repository.close(); removeSandbox(context) }
        }
    }

    @Test fun replacementFailureRollsBackRetiredCommandsAndRowsAcrossRestart() = runBlocking<Unit> {
        for (streamed in listOf(false, true)) for (phase in listOf("PREPARED", "QUARANTINED", "ROWS_DELETED")) {
            for (checkpoint in listOf("rows-cleared", "rows-imported")) {
                val context = sandbox()
                var repository = LedgerRepository(context)
                try {
                    repository.saveProject(ProjectInfo("project", "Before failed restore"))
                    repository.saveExpense(ExpenseInput(id = "bill", projectId = "project", description = "Original invoice", amountPaise = 6789, date = "2026-10-24"))
                    val candidate = repository.exportRecords()
                    repository.beginProjectDeletion("project")
                    if (phase != "PREPARED") repository.markProjectDeletionQuarantined("project")
                    if (phase == "ROWS_DELETED") repository.deleteProjectRows("project")
                    val before = repository.exportRecords().toString()
                    val journal = repository.projectDeletionJournals()
                    repository.replacementCheckpoint = { if (it == checkpoint) error("Injected restore interruption") }
                    val failure = runCatching { replace(repository, context, candidate, streamed) }.exceptionOrNull()
                    assertEquals("Injected restore interruption", failure?.message)
                    repository.replacementCheckpoint = {}
                    assertEquals(before, repository.exportRecords().toString())
                    assertEquals(journal, repository.projectDeletionJournals())
                    repository.close()
                    repository = LedgerRepository(context)
                    assertEquals(before, repository.exportRecords().toString())
                    assertEquals(journal, repository.projectDeletionJournals())
                    replace(repository, context, candidate, streamed)
                    assertTrue(repository.projectDeletionJournals().isEmpty())
                    assertEquals(6789L, repository.summary("project").first().recordedPaise)
                } finally { repository.close(); removeSandbox(context) }
            }
        }
    }

    @Test fun partialRefundNeverExceedsOnePaiseAllocationsAndSurvivesRestore() = runBlocking<Unit> {
        val context = sandbox()
        val restoredContext = sandbox()
        val repository = LedgerRepository(context)
        val restored = LedgerRepository(restoredContext)
        try {
            createAllocatedPayment(repository, listOf(1, 1, 1))
            val refund = repository.refundPayment("cash", 2, "Partial return", "2026-10-24")
            assertEquals(listOf(1L, 1L, 0L), refundShares(repository, refund, 3))
            assertEquals(1L, repository.summary("project").first().paidPaise)
            assertEquals(2L, repository.summary("project").first().outstandingPaise)
            repository.validateStorage()
            restored.replaceRecords(repository.exportRecords())
            assertEquals(repository.summary("project").first(), restored.summary("project").first())
            val finalRefund = restored.refundPayment("cash", 1, "Remaining return", "2026-10-24")
            assertEquals(listOf(0L, 0L, 1L), refundShares(restored, finalRefund, 3))
            assertEquals(0L, restored.summary("project").first().paidPaise)
            assertEquals(3L, restored.summary("project").first().outstandingPaise)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { restored.refundPayment("cash", 1, "Too much", "2026-10-24") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { restored.refundPayment("cash", 0, "Zero", "2026-10-24") } }
            restored.validateStorage()
        } finally { repository.close(); restored.close(); removeSandbox(context); removeSandbox(restoredContext) }
    }

    @Test fun unevenRefundResidualsAndReversalKeepLiteralBalances() = runBlocking<Unit> {
        val context = sandbox()
        val repository = LedgerRepository(context)
        try {
            createAllocatedPayment(repository, listOf(1, 2, 7))
            val refund = repository.refundPayment("cash", 6, "Partial return", "2026-10-24")
            assertEquals(listOf(1L, 1L, 4L), refundShares(repository, refund, 3))
            assertEquals(4L, repository.summary("project").first().paidPaise)
            assertEquals(6L, repository.summary("project").first().outstandingPaise)
            repository.reversePayment("cash", "Remaining return", "2026-10-24")
            assertEquals(0L, repository.summary("project").first().paidPaise)
            assertEquals(10L, repository.summary("project").first().outstandingPaise)
            repository.validateStorage()
        } finally { repository.close(); removeSandbox(context) }
    }

    private suspend fun createAllocatedPayment(repository: LedgerRepository, amounts: List<Long>) {
        repository.saveProject(ProjectInfo("project", "Refund regression"))
        amounts.forEachIndexed { index, amount ->
            repository.saveExpense(ExpenseInput(id = "bill-$index", projectId = "project", description = "Invoice $index", amountPaise = amount, date = "2026-10-24"))
        }
        repository.savePayment(PaymentInput(id = "cash", projectId = "project", amountPaise = amounts.sum(), date = "2026-10-24",
            allocations = amounts.mapIndexed { index, amount -> PaymentAllocationInput("bill-$index", amount) }))
    }

    private suspend fun refundShares(repository: LedgerRepository, refundId: String, count: Int): List<Long> {
        val rows = repository.exportRecords().getJSONArray("payment_allocations")
        val byExpense = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("paymentId") == refundId }
            .associate { it.getString("expenseId") to it.getLong("amountPaise") }
        return List(count) { byExpense["bill-$it"] ?: 0L }
    }

    private suspend fun replace(repository: LedgerRepository, context: Context, records: JSONObject, streamed: Boolean) {
        if (streamed) {
            val file = File(context.cacheDir, "candidate.json").apply { writeText(records.toString()) }
            repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
        } else repository.replaceRecords(records)
    }

    private fun sandbox(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "restore-refund-${UUID.randomUUID()}").apply { check(mkdirs()) }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(root, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(root, "cache").apply { check(mkdirs() || isDirectory) }
            override fun getDatabasePath(name: String) = File(root, "database/$name").apply { parentFile?.mkdirs() }
        }
    }

    private fun removeSandbox(context: Context) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val root = context.cacheDir.parentFile!!.canonicalFile
        check(root.parentFile == base && root.name.startsWith("restore-refund-"))
        root.deleteRecursively()
    }

    private fun quarantine(context: Context, projectId: String, name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        return File(File(context.filesDir, "project-delete-$digest").apply { check(mkdirs() || isDirectory) }, name)
    }
}
