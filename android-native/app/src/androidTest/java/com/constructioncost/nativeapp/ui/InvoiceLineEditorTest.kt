package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.constructioncost.nativeapp.domain.ExpenseLineInput
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvoiceLineEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun descriptionOnlyEditPreservesImportedAreaUnitsAndLiteralCost() {
        val original = ExpenseLineInput("area", "Plaster", 10000, "CALCULATED", "100", "100", "1",
            "sqft", "sq100", "{\"original\":\"legacy\"}")
        val draft = mutableStateOf(EntryDraft.fresh("project").with("description", "Invoice")
            .with("amount", "100").with("invoiceLines", JSONArray(listOf(LedgerViewModel.lineJson(original))).toString()))
        compose.setContent { MaterialTheme { Column { InvoiceLines(draft.value, { key, value -> draft.value = draft.value.with(key, value) }, emptyList()) } } }
        compose.onNodeWithText("1. Plaster", substring = true).performClick()
        compose.onNodeWithText("Line description").performTextReplacement("Plaster checked")
        compose.onNodeWithText("Quantity unit: sqft").performScrollTo().assertExists()
        compose.onNodeWithText("Rate unit: sq100").performScrollTo().assertExists()
        compose.onNodeWithText("Keep line").performClick()
        compose.runOnIdle {
            val line = LedgerViewModel.expense(draft.value).lines.single()
            assertEquals("Plaster checked", line.description)
            assertEquals(10000L, line.amountPaise)
            assertEquals("sqft", line.quantityUnit)
            assertEquals("sq100", line.rateUnit)
            assertEquals("legacy", org.json.JSONObject(line.legacyJson).getString("original"))
        }
    }
}
