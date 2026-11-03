package dev.piko.desktop

import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.*
import dev.piko.shared.PikoHome
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import javax.imageio.ImageIO

class DesktopLocalFileAccess(private val scope: CoroutineScope, private val changed: (String) -> Unit, cacheDirectory: File = PikoHome.root.resolve("cache/local-media").toFile()) : LocalFileAccess {
    private val cache = LocalMediaCache(cacheDirectory, scope, changed)
    private val folderCovers = ConcurrentHashMap<String, String>()
    private val pendingCovers = ConcurrentHashMap.newKeySet<String>()
    private val generation = AtomicLong(0)
    private val coverLifecycle = Any()
    private companion object { const val NO_COVER = "\u0000" }

    override fun cancelPending() {
        synchronized(coverLifecycle) {
            generation.incrementAndGet()
            pendingCovers.clear()
            folderCovers.clear()
        }
        cache.cancelPending()
    }
    private fun path(mount: LocalMount, location: LocalLocation): Path {
        require(location.mountId == mount.mountId)
        val root = Path.of(mount.localRootUriOrPath).toRealPath()
        val child = location.segments.fold(root) { path, part -> path.resolve(part) }.toRealPath()
        require(child.startsWith(root)) { "本机文件夹不可访问" }
        return child
    }
    private fun stat(mount: LocalMount, location: LocalLocation, path: Path, attributes: BasicFileAttributes): FileStat {
        val directory = attributes.isDirectory
        val parent = location.parent?.id ?: mount.parentFolderId
        val base = FileStat(
            id = location.copy(directory = directory).id, parentId = parent, name = path.fileName.toString(),
            kind = if (directory) FileKind.FOLDER else FileKind.FILE,
            size = if (directory) "0" else attributes.size().toString(),
            modifiedTime = attributes.lastModifiedTime().toInstant().toString(),
            createdTime = attributes.creationTime().toInstant().toString(),
            phase = TaskPhase.COMPLETE, fileExtension = path.fileName.toString().substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" },
            params = mapOf("local_path" to path.toString()),
        )
        return when {
            base.isPreviewableImage() -> base.copy(thumbnailLink = path.toString())
            directory -> base.copy(thumbnailLink = folderCover(mount, location))
            else -> base
        }
    }

    override fun folderCover(mount: LocalMount, location: LocalLocation): String {
        val coverKey = "${mount.mountId}|${location.id}"
        synchronized(coverLifecycle) {
            folderCovers[coverKey]?.let { return if (it == NO_COVER) "" else it }
            val gen = generation.get()
            if (pendingCovers.add(coverKey)) scope.launch(Dispatchers.IO) {
                try {
                    if (generation.get() != gen) return@launch
                    val cover = peekFolderCover(mount, location, path(mount, location), gen)
                    var notifyParent: String? = null
                    synchronized(coverLifecycle) {
                        if (generation.get() == gen) {
                            if (folderCovers.size >= 2048) folderCovers.clear()
                            folderCovers[coverKey] = cover.ifEmpty { NO_COVER }
                            if (cover.isNotEmpty()) notifyParent = location.parent?.id ?: mount.parentFolderId
                        }
                    }
                    notifyParent?.let(changed)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    synchronized(coverLifecycle) {
                        if (generation.get() == gen) {
                            if (folderCovers.size >= 2048) folderCovers.clear()
                            folderCovers[coverKey] = NO_COVER
                        }
                    }
                } finally {
                    synchronized(coverLifecycle) { if (generation.get() == gen) pendingCovers.remove(coverKey) }
                }
            }
        }
        return ""
    }

