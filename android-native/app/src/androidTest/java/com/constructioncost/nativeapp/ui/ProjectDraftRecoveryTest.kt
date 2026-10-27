package com.constructioncost.nativeapp.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ProjectDraftRecoveryTest {
    @Test fun projectSelectionDoesNotOwnPersistedDraftAcrossViewModelRestart() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LedgerRepository.inMemory(instrumentation.targetContext)
        val preferences = TestPreferences()
        val preferenceEvents = ConcurrentLinkedQueue<String>()
        val updateSequence = AtomicInteger()
        val observedPreferences = object : DataStore<Preferences> by preferences.store {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                val update = updateSequence.incrementAndGet()
                preferenceEvents += "${System.nanoTime()} update $update started; job=${currentCoroutineContext()[Job]}"
                try {
                    return preferences.store.updateData { stored ->
                        preferenceEvents += "${System.nanoTime()} update $update transform started; data=${stored.asMap()}"
                        transform(stored).also { changed ->
                            preferenceEvents += "${System.nanoTime()} update $update transform finished; data=${changed.asMap()}"
                        }
                    }.also { preferenceEvents += "${System.nanoTime()} update $update completed; data=${it.asMap()}" }
                } catch (failure: Throwable) {
                    preferenceEvents += "${System.nanoTime()} update $update failed: ${failure.javaClass.simpleName}: ${failure.message}"
                    throw failure
                }
            }
        }
        val models = ViewModelStore()
        val original = EntryDraft.fresh("a", "Plan").with("description", "Roof inspection")
            .with("amount", "123.45").with("date", "2032-02-29")
            .with("note", "Keep all entered fields").with("vendor", "supplier-a")
        var stage = "first ViewModel initialization"
        var currentModel: LedgerViewModel? = null
        var persisted = "Preferences have not emitted"
        try {
            repository.saveProject(ProjectInfo("a", "Project A"))
            repository.saveProject(ProjectInfo("b", "Project B"))
            val first = LedgerViewModel(instrumentation.targetContext, repository,
                SavedStateHandle(mapOf("project" to "a", "draft" to original.json())), observedPreferences)
            currentModel = first
            models.put("first", first)
            withTimeout(5_000) { first.state.first { it.ready } }
            instrumentation.runOnMainSync {
                first.field("note", "Persist this exact note")
                first.selectProject("b")
            }
            val entered = first.state.value.draft
            val draftKey = stringPreferencesKey("entry_draft")
            stage = "persisting the A draft and selected B project"
            withTimeout(5_000) { preferences.store.data.first {
                persisted = it.asMap().toString()
                it[draftKey] == entered.json() && it[stringPreferencesKey("project")] == "b"
            } }
            instrumentation.runOnMainSync { models.clear() }
            // An empty SavedStateHandle exercises durable preferences after process state is lost.
            val restarted = LedgerViewModel(instrumentation.targetContext, repository, SavedStateHandle(), observedPreferences)
            currentModel = restarted
            models.put("restarted", restarted)
            instrumentation.runOnMainSync {
                assertTrue("The restarted ViewModel must have a live owner scope",
                    restarted.viewModelScope.coroutineContext[Job]?.isActive == true)
            }
            stage = "restarted ViewModel initialization"
            val restored = withTimeout(5_000) { restarted.state.first { it.ready } }
            assertEquals("b", restored.projectId)
            assertEquals("a", restored.draft.projectId)
            assertEquals(entered.fields, restored.draft.fields)
            instrumentation.runOnMainSync {
                assertTrue("Draft discard must run in a live ViewModel scope",
                    restarted.viewModelScope.coroutineContext[Job]?.isActive == true)
                preferenceEvents += "discard requested; updates=${updateSequence.get()}"
                restarted.resetDraft()
                preferenceEvents += "discard returned; updates=${updateSequence.get()}; draft=${restarted.state.value.draft.json()}"
            }
            val cleared = restarted.state.value.draft
            assertEquals("b", cleared.projectId)
            assertFalse(cleared.hasContent)
            stage = "persisting the explicit draft discard ${cleared.json()}"
            withTimeout(5_000) { preferences.store.data.first {
                persisted = it.asMap().toString()
                it[draftKey] == cleared.json()
            } }
        } catch (failure: TimeoutCancellationException) {
            val scope = currentModel?.viewModelScope?.coroutineContext?.get(Job)
            throw AssertionError("Draft recovery timed out during $stage; state=${currentModel?.state?.value}; persisted=$persisted; " +
                "scope=$scope; children=${scope?.children?.toList()}; preferenceEvents=$preferenceEvents", failure)
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            preferences.close()
            repository.close()
        }
    }

    @Test fun deletedLastProjectRetainsOrphanDraftUntilExplicitMoveOrDiscard() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LedgerRepository.inMemory(instrumentation.targetContext)
        val preferences = TestPreferences()
        val models = ViewModelStore()
        val original = EntryDraft.fresh("a", "Plan").with("description", "Retained plan")
            .with("amount", "123.45").with("date", "2032-02-29").with("note", "Original input")
        try {
            repository.saveProject(ProjectInfo("a", "Project A"))
            val first = LedgerViewModel(instrumentation.targetContext, repository,
                SavedStateHandle(mapOf("project" to "a", "draft" to original.json())), preferences.store)
            models.put("first", first)
            withTimeout(5_000) { first.state.first { it.ready } }
            val records = repository.exportRecords()
            records.put("projects", org.json.JSONArray())
            repository.replaceRecords(records)
            val orphaned = withTimeout(5_000) { first.state.first { it.projectId.isBlank() } }
            assertFalse(orphaned.hasActiveProject)
            assertEquals(original, orphaned.draft)
            val draftKey = stringPreferencesKey("entry_draft")
            withTimeout(5_000) { preferences.store.data.first { it[draftKey] == original.json() } }
            instrumentation.runOnMainSync { models.clear() }
            val restarted = LedgerViewModel(instrumentation.targetContext, repository, SavedStateHandle(), preferences.store)
            models.put("restarted", restarted)
            assertEquals(original, withTimeout(5_000) { restarted.state.first { it.ready } }.draft)
            instrumentation.runOnMainSync {
                restarted.openNewEntry("Payment") { fail("An empty ledger must not open a project entry") }
                assertEquals(CREATE_PROJECT_MESSAGE, restarted.state.value.actionError)
                restarted.dismissActionError()
            }
            instrumentation.runOnMainSync { restarted.saveDraft { fail("A deleted project must reject a save") } }
            val rejected = withTimeout(5_000) { restarted.state.first { !it.saving && it.actionError != null } }
            assertEquals(original, rejected.draft)
            assertEquals(CREATE_PROJECT_MESSAGE, rejected.actionError)
            repository.saveProject(ProjectInfo("b", "Project B"))
            withTimeout(5_000) { restarted.state.first { it.projectId == "b" } }
            assertTrue(restarted.state.value.hasActiveProject)
            assertEquals(original, restarted.state.value.draft)
            instrumentation.runOnMainSync {
                restarted.selectProject("a")
                assertEquals("b", restarted.state.value.projectId)
                assertEquals(original, restarted.state.value.draft)
                restarted.reassignDraftToCurrentProject()
            }
            val moved = restarted.state.value.draft
            assertEquals("b", moved.projectId)
            assertEquals("Original input", moved.get("note"))
            assertEquals("123.45", moved.get("amount"))
            assertNotEquals(original.id, moved.id)
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            preferences.close()
            repository.close()
        }
    }

    @Test fun calendarDateUpdatesEmptyPlanAndProtectsPopulatedPlan() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LedgerRepository.inMemory(instrumentation.targetContext)
        val preferences = TestPreferences()
        val models = ViewModelStore()
        try {
            repository.saveProject(ProjectInfo("a", "Project A"))
            val empty = EntryDraft.fresh("a", "Plan")
            val model = LedgerViewModel(instrumentation.targetContext, repository,
                SavedStateHandle(mapOf("project" to "a", "draft" to empty.json())), preferences.store)
            models.put("plan", model)
            withTimeout(5_000) { model.state.first { it.ready } }
            var navigations = 0
            instrumentation.runOnMainSync {
                model.openNewEntry("Plan", "2032-02-29") { navigations++ }
                assertEquals("2032-02-29", model.state.value.draft.date)
                assertEquals(empty.id, model.state.value.draft.id)
                assertFalse(model.state.value.pendingDraftReplacement)
                model.field("description", "Keep this plan")
                val populated = model.state.value.draft
                model.openNewEntry("Plan", "2033-01-01") { navigations++ }
                assertTrue(model.state.value.pendingDraftReplacement)
                assertEquals(populated, model.state.value.draft)
                model.cancelDraftReplacement()
                assertEquals(populated, model.state.value.draft)
                model.openNewEntry("Plan", "2033-01-01") { navigations++ }
                model.confirmDraftReplacement()
                assertEquals("2033-01-01", model.state.value.draft.date)
                assertEquals("", model.state.value.draft.get("description"))
                model.resetDraft("Plan")
                model.openNewEntry("Plan", LocalDate.now().toString()) { navigations++ }
                assertEquals(LocalDate.now().toString(), model.state.value.draft.date)
                assertFalse(model.state.value.pendingDraftReplacement)
                model.field("description", "Today's plan")
                val today = model.state.value.draft
                model.openNewEntry("Plan", LocalDate.now().toString()) { navigations++ }
                assertEquals(today, model.state.value.draft)
                assertEquals(4, navigations)
            }
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            preferences.close()
            repository.close()
        }
    }
}
