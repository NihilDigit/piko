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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.DEFAULT_DOUBLE_TAP_SEEK_SECONDS
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.OriginalSubtitleTrackInfo
import dev.piko.shared.media.player.PlaybackStatsSection
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.SubtitleStylePrefs
import dev.piko.shared.media.player.VideoCropRegion
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.isKeyboardModality
import dev.piko.ui.platform.LocalFramelessWindow
import io.github.nihildigit.pikpak.FileStat
import kotlin.math.abs

private val COMPACT_WIDTH = 600.dp
private const val SUBTITLE_LABEL_MAX_LENGTH = 16
private val PLAIN_EPISODE = Regex("""[0-9]+(\.[0-9]+)?""")

/**
 * 播放器的完整控件层，叠在视频画面之上（Android 与桌面共用）：
 * - 完整移植 Bilby 的中央 5 槽位控件簇、四态可自定义顶/底栏渲染（含 `showBackground` 与自动去重）、
 *   贴边长按倍速拨盘与跨轴锁定、顶部两角横划取消区、对侧贴边音量/亮度竖条与双击侧边胶囊；
 * - 保留 Piko 原生特性：桌面无边框窗口控制、画质切换、音轨与字幕、画面旋转、区域裁剪编辑与 `?` 快捷键浮层。
 */
