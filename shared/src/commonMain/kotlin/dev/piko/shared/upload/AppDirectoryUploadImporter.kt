package dev.piko.shared.upload

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

/**
 * Copies a selected document into an independent, app-owned upload snapshot. Blocking: invoke on
 * an I/O dispatcher while the host holds/co-ordinates external document access. No external URLs
 * or access grants are persisted. The caller owns retention of successful imports.
 *
 * Use the same managed directory as [AppDirectoryUploadSources]. Staging must be an independent
 * app-private directory on the same filesystem (for atomic publication), outside the upload tree.
 * The callback is opened only after validating the name/limit and allocating a fresh import directory.
 * Source and sink are closed
 * before publication. A failed copy removes its own staging file, not unrelated imports.
 *
 * Like the upload source, this assumes an app-private root, not hostile concurrent filesystem
 * mutation. Each instance rechecks the root using the upload source's physical path guard.
 */
class AppDirectoryUploadImporter(managedDirectory: String, stagingDirectory: String) {
    private val root = Path(managedDirectory)
    private val staging = Path(stagingDirectory)
    private val guard = pathGuard(managedDirectory)
    private val stagingGuard = pathGuard(stagingDirectory)

    init {
        require(independent(root, staging) && independent(physical(root), physical(staging))) {
            "Import staging must be outside the managed upload directory"
        }
    }

    /**
     * Returns a durable `piko-app-upload:` identifier. [maxBytes] is an explicit per-file limit;
     * the source is streamed with a 64 KiB maximum request, even when the file has no known size.
     * [checkCancelled] should throw to abort, including between reads and before publication.
     */
    fun importFile(
        fileName: String,
        maxBytes: Long,
        checkCancelled: () -> Unit = {},
        openSource: () -> RawSource,
    ): String {
        require(maxBytes >= 0) { "Import byte limit must not be negative" }
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName && ':' !in fileName &&
            '\u0000' !in fileName && fileName != "." && fileName != "..") { "Invalid import file name" }
        checkCancelled()
        val id = Uuid.random().toString()
        val relativeDirectory = "imports/$id"
        val relativeFile = "$relativeDirectory/$fileName"
        val uri = guard.uriFor(relativeFile)
        stagingGuard.uriFor(id)
        val directory = Path(root, relativeDirectory)
        val stage = Path(staging, id)
        // UUIDs are collision-resistant, but never claim ownership of an existing entry.
        check(!SystemFileSystem.exists(directory) && !SystemFileSystem.exists(stage)) { "Import allocation already exists" }
        var published = false
        val target = Path(root, relativeFile)
        try {
            SystemFileSystem.createDirectories(directory)
            SystemFileSystem.createDirectories(staging)
            // Recheck paths after creation, before opening any external document. Staging is outside
            // the upload tree so a concurrent folder selection never observes an incomplete file.
            guard.uriFor(relativeFile)
            stagingGuard.uriFor(id)
            openSource().use { source ->
                SystemFileSystem.sink(stage).buffered().use { sink ->
                    val buffer = Buffer()
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val count = source.readAtMostTo(buffer, 64L * 1024)
                        if (count == -1L) break
                        if (count <= 0L || count > 64L * 1024 || buffer.size != count) {
                            throw IOException("Document source returned an invalid read count")
                        }
                        if (count > maxBytes - total) throw IOException("Document exceeds import byte limit")
                        sink.write(buffer, count)
                        total += count
                    }
                }
            }
            checkCancelled()
            guard.uriFor(relativeFile)
            stagingGuard.uriFor(id)
            SystemFileSystem.atomicMove(stage, target)
            published = true
            return uri
        } finally {
            if (!published) {
                // Cleanup failures must not replace the copy/cancellation exception. This directory
                // contains only this import; do not recursively delete unknown or redirected data.
                runCatching { stagingGuard.uriFor(id); SystemFileSystem.delete(stage, mustExist = false) }
                runCatching { guard.uriFor(relativeFile); SystemFileSystem.delete(directory, mustExist = false) }
            }
        }
    }

    private companion object {
        fun pathGuard(root: String) = AppDirectoryUploadSources(root, object : UploadFileAccess {
            override fun modifiedAtMs(path: String): Long = error("Importer does not query timestamps")
            override fun openAt(path: String, offset: Long): RawSource = error("Importer does not read uploads")
        })
        fun independent(a: Path, b: Path): Boolean {
            fun text(path: Path) = path.toString().replace('\\', '/').trimEnd('/')
            val first = text(a)
            val second = text(b)
            return first != second && !first.startsWith("$second/") && !second.startsWith("$first/")
        }
        fun physical(path: Path): Path {
            if (SystemFileSystem.metadataOrNull(path) != null) return SystemFileSystem.resolve(path)
            return Path(physical(path.parent ?: throw IOException("No existing import ancestor")), path.name)
        }
    }
}
