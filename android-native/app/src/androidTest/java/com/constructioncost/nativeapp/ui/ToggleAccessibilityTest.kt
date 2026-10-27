package com.constructioncost.nativeapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ToggleAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toggleHasOneLabeledStatefulActionAndChangesOnce() {
        val checked = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            MaterialTheme { Toggle("Local schedule reminders", checked.value, { calls++; checked.value = it }) }
        }
        compose.onNodeWithText("Local schedule reminders").assertIsOff()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNodeWithText("Local schedule reminders").performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithText("Local schedule reminders").performClick().assertIsOff()
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
