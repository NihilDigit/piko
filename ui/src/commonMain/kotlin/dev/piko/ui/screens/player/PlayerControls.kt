package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * 三种形态由同一个容器的形状、宽度、颜色连续过渡：暂停为圆形，播放为方角（与 toggle 按钮
 * 选中态的形变一致），加载时收成正圆、换成 primaryContainer，里面是 Expressive 的形变
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
    // 宽版容器在加载时收成正圆，指示器的形变图形在正圆里才居中匀称
    val width by animateDpAsState(
        targetValue = if (isLoading) size.height else size.width,
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
 * 进度条：Expressive 标准滑块加一段缓冲轨。
 *
 * 轨道按 SliderTokens 的几何自绘（16dp 高、4dp 手柄、手柄两侧 6dp 间隙、末端停止点），
 * 在未播放段上叠缓冲段。拖动期间只预览时间，松手才 seek：
 * 网络流每次 seek 都要重开 range 请求，跟手 seek 会连续打断缓冲。
 *
 * 自绘轨道画在滑块背后，滑块自带的轨道全部设成透明，只留手柄。能传入自定义 track 的重载
 * 两端没有交集：Android 的 1.5.0-alpha28 隐藏了按 value 传值且可换 track 的那几个，连同不带回调的
 * SliderState 重载与 SliderState.onValueChange 属性；桌面的 CMP 1.12.0-alpha03 又没有
 * alpha28 新加的、带回调的 SliderState 重载。两端都在的只有最基本的按 value 传值的重载，
 * 它在 alpha28 标了废弃但仍可用。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("DEPRECATION")
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
    val trackColors = SeekTrackColors(
        active = MaterialTheme.colorScheme.primary,
        inactive = MaterialTheme.colorScheme.secondaryContainer,
        buffered = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = BUFFERED_ALPHA),
        stop = MaterialTheme.colorScheme.primary,
    )
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = Color.Transparent,
        inactiveTrackColor = Color.Transparent,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledActiveTrackColor = Color.Transparent,
        disabledInactiveTrackColor = Color.Transparent,
        disabledActiveTickColor = Color.Transparent,
        disabledInactiveTickColor = Color.Transparent,
    )
    val positionText = formatTime((fraction * durationMillis).toLong())
    val durationText = formatTime(durationMillis)

    BoxWithConstraints(modifier = modifier) {
        Slider(
            value = fraction,
            onValueChange = {
                dragFraction = it
                onScrub((it * durationMillis).toLong())
            },
            onValueChangeFinished = {
                dragFraction?.let { target ->
                    val millis = (target * durationMillis).toLong()
                    pendingSeekMillis = millis
                    onSeek(millis)
                }
                dragFraction = null
                onScrub(null)
            },
            enabled = enabled,
            colors = colors,
            modifier = Modifier
                .fillMaxWidth()
                // 滑块把轨道放在两端各让出半个手柄宽、垂直居中的位置，这里按同样的几何画
                .drawBehind {
                    val handleInset = SeekHandleWidth.toPx() / 2
                    val verticalInset = (size.height - SeekTrackHeight.toPx()) / 2
                    inset(handleInset, verticalInset, handleInset, verticalInset) {
                        drawSeekTrack(fraction, bufferedFraction, trackColors)
                    }
                }
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
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val maxX = (constraints.maxWidth - placeable.width).coerceAtLeast(0)
                    val x = (handleCenterPx - placeable.width / 2f).roundToInt().coerceIn(0, maxX)
                    layout(0, 0) { placeable.place(x, -placeable.height - gapPx) }
                },
            ) {
                Text(
                    text = formatTime((dragging * durationMillis).toLong()),
                    style = TimeTextStyle(),
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

// 以下几何取自 SliderTokens（XS 规格）：轨道高、手柄宽、手柄与轨道间隙、停止点直径；
// 内侧圆角 2dp 与 SliderDefaults 一致
private val SeekTrackHeight = 16.dp
private val SeekHandleWidth = 4.dp
private val SeekThumbTrackGap = 6.dp
private val SeekStopIndicatorSize = 4.dp
private val SeekTrackInsideCorner = 2.dp

private const val CONTAINER_ALPHA = 0.72f
private const val LOADING_INDICATOR_FRACTION = 0.75f
private const val DISABLED_CONTENT_ALPHA = 0.38f
private const val BUFFERED_ALPHA = 0.38f
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
