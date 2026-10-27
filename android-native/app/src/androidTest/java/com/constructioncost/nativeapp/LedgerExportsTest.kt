package com.constructioncost.nativeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.io.File
import java.util.UUID
import android.net.Uri

@RunWith(AndroidJUnit4::class)
class LedgerExportsTest {
    @Test fun exportKeepsCostCashSchedulesAndAuditSeparate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        try {
            repository.saveProject(ProjectInfo("house", "House"))
            repository.saveProject(ProjectInfo("other", "Other project"))
            repository.saveExpense(ExpenseInput(id = "cement", projectId = "house", description = "Cement", amountPaise = 1850000,
                date = "2026-01-02", lines = listOf(ExpenseLineInput("cement-line", "Cement", 1850000, nature = "Material"))))
            repository.saveExpense(ExpenseInput(id = "private", projectId = "other", description = "Other project expense", amountPaise = 9900, date = "2026-01-02"))
            repository.savePayment(PaymentInput(id = "payment", projectId = "house", amountPaise = 400000, date = "2026-01-03",
                allocations = listOf(PaymentAllocationInput("cement", 400000))))
            repository.saveCommitment(CommitmentInput(id = "agreement", projectId = "house", title = "Labour agreement", amountPaise = 2500000, date = "2026-02-01",
                schedules = listOf(InstalmentInput("future", "house", "Future work", 2500000, "2026-02-01", "agreement"))))
            val file = File(context.cacheDir, "csv-${UUID.randomUUID()}.csv")
            val progress = mutableListOf<String>()
            val rows = try {
                LedgerExports.csv(context.contentResolver, Uri.fromFile(file), repository, "house", progress::add)
                file.readLines().drop(1).map { line ->
                    Regex("\"((?:[^\"]|\"\")*)\"(?:,|$)").findAll(line).map { it.groupValues[1].replace("\"\"", "\"") }.toList()
                }
            } finally { file.delete() }
            assertEquals("Exporting CSV rows", progress.first())
            assertTrue(progress.contains("Exporting CSV rows: ${rows.size}"))
            assertEquals("Flushing CSV file", progress.last())
            fun values(column: String) = rows.map { it[LedgerExports.csvHeader.indexOf(column)] }.filter(String::isNotBlank).map(::BigDecimal)
            assertEquals(BigDecimal("18500.00"), values("Recorded Cost INR").fold(BigDecimal.ZERO, BigDecimal::add))
            assertEquals(BigDecimal("4000.00"), values("Cash Outflow INR").fold(BigDecimal.ZERO, BigDecimal::add))
            assertEquals(BigDecimal("14500.00"), values("Outstanding INR").single())
            assertEquals(BigDecimal("25000.00"), values("Planned Amount INR").single())
            assertEquals(BigDecimal("25000.00"), values("Commitment INR").single())
            assertTrue(rows.any { it.first() == "Audit" })
            assertTrue(rows.any { it.first() == "Payment allocation" })
            assertFalse(rows.any { it[1] == "private" })
        } finally { repository.close() }
    }

    @Test fun spreadsheetFormulaTextIsEscaped() {
        assertEquals("\"'=SUM(A1:A2)\"", LedgerExports.csvCell("=SUM(A1:A2)"))
        assertEquals("\"'  @command\"", LedgerExports.csvCell("  @command"))
        assertEquals("\"a\"\"b\"", LedgerExports.csvCell("a\"b"))
        assertEquals("\"-4000.00\"", LedgerExports.csvCell("-4000.00"))
    }
}
