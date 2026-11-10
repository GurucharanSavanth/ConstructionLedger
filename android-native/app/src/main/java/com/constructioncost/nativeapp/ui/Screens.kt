package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.UiActions
import com.constructioncost.nativeapp.platform.CatalogSeeder
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun ActivityScreen(vm: LedgerViewModel, filter: ExpenseFilter, setFilter: (ExpenseFilter) -> Unit,
                            detail: (String, String) -> Unit, actions: UiActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val vendors by remember(project) { vm.repository.vendors(project) }.collectAsStateWithLifecycle(emptyList())
    val classes by remember(project) { vm.repository.classifications(project) }.collectAsStateWithLifecycle(emptyList())
    var page by rememberSaveable(project) { mutableIntStateOf(0) }
    var eventPage by rememberSaveable(project) { mutableIntStateOf(0) }
    var eventType by rememberSaveable(project) { mutableStateOf("ALL") }
    val filterError = when {
        filter.query.length > 200 -> "Search must be 200 characters or less."
        filter.fromDate.isNotBlank() && runCatching { LocalDate.parse(filter.fromDate) }.isFailure -> "Enter a valid from date."
        filter.toDate.isNotBlank() && runCatching { LocalDate.parse(filter.toDate) }.isFailure -> "Enter a valid to date."
        filter.fromDate.isNotBlank() && filter.toDate.isNotBlank() && filter.fromDate > filter.toDate -> "From date must be before to date."
        else -> null
    }
    val expenses by remember(project, filter, page, filterError) {
        if (filterError == null) vm.repository.expenses(project, filter, 76, page * 75) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val totals by remember(project, filter, filterError) {
        if (filterError == null) vm.repository.filteredExpenseSummary(project, filter) else flowOf(ExpenseSummary())
    }
        .collectAsStateWithLifecycle(ExpenseSummary())
    val visible = expenses.take(75)
    val events by remember(project, eventPage, eventType, filter.query, filter.fromDate, filter.toDate) {
        if (filterError == null) vm.repository.activity(project, 76, eventPage * 75, fromDate = filter.fromDate, toDate = filter.toDate,
            includeExpenses = false, query = filter.query, recordType = eventType.takeUnless { it == "ALL" }) else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Activity") }
        item { Field("Search activity", filter.query, { setFilter(filter.copy(query = it)); page = 0; eventPage = 0 }) }
        filterError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item { Section("Date and expense filters") {
            DateField("From date (all records)", filter.fromDate, { setFilter(filter.copy(fromDate = it)); page = 0; eventPage = 0 })
            DateField("To date (all records)", filter.toDate, { setFilter(filter.copy(toDate = it)); page = 0; eventPage = 0 })
            Choice("Expense status", filter.status, listOf("ALL", "OUTSTANDING", "UNPAID", "PARTIAL", "PAID", "UNCLASSIFIED", "UNCLASSIFIED_WORK", "UNCLASSIFIED_PLACE", "VOIDED").map { it to it }, { setFilter(filter.copy(status = it)); page = 0 }, false)
            Choice("Expense vendor", filter.vendorId, vendors.map { it.id to it.name }, { setFilter(filter.copy(vendorId = it.ifBlank { null })); page = 0 })
            Choice("Expense category", filter.classificationId, classes.map { it.id to classificationPath(it, classes) }, { setFilter(filter.copy(classificationId = it.ifBlank { null }, directClassification = false)); page = 0 })
            Choice("Expense sort", filter.sort, listOf("DATE_DESC" to "Newest", "DATE_ASC" to "Oldest", "AMOUNT_DESC" to "Largest", "AMOUNT_ASC" to "Smallest"), { setFilter(filter.copy(sort = it)); page = 0 }, false)
            TextButton({ setFilter(ExpenseFilter()); page = 0; eventPage = 0; eventType = "ALL" }) { Text("Clear filters") }
        } }
        item { Text("All ${totals.count} matching expenses · ${Money.format(totals.recordedPaise)} recorded · ${Money.format(totals.settledPaise)} settled · ${Money.format(totals.outstandingPaise)} outstanding") }
        item { Text("Page ${page + 1} · ${visible.size} expenses · ${Money.format(Money.sum(visible.filterNot { it.voided }.map { it.amountPaise }))} recorded · ${Money.format(Money.sum(visible.filterNot { it.voided }.map { it.settledPaise }))} settled on this page") }
        items(visible, key = { it.id }) { row -> RecordCard(row.description,
            "${row.date} · ${row.vendorName} · ${when { row.voided -> "Voided"; row.outstandingPaise == 0L -> "Paid"; row.settledPaise > 0 -> "Partial"; else -> "Unpaid" }}",
            Money.format(row.amountPaise)) { detail(row.id, "EXPENSE") } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ page-- }, enabled = page > 0) { Text("Previous 75") }
            OutlinedButton({ page++ }, enabled = expenses.size > 75) { Text("Next 75") }
        } }
        item { Title("Other ledger events") }
        item { Choice("Record type", eventType, listOf("ALL" to "All other events", "PAYMENT" to "Payment", "REVERSAL" to "Reversal", "REFUND" to "Refund",
            "INSTALMENT" to "Instalment", "COMMITMENT" to "Commitment", "NOTE" to "Note", "ATTACHMENT" to "Attachment"),
            { eventType = it; eventPage = 0 }, false) }
        items(events.take(75), key = { "${it.type}-${it.id}" }) { row ->
            RecordCard(row.title, "${row.type} · ${row.date}", row.amountPaise?.let(Money::format)) { detail(row.id, if (row.type == "ATTACHMENT") "DOCUMENT" else row.type) }
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ eventPage-- }, enabled = eventPage > 0) { Text("Previous events") }
            OutlinedButton({ eventPage++ }, enabled = events.size > 75) { Text("Next events") }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ actions.exportCsv(project) }) { Text("Export CSV") }
            OutlinedButton({ actions.exportPdf(project) }) { Text("Export PDF") }
        } }
    }
}

