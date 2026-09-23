package dev.piko.ui.screens.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.player.PlayerAspectRatio
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放器顶栏：返回、标题、画面比例、清晰度、同目录列表。
 *
 * 标题单行、中间省略：视频文件名的区分信息（集数、分辨率）通常在末尾，
 * 末尾省略会把几十集截成同一个前缀。完整标题在长按提示里。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerTopBar(
    title: String,
    isLocalPlayback: Boolean,
    aspectRatio: PlayerAspectRatio?,
    qualityOptions: List<String>,
    currentQuality: String?,
    showPlaylistEntry: Boolean,
    onPlaylistClick: () -> Unit,
    onBackClick: () -> Unit,
    onAspectRatioChange: (PlayerAspectRatio) -> Unit,
    onQualityChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onMenuOpenChange: (Boolean) -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(TopScrim))
            // 横屏隐藏了系统栏，顶部只剩挖孔一侧需要让位；竖屏让出状态栏。
            // 渐变在 padding 之前，仍然满幅。
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "返回",
                onClick = onBackClick,
                tooltipBelow = true,
            )
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(title) } },
                state = rememberTooltipState(),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (isLocalPlayback) {
                        Text(
                            text = "本地文件",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 后端不支持画面比例时整个入口隐藏，避免点了没反应
            if (aspectRatio != null) {
                SelectionMenuButton(
                    icon = Icons.Filled.AspectRatio,
                    label = "画面比例",
                    options = PlayerAspectRatio.entries,
                    selected = aspectRatio,
                    optionLabel = { it.label },
                    onSelect = onAspectRatioChange,
                    onOpenChange = onMenuOpenChange,
                )
            }

            if (qualityOptions.isNotEmpty()) {
                SelectionMenuButton(
                    icon = Icons.Outlined.HighQuality,
                    label = "清晰度",
                    options = qualityOptions,
                    selected = currentQuality,
                    optionLabel = { it },
                    onSelect = onQualityChange,
                    onOpenChange = onMenuOpenChange,
                )
            }

            if (showPlaylistEntry) {
                PlayerIconButton(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    label = "同目录视频",
                    onClick = onPlaylistClick,
                    tooltipBelow = true,
                )
            }
        }
    }
}

/**
 * 画面中央的播放控制：后退、播放/暂停、前进。
 *
 * 播放键用 Expressive 的可切换形状：暂停态为圆形，播放态为方角，状态变化本身有形变反馈。
 * 加载中播放键原位换成带容器的加载指示器，尺寸不变，三个按钮不会跳动。
 * 横屏画面大，用 Large 规格；竖屏画面只占屏幕中间一条，用 Medium。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerCenterControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    isLandscape: Boolean,
    onPlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playContainer = if (isLandscape) {
        IconButtonDefaults.largeContainerSize()
    } else {
        IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide)
    }
    val playIconSize = if (isLandscape) IconButtonDefaults.largeIconSize else IconButtonDefaults.mediumIconSize
    val seekContainer = if (isLandscape) {
        IconButtonDefaults.mediumContainerSize()
    } else {
        IconButtonDefaults.smallContainerSize()
    }
    val seekIconSize = if (isLandscape) IconButtonDefaults.mediumIconSize else IconButtonDefaults.smallIconSize
    val shapes = if (isLandscape) {
        IconButtonDefaults.toggleableShapes(
            shape = IconButtonDefaults.largeRoundShape,
            pressedShape = IconButtonDefaults.largePressedShape,
            checkedShape = IconButtonDefaults.largeSquareShape,
        )
    } else {
        IconButtonDefaults.toggleableShapes(
            shape = IconButtonDefaults.mediumRoundShape,
            pressedShape = IconButtonDefaults.mediumPressedShape,
            checkedShape = IconButtonDefaults.mediumSquareShape,
        )
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 32.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            icon = Icons.Filled.Replay10,
            label = "后退 ${SEEK_STEP_MILLIS / 1000} 秒",
            onClick = onSeekBackward,
            containerSize = seekContainer,
            iconSize = seekIconSize,
        )

        Box(modifier = Modifier.size(playContainer), contentAlignment = Alignment.Center) {
            if (isLoading) {
                ContainedLoadingIndicator(
                    modifier = Modifier.size(playContainer.height),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                FilledIconToggleButton(
                    checked = isPlaying,
                    onCheckedChange = { onPlayPause() },
                    shapes = shapes,
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(playContainer),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(playIconSize),
                    )
                }
            }
        }

        PlayerIconButton(
            icon = Icons.Filled.Forward10,
            label = "前进 ${SEEK_STEP_MILLIS / 1000} 秒",
            onClick = onSeekForward,
            containerSize = seekContainer,
            iconSize = seekIconSize,
        )
    }
}

/**
 * 播放器底栏：进度条在上，时间、倍速、全屏在下。
 *
 * 系统手势区的处理分两层：整栏让出 safeDrawing；进度条额外让出左右两侧的系统手势区，
 * 那里的横向拖动会被系统返回手势先拿走。按钮只响应点击，不受手势区影响，不必让。
 */
