package dev.piko.desktop.ui.player

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.ui.screens.player.MobilePlayerControls
import kotlin.test.Test

/** 鼠标停在进度条上时，指针上方出现该处的时间：这条路只有真实的悬停事件序列才走得到。 */
@OptIn(ExperimentalTestApi::class)
class SeekBarHoverTest {

    @Test
    fun `hovering the seek bar shows the time under the pointer`() = runComposeUiTest {
        setContent {
            MobilePlayerControls(
                title = "测试视频.mkv",
                isLocalPlayback = false,
                // 暂停：播放中控件 4.5 秒后自动收起，只要有动画在跑，测试时钟等空闲时就可能推过这个点
                isPlaying = false,
                isLoading = false,
                positionMillis = 10_000L,
                durationMillis = 100_000L,
                bufferedPositionMillis = 10_000L,
                playbackSpeed = 1f,
                aspectRatio = PlayerAspectRatio.Fit,
                qualityOptions = emptyList(),
                currentQuality = null,
                errorMessage = null,
                resumedFromMillis = null,
                onPlayPause = {},
                onSeek = {},
                onSpeedChange = {},
                onAspectRatioChange = {},
                onQualityChange = {},
                onRetry = {},
                onRestartFromBeginning = {},
                onBack = {},
                onToggleFullscreen = {},
                seekThumbOnHoverOnly = true,
            )
        }
        onNodeWithContentDescription("播放进度").performMouseInput { moveTo(center) }
        waitForIdle()
        // 中点是 50 秒；底栏里的当前时间是 0:10，不会与它混淆
        onNodeWithText("00:50", substring = true).assertExists()
    }
}