@Composable
internal fun CalendarScreen(vm: LedgerViewModel, add: () -> Unit, detail: (String, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var dateInput by rememberSaveable { mutableStateOf(selected) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val month = YearMonth.parse(selected.take(7))
    val events by remember(project, selected, page) {
        vm.repository.activity(project, 76, page * 75, selected, selected)
    }.collectAsStateWithLifecycle(emptyList())
    val leading = month.atDay(1).dayOfWeek.value - 1
    val weeks = (leading + month.lengthOfMonth() + 6) / 7
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Calendar") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ selected = month.minusMonths(1).atDay(1).toString(); dateInput = selected; page = 0 }) { Text("Previous month") }
            Text(month.toString(), style = MaterialTheme.typography.titleLarge)
            TextButton({ selected = month.plusMonths(1).atDay(1).toString(); dateInput = selected; page = 0 }) { Text("Next month") }
        } }
        item { Row(Modifier.fillMaxWidth()) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { Text(it, Modifier.weight(1f)) }
        } }
        item { Column {
            repeat(weeks) { week -> Row(Modifier.fillMaxWidth()) {
                repeat(7) { weekday ->
                    val number = week * 7 + weekday - leading + 1
                    if (number in 1..month.lengthOfMonth()) {
                        val date = month.atDay(number).toString()
                        TextButton({ selected = date; dateInput = date; page = 0 },
                            Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                contentDescription = "$date${if (selected == date) ", selected" else ""}"
                            }) { Text(number.toString()) }
                    } else Spacer(Modifier.weight(1f))
                }
            } }
        } }
        item { DateField("Jump to day", dateInput, { dateInput = it }) }
        item { TextButton({
            runCatching { LocalDate.parse(dateInput).toString() }.onSuccess { selected = it; page = 0 }
                .onFailure { vm.showError("Enter a valid date in YYYY-MM-DD format.") }
        }) { Text("Show selected day") } }
        item { Button({ vm.resetDraft("Plan"); vm.field("date", selected); add() }) { Text("Add plan for selected day") } }
        item { Title("Events on $selected") }
        items(events.take(75), key = { "${it.type}-${it.id}" }) { row ->
            RecordCard(row.title, row.type, row.amountPaise?.let(Money::format)) {
                detail(row.id, if (row.type == "ATTACHMENT") "DOCUMENT" else row.type)
            }
        }
        if (events.isEmpty()) item { Text("No ledger events on this day.") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ page-- }, enabled = page > 0) { Text("Previous events") }
            OutlinedButton({ page++ }, enabled = events.size > 75) { Text("Next events") }
        } }
    }
}

