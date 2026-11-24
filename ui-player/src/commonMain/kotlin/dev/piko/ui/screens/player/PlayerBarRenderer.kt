package dev.piko.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.CenterClusterConfig
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlayerBarSlot
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerLayoutMode
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.shared.media.player.PlayerStatusBarConfig
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalFramelessWindow
import dev.piko.ui.platform.PlayerNetworkType
import dev.piko.ui.platform.PlayerSystemStatusInfo
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_quality
import dev.piko.ui.resources.player_seek_backward_seconds
import dev.piko.ui.resources.player_seek_forward_seconds
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular

internal val PreviewSampleSystemStatus = PlayerSystemStatusInfo(
    batteryPercent = 86,
    isCharging = false,
    networkType = PlayerNetworkType.Wifi,
)

/**
 * 顶栏与底栏共享的业务状态与动作集合：由 `MobilePlayerControls` 组装一次，
 * 供 `CustomizablePlayerTopBar` 与 `CustomizablePlayerBottomBar` 按用户自定义布局动态渲染任意控件；
 * 自定义页通过 [PlayerBarEnvironment.preview] 构造样本环境，与真实栏 100% 共用同一排版实现。
 */
@Stable
internal class PlayerBarEnvironment(
    val layout: PlayerFormFactorLayout,
    val transportInCenter: Boolean,
    val transportInAnyBar: Boolean,
    val seekButtonSeconds: Int,
    val title: String,
    val episodeLabel: String?,
    val isLocalPlayback: Boolean,
    val isLandscape: Boolean,
    val isFullscreen: Boolean,
    val isLocked: Boolean,
    val isPlaying: Boolean,
    val compactWidth: Boolean,
    val positionMillisProvider: () -> Long,
    val scrubPositionMillis: Long?,
    val previewPositionMillis: Long?,
    val durationMillis: Long,
    val playbackSpeed: Float?,
    val showEpisodes: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val isSpeedPopupOpen: Boolean,
    val qualityOptions: List<String>,
    val currentQuality: String?,
    val isQualityPopupOpen: Boolean,
    val aspectRatio: PlayerAspectRatio?,
    val volume: Float?,
    val windowActions: PlayerWindowActions?,
    val onBackClick: () -> Unit,
    val onPlayPause: () -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSeekDeltaMillis: (Long) -> Unit,
    val onSpeedPopupOpenChange: (Boolean) -> Unit,
    val onSpeedChange: (Float) -> Unit,
    val onQualityPopupOpenChange: (Boolean) -> Unit,
    val onQualityChange: (String) -> Unit,
    val onEpisodesClick: () -> Unit,
    val onTracksClick: (() -> Unit)?,
    val onStatsClick: (() -> Unit)?,
    val onSettingsClick: (() -> Unit)?,
    val onSwitchToListen: (() -> Unit)?,
    val onRotate: (() -> Unit)?,
    val onCycleAspectRatio: (() -> Unit)?,
    val onToggleLock: () -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onVolumeChange: (Float) -> Unit,
    val onToggleMute: () -> Unit,
    val onVolumeDraggingChange: (Boolean) -> Unit,
    val dragAreaEnabled: Boolean = true,
    val statusBarConfig: PlayerStatusBarConfig? = null,
    val previewSystemStatus: PlayerSystemStatusInfo? = null,
    val previewClockText: String? = null,
    val previewCompactScale: Float = 1f,
    val isPreview: Boolean = false,
) {
    companion object {
        private val MinimalTopLayout = PlayerFormFactorLayout(
            center = CenterClusterConfig(),
            topBar = listOf(
                PlayerPanelItem.Control(PlayerControlId.Back),
                PlayerPanelItem.Control(PlayerControlId.Title),
                PlayerPanelItem.Spacer,
            ),
            bottomBar = emptyList(),
        )

        fun minimalTopBar(
            title: String,
            episodeLabel: String?,
            isLocalPlayback: Boolean,
            onBackClick: () -> Unit,
            windowActions: PlayerWindowActions? = null,
            isFullscreen: Boolean = false,
            dragAreaEnabled: Boolean = true,
        ): PlayerBarEnvironment = PlayerBarEnvironment(
            layout = MinimalTopLayout,
            transportInCenter = true,
            transportInAnyBar = false,
            seekButtonSeconds = 10,
            title = title,
            episodeLabel = episodeLabel,
            isLocalPlayback = isLocalPlayback,
            isLandscape = true,
            isFullscreen = isFullscreen,
            isLocked = false,
            isPlaying = false,
            compactWidth = false,
            positionMillisProvider = { 0L },
            scrubPositionMillis = null,
            previewPositionMillis = null,
            durationMillis = 0L,
            playbackSpeed = null,
            showEpisodes = false,
            hasPrevious = false,
            hasNext = false,
            isSpeedPopupOpen = false,
            qualityOptions = emptyList(),
            currentQuality = null,
            isQualityPopupOpen = false,
            aspectRatio = null,
            volume = null,
            windowActions = windowActions,
            onBackClick = onBackClick,
            onPlayPause = {},
            onPrevious = {},
            onNext = {},
            onSeekDeltaMillis = {},
            onSpeedPopupOpenChange = {},
            onSpeedChange = {},
            onQualityPopupOpenChange = {},
            onQualityChange = {},
            onEpisodesClick = {},
            onTracksClick = null,
            onStatsClick = null,
            onSettingsClick = null,
            onSwitchToListen = null,
            onRotate = null,
            onCycleAspectRatio = null,
            onToggleLock = {},
            onToggleFullscreen = {},
            onVolumeChange = {},
            onToggleMute = {},
            onVolumeDraggingChange = {},
            dragAreaEnabled = dragAreaEnabled,
        )

        fun preview(
            mode: PlayerLayoutMode,
            layout: PlayerFormFactorLayout,
            statusBar: PlayerStatusBarConfig,
            seekButtonSeconds: Int,
            transportInCenter: Boolean,
            sampleTitle: String,
            previewCompactScale: Float = 0.88f,
        ): PlayerBarEnvironment {
            val transportInAnyBar = mode.isVideo && !transportInCenter &&
                (layout.hasInTopBar(PlayerControlId.PlayPause) || layout.hasInBottomBar(PlayerControlId.PlayPause))
            return PlayerBarEnvironment(
                layout = layout,
                transportInCenter = transportInCenter,
                transportInAnyBar = transportInAnyBar,
                seekButtonSeconds = seekButtonSeconds,
                title = sampleTitle,
                episodeLabel = null,
                isLocalPlayback = false,
                isLandscape = mode.isLandscape,
                isFullscreen = true,
                isLocked = false,
                isPlaying = false,
                compactWidth = !mode.isLandscape,
                positionMillisProvider = { 754_000L },
                scrubPositionMillis = null,
                previewPositionMillis = null,
                durationMillis = 2_700_000L,
                playbackSpeed = 1.0f,
                showEpisodes = true,
                hasPrevious = true,
                hasNext = true,
                isSpeedPopupOpen = false,
                qualityOptions = listOf("1080P", "720P"),
                currentQuality = "1080P",
                isQualityPopupOpen = false,
                aspectRatio = PlayerAspectRatio.Fit,
                volume = 0.72f,
                windowActions = null,
                onBackClick = {},
                onPlayPause = {},
                onPrevious = {},
                onNext = {},
                onSeekDeltaMillis = {},
                onSpeedPopupOpenChange = {},
                onSpeedChange = {},
                onQualityPopupOpenChange = {},
                onQualityChange = {},
                onEpisodesClick = {},
                onTracksClick = {},
                onStatsClick = {},
                onSettingsClick = {},
                onSwitchToListen = {},
                onRotate = {},
                onCycleAspectRatio = {},
                onToggleLock = {},
                onToggleFullscreen = {},
                onVolumeChange = {},
                onToggleMute = {},
                onVolumeDraggingChange = {},
                dragAreaEnabled = false,
                statusBarConfig = statusBar,
                previewSystemStatus = PreviewSampleSystemStatus,
                previewClockText = "16:20",
                previewCompactScale = previewCompactScale,
                isPreview = true,
            )
        }
    }
}

