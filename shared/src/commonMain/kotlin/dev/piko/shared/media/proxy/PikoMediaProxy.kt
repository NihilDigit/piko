package dev.piko.shared.media.proxy

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
import io.ktor.utils.io.readLineStrict
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 本机回环 HTTP 代理：把 PikPak 文件包装成一个 http URL 交给播放器。
 *
 * 两端播放器都只认 URL，而直链会过期、单链接有 8 连接上限、拖动需要块缓存与优先级，
 * 这些都在 SDK 的 reader 里；代理让 mpv 读 reader，而不是直接读 CDN。
 *
 * 只实现播放器用得到的 HTTP/1.1 子集：GET/HEAD、单段 Range、416，每个响应后关闭连接。
 * 没用 ktor-server：它的路由、管线与引擎配置对两个方法的服务都是负担，
 * 而 socket 层的断开与取消语义在这里要亲手控制。
 */
class PikoMediaProxy(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName("PikoMediaProxy"))

    /** reader 的 worker 挂在代理的作用域下，代理关闭时一并结束。 */
    internal val readerContext: CoroutineContext get() = scope.coroutineContext

    private val startLock = Mutex()
    private var selector: SelectorManager? = null

    @Volatile
    private var server: ServerSocket? = null

    private val sessionsLock = Mutex()
    private val sessions = HashMap<String, ProxySession>()

    /**
     * 登记一个媒体会话，返回播放器可读的 URL。关闭返回值即释放 reader 与 handle。
     *
     * [fileName] 只用来给 URL 带上扩展名，FFmpeg 探测格式时会参考它。
     */
    suspend fun register(source: ProxyByteSource, fileName: String?): ProxyStream {
        val port = ensureStarted()
        val token = newToken()
        val session = ProxySession(source)
        sessionsLock.withLock { sessions[token] = session }
        return ProxyStream("http://$LOOPBACK:$port/$token/${pathNameOf(fileName)}") {
            session.close()
            scope.launch {
                sessionsLock.withLock { if (sessions[token] === session) sessions.remove(token) }
            }
        }
    }

    private suspend fun ensureStarted(): Int = startLock.withLock {
        server?.takeUnless { it.isClosed }?.let { return@withLock it.port }
        val manager = selector ?: SelectorManager(dispatcher).also { selector = it }
        // 端口传 0 由系统挑一个空闲端口；只绑回环，局域网里的其他设备连不上
        val bound = aSocket(manager).tcp().bind(LOOPBACK, 0)
        server = bound
        scope.launch { acceptLoop(bound) }
        bound.port
    }

    private suspend fun acceptLoop(bound: ServerSocket) {
        while (true) {
            val socket = try {
                bound.accept()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 监听 socket 被关了。下一次 register 会重新绑定
                return
            }
            scope.launch { serveConnection(socket) }
        }
    }

    private suspend fun serveConnection(socket: Socket) {
        try {
            coroutineScope {
                val input = socket.openReadChannel()
                val output = socket.openWriteChannel(autoFlush = false)
                val request = readRequest(input) ?: return@coroutineScope
                val response = launch { respond(request, output) }
                // 每个响应都带 Connection: close，客户端之后不会再发数据，
                // 读端结束就是对方断开了：立刻取消响应，把 reader 让给下一个请求
                val watcher = launch {
                    input.discard()
                    response.cancel()
                }
                response.join()
                watcher.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 客户端中途断开时写端抛异常，属于正常收尾
        } finally {
            socket.close()
        }
    }

    private suspend fun respond(request: ProxyRequest, output: ByteWriteChannel) {
        val isHead = request.method == "HEAD"
        if (!isHead && request.method != "GET") {
            output.writeHead(405, "Method Not Allowed", listOf("Allow" to "GET, HEAD", "Content-Length" to "0"))
            output.flushAndClose()
            return
        }
        val token = request.target.removePrefix("/").substringBefore('/').substringBefore('?')
        val session = sessionsLock.withLock { sessions[token] }?.takeUnless { it.isClosed }
        if (session == null) {
            output.writeHead(404, "Not Found", listOf("Content-Length" to "0"))
            output.flushAndClose()
            return
        }

        val size = session.size
        when (val range = parseByteRange(request.headers["range"], size)) {
            ByteRange.Unsatisfiable -> {
                output.writeHead(
                    416,
                    "Range Not Satisfiable",
                    listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"),
                )
                output.flushAndClose()
            }

            is ByteRange.Satisfiable -> {
                val headers = buildList {
                    add("Content-Type" to "application/octet-stream")
                    add("Accept-Ranges" to "bytes")
                    add("Content-Length" to range.length.toString())
                    if (range.isPartial) {
                        add("Content-Range" to "bytes ${range.start}-${range.endExclusive - 1}/$size")
                    }
                }
                if (range.isPartial) {
                    output.writeHead(206, "Partial Content", headers)
                } else {
                    output.writeHead(200, "OK", headers)
                }
                output.flush()
                if (!isHead && range.length > 0) {
                    session.stream(range.start, range.endExclusive) { buffer, offset, length ->
                        output.writeFully(buffer, offset, offset + length)
                        output.flush()
                    }
                }
                output.flushAndClose()
            }
        }
    }

    /** 关闭监听与全部会话。进程内常驻时不需要调用，主要给测试用。 */
    override fun close() {
        scope.cancel()
        runCatching { server?.close() }
        runCatching { selector?.close() }
        // 作用域已取消，拿不到锁也不再有并发写入，直接遍历快照
        runCatching { sessions.values.toList() }.getOrNull()?.forEach { it.close() }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}

/** 代理里的一个可播放 URL。关闭后同一 URL 回 404。 */
class ProxyStream internal constructor(
    val url: String,
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

internal class ProxyRequest(
    val method: String,
    val target: String,
    /** 名字已转小写。 */
    val headers: Map<String, String>,
)

private const val MAX_LINE_BYTES = 8 * 1024L
private const val MAX_HEADER_LINES = 100

private suspend fun readRequest(input: ByteReadChannel): ProxyRequest? {
    val requestLine = input.readLineStrict(MAX_LINE_BYTES) ?: return null
    val parts = requestLine.split(' ')
    if (parts.size < 3) return null
    val headers = HashMap<String, String>()
    repeat(MAX_HEADER_LINES) {
        val line = input.readLineStrict(MAX_LINE_BYTES) ?: return null
        if (line.isEmpty()) return ProxyRequest(parts[0].uppercase(), parts[1], headers)
        val colon = line.indexOf(':')
        if (colon > 0) {
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }
    return null
}

private suspend fun ByteWriteChannel.writeHead(status: Int, reason: String, headers: List<Pair<String, String>>) {
    val head = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("Connection: close\r\n\r\n")
    }
    writeStringUtf8(head)
}

internal sealed interface ByteRange {
    data class Satisfiable(val start: Long, val endExclusive: Long, val isPartial: Boolean) : ByteRange {
        val length: Long get() = endExclusive - start
    }

    data object Unsatisfiable : ByteRange
}

/**
 * 解析单段 Range。语法不对或是多段请求时按 RFC 9110 忽略 Range、回整个文件；
 * 起点越过文件尾才是 416。
 */
internal fun parseByteRange(header: String?, size: Long): ByteRange {
    val whole = ByteRange.Satisfiable(0, size, isPartial = false)
    val spec = header?.trim() ?: return whole
    if (!spec.startsWith("bytes=", ignoreCase = true)) return whole
    val value = spec.substring("bytes=".length).trim()
    // 多段要回 multipart/byteranges，播放器从不这样请求
    if (',' in value) return whole
    val dash = value.indexOf('-')
    if (dash < 0) return whole
    val first = value.substring(0, dash).trim()
    val last = value.substring(dash + 1).trim()

    if (first.isEmpty()) {
        val suffix = last.toLongOrNull() ?: return whole
        if (suffix <= 0L || size == 0L) return ByteRange.Unsatisfiable
        return ByteRange.Satisfiable((size - suffix).coerceAtLeast(0L), size, isPartial = true)
    }
    val start = first.toLongOrNull()?.takeIf { it >= 0L } ?: return whole
    val end = if (last.isEmpty()) Long.MAX_VALUE else last.toLongOrNull() ?: return whole
    if (end < start) return whole
    if (start >= size) return ByteRange.Unsatisfiable
    return ByteRange.Satisfiable(start, minOf(end, size - 1) + 1, isPartial = true)
}

@OptIn(ExperimentalUuidApi::class)
private fun newToken(): String = Uuid.random().toHexString()

private fun pathNameOf(fileName: String?): String {
    val extension = fileName?.substringAfterLast('.', "")?.lowercase()
        ?.takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) }
    return if (extension == null) "media" else "media.$extension"
}
