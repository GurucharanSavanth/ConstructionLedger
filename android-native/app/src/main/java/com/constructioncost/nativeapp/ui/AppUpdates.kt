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
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest

private const val RELEASES = "https://api.github.com/repos/GurucharanSavanth/ConstructionLedger/releases/latest"
private const val MAX_APK = 250L * 1024 * 1024
internal data class AppRelease(val tag: String, val url: String, val size: Long, val digest: String, val versionName: String = "")
private val updateDownloadMutex = Mutex()

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

private suspend fun download(address: String, maximum: Long, sink: (ByteArray, Int) -> Unit) {
    var location = address
    repeat(6) {
        currentCoroutineContext().ensureActive()
        val request = connection(location)
        try {
            val status = request.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                location = URL(URL(location), request.getHeaderField("Location") ?: error("Missing update redirect")).toString()
            } else {
                if (status != 200) throw UpdateHttpException(status)
                var total = 0L
                request.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
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

internal fun newerUpdatePackage(candidate: PackageInfo, installed: PackageInfo): Boolean {
    check(candidate.packageName == installed.packageName) { "Update belongs to another application" }
    check(signers(candidate).isNotEmpty() && signers(candidate) == signers(installed)) { "Update signing key differs from installed application" }
    return version(candidate) > version(installed)
}

private suspend fun latest(context: Context): AppRelease? = withContext(Dispatchers.IO) {
    val bytes = java.io.ByteArrayOutputStream()
    download(RELEASES, 2L * 1024 * 1024) { buffer, count -> bytes.write(buffer, 0, count) }
    val release = JSONObject(bytes.toString("UTF-8"))
    check(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "Release is not stable" }
    val tag = release.getString("tag_name")
    val assets = release.getJSONArray("assets")
    val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk", true) }
    check(apks.size == 1) { "Release must contain exactly one universal signed APK" }
    val asset = apks.single()
    val size = asset.getLong("size")
    check(size in 1..MAX_APK) { "Invalid update size" }
    val digest = asset.optString("digest")
    check(Regex("sha256:[a-fA-F0-9]{64}").matches(digest)) { "Release has no SHA-256 digest" }
    val available = AppRelease(tag, asset.getString("browser_download_url"), size, digest.substringAfter(':'))
    val (_, candidate) = verifiedUpdateDownload(context, available)
    val installed = context.packageManager.getPackageInfo(context.packageName, packageFlags())
    if (!newerUpdatePackage(candidate, installed)) return@withContext null
    available.copy(versionName = candidate.versionName ?: version(candidate).toString())
}

internal suspend fun verifiedUpdateDownload(context: Context, release: AppRelease): Pair<File, PackageInfo> = withContext(Dispatchers.IO) { updateDownloadMutex.withLock {
    check(release.size in 1..MAX_APK && Regex("[a-fA-F0-9]{64}").matches(release.digest)) { "Invalid update metadata" }
    val directory = File(context.cacheDir, "updates").apply { mkdirs() }
    val file = File(directory, "${release.digest.lowercase()}.apk")
    val job = currentCoroutineContext()[Job]
    fun digest(): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            job?.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
    try {
        if (!file.isFile || file.length() != release.size || !digest().equals(release.digest, true)) {
            file.outputStream().use { output -> download(release.url, release.size) { buffer, count -> output.write(buffer, 0, count) } }
        }
        check(file.length() == release.size) { "Incomplete update download" }
        check(digest().equals(release.digest, true)) { "Update checksum mismatch" }
        val manager = context.packageManager
        val candidate = manager.getPackageArchiveInfo(file.path, packageFlags()) ?: error("Invalid APK")
        file to candidate
    } catch (error: Exception) { file.delete(); throw error }
} }

@Composable
internal fun AppUpdates(manual: Boolean = false, checkLatest: suspend (Context) -> AppRelease? = ::latest) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checked by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var release by rememberSaveable(stateSaver = listSaver<AppRelease?, String>(
        save = { it?.let { value -> listOf(value.tag, value.url, value.size.toString(), value.digest, value.versionName) } ?: emptyList() },
        restore = { if (it.isEmpty()) null else AppRelease(it[0], it[1], it[2].toLong(), it[3], it[4]) }
    )) { mutableStateOf<AppRelease?>(null) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var retryCheck by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    fun check(showResult: Boolean) {
        if (busy) return
        busy = true
        retryCheck = false
        message = null
        checkJob = scope.launch {
            try {
                release = checkLatest(context)
                if (showResult && release == null) message = "BuildLedger is up to date."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (showResult) { retryCheck = true; message = updateFailureMessage(error) } }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { if (!manual && !checked) { checked = true; check(false) } }
    if (manual) OutlinedButton({ check(true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy) { WebIcon("download"); Spacer(Modifier.width(12.dp)); Text(if (busy) "Checking updates…" else "Check for updates") }
    if (manual) {
        Text("Installed version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}", style = MaterialTheme.typography.bodyMedium)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (checkJob?.isActive == true) TextButton({ checkJob?.cancel() }) { Text("Cancel check") }
        }
        Text("Checks download and verify the latest APK before installation.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Get BuildLedger: https://github.com/GurucharanSavanth/ConstructionLedger/releases/latest")
            }
            context.startActivity(Intent.createChooser(send, "Share BuildLedger"))
        }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            WebIcon("share")
            Spacer(Modifier.width(12.dp))
            Text("Share app")
        }
    }
    release?.let { update -> AlertDialog(
        onDismissRequest = { if (!busy) release = null },
        title = { Text("BuildLedger ${update.versionName} available") },
        text = { Text(if (busy) "Preparing and verifying update…" else "The signed update is ready. Android will ask you to approve installation. Your ledger stays on this device.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val (apk, candidate) = verifiedUpdateDownload(context, update)
                    check(newerUpdatePackage(candidate, context.packageManager.getPackageInfo(context.packageName, packageFlags()))) { "Update version code must increase" }
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        message = "Allow updates from BuildLedger, return here, then tap Install update."
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        release = null
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = "Update failed: ${error.message}" }
                finally { busy = false }
            }
        }) { Text("Install update") } },
        dismissButton = { TextButton({ release = null }, enabled = !busy) { Text("Later") } }
    ) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("App updates") },
        text = { Text(text) }, confirmButton = {
            TextButton({ message = null; if (retryCheck) check(true) }) { Text(if (retryCheck) "Retry" else "OK") }
        }, dismissButton = { if (retryCheck) TextButton({ message = null }) { Text("Cancel") } }) }
}
