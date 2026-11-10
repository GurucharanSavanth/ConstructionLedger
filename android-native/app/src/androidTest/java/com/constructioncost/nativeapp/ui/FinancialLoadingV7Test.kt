package com.constructioncost.nativeapp.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.local.LedgerDatabase
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ExpenseFilter
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.Money
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class FinancialLoadingV7Test {
    @get:Rule(order = 0) val preferences = TestPreferencesRule()
    private val models = ViewModelStore()
    private val releaseSummary = CountDownLatch(1)
    private var repository: LedgerRepository? = null
    private var sandbox: File? = null
    @get:Rule(order = 1) val cleanup = object : ExternalResource() {
        override fun after() {
            releaseSummary.countDown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            repository?.close()
            sandbox?.let {
                check(it.canonicalFile.parentFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile)
                it.deleteRecursively()
            }
        }
    }
    @get:Rule(order = 2) val compose = createComposeRule()

    @Test fun editCommandKeepsValidationRecoverableAndRoutesSqliteFailureToRecovery() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "command-recovery-${UUID.randomUUID()}").apply { check(mkdirs()) }
            .also { sandbox = it }
        val context = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(directory, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val ledger = LedgerRepository.inMemory(context).also { repository = it }
        runBlocking { ledger.saveProject(ProjectInfo("commands", "Command fixture")) }
        lateinit var model: LedgerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = LedgerViewModel(context, ledger, SavedStateHandle(), preferences.preferences.store)
            models.put("command-recovery", model)
        }
        runBlocking { withTimeout(5_000) { model.state.first { it.ready } } }
        compose.setContent { Text("Command fixture") }
        compose.runOnIdle { model.command("Loaded") { throw IllegalArgumentException("Validation fixture") } }
        compose.waitUntil(5_000) { !model.state.value.saving && model.state.value.actionError != null }
        assertFalse(model.state.value.recoveryRequired)
        assertEquals("Validation fixture", model.state.value.actionError)
        compose.runOnIdle { model.command("Loaded") { throw android.database.sqlite.SQLiteException("Edit read fixture") } }
        compose.waitUntil(5_000) { model.state.value.recoveryRequired && !model.state.value.saving }
        assertTrue(model.state.value.error.orEmpty().contains("Edit read fixture"))
    }

    @Test fun sharedReadDistinguishesLoadingFromEmptyAndRoutesFailureToRecovery() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "read-recovery-${UUID.randomUUID()}").apply { check(mkdirs()) }
            .also { sandbox = it }
        val context = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(directory, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val ledger = LedgerRepository.inMemory(context).also { repository = it }
        runBlocking { ledger.saveProject(ProjectInfo("reads", "Read fixture")) }
        lateinit var model: LedgerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = LedgerViewModel(context, ledger, SavedStateHandle(), preferences.preferences.store)
            models.put("read-recovery", model)
        }
        runBlocking { withTimeout(5_000) { model.state.first { it.ready } } }
        val results = MutableSharedFlow<Result<List<String>>>(extraBufferCapacity = 1)
        val source = results.map { it.getOrThrow() }
        compose.setContent {
            val rows = source.readLedger(model)
            Text(if (rows == null) "Loading saved records" else if (rows.isEmpty()) "Verified empty" else rows.joinToString())
        }
        compose.onNodeWithText("Loading saved records").assertIsDisplayed()
        compose.onNodeWithText("Verified empty").assertDoesNotExist()
        compose.waitUntil(5_000) { results.subscriptionCount.value > 0 }
        compose.runOnIdle { assertTrue(results.tryEmit(Result.success(emptyList()))) }
        compose.onNodeWithText("Verified empty").assertIsDisplayed()
        compose.runOnIdle { assertTrue(results.tryEmit(Result.failure(android.database.sqlite.SQLiteException("Read fixture failure")))) }
        compose.waitUntil(5_000) { model.state.value.recoveryRequired }
        assertTrue(model.state.value.error.orEmpty().contains("Read fixture failure"))
        compose.onNodeWithText("Verified empty").assertDoesNotExist()
        compose.onNodeWithText("Loading saved records").assertIsDisplayed()
    }

    @Test fun homeKeepsUnloadedFinancesHiddenUntilSummaryQueryCompletes() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "financial-loading-${UUID.randomUUID()}").apply { check(mkdirs()) }
            .also { sandbox = it }
        val context = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(directory, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val armed = AtomicBoolean()
        val summaryBlocked = CountDownLatch(1)
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ ->
                if (armed.get() && sql.contains("advancePaise")) {
                    summaryBlocked.countDown()
                    check(releaseSummary.await(10, TimeUnit.SECONDS)) { "Summary query test gate timed out" }
                }
            }, Executor { it.run() }).build()
        val ledger = LedgerRepository(database, context).also { repository = it }
        runBlocking {
            ledger.saveProject(ProjectInfo("loading-project", "Financial loading fixture"))
            ledger.saveExpense(ExpenseInput(id = "loading-invoice", projectId = "loading-project",
                description = "Recorded invoice", amountPaise = 12_345, date = "2026-10-22"))
        }
        lateinit var model: LedgerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = LedgerViewModel(context, ledger, SavedStateHandle(), preferences.preferences.store)
            models.put("financial-loading", model)
        }
        runBlocking { withTimeout(5_000) { model.state.first { it.ready } } }
        armed.set(true)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    WebTheme { WebHomeScreen(model, {}, { _, _ -> }, {}) }
                }
            }
            compose.waitUntil(5_000) { summaryBlocked.count == 0L }
            compose.onNodeWithText("Loading project finances").assertIsDisplayed()
            compose.onNodeWithText("Money paid").assertDoesNotExist()
            compose.onNodeWithText("Recorded cost").assertDoesNotExist()

            releaseSummary.countDown()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Recorded cost").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText("Recorded cost") and hasText(Money.format(12_345))).assertIsDisplayed()
            compose.onNodeWithText("Loading project finances").assertDoesNotExist()
        } finally {
            releaseSummary.countDown()
        }
    }

    @Test fun changingActivityFilterHidesPreviousTotalsUntilMatchingQueryCompletes() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "financial-filter-${UUID.randomUUID()}").apply { check(mkdirs()) }
            .also { sandbox = it }
        val context = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(directory, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val secondSummaryBlocked = CountDownLatch(1)
        val summaryArgs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val database = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .setQueryCallback(RoomDatabase.QueryCallback { sql, args ->
                if (sql.contains("COUNT(*) count")) summaryArgs += args.joinToString()
                if (sql.contains("COUNT(*) count") && args.any { it?.toString()?.contains("Second", ignoreCase = true) == true }) {
                    secondSummaryBlocked.countDown()
                    check(releaseSummary.await(10, TimeUnit.SECONDS)) { "Filtered summary query test gate timed out" }
                }
            }, Executor { it.run() }).build()
        val ledger = LedgerRepository(database, context).also { repository = it }
        runBlocking {
            ledger.saveProject(ProjectInfo("filter-project", "Filtered finances fixture"))
            ledger.saveExpense(ExpenseInput(id = "first-invoice", projectId = "filter-project",
                description = "First expense", amountPaise = 10_000, date = "2026-10-22"))
            ledger.saveExpense(ExpenseInput(id = "second-invoice", projectId = "filter-project",
                description = "Second expense", amountPaise = 20_000, date = "2026-10-22"))
        }
        lateinit var model: LedgerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = LedgerViewModel(context, ledger, SavedStateHandle(), preferences.preferences.store)
            models.put("financial-filter", model)
        }
        runBlocking { withTimeout(5_000) { model.state.first { it.ready } } }
        val filter = mutableStateOf(ExpenseFilter(query = "First"))
        val actions = UiActions(importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
            renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {}, restoreBackup = {},
            importLegacy = {}, exportCsv = {}, exportPdf = {}, notifyMessage = {}, setReminders = {})
        val firstCost = "Bill cost ${Money.format(10_000)}"
        val secondCost = "Bill cost ${Money.format(20_000)}"
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    WebTheme { WebActivityScreen(model, filter.value, { filter.value = it }, { _, _ -> }, actions) }
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(
                hasText("Bill cost", substring = true) or hasText("Loading bill totals"))
            compose.waitUntil(10_000) { compose.onAllNodesWithText(firstCost).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(firstCost).assertIsDisplayed()

            compose.onNodeWithContentDescription("Search supplier, item, category, invoice or room...")
                .performScrollTo().performTextReplacement("Second")
            compose.waitForIdle()
            assertTrue("Search input did not update the activity filter: ${filter.value.query}", filter.value.query == "Second")
            compose.waitUntil(5_000) { secondSummaryBlocked.count == 0L }
            assertTrue("Second query never reached SQLite; observed args=$summaryArgs",
                summaryArgs.any { it.contains("Second", ignoreCase = true) })
            compose.onNodeWithText("Loading bill totals").assertIsDisplayed()
            compose.onNodeWithText(firstCost).assertDoesNotExist()

            releaseSummary.countDown()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(secondCost).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(secondCost).assertIsDisplayed()
            compose.onNodeWithText(firstCost).assertDoesNotExist()
            compose.onNodeWithText("Loading bill totals").assertDoesNotExist()
        } finally {
            releaseSummary.countDown()
        }
    }
}