@Composable
internal fun InsightsScreen(vm: LedgerViewModel, activity: (ExpenseFilter) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    var dimension by rememberSaveable { mutableStateOf("WORK") }
    var parent by rememberSaveable { mutableStateOf<String?>(null) }
    var plannedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var plannedPage by rememberSaveable { mutableIntStateOf(0) }
    val classes by remember(project) { vm.repository.classifications(project) }.collectAsStateWithLifecycle(emptyList())
    val rows by remember(project, dimension, parent) { vm.repository.insights(project, parent, dimension) }.collectAsStateWithLifecycle(emptyList())
    val nature by remember(project) { vm.repository.costNature(project) }.collectAsStateWithLifecycle(emptyList())
    val planned by remember(project, plannedCategory, plannedPage) {
        vm.repository.plannedInsights(project, plannedCategory, 13, plannedPage * 12)
    }.collectAsStateWithLifecycle(emptyList())
    val visiblePlanned = planned.take(12)
    val monthly by remember(project) { vm.repository.monthly(project) }.collectAsStateWithLifecycle(emptyList())
    val summary by remember(project) { vm.repository.summary(project) }.collectAsStateWithLifecycle(LedgerSummary())
    var showOther by rememberSaveable(project, dimension) { mutableStateOf(false) }
    val comparison = insightComparison(rows)
    val ranked = comparison.ranked
    val other = comparison.other
    val breakdownTotal = comparison.totalPaise
    val unclassified = comparison.unclassifiedPaise
    val covered = comparison.coveredPaise
    val visibleRows = if (parent == null && !showOther) ranked.take(5) else if (parent == null) ranked else rows.filter { it.amountPaise > 0 || it.id != null }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Insights") }
        item { Text("Recorded cost ${Money.format(summary.recordedPaise)} · cash ${Money.format(summary.paidPaise)} · outstanding ${Money.format(summary.outstandingPaise)}") }
        item { Choice("Breakdown", dimension, listOf("WORK" to "Work stages", "COST" to "Cost heads", "PLACE" to "Places"), { dimension = it; parent = null }, false) }
        if (parent != null) item { TextButton({ parent = classes.firstOrNull { it.id == parent }?.parentId }) { Text("Up one level") } }
        item { Text(parent?.let { id -> classes.firstOrNull { it.id == id }?.let { classificationPath(it, classes) } } ?: "All $dimension costs") }
        if (parent == null && ranked.isNotEmpty()) {
            item { Title("At a glance") }
            item { RecordCard("Largest segment", ranked.first().name,
                "${Money.format(ranked.first().amountPaise)} · ${insightSharePercent(ranked.first().amountPaise, breakdownTotal)}%") }
            item { RecordCard("Coverage", "${Money.format(unclassified)} unclassified",
                "${insightSharePercent(covered, breakdownTotal)}% categorised") }
            item { RecordCard("Spread", "${ranked.size} groups with recorded cost",
                "${Money.format(comparison.otherPaise)} outside the top five") }
            item { Title(if (showOther) "All ranked groups" else "Top five groups") }
        } else if (parent == null) item { Text("No recorded costs to compare yet.") }
        items(visibleRows, key = { it.id ?: "unclassified" }) { row ->
            RecordCard(row.name, "${row.expenseCount} expenses · ${row.kind}", Money.format(row.amountPaise)) {
                if (row.kind == "DIRECT_ALLOCATION") activity(ExpenseFilter(classificationId = row.id, directClassification = true))
                else if (row.id != null && classes.any { it.parentId == row.id }) parent = row.id
                else activity(if (row.id == null) ExpenseFilter(status = when (dimension) { "WORK" -> "UNCLASSIFIED_WORK"; "PLACE" -> "UNCLASSIFIED_PLACE"; else -> "UNCLASSIFIED" }) else ExpenseFilter(classificationId = row.id))
            }
            InsightShareBar(row.name, row.amountPaise, summary.recordedPaise)
            TextButton({ activity(if (row.id == null) ExpenseFilter(status = when (dimension) { "WORK" -> "UNCLASSIFIED_WORK"; "PLACE" -> "UNCLASSIFIED_PLACE"; else -> "UNCLASSIFIED" }) else ExpenseFilter(classificationId = row.id, directClassification = row.kind == "DIRECT_ALLOCATION")) }) { Text("View matching expenses") }
            if (row.id != null) TextButton({ plannedCategory = row.id; plannedPage = 0 }) { Text("Show related plans and commitments") }
        }
        if (parent == null && other.isNotEmpty()) item {
            RecordCard("Other", "${other.size} groups outside the top five · ${if (showOther) "hide" else "show"} exact groups",
                Money.format(comparison.otherPaise)) { showOther = !showOther }
        }
        item { Title("Cost nature") }
        item { Text("Recorded expenses only") }
        items(nature, key = { it.nature }) { row ->
            RecordCard(row.nature.ifBlank { "Unclassified / Legacy" }, "Recorded cost", Money.format(row.amountPaise))
            InsightShareBar(row.nature.ifBlank { "Unclassified / Legacy" }, row.amountPaise, summary.recordedPaise)
        }
        item { Title("Future plans and commitments") }
        item { Choice("Related category", plannedCategory, classes.filter { !it.archived }.map { it.id to classificationPath(it, classes) },
            { plannedCategory = it.ifBlank { null }; plannedPage = 0 }) }
        item { Text("Schedules and agreements are shown separately from recorded cost and cash.") }
        items(visiblePlanned.filter { it.type == "INSTALMENT" }, key = { "plan-${it.id}" }) { row ->
            RecordCard(row.title, "Plan · ${row.date}", Money.format(row.amountPaise))
        }
        items(visiblePlanned.filter { it.type == "COMMITMENT" }, key = { "agreement-${it.id}" }) { row ->
            RecordCard(row.title, "Commitment · ${row.date}", Money.format(row.amountPaise))
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ plannedPage-- }, enabled = plannedPage > 0) { Text("Previous plans") }
            OutlinedButton({ plannedPage++ }, enabled = planned.size > 12) { Text("Next plans") }
        } }
        item { Title("Monthly recorded cost and cash") }
        items(monthly, key = { it.month }) { row -> RecordCard(row.month,
            "Cash paid ${Money.format(row.paidPaise)}", Money.format(row.recordedPaise)) }
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