/**
 * 极简只读播放器顶栏入口（供播放器内静态图片浏览等无进度条场景复用同一权威 `CustomizablePlayerTopBar`）。
 */
@Composable
fun CustomizablePlayerTopBar(
    title: String,
    episodeLabel: String?,
    isLocalPlayback: Boolean,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    windowActions: PlayerWindowActions? = null,
    isFullscreen: Boolean = false,
    dragAreaEnabled: Boolean = true,
) {
    val env = remember(title, episodeLabel, isLocalPlayback, onBackClick, windowActions, isFullscreen, dragAreaEnabled) {
        PlayerBarEnvironment.minimalTopBar(
            title = title,
            episodeLabel = episodeLabel,
            isLocalPlayback = isLocalPlayback,
            onBackClick = onBackClick,
            windowActions = windowActions,
            isFullscreen = isFullscreen,
            dragAreaEnabled = dragAreaEnabled,
        )
    }
    CustomizablePlayerTopBar(env = env, modifier = modifier)
}

/**
 * 基于四态自定义配置的播放器顶栏（视频横屏 / 视频竖屏）：
 * 按 `env.layout.topBar` 顺序渲染所有控件与 `Spacer`，桌面独立窗口的最小化/最大化/关闭固定贴在最右端。
 */
@Composable
internal fun CustomizablePlayerTopBar(
    env: PlayerBarEnvironment,
    modifier: Modifier = Modifier,
    editScope: PlayerBarEditScope? = null,
) {
    val framelessWindow = if (env.isPreview) null else LocalFramelessWindow.current
    val windowActions = env.windowActions
    val showWindowButtons = windowActions?.showWindowButtons ?: (framelessWindow != null)
    val effectivePin = windowActions?.onToggleAlwaysOnTop
        ?: framelessWindow?.let { fw -> { fw.setAlwaysOnTop(!fw.isAlwaysOnTop) } }
    val effectiveOnTop = windowActions?.isAlwaysOnTop ?: framelessWindow?.isAlwaysOnTop ?: false
    val effectiveClose = windowActions?.onClose ?: framelessWindow?.let { fw -> fw::close }
    // 若用户已在顶栏或底栏配置了 PinOnTop，窗口按钮组不再重复绘制置顶按钮（严格 DRY）
    val pinAlreadyInLayout = env.layout.hasControl(PlayerControlId.PinOnTop)
    val showPinInWindowCluster = !pinAlreadyInLayout && effectivePin != null
    val hasWindowCluster = !env.isPreview && (
        showPinInWindowCluster || (
            showWindowButtons && (
                windowActions?.onMinimize != null ||
                    windowActions?.onToggleMaximize != null ||
                    effectiveClose != null
                )
            )
        )
    val scale = env.previewCompactScale

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(TopScrim))
            .then(
                if (editScope == null) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                    )
                } else {
                    Modifier
                },
            )
            .padding(
                start = 12.dp * scale,
                end = 12.dp * scale,
                top = 8.dp * scale + (windowActions?.topChromePadding ?: 0.dp),
                bottom = 12.dp * scale,
            )
            .then(editScope?.barContainerModifier(PlayerBarSlot.Top) ?: Modifier),
        horizontalArrangement = Arrangement.spacedBy(6.dp * scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RenderVideoPanelItems(
            items = env.layout.topBar,
            env = env,
            inTopBar = true,
            editScope = editScope,
        )
        if (hasWindowCluster) {
            MediaWindowControlsCluster(
                windowActions = windowActions,
                effectivePin = if (showPinInWindowCluster) effectivePin else null,
                effectiveOnTop = effectiveOnTop,
                showWindowButtons = showWindowButtons,
                effectiveClose = effectiveClose,
            )
        }
    }
}

