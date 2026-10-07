package dev.piko.desktop.ui.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.piko.ui.screens.player.PlayerLevelControl
import dev.piko.ui.theme.PikoTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 底栏的静音键、音量滑块与滚轮读写同一个 [PlayerLevelControl]。静音就是音量为 0，
 * 所以拖到底之后的静音键必须是「取消静音」，点了要出声。
 */
@OptIn(ExperimentalTestApi::class)
class PlayerVolumeControlTest {
    private val platform = DesktopPikoPlatform(DesktopSettingsStore(File.createTempFile("piko-settings", ".properties")))

    private class FakeVolume(initial: Float) : PlayerLevelControl {
        var level by mutableFloatStateOf(initial)
        override fun current(): Float = level
        override fun set(fraction: Float): Float = fraction.also { level = it }
    }

    private fun ComposeUiTest.showControls(volume: FakeVolume) = setContent {
        CompositionLocalProvider(LocalPikoPlatform provides platform) {
            PikoTheme {
                MobilePlayerControls(
                    title = "测试视频.mkv",
                    isLocalPlayback = false,
                    // 暂停：播放中控件会自动收起
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
                    volume = volume,
                )
            }
        }
    }

    @Test
    fun `muting and unmuting returns to the previous level`() = runComposeUiTest {
        val volume = FakeVolume(0.7f)
        showControls(volume)
        onNodeWithContentDescription("静音").performClick()
        assertEquals(0f, volume.level)
        onNodeWithContentDescription("取消静音").performClick()
        assertEquals(0.7f, volume.level)
    }

    @Test
    fun `the mute button brings sound back after dragging the slider to zero`() = runComposeUiTest {
        val volume = FakeVolume(0.7f)
        showControls(volume)
        onNodeWithContentDescription("音量").performMouseInput { click(centerLeft) }
        assertEquals(0f, volume.level)
        onNodeWithContentDescription("取消静音").performClick()
        assertTrue(volume.level > 0f)
    }

    @Test
    fun `the wheel over the slider changes the volume`() = runComposeUiTest {
        val volume = FakeVolume(0.5f)
        showControls(volume)
        onNodeWithContentDescription("音量").performMouseInput {
            moveTo(center)
            scroll(-1f)
        }
        assertEquals(0.55f, volume.level, 0.001f)
    }
}
