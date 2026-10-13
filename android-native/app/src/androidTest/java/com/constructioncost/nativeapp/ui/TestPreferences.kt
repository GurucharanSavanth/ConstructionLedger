package com.constructioncost.nativeapp.ui

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.io.File
import java.util.UUID

class TestPreferences : AutoCloseable {
    private val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
    private val directory = File(cache, "preferences-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val job = SupervisorJob()
    val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
        File(directory, "ledger.preferences_pb")
    }

    override fun close() {
        runBlocking { job.cancelAndJoin() }
        check(directory.canonicalFile.parentFile == cache && directory.name.startsWith("preferences-test-"))
        directory.deleteRecursively()
    }
}

class TestPreferencesRule : ExternalResource() {
    lateinit var preferences: TestPreferences
    override fun before() { preferences = TestPreferences() }
    override fun after() { preferences.close() }
}
