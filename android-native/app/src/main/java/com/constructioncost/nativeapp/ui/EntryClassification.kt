package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constructioncost.nativeapp.domain.*

@Composable
internal fun SupplierField(vm:LedgerViewModel,draft:EntryDraft,vendors:List<VendorInfo>,change:(String,String)->Unit) {
    val selected=vendors.firstOrNull { it.id==draft.get("vendor") }
    val name=draft.get("supplierName",selected?.name.orEmpty())
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Field(if(draft.kind=="Payment") "Paid to / recipient" else "Supplier / vendor",name,{ value ->
            change("supplierName", value)
            change("vendor",vendors.firstOrNull { it.name.equals(value.trim(),true) }?.id.orEmpty())
        })
        if(name.isNotBlank() && selected==null) {
            vendors.filter { it.name.contains(name.trim(),true) }.take(5).forEach { vendor ->
                TextButton({ change("vendor", vendor.id); change("supplierName", vendor.name) }) { Text(vendor.name) }
            }
            OutlinedButton({ vm.useSupplier(name) }) { Text("Use new supplier: ${name.trim()}") }
        }
    }
}

@Composable
internal fun ClassificationChoice(
    vm: LedgerViewModel,
    projectId: String,
    label: String,
    selected: String?,
    options: List<Pair<String, String>>,
    classes: List<ClassificationInfo>,
    kind: String,
    parentId: String? = null,
    choose: (String) -> Unit,
    onCreated: ((ClassificationInfo) -> Unit)? = null
) {
    var creating by rememberSaveable(projectId, label, kind, parentId) { mutableStateOf(false) }
    var name by rememberSaveable(projectId, label, kind, parentId) { mutableStateOf("") }
    var attempted by rememberSaveable(projectId, label, kind, parentId) { mutableStateOf(false) }
    var localError by rememberSaveable(projectId, label, kind, parentId) { mutableStateOf<String?>(null) }
    val state by vm.state.collectAsStateWithLifecycle()
    val selectedCategory = classes.firstOrNull { it.id == selected }
    val createKind = if (kind == "STAGE") when (selectedCategory?.kind) {
        "STAGE" -> "SUBSTAGE"
        "SUBSTAGE", "MATERIAL" -> "MATERIAL"
        else -> "STAGE"
    } else kind
    val createParentId = if (kind == "STAGE") when (selectedCategory?.kind) {
        "STAGE", "SUBSTAGE" -> selectedCategory.id
        "MATERIAL" -> selectedCategory.parentId
        else -> null
    } else parentId
    val createTitle = when (createKind) {
        "COST_HEAD" -> "Create cost head"
        "COST_SUBHEAD" -> "Create subhead"
        "COST_DETAIL" -> "Create item or service"
        "STAGE" -> "Create work stage"
        "SUBSTAGE" -> "Create work substage"
        "MATERIAL" -> "Create work item"
        "PLACE" -> "Create place"
        else -> "Create category"
    }
    Choice(label, selected, options, choose, onCreate = {
        name = ""
        attempted = false
        localError = null
        creating = true
    })
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text(createTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field("Category name", name, { name = it }, enabled = !state.saving)
                if (localError != null) Text(localError.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (attempted && state.actionError != null) Text(state.actionError.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton({
            val normalized = name.trim()
            val siblings = classes.filter { it.kind == createKind && it.parentId == createParentId }
            val maximum = siblings.maxOfOrNull { it.sortOrder }
            if (normalized.isBlank()) localError = "Enter a category name."
            else if (maximum == Int.MAX_VALUE) localError = "Category order is full."
            else {
                attempted = true
                val info = ClassificationInfo(newId(), projectId, normalized, createKind, createParentId, (maximum ?: -1) + 1)
                vm.command("Category created") {
                    vm.repository.saveClassification(info)
                    if (onCreated == null) choose(info.id) else onCreated(info)
                    creating = false
                }
            }
        }, enabled = !state.saving) { Text("Create and select") } },
        dismissButton = { TextButton({ creating = false }, enabled = !state.saving) { Text("Cancel") } }
    )
}

@Composable
internal fun CostClassificationFields(vm: LedgerViewModel, projectId: String, draft: EntryDraft, classes: List<ClassificationInfo>, change: (String, String) -> Unit) {
    var query by remember(draft.id) { mutableStateOf("") }
    val available = classes.filter { kindDimension(it.kind) == "COST" && (!it.archived || it.id == draft.get("COST")) }
    val chosen = classes.firstOrNull { it.id == draft.get("COST") }
    val ancestors = mutableListOf<ClassificationInfo>()
    var node = chosen
    while (node != null && ancestors.none { it.id == node.id }) { ancestors.add(node); node = classes.firstOrNull { it.id == node.parentId } }
    val head = ancestors.firstOrNull { it.kind == "COST_HEAD" }
    val subhead = ancestors.firstOrNull { it.kind == "COST_SUBHEAD" }
    fun select(id: String) { change("COST", id); change("classesChanged", "true"); query = "" }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("Find a category", query, { query = it })
        if (query.isNotBlank()) {
            val matching = available.filter { classificationPath(it, classes).contains(query.trim(), true) }
            matching.take(8).forEach { category -> TextButton({ select(category.id) }) { Text(classificationPath(category, classes)) } }
            if (matching.isEmpty()) Text("No matching category. Create one from a cost head or subhead selector.", color = WebColors.muted)
            if (matching.size > 8) Text("${matching.size} matches. Type more to narrow the list.", color = WebColors.muted)
        }
        ClassificationChoice(vm, projectId, "Cost head", head?.id,
            available.filter { it.kind == "COST_HEAD" }.sortedBy { it.sortOrder }.map { it.id to it.name }, classes, "COST_HEAD", choose = { select(it) })
        if (head != null) ClassificationChoice(vm, projectId, "Subhead", subhead?.id,
            available.filter { it.parentId == head.id }.sortedBy { it.sortOrder }.map { it.id to it.name }, classes, "COST_SUBHEAD", head.id,
            choose = { select(it.ifBlank { head.id }) })
        if (subhead != null) ClassificationChoice(vm, projectId, "Material / service detail", chosen?.takeIf { it.kind == "COST_DETAIL" }?.id,
            available.filter { it.parentId == subhead.id }.sortedBy { it.sortOrder }.map { it.id to it.name }, classes, "COST_DETAIL", subhead.id,
            choose = { select(it.ifBlank { subhead.id }) })
        Choice("Cost nature", draft.get("nature"), costNatureOptions, { change("nature", it) }, allowNone = false)
    }
}
