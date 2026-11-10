package com.constructioncost.nativeapp.data.local

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.ProjectInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerRecoveryStateStoreTest {
    private fun isolatedContext(base: Context, sandbox: File) = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
        override fun getCacheDir() = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        override fun getDatabasePath(name: String) = File(sandbox, "databases/$name").apply { parentFile?.mkdirs() }
    }

    @Test fun profileCachesPermitFreshStartupButUnknownOriginalsStillRequireRecovery() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "recovery-profile-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = isolatedContext(base, sandbox)
        try {
            val cache = File(context.filesDir, "profileInstalled").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
            val skip = File(context.filesDir, "profileinstaller_profileWrittenFor_lastUpdateTime.dat").apply { createNewFile() }
            val state = LedgerRecoveryStateStore(context)
            assertEquals(LedgerRecoveryState.NEW_INSTALL, state.inspectBeforeOpen())
            assertArrayEquals(byteArrayOf(0, 1, 2, 3), cache.readBytes())
            assertTrue(skip.isFile)

            val original = File(context.filesDir, "attachments/profileInstalled").apply {
                parentFile?.mkdirs(); writeBytes(byteArrayOf(42, 0, -1))
            }
            assertEquals(LedgerRecoveryState.DATABASE_MISSING_WITH_EXISTING_USER_ARTIFACTS, state.inspectBeforeOpen())
            assertArrayEquals(byteArrayOf(42, 0, -1), original.readBytes())
        } finally { sandbox.deleteRecursively() }
    }

    @Test fun interruptedMarkersAndNonemptyPreferencesPreventFalseNewInstall() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (artifact in listOf("ledger-install-state.bak", "ledger-recovery-state.json.new", "datastore/ledger_ui.preferences_pb")) {
            val sandbox = File(base.cacheDir, "recovery-artifact-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val context = isolatedContext(base, sandbox)
            try {
                val original = File(context.filesDir, artifact).apply { parentFile?.mkdirs(); writeText("prior user state") }
                val state = LedgerRecoveryStateStore(context)
                assertNotEquals(artifact, LedgerRecoveryState.NEW_INSTALL, state.inspectBeforeOpen())
                assertTrue(artifact, state.isRecoveryRequired())
                assertEquals("prior user state", original.readText())
            } finally { sandbox.deleteRecursively() }
        }
    }

    @Test fun sameRepositoryCanRetryAfterDisposableCorruptDatabaseIsRepairedWhileClosed() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "recovery-retry-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = isolatedContext(base, sandbox)
        val healthy = LedgerRepository(context)
        try {
            healthy.validateStorage()
            healthy.saveProject(ProjectInfo("retry-project", "Preserved house"))
        } finally { healthy.close() }
        val file = context.getDatabasePath(LedgerRecoveryStateStore.DATABASE_NAME)
        val closedSnapshot = file.readBytes()
        file.writeText("deliberately damaged disposable database")
        val retry = LedgerRepository(context)
        try {
            assertNotNull(runCatching { retry.validateStorage() }.exceptionOrNull())
            assertTrue(LedgerRecoveryStateStore(context).isRecoveryRequired())
            file.writeBytes(closedSnapshot)
            assertTrue(LedgerRecoveryStateStore(context).clearForSafeOpenRetry())
            retry.validateStorage()
            assertEquals("Preserved house", retry.projects.first().single().name)
        } finally {
            retry.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }

    @Test fun freshDatabaseValidatesDurabilityBeforeAnyFinancialWrite() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "recovery-fresh-db-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = isolatedContext(base, sandbox)
        val repository = LedgerRepository(context)
        try {
            repository.validateStorage()
        } finally {
            repository.close()
            assertTrue(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }

    @Test fun corruptionCallbackMarksRecoveryAndNeverDelegatesDatabaseDeletion() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "recovery-callback-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = isolatedContext(base, sandbox)
        val database = context.getDatabasePath(LedgerRecoveryStateStore.DATABASE_NAME)
        val family = listOf(database, File(database.path + "-wal"), File(database.path + "-shm"))
        val expected = family.mapIndexed { index, file -> ByteArray(32) { (index + 1).toByte() }.also(file::writeBytes) }
        val original = object : SupportSQLiteOpenHelper.Callback(5) {
            var corruptionCalls = 0
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            override fun onCorruption(db: SupportSQLiteDatabase) { corruptionCalls++ }
        }
        var wrapped: SupportSQLiteOpenHelper.Callback? = null
        var safeConfiguration: SupportSQLiteOpenHelper.Configuration? = null
        val factory = PreservingSQLiteOpenHelperFactory(context, SupportSQLiteOpenHelper.Factory { configuration ->
            safeConfiguration = configuration
            wrapped = configuration.callback
            FrameworkSQLiteOpenHelperFactory().create(configuration)
        })
        val helper = factory.create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(LedgerRecoveryStateStore.DATABASE_NAME).callback(original).build())
        try {
            assertNotNull(wrapped)
            assertFalse(safeConfiguration!!.allowDataLossOnRecovery)
            val fakeDatabase = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java)) { _, method, _ ->
                when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            } as SupportSQLiteDatabase
            assertThrows(SQLiteDatabaseCorruptException::class.java) { wrapped!!.onCorruption(fakeDatabase) }
            assertEquals(0, original.corruptionCalls)
            assertEquals(LedgerRecoveryState.DATABASE_CORRUPTION, LedgerRecoveryStateStore(context).state())
            assertThrows(SQLiteException::class.java) { helper.writableDatabase }
            family.zip(expected).forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        } finally {
            helper.close()
            assertTrue(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }

    @Test fun missingDatabaseWithOriginalAttachmentsIsRecoveryNotNewInstall() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "recovery-missing-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = isolatedContext(base, sandbox)
        try {
            val original = File(context.filesDir, "attachments/receipt.pdf").apply {
                parentFile?.mkdirs()
                writeText("synthetic receipt bytes")
            }
            val state = LedgerRecoveryStateStore(context)
            assertEquals(LedgerRecoveryState.DATABASE_MISSING_WITH_EXISTING_USER_ARTIFACTS, state.inspectBeforeOpen())
            assertTrue(state.isRecoveryRequired())
            assertEquals("synthetic receipt bytes", original.readText())
        } finally {
            assertTrue(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }

    @Test fun emptyPreferencesDirectoryDoesNotTurnFreshInstallIntoRecovery() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "recovery-new-install-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = isolatedContext(base, sandbox)
        try {
            File(context.filesDir, "datastore/ledger_ui.preferences_pb").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf())
            }
            assertEquals(LedgerRecoveryState.NEW_INSTALL, LedgerRecoveryStateStore(context).inspectBeforeOpen())
        } finally {
            assertTrue(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }

    @Test fun unreadableDisposableLedgerIsNotRecreatedAndAttachmentsRemainUnchanged() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "recovery-corrupt-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = isolatedContext(base, sandbox)
        val attachments = File(context.filesDir, "attachments").apply { check(mkdirs() || isDirectory) }
        val original = File(attachments, "receipt.pdf").apply { writeText("synthetic attachment") }
        val attachmentBytes = original.readBytes()
        val healthy = LedgerRepository(context)
        try {
            healthy.saveProject(ProjectInfo("recovery-fixture", "Disposable corruption fixture"))
            healthy.validateStorage()
        } finally { healthy.close() }

        val database = context.getDatabasePath(LedgerRecoveryStateStore.DATABASE_NAME)
        val deliberatelyCorrupt = "synthetic damaged SQLite bytes".toByteArray()
        database.writeBytes(deliberatelyCorrupt)
        val broken = LedgerRepository(context)
        try {
            val failure = runCatching { broken.validateStorage() }.exceptionOrNull()
            assertNotNull("Corrupt database must fail closed, not open as a new ledger", failure)
            assertTrue(LedgerRecoveryStateStore(context).isRecoveryRequired())
            assertArrayEquals(deliberatelyCorrupt, database.readBytes())
            assertArrayEquals(attachmentBytes, original.readBytes())
        } finally {
            broken.close()
            assertTrue(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }
}
