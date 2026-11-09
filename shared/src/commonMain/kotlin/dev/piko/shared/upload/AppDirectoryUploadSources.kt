package dev.piko.shared.upload

import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Platform operations missing from kotlinx-io's filesystem: modification time and random access. */
interface UploadFileAccess {
    fun modifiedAtMs(path: String): Long
    /** Return an independently owned source already positioned at [offset], without scanning bytes. */
    fun openAt(path: String, offset: Long): RawSource
}

/**
 * Uploads from an app-private directory. Durable identifiers contain relative paths, never an iOS
 * container's absolute path. Resolve against a freshly created instance after each app launch.
 *
 * No import/copy or directory creation occurs here. External document-provider URLs must first be
 * imported by the host while it holds their security-scoped access. Releasing a task never deletes
 * its source. Directory links are skipped; opening links below the root is rejected. Checks and I/O
 * are not atomic (kotlinx-io has no openat/no-follow traversal); this is an app-owned, trusted root.
 */
class AppDirectoryUploadSources(
    managedDirectory: String,
    private val fileAccess: UploadFileAccess,
) : PikoUploadSources {
    private val root = Path(managedDirectory)
    private val rootPhysical: Path

    init {
        require(root.isAbsolute && root.parent != null && '\u0000' !in managedDirectory) { "An absolute app directory is required" }
        require(managedDirectory.replace('\\', '/').split('/').none { it == "." || it == ".." }) { "Dot path segments are not permitted" }
        require(SystemFileSystem.metadataOrNull(root)?.isDirectory != false) { "App root must be a directory" }
        rootPhysical = physical(root)
    }

    /** The empty relative name identifies the managed root for [listFolder], not for file reads. */
    fun uriFor(relativePath: String): String {
        relative(relativePath, allowEmpty = true)
        guard(if (relativePath.isEmpty()) root else Path(root, relativePath))
        return PREFIX + relativePath
    }

    override fun describe(uri: String): UploadSourceInfo? {
        val path = resolve(uri)
        return try {
            val metadata = SystemFileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile } ?: return null
            UploadSourceInfo(path.name, metadata.size, fileAccess.modifiedAtMs(path.toString()))
        } catch (_: IOException) {
            null
        }
    }

    override fun readAt(uri: String, offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "Upload range must not be negative" }
        val path = resolve(uri)
        val size = requireFileSize(path)
        if (offset > size || length.toLong() > size - offset) throw IOException("Upload range exceeds source length")
        return fileAccess.openAt(path.toString(), offset).buffered().use { it.readByteArray(length) }
    }

    override fun open(uri: String, offset: Long): RawSource {
        require(offset >= 0) { "Upload offset must not be negative" }
        val path = resolve(uri)
        if (offset > requireFileSize(path)) throw IOException("Upload offset exceeds source length")
        return fileAccess.openAt(path.toString(), offset)
    }

    override fun listFolder(uri: String): UploadFolder? {
        val directory = resolve(uri, allowRoot = true)
        if (SystemFileSystem.metadataOrNull(directory)?.isDirectory != true) return null
        return try {
            val files = mutableListOf<UploadTreeEntry>()
            // Iterative traversal: nested folders do not consume the call stack. Links are not followed.
            val pending = ArrayDeque<Pair<Path, String>>()
            pending.addLast(directory to "")
            while (pending.isNotEmpty()) {
                val (parent, relativeDir) = pending.removeFirst()
                guard(parent)
                for (child in SystemFileSystem.list(parent).sortedBy { it.name }) {
                    // Exclude unrepresentable names, dangling entries and redirected paths.
                    try {
                        relative(child.name)
                        guard(child)
                    } catch (_: IllegalArgumentException) {
                        continue
                    }
                    val metadata = SystemFileSystem.metadataOrNull(child) ?: continue
                    when {
                        metadata.isDirectory -> pending.addLast(child to join(relativeDir, child.name))
                        metadata.isRegularFile -> {
                            val relativePath = relativeToRoot(child)
                            files += UploadTreeEntry(PREFIX + relativePath, relativeDir)
                        }
                    }
                }
            }
            UploadFolder(directory.name, files)
        } catch (_: IOException) {
            // Do not return a silently truncated selection after a directory cannot be enumerated.
            null
        }
    }

    private fun resolve(uri: String, allowRoot: Boolean = false): Path {
        require(uri.startsWith(PREFIX)) { "Unknown app upload identifier" }
        val relativePath = uri.removePrefix(PREFIX)
        relative(relativePath, allowEmpty = allowRoot)
        return (if (relativePath.isEmpty()) root else Path(root, relativePath)).also(::guard)
    }

    private fun requireFileSize(path: Path): Long = SystemFileSystem.metadataOrNull(path)
        ?.takeIf { it.isRegularFile }?.size ?: throw IOException("Upload source is missing or not a regular file")

    private fun guard(path: Path) {
        require(physical(root) == rootPhysical) { "App upload root was redirected" }
        require(within(path, root) && within(physical(path), rootPhysical)) { "Upload path is outside app root" }
        var cursor = path
        while (cursor != root) {
            val parent = checkNotNull(cursor.parent)
            require(physical(cursor) == Path(physical(parent), cursor.name)) { "Links below app upload roots are not supported" }
            cursor = parent
        }
    }

    private fun relativeToRoot(path: Path): String = text(path).removePrefix(text(root) + "/")

    private companion object {
        const val PREFIX = "piko-app-upload:"
        fun text(path: Path): String = path.toString().replace('\\', '/').trimEnd('/')
        fun within(path: Path, root: Path): Boolean = text(path).let { it == text(root) || it.startsWith(text(root) + "/") }
        fun join(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"
        fun relative(value: String, allowEmpty: Boolean = false) {
            if (allowEmpty && value.isEmpty()) return
            require(value.isNotEmpty() && !Path(value).isAbsolute && '\\' !in value && '\u0000' !in value && ':' !in value) {
                "Expected a slash-separated app-relative upload name"
            }
            require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid upload path segment" }
        }
        fun physical(path: Path): Path {
            if (SystemFileSystem.metadataOrNull(path) != null) return SystemFileSystem.resolve(path)
            val parent = path.parent ?: throw IOException("No existing upload directory ancestor")
            if (SystemFileSystem.metadataOrNull(parent)?.isDirectory == true) {
                require(SystemFileSystem.list(parent).none { it.name == path.name }) { "Unresolvable upload entry" }
            }
            return Path(physical(parent), path.name)
        }
    }
}
