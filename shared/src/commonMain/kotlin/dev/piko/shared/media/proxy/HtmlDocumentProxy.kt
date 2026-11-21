package dev.piko.shared.media.proxy

import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.player.BasicSubtitleTextTranscoder
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 本地回环代理返回的文档资源（字节与 MIME 类型）。
 */
class DocumentProxyResource(
    val bytes: ByteArray,
    val contentType: String,
)

/**
 * 文档代理资源解析器：接收已通过严格目录穿越校验的相对路径分段列表，返回资源字节或 null (404)。
 */
fun interface DocumentProxyResolver {
    suspend fun resolve(segments: List<String>): DocumentProxyResource?
}

internal class DocumentProxySession(
    val entryFileName: String,
    val resolver: DocumentProxyResolver,
    private val onActivity: (() -> Unit)? = null,
) : AutoCloseable {
    @Volatile
    var isClosed: Boolean = false
        private set

    fun notifyActivity() {
        onActivity?.invoke()
    }

    override fun close() {
        isClosed = true
    }
}

/**
 * 一次已登记的本机回环 HTML 文档代理会话。关闭后对应高熵令牌 URL 立即返回 404。
 */
class DocumentProxyHandle internal constructor(
    val url: String,
    /** 允许 WebView 在同一文档会话内导航与加载子资源的唯一安全前缀（`http://127.0.0.1:<port>/doc/<token>/`）。 */
    val allowedUrlPrefix: String,
    private val onClose: () -> Unit,
) : AutoCloseable {
    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}

/**
 * 严格规范化并校验文档代理的相对请求路径：
 * - 拒绝反斜杠、NUL 字节、畸形百分号编码、Windows 盘符前缀及任何试图越出当前目录根的 `..`；
 * - 合法路径返回规范化后的非空相对路径段列表；非法或穿越路径返回 `null`（代理回 403）。
 */
fun normalizeDocumentRelativePath(rawPath: String): List<String>? {
    if (rawPath.isEmpty()) return null
    val withoutQuery = rawPath.substringBefore('?').substringBefore('#')
    if ('\\' in withoutQuery || '\u0000' in withoutQuery) return null
    val rawSegments = withoutQuery.split('/')
    val normalized = ArrayList<String>(rawSegments.size)
    for (rawSeg in rawSegments) {
        if (rawSeg.isEmpty()) continue
        val decoded = decodePercentStrict(rawSeg) ?: return null
        if (decoded.isEmpty() || '\u0000' in decoded || '/' in decoded || '\\' in decoded) return null
        if (decoded == ".") continue
        if (decoded == "..") {
            if (normalized.isEmpty()) return null
            normalized.removeAt(normalized.lastIndex)
            continue
        }
        // 阻止 Windows 盘符伪装（如 C:）或控制字符
        if (decoded.length >= 2 && decoded[1] == ':' && decoded[0].isLetter()) return null
        if (decoded.any { it.code < 0x20 }) return null
        normalized += decoded
    }
    return normalized.takeIf { it.isNotEmpty() }
}

internal fun sanitizeDocumentEntryName(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
    val safe = base.filter { it.code >= 0x20 && it != '/' && it != '\\' }
    return safe.ifEmpty { "index.html" }
}

internal fun percentEncodePathSegment(segment: String): String {
    val bytes = segment.encodeToByteArray()
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        val c = b.toInt() and 0xFF
        val unreserved = (c in 'a'.code..'z'.code) ||
            (c in 'A'.code..'Z'.code) ||
            (c in '0'.code..'9'.code) ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        if (unreserved) {
            sb.append(c.toChar())
        } else {
            sb.append('%')
            sb.append(HEX_DIGITS[c ushr 4])
            sb.append(HEX_DIGITS[c and 0x0F])
        }
    }
    return sb.toString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun decodePercentStrict(segment: String): String? {
    if ('%' !in segment) return segment
    val out = ByteArray(segment.length * 4)
    var writeIdx = 0
    var i = 0
    while (i < segment.length) {
        val c = segment[i]
        if (c == '%') {
            if (i + 2 >= segment.length) return null
            val hi = segment[i + 1].digitToIntOrNull(16) ?: return null
            val lo = segment[i + 2].digitToIntOrNull(16) ?: return null
            val byteVal = (hi shl 4) or lo
            if (byteVal == 0) return null
            out[writeIdx++] = byteVal.toByte()
            i += 3
        } else {
            val encoded = c.toString().encodeToByteArray()
            if (writeIdx + encoded.size > out.size) return null
            encoded.copyInto(out, writeIdx)
            writeIdx += encoded.size
            i++
        }
    }
    val sliced = out.copyOf(writeIdx)
    if (!BasicSubtitleTextTranscoder.isValidUtf8(sliced)) return null
    return sliced.decodeToString()
}

