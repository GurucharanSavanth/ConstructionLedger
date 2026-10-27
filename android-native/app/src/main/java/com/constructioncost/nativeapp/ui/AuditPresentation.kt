package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.constructioncost.nativeapp.domain.AuditInfo
import com.constructioncost.nativeapp.domain.Money
import org.json.JSONArray
import org.json.JSONObject

internal data class AuditChange(val label:String,val before:String,val after:String)

internal fun readableAuditChanges(audit:AuditInfo):List<AuditChange> {
    fun fields(source:String):Map<String,String> {
        val result=linkedMapOf<String,String>()
        fun flatten(value:Any?,path:String) {
            when(value) {
                null,JSONObject.NULL -> if(path.isNotBlank())result[path]="—"
                is JSONObject -> value.keys().asSequence().filter{it !in setOf("_auditProjectId","requestJson","idempotencyKey","storageName","sha256","legacyJson")}.forEach { key -> flatten(value.opt(key),if(path.isBlank())key else "$path / $key") }
                is JSONArray -> (0 until value.length()).forEach { flatten(value.opt(it),"$path / Item ${it+1}") }
                else -> result[path]=if(path.substringAfterLast(" / ").endsWith("Paise") && value is Number) Money.format(value.toLong()) else value.toString()
            }
        }
        runCatching { JSONObject(source) }.getOrNull()?.let{flatten(it,"")}
        return result
    }
    val before=fields(audit.beforeJson);val after=fields(audit.afterJson)
    return (before.keys+after.keys).distinct().filter{before[it]!=after[it]}.map { key ->
        val label=key.replace("Paise","").replace(Regex("([a-z])([A-Z])"),"$1 $2").replace('_',' ').replaceFirstChar{it.uppercase()}
        AuditChange(label,before[key] ?: "—",after[key] ?: "—")
    }
}

@Composable
internal fun AuditChanges(audit:AuditInfo) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("${audit.action.lowercase().replaceFirstChar{it.uppercase()}} · ${audit.recordType.replace('_',' ')}",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Field",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
            Text("Before",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
            Text("After",Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
        }
        readableAuditChanges(audit).forEach { change ->
            HorizontalDivider(color=WebColors.line)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(change.label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Text(change.before,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=WebColors.muted)
                Text(change.after,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