/**
 * 基于四态自定义配置的播放器底栏（视频横屏 / 视频竖屏）：
 * 上方为全宽细轨道进度条，下方按 `env.layout.bottomBar` 顺序渲染所有控件与 `Spacer`。
 */
@Composable
internal fun CustomizablePlayerBottomBar(
    env: PlayerBarEnvironment,
    thumbOnHoverOnly: Boolean,
    bufferedPositionMillisProvider: () -> Long,
    onSeek: (Long) -> Unit,
    onScrubPositionChange: (Long?) -> Unit,
    onScrubbingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    editScope: PlayerBarEditScope? = null,
) {
    var scrubPositionMillis by remember { mutableStateOf<Long?>(null) }
    val latestOnScrubbingChange by rememberUpdatedState(onScrubbingChange)
    val latestOnScrubPositionChange by rememberUpdatedState(onScrubPositionChange)
    val handleScrub: (Long?) -> Unit = remember {
        { target ->
            val wasScrubbing = scrubPositionMillis != null
            scrubPositionMillis = target
            latestOnScrubPositionChange(target)
            if (wasScrubbing != (target != null)) latestOnScrubbingChange(target != null)
        }
    }
    val scale = env.previewCompactScale

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(BottomScrim))
            .then(
                if (editScope == null) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                } else {
                    Modifier
                },
            )
            .padding(
                start = (if (editScope != null) (if (env.isLandscape) 12.dp else 10.dp) else (if (env.isLandscape) 16.dp else 12.dp)) * scale,
                end = (if (editScope != null) (if (env.isLandscape) 12.dp else 10.dp) else (if (env.isLandscape) 16.dp else 12.dp)) * scale,
                top = (if (editScope != null) 8.dp else 12.dp) * scale,
                bottom = (if (env.isLandscape) 10.dp else 14.dp) * scale,
            ),
    ) {
        PlayerSeekBar(
            positionMillisProvider = env.positionMillisProvider,
            previewMillis = env.previewPositionMillis,
            durationMillis = env.durationMillis,
            bufferedPositionMillisProvider = bufferedPositionMillisProvider,
            isPlaying = env.isPlaying,
            playbackSpeed = env.playbackSpeed ?: 1f,
            wavy = false,
            onSeek = onSeek,
            thumbOnHoverOnly = thumbOnHoverOnly,
            onScrub = handleScrub,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (editScope == null) {
                        Modifier.windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal))
                    } else {
                        Modifier
                    },
                ),
        )
        Spacer(Modifier.height(6.dp * scale))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(editScope?.barContainerModifier(PlayerBarSlot.Bottom) ?: Modifier),
            horizontalArrangement = Arrangement.spacedBy((if (env.isLandscape) 6.dp else 4.dp) * scale),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RenderVideoPanelItems(
                items = env.layout.bottomBar,
                env = env,
                inTopBar = false,
                editScope = editScope,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.RenderVideoPanelItems(
    items: List<PlayerPanelItem>,
    env: PlayerBarEnvironment,
    inTopBar: Boolean,
    editScope: PlayerBarEditScope? = null,
) {
    val barSlot = if (inTopBar) PlayerBarSlot.Top else PlayerBarSlot.Bottom
    val framelessWindow = if (env.isPreview) null else LocalFramelessWindow.current
    val isDesktopWindow = !env.isPreview && (framelessWindow != null || env.windowActions != null)
    val effectivePin = env.windowActions?.onToggleAlwaysOnTop
        ?: framelessWindow?.let { fw -> { fw.setAlwaysOnTop(!fw.isAlwaysOnTop) } }
        ?: if (env.isPreview) ({}) else null
    val effectiveOnTop = env.windowActions?.isAlwaysOnTop ?: framelessWindow?.isAlwaysOnTop ?: false
    val compactPreviewButtons = env.isPreview && env.previewCompactScale < 1f
    val buttonContainerSize = if (compactPreviewButtons) DpSize(36.dp, 36.dp) else null
    val buttonIconSize = if (compactPreviewButtons) 18.dp else 20.dp
    val suppressTooltip = editScope != null

    // Title 与紧邻其后的 Spacer 合并为单一 weight(1f)（移植自 Bilby PlayerShell.kt:1410）：
    // 防止两者同带 weight 导致右侧按钮停在 75% 处而非贴右沿。
    val titleIndex = items.indexOfFirst {
        it is PlayerPanelItem.Control && it.id == PlayerControlId.Title
    }
    val absorbedSpacerIndex = if (
        titleIndex >= 0 && items.getOrNull(titleIndex + 1) == PlayerPanelItem.Spacer
    ) {
        titleIndex + 1
    } else {
        -1
    }
    val hasRemainingSpacer = items.withIndex().any { (idx, item) ->
        item == PlayerPanelItem.Spacer && idx != absorbedSpacerIndex
    }

    for ((index, item) in items.withIndex()) {
        editScope?.RenderDropIndicator(barSlot, index)

        when (item) {
            PlayerPanelItem.Spacer -> {
                if (editScope == null) {
                    if (index != absorbedSpacerIndex) {
                        Spacer(
                            Modifier
                                .weight(1f)
                                .then(if (inTopBar) Modifier.height(36.dp).windowDragArea(enabled = env.dragAreaEnabled) else Modifier),
                        )
                    }
                } else {
                    val spacerMod = if (index == absorbedSpacerIndex) {
                        Modifier.width(24.dp)
                    } else {
                        Modifier.weight(1f)
                    }
                    key(barSlot, index, item) {
                        editScope.SpacerVisual(barSlot, index, spacerMod)
                    }
                }
            }

            is PlayerPanelItem.Control -> {
                if (editScope == null) {
                    val hiddenByTransport = if (item.id == PlayerControlId.PlayPause) {
                        env.transportInCenter
                    } else {
                        (env.transportInCenter || env.transportInAnyBar) && env.layout.center.hasControl(item.id)
                    }
                    if (hiddenByTransport) continue
                } else {
                    if (
                        !env.transportInCenter &&
                        env.transportInAnyBar &&
                        item.id != PlayerControlId.PlayPause &&
                        env.layout.center.hasControl(item.id)
                    ) {
                        editScope.onSlotSkipped(barSlot, index)
                        continue
                    }
                }

                val hiddenByCenter = editScope != null && env.transportInCenter && (
                    item.id == PlayerControlId.PlayPause || env.layout.center.hasControl(item.id)
                    )
                val selectOrRun: (() -> Unit) -> (() -> Unit) = { live ->
                    if (editScope != null) ({ editScope.onSelectSlot(barSlot, index) }) else live
                }

                when (item.id) {
                    PlayerControlId.Back -> if (!isDesktopWindow) {
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                label = tr("返回", "Back", "戻る", "뒤로", "返回"),
                                onClick = selectOrRun(env.onBackClick),
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                                containerSize = if (compactPreviewButtons) {
                                    DpSize(30.dp, 36.dp)
                                } else {
                                    IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow)
                                },
                                iconSize = if (compactPreviewButtons) 18.dp else 24.dp,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.Title -> {
                        val titleOuterMod = if (hasRemainingSpacer) {
                            if (editScope != null) Modifier.weight(1f, fill = false) else Modifier.widthIn(max = 360.dp)
                        } else {
                            Modifier.weight(1f)
                        }
                        RenderEditableSlot(
                            editScope = editScope,
                            bar = barSlot,
                            index = index,
                            item = item,
                            hiddenByCenter = hiddenByCenter,
                            clickableContainer = true,
                            modifier = titleOuterMod,
                        ) { innerMod ->
                            val titleBoxMod = innerMod
                                .then(if (inTopBar && editScope == null) Modifier.windowDragArea(enabled = env.dragAreaEnabled) else Modifier)
                                .padding(horizontal = 6.dp)
                            val titleBody: @Composable () -> Unit = {
                                Column {
                                    Text(
                                        text = env.title,
                                        style = MaterialTheme.typography.titleMediumEmphasized.copy(shadow = PlayerTextShadow),
                                        color = LocalFixedColors.current.OnMedia,
                                        maxLines = 1,
                                        overflow = TextOverflow.MiddleEllipsis,
                                    )
                                    if (env.episodeLabel != null || env.isLocalPlayback) {
                                        Row(
                                            modifier = Modifier.padding(top = 2.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (env.episodeLabel != null) {
                                                Text(
                                                    text = env.episodeLabel,
                                                    style = MaterialTheme.typography.labelLarge.tabular.copy(shadow = PlayerTextShadow),
                                                    color = LocalFixedColors.current.OnMedia.copy(alpha = 0.82f),
                                                    maxLines = 1,
                                                )
                                            }
                                            if (env.isLocalPlayback) LocalPlaybackBadge()
                                        }
                                    }
                                }
                            }
                            Box(titleBoxMod) {
                                if (suppressTooltip) {
                                    titleBody()
                                } else {
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                            if (inTopBar) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
                                        ),
                                        tooltip = { PlainTooltip { Text(env.title) } },
                                        state = rememberTooltipState(),
                                    ) {
                                        titleBody()
                                    }
                                }
                            }
                        }
                    }

                    PlayerControlId.SystemStatus -> if (env.isFullscreen) {
                        val statusConfig = env.statusBarConfig
                        RenderEditableSlot(
                            editScope = editScope,
                            bar = barSlot,
                            index = index,
                            item = item,
                            hiddenByCenter = hiddenByCenter,
                            clickableContainer = true,
                        ) { mod ->
                            if (statusConfig != null && !statusConfig.hasAnyItem && editScope != null) {
                                Text(
                                    text = "—",
                                    style = MaterialTheme.typography.labelSmall.tabular,
                                    color = LocalFixedColors.current.OnMedia.copy(alpha = 0.7f),
                                    modifier = mod.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            } else if (statusConfig != null) {
                                PlayerSystemStatusCapsule(
                                    modifier = mod,
                                    showBackground = item.showBackground,
                                    config = statusConfig,
                                    previewStatus = env.previewSystemStatus,
                                    previewClockText = env.previewClockText,
                                )
                            } else {
                                PlayerSystemStatusCapsule(
                                    modifier = mod,
                                    showBackground = item.showBackground,
                                )
                            }
                        }
                    }

                    PlayerControlId.PlayPause -> {
                        if (editScope != null && env.transportInCenter) {
                            RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = true) { mod ->
                                PlayerIconButton(
                                    icon = Icons.Filled.PlayArrow,
                                    label = playerControlLabel(PlayerControlId.PlayPause),
                                    onClick = { editScope.onSelectSlot(barSlot, index) },
                                    showBackground = item.showBackground,
                                    containerSize = buttonContainerSize,
                                    iconSize = buttonIconSize,
                                    suppressTooltip = true,
                                    modifier = mod,
                                )
                            }
                        } else if (editScope != null) {
                            key(barSlot, index, item) {
                                editScope.DecorateSlot(
                                    bar = barSlot,
                                    index = index,
                                    item = item,
                                    hiddenByCenter = false,
                                ) {
                                    ControlBarTransportCluster(
                                        config = env.layout.center,
                                        isPlaying = env.isPlaying,
                                        showEpisodeSkip = env.showEpisodes,
                                        canPrevious = env.hasPrevious,
                                        canNext = env.hasNext,
                                        allowSeek = env.durationMillis > 0L,
                                        onTogglePlayPause = { editScope.onSelectSlot(barSlot, index) },
                                        onPrevious = {},
                                        onNext = {},
                                        onSeekDeltaMillis = {},
                                        showPlayBackground = item.showBackground,
                                        tooltipBelow = inTopBar,
                                        previewMode = true,
                                        selectedSlot = editScope.selectedCenterSlot,
                                        isPlaySelected = editScope.isSlotSelected(barSlot, index),
                                        onSelectSlot = editScope::onSelectCenterSlot,
                                        dropTargetSlot = editScope.hoveredCenterSlot,
                                        showEmptySlots = editScope.draggingCenterAction,
                                        onSlotBounds = editScope::onCenterSlotBounds,
                                    )
                                }
                            }
                        } else {
                            ControlBarTransportCluster(
                                config = env.layout.center,
                                isPlaying = env.isPlaying,
                                showEpisodeSkip = env.showEpisodes,
                                canPrevious = env.hasPrevious,
                                canNext = env.hasNext,
                                allowSeek = env.durationMillis > 0L,
                                onTogglePlayPause = env.onPlayPause,
                                onPrevious = env.onPrevious,
                                onNext = env.onNext,
                                onSeekDeltaMillis = env.onSeekDeltaMillis,
                                showPlayBackground = item.showBackground,
                                tooltipBelow = inTopBar,
                            )
                        }
                    }

                    PlayerControlId.Previous -> if (env.showEpisodes) {
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Filled.SkipPrevious,
                                label = tr("上一集", "Previous episode", "前のエピソード", "이전 에피소드", "上一集"),
                                onClick = selectOrRun(env.onPrevious),
                                enabled = env.hasPrevious,
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                shortcut = "PageUp",
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.Next -> if (env.showEpisodes) {
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Filled.SkipNext,
                                label = tr("下一集", "Next episode", "次のエピソード", "다음 에피소드", "下一集"),
                                onClick = selectOrRun(env.onNext),
                                enabled = env.hasNext,
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                shortcut = "PageDown",
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.SeekBackward -> if (env.durationMillis > 0L) {
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Filled.FastRewind,
                                label = stringResource(Res.string.player_seek_backward_seconds, env.seekButtonSeconds),
                                onClick = selectOrRun { env.onSeekDeltaMillis(-env.seekButtonSeconds * 1_000L) },
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.SeekForward -> if (env.durationMillis > 0L) {
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Filled.FastForward,
                                label = stringResource(Res.string.player_seek_forward_seconds, env.seekButtonSeconds),
                                onClick = selectOrRun { env.onSeekDeltaMillis(env.seekButtonSeconds * 1_000L) },
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.TimeLabel -> RenderEditableSlot(
                        editScope = editScope,
                        bar = barSlot,
                        index = index,
                        item = item,
                        hiddenByCenter = hiddenByCenter,
                        clickableContainer = true,
                    ) { mod ->
                        Box(mod) {
                            PlayerTimeReadout(
                                positionMillisProvider = env.positionMillisProvider,
                                scrubPositionMillis = env.scrubPositionMillis,
                                previewPositionMillis = env.previewPositionMillis,
                                durationMillis = env.durationMillis,
                            )
                        }
                    }

                    PlayerControlId.Volume -> if (env.volume != null) {
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            Box(mod) {
                                PlayerVolumeControl(
                                    volume = env.volume,
                                    showSlider = !env.compactWidth,
                                    collapseWhenIdle = inTopBar,
                                    onVolumeChange = if (editScope != null) ({ editScope.onSelectSlot(barSlot, index) }) else env.onVolumeChange,
                                    onToggleMute = selectOrRun(env.onToggleMute),
                                    onDraggingChange = env.onVolumeDraggingChange,
                                    showBackground = item.showBackground,
                                    tooltipBelow = inTopBar,
                                    suppressTooltip = suppressTooltip,
                                )
                            }
                        }
                    }

                    PlayerControlId.Quality -> if (env.qualityOptions.size > 1 && env.currentQuality != null) {
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerChipButton(
                                text = env.currentQuality.qualityDisplayLabel,
                                selected = env.isQualityPopupOpen,
                                onClick = selectOrRun { env.onQualityPopupOpenChange(!env.isQualityPopupOpen) },
                                tooltip = if (!suppressTooltip && !env.isQualityPopupOpen) stringResource(Res.string.player_quality) else null,
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.Speed -> if (env.playbackSpeed != null) {
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerChipButton(
                                text = if (env.isPreview) "1.0x" else formatSpeed(env.playbackSpeed),
                                selected = env.isSpeedPopupOpen,
                                onClick = selectOrRun { env.onSpeedPopupOpenChange(!env.isSpeedPopupOpen) },
                                tooltip = if (!suppressTooltip && !env.isSpeedPopupOpen) {
                                    tr(
                                        "倍速 ([ / ]，Backspace 恢复原速)",
                                        "Speed ([ / ], Backspace resets)",
                                        "再生速度 ([ / ]、Backspace で等倍)",
                                        "재생 속도 ([ / ], Backspace 기본 속도)",
                                        "倍速 ([ / ]，Backspace 還原原速)",
                                    )
                                } else {
                                    null
                                },
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                                modifier = mod.semantics {
                                    contentDescription = tr(
                                        "倍速 ${formatSpeed(env.playbackSpeed)}",
                                        "Speed ${formatSpeed(env.playbackSpeed)}",
                                        "再生速度 ${formatSpeed(env.playbackSpeed)}",
                                        "재생 속도 ${formatSpeed(env.playbackSpeed)}",
                                        "倍速 ${formatSpeed(env.playbackSpeed)}",
                                    )
                                },
                            )
                        }
                    }

                    PlayerControlId.Episodes -> if (env.showEpisodes) {
                        val label = tr("选集", "Playlist", "再生リスト", "재생 목록", "選集")
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            if (env.compactWidth || inTopBar) {
                                PlayerIconButton(
                                    icon = Icons.Outlined.VideoLibrary,
                                    label = label,
                                    onClick = selectOrRun(env.onEpisodesClick),
                                    containerSize = buttonContainerSize,
                                    iconSize = buttonIconSize,
                                    tooltipBelow = inTopBar,
                                    showBackground = item.showBackground,
                                    suppressTooltip = suppressTooltip,
                                    modifier = mod,
                                )
                            } else {
                                PlayerChipButton(
                                    text = label,
                                    icon = Icons.Outlined.VideoLibrary,
                                    onClick = selectOrRun(env.onEpisodesClick),
                                    tooltipBelow = inTopBar,
                                    showBackground = item.showBackground,
                                    modifier = mod,
                                )
                            }
                        }
                    }

                    PlayerControlId.Tracks -> env.onTracksClick?.let { onTracks ->
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Outlined.Subtitles,
                                label = tr("音轨与字幕", "Audio and subtitles", "音声と字幕", "오디오 및 자막", "音軌與字幕"),
                                onClick = selectOrRun(onTracks),
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                shortcut = "C",
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.Listen -> env.onSwitchToListen?.let { onListen ->
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Outlined.Headphones,
                                label = tr("听视频", "Audio mode", "音声のみ", "오디오 모드", "聽影片"),
                                onClick = selectOrRun(onListen),
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.Rotate -> env.onRotate?.let { onRotate ->
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Filled.Rotate90DegreesCw,
                                label = tr("顺时针旋转 90 度", "Rotate 90° clockwise", "右に90度回転", "시계 방향으로 90도 회전", "順時針旋轉 90 度"),
                                onClick = selectOrRun(onRotate),
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                shortcut = "R",
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.AspectRatio -> env.onCycleAspectRatio?.let { onCycle ->
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Outlined.AspectRatio,
                                label = tr("画面比例", "Aspect ratio", "アスペクト比", "화면 비율", "畫面比例"),
                                onClick = selectOrRun(onCycle),
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.Lock -> RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                        PlayerIconButton(
                            icon = if (env.isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                            label = if (env.isLocked) {
                                tr("解锁画面", "Unlock screen", "画面のロックを解除", "화면 잠금 해제", "解鎖畫面")
                            } else {
                                tr("锁定画面", "Lock screen", "画面をロック", "화면 잠금", "鎖定畫面")
                            },
                            onClick = selectOrRun(env.onToggleLock),
                            selected = env.isLocked,
                            containerSize = buttonContainerSize,
                            iconSize = buttonIconSize,
                            tooltipBelow = inTopBar,
                            showBackground = item.showBackground,
                            suppressTooltip = suppressTooltip,
                            modifier = mod,
                        )
                    }

                    PlayerControlId.Stats -> env.onStatsClick?.let { onStats ->
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Outlined.Info,
                                label = tr("详细信息", "Details", "詳細情報", "세부 정보", "詳細資訊"),
                                onClick = selectOrRun(onStats),
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                shortcut = "I",
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.Settings -> env.onSettingsClick?.let { onSettings ->
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = Icons.Outlined.Tune,
                                label = tr("播放设置", "Playback settings", "再生設定", "재생 설정", "播放設定"),
                                onClick = selectOrRun(onSettings),
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    PlayerControlId.Fullscreen -> RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                        PlayerIconButton(
                            icon = if (env.isFullscreen && !env.isPreview) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            label = if (env.isFullscreen && !env.isPreview) {
                                tr("退出全屏", "Exit fullscreen", "全画面を終了", "전체 화면 종료", "結束全螢幕")
                            } else {
                                tr("全屏", "Fullscreen", "全画面", "전체 화면", "全螢幕")
                            },
                            onClick = selectOrRun(env.onToggleFullscreen),
                            containerSize = buttonContainerSize,
                            iconSize = buttonIconSize,
                            tooltipBelow = inTopBar,
                            shortcut = "F",
                            showBackground = item.showBackground,
                            suppressTooltip = suppressTooltip,
                            modifier = mod,
                        )
                    }

                    PlayerControlId.PinOnTop -> if (effectivePin != null) {
                        RenderEditableSlot(editScope, barSlot, index, item, hiddenByCenter = hiddenByCenter) { mod ->
                            PlayerIconButton(
                                icon = if (effectiveOnTop) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                label = if (effectiveOnTop) {
                                    tr("取消置顶", "Unpin", "最前面表示を解除", "고정 해제", "取消置頂")
                                } else {
                                    tr("置顶", "Pin on top", "最前面に表示", "항상 위에 표시", "置頂")
                                },
                                onClick = selectOrRun(effectivePin),
                                selected = effectiveOnTop,
                                containerSize = buttonContainerSize,
                                iconSize = buttonIconSize,
                                tooltipBelow = inTopBar,
                                shortcut = "T",
                                showBackground = item.showBackground,
                                suppressTooltip = suppressTooltip,
                                modifier = mod,
                            )
                        }
                    }

                    // 信息流专属控件不在视频栏渲染
                    PlayerControlId.AutoAdvance,
                    PlayerControlId.Star,
                    PlayerControlId.Vault,
                    PlayerControlId.OpenFull,
                    PlayerControlId.OpenFolder,
                    PlayerControlId.Download,
                    PlayerControlId.HideChrome,
                    -> Unit
                }
            }
        }
    }
    editScope?.RenderDropIndicator(barSlot, items.size)
}
