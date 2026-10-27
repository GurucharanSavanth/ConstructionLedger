package com.constructioncost.nativeapp.platform.files

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID

class DocumentPolicyTest {
    @Test fun archiveCleanupRemovesOnlyOldOwnedStagingAndCandidateFiles() {
        val directory = Files.createTempDirectory("archive-cleanup-test").toFile()
        try {
            fun stage(prefix: String, directoryTime: Long, contentsTime: Long = directoryTime) =
                directory.resolve("$prefix-${UUID.randomUUID()}").apply {
                    assertTrue(mkdir())
                    resolve("entry-0").apply { writeText("staged financial data"); assertTrue(setLastModified(contentsTime)) }
                    assertTrue(setLastModified(directoryTime))
                }
            fun candidate(time: Long, sidecar: String = "") = directory.resolve("candidate-${UUID.randomUUID()}.db$sidecar")
                .apply { writeText("candidate"); assertTrue(setLastModified(time)) }
            val oldBackup = stage("backup", 1_000)
            val oldLegacy = stage("legacy", 1_000)
            val fresh = stage("backup", 3_000)
            val activeContents = stage("legacy", 1_000, 3_000)
            val oldDatabase = candidate(1_000)
            val oldSidecars = listOf("-wal", "-shm", "-journal").map { suffix ->
                directory.resolve(oldDatabase.name + suffix).apply { writeText("sidecar"); assertTrue(setLastModified(1_000)) }
            }
            val freshDatabase = candidate(3_000)
            val boundary = candidate(2_000)
            val activeDatabase = candidate(1_000)
            val activeWal = directory.resolve(activeDatabase.name + "-wal").apply { writeText("active"); assertTrue(setLastModified(3_000)) }
            val unrelated = directory.resolve("receipt.pdf").apply { writeText("keep"); assertTrue(setLastModified(1_000)) }
            val invalidName = directory.resolve("backup-not-a-uuid").apply { assertTrue(mkdir()); assertTrue(setLastModified(1_000)) }
            val wrongKind = directory.resolve("backup-${UUID.randomUUID()}").apply { writeText("keep"); assertTrue(setLastModified(1_000)) }

            DocumentPolicy.cleanupArchiveStaging(directory, 2_000)

            assertFalse(oldBackup.exists())
            assertFalse(oldLegacy.exists())
            assertFalse(oldDatabase.exists())
            assertTrue(oldSidecars.none { it.exists() })
            assertTrue(listOf(fresh, activeContents, freshDatabase, boundary, activeDatabase, activeWal,
                unrelated, invalidName, wrongKind).all { it.exists() })
            assertEquals("keep", unrelated.readText())
            assertEquals("staged financial data", activeContents.resolve("entry-0").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun archiveCleanupDoesNotFollowSymlinks() {
        val directory = Files.createTempDirectory("archive-cleanup-links").toFile()
        val outside = Files.createTempDirectory("archive-cleanup-outside").toFile()
        val link = directory.resolve("backup-${UUID.randomUUID()}")
        val staging = directory.resolve("legacy-${UUID.randomUUID()}").apply { assertTrue(mkdir()) }
        val nestedLink = staging.resolve("entry-0")
        try {
            val original = outside.resolve("original.txt").apply { writeText("unrelated original") }
            try {
                Files.createSymbolicLink(link.toPath(), outside.toPath())
                Files.createSymbolicLink(nestedLink.toPath(), original.toPath())
            } catch (failure: Exception) {
                org.junit.Assume.assumeNoException("Host cannot create symbolic links", failure)
            }
            assertTrue(outside.setLastModified(1_000))
            assertTrue(original.setLastModified(1_000))
            assertTrue(staging.setLastModified(1_000))
            DocumentPolicy.cleanupArchiveStaging(directory, 2_000)
            assertEquals("unrelated original", original.readText())
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertTrue(Files.isSymbolicLink(nestedLink.toPath()))
        } finally {
            Files.deleteIfExists(link.toPath())
            Files.deleteIfExists(nestedLink.toPath())
            directory.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun interruptedCaptureCleanupPreservesCapturedBytesPendingAndUnrelatedFiles() {
        val directory = Files.createTempDirectory("camera-cleanup-test").toFile()
        try {
            val old = directory.resolve("photo-00000000-0000-0000-0000-000000000000.jpg").apply { writeText("old") }
            val pending = directory.resolve("photo-11111111-1111-1111-1111-111111111111.jpg").apply { writeText("active") }
            val unrelated = directory.resolve("receipt.pdf").apply { writeText("receipt") }
            val fresh = directory.resolve("photo-22222222-2222-2222-2222-222222222222.jpg").apply { writeText("new capture") }
            val empty = directory.resolve("photo-33333333-3333-3333-3333-333333333333.jpg").apply { createNewFile() }
            val activeEmpty = directory.resolve("photo-44444444-4444-4444-4444-444444444444.jpg").apply { createNewFile() }
            assertTrue(old.setLastModified(1000))
            assertTrue(pending.setLastModified(1000))
            assertTrue(fresh.setLastModified(3000))
            assertTrue(empty.setLastModified(1000))
            assertTrue(activeEmpty.setLastModified(1000))
            DocumentPolicy.cleanupCameraFiles(directory, activeEmpty.name, 2000)
            assertEquals("old", old.readText())
            assertFalse(empty.exists())
            assertTrue(activeEmpty.exists())
            assertEquals("active", pending.readText())
            assertEquals("receipt", unrelated.readText())
            assertEquals("new capture", fresh.readText())
            DocumentPolicy.cleanupCameraFiles(directory, null, 2000)
            assertEquals("active", pending.readText())
            assertFalse(activeEmpty.exists())
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