@Composable
private fun InsightShareBar(label: String, amountPaise: Long, totalPaise: Long) {
    val percent = insightSharePercent(amountPaise, totalPaise)
    val progress = if (totalPaise <= 0) 0f else BigDecimal.valueOf(amountPaise)
        .divide(BigDecimal.valueOf(totalPaise), 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    Text("$percent% of recorded cost", style = MaterialTheme.typography.labelMedium)
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(6.dp).semantics { contentDescription = "$label: $percent% of recorded cost" }
    )
}

@Composable
internal fun PaymentsScreen(vm: LedgerViewModel, add: () -> Unit, detail: (String, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val payments by remember(project) { vm.repository.payments(project) }.collectAsStateWithLifecycle(emptyList())
    val plans by remember(project) { vm.repository.instalments(project) }.collectAsStateWithLifecycle(emptyList())
    val agreements by remember(project) { vm.repository.commitments(project) }.collectAsStateWithLifecycle(emptyList())
    val expenses by remember(project) { vm.repository.expenses(project, ExpenseFilter(), 500) }.collectAsStateWithLifecycle(emptyList())
    var source by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    fun available(payment: PaymentRow): Long = payment.amountPaise - payment.allocatedPaise -
        Money.sum(payments.filter { it.originalPaymentId == payment.id }.map { it.amountPaise - it.allocatedPaise })
    val selected = payments.firstOrNull { it.id == source }
    val available = selected?.let(::available) ?: 0
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Payments") }
        item { Button({ vm.resetDraft("Payment"); add() }) { Text("Record payment") } }
        item { Section("Apply an advance") {
            Text("This applies existing cash to a cost. It does not post another payment.")
            Choice("Payment with available advance", source, payments.filter { it.kind == "PAYMENT" && available(it) > 0 }
                .map { it.id to "${it.date} · ${it.vendorName} · ${Money.format(available(it))} available" }, { source = it; target = "" })
            Choice("Unpaid cost", target, expenses.filter { !it.voided && it.outstandingPaise > 0 && (selected?.vendorId == null || it.vendorId == null || selected.vendorId == it.vendorId) }
                .map { it.id to "${it.description} · ${Money.format(it.outstandingPaise)} due" }, { target = it })
            Field("Amount to apply (₹)", amount, { amount = it }, true)
            Button({
                vm.command("Advance applied") {
                    val paise = Money.parse(amount)
                    require(source.isNotBlank() && target.isNotBlank() && paise <= available) { "Choose a payment, expense and amount within the available advance." }
                    vm.repository.allocateAdvance(source, target, paise, newId())
                    source = ""; target = ""; amount = ""
                }
            }, enabled = !state.saving) { Text("Apply advance") }
        } }
        item { Title("Scheduled instalments") }
        items(plans, key = { it.id }) { row -> RecordCard(row.title,
            "${row.dueDate} · ${if (row.cancelled) "Cancelled" else if (row.outstandingPaise == 0L) "Paid" else if (row.dueDate < LocalDate.now().toString()) "Overdue" else "Upcoming"}",
            Money.format(row.outstandingPaise)) { detail(row.id, "INSTALMENT") } }
        item { TextButton({ vm.resetDraft("Plan"); add() }) { Text("Add one-off plan") } }
        item { Title("Commitments") }
        items(agreements, key = { it.id }) { row -> RecordCard(row.title, row.date, Money.format(row.amountPaise)) { detail(row.id, "COMMITMENT") } }
        item { TextButton({ vm.resetDraft("Contract"); add() }) { Text("Add commitment and instalments") } }
        item { Title("Cash transactions") }
        items(payments, key = { it.id }) { row -> RecordCard(row.reference.ifBlank { row.kind },
            "${row.date} · ${row.vendorName} · ${row.kind} · allocated ${Money.format(row.allocatedPaise)}",
            Money.format(row.amountPaise)) { detail(row.id, row.kind) } }
    }
}

