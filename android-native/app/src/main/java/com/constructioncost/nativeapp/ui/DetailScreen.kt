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
                          edit: () -> Unit, detail: (String, String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val project = state.projectId
    val payments by remember(project) { vm.repository.payments(project) }.collectAsStateWithLifecycle(emptyList())
    val plans by remember(project) { vm.repository.instalments(project) }.collectAsStateWithLifecycle(emptyList())
    val commitments by remember(project) { vm.repository.commitments(project) }.collectAsStateWithLifecycle(emptyList())
    val notes by remember(project) { vm.repository.notes(project) }.collectAsStateWithLifecycle(emptyList())
    val files by remember(project) { vm.repository.attachments(project) }.collectAsStateWithLifecycle(emptyList())
    val classifications by remember(project) { vm.repository.classifications(project) }.collectAsStateWithLifecycle(emptyList())
    val auditTable = when (type) {
        "EXPENSE" -> "expenses"
        "PAYMENT", "REVERSAL", "REFUND" -> "payments"
        "INSTALMENT" -> "instalments"
        "COMMITMENT" -> "commitments"
        "NOTE" -> "activity_entries"
        "DOCUMENT" -> "attachments"
        else -> ""
    }
    val audits by remember(id, auditTable, project) {
        if (auditTable.isBlank()) flowOf(emptyList()) else vm.repository.audits(id, auditTable, project)
    }.collectAsStateWithLifecycle(emptyList())
    val fetchedExpense by produceState<ExpenseInput?>(null, id, project) {
        value = vm.repository.expense(id)?.takeIf { it.projectId == project }
    }
    val expense = fetchedExpense?.takeIf { it.id == id && it.projectId == project }
    val fetchedPaymentExpense by produceState<ExpenseRow?>(null, id, project) {
        value = if (type == "EXPENSE") vm.repository.expenseRow(id)?.takeIf { it.projectId == project } else null
    }
    val paymentExpense = fetchedPaymentExpense?.takeIf { it.id == id && it.projectId == project }
    var reason by rememberSaveable(id) { mutableStateOf("") }
    var date by rememberSaveable(id) { mutableStateOf(LocalDate.now().toString()) }
    var refund by rememberSaveable(id) { mutableStateOf("") }
    var name by rememberSaveable(id) { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    val payment = payments.firstOrNull { it.id == id && it.projectId == project }
    val receiptSnapshot by produceState("" to emptyList<String>(), payment?.id) {
        value = if (payment != null) payment.id to vm.repository.paymentAttachmentIds(payment.id) else "" to emptyList()
    }
    val paymentReceiptIds = receiptSnapshot.second.takeIf { receiptSnapshot.first == payment?.id }.orEmpty()
    val scope = rememberCoroutineScope()
    var allocationPage by rememberSaveable(id) { mutableIntStateOf(0) }
    var allocationRefresh by remember(id) { mutableIntStateOf(0) }
    val allocationKey = "${payment?.id.orEmpty()}#$allocationPage#$allocationRefresh"
    val allocationSnapshot by produceState("" to emptyList<AllocationCorrectionState>(), payment?.id, allocationPage, allocationRefresh) {
        value = if (type == "PAYMENT" && payment?.kind == "PAYMENT")
            allocationKey to vm.repository.paymentAllocationStates(payment.id, 26, allocationPage * 25) else "" to emptyList()
    }
    val allocationRows = allocationSnapshot.second.takeIf { allocationSnapshot.first == allocationKey }.orEmpty()
    val allocationNames by produceState(emptyMap<String, String>(), allocationRows) {
        value = allocationRows.associate { row -> row.expenseId to
            (vm.repository.expense(row.expenseId)?.description ?: row.expenseId) }
    }
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
    val targetExpenses by remember(project, correctionQuery, correctionPage) {
        vm.repository.expenses(project, ExpenseFilter(query = correctionQuery.trim(), status = "OUTSTANDING"), 76, correctionPage * 75)
    }.collectAsStateWithLifecycle(emptyList())
    val plan = plans.firstOrNull { it.id == id && it.projectId == project }
    val agreement = commitments.firstOrNull { it.id == id && it.projectId == project }
    val note = notes.firstOrNull { it.id == id && it.projectId == project }
    val file = files.firstOrNull { it.id == id && it.projectId == project }
    var documentCategory by rememberSaveable(file?.id, file?.revision) { mutableStateOf(file?.category.orEmpty()) }
    var documentStatus by rememberSaveable(file?.id, file?.revision) { mutableStateOf(file?.status.orEmpty()) }
    var documentRevisionLabel by rememberSaveable(file?.id, file?.revision) { mutableStateOf(file?.revisionLabel.orEmpty()) }
    var documentWork by rememberSaveable(file?.id, file?.revision) { mutableStateOf(file?.work.orEmpty()) }
    var documentPlace by rememberSaveable(file?.id, file?.revision) { mutableStateOf(file?.place.orEmpty()) }
    var documentNotes by rememberSaveable(file?.id, file?.revision) { mutableStateOf(file?.notes.orEmpty()) }
    var linkRefresh by remember(id) { mutableIntStateOf(0) }
    val linkSnapshot by produceState(id to emptyList<Pair<String, String>>(), id, project, linkRefresh) {
        value = id to vm.repository.attachmentLinks(id)
    }
    val links = linkSnapshot.second.takeIf { file?.id == id && file?.projectId == project && linkSnapshot.first == id }.orEmpty()
    var linkType by rememberSaveable(id) { mutableStateOf("EXPENSE") }
    var linkQuery by rememberSaveable(id) { mutableStateOf("") }
    var linkPage by rememberSaveable(id) { mutableIntStateOf(0) }
    var linkTarget by rememberSaveable(id) { mutableStateOf("") }
    var linkTargetLabel by rememberSaveable(id) { mutableStateOf("") }
    var unlinkTarget by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    val linkCandidates by remember(project, type, linkType, linkQuery, linkPage) {
        if (type == "DOCUMENT" && linkQuery.length <= 200)
            vm.repository.activity(project, 76, linkPage * 75, includeExpenses = true,
                query = linkQuery, recordType = linkType)
        else flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Title("Entry detail") }
        when (type) {
            "EXPENSE" -> {
                if (expense == null) item { Text("Expense is unavailable in the selected project.") }
                expense?.let { row ->
                    item { RecordCard(row.description, "${row.date} · ${if (row.revision == null) "" else "Revision ${row.revision}"}", Money.format(row.amountPaise)) }
                    item { Text("${if (row.mode == "CALCULATED") "Calculated" else "Final amount"} · ${row.note}") }
                    items(row.lines, key = { it.id }) { line -> RecordCard(line.description, line.mode, Money.format(line.amountPaise)) }
                    item { Text("Classifications: ${row.classifications.size} · linked documents: ${row.attachmentIds.size}") }
                    item { Button({ vm.editExpense(row.id, edit) }) { Text("Edit expense") } }
                    paymentExpense?.takeIf { !it.voided && it.outstandingPaise > 0 }?.let { unpaid -> item {
                        OutlinedButton({ vm.preparePayment(unpaid); edit() }) { Text("Record payment of ${Money.format(unpaid.outstandingPaise)}") }
                    } }
                    if (row.revision != null) item { OutlinedButton({ action = "void" }) { Text("Void expense") } }
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
                                        vm.showError(failure.message ?: "Could not load allocation")
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
                                    (correctionMode == "release" || correctionTarget.isNotBlank())) { Text("Review and save correction") }
                                TextButton({ correction = null }) { Text("Cancel correction") }
                            }
                        }
                        item { Field("Refund or reversal reason", reason, { reason = it }) }
                        item { DateField("Refund or reversal date", date, { date = it }) }
                        item { OutlinedButton({ action = "reverse" }) { Text("Reverse payment") } }
                        item { Field("Refund amount (₹)", refund, { refund = it }, true) }
                        item { OutlinedButton({ action = "refund" }) { Text("Refund settled funds") } }
                    }
                }
            }
            "INSTALMENT" -> plan?.let { row ->
                item { RecordCard(row.title, "${row.dueDate} · ${if (row.cancelled) "Cancelled" else if (row.outstandingPaise == 0L) "Paid" else "Due"}", Money.format(row.outstandingPaise)) }
                item { Text("Scheduled ${Money.format(row.amountPaise)} · paid ${Money.format(row.paidPaise)}") }
                if (!row.cancelled) {
                    if (row.outstandingPaise > 0) item { Button({ vm.preparePayment(instalment = row); edit() }) { Text("Record payment") } }
                    item { OutlinedButton({ vm.editInstalment(row, edit) }) { Text("Edit schedule") } }
                    item { OutlinedButton({ action = "cancel" }) { Text("Cancel future schedule") } }
                }
            }
            "COMMITMENT" -> agreement?.let { row ->
                item { RecordCard(row.title, "${row.date}${if (row.archived) " · Archived" else ""}", Money.format(row.amountPaise)) }
                if (!row.archived) item { OutlinedButton({ vm.editCommitment(row, edit) }) { Text("Edit commitment") } }
                if (!row.archived) item { OutlinedButton({ action = "archive agreement" }) { Text("Archive commitment") } }
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
                item { Section("Document details") {
                    Text("SHA-256 ${row.sha256}")
                    Field("Category", documentCategory, { documentCategory = it })
                    Field("Status", documentStatus, { documentStatus = it })
                    Field("Revision label", documentRevisionLabel, { documentRevisionLabel = it })
                    Field("Work", documentWork, { documentWork = it })
                    Choice("Suggested work stage", documentWork,
                        classifications.filter { it.kind == "STAGE" && it.parentId == null && !it.archived }
                            .sortedWith(compareBy<ClassificationInfo> { it.sortOrder }.thenBy { it.name })
                            .distinctBy { it.name }
                            .map { it.name to it.name },
                        { documentWork = it }, allowNone = false)
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
                    if (linkQuery.length > 200) Text("Search must be 200 characters or less.", color = MaterialTheme.colorScheme.error)
                    val options = linkCandidates.take(75)
                        .filter { candidate -> links.none { it.first == linkType && it.second == candidate.id } }
                        .map { candidate -> candidate.id to
                            "${candidate.title} · ${candidate.date}${candidate.amountPaise?.let { " · ${Money.format(it)}" }.orEmpty()}" }
                    val retained = linkTarget.takeIf { it.isNotBlank() && options.none { option -> option.first == it } }
                        ?.let { it to linkTargetLabel }
                    val choices = listOfNotNull(retained) + options
                    if (choices.isEmpty() && linkQuery.length <= 200) Text("No unlinked records on this page.")
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
                    }, enabled = !state.saving && linkTarget.isNotBlank() && linkQuery.length <= 200) { Text("Link document") }
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
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item { Title("Change history") }
        items(audits, key = { it.id }) { row ->
            Section("${row.action} · ${java.time.Instant.ofEpochMilli(row.createdAt)}") {
                Text("Before: ${row.beforeJson}")
                Text("After: ${row.afterJson}")
            }
        }
    }
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
        "void" -> Confirm("Void expense?", "This keeps its audit history. Settled payments must be reversed first.",
            { expense?.revision?.let { revision -> vm.command("Expense voided") { vm.repository.voidExpense(id, revision) } } }, { action = "" })
        "reverse" -> Confirm("Reverse payment?", "The original and its allocations remain in history. Cash and settlement are reversed exactly once.",
            { vm.command("Payment reversed") { require(reason.isNotBlank()); vm.repository.reversePayment(id, reason.trim(), date) } }, { action = "" })
        "refund" -> Confirm("Refund settled funds?", "This reduces cash and settlement by the entered amount.",
            { vm.command("Refund recorded") { require(reason.isNotBlank()); vm.repository.refundPayment(id, Money.parse(refund), reason.trim(), date) } }, { action = "" })
        "cancel" -> Confirm("Cancel schedule?", "Already recorded cash stays in the ledger.",
            { plan?.let { vm.command("Schedule cancelled") { vm.repository.cancelInstalment(id, it.revision) } } }, { action = "" })
        "archive agreement" -> Confirm("Archive commitment?", "The agreement leaves active plans. Its schedules, payments and history remain available.",
            { agreement?.let { vm.command("Commitment archived") { vm.repository.archiveCommitment(id, it.revision) } } }, { action = "" })
        "delete note" -> Confirm("Delete work log?", "The work log is removed; its audit history remains.",
            { note?.let { vm.command("Work log deleted") { vm.repository.deleteNote(id, it.revision) } } }, { action = "" })
        "delete document" -> Confirm("Delete original document?", "This permanently removes its bytes. Linked or revision source documents may need unlinking first.",
            { file?.let(actions.deleteDocument) }, { action = "" })
    }
}
