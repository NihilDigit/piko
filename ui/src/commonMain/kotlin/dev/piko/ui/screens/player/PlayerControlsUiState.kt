package dev.piko.ui.screens.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.SubtitlesOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalWindowInfo
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.trackDisplayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val CONTROLS_HIDE_DELAY_MILLIS = 4_500L
private const val CURSOR_HIDE_DELAY_MILLIS = CONTROLS_HIDE_DELAY_MILLIS
private const val RESUME_TIP_DURATION_MILLIS = 5_000L
private const val DOUBLE_TAP_FEEDBACK_MILLIS = 700L
private const val SEEK_PEEK_MILLIS = 900L
private const val KEY_VOLUME_HUD_MILLIS = 800L
private const val INDICATOR_FLASH_MILLIS = 1_200L
internal const val VOLUME_KEY_STEP = 0.05f
private const val UNMUTE_FALLBACK_VOLUME = 0.5f
private const val LONG_SEEK_STEP_MILLIS = 60_000L
private const val HOLD_ARROW_MILLIS = 350L
private const val HOLD_REWIND_STEP_MILLIS = 5_000L
private const val HOLD_REWIND_INTERVAL_MILLIS = 500L
private const val MAX_HUD_TRACK_CHARS = 20

private val DigitKeys = listOf(
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
)

/**
 * 播放器控件层的纯 UI 元素状态持有者（遵循 Compose `state-hoisting.md` Plain State Holder 规范）：
 * 仅封装控件显隐计时、双击进退累加、方向键长按倍速/快退、瞬态 HUD 读数与面板开关等 UI 元素状态与逻辑，
 * 业务播放状态仍完全由外部 `PlayerScreenState` 单向流入。
 */
