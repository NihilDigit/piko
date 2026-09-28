package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.SheetAction
import io.github.nihildigit.pikpak.FileStat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastRewind
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
/**
 * 播放器的完整控件层，叠在视频画面之上。Android 与桌面共用，名字沿用只有 Android 时的叫法。
 *
 * 无状态于播放器：只接收下面这组基础类型的值与回调，不引用任何播放器对象，数据全部来自
 * PlayerScreenState。这里自己持有的只有纯界面状态：控件显隐与自动隐藏计时、锁定、手势 HUD、
 * 双击累计、长按加速、续播提示计时、选集与设置面板的开合。
 *
 * 布局按所在窗口的宽高比分横竖，不看设备朝向：桌面窗口通常是横的，用的就是 Android 横屏
 * 那一套（侧边面板、大号中央按钮）。
 *
 * 平台附加项：[brightness] 与 [volume] 是竖滑手势与上下方向键调节的对象，平台没有就传 null；
 * 全屏由 [onToggleFullscreen] 交给调用方（Android 切横竖屏，桌面切窗口全屏），[isFullscreen]
 * 只决定全屏键的图标；[isLandscapeVideo] 决定竖屏时是否给出全屏入口。
 * 触屏与鼠标的点击、双击、拖动都走同一个手势层。鼠标悬停不产生点击，所以另外监听鼠标移动来
 * 唤出控件；[idleCursor] 不为 null 时，播放中控件收起、鼠标又一段时间没动，指针换成它（桌面传一个透明指针）。
 * 键盘：空格播放暂停，左右方向键快退快进（与双击同样累加），上下方向键调音量，F 切换全屏，R 顺时针转 90 度。
 * 全屏时返回（Android 的返回手势、桌面的 Esc）先退出全屏；其余时候返回的含义由调用方决定。
 */
