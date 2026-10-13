package com.constructioncost.nativeapp

import android.content.ContentResolver
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.constructioncost.nativeapp.domain.Money
import com.constructioncost.nativeapp.domain.LedgerSummary
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

object LedgerExports {
    suspend fun csv(resolver: ContentResolver, target: Uri, repository: LedgerRepository, projectId: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(target, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { output ->
            output.write("\uFEFF")
            output.appendLine(csvHeader.joinToString(",", transform = ::csvCell))
            repository.forEachExportRow(projectId) { table, row ->
                val label = when (table) {
                    "expenses" -> "Expense settlement"; "expense_lines" -> "Expense line"; "payments" -> row.getString("kind")
                    "instalments" -> "Instalment"; "commitments" -> "Commitment"; "payment_allocations" -> "Payment allocation"
                    "classification_allocations" -> "Classification allocation"; "audit_records" -> "Audit"; "import_records" -> "Migration evidence"
                    else -> table
                }
                val fields = mutableMapOf("Record Type" to label, "Source ID" to row.getString("id"),
                    "Date" to row.optString("date", row.optString("dueDate", row.optString("_expenseDate"))),
                    "Description" to row.optString("description", row.optString("title", row.optString("name"))),
                    "Supplier" to row.optString("_vendorName"), "Evidence JSON" to row.toString())
                when (table) {
                    "expenses" -> {
                        val settled = row.getLong("_settledPaise")
                        if (!row.getBoolean("voided")) {
                            if (!row.getBoolean("_hasLines")) fields["Recorded Cost INR"] = Money.input(row.getLong("amountPaise"))
                            fields["Outstanding INR"] = Money.input(row.getLong("amountPaise") - settled)
                        }
                        fields["Settled INR"] = Money.input(settled)
                        fields["Status"] = if (row.getBoolean("voided")) "Voided" else "Recorded"
                    }
                    "expense_lines" -> {
                        if (!row.getBoolean("_voided")) fields["Recorded Cost INR"] = Money.input(row.getLong("amountPaise"))
                        fields["Quantity"] = if (row.isNull("quantity")) "" else row.getString("quantity")
                        fields["Unit Rate INR"] = if (row.isNull("rate")) "" else row.getString("rate")
                        fields["Cost Nature"] = row.getString("nature")
                    }
                    "payments" -> {
                        fields["Cash Outflow INR"] = Money.input(if (row.getString("kind") == "PAYMENT") row.getLong("amountPaise") else -row.getLong("amountPaise"))
                        fields["Reference"] = row.getString("reference"); fields["Status"] = row.getString("kind")
                    }
                    "instalments" -> fields["Planned Amount INR"] = Money.input(if (row.getBoolean("cancelled")) 0 else row.getLong("amountPaise") - row.getLong("_paidPaise"))
                    "commitments" -> fields["Commitment INR"] = Money.input(row.getLong("amountPaise"))
                    "payment_allocations", "classification_allocations" -> {
                        fields["Allocation Amount INR"] = Money.input(row.getLong("amountPaise"))
                        fields["Classification"] = row.optString("_classificationName")
                    }
                }
                output.appendLine(csvHeader.map { fields[it].orEmpty() }.joinToString(",", transform = ::csvCell))
            }
        } ?: error("Cannot create CSV destination")
    }
    internal val csvHeader = listOf("Record Type", "Source ID", "Date", "Description", "Supplier", "Recorded Cost INR",
        "Cash Outflow INR", "Outstanding INR", "Settled INR", "Planned Amount INR", "Commitment INR",
        "Allocation Amount INR", "Quantity", "Unit Rate INR", "Cost Nature", "Classification", "Reference", "Status", "Evidence JSON")

    internal fun csvCell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') && !Regex("-?[0-9]+(?:\\.[0-9]+)?").matches(value)) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    suspend fun pdf(resolver: ContentResolver, target: Uri, summary: LedgerSummary, projectName: String) = withContext(Dispatchers.IO) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val canvas = page.canvas
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 21f; typeface = Typeface.DEFAULT_BOLD }
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f }
            canvas.drawText("BuildLedger project report", 42f, 55f, title)
            canvas.drawText("Generated ${LocalDate.now()}  |  ${projectName.take(44)}", 42f, 86f, body)
            canvas.drawText("Recorded expenditure: ${Money.input(summary.recordedPaise)} INR", 42f, 135f, body)
            canvas.drawText("Actual payments: ${Money.input(summary.paidPaise)} INR", 42f, 164f, body)
            canvas.drawText("Outstanding incurred cost: ${Money.input(summary.outstandingPaise)} INR", 42f, 193f, body)
            canvas.drawText("Available advances: ${Money.input(summary.advancePaise)} INR", 42f, 222f, body)
            canvas.drawText("Upcoming instalments: ${Money.input(summary.upcomingPaise)} INR", 42f, 251f, body)
            canvas.drawText("Budget: ${summary.budgetPaise?.let { Money.input(it) + " INR" } ?: "Not set"}", 42f, 280f, body)
            canvas.drawText("Agreed commitments: ${Money.input(summary.commitmentPaise)} INR", 42f, 309f, body)
            document.finishPage(page)
            resolver.openOutputStream(target, "wt")?.use(document::writeTo) ?: error("Cannot create PDF destination")
        } finally { document.close() }
    }
}
