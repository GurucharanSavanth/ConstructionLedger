package com.constructioncost.nativeapp.platform.files

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class DocumentPolicyTest {
    @Test fun interruptedCaptureCleanupPreservesPendingPhotoAndUnrelatedFiles() {
        val directory = Files.createTempDirectory("camera-cleanup-test").toFile()
        try {
            val old = directory.resolve("photo-00000000-0000-0000-0000-000000000000.jpg").apply { writeText("old") }
            val pending = directory.resolve("photo-11111111-1111-1111-1111-111111111111.jpg").apply { writeText("active") }
            val unrelated = directory.resolve("receipt.pdf").apply { writeText("receipt") }
            val fresh = directory.resolve("photo-22222222-2222-2222-2222-222222222222.jpg").apply { writeText("new capture") }
            assertTrue(old.setLastModified(1000))
            assertTrue(pending.setLastModified(1000))
            assertTrue(fresh.setLastModified(3000))
            DocumentPolicy.cleanupCameraFiles(directory, pending.name, 2000)
            assertFalse(old.exists())
            assertEquals("active", pending.readText())
            assertEquals("receipt", unrelated.readText())
            assertEquals("new capture", fresh.readText())
            DocumentPolicy.cleanupCameraFiles(directory, null, 2000)
            assertFalse(pending.exists())
            assertTrue(unrelated.exists())
            assertTrue(fresh.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun batchImportCategoryMatchesPreservedLegacyDefaults() {
        assertEquals("Survey / CAD", DocumentPolicy.category("Site.DWG"))
        assertEquals("Site photographs", DocumentPolicy.category("photo.HEIC"))
        assertEquals("Miscellaneous", DocumentPolicy.category("invoice.pdf"))
        assertEquals("Miscellaneous", DocumentPolicy.category("no-extension"))
    }
    @Test fun originalBytesSurviveBoundedCopyAndKnownSha256() {
        val directory = Files.createTempDirectory("document-test").toFile()
        try {
            val file = directory.resolve("abc.txt")
            file.outputStream().use { assertEquals(3L, DocumentPolicy.copy(ByteArrayInputStream("abc".toByteArray()), it)) }
            assertEquals("abc", file.readText())
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DocumentPolicy.sha256(file))
            DocumentPolicy.validate(file, "text/plain")
            DocumentPolicy.validate(file, "application/octet-stream")
            DocumentPolicy.validate(file, "application/x-dwg")
            assertThrows(IllegalArgumentException::class.java) { DocumentPolicy.validate(file, "../invalid") }
            assertThrows(IllegalArgumentException::class.java) { DocumentPolicy.validate(file, "application/pdf") }
            assertThrows(IllegalArgumentException::class.java) { DocumentPolicy.safeFile(directory, "../outside.txt") }
            assertThrows(IllegalArgumentException::class.java) { DocumentPolicy.copy(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream(), 3) }
        } finally { directory.deleteRecursively() }
    }
}
