package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 播放器顶栏：返回、标题与副标题、播放设置。
 *
 * 标题区单独包一层带 weight 的 Box：TooltipBox 不把 weight 的 parent data 交给 Row，
 * 直接给它 weight 时标题按内容宽度摆放，右侧按钮会紧跟在标题后面，而不是贴到右边。
 *
 * 标题单行、中间省略：视频文件名的区分信息（集数、分辨率）通常在末尾，
 * 末尾省略会把几十集截成同一个前缀。完整标题在长按提示里。
 * 按钮浮在视频上，规范要求带容器，否则对比度随画面变化没有保证。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerTopBar(
    title: String,
    episodeLabel: String?,
    isLocalPlayback: Boolean,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSettingsClick: (() -> Unit)? = null,
    /** 音轨与字幕。没有字幕、音轨也只有一条时为 null，不给入口。 */
    onTracksClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(TopScrim))
            // 横屏隐藏了系统栏，顶部只剩挖孔一侧需要让位；竖屏让出状态栏。
            // 渐变在 padding 之前，仍然满幅。
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            label = "返回",
            onClick = onBackClick,
            tooltipBelow = true,
            containerSize = IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow),
        )
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(title) } },
                state = rememberTooltipState(),
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (episodeLabel != null || isLocalPlayback) {
                        Row(
                            modifier = Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (episodeLabel != null) {
                                Text(
                                    text = episodeLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            if (isLocalPlayback) LocalPlaybackBadge()
                        }
                    }
                }
            }
        }
        if (onTracksClick != null) {
            PlayerIconButton(
                icon = Icons.Outlined.Subtitles,
                label = "音轨与字幕",
                onClick = onTracksClick,
                tooltipBelow = true,
            )
        }
        if (onSettingsClick != null) {
            PlayerIconButton(
                icon = Icons.Outlined.Tune,
                label = "播放设置",
                onClick = onSettingsClick,
                tooltipBelow = true,
            )
        }
    }
}

@Composable
private fun LocalPlaybackBadge() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = "本地文件",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 画面中央的播放控制：上一集、后退、播放/暂停、前进、下一集。
 *
 * 尺寸拉开层级：播放键最大，快进快退次之，换集最小，与使用频率一致。
 * 换集按钮在没有上一集或下一集时禁用而不是隐藏，整排不会跳动。
 * [showSideButtons] 为 false 时只留播放键：控件收起而仍在加载时，它独自留在画面中央承载加载指示。
 * 两侧按钮对称进出，播放键始终居中。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerCenterControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    isLandscape: Boolean,
    showSideButtons: Boolean,
    showEpisodeSkip: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizes = if (isLandscape) LandscapeCenterSizes else PortraitCenterSizes
    val motion = MaterialTheme.motionScheme

    @Composable
    fun RowScope.Side(content: @Composable () -> Unit) {
        AnimatedVisibility(
            visible = showSideButtons,
            enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f),
            exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f),
        ) {
            content()
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(sizes.spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showEpisodeSkip) {
            Side {
                PlayerIconButton(
                    icon = Icons.Filled.SkipPrevious,
                    label = "上一集",
                    onClick = onPrevious,
                    enabled = hasPrevious,
                    containerSize = sizes.skipContainer(),
                    iconSize = sizes.skipIcon,
                )
            }
        }
        Side {
            PlayerIconButton(
                icon = Icons.Filled.Replay10,
                label = "后退 ${SEEK_STEP_MILLIS / 1000} 秒",
                onClick = onSeekBackward,
                containerSize = sizes.seekContainer(),
                iconSize = sizes.seekIcon,
                shapes = sizes.seekShapes(),
            )
        }

        // 播放键两侧比其他按钮之间多留一段：它是这一组的主角，贴得和两侧一样近就分不出主次
        PlayPauseButton(
            isPlaying = isPlaying,
            isLoading = isLoading,
            size = sizes.playContainer(),
            iconSize = sizes.playIcon,
            squareCorner = sizes.playSquareCorner,
            pressedCorner = sizes.playPressedCorner,
            onClick = onPlayPause,
            modifier = Modifier.padding(horizontal = sizes.playSpacing - sizes.spacing),
        )

        Side {
            PlayerIconButton(
                icon = Icons.Filled.Forward10,
                label = "前进 ${SEEK_STEP_MILLIS / 1000} 秒",
                onClick = onSeekForward,
                containerSize = sizes.seekContainer(),
                iconSize = sizes.seekIcon,
                shapes = sizes.seekShapes(),
            )
        }
        if (showEpisodeSkip) {
            Side {
                PlayerIconButton(
                    icon = Icons.Filled.SkipNext,
                    label = "下一集",
                    onClick = onNext,
                    enabled = hasNext,
                    containerSize = sizes.skipContainer(),
                    iconSize = sizes.skipIcon,
                )
            }
        }
    }
}

