package com.constructioncost.nativeapp.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveIOTest {
    @Test fun archiveRoundTripAndTraversalRejection() {
        val directory = Files.createTempDirectory("archive-test").toFile()
        try {
            val archive = File(directory, "archive.zip")
            write(archive, mapOf("manifest.json" to "{}", "records.json" to "{}", "files/test-id" to "original receipt"))
            val result = ArchiveIO.extract(archive, directory) { Long.MAX_VALUE }
            assertEquals(setOf("manifest.json", "records.json", "files/test-id"), result.keys)
            assertEquals("original receipt", result.getValue("files/test-id").readText())
            write(archive, mapOf("files/../../outside" to "malicious"))
            assertThrows(IllegalArgumentException::class.java) { ArchiveIO.extract(archive, directory) { Long.MAX_VALUE } }
            assertFalse(checkNotNull(directory.parentFile).resolve("outside").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun expandedEntryLimitIsEnforced() {
        val directory = Files.createTempDirectory("archive-limit").toFile()
        try {
            val archive = File(directory, "large.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("files/large"))
                repeat(251) { zip.write(ByteArray(1024 * 1024)) }
                zip.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { ArchiveIO.extract(archive, directory) { Long.MAX_VALUE } }
        } finally { directory.deleteRecursively() }
    }
    @Test fun duplicateNamesAreRejectedEvenWhenZipIndexContainsBoth() {
        val directory = Files.createTempDirectory("archive-duplicate").toFile()
        try {
            val archive = File(directory, "duplicate.zip")
            write(archive, mapOf("files/first" to "one", "files/other" to "two"))
            val bytes = archive.readBytes()
            val old = "files/other".toByteArray()
            val replacement = "files/first".toByteArray()
            var occurrences = 0
            for (index in 0..(bytes.size - old.size)) {
                if (old.indices.all { bytes[index + it] == old[it] }) {
                    replacement.copyInto(bytes, index)
                    occurrences++
                }
            }
            assertEquals(2, occurrences)
            archive.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java) { ArchiveIO.extract(archive, directory) { Long.MAX_VALUE } }
        } finally { directory.deleteRecursively() }
    }
    @Test fun ledgerBeyondFormerThirtyTwoMiBLimitCanBeExtracted() {
        val directory = Files.createTempDirectory("archive-large-ledger").toFile()
        try {
            val archive = File(directory, "ledger.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("records.json"))
                val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }
                repeat(33) { zip.write(chunk) }
                zip.closeEntry()
            }
            assertEquals(33L * 1024 * 1024, ArchiveIO.extract(archive, directory) { Long.MAX_VALUE }.getValue("records.json").length())
        } finally { directory.deleteRecursively() }
    }
    @Test fun storageHeadroomIsCheckedBeforeEachEntryWithoutChangingTheOriginalArchive() {
        val directory = Files.createTempDirectory("archive-capacity").toFile()
        try {
            val archive = File(directory, "archive.zip")
            write(archive, linkedMapOf("manifest.json" to "{}", "files/test-id" to "receipt"))
            val original = archive.readBytes()
            var checks = 0
            assertThrows(IllegalArgumentException::class.java) {
                ArchiveIO.extract(archive, directory) {
                    checks++
                    if (checks == 1) Long.MAX_VALUE else 1024L * 1024 + 7
                }
            }
            assertEquals(2, checks)
            assertEquals("{}", File(directory, "entry-0").readText())
            assertFalse(File(directory, "entry-1").exists())
            assertArrayEquals(original, archive.readBytes())
        } finally { directory.deleteRecursively() }
    }
    private fun write(file: File, contents: Map<String, String>) = ZipOutputStream(file.outputStream()).use { zip ->
        contents.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
    }
}
