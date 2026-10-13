package com.constructioncost.nativeapp.data.backup

import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional password wrapper for a native ZIP. Format v1 (all integers big endian):
 * magic BLNENC01 (8), PBKDF2 iterations (4), random salt (16), random nonce prefix (8),
 * plaintext ZIP length (8), then consecutive encrypted frames of at most 1 MiB each.
 * Every frame has a 16-byte GCM tag, a nonce of prefix || zero-based frame index (4),
 * and AAD of the complete 44-byte header || frame index (4). The fixed size permits
 * exact ciphertext-length validation and bounded memory. The random prefix makes a
 * fresh nonce space for each backup; the index prevents frame substitution or reordering.
 * Decryption publishes the output only after every frame has authenticated.
 */
internal object NativeBackupEncryption {
    private val magic = "BLNENC01".toByteArray(Charsets.US_ASCII)
    private const val HEADER_BYTES = 44
    private const val ITERATIONS = 250_000
    private const val FRAME_BYTES = 1024 * 1024
    private const val TAG_BYTES = 16
    private val random = SecureRandom()

    fun isEncrypted(file: File): Boolean {
        if (!file.isFile || file.length() < magic.size) return false
        return runCatching {
            DataInputStream(file.inputStream()).use { input ->
                ByteArray(magic.size).also(input::readFully).contentEquals(magic)
            }
        }.getOrDefault(false)
    }

    fun encrypt(source: File, destination: File, password: String) {
        checkFiles(source, destination, password)
        val length = source.length()
        require(length in 1..ArchiveIO.MAX_ARCHIVE_BYTES && envelopeLength(length) <= ArchiveIO.MAX_ARCHIVE_BYTES) { "Invalid backup ZIP size" }
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(magic).putInt(ITERATIONS)
            .put(salt).put(prefix).putLong(length).array()
        val key = key(password, salt)
        try {
            publish(destination) { output ->
                output.write(header)
                val buffer = ByteArray(FRAME_BYTES)
                source.inputStream().use { input ->
                    var remaining = length
                    var index = 0
                    while (remaining > 0) {
                        val size = minOf(remaining, FRAME_BYTES.toLong()).toInt()
                        DataInputStream(input).readFully(buffer, 0, size)
                        output.write(cipher(Cipher.ENCRYPT_MODE, key, prefix, header, index).doFinal(buffer, 0, size))
                        remaining -= size
                        index++
                    }
                    require(input.read() == -1) { "Backup ZIP changed during encryption" }
                }
            }
        } finally { Arrays.fill(key, 0) }
    }

    fun decrypt(source: File, destination: File, password: String) {
        checkFiles(source, destination, password)
        require(source.length() in (HEADER_BYTES + TAG_BYTES + 1).toLong()..ArchiveIO.MAX_ARCHIVE_BYTES) { "Invalid encrypted backup size" }
        DataInputStream(source.inputStream()).use { input ->
            val header = ByteArray(HEADER_BYTES)
            input.readFully(header)
            val fields = ByteBuffer.wrap(header)
            val actualMagic = ByteArray(magic.size).also(fields::get)
            require(actualMagic.contentEquals(magic)) { "Unsupported encrypted backup" }
            require(fields.int == ITERATIONS) { "Unsupported backup key derivation" }
            val salt = ByteArray(16).also(fields::get)
            val prefix = ByteArray(8).also(fields::get)
            val length = fields.long
            require(length in 1..ArchiveIO.MAX_ARCHIVE_BYTES) { "Invalid encrypted backup size" }
            val expected = envelopeLength(length)
            require(source.length() == expected) { "Encrypted backup length mismatch" }
            val key = key(password, salt)
            try {
                publish(destination) { output ->
                    val buffer = ByteArray(FRAME_BYTES + TAG_BYTES)
                    var remaining = length
                    var index = 0
                    while (remaining > 0) {
                        val plainSize = minOf(remaining, FRAME_BYTES.toLong()).toInt()
                        input.readFully(buffer, 0, plainSize + TAG_BYTES)
                        val plain = cipher(Cipher.DECRYPT_MODE, key, prefix, header, index)
                            .doFinal(buffer, 0, plainSize + TAG_BYTES)
                        require(plain.size == plainSize) { "Invalid encrypted backup frame" }
                        output.write(plain)
                        remaining -= plainSize
                        index++
                    }
                    require(input.read() == -1) { "Encrypted backup has trailing data" }
                }
            } finally { Arrays.fill(key, 0) }
        }
    }

    private fun checkFiles(source: File, destination: File, password: String) {
        require(password.length in 10..1024) { "Backup password must be 10 to 1024 characters" }
        require(source.isFile && source.canonicalFile != destination.canonicalFile) { "Invalid backup paths" }
        require(destination.parentFile?.isDirectory == true) { "Backup destination directory is missing" }
    }

    private fun envelopeLength(plaintextLength: Long): Long = HEADER_BYTES + plaintextLength +
        ((plaintextLength + FRAME_BYTES - 1) / FRAME_BYTES) * TAG_BYTES

    private fun key(password: String, salt: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); Arrays.fill(chars, '\u0000') }
    }

    private fun cipher(mode: Int, key: ByteArray, prefix: ByteArray, header: ByteArray, index: Int): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).array()
        val aad = ByteBuffer.allocate(HEADER_BYTES + 4).put(header).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
    }

    private inline fun publish(destination: File, write: (FileOutputStream) -> Unit) {
        val temporary = File(destination.parentFile, "${destination.name}.part-${UUID.randomUUID()}")
        require(temporary.createNewFile()) { "Cannot stage encrypted backup" }
        try {
            FileOutputStream(temporary).use { output -> write(output); output.fd.sync() }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }
}
