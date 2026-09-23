package dev.piko.shared.media.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * 一个媒体会话：一个字节来源、同一时刻至多一个 reader、至多一个在读的请求。
 */
internal class ProxySession(private val source: ProxyByteSource) : AutoCloseable {
    val size: Long get() = source.size

    @Volatile
    var isClosed = false
        private set

    private val takeover = Mutex()

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var reader: ProxyReader? = null

    /**
     * 把 [start, endExclusive) 的字节依次交给 [sink]。
     *
     * 播放器拖动时先开新连接、后断旧连接，两个请求会短暂重叠，而 reader 只有一个游标。
     * 后到的请求先取消并等完前一个再 seek，于是任何时刻只有一个协程碰 reader。
     * 取消落在 reader.read 上时 SDK 保证游标不动、reader 仍可用，不必重建。
     */
    suspend fun stream(start: Long, endExclusive: Long, sink: suspend (ByteArray, Int, Int) -> Unit) {
        val self = currentCoroutineContext().job
        takeover.withLock {
            activeJob?.cancelAndJoin()
            activeJob = self
        }
        try {
            pump(start, endExclusive, sink)
        } finally {
            // 不能在这里拿 takeover：接手的请求正持锁等本协程结束，拿锁会互等
            if (activeJob === self) activeJob = null
        }
    }

    private suspend fun pump(start: Long, endExclusive: Long, sink: suspend (ByteArray, Int, Int) -> Unit) {
        val buffer = ByteArray(CHUNK_BYTES)
        var position = start
        // 连续两次失败才放弃：SDK 的 reader 单块重试三次仍失败后会永久失效，
        // 换一个新 reader 往往就能接着读，比让播放器断流重连代价小得多
        var recoveredWithoutProgress = false
        while (position < endExclusive) {
            currentCoroutineContext().ensureActive()
            val want = minOf(buffer.size.toLong(), endExclusive - position).toInt()
            val read = try {
                val current = reader ?: openReader()
                if (current.position != position) current.seekTo(position)
                current.read(buffer, 0, want)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isClosed || recoveredWithoutProgress) throw e
                reader?.close()
                reader = null
                recoveredWithoutProgress = true
                continue
            }
            check(read > 0) { "字节来源在 $position 处提前结束，应到 $endExclusive" }
            sink(buffer, 0, read)
            position += read
            recoveredWithoutProgress = false
        }
    }

    private suspend fun openReader(): ProxyReader {
        val opened = source.openReader()
        if (isClosed) {
            opened.close()
            throw CancellationException("会话已关闭")
        }
        reader = opened
        return opened
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        activeJob?.cancel()
        runCatching { reader?.close() }
        reader = null
        runCatching { source.close() }
    }

    private companion object {
        // 与 SDK reader 的块大小一致，一次 read 最多也只交出一块
        const val CHUNK_BYTES = 256 * 1024
    }
}