@Composable
fun MobilePlayerControls(
    // 播放器数据契约
    title: String,
    isLocalPlayback: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    playbackSpeed: Float?,
    aspectRatio: PlayerAspectRatio?,
    qualityOptions: List<String>,
    currentQuality: String?,
    errorMessage: String?,
    resumedFromMillis: Long?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onAspectRatioChange: (PlayerAspectRatio) -> Unit,
    onQualityChange: (String) -> Unit,
    onRetry: () -> Unit,
    onRestartFromBeginning: () -> Unit,
    // 平台附加项
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    isLandscapeVideo: Boolean? = null,
    playlist: List<PlaylistEntry> = emptyList(),
    currentFileId: String = "",
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSelectEntry: (PlaylistEntry) -> Unit = {},
    hideEpisodeThumbnails: Boolean = true,
    audioTracks: List<MediaTrack> = emptyList(),
    selectedAudioTrackId: String? = null,
    onSelectAudioTrack: (MediaTrack) -> Unit = {},
    subtitleTracks: List<MediaTrack> = emptyList(),
    selectedSubtitleTrackId: String? = null,
    onSelectSubtitleTrack: (MediaTrack?) -> Unit = {},
    brightness: PlayerLevelControl? = null,
    volume: PlayerLevelControl? = null,
    // 进度条手柄平时隐藏、鼠标悬停才出现。触屏没有悬停，要一直显示
    seekThumbOnHoverOnly: Boolean = false,
    idleCursor: PointerIcon? = null,
    // 调用方的消息提示放进底部提示区，与续播提示、全屏入口一起排布，不各自定位
    snackbarHost: @Composable () -> Unit = {},
    // 作用于这个文件的操作（分享、下载），见 rememberPlayerFileActions
    fileActions: List<SheetAction> = emptyList(),
    // 画面旋转的度数，后端不能旋转时为 null，设置面板与 R 键都不给
    rotationDegrees: Int? = null,
    onRotationChange: (Int) -> Unit = {},
    // 手动挂字幕。本机的由平台弹文件选择框，选完自己交给播放器；网盘的在字幕面板里就地浏览，选中后回调
    onPickLocalSubtitle: (() -> Unit)? = null,
    onPickDriveSubtitle: ((FileStat) -> Unit)? = null,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    // 窄到 M3 的 compact（600dp 以下，竖着的手机）时底栏只留常用的：换集有选集面板，旋转有 R 键，
    // 全摆出来时间读数就被挤没了
    val compactWidth = with(LocalDensity.current) { windowSize.width.toDp() } < COMPACT_WIDTH
    val isLandscape = windowSize.width > windowSize.height
    val accessibilityManager = LocalAccessibilityManager.current
    val focusRequester = remember { FocusRequester() }

    val currentPosition by rememberUpdatedState(positionMillis)
    val currentSpeed by rememberUpdatedState(playbackSpeed)

    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var activeGesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var isScrubbing by remember { mutableStateOf(false) }
    var openSheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var isSpeedPopupOpen by remember { mutableStateOf(false) }
    var mouseMoveCount by remember { mutableIntStateOf(0) }
    var isMouseIdle by remember { mutableStateOf(false) }
    // 每次用户操作控件时加一，让自动隐藏重新计时
    var interactionCount by remember { mutableIntStateOf(0) }

    var doubleTapVisible by remember { mutableStateOf(false) }
    var doubleTapForward by remember { mutableStateOf(true) }
    var doubleTapSeconds by remember { mutableIntStateOf(0) }
    var doubleTapCount by remember { mutableIntStateOf(0) }
    // 连续双击时位置回报跟不上，下一次在上一次的目标上累加，而不是在旧位置上
    var doubleTapTargetMillis by remember { mutableLongStateOf(0L) }

    var isBoosting by remember { mutableStateOf(false) }
    var speedBeforeBoost by remember { mutableFloatStateOf(1f) }
    // 松开长按时倍速改回原值，那一下不算「用户改了倍速」，不必再闪一次读数
    var skipSpeedFlash by remember { mutableStateOf(false) }

    // 播放键上临时显示的读数，见 PlayPauseButton。每闪一次加一，让收起的计时重新开始
    var flashIndicator by remember { mutableStateOf<CenterIndicator?>(null) }
    var flashCount by remember { mutableIntStateOf(0) }
    val currentRotation by rememberUpdatedState(rotationDegrees)

    var showResumeTip by remember(resumedFromMillis) { mutableStateOf(resumedFromMillis != null) }

    // 方向键调音量时借用手势 HUD 显示数值，停手一会儿后收起
    var keyVolume by remember { mutableStateOf<Float?>(null) }
    var keyVolumeCount by remember { mutableIntStateOf(0) }

    fun interacted() {
        interactionCount += 1
    }

    fun seekBy(deltaMillis: Long) {
        val limit = durationMillis.coerceAtLeast(0L)
        onSeek((currentPosition + deltaMillis).coerceIn(0L, limit))
        interacted()
    }

    // 双击两侧与左右方向键共用：连续同向操作累加，反馈显示本轮累计的秒数
    fun stepSeek(forward: Boolean, stepMillis: Long = SEEK_STEP_MILLIS) {
        val continuing = doubleTapVisible && doubleTapForward == forward
        val base = if (continuing) doubleTapTargetMillis else currentPosition
        val step = if (forward) stepMillis else -stepMillis
        val target = (base + step).coerceIn(0L, durationMillis.coerceAtLeast(0L))
        val stepSeconds = (stepMillis / 1000).toInt()
        doubleTapTargetMillis = target
        doubleTapSeconds = if (continuing) doubleTapSeconds + stepSeconds else stepSeconds
        doubleTapForward = forward
        doubleTapVisible = true
        doubleTapCount += 1
        onSeek(target)
    }

    fun stepVolume(delta: Float) {
        val control = volume ?: return
        keyVolume = control.set((control.current() + delta).coerceIn(0f, 1f))
        keyVolumeCount += 1
    }

    // 按住期间临时换倍速，松开回到原值：触屏长按画面与桌面按住方向键共用
    var boostSpeed by remember { mutableFloatStateOf(LONG_PRESS_BOOST_SPEED) }

    fun startBoost(speed: Float) {
        val current = currentSpeed ?: return
        if (isBoosting) return
        speedBeforeBoost = current
        boostSpeed = speed
        isBoosting = true
        onSpeedChange(speed)
    }

    fun endBoost() {
        if (!isBoosting) return
        isBoosting = false
        skipSpeedFlash = speedBeforeBoost != boostSpeed
        onSpeedChange(speedBeforeBoost)
    }

    // 左右方向键：轻按是进退，按住超过 HOLD_ARROW_MILLIS 变成快进（→ 临时倍速，松开复原）或快退（←）。
    // 播放器倒着放不了，快退是按住期间一小步一小步往回跳，读数照双击进退那样累加。
    // 系统的按键连发分不出是按住还是连按，所以进退改在松开时做，按下只起计时
    val scope = rememberCoroutineScope()
    var heldArrow by remember { mutableStateOf<Key?>(null) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var arrowHeld by remember { mutableStateOf(false) }

    // 撤掉按住的状态，不做轻按的那一步：停掉快退的循环，按住 → 时把倍速还回去
    fun abandonArrowHold() {
        holdJob?.cancel()
        holdJob = null
        if (arrowHeld && heldArrow == Key.DirectionRight) endBoost()
        heldArrow = null
        arrowHeld = false
    }

    fun arrowDown(key: Key) {
        if (heldArrow == key) return
        // 按着一个方向键又按下另一个：先前那个的松开再也对不上号，不在这里收尾的话临时倍速就一直留着
        abandonArrowHold()
        heldArrow = key
        holdJob = scope.launch {
            delay(HOLD_ARROW_MILLIS)
            arrowHeld = true
            if (key == Key.DirectionRight) {
                startBoost(LONG_PRESS_BOOST_SPEED)
            } else {
                while (true) {
                    stepSeek(forward = false, stepMillis = HOLD_REWIND_STEP_MILLIS)
                    delay(HOLD_REWIND_INTERVAL_MILLIS)
                }
            }
        }
    }

    fun arrowUp(key: Key): Boolean {
        if (heldArrow != key) return false
        heldArrow = null
        holdJob?.cancel()
        holdJob = null
        when {
            !arrowHeld -> stepSeek(forward = key == Key.DirectionRight)
            key == Key.DirectionRight -> endBoost()
        }
        arrowHeld = false
        interacted()
        return true
    }

    // 按住期间窗口失去焦点（Alt+Tab、弹出系统对话框），松开事件送到别的窗口去了，这里永远等不到：
    // 失焦即当作松开，方向键的按住与触屏长按的临时倍速一并收回
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (!windowFocused) {
            abandonArrowHold()
            endBoost()
        }
    }

    // 静音前的音量，再按一次 M 回到这里。不在静音时为 null
    var volumeBeforeMute by remember { mutableStateOf<Float?>(null) }

    fun toggleMute() {
        val control = volume ?: return
        val restore = volumeBeforeMute
        keyVolume = if (restore != null && control.current() == 0f) {
            control.set(restore)
        } else {
            volumeBeforeMute = control.current().takeIf { it > 0f } ?: VOLUME_KEY_STEP
            control.set(0f)
        }
        if (keyVolume != 0f) volumeBeforeMute = null
        keyVolumeCount += 1
    }

    // 倍速按预设一档一档地换；当前值不在预设里时，往那个方向取最近的一档
    fun stepSpeed(faster: Boolean) {
        val speed = currentSpeed ?: return
        val next = if (faster) PlayerSpeedPresets.firstOrNull { it > speed + 0.001f } else PlayerSpeedPresets.lastOrNull { it < speed - 0.001f }
        next?.let(onSpeedChange)
    }

    // C 键开关字幕：关掉时记下是哪一条，再开回同一条；没记过就开第一条
    var lastSubtitleId by remember { mutableStateOf<String?>(null) }
    val currentSubtitleTracks by rememberUpdatedState(subtitleTracks)
    val currentSubtitleId by rememberUpdatedState(selectedSubtitleTrackId)

    fun toggleSubtitles(): Boolean {
        val tracks = currentSubtitleTracks
        if (tracks.isEmpty()) return false
        val selected = currentSubtitleId
        if (selected != null) {
            lastSubtitleId = selected
            onSelectSubtitleTrack(null)
        } else {
            onSelectSubtitleTrack(tracks.find { it.id == lastSubtitleId } ?: tracks.first())
        }
        return true
    }

    // 键位照主流桌面播放器（YouTube、mpv、PotPlayer 的公约数），加了要同时写进 ShortcutsDialog 的「播放器」一节。
    // 带 Ctrl、Alt、⌘ 的一律放过，留给窗口与系统
    fun handleKey(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp) return arrowUp(event.key)
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val shift = event.isShiftPressed
        when (event.key) {
            Key.Spacebar, Key.K -> onPlayPause()
            Key.DirectionLeft, Key.DirectionRight -> {
                val forward = event.key == Key.DirectionRight
                // Shift 的大步不接按住，按下即进退
                if (shift) stepSeek(forward, LONG_SEEK_STEP_MILLIS) else arrowDown(event.key)
            }
            Key.J -> stepSeek(forward = false)
            Key.L -> stepSeek(forward = true)
            Key.DirectionUp -> stepVolume(VOLUME_KEY_STEP)
            Key.DirectionDown -> stepVolume(-VOLUME_KEY_STEP)
            Key.M -> toggleMute()
            Key.F -> onToggleFullscreen()
            Key.R -> onRotationChange(((currentRotation ?: return false) + 90) % 360)
            Key.LeftBracket -> stepSpeed(faster = false)
            Key.RightBracket -> stepSpeed(faster = true)
            Key.Backspace -> onSpeedChange(1f)
            Key.C -> return toggleSubtitles()
            Key.PageUp -> if (hasPrevious) onPrevious() else return false
            Key.PageDown -> if (hasNext) onNext() else return false
            Key.MoveHome -> onSeek(0L)
            else -> {
                // 数字键跳到全片的几成处，0 是开头
                val digit = DigitKeys.indexOf(event.key).takeIf { it >= 0 } ?: return false
                if (durationMillis <= 0L) return false
                onSeek(durationMillis * digit / 10)
            }
        }
        interacted()
        return true
    }

    val hideDelayMillis = remember(accessibilityManager) {
        // 读屏或「操作等待时间」无障碍设置开着时，系统会给出更长的建议值（可能是不限时）
        accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = CONTROLS_HIDE_DELAY_MILLIS,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: CONTROLS_HIDE_DELAY_MILLIS
    }
    // 光标停在控件栏上时不自动收起：只看鼠标移动的话，光标停在进度条上不动，几秒后控件栏就在
    // 光标底下收走了。两栏共用一个 source，hoverable 在控件栏退场被移除时会补发 Exit，不会卡在悬停
    val controlsHover = remember { MutableInteractionSource() }
    val isHoveringControls by controlsHover.collectIsHoveredAsState()
    // 倍速浮层挂在底栏上，底栏一收起它就跟着消失，开着时同样不收
    val holdControls = !isPlaying || isScrubbing || openSheet != null || isSpeedPopupOpen || errorMessage != null ||
        isHoveringControls
    val currentHoldControls by rememberUpdatedState(holdControls)
    // 鼠标与手指对点按的解释不同，见 [PointerSource]。鼠标沿用桌面播放器的通行约定：单击播放或暂停，
    // 双击全屏，控件由移动光标唤出；手指单击切换控件，双击按落点进退或暂停
    val pointerSource = LocalPointerSource.current
    LaunchedEffect(controlsVisible, holdControls, interactionCount, hideDelayMillis) {
        if (controlsVisible && !holdControls && hideDelayMillis != Long.MAX_VALUE) {
            delay(hideDelayMillis)
            controlsVisible = false
        }
    }
    LaunchedEffect(mouseMoveCount) {
        isMouseIdle = false
        delay(CURSOR_HIDE_DELAY_MILLIS)
        isMouseIdle = true
    }
    // 出错时控件栏必须可见：返回键在顶栏里
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) controlsVisible = true
    }

    LaunchedEffect(showResumeTip) {
        if (showResumeTip) {
            delay(RESUME_TIP_DURATION_MILLIS)
            showResumeTip = false
        }
    }

    LaunchedEffect(doubleTapCount) {
        if (doubleTapCount > 0) {
            delay(DOUBLE_TAP_FEEDBACK_MILLIS)
            doubleTapVisible = false
        }
    }
    LaunchedEffect(keyVolumeCount) {
        if (keyVolume != null) {
            delay(KEY_VOLUME_HUD_MILLIS)
            keyVolume = null
        }
    }

    fun flash(indicator: CenterIndicator) {
        flashIndicator = indicator
        flashCount += 1
    }
    LaunchedEffect(flashCount) {
        if (flashIndicator != null) {
            delay(INDICATOR_FLASH_MILLIS)
            flashIndicator = null
        }
    }
    // 倍速与旋转无论从哪里改（面板、底栏的倍速浮层、快捷键），都在播放键上闪一下新值。
    // 比对的是上一次看到的值：首次组合与换集都不算改动
    var lastSpeed by remember { mutableStateOf(playbackSpeed) }
    LaunchedEffect(playbackSpeed) {
        val previous = lastSpeed
        lastSpeed = playbackSpeed
        val speed = playbackSpeed ?: return@LaunchedEffect
        if (previous == null || previous == speed || isBoosting) return@LaunchedEffect
        if (skipSpeedFlash) {
            skipSpeedFlash = false
            return@LaunchedEffect
        }
        flash(CenterIndicator(Icons.Outlined.Speed, formatSpeedMultiplier(speed), "倍速 ${formatSpeedPreset(speed)}"))
    }
    var lastRotation by remember { mutableStateOf(rotationDegrees) }
    LaunchedEffect(rotationDegrees) {
        val previous = lastRotation
        lastRotation = rotationDegrees
        val degrees = rotationDegrees ?: return@LaunchedEffect
        if (previous == null || previous == degrees) return@LaunchedEffect
        flash(CenterIndicator(Icons.Outlined.Rotate90DegreesCw, "$degrees°", "画面旋转 $degrees 度"))
    }
    // 所有读数都由播放键变形托住，不另叠浮层：原先音量亮度是屏幕正中的一块面板、双击进退是贴边的半圆，
    // 各有各的样子，与播放键的读数叠在一起时互相遮挡。先后按「手上正在做的」排：拖动手势、按住倍速、
    // 刚调的音量、累计的进退，最后是闪一下的倍速与旋转
    val gesture = activeGesture ?: keyVolume?.let { PlayerGesture.Adjust(VerticalAdjust.Volume, it) }
    val centerIndicator = when {
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
                description = if (brightnessGesture) "亮度 $percent" else "音量 $percent",
                progress = gesture.fraction,
            )
        }
        gesture is PlayerGesture.Seek -> {
            val target = gesture.targetMillis(durationMillis)
            CenterIndicator(
                icon = if (target >= gesture.startPositionMillis) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                text = formatTime(target),
                description = "跳到 ${formatTime(target)}",
                progress = if (durationMillis > 0) target.toFloat() / durationMillis else null,
            )
        }
        isBoosting -> CenterIndicator(Icons.Filled.FastForward, formatSpeedMultiplier(boostSpeed), "倍速播放中")
        doubleTapVisible -> {
            val sign = if (doubleTapForward) "+" else "−"
            CenterIndicator(
                icon = if (doubleTapForward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                text = "$sign$doubleTapSeconds 秒",
                description = if (doubleTapForward) "快进 $doubleTapSeconds 秒" else "快退 $doubleTapSeconds 秒",
            )
        }
        else -> flashIndicator
    }

    val chromeVisible = controlsVisible && !isLocked
    // 面板的 BackHandler 在它之后组合，面板开着时先关面板
    BackHandler(enabled = isFullscreen, onBack = onToggleFullscreen)

    // 焦点在点过的按钮上时，按钮随控件栏收起或面板关闭，焦点也跟着没了，之后的按键无处可去。
    // 每逢这两种变化把焦点收回根节点
    LaunchedEffect(chromeVisible, openSheet) {
        runCatching { focusRequester.requestFocus() }
    }
    val hasPlaylist = playlist.size > 1
    // 显示区分段而不是「第几个 / 共几个」：目录里混着剧场版与特典时，序号对不上集数
    val episodeLabel = playlist.find { it.fileId == currentFileId }
        ?.takeIf { hasPlaylist && it.label.length <= SUBTITLE_LABEL_MAX_LENGTH }
        ?.label
        // 纯数字集号写成「第 24 集」；「25(SP)」「23 Beta」这类照原样，套上「第…集」反而别扭
        ?.let { if (it.matches(PLAIN_EPISODE)) "第 $it 集" else it }

    // 控件收起且鼠标一段时间没动才藏指针。只看控件时，单击画面收起控件指针也立刻消失，手还在鼠标上
    // 就找不到它；只看鼠标时，控件还显示着指针却没了
    val hideCursor = idleCursor != null && isMouseIdle && !controlsVisible && isPlaying

    PlayerTheme {
        val motion = MaterialTheme.motionScheme
        Box(
            modifier = modifier
                .fillMaxSize()
                // 在根节点先行拦截：焦点落在某个按钮上时，空格不该变成「点一下那个按钮」
                .onPreviewKeyEvent(::handleKey)
                // 只接焦点不进无障碍树：focusable 会让读屏在整个画面上多停一站
                .focusRequester(focusRequester)
                .focusTarget()
                // Initial 阶段只观察不消费，停在按钮与面板上的移动也算。只认没按键、位置真变了的
                // 鼠标移动：触屏的移动都是拖动，不该顺带唤出控件；指针不动而底下的布局变了时，
                // 桌面端可能补发原地的移动，不滤掉的话控件收起后会被它重新唤出
                //
                // 光标离开播放器时立即收起控件，不等计时。控件因故常驻时（暂停、面板开着）不收
                .pointerInput(Unit) {
                    // 自己记上一次的鼠标位置：Compose 桌面端悬停移动的 previousPosition 与 position
                    // 恒相等（CMP 1.12 实测），拿它比较的话任何移动都不算数，控件收起后再也唤不出来
                    var lastMousePosition: Offset? = null
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse }
                            val hovering = event.type == PointerEventType.Move && mouse != null && !mouse.pressed &&
                                lastMousePosition.let { it != null && it != mouse.position }
                            // 进出画面之后的第一次移动只记位置：原地补发的事件也会先经过这里
                            if (mouse != null) lastMousePosition = if (event.type == PointerEventType.Exit) null else mouse.position
                            if (hovering) {
                                controlsVisible = true
                                interacted()
                                mouseMoveCount += 1
                            }
                            // 只认真正出了画面的离开：桌面播放窗口的顶栏标题兼做拖动区，系统在那里把鼠标
                            // 交给窗口过程，Compose 同样收到 Exit，位置却仍在画面内。当作离开的话控件一收，
                            // 拖动区随之撤销，光标又落回画面把控件唤出，来回闪
                            val left = event.type == PointerEventType.Exit && mouse != null &&
                                !Rect(Offset.Zero, size.toSize()).contains(mouse.position)
                            if (left && !currentHoldControls) controlsVisible = false
                        }
                    }
                }
                .then(if (hideCursor) Modifier.pointerHoverIcon(idleCursor) else Modifier),
        ) {
            PlayerGestureLayer(
                isLocked = isLocked,
                durationMillis = durationMillis,
                positionProvider = { currentPosition },
                brightness = brightness,
                volume = volume,
                onGestureChange = { activeGesture = it },
                onToggleControls = {
                    if (pointerSource.isTouchLike) {
                        controlsVisible = !controlsVisible
                    } else {
                        interacted()
                        onPlayPause()
                    }
                },
                onSeekTo = onSeek,
                onDoubleTap = { zone ->
                    when {
                        !pointerSource.isTouchLike -> onToggleFullscreen()
                        zone == DoubleTapZone.PlayPause -> onPlayPause()
                        else -> stepSeek(forward = zone == DoubleTapZone.Forward)
                    }
                },
                onSpeedBoost = { active -> if (active) startBoost(LONG_PRESS_BOOST_SPEED) else endBoost() },
                // 滚轮调音量，与上下方向键同一步长。挂在手势层而不是根节点：面板里的列表滚到头后
                // 剩下的滚轮位移会冒泡到根节点，那时不该变成调音量
                modifier = Modifier.pointerInput(isLocked) {
                    if (isLocked) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val dy = change.scrollDelta.y
                            if (dy == 0f) continue
                            stepVolume(if (dy < 0f) VOLUME_KEY_STEP else -VOLUME_KEY_STEP)
                            change.consume()
                        }
                    }
                },
            )

            PlayerBottomStack(controlsVisible = chromeVisible, isLandscape = isLandscape) {
                snackbarHost()
                if (resumedFromMillis != null) {
                    ResumeTipCapsule(
                        visible = showResumeTip && !isLocked,
                        resumedPositionMillis = resumedFromMillis,
                        onRestart = {
                            onRestartFromBeginning()
                            showResumeTip = false
                        },
                        onDismiss = { showResumeTip = false },
                    )
                }
                // 竖屏放横屏片子时画面只占中间一条，下面整片黑边闲着，这里再给一个全屏入口
                FullscreenPromptButton(
                    visible = !isLocked && !isLandscape && isLandscapeVideo == true && errorMessage == null,
                    onClick = onToggleFullscreen,
                )
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    PlayerTopBar(
                        title = title,
                        episodeLabel = episodeLabel,
                        isLocalPlayback = isLocalPlayback,
                        onBackClick = onBack,
                        onSettingsClick = {
                            interacted()
                            openSheet = PlayerSheet.Settings
                        }.takeIf { playbackSpeed != null || qualityOptions.isNotEmpty() || aspectRatio != null },
                        onTracksClick = {
                            interacted()
                            openSheet = PlayerSheet.Tracks
                        }.takeIf {
                            // 文件本身没有字幕时也要给入口：正是这种时候才要自己挑一个
                            subtitleTracks.isNotEmpty() || audioTracks.size > 1 ||
                                onPickLocalSubtitle != null || onPickDriveSubtitle != null
                        },
                        fileActions = fileActions,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .hoverable(controlsHover)
                            // 淡入之外各自朝外滑半个栏高：整栏从屏幕外滑进来动作太大，只淡入又显得平
                            .animateEnterExit(
                                enter = slideInVertically(motion.defaultSpatialSpec()) { -it / 2 },
                                exit = slideOutVertically(motion.fastSpatialSpec()) { -it / 2 },
                            ),
                    )

                    PlayerBottomBar(
                        isLandscape = isLandscape,
                        isFullscreen = isFullscreen,
                        thumbOnHoverOnly = seekThumbOnHoverOnly,
                        positionMillis = positionMillis,
                        durationMillis = durationMillis,
                        bufferedPositionMillis = bufferedPositionMillis,
                        playbackSpeed = playbackSpeed,
                        showEpisodes = hasPlaylist,
                        showEpisodeSkip = hasPlaylist && !compactWidth,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        onPrevious = {
                            interacted()
                            onPrevious()
                        },
                        onNext = {
                            interacted()
                            onNext()
                        },
                        onSeek = {
                            interacted()
                            onSeek(it)
                        },
                        isSpeedPopupOpen = isSpeedPopupOpen,
                        onSpeedPopupOpenChange = {
                            interacted()
                            isSpeedPopupOpen = it
                        },
                        onSpeedChange = {
                            interacted()
                            onSpeedChange(it)
                        },
                        onEpisodesClick = { openSheet = PlayerSheet.Episodes },
                        onRotate = rotationDegrees?.takeIf { !compactWidth }?.let { degrees ->
                            {
                                interacted()
                                onRotationChange((degrees + 90) % 360)
                            }
                        },
                        onToggleFullscreen = {
                            interacted()
                            onToggleFullscreen()
                        },
                        onScrubbingChange = { isScrubbing = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .hoverable(controlsHover)
                            .animateEnterExit(
                                enter = slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
                                exit = slideOutVertically(motion.fastSpatialSpec()) { it / 2 },
                            ),
                    )
                }
            }

            // 播放键不在控件栏的淡入淡出里：加载时与有读数时，它要单独留在画面中央，
            // 变形后承载加载指示或读数，不再另叠一层
            AnimatedVisibility(
                visible = (chromeVisible || isLoading || centerIndicator != null) && errorMessage == null,
                enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = CENTER_ENTER_SCALE),
                exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = CENTER_ENTER_SCALE),
                modifier = Modifier.align(Alignment.Center),
            ) {
                PlayerCenterControls(
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    indicator = centerIndicator,
                    onPlayPause = {
                        // 控件收起时只剩这个按钮在转，点它先唤出控件，与点画面其他地方一致
                        if (chromeVisible) {
                            interacted()
                            onPlayPause()
                        } else {
                            controlsVisible = true
                        }
                    },
                )
            }

            // 锁定键跟随控件栏显隐；锁定后单击只唤出它自己
            AnimatedVisibility(
                visible = controlsVisible && errorMessage == null,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                    .padding(end = 16.dp),
            ) {
                LockToggle(
                    isLocked = isLocked,
                    onToggle = {
                        isLocked = !isLocked
                        interacted()
                    },
                )
            }

            errorMessage?.let { message ->
                PlaybackErrorCard(
                    message = message,
                    onRetry = onRetry,
                    onBack = onBack,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            PlayerSheetHost(
                sheet = openSheet,
                isLandscape = isLandscape,
                onDismiss = { openSheet = null },
            ) { sheet ->
                when (sheet) {
                    PlayerSheet.Episodes -> EpisodePanel(
                        entries = playlist,
                        currentFileId = currentFileId,
                        hideThumbnails = hideEpisodeThumbnails,
                        onSelect = {
                            openSheet = null
                            onSelectEntry(it)
                        },
                    )
                    PlayerSheet.Settings -> PlayerSettingsPanel(
                        playbackSpeed = playbackSpeed,
                        onSpeedChange = onSpeedChange,
                        qualityOptions = qualityOptions,
                        currentQuality = currentQuality,
                        onQualityChange = {
                            openSheet = null
                            onQualityChange(it)
                        },
                        aspectRatio = aspectRatio,
                        onAspectRatioChange = onAspectRatioChange,
                    )
                    // 选完不收面板：换字幕要看一眼效果，不对再换
                    PlayerSheet.Tracks -> TracksPanel(
                        audioTracks = audioTracks,
                        selectedAudioTrackId = selectedAudioTrackId,
                        onSelectAudio = onSelectAudioTrack,
                        subtitleTracks = subtitleTracks,
                        selectedSubtitleTrackId = selectedSubtitleTrackId,
                        onSelectSubtitle = onSelectSubtitleTrack,
                        onPickLocalSubtitle = onPickLocalSubtitle,
                        onPickDriveSubtitle = onPickDriveSubtitle?.let { { openSheet = PlayerSheet.DriveSubtitles } },
                    )
                    // 选中后回到音轨与字幕：新挂的那条在列表里亮起，看得到已经换上
                    PlayerSheet.DriveSubtitles -> DriveSubtitlePanel(
                        videoFileId = currentFileId,
                        onPick = { file ->
                            onPickDriveSubtitle?.invoke(file)
                            openSheet = PlayerSheet.Tracks
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private const val CONTROLS_HIDE_DELAY_MILLIS = 4_500L

// 中央按钮组进出时的缩放起点：略小一点再放大，与两侧按钮 0.6 的进场区分开，整组不会像是弹出来的
private const val CENTER_ENTER_SCALE = 0.9f
// 与控件同时收：指针先没了而控件还在，想点控件时要先晃一下鼠标
private const val CURSOR_HIDE_DELAY_MILLIS = CONTROLS_HIDE_DELAY_MILLIS
private const val RESUME_TIP_DURATION_MILLIS = 5_000L
private const val DOUBLE_TAP_FEEDBACK_MILLIS = 700L
private const val KEY_VOLUME_HUD_MILLIS = 800L
// 比音量 HUD 久一点：读数在胶囊里还要等变形走完才看得清
private const val INDICATOR_FLASH_MILLIS = 1_200L
private const val VOLUME_KEY_STEP = 0.05f

private val COMPACT_WIDTH = 600.dp

// Shift+方向键的大步进退
private const val LONG_SEEK_STEP_MILLIS = 60_000L

// 方向键按住多久算按住而不是轻按；系统的连发延迟约 500ms，短于它才不必等第一下连发
private const val HOLD_ARROW_MILLIS = 350L

// 按住 ← 快退：每隔多久往回跳一步、一步多长。约合十倍速倒放；步子再密，网盘取流跟不上，画面只会停在缓冲上
private const val HOLD_REWIND_STEP_MILLIS = 5_000L
private const val HOLD_REWIND_INTERVAL_MILLIS = 500L

private val DigitKeys = listOf(
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
)
private const val SUBTITLE_LABEL_MAX_LENGTH = 16
private val PLAIN_EPISODE = Regex("""\d+(\.\d+)?""")
