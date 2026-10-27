package com.constructioncost.nativeapp.platform.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.constructioncost.nativeapp.domain.AttachmentInfo
import com.constructioncost.nativeapp.platform.files.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal suspend fun renderPdfPage(renderer: PdfRenderer, page: Int, width: Int, zoom: Float): Bitmap {
    currentCoroutineContext().ensureActive()
    require(page in 0 until renderer.pageCount) { "Page no longer exists." }
    return renderer.openPage(page).use { sheet ->
        val wanted = (width * 2.5f * zoom).roundToInt().coerceAtLeast(1)
        val scale = min(wanted.toFloat() / sheet.width,
            sqrt(8_000_000f / (sheet.width.toFloat() * sheet.height)))
        val pixels = createBitmap((sheet.width * scale).roundToInt().coerceAtLeast(1),
            (sheet.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            pixels.eraseColor(Color.WHITE)
            sheet.render(pixels, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            currentCoroutineContext().ensureActive()
            pixels
        } catch (failure: Throwable) { pixels.recycle(); throw failure }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(info: AttachmentInfo, store: AttachmentStore, onBack: () -> Unit) {
    var page by rememberSaveable(info.id) { mutableIntStateOf(0) }
    var zoom by rememberSaveable(info.id) { mutableFloatStateOf(1f) }
    var pageCount by remember(info.id) { mutableIntStateOf(0) }
    var bitmap by remember(info.id) { mutableStateOf<Bitmap?>(null) }
    var error by remember(info.id) { mutableStateOf<String?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val currentWidth by rememberUpdatedState(viewportWidth)

    LaunchedEffect(info) {
        bitmap = null
        error = null
        try {
            withContext(Dispatchers.IO) {
                // Immutable originals are verified once; this session owns the descriptor and renderer until exit.
                store.openPdf(info).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        require(renderer.pageCount > 0) { "PDF has no pages." }
                        withContext(Dispatchers.Main) { pageCount = renderer.pageCount }
                        snapshotFlow { Triple(page, zoom, currentWidth) }.filter { it.third > 0 }
                            .collectLatest { (requestedPage, requestedZoom, requestedWidth) ->
                            withContext(Dispatchers.Main) { bitmap = null }
                            val rendered = renderPdfPage(renderer, requestedPage, requestedWidth, requestedZoom)
                            var published = false
                            try {
                                withContext(Dispatchers.Main) { bitmap = rendered; published = true }
                            } finally { if (!published) rendered.recycle() }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = when (failure) {
                is SecurityException -> "This PDF is locked or cannot be opened."
                else -> "Cannot render PDF: ${failure.message ?: "file is damaged or unsupported"}"
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(info.name, maxLines = 1) }, navigationIcon = {
        TextButton(onBack) { Text("Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text("Previous page") }
                Text("${if (pageCount == 0) 0 else page + 1} / $pageCount")
                TextButton(onClick = { if (page + 1 < pageCount) page++ }, enabled = page + 1 < pageCount) { Text("Next page") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Text("Zoom out") }
                Text("${(zoom * 100).roundToInt()}%")
                TextButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) }, enabled = zoom < 3f) { Text("Zoom in") }
            }
            error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) }
            if (bitmap == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.fillMaxSize().onSizeChanged {
                viewportWidth = with(density) { it.width.toDp().value.roundToInt() }
            }) {
                val imageWidth = maxWidth * zoom
                bitmap?.let { image ->
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                        Image(image.asImageBitmap(), "PDF page ${page + 1} of $pageCount",
                            Modifier.width(imageWidth).padding(8.dp))
                    }
                }
            }
        }
    }
}
