package com.constructioncost.nativeapp.ui

// Licensing intent: GPL-3.0-only; distribution notices and third-party review remain pending.

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.UiActions
import com.constructioncost.nativeapp.platform.CatalogSeeder
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth


@Composable
internal fun CalendarScreen(vm: LedgerViewModel, add: () -> Unit, detail: (String, String) -> Unit,
    header: @Composable () -> Unit, enableReminders: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    var selected by rememberSaveable(project) { mutableStateOf(LocalDate.now().toString()) }
    var displayMonth by rememberSaveable(project) { mutableStateOf(YearMonth.now().toString()) }
    var pageIndex by rememberSaveable(project, selected) { mutableIntStateOf(0) }
    val month = YearMonth.parse(displayMonth)
    val monthStart = month.atDay(1).toString()
    val monthEnd = month.atEndOfMonth().toString()
    val dayCounts = remember(project,monthStart,monthEnd) {vm.repository.calendarDayCounts(project,monthStart,monthEnd)}.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val counts = dayCounts.associate{it.date to it.eventCount}
    val events = remember(project,selected,pageIndex) {vm.repository.activity(project,76,pageIndex*75,fromDate=selected,toDate=selected)}.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val plans = remember(project) {vm.repository.instalments(project)}.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val leading = month.atDay(1).dayOfWeek.value-1
    val weeks = (leading+month.lengthOfMonth()+6)/7
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { header() }
        item {WebCard(Modifier.padding(horizontal=16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                OutlinedButton({displayMonth=month.minusMonths(1).toString()}) {Text("←",Modifier.semantics{contentDescription="Previous month"})}
                Text(month.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")),style=MaterialTheme.typography.titleLarge)
                OutlinedButton({displayMonth=month.plusMonths(1).toString()}) {Text("→",Modifier.semantics{contentDescription="Next month"})}
            }
            Row(Modifier.fillMaxWidth()) {listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun").forEach {Text(it,Modifier.weight(1f),color=WebColors.muted,style=MaterialTheme.typography.labelSmall)}}
            Column {
                repeat(weeks) {week-> Row(Modifier.fillMaxWidth()) {
                    repeat(7) {weekday->
                        val number=week*7+weekday-leading+1
                        if(number in 1..month.lengthOfMonth()) {
                            val date=month.atDay(number).toString()
                            Surface(onClick={selected=date},modifier=Modifier.weight(1f).height(64.dp).semantics(mergeDescendants=true){
                                contentDescription="$date${counts[date]?.let { ", $it events" } ?: ", no events"}${if(selected==date) ", selected" else ""}"
                            },color=if(selected==date) WebColors.blueSoft else androidx.compose.ui.graphics.Color.White,
                                border=androidx.compose.foundation.BorderStroke(.5.dp,WebColors.line)) {
                                Column(Modifier.padding(5.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                    Text(number.toString(),style=MaterialTheme.typography.titleMedium)
                                    if(counts[date]!=null) Text("●",color=WebColors.teal,style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else Spacer(Modifier.weight(1f).height(64.dp))
                    }
                }}
            }
        }}
        item {WebCard(Modifier.padding(horizontal=16.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(LocalDate.parse(selected).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                OutlinedButton({vm.openNewEntry("Plan", selected, add)}) {Text("+ Plan")}
            }
            events.take(75).filter {it.type in setOf("EXPENSE","PAYMENT","REVERSAL","REFUND","INSTALMENT","NOTE")}.forEach {row->
                val plan=plans.firstOrNull{it.id==row.id}
                if(plan?.cancelled!=true) WebListRow(row.title,when{row.type=="INSTALMENT" && row.amountPaise==0L -> "Completed instalment";row.type=="INSTALMENT" -> "Scheduled, not paid";row.type=="PAYMENT" -> "Payment made";else -> "Recorded"},row.amountPaise?.let(Money::format)) {detail(row.id,row.type)}
            }
            if(events.isEmpty()) EmptyPanel("No records for this date.","")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton({pageIndex--},enabled=pageIndex>0) {Text("Previous records")}
                TextButton({pageIndex++},enabled=events.size>75) {Text("Next records")}
            }
        }}
        item {WebCard(Modifier.padding(horizontal=16.dp)) {
            Text("Upcoming scheduled payments",style=MaterialTheme.typography.titleLarge)
            enableReminders?.let {OutlinedButton(it){Text("Enable reminders")}}
            Text("Android can remind you about scheduled payments. Notification permission is required.",color=WebColors.muted,style=MaterialTheme.typography.bodyMedium)
            val upcoming=plans.filter{!it.cancelled && it.outstandingPaise>0}.sortedBy{it.dueDate}.take(30)
            upcoming.forEach{plan->WebListRow(plan.title,plan.dueDate,Money.format(plan.outstandingPaise)){detail(plan.id,"INSTALMENT")}}
            if(upcoming.isEmpty()) EmptyPanel("No upcoming scheduled payments","")
        }}
    }
}


internal data class InsightComparison(
    val ranked: List<InsightRow>,
    val totalPaise: Long,
    val unclassifiedPaise: Long,
    val coveredPaise: Long,
    val otherPaise: Long
) {
    val other: List<InsightRow> get() = ranked.drop(5)
}

internal fun insightComparison(rows: List<InsightRow>): InsightComparison {
    val ranked = rows.filter { it.amountPaise > 0 }
        .sortedWith(compareByDescending<InsightRow> { it.amountPaise }.thenBy { it.name })
    val total = Money.sum(ranked.map { it.amountPaise })
    val unclassified = ranked.firstOrNull { it.id == null }?.amountPaise ?: 0L
    return InsightComparison(ranked, total, unclassified, Math.subtractExact(total, unclassified),
        Money.sum(ranked.drop(5).map { it.amountPaise }))
}

internal fun insightSharePercent(amountPaise: Long, totalPaise: Long): String =
    if (totalPaise <= 0) "0" else BigDecimal.valueOf(amountPaise)
        .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(totalPaise), 1, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()

internal fun advanceBalances(payments: List<PaymentRow>): Map<String, Long> {
    val returned = mutableMapOf<String, Long>()
    payments.forEach { payment ->
        payment.originalPaymentId?.let { original ->
            returned[original] = Math.addExact(returned[original] ?: 0L,
                Math.subtractExact(payment.amountPaise, payment.allocatedPaise))
        }
    }
    return payments.filter { it.kind == "PAYMENT" }.associate { payment ->
        payment.id to Math.subtractExact(Math.subtractExact(payment.amountPaise, payment.allocatedPaise), returned[payment.id] ?: 0L)
    }
}


@Composable
internal fun PaymentsScreen(vm: LedgerViewModel, add: () -> Unit, detail: (String, String) -> Unit, allocationOnly: Boolean = false) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val date = rememberLedgerDate()
    val payments = remember(project) { vm.repository.payments(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val plans = remember(project) { vm.repository.instalments(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val agreements = remember(project) { vm.repository.commitments(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    var expenseQuery by rememberSaveable(project) { mutableStateOf("") }
    var expensePage by rememberSaveable(project) { mutableIntStateOf(0) }
    val loadedExpenses = remember(project, expenseQuery, expensePage) {
        vm.repository.expenses(project, ExpenseFilter(query = expenseQuery.trim(), status = "OUTSTANDING"), 76, expensePage * 75)
    }.readLedger(vm)
    val expenses = loadedExpenses.orEmpty()
    var source by rememberSaveable(project) { mutableStateOf("") }
    var target by rememberSaveable(project) { mutableStateOf("") }
    var targetLabel by rememberSaveable(project) { mutableStateOf("") }
    var amount by rememberSaveable(project) { mutableStateOf("") }
    val balances = remember(payments) { advanceBalances(payments) }
    fun available(payment: PaymentRow): Long = balances[payment.id] ?: 0L
    val selected = payments.firstOrNull { it.id == source }
    val available = selected?.let(::available) ?: 0
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageHeading(if(allocationOnly) "Apply an advance" else "Payments",if(allocationOnly) "Use existing advance on a bill" else "Payments, schedules and supplier advances.") }
        if(!allocationOnly) item { Button({ vm.openNewEntry("Payment", then = add) }) { Text("Record payment") } }
        item { Section("Apply an advance", expandedInitially=allocationOnly) {
            Text("This applies existing cash to a cost. It does not post another payment.")
            Choice("Payment with available advance", source, payments.filter { it.kind == "PAYMENT" && available(it) > 0 }
                .map { it.id to "${it.date} · ${it.vendorName} · ${Money.format(available(it))} available" }, { source = it; target = ""; targetLabel = ""; expensePage = 0 })
            Field("Search unpaid expenses", expenseQuery, { expenseQuery = it; expensePage = 0 })
            if (loadedExpenses == null) Text("Loading unpaid expenses")
            val options = expenses.take(75).filter { selected?.vendorId == null || it.vendorId == null || selected.vendorId == it.vendorId }
                .map { it.id to "${it.description} · ${Money.format(it.outstandingPaise)} due" }
            val retained = target.takeIf { it.isNotBlank() && options.none { option -> option.first == it } }?.let { it to targetLabel }
            val choices = listOfNotNull(retained) + options
            Choice("Unpaid cost", target, choices, { target = it; targetLabel = choices.firstOrNull { option -> option.first == it }?.second.orEmpty() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ expensePage-- }, Modifier.weight(1f), enabled = expensePage > 0) { Text("Previous expenses") }
                OutlinedButton({ expensePage++ }, Modifier.weight(1f), enabled = expenses.size > 75) { Text("Next expenses") }
            }
            Field("Amount to apply (₹)", amount, { amount = it }, numeric = true)
            Button({
                vm.command("Advance applied") {
                    val paise = Money.parse(amount)
                    require(source.isNotBlank() && target.isNotBlank() && paise <= available) { "Choose a payment, expense and amount within the available advance." }
                    vm.repository.allocateAdvance(source, target, paise, newId())
                    source = ""; target = ""; targetLabel = ""; amount = ""
                }
            }, enabled = !state.saving && loadedExpenses != null) { Text("Apply advance") }
        } }
        if(!allocationOnly) {
        item { Title("Scheduled instalments") }
        items(plans, key = { it.id }) { row -> RecordCard(row.title,
            "${row.dueDate} · ${if (row.cancelled) "Cancelled" else if (row.outstandingPaise == 0L) "Paid" else if (row.dueDate < date.today.toString()) "Overdue" else "Upcoming"}",
            Money.format(row.outstandingPaise)) { detail(row.id, "INSTALMENT") } }
        item { TextButton({ vm.openNewEntry("Plan", then = add) }) { Text("Add one-off plan") } }
        item { Title("Commitments") }
        items(agreements, key = { it.id }) { row -> RecordCard(row.title, row.date, Money.format(row.amountPaise)) { detail(row.id, "COMMITMENT") } }
        item { TextButton({ vm.openNewEntry("Contract", then = add) }) { Text("Add commitment and instalments") } }
        item { Title("Cash transactions") }
        items(payments, key = { it.id }) { row -> RecordCard(row.reference.ifBlank { row.kind },
            "${row.date} · ${row.vendorName} · ${row.kind} · allocated ${Money.format(row.allocatedPaise)}",
            Money.format(row.amountPaise)) { detail(row.id, row.kind) } }
        }
    }
}

@Composable
internal fun DocumentsScreen(vm: LedgerViewModel, actions: UiActions, detail: (String, String) -> Unit, backup: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val documents = remember(project) { vm.repository.attachments(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    var query by rememberSaveable(project) { mutableStateOf("") }
    var categoryFilter by rememberSaveable(project) { mutableStateOf("<all>") }
    val categories = (com.constructioncost.nativeapp.platform.files.DocumentPolicy.categories + documents.map { it.category }).distinct().sorted()
    var adding by remember { mutableStateOf(false) }
    val matching = documents.filter { file ->
        (categoryFilter == "<all>" || file.category == categoryFilter) &&
            listOf(file.name, file.mimeType, file.category, file.status, file.revisionLabel, file.work, file.place, file.notes)
                .any { it.contains(query.trim(), ignoreCase = true) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("Project Files", "Drawings, CAD, bills, photos and agreements—all in one place.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            backup?.let { OutlinedButton(it) { Text("Backup & Restore") } }
            Button({ adding = true }) { Text("+ Add files") }
        } }
        actions.manageCapturedPhotos?.let { review -> item { OutlinedButton({ review(project) }) { Text("Kept camera photos") } } }
        item { WebCard(color = WebColors.soft) { Text("Original documents are stored in your private project vault on this device. All file formats can be stored; previews depend on installed Android support. Export a verified encrypted backup periodically and store it separately.", color = WebColors.muted) } }
        item { Field("Find drawings, bills or suppliers...", query, { query = it },showLabel=false) }
        item { Choice("Document category", categoryFilter,
            listOf("<all>" to "All files") + categories.map { it to it.ifBlank { "Unclassified" } },
            { categoryFilter = it }, allowNone = false,modifier=Modifier.fillMaxWidth(.5f),showLabel=false) }
        item { WebCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Saved documents", style = MaterialTheme.typography.titleLarge); Text("${matching.size} of ${documents.size}", color = WebColors.muted) }
            if (matching.isEmpty()) EmptyPanel("No files found", "Add drawings, photos, receipts, CAD files or documents of any format.")
            matching.forEach { file -> WebListRow(file.name,
                (listOf(file.category, file.revisionLabel, file.status).filter { it.isNotBlank() } +
                    listOf(java.time.Instant.ofEpochMilli(file.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(), "${file.size} bytes")).joinToString(" · "), "View →") { detail(file.id, "DOCUMENT") } }
        } }
    }
    if (adding) DocumentImportDialog(vm, actions, { adding = false })
}

@Composable
internal fun VendorsScreen(vm: LedgerViewModel, activity: (ExpenseFilter) -> Unit, detail: (String, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val vendors = remember(project) { vm.repository.vendors(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val payments = remember(project) { vm.repository.payments(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    var name by rememberSaveable(project) { mutableStateOf("") }
    var note by rememberSaveable(project) { mutableStateOf("") }
    var editing by rememberSaveable(project) { mutableStateOf("") }
    var paymentsFor by rememberSaveable(project) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Vendors") }
        item { Section(if (editing.isBlank()) "Add vendor" else "Edit vendor") {
            Field("Vendor name", name, { name = it })
            Field("Notes", note, { note = it }, multiline = true)
            Button({ vm.command("Vendor saved") {
                vm.repository.saveVendor(VendorInfo(editing.ifBlank { newId() }, project, name.trim(), note))
                editing = ""; name = ""; note = ""
            } }, enabled = !state.saving) { Text("Save vendor") }
        } }
        items(vendors, key = { it.id }) { vendor -> RecordCard(vendor.name,
            "Outstanding ${Money.format(vendor.outstandingPaise)} · ${vendor.note}") { activity(ExpenseFilter(vendorId = vendor.id)) }
            TextButton({ editing = vendor.id; name = vendor.name; note = vendor.note }) { Text("Edit ${vendor.name}") }
            val linkedCount = payments.count { it.vendorId == vendor.id }
            TextButton({ paymentsFor = vendor.id }) { Text("View $linkedCount payments for ${vendor.name}") }
        }
    }
    vendors.firstOrNull { it.id == paymentsFor }?.let { vendor ->
        AlertDialog(onDismissRequest = { paymentsFor = "" }, title = { Text("Payments for ${vendor.name}") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val linked = payments.filter { it.vendorId == vendor.id }
                    if (linked.isEmpty()) item { Text("No linked payments.") }
                    items(linked, key = { it.id }) { payment ->
                        RecordCard(payment.reference.ifBlank { payment.kind },
                            "${payment.date} · ${payment.kind}", Money.format(payment.amountPaise)) {
                            paymentsFor = ""
                            detail(payment.id, payment.kind)
                        }
                    }
                }
            }, confirmButton = { TextButton({ paymentsFor = "" }) { Text("Close") } })
    }
}

@Composable
internal fun ProjectsScreen(vm: LedgerViewModel, actions: UiActions) {
    val context = LocalContext.current.applicationContext
    val state by vm.state.collectAsStateWithLifecycle()
    val projects = remember(vm.repository) { vm.repository.projects }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    var id by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var budget by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var expectedRevision by rememberSaveable { mutableLongStateOf(0L) }
    var search by rememberSaveable { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<ProjectDeletionSummary?>(null) }
    var deleteConfirmation by rememberSaveable { mutableStateOf("") }
    var deleteLoading by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun askDelete(project: ProjectInfo) {
        deleteLoading = true; deleteError = null
        scope.launch {
            try {
                deleteCandidate = AttachmentStore(context, vm.repository).projectDeletionSummary(project.id)
                deleteConfirmation = ""
            } catch (failure: Exception) { deleteError = failure.message ?: "Cannot verify project contents" }
            finally { deleteLoading = false }
        }
    }
    val visible = projects.filter { "${it.name} ${it.id}".contains(search.trim(), ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Projects") }
        item { if (!state.hasActiveProject) Text(CREATE_PROJECT_MESSAGE, color = WebColors.muted) }
        item { Section(if (id.isBlank()) "Add project" else "Edit project", expandedInitially = projects.isEmpty()) {
            Field("Project name", name, { name = it })
            Field("Budget (₹, optional)", budget, { budget = it }, numeric = true)
            Toggle("Archived project", archived, { archived = it })
            Button({
                val editing = id.isNotBlank()
                val projectId = id.ifBlank { newId() }
                val projectName = name.trim()
                val projectBudget = budget
                val projectArchived = archived
                val revision = if (editing) expectedRevision else 0L
                vm.command("Project saved") {
                vm.repository.saveProject(ProjectInfo(projectId, projectName, projectBudget.takeIf { it.isNotBlank() }?.let { Money.parse(it, true) }, revision, projectArchived), requireExisting = editing)
                CatalogSeeder(context, vm.repository).seed(projectId)
                if (!projectArchived) {
                    vm.state.first { state -> state.projects.any { it.id == projectId && !it.archived } }
                    vm.selectProject(projectId)
                }
                id = ""; name = ""; budget = ""; archived = false
            } }, enabled = !state.saving) { Text(if (projects.isEmpty()) "Create Project" else "Save project") }
            if (id.isNotBlank()) TextButton({ id = ""; name = ""; budget = ""; archived = false }, enabled = !state.saving) { Text("Cancel edit") }
        } }
        item { Field("Search projects", search, { search = it }) }
        items(visible, key = { it.id }) { project ->
            RecordCard(project.name, "${if (project.id == state.projectId) "Selected" else if (project.archived) "Archived" else "Tap to select"} · ID ${project.id.take(8)}",
                project.budgetPaise?.let(Money::format)) { if (!project.archived) vm.selectProject(project.id) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ id = project.id; name = project.name; budget = project.budgetPaise?.let(Money::input).orEmpty(); archived = project.archived; expectedRevision = project.revision }, enabled = !state.saving) { Text("Edit") }
                TextButton({ askDelete(project) }, enabled = !deleteLoading && !state.saving) { Text("Delete") }
            }
        }
    }
    deleteCandidate?.let { project ->
        Dialog(onDismissRequest = { deleteCandidate = null }) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Delete project permanently?", style = MaterialTheme.typography.titleLarge)
                    Text("This permanently deletes ${project.projectName}, its records and original attachments. This cannot be undone.", color = MaterialTheme.colorScheme.error)
                    Text("Records: ${project.recordCount}\nAttachments: ${project.attachmentCount}\nAttachment bytes: ${project.attachmentBytes}")
                    Text("Type Yes to confirm deletion")
                    Field("Confirmation", deleteConfirmation, { deleteConfirmation = it })
                    Button({ actions.exportProject(project.projectId); deleteCandidate = null }, Modifier.fillMaxWidth()) { Text("Export Project as Excel") }
                    Button({ actions.deleteProject(project.projectId); deleteCandidate = null }, Modifier.fillMaxWidth(), enabled = deleteConfirmation == "Yes" && !state.saving) { Text("Delete Permanently") }
                    TextButton({ deleteCandidate = null }, Modifier.fillMaxWidth()) { Text("No") }
                }
            }
        }
    }
    if (deleteLoading) AlertDialog(onDismissRequest = {}, title = { Text("Verifying project") }, text = { LinearProgressIndicator() }, confirmButton = {})
    deleteError?.let { error -> AlertDialog(onDismissRequest = { deleteError = null }, title = { Text("Project contents could not be verified") }, text = { Text(error) }, confirmButton = { TextButton({ deleteError = null }) { Text("OK") } }) }
}

@Composable
internal fun CategoriesScreen(vm: LedgerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val classes = remember(project) { vm.repository.classifications(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    var editing by rememberSaveable(project) { mutableStateOf("") }
    var name by rememberSaveable(project) { mutableStateOf("") }
    var kind by rememberSaveable(project) { mutableStateOf("COST_HEAD") }
    var parent by rememberSaveable(project) { mutableStateOf("") }
    var source by rememberSaveable(project) { mutableStateOf("") }
    var target by rememberSaveable(project) { mutableStateOf("") }
    var merge by remember(project) { mutableStateOf(false) }
    var archiveCandidate by remember(project) { mutableStateOf<ClassificationInfo?>(null) }
    val sourceCategory = classes.firstOrNull { it.id == source && !it.archived }
    val kinds = listOf("STAGE", "SUBSTAGE", "MATERIAL", "COST_HEAD", "COST_SUBHEAD", "COST_DETAIL", "PLACE")
    var catalogMode by rememberSaveable(project) { mutableStateOf("COST") }
    var catalogSearch by rememberSaveable(project) { mutableStateOf("") }
    val parentKind = when (kind) { "SUBSTAGE" -> "STAGE"; "MATERIAL" -> "SUBSTAGE"; "COST_SUBHEAD" -> "COST_HEAD"; "COST_DETAIL" -> "COST_SUBHEAD"; "PLACE" -> "PLACE"; else -> "" }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { WebTabs(listOf("COST" to "Cost Heads","WORK" to "Work Stages"),catalogMode) {catalogMode=it;if(editing.isBlank()){kind=if(it=="COST")"COST_HEAD" else "STAGE";parent=""}} }
        item { Text("Rename, reorder or archive categories. Historical classification stays linked to its stable identity. Changes are audited.",color=WebColors.muted) }
        item { Field("Find category",catalogSearch,{catalogSearch=it}) }
        item { Section(if (editing.isBlank()) "Add category" else "Edit category") {
            Field("Name", name, { name = it })
            Choice("Type", kind, kinds.map { it to it.replace('_', ' ') }, { kind = it; parent = "" }, allowNone = false)
            if (parentKind.isNotBlank()) Choice("Parent", parent, classes.filter { it.kind == parentKind && !it.archived && it.id != editing }
                .map { it.id to classificationPath(it, classes) }, { parent = it })
            Button({ vm.command("Category saved") {
                val original = classes.firstOrNull { it.id == editing }
                vm.repository.saveClassification(ClassificationInfo(editing.ifBlank { newId() }, project, name.trim(), original?.kind ?: kind,
                    parent.ifBlank { null }, original?.sortOrder ?: 0, original?.archived ?: false, original?.nature.orEmpty()), original)
                editing = ""; name = ""; parent = ""
            } }, enabled = !state.saving) { Text("Save category") }
        } }
        item { Toggle("Show archived categories", state.showArchived, { vm.settings(showArchived = it) }) }
        item { Section("Merge or reassign expenses") {
            Choice("Source", source, classes.filter { !it.archived }.map { it.id to classificationPath(it, classes) }, { source = it; target = "" })
            Choice("Target", target, classes.filter { !it.archived && it.id != source && sourceCategory != null && kindDimension(it.kind) == kindDimension(sourceCategory.kind) }
                .map { it.id to classificationPath(it, classes) }, { target = it })
            OutlinedButton({ merge = true }, enabled = sourceCategory != null && target.isNotBlank() && !state.saving) { Text("Merge source into target") }
            OutlinedButton({ vm.command("Expenses reassigned") { vm.repository.reassignClassification(source, target); source = ""; target = "" } }, enabled = sourceCategory != null && target.isNotBlank() && !state.saving) { Text("Reassign expenses only") }
        } }
        items(classes.filter { (state.showArchived || !it.archived) && (if(catalogMode=="COST") kindDimension(it.kind)=="COST" else kindDimension(it.kind)!="COST") && classificationPath(it,classes).contains(catalogSearch.trim(),true) }, key = { it.id }) { category ->
            RecordCard(classificationPath(category, classes), "${category.kind}${if (category.archived) " · Archived" else ""}") {
                editing = category.id; name = category.name; kind = category.kind; parent = category.parentId.orEmpty()
            }
            val siblings = classes.filter { it.kind == category.kind && it.parentId == category.parentId }
                .sortedWith(compareBy<ClassificationInfo> { it.sortOrder }.thenBy { it.name }.thenBy { it.id })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.command("Category moved first") {
                    val firstOrder = siblings.minOf { it.sortOrder }.toLong() - 1
                    require(firstOrder >= Int.MIN_VALUE) { "Category order is full" }
                    vm.repository.saveClassification(category.copy(sortOrder = firstOrder.toInt()), category)
                } }, Modifier.weight(1f), enabled = !state.saving && siblings.firstOrNull()?.id != category.id) { Text("Move first") }
                OutlinedButton({ vm.command("Category moved last") {
                    val lastOrder = siblings.maxOf { it.sortOrder }.toLong() + 1
                    require(lastOrder <= Int.MAX_VALUE) { "Category order is full" }
                    vm.repository.saveClassification(category.copy(sortOrder = lastOrder.toInt()), category)
                } }, Modifier.weight(1f), enabled = !state.saving && siblings.lastOrNull()?.id != category.id) { Text("Move last") }
            }
            TextButton({ archiveCandidate = category }, enabled = !state.saving) {
                Text(if (category.archived) "Unarchive ${category.name}" else "Archive ${category.name}")
            }
        }
    }
    archiveCandidate?.let { category -> Confirm(if (category.archived) "Unarchive category?" else "Archive category?",
        "Historical expense classifications remain linked to this category.",
        { vm.command(if (category.archived) "Category unarchived" else "Category archived") {
            vm.repository.saveClassification(category.copy(archived = !category.archived), category)
        } }, { archiveCandidate = null }) }
    if (merge) Confirm("Merge category?", "Expense allocations move to the target and the source is archived. Historical audit stays available.",
        { vm.command("Categories merged") { vm.repository.mergeClassification(source, target); source = ""; target = "" } }, { merge = false })
}

@Composable
internal fun BackupScreen(vm: LedgerViewModel, actions: UiActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    var backupPassword by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var backupPasswordError by remember { mutableStateOf(false) }
    var legacyPassword by remember { mutableStateOf("") }
    var legacyPasswordError by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { WebCard(color=WebColors.soft) {
            Text("Complete encrypted backup",style=MaterialTheme.typography.titleMedium)
            Text("Exports bills, payments, allocations, future plans, contracts, activity records, original drawings, photos and settings. Save the archive somewhere safe outside this device.",color=WebColors.muted)
        } }
        item { WebCard {
            Text("Create encrypted backup",style=MaterialTheme.typography.titleLarge)
            Field("Recovery password (10+ characters)",backupPassword,{backupPassword=it;backupPasswordError=false},password=true)
            if(backupPasswordError) Text("Use 10 to 1024 characters and keep the password safe.",color=MaterialTheme.colorScheme.error)
            Button({
                if(backupPassword.length !in 10..1024) backupPasswordError=true
                else {val password=backupPassword;backupPassword="";actions.createBackup(password)}
            }) {Text("Back up everything")}
            Text("Choose the destination with the Android save dialog. There is no password recovery.",color=WebColors.muted,style=MaterialTheme.typography.bodyMedium)
        } }
        item { WebCard {
            Text("Restore from backup",style=MaterialTheme.typography.titleLarge)
            Text("Replaces this device's current BuildLedger records and original files after successful verification. Export a fresh backup before restoring.",color=WebColors.muted)
            Field("Backup recovery password",restorePassword,{restorePassword=it},password=true)
            Button({val password=restorePassword.ifEmpty{null};restorePassword="";actions.restoreBackup(password)}) {Text("Select backup to restore")}
            Text("Leave the password blank only for an existing unencrypted native ZIP.",color=WebColors.muted,style=MaterialTheme.typography.bodyMedium)
        } }
        item { WebCard {
            Text("Import web backup",style=MaterialTheme.typography.titleLarge)
            Text("For an encrypted ZIP exported from BuildLedger_Web, enter its original recovery password. The archive is checked before you confirm importing it.",color=WebColors.muted)
            OutlinedTextField(value=legacyPassword,onValueChange={legacyPassword=it;legacyPasswordError=false},
                label={Text("Legacy recovery password")},visualTransformation=PasswordVisualTransformation(),
                singleLine=true,modifier=Modifier.fillMaxWidth().testTag("legacyRecoveryPassword"))
            if(legacyPasswordError) Text("Enter at least 10 characters.",color=MaterialTheme.colorScheme.error)
            Button({
                if(legacyPassword.length<10) legacyPasswordError=true
                else {val password=legacyPassword;legacyPassword="";actions.importLegacy(password)}
            }) {Text("Choose legacy export")}
        } }
        item { Section("Exports") {
            OutlinedButton({actions.exportCsv(state.projectId)}, enabled = state.hasActiveProject) {Text("Export CSV")}
            OutlinedButton({actions.exportPdf(state.projectId)}, enabled = state.hasActiveProject) {Text("Export PDF")}
        } }
    }
}

@Composable
internal fun SettingsScreen(vm: LedgerViewModel, actions: UiActions, projectsAction: (() -> Unit)? = null, suppliersAction: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val projects = remember(vm.repository) { vm.repository.projects }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val selected = projects.firstOrNull { it.id == project }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selected != null) item(key = "project-settings") {
            var name by rememberSaveable(selected.id) { mutableStateOf(selected.name) }
            var budget by rememberSaveable(selected.id) { mutableStateOf(selected.budgetPaise?.let(Money::input).orEmpty()) }
            var expectedRevision by rememberSaveable(selected.id) { mutableLongStateOf(selected.revision) }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Field("Project name", name, { name = it })
                Field("Project budget (₹)", budget, { budget = it }, numeric = true)
                WebCard(color=WebColors.soft) { Text("Changes to the budget affect planning only. Financial transactions are unchanged.",color=WebColors.muted) }
                Button({
                    val projectName = name.trim()
                    val projectBudget = budget
                    val revision = expectedRevision
                    vm.command("Project settings saved") {
                    vm.repository.saveProject(selected.copy(name = projectName, budgetPaise = projectBudget.takeIf { it.isNotBlank() }?.let { Money.parse(it, true) }, revision = revision), requireExisting = true)
                    expectedRevision = Math.addExact(revision, 1L)
                } }, enabled = !state.saving) { Text("Save project settings") }
            }
        }
        item { Section("Android settings") {
            actions.manageRecoveryCopies?.let { TextButton(it) { Text("Manage retained originals") } }
            Toggle("Local schedule reminders", state.reminders, actions.setReminders)
            Toggle("Show archived categories", state.showArchived, { vm.settings(showArchived = it) })
            projectsAction?.let { TextButton(it) {Text("Manage projects")} }
            suppliersAction?.let { TextButton(it, enabled = state.hasActiveProject) {Text("Manage suppliers")} }
        } }
        item { WebCard { Text("Developer: Gurucharan S.", style = MaterialTheme.typography.bodyMedium) } }
    }
}
