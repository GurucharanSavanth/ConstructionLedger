package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.ui.EntryDraft
import com.constructioncost.nativeapp.ui.LedgerViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupplierMaterialInsightsTest {
    @Test fun activeSupplierInsightsRefreshWhenVendorIsRenamed() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "House"))
            repository.saveVendor(VendorInfo("supplier", "project", "Old supplier"))
            repository.saveExpense(ExpenseInput(id = "invoice", projectId = "project", description = "Invoice",
                amountPaise = 100, date = "2026-01-01", vendorId = "supplier"))
            val updates = Channel<List<InsightRow>>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.insights("project", dimension = "SUPPLIER").collect { updates.send(it) }
            }
            try {
                assertEquals("Old supplier", withTimeout(2_000) { updates.receive().single().name })
                repository.saveVendor(VendorInfo("supplier", "project", "New supplier"))
                assertEquals("New supplier", withTimeout(2_000) { updates.receive().single().name })
            } finally { collector.cancelAndJoin(); updates.close() }
        } finally { repository.close() }
    }

    @Test fun allocatedLinesSupplierIdentityAndExactDrilldownReconcile() = runBlocking<Unit> {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("project", "Build"))
            repository.saveProject(ProjectInfo("other", "Other build"))
            repository.saveVendor(VendorInfo("supplier", "project", "Same supplier name"))
            repository.saveVendor(VendorInfo("different", "project", "Same supplier name"))
            val lines = listOf(
                ExpenseLineInput("cement1", "Cement %_", 350000),
                ExpenseLineInput("cement2", "Cement %_", 350000),
                ExpenseLineInput("steel", "Steel", 300000))
            val invoice = LedgerViewModel.expense(EntryDraft.fresh("project")
                .with("id", "invoice").with("key", "invoice").with("description", "Mixed invoice")
                .with("vendor", "supplier").with("amount", "10000")
                .with("invoiceLines", JSONArray(lines.map(LedgerViewModel::lineJson)).toString())
                .with("invoiceCharges", "true").with("discountPercent", "10")
                .with("taxPercent", "18").with("transport", "500"))
            repository.saveExpense(invoice)
            repository.saveExpense(ExpenseInput(id = "direct", projectId = "project", description = "Cement %_",
                amountPaise = 1999, date = "2026-01-01"))
            repository.saveExpense(ExpenseInput(id = "lookalike", projectId = "project", description = "Cement AB",
                vendorId = "different", amountPaise = 2300, date = "2026-01-01"))
            repository.saveExpense(ExpenseInput(id = "foreign", projectId = "other", description = "Cement %_",
                amountPaise = 99000, date = "2026-01-01"))
            repository.saveExpense(ExpenseInput(id = "voided", projectId = "project", description = "Cement %_",
                vendorId = "supplier", amountPaise = 500, date = "2026-01-01"))
            repository.voidExpense("voided", 0)
            repository.savePayment(PaymentInput(id = "paid", projectId = "project", amountPaise = 400000,
                vendorId = "supplier", date = "2026-01-01", allocations = listOf(PaymentAllocationInput("invoice", 400000))))

            val summary = repository.summary("project").first()
            val materials = repository.insights("project", dimension = "MATERIAL").first()
            assertEquals(1116299L, summary.recordedPaise)
            assertEquals(summary.recordedPaise, Money.sum(materials.map { it.amountPaise }))
            val cement = materials.single { it.name == "Cement %_" }
            assertEquals(780399L, cement.amountPaise)
            assertEquals(2, cement.expenseCount)
            assertEquals(333600L, materials.single { it.name == "Steel" }.amountPaise)
            val filter = ExpenseFilter(materialName = "Cement %_", postedOnly = true)
            val matching = repository.expenses("project", filter).first()
            assertEquals(setOf("invoice", "direct"), matching.map { it.id }.toSet())
            val totals = repository.filteredExpenseSummary("project", filter).first()
            assertEquals(2, totals.count)
            // Drilldown displays whole matching invoices, not just the selected material's share.
            assertEquals(1113999L, totals.recordedPaise)
            assertEquals(400000L, totals.settledPaise)
            assertEquals(713999L, totals.outstandingPaise)

            val suppliers = repository.insights("project", dimension = "SUPPLIER").first()
            assertEquals(summary.recordedPaise, Money.sum(suppliers.map { it.amountPaise }))
            assertEquals(3, suppliers.size)
            assertEquals(1112000L, suppliers.single { it.id == "supplier" }.amountPaise)
            assertEquals(2300L, suppliers.single { it.id == "different" }.amountPaise)
            assertEquals(1999L, suppliers.single { it.id == null }.amountPaise)
            val unknown = ExpenseFilter(missingVendor = true, postedOnly = true)
            assertEquals(listOf("direct"), repository.expenses("project", unknown).first().map { it.id })
            assertEquals(1999L, repository.filteredExpenseSummary("project", unknown).first().recordedPaise)
            val selectedSupplier = ExpenseFilter(vendorId = "supplier", postedOnly = true)
            assertEquals(listOf("invoice"), repository.expenses("project", selectedSupplier).first().map { it.id })
            assertEquals(1112000L, repository.filteredExpenseSummary("project", selectedSupplier).first().recordedPaise)
        } finally { repository.close() }
    }
}
