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

class PikPakPlaybackProxySmokeTest {

    @Test
    fun playerAccessPatternSurvivesLinkExpiryAndSlowBlocks() = runBlocking {
        val payload = FakePikPakCloud.payload(6 * 1024 * 1024)
        val cloud = FakePikPakCloud(origin = payload, blockDelayMillis = 20, expireAfterRequests = 6)
        val repository = PikoMediaRepository(cloud.provider)

        val playback = repository.preparePlayback("f1").getOrThrow()
        val url = assertNotNull(playback.proxyUrl)
        try {
            withTimeout(60_000) {
                val head = openRequest(url, range = "bytes=0-")
                assertEquals(206, head.status)
                assertContentEquals(payload.copyOfRange(0, 300_000), head.body.readNBytes(300_000))

                // Concurrent tail range read while the head stream remains open (MP4 trailing moov probe).
                val tail = request(url, range = "bytes=${payload.size - 100_000}-")
                assertContentEquals(payload.copyOfRange(payload.size - 100_000, payload.size), tail.body)
                head.socket.close()

                val middle = openRequest(url, range = "bytes=3000000-")
                assertContentEquals(payload.copyOfRange(3_000_000, 3_500_000), middle.body.readNBytes(500_000))
                middle.socket.close()

                val whole = request(url, range = "bytes=0-")
                assertContentEquals(payload, whole.body)
            }
            assertTrue(cloud.expiredResponses.get() > 0)
            assertTrue(cloud.detailCalls.get() > 1)
        } finally {
            playback.close()
        }
        assertEquals(404, request(url).status)
    }

