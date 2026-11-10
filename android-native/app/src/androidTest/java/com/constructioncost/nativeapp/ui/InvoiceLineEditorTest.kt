package com.constructioncost.nativeapp.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ExpenseLineInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvoiceLineEditorTest {
    @get:Rule(order = 0) val preferences = TestPreferencesRule()
    private var testRepository: LedgerRepository? = null
    private val models = ViewModelStore()
    @get:Rule(order = 1) val databaseCleanup = object : ExternalResource() {
        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            testRepository?.close(); testRepository = null
        }
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<ComponentActivity>()

    private fun viewModel(): LedgerViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context).also { testRepository = it }
        runBlocking { repository.ensureDefaultProject() }
        lateinit var model: LedgerViewModel
        compose.activityRule.scenario.onActivity {
            model = LedgerViewModel(context, repository,
                SavedStateHandle(mapOf("project" to "default-project")), preferences.preferences.store)
            models.put("entry-line-editor", model)
        }
        runBlocking { withTimeout(5_000) { model.state.first { it.ready } } }
        return model
    }

    @Test fun unfinishedLineEditRetainsIdentityAndFieldsAfterRecreation() {
        val line = ExpenseLineInput("existing-line", "Original line", 10_000)
        val draft = mutableStateOf(EntryDraft.fresh("project").with("invoiceLines",
            JSONArray(listOf(LedgerViewModel.lineJson(line))).toString()))
        val model = viewModel()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { Column {
            InvoiceLines(model, "default-project", draft.value, { key, value -> draft.value = draft.value.with(key, value) }, emptyList())
        } } }
        compose.onNodeWithText("1. Original line", substring = true).performClick()
        compose.onNodeWithText("Line description").performTextReplacement("Edited before recreation")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Line final amount (₹)"))
        compose.onNodeWithText("Line final amount (₹)").performTextReplacement("250")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Invoice line").assertExists()
        compose.onNodeWithText("Keep line").performClick()
        compose.runOnIdle {
            val restored = LedgerViewModel.readLines(draft.value.get("invoiceLines")).single()
            assertEquals("existing-line", restored.id)
            assertEquals("Edited before recreation", restored.description)
            assertEquals(25_000L, restored.amountPaise)
        }
    }

    @Test fun addingLineKeepsCalculatedPurchaseWithoutDirectAmount() {
        val draft = mutableStateOf(EntryDraft.fresh("project").with("description", "Cement")
            .with("mode", "CALCULATED").with("quantity", "2").with("rate", "50"))
        val model = viewModel()
        compose.setContent { MaterialTheme { Column { InvoiceLines(model, "default-project", draft.value, { key, value -> draft.value = draft.value.with(key, value) }, emptyList()) } } }
        compose.onNodeWithText("Add invoice line").performClick()
        compose.onNodeWithText("Line description").performTextReplacement("Sand")
        compose.onNodeWithText("Line final amount (₹)").performTextReplacement("200")
        compose.onNodeWithText("Keep line").performClick()
        compose.runOnIdle {
            val expense = LedgerViewModel.expense(draft.value)
            assertEquals(listOf("Cement", "Sand"), expense.lines.map { it.description })
            assertEquals(listOf(10_000L, 20_000L), expense.lines.map { it.amountPaise })
            assertEquals("CALCULATED", expense.lines.first().mode)
            assertEquals(30_000L, expense.amountPaise)
        }
    }

    @Test fun invalidFirstItemCannotBeSilentlyReplaced() {
        val draft = mutableStateOf(EntryDraft.fresh("project").with("description", "Cement").with("amount", "invalid"))
        val model = viewModel()
        compose.setContent { MaterialTheme { Column { InvoiceLines(model, "default-project", draft.value, { key, value -> draft.value = draft.value.with(key, value) }, emptyList()) } } }
        compose.onNodeWithText("Add invoice line").performClick()
        compose.onNodeWithText("Line description").performTextReplacement("Sand")
        compose.onNodeWithText("Line final amount (₹)").performTextReplacement("200")
        compose.onNodeWithText("Keep line").performClick()
        compose.onNodeWithText("Invoice line").assertExists()
        compose.runOnIdle {
            assertEquals("invalid", draft.value.get("amount"))
            assertEquals("", draft.value.get("invoiceLines"))
        }
    }

    @Test fun addingLineKeepsExistingPurchaseAsFirstLine() {
        val draft = mutableStateOf(EntryDraft.fresh("project").with("description", "Cement")
            .with("amount", "18500"))
        val model = viewModel()
        compose.setContent { MaterialTheme { Column { InvoiceLines(model, "default-project", draft.value, { key, value -> draft.value = draft.value.with(key, value) }, emptyList()) } } }
        compose.onNodeWithText("Add invoice line").performClick()
        compose.onNodeWithText("Line description").performTextReplacement("Sand")
        compose.onNodeWithText("Line final amount (₹)").performTextReplacement("1500")
        compose.onNodeWithText("Keep line").performClick()
        compose.runOnIdle {
            val lines = LedgerViewModel.expense(draft.value).lines
            assertEquals(listOf("Cement", "Sand"), lines.map { it.description })
            assertEquals(listOf(1_850_000L, 150_000L), lines.map { it.amountPaise })
        }
    }

    @Test fun directLineRetainsOptionalQuantityRateWithoutRecalculatingAmount() {
        val original = ExpenseLineInput("direct", "Cement", 1850000, quantity = "50", rate = "400", quantityUnit = "kg", rateUnit = "kg")
        val draft = mutableStateOf(EntryDraft.fresh("project").with("description", "Invoice")
            .with("amount", "18500").with("invoiceLines", JSONArray(listOf(LedgerViewModel.lineJson(original))).toString()))
        val model = viewModel()
        compose.setContent { MaterialTheme { Column { InvoiceLines(model, "default-project", draft.value, { key, value -> draft.value = draft.value.with(key, value) }, emptyList()) } } }
        compose.onNodeWithText("1. Cement", substring = true).performClick()
        compose.onNodeWithText("Line description").performTextReplacement("Cement checked")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Quantity (optional)"))
        compose.onNodeWithText("Quantity (optional)").performTextReplacement("60")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Rate (optional, ₹)"))
        compose.onNodeWithText("Rate (optional, ₹)").performTextReplacement("500")
        compose.onNodeWithText("Keep line").performClick()
        compose.runOnIdle {
            val line = LedgerViewModel.expense(draft.value).lines.single()
            assertEquals(1850000L, line.amountPaise)
            assertEquals("60", line.quantity)
            assertEquals("500", line.rate)
            assertEquals("kg", line.quantityUnit)
        }
    }

    @Test fun descriptionOnlyEditPreservesImportedAreaUnitsAndLiteralCost() {
        val original = ExpenseLineInput("area", "Plaster", 10000, "CALCULATED", "100", "100", "1",
            "sqft", "sq100", "{\"original\":\"legacy\"}")
        val draft = mutableStateOf(EntryDraft.fresh("project").with("description", "Invoice")
            .with("amount", "100").with("invoiceLines", JSONArray(listOf(LedgerViewModel.lineJson(original))).toString()))
        val model = viewModel()
        compose.setContent { MaterialTheme { Column { InvoiceLines(model, "default-project", draft.value, { key, value -> draft.value = draft.value.with(key, value) }, emptyList()) } } }
        compose.onNodeWithText("1. Plaster", substring = true).performClick()
        compose.onNodeWithText("Line description").performTextReplacement("Plaster checked")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Quantity unit: sqft"))
        compose.onNodeWithContentDescription("Quantity unit: sqft").assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Rate unit: sq100"))
        compose.onNodeWithContentDescription("Rate unit: sq100").assertExists()
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
