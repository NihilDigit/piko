package dev.piko.ui.screens.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.piko.shared.media.player.PlayerAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 手势层与控件层之间的约定：这些行为只能在真实的指针事件序列下验证，
 * 回调参数错了界面上照样有反馈，肉眼不容易发现。
 */
@RunWith(AndroidJUnit4::class)
class MobilePlayerControlsTest {
    @get:Rule
    val rule = createComposeRule()

    private val seeks = mutableListOf<Long>()
    private val speeds = mutableListOf<Float>()
    private var playPauseCount = 0

    private fun setControls(speed: Float = 1f) {
        rule.setContent {
            MobilePlayerControls(
                title = "测试视频.mkv",
                isLocalPlayback = false,
                isPlaying = true,
                isLoading = false,
                positionMillis = START_MILLIS,
                durationMillis = DURATION_MILLIS,
                bufferedPositionMillis = START_MILLIS,
                playbackSpeed = speed,
                aspectRatio = PlayerAspectRatio.Fit,
                qualityOptions = emptyList(),
                currentQuality = null,
                errorMessage = null,
                resumedFromMillis = null,
                onPlayPause = { playPauseCount += 1 },
                onSeek = { seeks += it },
                onSpeedChange = { speeds += it },
                onAspectRatioChange = {},
                onQualityChange = {},
                onRetry = {},
                onRestartFromBeginning = {},
                onBack = {},
                onToggleFullscreen = {},
            )
        }
    }

    @Test
    fun doubleTapSeeksTowardTheTappedSide() {
        setControls()
        // 锁定键在右缘中部，GESTURE_Y_FRACTION 的高度已避开它
        rule.onRoot().performTouchInput { doubleClick(Offset(width * 0.25f, height * GESTURE_Y_FRACTION)) }
        rule.onRoot().performTouchInput { doubleClick(Offset(width * 0.9f, height * GESTURE_Y_FRACTION)) }
        rule.waitForIdle()

        assertEquals(START_MILLIS - SEEK_STEP_MILLIS, seeks[0])
        // 第二次在另一侧，不与第一次累加，从当前位置起算
        assertEquals(START_MILLIS + SEEK_STEP_MILLIS, seeks[1])
        assertEquals(0, playPauseCount)
    }

    @Test
    fun singleTapAfterDoubleTapKeepsSeeking() {
        setControls()
        rule.onRoot().performTouchInput { doubleClick(Offset(width * 0.9f, height * GESTURE_Y_FRACTION)) }
        rule.onRoot().performTouchInput { click(Offset(width * 0.9f, height * GESTURE_Y_FRACTION)) }
        rule.waitForIdle()

        assertEquals(listOf(START_MILLIS + SEEK_STEP_MILLIS, START_MILLIS + 2 * SEEK_STEP_MILLIS), seeks)
        // 接着点的这一下是进退，不是单击：控件不该被收起
        rule.onNodeWithContentDescription("返回").assertExists()
    }

    @Test
    fun horizontalDragPreviewsAndCommitsOnlyOnRelease() {
        setControls()
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.3f, height * GESTURE_Y_FRACTION))
            moveBy(Offset(width * 0.25f, 0f))
        }
        rule.waitForIdle()
        assertTrue("拖动中不应 seek", seeks.isEmpty())

        // 拖动中底栏的进度条停在目标处，它的状态描述是「mm:ss / mm:ss」，前一半是目标位置
        val readout = rule.onNodeWithContentDescription("播放进度")
            .fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription]
        val previewSeconds = readout.substringBefore('/').trim().split(':').fold(0L) { total, part -> total * 60 + part.toLong() }
        assertTrue("预览应在起点之后", previewSeconds * 1000 > START_MILLIS)

        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()

        val committed = seeks.single()
        // 读数显示的是向下取整的整秒，提交值应落在同一秒内
        assertTrue(committed in previewSeconds * 1000 until (previewSeconds + 1) * 1000)
    }

    @Test
    fun longPressBoostRestoresPreviousSpeed() {
        setControls(speed = 1.25f)
        rule.onRoot().performTouchInput { down(Offset(width * 0.5f, height * 0.4f)) }
        // 长按判定走的是测试时钟，要手动推过 longPressTimeout
        rule.mainClock.advanceTimeBy(LONG_PRESS_WAIT_MILLIS)
        assertEquals(listOf(LONG_PRESS_BOOST_SPEED), speeds)

        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf(LONG_PRESS_BOOST_SPEED, 1.25f), speeds)
        assertEquals(0, playPauseCount)
    }

    @Test
    fun tapAfterAutoHideRevealsControlsWithoutPausing() {
        setControls()
        rule.onNodeWithContentDescription("返回").assertExists()

        rule.mainClock.advanceTimeBy(10_000)
        rule.onNodeWithContentDescription("返回").assertDoesNotExist()

        // 画面中央是控件展开后播放键的位置，隐藏时点在这里只能唤出控件
        rule.onRoot().performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(1_000)

        rule.onNodeWithContentDescription("返回").assertExists()
        assertEquals(0, playPauseCount)
    }

    @Test
    fun doubleTapPauseHasAnIndicatorWhenControlsAreHidden() {
        setControls()
        rule.mainClock.advanceTimeBy(10_000)
        rule.onRoot().performTouchInput { doubleClick(Offset(width * 0.5f, height * GESTURE_Y_FRACTION)) }
        rule.mainClock.advanceTimeBy(80)
        assertEquals(1, playPauseCount)
        rule.onNodeWithContentDescription("已暂停").assertExists()
    }

    private companion object {
        const val START_MILLIS = 60_000L
        const val DURATION_MILLIS = 600_000L
        const val LONG_PRESS_WAIT_MILLIS = 1_000L

        // 手势要落在手势层上。控件初始可见，垂直居中那一行是后退、播放、前进按钮，
        // 点在那一行会被按钮接走：双击变成两次按钮 seek，拖动也到不了手势层
        const val GESTURE_Y_FRACTION = 0.3f
    }
}