@Stable
internal class PlayerControlsUiState(
    private val scope: CoroutineScope,
    initialResumedFromMillis: Long?,
    initialSpeed: Float?,
    initialRotation: Int?,
    initialBoostSpeed: Float,
) {
    var controlsVisible by mutableStateOf(true)
    var isLocked by mutableStateOf(false)
    var activeGesture by mutableStateOf<PlayerGesture?>(null)
    var isScrubbing by mutableStateOf(false)
    var isDraggingVolume by mutableStateOf(false)
    var openSheet by mutableStateOf<PlayerSheet?>(null)
    var isEditingCropRegion by mutableStateOf(false)
    var aspectBeforeEdit by mutableStateOf<PlayerAspectRatio?>(null)
    var isSpeedPopupOpen by mutableStateOf(false)
    var mouseMoveCount by mutableIntStateOf(0)
    var isMouseIdle by mutableStateOf(false)
    var interactionCount by mutableIntStateOf(0)

    var doubleTapVisible by mutableStateOf(false)
    var doubleTapForward by mutableStateOf(true)
    var doubleTapSeconds by mutableIntStateOf(0)
    var doubleTapCount by mutableIntStateOf(0)
    private var doubleTapTargetMillis by mutableLongStateOf(0L)
    var tapSeekRound by mutableStateOf(false)

    var isBoosting by mutableStateOf(false)
    private var speedBeforeBoost by mutableFloatStateOf(1f)
    private var activeBoostSpeed by mutableFloatStateOf(initialBoostSpeed)
    var skipSpeedFlash by mutableStateOf(false)
    var boostReadout by mutableStateOf<CenterIndicator?>(null)

    var flashIndicator by mutableStateOf<CenterIndicator?>(null)
    var flashCount by mutableIntStateOf(0)

    var settledSeekMillis by mutableStateOf<Long?>(null)
    var settledSeekCount by mutableIntStateOf(0)

    var showResumeTip by mutableStateOf(initialResumedFromMillis != null)

    var keyVolume by mutableStateOf<Float?>(null)
    var keyVolumeCount by mutableIntStateOf(0)
    var volumeRevision by mutableIntStateOf(0)
    private var volumeBeforeMute by mutableStateOf<Float?>(null)
    private var lastSubtitleId by mutableStateOf<String?>(null)

    var lastObservedSpeed by mutableStateOf(initialSpeed)
    var lastObservedRotation by mutableStateOf(initialRotation)

    private var heldArrow by mutableStateOf<Key?>(null)
    private var holdJob by mutableStateOf<Job?>(null)
    private var arrowHeld by mutableStateOf(false)
    private val heldKeys = HashSet<Key>()

    // 同步当前帧最新的数据输入供事件回调读取
    var currentPositionMillis: Long = 0L
    var currentPositionProvider: (() -> Long)? = null
    val effectivePositionMillis: Long
        get() = currentPositionProvider?.invoke() ?: currentPositionMillis
    var currentDurationMillis: Long = 0L
    var currentPlaybackSpeed: Float? = initialSpeed
    var currentRotationDegrees: Int? = initialRotation
    var currentIsPlaying: Boolean = false
    var currentIsFullscreen: Boolean = false
    var currentSeekStepMillis: Long = 10_000L
    var currentLongPressSpeed: Float = initialBoostSpeed
    var currentVolumeControl: PlayerLevelControl? = null
    var currentAudioTracks: List<MediaTrack> = emptyList()
    var currentSelectedAudioId: String? = null
    var currentSubtitleTracks: List<MediaTrack> = emptyList()
    var currentSelectedSubtitleId: String? = null
    var currentHasPrevious: Boolean = false
    var currentHasNext: Boolean = false
    var currentHasStats: Boolean = false
    var currentIsAlwaysOnTop: Boolean? = null

    fun interacted() {
        interactionCount += 1
    }

    fun flash(indicator: CenterIndicator) {
        flashIndicator = indicator
        flashCount += 1
    }

    fun recordSettledSeek(targetMillis: Long) {
        settledSeekMillis = targetMillis
        settledSeekCount += 1
    }

    fun beginCropEdit(currentAspect: PlayerAspectRatio?, onAspectRatioChange: (PlayerAspectRatio) -> Unit) {
        aspectBeforeEdit = currentAspect ?: PlayerAspectRatio.Fit
        openSheet = null
        onAspectRatioChange(PlayerAspectRatio.Fit)
        isEditingCropRegion = true
    }

    fun cancelCropEdit(onAspectRatioChange: (PlayerAspectRatio) -> Unit) {
        isEditingCropRegion = false
        aspectBeforeEdit?.let(onAspectRatioChange)
    }

    fun stepSeek(
        forward: Boolean,
        onSeek: (Long) -> Unit,
        stepMillis: Long = currentSeekStepMillis,
        byTap: Boolean = false,
    ) {
        val continuing = doubleTapVisible && doubleTapForward == forward
        val base = if (continuing) doubleTapTargetMillis else effectivePositionMillis
        val step = if (forward) stepMillis else -stepMillis
        val target = (base + step).coerceIn(0L, currentDurationMillis.coerceAtLeast(0L))
        val stepSeconds = (stepMillis / 1000).toInt()
        doubleTapTargetMillis = target
        doubleTapSeconds = if (continuing) doubleTapSeconds + stepSeconds else stepSeconds
        doubleTapForward = forward
        doubleTapVisible = true
        doubleTapCount += 1
        tapSeekRound = byTap
        onSeek(target)
    }

    fun setVolume(fraction: Float): Float? {
        val control = currentVolumeControl ?: return null
        val applied = control.set(fraction.coerceIn(0f, 1f))
        if (applied > 0f) volumeBeforeMute = null
        volumeRevision += 1
        return applied
    }

    fun stepVolume(delta: Float) {
        val control = currentVolumeControl ?: return
        keyVolume = setVolume(control.current() + delta)
        keyVolumeCount += 1
    }

    fun toggleMute(showHud: Boolean = true) {
        val control = currentVolumeControl ?: return
        val current = control.current()
        val applied = if (current > 0f) {
            control.set(0f).also {
                volumeBeforeMute = current
                volumeRevision += 1
            }
        } else {
            setVolume(volumeBeforeMute ?: UNMUTE_FALLBACK_VOLUME) ?: return
        }
        if (showHud) {
            keyVolume = applied
            keyVolumeCount += 1
        }
    }

    fun startBoost(onSpeedChange: (Float) -> Unit) {
        val current = currentPlaybackSpeed ?: return
        if (isBoosting) return
        val speed = currentLongPressSpeed
        speedBeforeBoost = current
        activeBoostSpeed = speed
        isBoosting = true
        controlsVisible = false
        onSpeedChange(speed)
        boostReadout = CenterIndicator(
            Icons.Filled.FastForward,
            formatSpeedMultiplier(speed),
            tr("倍速 ${formatSpeedPreset(speed)}", "Speed ${formatSpeedPreset(speed)}", "再生速度 ${formatSpeedPreset(speed)}", "재생 속도 ${formatSpeedPreset(speed)}", "倍速 ${formatSpeedPreset(speed)}"),
        )
    }

    fun endBoost(onSpeedChange: (Float) -> Unit) {
        if (!isBoosting) return
        isBoosting = false
        skipSpeedFlash = speedBeforeBoost != activeBoostSpeed
        onSpeedChange(speedBeforeBoost)
    }

    fun abandonArrowHold(onSpeedChange: (Float) -> Unit) {
        holdJob?.cancel()
        holdJob = null
        if (arrowHeld && heldArrow == Key.DirectionRight) endBoost(onSpeedChange)
        heldArrow = null
        arrowHeld = false
    }

    fun releaseHeldKeys(onSpeedChange: (Float) -> Unit) {
        heldKeys.clear()
        abandonArrowHold(onSpeedChange)
        endBoost(onSpeedChange)
    }

    /**
     * 逐层退出的统一决策（Esc 与系统返回共用）：每次只收起最上层的一级状态，不穿透多层，普通播放态不直接关窗。
     */
    fun dismissTopLayer(
        onAspectRatioChange: (PlayerAspectRatio) -> Unit,
        onToggleFullscreen: () -> Unit,
    ): Boolean = when {
        isEditingCropRegion -> {
            cancelCropEdit(onAspectRatioChange)
            true
        }
        isSpeedPopupOpen -> {
            isSpeedPopupOpen = false
            true
        }
        openSheet == PlayerSheet.DriveSubtitles -> {
            openSheet = PlayerSheet.Tracks
            true
        }
        openSheet != null -> {
            openSheet = null
            true
        }
        currentIsFullscreen -> {
            onToggleFullscreen()
            true
        }
        else -> false
    }

    private fun arrowDown(
        key: Key,
        onSeek: (Long) -> Unit,
        onSpeedChange: (Float) -> Unit,
    ) {
        if (heldArrow == key) return
        abandonArrowHold(onSpeedChange)
        heldArrow = key
        holdJob = scope.launch {
            delay(HOLD_ARROW_MILLIS)
            arrowHeld = true
            if (key == Key.DirectionRight) {
                startBoost(onSpeedChange)
            } else {
                while (true) {
                    stepSeek(forward = false, onSeek = onSeek, stepMillis = HOLD_REWIND_STEP_MILLIS)
                    delay(HOLD_REWIND_INTERVAL_MILLIS)
                }
            }
        }
    }

    private fun arrowUp(
        key: Key,
        onSeek: (Long) -> Unit,
        onSpeedChange: (Float) -> Unit,
    ): Boolean {
        if (heldArrow != key) return false
        heldArrow = null
        holdJob?.cancel()
        holdJob = null
        when {
            !arrowHeld -> stepSeek(forward = key == Key.DirectionRight, onSeek = onSeek)
            key == Key.DirectionRight -> endBoost(onSpeedChange)
        }
        arrowHeld = false
        interacted()
        return true
    }

    private fun stepSpeed(faster: Boolean, onSpeedChange: (Float) -> Unit) {
        val speed = currentPlaybackSpeed ?: return
        val next = if (faster) PlayerSpeedPresets.firstOrNull { it > speed + 0.001f }
        else PlayerSpeedPresets.lastOrNull { it < speed - 0.001f }
        next?.let(onSpeedChange)
    }

    private fun compactTrackLabel(track: MediaTrack, index: Int): String {
        val raw = trackDisplayName(track, index).trim()
        return if (raw.length > MAX_HUD_TRACK_CHARS) raw.take(MAX_HUD_TRACK_CHARS - 1) + "…" else raw
    }

    private fun toggleSubtitles(onSelectSubtitleTrack: (MediaTrack?) -> Unit) {
        val tracks = currentSubtitleTracks
        if (tracks.isEmpty()) {
            val text = tr("无可用字幕", "No subtitles", "字幕なし", "사용 가능한 자막 없음", "無可用字幕")
            flash(CenterIndicator(Icons.Outlined.SubtitlesOff, text, text))
            return
        }
        val selected = currentSelectedSubtitleId
        if (selected != null) {
            lastSubtitleId = selected
            onSelectSubtitleTrack(null)
            val text = tr("字幕：关闭", "Subtitles: Off", "字幕：オフ", "자막: 끔", "字幕：關閉")
            flash(CenterIndicator(Icons.Outlined.SubtitlesOff, text, text))
        } else {
            val targetIndex = tracks.indexOfFirst { it.id == lastSubtitleId }.let { if (it >= 0) it else 0 }
            val target = tracks[targetIndex]
            onSelectSubtitleTrack(target)
            val name = compactTrackLabel(target, targetIndex)
            val text = tr("字幕：$name", "Subtitles: $name", "字幕：$name", "자막: $name", "字幕：$name")
            flash(CenterIndicator(Icons.Outlined.Subtitles, text, text))
        }
    }

    private fun cycleAudioTrack(onSelectAudioTrack: (MediaTrack) -> Unit): Boolean {
        val tracks = currentAudioTracks
        if (tracks.isEmpty()) return false
        val targetIndex = if (tracks.size == 1) {
            0
        } else {
            val currentIndex = tracks.indexOfFirst { it.id == currentSelectedAudioId }
            ((currentIndex + 1) % tracks.size).also { onSelectAudioTrack(tracks[it]) }
        }
        val name = compactTrackLabel(tracks[targetIndex], targetIndex)
        val text = tr("音轨：$name", "Audio: $name", "音声：$name", "오디오: $name", "音軌：$name")
        flash(CenterIndicator(Icons.Filled.GraphicEq, text, text))
        return true
    }

    private fun togglePlayPauseWithFeedback(onPlayPause: () -> Unit) {
        if (!controlsVisible) {
            val pausing = currentIsPlaying
            flash(
                CenterIndicator(
                    if (pausing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (pausing) tr("暂停", "Pause", "一時停止", "일시 중지", "暫停") else tr("播放", "Play", "再生", "재생", "播放"),
                    if (pausing) tr("已暂停", "Paused", "一時停止中", "일시 중지됨", "已暫停") else tr("继续播放", "Continue playback", "再生を続ける", "계속 재생", "繼續播放"),
                ),
            )
        }
        onPlayPause()
    }

    private fun isRepeatableKey(key: Key, shift: Boolean): Boolean = when (key) {
        Key.DirectionUp, Key.DirectionDown,
        Key.J, Key.L, Key.MediaRewind, Key.MediaFastForward,
        Key.LeftBracket, Key.RightBracket,
        -> true
        Key.DirectionLeft, Key.DirectionRight -> shift
        else -> false
    }

    fun handleKey(
        event: KeyEvent,
        onPlayPause: () -> Unit,
        onSeek: (Long) -> Unit,
        onSpeedChange: (Float) -> Unit,
        onAspectRatioChange: (PlayerAspectRatio) -> Unit,
        onToggleFullscreen: () -> Unit,
        onRotationChange: (Int) -> Unit,
        onSelectSubtitleTrack: (MediaTrack?) -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onSelectAudioTrack: (MediaTrack) -> Unit = {},
        onToggleAlwaysOnTop: (() -> Unit)? = null,
        letFocusedControlHandleSpace: Boolean = false,
    ): Boolean {
        if (event.type == KeyEventType.KeyUp) {
            heldKeys.remove(event.key)
            return arrowUp(event.key, onSeek, onSpeedChange)
        }
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val shift = event.isShiftPressed

        if (event.key == Key.Escape) {
            if (!heldKeys.add(Key.Escape)) return true
            val dismissed = dismissTopLayer(onAspectRatioChange, onToggleFullscreen)
            if (dismissed) interacted()
            return dismissed
        }
        if (isEditingCropRegion) return false

        // 侧边/底部面板打开时，常规方向键与空格留给面板内部控件导航与激活；仅保留专用媒体键与面板自身开关
        if (openSheet != null) {
            when (event.key) {
                Key.I -> if (!shift && currentHasStats && openSheet == PlayerSheet.Stats) {
                    if (!heldKeys.add(Key.I)) return true
                    openSheet = null
                    interacted()
                    return true
                } else return false
                Key.MediaPlayPause -> {
                    if (!heldKeys.add(event.key)) return true
                    onPlayPause()
                    return true
                }
                Key.MediaPlay -> {
                    if (!heldKeys.add(event.key)) return true
                    if (!currentIsPlaying) onPlayPause()
                    return true
                }
                Key.MediaPause, Key.MediaStop -> {
                    if (!heldKeys.add(event.key)) return true
                    if (currentIsPlaying) onPlayPause()
                    return true
                }
                Key.MediaPrevious -> {
                    if (!heldKeys.add(event.key)) return true
                    if (currentHasPrevious) onPrevious() else return false
                    return true
                }
                Key.MediaNext -> {
                    if (!heldKeys.add(event.key)) return true
                    if (currentHasNext) onNext() else return false
                    return true
                }
                else -> return false
            }
        }

        if (event.key == Key.Spacebar && letFocusedControlHandleSpace) return false

        val repeatable = isRepeatableKey(event.key, shift)
        if (!repeatable && event.key in heldKeys) {
            return true
        }

        when (event.key) {
            Key.Spacebar, Key.K, Key.MediaPlayPause -> togglePlayPauseWithFeedback(onPlayPause)
            Key.MediaPlay -> if (!currentIsPlaying) togglePlayPauseWithFeedback(onPlayPause)
            Key.MediaPause, Key.MediaStop -> if (currentIsPlaying) togglePlayPauseWithFeedback(onPlayPause)
            Key.DirectionLeft, Key.DirectionRight -> {
                val forward = event.key == Key.DirectionRight
                if (shift) stepSeek(forward, onSeek, LONG_SEEK_STEP_MILLIS) else arrowDown(event.key, onSeek, onSpeedChange)
            }
            Key.J, Key.MediaRewind -> stepSeek(forward = false, onSeek = onSeek)
            Key.L, Key.MediaFastForward -> stepSeek(forward = true, onSeek = onSeek)
            Key.DirectionUp -> stepVolume(VOLUME_KEY_STEP)
            Key.DirectionDown -> stepVolume(-VOLUME_KEY_STEP)
            Key.M -> toggleMute()
            Key.F, Key.F11 -> onToggleFullscreen()
            Key.R -> onRotationChange(((currentRotationDegrees ?: return false) + 90) % 360)
            Key.LeftBracket -> stepSpeed(faster = false, onSpeedChange = onSpeedChange)
            Key.RightBracket -> stepSpeed(faster = true, onSpeedChange = onSpeedChange)
            Key.Backspace -> onSpeedChange(1f)
            Key.C -> toggleSubtitles(onSelectSubtitleTrack)
            Key.A -> if (!cycleAudioTrack(onSelectAudioTrack)) return false
            Key.I -> if (currentHasStats) {
                openSheet = if (openSheet == PlayerSheet.Stats) null else PlayerSheet.Stats
            } else return false
            Key.T -> {
                val onTop = currentIsAlwaysOnTop ?: return false
                val toggle = onToggleAlwaysOnTop ?: return false
                val nextOnTop = !onTop
                toggle()
                val text = if (nextOnTop) {
                    tr("已置顶", "Pinned on top", "最前面に固定", "항상 위에 고정됨", "已置頂")
                } else {
                    tr("取消置顶", "Unpinned", "最前面表示を解除", "고정 해제됨", "取消置頂")
                }
                flash(CenterIndicator(if (nextOnTop) Icons.Filled.PushPin else Icons.Outlined.PushPin, text, text))
            }
            Key.PageUp, Key.MediaPrevious -> if (currentHasPrevious) onPrevious() else return false
            Key.PageDown, Key.MediaNext -> if (currentHasNext) onNext() else return false
            Key.MoveHome -> onSeek(0L)
            else -> {
                val digit = DigitKeys.indexOf(event.key).takeIf { it >= 0 } ?: return false
                if (currentDurationMillis <= 0L) return false
                onSeek(currentDurationMillis * digit / 10)
            }
        }
        if (!repeatable) heldKeys.add(event.key)
        interacted()
        return true
    }

    fun shouldHoldControls(
        isPlaying: Boolean,
        errorMessage: String?,
        isHoveringControls: Boolean,
    ): Boolean = !isPlaying || isScrubbing || isDraggingVolume || openSheet != null ||
        isSpeedPopupOpen || errorMessage != null || isHoveringControls || isEditingCropRegion

    fun computeHudIndicator(durationMillis: Long): CenterIndicator? {
        val gesture = activeGesture ?: keyVolume?.let { PlayerGesture.Adjust(VerticalAdjust.Volume, it) }
        return when {
            gesture is PlayerGesture.Adjust -> {
                val percent = (gesture.fraction * 100).roundToInt()
                val brightnessGesture = gesture.kind == VerticalAdjust.Brightness
                CenterIndicator(
                    icon = when {
                        brightnessGesture -> Icons.Filled.BrightnessMedium
                        gesture.fraction <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    },
                    text = "$percent",
                    textTemplate = "100",
                    description = if (brightnessGesture) tr("亮度 $percent", "Brightness $percent", "明るさ $percent", "밝기 $percent", "亮度 $percent") else tr("音量 $percent", "Volume $percent", "音量 $percent", "볼륨 $percent", "音量 $percent"),
                    progress = gesture.fraction,
                )
            }
            gesture is PlayerGesture.Seek && gesture.cancelled ->
                CenterIndicator(Icons.Filled.Close, tr("松开取消", "Release to cancel", "離すとキャンセル", "놓으면 취소", "放開以取消"), tr("松开取消跳转", "Release to cancel seeking", "離すとシークをキャンセル", "놓으면 탐색 취소", "放開以取消跳轉"))
            gesture is PlayerGesture.Seek -> {
                val target = gesture.targetMillis(durationMillis)
                val forward = target >= gesture.startPositionMillis
                CenterIndicator(
                    icon = if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                    text = formatTime(abs(target - gesture.startPositionMillis)),
                    description = tr("跳到 ${formatTime(target)}", "Seek to ${formatTime(target)}", "${formatTime(target)} に移動", "${formatTime(target)}(으)로 이동", "跳至 ${formatTime(target)}"),
                    textTemplate = "00:00",
                )
            }
            doubleTapVisible -> {
                val sign = if (doubleTapForward) "+" else "−"
                CenterIndicator(
                    icon = if (doubleTapForward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                    text = tr("$sign$doubleTapSeconds 秒", "$sign$doubleTapSeconds sec", "$sign$doubleTapSeconds 秒", "$sign${doubleTapSeconds}초", "$sign$doubleTapSeconds 秒"),
                    description = if (doubleTapForward) tr("快进 $doubleTapSeconds 秒", "Forward $doubleTapSeconds sec", "$doubleTapSeconds 秒早送り", "${doubleTapSeconds}초 앞으로", "快轉 $doubleTapSeconds 秒") else tr("快退 $doubleTapSeconds 秒", "Back $doubleTapSeconds sec", "$doubleTapSeconds 秒巻き戻し", "${doubleTapSeconds}초 뒤로", "倒轉 $doubleTapSeconds 秒"),
                    textTemplate = "+${widestDigits((durationMillis / 1000).coerceAtLeast(doubleTapSeconds.toLong()).toString())} 秒",
                )
            }
            isBoosting -> boostReadout
            else -> null
        }
    }
}

@Composable
internal fun rememberPlayerControlsUiState(
    currentFileId: String,
    isPlaying: Boolean,
    isHoveringControls: Boolean,
    resumedFromMillis: Long?,
    playbackSpeed: Float?,
    rotationDegrees: Int?,
    longPressSpeed: Float,
    errorMessage: String?,
    onSpeedChange: (Float) -> Unit,
): PlayerControlsUiState {
    val scope = rememberCoroutineScope()
    val accessibilityManager = LocalAccessibilityManager.current
    val state = remember {
        PlayerControlsUiState(
            scope = scope,
            initialResumedFromMillis = resumedFromMillis,
            initialSpeed = playbackSpeed,
            initialRotation = rotationDegrees,
            initialBoostSpeed = longPressSpeed,
        )
    }
    val holdControls = state.shouldHoldControls(isPlaying, errorMessage, isHoveringControls)

    LaunchedEffect(resumedFromMillis) {
        state.showResumeTip = resumedFromMillis != null
    }

    val hideDelayMillis = remember(accessibilityManager) {
        accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = CONTROLS_HIDE_DELAY_MILLIS,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: CONTROLS_HIDE_DELAY_MILLIS
    }

    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (!windowFocused) {
            state.releaseHeldKeys(onSpeedChange)
        }
    }

    LaunchedEffect(state.controlsVisible, holdControls, state.interactionCount, hideDelayMillis) {
        if (state.controlsVisible && !holdControls && hideDelayMillis != Long.MAX_VALUE) {
            delay(hideDelayMillis)
            state.controlsVisible = false
        }
    }

    LaunchedEffect(state.mouseMoveCount) {
        state.isMouseIdle = false
        delay(CURSOR_HIDE_DELAY_MILLIS)
        state.isMouseIdle = true
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            state.controlsVisible = true
            state.isEditingCropRegion = false
        }
    }

    LaunchedEffect(currentFileId) {
        state.isEditingCropRegion = false
    }

    LaunchedEffect(state.settledSeekCount) {
        if (state.settledSeekMillis != null) {
            delay(SEEK_PEEK_MILLIS)
            state.settledSeekMillis = null
        }
    }

    LaunchedEffect(state.showResumeTip) {
        if (state.showResumeTip) {
            delay(RESUME_TIP_DURATION_MILLIS)
            state.showResumeTip = false
        }
    }

    LaunchedEffect(state.doubleTapCount) {
        if (state.doubleTapCount > 0) {
            delay(DOUBLE_TAP_FEEDBACK_MILLIS)
            state.doubleTapVisible = false
        }
    }

    LaunchedEffect(state.keyVolumeCount) {
        if (state.keyVolume != null) {
            delay(KEY_VOLUME_HUD_MILLIS)
            state.keyVolume = null
        }
    }

    LaunchedEffect(state.flashCount) {
        if (state.flashIndicator != null) {
            delay(INDICATOR_FLASH_MILLIS)
            state.flashIndicator = null
        }
    }

    LaunchedEffect(playbackSpeed) {
        val previous = state.lastObservedSpeed
        state.lastObservedSpeed = playbackSpeed
        val speed = playbackSpeed ?: return@LaunchedEffect
        if (previous == null || previous == speed || state.isBoosting) return@LaunchedEffect
        if (state.skipSpeedFlash) {
            state.skipSpeedFlash = false
            return@LaunchedEffect
        }
        state.flash(
            CenterIndicator(
                Icons.Outlined.Speed,
                formatSpeedMultiplier(speed),
                tr("倍速 ${formatSpeedPreset(speed)}", "Speed ${formatSpeedPreset(speed)}", "再生速度 ${formatSpeedPreset(speed)}", "재생 속도 ${formatSpeedPreset(speed)}", "倍速 ${formatSpeedPreset(speed)}"),
            ),
        )
    }

    LaunchedEffect(rotationDegrees) {
        val previous = state.lastObservedRotation
        state.lastObservedRotation = rotationDegrees
        val degrees = rotationDegrees ?: return@LaunchedEffect
        if (previous == null || previous == degrees) return@LaunchedEffect
        state.flash(
            CenterIndicator(
                Icons.Outlined.Rotate90DegreesCw,
                "$degrees°",
                tr("画面旋转 $degrees 度", "Rotation: $degrees°", "画面回転 $degrees 度", "화면 회전 ${degrees}도", "畫面旋轉 $degrees 度"),
            ),
        )
    }

    return state
}
