package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.LedgerSummary
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class SeededLedgerStateTest {
    private data class Cash(val id: String, val project: Int, var remaining: Long, var settled: Long)

    @Test fun seededPaymentsAllocationsRefundsReversalsAndRestoresMatchIndependentBalances() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (seed in listOf(20261026, 104729, 8675309)) {
            val repository = LedgerRepository.inMemory(context)
            val random = Random(seed)
            val cash = mutableListOf<Cash>()
            val counts = IntArray(7)
            try {
                repeat(2) { project ->
                    repository.saveProject(ProjectInfo("project-$project", "House $project", budgetPaise = 50_000))
                    repository.saveExpense(ExpenseInput(id = "bill-$project", projectId = "project-$project",
                        description = "Original invoice $project", amountPaise = 12_345, date = "2026-10-26"))
                    repository.savePayment(PaymentInput(id = "initial-$project", projectId = "project-$project",
                        amountPaise = 4_000, date = "2026-10-26", allocations = listOf(PaymentAllocationInput("bill-$project", 3_000))))
                    cash += Cash("initial-$project", project, 4_000, 3_000)
                }
                fun settled(project: Int) = cash.filter { it.project == project }.sumOf { it.settled }
                suspend fun create(step: Int) {
                    val project = random.nextInt(2)
                    val amount = random.nextLong(1, 1_001)
                    val allocated = random.nextLong(0, minOf(amount, 12_345 - settled(project)) + 1)
                    val input = PaymentInput(id = "cash-$step", projectId = "project-$project", amountPaise = amount,
                        date = "2026-10-26", allocations = if (allocated == 0L) emptyList()
                        else listOf(PaymentAllocationInput("bill-$project", allocated)))
                    assertEquals(input.id, repository.savePayment(input))
                    assertEquals(input.id, repository.savePayment(input))
                    cash += Cash(input.id, project, amount, allocated)
                    counts[0]++
                }
                repeat(100) { step ->
                    try {
                        when (step % 7) {
                            0 -> create(step)
                            1 -> {
                                val payment = cash.filter { it.remaining > it.settled && settled(it.project) < 12_345 }.randomOrNull(random)
                                if (payment == null) create(step) else {
                                    val amount = random.nextLong(1, minOf(payment.remaining - payment.settled, 12_345 - settled(payment.project)) + 1)
                                    repeat(2) { repository.allocateAdvance(payment.id, "bill-${payment.project}", amount, "allocate-$step") }
                                    payment.settled += amount
                                    counts[1]++
                                }
                            }
                            2 -> {
                                val payment = cash.filter { it.settled > 0 }.randomOrNull(random)
                                if (payment == null) create(step) else {
                                    val amount = random.nextLong(1, payment.settled + 1)
                                    repository.refundPayment(payment.id, amount, "Seeded refund", "2026-10-26")
                                    payment.remaining -= amount
                                    payment.settled -= amount
                                    counts[2]++
                                }
                            }
                            3 -> {
                                val payment = cash.filter { it.remaining > 0 }.randomOrNull(random)
                                if (payment == null) create(step) else {
                                    repository.reversePayment(payment.id, "Seeded reversal", "2026-10-26")
                                    payment.remaining = 0
                                    payment.settled = 0
                                    counts[3]++
                                }
                            }
                            4 -> {
                                val payment = cash.random(random)
                                val before = repository.exportRecords().toString()
                                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                                    repository.allocateAdvance(payment.id, "bill-${payment.project}", payment.remaining - payment.settled + 1, "invalid-$step")
                                } }
                                assertEquals(before, repository.exportRecords().toString())
                                counts[4]++
                            }
                            5 -> {
                                val project = random.nextInt(2)
                                val before = repository.exportRecords().toString()
                                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                                    repository.savePayment(PaymentInput(id = "cross-$step", projectId = "project-$project",
                                        amountPaise = 1, date = "2026-10-26", allocations = listOf(PaymentAllocationInput("bill-${1 - project}", 1))))
                                } }
                                assertEquals(before, repository.exportRecords().toString())
                                counts[5]++
                            }
                            6 -> {
                                repository.replaceRecords(repository.exportRecords())
                                counts[6]++
                            }
                        }
                        repeat(2) { project ->
                            val payments = cash.filter { it.project == project }
                            val paid = payments.sumOf { it.remaining }
                            val allocated = payments.sumOf { it.settled }
                            assertEquals(LedgerSummary(recordedPaise = 12_345, paidPaise = paid,
                                outstandingPaise = 12_345 - allocated, advancePaise = paid - allocated, budgetPaise = 50_000),
                                repository.summary("project-$project").first())
                        }
                        assertEquals(cash.size, repository.payments("project-0").first().count { it.kind == "PAYMENT" } +
                            repository.payments("project-1").first().count { it.kind == "PAYMENT" })
                    } catch (failure: Throwable) { throw AssertionError("seed=$seed step=$step action=${step % 7}", failure) }
                }
                assertTrue("Every state transition must execute: ${counts.toList()}", counts.all { it > 0 })
                repository.validateRecords(repository.exportRecords())
                println("SEEDED_LEDGER seed=$seed operations=100 actionCounts=${counts.toList()} projects=2 exactBalanceChecks=200")
            } finally { repository.close() }
        }
    }

    @Test fun cancellationAtReplacementCheckpointsRollsBackRecordsAndDeletionJournal() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (streamed in listOf(false, true)) for (checkpoint in listOf("rows-cleared", "rows-imported")) {
            val repository = LedgerRepository.inMemory(context)
            val file = File.createTempFile("cancelled-replacement-", ".json", context.cacheDir)
            try {
                repository.saveProject(ProjectInfo("project", "Original house"))
                repository.saveExpense(ExpenseInput(id = "bill", projectId = "project", description = "Original invoice",
                    amountPaise = 12_345, date = "2026-10-26"))
                val replacement = JSONObject(repository.exportRecords().toString()).apply {
                    getJSONArray("projects").getJSONObject(0).put("name", "Candidate house")
                }
                file.writeText(replacement.toString())
                repository.beginProjectDeletion("project")
                val before = repository.exportRecords().toString()
                val journal = repository.projectDeletionJournals()
                repository.replacementCheckpoint = { if (it == checkpoint) throw CancellationException("Injected cancellation at $checkpoint") }
                assertThrows(CancellationException::class.java) { runBlocking {
                    if (streamed) repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION)
                    else repository.replaceRecords(replacement)
                } }
                repository.replacementCheckpoint = {}
                assertEquals(before, repository.exportRecords().toString())
                assertEquals(journal, repository.projectDeletionJournals())
                assertEquals(12_345L, repository.summary("project").first().recordedPaise)
                repository.replaceRecords(replacement)
                assertTrue(repository.projectDeletionJournals().isEmpty())
                assertEquals("Candidate house", repository.projects.first().single().name)
                assertEquals(12_345L, repository.summary("project").first().recordedPaise)
            } finally { repository.close(); file.delete() }
        }
    }
}
