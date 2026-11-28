@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.IosSileroVadLoader
import dev.piko.shared.media.player.PlayerScreenState
import dev.piko.shared.media.player.SubtitleStylePrefs
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.ObserveMessages
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.player.CustomizablePlayerTopBar
import dev.piko.ui.screens.player.ListenScreen
import dev.piko.ui.screens.player.LocalPlayerLayoutPrefs
import dev.piko.ui.screens.player.MobilePlayerControls
import dev.piko.ui.screens.player.PlayerTheme
import dev.piko.ui.screens.player.playlistOf
import dev.piko.ui.screens.player.rememberPlayerFileActions
import dev.piko.ui.screens.player.rememberScrubFrameCache
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.Foundation.NSFileManager
import platform.UIKit.UIApplication
import kotlin.math.abs

/**
 * iOS 自定义内置播放器页面：
 * - 取流策略、画质切换、续播、选集、重连与实时 AI 字幕均复用共享 [PlayerScreenState]；
 * - 底层采用 [IosPlaybackBackend]（`AVPlayer` + 持久 `AVPlayerLayer` + `IosAvPlayerMediaBridge`）；
 * - 界面复用共享 [MobilePlayerControls] 与 [ListenScreen]，遵循 `LocalPlayerLayoutPrefs` 自定义样式与手势；
 * - 集成画中画（[IosPictureInPictureController]）、锁屏/耳机播控（[IosNowPlayingController]）、
 *   亮度/音量手势、防息屏与沉浸状态栏/方向控制。
 */
