package com.constructioncost.nativeapp.ui

import android.database.sqlite.SQLiteDatabase
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.ConstructionApplication
import com.constructioncost.nativeapp.MainActivity
import com.constructioncost.nativeapp.SeparateVerification
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SeparateVerification
class RecoveryV7ActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualRetryReusesRepositoryAndViewModelAndRetainsDraftAfterTransientReadFailure() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Fault injection requires the disposable verification application",
            context.packageName == "com.constructioncost.nativeapp.verification")
        val app = context.applicationContext as ConstructionApplication
        if (app.recoveryState.isRecoveryRequired()) {
            check(app.recoveryState.clearForSafeOpenRetry()) { "Verification app has unresolved recovery artifacts" }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(30_000) {
                app.recoveryState.isLedgerValidated() &&
                    compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty()
            }
            lateinit var model: LedgerViewModel
            lateinit var repository: LedgerRepository
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity)[LedgerViewModel::class.java]
                repository = activity.repository
                assertSame(repository, model.repository)
                model.field("description", "V7 retained draft after read failure")
                model.field("amount", "246.80")
                model.field("invoiceNumber", "V7-ACTIVITY-RETRY")
            }
            compose.waitForIdle()
            val retained = model.state.value.draft
            val before = runBlocking { repository.exportRecords().toString() }
            val database = context.getDatabasePath(LedgerRecoveryStateStore.DATABASE_NAME)
            var renamed = false
            try {
                SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                    raw.execSQL("ALTER TABLE projects RENAME TO v7_unavailable")
                    renamed = true
                }
                scenario.onActivity { model.retryInitialization() }
                compose.waitUntil(10_000) { model.state.value.recoveryRequired }
                compose.onNodeWithText("Your saved data needs attention").assertIsDisplayed()
                assertEquals(LedgerRecoveryState.DATABASE_OPEN_FAILURE, app.recoveryState.state())

                SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                    raw.execSQL("ALTER TABLE v7_unavailable RENAME TO projects")
                    renamed = false
                }
                compose.onNodeWithText("Retry").performScrollTo().performClick()
                compose.waitUntil(30_000) {
                    app.recoveryState.isLedgerValidated() && model.state.value.ready && !model.state.value.recoveryRequired &&
                        compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty()
                }
                scenario.onActivity { activity ->
                    assertSame(repository, activity.repository)
                    assertSame(model, ViewModelProvider(activity)[LedgerViewModel::class.java])
                    assertSame(repository, model.repository)
                    assertEquals(retained, model.state.value.draft)
                }
                assertEquals(before, runBlocking { repository.exportRecords().toString() })
                compose.onNodeWithText("Your saved data needs attention").assertDoesNotExist()
                scenario.recreate()
                compose.waitUntil(30_000) {
                    app.recoveryState.isLedgerValidated() &&
                        compose.onAllNodesWithText("Project overview").fetchSemanticsNodes().isNotEmpty()
                }
                scenario.onActivity { activity ->
                    assertSame(model, ViewModelProvider(activity)[LedgerViewModel::class.java])
                    assertSame(repository, activity.repository)
                    assertEquals(retained, model.state.value.draft)
                }
                assertFalse(app.recoveryState.isRecoveryRequired())
            } finally {
                if (renamed) {
                    SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
                        raw.execSQL("ALTER TABLE v7_unavailable RENAME TO projects")
                    }
                }
                if (app.recoveryState.state() == LedgerRecoveryState.DATABASE_OPEN_FAILURE) {
                    app.recoveryState.clearForSafeOpenRetry()
                }
            }
        }
    }
}
