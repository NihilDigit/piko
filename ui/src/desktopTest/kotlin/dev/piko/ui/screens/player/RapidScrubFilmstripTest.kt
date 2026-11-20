package dev.piko.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
