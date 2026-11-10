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
class PaymentEditTest {
    private fun repository() = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun fullAllocationFollowsAmountAndDateWithAtomicHistoryAndRetry() = runBlocking<Unit> {
        val repository = repository()
        try {
            repository.saveProject(ProjectInfo("p", "Build"))
            repository.saveExpense(ExpenseInput("bill", projectId = "p", description = "Cement", amountPaise = 10_000, date = "2026-01-01"))
            repository.saveInstalment(InstalmentInput("plan", "p", "Cement", 10_000, "2026-01-01"))
            repository.insertAttachment(AttachmentInfo("receipt", "p", "receipt.pdf", "application/pdf", 1,
                "a".repeat(64), "receipt.pdf", 1))
            repository.savePayment(PaymentInput("old", projectId = "p", amountPaise = 4_000, date = "2026-01-01",
                instalmentId = "plan", allocations = listOf(PaymentAllocationInput("bill", 4_000)), attachmentIds = listOf("receipt")))
            val edit = repository.paymentEditState("old")
            suspend fun save() = repository.replacePayment("old", edit.fingerprint, 6_000, "2026-02-01", null, "UPI 123", "Updated", "Amount correction", "edit-one")
            val replacement = save()
            assertEquals(replacement, save())
            assertEquals(3, repository.payments("p").first().size)
            assertEquals(4_000L, repository.payments("p").first().single { it.id == "old" }.amountPaise)
            assertEquals(6_000L, repository.summary("p").first().paidPaise)
            assertEquals(4_000L, repository.summary("p").first().outstandingPaise)
            assertEquals(6_000L, repository.instalments("p").first().single().paidPaise)
            assertEquals(listOf("receipt"), repository.paymentAttachmentIds(replacement))
            assertEquals(listOf(0L, 6_000L), repository.monthlyCash("p").first().map { it.paidPaise })
            assertTrue(repository.audits("old", "payments", "p").first().any { it.action == "EDIT_PAYMENT" })
            assertEquals(6_000L, repository.paymentAllocationStates(replacement).single().amountPaise)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replacePayment("old", edit.fingerprint,
                7_000, "2026-02-01", null, "UPI 123", "Updated", "Amount correction", "edit-one") } }
        } finally { repository.close() }
    }

    @Test fun overpaymentAndKeyCollisionRollBackReversalAndKeepOriginal() = runBlocking<Unit> {
        val repository = repository()
        try {
            repository.saveProject(ProjectInfo("p", "Build"))
            repository.saveExpense(ExpenseInput("bill", projectId = "p", description = "Cement", amountPaise = 5_000, date = "2026-01-01"))
            repository.savePayment(PaymentInput("old", projectId = "p", amountPaise = 4_000, date = "2026-01-01",
                allocations = listOf(PaymentAllocationInput("bill", 4_000))))
            val edit = repository.paymentEditState("old")
            val before = repository.exportRecords().toString()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replacePayment("old", edit.fingerprint,
                6_000, "2026-01-01", null, "", "", "Correction", "too-large") } }
            assertEquals(before, repository.exportRecords().toString())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replacePayment("old", edit.fingerprint,
                3_000, "2026-01-01", null, "", "", "Correction", "old") } }
            assertEquals(before, repository.exportRecords().toString())
        } finally { repository.close() }
    }

    @Test fun multipleAllocationsStayFixedAndStaleOrRefundedPaymentsCannotBeEdited() = runBlocking<Unit> {
        val repository = repository()
        try {
            repository.saveProject(ProjectInfo("p", "Build"))
            for (id in listOf("a", "b")) repository.saveExpense(ExpenseInput(id, projectId = "p", description = id,
                amountPaise = 10_000, date = "2026-01-01"))
            repository.savePayment(PaymentInput("old", projectId = "p", amountPaise = 6_000, date = "2026-01-01",
                allocations = listOf(PaymentAllocationInput("a", 2_000), PaymentAllocationInput("b", 1_000))))
            val stale = repository.paymentEditState("old")
            repository.allocateAdvance("old", "a", 1_000, "allocate")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replacePayment("old", stale.fingerprint,
                8_000, "2026-01-01", null, "", "", "Correction", "stale") } }
            val fresh = repository.paymentEditState("old")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replacePayment("old", fresh.fingerprint,
                3_000, "2026-01-01", null, "", "", "Correction", "under-allocated") } }
            val replacement = repository.replacePayment("old", fresh.fingerprint, 8_000, "2026-01-01", null, "", "", "Correction", "fresh")
            assertEquals(4_000L, repository.summary("p").first().advancePaise)
            assertEquals(mapOf("a" to 3_000L, "b" to 1_000L), repository.paymentAllocationStates(replacement).associate { it.expenseId to it.amountPaise })
            repository.refundPayment(replacement, 1_000, "Refund", "2026-01-02")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.paymentEditState(replacement) } }
        } finally { repository.close() }
    }

    @Test fun restoreInvalidatesUnallocatedPaymentEditAndCashMonthsExcludeExpenseOnlyMonths() = runBlocking<Unit> {
        val repository = repository()
        try {
            repository.saveProject(ProjectInfo("p", "Build"))
            repository.saveProject(ProjectInfo("other", "Other"))
            repository.savePayment(PaymentInput("foreign", projectId = "other", amountPaise = 50_000, date = "2027-01-01"))
            repository.saveExpense(ExpenseInput("bill", projectId = "p", description = "Future bill", amountPaise = 5_000, date = "2027-01-01"))
            repeat(14) { index -> repository.savePayment(PaymentInput("pay-$index", projectId = "p", amountPaise = 100,
                date = java.time.LocalDate.of(2025, 1, 1).plusMonths(index.toLong()).toString())) }
            val cash = repository.monthlyCash("p").first()
            assertEquals(12, cash.size)
            assertEquals("2025-03", cash.first().month)
            assertEquals("2026-02", cash.last().month)
            val edit = repository.paymentEditState("pay-0")
            repository.replaceRecords(repository.exportRecords())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replacePayment("pay-0", edit.fingerprint,
                200, "2025-01-01", null, "", "", "Correction", "after-restore") } }
            assertEquals(1_400L, repository.summary("p").first().paidPaise)
        } finally { repository.close() }
    }
}
