package dev.piko.shared.media.proxy

import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.*
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource

/** HTTP 请求拥有读取需求；空闲 reader 只保留对象和位置，不保留网络需求。 */
internal class ProxySession(private val source: ProxyByteSource, private val scope: CoroutineScope? = null) : AutoCloseable {
    val size: Long get() = source.size
    val receivedBytes: Long get() = source.receivedBytes
    @Volatile var isClosed = false
        private set
    @Volatile var isWithdrawn = false
        private set
    private class Entry(val reader: ProxyReader, var inUse: Boolean = true, var streaming: Boolean = false, var sequence: Long = 0, var expiry: Job? = null)
    private val lock = PikoLock()
    private val pool = mutableListOf<Entry>()
    private val warms = mutableSetOf<Deferred<Unit>>()
    private var sequence = 0L
    @Volatile private var feedback: PlaybackBufferSnapshot? = null

    @Volatile var role = StreamRole.FOREGROUND
        set(value) { field = value; configureReaders() }
    @Volatile var demand: TransferDemand? = null
        set(value) { field = value; configureReaders() }
    @Volatile var readAheadLimit: Long? = null
        set(value) { field = value; configureReaders() }
    @Volatile var steady = false
        set(value) { field = value; configureReaders() }
    @Volatile var urgent = false
        set(value) { field = value; if (value) steady = false; configureReaders() }

    private fun configureReaders() = withPikoLock(lock) {
        val active = pool.count { it.inUse }.coerceAtLeast(1)
        val steadyBudget = if (!isWithdrawn) (readAheadLimit ?: DEFAULT_STEADY_AHEAD) / active else 0L
        for (entry in pool) {
            entry.reader.role = role
            if (entry.reader.demand !== demand) entry.reader.demand = demand
            entry.reader.urgent = urgent
            val allowReadAhead = entry.inUse && !isWithdrawn && ((steady && !urgent) || entry.streaming)
            entry.reader.readAheadLimit = if (allowReadAhead) steadyBudget else 0L
            entry.reader.setDemandActive(entry.inUse && !isWithdrawn)
        }
    }

    fun reportPlaybackBuffer(snapshot: PlaybackBufferSnapshot) {
        val previous = feedback
        if (previous != null && snapshot.generation < previous.generation) return
        feedback = snapshot
        withPikoLock(lock) { pool.forEach { it.reader.reportPlaybackBuffer(snapshot) } }
    }

    fun reportPlaybackBuffer(bufferedDuration: Duration, mediaBytesPerSecond: Long? = null) =
        reportPlaybackBuffer(PlaybackBufferSnapshot(feedback?.generation ?: 0, bufferedDuration.coerceAtLeast(Duration.ZERO),
            TimeSource.Monotonic.markNow(), mediaBytesPerSecond))

    suspend fun prefetch(ranges: List<LongRange>, role: StreamRole = this.role, priority: Int? = null) {
        if (isWithdrawn) throw CancellationException("transport withdrawn")
        val warm = source.prefetch(ranges, role, priority, demand) ?: return
        withPikoLock(lock) { if (isWithdrawn) warm.cancel() else warms.add(warm) }
        try { warm.await() } finally { warm.cancel(); withPikoLock(lock) { warms.remove(warm) } }
    }

    suspend fun stream(start: Long, endExclusive: Long, sink: suspend (ByteArray, Int, Int) -> Unit) {
        val entry = acquire(start)
        var broken = false
        try {
            if (isWithdrawn) throw CancellationException("transport withdrawn")
            configureReaders()
            feedback?.let { entry.reader.reportPlaybackBuffer(it) }
            if (entry.reader.position != start) {
                withPikoLock(lock) { entry.streaming = false }
                entry.reader.seekTo(start)
                configureReaders()
            }
            val buffer = ByteArray(256 * 1024)
            var position = start
            while (position < endExclusive) {
                currentCoroutineContext().ensureActive()
                val count = entry.reader.read(buffer, 0, minOf(buffer.size.toLong(), endExclusive - position).toInt())
                check(count > 0) { "source ended at $position before $endExclusive" }
                try { sink(buffer, 0, count) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { throw ClientGone(e) }
                position += count
                if (!entry.streaming && position < endExclusive && position - start >= 256 * 1024L) {
                    withPikoLock(lock) { entry.streaming = true }
                    configureReaders()
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e !is ClientGone) {
                broken = true
                if (!isWithdrawn) PikoLog.w("Proxy", "读取 $start-$endExclusive/$size 失败", e)
            }
            throw e
        } finally { release(entry, broken) }
    }

    private suspend fun acquire(start: Long): Entry {
        val existing = withPikoLock(lock) {
            if (isWithdrawn) throw CancellationException("transport withdrawn")
            val idle = pool.filter { !it.inUse }
            (idle.firstOrNull { it.reader.position == start }
                ?: idle.minByOrNull { it.sequence }.takeIf { pool.size >= MAX_READERS })?.also {
                it.inUse = true; it.streaming = false; it.sequence = ++sequence; it.expiry?.cancel(); it.expiry = null
            }
        }
        if (existing != null) return existing
        val reader = source.openReader(role)
        return withPikoLock(lock) {
            if (isWithdrawn) { reader.close(); throw CancellationException("transport withdrawn") }
            Entry(reader, sequence = ++sequence).also { pool.add(it) }
        }
    }

    private fun release(entry: Entry, broken: Boolean) {
        val close = withPikoLock(lock) {
            entry.reader.setDemandActive(false)
            entry.reader.readAheadLimit = 0
            entry.inUse = false
            entry.streaming = false
            entry.sequence = ++sequence
            if (broken || isWithdrawn || pool.size > MAX_READERS) {
                pool.remove(entry); true
            } else {
                entry.expiry = scope?.launch {
                    delay(1500)
                    val expired = withPikoLock(lock) { !entry.inUse && pool.remove(entry) }
                    if (expired) entry.reader.close()
                }
                false
            }
        }
        if (close) entry.reader.close()
        configureReaders()
    }

    /** 撤回网络需求，但保留 URL 注册与来源，直到后端确认输入已排空。 */
    fun withdrawTransport() {
        val readers = withPikoLock(lock) {
            if (isWithdrawn) return
            isWithdrawn = true
            warms.forEach { it.cancel() }; warms.clear()
            pool.onEach { it.expiry?.cancel() }.map { it.reader }.also { pool.clear() }
        }
        readers.forEach { it.setDemandActive(false); it.close() }
        demand?.close()
    }

    override fun close() {
        if (isClosed) return
        withdrawTransport()
        isClosed = true
        source.close()
    }

    private class ClientGone(cause: Exception) : Exception("播放器断开", cause)
    private companion object {
        const val MAX_READERS = 4
        const val DEFAULT_STEADY_AHEAD = 8L * 1024 * 1024
    }
}
