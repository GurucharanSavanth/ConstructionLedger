package com.constructioncost.nativeapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotSame
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class UpdateRetryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedCheckRetainsRetryAfterRecreationAndChecksAgain() {
        val restoration = StateRestorationTester(compose)
        var attempts = 0
        restoration.setContent {
            MaterialTheme {
                Column { AppUpdates(manual = true, checkLatest = { attempts++; throw IOException("deterministic offline fixture") }) }
            }
        }
        compose.onNodeWithText("Check for updates").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Retry").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Retry").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Retry").assertExists()
        compose.runOnIdle { assertEquals(2, attempts) }
    }
    @Test fun failedCheckRetainsRetryAcrossActualActivityRecreation() {
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val attempts = AtomicInteger()
        fun install(activity: ComponentActivity) {
            activity.setContent {
                MaterialTheme {
                    Column { AppUpdates(manual = true, checkLatest = {
                        attempts.incrementAndGet()
                        throw IOException("deterministic recreation fixture")
                    }) }
                }
            }
        }
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is ComponentActivity && activity.javaClass == ComponentActivity::class.java) install(activity)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            val before = compose.activity
            compose.runOnUiThread { install(before) }
            compose.onNodeWithText("Check for updates").performClick()
            compose.onNodeWithText("Retry").assertExists()
            compose.runOnIdle { assertEquals("Initial manual check", 1, attempts.get()) }
            compose.activityRule.scenario.recreate()
            assertNotSame(before, compose.activity)
            compose.runOnIdle { assertEquals("Recreation must not check automatically", 1, attempts.get()) }
            compose.onNodeWithText("Retry").assertExists().performClick()
            compose.onNodeWithText("Retry").assertExists()
            compose.runOnIdle { assertEquals(2, attempts.get()) }
        } finally { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }

}
