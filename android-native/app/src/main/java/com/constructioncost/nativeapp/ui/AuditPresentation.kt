package com.constructioncost.nativeapp.ui

import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.constructioncost.nativeapp.domain.AuditInfo
import com.constructioncost.nativeapp.domain.Money
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AuditChange(val label:String,val before:String,val after:String)

internal data class AuditDisplay(val changes:List<AuditChange>,val truncated:Boolean)

internal fun readableAuditChanges(audit:AuditInfo):AuditDisplay {
    // Bound parsing before JSONObject recursively constructs the tree.
    fun safeToParse(source:String):Boolean {
        if(source.length>1_000_000)return false
        return runCatching {
            JsonReader(StringReader(source)).use { reader ->
                if(reader.peek()!=JsonToken.BEGIN_OBJECT)return@use false
                var depth=0;var tokens=0
                do {
                    if(++tokens>1_000)return@use false
                    when(reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> { reader.beginObject();if(++depth>24)return@use false }
                        JsonToken.BEGIN_ARRAY -> { reader.beginArray();if(++depth>24)return@use false }
                        JsonToken.END_OBJECT -> { reader.endObject();depth-- }
                        JsonToken.END_ARRAY -> { reader.endArray();depth-- }
                        JsonToken.NAME -> reader.nextName()
                        JsonToken.STRING,JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        else -> return@use false
                    }
                } while(depth>0)
                true
            }
        }.getOrDefault(false)
    }
    var truncated=false
    val omitted=mutableSetOf<String>()
    fun fields(source:String):Map<String,String>? {
        if(source.length>1_000_000)return null
        if(source.trim()=="null")return emptyMap()
        if(!safeToParse(source))return null
        val result=linkedMapOf<String,String>()
        var outputSize=0
        fun flatten(value:Any?,path:String):Boolean {
            when(value) {
                is JSONObject -> {
                    val keys=value.keys()
                    while(keys.hasNext()) {
                        val key=keys.next()
                        if(key in setOf("_auditProjectId","requestJson","idempotencyKey","storageName","sha256","legacyJson"))continue
                        if(path.length+key.length+(if(path.isBlank())0 else 3)>512)return false
                        if(!flatten(value.opt(key),if(path.isBlank())key else "$path / $key"))return false
                    }
                }
                is JSONArray -> for(index in 0 until value.length()) {
                    val suffix=" / Item ${index+1}"
                    if(path.length+suffix.length>512 || !flatten(value.opt(index),path+suffix))return false
                }
                else -> {
                    val text=when {
                        value==null || value==JSONObject.NULL -> "—"
                        path.substringAfterLast(" / ").endsWith("Paise") && value is Number -> Money.format(value.toLong())
                        else -> value.toString()
                    }
                    if(text.length>8_192 || outputSize+path.length+text.length>65_536) {
                        omitted.add(path);truncated=true
                    } else if(path.isNotBlank()) {
                        outputSize+=path.length+text.length
                        result[path]=text
                    }
                }
            }
            return true
        }
        val json=runCatching { JSONObject(source) }.getOrNull() ?: return emptyMap()
        return if(flatten(json,""))result else null
    }
    val before=fields(audit.beforeJson) ?: return AuditDisplay(emptyList(),true)
    val after=fields(audit.afterJson) ?: return AuditDisplay(emptyList(),true)
    val changes=(before.keys+after.keys).distinct().filter{it !in omitted && before[it]!=after[it]}.map { key ->
        val label=key.replace("Paise","").replace(Regex("([a-z])([A-Z])"),"$1 $2").replace('_',' ').replaceFirstChar{it.uppercase()}
        AuditChange(label,before[key] ?: "—",after[key] ?: "—")
    }
    return AuditDisplay(changes,truncated)
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
        val display=produceState<AuditDisplay?>(null,audit.beforeJson,audit.afterJson) {
            value=null
            value=withContext(Dispatchers.Default) { readableAuditChanges(audit) }
        }.value
        if(display==null)Text("Loading change history…")
        if(display?.truncated==true)Text("Some history is too large or complex to display.")
        display?.changes?.forEach { change ->
            HorizontalDivider(color=WebColors.line)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(change.label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Text(change.before,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=WebColors.muted)
                Text(change.after,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