/**
 * 播放键，加载时自己变成加载指示的容器，而不是在上面或旁边另叠一个指示器。
 *
 * 三种形态由同一个容器的形状、宽度、颜色连续过渡：暂停为正圆，播放为展宽的方角（与 toggle 按钮
 * 选中态的形变一致），加载时也是正圆、换成 primaryContainer，里面是 Expressive 的形变
 * LoadingIndicator。按下时圆角再收紧一级。形状与尺寸走 spatial 弹簧，颜色走 effects 弹簧，
 * 与规范对两类属性的分工一致。
 *
 * 不用 FilledIconToggleButton：它的形状只在 checked 与 pressed 间切换，接不进第三种形态，
 * 容器宽度也不能动画。加载中仍可点击，缓冲时暂停是合理操作。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    size: DpSize,
    iconSize: Dp,
    squareCorner: Dp,
    pressedCorner: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val roundCorner = size.height / 2
    val corner by animateDpAsState(
        targetValue = when {
            isLoading -> roundCorner
            pressed -> pressedCorner
            isPlaying -> squareCorner
            else -> roundCorner
        },
        animationSpec = motion.fastSpatialSpec(),
    )
    // 圆形态（暂停、加载）收成正圆，不是两头圆的胶囊；方角形态（播放中）才展开到 [size] 的宽度。
    // 按下只收紧圆角不动宽度，否则按一下左右抖
    val isRoundForm = isLoading || !isPlaying
    val width by animateDpAsState(
        targetValue = if (isRoundForm) size.height else size.width,
        animationSpec = motion.defaultSpatialSpec(),
    )
    val containerColor by animateColorAsState(
        targetValue = if (isLoading) colors.primaryContainer else colors.primary,
        animationSpec = motion.defaultEffectsSpec(),
    )
    val contentColor = if (isLoading) colors.onPrimaryContainer else colors.onPrimary
    val description = when {
        isLoading -> "加载中"
        isPlaying -> "暂停"
        else -> "播放"
    }

    // 外框固定为静止尺寸，容器在里面伸缩，两侧按钮不会随之挪动
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(corner),
            color = containerColor,
            contentColor = contentColor,
            interactionSource = interactionSource,
            modifier = Modifier
                .size(width, size.height)
                .semantics {
                    contentDescription = description
                    if (isLoading) liveRegion = LiveRegionMode.Polite
                },
        ) {
            AnimatedContent(
                targetState = isLoading,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.5f))
                        .togetherWith(fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.5f))
                },
                contentAlignment = Alignment.Center,
                label = "playLoading",
            ) { loading ->
                Box(contentAlignment = Alignment.Center) {
                    if (loading) {
                        LoadingIndicator(
                            color = contentColor,
                            modifier = Modifier.size(size.height * LOADING_INDICATOR_FRACTION),
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 中央按钮组在两种方向下的规格。播放键两边都是 Medium 高度，只比快进快退大一号，方角与按压圆角取自
 * icon button 规格的 16 与 12；竖屏的换集按钮再小一级，360dp 宽的屏幕上五个按钮仍排得下。
 */

// Medium 的 56 高，宽度取标准 56 与宽版 72 之间：方形显得局促，宽版又抢过了两侧
private val PlayContainerSize = DpSize(64.dp, 56.dp)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class CenterSizes(
    val spacing: Dp,
    /** 播放键与两侧按钮之间的距离，大于 [spacing]。 */
    val playSpacing: Dp,
    val playContainer: @Composable () -> DpSize,
    val playIcon: Dp,
    val playSquareCorner: Dp,
    val playPressedCorner: Dp,
    val seekContainer: @Composable () -> DpSize,
    val seekIcon: Dp,
    val seekShapes: @Composable () -> IconButtonShapes,
    val skipContainer: @Composable () -> DpSize,
    val skipIcon: Dp,
)

