package dev.piko.shared.media.proxy

import dev.piko.shared.media.testing.openRequest
import dev.piko.shared.media.testing.request
import io.github.nihildigit.pikpak.ReadEndReason
import io.github.nihildigit.pikpak.ReadTicket
import io.github.nihildigit.pikpak.StreamRole
import io.github.nihildigit.pikpak.TransferDemand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 代理协议层的冒烟：起真实的回环代理，用原始 socket 发请求，并核对每条 HTTP 请求与独立 [ReadTicket] 生命周期的零延迟解耦。
 */
class PikoMediaProxyTest {
    private val proxy = PikoMediaProxy()

    @AfterTest
    fun tearDown() = proxy.close()

    @Test
    fun eachStreamOpensBoundedReadTicketAndEndsImmediatelyWithoutGraceTimer() = runBlocking {
        val source = FakeSource(8L * 1024 * 1024)
        val demand = TransferDemand()
        val session = ProxySession(source, demand = demand)
        try {
            session.stream(4096L, 12_288L) { bytes, offset, count ->
                assertEquals(1, source.activeTickets.get())
                assertContentEquals(source.expected(4096L, 4096L + count), bytes.copyOfRange(offset, offset + count))
            }
            // 请求完成当即释放票据（无 1500ms 延迟），但会话级 TransferDemand 保持打开
            assertEquals(0, source.activeTickets.get())
            assertFalse(demand.isClosed)
            val recorded = source.ticketHistory.single()
            assertSame(demand, recorded.owner)
            assertEquals(4096L, recorded.startOffset)
            assertEquals(12_288L, recorded.endExclusive)
            assertEquals(ReadEndReason.RangeCompleted, recorded.endReason)
            assertEquals(true, recorded.allowFinishInFlightOnEnd)

            session.stream(2L * 1024 * 1024, 2L * 1024 * 1024 + 2048L) { _, _, _ ->
                assertEquals(1, source.activeTickets.get())
                // 会话内 seek 推进 epoch 时不断开当前流；ProxySession 纯粹上报协议层 RangeCompleted 事实，由底层缓存按 epoch 决定收尾策略
                demand.updateIntent { it.copy(epoch = it.epoch + 1L) }
            }
            assertEquals(0, source.activeTickets.get())
            assertEquals(2, source.ticketHistory.size)
            assertEquals(ReadEndReason.RangeCompleted, source.ticketHistory.last().endReason)
            assertFalse(demand.isClosed)

            assertFailsWith<ProxySession.ClientGone> {
                session.stream(3L * 1024 * 1024, 3L * 1024 * 1024 + 64L * 1024L) { _, _, _ ->
                    throw IllegalStateException("simulated socket write failure")
                }
            }
            assertEquals(ReadEndReason.ConsumerClosed, source.ticketHistory.last().endReason)

            val sinkEntered = CompletableDeferred<Unit>()
            val holdSink = CompletableDeferred<Unit>()
            val cancelledJob = async {
                session.stream(4L * 1024 * 1024, 4L * 1024 * 1024 + 64L * 1024L) { _, _, _ ->
                    sinkEntered.complete(Unit)
                    holdSink.await()
                }
            }
            withTimeout(5_000) { sinkEntered.await() }
            cancelledJob.cancelAndJoin()
            assertEquals(ReadEndReason.Cancelled, source.ticketHistory.last().endReason)
            assertFalse(demand.isClosed)
        } finally {
            session.close()
        }
        assertTrue(demand.isClosed)
    }

