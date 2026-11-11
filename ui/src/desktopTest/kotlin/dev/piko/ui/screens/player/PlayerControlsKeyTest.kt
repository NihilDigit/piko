package dev.piko.ui.screens.player

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlayerAspectRatio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class PlayerControlsKeyTest {
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun keyDown(key: Key, ctrl: Boolean = false, shift: Boolean = false): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isCtrlPressed = ctrl,
        isShiftPressed = shift,
    )

    private fun keyUp(key: Key): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyUp,
    )

    private fun newState(): PlayerControlsUiState = PlayerControlsUiState(
        scope = scope,
        initialResumedFromMillis = null,
        initialSpeed = 1f,
        initialRotation = 0,
        initialBoostSpeed = 2f,
    ).apply {
        currentDurationMillis = 120_000L
        currentPositionMillis = 30_000L
        currentIsPlaying = true
    }

    private fun PlayerControlsUiState.dispatch(
        event: KeyEvent,
        onPlayPause: () -> Unit = {},
        onSeek: (Long) -> Unit = {},
        onSpeedChange: (Float) -> Unit = {},
        onAspectRatioChange: (PlayerAspectRatio) -> Unit = {},
        onToggleFullscreen: () -> Unit = {},
        onRotationChange: (Int) -> Unit = {},
        onSelectSubtitleTrack: (MediaTrack?) -> Unit = {},
        onPrevious: () -> Unit = {},
        onNext: () -> Unit = {},
        onSelectAudioTrack: (MediaTrack) -> Unit = {},
        onToggleAlwaysOnTop: (() -> Unit)? = null,
        letFocusedControlHandleSpace: Boolean = false,
    ): Boolean = handleKey(
        event = event,
        onPlayPause = onPlayPause,
        onSeek = onSeek,
        onSpeedChange = onSpeedChange,
        onAspectRatioChange = onAspectRatioChange,
        onToggleFullscreen = onToggleFullscreen,
        onRotationChange = onRotationChange,
        onSelectSubtitleTrack = onSelectSubtitleTrack,
        onPrevious = onPrevious,
        onNext = onNext,
        onSelectAudioTrack = onSelectAudioTrack,
        onToggleAlwaysOnTop = onToggleAlwaysOnTop,
        letFocusedControlHandleSpace = letFocusedControlHandleSpace,
    )

    @Test
    fun toggleKeysSuppressAutoRepeatUntilKeyUpOrFocusLoss() {
        val state = newState().apply { controlsVisible = false }
        var playPauseCalls = 0
        var fullscreenCalls = 0

        assertTrue(state.dispatch(keyDown(Key.Spacebar), onPlayPause = { playPauseCalls++ }))
        assertEquals(1, playPauseCalls)
        assertNotNull(state.flashIndicator)

        // 按住空格触发系统连发时不得反复暂停/继续抽搐
        assertTrue(state.dispatch(keyDown(Key.Spacebar), onPlayPause = { playPauseCalls++ }))
        assertEquals(1, playPauseCalls)

        state.dispatch(keyUp(Key.Spacebar))
        assertTrue(state.dispatch(keyDown(Key.Spacebar), onPlayPause = { playPauseCalls++ }))
        assertEquals(2, playPauseCalls)

        // 全屏支持 F 与 F11，失焦释放后可再次响应
        assertTrue(state.dispatch(keyDown(Key.F11), onToggleFullscreen = { fullscreenCalls++ }))
        assertTrue(state.dispatch(keyDown(Key.F11), onToggleFullscreen = { fullscreenCalls++ }))
        assertEquals(1, fullscreenCalls)

        state.releaseHeldKeys {}
        assertTrue(state.dispatch(keyDown(Key.F11), onToggleFullscreen = { fullscreenCalls++ }))
        assertEquals(2, fullscreenCalls)
    }

    @Test
    fun repeatableKeysAllowContinuousSteppingAndUnhandledKeysStayUnhandled() {
        val state = newState()
        val seeks = mutableListOf<Long>()

        assertTrue(state.dispatch(keyDown(Key.L), onSeek = { seeks += it }))
        assertTrue(state.dispatch(keyDown(Key.L), onSeek = { seeks += it }))
        assertEquals(listOf(40_000L, 50_000L), seeks)

        // 未绑定按键即便连发也始终返回 false，不吞掉上层快捷键
        assertFalse(state.dispatch(keyDown(Key.Q)))
        assertFalse(state.dispatch(keyDown(Key.Q)))
        assertFalse(state.dispatch(keyDown(Key.W, ctrl = true)))
    }

    @Test
    fun escapeDismissesLayersInStrictPriorityOrder() {
        val state = newState().apply { currentIsFullscreen = true }
        var restoredAspect: PlayerAspectRatio? = null
        var fullscreenToggled = 0

        state.beginCropEdit(PlayerAspectRatio.Crop) { restoredAspect = it }
        state.isSpeedPopupOpen = true
        state.openSheet = PlayerSheet.DriveSubtitles

        // 1. 裁剪框编辑最优先
        assertTrue(state.dispatch(keyDown(Key.Escape), onAspectRatioChange = { restoredAspect = it }, onToggleFullscreen = { fullscreenToggled++ }))
        assertFalse(state.isEditingCropRegion)
        assertEquals(PlayerAspectRatio.Crop, restoredAspect)
        assertTrue(state.isSpeedPopupOpen)
        state.dispatch(keyUp(Key.Escape))

        // 2. 倍速弹层
        assertTrue(state.dispatch(keyDown(Key.Escape), onToggleFullscreen = { fullscreenToggled++ }))
        assertFalse(state.isSpeedPopupOpen)
        assertEquals(PlayerSheet.DriveSubtitles, state.openSheet)
        state.dispatch(keyUp(Key.Escape))

        // 3. 网盘字幕二级面板退回音轨与字幕面板
        assertTrue(state.dispatch(keyDown(Key.Escape), onToggleFullscreen = { fullscreenToggled++ }))
        assertEquals(PlayerSheet.Tracks, state.openSheet)
        state.dispatch(keyUp(Key.Escape))

        // 4. 关闭音轨与字幕面板
        assertTrue(state.dispatch(keyDown(Key.Escape), onToggleFullscreen = { fullscreenToggled++ }))
        assertNull(state.openSheet)
        assertEquals(0, fullscreenToggled)
        state.dispatch(keyUp(Key.Escape))

        // 5. 退出全屏
        assertTrue(state.dispatch(keyDown(Key.Escape), onToggleFullscreen = { fullscreenToggled++; state.currentIsFullscreen = false }))
        assertEquals(1, fullscreenToggled)
        state.dispatch(keyUp(Key.Escape))

        // 6. 普通窗口态按 Esc 不误关窗口
        assertFalse(state.dispatch(keyDown(Key.Escape), onToggleFullscreen = { fullscreenToggled++ }))
    }

    @Test
    fun subtitleAudioStatsAndPinShortcutsUpdateStateAndFlashHud() {
        val sub1 = MediaTrack(id = "s1", title = "简中", language = "zh")
        val sub2 = MediaTrack(id = "s2", title = "English", language = "en")
        val aud1 = MediaTrack(id = "a1", title = "日语 2.0", language = "ja")
        val aud2 = MediaTrack(id = "a2", title = "国语 5.1", language = "zh")
        val state = newState().apply {
            currentSubtitleTracks = listOf(sub1, sub2)
            currentSelectedSubtitleId = "s2"
            currentAudioTracks = listOf(aud1, aud2)
            currentSelectedAudioId = "a1"
            currentHasStats = true
            currentIsAlwaysOnTop = false
        }

        var selectedSub: MediaTrack? = sub2
        assertTrue(state.dispatch(keyDown(Key.C), onSelectSubtitleTrack = {
            selectedSub = it
            state.currentSelectedSubtitleId = it?.id
        }))
        assertNull(selectedSub)
        assertNotNull(state.flashIndicator)
        state.dispatch(keyUp(Key.C))

        // 再次按 C 恢复上次选中的 s2 而非退回第一条
        assertTrue(state.dispatch(keyDown(Key.C), onSelectSubtitleTrack = {
            selectedSub = it
            state.currentSelectedSubtitleId = it?.id
        }))
        assertEquals("s2", selectedSub?.id)
        state.dispatch(keyUp(Key.C))

        var selectedAudio: MediaTrack? = aud1
        assertTrue(state.dispatch(keyDown(Key.A), onSelectAudioTrack = {
            selectedAudio = it
            state.currentSelectedAudioId = it.id
        }))
        assertEquals("a2", selectedAudio?.id)
        state.dispatch(keyUp(Key.A))

        // T 切换窗口置顶并弹出 HUD
        var pinned = false
        assertTrue(state.dispatch(keyDown(Key.T), onToggleAlwaysOnTop = {
            pinned = !pinned
            state.currentIsAlwaysOnTop = pinned
        }))
        assertTrue(pinned)
        state.dispatch(keyUp(Key.T))

        // I 打开与关闭统计信息面板；面板打开期间普通空格留给面板内部控件
        assertTrue(state.dispatch(keyDown(Key.I)))
        assertEquals(PlayerSheet.Stats, state.openSheet)
        state.dispatch(keyUp(Key.I))

        assertFalse(state.dispatch(keyDown(Key.Spacebar)))
        assertTrue(state.dispatch(keyDown(Key.I)))
        assertNull(state.openSheet)
    }

    @Test
    fun spaceDefersToFocusedChildControlDuringKeyboardNavigation() {
        val state = newState()
        var playPauseCalls = 0
        assertFalse(
            state.dispatch(
                keyDown(Key.Spacebar),
                onPlayPause = { playPauseCalls++ },
                letFocusedControlHandleSpace = true,
            ),
        )
        assertEquals(0, playPauseCalls)
    }
}
