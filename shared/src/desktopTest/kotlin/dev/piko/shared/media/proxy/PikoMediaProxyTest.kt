package dev.piko.shared.media.proxy

import dev.piko.shared.media.testing.openRequest
import dev.piko.shared.media.testing.request
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

        /** 所有 reader 上正在进行的读。 */
        val activeReads = AtomicInteger(0)

        @Volatile
        var closed = false

        fun expected(start: Long, endExclusive: Long) =
            ByteArray((endExclusive - start).toInt()) { byteAt(start + it) }

        override suspend fun openReader(): ProxyReader = FakeReader()

        override fun close() {
            closed = true
        }

        private inner class FakeReader : ProxyReader {
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

            override fun close() = Unit
        }

        private fun byteAt(position: Long): Byte = (position % 251).toByte()
    }
}
