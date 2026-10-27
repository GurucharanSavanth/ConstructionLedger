package com.constructioncost.nativeapp.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.platform.UiActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File
import java.util.UUID

class ZeroProjectNavigationTest {
    @get:Rule(order = 0) val preferences = TestPreferencesRule()
    private lateinit var repository: LedgerRepository
    private lateinit var sandbox: File
    @get:Rule(order = 1) val cleanup = object : ExternalResource() {
        override fun after() {
            if (::repository.isInitialized) repository.close()
            if (::sandbox.isInitialized) {
                check(sandbox.canonicalFile.parentFile == InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile)
                sandbox.deleteRecursively()
            }
        }
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun globalNavigationWaitsForGraphInstallation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nav = NavHostController(instrumentation.targetContext)
            assertNull(nav.currentDestination)
            assertFalse(nav.navigateIfGraphReady("projects") {})
            assertNull(nav.currentDestination)
        }
    }

    @Test fun emptyLedgerKeepsGlobalActionsAndCreationAfterRestoreAndFinalDeletion() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        sandbox = File(base.cacheDir, "zero-project-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : Application() {
            init { attachBaseContext(base) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
            override fun getDatabasePath(name: String) = File(sandbox, "databases/$name")
        }
        repository = LedgerRepository.inMemory(context)
        var restoreRequests = 0
        val actions = UiActions(importDocument = {}, viewDocument = {}, shareDocument = {}, exportDocument = {},
            renameDocument = { _, _ -> }, newRevision = {}, deleteDocument = {}, createBackup = {},
            restoreBackup = { restoreRequests++ }, importLegacy = {}, exportCsv = {}, exportPdf = {},
            notifyMessage = {}, setReminders = {})
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                BuildLedgerApp(repository, actions, preferences.preferences.store)
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(CREATE_PROJECT_MESSAGE).fetchSemanticsNodes().isNotEmpty() }

        for (tab in listOf("Add Entry", "Activity", "Files", "Explore", "Home")) {
            compose.onNodeWithText(tab, substring = false).performClick()
            compose.onNodeWithText(CREATE_PROJECT_MESSAGE).assertExists()
        }
        compose.onNodeWithContentDescription("Backup and restore").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Create encrypted backup").fetchSemanticsNodes().isNotEmpty() }
        val exportsSection = hasText("Exports", substring = true) and hasClickAction()
        val modalList = hasScrollToNodeAction() and hasAnyAncestor(isDialog())
        compose.onNode(modalList).performScrollToNode(exportsSection)
        compose.onNode(exportsSection).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Export CSV").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(modalList).performScrollToNode(hasText("Export CSV"))
        compose.onNodeWithText("Export CSV").assertIsNotEnabled()
        compose.onNode(modalList).performScrollToNode(hasText("Export PDF"))
        compose.onNodeWithText("Export PDF").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(modalList).performScrollToNode(hasText("Select backup to restore"))
        compose.onNodeWithText("Select backup to restore").performClick()
        compose.runOnIdle { assertEquals(1, restoreRequests) }
        compose.onNodeWithText(CREATE_PROJECT_MESSAGE).assertExists()

        compose.onNodeWithContentDescription("Project settings").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(modalList).fetchSemanticsNodes().size == 1 }
        compose.onNode(modalList).performScrollToNode(hasText("Developer: Gurucharan S."))
        compose.onNodeWithText("Developer: Gurucharan S.").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Project name", substring = false).performTextInput("First project")
        compose.onNodeWithText("Create Project").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Select project. Current project: First project").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("First project", runBlocking { repository.projects.first().single().name })

        runBlocking {
            val empty = repository.exportRecords()
            for (key in empty.keys().asSequence().toList()) if (empty.opt(key) is JSONArray) empty.put(key, JSONArray())
            repository.replaceRecords(empty)
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(CREATE_PROJECT_MESSAGE).fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Backup and restore").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Create encrypted backup").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText(CREATE_PROJECT_MESSAGE).assertExists()
        assertTrue(runBlocking { repository.projects.first() }.isEmpty())
    }
}
