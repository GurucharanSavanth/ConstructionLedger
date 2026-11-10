package com.constructioncost.nativeapp.platform.files

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Keeps captured bytes until their document import commits or the user discards them. */
class CameraCaptures(private val context: Context) {
    private val root: File get() = File(context.filesDir, "camera-captures").apply {
        check((isDirectory || mkdirs()) && !Files.isSymbolicLink(toPath()) && canonicalFile.parentFile == context.filesDir.canonicalFile) {
            "Unsafe camera capture directory"
        }
    }
    private val legacy: File get() = File(context.cacheDir, "exports")

    fun create(name: String): File = resolve(name).also { check(it.createNewFile()) { "Cannot prepare camera photo" } }

    fun resolve(name: String): File {
        require(NAME.matches(name)) { "Invalid camera photo name" }
        val base = root
        val file = File(base, name)
        require(!Files.isSymbolicLink(file.toPath())) { "Unsafe camera photo" }
        return DocumentPolicy.safeFile(base, name)
    }

    fun resolveExisting(name: String): File {
        val current = resolve(name)
        if (current.exists()) return current
        val old = File(legacy, name)
        return if (old.exists()) checked(old) else current
    }

    fun checked(file: File): File {
        require(NAME.matches(file.name)) { "Invalid camera photo name" }
        val parent = file.parentFile ?: error("Camera photo path is missing")
        val allowed = parent.canonicalFile == root.canonicalFile ||
            parent.canonicalFile == legacy.canonicalFile && legacy.isDirectory &&
                !Files.isSymbolicLink(legacy.toPath()) && legacy.canonicalFile.parentFile == context.cacheDir.canonicalFile
        require(allowed && !Files.isSymbolicLink(file.toPath())) { "Photo is outside private camera storage" }
        return DocumentPolicy.safeFile(parent, file.name)
    }

    fun list(): List<File> {
        val old = legacy
        if (old.isDirectory && !Files.isSymbolicLink(old.toPath()) && old.canonicalFile.parentFile == context.cacheDir.canonicalFile) {
            eligible(old).forEach { source -> try { migrate(source) } catch (_: Exception) {
                // Keep the original cache file available for a later retry.
            } }
        }
        return (eligible(root) + if (old.isDirectory && !Files.isSymbolicLink(old.toPath()) &&
            old.canonicalFile.parentFile == context.cacheDir.canonicalFile) eligible(old) else emptyList())
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
    }

    fun discard(file: File) {
        val source = checked(file)
        check(source.isFile && source.delete()) { "Captured photo could not be discarded" }
    }

    private fun eligible(directory: File): List<File> = directory.listFiles().orEmpty().filter {
        NAME.matches(it.name) && it.isFile && it.length() > 0 && !Files.isSymbolicLink(it.toPath()) &&
            it.canonicalFile.parentFile == directory.canonicalFile
    }.map { DocumentPolicy.safeFile(directory, it.name) }

    private fun migrate(source: File) {
        if (source.length() > DocumentPolicy.MAX_FILE_BYTES) return
        var destination = resolve(source.name)
        if (destination.exists()) {
            if (destination.length() == source.length() && DocumentPolicy.sha256(destination) == DocumentPolicy.sha256(source)) {
                source.delete()
                return
            }
            destination = resolve("photo-${UUID.randomUUID()}.jpg")
        }
        val staged = File.createTempFile("capture-", ".tmp", root)
        try {
            FileOutputStream(staged).use { output -> source.inputStream().use { DocumentPolicy.copy(it, output) }; output.fd.sync() }
            check(staged.length() == source.length() && DocumentPolicy.sha256(staged) == DocumentPolicy.sha256(source)) {
                "Camera capture changed during migration"
            }
            try { Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(staged.toPath(), destination.toPath()) }
            source.delete()
        } finally { staged.delete() }
    }

    companion object {
        private val NAME = Regex("photo-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg")
    }
}
