package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.UiActions
import com.constructioncost.nativeapp.platform.files.DocumentPolicy

@Composable
internal fun DocumentImportDialog(vm:LedgerViewModel,actions:UiActions,dismiss:()->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project=state.projectId
    var category by rememberSaveable {mutableStateOf(DocumentPolicy.categories.first())}
    var status by rememberSaveable {mutableStateOf("Received")}
    var revision by rememberSaveable {mutableStateOf("R0")}
    var work by rememberSaveable {mutableStateOf("")}
    var place by rememberSaveable {mutableStateOf("")}
    var notes by rememberSaveable {mutableStateOf("")}
    var invoice by rememberSaveable {mutableStateOf("")}
    var query by rememberSaveable {mutableStateOf("")}
    val loadedClasses = remember(project){vm.repository.classifications(project)}.readLedger(vm)
    val classes = loadedClasses.orEmpty()
    val loadedBills = remember(project,query){vm.repository.expenses(project,ExpenseFilter(query=query),75)}.readLedger(vm)
    val bills = loadedBills.orEmpty()
    AlertDialog(onDismissRequest=dismiss,title={Text("Add files to project")},text={
        Column(Modifier.heightIn(max=510.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Choose document / photo / CAD files using the Android file picker after saving these details.",color=WebColors.muted)
            Choice("Document category",category,DocumentPolicy.categories.map{it to it},{category=it},allowNone = false)
            Choice("Drawing / file status",status,listOf("Received","For review","Approved for construction","Superseded","Archived").map{it to it},{status=it},allowNone = false)
            Field("Revision label",revision,{revision=it})
            if (loadedClasses == null) Text("Loading project categories")
            ClassificationChoice(vm, project, "Related work", work, classes.filter { it.kind == "STAGE" && !it.archived }.map { it.name to it.name },
                classes, "STAGE", choose = { work = it }, onCreated = { work = it.name })
            Field("Floor / room / area",place,{place=it})
            Field("Find related invoice",query,{query=it})
            if (loadedBills == null) Text("Loading related invoices")
            Choice("Related invoice (optional)",invoice,bills.map{it.id to "${it.vendorName} · ${it.description}"},{invoice=it})
            Field("Description / notes",notes,{notes=it},multiline=true)
            Text("The original document is preserved. Editing its contents outside the app should produce a new revision, not overwrite old evidence.",color=WebColors.muted)
        }
    },confirmButton={Button({
        val details=DocumentImportDetails(category,status,revision,work,place,notes,invoice.ifBlank{null})
        actions.importDetailedDocuments?.invoke(project,details) ?: actions.importDocument(project)
        dismiss()
    }, enabled = loadedBills != null && loadedClasses != null){Text("Save document")}},dismissButton={TextButton(dismiss){Text("Cancel")}})
}
