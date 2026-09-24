package dev.piko.ui.screens.player

import android.content.res.Configuration
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
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import kotlinx.coroutines.delay

/**
 * Android 播放器的完整控件层，叠在视频画面之上。
 *
 * 无状态于播放器：只接收下面这组基础类型的值与回调，不引用任何播放器对象。
 * Desktop 的 FluentPlayerControls 接收同一组值与回调，两端可由同一个 shared state holder 驱动。
 * 这里自己持有的只有纯界面状态：控件显隐与自动隐藏计时、锁定、手势 HUD、双击累计、
 * 长按加速、续播提示计时、选集与设置面板的开合。
 *
 * 平台附加项：亮度与系统音量由手势层直接改窗口与 AudioManager；横竖屏由 [onToggleFullscreen]
 * 交给调用方的 ScreenOrientationController；[isLandscapeVideo] 决定竖屏时是否给出全屏入口。
 */
@Composable
fun MobilePlayerControls(
    // 播放器数据契约（与 Desktop 一致）
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
    isLandscapeVideo: Boolean? = null,
    playlist: List<PlaylistEntry> = emptyList(),
    currentFileId: String = "",
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSelectEntry: (PlaylistEntry) -> Unit = {},
    hideEpisodeThumbnails: Boolean = true,
    // 调用方的消息提示放进底部提示区，与续播提示、全屏入口一起排布，不各自定位
    snackbarHost: @Composable () -> Unit = {},
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current
    val brightness = rememberWindowBrightness()
    val volume = rememberMediaVolume(context)
    val accessibilityManager = LocalAccessibilityManager.current

    val currentPosition by rememberUpdatedState(positionMillis)
    val currentSpeed by rememberUpdatedState(playbackSpeed)

    var controlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var activeGesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var isScrubbing by remember { mutableStateOf(false) }
    var openSheet by remember { mutableStateOf<PlayerSheet?>(null) }
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

    fun interacted() {
        interactionCount += 1
    }

    fun seekBy(deltaMillis: Long) {
        val limit = durationMillis.coerceAtLeast(0L)
        onSeek((currentPosition + deltaMillis).coerceIn(0L, limit))
        interacted()
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
    val holdControls = !isPlaying || isScrubbing || openSheet != null || errorMessage != null
    LaunchedEffect(controlsVisible, holdControls, interactionCount, hideDelayMillis) {
        if (controlsVisible && !holdControls && hideDelayMillis != Long.MAX_VALUE) {
            delay(hideDelayMillis)
            controlsVisible = false
        }
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

    val chromeVisible = controlsVisible && !isLocked
    val hasPlaylist = playlist.size > 1
    // 显示区分段而不是「第几个 / 共几个」：目录里混着剧场版与特典时，序号对不上集数
    val episodeLabel = playlist.find { it.fileId == currentFileId }
        ?.takeIf { hasPlaylist && it.label.length <= SUBTITLE_LABEL_MAX_LENGTH }
        ?.label
        // 纯数字集号写成「第 24 集」；「25(SP)」「23 Beta」这类照原样，套上「第…集」反而别扭
        ?.let { if (it.matches(PLAIN_EPISODE)) "第 $it 集" else it }

    PlayerTheme {
        val motion = MaterialTheme.motionScheme
        Box(modifier = modifier.fillMaxSize()) {
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
                    if (zone == DoubleTapZone.PlayPause) {
                        onPlayPause()
                        return@PlayerGestureLayer
                    }
                    val forward = zone == DoubleTapZone.Forward
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

            activeGesture?.let { PlayerGestureHud(it, durationMillis) }

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
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    PlayerBottomBar(
                        isLandscape = isLandscape,
                        positionMillis = positionMillis,
                        durationMillis = durationMillis,
                        bufferedPositionMillis = bufferedPositionMillis,
                        playbackSpeed = playbackSpeed,
                        showEpisodes = hasPlaylist,
                        onSeek = {
                            interacted()
                            onSeek(it)
                        },
                        onSpeedClick = { openSheet = PlayerSheet.Speed },
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
                visible = (chromeVisible || isLoading) && activeGesture == null && errorMessage == null,
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
                    PlayerSheet.Speed -> if (playbackSpeed != null) {
                        PlaybackSpeedPanel(playbackSpeed = playbackSpeed, onSpeedChange = onSpeedChange)
                    }
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
                }
            }
        }
    }
}

private const val CONTROLS_HIDE_DELAY_MILLIS = 4_500L
private const val RESUME_TIP_DURATION_MILLIS = 5_000L
private const val DOUBLE_TAP_FEEDBACK_MILLIS = 700L
private const val SUBTITLE_LABEL_MAX_LENGTH = 16
private val PLAIN_EPISODE = Regex("""\d+(\.\d+)?""")
