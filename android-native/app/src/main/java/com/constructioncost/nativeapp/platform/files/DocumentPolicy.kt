package com.constructioncost.nativeapp.platform.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import com.constructioncost.nativeapp.domain.MAX_ATTACHMENT_BYTES

object DocumentPolicy {
    fun cleanupCameraFiles(directory: File, pendingName: String?, createdBefore: Long) {
        directory.listFiles()?.filter { it.isFile && it.name != pendingName &&
            it.name.matches(Regex("photo-[0-9a-fA-F-]{36}\\.jpg")) && it.lastModified() < createdBefore && it.canonicalFile.parentFile == directory.canonicalFile }
            ?.forEach { check(it.delete()) { "Cannot remove interrupted photo capture" } }
    }
    fun category(name: String): String = when (name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
        "dwg", "dxf", "ifc", "rvt", "dgn", "skp" -> "Survey / CAD"
        "jpg", "jpeg", "png", "webp", "gif", "heic", "bmp", "avif" -> "Site photographs"
        else -> "Miscellaneous"
    }
    const val MAX_FILE_BYTES = MAX_ATTACHMENT_BYTES
    fun copy(input: InputStream, output: OutputStream, limit: Long = MAX_FILE_BYTES): Long {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= limit) { "Document exceeds the $limit byte limit" }
            output.write(buffer, 0, read)
        }
        return count
    }
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun safeFile(root: File, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9]{1,10})?"))) { "Unsafe document storage name" }
        val file = File(root, name).canonicalFile
        require(file.parentFile == root.canonicalFile) { "Document escapes private storage" }
        return file
    }
    fun validate(file: File, mime: String) {
        require(file.length() in 1..MAX_FILE_BYTES) { "Document is empty or exceeds 250 MiB" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}").matches(mime)) { "Invalid document MIME type" }
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(512)
            var size = 0
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
            buffer.copyOf(size)
        }
        fun starts(vararg bytes: Int) = head.size >= bytes.size && bytes.indices.all { head[it].toInt() and 255 == bytes[it] }
        val valid = when (mime) {
            "application/pdf" -> starts(37, 80, 68, 70, 45)
            "image/jpeg" -> starts(255, 216, 255)
            "image/png" -> starts(137, 80, 78, 71, 13, 10, 26, 10)
            "image/gif" -> String(head.take(6).toByteArray(), Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
            "image/webp" -> head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
            "text/plain", "text/csv" -> head.none { it.toInt() == 0 } && head.all { (it.toInt() and 255) >= 32 || it.toInt() in setOf(9, 10, 13) || it.toInt() < 0 }
            "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint" -> starts(208, 207, 17, 224, 161, 177, 26, 225)
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> starts(80, 75, 3, 4)
            // Opaque originals are preserved without decoding; viewing uses an installed handler.
            else -> true
        }
        require(valid) { "Unsupported document type or contents do not match $mime" }
    }
}