@Composable
internal fun IosVideoPlayerScreen(
    initialFileId: String,
    initialFileName: String,
    initialLocalPath: String? = null,
    initialStartMillis: Long? = null,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val preferences = services.preferences
    val scope = rememberCoroutineScope()

    var siblingVideos by remember(initialFileId) { mutableStateOf<List<FileStat>>(emptyList()) }
    var stateRef by remember(initialFileId) { mutableStateOf<PlayerScreenState?>(null) }

    val backend = remember(initialFileId) {
        IosPlaybackBackend(
            durationHintSecondsProvider = {
                stateRef?.mediaInfo?.durationSeconds?.takeIf { it > 0L } ?: 3600L
            },
        )
    }

    val state = remember(initialFileId, backend) {
        val snapshot = preferences.currentSnapshot
        PlayerScreenState(
            repository = services.mediaRepository,
            backend = backend,
            scope = scope,
            initialFileId = initialFileId,
            initialFileName = initialFileName,
            initialLocalPath = initialLocalPath,
            initialStartMillis = initialStartMillis,
            resolveLocalPath = { _, hint ->
                hint?.takeIf { it.isNotBlank() && NSFileManager.defaultManager.fileExistsAtPath(it) }
            },
            defaultMaxHeight = { preferences.playbackMaxHeightFlow.first() },
            aiSubtitleDecoder = if (IosAiSubtitleBridge.isAvailable) IosAiSubtitlePcmDecoder() else null,
            aiSubtitleVadFactory = { IosSileroVadLoader.create() },
            initialAiSubtitleLanguage = snapshot.aiSubtitleLanguage,
            onPersistAiSubtitleLanguage = { preferences.setAiSubtitleLanguage(it) },
            initialAiSubtitleOnDemand = snapshot.aiSubtitleOnDemand,
            onPersistAiSubtitleOnDemand = { preferences.setAiSubtitleOnDemand(it) },
            initialAiSubtitleSavedSeconds = snapshot.aiSubtitleSavedSeconds,
            onAddAiSubtitleSavedSeconds = { preferences.addAiSubtitleSavedSeconds(it) },
        ).also { stateRef = it }
    }

    val pipController = remember(backend) { IosPictureInPictureController.create(backend.playerLayer) }
    val isInPip by (pipController?.isInPictureInPicture ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) })
        .collectAsStateWithLifecycle()

    DisposableEffect(state, backend, pipController) {
        onDispose {
            pipController?.dispose()
            try {
                state.release()
            } finally {
                backend.release()
            }
        }
    }

    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var listenMode by rememberSaveable(initialFileId) { mutableStateOf(false) }
    val showListen = !state.isImage && (state.isAudioFile || listenMode)

    fun enterFullscreen(landscapeVideo: Boolean) {
        isFullscreen = true
        IosHostBridge.setOrientationMode(
            if (landscapeVideo) IosHostBridge.ORIENTATION_LANDSCAPE else IosHostBridge.ORIENTATION_PORTRAIT,
        )
    }

    fun exitFullscreen() {
        isFullscreen = false
        IosHostBridge.setOrientationMode(IosHostBridge.ORIENTATION_UNSPECIFIED)
    }

    DisposableEffect(Unit) {
        onDispose {
            IosHostBridge.setOrientationMode(IosHostBridge.ORIENTATION_UNSPECIFIED)
        }
    }

    // 视频模式始终采用浅色状态栏；全屏时隐藏状态栏与 Home 指示条
    DisposableEffect(isFullscreen, showListen) {
        val release = if (showListen) {
            null
        } else {
            IosHostBridge.acquireImmersiveBars(hidden = isFullscreen, lightContent = true)
        }
        onDispose { release?.invoke() }
    }

    // 听音频 / 听视频模式切换：隐藏视频层但不销毁 AVPlayer，音频无缝延续
    LaunchedEffect(showListen) {
        backend.setAudioOnlyMode(showListen)
        if (showListen && isFullscreen) {
            exitFullscreen()
        }
    }

    // 视频播放期间防息屏；听模式或暂停允许系统正常息屏
    val keepScreenOn = !showListen && !state.isImage && state.isPlaying
    DisposableEffect(keepScreenOn) {
        UIApplication.sharedApplication.idleTimerDisabled = keepScreenOn
        onDispose {
            UIApplication.sharedApplication.idleTimerDisabled = false
        }
    }

    // 画中画自动进入策略：仅在视频模式且正在播放时允许系统上滑自动进小窗
    val autoPipEnabled = !showListen && !state.isImage && state.isPlaying
    LaunchedEffect(pipController, autoPipEnabled) {
        pipController?.updatePolicy(autoEnterEnabled = autoPipEnabled)
    }

    // 加载同目录选集列表
    LaunchedEffect(initialFileId) {
        if (initialFileId.isEmpty()) return@LaunchedEffect
        val siblings = services.driveRepository.siblingMedia(initialFileId)
        siblingVideos = siblings.videos
        state.playlist = playlistOf(siblingVideos, preferences, siblings.subtitles, currentFileId = initialFileId)
    }

    val brightness = remember { IosScreenBrightnessControl() }
    val volume = remember(backend) { IosPlayerVolumeControl(backend) }
    DisposableEffect(brightness) {
        onDispose { brightness.restore() }
    }

    val seekStepSeconds by preferences.playerSeekStepSecondsFlow.collectAsStateWithLifecycle(
        initialValue = PlayerGestureDefaults.SEEK_STEP_SECONDS,
    )
    val longPressSpeed by preferences.playerBoostSpeedFlow.collectAsStateWithLifecycle(
        initialValue = PlayerGestureDefaults.BOOST_SPEED,
    )
    val playerLayoutPrefs by preferences.playerLayoutPrefsFlow.collectAsStateWithLifecycle(
        initialValue = preferences.currentSnapshot.playerLayout,
    )
    val subtitleStyle by preferences.subtitleStylePrefsFlow.collectAsStateWithLifecycle(
        initialValue = preferences.currentSnapshot.subtitleStyle,
    )
    val spoilerBlur by preferences.spoilerBlurFlow.collectAsStateWithLifecycle(
        initialValue = preferences.currentSnapshot.spoilerBlurEnabled,
    )
    val aiSubtitleState by state.aiSubtitleState.collectAsStateWithLifecycle()

    val latestSeekStepSeconds by rememberUpdatedState(seekStepSeconds)
    val latestShowListen by rememberUpdatedState(showListen)
    val latestAutoPipEnabled by rememberUpdatedState(autoPipEnabled)
    val latestIsInPip by rememberUpdatedState(isInPip)

    // 锁屏与控制中心播控
    val nowPlaying = remember(state) {
        IosNowPlayingController(
            onPlay = { state.play() },
            onPause = { state.pause() },
            onTogglePlayPause = { state.togglePlayPause() },
            onPrevious = { state.playPrevious() },
            onNext = { state.playNext() },
            onSeekTo = { ms -> state.seekTo(ms) },
            onSeekBy = { delta -> state.seekBy(delta) },
            seekStepSecondsProvider = { latestSeekStepSeconds },
        )
    }
    DisposableEffect(nowPlaying) {
        onDispose { nowPlaying.dispose() }
    }

    val listenArtistLabel = tr("听音频", "Listen", "音声再生", "오디오 듣기", "聽音訊")
    LaunchedEffect(state, nowPlaying, showListen, seekStepSeconds) {
        var lastReportedPosMs = Long.MIN_VALUE
        var lastReportedPlaying: Boolean? = null
        var lastReportedTitle: String? = null
        var lastReportedSpeed = -1f
        snapshotFlow {
            Triple(
                state.isImage,
                state.title to state.durationMillis,
                Triple(state.positionMillis / 1_000L, state.isPlaying, state.playbackSpeed ?: 1f),
            )
        }.collect { (isImage, meta, runtime) ->
            if (isImage) {
                nowPlaying.clear()
                return@collect
            }
            val (title, durationMs) = meta
            val (posSec, playing, speed) = runtime
            val posMs = posSec * 1_000L
            val changed = title != lastReportedTitle ||
                playing != lastReportedPlaying ||
                speed != lastReportedSpeed ||
                abs(posMs - lastReportedPosMs) >= 1_500L
            if (changed) {
                lastReportedTitle = title
                lastReportedPlaying = playing
                lastReportedSpeed = speed
                lastReportedPosMs = posMs
                nowPlaying.update(
                    title = title,
                    artist = if (showListen) listenArtistLabel else "Piko",
                    durationMillis = durationMs,
                    positionMillis = posMs,
                    isPlaying = playing,
                    speed = speed,
                    hasPrevious = state.previousEntry != null,
                    hasNext = state.nextEntry != null,
                    seekStepSeconds = seekStepSeconds,
                )
            }
        }
    }

    // 退后台生命周期处理：始终刷写进度；听模式、正在 PiP 或允许自动进 PiP 时绝不暂停，防止扼杀系统自动 PiP
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                state.flushProgress()
                if (!latestShowListen && !latestIsInPip && !latestAutoPipEnabled) {
                    state.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    ObserveMessages(state.messages, snackbarHostState)

    val leave: () -> Unit = {
        pipController?.updatePolicy(autoEnterEnabled = false)
        pipController?.exitPictureInPicture()
        if (isFullscreen) exitFullscreen()
        onBackClick()
    }

    CompositionLocalProvider(LocalPlayerLayoutPrefs provides playerLayoutPrefs) {
        when {
            state.isImage -> {
                Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
                    AsyncImage(
                        model = state.mediaInfo?.currentUrl,
                        contentDescription = state.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                    PlayerTheme {
                        CustomizablePlayerTopBar(
                            title = state.title,
                            episodeLabel = null,
                            isLocalPlayback = state.isLocalPlayback,
                            onBackClick = leave,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                }
            }
            showListen -> {
                Box(modifier = modifier.fillMaxSize()) {
                    ListenScreen(
                        state = state,
                        onBackClick = {
                            if (!state.isAudioFile && listenMode) {
                                listenMode = false
                            } else {
                                leave()
                            }
                        },
                        volumeControl = volume,
                        onSwitchToVideo = if (!state.isAudioFile) ({ listenMode = false }) else null,
                    )
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    )
                }
            }
            else -> {
                val scrubFrameCache = rememberScrubFrameCache(
                    state = state,
                    storyboardCache = services.storyboardCache,
                    mediaRepository = services.mediaRepository,
                    platform = platform,
                )
                val fileActions = rememberPlayerFileActions(
                    fileId = state.fileId,
                    isLocalPlayback = state.isLocalPlayback,
                    onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                )
                Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
                    // 保持 IosVideoSurface 在视频分支中始终稳定存在，避免进出 PiP 重建导致 AVPlayerLayer 失效
                    IosVideoSurface(
                        backend = backend,
                        modifier = Modifier.fillMaxSize(),
                    )
                    MobilePlayerControls(
                        title = state.title,
                        isLocalPlayback = state.isLocalPlayback,
                        isPlaying = state.isPlaying,
                        isLoading = state.isLoading,
                        loadingSpeedText = state.loadingSpeedText,
                        durationMillis = state.durationMillis,
                        positionMillisProvider = { state.positionMillis },
                        bufferedPositionMillisProvider = { state.bufferedPositionMillis },
                        playbackSpeed = state.playbackSpeed,
                        aspectRatio = state.aspectRatio,
                        videoAspect = state.videoAspect,
                        rotationDegrees = state.rotationDegrees.takeIf { state.supportsRotation },
                        onRotationChange = { deg ->
                            val turned = (deg - state.rotationDegrees) % 180 != 0
                            val landscapeVideo = state.isLandscapeVideo
                            state.setRotation(deg)
                            if (turned && isFullscreen && landscapeVideo != null) {
                                enterFullscreen(!landscapeVideo)
                            }
                        },
                        qualityOptions = state.qualityOptions,
                        currentQuality = state.currentQuality,
                        autoResolvedQuality = state.autoResolvedQuality,
                        errorMessage = state.errorMessage,
                        resumedFromMillis = state.resumedFromMillis,
                        onPlayPause = state::togglePlayPause,
                        onPausePlayback = state::pause,
                        onResumePlayback = state::play,
                        onSeek = state::seekTo,
                        onSpeedChange = state::setSpeed,
                        onAspectRatioChange = state::setAspectRatio,
                        onQualityChange = state::selectQuality,
                        onRetry = state::retry,
                        onRestartFromBeginning = state::restartFromBeginning,
                        onBack = {
                            if (isFullscreen) exitFullscreen() else leave()
                        },
                        onToggleFullscreen = {
                            if (isFullscreen) {
                                exitFullscreen()
                            } else {
                                enterFullscreen(state.isLandscapeVideo != false)
                            }
                        },
                        isFullscreen = isFullscreen,
                        isLandscapeVideo = state.isLandscapeVideo,
                        playlist = state.playlist,
                        currentFileId = state.fileId,
                        hasPrevious = state.previousEntry != null,
                        hasNext = state.nextEntry != null,
                        onPrevious = state::playPrevious,
                        onNext = state::playNext,
                        onSelectEntry = state::playEntry,
                        hideEpisodeThumbnails = spoilerBlur,
                        audioTracks = state.audioTracks,
                        selectedAudioTrackId = state.selectedAudioTrackId,
                        onSelectAudioTrack = state::selectAudioTrack,
                        subtitleTracks = state.subtitleTracks,
                        selectedSubtitleTrackId = state.selectedSubtitleTrackId,
                        onSelectSubtitleTrack = state::selectSubtitleTrack,
                        originalSubtitleHints = state.originalSubtitleHints,
                        subtitleStyle = subtitleStyle,
                        onSubtitleStylePreview = { /* AI 字幕叠层实时响应 previewSubtitleStyle */ },
                        onSubtitleStyleChange = { next: SubtitleStylePrefs ->
                            val sanitized = next.sanitize()
                            scope.launch { preferences.setSubtitleStylePrefs(sanitized) }
                        },
                        aiSubtitleState = aiSubtitleState,
                        onToggleAiSubtitles = state::setAiSubtitlesEnabled,
                        onSelectAiSubtitleLanguage = state::selectAiSubtitleLanguage,
                        onToggleAiSubtitleOnDemand = state::setAiSubtitleOnDemand,
                        brightness = brightness,
                        volume = volume,
                        seekStepSeconds = seekStepSeconds,
                        onSeekStepChange = { sec -> scope.launch { preferences.setPlayerSeekStepSeconds(sec) } },
                        longPressSpeed = longPressSpeed,
                        onLongPressSpeedChange = { spd -> scope.launch { preferences.setPlayerBoostSpeed(spd) } },
                        scrubFrameCache = scrubFrameCache,
                        fileActions = fileActions,
                        onSwitchToListen = {
                            if (isFullscreen) exitFullscreen()
                            listenMode = true
                        },
                        snackbarHost = {
                            SnackbarHost(hostState = snackbarHostState)
                        },
                    )
                }
            }
        }
    }
}
