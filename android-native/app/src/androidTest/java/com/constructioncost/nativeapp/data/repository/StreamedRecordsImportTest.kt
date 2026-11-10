package com.constructioncost.nativeapp.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.ExpenseLineInput
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.domain.VendorInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger

@RunWith(AndroidJUnit4::class)
class StreamedRecordsImportTest {
    @Test fun streamedBatchesPreserveTailRowsLargeTextMoneyAndImmutableJournals() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val file = File.createTempFile("streamed-batches-", ".json", context.cacheDir)
        try {
            val original = fixture()
            file.writeText(original.toString())
            val inspected = repository.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
            assertEquals(66, inspected.counts["expenses"])
            assertEquals(65, inspected.counts["expense_lines"])
            assertEquals(BigInteger("9223372036855578232"), inspected.financials!!.recordedPaise)
            repository.saveProject(ProjectInfo("project", "Prior batch house"))
            repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)

            val summary = repository.summary("project").first()
            assertEquals(802_425L, summary.recordedPaise)
            assertEquals(1_234L, summary.paidPaise)
            assertEquals(801_191L, summary.outstandingPaise)
            assertEquals(0L, summary.advancePaise)
            assertEquals(Long.MAX_VALUE, repository.summary("maximum").first().recordedPaise)
            assertEquals("क".repeat(50_000), repository.expense("invoice-32")!!.note)

            val restored = repository.exportRecords()
            assertEquals(original.getJSONArray("audit_records").toString(), restored.getJSONArray("audit_records").toString())
            assertEquals(1L, repository.expense("invoice-64")!!.revision)
            for (table in LedgerRepository.TABLES) {
                val expected = original.getJSONArray(table)
                val actual = restored.getJSONArray(table)
                for (index in 0 until expected.length()) expected.getJSONObject(index).remove("revision")
                for (index in 0 until actual.length()) actual.getJSONObject(index).remove("revision")
                assertEquals(table, expected.toString(), actual.toString())
            }
        } finally { repository.close(); file.delete() }
    }

    @Test fun lateConstraintOrTypeFailureRollsBackEveryPreviouslyFlushedBatch() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val candidate = fixture()
        for (duplicate in listOf(true, false)) {
            val repository = LedgerRepository.inMemory(context)
            val file = File.createTempFile("streamed-failure-", ".json", context.cacheDir)
            try {
                file.writeText(candidate.toString())
                repository.inspectRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
                repository.saveProject(ProjectInfo("existing", "Existing house", budgetPaise = 123_456))
                repository.saveExpense(ExpenseInput(id = "kept", projectId = "existing", description = "Original invoice",
                    amountPaise = 98_765, date = "2026-10-26"))
                val before = repository.exportRecords().toString()
                repository.replacementCheckpoint = { phase ->
                    if (phase == "rows-cleared") {
                        val damaged = JSONObject(candidate.toString())
                        val rows = damaged.getJSONArray("expenses")
                        if (duplicate) rows.put(JSONObject(rows.getJSONObject(0).toString()))
                        else rows.getJSONObject(rows.length() - 1).put("amountPaise", "invalid integer")
                        file.writeText(damaged.toString())
                    }
                }

                val failure = runCatching {
                    repository.replaceInspectedRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
                }.exceptionOrNull()

                assertTrue("Expected late import failure: $failure", if (duplicate) failure is SQLiteConstraintException else failure is IllegalArgumentException)
                assertEquals(before, repository.exportRecords().toString())
                assertEquals(98_765L, repository.summary("existing").first().recordedPaise)
                assertEquals(123_456L, repository.projects.first().single().budgetPaise)
            } finally { repository.close(); file.delete() }
        }
    }

    private suspend fun fixture(): JSONObject {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Batch house", budgetPaise = 1_234_567))
            repository.saveVendor(VendorInfo("supplier", "project", "Supplier"))
            repeat(65) { index ->
                repository.saveExpense(ExpenseInput(id = "invoice-$index", projectId = "project", description = "Invoice $index 😀",
                    amountPaise = 12_345, date = "2026-10-26", vendorId = if (index % 2 == 0) null else "supplier",
                    note = if (index == 32) "क".repeat(50_000) else "Quoted \"text\"\n$index",
                    lines = listOf(ExpenseLineInput(id = "line-$index", description = "Concrete", amountPaise = 12_345, nature = "Material"))))
            }
            repository.savePayment(PaymentInput(id = "payment", projectId = "project", amountPaise = 1_234,
                date = "2026-10-26", allocations = listOf(PaymentAllocationInput("invoice-0", 1_234))))
            repository.saveProject(ProjectInfo("maximum", "Maximum project", budgetPaise = Long.MAX_VALUE))
            repository.saveExpense(ExpenseInput(id = "maximum-invoice", projectId = "maximum", description = "Maximum invoice",
                amountPaise = Long.MAX_VALUE, date = "2026-10-26"))
            return repository.exportRecords()
        } finally { repository.close() }
    }
}
