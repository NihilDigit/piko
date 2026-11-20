package dev.piko.desktop.ui.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.storyboard.InMemoryStoryboardCache
import dev.piko.shared.media.storyboard.StoryboardBundle
import dev.piko.shared.media.storyboard.StoryboardEngine
import dev.piko.shared.media.storyboard.StoryboardFrame
import dev.piko.shared.media.storyboard.StoryboardPlaybackGate
import dev.piko.shared.media.storyboard.StoryboardSource
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.piko.ui.screens.player.ScrubFrameCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** 鼠标停在进度条上时，指针上方出现该处的时间与完整 16:9 预览卡：这条路只有真实的悬停事件序列才走得到。 */
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

    @Test
    fun `hovering a storyboard-enabled seek bar renders a full 16 by 9 preview card without height clipping`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = InMemoryStoryboardCache()
        runBlocking {
            cache.write(
                StoryboardBundle(
                    gcid = "HOVER_CARD_TEST",
                    durationMillis = 100_000L,
                    width = 384,
                    height = 216,
                    completed = true,
                    lastAccessEpochMillis = 1_000L,
                    frames = listOf(
                        StoryboardFrame(0, 0L, byteArrayOf(1, 2, 3)),
                        StoryboardFrame(1, 50_000L, byteArrayOf(4, 5, 6)),
                    ),
                ),
            )
        }
        val engine = StoryboardEngine(
            source = StoryboardSource(
                fileId = "hover-f1",
                gcid = "HOVER_CARD_TEST",
                mediaId = "m480",
                url = "https://cdn.example/480.ts",
                width = 640,
                height = 360,
                durationMillis = 100_000L,
                streamSizeBytes = 188L * 1000L,
            ),
            cache = cache,
            resolveStreamSize = { 188L * 1000L },
            fetchRange = { _, _ -> null },
            decoderFactory = { null },
            playbackGate = MutableStateFlow(StoryboardPlaybackGate(firstFrameReady = true)),
            scope = scope,
        )
        val frameCache = ScrubFrameCache(
            durationMillis = 100_000L,
            engine = engine,
            decodeBitmap = { ImageBitmap(384, 216) },
            scope = scope,
        )
        try {
            setContent {
                MobilePlayerControls(
                    title = "测试视频.mkv",
                    isLocalPlayback = false,
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
                    scrubFrameCache = frameCache,
                )
            }
            onNodeWithContentDescription("播放进度").performMouseInput { moveTo(center) }
            waitForIdle()
            onNodeWithText("00:50", substring = true).assertExists()
            val bounds = onNodeWithTag("seek-preview-card").getUnclippedBoundsInRoot()
            assertTrue(bounds.width >= 160.dp, "Expected preview card width >= 160dp, got ${bounds.width}")
            assertTrue(bounds.height >= 90.dp, "Expected full 16:9 preview card height >= 90dp (not squished to 32dp!), got ${bounds.height}")
        } finally {
            frameCache.clear()
            scope.cancel()
        }
    }
}
