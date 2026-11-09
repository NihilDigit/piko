package dev.piko.shared.media.proxy

import dev.piko.shared.media.testing.openRequest
import dev.piko.shared.media.testing.request
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 代理协议层的冒烟：起真实的回环代理，用原始 socket 发请求。
 *
 * 数据源的 reader 会检测自己被并发调用：SDK 的 reader 一个读位置一个调用方，被并发调用时不会报错，
 * 只会悄悄交出错位的字节，所以每个请求用各自的 reader 这一点只能在这里抓。
 */
class PikoMediaProxyTest {
    private val proxy = PikoMediaProxy()

    @AfterTest
    fun tearDown() = proxy.close()

    @Test
    fun urgentClipUsesItsExplicitWindowAfterSequentialReadingIsEstablished() = runBlocking {
        val source = FakeSource(8L * 1024 * 1024)
        val session = ProxySession(source)
        try {
            session.readAheadLimit = 2L * 1024 * 1024
            session.urgent = true
            session.stream(0, 1024 * 1024) { _, _, _ -> }
            assertEquals(0L, source.aheadAtRead.first(), "initial container probe stays bounded")
            assertTrue(source.aheadAtRead.any { it == 2L * 1024 * 1024 }, "startup must use the requested window before the first frame")
            source.aheadAtRead.clear()
            session.readAheadLimit = 0L
            session.stream(2L * 1024 * 1024, 3L * 1024 * 1024) { _, _, _ -> }
            assertTrue(source.aheadAtRead.all { it == 0L }, "paused neighbours keep their explicit hold")
        } finally { session.close() }
    }

    @Test
    fun probingAndSeekingHaveNoSpeculativeWindowOrImplicitTailFetch() = runBlocking {
        val source = FakeSource(32L * 1024 * 1024)
        val session = ProxySession(source)
        try {
            session.stream(0, 1024) { _, _, _ -> }
            session.stream(16L * 1024 * 1024, 16L * 1024 * 1024 + 1024) { _, _, _ -> }
            assertTrue(source.aheadAtRead.all { it == 0L })
            assertEquals(0, source.prefetchCalls.get())
            assertEquals(0, source.demandActiveReaders.get())
            session.steady = true
            session.stream(4096, 8192) { _, _, _ -> }
            assertTrue(source.aheadAtRead.last()!! > 0)
            session.urgent = true
            session.stream(0, 1024) { _, _, _ -> }
            assertEquals(0L, source.aheadAtRead.last())
        } finally { session.close() }
    }

    @Test
    fun withdrawnTransportKeepsItsUrlUntilInputHasStopped() = runBlocking {
        val source = FakeSource(1000)
        val stream = proxy.register(source, null)
        stream.withdrawTransport()
        assertEquals(200, request(stream.url, "HEAD").status)
        assertEquals(503, request(stream.url).status)
        assertTrue(!source.closed)
        stream.close()
        assertEquals(404, request(stream.url).status)
        assertTrue(source.closed)
    }

    @Test
    fun reusedAndSlicedReadersKeepTheOriginalFeedbackTimestamp() = runBlocking {
        val source = FakeSource(1_000_000)
        val session = ProxySession(SlicedByteSource(source, 100_000, 200_000))
        val clock = kotlin.time.TestTimeSource()
        val snapshot = io.github.nihildigit.pikpak.PlaybackBufferSnapshot(4,
            kotlin.time.Duration.parse("2s"), clock.markNow())
        try {
            session.reportPlaybackBuffer(snapshot)
            clock += kotlin.time.Duration.parse("1s")
            session.stream(0, 1024) { _, _, _ -> }
            session.stream(1024, 2048) { _, _, _ -> }
            assertTrue(source.feedback.all { it === snapshot })
            assertEquals(kotlin.time.Duration.parse("1s"), source.feedback.last().sampledAt.elapsedNow())
        } finally { session.close() }
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
    }

    // 交错得不好的 MP4 一直在两处来回读，一个请求掐断另一个，两边都读不完
    @Test
    fun overlappingRequestsAreServedSideBySide() = runBlocking {
        // 读得慢，保证第一个请求在第二个到来时还在读
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

        assertEquals(0, source.concurrencyViolations.get(), "one reader was used by two requests")
    }

    @Test
    fun clientDisconnectReleasesTheReaderForTheNextRequest() = runBlocking {
        val source = FakeSource(size = 16 * 1024 * 1024, readDelayMillis = 5)
        val url = proxy.register(source, "movie.mp4").url

        val dropped = openRequest(url, range = "bytes=0-")
        dropped.body.readNBytes(50_000)
        dropped.socket.close()
        // 断开之后读端的看门协程应当取消响应，reader 上不再有人读
        withTimeout(5_000) {
            while (source.activeReads.get() > 0) delay(10)
        }

        val next = request(url, range = "bytes=100-199")
        assertContentEquals(source.expected(100, 200), next.body)
        assertEquals(0, source.concurrencyViolations.get())
    }

    // 错一个偏移，播放器拿到的是另一处的字节，TS 照样能解，只是画面不在该在的时刻
    @Test
    fun aSliceServesItsWindowAsAWholeFile() = runBlocking {
        val source = FakeSource(size = 1_000_000)
        val url = proxy.register(SlicedByteSource(source, offset = 300_000, size = 200_000), "clip.ts").url

        val whole = request(url)
        assertEquals("200000", whole.headers["content-length"])
        assertContentEquals(source.expected(300_000, 500_000), whole.body)

        val tail = request(url, range = "bytes=199000-")
        assertEquals("bytes 199000-199999/200000", tail.headers["content-range"])
        assertContentEquals(source.expected(499_000, 500_000), tail.body)
    }

