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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.PlayerControlId
import dev.piko.shared.media.player.PlayerFormFactorLayout
import dev.piko.shared.media.player.PlayerPanelItem
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalFramelessWindow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_maximize
import dev.piko.ui.resources.player_minimize
import dev.piko.ui.resources.player_quality
import dev.piko.ui.resources.player_restore
import dev.piko.ui.resources.player_seek_backward_seconds
import dev.piko.ui.resources.player_seek_forward_seconds
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.tabular

/**
 * 顶栏与底栏共享的业务状态与动作集合：由 `MobilePlayerControls` 组装一次，
 * 供 `CustomizablePlayerTopBar` 与 `CustomizablePlayerBottomBar` 按用户自定义布局动态渲染任意控件。
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
)

/**
 * 基于四态自定义配置的播放器顶栏（视频横屏 / 视频竖屏）：
 * 按 `env.layout.topBar` 顺序渲染所有控件与 `Spacer`，桌面独立窗口的最小化/最大化/关闭固定贴在最右端。
 */
@Composable
internal fun CustomizablePlayerTopBar(
    env: PlayerBarEnvironment,
    modifier: Modifier = Modifier,
) {
    val framelessWindow = LocalFramelessWindow.current
    val windowActions = env.windowActions
    val showWindowButtons = windowActions?.showWindowButtons ?: (framelessWindow != null)
    val effectivePin = windowActions?.onToggleAlwaysOnTop
        ?: framelessWindow?.let { fw -> { fw.setAlwaysOnTop(!fw.isAlwaysOnTop) } }
    val effectiveOnTop = windowActions?.isAlwaysOnTop ?: framelessWindow?.isAlwaysOnTop ?: false
    val effectiveClose = windowActions?.onClose ?: framelessWindow?.let { fw -> fw::close }
    // 若用户已在顶栏或底栏配置了 PinOnTop，窗口按钮组不再重复绘制置顶按钮（严格 DRY）
    val pinAlreadyInLayout = env.layout.hasControl(PlayerControlId.PinOnTop)
    val showPinInWindowCluster = !pinAlreadyInLayout && effectivePin != null
    val hasWindowCluster = showPinInWindowCluster || (
        showWindowButtons && (
            windowActions?.onMinimize != null ||
                windowActions?.onToggleMaximize != null ||
                effectiveClose != null
            )
        )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(TopScrim))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp + (windowActions?.topChromePadding ?: 0.dp),
                bottom = 12.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RenderVideoPanelItems(
            items = env.layout.topBar,
            env = env,
            inTopBar = true,
        )
        if (hasWindowCluster) {
            PlayerPillCluster {
                if (showPinInWindowCluster && effectivePin != null) {
                    ClusterIconButton(
                        icon = if (effectiveOnTop) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        label = if (effectiveOnTop) {
                            tr("取消置顶", "Unpin", "最前面表示を解除", "고정 해제", "取消置頂")
                        } else {
                            tr("置顶", "Pin on top", "最前面に表示", "항상 위에 표시", "置頂")
                        },
                        onClick = effectivePin,
                        tooltipBelow = true,
                        shortcut = "T",
                        tint = if (effectiveOnTop) MaterialTheme.colorScheme.primary else LocalFixedColors.current.OnMedia,
                    )
                }
                if (showWindowButtons) {
                    windowActions?.onMinimize?.let { onMinimize ->
                        ClusterIconButton(
                            icon = Icons.Filled.Remove,
                            label = stringResource(Res.string.player_minimize),
                            onClick = onMinimize,
                            tooltipBelow = true,
                            iconSize = 18.dp,
                        )
                    }
                    windowActions?.onToggleMaximize?.let { onToggleMaximize ->
                        val restored = windowActions.isMaximized || windowActions.isFullscreen
                        ClusterIconButton(
                            icon = if (restored) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
                            label = stringResource(if (restored) Res.string.player_restore else Res.string.player_maximize),
                            onClick = onToggleMaximize,
                            tooltipBelow = true,
                            iconSize = 16.dp,
                        )
                    }
                    if (effectiveClose != null) {
                        PlayerCloseWindowButton(
                            label = tr("关闭", "Close", "閉じる", "닫기", "關閉"),
                            shortcut = LocalPikoPlatform.current.shortcutModifier.label("W"),
                            onClick = effectiveClose,
                        )
                    }
                }
            }
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(BottomScrim))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (env.isLandscape) 10.dp else 14.dp),
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
                .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal)),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RenderVideoPanelItems(
                items = env.layout.bottomBar,
                env = env,
                inTopBar = false,
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
) {
    val framelessWindow = LocalFramelessWindow.current
    val isDesktopWindow = framelessWindow != null || env.windowActions != null
    val effectivePin = env.windowActions?.onToggleAlwaysOnTop
        ?: framelessWindow?.let { fw -> { fw.setAlwaysOnTop(!fw.isAlwaysOnTop) } }
    val effectiveOnTop = env.windowActions?.isAlwaysOnTop ?: framelessWindow?.isAlwaysOnTop ?: false

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
        when (item) {
            PlayerPanelItem.Spacer -> if (index != absorbedSpacerIndex) {
                Spacer(
                    Modifier
                        .weight(1f)
                        .then(if (inTopBar) Modifier.height(36.dp).windowDragArea(enabled = env.dragAreaEnabled) else Modifier),
                )
            }

            is PlayerPanelItem.Control -> {
                val hiddenByTransport = if (item.id == PlayerControlId.PlayPause) {
                    env.transportInCenter
                } else {
                    (env.transportInCenter || env.transportInAnyBar) && env.layout.center.hasControl(item.id)
                }
                if (hiddenByTransport) continue

                when (item.id) {
                    PlayerControlId.Back -> if (!isDesktopWindow) {
                        PlayerIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            label = tr("返回", "Back", "戻る", "뒤로", "返回"),
                            onClick = env.onBackClick,
                            tooltipBelow = inTopBar,
                            showBackground = item.showBackground,
                            containerSize = IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow),
                        )
                    }

                    PlayerControlId.Title -> {
                        val titleModifier = (
                            if (hasRemainingSpacer) Modifier.widthIn(max = 360.dp)
                            else Modifier.weight(1f)
                            )
                            .then(if (inTopBar) Modifier.windowDragArea(enabled = env.dragAreaEnabled) else Modifier)
                            .padding(horizontal = 6.dp)
                        Box(titleModifier) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                    if (inTopBar) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
                                ),
                                tooltip = { PlainTooltip { Text(env.title) } },
                                state = rememberTooltipState(),
                            ) {
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
                        }
                    }

                    PlayerControlId.SystemStatus -> if (env.isFullscreen) {
                        PlayerSystemStatusCapsule(showBackground = item.showBackground)
                    }

                    PlayerControlId.PlayPause -> ControlBarTransportCluster(
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
                        tooltipBelow = inTopBar,
                    )

                    PlayerControlId.Previous -> if (env.showEpisodes) {
                        PlayerIconButton(
                            icon = Icons.Filled.SkipPrevious,
                            label = tr("上一集", "Previous episode", "前のエピソード", "이전 에피소드", "上一集"),
                            onClick = env.onPrevious,
                            enabled = env.hasPrevious,
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            shortcut = "PageUp",
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Next -> if (env.showEpisodes) {
                        PlayerIconButton(
                            icon = Icons.Filled.SkipNext,
                            label = tr("下一集", "Next episode", "次のエピソード", "다음 에피소드", "下一集"),
                            onClick = env.onNext,
                            enabled = env.hasNext,
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            shortcut = "PageDown",
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.SeekBackward -> if (env.durationMillis > 0L) {
                        PlayerIconButton(
                            icon = Icons.Filled.FastRewind,
                            label = stringResource(Res.string.player_seek_backward_seconds, env.seekButtonSeconds),
                            onClick = { env.onSeekDeltaMillis(-env.seekButtonSeconds * 1_000L) },
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.SeekForward -> if (env.durationMillis > 0L) {
                        PlayerIconButton(
                            icon = Icons.Filled.FastForward,
                            label = stringResource(Res.string.player_seek_forward_seconds, env.seekButtonSeconds),
                            onClick = { env.onSeekDeltaMillis(env.seekButtonSeconds * 1_000L) },
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.TimeLabel -> PlayerTimeReadout(
                        positionMillisProvider = env.positionMillisProvider,
                        scrubPositionMillis = env.scrubPositionMillis,
                        previewPositionMillis = env.previewPositionMillis,
                        durationMillis = env.durationMillis,
                    )

                    PlayerControlId.Volume -> if (env.volume != null) {
                        PlayerVolumeControl(
                            volume = env.volume,
                            showSlider = !env.compactWidth && !inTopBar,
                            onVolumeChange = env.onVolumeChange,
                            onToggleMute = env.onToggleMute,
                            onDraggingChange = env.onVolumeDraggingChange,
                            showBackground = item.showBackground,
                            tooltipBelow = inTopBar,
                        )
                    }

                    PlayerControlId.Quality -> if (env.qualityOptions.size > 1 && env.currentQuality != null) {
                        PlayerChipButton(
                            text = env.currentQuality.qualityDisplayLabel,
                            selected = env.isQualityPopupOpen,
                            onClick = { env.onQualityPopupOpenChange(!env.isQualityPopupOpen) },
                            tooltip = if (!env.isQualityPopupOpen) stringResource(Res.string.player_quality) else null,
                            tooltipBelow = inTopBar,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Speed -> if (env.playbackSpeed != null) {
                        PlayerChipButton(
                            text = formatSpeed(env.playbackSpeed),
                            selected = env.isSpeedPopupOpen,
                            onClick = { env.onSpeedPopupOpenChange(!env.isSpeedPopupOpen) },
                            tooltip = if (!env.isSpeedPopupOpen) {
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
                            modifier = Modifier.semantics {
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

                    PlayerControlId.Episodes -> if (env.showEpisodes) {
                        val label = tr("选集", "Playlist", "再生リスト", "재생 목록", "選集")
                        if (env.compactWidth || inTopBar) {
                            PlayerIconButton(
                                icon = Icons.Outlined.VideoLibrary,
                                label = label,
                                onClick = env.onEpisodesClick,
                                iconSize = 20.dp,
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                            )
                        } else {
                            PlayerChipButton(
                                text = label,
                                icon = Icons.Outlined.VideoLibrary,
                                onClick = env.onEpisodesClick,
                                tooltipBelow = inTopBar,
                                showBackground = item.showBackground,
                            )
                        }
                    }

                    PlayerControlId.Tracks -> env.onTracksClick?.let { onTracks ->
                        PlayerIconButton(
                            icon = Icons.Outlined.Subtitles,
                            label = tr("音轨与字幕", "Audio and subtitles", "音声と字幕", "오디오 및 자막", "音軌與字幕"),
                            onClick = onTracks,
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            shortcut = "C",
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Listen -> env.onSwitchToListen?.let { onListen ->
                        PlayerIconButton(
                            icon = Icons.Outlined.Headphones,
                            label = tr("听视频", "Audio mode", "音声のみ", "오디오 모드", "聽影片"),
                            onClick = onListen,
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Rotate -> env.onRotate?.let { onRotate ->
                        PlayerIconButton(
                            icon = Icons.Filled.Rotate90DegreesCw,
                            label = tr("顺时针旋转 90 度", "Rotate 90° clockwise", "右に90度回転", "시계 방향으로 90도 회전", "順時針旋轉 90 度"),
                            onClick = onRotate,
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            shortcut = "R",
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.AspectRatio -> env.onCycleAspectRatio?.let { onCycle ->
                        PlayerIconButton(
                            icon = Icons.Outlined.AspectRatio,
                            label = tr("画面比例", "Aspect ratio", "アスペクト比", "화면 비율", "畫面比例"),
                            onClick = onCycle,
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Lock -> PlayerIconButton(
                        icon = if (env.isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        label = if (env.isLocked) {
                            tr("解锁画面", "Unlock screen", "画面のロックを解除", "화면 잠금 해제", "解鎖畫面")
                        } else {
                            tr("锁定画面", "Lock screen", "画面をロック", "화면 잠금", "鎖定畫面")
                        },
                        onClick = env.onToggleLock,
                        selected = env.isLocked,
                        iconSize = 20.dp,
                        tooltipBelow = inTopBar,
                        showBackground = item.showBackground,
                    )

                    PlayerControlId.Stats -> env.onStatsClick?.let { onStats ->
                        PlayerIconButton(
                            icon = Icons.Outlined.Info,
                            label = tr("详细信息", "Details", "詳細情報", "세부 정보", "詳細資訊"),
                            onClick = onStats,
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            shortcut = "I",
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Settings -> env.onSettingsClick?.let { onSettings ->
                        PlayerIconButton(
                            icon = Icons.Outlined.Tune,
                            label = tr("播放设置", "Playback settings", "再生設定", "재생 설정", "播放設定"),
                            onClick = onSettings,
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            showBackground = item.showBackground,
                        )
                    }

                    PlayerControlId.Fullscreen -> PlayerIconButton(
                        icon = if (env.isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        label = if (env.isFullscreen) {
                            tr("退出全屏", "Exit fullscreen", "全画面を終了", "전체 화면 종료", "結束全螢幕")
                        } else {
                            tr("全屏", "Fullscreen", "全画面", "전체 화면", "全螢幕")
                        },
                        onClick = env.onToggleFullscreen,
                        iconSize = 20.dp,
                        tooltipBelow = inTopBar,
                        shortcut = "F",
                        showBackground = item.showBackground,
                    )

                    PlayerControlId.PinOnTop -> if (effectivePin != null) {
                        PlayerIconButton(
                            icon = if (effectiveOnTop) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            label = if (effectiveOnTop) {
                                tr("取消置顶", "Unpin", "最前面表示を解除", "고정 해제", "取消置頂")
                            } else {
                                tr("置顶", "Pin on top", "最前面に表示", "항상 위에 표시", "置頂")
                            },
                            onClick = effectivePin,
                            selected = effectiveOnTop,
                            iconSize = 20.dp,
                            tooltipBelow = inTopBar,
                            shortcut = "T",
                            showBackground = item.showBackground,
                        )
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
}
