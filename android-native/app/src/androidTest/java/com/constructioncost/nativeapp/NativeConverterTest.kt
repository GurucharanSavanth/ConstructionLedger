package com.constructioncost.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.backup.BackupService
import com.constructioncost.nativeapp.data.backup.LegacyArchiveReader
import com.constructioncost.nativeapp.data.backup.NativeBackupEncryption
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.platform.LegacyImportMapper
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class NativeConverterTest {
    @Test fun desktopMappingMatchesNativeMapperAndRestoresExactly() = runBlocking<Unit> { verifyConverterFixture("complex") }
    @Test fun desktopV5PlanningMetadataMatchesNativeMapperAndRestoresExactly() = runBlocking<Unit> { verifyConverterFixture("planning-metadata") }

    private suspend fun verifyConverterFixture(prefix: String) = withContext(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val sandbox = File(base.cacheDir, "converter-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(sandbox, "files").apply { check(mkdirs() || isDirectory) }
            override fun getCacheDir(): File = File(sandbox, "cache").apply { check(mkdirs() || isDirectory) }
        }
        val repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        try {
            val external = if (prefix == "complex") InstrumentationRegistry.getArguments().getString("converterArchive") else null
            val encrypted = if (external == null) File(context.cacheDir, "native.blbackup").also { destination ->
                instrumentation.context.assets.open("converter/$prefix-native.blbackup").use { input ->
                    destination.outputStream().use { DocumentPolicy.copy(input, it) }
                }
            } else File(requireNotNull(base.getExternalFilesDir(null)), external).also { require(it.isFile) }
            val decrypted = File(context.cacheDir, "native.zip")
            NativeBackupEncryption.decrypt(encrypted, decrypted, "native-fixture-password-123")
            ZipFile(decrypted).use { zip ->
                val records = JSONObject(zip.getInputStream(zip.getEntry("records.json")).bufferedReader().use { it.readText() })
                if (JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }).getInt("schema") < 5) {
                    for (table in listOf("commitments", "instalments")) {
                        val items = records.getJSONArray(table)
                        repeat(items.length()) { index -> items.getJSONObject(index).put("note", "").also { if (table == "commitments") it.put("calculationJson", "") } }
                    }
                }
                if (external == null) {
                    val legacy = File(context.cacheDir, "legacy.zip").also { destination ->
                        instrumentation.context.assets.open("converter/$prefix-legacy.zip").use { input ->
                            destination.outputStream().use { DocumentPolicy.copy(input, it) }
                        }
                    }
                    LegacyArchiveReader(context).read(Uri.fromFile(legacy), "fixture-password-123").use { archive ->
                        val mapped = LegacyImportMapper(context).map(archive.state).records
                        for (table in LedgerRepository.TABLES) {
                            val expected = mapped.getJSONArray(table).rows(table)
                            val actual = records.getJSONArray(table).rows(table)
                            assertEquals("Converter mapping for $table", expected, actual)
                        }
                    }
                }
                val service = BackupService(context, repository, store)
                val report = service.inspect(Uri.fromFile(encrypted), "native-fixture-password-123")
                assertEquals(java.math.BigInteger.valueOf(11_120L), report.financials.recordedPaise)
                assertEquals(java.math.BigInteger.valueOf(4_000L), report.financials.paidPaise)
                assertEquals(java.math.BigInteger.valueOf(7_120L), report.financials.outstandingPaise)
                service.restore(Uri.fromFile(encrypted), "native-fixture-password-123", report.archiveSha256)
                val summary = repository.summary("legacy-project").first()
                assertEquals(11_120L, summary.recordedPaise)
                assertEquals(4_000L, summary.paidPaise)
                assertEquals(7_120L, summary.outstandingPaise)
                assertEquals(0L, summary.advancePaise)
                if (prefix == "planning-metadata") {
                    assertEquals("Agreed labour rate", repository.commitments("legacy-project").first().single().note)
                    assertEquals("200", JSONObject(repository.commitments("legacy-project").first().single().calculationJson).getString("quantity"))
                    assertEquals("Inspect before payment", repository.instalments("legacy-project").first().single().note)
                }
                assertEquals(5_000L, repository.instalments("legacy-project").first().single().outstandingPaise)
                val expectedActivity = records.getJSONArray("activity_entries").length()
                assertEquals(expectedActivity, repository.notes("legacy-project").first().size)
                val attachments = repository.attachments("legacy-project").first()
                assertEquals(report.fileCount, attachments.size)
                for (attachment in attachments) {
                    val original = File(context.cacheDir, "original-${attachment.id}")
                    zip.getInputStream(zip.getEntry("files/${attachment.id}")).use { input ->
                        original.outputStream().use { DocumentPolicy.copy(input, it) }
                    }
                    assertEquals(DocumentPolicy.sha256(original), DocumentPolicy.sha256(store.fileFor(attachment)))
                }
            }
        } finally {
            repository.close()
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            sandbox.deleteRecursively()
        }
    }

    private fun JSONArray.rows(table: String): Map<String, String> = (0 until length()).associate { index ->
        val row = JSONObject(getJSONObject(index).toString())
        if (table == "import_records") row.remove("createdAt")
        for (key in listOf("originalJson", "requestJson", "legacyJson", "beforeJson", "afterJson")) {
            if (row.has(key) && !row.isNull(key)) {
                val text = row.getString(key)
                if (text.startsWith("{")) row.put(key, JSONObject(text))
                else if (text.startsWith("[")) row.put(key, JSONArray(text))
            }
        }
        row.getString("id") to canonical(row)
    }
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
