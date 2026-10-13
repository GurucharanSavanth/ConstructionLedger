package com.constructioncost.nativeapp.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.constructioncost.nativeapp.domain.ClassificationInfo
import java.time.LocalDate

@Composable
internal fun Field(label: String, value: String, change: (String) -> Unit, numeric: Boolean = false, multiline: Boolean = false, password: Boolean = false, enabled: Boolean = true) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None)
}

@Composable
internal fun DateField(label: String, value: String, change: (String) -> Unit) {
    val context = LocalContext.current
    Field("$label (YYYY-MM-DD)", value, change)
    TextButton(onClick = {
        val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day).toString()) },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose $label") }
}

@Composable
internal fun Section(title: String, expandedInitially: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(expandedInitially) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ expanded = !expanded }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("$title · ${if (expanded) "Hide" else "Show"}")
            }
            if (expanded) content()
        }
    }
}

@Composable
internal fun Choice(label: String, selected: String?, options: List<Pair<String, String>>, choose: (String) -> Unit, allowNone: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected?.takeIf { it.isNotBlank() } ?: "None"
    OutlinedButton({ open = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$label: $current") }
    if (open) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("Choose $label") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Search $label", query, { query = it })
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    if (allowNone) item { TextButton({ choose(""); open = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("None / unclassified") } }
                    items(options.filter { it.second.contains(query, true) }, key = { it.first }) { (id, name) ->
                        TextButton({ choose(id); open = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(name) }
                    }
                }
            }
        }, confirmButton = { TextButton({ open = false }) { Text("Close") } })
    }
}

@Composable
internal fun Toggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { change(!checked) }, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(vertical = 12.dp))
        Switch(checked, change)
    }
}

@Composable
internal fun RecordCard(title: String, subtitle: String, amount: String? = null, click: (() -> Unit)? = null) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().then(if (click != null) Modifier.clickable(onClick = click) else Modifier)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (amount != null) Text(amount, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun Title(text: String) { Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall) }

@Composable
internal fun Confirm(title: String, explanation: String, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(explanation) },
        confirmButton = { TextButton({ dismiss(); confirm() }) { Text("Confirm") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

internal fun classificationPath(node: ClassificationInfo, nodes: List<ClassificationInfo>): String {
    val labels = mutableListOf(node.name)
    val visited = mutableSetOf(node.id)
    var parent = node.parentId
    while (parent != null && visited.add(parent)) {
        val ancestor = nodes.firstOrNull { it.id == parent } ?: break
        labels.add(ancestor.name); parent = ancestor.parentId
    }
    return labels.asReversed().joinToString(" / ")
}

internal fun kindDimension(kind: String): String = when (kind) {
    "STAGE", "SUBSTAGE", "MATERIAL" -> "WORK"
    "PLACE" -> "PLACE"
    else -> "COST"
}
