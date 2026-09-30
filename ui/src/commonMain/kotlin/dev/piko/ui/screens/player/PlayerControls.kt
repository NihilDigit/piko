package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.ui.platform.LocalWindowInfo
import dev.piko.ui.components.ActionDropdownMenu
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.LocalFramelessWindow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.platform.windowDragArea
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
    /** 作用于正在播的这个文件的操作（分享、下载），排在播放设置之前。 */
    fileActions: List<SheetAction> = emptyList(),
    onMenuVisibilityChange: (Boolean) -> Unit = {},
) {
    // 桌面端独立的播放窗口没有标题栏：关窗按钮放在右上角，与其他按钮同款，左边的返回键与它重复，不再显示
    val framelessWindow = LocalFramelessWindow.current
    val compactActions = framelessWindow == null && with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() < 600.dp
    }
    var moreOpen by remember { mutableStateOf(false) }
    fun setMoreOpen(open: Boolean) { moreOpen = open; onMenuVisibilityChange(open) }
    LaunchedEffect(compactActions) { if (!compactActions && moreOpen) setMoreOpen(false) }
    DisposableEffect(Unit) { onDispose { onMenuVisibilityChange(false) } }
    val actions = fileActions + listOfNotNull(
        onTracksClick?.let { SheetAction(Icons.Outlined.Subtitles, "音轨与字幕", it) },
        onSettingsClick?.let { SheetAction(Icons.Outlined.Tune, "播放设置", it) },
    )
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
        if (framelessWindow == null) {
            PlayerIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "返回",
                onClick = onBackClick,
                tooltipBelow = true,
                containerSize = IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow),
            )
        }
        // 标题这一块兼做拖动窗口的地方（仅限没有标题栏的桌面窗口）
        Box(Modifier.weight(1f).windowDragArea().padding(horizontal = 12.dp)) {
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
        if (compactActions && actions.isNotEmpty()) {
            Box {
                PlayerIconButton(Icons.Outlined.MoreVert, "更多操作", { setMoreOpen(true) }, tooltipBelow = true)
                ActionDropdownMenu(moreOpen, actions) { setMoreOpen(false) }
            }
        } else {
            fileActions.forEach { action ->
                PlayerIconButton(icon = action.icon, label = action.label, onClick = action.onClick, tooltipBelow = true)
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
        if (framelessWindow != null) {
            val onTop = framelessWindow.isAlwaysOnTop
            PlayerIconButton(
                icon = if (onTop) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                label = if (onTop) "取消置顶" else "置顶",
                onClick = { framelessWindow.setAlwaysOnTop(!onTop) },
                tooltipBelow = true,
            )
            PlayerIconButton(
                icon = Icons.Filled.Close,
                label = "关闭",
                onClick = framelessWindow::close,
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
 * 画面中央只有播放键。原先两侧还有快进快退与换集：进退触屏靠双击两侧、鼠标与键盘靠方向键和进度条，
 * 按钮与手势重复，又把画面中央占掉一大片；换集挪进了底栏。
 * 播放键一直在这里，控件收起时它若还在（加载中、有读数）就独自承载加载指示或读数。
 */
@Composable
internal fun PlayerCenterControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    indicator: CenterIndicator?,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayPauseButton(
        isPlaying = isPlaying,
        isLoading = isLoading,
        indicator = indicator,
        size = PlayContainerSize,
        iconSize = IconButtonDefaults.mediumIconSize,
        squareCorner = 16.dp,
        pressedCorner = 12.dp,
        onClick = onPlayPause,
        modifier = modifier,
    )
}

/**
 * 播放键上临时显示的读数：倍速、旋转、音量、亮度、进退这类「正在改什么、刚改了什么」的反馈。
 * 播放键这时变形成横向的胶囊把它托住，过一会儿再变回去。[description] 给读屏用。
 * [progress] 不为 null 时胶囊里多一条进度，音量、亮度与拖动进度用它。
 */
internal data class CenterIndicator(
    val icon: ImageVector,
    val text: String,
    val description: String,
    val progress: Float? = null,
)

// 播放键里放的是哪一种内容
private sealed interface CenterFace {
    data object Icon : CenterFace
    data object Loading : CenterFace
    data class Readout(val indicator: CenterIndicator) : CenterFace
}

// 换内容才交叉淡入。读数只看图标与有没有进度条：拖音量时数值每帧都在变，按文字算的话一路都在淡入淡出，
// 数字糊成一片；同一种读数里数字就地换，胶囊的宽度随之伸缩
private val CenterFace.contentKey: Any
    get() = when (this) {
        is CenterFace.Readout -> indicator.icon to (indicator.progress != null)
        else -> this
    }

/**
 * 播放键，加载时自己变成加载指示的容器，有临时读数时变成托住读数的胶囊，而不是在上面或旁边另叠一层。
 *
 * 四种形态由同一个容器的形状、宽度、颜色连续过渡：暂停为正圆，播放为展宽的方角（与 toggle 按钮
 * 选中态的形变一致），加载时也是正圆、换成 primaryContainer，里面是 Expressive 的形变
 * LoadingIndicator；显示读数时拉成两头圆的胶囊、换成 tertiary。按下时圆角再收紧一级。
 * 形状与尺寸走 spatial 弹簧，颜色走 effects 弹簧，与规范对两类属性的分工一致。
 *
 * 读数优先于加载：长按倍速时恰好卡了一下，手指还按着，该看到的仍是倍速。
 *
 * 不用 FilledIconToggleButton：它的形状只在 checked 与 pressed 间切换，接不进另外两种形态，
 * 容器宽度也不能动画。不用 androidx.graphics.shapes 的 Morph：几种形态都是圆角矩形，只差宽度与圆角，
 * RoundedCornerShape 的尺寸插值就够了，也不必为此加依赖。加载中仍可点击，缓冲时暂停是合理操作。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    isLoading: Boolean,
    indicator: CenterIndicator?,
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
    val face = when {
        indicator != null -> CenterFace.Readout(indicator)
        isLoading -> CenterFace.Loading
        else -> CenterFace.Icon
    }

    val roundCorner = size.height / 2
    val corner by animateDpAsState(
        targetValue = when {
            face != CenterFace.Icon -> roundCorner
            pressed -> pressedCorner
            isPlaying -> squareCorner
            else -> roundCorner
        },
        animationSpec = motion.fastSpatialSpec(),
    )
    // 读数形态的宽度照内容量出来，胶囊恰好托住它：固定宽度的话「90°」两边空一大截，「01:23:45」又放不下
    val readoutStyle = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum")
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val readoutWidth = (face as? CenterFace.Readout)?.indicator?.let { shown ->
        val textWidth = with(density) { textMeasurer.measure(shown.text, readoutStyle).size.width.toDp() }
        val bar = if (shown.progress != null) READOUT_BAR_WIDTH + READOUT_GAP else 0.dp
        READOUT_PADDING * 2 + READOUT_ICON_SIZE + READOUT_GAP + bar + textWidth
    }
    // 圆形态（暂停、加载）收成正圆，不是两头圆的胶囊；方角形态（播放中）才展开到 [size] 的宽度，
    // 读数形态再照内容伸长。按下只收紧圆角不动宽度，否则按一下左右抖
    val width by animateDpAsState(
        targetValue = when {
            readoutWidth != null -> maxOf(size.width, readoutWidth)
            face == CenterFace.Loading || !isPlaying -> size.height
            else -> size.width
        },
        animationSpec = motion.defaultSpatialSpec(),
    )
    val containerColor by animateColorAsState(
        targetValue = when (face) {
            is CenterFace.Readout -> colors.tertiary
            CenterFace.Loading -> colors.primaryContainer
            CenterFace.Icon -> colors.primary
        },
        animationSpec = motion.defaultEffectsSpec(),
    )
    val contentColor = when (face) {
        is CenterFace.Readout -> colors.onTertiary
        CenterFace.Loading -> colors.onPrimaryContainer
        CenterFace.Icon -> colors.onPrimary
    }
    // 按钮的标签只说点下去做什么：读数形态下点击照样是播放或暂停，标签换成读数的话，读屏念完读数却不知道这一下做了什么
    val actionLabel = when {
        isLoading -> "加载中"
        isPlaying -> "暂停"
        else -> "播放"
    }

    // 外框固定为静止尺寸，容器在里面伸缩，两侧按钮不会随之挪动；读数形态比外框宽，用 requiredSize 向两侧溢出
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // 读数另占一个节点，以礼貌的 live region 播报，不并进按钮。垫在按钮底下、不接指针，只供读屏；
        // 节点常驻、只换描述，播报跟着描述的变化走
        Box(
            Modifier
                .matchParentSize()
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    indicator?.let { contentDescription = it.description }
                },
        )
        Surface(
            onClick = onClick,
            // 弹簧会冲过头。圆角从胶囊收回方角时只会冲到略小于 12dp，离 0 还远，仍兜一道：
            // 负的圆角让 CornerBasedShape 当场抛异常，见 ConnectedShapes.kt
            shape = RoundedCornerShape(corner.coerceAtLeast(0.dp)),
            color = containerColor,
            contentColor = contentColor,
            interactionSource = interactionSource,
            modifier = Modifier
                .requiredSize(width.coerceAtLeast(0.dp), size.height)
                .handCursor()
                .semantics { contentDescription = actionLabel },
        ) {
            AnimatedContent(
                targetState = face,
                transitionSpec = {
                    (fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.5f))
                        .togetherWith(fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.5f))
                },
                contentAlignment = Alignment.Center,
                contentKey = { it.contentKey },
                label = "playFace",
            ) { shown ->
                Box(contentAlignment = Alignment.Center) {
                    when (shown) {
                        CenterFace.Loading -> LoadingIndicator(
                            color = contentColor,
                            modifier = Modifier.size(size.height * LOADING_INDICATOR_FRACTION),
                        )
                        CenterFace.Icon -> Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                        )
                        is CenterFace.Readout -> {
                            // contentKey 不看文字，同一种读数换了数值时 shown 仍是旧的一份，数值取眼前的
                            val live = (face as? CenterFace.Readout)?.indicator
                                ?.takeIf { CenterFace.Readout(it).contentKey == shown.contentKey }
                                ?: shown.indicator
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(READOUT_GAP),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(live.icon, contentDescription = null, modifier = Modifier.size(READOUT_ICON_SIZE))
                                live.progress?.let { progress ->
                                    LinearProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        color = contentColor,
                                        trackColor = contentColor.copy(alpha = 0.3f),
                                        gapSize = 0.dp,
                                        drawStopIndicator = {},
                                        modifier = Modifier.width(READOUT_BAR_WIDTH),
                                    )
                                }
                                Text(text = live.text, style = readoutStyle, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

// 读数胶囊的内部排布：两端留白、图标、间隙、进度条
private val READOUT_PADDING = 20.dp
private val READOUT_ICON_SIZE = 20.dp
private val READOUT_GAP = 8.dp
private val READOUT_BAR_WIDTH = 120.dp

// Medium 的 56 高，宽度取标准 56 与宽版 72 之间：方形显得局促，宽版又抢过了两侧。方角与按压圆角取自
// icon button 规格的 16 与 12
private val PlayContainerSize = DpSize(64.dp, 56.dp)

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
    thumbOnHoverOnly: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillis: Long,
    playbackSpeed: Float?,
    showEpisodes: Boolean,
    /** 上一集、下一集两个快捷键。窄窗口不给，换集走选集面板。 */
    showEpisodeSkip: Boolean,
    /** 窄窗口：选集只留图标，见下。 */
    compactWidth: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    isSpeedPopupOpen: Boolean,
    onSpeedPopupOpenChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onEpisodesClick: () -> Unit,
    /** 顺时针转 90 度；后端不能旋转时为 null，不给按钮。 */
    onRotate: (() -> Unit)?,
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
            thumbOnHoverOnly = thumbOnHoverOnly,
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
                // 换集在时间前面，照多数播放器底栏的排法。没有上一集或下一集时禁用而不是隐藏，时间不会左右跳
                if (showEpisodeSkip) {
                    PlayerIconButton(icon = Icons.Filled.SkipPrevious, label = "上一集", onClick = onPrevious, enabled = hasPrevious)
                    PlayerIconButton(icon = Icons.Filled.SkipNext, label = "下一集", onClick = onNext, enabled = hasNext)
                    Spacer(Modifier.width(8.dp))
                }
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
                // 窄窗口只留图标，名字在提示里：带字的这一格约 80dp，省下的地方让旋转按钮常驻，
                // 360dp 的手机竖屏里时间、倍速、选集、旋转、全屏才排得下
                if (compactWidth) {
                    PlayerIconButton(icon = Icons.Outlined.VideoLibrary, label = "选集", onClick = onEpisodesClick)
                } else {
                    PlayerChipButton(text = "选集", icon = Icons.Outlined.VideoLibrary, onClick = onEpisodesClick)
                }
            }
            // 一次转 90 度，与 R 键相同。原先在播放设置里列四个角度，要转画面得先开面板，
            // 桌面上转了窗口还跟着对调，这一步该是顺手就点的
            if (onRotate != null) {
                PlayerIconButton(icon = Icons.Filled.Rotate90DegreesCw, label = "顺时针旋转 90 度", onClick = onRotate)
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
        modifier = modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight).handCursor(),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.ExtraSmallIconSize))
            Spacer(Modifier.width(ButtonDefaults.ExtraSmallIconSpacing))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * 进度条：细轨道，靠近时变粗，未播放段与缓冲段用半透明的前景色，透出画面。
 *
 * 原先是 M3 的 XS 滑块（16dp 高的轨道、竖条手柄、不透明的 secondaryContainer 底色），
 * 压在画面上又粗又闷。细轨道不在 M3 滑块的规格里，是视频播放器的通行做法。已播放段不用波浪：
 * M3 的波浪是进度指示器表示「过程在进行」的外观，滑块没有这一项；克制版也试过，在画面上仍然显得多余。
 * [thumbOnHoverOnly] 时手柄平时隐藏、悬停才出现（鼠标）；触屏没有悬停，手柄一直显示，否则看不出能拖。
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
    thumbOnHoverOnly: Boolean = false,
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
    val focusInteraction = remember { MutableInteractionSource() }
    val isFocused by focusInteraction.collectIsFocusedAsState()
    val isEngaged = dragFraction != null || hoverFraction != null || isFocused
    val scheme = MaterialTheme.colorScheme
    val trackColors = SeekTrackColors(
        active = scheme.primary,
        inactive = scheme.onSurface.copy(alpha = INACTIVE_TRACK_ALPHA),
        buffered = scheme.onSurface.copy(alpha = BUFFERED_TRACK_ALPHA),
    )
    val motion = MaterialTheme.motionScheme
    val thickness by animateDpAsState(if (isEngaged) SeekTrackEngagedThickness else SeekTrackThickness, motion.fastSpatialSpec())
    val thumbRadius by animateDpAsState(
        when {
            dragFraction != null -> SeekThumbDraggingRadius
            // 键盘停在进度条上时手柄要露出来，否则看不出焦点在这里、方向键会动它
            thumbOnHoverOnly && hoverFraction == null && !isFocused -> 0.dp
            else -> SeekThumbRadius
        },
        motion.fastSpatialSpec(),
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

    // 方向键一步 SEEK_STEP_MILLIS，与播放器其余地方的进退同一步长，每按一下即提交，相当于拖动后松手。
    // 连按时从上一步的目标接着算，位置回报还没跟上也不会原地打转
    fun stepBy(deltaMillis: Long): Boolean {
        if (!enabled) return false
        val base = pendingSeekMillis ?: positionMillis
        commitSeek(fractionOf(base + deltaMillis))
        return true
    }

    val focusRingColor = scheme.secondary

    BoxWithConstraints(modifier = modifier.height(SeekBarHeight)) {
        Box(
            Modifier
                .fillMaxWidth()
                // 布局仍占 SeekBarHeight，接触摸的这一层撑到 48dp、上下各溢出一截，轨道画在正中不挪位置。
                // 不能指望 Compose 的最小触控区自动外扩：它只在别处都没接住时才生效，而进度条底下铺着整屏的手势层
                .requiredHeight(SeekTouchHeight)
                .handCursor(enabled)
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
                        inset = SeekThumbDraggingRadius.toPx(),
                        focusRing = if (isFocused) focusRingColor else null,
                    )
                }
                // M3 滑块的键盘约定：Tab 停到手柄上，方向键调值。在播放器与信息流里，上级根节点的
                // onPreviewKeyEvent 先接住左右方向键（同为 SEEK_STEP_MILLIS 一步，另有按住快进），这里收不到；
                // 让上级赢，是因为那边的进退有读数反馈与按住加速，焦点停在进度条上时行为也不变。
                // 这里的处理留给没有上级快捷键的场合
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionRight -> stepBy(SEEK_STEP_MILLIS)
                        Key.DirectionLeft -> stepBy(-SEEK_STEP_MILLIS)
                        else -> false
                    }
                }
                .focusable(enabled, focusInteraction)
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
 * 轨道从左到右：已播放段、手柄、缓冲段、未播放段。[inset] 是两端给手柄留的边，
 * 与 [seekFractionAt] 的换算一致，手柄画在哪、点下去就是哪。
 */