@Composable
internal fun PlayerBottomBar(
    isLandscape: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    playbackSpeed: Float?,
    onSeek: (Long) -> Unit,
    onSpeedClick: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onScrubbingChange: (Boolean) -> Unit = {},
) {
    var scrubPositionMillis by remember { mutableStateOf<Long?>(null) }
    val shownPosition = scrubPositionMillis ?: positionMillis

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(BottomScrim))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(horizontal = 12.dp, vertical = if (isLandscape) 4.dp else 8.dp),
    ) {
        PlayerSeekBar(
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            bufferedPositionMillis = bufferedPositionMillis,
            onSeek = onSeek,
            onScrub = { target ->
                val wasScrubbing = scrubPositionMillis != null
                scrubPositionMillis = target
                if (wasScrubbing != (target != null)) onScrubbingChange(target != null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal)),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(shownPosition),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )
            Text(
                text = " / ${formatTime(durationMillis)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )

            if (playbackSpeed != null) {
                SpeedButton(speed = playbackSpeed, onClick = onSpeedClick)
            }
            PlayerIconButton(
                icon = if (isLandscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                label = if (isLandscape) "退出全屏" else "全屏",
                onClick = onToggleFullscreen,
            )
        }
    }
}

/**
 * 进度条：Expressive 标准滑块加一段缓冲轨。
 *
 * 轨道按 SliderTokens 的几何自绘（16dp 高、4dp 手柄、手柄两侧 6dp 间隙、末端停止点），
 * 在未播放段上叠缓冲段。拖动期间只预览时间，松手才 seek：
 * 网络流每次 seek 都要重开 range 请求，跟手 seek 会连续打断缓冲。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerSeekBar(
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Long?) -> Unit = {},
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // 松手到新位置回报之间有一段延迟，这段时间里滑块停在目标处，不回跳到旧位置
    var pendingSeekMillis by remember { mutableStateOf<Long?>(null) }
    val currentOnScrub by rememberUpdatedState(onScrub)

    LaunchedEffect(pendingSeekMillis, positionMillis) {
        val pending = pendingSeekMillis ?: return@LaunchedEffect
        if (abs(positionMillis - pending) < SEEK_SETTLE_TOLERANCE_MILLIS) {
            pendingSeekMillis = null
        } else {
            delay(SEEK_SETTLE_TIMEOUT_MILLIS)
            pendingSeekMillis = null
        }
    }

    val enabled = durationMillis > 0
    fun fractionOf(millis: Long): Float =
        if (enabled) (millis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f

    val fraction = dragFraction ?: fractionOf(pendingSeekMillis ?: positionMillis)
    val bufferedFraction = fractionOf(bufferedPositionMillis)
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer,
    )
    val bufferedColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = BUFFERED_ALPHA)
    val interactionSource = remember { MutableInteractionSource() }
    val positionText = formatTime((fraction * durationMillis).toLong())
    val durationText = formatTime(durationMillis)

    BoxWithConstraints(modifier = modifier) {
        Slider(
            value = fraction,
            onValueChange = {
                dragFraction = it
                currentOnScrub((it * durationMillis).toLong())
            },
            onValueChangeFinished = {
                dragFraction?.let { target ->
                    val millis = (target * durationMillis).toLong()
                    pendingSeekMillis = millis
                    onSeek(millis)
                }
                dragFraction = null
                currentOnScrub(null)
            },
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = colors,
                    enabled = enabled,
                )
            },
            track = { state ->
                val trackColors = SeekTrackColors(
                    active = colors.activeTrackColor,
                    inactive = colors.inactiveTrackColor,
                    buffered = bufferedColor,
                    stop = colors.activeTrackColor,
                )
                Canvas(Modifier.fillMaxWidth().height(SeekTrackHeight)) {
                    drawSeekTrack(state.coercedValueAsFraction, bufferedFraction, trackColors)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "播放进度"
                    stateDescription = "$positionText / $durationText"
                },
        )

        // 数值指示：只在拖动时出现，贴在手柄正上方，零尺寸布局不挤占进度条的高度
        dragFraction?.let { dragging ->
            val density = LocalDensity.current
            val trackWidthPx = with(density) { (maxWidth - SeekHandleWidth).toPx() }
            val handleCenterPx = with(density) { (SeekHandleWidth / 2).toPx() } + dragging * trackWidthPx
            val gapPx = with(density) { 4.dp.roundToPx() }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
                    val x = (handleCenterPx - placeable.width / 2f).roundToInt().coerceIn(0, maxX)
                    layout(0, 0) { placeable.place(x, -placeable.height - gapPx) }
                },
            ) {
                Text(
                    text = formatTime((dragging * durationMillis).toLong()),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private class SeekTrackColors(val active: Color, val inactive: Color, val buffered: Color, val stop: Color)

private fun DrawScope.drawSeekTrack(fraction: Float, bufferedFraction: Float, colors: SeekTrackColors) {
    val height = size.height
    val outer = height / 2
    val inner = SeekTrackInsideCorner.toPx()
    val handleX = fraction * size.width
    val clearance = SeekHandleWidth.toPx() / 2 + SeekThumbTrackGap.toPx()

    val activeEnd = handleX - clearance
    val inactiveStart = handleX + clearance
    if (activeEnd > 0f) {
        drawTrackSegment(0f, activeEnd, colors.active, startRadius = outer, endRadius = inner)
    }
    if (inactiveStart < size.width) {
        drawTrackSegment(inactiveStart, size.width, colors.inactive, startRadius = inner, endRadius = outer)
        val bufferedEnd = bufferedFraction * size.width
        if (bufferedEnd > inactiveStart) {
            val reachesEnd = bufferedEnd >= size.width - outer
            drawTrackSegment(
                inactiveStart,
                if (reachesEnd) size.width else bufferedEnd,
                colors.buffered,
                startRadius = inner,
                endRadius = if (reachesEnd) outer else inner,
            )
        }
        // 末端停止点：未播放段与遮罩的对比度不够时，它标出轨道的终点
        drawCircle(
            color = colors.stop,
            radius = SeekStopIndicatorSize.toPx() / 2,
            center = Offset(size.width - outer, height / 2),
        )
    }
}

private fun DrawScope.drawTrackSegment(start: Float, end: Float, color: Color, startRadius: Float, endRadius: Float) {
    if (end <= start) return
    val startCorner = CornerRadius(startRadius.coerceAtMost((end - start) / 2))
    val endCorner = CornerRadius(endRadius.coerceAtMost((end - start) / 2))
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = start,
                top = 0f,
                right = end,
                bottom = size.height,
                topLeftCornerRadius = startCorner,
                bottomLeftCornerRadius = startCorner,
                topRightCornerRadius = endCorner,
                bottomRightCornerRadius = endCorner,
            ),
        )
    }
    drawPath(path, color)
}

/** 底栏的倍速入口，直接显示当前倍速，1x 以外的值一眼可见。 */
@Composable
private fun SpeedButton(speed: Float, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Speed,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatSpeed(speed),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { contentDescription = "倍速 ${formatSpeed(speed)}" },
        )
    }
}

