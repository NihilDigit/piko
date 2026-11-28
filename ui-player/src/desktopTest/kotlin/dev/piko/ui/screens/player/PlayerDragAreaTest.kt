package dev.piko.ui.screens.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerLayoutPrefs
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.platform.FramelessWindow
import dev.piko.ui.platform.LocalFramelessWindow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoMotionScale
import dev.piko.ui.theme.PikoTheme
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class PlayerDragAreaTest {

    private class RecordingFramelessWindow : FramelessWindow {
        val dragAreas = LinkedHashMap<Any, Rect>()
        val exclusions = LinkedHashMap<Any, Rect>()
        override val isAlwaysOnTop: Boolean = false

        override fun updateDragArea(key: Any, bounds: Rect?) {
            if (bounds == null || bounds.isEmpty) dragAreas.remove(key) else dragAreas[key] = bounds
        }

        override fun updateExclusion(key: Any, bounds: Rect?) {
            if (bounds == null || bounds.isEmpty) exclusions.remove(key) else exclusions[key] = bounds
        }

        override fun close() = Unit
        override fun setAlwaysOnTop(onTop: Boolean) = Unit

        fun isDraggable(point: Offset): Boolean =
            exclusions.values.none { it.contains(point) } && dragAreas.values.any { it.contains(point) }
    }

    @Test
    fun `top bar drag area covers full edge padding and survives layouts without title or spacer`() = runOnTestScene { ctx ->
        val frameless = RecordingFramelessWindow()
        val pointerSource = PointerSource(initialTouchLike = false)
        // 自定义顶栏刻意移除 Title 与 Spacer，验证整条顶栏仍可拖动且按钮保留排除区
        val defaults = PlayerLayoutPrefs()
        val customPrefs = defaults.copy(
            videoLandscape = defaults.videoLandscape.copy(
                topBar = listOf(
                    PlayerPanelItem.Control(PlayerControlId.Settings),
                ),
            ),
        )
        val windowActions = PlayerWindowActions(
            isMaximized = false,
            isFullscreen = false,
            isAlwaysOnTop = false,
            showWindowButtons = true,
            onMinimize = {},
            onToggleMaximize = {},
            onToggleAlwaysOnTop = {},
            onClose = {},
        )
        val scene = ImageComposeScene(
            width = 800,
            height = 480,
            density = Density(1f),
            coroutineContext = ctx,
        ) {
            CompositionLocalProvider(
                LocalPikoPlatform provides PlayerGesturesTest.TestPikoPlatform(),
                LocalFramelessWindow provides frameless,
                LocalPointerSource provides pointerSource,
                LocalPlayerLayoutPrefs provides customPrefs,
            ) {
                PikoTheme(appearance = Appearance(einkMode = true)) {
                    Box(Modifier.fillMaxSize()) {
                        MobilePlayerControls(
                            title = "DragTest.mkv",
                            isLocalPlayback = false,
                            isPlaying = false,
                            isLoading = false,
                            positionMillis = 0L,
                            durationMillis = 120_000L,
                            bufferedPositionMillis = 30_000L,
                            playbackSpeed = 1.0f,
                            aspectRatio = PlayerAspectRatio.Fit,
                            qualityOptions = emptyList(),
                            currentQuality = null,
                            errorMessage = null,
                            resumedFromMillis = null,
                            windowActions = windowActions,
                            onPlayPause = {},
                            onSeek = {},
                            onSpeedChange = {},
                            onAspectRatioChange = {},
                            onQualityChange = {},
                            onRetry = {},
                            onRestartFromBeginning = {},
                            onBack = {},
                            onToggleFullscreen = {},
                        )
                    }
                }
            }
        }
        try {
            scene.render(1_000_000L)
            scene.render(20_000_000L)

            // 窗口最顶边 0..8dp 留白与顶栏中央空白处均可拖动
            assertTrue(frameless.isDraggable(Offset(400f, 3f)), "Topmost 3px edge must be draggable")
            assertTrue(frameless.isDraggable(Offset(400f, 24f)), "Top bar middle area must be draggable")
            assertTrue(frameless.isDraggable(Offset(4f, 24f)), "Top bar leading edge padding must be draggable")
            // 交互控件必须注册排除区，避免被顶栏拖动区吞噬点击
            assertTrue(frameless.exclusions.isNotEmpty(), "Top bar buttons must register caption exclusions")
            for (rect in frameless.exclusions.values) {
                assertFalse(
                    frameless.isDraggable(rect.center),
                    "Excluded button center ${rect.center} must not hit HTCAPTION",
                )
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `hidden chrome keeps invisible top edge drag strip and disables it in fullscreen`() = runOnTestScene { ctx ->
        val frameless = RecordingFramelessWindow()
        val pointerSource = PointerSource(initialTouchLike = true)
        var fullscreen by mutableStateOf(false)
        val scene = ImageComposeScene(
            width = 800,
            height = 480,
            density = Density(1f),
            coroutineContext = ctx,
        ) {
            CompositionLocalProvider(
                LocalPikoPlatform provides PlayerGesturesTest.TestPikoPlatform(),
                LocalFramelessWindow provides frameless,
                LocalPointerSource provides pointerSource,
            ) {
                PikoTheme(appearance = Appearance(einkMode = true)) {
                    Box(Modifier.fillMaxSize()) {
                        MobilePlayerControls(
                            title = "HiddenStrip.mkv",
                            isLocalPlayback = false,
                            isPlaying = true,
                            isLoading = false,
                            positionMillis = 10_000L,
                            durationMillis = 120_000L,
                            bufferedPositionMillis = 30_000L,
                            playbackSpeed = 1.0f,
                            aspectRatio = PlayerAspectRatio.Fit,
                            qualityOptions = emptyList(),
                            currentQuality = null,
                            errorMessage = null,
                            resumedFromMillis = null,
                            isFullscreen = fullscreen,
                            onPlayPause = {},
                            onSeek = {},
                            onSpeedChange = {},
                            onAspectRatioChange = {},
                            onQualityChange = {},
                            onRetry = {},
                            onRestartFromBeginning = {},
                            onBack = {},
                            onToggleFullscreen = {},
                        )
                    }
                }
            }
        }
        try {
            var nowNs = 1_000_000L
            scene.render(nowNs)
            nowNs += 20_000_000L
            scene.render(nowNs)

            // 触摸态单击空白处收起控件栏
            scene.sendPointerEvent(
                eventType = PointerEventType.Press,
                position = Offset(200f, 240f),
                timeMillis = 50L,
                type = PointerType.Touch,
                button = PointerButton.Primary,
            )
            scene.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = Offset(200f, 240f),
                timeMillis = 80L,
                type = PointerType.Touch,
                button = PointerButton.Primary,
            )
            // 等待单击确认（避开双击判定窗口）与退场动画推进
            kotlinx.coroutines.delay(450L)
            repeat(6) {
                nowNs += 100_000_000L
                scene.render(nowNs)
            }

            // 控件栏隐藏后，顶边 32dp 隐形条仍可拖动，画面主体区域不可拖动
            assertTrue(frameless.isDraggable(Offset(400f, 16f)), "Hidden top edge strip (16px) must be draggable")
            assertFalse(frameless.isDraggable(Offset(400f, 80f)), "Video canvas below top strip must stay HTCLIENT")

            // 切入全屏后立即撤销隐形拖动条
            fullscreen = true
            nowNs += 20_000_000L
            scene.render(nowNs)
            nowNs += 20_000_000L
            scene.render(nowNs)
            assertFalse(frameless.isDraggable(Offset(400f, 16f)), "Fullscreen must disable top edge drag strip")
        } finally {
            scene.close()
        }
    }

    private fun runOnTestScene(block: suspend (kotlin.coroutines.CoroutineContext) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val motionScale = PikoMotionScale()
        try {
            runBlocking(dispatcher + motionScale) { block(coroutineContext) }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }
}
