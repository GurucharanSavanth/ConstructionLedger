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
import java.time.LocalDate

@Composable
internal fun PaymentEditDialog(vm: LedgerViewModel, snapshot: PaymentEditState, dismiss: () -> Unit,
                               saved: (String) -> Unit) {
    val original = snapshot.payment
    val state by vm.state.collectAsStateWithLifecycle()
    val loadedVendors = remember(original.projectId) { vm.repository.vendors(original.projectId) }.readLedger(vm)
    val vendors = loadedVendors.orEmpty()
    var amount by rememberSaveable(snapshot.fingerprint) { mutableStateOf(Money.input(original.amountPaise)) }
    var date by rememberSaveable(snapshot.fingerprint) { mutableStateOf(original.date) }
    var vendor by rememberSaveable(snapshot.fingerprint) { mutableStateOf(original.vendorId.orEmpty()) }
    var reference by rememberSaveable(snapshot.fingerprint) { mutableStateOf(original.reference) }
    var note by rememberSaveable(snapshot.fingerprint) { mutableStateOf(original.note) }
    var reason by rememberSaveable(snapshot.fingerprint) { mutableStateOf("") }
    val key = rememberSaveable(snapshot.fingerprint) { newId() }
    var review by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    val followsAmount = original.allocations.size == 1 && original.allocations.single().amountPaise == original.amountPaise
    AlertDialog(onDismissRequest = { if (!state.saving) dismiss() }, title = { Text("Edit payment") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Saving reverses the original payment and records its replacement together. Both remain in change history.")
            Field("Payment amount (₹)", amount, { amount = it }, numeric = true, enabled = !state.saving)
            DateField("Payment date", date, { date = it })
            if (loadedVendors == null) Text("Loading suppliers")
            Choice("Supplier", vendor, vendors.map { it.id to it.name }, { vendor = it })
            Field("Method / reference", reference, { reference = it }, enabled = !state.saving)
            Field("Notes", note, { note = it }, multiline = true, enabled = !state.saving)
            Field("Reason for correction", reason, { reason = it }, enabled = !state.saving)
            Text(if (followsAmount) "The fully linked expense allocation follows the new amount."
                else "Existing allocations stay at ${Money.format(Money.sum(original.allocations.map { it.amountPaise }))}. Any remaining amount is an advance.")
            Text("Linked receipts and the instalment reference are preserved.")
            (validation ?: state.actionError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton({
            validation = runCatching {
                val value = Money.parse(amount)
                LocalDate.parse(date)
                require(reason.isNotBlank()) { "Enter a reason for this correction." }
                require(followsAmount || value >= Money.sum(original.allocations.map { it.amountPaise })) {
                    "The payment cannot be less than its existing allocations."
                }
            }.exceptionOrNull()?.message
            if (validation == null) review = true
        }, enabled = !state.saving && loadedVendors != null) { Text("Review correction") }
    }, dismissButton = { TextButton(dismiss, enabled = !state.saving) { Text("Cancel") } })
    if (review) Confirm("Save payment correction?",
        "Replace ${Money.format(original.amountPaise)} dated ${original.date} with ₹$amount dated $date. The original entry stays in history.", {
            val newAmount = Money.parse(amount)
            val newDate = date
            val newVendor = vendor.ifBlank { null }
            val newReference = reference
            val newNote = note
            val explanation = reason.trim()
            vm.command("Payment corrected") {
                val id = vm.repository.replacePayment(original.id, snapshot.fingerprint, newAmount, newDate,
                    newVendor, newReference, newNote, explanation, key)
                dismiss()
                saved(id)
            }
        }, { review = false })
}