// 横屏原先整组大一级（播放键 Large、快进快退 Medium），在桌面窗口里压过画面，现在与竖屏只差换集按钮
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val LandscapeCenterSizes = CenterSizes(
    spacing = 16.dp,
    playSpacing = 28.dp,
    playContainer = { PlayContainerSize },
    playIcon = IconButtonDefaults.mediumIconSize,
    playSquareCorner = 16.dp,
    playPressedCorner = 12.dp,
    seekContainer = { IconButtonDefaults.smallContainerSize() },
    seekIcon = IconButtonDefaults.smallIconSize,
    seekShapes = { IconButtonDefaults.shapes() },
    skipContainer = { IconButtonDefaults.smallContainerSize() },
    skipIcon = IconButtonDefaults.smallIconSize,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val PortraitCenterSizes = CenterSizes(
    spacing = 16.dp,
    playSpacing = 24.dp,
    playContainer = { PlayContainerSize },
    playIcon = IconButtonDefaults.mediumIconSize,
    playSquareCorner = 16.dp,
    playPressedCorner = 12.dp,
    seekContainer = { IconButtonDefaults.smallContainerSize() },
    seekIcon = IconButtonDefaults.smallIconSize,
    seekShapes = { IconButtonDefaults.shapes() },
    skipContainer = { IconButtonDefaults.extraSmallContainerSize() },
    skipIcon = IconButtonDefaults.extraSmallIconSize,
)

/**
 * 播放器底栏：进度条在上，时间与选集、倍速、全屏在下。
 *
 * 系统手势区的处理分两层：整栏让出 safeDrawing；进度条额外让出左右两侧的系统手势区，
 * 那里的横向拖动会被系统返回手势先拿走。按钮只响应点击，不受手势区影响，不必让。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerBottomBar(
    isLandscape: Boolean,
    isFullscreen: Boolean,
    isPlaying: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    playbackSpeed: Float?,
    showEpisodes: Boolean,
    onSeek: (Long) -> Unit,
    isSpeedPopupOpen: Boolean,
    onSpeedPopupOpenChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEpisodesClick: () -> Unit,
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
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (isLandscape) 8.dp else 12.dp),
    ) {
        PlayerSeekBar(
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            bufferedPositionMillis = bufferedPositionMillis,
            onSeek = onSeek,
            isPlaying = isPlaying,
            onScrub = { target ->
                val wasScrubbing = scrubPositionMillis != null
                scrubPositionMillis = target
                if (wasScrubbing != (target != null)) onScrubbingChange(target != null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal)),
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(shownPosition),
                    style = TimeTextStyle(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = " / ${formatTime(durationMillis)}",
                    style = TimeTextStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (playbackSpeed != null) {
                // 浮层锚在这个 Box 上，出现在按钮正上方
                Box {
                    PlayerChipButton(
                        text = formatSpeed(playbackSpeed),
                        onClick = { onSpeedPopupOpenChange(!isSpeedPopupOpen) },
                        modifier = Modifier.semantics { contentDescription = "倍速 ${formatSpeed(playbackSpeed)}" },
                    )
                    if (isSpeedPopupOpen) {
                        SpeedPopup(
                            playbackSpeed = playbackSpeed,
                            onSpeedChange = onSpeedChange,
                            onDismiss = { onSpeedPopupOpenChange(false) },
                        )
                    }
                }
            }
            if (showEpisodes) {
                PlayerChipButton(text = "选集", icon = Icons.Outlined.VideoLibrary, onClick = onEpisodesClick)
            }
            PlayerIconButton(
                icon = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                label = if (isFullscreen) "退出全屏" else "全屏",
                onClick = onToggleFullscreen,
            )
        }
    }
}

/**
 * 倍速的浮动滑块：贴在「1x」按钮正上方，只有当前值与一条滑块，点数值回到 1x。
 * 不用面板：调倍速时要看着画面，横屏的侧边面板与竖屏的底部面板都会盖住一大块。常用预设在右上角的播放设置里。
 */
@Composable
private fun SpeedPopup(playbackSpeed: Float, onSpeedChange: (Float) -> Unit, onDismiss: () -> Unit) {
    val gap = with(LocalDensity.current) { SpeedPopupGap.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap) { AboveAnchorPositionProvider(gap) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.width(SpeedPopupWidth).padding(start = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatSpeed(playbackSpeed),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClickLabel = "恢复 1x") { onSpeedChange(1f) }
                        .widthIn(min = 56.dp)
                        .padding(vertical = 12.dp),
                )
                Box(Modifier.weight(1f)) {
                    SpeedSlider(playbackSpeed, onSpeedChange)
                }
            }
        }
    }
}

