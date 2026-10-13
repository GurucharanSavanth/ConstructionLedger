package com.constructioncost.nativeapp.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest

class NativeBackupEncryptionTest {
    private val password = "correct horse backup password"

    @Test fun multiFrameRoundTripAndMagicDetection() = inDirectory { directory ->
        val source = File(directory, "source.zip")
        source.outputStream().use { output ->
            val chunk = ByteArray(1024 * 1024) { index -> (index * 31).toByte() }
            repeat(3) { output.write(chunk) }
            output.write(ByteArray(17) { it.toByte() })
        }
        val encrypted = File(directory, "encrypted.bln")
        val restored = File(directory, "restored.zip")
        assertFalse(NativeBackupEncryption.isEncrypted(source))
        NativeBackupEncryption.encrypt(source, encrypted, password)
        assertTrue(NativeBackupEncryption.isEncrypted(encrypted))
        assertEquals(44L + source.length() + 4L * 16, encrypted.length())
        NativeBackupEncryption.decrypt(encrypted, restored, password)
        assertEquals(source.length(), restored.length())
        assertArrayEquals(hash(source), hash(restored))
    }

    @Test fun rejectsCorruptionAndLeavesExistingDestinationUntouched() = inDirectory { directory ->
        val source = File(directory, "source.zip")
        source.outputStream().use { output ->
            val chunk = ByteArray(1024 * 1024) { index -> (index * 17).toByte() }
            repeat(2) { output.write(chunk) }
            output.write(7)
        }
        val encrypted = File(directory, "good.bln")
        NativeBackupEncryption.encrypt(source, encrypted, password)
        val target = File(directory, "target.zip")
        fun reject(name: String, edit: (File) -> Unit) {
            val changed = File(directory, name)
            encrypted.copyTo(changed)
            edit(changed)
            target.writeText("untouched")
            assertThrows(Exception::class.java) { NativeBackupEncryption.decrypt(changed, target, password) }
            assertEquals("untouched", target.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith("target.zip.part-") })
        }
        target.writeText("untouched")
        assertThrows(Exception::class.java) { NativeBackupEncryption.decrypt(encrypted, target, "wrong backup password") }
        assertEquals("untouched", target.readText())
        reject("bad-magic.bln") { flip(it, 0) }
        reject("bad-iterations.bln") { flip(it, 8) }
        reject("bad-salt.bln") { flip(it, 12) }
        reject("bad-nonce.bln") { flip(it, 28) }
        reject("bad-length.bln") { flip(it, 43) }
        reject("bad-ciphertext.bln") { flip(it, 44) }
        reject("bad-tag.bln") { flip(it, it.length() - 1) }
        reject("truncated.bln") { RandomAccessFile(it, "rw").use { file -> file.setLength(file.length() - 1) } }
        reject("appended.bln") { it.appendBytes(byteArrayOf(0)) }
        reject("oversized-header.bln") { RandomAccessFile(it, "rw").use { file -> file.seek(36); file.writeLong(ArchiveIO.MAX_ARCHIVE_BYTES + 1) } }
        reject("swapped-frames.bln") { file ->
            val frameSize = 1024 * 1024 + 16
            RandomAccessFile(file, "rw").use { io ->
                val first = ByteArray(frameSize)
                val second = ByteArray(frameSize)
                io.seek(44); io.readFully(first); io.readFully(second)
                io.seek(44); io.write(second); io.write(first)
            }
        }
    }

    @Test fun rejectsEmptyInputAndShortPasswords() = inDirectory { directory ->
        val empty = File(directory, "empty.zip").apply { createNewFile() }
        val output = File(directory, "output.bln")
        assertThrows(IllegalArgumentException::class.java) { NativeBackupEncryption.encrypt(empty, output, password) }
        assertFalse(output.exists())
        empty.writeText("zip")
        assertThrows(IllegalArgumentException::class.java) { NativeBackupEncryption.encrypt(empty, output, "short") }
        assertFalse(output.exists())
    }

    private fun flip(file: File, offset: Long) = RandomAccessFile(file, "rw").use { io ->
        io.seek(offset)
        val value = io.readByte()
        io.seek(offset)
        io.writeByte(value.toInt() xor 1)
    }

    private fun hash(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private inline fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("native-backup-encryption").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }
}