    @Test
    fun withdrawTransportEndsActiveTicketsWithoutFinishInFlightAndKeepsUrlUntilClose() = runBlocking {
        val source = FakeSource(2L * 1024 * 1024)
        val demand = TransferDemand()
        val stream = proxy.register(source, "movie.mp4", demand)
        val enteredRead = CompletableDeferred<Unit>()
        val unblockSink = CompletableDeferred<Unit>()
        source.onTicketRead = {
            enteredRead.complete(Unit)
        }

        val inFlight = async {
            stream.session.stream(0L, 512L * 1024L) { _, _, _ ->
                enteredRead.complete(Unit)
                unblockSink.await()
            }
        }
        withTimeout(5_000) { enteredRead.await() }
        assertEquals(1, source.activeTickets.get())

        stream.withdrawTransport()
        assertEquals(0, source.activeTickets.get(), "withdrawTransport 必须立即撤销在途 ReadTicket")
        assertEquals(ReadEndReason.Cancelled, source.ticketHistory.single().endReason)
        assertEquals(false, source.ticketHistory.single().allowFinishInFlightOnEnd)
        assertTrue(demand.isClosed)
        assertEquals(200, request(stream.url, "HEAD").status)
        assertEquals(503, request(stream.url).status)
        assertFalse(source.closed)

        unblockSink.complete(Unit)
        runCatching { inFlight.await() }

        stream.close()
        assertEquals(404, request(stream.url).status)
        assertTrue(source.closed)
    }

    @Test
    fun sourceFailureEndsTicketWithAllowFinishInFlightFalse() = runBlocking {
        val source = FakeSource(64L * 1024L).apply { failReads = true }
        val session = ProxySession(source)
        try {
            assertFailsWith<IllegalStateException> {
                session.stream(0L, 4096L) { _, _, _ -> }
            }
            assertEquals(0, source.activeTickets.get())
            assertEquals(ReadEndReason.SourceFailed, source.ticketHistory.single().endReason)
            assertEquals(false, source.ticketHistory.single().allowFinishInFlightOnEnd)
        } finally {
            session.close()
        }
    }

    @Test
    fun servesRangesWithCorrectHeadersAndBytes() = runBlocking {
        val source = FakeSource(size = 1_000_000)
        val url = proxy.register(source, "movie.mkv").url

        val open = request(url, range = "bytes=1000-")
        assertEquals(206, open.status)
        assertEquals("bytes 1000-999999/1000000", open.headers["content-range"])
        assertEquals("999000", open.headers["content-length"])
        assertContentEquals(source.expected(1000, 1_000_000), open.body)

        val bounded = request(url, range = "bytes=10-19")
        assertEquals(206, bounded.status)
        assertContentEquals(source.expected(10, 20), bounded.body)

        val suffix = request(url, range = "bytes=-100")
        assertEquals("bytes 999900-999999/1000000", suffix.headers["content-range"])
        assertContentEquals(source.expected(999_900, 1_000_000), suffix.body)

        val whole = request(url)
        assertEquals(200, whole.status)
        assertEquals("bytes", whole.headers["accept-ranges"])
        assertEquals(1_000_000, whole.body.size)

        // FFmpeg 探到文件尾之后会发这种请求，回错状态码它会当成读错误
        val beyond = request(url, range = "bytes=1000000-")
        assertEquals(416, beyond.status)
        assertEquals("bytes */1000000", beyond.headers["content-range"])

        val head = request(url, "HEAD", range = "bytes=0-")
        assertEquals(206, head.status)
        assertEquals("1000000", head.headers["content-length"])

        assertEquals(0, source.concurrencyViolations.get())
        assertEquals(0, source.activeTickets.get())
    }

    // 交错得不好的 MP4 一直在两处来回读，一个请求不能掐断另一个
    @Test
    fun overlappingRequestsAreServedSideBySide() = runBlocking {
        val source = FakeSource(size = 16 * 1024 * 1024, readDelayMillis = 5)
        val url = proxy.register(source, "movie.mp4").url

        val first = openRequest(url, range = "bytes=0-999999")
        first.body.readNBytes(100_000)

        val second = withTimeout(10_000) { request(url, range = "bytes=8000000-8099999") }
        assertEquals(206, second.status)
        assertContentEquals(source.expected(8_000_000, 8_100_000), second.body)

        val rest = withTimeout(10_000) { first.body.readAllBytes() }
        assertContentEquals(source.expected(100_000, 1_000_000), rest, "the earlier request was cut off")
        first.socket.close()

        assertEquals(0, source.concurrencyViolations.get(), "one ticket was used concurrently by two requests")
    }

