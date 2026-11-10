package com.constructioncost.nativeapp.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import com.constructioncost.nativeapp.domain.ClassificationInfo
import java.time.LocalDate

@Composable
internal fun Field(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier,
    numeric: Boolean = false, multiline: Boolean = false, password: Boolean = false, enabled: Boolean = true, showLabel: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(value, change, modifier.fillMaxWidth().then(if(showLabel) Modifier else Modifier.semantics { contentDescription = label }), enabled = enabled,
        singleLine = !multiline, minLines = if (multiline) 3 else 1,
        interactionSource = interaction, textStyle = MaterialTheme.typography.bodyLarge.copy(color = WebColors.ink),
        cursorBrush = SolidColor(WebColors.blue),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { input -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if(showLabel) Text(label, color = WebColors.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Box(Modifier.fillMaxWidth().heightIn(min = if(multiline) 94.dp else 48.dp)
                .border(1.dp, if(focused) WebColors.blue else androidx.compose.ui.graphics.Color(0xFFCBD6E0), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.TopStart) {
                if(!showLabel && value.isEmpty()) Text(label,color=WebColors.muted,style=MaterialTheme.typography.bodyLarge)
                input()
            }
        } })
}

@Composable
internal fun DateField(label: String, value: String, change: (String) -> Unit, compact: Boolean = false) {
    val context = LocalContext.current
    if(compact) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label,fontSize=13.sp,color=WebColors.muted)
            Box {
                Field("yyyy-MM-dd",value,change,showLabel=false)
                IconButton(onClick = {
                    val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
                    DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day).toString()) },
                        date.year, date.monthValue - 1, date.dayOfMonth).show()
                },modifier=Modifier.align(Alignment.CenterEnd).size(42.dp)) { WebIcon("calendar",Modifier.size(18.dp).semantics { contentDescription="Choose $label" }) }
            }
        }
        return
    }
    Column {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
    Box(Modifier.weight(1f)) { Field(label, value, change) }
    IconButton(onClick = {
        val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day).toString()) },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
    }, modifier = Modifier.heightIn(min = 48.dp)) { WebIcon("calendar", Modifier.semantics { contentDescription = "Choose $label" }) }
    }
    }
}

@Composable
internal fun Section(title: String, expandedInitially: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(expandedInitially) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton({ expanded = !expanded }, Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(0.dp)) {
                Text("${if(expanded) "▾" else "▸"} $title", Modifier.weight(1f))
            }
            if (expanded) content()
    }
}

@Composable
internal fun Choice(label: String, selected: String?, options: List<Pair<String, String>>, choose: (String) -> Unit, modifier: Modifier = Modifier,
    allowNone: Boolean = true, showLabel: Boolean = true, emptyLabel: String = "None", onCreate: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected?.takeIf { it.isNotBlank() } ?: emptyLabel
    Column(modifier,verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if(showLabel) Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        OutlinedButton({ open = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $current" }, shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFCBD6E0)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = WebColors.ink)) {
            Text(current, Modifier.weight(1f), fontWeight = FontWeight.Normal); Text("⌄", color = WebColors.muted)
        }
    }
    if (open) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("Choose $label") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Search $label", query, { query = it })
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    if (allowNone) item { TextButton({ choose(""); open = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("None / unclassified") } }
                    if (onCreate != null) item { TextButton({ open = false; onCreate() }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Create category…") } }
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
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = change), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(vertical = 12.dp))
        Switch(checked, onCheckedChange = null)
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
        confirmButton = { TextButton({ dismiss(); confirm() }) { Text(title.removeSuffix("?")) } },
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
