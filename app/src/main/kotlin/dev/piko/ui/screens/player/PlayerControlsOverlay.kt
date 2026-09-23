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
import kotlinx.coroutines.delay

/**
 * Android 播放器的完整控件层，叠在视频画面之上。
 *
 * 无状态于播放器：只接收下面这组基础类型的值与回调，不引用任何播放器对象。
 * Desktop 的 FluentPlayerControls 接收同一组值与回调，两端可由同一个 shared state holder 驱动。
 * 这里自己持有的只有纯界面状态：控件显隐与自动隐藏计时、锁定、手势 HUD、双击累计、
 * 长按加速、续播提示计时、菜单与倍速面板的开合。
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
    showPlaylistEntry: Boolean = false,
    onPlaylistClick: () -> Unit = {},
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
    var isMenuOpen by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
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
    val holdControls = !isPlaying || isScrubbing || isMenuOpen || showSpeedSheet || errorMessage != null
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

            if (isLoading && !chromeVisible && errorMessage == null) {
                PlayerLoadingIndicator(Modifier.align(Alignment.Center))
            }

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

            // 竖屏放横屏片子时画面只占中间一条，下面整片黑边闲着，这里给一个大目标
            FullscreenPromptButton(
                visible = !isLocked && !isLandscape && isLandscapeVideo == true && errorMessage == null,
                controlsVisible = chromeVisible,
                onClick = onToggleFullscreen,
            )

            if (resumedFromMillis != null) {
                ResumeTipCapsule(
                    visible = showResumeTip && !isLocked,
                    resumedPositionMillis = resumedFromMillis,
                    isLandscape = isLandscape,
                    controlsVisible = chromeVisible,
                    onRestart = {
                        onRestartFromBeginning()
                        showResumeTip = false
                    },
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
                        isLocalPlayback = isLocalPlayback,
                        aspectRatio = aspectRatio,
                        qualityOptions = qualityOptions,
                        currentQuality = currentQuality,
                        showPlaylistEntry = showPlaylistEntry,
                        onPlaylistClick = {
                            interacted()
                            onPlaylistClick()
                        },
                        onBackClick = onBack,
                        onAspectRatioChange = {
                            interacted()
                            onAspectRatioChange(it)
                        },
                        onQualityChange = {
                            interacted()
                            onQualityChange(it)
                        },
                        onMenuOpenChange = { isMenuOpen = it },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    if (activeGesture == null && errorMessage == null) {
                        PlayerCenterControls(
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            isLandscape = isLandscape,
                            onPlayPause = {
                                interacted()
                                onPlayPause()
                            },
                            onSeekBackward = { seekBy(-SEEK_STEP_MILLIS) },
                            onSeekForward = { seekBy(SEEK_STEP_MILLIS) },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    PlayerBottomBar(
                        isLandscape = isLandscape,
                        positionMillis = positionMillis,
                        durationMillis = durationMillis,
                        bufferedPositionMillis = bufferedPositionMillis,
                        playbackSpeed = playbackSpeed,
                        onSeek = {
                            interacted()
                            onSeek(it)
                        },
                        onSpeedClick = { showSpeedSheet = true },
                        onToggleFullscreen = {
                            interacted()
                            onToggleFullscreen()
                        },
                        onScrubbingChange = { isScrubbing = it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            // 锁定键跟随控件栏显隐；锁定后单击只唤出它自己
            AnimatedVisibility(
                visible = controlsVisible && errorMessage == null,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                    .padding(start = 16.dp),
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

            if (showSpeedSheet && playbackSpeed != null) {
                PlaybackSpeedSheet(
                    speed = playbackSpeed,
                    onSpeedChange = onSpeedChange,
                    onDismiss = { showSpeedSheet = false },
                )
            }
        }
    }
}

private const val CONTROLS_HIDE_DELAY_MILLIS = 4_500L
private const val RESUME_TIP_DURATION_MILLIS = 5_000L
private const val DOUBLE_TAP_FEEDBACK_MILLIS = 700L
