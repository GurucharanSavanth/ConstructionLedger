package com.constructioncost.nativeapp.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.util.AtomicFile
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class LedgerRecoveryState {
    NEW_INSTALL,
    HEALTHY_EXISTING_DATABASE,
    DATABASE_OPEN_FAILURE,
    DATABASE_CORRUPTION,
    DATABASE_MIGRATION_FAILURE,
    DATABASE_MISSING_WITH_EXISTING_USER_ARTIFACTS,
    PREFERENCES_UNAVAILABLE,
    RESTORE_IN_PROGRESS,
    RECOVERY_REQUIRED
}

/** Durable recovery state lives outside Room and DataStore. */
class LedgerRecoveryStateStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = appContext.filesDir
    private val recoveryFile = AtomicFile(File(root, RECOVERY_FILE))
    private val installFile = AtomicFile(File(root, INSTALL_FILE))
    private val key = root.absolutePath

    fun state(): LedgerRecoveryState {
        if (key in recoveryPaths || listOf("", ".bak", ".new").any { File(recoveryFile.baseFile.path + it).exists() }) {
            return readRecoveryState()
        }
        return if (installFile.baseFile.exists()) LedgerRecoveryState.HEALTHY_EXISTING_DATABASE else LedgerRecoveryState.NEW_INSTALL
    }

    fun inspectBeforeOpen(): LedgerRecoveryState {
        val current = state()
        if (current in RECOVERY_STATES) return current
        val database = appContext.getDatabasePath(DATABASE_NAME)
        if (database.exists()) return LedgerRecoveryState.HEALTHY_EXISTING_DATABASE
        val companions = listOf("-wal", "-shm", "-journal").any { File(database.path + it).exists() }
        val userArtifacts = root.walkTopDown().any { file ->
            file.isDirectory && file.name in setOf("attachments", "attachment-trash") ||
                file.isFile && !(file.parentFile == root && file.name in PROFILE_CACHE_FILES) &&
                    (!file.toRelativeString(root).replace('\\', '/').startsWith("datastore/") || file.length() > 0) &&
                    file.absolutePath !in setOf(recoveryFile.baseFile.absolutePath, installFile.baseFile.absolutePath) &&
                    (!file.name.endsWith(".bak") || file.name == "$INSTALL_FILE.bak")
        }
        if (installFile.baseFile.exists() || companions || userArtifacts) {
            markRecoveryRequired(
                LedgerRecoveryState.DATABASE_MISSING_WITH_EXISTING_USER_ARTIFACTS,
                "The ledger database is missing while application data remains."
            )
            return LedgerRecoveryState.DATABASE_MISSING_WITH_EXISTING_USER_ARTIFACTS
        }
        return LedgerRecoveryState.NEW_INSTALL
    }

    fun markRecoveryRequired(state: LedgerRecoveryState, detail: String) {
        require(state in RECOVERY_STATES)
        recoveryPaths += key
        try {
            val bytes = JSONObject().put("state", state.name).put("detail", detail.take(2000)).toString().toByteArray()
            val stream = recoveryFile.startWrite()
            try {
                stream.write(bytes)
                recoveryFile.finishWrite(stream)
            } catch (failure: Throwable) {
                recoveryFile.failWrite(stream)
                throw failure
            }
        } catch (_: IOException) {
            // The in-process latch still blocks writes and file cleanup if durable marker storage is unavailable.
        }
    }

    fun markLedgerValidated() {
        val stream = installFile.startWrite()
        try {
            stream.write("ledger-v5-validated".toByteArray())
            installFile.finishWrite(stream)
        } catch (failure: Throwable) {
            installFile.failWrite(stream)
            throw failure
        }
        recoveryFile.delete()
        recoveryPaths.remove(key)
        validatedPaths += key
    }

    fun clearForSafeOpenRetry(): Boolean {
        if (state() !in setOf(
                LedgerRecoveryState.DATABASE_OPEN_FAILURE,
                LedgerRecoveryState.DATABASE_CORRUPTION,
                LedgerRecoveryState.DATABASE_MIGRATION_FAILURE
            )) return false
        recoveryFile.delete()
        recoveryPaths.remove(key)
        validatedPaths.remove(key)
        return !recoveryFile.baseFile.exists() && !File(recoveryFile.baseFile.path + ".bak").exists()
    }

    fun isRecoveryRequired(): Boolean = key in recoveryPaths ||
        listOf("", ".bak", ".new").any { File(recoveryFile.baseFile.path + it).exists() } || state() in RECOVERY_STATES

    fun isLedgerValidated(): Boolean = key in validatedPaths && !isRecoveryRequired()

    fun beginStartup() {
        validatedPaths.remove(key)
    }

    private fun readRecoveryState(): LedgerRecoveryState = try {
        LedgerRecoveryState.valueOf(JSONObject(recoveryFile.baseFile.readText()).optString("state"))
            .takeIf { it in RECOVERY_STATES } ?: LedgerRecoveryState.RECOVERY_REQUIRED
    } catch (_: Exception) {
        LedgerRecoveryState.RECOVERY_REQUIRED
    }

    companion object {
        const val DATABASE_NAME = "ledger.db"
        private const val RECOVERY_FILE = "ledger-recovery-state.json"
        private const val INSTALL_FILE = "ledger-install-state"
        // AndroidX can write these caches before the first ledger is opened.
        private val PROFILE_CACHE_FILES = setOf("profileInstalled", "profileinstaller_profileWrittenFor_lastUpdateTime.dat")
        private val recoveryPaths = ConcurrentHashMap.newKeySet<String>()
        private val validatedPaths = ConcurrentHashMap.newKeySet<String>()
        private val RECOVERY_STATES = setOf(
            LedgerRecoveryState.DATABASE_OPEN_FAILURE,
            LedgerRecoveryState.DATABASE_CORRUPTION,
            LedgerRecoveryState.DATABASE_MIGRATION_FAILURE,
            LedgerRecoveryState.DATABASE_MISSING_WITH_EXISTING_USER_ARTIFACTS,
            LedgerRecoveryState.RESTORE_IN_PROGRESS,
            LedgerRecoveryState.RECOVERY_REQUIRED
        )
    }
}

