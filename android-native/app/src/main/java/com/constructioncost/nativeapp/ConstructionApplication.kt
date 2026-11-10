package com.constructioncost.nativeapp

import android.app.Application
import android.util.Log
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

class ConstructionApplication : Application() {
    val recoveryState by lazy { LedgerRecoveryStateStore(this) }
    val operations by lazy { LedgerOperations(this) }
    private val repositoryLock = Any()
    internal val ledgerLifecycleMutex = Mutex()
    private var activeRepository: LedgerRepository? = null
    val repository: LedgerRepository get() = synchronized(repositoryLock) {
        activeRepository ?: LedgerRepository(this).also { activeRepository = it }
    }

    fun closeAndResetRepository() = synchronized(repositoryLock) {
        activeRepository?.close()
        activeRepository = null
    }
    override fun onCreate() {
        super.onCreate()
        val cutoff = System.currentTimeMillis()
        CoroutineScope(Dispatchers.IO).launch {
            try { DocumentPolicy.cleanupArchiveStaging(cacheDir, cutoff) }
            catch (failure: Exception) { Log.w("BuildLedger", "Interrupted archive cleanup failed", failure) }
        }
    }
}
