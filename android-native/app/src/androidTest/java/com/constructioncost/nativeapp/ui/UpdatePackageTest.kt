package com.constructioncost.nativeapp.ui

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.SeparateVerification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@SeparateVerification
@RunWith(AndroidJUnit4::class)
class UpdatePackageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture get() = File("/data/local/tmp/buildledger-updater-V23.apk").also {
        check(context.packageName.endsWith(".verification") && it.isFile) { "Use the disposable verification package and staged public V23 APK" }
    }
    private fun info(): PackageInfo = context.packageManager.getPackageArchiveInfo(fixture.path, PackageManager.GET_SIGNING_CERTIFICATES)!!
    private fun cached(bytes: ByteArray = fixture.readBytes()): AppRelease {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        File(context.cacheDir, "updates").apply { mkdirs() }.resolve("$digest.apk").writeBytes(bytes)
        return AppRelease("V23", "https://invalid.example/not-used.apk", bytes.size.toLong(), digest)
    }

    @Test fun arbitraryTagUsesSignedApkVersionAndReusesVerifiedCache() = runBlocking<Unit> {
        val release = cached()
        val (file, candidate) = verifiedUpdateDownload(context, release)
        assertEquals("f.005", candidate.versionName)
        assertEquals(6L, candidate.longVersionCode)
        assertEquals(fixture.length(), file.length())
        val installed = info().apply { longVersionCode = 5; versionName = "f.004" }
        assertTrue(newerUpdatePackage(candidate, installed))
        val (_, sameApkUnderAnotherTag) = verifiedUpdateDownload(context, release.copy(tag = "any-release-name"))
        assertTrue(newerUpdatePackage(sameApkUnderAnotherTag, installed))
    }

    @Test fun sameAndOlderVersionCodesAreNotUpdates() {
        val candidate = info()
        assertFalse(newerUpdatePackage(candidate, info()))
        assertFalse(newerUpdatePackage(candidate, info().apply { longVersionCode = 7 }))
    }

    @Test fun differentApplicationOrSigningKeyIsRejected() {
        val candidate = info()
        assertThrows(IllegalStateException::class.java) {
            newerUpdatePackage(candidate, info().apply { packageName = "another.application"; longVersionCode = 5 })
        }
        val differentKey = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .apply { packageName = candidate.packageName; longVersionCode = 5 }
        assertThrows(IllegalStateException::class.java) { newerUpdatePackage(candidate, differentKey) }
    }

    @Test fun tamperedCacheAndInvalidApkCannotReachInstallation() = runBlocking<Unit> {
        val release = cached()
        val file = File(context.cacheDir, "updates/${release.digest}.apk")
        file.appendBytes(byteArrayOf(1))
        try {
            verifiedUpdateDownload(context, release)
            fail("Tampered cache must be rejected before the untrusted fallback URL is used")
        } catch (expected: IllegalArgumentException) {
            assertFalse(file.exists())
        }
        val invalid = cached(byteArrayOf(1, 2, 3))
        try {
            verifiedUpdateDownload(context, invalid)
            fail("Non-APK bytes must be rejected")
        } catch (expected: IllegalStateException) {
            assertFalse(File(context.cacheDir, "updates/${invalid.digest}.apk").exists())
        }
    }
}
