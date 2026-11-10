package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.ExpenseLineInput
import com.constructioncost.nativeapp.domain.PaymentAllocationInput
import com.constructioncost.nativeapp.domain.PaymentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ConcurrentLedgerV7Test {
    @Test fun twoWritersFiftyOperations() = runBlocking<Unit> { exercise(2, 50) }
    @Test fun twoWritersOneHundredOperations() = runBlocking<Unit> { exercise(2, 100) }
    @Test fun fiveWritersFiftyOperations() = runBlocking<Unit> { exercise(5, 50) }
    @Test fun fiveWritersOneHundredOperations() = runBlocking<Unit> { exercise(5, 100) }
    @Test fun tenWritersFiftyOperations() = runBlocking<Unit> { exercise(10, 50) }
    @Test fun tenWritersOneHundredOperations() = runBlocking<Unit> { exercise(10, 100) }

    private suspend fun <T> together(writers: Int, action: suspend (Int) -> T): List<T> = coroutineScope {
        val ready = Channel<Unit>(writers)
        val start = CompletableDeferred<Unit>()
        val tasks = List(writers) { writer -> async(Dispatchers.Default) {
            ready.send(Unit)
            start.await()
            action(writer)
        } }
        repeat(writers) { ready.receive() }
        ready.close()
        start.complete(Unit)
        tasks.awaitAll()
    }

    private suspend fun exercise(writers: Int, operations: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val completed = AtomicInteger()
        val expenseAttempts = AtomicInteger()
        val paymentAttempts = AtomicInteger()
        val sharedAttempts = AtomicInteger()
        val editAttempts = AtomicInteger()
        try {
            repository.saveProject(ProjectInfo("project", "Concurrent disposable ledger"))
            val inputs = List(writers) { writer -> List(operations) { operation ->
                val suffix = "$writer-$operation"
                val amount = 10_000L + writer * 100L + operation
                val expense = ExpenseInput(id = "expense-$suffix", idempotencyKey = "expense-key-$suffix",
                    projectId = "project", description = "Invoice $suffix", amountPaise = amount, date = "2026-10-22",
                    lines = listOf(ExpenseLineInput("line-$suffix", "Material $suffix", amount)))
                val payment = PaymentInput(id = "payment-$suffix", idempotencyKey = "payment-key-$suffix",
                    projectId = "project", amountPaise = amount / 3, date = "2026-10-22",
                    allocations = listOf(PaymentAllocationInput(expense.id, amount / 3)))
                expense to payment
            } }
            val expenses = inputs.flatten().map { it.first }
            val payments = inputs.flatten().map { it.second }
            val recorded = expenses.fold(BigInteger.ZERO) { total, input -> total.add(BigInteger.valueOf(input.amountPaise)) }
            val allocated = payments.fold(BigInteger.ZERO) { total, input -> total.add(BigInteger.valueOf(input.amountPaise)) }
            together(writers) { writer ->
                inputs[writer].forEachIndexed { operation, (expense, payment) ->
                    try {
                        repeat(2) {
                            expenseAttempts.incrementAndGet()
                            assertEquals(expense.id, repository.saveExpense(expense))
                        }
                        paymentAttempts.incrementAndGet()
                        assertEquals(payment.id, repository.savePayment(payment))
                        completed.incrementAndGet()
                    } catch (failure: Throwable) {
                        throw AssertionError("writers=$writers operations=$operations writer=$writer operation=$operation", failure)
                    }
                }
            }
            val shared = PaymentInput(id = "shared-payment", idempotencyKey = "shared-payment-key", projectId = "project",
                amountPaise = 37, date = "2026-10-22", reference = "Shared idempotent advance")
            val sharedIds = together(writers) {
                sharedAttempts.incrementAndGet()
                repository.savePayment(shared)
            }
            assertEquals(setOf(shared.id), sharedIds.toSet())

            val stale = requireNotNull(repository.expense(expenses.first().id))
            val updates = List(writers) { writer -> stale.copy(description = "Accepted correction $writer") }
            val winners = together(writers) { writer ->
                editAttempts.incrementAndGet()
                try { repository.saveExpense(updates[writer]); writer }
                catch (failure: IllegalArgumentException) {
                    assertEquals("Revision conflict; reload this record", failure.message)
                    null
                }
            }.filterNotNull()
            assertEquals("writers=$writers operations=$operations", 1, winners.size)
            val corrected = requireNotNull(repository.expense(stale.id))
            assertEquals(updates[winners.single()].description, corrected.description)
            assertEquals(1L, corrected.revision)

            val snapshot = repository.exportRecords()
            fun rows(table: String): List<JSONObject> {
                val array = snapshot.getJSONArray(table)
                return (0 until array.length()).map(array::getJSONObject)
            }
            val count = writers * operations
            assertEquals(count, completed.get())
            assertEquals(count * 2, expenseAttempts.get())
            assertEquals(count, paymentAttempts.get())
            assertEquals(writers, sharedAttempts.get())
            assertEquals(writers, editAttempts.get())
            assertEquals(count, rows("expenses").size)
            assertEquals(expenses.map { it.id }.toSet(), rows("expenses").map { it.getString("id") }.toSet())
            assertEquals(expenses.associate { it.id to it.amountPaise }, rows("expenses").associate { it.getString("id") to it.getLong("amountPaise") })
            assertEquals(count, rows("expense_lines").size)
            assertEquals(expenses.flatMap { it.lines }.map { it.id }.toSet(), rows("expense_lines").map { it.getString("id") }.toSet())
            assertEquals(count + 1, rows("payments").size)
            assertEquals(payments.map { it.id }.toSet() + shared.id, rows("payments").map { it.getString("id") }.toSet())
            assertEquals(count, rows("payment_allocations").size)
            assertEquals(count, rows("payment_allocations").map { it.getString("id") }.toSet().size)
            assertEquals(payments.associate { (it.id to it.allocations.single().expenseId) to it.amountPaise },
                rows("payment_allocations").associate { (it.getString("paymentId") to it.getString("expenseId")) to it.getLong("amountPaise") })
            val audits = rows("audit_records")
            assertEquals(count + 1, audits.count { it.getString("recordType") == "expenses" && it.getString("action") == "SAVE" })
            assertEquals(count + 1, audits.count { it.getString("recordType") == "payments" && it.getString("action") == "SAVE" })
            val summary = repository.summary("project").first()
            assertEquals(recorded.longValueExact(), summary.recordedPaise)
            assertEquals(allocated.add(BigInteger.valueOf(shared.amountPaise)).longValueExact(), summary.paidPaise)
            assertEquals(recorded.subtract(allocated).longValueExact(), summary.outstandingPaise)
            assertEquals(shared.amountPaise, summary.advancePaise)
        } finally {
            println("CONCURRENT_LEDGER writers=$writers operationsPerWriter=$operations completed=${completed.get()} " +
                "expenseAttempts=${expenseAttempts.get()} paymentAttempts=${paymentAttempts.get()} " +
                "sharedPaymentAttempts=${sharedAttempts.get()} staleEditAttempts=${editAttempts.get()}; deterministic concurrency, not seeded stateful fuzz")
            repository.close()
        }
    }
}
