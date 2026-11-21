package dev.piko.ui.screens.player

import androidx.compose.ui.graphics.ImageBitmap
import dev.piko.shared.media.storyboard.InMemoryStoryboardCache
import dev.piko.shared.media.storyboard.StoryboardEngine
import dev.piko.shared.media.storyboard.StoryboardIdrDecoder
import dev.piko.shared.media.storyboard.StoryboardPlanner
import dev.piko.shared.media.storyboard.StoryboardPlaybackGate
import dev.piko.shared.media.storyboard.StoryboardSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class RapidScrubFilmstripTest {
    @Test
    fun buildsEvenlySpacedFilmstripTimestampsAcrossDurations() {
        assertTrue(buildFilmstripTimestamps(0L).isEmpty())

        // 45s 短片：自适应短间隔，生成 >= 15 帧
        val shortFrames = buildFilmstripTimestamps(45_000L)
        assertTrue(shortFrames.size in 15..25, "shortFrames=${shortFrames.size}")
        assertEquals(0L, shortFrames.first())
        assertTrue(shortFrames.last() <= 45_000L)

        // 10 分钟常规片：10s–30s 间隔，落在 20–60 帧区间
        val tenMinFrames = buildFilmstripTimestamps(600_000L)
        assertTrue(filmstripStepMillis(600_000L) in 10_000L..30_000L)
        assertTrue(tenMinFrames.size in 20..65, "tenMinFrames=${tenMinFrames.size}")

        // 24 分钟番剧：10s–30s 间隔，落在 20–65 帧区间
        val epFrames = buildFilmstripTimestamps(1_440_000L)
        assertTrue(filmstripStepMillis(1_440_000L) in 10_000L..30_000L)
        assertTrue(epFrames.size in 20..65, "epFrames=${epFrames.size}")

        // 2 小时电影：封顶约 60 帧且严格单调递增
        val movieFrames = buildFilmstripTimestamps(7_200_000L)
        assertTrue(movieFrames.size in 40..65, "movieFrames=${movieFrames.size}")
        for (i in 1 until movieFrames.size) {
            assertTrue(movieFrames[i] > movieFrames[i - 1])
        }
    }

    @Test
    fun enteringRapidScrubPausesPlaybackAndRestoresOnlyWhenPreviouslyPlaying() {
        var playing = true
        var pauseCalls = 0
        var resumeCalls = 0
        var committedMillis: Long? = null
        var currentSpeed = 1f

        val controller = PlaybackSpeedController(
            readSpeed = { currentSpeed },
            writeSpeed = { currentSpeed = it },
            readPositionMillis = { 30_000L },
            readDurationMillis = { 600_000L },
            onCommitRapidScrub = { committedMillis = it },
            allowRapidScrub = { true },
            readIsPlaying = { playing },
            onPauseForRapidScrub = {
                pauseCalls++
                playing = false
            },
            onResumeAfterRapidScrub = {
                resumeCalls++
                playing = true
            },
        )

        // 1. 播放中进入速览 -> 立即暂停；确认跳转 -> 提交位置并恢复播放
        controller.open(temporary = true)
        controller.choose(RAPID_SCRUB_VALUE)
        assertTrue(controller.isRapidScrubActive)
        assertFalse(playing)
        assertEquals(1, pauseCalls)
        assertEquals(0, resumeCalls)

        controller.commitRapidScrub(120_000L)
        assertEquals(120_000L, committedMillis)
        assertTrue(playing)
        assertEquals(1, resumeCalls)

        // 2. 播放中进入速览 -> 取消 -> 恢复播放，不提交跳转
        committedMillis = null
        controller.open(temporary = true)
        controller.choose(RAPID_SCRUB_VALUE)
        assertFalse(playing)
        assertEquals(2, pauseCalls)
        controller.cancel()
        assertEquals(null, committedMillis)
        assertTrue(playing)
        assertEquals(2, resumeCalls)

        // 3. 原本已暂停时进入速览 -> 不重复暂停，确认或取消退出后仍保持暂停
        playing = false
        controller.open(temporary = true)
        controller.choose(RAPID_SCRUB_VALUE)
        assertEquals(2, pauseCalls)
        controller.commitRapidScrub(240_000L)
        assertEquals(240_000L, committedMillis)
        assertFalse(playing)
        assertEquals(2, resumeCalls)

        // 4. 播放中从速览退回 3.5x -> 立即恢复播放
        playing = true
        controller.open(temporary = true)
        controller.choose(RAPID_SCRUB_VALUE)
        assertFalse(playing)
        controller.exitRapidScrubToMaxSpeed()
        assertFalse(controller.isRapidScrubActive)
        assertTrue(playing)
        assertEquals(3, resumeCalls)
        controller.cancel()

        // 5. 异步 isPlaying 延迟竞态：在 3.5x <-> 速览 边界快速往返时，readIsPlaying() 尚未观测到异步恢复，
        //    仍必须识别 resumeQueuedByScrub 再次暂停并在最终退出时恢复播放，绝不卡死在暂停态
        var asyncPlaying = true
        var asyncPauseCalls = 0
        var asyncResumeCalls = 0
        val asyncController = PlaybackSpeedController(
            readSpeed = { currentSpeed },
            writeSpeed = { currentSpeed = it },
            readPositionMillis = { 30_000L },
            readDurationMillis = { 600_000L },
            allowRapidScrub = { true },
            readIsPlaying = { asyncPlaying },
            onPauseForRapidScrub = {
                asyncPauseCalls++
                asyncPlaying = false
            },
            onResumeAfterRapidScrub = {
                // 模拟 mpv 异步回报：此时暂不把 asyncPlaying 立即置回 true
                asyncResumeCalls++
            },
        )
        asyncController.open(temporary = true)
        asyncController.choose(RAPID_SCRUB_VALUE)
        assertEquals(1, asyncPauseCalls)
        assertEquals(0, asyncResumeCalls)
        // 退回 3.5x：触发异步 resume（此时 asyncPlaying 仍滞后为 false）
        asyncController.exitRapidScrubToMaxSpeed()
        assertEquals(1, asyncResumeCalls)
        assertFalse(asyncPlaying)
        // 紧接着立即重新进入速览：必须识别已排队的 resume，再次下发暂停
        asyncController.choose(RAPID_SCRUB_VALUE)
        assertEquals(2, asyncPauseCalls)
        // 最终确认退出速览：必须恢复播放，不卡在暂停态
        asyncController.commitRapidScrub(90_000L)
        assertEquals(2, asyncResumeCalls)
    }

    @Test
    fun longPressAcceleratesImmediatelyToConfiguredBoostSpeedAndRestoresOnRelease() {
        var currentSpeed = 1.0f
        val writtenSpeeds = mutableListOf<Float>()
        val dialWrittenSpeeds = mutableListOf<Float>()

        val controller = PlaybackSpeedController(
            readSpeed = { currentSpeed },
            writeSpeed = {
                currentSpeed = it
                writtenSpeeds += it
            },
            defaultHoldSpeed = 2.5f,
            readPositionMillis = { 15_000L },
            readDurationMillis = { 600_000L },
            allowRapidScrub = { true },
            onDialSpeedWrite = { dialWrittenSpeeds += it },
        )

        // 1. 常规长按（无论控件隐藏还是可见）：立即加速到配置的 2.5x，松手恢复 1.0x，且全部走 dial 回调不触发旧 1x 闪烁
        controller.open(temporary = true, showDial = true)
        assertTrue(controller.active)
        assertEquals(2.5f, currentSpeed)
        assertEquals(listOf(2.5f), writtenSpeeds)
        assertEquals(listOf(2.5f), dialWrittenSpeeds)

        controller.releaseHoldDrag()
        assertFalse(controller.active)
        assertEquals(1.0f, currentSpeed)
        assertEquals(listOf(2.5f, 1.0f), writtenSpeeds)
        assertEquals(listOf(2.5f, 1.0f), dialWrittenSpeeds)

        // 2. 配置的 defaultHoldSpeed 异常 <= 1.0x 时自动回退到 2.0x，绝不卡死在 1.0x 无加速
        controller.defaultHoldSpeed = 1.0f
        writtenSpeeds.clear()
        controller.open(temporary = true, showDial = true)
        assertEquals(LONG_PRESS_BOOST_SPEED, currentSpeed)
        controller.releaseHoldDrag()
        assertEquals(1.0f, currentSpeed)

        // 3. 在拨盘中点选 1x 后，不得残留 lockedByHold=true 导致下次长按卡在 1x 不加速
        controller.defaultHoldSpeed = 2.0f
        controller.open(temporary = true, showDial = true)
        controller.choose(100)
        assertFalse(controller.lockedByHold)
        assertEquals(1.0f, currentSpeed)

        controller.open(temporary = true, showDial = true)
        assertEquals(2.0f, currentSpeed, "Next long-press after selecting 1x must still accelerate immediately")
        controller.releaseHoldDrag()
        assertEquals(1.0f, currentSpeed)

        // 4. 仅胶囊模式（如关闭拨盘或键盘右方向键长按 showDial=false）：立即加速且不混入速览档位
        controller.open(temporary = true, showDial = false)
        assertEquals(2.0f, currentSpeed)
        assertFalse(controller.selection?.choices.orEmpty().contains(RAPID_SCRUB_VALUE))
        controller.finish(cancel = false)
        assertEquals(1.0f, currentSpeed)
    }

    @Test
    fun draggingThrough3xIntoRapidScrubAndClosingForceRestores1xAndIgnoresStaleEchoes() {
        val timeSource = TestTimeSource()
        var playing = true
        var currentSpeed = 1.0f
        var effectiveNativeSpeed = 1.0f
        val events = mutableListOf<String>()
        val writtenSpeeds = mutableListOf<Float>()

        val controller = PlaybackSpeedController(
            readSpeed = { currentSpeed },
            writeSpeed = { next ->
                currentSpeed = next
                // 模拟 MediaMP 0.5.3 在暂停态会忽略 setRateImpl 的行为：验证进入速览时 1x 必须在 pause 之前写入
                if (playing) effectiveNativeSpeed = next
                writtenSpeeds += next
                events += "speed:$next"
            },
            defaultHoldSpeed = 2.0f,
            readPositionMillis = { 30_000L },
            readDurationMillis = { 600_000L },
            allowRapidScrub = { true },
            readIsPlaying = { playing },
            onPauseForRapidScrub = {
                playing = false
                events += "pause"
            },
            onResumeAfterRapidScrub = {
                playing = true
                events += "resume"
            },
            timeSource = timeSource,
        )

        fun dragToRapidScrub() {
            controller.open(temporary = true, showDial = true)
            assertTrue(controller.beginDrag(vertical = true))
            var alongDp = 0f
            // 沿拨盘持续向下拖动：2.0x -> 2.5x -> 3.0x -> 3.5x -> ∞ 速览
            repeat(12) {
                alongDp += 20f
                controller.move(alongDp = alongDp, acrossDp = 0f)
            }
            assertTrue(controller.isRapidScrubActive, "Drag must reach rapid scrub slot")
            controller.releaseHoldDrag()
            assertTrue(controller.rapidScrubBrowseMode)
        }

        // 1. 拖经 2x -> 2.5x -> 3x -> 3.5x -> 速览 -> 松手进入浏览态 -> cancel() 关闭：
        //    - 进入速览时先写 1.0x 再暂停，确保原生播放器不滞留在 3x/3.5x
        //    - cancel() 退出时必须强制再次写入 1.0x（即使 lastWrittenSpeed 已为 1.0x）
        dragToRapidScrub()
        assertTrue(writtenSpeeds.contains(3.0f), "Expected 3.0x during drag, got $writtenSpeeds")
        assertTrue(writtenSpeeds.contains(3.5f), "Expected 3.5x during drag, got $writtenSpeeds")
        assertEquals(1.0f, effectiveNativeSpeed, "Native speed must be restored to 1.0x BEFORE pausing for rapid scrub")
        val pauseIdx = events.lastIndexOf("pause")
        val prePauseRestoreIdx = events.lastIndexOf("speed:1.0")
        assertTrue(prePauseRestoreIdx in 0 until pauseIdx, "1.0x write must precede pause: $events")

        val writesBeforeCancel = writtenSpeeds.size
        controller.cancel()
        assertFalse(controller.active)
        assertTrue(playing)
        assertEquals(1.0f, controller.speed)
        assertEquals(1.0f, currentSpeed)
        assertEquals(writesBeforeCancel + 1, writtenSpeeds.size, "cancel() MUST force-write 1.0x on exit")
        assertEquals(1.0f, writtenSpeeds.last())

        // 2. 退出后 400ms 稳定窗内，即使先收到乐观 1.0x 再收到 libmpv/MediaMP 滞后的 3.0x / 3.5x 回声，
        //    也绝不能污染 controller.speed 或 baseSpeed
        timeSource += 16.milliseconds
        controller.sync(1.0f)
        timeSource += 40.milliseconds
        controller.sync(3.0f)
        assertEquals(1.0f, controller.speed)
        timeSource += 40.milliseconds
        controller.sync(3.5f)
        assertEquals(1.0f, controller.speed)

        // 3. 稳定窗内若后端 readSpeed 暂被过期回声污染为 3.0x，立刻再次长按并松手仍须回到 1.0x
        currentSpeed = 3.0f
        controller.open(temporary = true, showDial = true)
        controller.releaseHoldDrag()
        assertEquals(1.0f, controller.speed)
        assertEquals(1.0f, currentSpeed)

        // 4. 拖经 3x/3.5x -> 速览 -> commitRapidScrub() 跳转确认：同样强制写回 1.0x 并抵御 3.0x 回声
        timeSource += 450.milliseconds
        writtenSpeeds.clear()
        events.clear()
        dragToRapidScrub()
        val writesBeforeCommit = writtenSpeeds.size
        controller.commitRapidScrub(180_000L)
        assertFalse(controller.active)
        assertTrue(playing)
        assertEquals(1.0f, controller.speed)
        assertEquals(1.0f, currentSpeed)
        assertEquals(writesBeforeCommit + 1, writtenSpeeds.size, "commitRapidScrub() MUST force-write 1.0x on exit")
        assertEquals(1.0f, writtenSpeeds.last())

        timeSource += 50.milliseconds
        controller.sync(3.0f)
        assertEquals(1.0f, controller.speed)

        // 5. 显式 applySpeed 立即清除稳定窗并强制写入目标倍速；超时 400ms 后外部 sync 恢复正常生效
        controller.applySpeed(1.5f)
        assertEquals(1.5f, controller.speed)
        assertEquals(1.5f, currentSpeed)
        currentSpeed = 1.25f
        controller.sync(1.25f)
        assertEquals(1.25f, controller.speed)

        controller.open(temporary = true, showDial = true)
        controller.releaseHoldDrag()
        assertEquals(1.25f, controller.speed)
        timeSource += 450.milliseconds
        currentSpeed = 1.0f
        controller.sync(1.0f)
        assertEquals(1.0f, controller.speed)
    }

    @Test
    fun choosingRapidScrubDirectlyAndClosingAlwaysForceRestores1x() {
        var currentSpeed = 1.0f
        var playing = true
        val writtenSpeeds = mutableListOf<Float>()

        val controller = PlaybackSpeedController(
            readSpeed = { currentSpeed },
            writeSpeed = { next ->
                currentSpeed = next
                writtenSpeeds += next
            },
            defaultHoldSpeed = 2.0f,
            readPositionMillis = { 20_000L },
            readDurationMillis = { 600_000L },
            allowRapidScrub = { true },
            readIsPlaying = { playing },
            onPauseForRapidScrub = { playing = false },
            onResumeAfterRapidScrub = { playing = true },
        )

        // 通过 choose(RAPID_SCRUB_VALUE) 进入速览 -> cancel() 退出强制写回 1.0x
        controller.open(temporary = true, showDial = true)
        controller.choose(RAPID_SCRUB_VALUE)
        assertTrue(controller.isRapidScrubActive)
        assertTrue(controller.rapidScrubBrowseMode)
        val countBeforeCancel = writtenSpeeds.size
        controller.cancel()
        assertEquals(1.0f, currentSpeed)
        assertEquals(1.0f, controller.speed)
        assertTrue(playing)
        assertEquals(countBeforeCancel + 1, writtenSpeeds.size)
        assertEquals(1.0f, writtenSpeeds.last())

        // 通过 choose(RAPID_SCRUB_VALUE) 进入速览 -> commitRapidScrub() 退出强制写回 1.0x
        controller.open(temporary = true, showDial = true)
        controller.choose(RAPID_SCRUB_VALUE)
        val countBeforeCommit = writtenSpeeds.size
        controller.commitRapidScrub(90_000L)
        assertEquals(1.0f, currentSpeed)
        assertEquals(1.0f, controller.speed)
        assertTrue(playing)
        assertEquals(countBeforeCommit + 1, writtenSpeeds.size)
        assertEquals(1.0f, writtenSpeeds.last())
    }

    @Test
    fun scrubFrameCacheMicroBatchesRapidBurstUpdatesIntoFarFewerRevisions() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val durationMs = 120_000L
            val streamSize = 188L * 40_000L
            val slots = StoryboardPlanner.buildSlots(durationMillis = durationMs, streamSizeBytes = streamSize)
            val slotByOffset = slots.associateBy { it.byteOffset }
            val headerChunk = buildSyntheticHeaderTs()
            val source = StoryboardSource(
                fileId = "f-batch",
                gcid = "GCID_UI_BATCH_TEST",
                mediaId = "m360",
                url = "https://cdn.example/360.ts",
                width = 640,
                height = 360,
                durationMillis = durationMs,
                streamSizeBytes = streamSize,
            )
            val gate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = true, isPlaying = false, isPlaybackBusy = false))
            val engine = StoryboardEngine(
                source = source,
                cache = InMemoryStoryboardCache(),
                resolveStreamSize = { streamSize },
                fetchRange = { offset, length ->
                    if (offset == 0L) {
                        headerChunk.copyOfRange(0, minOf(length, headerChunk.size))
                    } else {
                        delay(5L)
                        val plan = slotByOffset[offset]
                        buildSyntheticSlotTs(ptsMs = plan?.estimatedMs ?: 1_000L, sizeBytes = length)
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
            val stubBitmap = object : ImageBitmap {
                override val width: Int = 4
                override val height: Int = 4
                override val config: androidx.compose.ui.graphics.ImageBitmapConfig = androidx.compose.ui.graphics.ImageBitmapConfig.Argb8888
                override val colorSpace: androidx.compose.ui.graphics.colorspace.ColorSpace = androidx.compose.ui.graphics.colorspace.ColorSpaces.Srgb
                override val hasAlpha: Boolean = true
                override fun readPixels(
                    buffer: IntArray,
                    startX: Int,
                    startY: Int,
                    width: Int,
                    height: Int,
                    bufferOffset: Int,
                    stride: Int,
                ) = Unit
                override fun prepareToDraw() = Unit
            }
            val frameCache = ScrubFrameCache(
                durationMillis = durationMs,
                engine = engine,
                decodeBitmap = { stubBitmap },
                scope = scope,
                batchWindowMillis = 65L,
            )
            try {
                withTimeout(5_000L) {
                    while (!frameCache.isComplete) delay(15L)
                }
                assertEquals(slots.size, frameCache.loadedCount)
                assertTrue(
                    frameCache.revision in 1..(slots.size / 2),
                    "Expected micro-batching to coalesce ${slots.size} decoded frames into far fewer UI revisions, got revision=${frameCache.revision}",
                )
                assertTrue(frameCache.displaySlots().all { it.isDecoded && it.bitmap != null })
            } finally {
                frameCache.clear()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun buildTsPacket(pid: Int, payload: ByteArray, payloadStart: Boolean, cc: Int): ByteArray {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (((if (payloadStart) 0x40 else 0) or ((pid ushr 8) and 0x1F))).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        if (payload.size >= 184) {
            pkt[3] = (0x10 or (cc and 0x0F)).toByte()
            payload.copyInto(pkt, 4, 0, 184)
        } else {
            pkt[3] = (0x30 or (cc and 0x0F)).toByte()
            val adaptLen = 183 - payload.size
            pkt[4] = adaptLen.toByte()
            if (adaptLen >= 1) pkt[5] = 0x00
            payload.copyInto(pkt, 5 + adaptLen, 0, payload.size)
        }
        return pkt
    }

    private fun encodePtsBytes(ticks: Long): ByteArray {
        val t = ticks and ((1L shl 33) - 1L)
        return byteArrayOf(
            (0x21L or ((t ushr 29) and 0x0EL)).toByte(),
            ((t ushr 22) and 0xFFL).toByte(),
            (0x01L or ((t ushr 14) and 0xFEL)).toByte(),
            ((t ushr 7) and 0xFFL).toByte(),
            (0x01L or ((t shl 1) and 0xFEL)).toByte(),
        )
    }

    private fun hevcNalBytes(nalType: Int, bodySize: Int): ByteArray = ByteArray(6 + bodySize).also { out ->
        out[3] = 1
        out[4] = ((nalType and 0x3F) shl 1).toByte()
        out[5] = 0x01
        for (i in 6 until out.size) out[i] = 0x5A
    }

    private fun videoPesTs(videoPid: Int, ptsTicks: Long, payload: ByteArray, startCc: Int): Pair<ByteArray, Int> {
        val pts = encodePtsBytes(ptsTicks)
        val header = byteArrayOf(
            0x00, 0x00, 0x01, 0xE0.toByte(),
            0x00, 0x00,
            0x80.toByte(), 0x80.toByte(), 0x05,
            pts[0], pts[1], pts[2], pts[3], pts[4],
        )
        val full = header + payload
        val packets = ArrayList<ByteArray>()
        var offset = 0
        var cc = startCc
        var first = true
        while (offset < full.size) {
            val take = minOf(184, full.size - offset)
            packets += buildTsPacket(videoPid, full.copyOfRange(offset, offset + take), first, cc)
            cc = (cc + 1) and 0x0F
            offset += take
            first = false
        }
        val out = ByteArray(packets.size * 188)
        packets.forEachIndexed { idx, pkt -> pkt.copyInto(out, idx * 188) }
        return out to cc
    }

    private fun buildSyntheticHeaderTs(videoPid: Int = 0x0100): ByteArray {
        val pat = buildTsPacket(
            pid = 0,
            payload = byteArrayOf(
                0x00, 0x00, 0xB0.toByte(), 13, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
                0x00, 0x01, (0xE0 or ((0x1000 ushr 8) and 0x1F)).toByte(), (0x1000 and 0xFF).toByte(),
                0, 0, 0, 0,
            ),
            payloadStart = true,
            cc = 0,
        )
        val pmt = buildTsPacket(
            pid = 0x1000,
            payload = byteArrayOf(
                0x00, 0x02, 0xB0.toByte(), 18, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
                0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00,
                0x24, (0xE0 or ((videoPid ushr 8) and 0x1F)).toByte(), (videoPid and 0xFF).toByte(),
                0xF0.toByte(), 0x00, 0, 0, 0, 0,
            ),
            payloadStart = true,
            cc = 0,
        )
        val au = hevcNalBytes(32, 8) + hevcNalBytes(33, 12) + hevcNalBytes(34, 8) + hevcNalBytes(19, 400)
        val (pes0, cc1) = videoPesTs(videoPid, 90_000L, au, 0)
        val (term, _) = videoPesTs(videoPid, 93_600L, hevcNalBytes(1, 32), cc1)
        val raw = pat + pmt + pes0 + term
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

    private fun buildSyntheticSlotTs(ptsMs: Long, sizeBytes: Int, videoPid: Int = 0x0100): ByteArray {
        val ticks = 90_000L + ptsMs * 90L
        val (pes, cc1) = videoPesTs(videoPid, ticks, hevcNalBytes(19, 400), 0)
        val (term, _) = videoPesTs(videoPid, ticks + 3_600L, hevcNalBytes(1, 32), cc1)
        val raw = pes + term
        return ByteArray(sizeBytes).also { dst ->
            raw.copyInto(dst, 0, 0, minOf(raw.size, dst.size))
            var p = ((minOf(raw.size, dst.size) + 187) / 188) * 188
            while (p + 188 <= dst.size) {
                dst[p] = 0x47
                dst[p + 1] = 0x1F
                dst[p + 2] = 0xFF.toByte()
                dst[p + 3] = 0x10
                p += 188
            }
        }
    }
}