    @Test
    fun clientDisconnectReleasesTheTicketForTheNextRequest() = runBlocking {
        val source = FakeSource(size = 16 * 1024 * 1024, readDelayMillis = 5)
        val url = proxy.register(source, "movie.mp4").url

        val dropped = openRequest(url, range = "bytes=0-")
        dropped.body.readNBytes(50_000)
        dropped.socket.close()
        // 断开之后读端的看门协程应当取消响应，票据立即以 ConsumerClosed 结束，不再有人读
        withTimeout(5_000) {
            while (source.activeReads.get() > 0 || source.activeTickets.get() > 0) delay(10)
        }
        assertEquals(ReadEndReason.ConsumerClosed, source.ticketHistory.first().endReason)

        val next = request(url, range = "bytes=100-199")
        assertContentEquals(source.expected(100, 200), next.body)
        assertEquals(0, source.concurrencyViolations.get())
        assertEquals(0, source.activeTickets.get())
    }

    @Test
    fun aSliceServesItsWindowAsAWholeFileAndTranslatesBoundedTicketOffsets() = runBlocking {
        val source = FakeSource(size = 1_000_000)
        val url = proxy.register(SlicedByteSource(source, offset = 300_000, size = 200_000), "clip.ts").url

        val whole = request(url)
        assertEquals("200000", whole.headers["content-length"])
        assertContentEquals(source.expected(300_000, 500_000), whole.body)

        val tail = request(url, range = "bytes=199000-")
        assertEquals("bytes 199000-199999/200000", tail.headers["content-range"])
        assertContentEquals(source.expected(499_000, 500_000), tail.body)

        assertEquals(0, source.activeTickets.get())
        val firstTicket = source.ticketHistory[0]
        assertEquals(300_000L, firstTicket.startOffset)
        assertEquals(500_000L, firstTicket.endExclusive)
        val tailTicket = source.ticketHistory[1]
        assertEquals(499_000L, tailTicket.startOffset)
        assertEquals(500_000L, tailTicket.endExclusive)
    }

    @Test
    fun overlappingDistantRequestsUseIndependentTicketsAndReleaseIndependently() = runBlocking {
        val source = FakeSource(size = 32 * 1024 * 1024)
        val session = ProxySession(source)
        val firstEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        try {
            val first = async {
                session.stream(1_000_000L, 1_004_096L) { _, _, _ ->
                    firstEntered.complete(Unit)
                    finishFirst.await()
                }
            }
            withTimeout(5_000) { firstEntered.await() }
            session.stream(16_000_000L, 16_004_096L) { bytes, offset, count ->
                assertEquals(2, source.activeTickets.get())
                assertContentEquals(source.expected(16_000_000L, 16_004_096L), bytes.copyOfRange(offset, offset + count))
            }
            assertEquals(1, source.activeTickets.get())

            session.stream(16_004_096L, 16_008_192L) { bytes, offset, count ->
                assertEquals(1 + 1, source.activeTickets.get())
                assertContentEquals(source.expected(16_004_096L, 16_008_192L), bytes.copyOfRange(offset, offset + count))
            }
            assertEquals(1, source.activeTickets.get())

            finishFirst.complete(Unit)
            first.await()
            assertEquals(0, source.activeTickets.get())
            assertEquals(0, source.concurrencyViolations.get())
        } finally {
            finishFirst.complete(Unit)
            session.close()
        }
    }

    @Test
    fun closedSessionIsGone() = runBlocking {
        val source = FakeSource(size = 1000)
        val stream = proxy.register(source, null)
        stream.close()
        assertEquals(404, request(stream.url).status)
        assertTrue(source.closed)
    }

    private class RecordedTicket(
        val owner: TransferDemand,
        val startOffset: Long,
        val endExclusive: Long,
    ) {
        @Volatile
        var endReason: ReadEndReason? = null

        val allowFinishInFlightOnEnd: Boolean?
            get() = endReason?.allowsFinishInFlight
    }

