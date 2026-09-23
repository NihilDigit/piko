package dev.piko.shared.media

import dev.piko.shared.media.testing.FakePikPakCloud
import dev.piko.shared.media.testing.openRequest
import dev.piko.shared.media.testing.request
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 从 preparePlayback 到代理 URL 的整条链路，底下是真实的 SDK handle 与 reader，
 * 只有 PikPak 的服务端是模拟的。访问方式照播放器来：从头读、跳到文件尾读索引、
 * 旧连接半途丢下再跳到中段，最后整段读一遍对字节。
 */
class PikPakPlaybackProxySmokeTest {

    @Test
    fun playerAccessPatternSurvivesLinkExpiryAndSlowBlocks() = runBlocking {
        val payload = FakePikPakCloud.payload(6 * 1024 * 1024)
        // 每代直链只服务 6 次请求就过期，整段读下来一定会跨过几次过期
        val cloud = FakePikPakCloud(origin = payload, blockDelayMillis = 20, expireAfterRequests = 6)
        val repository = PikoMediaRepository(cloud.provider)

        val playback = repository.preparePlayback("f1").getOrThrow()
        val url = assertNotNull(playback.proxyUrl, "有 gcid 的原画应当走代理")
        try {
            withTimeout(60_000) {
                val head = openRequest(url, range = "bytes=0-")
                assertEquals(206, head.status)
                assertContentEquals(payload.copyOfRange(0, 300_000), head.body.readNBytes(300_000))

                // MP4 的 moov 在文件尾时，FFmpeg 先开一个新连接读尾部，旧连接还挂着
                val tail = request(url, range = "bytes=${payload.size - 100_000}-")
                assertContentEquals(payload.copyOfRange(payload.size - 100_000, payload.size), tail.body)
                head.socket.close()

                val middle = openRequest(url, range = "bytes=3000000-")
                assertContentEquals(payload.copyOfRange(3_000_000, 3_500_000), middle.body.readNBytes(500_000))
                middle.socket.close()

                val whole = request(url, range = "bytes=0-")
                assertContentEquals(payload, whole.body, "跨过直链过期后字节应当不错位")
            }
            assertTrue(cloud.expiredResponses.get() > 0, "测试没有真正触发直链过期")
            assertTrue(cloud.detailCalls.get() > 1, "过期后应当重取详情换新链接")
        } finally {
            playback.close()
        }
        assertEquals(404, request(url).status, "关闭后会话应当释放")
    }

    @Test
    fun transcodeVariantIsServedWithItsProbedSize() = runBlocking {
        val origin = FakePikPakCloud.payload(2 * 1024 * 1024)
        val transcode = FakePikPakCloud.payload(900_000).also { it.reverse() }
        val cloud = FakePikPakCloud(origin = origin, transcode = transcode)
        val repository = PikoMediaRepository(cloud.provider)

        // 转码流的长度不在元数据里；把原画大小当成转码流大小，Content-Range 与实际字节就对不上
        val playback = repository.preparePlayback("f1", preferredResolution = "480P").getOrThrow()
        try {
            val url = assertNotNull(playback.proxyUrl)
            assertEquals(false, playback.info.isOrigin)
            val whole = withTimeout(30_000) { request(url, range = "bytes=0-") }
            assertEquals("bytes 0-${transcode.size - 1}/${transcode.size}", whole.headers["content-range"])
            assertContentEquals(transcode, whole.body)
        } finally {
            playback.close()
        }
    }
}
