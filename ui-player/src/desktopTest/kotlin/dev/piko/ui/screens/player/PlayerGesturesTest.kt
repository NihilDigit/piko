package dev.piko.ui.screens.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlayerGestureConfig
import dev.piko.shared.media.player.VerticalSwipeAction
import dev.piko.ui.adaptive.FormFactor
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.trackInputModality
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.DiskSpace
import dev.piko.ui.platform.DownloadLocationPicker
import dev.piko.ui.platform.ExternalVideoPlayer
import dev.piko.ui.platform.LinkAssociation
import dev.piko.ui.platform.LocalFileActions
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.platform.UploadPicker
import dev.piko.ui.platform.VideoPreviewSupport
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.MotionStyle
import dev.piko.ui.theme.PikoMotionScale
import dev.piko.ui.theme.PikoTheme
import dev.piko.update.AppUpdateService
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PlayerGesturesTest {
    @BeforeTest
    fun setUpLocale() {
        PikoLocale.apply(AppLanguage.ZH_CN)
    }

    private class TestPikoPlatform(
        override val motionScale: PikoMotionScale = PikoMotionScale(),
    ) : PikoPlatform {
        override val appVersion: String = "test"
        override val updater: AppUpdateService? = null
        override val supportsDynamicColor: Boolean = false
        @Composable override fun dynamicColorScheme(dark: Boolean): ColorScheme = error("unused")
        override val supportsBlur: Boolean = false
        override val formFactor: FormFactor = FormFactor.Desktop
        override val motionStyle: MotionStyle = MotionStyle.Standard
        override val deviceSummary: String = "test"
        override suspend fun exportLog(fileName: String, content: String): Boolean = false
        override fun openUrl(url: String) = Unit
        @Composable override fun isImeVisible(): Boolean = false
        override fun readClipboardText(): String? = null
        override fun copyToClipboard(label: String, text: String) = Unit
        override val localFiles: LocalFileActions = object : LocalFileActions {
            override fun thumbnailModel(path: String): Any? = null
            override fun exists(path: String): Boolean = false
            override fun openExternally(path: String, isMedia: Boolean) = Unit
            override fun openContainingFolder(path: String) = Unit
            override val canShare: Boolean = false
            override fun share(path: String, isMedia: Boolean) = Unit
        }
        override val downloadLocation: DownloadLocationPicker = object : DownloadLocationPicker {
            override val description: String = ""
            override fun displayName(storedPath: String): String = storedPath
            override fun diskSpace(storedPath: String): DiskSpace? = null
            @Composable override fun rememberLauncher(onPicked: (String) -> Unit): () -> Unit = {}
        }
        override val uploadPicker: UploadPicker = object : UploadPicker {
            @Composable override fun rememberFilesLauncher(onPicked: (List<String>) -> Unit): () -> Unit = {}
            @Composable override fun rememberFolderLauncher(onPicked: (String) -> Unit): () -> Unit = {}
        }
        override val externalPlayer: ExternalVideoPlayer? = null
        override val videoPreview: VideoPreviewSupport? = null
        override val linkAssociation: LinkAssociation? = null
        @Composable
        override fun FullscreenDialog(
            onDismiss: () -> Unit,
            immersive: Boolean,
            systemBarsVisible: Boolean,
            content: @Composable () -> Unit,
        ) {
            content()
        }
        @Composable override fun ListScrollbar(state: LazyListState, modifier: Modifier) = Unit
        @Composable override fun ListScrollbar(state: LazyGridState, modifier: Modifier) = Unit
    }

    private class FastGestureViewConfiguration(
        private val base: ViewConfiguration,
    ) : ViewConfiguration by base {
        override val longPressTimeoutMillis: Long = 70L
        override val doubleTapTimeoutMillis: Long = 55L
        override val doubleTapMinTimeMillis: Long = 10L
        override val touchSlop: Float = 8f
    }

    private class FakeLevelControl(initial: Float) : PlayerLevelControl {
        var value by mutableFloatStateOf(initial)
        override fun current(): Float = value
        override fun set(fraction: Float): Float {
            value = fraction.coerceIn(0f, 1f)
            return value
        }
    }

    @Composable
    private fun ProvideTestPlayerEnvironment(
        motionScale: PikoMotionScale,
        appearance: Appearance = Appearance(),
        content: @Composable () -> Unit,
    ) {
        val platform = remember(motionScale) { TestPikoPlatform(motionScale) }
        CompositionLocalProvider(LocalPikoPlatform provides platform) {
            PikoTheme(appearance = appearance) {
                val base = LocalViewConfiguration.current
                val fast = remember(base) { FastGestureViewConfiguration(base) }
                CompositionLocalProvider(LocalViewConfiguration provides fast, content = content)
            }
        }
    }

    private fun ImageComposeScene.hasContentDescription(expected: String): Boolean {
        fun visit(node: SemanticsNode): Boolean {
            val descriptions = node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
            if (descriptions?.any { it.contains(expected) } == true) return true
            return node.children.any(::visit)
        }
        return semanticsOwners.any { owner -> visit(owner.unmergedRootSemanticsNode) }
    }

    private fun <T> withSingleThreadScene(
        block: suspend (coroutineContext: kotlin.coroutines.CoroutineContext, motionScale: PikoMotionScale) -> T,
    ): T {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "player-gesture-test").apply { isDaemon = true }
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val motionScale = PikoMotionScale()
        return try {
            runBlocking(dispatcher + motionScale) { block(coroutineContext, motionScale) }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    /**
     * 回归测试 1（Root Cause A）：
     * 当播放器控件收起后（`controlsVisible == false`），不仅两侧双击快进与连点续进退正常工作，
     * 在画面正中央（原 `PlayerCenterCluster` 64×56dp 常驻 `hoverable` 盲区）长按也必须立即打开 Speed Dial、
     * 支持跟手滑档并在松手后恢复原倍速；即使正中展示被动瞬态读数（如双击暂停后的「已暂停」胶囊），
     * 正中央紧接着的长按倍速与单击唤出控件也不得被阻断。
     */
    @Test
    fun hiddenControlsAllowCenterLongPressSpeedDialAndDoubleTapSeek() = withSingleThreadScene { ctx, motionScale ->
        var playing by mutableStateOf(true)
        var speed by mutableFloatStateOf(1.25f)
        var position by mutableStateOf(60_000L)
        val seeks = mutableListOf<Long>()
        val speedWrites = mutableListOf<Float>()
        var playPauseCount = 0
        val pointerSource = PointerSource(initialTouchLike = true)

        var virtualNano = 1_000_000L
        var eventTimeMs = 100L
        val scene = ImageComposeScene(width = 660, height = 360, coroutineContext = ctx) {
            CompositionLocalProvider(LocalPointerSource provides pointerSource) {
                ProvideTestPlayerEnvironment(motionScale) {
                    Box(Modifier.fillMaxSize().trackPointerSource(pointerSource)) {
                        MobilePlayerControls(
                            title = "测试剧集 01.mkv",
                            isLocalPlayback = false,
                            isPlaying = playing,
                            isLoading = false,
                            positionMillis = position,
                            durationMillis = 600_000L,
                            bufferedPositionMillis = 120_000L,
                            playbackSpeed = speed,
                            aspectRatio = PlayerAspectRatio.Fit,
                            qualityOptions = emptyList(),
                            currentQuality = null,
                            errorMessage = null,
                            resumedFromMillis = null,
                            longPressSpeed = 2.5f,
                            onPlayPause = {
                                playPauseCount++
                                playing = !playing
                            },
                            onSeek = { target ->
                                seeks += target
                                position = target
                            },
                            onSpeedChange = { next ->
                                speedWrites += next
                                speed = next
                            },
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

        suspend fun pumpFrames(count: Int = 4, stepMs: Long = 16L) {
            repeat(count) {
                virtualNano += stepMs * 1_000_000L
                scene.render(virtualNano).close()
                delay(5)
            }
        }

        fun touch(type: PointerEventType, x: Float, y: Float) {
            eventTimeMs += 10L
            scene.sendPointerEvent(type, Offset(x, y), timeMillis = eventTimeMs, type = PointerType.Touch)
        }

        try {
            pumpFrames(count = 20, stepMs = 40L)
            assertTrue(scene.hasContentDescription("返回"), "Initial controls should be visible")

            // 1. 单击空白区域收起播放器控件：
            // 1a. 在 MorphContainer 退场动画尚未结束（刚起跳 2 帧）时立即正中央长按，也绝不可被已禁用交互的 MorphContainer 吞掉
            touch(PointerEventType.Press, 220f, 100f)
            touch(PointerEventType.Release, 220f, 100f)
            delay(95)
            pumpFrames(count = 2, stepMs = 16L)

            touch(PointerEventType.Press, 330f, 180f)
            delay(110)
            pumpFrames()
            assertEquals(2.5f, speed, "Center long-press during control fade-out must not be blocked by MorphContainer")
            touch(PointerEventType.Release, 330f, 180f)
            pumpFrames(count = 35, stepMs = 50L)
            assertEquals(1.25f, speed)
            assertFalse(scene.hasContentDescription("返回"), "Controls must be hidden after single tap and exit spring")
            assertEquals(0, playPauseCount)

            // 2. 控件隐藏态下右侧双击快进 + 连点续进退：正常累加且不唤出控件
            touch(PointerEventType.Press, 580f, 180f)
            touch(PointerEventType.Release, 580f, 180f)
            delay(20)
            touch(PointerEventType.Press, 580f, 180f)
            touch(PointerEventType.Release, 580f, 180f)
            pumpFrames()
            assertEquals(listOf(70_000L), seeks)
            assertFalse(scene.hasContentDescription("返回"))

            // 读数停留期间同侧单点立即继续快进
            delay(25)
            touch(PointerEventType.Press, 580f, 180f)
            touch(PointerEventType.Release, 580f, 180f)
            pumpFrames()
            assertEquals(listOf(70_000L, 80_000L), seeks)

            // 3. 控件隐藏态下直接在画面正中央 (330, 180) 长按：必须立即进入 Speed Dial (2.5x)，上滑切到 2.0x，松手恢复 1.25x
            touch(PointerEventType.Press, 330f, 180f)
            delay(110)
            pumpFrames()
            assertEquals(2.5f, speed, "Center long-press while controls are hidden must activate Speed Dial boost")
            assertTrue(scene.hasContentDescription("倍速"), "SpeedDialStrip semantics must be mounted while holding")

            // 沿横屏竖直拨盘上滑一档（2.5x -> 2.0x），确认长按命中链未被卸载或取消
            touch(PointerEventType.Move, 330f, 132f)
            pumpFrames()
            assertEquals(2.0f, speed, "Dragging upward during hidden-controls Speed Dial must step speed to 2.0x")

            touch(PointerEventType.Release, 330f, 132f)
            pumpFrames()
            assertEquals(1.25f, speed, "Plain release must restore pre-hold speed 1.25x")
            assertEquals(0, playPauseCount, "Releasing Speed Dial must never trigger play/pause")
            assertFalse(scene.hasContentDescription("返回"), "Releasing Speed Dial must keep controls hidden")

            // 4. 控件隐藏态下双击正中央暂停（弹出被动「已暂停」中心读数），紧接着在正中央长按仍须正常激活 Speed Dial
            touch(PointerEventType.Press, 330f, 180f)
            touch(PointerEventType.Release, 330f, 180f)
            delay(20)
            touch(PointerEventType.Press, 330f, 180f)
            touch(PointerEventType.Release, 330f, 180f)
            pumpFrames()
            assertEquals(1, playPauseCount)
            assertFalse(playing)
            assertTrue(scene.hasContentDescription("已暂停"))

            touch(PointerEventType.Press, 330f, 180f)
            delay(110)
            pumpFrames()
            assertEquals(2.5f, speed, "Passive center readout flash must not block center long-press Speed Dial")
            touch(PointerEventType.Release, 330f, 180f)
            pumpFrames()
            assertEquals(1.25f, speed)
            assertTrue(speedWrites.isNotEmpty())
        } finally {
            scene.close()
        }
    }

    /**
     * 回归测试 2（Root Cause B + 桌面键鼠/触屏双栖切换）：
     * 模拟 `VideoPlayerWindow` 真实配置（`idleCursor != null` 且初始 `PointerSource(initialTouchLike = false)`）：
     * 等待 4.5s 自动隐藏控件并进入 `isMouseIdle = true`（`hideCursor = true`）后：
     * 1. 鼠标静止不移动直接按下长按：400ms/70ms 激活 Speed Dial 使 `hideCursor` 翻转为 `false` 时，
     *    父级 `pointerHoverIcon` 就地换参不卸载，绝不触发 `HitPathTracker` 取消，继续拖动并横滑锁定 3.0x 成功；
     * 2. 紧接着 Windows 触屏首次落下手指长按（`pointerSource.isTouchLike` 由 `false` 切为 `true`）：
     *    `focusProperties` 不改变修饰符链长度，长按正常从已锁定的 3.0x 起手，松手解除锁定恢复 1.0x。
     */
    @Test
    fun desktopIdleCursorAndMouseToTouchTransitionNeverCancelLongPressSpeedDial() = withSingleThreadScene { ctx, motionScale ->
        var speed by mutableFloatStateOf(1.0f)
        var playPauseCount = 0
        val pointerSource = PointerSource(initialTouchLike = false)

        var virtualNano = 1_000_000L
        var eventTimeMs = 100L
        val scene = ImageComposeScene(width = 660, height = 360, coroutineContext = ctx) {
            CompositionLocalProvider(LocalPointerSource provides pointerSource) {
                ProvideTestPlayerEnvironment(motionScale, appearance = Appearance(einkMode = true)) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .trackInputModality()
                            .trackPointerSource(pointerSource),
                    ) {
                        MobilePlayerControls(
                            title = "DesktopCursorIdle.mkv",
                            isLocalPlayback = false,
                            isPlaying = true,
                            isLoading = false,
                            positionMillis = 15_000L,
                            durationMillis = 300_000L,
                            bufferedPositionMillis = 60_000L,
                            playbackSpeed = speed,
                            aspectRatio = PlayerAspectRatio.Fit,
                            qualityOptions = emptyList(),
                            currentQuality = null,
                            errorMessage = null,
                            resumedFromMillis = null,
                            longPressSpeed = 2.5f,
                            idleCursor = PointerIcon.Crosshair,
                            onPlayPause = { playPauseCount++ },
                            onSeek = {},
                            onSpeedChange = { speed = it },
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

        suspend fun pumpFrames(count: Int = 4, stepMs: Long = 16L) {
            repeat(count) {
                virtualNano += stepMs * 1_000_000L
                scene.render(virtualNano).close()
                delay(5)
            }
        }

        try {
            pumpFrames()
            assertTrue(scene.hasContentDescription("返回"))

            // 等待超过 4.5s（兼容 Windows 15.6ms 定时器粒度）让 controlsVisible = false 且 isMouseIdle = true
            var waitedMs = 0L
            while (scene.hasContentDescription("返回") && waitedMs < 6_500L) {
                delay(250)
                waitedMs += 250L
                pumpFrames(count = 4, stepMs = 32L)
            }
            delay(300)
            pumpFrames(count = 4, stepMs = 32L)
            assertFalse(scene.hasContentDescription("返回"), "Controls must auto-hide after idle timeout")
            assertFalse(pointerSource.isTouchLike)

            // 1. 鼠标静止状态下直接左键长按（触发 speedController.active = true -> hideCursor 由 true 变 false）
            eventTimeMs += 20L
            scene.sendPointerEvent(
                eventType = PointerEventType.Press,
                position = Offset(220f, 180f),
                timeMillis = eventTimeMs,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            delay(110)
            pumpFrames(count = 6)
            assertEquals(2.5f, speed, "Mouse long-press while cursor & controls are hidden must NOT self-cancel")
            assertTrue(scene.hasContentDescription("倍速"), "SpeedDialOverlay must stay active across hideCursor flip")

            // 下滑升档至 3.0x，再向右跨轴滑动锁定 3.0x，左键抬起保持 3.0x 且不触发单击暂停
            eventTimeMs += 20L
            scene.sendPointerEvent(
                eventType = PointerEventType.Move,
                position = Offset(220f, 228f),
                timeMillis = eventTimeMs,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = true),
            )
            pumpFrames()
            assertEquals(3.0f, speed)

            eventTimeMs += 20L
            scene.sendPointerEvent(
                eventType = PointerEventType.Move,
                position = Offset(266f, 232f),
                timeMillis = eventTimeMs,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = true),
            )
            pumpFrames()

            eventTimeMs += 20L
            scene.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = Offset(266f, 232f),
                timeMillis = eventTimeMs,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = false),
                button = PointerButton.Primary,
            )
            pumpFrames()
            assertEquals(3.0f, speed, "Cross-axis locked Speed Dial must persist 3.0x after mouse release")
            assertEquals(0, playPauseCount, "Releasing mouse hold must not toggle play/pause")

            // 2. 紧接着以 Windows 触摸屏手指按下长按（pointerSource.isTouchLike 由 false 切换为 true）
            eventTimeMs += 20L
            scene.sendPointerEvent(
                eventType = PointerEventType.Press,
                position = Offset(330f, 180f),
                timeMillis = eventTimeMs,
                type = PointerType.Touch,
            )
            delay(110)
            pumpFrames()
            assertTrue(pointerSource.isTouchLike)
            assertEquals(3.0f, speed, "Touch hold after lock starts from locked 3.0x without structural cancel")
            assertTrue(scene.hasContentDescription("倍速"))

            // 未重锁直接抬指：解除长按锁定并恢复初始基准 1.0x
            eventTimeMs += 20L
            scene.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = Offset(330f, 180f),
                timeMillis = eventTimeMs,
                type = PointerType.Touch,
            )
            pumpFrames()
            assertEquals(1.0f, speed, "Plain touch release after prior hold-lock must restore base 1.0x")
            assertEquals(0, playPauseCount)
        } finally {
            scene.close()
        }
    }

    /**
     * 回归测试 3（合成取消 `onCancelPointerInput` 安全回滚）：
     * 当系统手势拦截或父节点移除引发 `onCancelPointerInput()`（`isInitiallyConsumed = true`）时：
     * - Speed Dial 即使已滑入跨轴锁定预备态，也必须执行 `cancel()` 还原 `preOpenSpeed`，绝不误锁定；
     * - 横划进退（`Seek`）与纵划切集（`EpisodeSwipe`）必须静默撤销，绝不提交跳转或切集。
     */
    @Test
    fun pointerCancellationRollsBackSpeedDialAndAbortsSeekAndEpisodeSwipe() = withSingleThreadScene { ctx, motionScale ->
        var speed by mutableFloatStateOf(1.0f)
        var attachCancelableAncestor by mutableStateOf(true)
        var activeGesture by mutableStateOf<PlayerGesture?>(null)
        val committedSeeks = mutableListOf<Long>()
        var nextCalls = 0
        lateinit var controller: PlaybackSpeedController

        var virtualNano = 1_000_000L
        var eventTimeMs = 100L
        val scene = ImageComposeScene(width = 600, height = 360, coroutineContext = ctx) {
            ProvideTestPlayerEnvironment(motionScale) {
                controller = rememberPlaybackSpeedController(
                    mediaKey = "cancel-test",
                    playbackSpeed = speed,
                    defaultHoldSpeed = 2.0f,
                    onSpeedChange = { speed = it },
                    readDurationMillis = { 240_000L },
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (attachCancelableAncestor) {
                                Modifier.pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) awaitPointerEvent(PointerEventPass.Initial)
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    PlayerGestureLayer(
                        isLocked = false,
                        durationMillis = 240_000L,
                        positionProvider = { 60_000L },
                        brightness = null,
                        volume = null,
                        speedController = controller,
                        speedSupported = true,
                        gestureConfig = PlayerGestureConfig.Default.copy(
                            rightVerticalSwipe = VerticalSwipeAction.Episode,
                        ),
                        canPrevious = true,
                        canNext = true,
                        onGestureChange = { activeGesture = it },
                        onToggleControls = {},
                        onSeekTo = { committedSeeks += it },
                        onDoubleTap = {},
                        onPrevious = {},
                        onNext = { nextCalls++ },
                        onSpeedStart = {},
                    )
                }
            }
        }

        suspend fun pumpFrames(count: Int = 3) {
            repeat(count) {
                virtualNano += 16_000_000L
                scene.render(virtualNano).close()
                delay(5)
            }
        }

        fun touch(type: PointerEventType, x: Float, y: Float) {
            eventTimeMs += 15L
            scene.sendPointerEvent(type, Offset(x, y), timeMillis = eventTimeMs, type = PointerType.Touch)
        }

        suspend fun triggerSyntheticCancel() {
            attachCancelableAncestor = false
            pumpFrames()
            // 清理底层输入状态，随后恢复父节点供下一轮测试使用
            touch(PointerEventType.Release, 200f, 180f)
            attachCancelableAncestor = true
            pumpFrames()
        }

        try {
            pumpFrames()

            // 1. 长按进入 Speed Dial 并跨轴右滑进入 locked = true，随后触发指针取消 -> 必须还原 1.0x 且不锁定
            touch(PointerEventType.Press, 200f, 180f)
            delay(110)
            pumpFrames()
            assertTrue(controller.active)
            assertEquals(2.0f, speed)
            touch(PointerEventType.Move, 248f, 180f)
            pumpFrames()
            assertEquals(true, controller.selection?.locked)

            triggerSyntheticCancel()
            assertFalse(controller.active)
            assertFalse(controller.lockedByHold, "Pointer cancel must NOT commit cross-axis hold lock")
            assertEquals(1.0f, speed, "Pointer cancel must restore pre-open 1.0x speed")

            // 2. 横划进退途中触发指针取消 -> 绝不提交 onSeekTo
            touch(PointerEventType.Press, 150f, 180f)
            touch(PointerEventType.Move, 260f, 180f)
            pumpFrames()
            assertNotNull(activeGesture as? PlayerGesture.Seek)

            triggerSyntheticCancel()
            assertNull(activeGesture)
            assertTrue(committedSeeks.isEmpty(), "Cancelled horizontal swipe must not commit seek")

            // 3. 右侧上滑切集已武装（armed = true）时触发指针取消 -> 绝不调用 onNext
            touch(PointerEventType.Press, 520f, 260f)
            touch(PointerEventType.Move, 520f, 160f)
            pumpFrames()
            assertEquals(true, (activeGesture as? PlayerGesture.EpisodeSwipe)?.armed)

            triggerSyntheticCancel()
            assertNull(activeGesture)
            assertEquals(0, nextCalls, "Cancelled episode swipe must not advance episode")
        } finally {
            scene.close()
        }
    }

    /**
     * 回归测试 4（全手势矩阵覆盖）：
     * - 竖屏窗口（390×844）下长按 Speed Dial 采用水平选档与上下跨轴锁定；
     * - 横划进退拖入顶部 15% 两角取消区松手不提交，拖出取消区恢复提交；
     * - 左右分区纵划调节亮度与音量，中间 20% 死区不误触；
     * - 鼠标右键/中键按下被彻底忽略；
     * - 锁屏态（`isLocked = true`）屏蔽双击快进、横划、纵划与长按倍速，仅保留单击唤出解锁入口。
     */
    @Test
    fun portraitDialSeekCancelZoneVerticalAdjustSecondaryMouseAndLockWorkCleanly() = withSingleThreadScene { ctx, motionScale ->
        var speed by mutableFloatStateOf(1.0f)
        var locked by mutableStateOf(false)
        val brightness = FakeLevelControl(0.4f)
        val volume = FakeLevelControl(0.5f)
        val seeks = mutableListOf<Long>()
        val doubleTaps = mutableListOf<DoubleTapZone>()
        var toggleCalls = 0
        lateinit var controller: PlaybackSpeedController

        var virtualNano = 1_000_000L
        var eventTimeMs = 100L
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = ctx) {
            ProvideTestPlayerEnvironment(motionScale) {
                controller = rememberPlaybackSpeedController(
                    mediaKey = "portrait-matrix",
                    playbackSpeed = speed,
                    defaultHoldSpeed = 2.0f,
                    onSpeedChange = { speed = it },
                    readDurationMillis = { 120_000L },
                )
                PlayerGestureLayer(
                    isLocked = locked,
                    durationMillis = 120_000L,
                    positionProvider = { 30_000L },
                    brightness = brightness,
                    volume = volume,
                    speedController = controller,
                    speedSupported = true,
                    gestureConfig = PlayerGestureConfig.Default,
                    canPrevious = false,
                    canNext = false,
                    onGestureChange = {},
                    onToggleControls = { toggleCalls++ },
                    onSeekTo = { seeks += it },
                    onDoubleTap = { doubleTaps += it },
                    onPrevious = {},
                    onNext = {},
                    onSpeedStart = {},
                )
            }
        }

        suspend fun pumpFrames(count: Int = 3) {
            repeat(count) {
                virtualNano += 16_000_000L
                scene.render(virtualNano).close()
                delay(5)
            }
        }

        fun touch(type: PointerEventType, x: Float, y: Float) {
            eventTimeMs += 15L
            scene.sendPointerEvent(type, Offset(x, y), timeMillis = eventTimeMs, type = PointerType.Touch)
        }

        try {
            pumpFrames()

            // 1. 竖屏下半屏长按（y = 600 >= 422 -> dockTop = true）：右滑升至 2.5x，上滑锁定，松手保留 2.5x
            touch(PointerEventType.Press, 195f, 600f)
            delay(110)
            pumpFrames()
            assertTrue(controller.active)
            assertEquals(true, controller.selection?.dockTop)
            assertEquals(2.0f, speed)

            touch(PointerEventType.Move, 265f, 600f)
            pumpFrames()
            assertEquals(2.5f, speed)

            touch(PointerEventType.Move, 265f, 552f)
            pumpFrames()
            assertEquals(true, controller.selection?.locked)

            touch(PointerEventType.Release, 265f, 552f)
            pumpFrames()
            assertFalse(controller.active)
            assertEquals(2.5f, speed)
            controller.applySpeed(1.0f)

            // 2. 横划进退拖入左上角 15% 取消区（x <= 58.5, y <= 126.6）松手取消；再次横划正常松手提交
            touch(PointerEventType.Press, 120f, 400f)
            touch(PointerEventType.Move, 220f, 400f)
            touch(PointerEventType.Move, 30f, 40f)
            touch(PointerEventType.Release, 30f, 40f)
            pumpFrames()
            assertTrue(seeks.isEmpty(), "Releasing inside top-corner cancel zone must cancel seek")

            touch(PointerEventType.Press, 120f, 400f)
            touch(PointerEventType.Move, 240f, 400f)
            touch(PointerEventType.Release, 240f, 400f)
            pumpFrames()
            assertEquals(1, seeks.size)
            assertTrue(seeks.single() > 30_000L)

            // 3. 左侧（x = 60 < 156）上滑调高亮度，右侧（x = 330 > 234）下滑调低音量
            touch(PointerEventType.Press, 60f, 500f)
            touch(PointerEventType.Move, 60f, 360f)
            touch(PointerEventType.Release, 60f, 360f)
            pumpFrames()
            assertTrue(brightness.value > 0.4f)

            touch(PointerEventType.Press, 330f, 360f)
            touch(PointerEventType.Move, 330f, 500f)
            touch(PointerEventType.Release, 330f, 500f)
            pumpFrames()
            assertTrue(volume.value < 0.5f)

            // 4. 鼠标右键长按/点击一律忽略
            val togglesBeforeRightClick = toggleCalls
            eventTimeMs += 20L
            scene.sendPointerEvent(
                eventType = PointerEventType.Press,
                position = Offset(195f, 400f),
                timeMillis = eventTimeMs,
                type = PointerType.Mouse,
                buttons = PointerButtons(isSecondaryPressed = true),
                button = PointerButton.Secondary,
            )
            delay(110)
            pumpFrames()
            assertFalse(controller.active, "Right mouse button hold must never trigger Speed Dial")
            eventTimeMs += 20L
            scene.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = Offset(195f, 400f),
                timeMillis = eventTimeMs,
                type = PointerType.Mouse,
                buttons = PointerButtons(isSecondaryPressed = false),
                button = PointerButton.Secondary,
            )
            delay(80)
            pumpFrames()
            assertEquals(togglesBeforeRightClick, toggleCalls, "Right mouse button click must not toggle controls")

            // 5. 锁屏态（isLocked = true）：屏蔽长按倍速、横划 seek 与双击进退，仅单击触发 onToggleControls
            locked = true
            pumpFrames()
            val seeksBeforeLock = seeks.size

            touch(PointerEventType.Press, 195f, 400f)
            delay(110)
            pumpFrames()
            assertFalse(controller.active, "Locked player must block long-press Speed Dial")
            touch(PointerEventType.Move, 300f, 400f)
            touch(PointerEventType.Release, 300f, 400f)
            pumpFrames()
            assertEquals(seeksBeforeLock, seeks.size, "Locked player must block horizontal drag seek")

            touch(PointerEventType.Press, 350f, 400f)
            touch(PointerEventType.Release, 350f, 400f)
            delay(80)
            pumpFrames()
            assertTrue(doubleTaps.isEmpty())
            assertTrue(toggleCalls > togglesBeforeRightClick, "Locked player single-tap must still toggle unlock button")
        } finally {
            scene.close()
        }
    }

    /**
     * 回归测试 5（从 `∞` 粗剪速览上滑退回 `3.5x` 时重锚拖拽基准）：
     * 当手指长按拨入 `∞`（`RAPID_SCRUB_VALUE`）并在胶片区大幅移动后，上滑触发 `exitRapidScrubToMaxSpeed()`
     * 退回 `3.5x` 时，随后哪怕仅移动 1dp 也必须稳定停留在 `3.5x`，绝不能因残留 `lastAlong` 差值瞬间跳跌至 `0.5x`。
     */
    @Test
    fun exitingRapidScrubResetsDragAnchorWithoutSnappingToMinSpeed() {
        var currentSpeed = 1.0f
        var pausedForScrub = 0
        var resumedAfterScrub = 0
        val controller = PlaybackSpeedController(
            readSpeed = { currentSpeed },
            writeSpeed = { currentSpeed = it },
            defaultHoldSpeed = 2.5f,
            readPositionMillis = { 45_000L },
            readDurationMillis = { 600_000L },
            allowRapidScrub = { true },
            readIsPlaying = { true },
            onPauseForRapidScrub = { pausedForScrub++ },
            onResumeAfterRapidScrub = { resumedAfterScrub++ },
        )

        controller.open(temporary = true, showDial = true)
        assertEquals(2.5f, currentSpeed)
        assertTrue(controller.beginDrag(vertical = true))

        // 沿竖直拨盘向下滑入 ∞ 档（2.5x -> 3.0x -> 3.5x -> ∞）
        controller.move(alongDp = 140f, acrossDp = 0f)
        assertTrue(controller.isRapidScrubActive, "Dragging past 3.5x must enter rapid scrub mode")
        assertEquals(1, pausedForScrub)
        assertEquals(1.0f, currentSpeed, "Rapid scrub drops backend rate to base speed while paused")

        // 模拟用户在胶片浏览途中手指已移至上方区域并触发退出 ∞ 回到最高倍速 3.5x
        controller.exitRapidScrubToMaxSpeed()
        assertFalse(controller.isRapidScrubActive)
        assertEquals(350, controller.selection?.value)
        assertEquals(3.5f, currentSpeed)
        assertEquals(1, resumedAfterScrub)

        // 紧接着手指仅微移 1dp：必须重锚当前坐标并维持 3.5x，严禁跳跌至 0.5x
        controller.move(alongDp = -120f, acrossDp = 2f)
        assertEquals(350, controller.selection?.value, "First post-exit move must re-anchor at 3.5x")
        assertEquals(3.5f, currentSpeed)

        controller.move(alongDp = -124f, acrossDp = 2f)
        assertEquals(350, controller.selection?.value, "Sub-intent movement after re-anchor must stay at 3.5x")

        // 继续向上滑动满一档（跨越 10dp 意图阈值 + 36dp 步长）平滑退一档至 3.0x
        controller.move(alongDp = -167f, acrossDp = 2f)
        assertEquals(300, controller.selection?.value, "Intentional upward drag step must step from 3.5x to 3.0x")
        assertEquals(3.0f, currentSpeed)

        // 跨轴右滑锁定 3.0x，松手后保留 3.0x
        controller.move(alongDp = -167f, acrossDp = 40f)
        assertEquals(true, controller.selection?.locked)
        controller.releaseHoldDrag()
        assertFalse(controller.active)
        assertTrue(controller.lockedByHold)
        assertEquals(3.0f, currentSpeed)
    }
}
