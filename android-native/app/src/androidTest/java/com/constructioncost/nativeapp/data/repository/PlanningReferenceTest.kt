package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanningReferenceTest {
    @Test fun supplierChangesCannotInvalidateLinkedExpensesCancelledPlansOrPaymentHistory() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("p", "Build"))
            for (id in listOf("a", "b")) repository.saveVendor(VendorInfo(id, "p", id))
            repository.saveCommitment(CommitmentInput("c", "p", "Agreement", 10_000, "2026-01-01", vendorId = "a"))
            repository.saveExpense(ExpenseInput("e", projectId = "p", description = "Invoice", amountPaise = 1_000,
                date = "2026-01-01", vendorId = "a", commitmentId = "c"))
            val before = repository.exportRecords().toString()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveCommitment(CommitmentInput(
                "c", "p", "Agreement", 10_000, "2026-01-01", vendorId = "b", revision = 0)) } }
            assertEquals(before, repository.exportRecords().toString())
            repository.saveCommitment(CommitmentInput("cancelled", "p", "Cancelled agreement", 5_000, "2026-01-01", vendorId = "a",
                schedules = listOf(InstalmentInput("s", "p", "First", 5_000, "2026-01-01", vendorId = "a"))))
            repository.cancelInstalment("s", 0)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveCommitment(CommitmentInput(
                "cancelled", "p", "Cancelled agreement", 5_000, "2026-01-01", vendorId = "b", revision = 0)) } }
            repository.saveInstalment(InstalmentInput("paid", "p", "Paid plan", 5_000, "2026-01-01", vendorId = "a"))
            repository.savePayment(PaymentInput("cash", projectId = "p", amountPaise = 1_000, date = "2026-01-01", vendorId = "a", instalmentId = "paid"))
            repository.reversePayment("cash", "Correction", "2026-01-01")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveInstalment(InstalmentInput(
                "paid", "p", "Paid plan", 5_000, "2026-01-01", vendorId = "b", revision = 0)) } }
            repository.saveInstalment(InstalmentInput("expense-plan", "p", "Planned bill", 5_000, "2026-01-01", vendorId = "a"))
            repository.saveExpense(ExpenseInput("schedule-bill", projectId = "p", description = "Invoice", amountPaise = 1_000,
                date = "2026-01-01", vendorId = "a", instalmentId = "expense-plan"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveInstalment(InstalmentInput(
                "expense-plan", "p", "Planned bill", 5_000, "2026-01-01", vendorId = "b", revision = 0)) } }
            repository.validateRecords(repository.exportRecords())
            assertEquals("a", repository.commitments("p").first().first { it.id == "c" }.vendorId)
        } finally { repository.close() }
    }
}
