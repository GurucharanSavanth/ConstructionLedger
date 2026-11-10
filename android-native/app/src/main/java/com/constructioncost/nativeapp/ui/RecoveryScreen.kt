package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.constructioncost.nativeapp.data.local.LedgerRecoveryState

@Composable
internal fun StartupScreen(message: String = "Checking local ledger integrity") {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(message, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun RecoveryScreen(
    state: LedgerRecoveryState,
    detail: String?,
    onRetry: () -> Unit,
    onRestore: (String?) -> Unit,
    onDiagnostic: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val password = remember { mutableStateOf("") }
        Text("Your saved data needs attention", style = MaterialTheme.typography.headlineSmall)
        Text("BuildLedger could not safely load the local ledger. Existing database and document files are being preserved. Do not clear app storage.")
        Text(detail?.takeIf { it.isNotBlank() } ?: state.name.replace('_', ' '), color = MaterialTheme.colorScheme.error)
        OutlinedTextField(password.value, { password.value = it }, label = { Text("Backup password, if encrypted") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { onRestore(password.value.takeIf { it.isNotEmpty() }) }, modifier = Modifier.fillMaxWidth()) { Text("Select backup to restore") }
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
        OutlinedButton(onClick = onDiagnostic, modifier = Modifier.fillMaxWidth()) { Text("Export diagnostic information") }
        Text("Recovery instructions", style = MaterialTheme.typography.titleMedium)
        Text("Keep this installation and any external backups. Do not clear app storage or uninstall. Retry after resolving a storage problem, or select a known-good backup. Restoring preserves existing files for another recovery attempt. Recovery is not guaranteed.")
    }
}
