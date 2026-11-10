package com.constructioncost.nativeapp.platform.files

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CameraCapturesTest {
    @Test fun legacyCaptureMigratesWithoutChangingBytesAndDiscardStaysScoped() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(base.cacheDir, "capture-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val files = File(sandbox, "files").apply { check(mkdirs()) }
        val cache = File(sandbox, "cache").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
        }
        try {
            val captures = CameraCaptures(context)
            val current = captures.create("photo-${UUID.randomUUID()}.jpg")
            current.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1))
            assertEquals(current, captures.resolveExisting(current.name))
            val legacy = File(cache, "exports").apply { check(mkdirs()) }
            val old = File(legacy, "photo-${UUID.randomUUID()}.jpg")
            val originalBytes = "preserved legacy capture".toByteArray()
            old.writeBytes(originalBytes)
            assertEquals(old.canonicalFile, captures.resolveExisting(old.name))
            val listed = captures.list()
            assertEquals(2, listed.size)
            val migrated = listed.single { it.name == old.name }
            val parent = requireNotNull(migrated.parentFile) { "The migrated capture must have a private parent directory" }
            assertEquals(File(files, "camera-captures").canonicalFile, parent.canonicalFile)
            assertArrayEquals(originalBytes, migrated.readBytes())
            assertFalse(old.exists())
            val unrelated = File(sandbox, "photo-${UUID.randomUUID()}.jpg").apply { writeText("other file") }
            assertThrows(IllegalArgumentException::class.java) { captures.discard(unrelated) }
            assertTrue(unrelated.exists())
            captures.discard(migrated)
            assertFalse(migrated.exists())
            assertEquals(listOf(current), captures.list())
        } finally {
            check(sandbox.canonicalFile.parentFile == base.cacheDir.canonicalFile && sandbox.name.startsWith("capture-test-"))
            sandbox.deleteRecursively()
        }
    }
}