/** 浮层放在锚点正上方居中，靠窗口边时往里收，不超出窗口。 */
private class AboveAnchorPositionProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

private val SpeedPopupWidth = 280.dp
private val SpeedPopupGap = 8.dp

/** 时间码用等宽数字：比例数字随秒数跳动，右侧按钮会跟着左右抖。 */
@Composable
private fun TimeTextStyle(): TextStyle =
    MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")

/**
 * 底栏的文字按钮。浮在视频上不能用无容器的 TextButton，改用 XS 高度的 tonal 按钮，
 * 与旁边的图标按钮同为半透明容器，按压时有形变。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = playerContainerColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = ButtonDefaults.ExtraSmallContentPadding,
        modifier = modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.ExtraSmallIconSize))
            Spacer(Modifier.width(ButtonDefaults.ExtraSmallIconSpacing))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * 进度条：细轨道，靠近时变粗；播放中已播放段是一条流动的细波浪，暂停、拖动、悬停时拉平。
 *
 * 原先是 Expressive 标准滑块（16dp 高的轨道、竖条手柄、不透明的 secondaryContainer 底色），
 * 压在画面上又粗又闷。这里未播放段与缓冲段用半透明的前景色，透出画面；波浪取自 Android 13 起
 * 系统媒体控件的进度条，播放与暂停一眼可辨。
 *
 * 不用 Material 的 Slider：换手柄与轨道的重载两端没有交集（Android 的 1.5.0-alpha28 与桌面的
 * 1.12.0-alpha03 各缺一半），手柄固定是 44dp 高的竖条，配不了细轨道。手势自己接：按下即跳到该处，
 * 拖动期间只预览时间，松手才 seek，网络流每次 seek 都要重开 range 请求，跟手 seek 会连续打断缓冲。
 * 读屏的进度与「设置进度」动作也自己补上。桌面上鼠标悬停在轨道上时，指针上方显示该处的时间。
 */
