package com.constructioncost.nativeapp.platform.files

import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.constructioncost.nativeapp.data.repository.LedgerRepository
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.domain.newId
import com.constructioncost.nativeapp.platform.pdf.PdfScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfPreviewTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: LedgerRepository
    private lateinit var original: File
    private lateinit var moved: File
    @get:Rule(order = 0) val cleanup = object : ExternalResource() {
        override fun after() {
            if (::original.isInitialized) original.delete()
            if (::moved.isInitialized) moved.delete()
            if (::repository.isInitialized) repository.close()
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pageAndZoomUseOneVerifiedSessionAndClosingDoesNotShowCancellationError() {
        repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        val id = newId()
        original = DocumentPolicy.safeFile(store.root, "$id.bin")
        moved = DocumentPolicy.safeFile(store.root, "$id-held.bin")
        val document = PdfDocument()
        try {
            for (number in 1..2) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(320, 480, number).create())
                page.canvas.drawText("Page $number", 20f, 40f, Paint().apply { textSize = 20f })
                document.finishPage(page)
            }
            original.outputStream().use(document::writeTo)
        } finally { document.close() }
        val hash = DocumentPolicy.sha256(original)
        val info = AttachmentInfo(id, "pdf-preview", "PDF preview fixture", "application/pdf", original.length(), hash, original.name, 1)
        val showing = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (showing.value) Box(Modifier.width(220.dp).testTag("previewViewport")) {
                PdfScreen(info, store) { showing.value = false }
            } else Text("Viewer closed")
        } }
        fun awaitPage(number: Int) = compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("PDF page $number of 2").fetchSemanticsNodes().isNotEmpty()
        }
        awaitPage(1)
        val viewportWidth = compose.onNodeWithTag("previewViewport").fetchSemanticsNode().boundsInRoot.width
        val pageWidth = compose.onNodeWithContentDescription("PDF page 1 of 2").fetchSemanticsNode().boundsInRoot.width
        assertTrue("PDF page must fit its viewport", pageWidth <= viewportWidth)
        assertTrue(original.renameTo(moved))
        compose.onNodeWithText("Next page").performClick()
        awaitPage(2)
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithText("150%").assertExists()
        awaitPage(2)
        compose.onNodeWithText("Previous page").performClick()
        awaitPage(1)
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Viewer closed").assertExists()
        assertTrue(compose.onAllNodesWithText("Cannot render PDF:", substring = true).fetchSemanticsNodes().isEmpty())
        assertEquals(hash, DocumentPolicy.sha256(moved))
    }

    @Test fun imagePreviewFitsConstrainedViewport() {
        repository = LedgerRepository.inMemory(context)
        val store = AttachmentStore(context, repository)
        val id = newId()
        original = DocumentPolicy.safeFile(store.root, "$id.png")
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        try { original.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val info = AttachmentInfo(id, "image-preview", "Image preview fixture", "image/png", original.length(),
            DocumentPolicy.sha256(original), original.name, 1)
        compose.setContent { MaterialTheme { Box(Modifier.width(220.dp).testTag("previewViewport")) {
            DocumentPreviewScreen(info, store, {}, {})
        } } }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(info.name).fetchSemanticsNodes().isNotEmpty()
        }
        val viewportWidth = compose.onNodeWithTag("previewViewport").fetchSemanticsNode().boundsInRoot.width
        val imageWidth = compose.onNodeWithContentDescription(info.name).fetchSemanticsNode().boundsInRoot.width
        assertEquals(viewportWidth, imageWidth, 1f)
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithText("150%").assertExists()
        assertEquals("Original bytes stay unchanged", info.sha256, DocumentPolicy.sha256(original))
    }
}
