package com.constructioncost.nativeapp

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.ui.LedgerViewModel
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.CatalogSeeder

@RunWith(AndroidJUnit4::class)
class ActivityRecreationTest {
    private fun startupEvidence(instrumentation: android.app.Instrumentation): String {
        val context = instrumentation.targetContext
        val files = context.filesDir.walkTopDown().map { it.relativeTo(context.filesDir).path }.toList()
        val state = (context.applicationContext as ConstructionApplication).recoveryState.state()
        val marker = File(context.filesDir, "ledger-recovery-state.json").takeIf { it.isFile }?.readText()
        return "state=$state, marker=$marker, files=$files"
    }

    @Test fun defaultCatalogSeedsRealRootNodesWithoutLiteralNullParents() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        try {
            repository.saveProject(ProjectInfo("catalog-test", "Catalog house"))
            CatalogSeeder(context, repository).seed("catalog-test")
            val rows = repository.classifications("catalog-test").first()
            assertTrue(rows.count { it.kind.startsWith("COST_") } == 236)
            assertTrue(rows.any { it.id == "catalog-test:cost-01" && it.parentId == null })
            assertTrue(rows.any { it.id == "catalog-test:cost-01-01" && it.parentId == "catalog-test:cost-01" })
            assertFalse(rows.any { it.parentId == "catalog-test:null" })
            CatalogSeeder(context, repository).seed("catalog-test")
            org.junit.Assert.assertEquals(rows.size, repository.classifications("catalog-test").first().size)
        } finally { repository.close() }
    }
    @Test fun coldStartupPreservesCapturedBytesAndRemovesOnlyEmptyStaging() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "exports").apply { check(mkdirs() || isDirectory) }
        val orphan = File(directory, "photo-${UUID.randomUUID()}.jpg").apply { writeText("interrupted capture"); check(setLastModified(1000)) }
        val migrated = File(instrumentation.targetContext.filesDir, "camera-captures/${orphan.name}")
        val empty = File(directory, "photo-${UUID.randomUUID()}.jpg").apply { createNewFile(); check(setLastModified(1000)) }
        val retained = File(directory, "receipt-${UUID.randomUUID()}.txt").apply { writeText("unrelated export") }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                instrumentation.waitForIdleSync()
                val validated = withTimeoutOrNull(30000) {
                    while (!(instrumentation.targetContext.applicationContext as ConstructionApplication).recoveryState.isLedgerValidated()) delay(25)
                    true
                }
                assertTrue("Startup did not validate its ledger: ${startupEvidence(instrumentation)}", validated == true)
                withTimeout(10000) { while (empty.exists()) delay(25) }
                assertEquals("interrupted capture", (if (migrated.exists()) migrated else orphan).readText())
                assertTrue(retained.exists())
            }
        } finally { orphan.delete(); migrated.delete(); empty.delete(); retained.delete() }
    }
    @Test fun retainedViewModelAndPlatformActionsShareRoomAfterRecreation() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val shared = (instrumentation.targetContext.applicationContext as ConstructionApplication).repository
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            val validated = withTimeoutOrNull(30000) {
                while (!((instrumentation.targetContext.applicationContext as ConstructionApplication).recoveryState.isLedgerValidated())) delay(25)
                true
            }
            assertTrue("Startup did not validate its ledger: ${startupEvidence(instrumentation)}", validated == true)
            lateinit var original: LedgerViewModel
            scenario.onActivity { activity ->
                original = ViewModelProvider(activity)[LedgerViewModel::class.java]
                assertSame(shared, activity.repository)
                assertSame(shared, original.repository)
            }
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val retained = ViewModelProvider(activity)[LedgerViewModel::class.java]
                assertSame(original, retained)
                assertSame(shared, activity.repository)
                assertSame(shared, retained.repository)
            }
        }
    }
}