internal class PreservingSQLiteOpenHelperFactory(
    context: Context,
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()
) : SupportSQLiteOpenHelper.Factory {
    private val recovery = LedgerRecoveryStateStore(context)

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val guarded = object : SupportSQLiteOpenHelper.Callback(callback.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = forward { callback.onConfigure(db) }
            override fun onCreate(db: SupportSQLiteDatabase) = forward { callback.onCreate(db) }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                try {
                    callback.onUpgrade(db, oldVersion, newVersion)
                } catch (failure: Throwable) {
                    recovery.markRecoveryRequired(LedgerRecoveryState.DATABASE_MIGRATION_FAILURE, failure.message ?: "Database migration failed")
                    throw failure
                }
            }
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = callback.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = forward { callback.onOpen(db) }
            override fun onCorruption(db: SupportSQLiteDatabase) {
                recovery.markRecoveryRequired(LedgerRecoveryState.DATABASE_CORRUPTION, "SQLite reported ledger corruption.")
                throw SQLiteDatabaseCorruptException("Ledger database corruption detected; original database files were preserved.")
            }
        }
        val safeConfiguration = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(guarded)
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        val helper = delegate.create(safeConfiguration)
        return object : SupportSQLiteOpenHelper by helper {
            private fun ensureAllowed() {
                if (configuration.name == LedgerRecoveryStateStore.DATABASE_NAME && recovery.isRecoveryRequired() &&
                    recovery.state() != LedgerRecoveryState.RESTORE_IN_PROGRESS) {
                    throw SQLiteException("Ledger recovery is required; database opening is blocked to preserve existing data.")
                }
            }
            override val readableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
                get() { ensureAllowed(); return helper.readableDatabase }
            override val writableDatabase: androidx.sqlite.db.SupportSQLiteDatabase
                get() { ensureAllowed(); return helper.writableDatabase }
        }
    }

    private inline fun forward(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            recovery.markRecoveryRequired(LedgerRecoveryState.DATABASE_OPEN_FAILURE, failure.message ?: "SQLite initialization failed")
            throw failure
        }
    }
}
