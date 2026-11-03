@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.upload

import dev.piko.shared.storage.IosAppDirectories
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.*

/**
 * Import one file URL supplied by a document picker. Copies under coordinated, security-scoped
 * access rather than persisting a provider URL or bookmark. A false scope-acquisition result also
 * occurs for ordinary in-container files; actual access/coordination errors still fail the import.
 *
 * The host must keep the selected URL alive for this operation and enqueue only the returned
 * managed identifier after awaiting success. Each document is an independent snapshot. Folder selection,
 * picker presentation, batch rollback, progress UI and stale-import retention remain host concerns.
 * This is foreground I/O, not a background URLSession or an indefinite document-provider grant.
 */
suspend fun importIosUploadDocument(
    directories: IosAppDirectories,
    url: NSURL,
    maxBytes: Long,
): String = withContext(Dispatchers.IO) {
    require(url.scheme?.lowercase() == "file") { "A local document URL is required" }
    require(maxBytes >= 0) { "Import byte limit must not be negative" }
    val name = url.lastPathComponent ?: throw IOException("Document has no file name")
    val context = currentCoroutineContext()
    val importer = AppDirectoryUploadImporter(directories.downloads, Path(directories.caches, "upload-imports").toString())
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            var outcome: Result<String>? = null
            fun importReadable(readable: NSURL): String {
                context.ensureActive()
                require(readable.scheme?.lowercase() == "file") { "Selected document is not a file URL" }
                val path = readable.path ?: throw IOException("Document path is unavailable")
                if (SystemFileSystem.metadataOrNull(Path(path))?.isRegularFile != true) {
                    throw IOException("Selected document is not a regular file")
                }
                return importer.importFile(name, maxBytes, checkCancelled = { context.ensureActive() }) {
                    SystemFileSystem.source(Path(path))
                }
            }
            NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
                url = url,
                options = 0uL,
                error = error.ptr,
                byAccessor = { coordinated ->
                    // Never unwind a Kotlin exception through the Objective-C callback.
                    outcome = runCatching {
                        importReadable(coordinated ?: throw IOException("Document coordinator returned no URL"))
                    }
                },
            )
            val coordinatorError = error.value
            val result = if (coordinatorError != null) {
                // Some simulator processes cannot contact the file coordination service for their
                // own temporary files. Only retry a non-scoped, regular file physically inside this
                // process's temporary directory; external provider URLs must stay coordinated.
                if (outcome == null && !scoped && coordinatorError.domain == NSCocoaErrorDomain &&
                    coordinatorError.code == 256L && isPrivateTemporaryFile(url)
                ) {
                    importReadable(url)
                } else {
                    throw IOException("Document coordination failed: ${coordinatorError.domain} (${coordinatorError.code})")
                }
            } else {
                outcome?.getOrThrow() ?: throw IOException("Document coordination did not provide a readable file")
            }
            result
        }
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

/** Resolve symbolic links before accepting a fallback; paths outside the private temp root fail closed. */
private fun isPrivateTemporaryFile(url: NSURL): Boolean = runCatching {
    val rawPath = url.path ?: return@runCatching false
    val path = Path(rawPath)
    if (SystemFileSystem.metadataOrNull(path)?.isRegularFile != true) return@runCatching false
    val root = SystemFileSystem.resolve(Path(NSTemporaryDirectory())).toString().trimEnd('/')
    val resolved = SystemFileSystem.resolve(path).toString()
    root.isNotEmpty() && resolved.startsWith("$root/")
}.getOrDefault(false)