    /**
     * 快速窥探本地文件夹内首个可展示图片或视频封面（最多深入一层子目录），供海报墙叠页文件夹卡片使用；
     * 仅从 IO 协程调用；视频经后台限流车道抽帧，完整结果由调用方写入内存并通知父目录。
     */
    private suspend fun peekFolderCover(mount: LocalMount, dirLocation: LocalLocation, dirPath: Path, gen: Long, depth: Int = 0): String {
        if (generation.get() != gen) return ""
        val subdirs = mutableListOf<Pair<LocalLocation, Path>>()
        var firstVideo: Pair<FileStat, LocalLocation>? = null
        Files.newDirectoryStream(dirPath).use { stream ->
            var scanned = 0
            for (child in stream) {
                if (++scanned > 48) break
                val attrs = runCatching { Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }.getOrNull() ?: continue
                if (attrs.isSymbolicLink) continue
                val name = child.fileName?.toString() ?: continue
                if (attrs.isRegularFile) {
                    if (name.isPreviewableImage()) return child.toString()
                    if (firstVideo == null && name.isPlayableVideo()) {
                        val childLoc = runCatching { dirLocation.child(name, directory = false) }.getOrNull() ?: continue
                        val videoStat = FileStat(
                            id = childLoc.id,
                            parentId = dirLocation.id,
                            name = name,
                            kind = FileKind.FILE,
                            size = attrs.size().toString(),
                            modifiedTime = attrs.lastModifiedTime().toInstant().toString(),
                            createdTime = attrs.creationTime().toInstant().toString(),
                            phase = TaskPhase.COMPLETE,
                            fileExtension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" },
                            params = mapOf("local_path" to child.toString()),
                        )
                        firstVideo = videoStat to childLoc
                    }
                } else if (attrs.isDirectory && depth < 1 && subdirs.size < 6) {
                    val subLoc = runCatching { dirLocation.child(name, directory = true) }.getOrNull() ?: continue
                    subdirs += subLoc to child
                }
            }
        }
        if (firstVideo != null) {
            val (videoStat, videoLocation) = firstVideo
            if (generation.get() != gen) return ""
            val enriched = cache.probe(videoStat) { output -> metadata(mount, videoLocation, videoStat, output, gen) }
            if (enriched.thumbnailLink.isNotBlank()) return enriched.thumbnailLink
        }
        if (depth < 1) {
            for ((subLoc, subPath) in subdirs) {
                val subCover = runCatching { peekFolderCover(mount, subLoc, subPath, gen, depth = depth + 1) }.getOrDefault("")
                if (subCover.isNotBlank()) return subCover
            }
        }
        return ""
    }
    override suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat> = withContext(Dispatchers.IO) {
        try {
            Files.newDirectoryStream(path(mount, location)).use { entries ->
                entries.mapNotNull { child ->
                    // 链接既不作为目录遍历，也不暴露指向挂载范围外的文件。
                    val attributes = runCatching { Files.readAttributes(child, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }.getOrNull() ?: return@mapNotNull null
                    if (attributes.isSymbolicLink || (!attributes.isDirectory && !attributes.isRegularFile)) return@mapNotNull null
                    val target = runCatching { location.child(child.fileName.toString(), attributes.isDirectory) }.getOrNull() ?: return@mapNotNull null
                    val file = stat(mount, target, child, attributes)
                    cache.enrich(file) { output -> metadata(mount, target, file, output) }
                }
            }
        } catch (_: Exception) { throw IllegalStateException("本机文件夹不可访问") }
    }
    override suspend fun playbackPath(mount: LocalMount, location: LocalLocation): String = withContext(Dispatchers.IO) {
        try { path(mount, location).toString() } catch (_: Exception) { throw IllegalStateException("本机文件夹不可访问") }
    }
    override suspend fun probe(mount: LocalMount, location: LocalLocation): FileStat? = withContext(Dispatchers.IO) {
        try {
            val target = path(mount, location)
            val file = stat(mount, location, target, Files.readAttributes(target, BasicFileAttributes::class.java))
            cache.probe(file) { output -> metadata(mount, location, file, output) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { throw IllegalStateException("本机文件夹不可访问") }
    }
    private suspend fun metadata(mount: LocalMount, location: LocalLocation, file: FileStat, output: File, gen: Long = generation.get()): LocalMediaMetadata? {
        val context = currentCoroutineContext()
        if (!context.isActive || generation.get() != gen) throw CancellationException("本地媒体探测已取消")
        val verified = path(mount, location)
        if (file.isPlayableVideo()) return FileChannel.open(verified, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            dev.piko.desktop.media.probeLocalVideo(channel, output, cancelled = { !context.isActive || generation.get() != gen })
        }
        if (!file.isPreviewableImage()) return null
        Files.newInputStream(verified, NOFOLLOW_LINKS).use { source ->
            ImageIO.createImageInputStream(source).use { input ->
                val readers = ImageIO.getImageReaders(input)
                if (!readers.hasNext()) return null
                val reader = readers.next()
                try {
                    reader.input = input
                    val swaps = Files.newInputStream(path(mount, location), NOFOLLOW_LINKS).buffered().use(::localImageSwapsAxes)
                    return if (swaps) LocalMediaMetadata(reader.getHeight(0), reader.getWidth(0)) else LocalMediaMetadata(reader.getWidth(0), reader.getHeight(0))
                } finally { reader.dispose() }
            }
        }
    }
}
