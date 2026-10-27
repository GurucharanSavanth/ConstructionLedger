package com.constructioncost.nativeapp.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
internal fun <T> Flow<T>.readLedger(vm: LedgerViewModel): T? = key(this) {
    val value by remember(this, vm) { map<T, T?> { it }.catch { vm.storageFailure(it); emit(null) } }
        .collectAsStateWithLifecycle(null)
    value
}

internal data class LedgerLookup<T>(val value: T)

@Composable
internal fun <T> ledgerLookup(vm: LedgerViewModel, vararg keys: Any?, read: suspend () -> T): LedgerLookup<T>? =
    remember(*keys) { flow { emit(LedgerLookup(read())) } }.readLedger(vm)

internal data class LedgerDate(val today: LocalDate, val zone: ZoneId)

@Composable
internal fun rememberLedgerDate(): LedgerDate {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun now() = LedgerDate(LocalDate.now(), ZoneId.systemDefault())
    var date by remember { mutableStateOf(now()) }
    var clockRevision by remember { mutableIntStateOf(0) }
    fun refresh() { date = now(); clockRevision++ }
    DisposableEffect(context, lifecycle) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    LaunchedEffect(date, clockRevision) {
        val current = ZonedDateTime.now()
        delay(Duration.between(current, current.toLocalDate().plusDays(1).atStartOfDay(current.zone)).toMillis().coerceAtLeast(1))
        date = now()
    }
    return date
}