/**
 * 倍速面板：连续滑块加一组常用值。
 *
 * 常用值用 Expressive 的连接式按钮组做单选，滑块负责预设之外的值，步进 0.05。
 * 滑块不设 stops：0.5 到 3.5 按 0.05 分是 59 个停止点，规范明确不建议过密。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackSpeedSheet(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "播放倍速",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = formatSpeed(speed),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = speed,
                onValueChange = { raw -> onSpeedChange((raw / SPEED_SLIDER_STEP).roundToInt() * SPEED_SLIDER_STEP) },
                valueRange = MIN_SPEED..MAX_SPEED,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "播放倍速" },
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatSpeed(MIN_SPEED),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatSpeed(MAX_SPEED),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                PresetSpeeds.forEachIndexed { index, preset ->
                    val checked = abs(speed - preset) < SPEED_MATCH_TOLERANCE
                    ToggleButton(
                        checked = checked,
                        onCheckedChange = { onSpeedChange(preset) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            PresetSpeeds.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        colors = ToggleButtonDefaults.toggleButtonColors(),
                        // 六个预设要在 360dp 宽的竖屏里排成一行，默认的 24dp 水平内边距放不下
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = formatSpeedPreset(preset),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 带长按提示的图标按钮。
 *
 * 控件栏全是纯图标按钮，提示是这类按钮在触屏上唯一的文字说明；
 * 触控目标由 IconButton 自带的最小交互尺寸保证不低于 48dp。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    containerSize: androidx.compose.ui.unit.DpSize? = null,
    iconSize: Dp = 24.dp,
    tooltipBelow: Boolean = false,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            enabled = enabled,
            modifier = if (containerSize != null) Modifier.size(containerSize) else Modifier,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * 单选菜单入口。选中项用 Expressive 菜单的选中态标出（形状与配色变化加前置勾），
 * 不再在文字后面拼符号。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> SelectionMenuButton(
    icon: ImageVector,
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onOpenChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    fun setExpanded(value: Boolean) {
        expanded = value
        onOpenChange(value)
    }

    Box {
        PlayerIconButton(
            icon = icon,
            label = label,
            onClick = { setExpanded(true) },
            tooltipBelow = true,
        )
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = { setExpanded(false) },
        ) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        selected = option == selected,
                        onClick = {
                            setExpanded(false)
                            onSelect(option)
                        },
                        text = { Text(optionLabel(option)) },
                        shapes = MenuDefaults.itemShape(index, options.size),
                        selectedLeadingIcon = {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                    )
                }
            }
        }
    }
}

internal val PlayerAspectRatio.label: String
    get() = when (this) {
        PlayerAspectRatio.Fit -> "适应屏幕"
        PlayerAspectRatio.Crop -> "裁剪填充"
        PlayerAspectRatio.Stretch -> "拉伸全屏"
    }

private val TopScrim = listOf(
    Color.Black.copy(alpha = 0.8f),
    Color.Black.copy(alpha = 0.4f),
    Color.Transparent,
)
private val BottomScrim = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.5f),
    Color.Black.copy(alpha = 0.85f),
)

// 以下几何取自 SliderTokens（XS 规格）：轨道高、手柄宽、手柄与轨道间隙、停止点直径；
// 内侧圆角 2dp 与 SliderDefaults 一致
private val SeekTrackHeight = 16.dp
private val SeekHandleWidth = 4.dp
private val SeekThumbTrackGap = 6.dp
private val SeekStopIndicatorSize = 4.dp
private val SeekTrackInsideCorner = 2.dp

private const val BUFFERED_ALPHA = 0.38f
private const val SEEK_SETTLE_TOLERANCE_MILLIS = 1_500L
private const val SEEK_SETTLE_TIMEOUT_MILLIS = 1_500L

internal const val SEEK_STEP_MILLIS = 10_000L
internal const val MIN_SPEED = 0.5f
internal const val MAX_SPEED = 3.5f
internal const val LONG_PRESS_BOOST_SPEED = 2.0f
private const val SPEED_SLIDER_STEP = 0.05f
private const val SPEED_MATCH_TOLERANCE = 0.005f

private val PresetSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

internal fun formatSpeed(speed: Float): String = formatSpeedPreset(speed) + "x"

// 按两位小数取整后去掉末尾的 0：1.00 显示为 1，1.50 显示为 1.5
private fun formatSpeedPreset(speed: Float): String =
    BigDecimal(speed.toDouble()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

internal fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
