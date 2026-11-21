package dev.piko.shared.media.storyboard

import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.PlayableMediaKind
import io.github.nihildigit.pikpak.DownloadLink
import io.github.nihildigit.pikpak.MediaVariant
import io.github.nihildigit.pikpak.VideoInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryboardPlannerAndCacheTest {

    private fun variant(
        mediaId: String,
        name: String,
        height: Int,
        isOrigin: Boolean = false,
        url: String = "https://cdn.example/$mediaId.ts",
    ): MediaVariant = MediaVariant(
        mediaId = mediaId,
        mediaName = name,
        resolutionName = name,
        video = VideoInfo(height = height, width = (height * 16) / 9, duration = 600L),
        link = DownloadLink(url = url),
        isOrigin = isOrigin,
    )

    private fun mediaInfo(variants: List<MediaVariant>): PlayableMediaInfo = PlayableMediaInfo(
        fileId = "file-1",
        name = "episode.mkv",
        gcid = "ABCDEF0123456789",
        currentUrl = "https://cdn.example/origin.mkv",
        durationSeconds = 600L,
        currentResolution = "1080",
        availableVariants = variants,
        sizeBytes = 500_000_000L,
        kind = PlayableMediaKind.Video,
        width = 1920,
        height = 1080,
    )

    @Test
    fun strictSourceGatingAllowsOnlyTranscodesAtOrBelow480p() {
        // 1. 纯原画视频 -> 禁止
        val originOnly = mediaInfo(listOf(variant("orig", "Original", 1080, isOrigin = true)))
        assertNull(StoryboardSourceResolver.resolve(isLocalPlayback = false, mediaInfo = originOnly, durationMillis = 600_000L))

        // 2. 最低转码为 720P 或 1080P -> 严格禁止
        val hdOnly = mediaInfo(
            listOf(
                variant("orig", "Original", 2160, isOrigin = true),
                variant("m1080", "1080P", 1080),
                variant("m720", "720P", 720),
            ),
        )
        assertNull(StoryboardSourceResolver.resolve(isLocalPlayback = false, mediaInfo = hdOnly, durationMillis = 600_000L))

        // 3. 本地文件 -> 禁止
        val with480 = mediaInfo(
            listOf(
                variant("orig", "Original", 1080, isOrigin = true),
                variant("m720", "720P", 720),
                variant("m480", "480P", 480),
                variant("m360", "360P", 360),
            ),
        )
        assertNull(StoryboardSourceResolver.resolve(isLocalPlayback = true, mediaInfo = with480, durationMillis = 600_000L))

        // 4. 存在 480P 与 360P -> 优选最低可用 <= 480P（360P）
        val resolved = StoryboardSourceResolver.resolve(isLocalPlayback = false, mediaInfo = with480, durationMillis = 600_000L)
        assertNotNull(resolved)
        assertEquals("m360", resolved.mediaId)
        assertEquals(360, resolved.height)

        // 5. 若 360P 标记为不可读，自动回落至 480P；若两者均不可读则禁用
        val fallback480 = StoryboardSourceResolver.resolve(
            isLocalPlayback = false,
            mediaInfo = with480,
            durationMillis = 600_000L,
            unreadableMediaIds = setOf("m360"),
        )
        assertEquals("m480", fallback480?.mediaId)
        assertNull(
            StoryboardSourceResolver.resolve(
                isLocalPlayback = false,
                mediaInfo = with480,
                durationMillis = 600_000L,
                unreadableMediaIds = setOf("m360", "m480"),
            ),
        )
    }

    @Test
    fun plannerComputesAdaptiveSlotCountsWavesAndCursorPriority() {
        // <= 10min (~5s step), clamped 48..180
        assertEquals(120, StoryboardPlanner.slotCountForDuration(600_000L))
        assertEquals(48, StoryboardPlanner.slotCountForDuration(120_000L))
        // 10–30min (~12s step)
        assertEquals(100, StoryboardPlanner.slotCountForDuration(1_200_000L))
        // 30–90min (~25s step)
        assertEquals(144, StoryboardPlanner.slotCountForDuration(3_600_000L))
        // > 90min (~35s step, clamped <= 180)
        assertEquals(180, StoryboardPlanner.slotCountForDuration(9_000_000L))

        val streamSize = 188L * 50_000L
        val slots = StoryboardPlanner.buildSlots(durationMillis = 1_200_000L, streamSizeBytes = streamSize)
        assertEquals(100, slots.size)
        for (slot in slots) {
            assertEquals(0L, slot.byteOffset % StoryboardPlanner.TS_PACKET_BYTES)
            assertTrue(slot.byteOffset < streamSize)
        }

        // Wave 1: 12..16 evenly-spaced anchors covering 0 and lastIndex
        val wave1 = StoryboardPlanner.wave1Indices(slots.size)
        assertTrue(wave1.size in StoryboardPlanner.WAVE1_MIN_ANCHORS..StoryboardPlanner.WAVE1_MAX_ANCHORS)
        assertEquals(0, wave1.first())
        assertEquals(slots.lastIndex, wave1.last())

        // Wave 2: exact complement of Wave 1
        val wave2 = StoryboardPlanner.wave2Indices(slots.size, wave1)
        assertEquals(slots.size - wave1.size, wave2.size)
        assertEquals((0 until slots.size).toSet(), (wave1 + wave2).toSet())

        // Cursor-priority ordering around 600_000ms (slot 50)
        val prioritized = StoryboardPlanner.prioritizeAround(slots, targetMs = 600_000L)
        assertEquals(50, prioritized.first().slotIndex)
        assertTrue(prioritized[1].slotIndex in setOf(49, 51))
    }

    @Test
    fun timelineBinarySearchMapsRealPtsIntervalsAccurately() {
        val frames = listOf(
            StoryboardFrame(0, ptsMs = 1_200L, jpegBytes = byteArrayOf(1)),
            StoryboardFrame(1, ptsMs = 12_500L, jpegBytes = byteArrayOf(2)),
            StoryboardFrame(2, ptsMs = 25_000L, jpegBytes = byteArrayOf(3)),
            StoryboardFrame(3, ptsMs = 40_000L, jpegBytes = byteArrayOf(4)),
        )
        val timeline = StoryboardTimeline(durationMillis = 60_000L, totalSlots = 4, frames = frames, isComplete = true)

        // [0, 12_500) -> frame 0
        assertEquals(0, timeline.findFrameIndex(0L))
        assertEquals(0, timeline.findFrameIndex(1_200L))
        assertEquals(0, timeline.findFrameIndex(12_499L))
        // [12_500, 25_000) -> frame 1
        assertEquals(1, timeline.findFrameIndex(12_500L))
        assertEquals(1, timeline.findFrameIndex(20_000L))
        // [25_000, 40_000) -> frame 2
        assertEquals(2, timeline.findFrameIndex(25_000L))
        assertEquals(2, timeline.findFrameIndex(39_999L))
        // [40_000, 60_000] -> frame 3
        assertEquals(3, timeline.findFrameIndex(40_000L))
        assertEquals(3, timeline.findFrameIndex(60_000L))
    }

    @Test
    fun fileStoryboardCacheRoundTripAndEviction(): Unit = runBlocking {
        val dir = Files.createTempDirectory("piko-sb-cache").toFile()
        try {
            var clock = 5_000_000_000L
            val cache = FileStoryboardCache(dir.absolutePath, nowMillis = { clock })
            val bundle = StoryboardBundle(
                gcid = "abc123def456",
                durationMillis = 900_000L,
                width = 640,
                height = 360,
                completed = true,
                lastAccessEpochMillis = clock,
                frames = listOf(
                    StoryboardFrame(slotIndex = 0, ptsMs = 0L, jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)),
                    StoryboardFrame(slotIndex = 5, ptsMs = 45_000L, jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 3, 4, 5)),
                ),
            )
            cache.write(bundle)
            val restored = cache.read("ABC123DEF456")
            assertNotNull(restored)
            assertEquals("ABC123DEF456", restored.gcid)
            assertEquals(900_000L, restored.durationMillis)
            assertEquals(640, restored.width)
            assertEquals(360, restored.height)
            assertTrue(restored.completed)
            assertEquals(2, restored.frames.size)
            assertEquals(45_000L, restored.frames[1].ptsMs)
            assertContentEquals(bundle.frames[1].jpegBytes, restored.frames[1].jpegBytes)

            // 写第二个较新的包并验证 TTL 清理只删老包
            clock += 20L * 86_400_000L
            val newer = bundle.copy(gcid = "NEWER999", lastAccessEpochMillis = clock)
            cache.write(newer)

            val freed = cache.prune(maxAgeMillis = 14L * 86_400_000L, targetMaxBytes = -1L)
            assertTrue(freed > 0L)
            assertNull(cache.read("ABC123DEF456"))
            assertNotNull(cache.read("NEWER999"))

            // 确认 384x216 默认解码规格与 .sb2 落盘格式，并验证旧版 .sb1 文件在 prune() 时被自动回收
            assertEquals(384, StoryboardIdrDecoder.DEFAULT_MAX_WIDTH_PX)
            assertTrue(StoryboardIdrDecoder.DEFAULT_JPEG_QUALITY in 78..82)
            assertTrue(dir.resolve("NEWER999.sb2").isFile)
            val legacySb1 = dir.resolve("LEGACY_OLD.sb1").apply { writeBytes(ByteArray(256) { 7 }) }
            val freedLegacy = cache.prune(maxAgeMillis = 0L, targetMaxBytes = -1L)
            assertTrue(freedLegacy >= 256L)
            assertFalse(legacySb1.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun engineServesDiskCacheHitWithZeroNetworkAndSupportsUserBoostOverride() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = InMemoryStoryboardCache()
            val gcid = "CACHED_VIDEO_GCID"
            cache.write(
                StoryboardBundle(
                    gcid = gcid,
                    durationMillis = 120_000L,
                    width = 640,
                    height = 360,
                    completed = true,
                    lastAccessEpochMillis = 1_000L,
                    frames = listOf(
                        StoryboardFrame(0, 0L, byteArrayOf(1, 2, 3)),
                        StoryboardFrame(1, 60_000L, byteArrayOf(4, 5, 6)),
                    ),
                ),
            )
            val networkCalls = AtomicInteger(0)
            val decoderClosed = AtomicBoolean(false)
            val gate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = false, isPlaybackBusy = true))
            val source = StoryboardSource(
                fileId = "f1",
                gcid = gcid,
                mediaId = "m480",
                url = "https://cdn.example/480.ts",
                width = 640,
                height = 360,
                durationMillis = 120_000L,
                streamSizeBytes = 188L * 10_000L,
            )
            val engine = StoryboardEngine(
                source = source,
                cache = cache,
                resolveStreamSize = { source.streamSizeBytes },
                fetchRange = { _, _ ->
                    networkCalls.incrementAndGet()
                    null
                },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray? = byteArrayOf(1)
                        override fun close() { decoderClosed.set(true) }
                    }
                },
                playbackGate = gate,
                scope = scope,
            )
            engine.start()
            val timeline = withTimeout(2_000L) {
                engine.timeline.first { it.isComplete && it.frames.size == 2 }
            }
            assertEquals(2, timeline.frames.size)
            assertEquals(0, networkCalls.get(), "Disk cache hit must incur zero network traffic")
            engine.close()

            // Cold miss + Stage (c) boostAround breaks startup wait immediately
            val coldGate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = false, bufferedAheadMs = 0L, isPlaybackBusy = false))
            val coldNetworkCalls = AtomicInteger(0)
            val coldEngine = StoryboardEngine(
                source = source.copy(gcid = "COLD_VIDEO_GCID"),
                cache = cache,
                resolveStreamSize = { source.streamSizeBytes },
                fetchRange = { _, _ ->
                    coldNetworkCalls.incrementAndGet()
                    null
                },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray? = null
                        override fun close() { decoderClosed.set(true) }
                    }
                },
                playbackGate = coldGate,
                scope = scope,
            )
            coldEngine.start()
            delay(350L)
            assertEquals(0, coldNetworkCalls.get(), "Cold miss must wait for first frame + healthy buffer before fetching")
            coldEngine.boostAround(60_000L)
            withTimeout(2_000L) {
                while (coldNetworkCalls.get() == 0) delay(25L)
            }
            assertTrue(coldNetworkCalls.get() >= 1, "User interaction boost must immediately break startup wait")
            coldEngine.close()
            assertTrue(decoderClosed.get(), "Decoder must be closed deterministically")
        } finally {
            scope.cancel()
        }
    }

    /** 构造符合 MPEG-TS / HEVC 语法的合成 TS 流片段（PAT + PMT + 参数集 + IDR/CRA 帧 + 终结 PES）。 */
    private fun tsPacket(
        pid: Int,
        payload: ByteArray,
        payloadStart: Boolean,
        cc: Int,
        randomAccess: Boolean = false,
    ): ByteArray {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (((if (payloadStart) 0x40 else 0) or ((pid ushr 8) and 0x1F))).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        val hasAdapt = randomAccess || payload.size < 184
        if (!hasAdapt) {
            pkt[3] = (0x10 or (cc and 0x0F)).toByte()
            payload.copyInto(pkt, 4, 0, 184)
        } else if (payload.size == 183 && !randomAccess) {
            pkt[3] = (0x30 or (cc and 0x0F)).toByte()
            pkt[4] = 0
            payload.copyInto(pkt, 5, 0, 183)
        } else {
            pkt[3] = (0x30 or (cc and 0x0F)).toByte()
            val maxPayload = minOf(payload.size, 182)
            val adaptLen = 183 - maxPayload
            pkt[4] = adaptLen.toByte()
            if (adaptLen >= 1) {
                pkt[5] = if (randomAccess) 0x40.toByte() else 0x00
            }
            payload.copyInto(pkt, 5 + adaptLen, 0, maxPayload)
        }
        return pkt
    }

    private fun buildPatPacket(pmtPid: Int, cc: Int = 0): ByteArray {
        val section = byteArrayOf(
            0x00, // pointer_field
            0x00, // table_id PAT
            0xB0.toByte(), 13, // section_length = 13 (5 hdr + 4 entry + 4 crc)
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, // program = 1
            (0xE0 or ((pmtPid ushr 8) and 0x1F)).toByte(), (pmtPid and 0xFF).toByte(),
            0, 0, 0, 0,
        )
        return tsPacket(pid = 0, payload = section, payloadStart = true, cc = cc)
    }

    private fun buildPmtPacket(pmtPid: Int, videoPid: Int, cc: Int = 0): ByteArray {
        val section = byteArrayOf(
            0x00, // pointer_field
            0x02, // table_id PMT
            0xB0.toByte(), 18, // section_length = 18 (9 hdr + 5 es + 4 crc)
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0xE1.toByte(), 0x00, // PCR PID
            0xF0.toByte(), 0x00, // program_info_length = 0
            0x24, // STREAM_HEVC
            (0xE0 or ((videoPid ushr 8) and 0x1F)).toByte(), (videoPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00, // es_info_length = 0
            0, 0, 0, 0,
        )
        return tsPacket(pid = pmtPid, payload = section, payloadStart = true, cc = cc)
    }

    private fun encodePts(ticks: Long): ByteArray {
        val t = ticks and ((1L shl 33) - 1L)
        return byteArrayOf(
            (0x21L or ((t ushr 29) and 0x0EL)).toByte(),
            ((t ushr 22) and 0xFFL).toByte(),
            (0x01L or ((t ushr 14) and 0xFEL)).toByte(),
            ((t ushr 7) and 0xFFL).toByte(),
            (0x01L or ((t shl 1) and 0xFEL)).toByte(),
        )
    }

    private fun buildVideoPesPackets(
        videoPid: Int,
        ptsTicks: Long,
        annexBPayload: ByteArray,
        startCc: Int,
        randomAccess: Boolean = true,
        declaredLength: Boolean = false,
    ): Pair<ByteArray, Int> {
        val pts = encodePts(ptsTicks)
        val pesPayloadLen = if (declaredLength) 8 + annexBPayload.size else 0
        val pesHeader = byteArrayOf(
            0x00, 0x00, 0x01, 0xE0.toByte(),
            ((pesPayloadLen ushr 8) and 0xFF).toByte(), (pesPayloadLen and 0xFF).toByte(),
            0x80.toByte(), 0x80.toByte(), 0x05,
            pts[0], pts[1], pts[2], pts[3], pts[4],
        )
        val fullPes = ByteArray(pesHeader.size + annexBPayload.size).also {
            pesHeader.copyInto(it, 0)
            annexBPayload.copyInto(it, pesHeader.size)
        }
        val chunks = ArrayList<ByteArray>()
        var offset = 0
        var cc = startCc
        var first = true
        while (offset < fullPes.size) {
            val capacity = if (first && randomAccess) 182 else 184
            val take = minOf(capacity, fullPes.size - offset)
            val slice = fullPes.copyOfRange(offset, offset + take)
            chunks += tsPacket(
                pid = videoPid,
                payload = slice,
                payloadStart = first,
                cc = cc,
                randomAccess = first && randomAccess,
            )
            cc = (cc + 1) and 0x0F
            offset += take
            first = false
        }
        val out = ByteArray(chunks.size * 188)
        chunks.forEachIndexed { idx, pkt -> pkt.copyInto(out, idx * 188) }
        return out to cc
    }

    private fun hevcNal(nalType: Int, bodySize: Int, fill: Byte = 0x5A): ByteArray {
        val out = ByteArray(4 + 2 + bodySize)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        out[4] = ((nalType and 0x3F) shl 1).toByte()
        out[5] = 0x01 // nuh_temporal_id_plus1 = 1
        for (i in 6 until out.size) out[i] = fill
        return out
    }

    private fun buildSyntheticHeaderChunk(videoPid: Int = 0x0100): ByteArray {
        val pat = buildPatPacket(pmtPid = 0x1000)
        val pmt = buildPmtPacket(pmtPid = 0x1000, videoPid = videoPid)
        val vps = hevcNal(32, 8)
        val sps = hevcNal(33, 12)
        val pps = hevcNal(34, 8)
        val idr = hevcNal(19, 1200)
        val firstAu = vps + sps + pps + idr
        val (pes0, cc1) = buildVideoPesPackets(videoPid, ptsTicks = 90_000L, annexBPayload = firstAu, startCc = 0)
        val (terminator, _) = buildVideoPesPackets(videoPid, ptsTicks = 93_600L, annexBPayload = hevcNal(1, 32), startCc = cc1, randomAccess = false)
        val raw = pat + pmt + pes0 + terminator
        return ByteArray(64 * 1024).also { dst ->
            raw.copyInto(dst, 0)
            var p = raw.size
            while (p + 188 <= dst.size) {
                dst[p] = 0x47
                dst[p + 1] = 0x1F
                dst[p + 2] = 0xFF.toByte()
                dst[p + 3] = 0x10
                p += 188
            }
        }
    }

    private fun buildSyntheticSlotChunk(
        ptsMs: Long,
        videoPid: Int = 0x0100,
        nalType: Int = 19,
        payloadBytes: Int = 1200,
        sizeBytes: Int = 64 * 1024,
    ): ByteArray {
        val ticks = 90_000L + ptsMs * 90L
        val (pes, cc1) = buildVideoPesPackets(videoPid, ptsTicks = ticks, annexBPayload = hevcNal(nalType, payloadBytes), startCc = 0)
        val (terminator, _) = buildVideoPesPackets(videoPid, ptsTicks = ticks + 3_600L, annexBPayload = hevcNal(1, 32), startCc = cc1, randomAccess = false)
        val raw = pes + terminator
        return ByteArray(sizeBytes).also { dst ->
            raw.copyInto(dst, 0, 0, minOf(raw.size, dst.size))
            var p = minOf(raw.size, dst.size)
            p = ((p + 187) / 188) * 188
            while (p + 188 <= dst.size) {
                dst[p] = 0x47
                dst[p + 1] = 0x1F
                dst[p + 2] = 0xFF.toByte()
                dst[p + 3] = 0x10
                p += 188
            }
        }
    }

    @Test
    fun adaptiveProbeSizesCoverFiveSecondGopWithinSmartBounds() {
        // 极低码率 360P（20 KiB/s -> 100+32=132 KiB）钳制到下限 192 KiB
        assertEquals(
            StoryboardPlanner.MIN_ADAPTIVE_PROBE_BYTES,
            StoryboardPlanner.adaptiveProbeBytes(durationMillis = 600_000L, streamSizeBytes = 20L * 1024L * 600L),
        )
        // 典型 46 分钟 480P MPEG-TS（~140 MiB -> ~52 KiB/s）：单次首探落在 288..304 KiB，单 RTT 覆盖 5s GOP + IDR
        val typical480p = StoryboardPlanner.adaptiveProbeBytes(
            durationMillis = 2_760_000L,
            streamSizeBytes = 140L * 1024L * 1024L,
        )
        assertTrue(
            typical480p in 272 * 1024..320 * 1024,
            "Expected typical 480P single-shot probe in 272..320 KiB, got ${typical480p / 1024} KiB",
        )
        assertEquals(0, typical480p % (16 * 1024), "Adaptive probe must align to 16 KiB granularity")
        // 高码率 480P（90 KiB/s -> 482 KiB）钳制到上限 384 KiB
        assertEquals(
            StoryboardPlanner.MAX_ADAPTIVE_PROBE_BYTES,
            StoryboardPlanner.adaptiveProbeBytes(durationMillis = 1_800_000L, streamSizeBytes = 90L * 1024L * 1_800L),
        )
    }

    @Test
    fun adaptiveProbeAndContinuationSkipTruncatedCraNalu21WithoutRefetchingPrimary() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val streamSize = 188L * 40_000L // ~7.52 MB over 120s -> ~62.7 KiB/s
            val durationMs = 120_000L
            val slots = StoryboardPlanner.buildSlots(durationMillis = durationMs, streamSizeBytes = streamSize)
            val probeSize = StoryboardPlanner.adaptiveProbeBytes(durationMs, streamSize)
            assertTrue(
                probeSize in StoryboardPlanner.MIN_ADAPTIVE_PROBE_BYTES..StoryboardPlanner.MAX_ADAPTIVE_PROBE_BYTES,
                "Adaptive probeSize=$probeSize must stay in 192..384 KiB",
            )
            val targetSlot = slots[1]
            val longGopSlot = slots[2]
            assertEquals(probeSize, targetSlot.probeLengthBytes)

            // 构造跨 primary 边界截断的 HEVC CRA (NALU 21) 访问单元：前一半落在 primary 末尾，后一半 + 终结 PES 落在续块开头
            val headerChunk = buildSyntheticHeaderChunk()
            val header = TsIdrExtractor.parseHeader(headerChunk, fallbackWidth = 640, fallbackHeight = 360)
            assertNotNull(header)

            val craTicks = 90_000L + 2_500L * 90L
            val (craPes, ccAfterCra) = buildVideoPesPackets(
                videoPid = header.videoPid,
                ptsTicks = craTicks,
                annexBPayload = hevcNal(nalType = 21, bodySize = 4_000),
                startCc = 0,
            )
            val (craTerminator, _) = buildVideoPesPackets(
                videoPid = header.videoPid,
                ptsTicks = craTicks + 3_600L,
                annexBPayload = hevcNal(nalType = 1, bodySize = 64),
                startCc = ccAfterCra,
                randomAccess = false,
            )
            val combinedExpected = ByteArray(StoryboardPlanner.EXPANDED_PROBE_BYTES)
            // 用空包（PID 0x1FFF）填充前部，使 CRA PES 恰好横跨 primary 边界（前 ~1.8 KiB 在首块，剩余 + 终结包在续块）
            val startPacketIndex = (probeSize / 188) - 10
            val craStartByte = startPacketIndex * 188
            var p = 0
            while (p + 188 <= combinedExpected.size) {
                combinedExpected[p] = 0x47
                combinedExpected[p + 1] = 0x1F
                combinedExpected[p + 2] = 0xFF.toByte()
                combinedExpected[p + 3] = 0x10
                p += 188
            }
            craPes.copyInto(combinedExpected, craStartByte)
            craTerminator.copyInto(combinedExpected, craStartByte + craPes.size)

            val firstHalf = combinedExpected.copyOfRange(0, probeSize)
            val secondHalf = combinedExpected.copyOfRange(probeSize, combinedExpected.size)

            // 验证 TsIdrExtractor 识别为 TruncatedAtTail 且绝不把截断半帧 NALU 21 交给解码器
            assertNull(
                TsIdrExtractor.extractFirstIdr(firstHalf, header, durationMillis = durationMs, allowTailDrain = false),
                "Truncated mid-stream CRA NALU 21 at primary boundary must NOT be drained to FFmpeg",
            )
            assertTrue(
                TsIdrExtractor.extractFirstIdrDetailed(firstHalf, header, durationMillis = durationMs, allowTailDrain = false) is TsIdrExtractionResult.TruncatedAtTail,
                "Truncated CRA NALU 21 at chunk tail must be reported as TruncatedAtTail",
            )
            val fullExtracted = TsIdrExtractor.extractFirstIdr(combinedExpected, header, durationMillis = durationMs, allowTailDrain = false)
            assertNotNull(fullExtracted, "Concatenated buffer must cleanly extract the complete CRA NALU 21")
            assertEquals(2_500L, fullExtracted.ptsMs)

            // 构造超长 GOP 槽位：整个 primary 块内全为空包（NotFound），IDR 落在续块中
            val emptyPrimary = ByteArray(probeSize).also { dst ->
                var q = 0
                while (q + 188 <= dst.size) {
                    dst[q] = 0x47
                    dst[q + 1] = 0x1F
                    dst[q + 2] = 0xFF.toByte()
                    dst[q + 3] = 0x10
                    q += 188
                }
            }
            assertEquals(
                TsIdrExtractionResult.NotFound,
                TsIdrExtractor.extractFirstIdrDetailed(emptyPrimary, header, durationMillis = durationMs, allowTailDrain = false),
            )
            val longGopContLen = StoryboardPlanner.EXPANDED_PROBE_BYTES - probeSize
            val longGopCont = buildSyntheticSlotChunk(ptsMs = longGopSlot.estimatedMs, sizeBytes = longGopContLen)

            // 端到端验证 StoryboardEngine：流头首探 <= 64 KiB，中段槽位单次自适应首探，续块零重复拉取且区分尾块与超长 GOP
            val requests = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
            val slotByOffset = slots.associateBy { it.byteOffset }
            val gate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = true, isPlaying = false, isPlaybackBusy = false))
            val source = StoryboardSource(
                fileId = "f-cont",
                gcid = "GCID_CONT_TEST",
                mediaId = "m360",
                url = "https://cdn.example/360.ts",
                width = 640,
                height = 360,
                durationMillis = durationMs,
                streamSizeBytes = streamSize,
            )
            val engine = StoryboardEngine(
                source = source,
                cache = InMemoryStoryboardCache(),
                resolveStreamSize = { streamSize },
                fetchRange = { offset, length ->
                    requests += offset to length
                    when (offset) {
                        0L -> headerChunk.copyOfRange(0, minOf(length, headerChunk.size))
                        targetSlot.byteOffset -> firstHalf.copyOfRange(0, minOf(length, firstHalf.size))
                        targetSlot.byteOffset + probeSize -> secondHalf.copyOfRange(0, minOf(length, secondHalf.size))
                        longGopSlot.byteOffset -> emptyPrimary.copyOfRange(0, minOf(length, emptyPrimary.size))
                        longGopSlot.byteOffset + probeSize -> longGopCont.copyOfRange(0, minOf(length, longGopCont.size))
                        else -> {
                            val plan = slotByOffset[offset]
                            buildSyntheticSlotChunk(ptsMs = plan?.estimatedMs ?: 1_000L, sizeBytes = length)
                        }
                    }
                },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
                        override fun close() {}
                    }
                },
                playbackGate = gate,
                scope = scope,
            )
            engine.start()
            val timeline = withTimeout(5_000L) {
                engine.timeline.first { it.isComplete }
            }
            engine.close()

            val headReq = requests.first { (off, _) -> off == 0L }
            assertTrue(headReq.second <= StoryboardPlanner.DEFAULT_PROBE_BYTES, "Head probe must stay <= 64 KiB for MICRO_PROBE_PRIORITY=12")
            val primaryCallsForTarget = requests.filter { (off, _) -> off == targetSlot.byteOffset }
            val contCallsForTarget = requests.filter { (off, _) -> off == targetSlot.byteOffset + probeSize }
            assertEquals(1, primaryCallsForTarget.size, "Primary chunk must NOT be re-fetched on continuation retry")
            assertEquals(probeSize, primaryCallsForTarget.single().second)
            assertEquals(1, contCallsForTarget.size, "Tail continuation must be fetched exactly once")
            assertTrue(
                contCallsForTarget.single().second <= StoryboardPlanner.TAIL_CONTINUATION_BYTES,
                "TruncatedAtTail continuation must stay bounded to TAIL_CONTINUATION_BYTES",
            )
            val longGopContCalls = requests.filter { (off, _) -> off == longGopSlot.byteOffset + probeSize }
            assertEquals(1, longGopContCalls.size, "Long GOP slot must expand cleanly up to EXPANDED_PROBE_BYTES in one continuation")
            assertEquals(longGopContLen, longGopContCalls.single().second)
            assertTrue(timeline.frames.any { it.slotIndex == targetSlot.slotIndex && it.ptsMs == 2_500L })
            assertTrue(timeline.frames.any { it.slotIndex == longGopSlot.slotIndex && it.ptsMs == longGopSlot.estimatedMs })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unifiedSchedulerPreemptsWave1OnUserBoostAndUsesSixWayConcurrencyWhenPaused() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val streamSize = 188L * 50_000L
            val durationMs = 120_000L
            val slots = StoryboardPlanner.buildSlots(durationMillis = durationMs, streamSizeBytes = streamSize)
            val wave1Set = StoryboardPlanner.wave1Indices(slots.size).toSet()
            val headerChunk = buildSyntheticHeaderChunk()
            val slotByOffset = slots.associateBy { it.byteOffset }

            // 1. 暂停态（如打开「速览」）应零等待启动并拉满 6 路并发
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            val pausedGate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = true, isPlaying = false, isPlaybackBusy = false))
            val pausedSource = StoryboardSource(
                fileId = "f-conc",
                gcid = "GCID_CONC_6",
                mediaId = "m360",
                url = "https://cdn.example/360.ts",
                width = 640,
                height = 360,
                durationMillis = durationMs,
                streamSizeBytes = streamSize,
            )
            val pausedEngine = StoryboardEngine(
                source = pausedSource,
                cache = InMemoryStoryboardCache(),
                resolveStreamSize = { streamSize },
                fetchRange = { offset, length ->
                    if (offset == 0L) {
                        headerChunk.copyOfRange(0, minOf(length, headerChunk.size))
                    } else {
                        val cur = inFlight.incrementAndGet()
                        maxInFlight.accumulateAndGet(cur, ::maxOf)
                        try {
                            delay(35L)
                            val plan = slotByOffset[offset]
                            buildSyntheticSlotChunk(ptsMs = plan?.estimatedMs ?: 1_000L, sizeBytes = length)
                        } finally {
                            inFlight.decrementAndGet()
                        }
                    }
                },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9)
                        override fun close() {}
                    }
                },
                playbackGate = pausedGate,
                scope = scope,
            )
            pausedEngine.start()
            val doneTimeline = withTimeout(5_000L) {
                pausedEngine.timeline.first { it.isComplete }
            }
            pausedEngine.close()
            assertEquals(slots.size, doneTimeline.frames.size)
            assertEquals(6, maxInFlight.get(), "Paused / rapid-scrub mode must fan out to 6 concurrent HTTP range workers")

            // 2. 统一优先级队列抢占：在 Wave 1 尚未完成前触发 boostAround(60_000ms)，目标附近槽位（含 Wave 2 邻居）立即插队抢占
            val firstWaveSlotStarted = CompletableDeferred<Unit>()
            val releaseWaveGate = CompletableDeferred<Unit>()
            val fetchOrder = Collections.synchronizedList(mutableListOf<Int>())
            val playingGate = MutableStateFlow(
                StoryboardPlaybackGate(
                    firstFrameReady = true,
                    bufferedAheadMs = 5_000L,
                    isPlaying = true,
                    isPlaybackBusy = false,
                ),
            )
            val preemptEngine = StoryboardEngine(
                source = pausedSource.copy(gcid = "GCID_PREEMPT"),
                cache = InMemoryStoryboardCache(),
                resolveStreamSize = { streamSize },
                fetchRange = { offset, length ->
                    if (offset == 0L) {
                        headerChunk.copyOfRange(0, minOf(length, headerChunk.size))
                    } else {
                        val plan = slotByOffset[offset]
                        if (plan != null) {
                            fetchOrder += plan.slotIndex
                            firstWaveSlotStarted.complete(Unit)
                            releaseWaveGate.await()
                        }
                        buildSyntheticSlotChunk(ptsMs = plan?.estimatedMs ?: 1_000L, sizeBytes = length)
                    }
                },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7)
                        override fun close() {}
                    }
                },
                playbackGate = playingGate,
                scope = scope,
            )
            preemptEngine.start()
            withTimeout(2_000L) { firstWaveSlotStarted.await() }
            // 在首批 Wave 1 请求尚在途时触发用户焦点（60_000ms -> 槽位 24 附近）
            val boostTargetMs = 60_000L
            preemptEngine.boostAround(boostTargetMs)
            releaseWaveGate.complete(Unit)
            withTimeout(5_000L) {
                preemptEngine.timeline.first { it.isComplete }
            }
            preemptEngine.close()

            val nearestTargetIdx = slots.minBy { kotlin.math.abs(it.estimatedMs - boostTargetMs) }.slotIndex
            val nonWave1Neighbor = listOf(nearestTargetIdx, nearestTargetIdx - 1, nearestTargetIdx + 1)
                .first { it in slots.indices && it !in wave1Set }
            val neighborOrderPos = fetchOrder.indexOf(nonWave1Neighbor)
            val lastWave1OrderPos = wave1Set.mapNotNull { idx -> fetchOrder.indexOf(idx).takeIf { it >= 0 } }.maxOrNull() ?: 0
            assertTrue(
                neighborOrderPos in 0 until lastWave1OrderPos,
                "Boosted Wave 2 neighbor ($nonWave1Neighbor at pos=$neighborOrderPos) must preempt remaining Wave 1 anchors (last Wave 1 at pos=$lastWave1OrderPos)",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun tailDrainExtractsCompactSinglePacketIdrsAtEofAndDeclaredLengthWithoutLeakingMidStreamTruncated() {
        val headerChunk = buildSyntheticHeaderChunk()
        val header = TsIdrExtractor.parseHeader(headerChunk, fallbackWidth = 640, fallbackHeight = 360)
        assertNotNull(header)

        val nullPacket = ByteArray(188) { 0xFF.toByte() }.apply {
            this[0] = 0x47
            this[1] = 0x1F
            this[2] = 0xFF.toByte()
            this[3] = 0x10
        }

        // 1. 单 TS 包（< 1 KiB）紧凑无界 IDR 在真实 EOF（allowTailDrain = true）必须完整提取，而在流中段（allowTailDrain = false）拒绝截断半帧
        val eofPtsMs = 115_000L
        val (compactUnboundedPkt, _) = buildVideoPesPackets(
            videoPid = header.videoPid,
            ptsTicks = 90_000L + eofPtsMs * 90L,
            annexBPayload = hevcNal(nalType = 19, bodySize = 96),
            startCc = 0,
            randomAccess = false,
            declaredLength = false,
        )
        assertEquals(188, compactUnboundedPkt.size, "Compact IDR must fit in a single 188-byte TS packet (< 1 KiB)")
        val eofChunk = compactUnboundedPkt + nullPacket
        assertNull(
            TsIdrExtractor.extractFirstIdr(eofChunk, header, durationMillis = 120_000L, allowTailDrain = false),
            "Unbounded tail IDR mid-stream (allowTailDrain=false) must wait for continuation",
        )
        val eofExtracted = TsIdrExtractor.extractFirstIdr(eofChunk, header, durationMillis = 120_000L, allowTailDrain = true)
        assertNotNull(eofExtracted, "Compact single-packet (< 1 KiB) IDR at EOF (allowTailDrain=true) must be extracted")
        assertEquals(eofPtsMs, eofExtracted.ptsMs)

        // 2. 显式声明固定 PES_packet_length 的单 TS 包（< 1 KiB）IDR 在 allowTailDrain = false 下已证明完整，必须直接提取
        val declaredPtsMs = 42_000L
        val (compactDeclaredPkt, _) = buildVideoPesPackets(
            videoPid = header.videoPid,
            ptsTicks = 90_000L + declaredPtsMs * 90L,
            annexBPayload = hevcNal(nalType = 19, bodySize = 96),
            startCc = 0,
            randomAccess = false,
            declaredLength = true,
        )
        assertEquals(188, compactDeclaredPkt.size)
        val declaredChunk = compactDeclaredPkt + nullPacket
        val declaredExtracted = TsIdrExtractor.extractFirstIdr(declaredChunk, header, durationMillis = 120_000L, allowTailDrain = false)
        assertNotNull(declaredExtracted, "Declared-length single-packet (< 1 KiB) IDR must be extracted even when allowTailDrain=false")
        assertEquals(declaredPtsMs, declaredExtracted.ptsMs)

        // 3. 参数集 + Prefix SEI 占满首 TS 包（randomAccess = false，IRAP 落在第 2 个 TS 包）在 EOF 同样必须提取
        val splitPtsMs = 118_000L
        val splitAu = hevcNal(32, 60) + hevcNal(33, 60) + hevcNal(34, 60) + hevcNal(39, 40) + hevcNal(19, 120)
        val (splitPkts, _) = buildVideoPesPackets(
            videoPid = header.videoPid,
            ptsTicks = 90_000L + splitPtsMs * 90L,
            annexBPayload = splitAu,
            startCc = 0,
            randomAccess = false,
            declaredLength = false,
        )
        assertTrue(splitPkts.size >= 376)
        val splitExtracted = TsIdrExtractor.extractFirstIdr(splitPkts, header, durationMillis = 120_000L, allowTailDrain = true)
        assertNotNull(splitExtracted, "EOF IDR whose IRAP NALU begins in TS packet #2+ must not be skipped")
        assertEquals(splitPtsMs, splitExtracted.ptsMs)
    }

    @Test
    fun engineYieldsDuringCriticallyLowBufferAndThrottlesPlayingConcurrency() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val streamSize = 188L * 40_000L
            val durationMs = 120_000L
            val slots = StoryboardPlanner.buildSlots(durationMillis = durationMs, streamSizeBytes = streamSize)
            val headerChunk = buildSyntheticHeaderChunk()
            val slotByOffset = slots.associateBy { it.byteOffset }

            val networkCalls = AtomicInteger(0)
            val inFlight = AtomicInteger(0)
            val maxInFlightWhileLowHealthy = AtomicInteger(0)

            // 1. 首帧就绪且正在播放，但前方缓冲仅 1.2s（低于 3s 临界线）：后台雪碧图必须按兵不动（0 请求）
            val gate = MutableStateFlow(
                StoryboardPlaybackGate(
                    firstFrameReady = true,
                    bufferedAheadMs = 1_200L,
                    isPlaying = true,
                    isPlaybackBusy = false,
                ),
            )
            val source = StoryboardSource(
                fileId = "f-slow",
                gcid = "GCID_LOW_BUFFER",
                mediaId = "m360",
                url = "https://cdn.example/360.ts",
                width = 640,
                height = 360,
                durationMillis = durationMs,
                streamSizeBytes = streamSize,
            )
            val engine = StoryboardEngine(
                source = source,
                cache = InMemoryStoryboardCache(),
                resolveStreamSize = { streamSize },
                fetchRange = { offset, length ->
                    networkCalls.incrementAndGet()
                    if (offset == 0L) {
                        headerChunk.copyOfRange(0, minOf(length, headerChunk.size))
                    } else {
                        val cur = inFlight.incrementAndGet()
                        maxInFlightWhileLowHealthy.accumulateAndGet(cur, ::maxOf)
                        try {
                            delay(25L)
                            val plan = slotByOffset[offset]
                            buildSyntheticSlotChunk(ptsMs = plan?.estimatedMs ?: 1_000L, sizeBytes = length)
                        } finally {
                            inFlight.decrementAndGet()
                        }
                    }
                },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 5)
                        override fun close() {}
                    }
                },
                playbackGate = gate,
                scope = scope,
            )
            engine.start()
            delay(400L)
            assertEquals(0, networkCalls.get(), "Background storyboard must not fetch while playback buffer is critically low (<3s)")

            // 2. 缓冲回升至 5.5s（3s..8s 区间）：放行启动，但严格限流为单路并发，不抢占主播放带宽
            gate.value = gate.value.copy(bufferedAheadMs = 5_500L)
            withTimeout(2_000L) {
                while (networkCalls.get() < 4) delay(20L)
            }
            assertEquals(1, maxInFlightWhileLowHealthy.get(), "Moderate buffer (3–8s) during active playback must cap storyboard to 1 worker")

            // 3. 缓冲再次跌破 3s：后台抓取立即暂停退让
            gate.value = gate.value.copy(bufferedAheadMs = 1_000L)
            delay(80L)
            val pausedCount = networkCalls.get()
            delay(200L)
            assertEquals(pausedCount, networkCalls.get(), "Background storyboard must yield immediately when buffer drops below 3s")

            // 4. 用户悬停/速览交互（boostAround）仍可即时打破等待
            engine.boostAround(60_000L)
            withTimeout(2_000L) {
                while (networkCalls.get() <= pausedCount) delay(20L)
            }
            engine.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun mostlyFailedSlotsDoNotPoisonCacheAsCompletedAndMalformedPesLengthReturnsNull() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val streamSize = 188L * 30_000L
            val durationMs = 120_000L
            val headerChunk = buildSyntheticHeaderChunk()
            val header = assertNotNull(TsIdrExtractor.parseHeader(headerChunk, fallbackWidth = 640, fallbackHeight = 360))

            // 1. Malformed PES packet where 6 + declared <= headerEnd must return null without IllegalArgumentException
            val pts = encodePts(90_000L)
            val malformedPes = byteArrayOf(
                0x00, 0x00, 0x01, 0xE0.toByte(),
                0x00, 0x04, // declared = 4 => 6 + declared = 10 <= headerEnd (9 + 10 = 19)
                0x80.toByte(), 0x80.toByte(), 0x0A, // PES_header_data_length = 10 => headerEnd = 19
                pts[0], pts[1], pts[2], pts[3], pts[4],
                0, 0, 0, 0, 0,
                0, 0, 0, 1, 0x26, 0x01,
            )
            val malformedTs = tsPacket(pid = header.videoPid, payload = malformedPes, payloadStart = true, cc = 0) +
                tsPacket(pid = 0x1FFF, payload = ByteArray(184), payloadStart = false, cc = 0)
            assertNull(TsIdrExtractor.extractFirstIdr(malformedTs, header, durationMillis = durationMs, allowTailDrain = true))

            // 2. Slot 0 succeeds from headerChunk, but all remaining slots fail (network outage):
            // must persist partial frame(s) with completed = false instead of poisoning cache as completed = true
            val cache = InMemoryStoryboardCache()
            val gate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = true, isPlaying = false, isPlaybackBusy = false))
            val source = StoryboardSource(
                fileId = "f-outage",
                gcid = "GCID_PARTIAL_OUTAGE",
                mediaId = "m360",
                url = "https://cdn.example/360.ts",
                width = 640,
                height = 360,
                durationMillis = durationMs,
                streamSizeBytes = streamSize,
            )
            val engine = StoryboardEngine(
                source = source,
                cache = cache,
                resolveStreamSize = { streamSize },
                fetchRange = { offset, length ->
                    if (offset == 0L) headerChunk.copyOfRange(0, minOf(length, headerChunk.size)) else null
                },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)
                        override fun close() {}
                    }
                },
                playbackGate = gate,
                scope = scope,
            )
            engine.start()
            withTimeout(3_000L) {
                while (cache.read(source.gcid) == null) delay(25L)
            }
            delay(150L)
            engine.close()

            val persisted = assertNotNull(cache.read(source.gcid))
            assertEquals(1, persisted.frames.size)
            assertFalse(persisted.completed, "1-frame partial storyboard after slot failures must NOT be marked completed")
            assertFalse(engine.timeline.value.isComplete, "Timeline must NOT be marked complete when most slots failed")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sparseCompletedDiskCacheSelfHealsInBackgroundWhileShortOrDenseCachesStayComplete() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val durationMs = 2_760_000L // 46 min -> 110 slots
            val expectedSlots = StoryboardPlanner.slotCountForDuration(durationMs)
            assertEquals(110, expectedSlots)
            val streamSize = 188L * 700_000L
            val slots = StoryboardPlanner.buildSlots(durationMillis = durationMs, streamSizeBytes = streamSize)
            val headerChunk = buildSyntheticHeaderChunk()
            val slotByOffset = slots.associateBy { it.byteOffset }

            // 模拟旧版稀疏 .sb2：仅 37 帧却标为 completed=true（< 110 * 65% = 71）
            val sparseFrames = (0 until 37).map { i ->
                val slotIdx = i * 3
                StoryboardFrame(
                    slotIndex = slotIdx,
                    ptsMs = slots[slotIdx].estimatedMs,
                    jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x10),
                )
            }
            val sparseBundle = StoryboardBundle(
                gcid = "SPARSE_46M_GCID",
                durationMillis = durationMs,
                width = 640,
                height = 360,
                completed = true,
                lastAccessEpochMillis = 1_000L,
                frames = sparseFrames,
            )
            assertTrue(StoryboardEngine.isSparseCompletedCache(sparseBundle, durationMs, expectedSlots))
            // 短视频 (< 180s)、高密度缓存 (>= 65%) 与长 GOP 均匀去重缓存（无大空洞）均不触发自愈降级
            assertFalse(StoryboardEngine.isSparseCompletedCache(sparseBundle.copy(durationMillis = 120_000L), 120_000L, 48))
            val denseFrames = (0 until 72).map { idx -> StoryboardFrame(idx, idx * 25_000L, byteArrayOf(1)) }
            assertFalse(StoryboardEngine.isSparseCompletedCache(sparseBundle.copy(frames = denseFrames), durationMs, expectedSlots))
            val uniformLongGopFrames = (0 until 24).map { idx -> StoryboardFrame(idx * 2, idx * 10_000L, byteArrayOf(1)) }
            assertFalse(
                StoryboardEngine.isSparseCompletedCache(
                    bundle = sparseBundle.copy(durationMillis = 240_000L, frames = uniformLongGopFrames),
                    durationMillis = 240_000L,
                    expectedSlots = 48,
                ),
                "Valid completed 4-minute video with uniform 10s GOPs (no large PTS gaps) must NOT trigger infinite self-healing",
            )

            val cache = InMemoryStoryboardCache()
            cache.write(sparseBundle)
            val fetchedOffsets = Collections.synchronizedSet(HashSet<Long>())
            val gate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = true, isPlaying = false, isPlaybackBusy = false))
            val source = StoryboardSource(
                fileId = "f-sparse",
                gcid = "SPARSE_46M_GCID",
                mediaId = "m480",
                url = "https://cdn.example/480.ts",
                width = 640,
                height = 360,
                durationMillis = durationMs,
                streamSizeBytes = streamSize,
            )
            val engine = StoryboardEngine(
                source = source,
                cache = cache,
                resolveStreamSize = { streamSize },
                fetchRange = { offset, length ->
                    fetchedOffsets += offset
                    if (offset == 0L) {
                        headerChunk.copyOfRange(0, minOf(length, headerChunk.size))
                    } else {
                        val plan = slotByOffset[offset]
                        buildSyntheticSlotChunk(ptsMs = plan?.estimatedMs ?: 1_000L, sizeBytes = length)
                    }
                },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x22)
                        override fun close() {}
                    }
                },
                playbackGate = gate,
                scope = scope,
            )
            engine.start()
            val healedTimeline = withTimeout(5_000L) {
                engine.timeline.first { it.isComplete }
            }
            engine.close()

            assertEquals(expectedSlots, healedTimeline.frames.size, "Sparse cache must self-heal all missing slots")
            val persisted = assertNotNull(cache.read(source.gcid))
            assertTrue(persisted.completed)
            assertEquals(expectedSlots, persisted.frames.size)
            // 已缓存的非 0 槽位不应被重复拉取
            assertFalse(slots[3].byteOffset in fetchedOffsets, "Already-cached slot 3 must be reused without refetching")
            assertTrue(slots[1].byteOffset in fetchedOffsets, "Missing slot 1 must be fetched during self-healing")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun healthyPlaybackFansOutToFourWave1WorkersAndSurvivesZeroBufferAheadReading() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val durationMs = 120_000L
            val streamSize = 188L * 50_000L
            val slots = StoryboardPlanner.buildSlots(durationMillis = durationMs, streamSizeBytes = streamSize)
            val headerChunk = buildSyntheticHeaderChunk()
            val slotByOffset = slots.associateBy { it.byteOffset }

            val inFlight = AtomicInteger(0)
            val maxInFlightWave1 = AtomicInteger(0)
            val totalSlotFetches = AtomicInteger(0)
            val fetchesAfterUnreportedZero = AtomicInteger(0)
            val unreportedZeroPhase = AtomicBoolean(false)

            // 1. 起播即带 8s 健康缓冲：零等待直接启动并拉满 Wave 1 四路并发
            val gate = MutableStateFlow(
                StoryboardPlaybackGate(
                    firstFrameReady = true,
                    bufferedAheadMs = 8_000L,
                    isPlaying = true,
                    isPlaybackBusy = false,
                ),
            )
            val source = StoryboardSource(
                fileId = "f-healthy",
                gcid = "GCID_HEALTHY_4W",
                mediaId = "m360",
                url = "https://cdn.example/360.ts",
                width = 640,
                height = 360,
                durationMillis = durationMs,
                streamSizeBytes = streamSize,
            )
            val engine = StoryboardEngine(
                source = source,
                cache = InMemoryStoryboardCache(),
                resolveStreamSize = { streamSize },
                fetchRange = { offset, length ->
                    if (offset == 0L) {
                        headerChunk.copyOfRange(0, minOf(length, headerChunk.size))
                    } else {
                        totalSlotFetches.incrementAndGet()
                        if (unreportedZeroPhase.get()) {
                            fetchesAfterUnreportedZero.incrementAndGet()
                        }
                        val cur = inFlight.incrementAndGet()
                        maxInFlightWave1.accumulateAndGet(cur, ::maxOf)
                        try {
                            delay(35L)
                            val plan = slotByOffset[offset]
                            buildSyntheticSlotChunk(ptsMs = plan?.estimatedMs ?: 1_000L, sizeBytes = length)
                        } finally {
                            inFlight.decrementAndGet()
                        }
                    }
                },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33)
                        override fun close() {}
                    }
                },
                playbackGate = gate,
                scope = scope,
            )
            engine.start()
            withTimeout(2_000L) {
                while (maxInFlightWave1.get() < StoryboardEngine.WAVE1_PLAYING_CONCURRENCY) delay(15L)
            }
            assertEquals(
                StoryboardEngine.WAVE1_PLAYING_CONCURRENCY,
                maxInFlightWave1.get(),
                "Healthy playing buffer (>= 6s) must run 4 concurrent Wave 1 workers without 4s startup delay",
            )

            // 2. 真实测得缓冲耗尽（bufferedAheadMs == 0 且 hasBufferReading == true）：必须立即退让为 0 并发
            gate.value = gate.value.copy(bufferedAheadMs = 0L, hasBufferReading = true)
            delay(80L)
            val countWhileStarving = totalSlotFetches.get()
            delay(160L)
            assertEquals(
                countWhileStarving,
                totalSlotFetches.get(),
                "Measured 0ms buffer (hasBufferReading=true) must immediately yield background concurrency to 0",
            )

            // 3. 后端未上报缓冲读数（hasBufferReading == false 且 isPlaybackBusy == false）：不落入旧 sawPositiveBufferAhead 陷阱，恢复并顺利完成
            unreportedZeroPhase.set(true)
            gate.value = gate.value.copy(bufferedAheadMs = 0L, hasBufferReading = false)
            val completed = withTimeout(5_000L) {
                engine.timeline.first { it.isComplete }
            }
            engine.close()

            assertEquals(slots.size, completed.frames.size)
            assertTrue(
                fetchesAfterUnreportedZero.get() > 0,
                "Unreported buffer reading (hasBufferReading=false) while playback is smooth must NOT stall storyboard concurrency to 0",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun declaredLengthTruncatedIdrReportsExactRemainingBytesWhileNonIdrTailReportsNotFound() {
        val headerChunk = buildSyntheticHeaderChunk()
        val header = assertNotNull(TsIdrExtractor.parseHeader(headerChunk, fallbackWidth = 640, fallbackHeight = 360))

        // 1. 声明了固定 PES 长度的 IDR 在中途被截断 -> TruncatedAtTail(declaredRemainingBytes > 0)
        val (declaredIdr, _) = buildVideoPesPackets(
            videoPid = header.videoPid,
            ptsTicks = 90_000L + 15_000L * 90L,
            annexBPayload = hevcNal(nalType = 19, bodySize = 2_000),
            startCc = 0,
            randomAccess = true,
            declaredLength = true,
        )
        val truncatedIdr = declaredIdr.copyOfRange(0, 188 * 4)
        val idrResult = TsIdrExtractor.extractFirstIdrDetailed(truncatedIdr, header, durationMillis = 120_000L, allowTailDrain = false)
        val truncatedTail = assertNotNull(
            idrResult as? TsIdrExtractionResult.TruncatedAtTail,
            "Expected TruncatedAtTail, got $idrResult",
        )
        assertTrue(
            (truncatedTail.declaredRemainingBytes ?: 0) > 0,
            "Expected positive declaredRemainingBytes, got ${truncatedTail.declaredRemainingBytes}",
        )

        // 2. 非 IDR（NALU 1 P/B 帧，randomAccess = false）在块尾截断 -> NotFound（不误判为 IDR 尾块）
        val (nonIdr, _) = buildVideoPesPackets(
            videoPid = header.videoPid,
            ptsTicks = 90_000L + 16_000L * 90L,
            annexBPayload = hevcNal(nalType = 1, bodySize = 2_000),
            startCc = 0,
            randomAccess = false,
            declaredLength = false,
        )
        val truncatedNonIdr = nonIdr.copyOfRange(0, 188 * 4)
        assertEquals(
            TsIdrExtractionResult.NotFound,
            TsIdrExtractor.extractFirstIdrDetailed(truncatedNonIdr, header, durationMillis = 120_000L, allowTailDrain = false),
        )
    }

    @Test
    fun boostAroundCoalescesSubSlotJitterWhileAcceptingLargeJumpsAndBoundaries() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // 46 分钟（110 槽，单槽约 25s -> 合并阈值约 12.5s）
            val durationMs = 2_760_000L
            val source = StoryboardSource(
                fileId = "f-boost",
                gcid = "GCID_BOOST_COALESCE",
                mediaId = "m480",
                url = "https://cdn.example/480.ts",
                width = 640,
                height = 360,
                durationMillis = durationMs,
                streamSizeBytes = 188L * 700_000L,
            )
            val engine = StoryboardEngine(
                source = source,
                cache = InMemoryStoryboardCache(),
                resolveStreamSize = { source.streamSizeBytes },
                fetchRange = { _, _ -> null },
                decoderFactory = { null },
                playbackGate = MutableStateFlow(StoryboardPlaybackGate()),
                scope = scope,
            )
            // 首次调用立即生效
            engine.boostAround(600_000L)
            assertEquals(600_000L, engine.activePriorityTargetMs)
            // 半槽以内的频繁悬停抖动被合并，不触发队列重排
            engine.boostAround(602_500L)
            engine.boostAround(608_000L)
            assertEquals(600_000L, engine.activePriorityTargetMs)
            // 跨槽大跳转立即生效
            engine.boostAround(1_200_000L)
            assertEquals(1_200_000L, engine.activePriorityTargetMs)
            // 回到片头/片尾边界始终放行
            engine.boostAround(0L)
            assertEquals(0L, engine.activePriorityTargetMs)
            engine.boostAround(durationMs)
            assertEquals(durationMs, engine.activePriorityTargetMs)
            engine.close()
        } finally {
            scope.cancel()
        }
    }

    private fun buildAvcPmtPacket(pmtPid: Int, videoPid: Int, cc: Int = 0): ByteArray {
        val section = byteArrayOf(
            0x00, // pointer_field
            0x02, // table_id PMT
            0xB0.toByte(), 18,
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0xE1.toByte(), 0x00,
            0xF0.toByte(), 0x00,
            0x1B, // STREAM_AVC (H.264)
            (0xE0 or ((videoPid ushr 8) and 0x1F)).toByte(), (videoPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            0, 0, 0, 0,
        )
        return tsPacket(pid = pmtPid, payload = section, payloadStart = true, cc = cc)
    }

    private fun avcNal(nalType: Int, bodySize: Int, fill: Byte = 0x3C): ByteArray {
        val out = ByteArray(4 + 1 + bodySize)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        out[4] = (0x60 or (nalType and 0x1F)).toByte()
        for (i in 5 until out.size) out[i] = fill
        return out
    }

    private fun fillNullPackets(dst: ByteArray, fromByte: Int = 0, endByte: Int = dst.size) {
        var p = ((fromByte + 187) / 188) * 188
        while (p + 188 <= endByte) {
            dst[p] = 0x47
            dst[p + 1] = 0x1F
            dst[p + 2] = 0xFF.toByte()
            dst[p + 3] = 0x10
            p += 188
        }
    }

    @Test
    fun streamingScanEarlyAbortsCoversTenSecondGopSingleRttAndSeparatesPriorities() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val durationMs = 1_440_000L // 24 min -> 120 slots
            val streamSize = 188L * 500_000L // ~94 MiB (~65 KiB/s -> adaptiveStreamScanBytes ~ 896 KiB)
            val scanBudget = StoryboardPlanner.adaptiveStreamScanBytes(durationMs, streamSize)
            assertTrue(
                scanBudget in StoryboardPlanner.MIN_STREAM_SCAN_BYTES..StoryboardPlanner.MAX_STREAM_SCAN_BYTES,
                "Expected streaming scan budget in 512..1280 KiB, got ${scanBudget / 1024} KiB",
            )
            assertTrue(scanBudget >= 800 * 1024, "65 KiB/s stream should budget >= 800 KiB for 10s+ GOP")

            val slots = StoryboardPlanner.buildSlots(durationMillis = durationMs, streamSizeBytes = streamSize)
            val wave1Set = StoryboardPlanner.wave1Indices(slots.size).toSet()
            val headerChunk = buildSyntheticHeaderChunk()
            val header = assertNotNull(TsIdrExtractor.parseHeader(headerChunk, fallbackWidth = 852, fallbackHeight = 480))

            // 构造三个典型槽位流数据：
            // 1. earlySlot：IDR 落在 +32 KiB 处（应提前中止，不读完 896 KiB）
            // 2. tenSecGopSlot：IDR 落在 +680 KiB 处（远超旧 512 KiB 上限，单 RTT 命中且 0 续块）
            // 3. extremeVbrSlot：IDR 落在 +1.35 MiB 处（超出首窗，沿同一扫描器单次续流命中）
            val earlySlot = slots[wave1Set.first { it > 0 }]
            val tenSecGopSlot = slots[wave1Set.first { it > earlySlot.slotIndex }]
            val extremeVbrSlot = slots[wave1Set.first { it > tenSecGopSlot.slotIndex }]

            fun buildDelayedSlotBuffer(ptsMs: Long, idrStartOffset: Int, totalBytes: Int): ByteArray {
                val alignedStart = (idrStartOffset / 188) * 188
                val buf = ByteArray(totalBytes)
                fillNullPackets(buf, 0, totalBytes)
                val ticks = 90_000L + ptsMs * 90L
                val (pes, cc1) = buildVideoPesPackets(header.videoPid, ptsTicks = ticks, annexBPayload = hevcNal(19, 4_000), startCc = 0)
                val (term, _) = buildVideoPesPackets(header.videoPid, ptsTicks = ticks + 3_600L, annexBPayload = hevcNal(1, 32), startCc = cc1, randomAccess = false)
                pes.copyInto(buf, alignedStart)
                term.copyInto(buf, alignedStart + pes.size)
                return buf
            }

            val earlyBuf = buildDelayedSlotBuffer(earlySlot.estimatedMs, 32 * 1024, scanBudget)
            val tenSecBuf = buildDelayedSlotBuffer(tenSecGopSlot.estimatedMs, 680 * 1024, scanBudget)
            val extremeBuf = buildDelayedSlotBuffer(extremeVbrSlot.estimatedMs, 1_350 * 1024, StoryboardPlanner.EXPANDED_STREAM_SCAN_BYTES)
            val slotByOffset = slots.associateBy { it.byteOffset }

            val resolveStreamSizeCalls = AtomicInteger(0)
            val bytesServedByOffset = Collections.synchronizedMap(HashMap<Long, Int>())
            val requestCountByOffset = Collections.synchronizedMap(HashMap<Long, Int>())
            val prioritiesBySlot = Collections.synchronizedMap(HashMap<Int, StoryboardFetchPriority>())
            val gateTransitions = Collections.synchronizedList(mutableListOf<Pair<Boolean, Boolean>>())

            val gate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = true, bufferedAheadMs = 10_000L, isPlaying = true, isPlaybackBusy = false))
            val source = StoryboardSource(
                fileId = "f-stream",
                gcid = "GCID_STREAM_FAST",
                mediaId = "m480",
                url = "https://cdn.example/480.ts",
                width = 852,
                height = 480,
                durationMillis = durationMs,
                streamSizeBytes = null, // 未知流长：必须由偏移 0 流式探测单 RTT 一并带回
            )

            val fetcher = StoryboardStreamFetcher { offset, maxLength, priority, onChunk ->
                requestCountByOffset.merge(offset, 1, Int::plus)
                val backing = when {
                    offset == 0L -> headerChunk
                    offset == earlySlot.byteOffset -> earlyBuf
                    offset == tenSecGopSlot.byteOffset -> tenSecBuf
                    offset == extremeVbrSlot.byteOffset || offset == extremeVbrSlot.byteOffset + scanBudget -> {
                        val rel = (offset - extremeVbrSlot.byteOffset).toInt()
                        extremeBuf.copyOfRange(rel, minOf(extremeBuf.size, rel + maxLength))
                    }
                    else -> {
                        val plan = slotByOffset[offset]
                        if (plan != null) prioritiesBySlot[plan.slotIndex] = priority
                        buildDelayedSlotBuffer(plan?.estimatedMs ?: 1_000L, 16 * 1024, minOf(maxLength, 64 * 1024))
                    }
                }
                if (offset == earlySlot.byteOffset) prioritiesBySlot[earlySlot.slotIndex] = priority
                if (offset == tenSecGopSlot.byteOffset) prioritiesBySlot[tenSecGopSlot.slotIndex] = priority
                if (offset == extremeVbrSlot.byteOffset) prioritiesBySlot[extremeVbrSlot.slotIndex] = priority

                // 故意用非 188 对齐的不规则网络分块喂入，验证增量扫描器跨任意分块边界同步与提前中止
                val chunkSizes = intArrayOf(1350, 4096, 17_000, 32_768)
                var pos = 0
                var chunkIdx = 0
                val limit = minOf(backing.size, maxLength)
                while (pos < limit) {
                    val step = minOf(chunkSizes[chunkIdx % chunkSizes.size], limit - pos)
                    val slice = backing.copyOfRange(pos, pos + step)
                    pos += step
                    chunkIdx++
                    if (onChunk(slice, step)) break
                }
                bytesServedByOffset.merge(offset, pos, Int::plus)
                StoryboardRangeProbe(
                    streamSizeBytes = streamSize,
                    bytesConsumed = pos,
                    reachedEof = offset + pos >= streamSize,
                )
            }

            val closedCallback = AtomicBoolean(false)
            val engine = StoryboardEngine(
                source = source,
                cache = InMemoryStoryboardCache(),
                resolveStreamSize = {
                    resolveStreamSizeCalls.incrementAndGet()
                    streamSize
                },
                fetchRange = { _, _ -> error("Legacy batch fetchRange must not be called when streamFetcher is provided") },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42)
                        override fun close() {}
                    }
                },
                playbackGate = gate,
                scope = scope,
                streamFetcher = fetcher,
                onTransportGateChange = { yieldBg, urgentOk -> gateTransitions += yieldBg to urgentOk },
                onClose = { closedCallback.set(true) },
            )

            engine.start()
            val timeline = withTimeout(8_000L) {
                engine.timeline.first { it.isComplete }
            }
            engine.close()

            assertEquals(0, resolveStreamSizeCalls.get(), "Streaming head probe must resolve streamSize from Content-Range in 1 RTT without extra resolveStreamSize call")
            assertEquals(slots.size, timeline.frames.size, "All 120 slots including 10s+ and extreme VBR GOPs must succeed with 0 failures")
            assertTrue(closedCallback.get(), "onClose callback must fire deterministically")

            // 1. earlySlot 在 ~32 KiB 抽到 IDR 后必须立刻中止流，远小于 896 KiB 预算
            val earlyServed = bytesServedByOffset[earlySlot.byteOffset] ?: Int.MAX_VALUE
            assertTrue(
                earlyServed < 96 * 1024,
                "Streaming scan must abort early after IDR at +32 KiB (consumed ${earlyServed / 1024} KiB of ${scanBudget / 1024} KiB budget)",
            )
            // 2. tenSecGopSlot 在 +680 KiB 单 RTT 命中，0 续块
            assertEquals(1, requestCountByOffset[tenSecGopSlot.byteOffset], "10s GOP at +680 KiB must complete in 1 RTT without continuation")
            assertTrue(timeline.frames.any { it.slotIndex == tenSecGopSlot.slotIndex && it.ptsMs == tenSecGopSlot.estimatedMs })
            // 3. extremeVbrSlot 经单次续流成功解码
            assertTrue(timeline.frames.any { it.slotIndex == extremeVbrSlot.slotIndex && it.ptsMs == extremeVbrSlot.estimatedMs })
            // 4. 分层优先级：Wave 1 全部走 URGENT，Wave 2 全部走 BACKGROUND
            for (idx in wave1Set) {
                if (idx == 0) continue
                assertEquals(StoryboardFetchPriority.URGENT, prioritiesBySlot[idx], "Wave 1 anchor slot $idx must use URGENT priority")
            }
            val wave2Indices = StoryboardPlanner.wave2Indices(slots.size, wave1Set.toList())
            assertTrue(wave2Indices.isNotEmpty())
            for (idx in wave2Indices.take(16)) {
                assertEquals(StoryboardFetchPriority.BACKGROUND, prioritiesBySlot[idx], "Wave 2 slot $idx must use BACKGROUND priority")
            }
            assertTrue(gateTransitions.isNotEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun avcH264MpegTsStreamParsesExtractsAndDispatchesAvcCodecToDecoder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val videoPid = 0x0101
            val pat = buildPatPacket(pmtPid = 0x1000)
            val pmt = buildAvcPmtPacket(pmtPid = 0x1000, videoPid = videoPid)
            val sps = avcNal(7, 16)
            val pps = avcNal(8, 8)
            val idr0 = avcNal(5, 1_200)
            val (pes0, cc1) = buildVideoPesPackets(videoPid, ptsTicks = 90_000L, annexBPayload = sps + pps + idr0, startCc = 0)
            val (term0, _) = buildVideoPesPackets(videoPid, ptsTicks = 93_600L, annexBPayload = avcNal(1, 32), startCc = cc1, randomAccess = false)
            val headBytes = ByteArray(32 * 1024).also { dst ->
                val raw = pat + pmt + pes0 + term0
                raw.copyInto(dst, 0)
                fillNullPackets(dst, raw.size, dst.size)
            }

            val parsed = assertNotNull(TsIdrExtractor.parseHeader(headBytes, fallbackWidth = 852, fallbackHeight = 480))
            assertEquals(TsVideoCodec.AVC, parsed.codec)
            assertEquals(videoPid, parsed.videoPid)
            assertTrue(parsed.csdAnnexB.size == sps.size + pps.size)

            val slotPtsMs = 45_000L
            val (slotPes, slotCc1) = buildVideoPesPackets(videoPid, ptsTicks = 90_000L + slotPtsMs * 90L, annexBPayload = avcNal(5, 1_500), startCc = 0)
            val (slotTerm, _) = buildVideoPesPackets(videoPid, ptsTicks = 90_000L + (slotPtsMs + 40L) * 90L, annexBPayload = avcNal(1, 32), startCc = slotCc1, randomAccess = false)
            val slotBytes = ByteArray(32 * 1024).also { dst ->
                val raw = slotPes + slotTerm
                raw.copyInto(dst, 0)
                fillNullPackets(dst, raw.size, dst.size)
            }

            val extracted = assertNotNull(TsIdrExtractor.extractFirstIdr(slotBytes, parsed, durationMillis = 120_000L))
            assertEquals(slotPtsMs, extracted.ptsMs)
            assertFalse(extracted.hasInlineParameterSets)

            // 端到端验证 StoryboardEngine 将 TsVideoCodec.AVC 传递给解码器并产出完整时间线
            val observedCodecs = Collections.synchronizedSet(HashSet<TsVideoCodec>())
            val durationMs = 120_000L
            val streamSize = 188L * 10_000L
            val source = StoryboardSource(
                fileId = "f-avc",
                gcid = "GCID_AVC_H264",
                mediaId = "m480",
                url = "https://cdn.example/avc480.ts",
                width = 852,
                height = 480,
                durationMillis = durationMs,
                streamSizeBytes = streamSize,
            )
            val slots = StoryboardPlanner.buildSlots(durationMs, streamSize)
            val slotByOffset = slots.associateBy { it.byteOffset }
            val gate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = true, isPlaying = false, isPlaybackBusy = false))
            val engine = StoryboardEngine(
                source = source,
                cache = InMemoryStoryboardCache(),
                resolveStreamSize = { streamSize },
                fetchRange = { _, _ -> null },
                decoderFactory = {
                    object : StoryboardIdrDecoder {
                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray? = null

                        override suspend fun decodeToJpeg(
                            csdAnnexB: ByteArray,
                            idrAccessUnitAnnexB: ByteArray,
                            width: Int,
                            height: Int,
                            codec: TsVideoCodec,
                            maxWidthPx: Int,
                            jpegQuality: Int,
                        ): ByteArray? {
                            observedCodecs += codec
                            return if (codec == TsVideoCodec.AVC) byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x77) else null
                        }

                        override fun close() {}
                    }
                },
                playbackGate = gate,
                scope = scope,
                streamFetcher = StoryboardStreamFetcher { offset, maxLength, _, onChunk ->
                    val data = if (offset == 0L) {
                        headBytes
                    } else {
                        val estMs = slotByOffset[offset]?.estimatedMs ?: 1_000L
                        val (p, c1) = buildVideoPesPackets(videoPid, ptsTicks = 90_000L + estMs * 90L, annexBPayload = avcNal(5, 800), startCc = 0)
                        val (t, _) = buildVideoPesPackets(videoPid, ptsTicks = 90_000L + (estMs + 40L) * 90L, annexBPayload = avcNal(1, 32), startCc = c1, randomAccess = false)
                        p + t
                    }
                    val len = minOf(data.size, maxLength)
                    onChunk(data, len)
                    StoryboardRangeProbe(streamSizeBytes = streamSize, bytesConsumed = len, reachedEof = offset + len >= streamSize)
                },
            )
            engine.start()
            val timeline = withTimeout(5_000L) { engine.timeline.first { it.isComplete } }
            engine.close()

            assertEquals(setOf(TsVideoCodec.AVC), observedCodecs)
            assertEquals(slots.size, timeline.frames.size)
        } finally {
            scope.cancel()
        }
    }
}
