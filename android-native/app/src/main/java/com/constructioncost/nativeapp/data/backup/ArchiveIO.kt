package com.constructioncost.nativeapp.data.backup

import com.constructioncost.nativeapp.platform.files.DocumentPolicy
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

internal object ArchiveIO {
    const val MAX_ARCHIVE_BYTES = 850L * 1024 * 1024
    const val MAX_JSON_BYTES = 256L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 1024L * 1024
    const val MAX_FILES = 1000
    const val MAX_RECORDS = 1000000
    fun stage(input: InputStream, directory: File): File {
        val archive = File(directory, "source.zip")
        archive.outputStream().use { DocumentPolicy.copy(input, it, MAX_ARCHIVE_BYTES) }
        return archive
    }
    fun extract(archive: File, directory: File, legacy: Boolean = false, availableBytes: () -> Long): Map<String, File> {
        require(archive.length() <= MAX_ARCHIVE_BYTES) { "Archive exceeds 850 MiB" }
        val files = linkedMapOf<String, File>()
        var total = 0L
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                val valid = if (legacy) name == "header.json" || name == "state.enc" || name.matches(Regex("objects/[A-Za-z0-9_-]+\\.enc"))
                    else name == "manifest.json" || name == "records.json" || name.matches(Regex("files/[A-Za-z0-9_-]+"))
                require(valid && !entry.isDirectory && !files.containsKey(name)) { "Unsafe, unexpected or duplicate archive entry: $name" }
                require(files.size < MAX_FILES + 2) { "Archive contains too many entries" }
                require(!legacy || entry.method == java.util.zip.ZipEntry.STORED) { "Unsupported legacy ZIP compression" }
                val limit = when (name) {
                    "records.json" -> MAX_JSON_BYTES
                    "state.enc" -> MAX_JSON_BYTES + 16
                    "manifest.json", "header.json" -> MAX_MANIFEST_BYTES
                    else -> DocumentPolicy.MAX_FILE_BYTES + if (legacy) 16 else 0
                }
                require(entry.size in 0..limit) { "Archive entry exceeds its size limit: $name" }
                require(total + entry.size <= MAX_ARCHIVE_BYTES) { "Archive expanded size exceeds 850 MiB" }
                require(availableBytes() > entry.size + 1024 * 1024) { "Not enough private storage to inspect the archive" }
                val target = File(directory, "entry-${files.size}")
                val crc = java.util.zip.CRC32()
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= limit && total + size <= MAX_ARCHIVE_BYTES) { "Archive expanded size exceeds its limit" }
                            crc.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                require(size == entry.size && crc.value == entry.crc) { "Archive checksum or size mismatch: $name" }
                total += size
                files[name] = target
            }
        }
        return files
    }
}