    private class FakeSource(
        override val size: Long,
        private val readDelayMillis: Long = 0,
    ) : ProxyByteSource {
        val concurrencyViolations = AtomicInteger(0)
        val activeTickets = AtomicInteger(0)
        val activeReads = AtomicInteger(0)
        val prefetchCalls = AtomicInteger(0)
        val ticketHistory = CopyOnWriteArrayList<RecordedTicket>()

        @Volatile
        var failReads = false

        @Volatile
        var onTicketRead: (() -> Unit)? = null

        @Volatile
        var closed = false

        fun expected(start: Long, endExclusive: Long) =
            ByteArray((endExclusive - start).toInt()) { byteAt(start + it) }

        override suspend fun prefetch(ranges: List<LongRange>, role: StreamRole, priority: Int?): Deferred<Unit> {
            prefetchCalls.incrementAndGet()
            return CompletableDeferred(Unit)
        }

        override fun beginRead(owner: TransferDemand, startOffset: Long, endExclusive: Long): ReadTicket {
            val clampedStart = startOffset.coerceIn(0L, size)
            val clampedEnd = endExclusive.coerceIn(clampedStart, size)
            val record = RecordedTicket(owner, clampedStart, clampedEnd)
            ticketHistory.add(record)
            activeTickets.incrementAndGet()
            return object : ReadTicket {
                private val ownReads = AtomicInteger(0)
                private val ended = AtomicInteger(0)

                override val owner: TransferDemand = owner
                override val epoch: Long = owner.intent.epoch
                override val startOffset: Long = clampedStart
                override val endExclusive: Long = clampedEnd

                @Volatile
                override var position: Long = clampedStart
                    private set

                override suspend fun readSome(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (ended.get() != 0 || owner.isClosed) throw CancellationException("ticket closed")
                    if (ownReads.incrementAndGet() > 1) concurrencyViolations.incrementAndGet()
                    activeReads.incrementAndGet()
                    try {
                        onTicketRead?.invoke()
                        if (readDelayMillis > 0) delay(readDelayMillis)
                        if (ended.get() != 0 || owner.isClosed) throw CancellationException("ticket closed")
                        if (failReads) throw IllegalStateException("simulated upstream read error")
                        val remaining = endExclusive - position
                        if (remaining <= 0L) return -1
                        if (length == 0) return 0
                        val count = minOf(length.toLong(), remaining, 64L * 1024L).toInt()
                        for (i in 0 until count) buffer[offset + i] = byteAt(position + i)
                        position += count
                        return count
                    } finally {
                        activeReads.decrementAndGet()
                        ownReads.decrementAndGet()
                    }
                }

                override fun endRead(reason: ReadEndReason) {
                    if (ended.compareAndSet(0, 1)) {
                        record.endReason = reason
                        activeTickets.decrementAndGet()
                    }
                }
            }
        }

        override suspend fun openReader(): ProxyReader = object : ProxyReader {
            private val ownReads = AtomicInteger(0)
            override var position = 0L
                private set

            override suspend fun seekTo(position: Long) {
                this.position = position
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (ownReads.incrementAndGet() > 1) concurrencyViolations.incrementAndGet()
                activeReads.incrementAndGet()
                try {
                    if (readDelayMillis > 0) delay(readDelayMillis)
                    if (failReads) throw IllegalStateException("simulated upstream read error")
                    if (position >= size) return -1
                    val count = minOf(length.toLong(), size - position, 64L * 1024L).toInt()
                    for (i in 0 until count) buffer[offset + i] = byteAt(position + i)
                    position += count
                    return count
                } finally {
                    activeReads.decrementAndGet()
                    ownReads.decrementAndGet()
                }
            }

            override fun close() = Unit
        }

        override fun close() {
            closed = true
        }

        private fun byteAt(position: Long): Byte = (position % 251).toByte()
    }
}
