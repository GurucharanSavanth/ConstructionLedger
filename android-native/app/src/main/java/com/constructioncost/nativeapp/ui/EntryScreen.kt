package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal val costNatureOptions = listOf("" to "Unclassified / Legacy", "Material" to "Material", "Labour" to "Labour",
    "Professional Service" to "Professional Service", "Equipment Purchase" to "Equipment Purchase",
    "Equipment Hire" to "Equipment Hire", "Utility / Connection" to "Utility / Connection",
    "Transport" to "Transport", "Fee / Approval" to "Fee / Approval", "Other" to "Other")

@Composable
internal fun EntryScreen(vm: LedgerViewModel, actions: UiActions, saved: () -> Unit, payments: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val repo = vm.repository
    val project = draft.projectId.ifBlank { state.projectId }
    val projects = remember(repo) { repo.projects }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val classes = remember(project) { repo.classifications(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val vendors = remember(project) { repo.vendors(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val files = remember(project) { repo.attachments(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val commitments = remember(project) { repo.commitments(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val plans = remember(project) { repo.instalments(project) }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    var expensePage by rememberSaveable(project) { mutableIntStateOf(0) }
    var expenseQuery by rememberSaveable(project) { mutableStateOf("") }
    val loadedExpenses = remember(project, expensePage, expenseQuery) {
        repo.expenses(project, ExpenseFilter(query = expenseQuery.trim(), status = "OUTSTANDING"), 76, expensePage * 75)
    }.readLedger(vm)
    val expenses = loadedExpenses.orEmpty()
    val visibleExpenses = expenses.take(75)
    var modeConfirmation by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var duplicateInvoices by remember { mutableStateOf(emptyList<ExpenseRow>()) }
    val scope = rememberCoroutineScope()
    val cost = draft.kind !in listOf("Payment", "Plan", "Contract", "Allocate advance", "Work log")
    val editing = draft.get("revision").isNotBlank()
    val staleProject = draft.projectId != state.projectId && projects.none { it.id == draft.projectId }
    val change: (String, String) -> Unit = { key, value -> vm.field(key, value) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { PageHeading(if (editing) "Edit entry" else webEntryChoices.firstOrNull { it.kind == draft.kind }?.title ?: "Add Entry",
            webEntryChoices.firstOrNull { it.kind == draft.kind }?.description ?: "Enter the details, then review before saving.") }
        if (draft.projectId != state.projectId) item {
            if (projects.any { it.id == draft.projectId }) {
                Text("This retained draft belongs to a different project. Saving keeps it in its original project.", color = MaterialTheme.colorScheme.error)
                TextButton({ vm.selectProject(draft.projectId) }) { Text("Switch to draft project") }
            } else {
                Text("This draft's original project is no longer available. Choose a project and explicitly move or discard the draft before saving.", color = MaterialTheme.colorScheme.error)
                Button({ vm.reassignDraftToCurrentProject() }, enabled = state.projectId.isNotBlank()) { Text("Move draft to current project") }
            }
        }
        if (draft.kind == "Allocate advance") item {
            Text("Apply an existing payment to an unpaid bill without posting new cash.")
            Button(payments) { Text("Choose advance in Payments") }
        } else {
            item { Field(when(draft.kind) { "Purchase" -> "Material / item"; "Labour" -> "Work performed"; "Other cost" -> "Purpose"; "Received bill" -> "What was billed?"; "Payment" -> "Payment description (optional)"; else -> "Description" }, draft.get("description"), { change("description", it) }, enabled = !state.saving) }
            if (draft.kind != "Work log") item {
                Field(if (cost) "Final amount (₹)" else "Amount (₹)", draft.get("amount"), { change("amount", it) }, numeric = true,
                    enabled = !state.saving && (draft.get("mode") != "CALCULATED" || draft.get("originalLines").isNotBlank()))
                if (cost) {
                    val amount = runCatching { LedgerViewModel.amount(draft) }.getOrNull()
                    if (amount != null) Text("Recorded cost: ${Money.format(amount)}")
                }
            }
            item { DateField(if (draft.kind in listOf("Plan", "Contract")) "Due / first schedule date" else "Date", draft.date, { change("date", it) }) }
            if (draft.kind != "Work log") item { SupplierField(vm, draft, vendors, change) }
            if (cost) item {
                CostClassificationFields(vm, project, draft, classes, change)
                ClassificationChoice(vm, project, "Work stage", draft.get("WORK"),
                    classes.filter { kindDimension(it.kind) == "WORK" && (!it.archived || it.id == draft.get("WORK")) }.map { it.id to classificationPath(it, classes) },
                    classes, "STAGE", choose = { change("WORK", it); change("classesChanged", "true") })
                ClassificationChoice(vm, project, "Where used?", draft.get("PLACE"),
                    classes.filter { it.kind == "PLACE" && (!it.archived || it.id == draft.get("PLACE")) }.map { it.id to it.name },
                    classes, "PLACE", choose = { change("PLACE", it); change("classesChanged", "true") })
                Field("Bill number (optional)", draft.get("invoiceNumber"), { change("invoiceNumber", it) })
            }
            if (cost && !editing) item {
                Choice("Payment status", draft.get("paidStatus", "Unpaid"), listOf("Unpaid" to "Not paid yet", "Partial" to "Partly paid", "Full" to "Paid in full"), { status ->
                    change("paidStatus", status)
                    change("paid", when (status) { "Full" -> runCatching { Money.input(LedgerViewModel.amount(draft)) }.getOrDefault(""); "Unpaid" -> "0"; else -> "" })
                }, allowNone = false)
                if (draft.get("paidStatus") == "Partial") Field("Amount paid now, if partial (₹)", draft.get("paid"), { change("paid", it) }, numeric = true)
                val amount = runCatching { LedgerViewModel.amount(draft) }.getOrNull()
                val paid = runCatching { LedgerViewModel.paidAmount(draft) }.getOrNull()
                if (amount != null && paid != null && paid <= amount) WebCard(color = WebColors.blueSoft) {
                    Text("Recorded bill cost", color = WebColors.muted)
                    Text(Money.format(amount), style = MaterialTheme.typography.headlineSmall)
                    Text("Money paid ${Money.format(paid)} · Remaining ${Money.format(amount - paid)}", color = WebColors.muted)
                }
            }
            if (cost) item {
                Section("Calculation and invoice lines") {
                    if (draft.get("originalLines").isNotBlank()) {
                        Text("Original invoice lines and calculation evidence are preserved. Use Edit invoice lines to change their amounts; unchanged lines retain their evidence.")
                        Button({ change("invoiceLines", draft.get("originalLines")); change("originalLines", "") }) { Text("Edit invoice lines") }
                    } else {
                        if (draft.get("invoiceLines").isBlank()) Choice("Cost nature", draft.get("nature"), costNatureOptions,
                            { change("nature", it) }, allowNone = false)
                        Choice("Amount mode", draft.get("mode", "DIRECT_AMOUNT"), listOf("DIRECT_AMOUNT" to "Final amount", "CALCULATED" to "Quantity × rate"), { next ->
                            if (next != draft.get("mode", "DIRECT_AMOUNT")) modeConfirmation = next
                        }, allowNone = false)
                        if (draft.get("mode") == "CALCULATED" && draft.get("invoiceLines").isBlank()) {
                            Field("Quantity", draft.get("quantity"), { change("quantity", it) }, numeric = true)
                            Field("Rate (₹)", draft.get("rate"), { change("rate", it) }, numeric = true)
                            Field("Multiplier", draft.get("multiplier", "1"), { change("multiplier", it) }, numeric = true)
                            Choice("Quantity unit", draft.get("quantityUnit", "unit"), listOf("unit", "sqft", "sq100", "kg", "tonne").map { it to it }, { change("quantityUnit", it) }, allowNone = false)
                            Choice("Rate unit", draft.get("rateUnit", "unit"), listOf("unit", "sqft", "sq100", "kg", "tonne").map { it to it }, { change("rateUnit", it) }, allowNone = false)
                            Field("Discount amount (₹)", draft.get("discountAmount", "0"), { change("discountAmount", it) }, numeric = true)
                            Field("Discount percentage", draft.get("discountPercent", "0"), { change("discountPercent", it) }, numeric = true)
                            Field("Tax amount (₹)", draft.get("taxAmount", "0"), { change("taxAmount", it) }, numeric = true)
                            Field("Tax percentage on discounted base", draft.get("taxPercent", "0"), { change("taxPercent", it) }, numeric = true)
                            Field("Transport outside tax base (₹)", draft.get("transport", "0"), { change("transport", it) }, numeric = true)
                            Field("Other explicit adjustment (₹)", draft.get("adjustment", "0"), { change("adjustment", it) }, numeric = true)
                            val calculation = runCatching { LedgerViewModel.calculation(draft) }
                            calculation.getOrNull()?.let {
                                Text("Components (rounded independently): base ${Money.format(it.basePaise)}, discount ${Money.format(it.discountPaise)}, tax ${Money.format(it.taxPaise)}, transport ${Money.format(it.transportPaise)}, adjustment ${Money.format(it.adjustmentPaise)}.")
                                Text("Final cost ${Money.format(it.totalPaise)}. Rounded once after all calculations.")
                            }
                            calculation.exceptionOrNull()?.let { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) }
                        } else if (draft.get("invoiceLines").isBlank()) {
                            Text("Final amount is authoritative. Optional quantity/rate describe this invoice without changing its cost.")
                            Field("Descriptive quantity (optional)", draft.get("quantity"), { change("quantity", it) }, numeric = true)
                            Field("Descriptive rate (optional)", draft.get("rate"), { change("rate", it) }, numeric = true)
                        }
                        InvoiceLines(vm, project, draft, change, classes)
                    }
                }
            }
            if (draft.kind == "Contract") {
                item { Section("Contract calculation") {
                    Toggle("Calculate agreed amount", draft.get("mode") == "CALCULATED", { enabled ->
                        if (!enabled) runCatching { LedgerViewModel.amount(draft) }.getOrNull()?.let { change("amount", Money.input(it)) }
                        change("mode", if (enabled) "CALCULATED" else "DIRECT_AMOUNT")
                    })
                    if (draft.get("mode") == "CALCULATED") {
                        Field("Contract quantity", draft.get("quantity"), { change("quantity", it) }, numeric = true)
                        Field("Contract rate (₹)", draft.get("rate"), { change("rate", it) }, numeric = true)
                        Field("Contract multiplier", draft.get("multiplier", "1"), { change("multiplier", it) }, numeric = true)
                        Choice("Contract quantity unit", draft.get("quantityUnit", "unit"), listOf("unit", "sqft", "sq100", "kg", "tonne").map { it to it }, { change("quantityUnit", it) }, allowNone = false)
                        Choice("Contract rate unit", draft.get("rateUnit", "unit"), listOf("unit", "sqft", "sq100", "kg", "tonne").map { it to it }, { change("rateUnit", it) }, allowNone = false)
                        val calculated = runCatching { LedgerViewModel.calculation(draft) }
                        calculated.getOrNull()?.let { Text("Agreed amount: ${Money.format(it.totalPaise)}. This is a future commitment; no cost or cash is posted.") }
                        calculated.exceptionOrNull()?.let { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) }
                    }
                } }
                item { ContractSchedule(draft, change) }
            }
            if (draft.kind == "Work log") item { WorkTimer(draft, change) }
            item {
                Section("More details", expandedInitially = !cost) {
                    if (draft.kind == "Plan" || draft.kind == "Contract") {
                        Text("Planned classifications describe future schedules. They do not increase recorded cost or cash.")
                        listOf("WORK" to "Work stage", "COST" to "Cost head", "PLACE" to "Place").forEach { (dimension, label) ->
                            ClassificationChoice(vm, project, label, draft.get(dimension), classes.filter { kindDimension(it.kind) == dimension && (!it.archived || it.id == draft.get(dimension)) }
                                .map { it.id to classificationPath(it, classes) }, classes,
                                when (dimension) { "WORK" -> "STAGE"; "COST" -> "COST_HEAD"; else -> "PLACE" }, choose = {
                                    change(dimension, it); change("plannedChanged$dimension", "true")
                                })
                        }
                    }
                    if (draft.kind == "Payment") {
                        Field("Search unpaid expenses", expenseQuery, { expenseQuery = it; expensePage = 0 })
                        if (loadedExpenses == null) Text("Loading unpaid expenses")
                        val selectedExpense = draft.get("expense")
                        val expenseOptions = visibleExpenses.filter { it.outstandingPaise > 0 }
                            .map { it.id to "${it.description} · ${it.vendorName} · ${Money.format(it.outstandingPaise)}" }
                        val retainedOption = selectedExpense.takeIf { it.isNotBlank() && expenseOptions.none { option -> option.first == it } }
                            ?.let { it to draft.get("expenseLabel", "Previously selected expense") }
                        val choices = listOfNotNull(retainedOption) + expenseOptions
                        Choice("Unpaid expense (optional; none = advance)", selectedExpense, choices, { id ->
                                change("expense", id)
                                change("expenseLabel", choices.firstOrNull { it.first == id }?.second.orEmpty())
                                visibleExpenses.firstOrNull { it.id == id }?.let { change("vendor", it.vendorId.orEmpty()); change("amount", Money.input(it.outstandingPaise)) }
                            })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ expensePage-- }, Modifier.weight(1f), enabled = expensePage > 0) { Text("Previous expenses") }
                            OutlinedButton({ expensePage++ }, Modifier.weight(1f), enabled = expenses.size > 75) { Text("Next expenses") }
                        }
                    }
                    if (draft.kind == "Payment" || cost) {
                        Choice("Linked schedule", draft.get("instalment"), plans.filter { !it.cancelled && (draft.get("vendor").isBlank() || it.vendorId == draft.get("vendor")) }
                            .map { it.id to "${it.title} · ${it.dueDate} · ${Money.format(it.outstandingPaise)}" }, { id ->
                                change("instalment", id)
                                if (draft.kind == "Payment") plans.firstOrNull { it.id == id }?.let { change("vendor", it.vendorId.orEmpty()); change("amount", Money.input(it.outstandingPaise)) }
                            })
                    }
                    if (cost || draft.kind == "Plan") Choice("Linked commitment", draft.get("commitment"), commitments.filter { !it.archived }.map { it.id to it.title }, { change("commitment", it) })
                    if (draft.kind in listOf("Plan", "Contract")) Toggle("Local reminder for this schedule", draft.get("remind") == "true", { change("remind", it.toString()) })
                    if (draft.kind == "Plan" && draft.get("remind") == "true") {
                        Choice("Reminder lead time", draft.get("reminderDays", "3"), listOf("0" to "On due date", "1" to "1 day before", "3" to "3 days before", "7" to "7 days before") +
                            listOfNotNull(draft.get("reminderDays").takeIf { it.isNotBlank() && it !in setOf("0", "1", "3", "7") }?.let { it to "$it days before" }), { change("reminderDays", it) }, allowNone = false)
                    }
                }
            }
            if (cost || draft.kind == "Payment") item {
                Choice("Payment method", draft.get("method", "UPI"), listOf("UPI", "Bank transfer", "Cash", "Cheque").map { it to it }, { change("method", it) }, allowNone = false)
                Field("Payment reference (optional)", draft.get("reference"), { change("reference", it) })
            }
            item { Field("Notes", draft.get("note"), { change("note", it) }, multiline = true) }
            if (cost || draft.kind == "Payment") item {
                Text("Attach bills / files (optional)")
                OutlinedButton({ actions.importDocument(project) }) { Text("Choose file") }
                actions.capturePhoto?.let { capture -> OutlinedButton({ capture(project) }) { Text("Take photo with camera") } }
                val selected = draft.get("attachments").split(",").filter { it.isNotBlank() }.toSet()
                Choice("Attach document", "", files.filter { it.id !in selected }.map { it.id to it.name }, { id ->
                    if (id.isNotBlank()) change("attachments", (selected + id).joinToString(","))
                })
                files.filter { it.id in selected }.forEach { file ->
                    TextButton({ actions.viewDocument(file) }) { Text("View ${file.name}") }
                    TextButton({ change("attachments", (selected - file.id).joinToString(",")) }) { Text("Unlink ${file.name}") }
                }
            }
            state.actionError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item {
                Button({
                    try {
                        LocalDate.parse(draft.date)
                        require(draft.get("supplierName").isBlank() || draft.get("vendor").isNotBlank()) { "Choose the supplier suggestion or tap Use new supplier before reviewing." }
                        require(draft.kind == "Payment" || draft.get("description").isNotBlank()) { "Enter a description." }
                        if (draft.kind != "Work log") {
                            if (cost) LedgerViewModel.expense(draft) else when (draft.kind) {
                                "Payment" -> LedgerViewModel.payment(draft)
                                "Plan" -> LedgerViewModel.instalment(draft)
                                "Contract" -> LedgerViewModel.commitment(draft)
                            }
                        }
                        duplicateInvoices = emptyList()
                        if (cost && draft.get("invoiceNumber").isNotBlank()) {
                            reviewing = true
                            scope.launch {
                                try {
                                    val matches = repo.invoiceDuplicate(project, draft.get("invoiceNumber").trim(), draft.get("vendor").ifBlank { null }, draft.id)
                                    if (vm.state.value.draft != draft) vm.showError("Entry changed while checking. Review it again.")
                                    else { duplicateInvoices = matches; review = true }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { vm.storageFailure(failure) }
                                finally { reviewing = false }
                            }
                        } else { if(draft.kind == "Work log") vm.pauseTimerForReview(); review = true }
                    } catch (error: Exception) { vm.showError(error.message ?: "Check the amount and date.") }
                }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.saving && !staleProject && !reviewing && (draft.kind != "Payment" || loadedExpenses != null)) { Text(if (state.saving) "Saving…" else if (reviewing) "Checking invoice…" else "Review and save") }
                TextButton({ discard = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.saving) { Text("Discard draft") }
            }
        }
    }
    modeConfirmation?.let { next -> Confirm("Change amount mode?", "All entered values stay in this draft. The final cost will use the selected mode; review it before saving.", {
        if (next == "DIRECT_AMOUNT") runCatching { Money.input(LedgerViewModel.amount(draft)) }.getOrNull()?.let { change("amount", it) }
        change("mode", next)
    }, { modeConfirmation = null }) }
    if (discard) Confirm("Discard draft?", "This removes unsaved inputs. Saved ledger records remain available in Activity.", { vm.resetDraft() }, { discard = false })
    if (review) {
        val amount = runCatching { if (cost || draft.kind == "Contract") LedgerViewModel.amount(draft) else Money.parse(draft.get("amount")) }.getOrNull()
        val paid = runCatching { LedgerViewModel.paidAmount(draft) }.getOrDefault(0)
        AlertDialog(onDismissRequest = { if (!state.saving) review = false }, title = { Text("Review entry") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(draft.get("description")); Text("Date: ${draft.date}")
                if (draft.get("invoiceNumber").isNotBlank()) Text("Invoice reference: ${draft.get("invoiceNumber")}")
                if (duplicateInvoices.isNotEmpty()) {
                    Text("This invoice reference matches an existing expense. Check these entries before saving another cost.", color = MaterialTheme.colorScheme.error)
                    duplicateInvoices.forEach { Text("${it.description} · ${it.date} · ${Money.format(it.amountPaise)}") }
                }
                if (amount != null) Text("Amount: ${Money.format(amount)}")
                Text(when {
                    cost -> "Recorded cost ${amount?.let(Money::format)}; cash ${Money.format(paid)}; remaining ${amount?.let { Money.format(it - paid) }}."
                    draft.kind == "Payment" -> "Posts cash. Any amount without an expense allocation remains an advance."
                    draft.kind in listOf("Plan", "Contract") -> "Future obligation only. Recorded cost and cash do not change."
                    else -> "Saves a work log. No cost or cash is posted."
                })
                if (draft.get("vendor").isNotBlank()) Text("Vendor: ${vendors.firstOrNull { it.id == draft.get("vendor") }?.name}")
                listOf("WORK", "COST", "PLACE").forEach { dimension -> classes.firstOrNull { it.id == draft.get(dimension) }?.let { Text("$dimension: ${classificationPath(it, classes)}") } }
                state.actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ vm.saveDraft { review = false; saved() } }, enabled = !state.saving) { Text(if (state.saving) "Saving…" else "Save entry") } },
            dismissButton = { TextButton({ review = false }, enabled = !state.saving) { Text("Back to edit") } })
    }
}

@Composable
internal fun InvoiceLines(vm: LedgerViewModel, projectId: String, draft: EntryDraft, change: (String, String) -> Unit, classes: List<ClassificationInfo>) {
    val text = draft.get("invoiceLines")
    val lines = remember(text) { if (text.isBlank()) emptyList() else LedgerViewModel.readLines(text) }
    var editId by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var adding by rememberSaveable(draft.id) { mutableStateOf(false) }
    var chargesConfirmation by remember { mutableStateOf<Boolean?>(null) }
    if (lines.isNotEmpty()) {
        Toggle("Invoice discount, tax and charges", draft.get("invoiceCharges") == "true", { enabled ->
            if (draft.get("revision").isNotBlank()) chargesConfirmation = enabled
            else change("invoiceCharges", enabled.toString())
        })
        if (draft.get("invoiceCharges") == "true") Section("Invoice charges", true) {
            Text("Line amounts are the base. Discount applies first; tax uses the discounted base. Transport and other charges are outside that tax base.")
            Field("Invoice discount amount (₹)", draft.get("discountAmount", "0"), { change("discountAmount", it) }, numeric = true)
            Field("Invoice discount percentage", draft.get("discountPercent", "0"), { change("discountPercent", it) }, numeric = true)
            Field("Invoice tax amount (₹)", draft.get("taxAmount", "0"), { change("taxAmount", it) }, numeric = true)
            Field("Invoice tax percentage", draft.get("taxPercent", "0"), { change("taxPercent", it) }, numeric = true)
            Field("Invoice transport (₹)", draft.get("transport", "0"), { change("transport", it) }, numeric = true)
            Field("Invoice other charge (₹)", draft.get("adjustment", "0"), { change("adjustment", it) }, numeric = true)
            val calculated = runCatching { Money.calculate(LedgerViewModel.invoiceDetails(draft)) }
            calculated.getOrNull()?.let { Text("Components (rounded independently): base ${Money.format(it.basePaise)}, discount ${Money.format(it.discountPaise)}, tax ${Money.format(it.taxPaise)}. Final cost ${Money.format(it.totalPaise)}. Charges are apportioned once across lines.") }
            calculated.exceptionOrNull()?.let { Text(it.message.orEmpty(), color = MaterialTheme.colorScheme.error) }
        }
    }
    chargesConfirmation?.let { enabled -> Confirm("Change invoice charges?", "The final total may change. Line base amounts stay intact; review the total before saving.", {
        change("invoiceCharges", enabled.toString())
    }, { chargesConfirmation = null }) }
    lines.forEachIndexed { index, line ->
        TextButton({ editId = line.id }) { Text("${index + 1}. ${line.description} · ${Money.format(line.amountPaise)} · Edit") }
        TextButton({
            val updated = lines.filterIndexed { i, _ -> i != index }
            if (LedgerViewModel.lineClasses(line).isNotEmpty()) change("classesChanged", "true")
            change("invoiceLines", JSONArray(updated.map { LedgerViewModel.lineJson(it) }).toString())
            if (updated.isNotEmpty()) change("amount", Money.input(Money.sum(updated.map { it.amountPaise })))
            else { change("invoiceLines", ""); change("invoiceCharges", "false"); change("mode", "DIRECT_AMOUNT") }
        }) { Text("Remove line ${index + 1}") }
    }
    OutlinedButton({ adding = true }) { Text("Add invoice line") }
    if (editId != null || adding) {
        val original = editId?.let { selected -> lines.firstOrNull { it.id == selected } }
        var description by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.description.orEmpty()) }
        var amount by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.amountPaise?.let(Money::input).orEmpty()) }
        var quantity by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.quantity.orEmpty()) }
        var rate by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.rate.orEmpty()) }
        var multiplier by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.multiplier ?: "1") }
        var quantityUnit by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.quantityUnit ?: "unit") }
        var rateUnit by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.rateUnit ?: "unit") }
        var nature by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.nature.orEmpty()) }
        var calculated by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.mode == "CALCULATED") }
        var lineWork by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.let { LedgerViewModel.lineClasses(it)["WORK"] }.orEmpty()) }
        var lineCost by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.let { LedgerViewModel.lineClasses(it)["COST"] }.orEmpty()) }
        var linePlace by rememberSaveable(draft.id, editId, adding) { mutableStateOf(original?.let { LedgerViewModel.lineClasses(it)["PLACE"] }.orEmpty()) }
        var error by rememberSaveable(draft.id, editId, adding) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { editId = null; adding = false }, title = { Text("Invoice line") }, text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Field("Line description", description, { description = it }) }
                item { Choice("Cost nature", nature, costNatureOptions, { nature = it }, allowNone = false) }
                item { Text("A blank line category uses the whole-expense category. If one line has a category for a dimension, each line needs one or a whole-expense fallback.") }
                listOf(Triple("WORK", "Line work stage", lineWork), Triple("COST", "Line cost head", lineCost),
                    Triple("PLACE", "Line place", linePlace)).forEach { (dimension, label, selected) ->
                    item { ClassificationChoice(vm, projectId, label, selected, classes.filter { kindDimension(it.kind) == dimension && (!it.archived || it.id == selected) }
                        .map { it.id to classificationPath(it, classes) }, classes,
                        when (dimension) { "WORK" -> "STAGE"; "COST" -> "COST_HEAD"; else -> "PLACE" }, choose = { id ->
                            when (dimension) { "WORK" -> lineWork = id; "COST" -> lineCost = id; else -> linePlace = id }
                        }) }
                }
                item { Toggle("Calculate quantity × rate", calculated, { calculated = it }) }
                if (calculated) {
                    item { Field("Quantity", quantity, { quantity = it }, numeric = true) }
                    item { Choice("Quantity unit", quantityUnit, listOf("unit", "sqft", "sq100", "kg", "tonne").map { it to it }, { quantityUnit = it }, allowNone = false) }
                    item { Field("Rate (₹)", rate, { rate = it }, numeric = true) }
                    item { Choice("Rate unit", rateUnit, listOf("unit", "sqft", "sq100", "kg", "tonne").map { it to it }, { rateUnit = it }, allowNone = false) }
                    item { Field("Multiplier", multiplier, { multiplier = it }, numeric = true) }
                } else {
                    item { Field(if (draft.get("invoiceCharges") == "true") "Line base amount (₹)" else "Line final amount (₹)", amount, { amount = it }, numeric = true) }
                    item { Text("Quantity and rate are optional reference details. The entered final amount stays authoritative.") }
                    item { Field("Quantity (optional)", quantity, { quantity = it }, numeric = true) }
                    item { Choice("Quantity unit", quantityUnit, listOf("unit", "sqft", "sq100", "kg", "tonne").map { it to it }, { quantityUnit = it }, allowNone = false) }
                    item { Field("Rate (optional, ₹)", rate, { rate = it }, numeric = true) }
                    item { Choice("Rate unit", rateUnit, listOf("unit", "sqft", "sq100", "kg", "tonne").map { it to it }, { rateUnit = it }, allowNone = false) }
                }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        }, confirmButton = { TextButton({
            try {
                require(description.isNotBlank()) { "Enter a line description." }
                val posted = if (calculated) Money.calculate(quantity, rate, multiplier, quantityUnit = quantityUnit, rateUnit = rateUnit).totalPaise else Money.parse(amount)
                if (!calculated) {
                    quantity.takeIf { it.isNotBlank() }?.let { require(Money.decimal(it).signum() > 0) { "Quantity must be greater than zero." } }
                    rate.takeIf { it.isNotBlank() }?.let { Money.parse(it, allowZero = true) }
                }
                val base = original?.copy(description = description, amountPaise = posted,
                    mode = if (calculated) "CALCULATED" else "DIRECT_AMOUNT",
                    quantity = quantity.ifBlank { null }, rate = rate.ifBlank { null },
                    multiplier = multiplier, quantityUnit = quantityUnit, rateUnit = rateUnit, nature = nature)
                    ?: ExpenseLineInput(newId(), description, posted, if (calculated) "CALCULATED" else "DIRECT_AMOUNT",
                        quantity.ifBlank { null }, rate.ifBlank { null }, multiplier, quantityUnit, rateUnit, nature = nature)
                val selectedClasses = mapOf("WORK" to lineWork, "COST" to lineCost, "PLACE" to linePlace).filterValues { it.isNotBlank() }
                val line = LedgerViewModel.withLineClasses(base, selectedClasses)
                val firstLine = if (lines.isEmpty() && editId == null && draft.get("description").isNotBlank())
                    LedgerViewModel.expense(draft).lines else emptyList()
                val updated = (lines + firstLine).toMutableList().also {
                    if (editId != null) {
                        val index = it.indexOfFirst { existing -> existing.id == editId }
                        require(index >= 0) { "This line changed. Reopen it before saving." }
                        it[index] = line
                    } else it.add(line)
                }
                LedgerViewModel.invoiceClassifications(updated, draft)
                if (original?.let { LedgerViewModel.lineClasses(it) }.orEmpty() != selectedClasses) change("classesChanged", "true")
                change("invoiceLines", JSONArray(updated.map { LedgerViewModel.lineJson(it) }).toString())
                change("amount", Money.input(Money.sum(updated.map { it.amountPaise }))); change("mode", "DIRECT_AMOUNT")
                editId = null; adding = false
            } catch (failure: Exception) { error = failure.message }
        }) { Text("Keep line") } }, dismissButton = { TextButton({ editId = null; adding = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ContractSchedule(draft: EntryDraft, change: (String, String) -> Unit) {
    Section("Instalment schedule", true) {
        val editing = draft.get("revision").isNotBlank()
        if (!editing) {
            Field("Number of instalments (1–120)", draft.get("count", "1"), { change("count", it) }, numeric = true)
            Choice("Frequency", draft.get("frequency", "Monthly"), listOf("Monthly", "Weekly").map { it to it }, { change("frequency", it) }, allowNone = false)
        }
        var error by remember { mutableStateOf<String?>(null) }
        if (!editing) {
            Button({
                try {
                    val copy = draft.with("schedules", "")
                    val rows = LedgerViewModel.commitment(copy).schedules
                    change("schedules", JSONArray(rows.map { JSONObject().put("id", it.id).put("amount", Money.input(it.amountPaise)).put("date", it.dueDate) }).toString())
                    error = null
                } catch (failure: Exception) { error = failure.message }
            }) { Text("Generate equal schedule") }
            Text("Equal division keeps every paise. Monthly dates use the original day, clamped only in shorter months. Edit each amount/date for a custom schedule.")
        } else {
            Text("Changing the commitment total spreads it proportionally across the existing instalments below. Dates and paid history stay unchanged. Custom amounts must add up to the new total.")
            if (draft.get("schedules").isNotBlank()) TextButton({ change("schedules", ""); error = null }) {
                Text("Use original dates and proportions")
            }
        }
        val preview = runCatching { LedgerViewModel.commitmentScheduleRows(draft) }
        val rows = preview.getOrElse { JSONArray(draft.get("schedules").ifBlank { draft.get("originalSchedules").ifBlank { "[]" } }) }
        val text = rows.toString()
        if (rows.length() > 0) {
            repeat(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                Text("Instalment ${index + 1}")
                if (row.has("paid") && row.getLong("paid") > 0) Text("Cash already paid: ${Money.format(row.getLong("paid"))}")
                Field("Instalment ${index + 1} amount (₹)", row.getString("amount"), {
                    val updated = JSONArray(text); updated.getJSONObject(index).put("amount", it); change("schedules", updated.toString())
                }, numeric = true)
                DateField("Instalment ${index + 1} date", row.getString("date"), {
                    val updated = JSONArray(text); updated.getJSONObject(index).put("date", it); change("schedules", updated.toString())
                })
            }
        }
        if (rows.length() > 0) preview.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun WorkTimer(draft: EntryDraft, change: (String, String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val started = draft.get("timerStarted").toLongOrNull()
    val stored = draft.get("timerSeconds").toLongOrNull() ?: 0
    LaunchedEffect(started) { while (started != null) { delay(1000); now = System.currentTimeMillis() } }
    val seconds = stored + if (started != null) ((now - started) / 1000).coerceAtLeast(0) else 0
    Section("Work log timer", true) {
        Text("%02d:%02d:%02d".format(seconds/3600,(seconds/60)%60,seconds%60),style=MaterialTheme.typography.headlineLarge,color=WebColors.teal)
        Text("Time and notes only. No cost or cash is posted.",color=WebColors.muted)
        Button({
            if (started == null) change("timerStarted", System.currentTimeMillis().toString())
            else {
                change("timerSeconds", seconds.toString()); change("timerStarted", "")
                change("note", draft.get("note").substringBefore("\nWork duration:") + "\nWork duration: $seconds seconds")
            }
        }) { Text(if (started == null) "Start timer" else "Stop timer") }
        TextButton({ change("timerStarted", ""); change("timerSeconds", "0") }) { Text("Reset timer") }
    }
}