/**
 * 按文件名扩展名与内容推断响应 `Content-Type`。
 * HTML 文件若本身不是有效 UTF-8（如带 `<meta charset="gbk">` 的旧归档），不强行附加 `charset=utf-8`，交由浏览器按文档声明解析。
 */
fun documentMimeTypeFor(fileName: String, bytes: ByteArray? = null): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> {
            val utf8 = bytes == null || BasicSubtitleTextTranscoder.hasUtf8Bom(bytes) || BasicSubtitleTextTranscoder.isValidUtf8(bytes)
            if (utf8) "text/html; charset=utf-8" else "text/html"
        }
        "xhtml" -> "application/xhtml+xml; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "txt", "md", "markdown", "log", "csv" -> "text/plain; charset=utf-8"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}

/**
 * 基于网盘目录树（或本机挂载目录）的相对路径文档资源解析器：
 * - 入口 HTML 文件直接按 [entryFileId] 读取；
 * - 同目录及子目录内的相对引用资源（CSS、图片、JS、字体等）在 [parentFolderId] 范围内逐层查找并按会话级有界 LRU 缓存；
 * - 无有效父目录（如压缩包内或归档条目）时安全退化为仅提供入口文件。
 */
class DriveHtmlDocumentResolver(
    private val entryFileId: String,
    private val entryFileName: String,
    private val parentFolderId: String?,
    private val localPath: String?,
    private val driveRepository: PikoDriveRepository,
    private val mediaRepository: PikoMediaRepository,
    private val maxAssetBytes: Int = MAX_SINGLE_ASSET_BYTES,
    private val maxCacheBytes: Int = MAX_SESSION_CACHE_BYTES,
) : DocumentProxyResolver {
    private val safeEntryName = sanitizeDocumentEntryName(entryFileName)
    private val lock = Mutex()
    private val folderChildrenCache = HashMap<String, List<FileStat>>()
    private val assetCache = LinkedHashMap<String, DocumentProxyResource>()
    private var cachedBytes = 0

    override suspend fun resolve(segments: List<String>): DocumentProxyResource? {
        val cacheKey = segments.joinToString("/")
        lock.withLock {
            assetCache.remove(cacheKey)?.let { hit ->
                assetCache[cacheKey] = hit
                return hit
            }
        }

        val resolved = if (segments.size == 1 && segments[0] == safeEntryName) {
            fetchFileResource(entryFileId, safeEntryName, localPath)
        } else {
            resolveRelativeAsset(segments)
        } ?: return null

        if (resolved.bytes.size <= maxCacheBytes) {
            lock.withLock {
                assetCache.remove(cacheKey)?.let { cachedBytes -= it.bytes.size }
                while (cachedBytes + resolved.bytes.size > maxCacheBytes && assetCache.isNotEmpty()) {
                    val eldestKey = assetCache.keys.first()
                    val removed = assetCache.remove(eldestKey)
                    if (removed != null) cachedBytes -= removed.bytes.size
                }
                assetCache[cacheKey] = resolved
                cachedBytes += resolved.bytes.size
            }
        }
        return resolved
    }

    private suspend fun resolveRelativeAsset(segments: List<String>): DocumentProxyResource? {
        val localEntry = LocalLocation.of(entryFileId)
        if (localEntry != null) {
            val baseParent = localEntry.parent ?: return null
            val targetLocation = LocalLocation(
                mountId = baseParent.mountId,
                segments = baseParent.segments + segments,
                directory = false,
            )
            return fetchFileResource(targetLocation.id, segments.last(), localPath = null)
        }
        val rootFolderId = parentFolderId?.takeIf { isDriveFolderId(it) } ?: return null
        var currentFolderId = rootFolderId
        for (i in 0 until segments.lastIndex) {
            val dirName = segments[i]
            val children = listFolderCached(currentFolderId) ?: return null
            val folder = children.firstOrNull { it.isFolder && !it.trashed && it.name == dirName }
                ?: children.firstOrNull { it.isFolder && !it.trashed && it.name.equals(dirName, ignoreCase = true) }
                ?: return null
            currentFolderId = folder.id
        }
        val targetName = segments.last()
        val children = listFolderCached(currentFolderId) ?: return null
        val file = children.firstOrNull { !it.isFolder && !it.trashed && it.name == targetName }
            ?: children.firstOrNull { !it.isFolder && !it.trashed && it.name.equals(targetName, ignoreCase = true) }
            ?: return null
        return fetchFileResource(file.id, file.name, localPath = null)
    }

    private suspend fun listFolderCached(folderId: String): List<FileStat>? {
        lock.withLock { folderChildrenCache[folderId]?.let { return it } }
        val listed = driveRepository.listAllFiles(folderId).getOrNull() ?: return null
        lock.withLock {
            if (folderChildrenCache.size < MAX_CACHED_FOLDERS) {
                folderChildrenCache[folderId] = listed
            }
        }
        return listed
    }

    private suspend fun fetchFileResource(
        fileId: String,
        fileName: String,
        localPath: String?,
    ): DocumentProxyResource? {
        val loaded = mediaRepository.readDocumentBytes(
            fileId = fileId,
            maxBytes = maxAssetBytes,
            localPath = localPath,
        ).getOrNull() ?: return null
        return DocumentProxyResource(
            bytes = loaded.bytes,
            contentType = documentMimeTypeFor(fileName, loaded.bytes),
        )
    }

    companion object {
        const val MAX_SINGLE_ASSET_BYTES: Int = 16 * 1024 * 1024
        const val MAX_SESSION_CACHE_BYTES: Int = 16 * 1024 * 1024
        private const val MAX_CACHED_FOLDERS: Int = 64
    }
}

