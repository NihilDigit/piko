package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
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
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import kotlinx.coroutines.delay

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
 * 唤出控件；[idleCursor] 不为 null 时，播放中鼠标一段时间没动，指针换成它（桌面传一个透明指针）。
 * 键盘：空格播放暂停，左右方向键快退快进（与双击同样累加），上下方向键调音量，F 切换全屏。
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
    // 锁定只防触屏误触，鼠标与键盘用不上
    showLockToggle: Boolean = true,
    idleCursor: PointerIcon? = null,
    // 调用方的消息提示放进底部提示区，与续播提示、全屏入口一起排布，不各自定位
    snackbarHost: @Composable () -> Unit = {},
) {
    val windowSize = LocalWindowInfo.current.containerSize
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
    fun stepSeek(forward: Boolean) {
        val continuing = doubleTapVisible && doubleTapForward == forward
        val base = if (continuing) doubleTapTargetMillis else currentPosition
        val step = if (forward) SEEK_STEP_MILLIS else -SEEK_STEP_MILLIS
        val target = (base + step).coerceIn(0L, durationMillis.coerceAtLeast(0L))
        val stepSeconds = (SEEK_STEP_MILLIS / 1000).toInt()
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

    fun handleKey(key: Key): Boolean {
        when (key) {
            Key.Spacebar -> onPlayPause()
            Key.DirectionLeft -> stepSeek(forward = false)
            Key.DirectionRight -> stepSeek(forward = true)
            Key.DirectionUp -> stepVolume(VOLUME_KEY_STEP)
            Key.DirectionDown -> stepVolume(-VOLUME_KEY_STEP)
            Key.F -> onToggleFullscreen()
            else -> return false
        }
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
    // 倍速浮层挂在底栏上，底栏一收起它就跟着消失，开着时同样不收
    val holdControls = !isPlaying || isScrubbing || openSheet != null || isSpeedPopupOpen || errorMessage != null
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

    val hud = activeGesture ?: keyVolume?.let { PlayerGesture.Adjust(VerticalAdjust.Volume, it) }
    // 指针只看鼠标自己：一段时间没动才藏。原先跟着控件收起一起藏，单击画面收起控件时指针也立刻消失，
    // 手还在鼠标上就找不到指针
    val hideCursor = idleCursor != null && isMouseIdle && isPlaying

    PlayerTheme {
        val motion = MaterialTheme.motionScheme
        Box(
            modifier = modifier
                .fillMaxSize()
                // 在根节点先行拦截：焦点落在某个按钮上时，空格不该变成「点一下那个按钮」
                .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && handleKey(event.key) }
                // 只接焦点不进无障碍树：focusable 会让读屏在整个画面上多停一站
                .focusRequester(focusRequester)
                .focusTarget()
                // Initial 阶段只观察不消费，停在按钮与面板上的移动也算。只认没按键、位置真变了的
                // 鼠标移动：触屏的移动都是拖动，不该顺带唤出控件；指针不动而底下的布局变了时，
                // 桌面端可能补发原地的移动，不滤掉的话控件收起后会被它重新唤出
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val hovering = event.type == PointerEventType.Move &&
                                event.changes.any {
                                    it.type == PointerType.Mouse && !it.pressed && it.position != it.previousPosition
                                }
                            if (hovering) {
                                controlsVisible = true
                                interacted()
                                mouseMoveCount += 1
                            }
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
                onToggleControls = { controlsVisible = !controlsVisible },
                onSeekTo = onSeek,
                onDoubleTap = { zone ->
                    if (zone == DoubleTapZone.PlayPause) onPlayPause() else stepSeek(forward = zone == DoubleTapZone.Forward)
                },
                onSpeedBoost = { active ->
                    val speed = currentSpeed ?: return@PlayerGestureLayer
                    if (active) {
                        speedBeforeBoost = speed
                        isBoosting = true
                        onSpeedChange(LONG_PRESS_BOOST_SPEED)
                    } else if (isBoosting) {
                        isBoosting = false
                        onSpeedChange(speedBeforeBoost)
                    }
                },
            )

            hud?.let { PlayerGestureHud(it, durationMillis) }

            AnimatedVisibility(
                visible = doubleTapVisible,
                enter = fadeIn(motion.fastEffectsSpec()),
                exit = fadeOut(motion.defaultEffectsSpec()),
                modifier = Modifier.fillMaxSize(),
            ) {
                DoubleTapIndicator(forward = doubleTapForward, seconds = doubleTapSeconds)
            }

            SpeedBoostCapsule(visible = isBoosting, isLandscape = isLandscape, speed = LONG_PRESS_BOOST_SPEED)

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
                        }.takeIf { subtitleTracks.isNotEmpty() || audioTracks.size > 1 },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    PlayerBottomBar(
                        isLandscape = isLandscape,
                        isFullscreen = isFullscreen,
                        positionMillis = positionMillis,
                        durationMillis = durationMillis,
                        bufferedPositionMillis = bufferedPositionMillis,
                        playbackSpeed = playbackSpeed,
                        showEpisodes = hasPlaylist,
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
                        onToggleFullscreen = {
                            interacted()
                            onToggleFullscreen()
                        },
                        onScrubbingChange = { isScrubbing = it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            // 中央按钮组不在控件栏的淡入淡出里：加载时播放键要单独留在画面中央，变形后承载加载指示，
            // 不再另叠一个指示器。两侧按钮随控件栏显隐，由组件自己处理
            AnimatedVisibility(
                visible = (chromeVisible || isLoading) && hud == null && errorMessage == null,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.Center),
            ) {
                PlayerCenterControls(
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    isLandscape = isLandscape,
                    showSideButtons = chromeVisible,
                    showEpisodeSkip = hasPlaylist,
                    hasPrevious = hasPrevious,
                    hasNext = hasNext,
                    onPlayPause = {
                        // 控件收起时只剩这个按钮在转，点它先唤出控件，与点画面其他地方一致
                        if (chromeVisible) {
                            interacted()
                            onPlayPause()
                        } else {
                            controlsVisible = true
                        }
                    },
                    onSeekBackward = { seekBy(-SEEK_STEP_MILLIS) },
                    onSeekForward = { seekBy(SEEK_STEP_MILLIS) },
                    onPrevious = {
                        interacted()
                        onPrevious()
                    },
                    onNext = {
                        interacted()
                        onNext()
                    },
                )
            }

            // 锁定键跟随控件栏显隐；锁定后单击只唤出它自己
            AnimatedVisibility(
                visible = showLockToggle && controlsVisible && errorMessage == null,
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
                    )
                }
            }
        }
    }
}

private const val CONTROLS_HIDE_DELAY_MILLIS = 4_500L
// 与控件同时收：指针先没了而控件还在，想点控件时要先晃一下鼠标
private const val CURSOR_HIDE_DELAY_MILLIS = CONTROLS_HIDE_DELAY_MILLIS
private const val RESUME_TIP_DURATION_MILLIS = 5_000L
private const val DOUBLE_TAP_FEEDBACK_MILLIS = 700L
private const val KEY_VOLUME_HUD_MILLIS = 800L
private const val VOLUME_KEY_STEP = 0.05f
private const val SUBTITLE_LABEL_MAX_LENGTH = 16
private val PLAIN_EPISODE = Regex("""\d+(\.\d+)?""")
