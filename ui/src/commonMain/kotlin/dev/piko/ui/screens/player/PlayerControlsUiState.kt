package dev.piko.ui.screens.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Speed
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
    private var volumeBeforeMute by mutableStateOf<Float?>(null)
    private var lastSubtitleId by mutableStateOf<String?>(null)

    var lastObservedSpeed by mutableStateOf(initialSpeed)
    var lastObservedRotation by mutableStateOf(initialRotation)

    private var heldArrow by mutableStateOf<Key?>(null)
    private var holdJob by mutableStateOf<Job?>(null)
    private var arrowHeld by mutableStateOf(false)

    // 同步当前帧最新的数据输入供事件回调读取
    var currentPositionMillis: Long = 0L
    var currentDurationMillis: Long = 0L
    var currentPlaybackSpeed: Float? = initialSpeed
    var currentRotationDegrees: Int? = initialRotation
    var currentIsPlaying: Boolean = false
    var currentSeekStepMillis: Long = 10_000L
    var currentLongPressSpeed: Float = initialBoostSpeed
    var currentVolumeControl: PlayerLevelControl? = null
    var currentSubtitleTracks: List<MediaTrack> = emptyList()
    var currentSelectedSubtitleId: String? = null
    var currentHasPrevious: Boolean = false
    var currentHasNext: Boolean = false

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
        val base = if (continuing) doubleTapTargetMillis else currentPositionMillis
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
            control.set(0f).also { volumeBeforeMute = current }
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

    private fun toggleSubtitles(onSelectSubtitleTrack: (MediaTrack?) -> Unit): Boolean {
        val tracks = currentSubtitleTracks
        if (tracks.isEmpty()) return false
        val selected = currentSelectedSubtitleId
        if (selected != null) {
            lastSubtitleId = selected
            onSelectSubtitleTrack(null)
        } else {
            onSelectSubtitleTrack(tracks.find { it.id == lastSubtitleId } ?: tracks.first())
        }
        return true
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
    ): Boolean {
        if (isEditingCropRegion) {
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                cancelCropEdit(onAspectRatioChange)
                return true
            }
            return false
        }
        if (event.type == KeyEventType.KeyUp) return arrowUp(event.key, onSeek, onSpeedChange)
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val shift = event.isShiftPressed
        when (event.key) {
            Key.Spacebar, Key.K, Key.MediaPlayPause -> onPlayPause()
            Key.MediaPlay -> if (!currentIsPlaying) onPlayPause() else return true
            Key.MediaPause, Key.MediaStop -> if (currentIsPlaying) onPlayPause() else return true
            Key.DirectionLeft, Key.DirectionRight -> {
                val forward = event.key == Key.DirectionRight
                if (shift) stepSeek(forward, onSeek, LONG_SEEK_STEP_MILLIS) else arrowDown(event.key, onSeek, onSpeedChange)
            }
            Key.J, Key.MediaRewind -> stepSeek(forward = false, onSeek = onSeek)
            Key.L, Key.MediaFastForward -> stepSeek(forward = true, onSeek = onSeek)
            Key.DirectionUp -> stepVolume(VOLUME_KEY_STEP)
            Key.DirectionDown -> stepVolume(-VOLUME_KEY_STEP)
            Key.M -> toggleMute()
            Key.F -> onToggleFullscreen()
            Key.R -> onRotationChange(((currentRotationDegrees ?: return false) + 90) % 360)
            Key.LeftBracket -> stepSpeed(faster = false, onSpeedChange = onSpeedChange)
            Key.RightBracket -> stepSpeed(faster = true, onSpeedChange = onSpeedChange)
            Key.Backspace -> onSpeedChange(1f)
            Key.C -> return toggleSubtitles(onSelectSubtitleTrack)
            Key.PageUp, Key.MediaPrevious -> if (currentHasPrevious) onPrevious() else return false
            Key.PageDown, Key.MediaNext -> if (currentHasNext) onNext() else return false
            Key.MoveHome -> onSeek(0L)
            else -> {
                val digit = DigitKeys.indexOf(event.key).takeIf { it >= 0 } ?: return false
                if (currentDurationMillis <= 0L) return false
                onSeek(currentDurationMillis * digit / 10)
            }
        }
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
            state.abandonArrowHold(onSpeedChange)
            state.endBoost(onSpeedChange)
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
