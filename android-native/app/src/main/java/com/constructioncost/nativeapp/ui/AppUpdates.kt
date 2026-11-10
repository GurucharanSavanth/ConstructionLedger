package com.constructioncost.nativeapp.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest

internal fun newerRelease(remote: String, installed: String): Boolean {
    fun sequence(value: String): Long? = Regex("v?f\\.(\\d{3,})").matchEntire(value)
        ?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }
    val nextSequence = sequence(remote)
    val currentSequence = sequence(installed)
    if (nextSequence != null) return if (currentSequence != null) nextSequence > currentSequence
        else Regex("v?\\d+\\.\\d+\\.\\d+").matches(installed)
    if (currentSequence != null) return false
    fun parts(value: String): List<Long>? = Regex("v?(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(value)
        ?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }
    val next = parts(remote) ?: return false
    val current = parts(installed) ?: return false
    return next.zip(current).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

private const val RELEASES = "https://api.github.com/repos/GurucharanSavanth/ConstructionLedger/releases/latest"
private const val MAX_APK = 250L * 1024 * 1024
private data class AppRelease(val tag: String, val url: String, val size: Long, val digest: String)

private fun connection(address: String): HttpURLConnection {
    val url = URL(address)
    require(url.protocol == "https" && url.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Untrusted update address" }
    return (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", "BuildLedger-Updater")
        setRequestProperty("Accept", "application/vnd.github+json")
    }
}

private fun download(address: String, maximum: Long, sink: (ByteArray, Int) -> Unit) {
    var location = address
    repeat(6) {
        val request = connection(location)
        try {
            val status = request.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                location = URL(URL(location), request.getHeaderField("Location") ?: error("Missing update redirect")).toString()
            } else {
                check(status == 200) { "Update server returned HTTP $status" }
                var total = 0L
                request.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= maximum) { "Update exceeds size limit" }
                        sink(buffer, count)
                    }
                }
                return
            }
        } finally { request.disconnect() }
    }
    error("Too many update redirects")
}

@Suppress("DEPRECATION")
private fun packageFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
@Suppress("DEPRECATION")
private fun signers(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28)
    info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map {
    MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
}.toSet()
@Suppress("DEPRECATION")
private fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

private suspend fun latest(context: Context): AppRelease? = withContext(Dispatchers.IO) {
    val bytes = java.io.ByteArrayOutputStream()
    download(RELEASES, 2L * 1024 * 1024) { buffer, count -> bytes.write(buffer, 0, count) }
    val release = JSONObject(bytes.toString("UTF-8"))
    check(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "Release is not stable" }
    val installed = context.packageManager.getPackageInfo(context.packageName, 0)
    val tag = release.getString("tag_name")
    if (!newerRelease(tag, installed.versionName.orEmpty())) return@withContext null
    val assets = release.getJSONArray("assets")
    val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk", true) }
    check(apks.size == 1) { "Release must contain exactly one universal signed APK" }
    val asset = apks.single()
    val size = asset.getLong("size")
    check(size in 1..MAX_APK) { "Invalid update size" }
    val digest = asset.optString("digest")
    check(Regex("sha256:[a-fA-F0-9]{64}").matches(digest)) { "Release has no SHA-256 digest" }
    AppRelease(tag, asset.getString("browser_download_url"), size, digest.substringAfter(':'))
}

private suspend fun prepare(context: Context, release: AppRelease): File = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    val file = File(directory, "update.apk")
    try {
        val hash = MessageDigest.getInstance("SHA-256")
        file.outputStream().use { output -> download(release.url, release.size) { buffer, count ->
            hash.update(buffer, 0, count)
            output.write(buffer, 0, count)
        } }
        check(file.length() == release.size) { "Incomplete update download" }
        check(hash.digest().joinToString("") { "%02x".format(it) }.equals(release.digest, true)) { "Update checksum mismatch" }
        val manager = context.packageManager
        val candidate = manager.getPackageArchiveInfo(file.path, packageFlags()) ?: error("Invalid APK")
        val installed = manager.getPackageInfo(context.packageName, packageFlags())
        check(candidate.packageName == context.packageName) { "Update belongs to another application" }
        check(version(candidate) > version(installed)) { "Update version code must increase" }
        check(signers(candidate).isNotEmpty() && signers(candidate) == signers(installed)) { "Update signing key differs from installed application" }
        check(candidate.versionName == release.tag.removePrefix("v")) { "APK version differs from release tag" }
        file
    } catch (error: Exception) { file.delete(); throw error }
}

@Composable
internal fun AppUpdates(manual: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checked by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun check(showResult: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                release = latest(context)
                if (showResult && release == null) message = "BuildLedger is up to date."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (showResult) message = "Could not check updates: ${error.message}. Try again later." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { if (!manual && !checked) { checked = true; check(false) } }
    if (manual) TextButton({ check(true) }, enabled = !busy) { Text(if (busy) "Checking updates…" else "Check for updates") }
    release?.let { update -> AlertDialog(
        onDismissRequest = { if (!busy) { release = null; ready = null } },
        title = { Text("BuildLedger ${update.tag} available") },
        text = { Text(if (busy) "Downloading and verifying update…" else "Download the signed update from GitHub. Android will ask you to approve installation. Your ledger stays on this device.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val apk = ready ?: prepare(context, update).also { ready = it }
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        message = "Allow updates from BuildLedger, return here, then tap Install update."
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        release = null
                        ready = null
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = "Update failed: ${error.message}"; ready = null }
                finally { busy = false }
            }
        }) { Text(if (ready == null) "Download update" else "Install update") } },
        dismissButton = { TextButton({ release = null; ready = null }, enabled = !busy) { Text("Later") } }
    ) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("App updates") },
        text = { Text(text) }, confirmButton = { TextButton({ message = null }) { Text("OK") } }) }
}
