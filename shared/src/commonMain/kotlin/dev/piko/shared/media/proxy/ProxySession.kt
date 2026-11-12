package dev.piko.shared.media.proxy

import dev.piko.shared.log.PikoLog
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.BufferBudget
import io.github.nihildigit.pikpak.ConsumerIntent
import io.github.nihildigit.pikpak.ReadEndReason
import io.github.nihildigit.pikpak.ReadTicket
import io.github.nihildigit.pikpak.ServiceIntent
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * 纯 HTTP 区间传输会话适配器：不计算预读策略、不猜测流式状态、不维护 1500ms 连接池生命周期耦合。
 *
 * 每个 HTTP Range 请求各自从 [source] 申请一张有界 [ReadTicket]，写出响应体期间推进票据游标，
 * 请求完成或客户端断开时立即在 `finally` 中调用 [ReadTicket.endRead] 释放该请求的读取需求；
 * 会话级 [TransferDemand] 保持存活直至 [withdrawTransport] / [close]。
 */
internal class ProxySession(
    private val source: ProxyByteSource,
    demand: TransferDemand? = null,
    internal val label: String = "anon",
) : AutoCloseable {
    val size: Long get() = source.size
    val receivedBytes: Long get() = source.receivedBytes
    val isReactivating: StateFlow<Boolean> get() = source.isReactivating

    @Volatile
    var isClosed = false
        private set

    @Volatile
    var isWithdrawn = false
        private set

    private var withdrawnLogged = false
    private val lock = PikoLock()
    private val tickets = LinkedHashSet<ReadTicket>()
    private val warms = LinkedHashSet<Deferred<Unit>>()

    @Volatile
    private var externalDemand: TransferDemand? = demand
    private var fallbackDemand: TransferDemand? = null

    val demand: TransferDemand
        get() = externalDemand ?: withPikoLock(lock) { activeDemandLocked() }

    internal fun bindDemand(owner: TransferDemand) {
        val oldFallback = withPikoLock(lock) {
            if (isWithdrawn) {
                owner.close()
                return
            }
            externalDemand = owner
            fallbackDemand.also { fallbackDemand = null }
        }
        oldFallback?.close()
    }

    private fun activeDemandLocked(): TransferDemand {
        externalDemand?.let { return it }
        return fallbackDemand ?: TransferDemand(
            ConsumerIntent(
                service = ServiceIntent.WAITING,
                bufferBudget = BufferBudget.NONE,
            ),
        ).also { fallbackDemand = it }
    }

    internal fun markWithdrawnLogged(): Boolean = withPikoLock(lock) {
        if (withdrawnLogged) false else {
            withdrawnLogged = true
            true
        }
    }

    suspend fun prefetch(
        ranges: List<LongRange>,
        role: StreamRole = StreamRole.BACKGROUND,
        priority: Int? = null,
    ) {
        val owner = withPikoLock(lock) {
            if (isWithdrawn) throw CancellationException("transport withdrawn")
            activeDemandLocked()
        }
        val warm = source.prefetch(ranges, owner, role, priority) ?: return
        withPikoLock(lock) {
            if (isWithdrawn) warm.cancel() else warms.add(warm)
        }
        try {
            warm.await()
        } finally {
            warm.cancel()
            withPikoLock(lock) { warms.remove(warm) }
        }
    }

    suspend fun stream(start: Long, endExclusive: Long, sink: suspend (ByteArray, Int, Int) -> Unit) {
        if (endExclusive <= start) {
            if (isWithdrawn) throw CancellationException("transport withdrawn")
            return
        }
        val ticket = withPikoLock(lock) {
            if (isWithdrawn) throw CancellationException("transport withdrawn")
            val owner = activeDemandLocked()
            source.beginRead(owner, start, endExclusive).also { created ->
                tickets.add(created)
            }
        }
        var endReason = ReadEndReason.Cancelled
        try {
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var position = start
            while (position < endExclusive) {
                currentCoroutineContext().ensureActive()
                if (isWithdrawn) throw CancellationException("transport withdrawn")
                val maxChunk = minOf(buffer.size.toLong(), endExclusive - position).toInt()
                val count = ticket.readSome(buffer, 0, maxChunk)
                check(count > 0) { "source ended at $position before $endExclusive" }
                try {
                    sink(buffer, 0, count)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw ClientGone(e)
                }
                position += count
            }
            endReason = if (isWithdrawn) ReadEndReason.Cancelled else ReadEndReason.RangeCompleted
        } catch (e: CancellationException) {
            endReason = if (!isWithdrawn && e.isClientDisconnect()) {
                ReadEndReason.ConsumerClosed
            } else {
                ReadEndReason.Cancelled
            }
            throw e
        } catch (e: Exception) {
            if (e is ClientGone) {
                endReason = if (isWithdrawn) ReadEndReason.Cancelled else ReadEndReason.ConsumerClosed
            } else {
                if (isWithdrawn) {
                    endReason = ReadEndReason.Cancelled
                    throw CancellationException("transport withdrawn", e)
                }
                endReason = ReadEndReason.SourceFailed
                val rangeDesc = "bytes $start..${endExclusive - 1}/$size"
                PikoLog.w(TAG, "Proxy stream read failed: session=$label, range=$rangeDesc", e)
            }
            throw e
        } finally {
            withPikoLock(lock) { tickets.remove(ticket) }
            ticket.endRead(endReason)
        }
    }

    /** 撤回网络需求，但保留 URL 注册与来源，直到后端确认输入已排空。 */
    fun withdrawTransport() {
        var activeTickets: List<ReadTicket> = emptyList()
        var activeWarms: List<Deferred<Unit>> = emptyList()
        var bound: TransferDemand? = null
        var fallback: TransferDemand? = null
        val shouldWithdraw = withPikoLock(lock) {
            if (isWithdrawn) {
                false
            } else {
                isWithdrawn = true
                activeWarms = warms.toList()
                warms.clear()
                activeTickets = tickets.toList()
                tickets.clear()
                bound = externalDemand
                fallback = fallbackDemand
                fallbackDemand = null
                true
            }
        }
        if (!shouldWithdraw) return
        activeWarms.forEach { it.cancel() }
        bound?.close()
        fallback?.close()
        activeTickets.forEach { it.endRead(ReadEndReason.Cancelled) }
    }

    override fun close() {
        if (isClosed) return
        withdrawTransport()
        isClosed = true
        source.close()
    }

    internal class ClientGone(cause: Exception) : Exception("Client disconnected", cause)
    internal class ClientDisconnectedCancellation : CancellationException("Client disconnected")

    private fun Throwable.isClientDisconnect(): Boolean {
        var cur: Throwable? = this
        while (cur != null) {
            if (cur is ClientDisconnectedCancellation || cur is ClientGone) return true
            cur = cur.cause
        }
        return false
    }

    private companion object {
        const val TAG = "MediaProxy"
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}
