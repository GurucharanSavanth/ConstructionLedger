package com.constructioncost.nativeapp.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.InstalmentInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.VendorInfo
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupplierDrilldownV7Test {
    @get:Rule(order = 0) val preferences = TestPreferencesRule()
    private lateinit var repository: LedgerRepository
    @get:Rule(order = 1) val cleanup = object : ExternalResource() {
        override fun after() { if (::repository.isInitialized) repository.close() }
    }
    @get:Rule(order = 2) val compose = createComposeRule()
    private val actions = UiActions(importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
        renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {}, restoreBackup = {},
        importLegacy = {}, exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {})

    @Test fun unknownSupplierDrilldownShowsOnlyItsInvoices() {
        repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            repository.ensureDefaultProject()
            repository.saveVendor(VendorInfo("supplier", "default-project", "Known supplier"))
            repository.saveExpense(ExpenseInput(id = "unknown-bill", projectId = "default-project",
                description = "No supplier invoice", amountPaise = 10_000, date = "2026-10-22"))
            repository.saveExpense(ExpenseInput(id = "supplier-bill", projectId = "default-project",
                description = "Known supplier invoice", amountPaise = 20_000, date = "2026-10-22", vendorId = "supplier"))
            repository.savePayment(PaymentInput(id = "unrelated-payment", projectId = "default-project",
                amountPaise = 5_000, date = "2026-10-22", reference = "Unrelated transfer"))
            repository.saveInstalment(InstalmentInput(id = "unrelated-plan", projectId = "default-project",
                title = "Unrelated planned payment", amountPaise = 7_000, dueDate = "2026-10-22"))
            assertTrue(repository.projectAudits("default-project").first().isNotEmpty())
        }
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Explore", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Supplier", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Supplier", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Unknown supplier").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Unknown supplier", substring = false))
        compose.onAllNodesWithText("Unknown supplier", substring = false).onFirst().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Search supplier, item, category, invoice or room...")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Filter activities: Bills and purchases").assertExists()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Showing", substring = true))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Showing 1 records").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("No supplier invoice", substring = false))
        compose.onNodeWithText("No supplier invoice", substring = false).assertExists()
        compose.onNodeWithText("Known supplier invoice", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Unrelated transfer", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Unrelated planned payment", substring = false).assertDoesNotExist()
        compose.onAllNodesWithText("Change log", substring = false).assertCountEquals(0)
    }
}
