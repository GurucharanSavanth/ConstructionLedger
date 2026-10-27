package com.constructioncost.nativeapp.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.UiActions
import com.constructioncost.nativeapp.platform.CatalogSeeder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeReplicaTest {
    @get:Rule(order=0) val preferences=TestPreferencesRule()
    private lateinit var repository:LedgerRepository
    @get:Rule(order=1) val cleanup=object:ExternalResource(){override fun after(){if(::repository.isInitialized)repository.close()}}
    @get:Rule(order=2) val compose=createComposeRule()
    private val actions=UiActions(importDocument={},viewDocument={},shareDocument={},exportDocument={},renameDocument={_,_->},newRevision={},deleteDocument={},
        createBackup={},restoreBackup={},importLegacy={},exportCsv={},exportPdf={},notifyMessage={},setReminders={})

    private fun start() {
        repository=LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {repository.ensureDefaultProject();CatalogSeeder(InstrumentationRegistry.getInstrumentation().targetContext,repository).seed("default-project")}
        compose.setContent {BuildLedgerApp(repository,actions,preferences.preferences.store)}
        compose.waitUntil(10_000){compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty()}
    }

    @Test fun fiveWebTabsAndNineNativeEntryChoicesAreReachable() {
        start()
        screenshot("home")
        listOf("Home","Add Entry","Activity","Files","Explore").forEach{compose.onNodeWithText(it).assertExists()}
        compose.onNodeWithText("Add Entry").performClick()
        screenshot("add-entry")
        webEntryChoices.forEach { choice ->
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(choice.title))
            compose.onNodeWithText(choice.title).assertExists()
        }
        compose.onNodeWithText("Files").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Project Files").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Project Files").assertExists()
        screenshot("files")
        compose.onNodeWithText("Explore").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Cost explorer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cost explorer").assertExists()
        compose.onNodeWithText("Cost Heads").assertExists()
        screenshot("explore")
        compose.onNodeWithText("Activity").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Search supplier, item, category, invoice or room...").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search supplier, item, category, invoice or room...").assertExists()
        screenshot("activity")
    }

    private fun screenshot(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        try {
            java.io.File(instrumentation.targetContext.cacheDir,"native-replica-$name.png").outputStream().use {output->
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))
            }
        } finally {bitmap.recycle()}
    }

    @Test fun purchaseFlowReviewsAndPostsOnceIntoUnifiedActivity() {
        start()
        compose.onNodeWithText("Add Entry").performClick()
        compose.onNodeWithText("Bought materials").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Material / item").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Material / item").performTextInput("Replica cement invoice")
        compose.onNodeWithText("Final amount (₹)").performTextInput("18500")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Supplier / vendor"))
        compose.onNodeWithText("Supplier / vendor").performTextInput("Replica supplier")
        compose.onNodeWithText("Use new supplier: Replica supplier").performClick()
        compose.waitUntil(10_000){compose.onAllNodesWithText("Use new supplier: Replica supplier").fetchSemanticsNodes().isEmpty()}
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Payment status: Not paid yet"))
        compose.onNodeWithContentDescription("Payment status: Not paid yet").performClick()
        compose.onNodeWithText("Partly paid").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Amount paid now, if partial (₹)"))
        compose.onNodeWithText("Amount paid now, if partial (₹)").performTextInput("4000")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Review and save"))
        compose.onNodeWithText("Review and save").performClick()
        compose.onNodeWithText("Review entry").assertExists()
        compose.onNodeWithText("Save entry").performClick()
        compose.waitUntil(10_000){runBlocking{repository.expenses("default-project").first().size==1}}
        compose.waitUntil(10_000){compose.onAllNodesWithContentDescription("Search supplier, item, category, invoice or room...").fetchSemanticsNodes().isNotEmpty()}
        val activityRows=runBlocking { repository.journal("default-project",ExpenseFilter(postedOnly=true)).first() }
        val expenseIndex=activityRows.indexOfFirst { it.event.type=="EXPENSE" && it.event.title=="Replica cement invoice" }
        assertTrue("Posted expense must be present in the project journal",expenseIndex>=0)
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Showing ${activityRows.size} records").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(7+expenseIndex)
        compose.onNodeWithText("Replica cement invoice").assertIsDisplayed()
        assertEquals(1_850_000L,runBlocking{repository.summary("default-project").first().recordedPaise})
        assertEquals(400_000L,runBlocking{repository.summary("default-project").first().paidPaise})
        assertEquals(1_450_000L,runBlocking{repository.summary("default-project").first().outstandingPaise})
        assertEquals("Replica supplier",runBlocking{repository.expenses("default-project").first().single().vendorName})
    }

    @Test fun olderLoadedJournalPagesRefreshAfterAnEditedRecordOutsideFirstPage() {
        repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            repository.ensureDefaultProject()
            repeat(151) { index -> repository.saveNote(ActivityNote("note-$index", "default-project", "Journal work $index", "2026-10-23", "")) }
        }
        compose.setContent { BuildLedgerApp(repository, actions, preferences.preferences.store) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Activity").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Filter activities: All activity").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Filter activities: All activity").performClick()
        compose.onNode(hasText("Work log") and hasAnyAncestor(isDialog())).performClick()
        fun awaitCount(count: Int) = compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Showing $count records").fetchSemanticsNodes().isNotEmpty()
        }
        fun more() {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Show 75 more"))
            compose.onNodeWithText("Show 75 more").performClick()
            compose.onNode(hasScrollToNodeAction()).performScrollToIndex(0)
        }
        awaitCount(75)
        more(); awaitCount(150)
        more(); awaitCount(151)
        runBlocking { repository.saveNote(ActivityNote("note-60", "default-project", "Edited cached work", "2026-10-23", "", revision = 0)) }
        compose.onNode(hasScrollToNodeAction()).performScrollToIndex(0)
        awaitCount(75)
        more(); awaitCount(150)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Edited cached work"))
        compose.onNodeWithText("Edited cached work").assertExists()
    }

    @Test fun filteredActivityAndAuditEvidenceStayProjectScoped()=runBlocking<Unit> {
        repository=LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        repository.ensureDefaultProject()
        repository.saveProject(ProjectInfo("other","Other project"))
        repository.saveExpense(ExpenseInput(id="cement",projectId="default-project",description="Cement",amountPaise=10000,date="2026-10-01",note="Unique room evidence"))
        repository.saveExpense(ExpenseInput(id="private",projectId="other",description="Private invoice",amountPaise=25000,date="2026-10-01"))
        val filtered=repository.activity("default-project",query="Unique room evidence",sortByRecordedTime=true,
            expenseFilter=ExpenseFilter(query="Unique room evidence")).first()
        assertEquals(listOf("cement"),filtered.map{it.id})
        assertTrue(filtered.single().recordedAt>0)
        val audit=repository.projectAudits("default-project").first()
        assertTrue(audit.any{it.recordId=="cement"})
        assertFalse(audit.any{it.recordId=="private"})
        assertTrue(repository.projectAudits("default-project",search="Private invoice").first().isEmpty())
    }

    @Test fun overdueAndSettledFiltersKeepPlansSeparateFromBillCosts()=runBlocking<Unit> {
        repository=LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        repository.ensureDefaultProject()
        repository.saveInstalment(InstalmentInput(id="overdue",projectId="default-project",title="Overdue schedule",amountPaise=9000,dueDate="2020-01-01"))
        repository.saveInstalment(InstalmentInput(id="settled",projectId="default-project",title="Settled schedule",amountPaise=5000,dueDate="2020-01-01"))
        repository.savePayment(PaymentInput(id="paid",projectId="default-project",amountPaise=5000,date="2020-01-01",instalmentId="settled"))
        assertEquals(listOf("overdue"),repository.activity("default-project",settlementStatus="OVERDUE").first().map{it.id})
        val completed=repository.activity("default-project",expenseFilter=ExpenseFilter(status="PAID"),settlementStatus="SETTLED").first()
        assertEquals(listOf("settled"),completed.map{it.id})
        assertEquals(0L,repository.summary("default-project").first().recordedPaise)
        val audit=AuditInfo("audit","expenses","bill","EDIT","{\"amountPaise\":10000,\"description\":\"Cement\"}","{\"amountPaise\":12500,\"description\":\"Cement\"}",0)
        val change=readableAuditChanges(audit).single()
        assertEquals("Amount",change.label)
        assertEquals("₹100",change.before)
        assertEquals("₹125",change.after)
    }

    @Test fun deletingPartPaidBillReleasesAdvanceWithoutDeletingCash()=runBlocking<Unit> {
        repository=LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        repository.ensureDefaultProject()
        repository.saveExpense(ExpenseInput(id="part-paid",projectId="default-project",description="Part paid bill",amountPaise=10000,paidPaise=4000,date="2026-10-22"))
        repository.deleteExpense("part-paid",0)
        val summary=repository.summary("default-project").first()
        assertEquals(0L,summary.recordedPaise)
        assertEquals(0L,summary.outstandingPaise)
        assertEquals(4000L,summary.paidPaise)
        assertEquals(4000L,summary.advancePaise)
        assertTrue(repository.expenses("default-project",ExpenseFilter(postedOnly=true)).first().isEmpty())
        assertTrue(repository.projectAudits("default-project").first().any{it.recordId=="part-paid" && it.action=="DELETE"})
        assertThrows(IllegalArgumentException::class.java){runBlocking{repository.deleteExpense("part-paid",0)}}
        assertEquals(4000L,repository.summary("default-project").first().paidPaise)
    }
}
