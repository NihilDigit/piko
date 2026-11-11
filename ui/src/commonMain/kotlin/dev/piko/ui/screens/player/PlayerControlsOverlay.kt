package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlaybackStatsSection
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.VideoCropRegion
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.isKeyboardModality
import dev.piko.ui.platform.LocalFramelessWindow
import io.github.nihildigit.pikpak.FileStat
import kotlin.math.abs

private val HUD_TOP_PADDING = 88.dp
private val COMPACT_WIDTH = 600.dp
private const val SUBTITLE_LABEL_MAX_LENGTH = 16
private val PLAIN_EPISODE = Regex("""[0-9]+(\.[0-9]+)?""")

/**
 * 播放器的完整控件层，叠在视频画面之上（Android 与桌面共用）：
 * 业务状态单向来自 `PlayerScreenState`，控件显隐、手势进退与瞬态读数由纯 UI 元素状态持有者
 * [PlayerControlsUiState] 管理，各栏与侧面板由顶层无状态组件渲染。
 */
@Composable
fun MobilePlayerControls(
    title: String,
    isLocalPlayback: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    playbackSpeed: Float?,
    aspectRatio: PlayerAspectRatio?,
    cropRegion: VideoCropRegion? = null,
    videoAspect: Float? = null,
    qualityOptions: List<String>,
    currentQuality: String?,
    errorMessage: String?,
    resumedFromMillis: Long?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onAspectRatioChange: (PlayerAspectRatio) -> Unit,
    onCropRegionChange: (VideoCropRegion?) -> Unit = {},
    onQualityChange: (String) -> Unit,
    onRetry: () -> Unit,
    onRestartFromBeginning: () -> Unit,
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
    seekThumbOnHoverOnly: Boolean = false,
    idleCursor: PointerIcon? = null,
    snackbarHost: @Composable () -> Unit = {},
    fileActions: List<SheetAction> = emptyList(),
    rotationDegrees: Int? = null,
    onRotationChange: (Int) -> Unit = {},
    onPickLocalSubtitle: (() -> Unit)? = null,
    onPickDriveSubtitle: ((FileStat) -> Unit)? = null,
    seekStepSeconds: Int = PlayerGestureDefaults.SEEK_STEP_SECONDS,
    onSeekStepChange: ((Int) -> Unit)? = null,
    longPressSpeed: Float = PlayerGestureDefaults.BOOST_SPEED,
    onLongPressSpeedChange: ((Float) -> Unit)? = null,
    playbackStats: (() -> List<PlaybackStatsSection>)? = null,
    loadingSpeedText: String? = null,
    registerKeyFallback: (((KeyEvent) -> Boolean)?) -> Unit = {},
) {
    val windowInfo = LocalWindowInfo.current
    val windowSize = windowInfo.containerSize
    val windowFocused = windowInfo.isWindowFocused
    val compactWidth = with(LocalDensity.current) { windowSize.width.toDp() } < COMPACT_WIDTH
    val isLandscape = windowSize.width > windowSize.height
    val focusRequester = remember { FocusRequester() }
    val pointerSource = LocalPointerSource.current
    val framelessWindow = LocalFramelessWindow.current
    var subtreeHasFocus by remember { mutableStateOf(false) }
    var rootSelfFocused by remember { mutableStateOf(false) }

    val controlsHover = remember { MutableInteractionSource() }
    val isHoveringControls by controlsHover.collectIsHoveredAsState()

    val ui = rememberPlayerControlsUiState(
        currentFileId = currentFileId,
        isPlaying = isPlaying,
        isHoveringControls = isHoveringControls,
        resumedFromMillis = resumedFromMillis,
        playbackSpeed = playbackSpeed,
        rotationDegrees = rotationDegrees,
        longPressSpeed = longPressSpeed,
        errorMessage = errorMessage,
        onSpeedChange = onSpeedChange,
    )
    SideEffect {
        ui.currentPositionMillis = positionMillis
        ui.currentDurationMillis = durationMillis
        ui.currentPlaybackSpeed = playbackSpeed
        ui.currentRotationDegrees = rotationDegrees
        ui.currentIsPlaying = isPlaying
        ui.currentIsFullscreen = isFullscreen
        ui.currentSeekStepMillis = seekStepSeconds * 1000L
        ui.currentLongPressSpeed = longPressSpeed
        ui.currentVolumeControl = volume
        ui.currentAudioTracks = audioTracks
        ui.currentSelectedAudioId = selectedAudioTrackId
        ui.currentSubtitleTracks = subtitleTracks
        ui.currentSelectedSubtitleId = selectedSubtitleTrackId
        ui.currentHasPrevious = hasPrevious
        ui.currentHasNext = hasNext
        ui.currentHasStats = playbackStats != null
        ui.currentIsAlwaysOnTop = framelessWindow?.isAlwaysOnTop
    }

    val latestOnPlayPause by rememberUpdatedState(onPlayPause)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnSpeedChange by rememberUpdatedState(onSpeedChange)
    val latestOnAspectRatioChange by rememberUpdatedState(onAspectRatioChange)
    val latestOnToggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    val latestOnRotationChange by rememberUpdatedState(onRotationChange)
    val latestOnSelectAudioTrack by rememberUpdatedState(onSelectAudioTrack)
    val latestOnSelectSubtitleTrack by rememberUpdatedState(onSelectSubtitleTrack)
    val latestOnPrevious by rememberUpdatedState(onPrevious)
    val latestOnNext by rememberUpdatedState(onNext)
    val latestFramelessWindow by rememberUpdatedState(framelessWindow)
    val latestRegisterKeyFallback by rememberUpdatedState(registerKeyFallback)

    val dispatchPlayerKey: (KeyEvent, Boolean) -> Boolean = remember(ui) {
        { event, letFocusedControlHandleSpace ->
            ui.handleKey(
                event = event,
                onPlayPause = latestOnPlayPause,
                onSeek = latestOnSeek,
                onSpeedChange = latestOnSpeedChange,
                onAspectRatioChange = latestOnAspectRatioChange,
                onToggleFullscreen = latestOnToggleFullscreen,
                onRotationChange = latestOnRotationChange,
                onSelectSubtitleTrack = latestOnSelectSubtitleTrack,
                onPrevious = latestOnPrevious,
                onNext = latestOnNext,
                onSelectAudioTrack = latestOnSelectAudioTrack,
                onToggleAlwaysOnTop = latestFramelessWindow?.let { fw -> { fw.setAlwaysOnTop(!fw.isAlwaysOnTop) } },
                letFocusedControlHandleSpace = letFocusedControlHandleSpace,
            )
        }
    }
    DisposableEffect(dispatchPlayerKey) {
        latestRegisterKeyFallback { event -> dispatchPlayerKey(event, false) }
        onDispose { latestRegisterKeyFallback(null) }
    }

    val holdControls = ui.shouldHoldControls(isPlaying, errorMessage, isHoveringControls)
    val currentHoldControls by rememberUpdatedState(holdControls)

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(viewportSize, aspectRatio, cropRegion) {
        if (aspectRatio == PlayerAspectRatio.RegionCrop && cropRegion != null && viewportSize.width > 0 && viewportSize.height > 0) {
            val currentAspect = viewportSize.width.toFloat() / viewportSize.height.toFloat()
            if (abs(cropRegion.containerAspect - currentAspect) > 0.001f) {
                onCropRegionChange(cropRegion.copy(containerAspect = currentAspect))
            }
        }
    }

    val hudIndicator = ui.computeHudIndicator(durationMillis)
    val centerIndicator = ui.flashIndicator
    val seekPreviewMillis = (ui.activeGesture as? PlayerGesture.Seek)?.targetMillis(durationMillis) ?: ui.settledSeekMillis

    val chromeVisible = ui.controlsVisible && !ui.isLocked && !ui.isEditingCropRegion
    var stalledLoading by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        stalledLoading = false
        if (isLoading) {
            kotlinx.coroutines.delay(dev.piko.ui.components.LOADING_APPEAR_DELAY_MS)
            stalledLoading = true
        }
    }
    val bottomBarVisible = (chromeVisible || seekPreviewMillis != null) && !ui.isEditingCropRegion
    val centerVisible = ((chromeVisible && !ui.isBoosting) || (isLoading && (chromeVisible || stalledLoading)) || centerIndicator != null) && errorMessage == null && !ui.isEditingCropRegion

    BackHandler(enabled = isFullscreen && !ui.isEditingCropRegion && !ui.isSpeedPopupOpen && ui.openSheet == null, onBack = onToggleFullscreen)
    BackHandler(enabled = ui.isSpeedPopupOpen && !ui.isEditingCropRegion) { ui.isSpeedPopupOpen = false }
    BackHandler(enabled = ui.isEditingCropRegion) { ui.cancelCropEdit(onAspectRatioChange) }

    LaunchedEffect(
        subtreeHasFocus,
        windowFocused,
        chromeVisible,
        ui.openSheet,
        ui.isSpeedPopupOpen,
        ui.isEditingCropRegion,
        pointerSource.isTouchLike,
    ) {
        if (!pointerSource.isTouchLike && windowFocused && !subtreeHasFocus && ui.openSheet == null && !ui.isSpeedPopupOpen) {
            withFrameNanos {}
            if (!subtreeHasFocus && !pointerSource.isTouchLike) {
                runCatching { focusRequester.requestFocus() }
            }
        }
    }

    val hasPlaylist = playlist.size > 1
    val hasTracksEntry = subtitleTracks.isNotEmpty() || audioTracks.size > 1 ||
        onPickLocalSubtitle != null || onPickDriveSubtitle != null
    val episodeLabel = playlist.find { it.fileId == currentFileId }
        ?.takeIf { hasPlaylist && it.label.length <= SUBTITLE_LABEL_MAX_LENGTH }
        ?.label
        ?.let { if (it.matches(PLAIN_EPISODE)) tr("第 $it 集", "Episode $it", "第 $it 話", "에피소드 $it", "第 $it 集") else it }

    val volumeLevel = volume?.current()
    val hideCursor = idleCursor != null && ui.isMouseIdle && !ui.controlsVisible && isPlaying

    PlayerTheme {
        val motion = MaterialTheme.motionScheme
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape) false
                    else dispatchPlayerKey(event, subtreeHasFocus && !rootSelfFocused && isKeyboardModality)
                }
                .onKeyEvent { event ->
                    if (event.key == Key.Escape) dispatchPlayerKey(event, false) else false
                }
                .onFocusChanged { state ->
                    subtreeHasFocus = state.hasFocus
                    rootSelfFocused = state.isFocused
                }
                .focusRequester(focusRequester)
                .then(if (!pointerSource.isTouchLike) Modifier.focusTarget() else Modifier)
                .pointerInput(Unit) {
                    var lastMousePosition: Offset? = null
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse }
                            val hovering = event.type == PointerEventType.Move && mouse != null && !mouse.pressed &&
                                lastMousePosition.let { it != null && it != mouse.position }
                            if (mouse != null) lastMousePosition = if (event.type == PointerEventType.Exit) null else mouse.position
                            if (hovering) {
                                ui.controlsVisible = true
                                ui.interacted()
                                ui.mouseMoveCount += 1
                            }
                            val left = event.type == PointerEventType.Exit && mouse != null &&
                                !Rect(Offset.Zero, size.toSize()).contains(mouse.position)
                            if (left && !currentHoldControls) ui.controlsVisible = false
                        }
                    }
                }
                .then(if (hideCursor) Modifier.pointerHoverIcon(idleCursor) else Modifier),
        ) {
            PlayerGestureLayer(
                isLocked = ui.isLocked || ui.isEditingCropRegion,
                durationMillis = durationMillis,
                positionProvider = { ui.currentPositionMillis },
                brightness = brightness,
                volume = volume,
                onGestureChange = { ui.activeGesture = it },
                onToggleControls = {
                    if (pointerSource.isTouchLike) {
                        ui.controlsVisible = !ui.controlsVisible
                    } else {
                        if (!rootSelfFocused) runCatching { focusRequester.requestFocus() }
                        ui.interacted()
                        onPlayPause()
                    }
                },
                onSeekTo = { target ->
                    ui.recordSettledSeek(target)
                    onSeek(target)
                },
                onDoubleTap = { zone ->
                    when {
                        !pointerSource.isTouchLike -> onToggleFullscreen()
                        zone == DoubleTapZone.PlayPause -> {
                            ui.flash(
                                CenterIndicator(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    if (isPlaying) tr("暂停", "Pause", "一時停止", "일시 중지", "暫停") else tr("播放", "Play", "再生", "재생", "播放"),
                                    if (isPlaying) tr("已暂停", "Paused", "一時停止中", "일시 중지됨", "已暫停") else tr("继续播放", "Continue playback", "再生を続ける", "계속 재생", "繼續播放"),
                                ),
                            )
                            onPlayPause()
                            ui.interacted()
                        }
                        else -> ui.stepSeek(forward = zone == DoubleTapZone.Forward, onSeek = onSeek, byTap = true)
                    }
                },
                seekTapActive = { ui.doubleTapVisible && ui.tapSeekRound && pointerSource.isTouchLike },
                onSpeedBoost = { active -> if (active) ui.startBoost(onSpeedChange) else ui.endBoost(onSpeedChange) },
                modifier = Modifier.pointerInput(ui.isLocked, ui.isEditingCropRegion) {
                    if (ui.isLocked || ui.isEditingCropRegion) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val dy = change.scrollDelta.y
                            if (dy == 0f) continue
                            ui.stepVolume(if (dy < 0f) VOLUME_KEY_STEP else -VOLUME_KEY_STEP)
                            change.consume()
                        }
                    }
                },
            )

            val isSeeking = ui.isScrubbing || seekPreviewMillis != null
            PlayerBottomStack(controlsVisible = bottomBarVisible, isLandscape = isLandscape) {
                snackbarHost()
                if (resumedFromMillis != null) {
                    ResumeTipCapsule(
                        visible = ui.showResumeTip && !ui.isLocked && !isSeeking,
                        resumedPositionMillis = resumedFromMillis,
                        onRestart = {
                            onRestartFromBeginning()
                            ui.showResumeTip = false
                        },
                        onDismiss = { ui.showResumeTip = false },
                    )
                }
                FullscreenPromptButton(
                    visible = !isFullscreen && !ui.isLocked && !isLandscape && isLandscapeVideo == true &&
                        errorMessage == null && !ui.isEditingCropRegion && !isSeeking,
                    onClick = onToggleFullscreen,
                )
                RestoreCropPromptButton(
                    visible = chromeVisible && !isSeeking && aspectRatio == PlayerAspectRatio.RegionCrop && errorMessage == null,
                    onClick = {
                        ui.interacted()
                        onCropRegionChange(null)
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
                        episodeLabel = episodeLabel,
                        isLocalPlayback = isLocalPlayback,
                        onBackClick = onBack,
                        onTracksClick = {
                            ui.interacted()
                            ui.openSheet = PlayerSheet.Tracks
                        }.takeIf { hasTracksEntry },
                        onStatsClick = {
                            ui.interacted()
                            ui.openSheet = PlayerSheet.Stats
                        }.takeIf { playbackStats != null },
                        onSettingsClick = {
                            ui.interacted()
                            ui.openSheet = PlayerSheet.Settings
                        }.takeIf {
                            hasTracksEntry || fileActions.isNotEmpty() || playbackSpeed != null ||
                                qualityOptions.isNotEmpty() || aspectRatio != null || onSeekStepChange != null
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .hoverable(controlsHover)
                            .animateEnterExit(
                                enter = slideInVertically(motion.defaultSpatialSpec()) { -it / 2 },
                                exit = slideOutVertically(motion.fastSpatialSpec()) { -it / 2 },
                            ),
                    )
                }
            }

            AnimatedVisibility(
                visible = bottomBarVisible,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    PlayerBottomBar(
                        isLandscape = isLandscape,
                        isFullscreen = isFullscreen,
                        thumbOnHoverOnly = seekThumbOnHoverOnly,
                        positionMillis = positionMillis,
                        previewPositionMillis = seekPreviewMillis,
                        durationMillis = durationMillis,
                        bufferedPositionMillis = bufferedPositionMillis,
                        playbackSpeed = playbackSpeed,
                        showEpisodes = hasPlaylist,
                        showEpisodeSkip = hasPlaylist && !compactWidth,
                        compactWidth = compactWidth,
                        hasPrevious = hasPrevious,
                        hasNext = hasNext,
                        onPrevious = {
                            ui.interacted()
                            onPrevious()
                        },
                        onNext = {
                            ui.interacted()
                            onNext()
                        },
                        onSeek = {
                            ui.interacted()
                            onSeek(it)
                        },
                        isSpeedPopupOpen = ui.isSpeedPopupOpen,
                        onSpeedPopupOpenChange = {
                            ui.interacted()
                            ui.isSpeedPopupOpen = it
                        },
                        onSpeedChange = {
                            ui.interacted()
                            onSpeedChange(it)
                        },
                        onEpisodesClick = { ui.openSheet = PlayerSheet.Episodes },
                        onRotate = rotationDegrees?.let { degrees ->
                            {
                                ui.interacted()
                                onRotationChange((degrees + 90) % 360)
                            }
                        },
                        onToggleFullscreen = {
                            ui.interacted()
                            onToggleFullscreen()
                        },
                        onScrubbingChange = { ui.isScrubbing = it },
                        volume = volumeLevel,
                        onVolumeChange = {
                            ui.interacted()
                            ui.setVolume(it)
                        },
                        onToggleMute = {
                            ui.interacted()
                            ui.toggleMute(showHud = false)
                        },
                        onVolumeDraggingChange = { ui.isDraggingVolume = it },
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

            PlayerHud(
                indicator = if (ui.isEditingCropRegion) null else hudIndicator,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(top = HUD_TOP_PADDING),
            )

            PlayerCenterControls(
                visible = centerVisible,
                isPlaying = isPlaying,
                isLoading = isLoading,
                indicator = centerIndicator,
                loadingSpeedText = loadingSpeedText,
                modifier = Modifier.align(Alignment.Center),
                onPlayPause = {
                    if (chromeVisible) {
                        ui.interacted()
                        onPlayPause()
                    } else {
                        ui.controlsVisible = true
                    }
                },
            )

            AnimatedVisibility(
                visible = ui.controlsVisible && errorMessage == null && !ui.isEditingCropRegion,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                    .padding(end = 16.dp),
            ) {
                LockToggle(
                    isLocked = ui.isLocked,
                    onToggle = {
                        ui.isLocked = !ui.isLocked
                        ui.interacted()
                    },
                )
            }

            if (ui.isEditingCropRegion) {
                CropRegionEditorOverlay(
                    initialRegion = cropRegion ?: VideoCropRegion(),
                    videoAspect = videoAspect,
                    onCancel = { ui.cancelCropEdit(onAspectRatioChange) },
                    onReset = {
                        ui.isEditingCropRegion = false
                        onCropRegionChange(null)
                    },
                    onConfirm = { region ->
                        ui.isEditingCropRegion = false
                        onCropRegionChange(region)
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

            PlayerSheetsOverlay(
                openSheet = ui.openSheet,
                isLandscape = isLandscape,
                title = title,
                playlist = playlist,
                currentFileId = currentFileId,
                hideEpisodeThumbnails = hideEpisodeThumbnails,
                fileActions = fileActions,
                playbackSpeed = playbackSpeed,
                qualityOptions = qualityOptions,
                currentQuality = currentQuality,
                aspectRatio = aspectRatio,
                seekStepSeconds = seekStepSeconds,
                longPressSpeed = longPressSpeed,
                audioTracks = audioTracks,
                selectedAudioTrackId = selectedAudioTrackId,
                subtitleTracks = subtitleTracks,
                selectedSubtitleTrackId = selectedSubtitleTrackId,
                onDismiss = { ui.openSheet = null },
                onOpenSheet = { ui.openSheet = it },
                onSelectEntry = onSelectEntry,
                onSpeedChange = onSpeedChange,
                onQualityChange = onQualityChange,
                onAspectRatioChange = onAspectRatioChange,
                onBeginCropEdit = { ui.beginCropEdit(aspectRatio, onAspectRatioChange) },
                onSeekStepChange = onSeekStepChange,
                onLongPressSpeedChange = onLongPressSpeedChange,
                onSelectAudioTrack = onSelectAudioTrack,
                onSelectSubtitleTrack = onSelectSubtitleTrack,
                onPickLocalSubtitle = onPickLocalSubtitle,
                onPickDriveSubtitle = onPickDriveSubtitle,
                playbackStats = playbackStats,
            )
        }
    }
}
