package com.constructioncost.nativeapp.data.backup

import android.content.Context
import android.net.Uri
import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import com.constructioncost.nativeapp.platform.files.availablePrivateBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class LegacyArchive internal constructor(val state: JSONObject, val files: Map<String, File>, val metadata: JSONObject, private val staging: File) : Closeable {
    override fun close() { staging.deleteRecursively() }
}

/** Reads the preserved web archive format; native data reconciliation belongs to the importer. */
class LegacyArchiveReader(private val context: Context) {
    suspend fun read(source: Uri, password: String): LegacyArchive = withContext(Dispatchers.IO) {
        require(password.length >= 10) { "Legacy recovery password must contain at least 10 characters" }
        val directory = File(context.cacheDir, "legacy-${UUID.randomUUID()}").apply { check(mkdirs()) }
        try {
            val archive = context.contentResolver.openInputStream(source)?.use { ArchiveIO.stage(it, directory) } ?: error("Cannot open legacy backup")
            val entries = ArchiveIO.extract(archive, directory, legacy = true) { availablePrivateBytes(context, directory) }
            val header = JSONObject(entries["header.json"]?.readText() ?: error("Legacy backup header is missing"))
            require(header.get("product") == "BuildLedger" && header.get("format") == 1 && header.get("encrypted") == true && header.get("kdf") == "PBKDF2-SHA256" && header.get("iterations") == 250000) { "Unsupported legacy encryption format" }
            val salt = decode(header.getString("salt"), 16)
            val passwordSpec = PBEKeySpec(password.toCharArray(), salt, 250000, 256)
            val key = try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(passwordSpec).encoded, "AES") } finally { passwordSpec.clearPassword() }
            val database = header.getJSONObject("database")
            val stateFile = File(directory, "state.json")
            decrypt(entries["state.enc"] ?: error("Legacy database is missing"), stateFile, key, database.getString("iv"), legacyDecryptLimit(Runtime.getRuntime().maxMemory(), true))
            require(DocumentPolicy.sha256(stateFile) == database.getString("sha256")) { "Legacy database checksum mismatch" }
            val state = JSONObject(stateFile.readText())
            val stateFiles = state.getJSONArray("files")
            val objects = header.getJSONArray("objects")
            require(objects.length() == stateFiles.length() && objects.length() <= ArchiveIO.MAX_FILES) { "Legacy archive does not contain every original document" }
            val inventory = mutableMapOf<String, JSONObject>()
            for (index in 0 until objects.length()) {
                val item = objects.getJSONObject(index)
                val id = item.getString("id")
                require(id.matches(Regex("[A-Za-z0-9_-]+")) && item.getString("path") == "objects/$id.enc" && inventory.put(id, item) == null) { "Unsafe or duplicate legacy document" }
            }
            val files = linkedMapOf<String, File>()
            for (index in 0 until stateFiles.length()) {
                val meta = stateFiles.getJSONObject(index)
                val id = meta.getString("id")
                require(!files.containsKey(id)) { "Duplicate legacy document metadata" }
                val item = inventory[id] ?: error("Legacy document is missing: $id")
                val expectedSize = item.get("size")
                val metaSize = meta.get("size")
                require((expectedSize is Long || expectedSize is Int) && (metaSize is Long || metaSize is Int) && item.getLong("size") == meta.getLong("size") && item.getLong("size") in 1..DocumentPolicy.MAX_FILE_BYTES) { "Invalid legacy document size" }
                require(item.getString("sha256") == meta.getString("sha256") && (item.getString("type") == meta.getString("type") || item.getString("type") == "application/octet-stream")) { "Legacy document metadata mismatch" }
                require(availablePrivateBytes(context, directory) > item.getLong("size") + 1024 * 1024) { "Not enough storage to decrypt legacy document" }
                val plain = File(directory, "document-$index")
                decrypt(entries[item.getString("path")] ?: error("Legacy original document bytes are missing"), plain, key, item.getString("iv"), legacyDecryptLimit(Runtime.getRuntime().maxMemory(), false))
                require(plain.length() == item.getLong("size") && DocumentPolicy.sha256(plain) == item.getString("sha256")) { "Legacy document checksum or size mismatch" }
                files[id] = plain
            }
            require(entries.keys == inventory.keys.map { "objects/$it.enc" }.toSet() + setOf("header.json", "state.enc")) { "Legacy archive contains unexpected entries" }
            LegacyArchive(state, files, header, directory)
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }
    private fun decode(encoded: String, length: Int): ByteArray = Base64.getDecoder().decode(encoded).also {
        require(it.size == length) { "Invalid legacy encryption salt or IV" }
    }
    private fun decrypt(source: File, destination: File, key: SecretKeySpec, iv: String, limit: Long) {
        require(source.length() in 17..Math.addExact(limit, 16)) {
            "Legacy encrypted item exceeds this device's safe import limit (${limit / 1024 / 1024} MiB). Existing ledger is unchanged. Retain the original backup and restore it in the preserved web app on a computer. Android migration of this large item is unsupported on this device."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decode(iv, 12)))
        try {
            CipherInputStream(source.inputStream(), cipher).use { input -> destination.outputStream().use { DocumentPolicy.copy(input, it, limit) } }
        } catch (failure: Exception) { throw IllegalArgumentException("Incorrect legacy password or damaged encrypted archive", failure) }
    }
}

// shortcut: legacy GCM buffers an entire item and legacy mapping holds JSON; increase only with a streaming bridge.
internal fun legacyDecryptLimit(maxHeap: Long, database: Boolean): Long =
    minOf(if (database) 16L * 1024 * 1024 else 32L * 1024 * 1024, maxHeap / if (database) 32 else 16)
