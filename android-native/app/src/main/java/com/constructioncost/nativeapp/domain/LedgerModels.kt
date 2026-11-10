package com.constructioncost.nativeapp.domain

import java.time.LocalDate
import java.util.UUID

const val MAX_ATTACHMENT_BYTES = 250L * 1024 * 1024

fun newId(): String = UUID.randomUUID().toString()

data class ProjectInfo(val id: String, val name: String, val budgetPaise: Long? = null, val revision: Long = 0, val archived: Boolean = false)
data class ProjectDeletionSummary(val projectId: String, val projectName: String, val recordCount: Long, val attachmentCount: Long, val attachmentBytes: Long)
data class ClassificationInfo(val id: String, val projectId: String, val name: String, val kind: String, val parentId: String? = null, val sortOrder: Int = 0, val archived: Boolean = false, val nature: String = "")
data class VendorInfo(val id: String, val projectId: String, val name: String, val note: String = "", val outstandingPaise: Long = 0)
data class CommitmentInfo(val id: String, val projectId: String, val title: String, val amountPaise: Long, val vendorId: String? = null, val date: String = LocalDate.now().toString(), val revision: Long = 0, val archived: Boolean = false, val note: String = "", val calculationJson: String = "")
data class AttachmentInfo(val id: String, val projectId: String, val name: String, val mimeType: String, val size: Long, val sha256: String, val storageName: String, val createdAt: Long, val revision: Long = 0, val parentId: String? = null, val referenceCount: Int = 0, val category: String = "", val status: String = "", val revisionLabel: String = "", val work: String = "", val place: String = "", val notes: String = "")
data class DocumentImportDetails(val category: String, val status: String, val revisionLabel: String, val work: String, val place: String, val notes: String, val expenseId: String? = null)
data class AuditInfo(val id: String, val recordType: String, val recordId: String, val action: String, val beforeJson: String, val afterJson: String, val createdAt: Long)
data class ActivityNote(val id: String, val projectId: String, val title: String, val date: String, val note: String, val revision: Long = 0)

data class LedgerSummary(val recordedPaise: Long = 0, val paidPaise: Long = 0, val outstandingPaise: Long = 0, val advancePaise: Long = 0, val upcomingPaise: Long = 0, val budgetPaise: Long? = null, val commitmentPaise: Long = 0)
data class ExpenseSummary(val count: Int = 0, val recordedPaise: Long = 0, val settledPaise: Long = 0, val outstandingPaise: Long = 0)
data class ExpenseRow(val id: String, val projectId: String, val description: String, val amountPaise: Long, val date: String, val vendorId: String? = null, val vendorName: String = "", val settledPaise: Long = 0, val outstandingPaise: Long = amountPaise, val note: String = "", val mode: String = "DIRECT_AMOUNT", val revision: Long = 0, val voided: Boolean = false, val invoiceNumber: String = "")
data class PaymentRow(val id: String, val projectId: String, val amountPaise: Long, val date: String, val vendorId: String? = null, val vendorName: String = "", val reference: String = "", val kind: String = "PAYMENT", val originalPaymentId: String? = null, val allocatedPaise: Long = 0, val instalmentId: String? = null, val note: String = "")
data class InstalmentRow(val id: String, val projectId: String, val title: String, val amountPaise: Long, val dueDate: String, val commitmentId: String? = null, val vendorId: String? = null, val paidPaise: Long = 0, val outstandingPaise: Long = amountPaise, val cancelled: Boolean = false, val remind: Boolean = false, val revision: Long = 0, val reminderDays: Int = 3, val note: String = "")
data class InsightRow(val id: String?, val name: String, val kind: String, val amountPaise: Long, val expenseCount: Int, val parentId: String? = null)
data class PlannedRow(val type: String, val id: String, val title: String, val amountPaise: Long, val date: String, val vendorId: String?)
data class CostNatureRow(val nature: String, val amountPaise: Long)
data class MonthlyRow(val month: String, val recordedPaise: Long, val paidPaise: Long)
data class ActivityRow(val id: String, val type: String, val title: String, val date: String, val amountPaise: Long? = null, val recordedAt: Long = 0, val party: String = "")
data class ActivityCursor(val recordedAt: Long, val type: String, val id: String)
data class JournalRow(val event: ActivityRow, val audit: AuditInfo? = null)
data class CalendarDayCount(val date: String, val eventCount: Int)

data class ExpenseFilter(val query: String = "", val fromDate: String = "", val toDate: String = "", val classificationId: String? = null, val vendorId: String? = null, val status: String = "ALL", val sort: String = "DATE_DESC", val directClassification: Boolean = false, val materialName: String? = null, val missingVendor: Boolean = false, val postedOnly: Boolean = false, val costNature: String? = null)
data class ClassificationPart(val classificationId: String, val dimension: String, val amountPaise: Long)
data class ExpenseLineInput(val id: String = newId(), val description: String, val amountPaise: Long, val mode: String = "DIRECT_AMOUNT", val quantity: String? = null, val rate: String? = null, val multiplier: String = "1", val quantityUnit: String = "unit", val rateUnit: String = "unit", val legacyJson: String = "", val nature: String = "")
data class ExpenseInput(val id: String = newId(), val idempotencyKey: String = id, val projectId: String, val description: String, val amountPaise: Long, val date: String, val vendorId: String? = null, val note: String = "", val mode: String = "DIRECT_AMOUNT", val lines: List<ExpenseLineInput> = emptyList(), val classifications: List<ClassificationPart> = emptyList(), val attachmentIds: List<String> = emptyList(), val paidPaise: Long = 0, val paymentReference: String = "", val commitmentId: String? = null, val instalmentId: String? = null, val revision: Long? = null, val calculation: DetailedAmount? = null, val invoiceNumber: String = "")
data class PaymentAllocationInput(val expenseId: String, val amountPaise: Long)
data class AllocationCorrectionState(val allocationId: String, val paymentId: String, val expenseId: String, val amountPaise: Long, val fingerprint: String)
data class PaymentInput(val id: String = newId(), val idempotencyKey: String = id, val projectId: String, val amountPaise: Long, val date: String, val vendorId: String? = null, val reference: String = "", val note: String = "", val instalmentId: String? = null, val allocations: List<PaymentAllocationInput> = emptyList(), val attachmentIds: List<String> = emptyList())

data class PaymentEditState(val payment: PaymentInput, val fingerprint: String)
data class InstalmentInput(val id: String = newId(), val projectId: String, val title: String, val amountPaise: Long, val dueDate: String, val commitmentId: String? = null, val vendorId: String? = null, val remind: Boolean = false, val revision: Long? = null, val classificationIds: List<String>? = null, val reminderDays: Int = 3, val note: String = "")
data class CommitmentInput(val id: String = newId(), val projectId: String, val title: String, val amountPaise: Long, val date: String, val vendorId: String? = null, val schedules: List<InstalmentInput> = emptyList(), val revision: Long? = null, val classificationIds: List<String>? = null, val note: String = "", val calculation: DetailedAmount? = null)

data class DetailedAmount(val quantity: String, val rate: String, val multiplier: String = "1", val discountAmount: String = "0", val discountPercent: String = "0", val taxAmount: String = "0", val taxPercent: String = "0", val transport: String = "0", val adjustment: String = "0", val quantityUnit: String = "unit", val rateUnit: String = "unit")
