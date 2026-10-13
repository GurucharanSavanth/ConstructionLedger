package com.constructioncost.nativeapp.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordBoundariesTest {
    @Test fun wideUtf8RestoreAndOversizedAuditCannotCommit() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LedgerRepository.inMemory(context)
        val file = File.createTempFile("oversized-record-", ".json", context.cacheDir)
        try {
            repository.saveProject(ProjectInfo("project", "House"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repository.saveExpense(ExpenseInput(id = "oversized", projectId = "project", description = "Invoice", amountPaise = 100, date = "2026-01-01", note = "\"".repeat(500_000)))
            } }
            assertTrue(repository.expenses("project").first().isEmpty())
            assertEquals(1, repository.exportRecords().getJSONArray("audit_records").length())
            repository.saveExpense(ExpenseInput(id = "retained", projectId = "project", description = "Original", amountPaise = 100, date = "2026-01-01"))
            val malicious = repository.exportRecords()
            malicious.getJSONArray("expenses").getJSONObject(0).put("note", "अ".repeat(400_000))
            file.writeText(malicious.toString())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.replaceRecordsFile(file, LedgerRepository.SNAPSHOT_VERSION) } }
            assertEquals("Original", repository.expense("retained")!!.description)
            assertEquals(100L, repository.summary("project").first().recordedPaise)
        } finally { repository.close(); file.delete() }
    }
}