    @Test
    fun sequentialAndInterleavedShortRangesReusePooledReadersWithoutExtraSeeks() = runBlocking {
        val source = FakeSource(size = 32 * 1024 * 1024)
        val session = ProxySession(source)
        try {
            // 1. 首尾探测后直接跳往正片中段时，直接复用探测游标，不残留废弃的文件头游标
            session.stream(0L, 4096L) { _, _, _ -> }
            session.stream(1_000_000L, 1_004_096L) { _, _, _ -> }
            assertEquals(2, source.openedReaders.get())
            assertEquals(1, source.seekCalls.get())

            // 2. 音视频两轨（1 MB 与 16 MB）交替发短 Range 请求时，各自保有独立轨道游标，连续接续不触发来回 seek
            val ranges = listOf(
                1_004_096L to 1_008_192L,
                16_000_000L to 16_004_096L,
                1_008_192L to 1_012_288L,
                16_004_096L to 16_008_192L,
            )
            ranges.forEach { (start, end) ->
                session.stream(start, end) { bytes, offset, count ->
                    assertEquals(1, source.demandActiveReaders.get())
                    assertContentEquals(source.expected(start, start + count), bytes.copyOfRange(offset, offset + count))
                }
                assertEquals(0, source.demandActiveReaders.get(), "无作用域会话归还游标后应立即撤回需求")
            }
            assertEquals(3, source.openedReaders.get())
            assertEquals(2, source.seekCalls.get())
            assertEquals(0, source.concurrencyViolations.get())
        } finally {
            session.close()
        }
    }

    @Test
    fun slicedReaderForwardsDemandActivationAndRelease() = runBlocking {
        val source = FakeSource(size = 1_000_000)
        val session = ProxySession(SlicedByteSource(source, offset = 300_000, size = 200_000))
        try {
            session.stream(0L, 4096L) { bytes, offset, count ->
                assertEquals(1, source.demandActiveReaders.get())
                assertContentEquals(source.expected(300_000L, 304_096L), bytes.copyOfRange(offset, offset + count))
            }
            assertEquals(0, source.demandActiveReaders.get())
        } finally {
            session.close()
        }
    }

    @Test
    fun overlappingDistantRequestsUseIndependentReadersAndResumeNearbyWithoutSeek() = runBlocking {
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
                assertEquals(2, source.demandActiveReaders.get())
                assertContentEquals(source.expected(16_000_000L, 16_004_096L), bytes.copyOfRange(offset, offset + count))
            }
            assertEquals(1, source.demandActiveReaders.get())
            // 第二轨仍可复用自己的连续位置，不 seek，也不新增 reader。
            session.stream(16_004_096L, 16_008_192L) { _, _, _ -> }
            assertEquals(2, source.openedReaders.get())
            assertEquals(2, source.seekCalls.get())
            finishFirst.complete(Unit)
            first.await()
            assertEquals(0, source.demandActiveReaders.get())
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

    /**
     * 内容可预测的来源。每个 reader 在自己的调用重叠时记一次违规，模拟 SDK reader 的单调用方约束。
     */
    private class FakeSource(
        override val size: Long,
        private val readDelayMillis: Long = 0,
    ) : ProxyByteSource {
        val concurrencyViolations = AtomicInteger(0)
        val openedReaders = AtomicInteger(0)
        val seekCalls = AtomicInteger(0)
        val demandActiveReaders = AtomicInteger(0)
        val aheadAtRead = java.util.concurrent.CopyOnWriteArrayList<Long?>()
        val feedback = java.util.concurrent.CopyOnWriteArrayList<io.github.nihildigit.pikpak.PlaybackBufferSnapshot>()
        val prefetchCalls = AtomicInteger()

        override suspend fun prefetch(ranges: List<LongRange>, role: io.github.nihildigit.pikpak.StreamRole, priority: Int?): kotlinx.coroutines.Deferred<Unit> {
            prefetchCalls.incrementAndGet()
            return CompletableDeferred(Unit)
        }

        /** 所有 reader 上正在进行的读。 */
        val activeReads = AtomicInteger(0)

        @Volatile
        var closed = false

        fun expected(start: Long, endExclusive: Long) =
            ByteArray((endExclusive - start).toInt()) { byteAt(start + it) }

        override suspend fun openReader(): ProxyReader {
            openedReaders.incrementAndGet()
            return FakeReader()
        }

        override fun close() {
            closed = true
        }

        private inner class FakeReader : ProxyReader {
            override var readAheadLimit: Long? = null
            override fun reportPlaybackBuffer(snapshot: io.github.nihildigit.pikpak.PlaybackBufferSnapshot) { feedback.add(snapshot) }
            private val ownReads = AtomicInteger(0)
            private var demandActive = false

            override fun setDemandActive(active: Boolean) {
                if (demandActive == active) return
                demandActive = active
                demandActiveReaders.addAndGet(if (active) 1 else -1)
            }

            override var position = 0L
                private set

            override suspend fun seekTo(position: Long) {
                seekCalls.incrementAndGet()
                this.position = position
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                aheadAtRead.add(readAheadLimit)
                if (ownReads.incrementAndGet() > 1) concurrencyViolations.incrementAndGet()
                activeReads.incrementAndGet()
                try {
                    if (readDelayMillis > 0) delay(readDelayMillis)
                    if (position >= size) return -1
                    val count = minOf(length.toLong(), size - position, 64L * 1024).toInt()
                    for (i in 0 until count) buffer[offset + i] = byteAt(position + i)
                    position += count
                    return count
                } finally {
                    activeReads.decrementAndGet()
                    ownReads.decrementAndGet()
                }
            }

            override fun close() = setDemandActive(false)
        }

        private fun byteAt(position: Long): Byte = (position % 251).toByte()
    }
}
