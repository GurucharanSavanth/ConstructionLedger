package com.constructioncost.nativeapp.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ApplicationContextViewModelTest {
    @Test fun initializationRetryUsesApplicationContextAfterTheCallerIsReleased() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "viewmodel-context-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val durableContext = object : Application() {
            init { attachBaseContext(base.applicationContext) }
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { check(mkdirs() || isDirectory) }
        }
        var callerReleased = false
        val caller = object : ContextWrapper(durableContext) {
            override fun getApplicationContext(): Context {
                check(!callerReleased) { "The caller's context is no longer available" }
                return durableContext
            }
        }
        val repository = LedgerRepository.inMemory(durableContext)
        val preferences = TestPreferences()
        val models = ViewModelStore()
        try {
            repository.saveProject(ProjectInfo("context-project", "Context lifetime fixture"))
            val original = EntryDraft.fresh("context-project").with("description", "Retain entered invoice")
                .with("amount", "123.45").with("note", "Retry preserves every field")
            val model = LedgerViewModel(caller, repository,
                SavedStateHandle(mapOf("project" to "context-project", "draft" to original.json())), preferences.store)
            models.put("context-lifetime", model)
            withTimeout(5_000) { model.state.first { it.ready } }
            var retryFailure: Throwable? = null
            instrumentation.runOnMainSync {
                callerReleased = true
                retryFailure = runCatching { model.retryInitialization() }.exceptionOrNull()
            }
            assertNull("The retained ViewModel must not call back into its released caller context", retryFailure)
            val retried = withTimeout(5_000) { model.state.first { it.ready } }
            assertEquals("context-project", retried.projectId)
            assertEquals(original.fields, retried.draft.fields)
            assertNull(retried.error)
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            preferences.close()
            repository.close()
            check(directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
