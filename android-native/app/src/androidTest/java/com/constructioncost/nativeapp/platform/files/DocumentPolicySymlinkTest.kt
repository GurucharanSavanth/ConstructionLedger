package com.constructioncost.nativeapp.platform.files

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DocumentPolicySymlinkTest {
    @Test fun archiveCleanupNeverDeletesSymlinkTargets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "symlink-cleanup-$id").apply { check(mkdir()) }
        val outside = File(context.cacheDir, "symlink-target-$id").apply { check(mkdir()) }
        val original = File(outside, "original.txt").apply { writeText("keep") }
        val topLevelLink = File(root, "backup-$id")
        val staging = File(root, "legacy-$id").apply { check(mkdir()) }
        val nestedLink = File(staging, "entry-0")
        try {
            Os.symlink(outside.path, topLevelLink.path)
            Os.symlink(original.path, nestedLink.path)
            DocumentPolicy.cleanupArchiveStaging(root, System.currentTimeMillis() + 1_000)
            assertEquals("keep", original.readText())
            assertTrue(Files.isSymbolicLink(topLevelLink.toPath()))
            assertTrue(Files.isSymbolicLink(nestedLink.toPath()))
        } finally {
            Files.deleteIfExists(nestedLink.toPath())
            Files.deleteIfExists(topLevelLink.toPath())
            staging.delete()
            original.delete()
            outside.delete()
            root.delete()
        }
    }
}