/**
 * 桌面端外部浏览器 HTML 会话有界托管表：
 * 保留最近打开的少量会话（默认最多 6 个），每次浏览器拉取资源刷新活跃时刻，
 * 空闲超时自动释放，退出或切换账号时统一立即销毁。
 */
object HtmlDocumentProxySessions {
    private const val TAG = "HtmlDocProxy"
    private const val MAX_SESSIONS = 6
    private const val IDLE_TIMEOUT_MILLIS = 30L * 60L * 1000L

    private class RetainedDoc(
        val fileId: String,
        val handle: DocumentProxyHandle,
        @Volatile var lastActivity: TimeMark = TimeSource.Monotonic.markNow(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = PikoLock()
    private val retained = ArrayDeque<RetainedDoc>()
    private var cleanupJob: Job? = null

    suspend fun openForExternalBrowser(
        fileId: String,
        fileName: String,
        parentFolderId: String?,
        localPath: String?,
        driveRepository: PikoDriveRepository,
        mediaRepository: PikoMediaRepository,
    ): Result<DocumentProxyHandle> = try {
        val resolver = DriveHtmlDocumentResolver(
            entryFileId = fileId,
            entryFileName = fileName,
            parentFolderId = parentFolderId,
            localPath = localPath,
            driveRepository = driveRepository,
            mediaRepository = mediaRepository,
        )
        var entryRef: RetainedDoc? = null
        val handle = mediaRepository.loopbackProxy.registerDocument(
            entryFileName = fileName,
            resolver = resolver,
            onActivity = {
                entryRef?.lastActivity = TimeSource.Monotonic.markNow()
            },
        )
        val newEntry = RetainedDoc(fileId = fileId, handle = handle)
        entryRef = newEntry
        val toClose = ArrayList<DocumentProxyHandle>(2)
        withPikoLock(lock) {
            val iter = retained.iterator()
            while (iter.hasNext()) {
                val existing = iter.next()
                if (existing.fileId == fileId) {
                    iter.remove()
                    toClose += existing.handle
                }
            }
            retained.addLast(newEntry)
            while (retained.size > MAX_SESSIONS) {
                toClose += retained.removeFirst().handle
            }
            scheduleCleanupLocked()
        }
        toClose.forEach { runCatching { it.close() } }
        Result.success(handle)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        PikoLog.w(TAG, "Failed to register desktop HTML proxy session: ${e::class.simpleName}: ${e.message}")
        Result.failure(e)
    }

    private fun scheduleCleanupLocked() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = scope.launch {
            while (true) {
                delay(60_000L)
                val expired = ArrayList<DocumentProxyHandle>()
                var remainingEmpty = false
                withPikoLock(lock) {
                    val iter = retained.iterator()
                    while (iter.hasNext()) {
                        val doc = iter.next()
                        if (doc.lastActivity.elapsedNow().inWholeMilliseconds >= IDLE_TIMEOUT_MILLIS) {
                            iter.remove()
                            expired += doc.handle
                        }
                    }
                    remainingEmpty = retained.isEmpty()
                    if (remainingEmpty) cleanupJob = null
                }
                expired.forEach { runCatching { it.close() } }
                if (remainingEmpty) return@launch
            }
        }
    }

    fun closeAll() {
        val all = withPikoLock(lock) {
            cleanupJob?.cancel()
            cleanupJob = null
            retained.map { it.handle }.also { retained.clear() }
        }
        all.forEach { runCatching { it.close() } }
    }
}
