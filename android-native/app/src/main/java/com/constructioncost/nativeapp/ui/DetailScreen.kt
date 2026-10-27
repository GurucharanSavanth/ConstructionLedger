package com.constructioncost.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.UiActions
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
internal fun DetailScreen(vm: LedgerViewModel, id: String, type: String, actions: UiActions,
                          edit: () -> Unit, detail: (String, String) -> Unit, showTitle: Boolean = true, onDeleted: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val payments = remember(project, type) { if (type in setOf("EXPENSE", "PAYMENT", "REVERSAL", "REFUND")) vm.repository.payments(project) else flowOf(emptyList()) }
        .readLedger(vm) ?: return StartupScreen("Loading saved records")
    val plans = remember(project, type) { if (type in setOf("INSTALMENT", "COMMITMENT")) vm.repository.instalments(project) else flowOf(emptyList()) }
        .readLedger(vm) ?: return StartupScreen("Loading saved records")
    val commitments = remember(project, type) { if (type == "COMMITMENT") vm.repository.commitments(project, includeArchived = true) else flowOf(emptyList()) }
        .readLedger(vm) ?: return StartupScreen("Loading saved records")
    val notes = remember(project, type) { if (type == "NOTE") vm.repository.notes(project) else flowOf(emptyList()) }
        .readLedger(vm) ?: return StartupScreen("Loading saved records")
    val files = remember(project, type) { if (type in setOf("EXPENSE", "PAYMENT", "REVERSAL", "REFUND", "DOCUMENT")) vm.repository.attachments(project) else flowOf(emptyList()) }
        .readLedger(vm) ?: return StartupScreen("Loading saved records")
    val classifications = remember(project, type) { if (type == "DOCUMENT") vm.repository.classifications(project) else flowOf(emptyList()) }
        .readLedger(vm) ?: return StartupScreen("Loading saved records")
    val auditTable = when (type) {
        "EXPENSE" -> "expenses"
        "PAYMENT", "REVERSAL", "REFUND" -> "payments"
        "INSTALMENT" -> "instalments"
        "COMMITMENT" -> "commitments"
        "NOTE" -> "activity_entries"
        "DOCUMENT" -> "attachments"
        else -> ""
    }
    val audits = remember(id, auditTable, project) {
        if (auditTable.isBlank()) flowOf(emptyList()) else vm.repository.audits(id, auditTable, project)
    }.readLedger(vm) ?: return StartupScreen("Loading saved records")
    val fetchedExpense = ledgerLookup(vm, id, type, project, audits) {
        if (type == "EXPENSE") vm.repository.expense(id)?.takeIf { it.projectId == project } else null
    }
    val expense = fetchedExpense?.value
    val fetchedPaymentExpense = ledgerLookup(vm, id, type, project, audits, payments) {
        if (type == "EXPENSE") vm.repository.expenseRow(id)?.takeIf { it.projectId == project } else null
    }
    val paymentExpense = fetchedPaymentExpense?.value
    var reason by rememberSaveable(id) { mutableStateOf("") }
    var date by rememberSaveable(id) { mutableStateOf(LocalDate.now().toString()) }
    var refund by rememberSaveable(id) { mutableStateOf("") }
    var name by rememberSaveable(id) { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    val payment = payments.firstOrNull { it.id == id && it.projectId == project }
    val receiptSnapshot = ledgerLookup(vm, payment?.id, audits) {
        if (payment != null) vm.repository.paymentAttachmentIds(payment.id) else emptyList()
    }
    val paymentReceiptIds = receiptSnapshot?.value.orEmpty()
    val scope = rememberCoroutineScope()
    var paymentEdit by remember(id, project) { mutableStateOf<PaymentEditState?>(null) }
    var paymentEditId by rememberSaveable(id, project) { mutableStateOf("") }
    var paymentEditFingerprint by rememberSaveable(id, project) { mutableStateOf("") }
    LaunchedEffect(id, project, paymentEditId, paymentEditFingerprint) {
        if (paymentEditId.isNotBlank() && paymentEditFingerprint.isNotBlank() && paymentEdit == null) {
            try {
                val current = vm.repository.paymentEditState(paymentEditId)
                if (current.fingerprint == paymentEditFingerprint) paymentEdit = current
                else {
                    paymentEditId = ""; paymentEditFingerprint = ""
                    vm.showError("Payment changed while this correction was open. Reopen it and review the latest version.")
                }
            } catch (failure: Exception) {
                paymentEditId = ""; paymentEditFingerprint = ""
                if (failure is android.database.sqlite.SQLiteException) vm.storageFailure(failure)
                else vm.showError(failure.message ?: "Payment correction could not be reopened.")
            }
        }
    }
    var allocationPage by rememberSaveable(id) { mutableIntStateOf(0) }
    var allocationRefresh by remember(id) { mutableIntStateOf(0) }
    val allocationSnapshot = ledgerLookup(vm, payment?.id, allocationPage, allocationRefresh, audits) {
        if (type == "PAYMENT" && payment?.kind == "PAYMENT")
            vm.repository.paymentAllocationStates(payment.id, 26, allocationPage * 25) else emptyList()
    }
    val allocationRows = allocationSnapshot?.value.orEmpty()
    val allocationNamesLookup = ledgerLookup(vm, allocationRows) {
        allocationRows.associate { row -> row.expenseId to
            (vm.repository.expense(row.expenseId)?.description ?: row.expenseId) }
    }
    val allocationNames = allocationNamesLookup?.value.orEmpty()
    var correction by remember(id) { mutableStateOf<AllocationCorrectionState?>(null) }
    var correctionLoading by remember(id) { mutableStateOf(false) }
    var correctionAmount by remember(id) { mutableStateOf("") }
    var correctionMode by remember(id) { mutableStateOf("release") }
    var correctionTarget by remember(id) { mutableStateOf("") }
    var correctionTargetLabel by remember(id) { mutableStateOf("") }
    var correctionQuery by remember(id) { mutableStateOf("") }
    var correctionPage by remember(id) { mutableIntStateOf(0) }
    var correctionKey by remember(id) { mutableStateOf("") }
    var correctionConfirm by remember(id) { mutableStateOf(false) }
    val loadedTargetExpenses = remember(project, type, correctionMode, correction, correctionQuery, correctionPage) {
        if (type == "PAYMENT" && correction != null && correctionMode == "transfer")
            vm.repository.expenses(project, ExpenseFilter(query = correctionQuery.trim(), status = "OUTSTANDING"), 76, correctionPage * 75)
        else flowOf(emptyList())
    }.readLedger(vm)
    val targetExpenses = loadedTargetExpenses.orEmpty()
    val plan = plans.firstOrNull { it.id == id && it.projectId == project }
    val agreement = commitments.firstOrNull { it.id == id && it.projectId == project }
    val note = notes.firstOrNull { it.id == id && it.projectId == project }
    val file = files.firstOrNull { it.id == id && it.projectId == project }
    var linkRefresh by remember(id) { mutableIntStateOf(0) }
    val linkSnapshot = ledgerLookup(vm, id, project, linkRefresh, audits) {
        if (type == "DOCUMENT") vm.repository.attachmentLinks(id) else emptyList()
    }
    val links = linkSnapshot?.value.orEmpty()
    var linkType by rememberSaveable(id) { mutableStateOf("EXPENSE") }
    var linkQuery by rememberSaveable(id) { mutableStateOf("") }
    var linkPage by rememberSaveable(id) { mutableIntStateOf(0) }
    var linkTarget by rememberSaveable(id) { mutableStateOf("") }
    var linkTargetLabel by rememberSaveable(id) { mutableStateOf("") }
    var unlinkTarget by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    val loadedLinkCandidates = remember(project, type, linkType, linkQuery, linkPage) {
        if (type == "DOCUMENT" && linkQuery.length <= 200)
            vm.repository.activity(project, 76, linkPage * 75, includeExpenses = true,
                query = linkQuery, recordType = linkType)
        else flowOf(emptyList())
    }.readLedger(vm)
    val linkCandidates = loadedLinkCandidates.orEmpty()
    if (fetchedExpense == null || fetchedPaymentExpense == null || receiptSnapshot == null ||
        allocationSnapshot == null || allocationNamesLookup == null || linkSnapshot == null)
        return StartupScreen("Loading saved entry")
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) item { Title("Entry detail") }
        when (type) {
            "EXPENSE" -> {
                if (expense == null) item { Text("Expense is unavailable in the selected project.") }
                expense?.let { row ->
                    item { RecordCard(row.description, "${row.date} · ${if (row.revision == null) "" else "Revision ${row.revision}"}", Money.format(row.amountPaise)) }
                    item { Text("${if (row.mode == "CALCULATED") "Calculated" else "Final amount"} · ${row.note}") }
                    items(row.lines, key = { it.id }) { line -> RecordCard(line.description, line.mode, Money.format(line.amountPaise)) }
                    item { Text("Classifications: ${row.classifications.size} · linked documents: ${row.attachmentIds.size}") }
                    if (paymentExpense?.voided == true) item { Text("Voided expense") }
                    if (paymentExpense?.voided == false) item { Button({ vm.editExpense(row.id, edit) }) { Text("Edit expense") } }
                    paymentExpense?.takeIf { !it.voided && it.outstandingPaise > 0 }?.let { unpaid -> item {
                        OutlinedButton({ vm.preparePayment(expense = unpaid, then = edit) }) { Text("Record payment of ${Money.format(unpaid.outstandingPaise)}") }
                    } }
                    if (row.revision != null && paymentExpense?.voided == false) item { OutlinedButton({ action = "delete bill" }) { Text("Delete bill") } }
                    items(row.attachmentIds, key = { it }) { attachmentId ->
                        files.firstOrNull { it.id == attachmentId && it.projectId == project }?.let { linked -> TextButton({ detail(linked.id, "DOCUMENT") }) { Text("Open ${linked.name}") } }
                    }
                }
            }
            "PAYMENT", "REVERSAL", "REFUND" -> {
                payment?.let { row ->
                    item { RecordCard(row.reference.ifBlank { row.kind }, "${row.date} · ${row.vendorName} · ${row.kind}", Money.format(row.amountPaise)) }
                    item { Text("Allocated ${Money.format(row.allocatedPaise)} · ${row.note}") }
                    if (paymentReceiptIds.isNotEmpty()) item { Title("Linked receipts") }
                    items(paymentReceiptIds, key = { it }) { receiptId ->
                        files.firstOrNull { it.id == receiptId && it.projectId == project }?.let { receipt ->
                            RecordCard(receipt.name, "${receipt.mimeType} · ${receipt.size} bytes") { actions.viewDocument(receipt) }
                        }
                    }
                    row.originalPaymentId?.let { original -> item { TextButton({ detail(original, "PAYMENT") }) { Text("View original payment") } } }
                    if (row.kind == "PAYMENT") {
                        val hasRefundOrReversal = payments.any { it.originalPaymentId == row.id && it.kind in setOf("REFUND", "REVERSAL") }
                        if (!hasRefundOrReversal) item {
                            OutlinedButton({ vm.command("Payment loaded for editing") {
                                paymentEdit = vm.repository.paymentEditState(row.id)
                                paymentEditId = paymentEdit?.payment?.id.orEmpty()
                                paymentEditFingerprint = paymentEdit?.fingerprint.orEmpty()
                            } }, enabled = !state.saving) { Text("Edit payment") }
                        }
                        if (hasRefundOrReversal) item { Text("Allocation corrections are unavailable after a refund or reversal.") }
                        if (!hasRefundOrReversal && allocationRows.isNotEmpty()) item { Title("Payment allocations") }
                        if (!hasRefundOrReversal) items(allocationRows.take(25), key = { it.allocationId }) { allocation ->
                            RecordCard(allocationNames[allocation.expenseId] ?: allocation.expenseId,
                                "Allocated to expense", Money.format(allocation.amountPaise))
                            OutlinedButton({
                                correctionLoading = true
                                scope.launch {
                                    try {
                                        val snapshot = vm.repository.allocationCorrectionState(allocation.allocationId)
                                            ?: error("Allocation no longer exists")
                                        correction = snapshot
                                        correctionAmount = Money.input(snapshot.amountPaise)
                                        correctionMode = "release"
                                        correctionTarget = ""
                                        correctionTargetLabel = ""
                                        correctionQuery = ""
                                        correctionPage = 0
                                        correctionKey = UUID.randomUUID().toString()
                                    } catch (failure: Exception) {
                                        if (failure is android.database.sqlite.SQLiteException) vm.storageFailure(failure)
                                        else vm.showError(failure.message ?: "Allocation could not be opened.")
                                    } finally { correctionLoading = false }
                                }
                            }, modifier = Modifier.semantics {
                                contentDescription = "Correct ${Money.format(allocation.amountPaise)} allocation to ${allocationNames[allocation.expenseId] ?: allocation.expenseId}"
                            }, enabled = !correctionLoading && !state.saving) { Text("Correct allocation") }
                        }
                        if (!hasRefundOrReversal && allocationRows.isNotEmpty()) item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ allocationPage-- }, Modifier.weight(1f), enabled = allocationPage > 0) { Text("Previous allocations") }
                                OutlinedButton({ allocationPage++ }, Modifier.weight(1f), enabled = allocationRows.size > 25) { Text("Next allocations") }
                            }
                        }
                        if (!hasRefundOrReversal && correction != null) item {
                            Section("Correct allocation", true) {
                                val snapshot = correction!!
                                Text("Current allocation: ${Money.format(snapshot.amountPaise)} to ${allocationNames[snapshot.expenseId] ?: snapshot.expenseId}")
                                Text("Recorded cash stays unchanged. The corrected amount leaves this expense and becomes an advance or moves to another unpaid expense.")
                                Field("Amount to correct (₹)", correctionAmount, { correctionAmount = it }, numeric = true)
                                Choice("Destination", correctionMode, listOf("release" to "Release to advance", "transfer" to "Transfer to expense"),
                                    { correctionMode = it }, allowNone = false)
                                if (correctionMode == "transfer") {
                                    Field("Search unpaid destination expenses", correctionQuery, { correctionQuery = it; correctionPage = 0 })
                                    if (loadedTargetExpenses == null) Text("Loading destination expenses")
                                    val options = targetExpenses.take(75).filter { it.id != snapshot.expenseId && it.outstandingPaise > 0 }
                                        .map { it.id to "${it.description} · ${it.vendorName} · ${Money.format(it.outstandingPaise)} outstanding" }
                                    val retained = correctionTarget.takeIf { it.isNotBlank() && options.none { option -> option.first == it } }
                                        ?.let { it to correctionTargetLabel }
                                    Choice("Destination expense", correctionTarget, listOfNotNull(retained) + options, { selected ->
                                        correctionTarget = selected
                                        correctionTargetLabel = (listOfNotNull(retained) + options).firstOrNull { it.first == selected }?.second.orEmpty()
                                    }, allowNone = false)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton({ correctionPage-- }, Modifier.weight(1f), enabled = correctionPage > 0) { Text("Previous expenses") }
                                        OutlinedButton({ correctionPage++ }, Modifier.weight(1f), enabled = targetExpenses.size > 75) { Text("Next expenses") }
                                    }
                                }
                                Button({ correctionConfirm = true }, enabled = !state.saving &&
                                    runCatching { Money.parse(correctionAmount) in 1..snapshot.amountPaise }.getOrDefault(false) &&
                                    (correctionMode == "release" || loadedTargetExpenses != null && correctionTarget.isNotBlank())) { Text("Review and save correction") }
                                TextButton({ correction = null }) { Text("Cancel correction") }
                            }
                        }
                        val correctedCash = Money.sum(payments.filter { it.originalPaymentId == row.id }.map { it.amountPaise })
                        if (correctedCash < row.amountPaise) {
                            item { Field("Refund or reversal reason", reason, { reason = it }) }
                            item { DateField("Refund or reversal date", date, { date = it }) }
                            item { OutlinedButton({ action = "reverse" }) { Text("Reverse payment") } }
                            item { Field("Refund amount (₹)", refund, { refund = it }, numeric = true) }
                            item { OutlinedButton({ action = "refund" }) { Text("Refund settled funds") } }
                        } else item { Text("This payment has been fully reversed or refunded. Its history remains available.") }
                    }
                }
            }
            "INSTALMENT" -> plan?.let { row ->
                item { RecordCard(row.title, "${row.dueDate} · ${if (row.cancelled) "Cancelled" else if (row.outstandingPaise == 0L) "Paid" else "Due"}", Money.format(row.outstandingPaise)) }
                item { Text("Scheduled ${Money.format(row.amountPaise)} · paid ${Money.format(row.paidPaise)}") }
                if (row.note.isNotBlank()) item { Text(row.note) }
                if (!row.cancelled) {
                    if (row.outstandingPaise > 0) item { Button({ vm.preparePayment(instalment = row, then = edit) }) { Text("Record payment") } }
                    item { OutlinedButton({ vm.editInstalment(row, edit) }) { Text("Edit schedule") } }
                    item { OutlinedButton({ action = "cancel" }) { Text("Cancel future schedule") } }
                }
            }
            "COMMITMENT" -> agreement?.let { row ->
                item { RecordCard(row.title, "${row.date}${if (row.archived) " · Archived" else ""}", Money.format(row.amountPaise)) }
                if (row.note.isNotBlank()) item { Text(row.note) }
                if (row.calculationJson.isNotBlank()) item {
                    val calculation = org.json.JSONObject(row.calculationJson)
                    Text("Agreed calculation: ${calculation.getString("quantity")} ${calculation.getString("quantityUnit")} × ₹${calculation.getString("rate")} per ${calculation.getString("rateUnit")} × ${calculation.getString("multiplier")}")
                }
                if (!row.archived) item { OutlinedButton({ vm.editCommitment(row, edit) }) { Text("Edit commitment") } }
                if (!row.archived) item { OutlinedButton({ action = "archive agreement" }) { Text("Archive commitment") } }
                if (!row.archived) item { OutlinedButton({ action = "cancel agreement" }) { Text("Cancel commitment and schedules") } }
                items(plans.filter { it.commitmentId == row.id }, key = { it.id }) { schedule ->
                    RecordCard(schedule.title, schedule.dueDate, Money.format(schedule.outstandingPaise)) { detail(schedule.id, "INSTALMENT") }
                }
            }
            "NOTE" -> note?.let { row ->
                item { RecordCard(row.title, "${row.date} · work log") }
                item { Text(row.note) }
                item { OutlinedButton({ vm.editNote(row, edit) }) { Text("Edit work log") } }
                item { OutlinedButton({ action = "delete note" }) { Text("Delete work log") } }
            }
            "DOCUMENT" -> file?.let { row ->
                item { RecordCard(row.name, "${row.mimeType} · ${row.size} bytes · ${row.referenceCount} linked records") }
                item(key = "document-details-${row.id}") {
                    var documentCategory by rememberSaveable(row.id, row.revision) { mutableStateOf(row.category) }
                    var documentStatus by rememberSaveable(row.id, row.revision) { mutableStateOf(row.status) }
                    var documentRevisionLabel by rememberSaveable(row.id, row.revision) { mutableStateOf(row.revisionLabel) }
                    var documentWork by rememberSaveable(row.id, row.revision) { mutableStateOf(row.work) }
                    var documentPlace by rememberSaveable(row.id, row.revision) { mutableStateOf(row.place) }
                    var documentNotes by rememberSaveable(row.id, row.revision) { mutableStateOf(row.notes) }
                    Section("Document details") {
                    Text("SHA-256 ${row.sha256}")
                    Choice("Suggested category", documentCategory,
                        com.constructioncost.nativeapp.platform.files.DocumentPolicy.categories.map { it to it }, { documentCategory = it })
                    Field("Category", documentCategory, { documentCategory = it })
                    Field("Status", documentStatus, { documentStatus = it })
                    Field("Revision label", documentRevisionLabel, { documentRevisionLabel = it })
                    Field("Work", documentWork, { documentWork = it })
                    ClassificationChoice(vm, state.projectId, "Suggested work stage", documentWork,
                        classifications.filter { it.kind == "STAGE" && it.parentId == null && !it.archived }
                            .sortedWith(compareBy<ClassificationInfo> { it.sortOrder }.thenBy { it.name })
                            .distinctBy { it.name }
                            .map { it.name to it.name },
                        classifications, "STAGE", choose = { documentWork = it }, onCreated = { documentWork = it.name })
                    Text("Choose a stage or keep your custom Work text.", style = MaterialTheme.typography.bodySmall)
                    Field("Place", documentPlace, { documentPlace = it })
                    Field("Notes", documentNotes, { documentNotes = it }, multiline = true)
                    Button({ vm.command("Document details saved") {
                        vm.repository.saveAttachmentDetails(row.id, row.revision, documentCategory, documentStatus,
                            documentRevisionLabel, documentWork, documentPlace, documentNotes)
                    } }, enabled = !state.saving) { Text("Save document details") }
                } }
                item { Button({ actions.viewDocument(row) }) { Text("View original") } }
                item { OutlinedButton({ actions.newRevision(row) }) { Text("Import new revision") } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ actions.shareDocument(row.id) }) { Text("Share") }
                    OutlinedButton({ actions.exportDocument(row) }) { Text("Save copy") }
                } }
                item { Field("Document name", name, { name = it }) }
                item { OutlinedButton({ actions.renameDocument(row, name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") } }
                item { OutlinedButton({ action = "delete document" }) { Text("Delete original") } }
                item { Section("Link document to ledger entry") {
                    Choice("Record type", linkType, listOf("EXPENSE" to "Expense", "PAYMENT" to "Payment", "NOTE" to "Work log"), {
                        linkType = it; linkTarget = ""; linkTargetLabel = ""; linkPage = 0
                    }, allowNone = false)
                    Field("Search records", linkQuery, { linkQuery = it; linkPage = 0 })
                    if (loadedLinkCandidates == null) Text("Loading records")
                    if (linkQuery.length > 200) Text("Search must be 200 characters or less.", color = MaterialTheme.colorScheme.error)
                    val options = linkCandidates.take(75)
                        .filter { candidate -> links.none { it.first == linkType && it.second == candidate.id } }
                        .map { candidate -> candidate.id to
                            "${candidate.title} · ${candidate.date}${candidate.amountPaise?.let { " · ${Money.format(it)}" }.orEmpty()}" }
                    val retained = linkTarget.takeIf { it.isNotBlank() && options.none { option -> option.first == it } }
                        ?.let { it to linkTargetLabel }
                    val choices = listOfNotNull(retained) + options
                    if (loadedLinkCandidates != null && choices.isEmpty() && linkQuery.length <= 200) Text("No unlinked records on this page.")
                    Choice("Ledger entry", linkTarget, choices, { selected ->
                        linkTarget = selected
                        linkTargetLabel = choices.firstOrNull { it.first == selected }?.second.orEmpty()
                    }, allowNone = false)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ linkPage-- }, Modifier.weight(1f), enabled = linkPage > 0) { Text("Previous records") }
                        OutlinedButton({ linkPage++ }, Modifier.weight(1f), enabled = linkCandidates.size > 75) { Text("Next records") }
                    }
                    Button({
                        val selectedType = linkType
                        val selectedId = linkTarget
                        vm.command("Document linked") {
                            when (selectedType) {
                                "EXPENSE" -> vm.repository.linkAttachment(row.id, expenseId = selectedId)
                                "PAYMENT" -> vm.repository.linkAttachment(row.id, paymentId = selectedId)
                                "NOTE" -> vm.repository.linkAttachment(row.id, noteId = selectedId)
                                else -> error("Choose a record type")
                            }
                            linkRefresh++
                            linkTarget = ""; linkTargetLabel = ""
                        }
                    }, enabled = !state.saving && loadedLinkCandidates != null && linkTarget.isNotBlank() && linkQuery.length <= 200) { Text("Link document") }
                } }
                if (links.isNotEmpty()) item { Title("Linked records") }
                items(links, key = { "${it.first}-${it.second}" }) { (recordType, recordId) ->
                    RecordCard(recordType, "Open linked entry") { detail(recordId, recordType) }
                    TextButton({ unlinkTarget = recordType to recordId }, Modifier.semantics {
                        contentDescription = "Unlink document from $recordType $recordId"
                    }, enabled = !state.saving) { Text("Unlink from $recordType") }
                }
                val rootId = row.parentId ?: row.id
                val family = files.filter { it.id == rootId || it.parentId == rootId }
                if (family.isNotEmpty()) item { Title("Revision family") }
                items(family.filter { it.id != row.id }, key = { it.id }) { related ->
                    RecordCard(related.name, "Original preserved · ${related.size} bytes") { detail(related.id, "DOCUMENT") }
                }
            }
        }
        if (type !in setOf("EXPENSE", "PAYMENT", "REVERSAL", "REFUND", "INSTALMENT", "COMMITMENT", "NOTE", "DOCUMENT")) item {
            Text("This record type has no detail view: $type")
        }
        state.actionError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item { Title("Change history") }
        items(audits, key = { it.id }) { row ->
            Section("${row.action} · ${java.time.Instant.ofEpochMilli(row.createdAt)}") {
                AuditChanges(row)
            }
        }
    }
    paymentEdit?.let { snapshot -> PaymentEditDialog(vm, snapshot,
        { paymentEdit = null; paymentEditId = ""; paymentEditFingerprint = "" }, { detail(it, "PAYMENT") }) }
    if (correctionConfirm && correction != null) Confirm("Correct allocation?",
        "Move ${correctionAmount} from the current expense ${if (correctionMode == "release") "to an advance" else "to ${correctionTargetLabel}"}. Recorded cash will not change.",
        {
            val snapshot = correction ?: return@Confirm
            val amount = correctionAmount
            val target = correctionTarget.takeIf { correctionMode == "transfer" }
            val key = correctionKey
            vm.command("Allocation corrected; cash unchanged") {
                vm.repository.correctAllocation(snapshot.allocationId, snapshot.fingerprint, Money.parse(amount), target, key)
                correction = null
                allocationRefresh++
            }
        }, { correctionConfirm = false })
    if (type == "DOCUMENT" && unlinkTarget != null) {
        val selected = unlinkTarget!!
        Confirm("Unlink document?", "The original document stays in private storage. Only its link to this entry is removed.",
            { vm.command("Document unlinked") {
                vm.repository.unlinkAttachment(id, selected.first, selected.second)
                linkRefresh++
            } }, { unlinkTarget = null })
    }
    when (action) {
        "delete bill" -> Confirm("Delete bill?", "The bill cost is removed. Cash payments stay recorded and become available as advances. Original documents and the permanent change log are kept.",
            { expense?.revision?.let { revision -> vm.command("Bill deleted; cash and originals preserved") { vm.repository.deleteExpense(id, revision); onDeleted() } } }, { action = "" })
        "reverse" -> Confirm("Reverse payment?", "The original and its allocations remain in history. Cash and settlement are reversed exactly once.",
            { vm.command("Payment reversed") { require(reason.isNotBlank()) { "Enter a reason for the reversal." }; vm.repository.reversePayment(id, reason.trim(), date) } }, { action = "" })
        "refund" -> Confirm("Refund settled funds?", "This reduces cash and settlement by the entered amount.",
            { vm.command("Refund recorded") { require(reason.isNotBlank()) { "Enter a reason for the refund." }; vm.repository.refundPayment(id, Money.parse(refund), reason.trim(), date) } }, { action = "" })
        "cancel" -> Confirm("Cancel schedule?", "Already recorded cash stays in the ledger.",
            { plan?.let { vm.command("Schedule cancelled") { vm.repository.cancelInstalment(id, it.revision) } } }, { action = "" })
        "archive agreement" -> Confirm("Archive commitment?", "This hides the agreement from active commitments. Its schedules stay active; payments and history remain available.",
            { agreement?.let { vm.command("Commitment archived") { vm.repository.archiveCommitment(id, it.revision) } } }, { action = "" })
        "cancel agreement" -> Confirm("Cancel commitment and schedules?", "This cancels all its active schedules and reminders. Recorded payments, documents and change history stay available.",
            {
                val selected = agreement
                val revisions = plans.filter { it.commitmentId == id && !it.cancelled }.associate { it.id to it.revision }
                selected?.let { vm.command("Commitment and schedules cancelled") { vm.repository.cancelCommitment(id, it.revision, revisions) } }
            }, { action = "" })
        "delete note" -> Confirm("Delete work log?", "The work log is removed; its audit history remains.",
            { note?.let { vm.command("Work log deleted") { vm.repository.deleteNote(id, it.revision) } } }, { action = "" })
        "delete document" -> Confirm("Delete original document?", "This permanently removes its bytes. Linked or revision source documents may need unlinking first.",
            { file?.let(actions.deleteDocument) }, { action = "" })
    }
}