private fun DrawScope.drawSeekTrack(
    fraction: Float,
    bufferedFraction: Float,
    colors: SeekTrackColors,
    thickness: Float,
    thumbRadius: Float,
    inset: Float,
    focusRing: Color?,
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
        drawLine(colors.active, Offset(start, centerY), Offset(thumbX, centerY), thickness, StrokeCap.Round)
    }
    if (thumbRadius > 0f) drawCircle(colors.active, thumbRadius, Offset(thumbX, centerY))
    // 键盘焦点照 M3 画在手柄外面一圈，隔开一道缝，不与手柄连成一块
    if (focusRing != null) {
        val ringWidth = SeekFocusRingWidth.toPx()
        val ringRadius = thumbRadius + SeekFocusRingGap.toPx() + ringWidth / 2
        drawCircle(focusRing, ringRadius, Offset(thumbX, centerY), style = Stroke(ringWidth))
    }
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
            modifier = (if (containerSize != null) Modifier.size(containerSize) else Modifier).handCursor(enabled),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * 鼠标悬停在可点的控件上时换成手型。Compose 的按钮默认不换，浮在画面上的半透明按钮
 * 与画面本身难以分辨，指针变化是鼠标用户判断「这里能点」的依据。触屏上没有影响。
 */
internal fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

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

// 进度条的布局高度与轨道几何。布局高度决定它在底栏里占多少地方，ClipChrome 按它把细线挪到底边，
// 不随触控区改；触控区另取 48dp 的最小触控尺寸，细轨道要能点中
private val SeekBarHeight = 32.dp
private val SeekTouchHeight = 48.dp
private val SeekFocusRingWidth = 3.dp
private val SeekFocusRingGap = 2.dp
private val SeekTrackThickness = 4.dp
private val SeekTrackEngagedThickness = 8.dp
private val SeekThumbRadius = 6.dp
private val SeekThumbDraggingRadius = 9.dp
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

// 播放键上的读数用乘号：字号大，字母 x 与数字挤在一起像是一个词
internal fun formatSpeedMultiplier(speed: Float): String = formatSpeedPreset(speed) + "×"

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
