package com.constructioncost.nativeapp.platform.files

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DocumentPreviewTest {
    @Test fun imagePreviewPreservesEveryExifOrientationWithoutChangingOriginal() {
        val file = File.createTempFile("orientation-", ".jpg", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        val corners = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val expected = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
            listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2))
        try {
            val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            for (y in 0 until source.height) for (x in 0 until source.width) source.setPixel(x, y, corners[(if (y < 40) 0 else 2) + if (x < 60) 0 else 1])
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            source.recycle()
            for (orientation in 1..8) {
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                val hash = DocumentPolicy.sha256(file)
                val image = previewImage(file)
                try {
                    assertEquals(if (orientation >= 5) 80 else 120, image.width)
                    assertEquals(if (orientation >= 5) 120 else 80, image.height)
                    val pixels = listOf(image.getPixel(image.width / 4, image.height / 4), image.getPixel(image.width * 3 / 4, image.height / 4),
                        image.getPixel(image.width / 4, image.height * 3 / 4), image.getPixel(image.width * 3 / 4, image.height * 3 / 4))
                    pixels.zip(expected[orientation - 1]).forEach { (actual, corner) ->
                        val color = corners[corner]
                        assertTrue("EXIF orientation $orientation", kotlin.math.abs(Color.red(actual) - Color.red(color)) < 40 &&
                            kotlin.math.abs(Color.green(actual) - Color.green(color)) < 40 && kotlin.math.abs(Color.blue(actual) - Color.blue(color)) < 40)
                    }
                    assertEquals(hash, DocumentPolicy.sha256(file))
                } finally { image.recycle() }
            }
        } finally { file.delete() }
    }
    @Test fun textPreviewIsBoundedAndPlainAndDoesNotChangeOriginal() {
        val file = File.createTempFile("preview-", ".txt", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeText("<script>offline evidence</script>\nUnicode: ₹ 水")
            assertEquals(file.readText(), previewText(file))
            file.outputStream().use { out -> repeat(140) { out.write(ByteArray(8192) { 'a'.code.toByte() }) } }
            val size = file.length()
            val result = previewText(file)
            assertTrue(result.length < 1_049_000)
            assertTrue(result.contains("Preview limited"))
            assertEquals(size, file.length())
        } finally { file.delete() }
    }

    @Test fun largeImagesAreSampledAndInvalidImagesFailCleanly() {
        val file = File.createTempFile("preview-", ".png", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            val source = Bitmap.createBitmap(3000, 2000, Bitmap.Config.RGB_565)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            source.recycle()
            val image = previewImage(file)
            assertTrue(image.width.toLong() * image.height <= 4_000_000L)
            assertEquals(1500, image.width)
            assertEquals(1000, image.height)
            image.recycle()
            file.writeText("Not an image")
            assertThrows(IllegalArgumentException::class.java) { previewImage(file) }
            assertTrue(supportsLocalPreview("image/png"))
            assertTrue(supportsLocalPreview("audio/mpeg"))
            assertFalse(supportsLocalPreview("text/html"))
        } finally { file.delete() }
    }
}
