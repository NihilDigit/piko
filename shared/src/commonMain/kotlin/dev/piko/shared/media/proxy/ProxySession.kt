package dev.piko.shared.media.proxy

import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * 一个媒体会话：一个字节来源，按请求位置自适应复用一组短时保活的 reader 游标。
 *
 * 播放器拖动时先开新连接、后断旧连接，两个请求会短暂重叠；交错得不好的 MP4 甚至一直在音视频两处偏移来回发短请求。
 * 若每个 HTTP 请求结束都立即关闭游标，在途预读（READ_AHEAD_PRIORITY）会在几毫秒内被连环取消并销毁底层连接，
 * 新请求重建游标又从单槽位（256 KiB）重算跨度。这里维护小容量多轨游标池：邻近偏移直接接续原游标（零 seek、零取消），
 * 远距离双轨各自保有独立游标，闲置超时或会话关闭时统一回收。
 */
internal class ProxySession(
    private val source: ProxyByteSource,
    private val scope: CoroutineScope? = null,
) : AutoCloseable {
    val size: Long get() = source.size

    @Volatile
    var isClosed = false
        private set

    private class PooledReader(
        val reader: ProxyReader,
        var inUse: Boolean = true,
        var lastUsedSeq: Long = 0L,
        var idleJob: Job? = null,
    )

    private val poolLock = Any()
    private val pool = ArrayList<PooledReader>(MAX_POOLED_READERS)
    private var useSeq = 0L

    @Volatile
    private var tailRequested = false

    private fun snapshotReaders(): List<ProxyReader> = synchronized(poolLock) {
        pool.map { it.reader }
    }

    /**
     * 前台或后台，见 [ProxyReader.role]。切换时同步给池中所有游标（含短时保活游标），缓存不丢；之后开的照它开。
     * 预热的会话以后台建起，翻到它时升为前台。
     */
    @Volatile
    var role: StreamRole = StreamRole.FOREGROUND
        set(value) {
            field = value
            snapshotReaders().forEach { it.role = value }
        }

    /** 预读深度，见 [ProxyReader.readAheadLimit]。null 为默认；同步给池中所有游标，降档时闲置游标一并收起预读。 */
    @Volatile
    var readAheadLimit: Long? = null
        set(value) {
            field = value
            snapshotReaders().forEach { it.readAheadLimit = value }
        }

    /**
     * 有人正等着这个会话，见 [ProxyReader.urgent]。同步给池中游标，之后开的照它开：
     * 拖动时播放器正是新开一个请求去读新位置。
     */
    @Volatile
    var urgent: Boolean = false
        set(value) {
            field = value
            snapshotReaders().forEach { it.urgent = value }
        }

    /**
     * 把 [ranges] 取进缓存，等全部到手才返回；[role] 缺省为会话的角色。没有缓存可填的来源立即返回。
     * 等的一方被取消（超时、翻走）时一并撤回这次预取：SDK 的预取不随等待者取消，留着它会以更早的
     * 需求排在眼前要放的段前面。
     */
    suspend fun prefetch(ranges: List<LongRange>, role: StreamRole = this.role, priority: Int? = null) {
        val warm = source.prefetch(ranges, role, priority) ?: return
        try {
            warm.await()
        } finally {
            warm.cancel()
        }
    }

    /** 把 [start, endExclusive) 的字节依次交给 [sink]。 */
    suspend fun stream(start: Long, endExclusive: Long, sink: suspend (ByteArray, Int, Int) -> Unit) {
        if (start == 0L) requestTail()
        val entry = acquireReader(start)
        val reader = entry.reader
        var broken = false
        try {
            if (isClosed) throw CancellationException("会话已关闭")
            val currentRole = role
            if (reader.role != currentRole) reader.role = currentRole
            val currentLimit = readAheadLimit
            if (currentLimit != null && reader.readAheadLimit != currentLimit) {
                reader.readAheadLimit = currentLimit
            }
            val currentUrgent = urgent
            if (reader.urgent != currentUrgent) reader.urgent = currentUrgent
            if (reader.position != start) {
                reader.seekTo(start)
            }
            pump(reader, start, endExclusive, sink)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 播放器换片或读够时断开连接，写给它就会失败，游标本身完好，不算底层读取损坏
            if (e !is ClientGone) {
                broken = true
                if (!isClosed) PikoLog.w("Proxy", "读取 $start-$endExclusive/$size 失败", e)
            }
            throw e
        } finally {
            releaseReader(entry, broken)
        }
    }

    private suspend fun acquireReader(start: Long): PooledReader {
        var reused: PooledReader? = null
        synchronized(poolLock) {
            if (isClosed) throw CancellationException("会话已关闭")
            val idle = pool.filter { !it.inUse }
            // 1. 优先接续位置紧邻的闲置游标（连续分段 Range 或同一音/视频轨的下一帧，position == start 时零 seek）
            val nearForward = idle
                .filter { start >= it.reader.position && start - it.reader.position <= FORWARD_REUSE_GAP_BYTES }
                .minByOrNull { start - it.reader.position }
            val nearBackward = if (nearForward == null) {
                idle
                    .filter { it.reader.position > start && it.reader.position - start <= BACKWARD_REUSE_GAP_BYTES }
                    .minByOrNull { it.reader.position - start }
            } else null
            val candidate = nearForward ?: nearBackward ?: if (pool.size >= MAX_POOLED_READERS) {
                // 2. 池已满时淘汰复用最久未用的闲置游标；池未满时为远距离新轨道另开游标，免得音视频双轨交替互相冲掉预读
                idle.minByOrNull { it.lastUsedSeq }
            } else null
            if (candidate != null) {
                candidate.inUse = true
                candidate.lastUsedSeq = ++useSeq
                candidate.idleJob?.cancel()
                candidate.idleJob = null
                reused = candidate
            }
        }
        reused?.let { return it }

        val fresh = source.openReader(role)
        var closeImmediately = false
        val created = synchronized(poolLock) {
            if (isClosed) {
                closeImmediately = true
                PooledReader(fresh, inUse = false)
            } else {
                PooledReader(fresh, inUse = true, lastUsedSeq = ++useSeq).also { pool.add(it) }
            }
        }
        if (closeImmediately) {
            runCatching { fresh.close() }
            throw CancellationException("会话已关闭")
        }
        return created
    }

    private fun releaseReader(entry: PooledReader, broken: Boolean) {
        var toClose: List<ProxyReader> = emptyList()
        synchronized(poolLock) {
            entry.idleJob?.cancel()
            entry.idleJob = null
            if (isClosed || broken) {
                pool.remove(entry)
                toClose = listOf(entry.reader)
            } else {
                entry.inUse = false
                entry.lastUsedSeq = ++useSeq
                // 超出池容量上限的额外并发游标在归还时收敛回 MAX_POOLED_READERS
                if (pool.size > MAX_POOLED_READERS) {
                    val victims = pool.filter { !it.inUse }
                        .sortedBy { it.lastUsedSeq }
                        .take(pool.size - MAX_POOLED_READERS)
                    victims.forEach { victim ->
                        victim.idleJob?.cancel()
                        victim.idleJob = null
                        pool.remove(victim)
                    }
                    toClose = victims.map { it.reader }
                }
                if (entry in pool && scope != null) {
                    entry.idleJob = scope.launch {
                        delay(IDLE_READER_KEEP_ALIVE)
                        val expired = synchronized(poolLock) {
                            if (!entry.inUse && pool.remove(entry)) {
                                entry.idleJob = null
                                entry.reader
                            } else null
                        }
                        expired?.let { runCatching { it.close() } }
                    }
                }
            }
        }
        toClose.forEach { runCatching { it.close() } }
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
        val toClose: List<ProxyReader>
        synchronized(poolLock) {
            if (isClosed) return
            isClosed = true
            pool.forEach { it.idleJob?.cancel(); it.idleJob = null }
            toClose = pool.map { it.reader }
            pool.clear()
        }
        toClose.forEach { runCatching { it.close() } }
        runCatching { source.close() }
    }

    private class ClientGone(cause: Exception) : Exception("播放器断开", cause)

    private companion object {
        // 与 SDK reader 的块大小一致，一次 read 最多也只交出一块
        const val CHUNK_BYTES = 256 * 1024

        const val TAIL_BYTES = 512L * 1024

        // 覆盖交错 MP4 的视频轨、音轨、字幕轨及尾部探测，互不挤占预读窗口
        const val MAX_POOLED_READERS = 4
        const val FORWARD_REUSE_GAP_BYTES = 2L * 1024 * 1024
        const val BACKWARD_REUSE_GAP_BYTES = 512L * 1024
        val IDLE_READER_KEEP_ALIVE = 1500.milliseconds
    }
}
