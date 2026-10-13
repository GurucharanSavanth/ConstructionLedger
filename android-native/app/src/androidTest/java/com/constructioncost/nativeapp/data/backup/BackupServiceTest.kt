package com.constructioncost.nativeapp.data.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.ExpenseInput
import com.constructioncost.nativeapp.domain.InstalmentInput
import com.constructioncost.nativeapp.domain.ProjectInfo
import com.constructioncost.nativeapp.platform.LegacyImportMapper
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    @Test fun nativeBackupRestoresOriginalBytesAndRejectsTampering() = runBlocking {
        val (context, sandbox) = isolatedContext()
        val repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        val id = UUID.randomUUID().toString()
        val projectId = UUID.randomUUID().toString()
        val original = File(store.root, "$id.bin")
        val backup = File(context.cacheDir, "native-$id.zip")
        val encrypted = File(context.cacheDir, "native-$id.blbackup")
        val older = File(context.cacheDir, "native-v1-$id.zip")
        val olderTwo = File(context.cacheDir, "native-v2-$id.zip")
        val olderThree = File(context.cacheDir, "native-v3-$id.zip")
        val badCounts = File(context.cacheDir, "native-v1-counts-$id.zip")
        val corrupted = File(context.cacheDir, "native-corrupt-$id.zip")
        val badSettings = File(context.cacheDir, "native-settings-$id.zip")
        try {
            repository.saveProject(ProjectInfo(projectId, "House"))
            repository.saveExpense(ExpenseInput(id = "invoice-$id", projectId = projectId, description = "Concrete invoice", amountPaise = 1_850_000, date = "2026-01-01", invoiceNumber = "INV-42"))
            repository.saveInstalment(InstalmentInput(id = "plan-$id", projectId = projectId, title = "Milestone", amountPaise = 900_000, dueDate = "2026-02-01", remind = true, reminderDays = 7))
            original.writeText("%PDF-1.4\noriginal native bytes\n")
            repository.insertAttachment(AttachmentInfo(id, projectId, "Receipt.pdf", "application/pdf", original.length(),
                DocumentPolicy.sha256(original), original.name, 1, category = "Invoice", status = "Approved", revisionLabel = "R2", work = "Concrete", place = "Level 1", notes = "Signed original"))
            val service = BackupService(context, repository, store)
            val settings = JSONObject().put("project", projectId).put("reminders", true).put("showArchived", false)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.create(Uri.fromFile(backup), JSONObject().put("password", "must-not-export"))
            } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.create(Uri.fromFile(backup), JSONObject().put("project", "x".repeat(16 * 1024)))
            } }
            val created = service.create(Uri.fromFile(backup), settings)
            assertEquals(1850000L, created.financials.recordedPaise)
            assertEquals(0L, created.financials.paidPaise)
            assertEquals(1850000L, created.financials.outstandingPaise)
            assertEquals(created.financials, service.inspect(Uri.fromFile(backup)).financials)
            assertEquals(created.counts, service.create(Uri.fromFile(encrypted), settings, "correct-native-password").counts)
            assertTrue(NativeBackupEncryption.isEncrypted(encrypted))
            assertEquals(created.counts, service.inspect(Uri.fromFile(encrypted), "correct-native-password").counts)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.inspect(Uri.fromFile(encrypted)) } }
            assertThrows(Exception::class.java) { runBlocking { service.restore(Uri.fromFile(encrypted), "wrong-native-password") } }
            assertEquals("Invoice", repository.attachment(id)!!.category)
            assertEquals(1, created.fileCount)
            assertEquals(projectId, service.inspect(Uri.fromFile(backup)).settings.getString("project"))
            assertTrue(service.inspect(Uri.fromFile(backup)).settings.getBoolean("reminders"))
            assertEquals(created.counts, service.inspect(Uri.fromFile(backup)).counts)
            rewriteZip(backup, badSettings) { name, bytes ->
                if (name == "manifest.json") JSONObject(bytes.toString(Charsets.UTF_8))
                    .put("settings", JSONObject().put("password", "must-not-import"))
                    .toString().toByteArray(Charsets.UTF_8) else bytes
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.inspect(Uri.fromFile(badSettings)) } }
            downgradeToSchema1(backup, older)
            assertEquals(0, service.inspect(Uri.fromFile(older)).settings.length())
            downgradeToSchema2(backup, olderTwo)
            assertEquals(created.counts, service.inspect(Uri.fromFile(olderTwo)).counts)
            downgradeToSchema3(backup, olderThree)
            assertEquals(created.counts, service.inspect(Uri.fromFile(olderThree)).counts)
            rewriteZip(older, badCounts) { name, bytes ->
                if (name == "manifest.json") JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                    getJSONObject("counts").put("projects", 2)
                }.toString().toByteArray(Charsets.UTF_8) else bytes
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.inspect(Uri.fromFile(badCounts)) } }
            store.delete(id)
            assertNull(repository.attachment(id))
            rewriteZip(backup, corrupted) { name, bytes ->
                if (name == "files/$id") bytes.apply { this[0] = (this[0].toInt() xor 1).toByte() } else bytes
            }
            assertThrows(Exception::class.java) { runBlocking { service.restore(Uri.fromFile(corrupted)) } }
            assertNull(repository.attachment(id))
            assertEquals(0, service.restore(Uri.fromFile(older)).settings.length())
            assertEquals(0, repository.exportRecords().getJSONArray("planned_classifications").length())
            assertEquals("", repository.attachment(id)!!.category)
            assertEquals("", repository.expense("invoice-$id")!!.invoiceNumber)
            assertEquals(3, repository.instalments(projectId).first().single().reminderDays)
            store.delete(id)
            assertEquals(created.counts, service.restore(Uri.fromFile(olderTwo)).counts)
            assertEquals("", repository.attachment(id)!!.notes)
            assertEquals("", repository.expense("invoice-$id")!!.invoiceNumber)
            assertEquals(3, repository.instalments(projectId).first().single().reminderDays)
            store.delete(id)
            assertEquals(created.counts, service.restore(Uri.fromFile(olderThree)).counts)
            assertEquals("", repository.expense("invoice-$id")!!.invoiceNumber)
            assertEquals(3, repository.instalments(projectId).first().single().reminderDays)
            store.delete(id)
            val restored = service.restore(Uri.fromFile(backup))
            assertEquals(restored.counts, service.restore(Uri.fromFile(encrypted), "correct-native-password").counts)
            assertEquals(created.counts, restored.counts)
            assertEquals(projectId, restored.settings.getString("project"))
            assertTrue(restored.settings.getBoolean("reminders"))
            assertFalse(restored.settings.getBoolean("showArchived"))
            val info = repository.attachment(id)!!
            assertEquals("%PDF-1.4\noriginal native bytes\n", store.fileFor(info).readText())
            assertEquals(info.sha256, DocumentPolicy.sha256(store.fileFor(info)))
            assertEquals(listOf("Invoice", "Approved", "R2", "Concrete", "Level 1", "Signed original"), listOf(info.category, info.status, info.revisionLabel, info.work, info.place, info.notes))
            assertEquals("INV-42", repository.expense("invoice-$id")!!.invoiceNumber)
            assertEquals(7, repository.instalments(projectId).first().single().reminderDays)
        } finally {
            repository.attachment(id)?.let { store.fileFor(it).delete() }
            original.delete(); backup.delete(); older.delete(); olderTwo.delete(); olderThree.delete(); badCounts.delete(); corrupted.delete(); badSettings.delete(); repository.close(); sandbox.deleteRecursively()
        }
    }

    @Test fun webEncryptedFixtureImportsAndRejectsWrongPasswordAndUnsafePath() = runBlocking {
        val (context, sandbox) = isolatedContext()
        val source = File(context.cacheDir, "legacy-fixture-${UUID.randomUUID()}.zip")
        val unsafe = File(context.cacheDir, "legacy-unsafe-${UUID.randomUUID()}.zip")
        val damaged = File(context.cacheDir, "legacy-damaged-${UUID.randomUUID()}.zip")
        val repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("legacy_web_v1.zip").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val reader = LegacyArchiveReader(context)
            assertThrows(Exception::class.java) { runBlocking { reader.read(Uri.fromFile(source), "wrong-password-123") } }
            rewriteZip(source, unsafe) { name, bytes ->
                if (name == "header.json") bytes.toString(Charsets.UTF_8).replace("objects/legacy-pdf-1.enc", "../outside.enc").toByteArray()
                else bytes
            }
            assertThrows(Exception::class.java) { runBlocking { reader.read(Uri.fromFile(unsafe), "fixture-password-123") } }
            rewriteZip(source, damaged) { name, bytes ->
                if (name == "objects/legacy-pdf-1.enc") bytes.apply { this[0] = (this[0].toInt() xor 1).toByte() } else bytes
            }
            assertThrows(Exception::class.java) { runBlocking { reader.read(Uri.fromFile(damaged), "fixture-password-123") } }
            reader.read(Uri.fromFile(source), "fixture-password-123").use { legacy ->
                assertEquals("Legacy fixture house", legacy.state.getJSONObject("project").getString("name"))
                assertEquals("%PDF-1.4\noriginal fixture\n", legacy.files.getValue("legacy-pdf-1").readText())
                val mapped = LegacyImportMapper(context).map(legacy.state)
                val report = BackupService(context, repository, store).restorePrepared(mapped.records, legacy.files)
                assertEquals("BuildLedgerLegacy", report.format)
                assertEquals(1, report.fileCount)
                assertEquals("%PDF-1.4\noriginal fixture\n", store.fileFor(repository.attachment("legacy-pdf-1")!!).readText())
            }
        } finally {
            repository.attachment("legacy-pdf-1")?.let { store.fileFor(it).delete() }
            source.delete(); unsafe.delete(); damaged.delete(); repository.close(); sandbox.deleteRecursively()
        }
    }

    @Test fun preparedRestoreKeepsOldOriginalOnFailureAndPrunesItAfterCommit() = runBlocking {
        val (context, sandbox) = isolatedContext()
        val repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        val oldId = UUID.randomUUID().toString()
        val newId = UUID.randomUUID().toString()
        val projectId = UUID.randomUUID().toString()
        val old = File(store.root, "$oldId.bin")
        val source = File(context.cacheDir, "candidate-$newId.pdf")
        try {
            repository.saveProject(ProjectInfo(projectId, "House"))
            old.writeText("old original")
            repository.insertAttachment(AttachmentInfo(oldId, projectId, "Old", "text/plain", old.length(), DocumentPolicy.sha256(old), old.name, 1))
            source.writeText("%PDF-1.4\nnew original")
            val records = repository.exportRecords()
            val attachments = records.getJSONArray("attachments")
            attachments.remove(0)
            attachments.put(JSONObject().put("id", newId).put("projectId", projectId).put("name", "New")
                .put("mimeType", "application/pdf").put("size", source.length())
                .put("sha256", DocumentPolicy.sha256(source)).put("storageName", "$newId.bin")
                .put("createdAt", 2).put("revision", 0).put("parentId", JSONObject.NULL)
                .put("category", "").put("status", "").put("revisionLabel", "")
                .put("work", "").put("place", "").put("notes", ""))
            val service = BackupService(context, repository, store)
            source.appendText("damage")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.restorePrepared(records, mapOf(newId to source)) } }
            assertEquals("old original", old.readText())
            assertNotNull(repository.attachment(oldId))
            source.writeText("%PDF-1.4\nnew original")
            val report = service.restorePrepared(records, mapOf(newId to source))
            assertEquals(1, report.fileCount)
            assertNull(repository.attachment(oldId))
            assertFalse(old.exists())
            assertEquals(source.readText(), store.fileFor(repository.attachment(newId)!!).readText())
        } finally {
            source.delete()
            old.delete()
            repository.attachment(newId)?.let { store.fileFor(it).delete() }
            repository.close()
            sandbox.deleteRecursively()
        }
    }

    private fun isolatedContext(): Pair<Context, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "backup-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir(): File = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        } to sandbox
    }

    private fun rewriteZip(source: File, target: File, changed: (String, ByteArray) -> ByteArray) {
        ZipFile(source).use { input -> ZipOutputStream(target.outputStream()).use { output ->
            val entries = input.entries()
            while (entries.hasMoreElements()) {
                val original = entries.nextElement()
                val bytes = changed(original.name, input.getInputStream(original).use { it.readBytes() })
                val entry = ZipEntry(original.name)
                if (original.method == ZipEntry.STORED) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        } }
    }

    private fun downgradeToSchema1(source: File, target: File) {
        val oldRecords = ZipFile(source).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("records.json")).use { it.readBytes().toString(Charsets.UTF_8) })
        }.apply {
            remove("planned_classifications")
            getJSONArray("expense_lines").let { lines ->
                for (index in 0 until lines.length()) lines.getJSONObject(index).remove("nature")
            }
            removeDocumentDetails(this)
            removeInvoiceReminderDetails(this)
        }.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(oldRecords).joinToString("") { "%02x".format(it) }
        rewriteZip(source, target) { name, bytes -> when (name) {
            "records.json" -> oldRecords
            "manifest.json" -> JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                put("schema", 1)
                getJSONObject("counts").remove("planned_classifications")
                put("recordsSha256", hash)
                remove("settings")
            }.toString().toByteArray(Charsets.UTF_8)
            else -> bytes
        } }
    }

    private fun downgradeToSchema2(source: File, target: File) {
        val oldRecords = ZipFile(source).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("records.json")).use { it.readBytes().toString(Charsets.UTF_8) })
        }.apply { removeDocumentDetails(this); removeInvoiceReminderDetails(this) }.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(oldRecords).joinToString("") { "%02x".format(it) }
        rewriteZip(source, target) { name, bytes -> when (name) {
            "records.json" -> oldRecords
            "manifest.json" -> JSONObject(bytes.toString(Charsets.UTF_8)).apply {
                put("schema", 2)
                put("recordsSha256", hash)
            }.toString().toByteArray(Charsets.UTF_8)
            else -> bytes
        } }
    }

    private fun removeDocumentDetails(records: JSONObject) {
        val attachments = records.getJSONArray("attachments")
        for (index in 0 until attachments.length()) {
            val row = attachments.getJSONObject(index)
            listOf("category", "status", "revisionLabel", "work", "place", "notes").forEach { row.remove(it) }
        }
    }
    private fun downgradeToSchema3(source: File, target: File) {
        val oldRecords = ZipFile(source).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("records.json")).use { it.readBytes().toString(Charsets.UTF_8) })
        }.apply { removeInvoiceReminderDetails(this) }.toString().toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(oldRecords).joinToString("") { "%02x".format(it) }
        rewriteZip(source, target) { name, bytes -> when (name) {
            "records.json" -> oldRecords
            "manifest.json" -> JSONObject(bytes.toString(Charsets.UTF_8)).apply { put("schema", 3); put("recordsSha256", hash) }.toString().toByteArray(Charsets.UTF_8)
            else -> bytes
        } }
    }
    private fun removeInvoiceReminderDetails(records: JSONObject) {
        records.getJSONArray("expenses").let { items -> for (index in 0 until items.length()) items.getJSONObject(index).remove("invoiceNumber") }
        records.getJSONArray("reminders").let { items -> for (index in 0 until items.length()) items.getJSONObject(index).remove("leadDays") }
    }
}
