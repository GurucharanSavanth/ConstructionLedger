package com.constructioncost.nativeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.platform.LegacyImportMapper
import com.constructioncost.nativeapp.domain.Money
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyImportMapperTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(): JSONObject = JSONObject().apply {
        put("schema", 1)
        put("project", JSONObject().put("name", "Test house").put("budget", 1000000L).put("currency", "INR"))
        put("settings", JSONObject())
        put("bills", JSONArray().put(JSONObject().put("id", "bill-1").put("title", "Cement and steel")
            .put("vendor", "Supplier").put("date", "2026-01-02").put("total", 11120L)
            .put("extras", 500L).put("tax", 1620L).put("discount", 1000L)
            .put("lines", JSONArray().put(JSONObject().put("label", "Cement").put("mode", "amount")
                .put("directAmountPaise", 5000L).put("value", 5000L).put("work", "Foundation"))
                .put(JSONObject().put("label", "Steel").put("mode", "amount")
                    .put("directAmountPaise", 5000L).put("value", 5000L).put("work", "Foundation")))))
        put("payments", JSONArray().put(JSONObject().put("id", "payment-1").put("amount", 4000L)
            .put("vendor", "Supplier").put("date", "2026-01-03").put("reference", "cash")))
        put("allocations", JSONArray().put(JSONObject().put("id", "allocation-1").put("paymentId", "payment-1")
            .put("billId", "bill-1").put("amount", 4000L)))
        put("plans", JSONArray()); put("contracts", JSONArray()); put("entries", JSONArray())
        put("files", JSONArray()); put("audit", JSONArray())
    }

    @Test fun mappedExpenseAndPaymentReconcileInRoom() = runBlocking {
        val mapped = LegacyImportMapper(context).map(fixture())
        assertEquals(11120L, mapped.recordedPaise)
        assertEquals(4000L, mapped.paidPaise)
        val lines = mapped.records.getJSONArray("expense_lines")
        assertEquals(11120L, (0 until lines.length()).sumOf { lines.getJSONObject(it).getLong("amountPaise") })
        val repository = LedgerRepository.inMemory(context)
        try { repository.validateRecords(mapped.records) } finally { repository.close() }
    }

    @Test fun omittedQuantityUnitUsesRateUnitAndRemainsCalculableAfterImport() = runBlocking<Unit> {
        for (quantityUnit in listOf("absent", "null", "blank")) {
            val line = JSONObject().put("label", "Steel").put("mode", "calculated")
                .put("qty", "2").put("rate", "10").put("unitId", "kg").put("value", 2000L)
            when (quantityUnit) {
                "null" -> line.put("quantityUnitId", JSONObject.NULL)
                "blank" -> line.put("quantityUnitId", "")
            }
            val source = fixture().put("payments", JSONArray()).put("allocations", JSONArray())
            source.getJSONArray("bills").getJSONObject(0).put("total", 2000L)
                .put("extras", 0).put("tax", 0).put("discount", 0).put("lines", JSONArray().put(line))
            val repository = LedgerRepository.inMemory(context)
            try {
                repository.replaceRecords(LegacyImportMapper(context).map(source).records)
                val expense = repository.expense("legacy:bill:bill-1")!!
                val imported = expense.lines.single()
                assertEquals("kg", imported.quantityUnit)
                assertEquals("kg", imported.rateUnit)
                assertEquals(2000L, Money.calculate(imported.quantity!!, imported.rate!!, imported.multiplier,
                    quantityUnit = imported.quantityUnit, rateUnit = imported.rateUnit).totalPaise)
                repository.saveExpense(expense.copy(description = "Edited steel invoice"))
                assertEquals("kg", repository.expense(expense.id)!!.lines.single().quantityUnit)
            } finally { repository.close() }
        }
    }
    @Test fun legacyAuditExportsWithItsKnownProjectWithoutGuessingTargetNamespace() = runBlocking<Unit> {
        val source = fixture().put("audit", JSONArray().put(JSONObject().put("id", "legacy-change")
            .put("targetId", "removed-item").put("type", "delete").put("at", "2026-01-03T10:00:00Z").put("detail", "Original deleted value")))
        val repository = LedgerRepository.inMemory(context)
        try {
            repository.replaceRecords(LegacyImportMapper(context).map(source).records)
            val rows = mutableListOf<JSONObject>()
            repository.forEachExportRow("legacy-project") { table, row -> if (table == "audit_records" && row.getString("recordType") == "legacy") rows += row }
            assertEquals(1, rows.size)
            assertEquals("Original deleted value", JSONObject(rows.single().getString("afterJson")).getString("detail"))
            assertEquals("legacy-project", JSONObject(rows.single().getString("afterJson")).getString("_auditProjectId"))
        } finally { repository.close() }
    }

    @Test fun legacyDocumentDetailsBecomeEditableNativeFields() {
        val source = fixture().put("files", JSONArray().put(JSONObject().put("id", "drawing")
            .put("name", "Footing.pdf").put("type", "application/pdf").put("size", 123L)
            .put("sha256", "a".repeat(64)).put("importedAt", "2026-01-02T10:00:00Z")
            .put("category", "Drawing").put("status", "Approved for construction").put("revision", "R2")
            .put("work", "Foundation").put("place", "Ground floor").put("notes", "Engineer approval")))
        val document = LegacyImportMapper(context).map(source).records.getJSONArray("attachments").getJSONObject(0)
        assertEquals("Drawing", document.getString("category"))
        assertEquals("Approved for construction", document.getString("status"))
        assertEquals("R2", document.getString("revisionLabel"))
        assertEquals("Foundation", document.getString("work"))
        assertEquals("Ground floor", document.getString("place"))
        assertEquals("Engineer approval", document.getString("notes"))
    }

    @Test fun dueDayReminderAndInvoiceReferenceSurviveLegacyMapping() {
        val source = fixture()
        source.getJSONArray("bills").getJSONObject(0).put("invoice", "INV-204")
        source.put("plans", JSONArray().put(JSONObject().put("id", "plan").put("title", "Engineer instalment")
            .put("amount", 2500000L).put("due", "2026-02-01").put("remind", 0)))
        val mapped = LegacyImportMapper(context).map(source).records
        assertEquals("INV-204", mapped.getJSONArray("expenses").getJSONObject(0).getString("invoiceNumber"))
        assertEquals(true, mapped.getJSONArray("instalments").getJSONObject(0).getBoolean("remind"))
        assertEquals(0, mapped.getJSONArray("reminders").getJSONObject(0).getInt("leadDays"))
        assertEquals(true, mapped.getJSONArray("reminders").getJSONObject(0).getBoolean("enabled"))
    }

    @Test fun orphanedAllocationCannotReplaceLedger() {
        val bad = fixture().apply { getJSONArray("allocations").getJSONObject(0).put("billId", "missing") }
        assertThrows(IllegalArgumentException::class.java) { LegacyImportMapper(context).map(bad) }
    }
}
