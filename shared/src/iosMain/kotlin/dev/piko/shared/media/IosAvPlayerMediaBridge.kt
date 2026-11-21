package dev.piko.shared.media

import dev.piko.shared.log.PikoLog
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.isClosed
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.network.sockets.port
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readLineStrict
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * iOS `AVPlayer` / `AVURLAsset` 回环适配桥（兼容 iOS 15.0 至 iOS 27+）：
 *
 * 1. `PikoMediaProxy` 面向 libmpv/FFmpeg 统一返回 `Content-Type: application/octet-stream`。
 *    Apple `AVFoundation`（尤其 iOS 15–18）在遇到独立 `.ts` 或 `application/octet-stream` 时会报
 *    `AVFoundationErrorDomain -11828` / `CoreMediaErrorDomain -12847` 而拒绝播放。
 * 2. 对 MPEG-TS 流（信息流 180 秒有界切片与 720P/1080P 转码 `stream.ts`），本桥动态生成内存
 *    HLS VOD 播放列表（`playlist.m3u8`，`Content-Type: application/vnd.apple.mpegurl`）并将分片流
 *    `segment.ts` 透传回上游回环代理同时改写 `Content-Type: video/mp2t`；
 * 3. 对其他回环代理媒体（`.mp4`/`.mov`/`.m4v`/`.m4a`/`.mp3` 等），原样转发 `GET`/`HEAD` 与 `Range`
 *    并注入精确 MIME 类型；客户端断开时立即关闭上游连接，确保 `PikoMediaProxy` 及时撤销 `ReadTicket`。
 */
