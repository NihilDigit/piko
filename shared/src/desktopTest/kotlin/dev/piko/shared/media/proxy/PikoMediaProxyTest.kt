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
 * 数据源是一个会检测并发调用的假 reader：SDK 的 reader 被并发调用时不会报错，只会
 * 悄悄交出错位的字节，所以独占性只能在这里抓。
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

    @Test
    fun newRequestAbortsTheOverlappingOneAndReusesTheReader() = runBlocking {
        // 读得慢，保证第一个请求在第二个到来时还在读
        val source = FakeSource(size = 16 * 1024 * 1024, readDelayMillis = 5)
        val url = proxy.register(source, "movie.mp4").url

        // 播放器拖动：旧连接读了一点就不再读，但也不关
        val stale = openRequest(url, range = "bytes=0-")
        stale.body.readNBytes(100_000)

        val seek = withTimeout(10_000) { request(url, range = "bytes=8000000-8099999") }
        assertEquals(206, seek.status)
        assertContentEquals(source.expected(8_000_000, 8_100_000), seek.body)

        // 旧响应被截断：连接在 Content-Length 之前就被关掉
        val rest = withTimeout(10_000) { stale.body.readAllBytes() }
        assertTrue(100_000 + rest.size < 16 * 1024 * 1024, "旧请求应被中止")
        stale.socket.close()

        assertEquals(0, source.concurrencyViolations.get(), "reader 被并发调用")
        assertEquals(1, source.openedReaders.get(), "中止不应让 reader 重建")
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

    @Test
    fun replacesAFailedReaderWithoutBreakingTheResponse() = runBlocking {
        val source = FakeSource(size = 2_000_000, failFirstReaderAt = 700_000)
        val url = proxy.register(source, "movie.mp4").url

        val response = request(url, range = "bytes=0-")
        assertContentEquals(source.expected(0, 2_000_000), response.body)
        assertEquals(2, source.openedReaders.get())
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
     * 内容可预测的来源。reader 在调用重叠时记一次违规，模拟 SDK reader 的单游标约束。
     */
    private class FakeSource(
        override val size: Long,
        private val readDelayMillis: Long = 0,
        private val failFirstReaderAt: Long? = null,
    ) : ProxyByteSource {
        val concurrencyViolations = AtomicInteger(0)
        val activeReads = AtomicInteger(0)
        val openedReaders = AtomicInteger(0)

        @Volatile
        var closed = false

        fun expected(start: Long, endExclusive: Long) =
            ByteArray((endExclusive - start).toInt()) { byteAt(start + it) }

        override suspend fun openReader(): ProxyReader {
            val index = openedReaders.incrementAndGet()
            return FakeReader(failAt = failFirstReaderAt.takeIf { index == 1 })
        }

        override fun close() {
            closed = true
        }

        private inner class FakeReader(private val failAt: Long?) : ProxyReader {
            override var position = 0L
                private set

            override suspend fun seekTo(position: Long) {
                this.position = position
            }

            override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (activeReads.incrementAndGet() > 1) concurrencyViolations.incrementAndGet()
                try {
                    if (readDelayMillis > 0) delay(readDelayMillis)
                    if (position >= size) return -1
                    if (failAt != null && position >= failAt) error("模拟的块失败")
                    val count = minOf(length.toLong(), size - position, 64L * 1024).toInt()
                    for (i in 0 until count) buffer[offset + i] = byteAt(position + i)
                    position += count
                    return count
                } finally {
                    activeReads.decrementAndGet()
                }
            }

            override fun close() = Unit
        }

        private fun byteAt(position: Long): Byte = (position % 251).toByte()
    }
}
