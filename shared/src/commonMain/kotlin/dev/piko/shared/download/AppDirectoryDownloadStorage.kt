package dev.piko.shared.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Filesystem downloads confined to two disjoint app-owned directories. No directories are created
 * by construction or queries. The exporter writes atomically to [downloadTarget]; commit only checks
 * that captured destination, even if the preference has subsequently changed.
 *
 * Existing ancestors are physically resolved (including platform aliases above the roots). Links
 * below either root are rejected, including dangling entries detectable by directory enumeration.
 * kotlinx-io has no no-follow/openat API: checks and subsequent I/O are not one atomic operation.
 * These app-private roots must not be concurrently replaced by an untrusted process. Hard links,
 * mount points and filesystem-specific aliases are subject to the host's filesystem semantics.
 */
public class AppDirectoryDownloadStorage(
    managedDirectory: String,
    cacheDirectory: String,
    private val directoryProvider: () -> String = { managedDirectory },
) : PikoDownloadStorage {
    private val managed = absolute(managedDirectory)
    private val cache = absolute(cacheDirectory)
    private val managedPhysical = physical(managed)
    private val cachePhysical = physical(cache)

    init {
        require(managed.parent != null && cache.parent != null) { "Filesystem roots are not app directories" }
        require(SystemFileSystem.metadataOrNull(managed)?.isDirectory != false && SystemFileSystem.metadataOrNull(cache)?.isDirectory != false) {
            "Existing app roots must be directories"
        }
        require(managedPhysical.parent != null && cachePhysical.parent != null) { "Filesystem roots are not app directories" }
        require(!within(managed, cache) && !within(cache, managed)) { "Download and cache roots must be disjoint" }
        require(!within(managedPhysical, cachePhysical) && !within(cachePhysical, managedPhysical)) {
            "Physical download and cache roots must be disjoint"
        }
    }

    override fun pathFor(fileName: String): String = target(fileName).toString()

    override suspend fun downloadTarget(fileName: String): String = withContext(Dispatchers.IO) {
        val destination = target(fileName)
        val parent = checkNotNull(destination.parent)
        SystemFileSystem.createDirectories(parent)
        guard(destination, managed, managedPhysical)
        if (SystemFileSystem.metadataOrNull(parent)?.isDirectory != true) throw IOException("Download parent is not a directory")
        val metadata = SystemFileSystem.metadataOrNull(destination)
        if (metadata != null && !metadata.isRegularFile) throw IOException("Download destination is not a regular file")
        destination.toString()
    }

    override suspend fun cacheTarget(name: String): String = withContext(Dispatchers.IO) {
        relative(name)
        require('/' !in name) { "Cache name must be a single component" }
        val destination = Path(cache, "$name.data")
        guard(destination, cache, cachePhysical)
        SystemFileSystem.createDirectories(cache)
        guard(destination, cache, cachePhysical)
        if (SystemFileSystem.metadataOrNull(cache)?.isDirectory != true) throw IOException("Cache root is not a directory")
        val metadata = SystemFileSystem.metadataOrNull(destination)
        if (metadata != null && !metadata.isRegularFile) throw IOException("Cache destination is not a regular file")
        destination.toString()
    }

    override suspend fun commit(fileName: String, downloadedPath: String): String = withContext(Dispatchers.IO) {
        relative(fileName)
        val captured = absolute(downloadedPath)
        require(captured != managed && within(captured, managed)) { "Commit requires a managed download destination" }
        require(text(captured).endsWith("/$fileName")) { "Commit destination does not match file name" }
        guard(captured, managed, managedPhysical)
        if (SystemFileSystem.metadataOrNull(captured)?.isRegularFile != true) throw IOException("Exported download is missing or not a regular file")
        // Do not consult directoryProvider here: this is the path captured before export.
        downloadedPath
    }

    override suspend fun existingLength(fileName: String): Long = withContext(Dispatchers.IO) {
        SystemFileSystem.metadataOrNull(target(fileName))?.takeIf { it.isRegularFile }?.size ?: 0L
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        SystemFileSystem.metadataOrNull(target(fileName, allowEmpty = true)) != null
    }

    override suspend fun locate(fileName: String): String? = withContext(Dispatchers.IO) {
        val destination = target(fileName, allowEmpty = true)
        destination.toString().takeIf { SystemFileSystem.metadataOrNull(destination) != null }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val destination = deletionTarget(path)
        val metadata = SystemFileSystem.metadataOrNull(destination) ?: return@withContext false
        if (metadata.isDirectory && SystemFileSystem.list(destination).isNotEmpty()) return@withContext false
        if (!metadata.isDirectory && !metadata.isRegularFile) return@withContext false
        // delete is deliberately non-recursive; genuine filesystem failures propagate.
        SystemFileSystem.delete(destination)
        true
    }

    override suspend fun pruneEmptyFolders(folder: String): Unit = withContext(Dispatchers.IO) {
        val start = target(folder, allowEmpty = true)
        fun prune(directory: Path) {
            guard(directory, managed, managedPhysical)
            if (SystemFileSystem.metadataOrNull(directory)?.isDirectory != true) return
            for (child in SystemFileSystem.list(directory)) {
                // A physical mismatch is a link/alias. Leave it in place, without following it.
                val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
                val expected = Path(physical(directory), child.name)
                if (physical(child) != expected) continue
                if (metadata.isDirectory) prune(child)
            }
            guard(directory, managed, managedPhysical)
            if (directory != managed && SystemFileSystem.list(directory).isEmpty()) SystemFileSystem.delete(directory)
        }
        prune(start)
    }

    private fun target(fileName: String, allowEmpty: Boolean = false): Path {
        relative(fileName, allowEmpty)
        val selectedValue = directoryProvider()
        val selected = if (selectedValue.isBlank()) managed else absolute(selectedValue)
        require(within(selected, managed)) { "Selected directory must be within managed downloads" }
        guard(selected, managed, managedPhysical)
        val destination = if (fileName.isEmpty()) selected else Path(selected, fileName)
        guard(destination, managed, managedPhysical)
        return destination
    }

    private fun deletionTarget(value: String): Path {
        val destination = if (Path(value).isAbsolute) absolute(value) else target(value)
        require(destination != managed && destination != cache) { "App roots must not be deleted" }
        when {
            within(destination, managed) -> guard(destination, managed, managedPhysical)
            within(destination, cache) -> guard(destination, cache, cachePhysical)
            else -> throw IllegalArgumentException("Delete path is outside app roots")
        }
        return destination
    }

    private fun guard(path: Path, root: Path, rootPhysical: Path) {
        require(within(path, root)) { "Path is outside app root" }
        require(physical(root) == rootPhysical) { "App root was replaced or redirected" }
        require(within(physical(path), rootPhysical)) { "Physical path is outside app root" }
        var cursor = path
        while (cursor != root) {
            val parent = checkNotNull(cursor.parent)
            require(physical(cursor) == Path(physical(parent), cursor.name)) { "Links below app roots are not supported" }
            cursor = parent
        }
    }

    private companion object {
        fun text(path: Path): String = path.toString().replace('\\', '/').trimEnd('/')

        fun within(path: Path, root: Path): Boolean {
            val candidate = text(path)
            val base = text(root)
            return candidate == base || candidate.startsWith("$base/")
        }

        fun relative(value: String, allowEmpty: Boolean = false) {
            if (allowEmpty && value.isEmpty()) return
            require(value.isNotEmpty() && !Path(value).isAbsolute && '\\' !in value && '\u0000' !in value && ':' !in value) {
                "Expected a slash-separated relative file name"
            }
            require(value.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid relative path segment" }
        }

        fun absolute(value: String): Path {
            require(value.isNotBlank() && '\u0000' !in value) { "Expected an absolute directory or file path" }
            val normalized = value.replace('\\', '/')
            require(normalized.split('/').none { it == "." || it == ".." }) { "Dot path segments are not permitted" }
            val path = Path(value)
            require(path.isAbsolute) { "Expected an absolute path" }
            // POSIX selected directories may contain colons. Windows drive/UNC paths must not
            // introduce alternate data streams; relative download names stay portable everywhere.
            if (!normalized.startsWith('/') || normalized.startsWith("//")) {
                require(normalized.drop(if (normalized.length >= 2 && normalized[1] == ':') 2 else 0).none { it == ':' }) {
                    "Unexpected colon in Windows absolute path"
                }
            }
            return path
        }

        /** Resolve existing ancestors without creating missing roots; catch dangling link entries. */
        fun physical(path: Path): Path {
            if (SystemFileSystem.metadataOrNull(path) != null) return SystemFileSystem.resolve(path)
            val parent = path.parent ?: throw IOException("No existing filesystem ancestor")
            if (SystemFileSystem.metadataOrNull(parent)?.isDirectory == true) {
                require(SystemFileSystem.list(parent).none { it.name == path.name }) { "Unresolvable filesystem entry" }
            }
            return Path(physical(parent), path.name)
        }
    }
}