class IosAvPlayerMediaBridge(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("IosAvPlayerMediaBridge"))
    private val startLock = Mutex()
    private var selector: SelectorManager? = null

    @Volatile
    private var server: ServerSocket? = null

    private val entriesLock = Mutex()
    private val entries = HashMap<String, BridgeEntry>()

    private sealed interface BridgeEntry {
        data class HlsLoopbackTs(
            val upstream: LoopbackTarget,
            val durationSeconds: Long,
        ) : BridgeEntry

        data class HlsDirectTs(
            val segmentUrl: String,
            val durationSeconds: Long,
        ) : BridgeEntry

        data class HlsLocalTs(
            val filePath: String,
            val durationSeconds: Long,
        ) : BridgeEntry

        data class MimeProxy(
            val upstream: LoopbackTarget,
            val contentType: String,
        ) : BridgeEntry
    }

    /** 包装任意网络或回环代理地址供 `AVPlayer` 使用；释放播放器或换源时必须调用 `close()` 注销令牌。 */
    suspend fun wrap(
        url: String,
        durationSeconds: Long = DEFAULT_CLIP_DURATION_SECONDS,
        forceHlsTs: Boolean = false,
    ): WrappedAvPlayerUrl {
        val safeDuration = durationSeconds.coerceAtLeast(1L)
        val loopback = parseLoopbackUrl(url)
        val isTs = forceHlsTs || isMpegTsUrl(url)
        if (isTs) {
            val port = ensureStarted()
            val token = newToken()
            val entry = if (loopback != null) {
                BridgeEntry.HlsLoopbackTs(upstream = loopback, durationSeconds = safeDuration)
            } else {
                BridgeEntry.HlsDirectTs(segmentUrl = url, durationSeconds = safeDuration)
            }
            entriesLock.withLock { entries[token] = entry }
            return WrappedAvPlayerUrl(
                playbackUrl = "http://$LOOPBACK:$port/$token/playlist.m3u8",
                isHls = true,
                onClose = { unregister(token, entry) },
            )
        }
        if (loopback != null) {
            val mime = mimeTypeForMediaUrl(url)
            if (mime != DEFAULT_MIME_TYPE) {
                val port = ensureStarted()
                val token = newToken()
                val ext = extensionOfMediaUrl(url) ?: "mp4"
                val entry = BridgeEntry.MimeProxy(upstream = loopback, contentType = mime)
                entriesLock.withLock { entries[token] = entry }
                return WrappedAvPlayerUrl(
                    playbackUrl = "http://$LOOPBACK:$port/$token/media.$ext",
                    onClose = { unregister(token, entry) },
                )
            }
        }
        return WrappedAvPlayerUrl(playbackUrl = url, onClose = {})
    }

    /** 包装本机 `.ts` 文件为本地 HLS VOD 播放列表；非 `.ts` 本机文件无需桥接，返回 `null` 由调用方直接 `fileURLWithPath`。 */
    suspend fun wrapLocalTsFile(
        filePath: String,
        durationSeconds: Long = DEFAULT_CLIP_DURATION_SECONDS,
    ): WrappedAvPlayerUrl? {
        if (!isMpegTsUrl(filePath)) return null
        val port = ensureStarted()
        val token = newToken()
        val entry = BridgeEntry.HlsLocalTs(filePath = filePath, durationSeconds = durationSeconds.coerceAtLeast(1L))
        entriesLock.withLock { entries[token] = entry }
        return WrappedAvPlayerUrl(
            playbackUrl = "http://$LOOPBACK:$port/$token/playlist.m3u8",
            isHls = true,
            onClose = { unregister(token, entry) },
        )
    }

    private fun unregister(token: String, entry: BridgeEntry) {
        scope.launch {
            entriesLock.withLock {
                if (entries[token] === entry) entries.remove(token)
            }
        }
    }

    private suspend fun ensureStarted(): Int = startLock.withLock {
        server?.takeUnless { it.isClosed }?.let { return@withLock it.port }
        val manager = selector ?: SelectorManager(dispatcher).also { selector = it }
        val bound = aSocket(manager).tcp().bind(LOOPBACK, 0)
        server = bound
        PikoLog.i(TAG, "iOS AVPlayer media bridge listening on $LOOPBACK:${bound.port}")
        scope.launch { acceptLoop(bound, manager) }
        bound.port
    }

    private suspend fun acceptLoop(bound: ServerSocket, manager: SelectorManager) {
        while (true) {
            val socket = try {
                bound.accept()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!bound.isClosed) {
                    PikoLog.w(TAG, "iOS media bridge accept failed on port ${bound.port}", e)
                }
                runCatching { bound.close() }
                withContext(NonCancellable) {
                    startLock.withLock { if (server === bound) server = null }
                }
                return
            }
            scope.launch { serveConnection(socket, bound.port, manager) }
        }
    }

    private suspend fun serveConnection(socket: Socket, listenPort: Int, manager: SelectorManager) {
        try {
            coroutineScope {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = false)
                val request = readHttpRequest(input) ?: return@coroutineScope
                val response = launch { respond(request, listenPort, manager, output) }
                val watcher = launch {
                    try {
                        input.discard()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // 客户端主动断开或 RST
                    }
                    response.cancel()
                }
                response.join()
                watcher.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 连接中途断开属正常播放取消/拖动行为
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun respond(
        request: BridgeHttpRequest,
        listenPort: Int,
        manager: SelectorManager,
        output: ByteWriteChannel,
    ) {
        val isHead = request.method == "HEAD"
        if (!isHead && request.method != "GET") {
            output.writeSimpleResponse(405, "Method Not Allowed", listOf("Allow" to "GET, HEAD"))
            return
        }
        val cleanTarget = request.target.substringBefore('?').removePrefix("/")
        val token = cleanTarget.substringBefore('/')
        val subPath = cleanTarget.substringAfter('/', missingDelimiterValue = "")
        val entry = entriesLock.withLock { entries[token] }
        if (entry == null) {
            output.writeSimpleResponse(404, "Not Found")
            return
        }
        when (entry) {
            is BridgeEntry.HlsLoopbackTs -> {
                if (subPath == "playlist.m3u8") {
                    val segmentUrl = "http://$LOOPBACK:$listenPort/$token/segment.ts"
                    val body = buildVodPlaylist(segmentUrl, entry.durationSeconds).encodeToByteArray()
                    output.writePlaylistResponse(body, isHead)
                } else if (subPath == "segment.ts") {
                    proxyUpstream(manager, entry.upstream, request, VIDEO_MP2T_MIME, output)
                } else {
                    output.writeSimpleResponse(404, "Not Found")
                }
            }

            is BridgeEntry.HlsDirectTs -> {
                if (subPath == "playlist.m3u8") {
                    val body = buildVodPlaylist(entry.segmentUrl, entry.durationSeconds).encodeToByteArray()
                    output.writePlaylistResponse(body, isHead)
                } else {
                    output.writeSimpleResponse(404, "Not Found")
                }
            }

            is BridgeEntry.HlsLocalTs -> {
                if (subPath == "playlist.m3u8") {
                    val segmentUrl = "http://$LOOPBACK:$listenPort/$token/segment.ts"
                    val body = buildVodPlaylist(segmentUrl, entry.durationSeconds).encodeToByteArray()
                    output.writePlaylistResponse(body, isHead)
                } else if (subPath == "segment.ts") {
                    serveLocalFile(entry.filePath, request, VIDEO_MP2T_MIME, output)
                } else {
                    output.writeSimpleResponse(404, "Not Found")
                }
            }

            is BridgeEntry.MimeProxy -> {
                proxyUpstream(manager, entry.upstream, request, entry.contentType, output)
            }
        }
    }

    private suspend fun proxyUpstream(
        manager: SelectorManager,
        upstream: LoopbackTarget,
        request: BridgeHttpRequest,
        contentType: String,
        clientOut: ByteWriteChannel,
    ) {
        val upstreamSocket = try {
            aSocket(manager).tcp().connect(LOOPBACK, upstream.port)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PikoLog.w(TAG, "iOS media bridge failed to connect upstream loopback port ${upstream.port}", e)
            clientOut.writeSimpleResponse(502, "Bad Gateway")
            return
        }
        try {
            val upIn = upstreamSocket.openReadChannel()
            val upOut = upstreamSocket.openWriteChannel(autoFlush = false)
            val reqHead = buildString {
                append(request.method).append(' ').append(upstream.path).append(" HTTP/1.1\r\n")
                append("Host: ").append(LOOPBACK).append(':').append(upstream.port).append("\r\n")
                request.headers["range"]?.let { append("Range: ").append(it).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            upOut.writeFully(reqHead.encodeToByteArray())
            upOut.flush()

            val statusLine = upIn.readLineStrict(MAX_LINE_BYTES)
            if (statusLine == null) {
                clientOut.writeSimpleResponse(502, "Bad Gateway")
                return
            }
            val upstreamHeaders = ArrayList<Pair<String, String>>(8)
            while (true) {
                val line = upIn.readLineStrict(MAX_LINE_BYTES) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    upstreamHeaders += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
                }
            }
            val rewritten = rewriteResponseHeaders(upstreamHeaders, contentType)
            val respHead = buildString {
                append(statusLine).append("\r\n")
                rewritten.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            clientOut.writeFully(respHead.encodeToByteArray())
            clientOut.flush()

            if (request.method != "HEAD") {
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                while (true) {
                    val read = upIn.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    clientOut.writeFully(buffer, 0, read)
                    clientOut.flush()
                }
            }
            clientOut.flushAndClose()
        } finally {
            runCatching { upstreamSocket.close() }
        }
    }

    private suspend fun serveLocalFile(
        filePath: String,
        request: BridgeHttpRequest,
        contentType: String,
        clientOut: ByteWriteChannel,
    ) {
        val path = Path(filePath)
        val size = SystemFileSystem.metadataOrNull(path)?.size
        if (size == null || size < 0L) {
            clientOut.writeSimpleResponse(404, "Not Found")
            return
        }
        val range = parseBridgeByteRange(request.headers["range"], size)
        if (range == null) {
            clientOut.writeSimpleResponse(
                416,
                "Range Not Satisfiable",
                listOf("Content-Range" to "bytes */$size"),
            )
            return
        }
        val headers = buildList {
            add("Content-Type" to contentType)
            add("Accept-Ranges" to "bytes")
            add("Content-Length" to range.length.toString())
            if (range.isPartial) {
                add("Content-Range" to "bytes ${range.start}-${range.endExclusive - 1}/$size")
            }
        }
        val statusCode = if (range.isPartial) 206 else 200
        val reason = if (range.isPartial) "Partial Content" else "OK"
        val head = buildString {
            append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        clientOut.writeFully(head.encodeToByteArray())
        clientOut.flush()
        if (request.method == "HEAD" || range.length == 0L) {
            clientOut.flushAndClose()
            return
        }
        SystemFileSystem.source(path).use { raw ->
            val buf = kotlinx.io.Buffer()
            var skipped = 0L
            while (skipped < range.start) {
                val want = minOf(STREAM_BUFFER_BYTES.toLong(), range.start - skipped)
                val n = raw.readAtMostTo(buf, want)
                if (n <= 0L) break
                buf.clear()
                skipped += n
            }
            var remaining = range.length
            while (remaining > 0L) {
                val want = minOf(STREAM_BUFFER_BYTES.toLong(), remaining)
                val n = raw.readAtMostTo(buf, want)
                if (n <= 0L) break
                val count = n.toInt()
                val bytes = buf.readByteArray(count)
                clientOut.writeFully(bytes)
                clientOut.flush()
                remaining -= count
            }
        }
        clientOut.flushAndClose()
    }

    override fun close() {
        scope.cancel()
        runCatching { server?.close() }
        server = null
        runCatching { selector?.close() }
        selector = null
    }

    companion object {
        private const val TAG = "IosMediaBridge"
        private const val LOOPBACK = "127.0.0.1"
        private const val DEFAULT_CLIP_DURATION_SECONDS = 180L
        private const val STREAM_BUFFER_BYTES = 32 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024L
        private const val MAX_HEADER_LINES = 100
        const val HLS_MIME_TYPE = "application/vnd.apple.mpegurl"
        const val VIDEO_MP2T_MIME = "video/mp2t"
        const val DEFAULT_MIME_TYPE = "application/octet-stream"

        /** 进程级单例：所有 iOS `AVPlayer` 与 `IosPreviewBackend` 共用同一回环端口。 */
        val Shared: IosAvPlayerMediaBridge by lazy { IosAvPlayerMediaBridge() }
    }
}

class WrappedAvPlayerUrl internal constructor(
    val playbackUrl: String,
    val isHls: Boolean = false,
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

internal data class LoopbackTarget(val port: Int, val path: String)

internal data class BridgeHttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
)

internal data class BridgeByteRange(
    val start: Long,
    val endExclusive: Long,
    val isPartial: Boolean,
) {
    val length: Long get() = endExclusive - start
}

internal fun parseLoopbackUrl(url: String): LoopbackTarget? {
    val prefix = "http://127.0.0.1:"
    if (!url.startsWith(prefix, ignoreCase = true)) return null
    val rest = url.substring(prefix.length)
    val slash = rest.indexOf('/')
    val portStr = if (slash >= 0) rest.substring(0, slash) else rest
    val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val path = if (slash >= 0) rest.substring(slash) else "/"
    return LoopbackTarget(port = port, path = path)
}

internal fun extensionOfMediaUrl(url: String): String? {
    val withoutFragment = url.substringBefore('#').substringBefore('?')
    val lastSegment = withoutFragment.substringAfterLast('/')
    val dot = lastSegment.lastIndexOf('.')
    if (dot < 0 || dot == lastSegment.lastIndex) return null
    return lastSegment.substring(dot + 1).lowercase().takeIf { it.length in 1..6 && it.all(Char::isLetterOrDigit) }
}

internal fun isMpegTsUrl(url: String): Boolean {
    val ext = extensionOfMediaUrl(url) ?: return false
    return ext == "ts" || ext == "m2ts" || ext == "mts"
}

internal fun mimeTypeForMediaUrl(url: String): String = when (extensionOfMediaUrl(url)) {
    "ts", "m2ts", "mts" -> IosAvPlayerMediaBridge.VIDEO_MP2T_MIME
    "m3u8" -> IosAvPlayerMediaBridge.HLS_MIME_TYPE
    "mp4" -> "video/mp4"
    "m4v" -> "video/x-m4v"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    "ogg" -> "audio/ogg"
    else -> IosAvPlayerMediaBridge.DEFAULT_MIME_TYPE
}

internal fun buildVodPlaylist(segmentUrl: String, durationSeconds: Long): String {
    val target = durationSeconds.coerceAtLeast(1L)
    return buildString {
        append("#EXTM3U\n")
        append("#EXT-X-VERSION:3\n")
        append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        append("#EXT-X-MEDIA-SEQUENCE:0\n")
        append("#EXT-X-PLAYLIST-TYPE:VOD\n")
        append("#EXTINF:").append(target).append(".0,\n")
        append(segmentUrl).append('\n')
        append("#EXT-X-ENDLIST\n")
    }
}

internal fun rewriteResponseHeaders(
    headers: List<Pair<String, String>>,
    contentType: String,
): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>(headers.size + 1)
    var replacedContentType = false
    for ((name, value) in headers) {
        when {
            name.equals("connection", ignoreCase = true) -> continue
            name.equals("content-type", ignoreCase = true) -> {
                if (!replacedContentType) {
                    out += "Content-Type" to contentType
                    replacedContentType = true
                }
            }
            else -> out += name to value
        }
    }
    if (!replacedContentType) {
        out += "Content-Type" to contentType
    }
    return out
}

internal fun parseBridgeByteRange(header: String?, size: Long): BridgeByteRange? {
    val whole = BridgeByteRange(0L, size, isPartial = false)
    val spec = header?.trim() ?: return whole
    if (!spec.startsWith("bytes=", ignoreCase = true)) return whole
    val value = spec.substring("bytes=".length).trim()
    if (',' in value) return whole
    val dash = value.indexOf('-')
    if (dash < 0) return whole
    val first = value.substring(0, dash).trim()
    val last = value.substring(dash + 1).trim()
    if (first.isEmpty()) {
        val suffix = last.toLongOrNull() ?: return whole
        if (suffix <= 0L || size == 0L) return null
        return BridgeByteRange((size - suffix).coerceAtLeast(0L), size, isPartial = true)
    }
    val start = first.toLongOrNull()?.takeIf { it >= 0L } ?: return whole
    val end = if (last.isEmpty()) Long.MAX_VALUE else last.toLongOrNull() ?: return whole
    if (end < start) return whole
    if (start >= size) return null
    return BridgeByteRange(start, minOf(end, size - 1L) + 1L, isPartial = true)
}

private suspend fun readHttpRequest(input: ByteReadChannel): BridgeHttpRequest? {
    val requestLine = input.readLineStrict(8 * 1024L) ?: return null
    val parts = requestLine.split(' ')
    if (parts.size < 3) return null
    val headers = HashMap<String, String>()
    repeat(100) {
        val line = input.readLineStrict(8 * 1024L) ?: return null
        if (line.isEmpty()) return BridgeHttpRequest(parts[0].uppercase(), parts[1], headers)
        val colon = line.indexOf(':')
        if (colon > 0) {
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }
    return null
}

private suspend fun ByteWriteChannel.writeSimpleResponse(
    status: Int,
    reason: String,
    extraHeaders: List<Pair<String, String>> = emptyList(),
) {
    val head = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        extraHeaders.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
        append("Content-Length: 0\r\n")
        append("Connection: close\r\n\r\n")
    }
    writeFully(head.encodeToByteArray())
    flushAndClose()
}

private suspend fun ByteWriteChannel.writePlaylistResponse(body: ByteArray, isHead: Boolean) {
    val head = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("Content-Type: ").append(IosAvPlayerMediaBridge.HLS_MIME_TYPE).append("\r\n")
        append("Cache-Control: no-store\r\n")
        append("Content-Length: ").append(body.size).append("\r\n")
        append("Connection: close\r\n\r\n")
    }
    writeFully(head.encodeToByteArray())
    if (!isHead && body.isNotEmpty()) {
        writeFully(body)
    }
    flushAndClose()
}

@OptIn(ExperimentalUuidApi::class)
private fun newToken(): String = Uuid.random().toHexString()
