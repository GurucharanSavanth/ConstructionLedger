package com.constructioncost.nativeapp.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.Money
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.atomic.AtomicInteger

@RunWith(Parameterized::class)
class EntryReviewAccessibilityTest(private val fontScale: Float, private val orientation: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "fontScale={0}, orientation={1}")
        fun configurations(): List<Array<Any>> = listOf(1f, 1.3f, 1.5f, 2f).flatMap { scale ->
            listOf(Configuration.ORIENTATION_PORTRAIT, Configuration.ORIENTATION_LANDSCAPE)
                .map { requested -> arrayOf<Any>(scale, requested) }
        }
    }

    @get:Rule(order = 0) val preferences = TestPreferencesRule()
    private var repository: LedgerRepository? = null
    private val models = ViewModelStore()
    @get:Rule(order = 1) val cleanup = object : ExternalResource() {
        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            repository?.close()
        }
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longReviewKeepsAmountAndBothActionsReachableAndPostsOnce() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.requestedOrientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == orientation }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(orientation, compose.activity.resources.configuration.orientation)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ledger = LedgerRepository.inMemory(context).also { repository = it }
        runBlocking { ledger.saveProject(ProjectInfo("review-project", "Review accessibility fixture")) }
        val description = (1..12).joinToString("\n") {
            "Invoice detail $it: foundation materials, delivery, site measurements and work notes."
        }
        val invoiceNumber = "V7-ACCESSIBILITY-" + "REFERENCE-".repeat(16)
        val draft = EntryDraft.fresh("review-project", "Purchase").with("description", description)
            .with("amount", "123.45").with("invoiceNumber", invoiceNumber).with("paidStatus", "Unpaid")
        lateinit var model: LedgerViewModel
        compose.activityRule.scenario.onActivity {
            model = LedgerViewModel(context, ledger,
                SavedStateHandle(mapOf("project" to "review-project", "draft" to draft.json())), preferences.preferences.store)
            models.put("entry-review", model)
        }
        runBlocking { withTimeout(5_000) { model.state.first { it.ready } } }
        val saved = AtomicInteger()
        val actions = UiActions(importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
            renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {}, restoreBackup = {},
            importLegacy = {}, exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {})
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                WebTheme { EntryScreen(model, actions, { saved.incrementAndGet() }, {}) }
            }
        }

        fun review() {
            compose.waitUntil(10_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().size == 1 }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Review and save"))
            compose.onNodeWithText("Review and save").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Review entry").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Amount: ${Money.format(12_345)}").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Save entry").assertIsDisplayed()
            compose.onNodeWithText("Back to edit").assertIsDisplayed()
        }
        review()
        compose.onNodeWithText("Back to edit").performClick()
        assertEquals(draft, model.state.value.draft)
        assertEquals(0, runBlocking { ledger.expenses("review-project").first().size })
        review()
        compose.onNodeWithText("Save entry").performSemanticsAction(SemanticsActions.OnClick) { click ->
            click()
            click()
        }
        compose.waitUntil(10_000) { saved.get() == 1 && !model.state.value.saving }
        val expenses = runBlocking { ledger.expenses("review-project").first() }
        assertEquals(1, saved.get())
        assertEquals(1, expenses.size)
        assertEquals(draft.id, expenses.single().id)
        assertEquals(description, expenses.single().description)
        assertEquals(invoiceNumber, expenses.single().invoiceNumber)
        assertEquals(12_345L, expenses.single().amountPaise)
        compose.waitForIdle()
    }
}
