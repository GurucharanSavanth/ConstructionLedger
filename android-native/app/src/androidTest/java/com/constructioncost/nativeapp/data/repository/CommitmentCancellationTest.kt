package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommitmentCancellationTest {
    @Test fun cancellationRetainsCashHistoryAndRejectsChangedScheduleSet() = runBlocking {
        val repository = LedgerRepository.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            repository.saveProject(ProjectInfo("p", "Build"))
            repository.saveCommitment(CommitmentInput("c", "p", "Masonry", 10_000, "2026-01-01", schedules = listOf(
                InstalmentInput("s", "p", "First", 5_000, "2026-11-01", remind = true, reminderDays = 7))))
            repository.savePayment(PaymentInput("cash", projectId = "p", amountPaise = 2_000, date = "2026-01-01", instalmentId = "s"))
            repository.saveInstalment(InstalmentInput("second", "p", "Second", 5_000, "2026-12-01", commitmentId = "c", remind = true))
            val before = repository.exportRecords().toString()
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.cancelCommitment("c", 0, mapOf("s" to 0)) } }
            assertEquals(before, repository.exportRecords().toString())
            repository.cancelCommitment("c", 0, mapOf("s" to 0, "second" to 0))
            assertTrue(repository.commitments("p", true).first().single().archived)
            assertTrue(repository.instalments("p").first().all { it.cancelled && !it.remind })
            assertEquals(2_000L, repository.summary("p").first().paidPaise)
            assertEquals("s", repository.payments("p").first().single().instalmentId)
            assertEquals(7, repository.instalments("p").first().single { it.id == "s" }.reminderDays)
            repository.validateRecords(repository.exportRecords())
        } finally { repository.close() }
    }
}
