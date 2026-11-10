package com.constructioncost.nativeapp.platform.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.constructioncost.nativeapp.domain.AttachmentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

internal fun supportsLocalPreview(mime: String) = mime.startsWith("image/") || mime.startsWith("audio/") ||
    mime.startsWith("video/") || mime in setOf("text/plain", "text/csv")

internal fun previewText(file: File): String = file.reader(Charsets.UTF_8).use { reader ->
    val text = StringBuilder()
    val buffer = CharArray(8192)
    while (text.length < 1_048_576) {
        val count = reader.read(buffer, 0, minOf(buffer.size, 1_048_576 - text.length))
        if (count < 0) return@use text.toString()
        text.append(buffer, 0, count)
    }
    if (reader.read() >= 0) text.append("\n\nPreview limited to the first 1 MiB of text. Export the original to read the complete file.")
    text.toString()
}

internal fun previewImage(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image." }
    var sample = 1
    while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 4_000_000L) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Image cannot be decoded.")
    val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }
    if (matrix.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPreviewScreen(info: AttachmentInfo, store: AttachmentStore, back: () -> Unit, external: () -> Unit) {
    BackHandler(onBack = back)
    var bitmap by remember(info.id) { mutableStateOf<Bitmap?>(null) }
    var text by remember(info.id) { mutableStateOf<String?>(null) }
    var file by remember(info.id) { mutableStateOf<File?>(null) }
    var error by remember(info.id) { mutableStateOf<String?>(null) }
    var zoom by rememberSaveable(info.id) { mutableFloatStateOf(1f) }
    val media = info.mimeType.startsWith("audio/") || info.mimeType.startsWith("video/")
    LaunchedEffect(info.id) {
        try {
            val preview = withContext(Dispatchers.IO) {
                val verified = store.verifiedFile(info)
                Triple(verified, if (!media && info.mimeType.startsWith("image/")) previewImage(verified) else null,
                    if (!media && !info.mimeType.startsWith("image/")) previewText(verified) else null)
            }
            file = preview.first; bitmap = preview.second; text = preview.third
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "This file cannot be opened. Try opening it with another installed app." }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(info.name) }, navigationIcon = { TextButton(back) { Text("Back") } },
        actions = { TextButton(external) { Text("Open with") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (media) {
                if (file == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                file?.let { LocalMediaPreview(info, it) { error = "This media cannot be played. Try opening it with another installed app." } }
            } else if (bitmap == null && text == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            bitmap?.let { image ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton({ zoom = (zoom - .5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Text("Zoom out") }
                    Text("${(zoom * 100).toInt()}%")
                    TextButton({ zoom = (zoom + .5f).coerceAtMost(4f) }, enabled = zoom < 4f) { Text("Zoom in") }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val imageWidth = maxWidth * zoom
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                        Image(image.asImageBitmap(), info.name, Modifier.width(imageWidth))
                    }
                }
            }
            text?.let { Text(it, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) }
        }
    }
}

@Composable
private fun LocalMediaPreview(info: AttachmentInfo, file: File, failed: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember(info.id) { mutableStateOf<VideoView?>(null) }
    Text("Use the playback controls to play or pause. Playback pauses when the app moves to the background.", Modifier.padding(16.dp))
    AndroidView(factory = { context -> VideoView(context).apply {
        setMediaController(MediaController(context).also { it.setAnchorView(this) })
        setOnErrorListener { _, _, _ -> failed(); true }
        setOnPreparedListener { controller ->
            controller.setScreenOnWhilePlaying(info.mimeType.startsWith("video/"))
            seekTo(1)
        }
        setVideoURI(Uri.fromFile(file))
        view = this
    } }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 420.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton({ view?.start() }) { Text("Play") }
        TextButton({ view?.pause() }) { Text("Pause") }
        TextButton({ view?.seekTo(0) }) { Text("Restart") }
    }
    DisposableEffect(lifecycle, info.id) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) view?.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view?.stopPlayback() }
    }
}
