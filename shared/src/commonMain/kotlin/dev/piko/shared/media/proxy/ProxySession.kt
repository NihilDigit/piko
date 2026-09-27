package dev.piko.shared.media.proxy

import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile

/**
 * 一个媒体会话：一个字节来源，播放器的每个请求各开一个 reader。
 *
 * 播放器拖动时先开新连接、后断旧连接，两个请求会短暂重叠；交错得不好的 MP4 甚至一直在两处来回读。
 * SDK 同一文件的 reader 共用一份缓存，各有各的读位置，所以请求之间互不取消，一处取到的另一处直接读。
 */
internal class ProxySession(private val source: ProxyByteSource) : AutoCloseable {
    val size: Long get() = source.size

    @Volatile
    var isClosed = false
        private set

    private val readers = MutableStateFlow<Set<ProxyReader>>(emptySet())

    @Volatile
    private var tailRequested = false

    /**
     * 前台或后台，见 [ProxyReader.role]。切换时同步给在读的 reader，缓存不丢；之后开的照它开。
     * 预热的会话以后台建起，翻到它时升为前台。
     */
    @Volatile
    var role: StreamRole = StreamRole.FOREGROUND
        set(value) {
            field = value
            readers.value.forEach { it.role = value }
        }

    /** 预读深度，见 [ProxyReader.readAheadLimit]。null 为默认；之后开的 reader 照样带上。 */
    @Volatile
    var readAheadLimit: Long? = null
        set(value) {
            field = value
            readers.value.forEach { it.readAheadLimit = value }
        }

    /**
     * 把 [ranges] 取进缓存，等全部到手才返回；[role] 缺省为会话的角色。没有缓存可填的来源立即返回。
     * 等的一方被取消（超时、翻走）时一并撤回这次预取：SDK 的预取不随等待者取消，留着它会以更早的
     * 需求排在眼前要放的段前面。
     */
    suspend fun prefetch(ranges: List<LongRange>, role: StreamRole = this.role) {
        val warm = source.prefetch(ranges, role) ?: return
        try {
            warm.await()
        } finally {
            warm.cancel()
        }
    }

    /** 把 [start, endExclusive) 的字节依次交给 [sink]。 */
    suspend fun stream(start: Long, endExclusive: Long, sink: suspend (ByteArray, Int, Int) -> Unit) {
        if (start == 0L) requestTail()
        val reader = source.openReader(role)
        readers.update { it + reader }
        try {
            if (isClosed) throw CancellationException("会话已关闭")
            readAheadLimit?.let { reader.readAheadLimit = it }
            if (start != 0L) reader.seekTo(start)
            pump(reader, start, endExclusive, sink)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 播放器换片时断开连接，写给它就会失败，那不算读取出错
            if (!isClosed && e !is ClientGone) PikoLog.w("Proxy", "读取 $start-$endExclusive/$size 失败", e)
            throw e
        } finally {
            readers.update { it - reader }
            reader.close()
        }
    }

    /**
     * 播放器读文件头时顺手取文件尾。MKV 的 Cues、moov 放在末尾的 MP4，索引都在文件尾，
     * 解复用器读完头要跳过去再跳回来，每一跳都是一次往返；与读头并行取，跳过去时已在缓存里。
     * 索引在文件头的 MP4 白取这一截，512 KiB 不值得为它先探一次格式。
     */
    private suspend fun requestTail() {
        if (tailRequested || size <= TAIL_BYTES * 2) return
        tailRequested = true
        val priority = if (role == StreamRole.FOREGROUND) PikPakStreamReader.INDEX_PRIORITY else null
        source.prefetch(listOf(size - TAIL_BYTES until size), role, priority)
    }

    private suspend fun pump(reader: ProxyReader, start: Long, endExclusive: Long, sink: suspend (ByteArray, Int, Int) -> Unit) {
        val buffer = ByteArray(CHUNK_BYTES)
        var position = start
        while (position < endExclusive) {
            currentCoroutineContext().ensureActive()
            val want = minOf(buffer.size.toLong(), endExclusive - position).toInt()
            val read = reader.read(buffer, 0, want)
            check(read > 0) { "字节来源在 $position 处提前结束，应到 $endExclusive" }
            try {
                sink(buffer, 0, read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ClientGone(e)
            }
            position += read
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        readers.value.forEach { runCatching { it.close() } }
        runCatching { source.close() }
    }

    private class ClientGone(cause: Exception) : Exception("播放器断开", cause)

    private companion object {
        // 与 SDK reader 的块大小一致，一次 read 最多也只交出一块
        const val CHUNK_BYTES = 256 * 1024

        const val TAIL_BYTES = 512L * 1024
    }
}