    @Test
    fun transcodeVariantIsServedWithItsProbedSize() = runBlocking {
        val origin = FakePikPakCloud.payload(2 * 1024 * 1024)
        val transcode = FakePikPakCloud.payload(900_000).also { it.reverse() }
        val cloud = FakePikPakCloud(origin = origin, transcode = transcode)
        val repository = PikoMediaRepository(cloud.provider)

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

    @Test
    fun clipSliceServesBoundedMultiExtendWindowAndPrefetchesHeadAndTail() = runBlocking {
        val origin = FakePikPakCloud.payload(4 * 1024 * 1024)
        // TS 流长度是 188 的整数倍：188 * 20_000 = 3_760_000 字节，在 600 秒时长下每 30 秒约 188_000 字节；
        // 在起点处嵌入真实带完整 PAT + PMT + HEVC VPS/SPS/PPS + IDR 的安全接入点，验证立即命中且不整条拉取。
        val transcode = FakePikPakCloud.payload(188 * 20_000)
        val realSample = generateSequence(java.io.File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .map { it.resolve("testdata/media/transcode-hevc-aac.ts") }
            .first { it.isFile }
            .readBytes()
        val expectedOffset = (transcode.size.toDouble() / 600_000.0 * 120_000L).toLong() / 188L * 188L
        val sampleHeadBytes = minOf(realSample.size / 188 * 188, 188 * 120)
        realSample.copyInto(transcode, destinationOffset = 0, startIndex = 0, endIndex = sampleHeadBytes)
        realSample.copyInto(transcode, destinationOffset = expectedOffset.toInt(), startIndex = 0, endIndex = sampleHeadBytes)
        val scanner = dev.piko.shared.media.TsTimestamps.VerifiedSliceScanner()
        val verifiedInSample = assertNotNull(scanner.feed(realSample, 0L) ?: scanner.finish())
        val actualSliceOffset = expectedOffset + verifiedInSample.sliceStartOffset
        val cloud = FakePikPakCloud(origin = origin, transcode = transcode, transcodeName = "720P")
        val repository = PikoMediaRepository(cloud.provider)

        val clip = repository.prepareClip("f1", startMs = 120_000L, videoDurationMs = 600_000L, role = io.github.nihildigit.pikpak.StreamRole.FOREGROUND).getOrThrow()
        try {
            assertTrue(clip.sliced)
            val url = assertNotNull(clip.url)
            clip.prefetch(io.github.nihildigit.pikpak.StreamRole.FOREGROUND)
            // 预取只取流首 PTS 校准块（512 KiB）+ 有界切片开头（768 KiB）+ 切片末尾（768 KiB）共约 2 MiB，不拉取整条流
            assertTrue(cloud.deliveredCdnBytes.get() <= 2_200_000L, "delivered=${cloud.deliveredCdnBytes.get()}")
            // 有界窗口覆盖 180s 多轮续播，且小于整条剩余转码流，避免暴露长流尾部引发无索引 TS 二分查找失败
            val fullRemaining = transcode.size - actualSliceOffset
            val sliceBytes = assertNotNull(clip.streamBytes)
            assertTrue(sliceBytes in 1_000_000L until fullRemaining, "sliceBytes=$sliceBytes, fullRemaining=$fullRemaining")
            val tailRead = withTimeout(30_000) { request(url, range = "bytes=${sliceBytes - 1024}-") }
            assertEquals(206, tailRead.status)
            assertContentEquals(
                transcode.copyOfRange((actualSliceOffset + sliceBytes - 1024).toInt(), (actualSliceOffset + sliceBytes).toInt()),
                tailRead.body,
            )
        } finally {
            clip.close()
        }
    }

    @Test
    fun parameterlessTsStreamRefusesRawCutAndFallsBackToFullStream() = runBlocking {
        val origin = FakePikPakCloud.payload(4 * 1024 * 1024)
        // 长度虽为 188 整数倍，但不含任何合法 PAT/PMT 与视频参数集：严禁在估算偏移半路裸切导致硬解崩溃回退软解
        val parameterlessTs = FakePikPakCloud.payload(188 * 6_000)
        val cloud = FakePikPakCloud(origin = origin, transcode = parameterlessTs, transcodeName = "720P")
        val repository = PikoMediaRepository(cloud.provider)

        val clip = repository.prepareClip("f1", startMs = 120_000L, videoDurationMs = 600_000L, role = io.github.nihildigit.pikpak.StreamRole.FOREGROUND).getOrThrow()
        try {
            assertEquals(false, clip.sliced)
            assertEquals(120_000L, clip.startOnPlayer)
        } finally {
            clip.close()
        }
    }

    @Test
    fun clipDetailsAreReusedWithinClientAndRefreshedAfterClientReconstruction() = runBlocking {
        val first = FakePikPakCloud(ByteArray(1024) { 1 })
        val second = FakePikPakCloud(ByteArray(2048) { 2 })
        val provider = object : dev.piko.shared.data.PikoClientProvider {
            override val currentClient = kotlinx.coroutines.flow.MutableStateFlow<io.github.nihildigit.pikpak.PikPakClient?>(first.provider.currentClient.value)
        }
        val media = PikoMediaRepository(provider)
        media.clipProbe("f1")
        assertEquals(1, first.detailCalls.get())
        media.prepareClip("f1", 0, 600_000, io.github.nihildigit.pikpak.StreamRole.FOREGROUND).getOrThrow().use { clip ->
            assertEquals(1024L, clip.streamBytes)
        }
        assertEquals(1, first.detailCalls.get())
        media.clipProbe("f1")
        assertEquals(2, first.detailCalls.get())
        provider.currentClient.value = second.provider.currentClient.value
        assertEquals(first.provider.currentClient.value!!.account, provider.currentClient.value!!.account)
        media.prepareClip("f1", 0, 600_000, io.github.nihildigit.pikpak.StreamRole.FOREGROUND).getOrThrow().use { clip ->
            assertEquals(2048L, clip.streamBytes)
            assertContentEquals(second.origin, request(assertNotNull(clip.url), range = "bytes=0-").body)
        }
        assertEquals(1, second.detailCalls.get())
    }
}