@Composable
fun MobilePlayerControls(
    title: String,
    isLocalPlayback: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMillis: Long = 0L,
    durationMillis: Long,
    bufferedPositionMillis: Long = 0L,
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
    hideEpisodeThumbnails: Boolean = false,
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
    subtitleStyle: SubtitleStylePrefs = SubtitleStylePrefs.DEFAULT,
    onSubtitleStylePreview: ((SubtitleStylePrefs) -> Unit)? = null,
    onSubtitleStyleChange: ((SubtitleStylePrefs) -> Unit)? = null,
    seekStepSeconds: Int = PlayerGestureDefaults.SEEK_STEP_SECONDS,
    onSeekStepChange: ((Int) -> Unit)? = null,
    longPressSpeed: Float = PlayerGestureDefaults.BOOST_SPEED,
    onLongPressSpeedChange: ((Float) -> Unit)? = null,
    playbackStats: (() -> List<PlaybackStatsSection>)? = null,
    loadingSpeedText: String? = null,
    registerKeyFallback: (((KeyEvent) -> Boolean)?) -> Unit = {},
    positionMillisProvider: (() -> Long)? = null,
    bufferedPositionMillisProvider: (() -> Long)? = null,
    windowActions: PlayerWindowActions? = null,
    onSwitchToListen: (() -> Unit)? = null,
    autoResolvedQuality: String? = null,
    scrubFrameCache: ScrubFrameCache? = null,
    originalSubtitleHints: List<OriginalSubtitleTrackInfo> = emptyList(),
    onPausePlayback: (() -> Unit)? = null,
    onResumePlayback: (() -> Unit)? = null,
) {
    val windowInfo = LocalWindowInfo.current
    val windowSize = windowInfo.containerSize
    val windowFocused = windowInfo.isWindowFocused
    val compactWidth = with(LocalDensity.current) { windowSize.width.toDp() } < COMPACT_WIDTH
    val isLandscape = windowSize.width > windowSize.height
    val layoutPrefs = LocalPlayerLayoutPrefs.current
    val activeLayout = layoutPrefs.videoLayout(isLandscape = isLandscape)
    val effectiveSeekStepSeconds = if (layoutPrefs.effectiveDoubleTapSeekSeconds != DEFAULT_DOUBLE_TAP_SEEK_SECONDS) {
        layoutPrefs.effectiveDoubleTapSeekSeconds
    } else {
        seekStepSeconds
    }

    val focusRequester = remember { FocusRequester() }
    val pointerSource = LocalPointerSource.current
    val framelessWindow = LocalFramelessWindow.current
    val haptic = LocalHapticFeedback.current
    var subtreeHasFocus by remember { mutableStateOf(false) }
    var rootSelfFocused by remember { mutableStateOf(false) }

    val latestFallbackPositionMillis by rememberUpdatedState(positionMillis)
    val latestFallbackBufferedMillis by rememberUpdatedState(bufferedPositionMillis)
    val readPositionMillis: () -> Long = positionMillisProvider ?: remember { { latestFallbackPositionMillis } }
    val readBufferedPositionMillis: () -> Long = bufferedPositionMillisProvider ?: remember { { latestFallbackBufferedMillis } }

    val controlsHover = remember { MutableInteractionSource() }
    val isHoveringControls by controlsHover.collectIsHoveredAsState()

    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestOnPausePlayback by rememberUpdatedState(onPausePlayback)
    val latestOnResumePlayback by rememberUpdatedState(onResumePlayback)
    val latestOnRapidScrubPlayPause by rememberUpdatedState(onPlayPause)
    val latestOnRapidScrubSeek by rememberUpdatedState(onSeek)
    val speedController = rememberPlaybackSpeedController(
        mediaKey = currentFileId,
        playbackSpeed = playbackSpeed,
        defaultHoldSpeed = longPressSpeed,
        onSpeedChange = onSpeedChange,
        readPositionMillis = readPositionMillis,
        readDurationMillis = { durationMillis },
        onCommitRapidScrub = { target -> latestOnRapidScrubSeek(target) },
        allowRapidScrub = { scrubFrameCache != null },
        readIsPlaying = { latestIsPlaying },
        onPauseForRapidScrub = {
            latestOnPausePlayback?.invoke() ?: run {
                if (latestIsPlaying) latestOnRapidScrubPlayPause()
            }
        },
        onResumeAfterRapidScrub = {
            latestOnResumePlayback?.invoke() ?: run {
                if (!latestIsPlaying) latestOnRapidScrubPlayPause()
            }
        },
    )
    val ui = rememberPlayerControlsUiState(
        currentFileId = currentFileId,
        isPlaying = isPlaying,
        isHoveringControls = isHoveringControls,
        resumedFromMillis = resumedFromMillis,
        playbackSpeed = playbackSpeed,
        rotationDegrees = rotationDegrees,
        longPressSpeed = longPressSpeed,
        errorMessage = errorMessage,
        onSpeedChange = { target -> speedController.applySpeed(target) },
        isSpeedDialActive = speedController.active || speedController.lockedHint != null,
    )
    val applyExplicitSpeed: (Float) -> Unit = remember(speedController, ui) {
        { target ->
            ui.clearDialSpeedSuppression()
            speedController.applySpeed(target)
        }
    }
    LaunchedEffect(windowFocused, speedController) {
        if (!windowFocused && speedController.active) {
            speedController.cancel()
        }
    }
    SideEffect {
        speedController.onDialSpeedWrite = ui::markDialSpeedWrite
        ui.onStartHoldBoost = {
            speedController.open(temporary = true, showDial = false)
        }
        ui.onEndHoldBoost = {
            if (speedController.active) speedController.finish(cancel = false)
        }
        ui.currentPositionProvider = readPositionMillis
        if (positionMillisProvider == null) {
            ui.currentPositionMillis = positionMillis
        }
        ui.currentDurationMillis = durationMillis
        ui.currentPlaybackSpeed = playbackSpeed
        ui.currentRotationDegrees = rotationDegrees
        ui.currentIsPlaying = isPlaying
        ui.currentIsFullscreen = isFullscreen
        ui.currentSeekStepMillis = effectiveSeekStepSeconds * 1_000L
        ui.currentLongPressSpeed = longPressSpeed
        ui.currentVolumeControl = volume
        ui.currentAudioTracks = audioTracks
        ui.currentSelectedAudioId = selectedAudioTrackId
        ui.currentSubtitleTracks = subtitleTracks
        ui.currentSelectedSubtitleId = selectedSubtitleTrackId
        ui.currentHasPrevious = hasPrevious
        ui.currentHasNext = hasNext
        ui.currentHasStats = playbackStats != null
        ui.currentIsAlwaysOnTop = windowActions?.isAlwaysOnTop ?: framelessWindow?.isAlwaysOnTop
    }

    val latestOnPlayPause by rememberUpdatedState(onPlayPause)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestOnSpeedChange by rememberUpdatedState(applyExplicitSpeed)
    val latestOnAspectRatioChange by rememberUpdatedState(onAspectRatioChange)
    val latestOnToggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    val latestOnRotationChange by rememberUpdatedState(onRotationChange)
    val latestOnSelectAudioTrack by rememberUpdatedState(onSelectAudioTrack)
    val latestOnSelectSubtitleTrack by rememberUpdatedState(onSelectSubtitleTrack)
    val latestOnPrevious by rememberUpdatedState(onPrevious)
    val latestOnNext by rememberUpdatedState(onNext)
    val latestFramelessWindow by rememberUpdatedState(framelessWindow)
    val latestWindowActions by rememberUpdatedState(windowActions)
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
                onToggleAlwaysOnTop = latestWindowActions?.onToggleAlwaysOnTop
                    ?: latestFramelessWindow?.let { fw -> { fw.setAlwaysOnTop(!fw.isAlwaysOnTop) } },
                letFocusedControlHandleSpace = letFocusedControlHandleSpace,
            )
        }
    }
    DisposableEffect(dispatchPlayerKey) {
        latestRegisterKeyFallback { event -> dispatchPlayerKey(event, false) }
        onDispose { latestRegisterKeyFallback(null) }
    }

    val holdControls = ui.shouldHoldControls(isPlaying, errorMessage, isHoveringControls) || speedController.active
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

    val activeLevel = ui.computeActiveLevel()
    val activeSeekGesture = ui.activeGesture as? PlayerGesture.Seek
    val activeEpisodeSwipe = ui.activeGesture as? PlayerGesture.EpisodeSwipe
    val centerIndicator = ui.flashIndicator
    val seekPreviewMillis = activeSeekGesture?.targetMillis(durationMillis) ?: ui.settledSeekMillis
    var barScrubPositionMillis by remember { mutableStateOf<Long?>(null) }

    val effectiveOpenSheet = when {
        ui.isSpeedPopupOpen -> PlayerSheet.Speed
        ui.isQualityPopupOpen -> PlayerSheet.Quality
        else -> ui.openSheet
    }
    val modalOverlayOpen = effectiveOpenSheet != null || ui.isShortcutsOverlayOpen || ui.isEditingCropRegion
    val chromeVisible = ui.controlsVisible && !ui.isLocked && !modalOverlayOpen && !speedController.active
    var stalledLoading by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        stalledLoading = false
        if (isLoading) {
            kotlinx.coroutines.delay(dev.piko.ui.components.LOADING_APPEAR_DELAY_MS)
            stalledLoading = true
        }
    }

    // 遵循 Bilby 规则：触屏且开启中央控件时由中央 5 槽位承载播放/切集/步进，
    // 键鼠或关闭触控中央控件时整组播放簇自动迁至顶/底栏 PlayPause 槽位，严格单入口不重复。
    val centerControlsActive = layoutPrefs.isCenterControlsActive(pointerSource.isTouchLike)
    val transportInCenter = centerControlsActive && activeLayout.center.showCenterPlay
    val transportInAnyBar = !transportInCenter && (
        activeLayout.hasInBottomBar(PlayerControlId.PlayPause) ||
            activeLayout.hasInTopBar(PlayerControlId.PlayPause)
        )

    val bottomBarVisible = (chromeVisible || seekPreviewMillis != null) && !modalOverlayOpen && !speedController.active
    val showCenterCluster = transportInCenter && chromeVisible && !ui.isBoosting && errorMessage == null
    val centerUnblocked = errorMessage == null && !modalOverlayOpen &&
        activeSeekGesture == null && activeEpisodeSwipe == null && !speedController.active
    val centerInteractive = (showCenterCluster || (isLoading && (chromeVisible || stalledLoading))) && centerUnblocked
    val centerVisible = centerInteractive || (centerIndicator != null && centerUnblocked)

    BackHandler(enabled = isFullscreen && !ui.isEditingCropRegion && !ui.isSpeedPopupOpen && !ui.isQualityPopupOpen && !ui.isShortcutsOverlayOpen && !speedController.active && ui.openSheet == null, onBack = onToggleFullscreen)
    BackHandler(enabled = ui.isShortcutsOverlayOpen) { ui.isShortcutsOverlayOpen = false }
    BackHandler(enabled = ui.isSpeedPopupOpen && !ui.isEditingCropRegion) { ui.isSpeedPopupOpen = false }
    BackHandler(enabled = ui.isQualityPopupOpen && !ui.isEditingCropRegion) { ui.isQualityPopupOpen = false }
    BackHandler(enabled = ui.isEditingCropRegion) { ui.cancelCropEdit(onAspectRatioChange) }

    LaunchedEffect(
        subtreeHasFocus,
        windowFocused,
        chromeVisible,
        ui.openSheet,
        ui.isSpeedPopupOpen,
        ui.isQualityPopupOpen,
        ui.isShortcutsOverlayOpen,
        ui.isEditingCropRegion,
        speedController.active,
        pointerSource.isTouchLike,
    ) {
        if (!pointerSource.isTouchLike && windowFocused && !subtreeHasFocus &&
            ui.openSheet == null && !ui.isSpeedPopupOpen && !ui.isQualityPopupOpen &&
            !ui.isShortcutsOverlayOpen && !speedController.active
        ) {
            withFrameNanos {}
            if (!subtreeHasFocus && !pointerSource.isTouchLike) {
                runCatching { focusRequester.requestFocus() }
            }
        }
    }

    val hasPlaylist = playlist.size > 1
    val hasTracksEntry = subtitleTracks.isNotEmpty() || audioTracks.size > 1 ||
        originalSubtitleHints.isNotEmpty() ||
        onPickLocalSubtitle != null || onPickDriveSubtitle != null
    val hasSettingsEntry = hasTracksEntry || fileActions.isNotEmpty() || playbackSpeed != null ||
        qualityOptions.isNotEmpty() || aspectRatio != null || onSeekStepChange != null ||
        onSubtitleStyleChange != null
    val episodeLabel = playlist.find { it.fileId == currentFileId }
        ?.takeIf { hasPlaylist && it.label.length <= SUBTITLE_LABEL_MAX_LENGTH }
        ?.label
        ?.let { if (it.matches(PLAIN_EPISODE)) tr("第 $it 集", "Episode $it", "第 $it 話", "에피소드 $it", "第 $it 集") else it }

    val hideCursor = idleCursor != null && !pointerSource.isTouchLike &&
        ui.isMouseIdle && !ui.controlsVisible && isPlaying && !speedController.active

    // 保留上次电平条停靠边，确保滑出退场与入场同侧一致
    var lastLevelDockEnd by remember { mutableStateOf(true) }
    if (activeLevel != null) lastLevelDockEnd = activeLevel.dockEnd
    val levelDockEnd = activeLevel?.dockEnd ?: lastLevelDockEnd

    // 保留上次双击方向，确保退场滑出与入场同侧一致
    var lastDoubleTapForward by remember { mutableStateOf(true) }
    if (ui.doubleTapVisible) lastDoubleTapForward = ui.doubleTapForward
    val doubleTapForwardSide = if (ui.doubleTapVisible) ui.doubleTapForward else lastDoubleTapForward

    CompositionLocalProvider(
        LocalAutoResolvedQuality provides autoResolvedQuality,
        LocalScrubFrameCache provides scrubFrameCache,
    ) {
    PlayerTheme {
        val motion = MaterialTheme.motionScheme
        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .onPreviewKeyEvent { event ->
                    val dialCapturesKeys = speedController.active &&
                        layoutPrefs.gestures.longPressSpeedDialEnabled &&
                        speedController.selection?.showDial != false
                    if (event.key == Key.Escape || dialCapturesKeys) false
                    else dispatchPlayerKey(event, subtreeHasFocus && !rootSelfFocused && isKeyboardModality)
                }
                .onKeyEvent { event ->
                    val dialCapturesKeys = speedController.active &&
                        layoutPrefs.gestures.longPressSpeedDialEnabled &&
                        speedController.selection?.showDial != false
                    if (event.key == Key.Escape && !dialCapturesKeys) dispatchPlayerKey(event, false) else false
                }
                .onFocusChanged { state ->
                    subtreeHasFocus = state.hasFocus
                    rootSelfFocused = state.isFocused
                }
                .focusRequester(focusRequester)
                .focusProperties { canFocus = !pointerSource.isTouchLike }
                .focusTarget()
                .pointerInput(speedController) {
                    var lastMousePosition: Offset? = null
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (speedController.active) continue
                            val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse }
                            val hovering = event.type == PointerEventType.Move && mouse != null && !mouse.pressed &&
                                lastMousePosition.let { it != null && it != mouse.position }
                            if (mouse != null) lastMousePosition = if (event.type == PointerEventType.Exit) null else mouse.position
                            if (hovering) {
                                ui.recordMouseMove()
                            }
                            val left = event.type == PointerEventType.Exit && mouse != null &&
                                !Rect(Offset.Zero, size.toSize()).contains(mouse.position)
                            if (left && !currentHoldControls) ui.controlsVisible = false
                        }
                    }
                }
                // 桌面光标显隐必须就地替换 PointerIcon 参数而非增删修饰符节点：
                // HoverIconModifierNode 实现 PointerInputModifierNode，若长按倍速激活瞬间将其从父链卸载，
                // HitPathTracker 会连带移除整个子孙命中子树并向 PlayerGestureLayer 发送合成取消事件。
                .then(
                    if (idleCursor != null) {
                        Modifier.pointerHoverIcon(if (hideCursor) idleCursor else PointerIcon.Default)
                    } else {
                        Modifier
                    },
                ),
        ) {
            PlayerGestureLayer(
                isLocked = ui.isLocked || modalOverlayOpen,
                durationMillis = durationMillis,
                positionProvider = readPositionMillis,
                brightness = brightness,
                volume = volume,
                speedController = speedController,
                speedSupported = playbackSpeed != null,
                gestureConfig = layoutPrefs.gestures,
                canPrevious = hasPrevious,
                canNext = hasNext,
                onGestureChange = { ui.activeGesture = it },
                onToggleControls = {
                    if (pointerSource.isTouchLike) {
                        ui.controlsVisible = !ui.controlsVisible
                    } else {
                        if (!rootSelfFocused) runCatching { focusRequester.requestFocus() }
                        if (!ui.controlsVisible) {
                            ui.flash(
                                CenterIndicator(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    if (isPlaying) tr("暂停", "Pause", "一時停止", "일시 중지", "暫停") else tr("播放", "Play", "再生", "재생", "播放"),
                                    if (isPlaying) tr("已暂停", "Paused", "一時停止中", "일시 중지됨", "已暫停") else tr("继续播放", "Continue playback", "再生を続ける", "계속 재생", "繼續播放"),
                                ),
                            )
                        }
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
                onPrevious = {
                    ui.interacted()
                    onPrevious()
                },
                onNext = {
                    ui.interacted()
                    onNext()
                },
                onSpeedStart = {
                    ui.controlsVisible = false
                    ui.activeGesture = null
                    ui.interacted()
                },
                seekTapActive = { ui.doubleTapVisible && ui.tapSeekRound && pointerSource.isTouchLike },
                modifier = Modifier.pointerInput(ui.isLocked, modalOverlayOpen, speedController) {
                    if (ui.isLocked || modalOverlayOpen) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll || speedController.active) continue
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
                        visible = ui.showResumeTip && !ui.isLocked && !modalOverlayOpen && !isSeeking,
                        resumedPositionMillis = resumedFromMillis,
                        onRestart = {
                            onRestartFromBeginning()
                            ui.showResumeTip = false
                        },
                        onDismiss = { ui.showResumeTip = false },
                    )
                }
                FullscreenPromptButton(
                    visible = layoutPrefs.showPortraitFullscreenPrompt && !isFullscreen && !ui.isLocked &&
                        !isLandscape && isLandscapeVideo == true &&
                        errorMessage == null && !modalOverlayOpen && !isSeeking,
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

            val volumeLevel = volume?.let { control ->
                ui.volumeRevision
                control.current()
            }
            val barEnv = PlayerBarEnvironment(
                layout = activeLayout,
                transportInCenter = transportInCenter,
                transportInAnyBar = transportInAnyBar,
                seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                title = title,
                episodeLabel = episodeLabel,
                isLocalPlayback = isLocalPlayback,
                isLandscape = isLandscape,
                isFullscreen = isFullscreen,
                isLocked = ui.isLocked,
                isPlaying = isPlaying,
                compactWidth = compactWidth,
                positionMillisProvider = readPositionMillis,
                scrubPositionMillis = barScrubPositionMillis,
                previewPositionMillis = seekPreviewMillis,
                durationMillis = durationMillis,
                playbackSpeed = playbackSpeed,
                showEpisodes = hasPlaylist,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                isSpeedPopupOpen = ui.isSpeedPopupOpen,
                qualityOptions = qualityOptions,
                currentQuality = currentQuality,
                isQualityPopupOpen = ui.isQualityPopupOpen,
                aspectRatio = aspectRatio,
                volume = volumeLevel,
                windowActions = windowActions,
                onBackClick = onBack,
                onPlayPause = {
                    ui.interacted()
                    onPlayPause()
                },
                onPrevious = {
                    ui.interacted()
                    onPrevious()
                },
                onNext = {
                    ui.interacted()
                    onNext()
                },
                onSeekDeltaMillis = { deltaMillis ->
                    ui.interacted()
                    ui.stepSeek(
                        forward = deltaMillis >= 0L,
                        onSeek = onSeek,
                        stepMillis = abs(deltaMillis),
                    )
                },
                onSpeedPopupOpenChange = {
                    ui.interacted()
                    if (it) {
                        ui.isQualityPopupOpen = false
                        ui.openSheet = null
                    }
                    ui.isSpeedPopupOpen = it
                },
                onSpeedChange = {
                    ui.interacted()
                    applyExplicitSpeed(it)
                },
                onQualityPopupOpenChange = {
                    ui.interacted()
                    if (it) {
                        ui.isSpeedPopupOpen = false
                        ui.openSheet = null
                    }
                    ui.isQualityPopupOpen = it
                },
                onQualityChange = {
                    ui.interacted()
                    onQualityChange(it)
                },
                onEpisodesClick = {
                    ui.interacted()
                    ui.isSpeedPopupOpen = false
                    ui.isQualityPopupOpen = false
                    ui.openSheet = PlayerSheet.Episodes
                },
                onTracksClick = {
                    ui.interacted()
                    ui.isSpeedPopupOpen = false
                    ui.isQualityPopupOpen = false
                    ui.openSheet = PlayerSheet.Tracks
                }.takeIf { hasTracksEntry },
                onStatsClick = {
                    ui.interacted()
                    ui.isSpeedPopupOpen = false
                    ui.isQualityPopupOpen = false
                    ui.openSheet = PlayerSheet.Stats
                }.takeIf { playbackStats != null },
                onSettingsClick = {
                    ui.interacted()
                    ui.isSpeedPopupOpen = false
                    ui.isQualityPopupOpen = false
                    ui.openSheet = PlayerSheet.Settings
                }.takeIf { hasSettingsEntry },
                onSwitchToListen = onSwitchToListen?.let { switch ->
                    {
                        ui.interacted()
                        switch()
                    }
                },
                onRotate = rotationDegrees?.let { degrees ->
                    {
                        ui.interacted()
                        onRotationChange((degrees + 90) % 360)
                    }
                },
                onCycleAspectRatio = aspectRatio?.let { current ->
                    {
                        ui.interacted()
                        val next = when (current) {
                            PlayerAspectRatio.Fit -> PlayerAspectRatio.Crop
                            PlayerAspectRatio.Crop -> PlayerAspectRatio.Stretch
                            PlayerAspectRatio.Stretch, PlayerAspectRatio.RegionCrop -> PlayerAspectRatio.Fit
                        }
                        onAspectRatioChange(next)
                    }
                },
                onToggleLock = {
                    haptic.performHapticFeedback(
                        if (ui.isLocked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                    )
                    ui.isLocked = !ui.isLocked
                    ui.interacted()
                },
                onToggleFullscreen = {
                    ui.interacted()
                    onToggleFullscreen()
                },
                onVolumeChange = {
                    ui.interacted()
                    ui.setVolume(it)
                },
                onToggleMute = {
                    ui.interacted()
                    ui.toggleMute(showHud = false)
                },
                onVolumeDraggingChange = { ui.isDraggingVolume = it },
                dragAreaEnabled = chromeVisible,
            )

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(Modifier.fillMaxSize()) {
                    CustomizablePlayerTopBar(
                        env = barEnv,
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
                    CustomizablePlayerBottomBar(
                        env = barEnv,
                        thumbOnHoverOnly = seekThumbOnHoverOnly,
                        bufferedPositionMillisProvider = readBufferedPositionMillis,
                        onSeek = {
                            ui.interacted()
                            onSeek(it)
                        },
                        onScrubPositionChange = { barScrubPositionMillis = it },
                        onScrubbingChange = { ui.isScrubbing = it },
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

            // Bilby 三分式手势 HUD：横划进退（含取消态）居中、音量/亮度贴对侧边缘、双击快进/快退贴同侧边缘
            SeekHudOverlay(
                seek = if (modalOverlayOpen) null else activeSeekGesture,
                durationMillis = durationMillis,
                modifier = Modifier.align(Alignment.Center),
            )

            EpisodeSwipeHud(
                visible = !modalOverlayOpen && activeEpisodeSwipe != null,
                direction = activeEpisodeSwipe?.direction ?: 0,
                armed = activeEpisodeSwipe?.armed == true,
                available = if ((activeEpisodeSwipe?.direction ?: 1) > 0) hasNext else hasPrevious,
                modifier = Modifier.align(Alignment.Center),
            )

            PlayerLevelEdgeBar(
                adjust = if (modalOverlayOpen) null else activeLevel,
                dockEnd = levelDockEnd,
                large = isLandscape || !compactWidth,
                modifier = Modifier
                    .align(if (levelDockEnd) Alignment.CenterEnd else Alignment.CenterStart)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            if (levelDockEnd) WindowInsetsSides.End else WindowInsetsSides.Start,
                        ),
                    )
                    .padding(horizontal = 16.dp),
            )

            DoubleTapSeekHint(
                visible = !modalOverlayOpen && ui.doubleTapVisible,
                forward = doubleTapForwardSide,
                seconds = ui.doubleTapSeconds,
                modifier = Modifier
                    .align(if (doubleTapForwardSide) Alignment.CenterEnd else Alignment.CenterStart)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            if (doubleTapForwardSide) WindowInsetsSides.End else WindowInsetsSides.Start,
                        ),
                    )
                    .padding(horizontal = 16.dp),
            )

            PlayerCenterCluster(
                config = activeLayout.center,
                centerVisible = centerVisible,
                interactive = centerInteractive,
                showSideButtons = showCenterCluster && !isLoading,
                isPlaying = isPlaying,
                isLoading = isLoading,
                indicator = centerIndicator,
                large = isLandscape || !compactWidth,
                canPrevious = hasPrevious,
                canNext = hasNext,
                allowSeek = durationMillis > 0L,
                loadingSpeedText = loadingSpeedText,
                onPlayPause = {
                    if (chromeVisible) {
                        ui.interacted()
                        onPlayPause()
                    } else {
                        ui.controlsVisible = true
                    }
                },
                onPrevious = {
                    ui.interacted()
                    onPrevious()
                },
                onNext = {
                    ui.interacted()
                    onNext()
                },
                onSeekDeltaMillis = { deltaMillis ->
                    ui.interacted()
                    ui.stepSeek(
                        forward = deltaMillis >= 0L,
                        onSeek = onSeek,
                        stepMillis = abs(deltaMillis),
                    )
                },
                // 控件收起或仅展示被动中心读数时必须禁用 hoverable（HoverableNode 不与同级共享指针输入，
                // 常驻开启会在画面正中形成不可见盲区，吞掉长按倍速、单击唤出与双击暂停）。
                modifier = Modifier.align(Alignment.Center).hoverable(controlsHover, enabled = centerInteractive),
            )

            // 锁屏键：若用户已在顶/底栏放置 Lock 则未锁定时不重复显示悬浮锁（严格 DRY）；已锁定时常驻右侧解锁入口
            AnimatedVisibility(
                visible = ui.controlsVisible && errorMessage == null && !modalOverlayOpen &&
                    !speedController.active &&
                    (ui.isLocked || (pointerSource.isTouchLike && !activeLayout.hasControl(PlayerControlId.Lock))) &&
                    !(activeLevel != null && levelDockEnd),
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End))
                    .padding(end = 16.dp)
                    .hoverable(controlsHover),
            ) {
                LockToggle(
                    isLocked = ui.isLocked,
                    onToggle = {
                        haptic.performHapticFeedback(
                            if (ui.isLocked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        ui.isLocked = !ui.isLocked
                        ui.interacted()
                    },
                )
            }

            if (!ui.isEditingCropRegion && playbackSpeed != null) {
                SpeedDialOverlay(
                    controller = speedController,
                    isFullscreen = isFullscreen,
                    showDial = layoutPrefs.gestures.longPressSpeedDialEnabled,
                    durationMillis = durationMillis,
                    modifier = Modifier.fillMaxSize(),
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
                openSheet = effectiveOpenSheet,
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
                seekStepSeconds = effectiveSeekStepSeconds,
                longPressSpeed = longPressSpeed,
                audioTracks = audioTracks,
                selectedAudioTrackId = selectedAudioTrackId,
                subtitleTracks = subtitleTracks,
                selectedSubtitleTrackId = selectedSubtitleTrackId,
                onDismiss = {
                    ui.isSpeedPopupOpen = false
                    ui.isQualityPopupOpen = false
                    ui.openSheet = null
                    ui.interacted()
                },
                onOpenSheet = {
                    ui.isSpeedPopupOpen = false
                    ui.isQualityPopupOpen = false
                    ui.openSheet = it
                },
                onSelectEntry = onSelectEntry,
                onSpeedChange = applyExplicitSpeed,
                onQualityChange = onQualityChange,
                onAspectRatioChange = onAspectRatioChange,
                onBeginCropEdit = { ui.beginCropEdit(aspectRatio, onAspectRatioChange) },
                onSeekStepChange = onSeekStepChange,
                onLongPressSpeedChange = onLongPressSpeedChange,
                onSelectAudioTrack = onSelectAudioTrack,
                onSelectSubtitleTrack = onSelectSubtitleTrack,
                onPickLocalSubtitle = onPickLocalSubtitle,
                onPickDriveSubtitle = onPickDriveSubtitle,
                subtitleStyle = subtitleStyle,
                onSubtitleStylePreview = onSubtitleStylePreview,
                onSubtitleStyleChange = onSubtitleStyleChange,
                playbackStats = playbackStats,
                hasTracks = hasTracksEntry,
                hasSettings = hasSettingsEntry,
                originalSubtitleHints = originalSubtitleHints,
            )

            PlayerShortcutsOverlay(
                visible = ui.isShortcutsOverlayOpen,
                compactWidth = compactWidth,
                onDismiss = {
                    ui.isShortcutsOverlayOpen = false
                    ui.interacted()
                },
            )
        }
    }
    }
}