@Composable
internal fun PlayerSeekBar(
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    onScrub: (Long?) -> Unit = {},
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    // 松手到新位置回报之间有一段延迟，这段时间里滑块停在目标处，不回跳到旧位置
    var pendingSeekMillis by remember { mutableStateOf<Long?>(null) }

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
    val isEngaged = dragFraction != null || hoverFraction != null
    val scheme = MaterialTheme.colorScheme
    val trackColors = SeekTrackColors(
        active = scheme.primary,
        inactive = scheme.onSurface.copy(alpha = INACTIVE_TRACK_ALPHA),
        buffered = scheme.onSurface.copy(alpha = BUFFERED_TRACK_ALPHA),
    )
    val motion = MaterialTheme.motionScheme
    val thickness by animateDpAsState(if (isEngaged) SeekTrackEngagedThickness else SeekTrackThickness, motion.fastSpatialSpec())
    val thumbRadius by animateDpAsState(if (dragFraction != null) SeekThumbDraggingRadius else SeekThumbRadius, motion.fastSpatialSpec())
    // 波幅按 0 到 1 渐变：暂停、拖动时慢慢拉平，恢复播放时再慢慢起伏，不会一下子弹直
    val waveAmount by animateFloatAsState(if (isPlaying && !isEngaged && enabled) 1f else 0f, motion.slowEffectsSpec())
    // 相位只在绘制阶段读：它每帧都变，在组合里读会让整条进度条每帧重组
    val wavePhase = rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(WAVE_PERIOD_MILLIS, easing = LinearEasing)),
    )
    val positionText = formatTime((fraction * durationMillis).toLong())
    val durationText = formatTime(durationMillis)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)

    fun commitSeek(target: Float) {
        val millis = (target * durationMillis).toLong()
        pendingSeekMillis = millis
        currentOnSeek(millis)
    }

    BoxWithConstraints(modifier = modifier.height(SeekBarHeight)) {
        Box(
            Modifier
                .fillMaxSize()
                // 按下即跳到该处并开始拖动，松手才真正 seek
                .pointerInput(enabled, durationMillis) {
                    if (!enabled) return@pointerInput
                    val inset = SeekThumbDraggingRadius.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun follow(x: Float) {
                            val target = seekFractionAt(x, size.width.toFloat(), inset)
                            dragFraction = target
                            currentOnScrub((target * durationMillis).toLong())
                        }
                        follow(down.position.x)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            follow(change.position.x)
                            change.consume()
                        }
                        dragFraction?.let(::commitSeek)
                        dragFraction = null
                        currentOnScrub(null)
                    }
                }
                // 只有鼠标会悬停；触屏的移动都是拖动，交给上面处理
                .pointerInput(enabled) {
                    val inset = SeekThumbDraggingRadius.toPx()
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            hoverFraction = when {
                                !enabled || change.type != PointerType.Mouse || event.type == PointerEventType.Exit -> null
                                else -> seekFractionAt(change.position.x, size.width.toFloat(), inset)
                            }
                        }
                    }
                }
                .drawBehind {
                    drawSeekTrack(
                        fraction = fraction,
                        bufferedFraction = bufferedFraction,
                        colors = trackColors,
                        thickness = thickness.toPx(),
                        thumbRadius = thumbRadius.toPx(),
                        waveAmount = waveAmount,
                        wavePhase = wavePhase.value,
                        inset = SeekThumbDraggingRadius.toPx(),
                    )
                }
                .semantics {
                    contentDescription = "播放进度"
                    stateDescription = "$positionText / $durationText"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    if (enabled) {
                        setProgress { target ->
                            commitSeek(target.coerceIn(0f, 1f))
                            true
                        }
                    }
                },
        )

        // 拖动或悬停时的时间气泡，贴在该处正上方；零尺寸布局，不挤占进度条的高度
        (dragFraction ?: hoverFraction)?.let { shown ->
            val density = LocalDensity.current
            val inset = with(density) { SeekThumbDraggingRadius.toPx() }
            val trackWidthPx = constraints.maxWidth - 2 * inset
            val anchorPx = inset + shown * trackWidthPx
            val gapPx = with(density) { 2.dp.roundToPx() }
            val dragging = dragFraction != null
            Surface(
                shape = MaterialTheme.shapes.small,
                // 拖动时是要跳过去的位置，用主题色强调；悬停只是看看，用中性的反色
                color = if (dragging) scheme.primary else scheme.inverseSurface,
                contentColor = if (dragging) scheme.onPrimary else scheme.inverseOnSurface,
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
                    val x = (anchorPx - placeable.width / 2f).roundToInt().coerceIn(0, maxX)
                    layout(0, 0) { placeable.place(x, -placeable.height - gapPx) }
                },
            ) {
                Text(
                    text = formatTime((shown * durationMillis).toLong()),
                    style = TimeTextStyle(),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** 指针横坐标对应的进度。两端各让出手柄放大后的半径，拖到头时手柄不出界。 */
private fun seekFractionAt(x: Float, width: Float, inset: Float): Float =
    ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)

private class SeekTrackColors(val active: Color, val inactive: Color, val buffered: Color)

/**
 * 轨道从左到右：已播放段（播放中是波浪）、手柄、缓冲段、未播放段。[inset] 是两端给手柄留的边，
 * 与 [seekFractionAt] 的换算一致，手柄画在哪、点下去就是哪。
 */
private fun DrawScope.drawSeekTrack(
    fraction: Float,
    bufferedFraction: Float,
    colors: SeekTrackColors,
    thickness: Float,
    thumbRadius: Float,
    waveAmount: Float,
    wavePhase: Float,
    inset: Float,
) {
    val centerY = size.height / 2
    val start = inset
    val end = size.width - inset
    val thumbX = start + (end - start) * fraction
    val bufferedX = start + (end - start) * bufferedFraction

    drawLine(colors.inactive, Offset(thumbX, centerY), Offset(end, centerY), thickness, StrokeCap.Round)
    if (bufferedX > thumbX) {
        drawLine(colors.buffered, Offset(thumbX, centerY), Offset(bufferedX, centerY), thickness, StrokeCap.Round)
    }

    if (thumbX > start) {
        val amplitude = WaveAmplitude.toPx() * waveAmount
        if (amplitude < MIN_VISIBLE_AMPLITUDE_PX) {
            drawLine(colors.active, Offset(start, centerY), Offset(thumbX, centerY), thickness, StrokeCap.Round)
        } else {
            val wavelength = WaveLength.toPx()
            // 起点与手柄前各用一个波长把波幅收到 0：两端落在中线上，与手柄、轨道起点接得上
            fun yAt(x: Float): Float {
                val taper = minOf(1f, (x - start) / wavelength, (thumbX - x) / wavelength).coerceAtLeast(0f)
                return centerY + amplitude * taper * sin(2 * PI.toFloat() * (x / wavelength - wavePhase))
            }
            val path = Path().apply {
                moveTo(start, yAt(start))
                var x = start
                while (x < thumbX) {
                    x = minOf(x + WAVE_STEP_PX, thumbX)
                    lineTo(x, yAt(x))
                }
            }
            drawPath(path, colors.active, style = Stroke(width = thickness, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    drawCircle(colors.active, thumbRadius, Offset(thumbX, centerY))
}

/** 浮在视频上的控件容器色。半透明：既保证图标对比度，又不整块挡住画面。 */
@Composable
internal fun playerContainerColor(): Color =
    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = CONTAINER_ALPHA)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun playerIconButtonColors(): IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(
    containerColor = playerContainerColor(),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = playerContainerColor().copy(alpha = CONTAINER_ALPHA / 2),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA),
)

/**
 * 带长按提示与半透明容器的图标按钮。
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
    containerSize: DpSize? = null,
    iconSize: Dp = 24.dp,
    shapes: IconButtonShapes = IconButtonDefaults.shapes(),
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
        FilledTonalIconButton(
            onClick = onClick,
            shapes = shapes,
            colors = playerIconButtonColors(),
            enabled = enabled,
            modifier = if (containerSize != null) Modifier.size(containerSize) else Modifier,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

private val TopScrim = listOf(
    Color.Black.copy(alpha = 0.7f),
    Color.Black.copy(alpha = 0.3f),
    Color.Transparent,
)
private val BottomScrim = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.45f),
    Color.Black.copy(alpha = 0.8f),
)

// 进度条的触控高度与轨道几何。触控区比轨道高得多：细轨道要能点中
private val SeekBarHeight = 32.dp
private val SeekTrackThickness = 4.dp
private val SeekTrackEngagedThickness = 8.dp
private val SeekThumbRadius = 6.dp
private val SeekThumbDraggingRadius = 9.dp

// 波浪：波幅、波长与流过一个波长的时间，取 Android 系统媒体控件进度条的量级，细而慢，不抢画面
private val WaveAmplitude = 3.dp
private val WaveLength = 24.dp
private const val WAVE_PERIOD_MILLIS = 1_600
private const val WAVE_STEP_PX = 2f
private const val MIN_VISIBLE_AMPLITUDE_PX = 0.5f
private const val INACTIVE_TRACK_ALPHA = 0.28f
private const val BUFFERED_TRACK_ALPHA = 0.55f

private const val CONTAINER_ALPHA = 0.72f
private const val LOADING_INDICATOR_FRACTION = 0.75f
private const val DISABLED_CONTENT_ALPHA = 0.38f
private const val SEEK_SETTLE_TOLERANCE_MILLIS = 1_500L
private const val SEEK_SETTLE_TIMEOUT_MILLIS = 1_500L

// 公开给 app 模块里的控件测试用
const val SEEK_STEP_MILLIS = 10_000L
internal const val MIN_SPEED = 0.5f
internal const val MAX_SPEED = 3.5f
const val LONG_PRESS_BOOST_SPEED = 2.0f

internal fun formatSpeed(speed: Float): String = formatSpeedPreset(speed) + "x"

// 按两位小数取整后去掉末尾的 0：1.00 显示为 1，1.50 显示为 1.5
internal fun formatSpeedPreset(speed: Float): String =
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
