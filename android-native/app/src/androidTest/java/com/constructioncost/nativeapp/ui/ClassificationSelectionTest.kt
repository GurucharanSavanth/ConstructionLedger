package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClassificationSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun expensePickerSelectionsSurviveDraftRecreationAndPosting() = selections("Direct cost")
    @Test fun plannedPickerSelectionsSurviveDraftRecreationAndPosting() = selections("Plan")

    private fun selections(kind: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val preferences = TestPreferences()
        val models = ViewModelStore()
        try {
            runBlocking {
                repository.ensureDefaultProject()
                repository.saveClassification(ClassificationInfo("existing-cost", "default-project", "Existing cost", "COST_HEAD"))
            }
            val draft = EntryDraft.fresh("default-project", kind).with("description", "Picker regression")
                .with("amount", "50")
            val handle = SavedStateHandle(mapOf("project" to "default-project", "draft" to draft.json()))
            var model by mutableStateOf(LedgerViewModel(context, repository, handle, preferences.store))
            models.put("original", model)
            compose.setContent {
                val state by model.state.collectAsState()
                val classes by repository.classifications("default-project").collectAsState(emptyList())
                Column {
                    listOf("COST" to "COST_HEAD", "WORK" to "STAGE", "PLACE" to "PLACE").forEach { (dimension, categoryKind) ->
                        ClassificationChoice(model, "default-project", dimension, state.draft.get(dimension),
                            classes.filter { it.kind == categoryKind }.map { it.id to it.name }, classes, categoryKind,
                            choose = { model.field(dimension, it); model.field("classesChanged", "true") })
                    }
                }
            }
            compose.waitUntil(10_000) { model.state.value.ready }
            val selected = mutableMapOf<String, String>()
            listOf("COST", "WORK", "PLACE").forEach { dimension ->
                compose.onNodeWithContentDescription("$dimension: None").performClick()
                compose.onNodeWithText("Create category…").performClick()
                if (dimension == "COST") {
                    compose.onNodeWithText("Category name").performTextInput("Existing cost")
                    compose.onNodeWithText("Create and select").performClick()
                    compose.waitUntil(10_000) { !model.state.value.saving && model.state.value.actionError != null }
                    assertEquals("", model.state.value.draft.get("COST"))
                    compose.onNodeWithText("Category name").assertTextContains("Existing cost")
                    compose.onNodeWithText("Category name").performTextReplacement("")
                }
                compose.onNodeWithText("Category name").performTextInput("New $dimension")
                compose.onNodeWithText("Create and select").performClick()
                compose.waitUntil(10_000) { !model.state.value.saving && model.state.value.draft.get(dimension).isNotBlank() }
                selected[dimension] = model.state.value.draft.get(dimension)
                compose.onNodeWithContentDescription("$dimension: New $dimension").assertExists()
            }
            runBlocking { withTimeout(10_000) { preferences.store.data.first { it.asMap().values.any { value ->
                value is String && value.contains(selected.getValue("PLACE"))
            } } } }
            compose.runOnIdle {
                models.clear()
                model = LedgerViewModel(context, repository, SavedStateHandle(), preferences.store)
                models.put("recreated", model)
            }
            compose.waitUntil(10_000) { model.state.value.ready }
            selected.forEach { (dimension, id) -> assertEquals(id, model.state.value.draft.get(dimension)) }
            compose.runOnIdle { model.saveDraft {} }
            compose.waitUntil(10_000) { !model.state.value.saving }
            assertEquals(null, model.state.value.actionError)
            if (kind == "Plan") {
                val saved = runBlocking { repository.plannedClassifications("INSTALMENT", draft.id) }
                assertEquals(selected.values.toSet(), saved.toSet())
                assertEquals(5_000L, runBlocking { repository.instalments("default-project").first().single().amountPaise })
            } else {
                val saved = runBlocking { repository.expense(draft.id)!! }
                assertEquals(5_000L, saved.amountPaise)
                assertEquals(selected.values.toSet(), saved.classifications.map { it.classificationId }.toSet())
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
            preferences.close()
            repository.close()
        }
    }
}
