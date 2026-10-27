package com.constructioncost.nativeapp.platform

import android.os.SystemClock
import android.util.Log

internal class OperationProgress(private val operation: String, private val notify: (String) -> Unit) {
    private val timings = linkedMapOf<String, Long>()
    private var phase: String? = null
    private var started = 0L

    fun phase(name: String) {
        recordPhase()
        phase = name
        started = SystemClock.elapsedRealtime()
        notify(name)
    }

    fun count(completed: Int, total: Int? = null) {
        notify("$phase: $completed${total?.let { " of $it" }.orEmpty()}")
    }

    fun finish(): Map<String, Long> {
        recordPhase()
        phase = null
        return timings.toMap()
    }

    private fun recordPhase() {
        phase?.let { name ->
            val elapsed = SystemClock.elapsedRealtime() - started
            timings[name] = (timings[name] ?: 0) + elapsed
            Log.i("LedgerOperations", "$operation / $name: ${elapsed}ms")
        }
    }
}
