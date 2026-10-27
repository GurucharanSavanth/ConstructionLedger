package com.constructioncost.nativeapp.ui

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.*
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlanMetadataTest {
    @Test fun agreementCalculationAndNotesSurviveDraftEditsEncryptedBackupAndRestore() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "plan-metadata-${newId()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(sandbox, "files").apply { mkdirs() }
            override fun getCacheDir() = File(sandbox, "cache").apply { mkdirs() }
        }
        val repository = LedgerRepository.inMemory(context)
        try {
            repository.saveProject(ProjectInfo("p", "House"))
            val draft = EntryDraft.fresh("p", "Contract").with("description", "Plaster")
                .with("mode", "CALCULATED").with("quantity", "1000").with("quantityUnit", "sqft")
                .with("rate", "125").with("rateUnit", "sq100").with("count", "2")
                .with("note", "Agreed rate includes labour. ₹ 水")
            val input = LedgerViewModel.commitment(draft)
            assertEquals(125_000L, input.amountPaise)
            repository.saveCommitment(input)
            val first = repository.instalments("p").first().first()
            repository.saveInstalment(InstalmentInput(first.id, "p", first.title, first.amountPaise, first.dueDate,
                commitmentId = input.id, revision = first.revision, reminderDays = 7, note = "Pay after inspection."))
            val agreement = repository.commitments("p").first().single()
            val edit = LedgerViewModel.draftForCommitment(agreement, repository.instalments("p").first())
            assertEquals("1000", edit.get("quantity"))
            assertEquals(draft.get("note"), edit.get("note"))
            repository.saveCommitment(LedgerViewModel.commitment(edit.with("description", "Plaster checked")))
            val plan = repository.instalments("p").first().single { it.id == first.id }
            assertEquals("Pay after inspection.", plan.note)
            assertEquals(7, plan.reminderDays)
            val service = BackupService(context, repository, AttachmentStore(context, repository))
            val file = File(sandbox, "metadata.blbackup")
            service.create(Uri.fromFile(file), password = "test-metadata-password")
            val report = service.inspect(Uri.fromFile(file), "test-metadata-password")
            service.restore(Uri.fromFile(file), "test-metadata-password", report.archiveSha256)
            assertEquals(draft.get("note"), repository.commitments("p").first().single().note)
            assertEquals(agreement.calculationJson, repository.commitments("p").first().single().calculationJson)
            assertEquals("Pay after inspection.", repository.instalments("p").first().single { it.id == first.id }.note)
            assertEquals(0L, repository.summary("p").first().recordedPaise)
            assertEquals(0L, repository.summary("p").first().paidPaise)
            val after = repository.commitments("p").first().single()
            repository.saveCommitment(LedgerViewModel.commitment(LedgerViewModel.draftForCommitment(after,
                repository.instalments("p").first()).with("mode", "DIRECT_AMOUNT").with("amount", "1500")))
            assertEquals("", repository.commitments("p").first().single().calculationJson)
        } finally { repository.close(); sandbox.deleteRecursively() }
    }
}