@Composable
internal fun DocumentsScreen(vm: LedgerViewModel, actions: UiActions, detail: (String, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val documents by remember(project) { vm.repository.attachments(project) }.collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable(project) { mutableStateOf("") }
    var categoryFilter by rememberSaveable(project) { mutableStateOf("<all>") }
    var statusFilter by rememberSaveable(project) { mutableStateOf("<all>") }
    val categories = documents.map { it.category }.distinct().sorted()
    val statuses = documents.map { it.status }.distinct().sorted()
    val matching = documents.filter { file ->
        (categoryFilter == "<all>" || file.category == categoryFilter) &&
            (statusFilter == "<all>" || file.status == statusFilter) &&
            listOf(file.name, file.mimeType, file.category, file.status, file.revisionLabel, file.work, file.place, file.notes)
                .any { it.contains(query.trim(), ignoreCase = true) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Documents") }
        item { Button({ actions.importDocument(project) }) { Text("Import document") } }
        actions.capturePhoto?.let { capture -> item {
            OutlinedButton({ capture(project) }) { Text("Take photo of document") }
        } }
        item { Field("Search documents", query, { query = it }) }
        item { Choice("Category filter", categoryFilter,
            listOf("<all>" to "All categories") + categories.map { it to it.ifBlank { "Unclassified" } },
            { categoryFilter = it }, allowNone = false) }
        item { Choice("Status filter", statusFilter,
            listOf("<all>" to "All statuses") + statuses.map { it to it.ifBlank { "Unspecified" } },
            { statusFilter = it }, allowNone = false) }
        if (matching.isEmpty()) item { Text("No documents match these filters.") }
        items(matching, key = { it.id }) { file ->
            RecordCard(file.name,
                (listOf(file.category, file.status, file.revisionLabel, file.work, file.place).filter { it.isNotBlank() } +
                    listOf(file.mimeType, "${file.size} bytes", "${file.referenceCount} links")).joinToString(" · ")) { detail(file.id, "DOCUMENT") }
        }
    }
}

@Composable
internal fun VendorsScreen(vm: LedgerViewModel, activity: (ExpenseFilter) -> Unit, detail: (String, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val vendors by remember(project) { vm.repository.vendors(project) }.collectAsStateWithLifecycle(emptyList())
    val payments by remember(project) { vm.repository.payments(project) }.collectAsStateWithLifecycle(emptyList())
    var name by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf("") }
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
internal fun ProjectsScreen(vm: LedgerViewModel) {
    val context = LocalContext.current.applicationContext
    val state by vm.state.collectAsStateWithLifecycle()
    val projects by vm.repository.projects.collectAsStateWithLifecycle(emptyList())
    var id by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var budget by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Projects") }
        item { Section(if (id.isBlank()) "Add project" else "Edit project") {
            Field("Project name", name, { name = it })
            Field("Budget (₹, optional)", budget, { budget = it }, true)
            Button({ vm.command("Project saved") {
                val existing = projects.firstOrNull { it.id == id }
                val projectId = id.ifBlank { newId() }
                vm.repository.saveProject(ProjectInfo(projectId, name.trim(), budget.takeIf { it.isNotBlank() }?.let { Money.parse(it, true) }, existing?.revision ?: 0))
                CatalogSeeder(context, vm.repository).seed(projectId)
                vm.selectProject(projectId)
                id = ""; name = ""; budget = ""
            } }, enabled = !state.saving) { Text("Save project") }
        } }
        items(projects, key = { it.id }) { project ->
            RecordCard(project.name, if (project.id == state.projectId) "Selected project" else "Tap to select",
                project.budgetPaise?.let(Money::format)) { vm.selectProject(project.id) }
            TextButton({ id = project.id; name = project.name; budget = project.budgetPaise?.let(Money::input).orEmpty() }) { Text("Edit ${project.name}") }
        }
    }
}

@Composable
internal fun CategoriesScreen(vm: LedgerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val classes by remember(project) { vm.repository.classifications(project) }.collectAsStateWithLifecycle(emptyList())
    var editing by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("STAGE") }
    var parent by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var merge by remember { mutableStateOf(false) }
    var archiveCandidate by remember { mutableStateOf<ClassificationInfo?>(null) }
    val kinds = listOf("STAGE", "SUBSTAGE", "MATERIAL", "COST_HEAD", "COST_SUBHEAD", "COST_DETAIL", "PLACE")
    val parentKind = when (kind) { "SUBSTAGE" -> "STAGE"; "MATERIAL" -> "SUBSTAGE"; "COST_SUBHEAD" -> "COST_HEAD"; "COST_DETAIL" -> "COST_SUBHEAD"; "PLACE" -> "PLACE"; else -> "" }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Title("Categories") }
        item { Section(if (editing.isBlank()) "Add category" else "Edit category") {
            Field("Name", name, { name = it })
            Choice("Type", kind, kinds.map { it to it.replace('_', ' ') }, { kind = it; parent = "" }, false)
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
            Choice("Target", target, classes.filter { !it.archived && it.id != source && source.isNotBlank() && kindDimension(it.kind) == kindDimension(classes.first { source == it.id }.kind) }
                .map { it.id to classificationPath(it, classes) }, { target = it })
            OutlinedButton({ merge = true }, enabled = source.isNotBlank() && target.isNotBlank()) { Text("Merge source into target") }
            OutlinedButton({ vm.command("Expenses reassigned") { vm.repository.reassignClassification(source, target); source = ""; target = "" } }, enabled = source.isNotBlank() && target.isNotBlank() && !state.saving) { Text("Reassign expenses only") }
        } }
        items(classes.filter { state.showArchived || !it.archived }, key = { it.id }) { category ->
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
    var backupPasswordError by remember { mutableStateOf(false) }
    var legacyPassword by remember { mutableStateOf("") }
    var legacyPasswordError by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Backup and recovery") }
        item { Section("Backup password (optional)", true) {
            Text("Leave blank for a plain ZIP. Enter a password to protect a new backup or open an encrypted backup. Keep it safely: there is no password recovery.")
            OutlinedTextField(value = backupPassword, onValueChange = { backupPassword = it; backupPasswordError = false },
                label = { Text("Backup password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            if (backupPasswordError) Text("Use 10 to 1024 characters, or leave blank.", color = MaterialTheme.colorScheme.error)
        } }
        item { RecordCard("Create backup", "Export records and original documents to a local file.") {
            if (backupPassword.isNotEmpty() && backupPassword.length !in 10..1024) backupPasswordError = true
            else { val password = backupPassword.ifEmpty { null }; backupPassword = ""; actions.createBackup(password) }
        } }
        item { RecordCard("Restore backup", "Verify an archive before replacing this local ledger.") {
            if (backupPassword.isNotEmpty() && backupPassword.length !in 10..1024) backupPasswordError = true
            else { val password = backupPassword.ifEmpty { null }; backupPassword = ""; actions.restoreBackup(password) }
        } }
        item { Section("Import legacy ledger", true) {
            Text("Enter the legacy archive password, then choose its export file.")
            OutlinedTextField(value = legacyPassword, onValueChange = { legacyPassword = it; legacyPasswordError = false },
                label = { Text("Legacy recovery password") }, visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag("legacyRecoveryPassword"))
            if (legacyPasswordError) Text("Enter at least 10 characters.", color = MaterialTheme.colorScheme.error)
            Button({
                if (legacyPassword.length < 10) legacyPasswordError = true
                else {
                    val password = legacyPassword
                    legacyPassword = ""
                    actions.importLegacy(password)
                }
            }) { Text("Choose legacy export") }
        } }
        item { RecordCard("Export CSV", "Download ledger records as a spreadsheet file.") { actions.exportCsv(state.projectId) } }
        item { RecordCard("Export PDF", "Create a local financial report.") { actions.exportPdf(state.projectId) } }
    }
}

@Composable
internal fun SettingsScreen(vm: LedgerViewModel, actions: UiActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val projects by vm.repository.projects.collectAsStateWithLifecycle(emptyList())
    val selected = projects.firstOrNull { it.id == project }
    var name by rememberSaveable(selected?.id) { mutableStateOf(selected?.name.orEmpty()) }
    var budget by rememberSaveable(selected?.id) { mutableStateOf(selected?.budgetPaise?.let(Money::input).orEmpty()) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Local settings") }
        item { Text("This ledger is stored on this device. Use Backup to make a portable copy.") }
        if (selected != null) item { Section("Current project", true) {
            Field("Name", name, { name = it })
            Field("Budget (₹, optional)", budget, { budget = it }, true)
            Button({ vm.command("Project settings saved") {
                vm.repository.saveProject(selected.copy(name = name.trim(), budgetPaise = budget.takeIf { it.isNotBlank() }?.let { Money.parse(it, true) }))
            } }, enabled = !state.saving) { Text("Save project settings") }
        } }
        item { AppUpdates(manual = true) }
        item { Toggle("Local schedule reminders", state.reminders, { vm.settings(reminders = it); actions.setReminders(it) }) }
        item { Toggle("Show archived categories", state.showArchived, { vm.settings(showArchived = it) }) }
    }
}
