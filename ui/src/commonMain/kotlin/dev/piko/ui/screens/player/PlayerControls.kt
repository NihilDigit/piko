package dev.piko.ui.screens.player

import dev.piko.ui.components.MediaLoadingCaption
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import dev.piko.shared.media.AUTO_QUALITY
import dev.piko.ui.components.LocalPointerSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.wrapContentWidth
import dev.piko.ui.components.MorphContainer
import androidx.compose.runtime.derivedStateOf
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
import dev.piko.shared.media.ORIGINAL_QUALITY
import dev.piko.ui.platform.LocalFramelessWindow
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import dev.piko.ui.format.formatDurationMillis
import dev.piko.ui.format.formatPlaybackSpeed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import dev.piko.data.auth.PlayerGestureDefaults
import kotlin.math.roundToInt

/**
 * 播放器顶栏：返回、标题与副标题、播放设置；桌面的独立窗口另有置顶与关闭。
 * 音轨与字幕、分享、下载都在播放设置面板里：原先一字排在这里，竖屏时把标题挤没，收进菜单后又多点一层。
 *
 * 标题区单独包一层带 weight 的 Box：TooltipBox 不把 weight 的 parent data 交给 Row，
 * 直接给它 weight 时标题按内容宽度摆放，右侧按钮会紧跟在标题后面，而不是贴到右边。
 *
 * 标题单行、中间省略：视频文件名的区分信息（集数、分辨率）通常在末尾，
 * 末尾省略会把几十集截成同一个前缀。完整标题在长按提示里。
 * 按钮浮在视频上，规范要求带容器，否则对比度随画面变化没有保证。
 */
/**
 * 桌面独立播放窗口交给顶栏的窗口控制动作：最小化、最大化/还原、置顶与关闭。
 * Windows 无边框窗口显示整组控制；macOS / Linux 复用系统窗口键，仅保留置顶并去掉重复返回键。
 */
@Immutable
data class PlayerWindowActions(
    val isMaximized: Boolean = false,
    val isFullscreen: Boolean = false,
    val isAlwaysOnTop: Boolean = false,
    val showWindowButtons: Boolean = true,
    val topChromePadding: Dp = 0.dp,
    val onMinimize: (() -> Unit)? = null,
    val onToggleMaximize: (() -> Unit)? = null,
    val onToggleAlwaysOnTop: (() -> Unit)? = null,
    val onClose: (() -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerTopBar(
    title: String,
    episodeLabel: String?,
    isLocalPlayback: Boolean,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onTracksClick: (() -> Unit)? = null,
    onStatsClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    windowActions: PlayerWindowActions? = null,
    isFullscreen: Boolean = false,
    dragAreaEnabled: Boolean = true,
) {
    // 桌面端独立的播放窗口没有导航返回栈：由窗口动作或系统标题栏关闭，不再显示移动端返回键
    val framelessWindow = LocalFramelessWindow.current
    val isDesktopWindow = framelessWindow != null || windowActions != null
    val hasActionCluster = onTracksClick != null || onStatsClick != null || onSettingsClick != null
    val effectivePin = windowActions?.onToggleAlwaysOnTop ?: framelessWindow?.let { fw -> { fw.setAlwaysOnTop(!fw.isAlwaysOnTop) } }
    val effectiveOnTop = windowActions?.isAlwaysOnTop ?: framelessWindow?.isAlwaysOnTop ?: false
    val showWindowButtons = windowActions?.showWindowButtons ?: (framelessWindow != null)
    val effectiveClose = windowActions?.onClose ?: framelessWindow?.let { fw -> fw::close }
    val hasWindowCluster = effectivePin != null || (showWindowButtons && (windowActions?.onMinimize != null || windowActions?.onToggleMaximize != null || effectiveClose != null))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(TopScrim))
            // 横屏隐藏了系统栏，顶部只剩挖孔一侧需要让位；竖屏让出状态栏。
            // 渐变在 padding 之前，仍然满幅。
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
            .padding(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp + (windowActions?.topChromePadding ?: 0.dp),
                bottom = 12.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isDesktopWindow) {
            PlayerIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = tr("返回", "Back", "戻る", "뒤로", "返回"),
                onClick = onBackClick,
                tooltipBelow = true,
                containerSize = IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow),
            )
        }
        // 标题这一块兼做拖动窗口的地方（仅限没有标题栏的桌面窗口）
        Box(Modifier.weight(1f).windowDragArea(enabled = dragAreaEnabled).padding(horizontal = 8.dp)) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(title) } },
                state = rememberTooltipState(),
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMediumEmphasized.copy(shadow = PlayerTextShadow),
                        color = LocalFixedColors.current.OnMedia,
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
                                    style = MaterialTheme.typography.labelLarge.tabular.copy(shadow = PlayerTextShadow),
                                    color = LocalFixedColors.current.OnMedia.copy(alpha = 0.82f),
                                    maxLines = 1,
                                )
                            }
                            if (isLocalPlayback) LocalPlaybackBadge()
                        }
                    }
                }
            }
        }
        if (isFullscreen) {
            PlayerSystemStatusCapsule()
        }
        if (hasActionCluster) {
            PlayerPillCluster {
                if (onTracksClick != null) {
                    ClusterIconButton(
                        icon = Icons.Outlined.Subtitles,
                        label = tr("音轨与字幕", "Audio and subtitles", "音声と字幕", "오디오 및 자막", "音軌與字幕"),
                        onClick = onTracksClick,
                        tooltipBelow = true,
                    )
                }
                if (onStatsClick != null) {
                    ClusterIconButton(
                        icon = Icons.Outlined.Info,
                        label = tr("详细信息", "Details", "詳細情報", "세부 정보", "詳細資訊"),
                        onClick = onStatsClick,
                        tooltipBelow = true,
                        shortcut = "I",
                    )
                }
                if (onSettingsClick != null) {
                    ClusterIconButton(
                        icon = Icons.Outlined.Tune,
                        label = tr("播放设置", "Playback settings", "再生設定", "재생 설정", "播放設定"),
                        onClick = onSettingsClick,
                        tooltipBelow = true,
                    )
                }
            }
        }
        if (hasWindowCluster) {
            PlayerPillCluster {
                if (effectivePin != null) {
                    ClusterIconButton(
                        icon = if (effectiveOnTop) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        label = if (effectiveOnTop) tr("取消置顶", "Unpin", "最前面表示を解除", "고정 해제", "取消置頂") else tr("置顶", "Pin on top", "最前面に表示", "항상 위에 표시", "置頂"),
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

/** 浮在视频画面上的分组半透明高光描边胶囊：在纯白或亮画面上仍保持清晰的边缘对比度。 */
@Composable
internal fun PlayerPillCluster(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = playerContainerColor(),
        contentColor = LocalFixedColors.current.OnMedia,
        border = BorderStroke(1.dp, LocalFixedColors.current.HudSpecularBorder),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClusterIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
    tint: Color = LocalFixedColors.current.OnMedia,
    tooltipBelow: Boolean = false,
    shortcut: String? = null,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered && enabled) Color.White.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(if (shortcut != null) "$label ($shortcut)" else label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(bg)
                .hoverable(hoverSource, enabled = enabled)
                .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
                .handCursor(enabled),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) tint else tint.copy(alpha = DISABLED_CONTENT_ALPHA),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerCloseWindowButton(
    label: String,
    shortcut: String?,
    onClick: () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) PlayerCloseHoverRed else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(if (shortcut != null) "$label ($shortcut)" else label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(bg)
                .hoverable(hoverSource)
                .clickable(onClickLabel = label, onClick = onClick)
                .handCursor(),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = label,
                tint = if (hovered) Color.White else LocalFixedColors.current.OnMedia,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun LocalPlaybackBadge() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = stringResource(Res.string.text_local_file),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 画面中央只有播放键。原先两侧还有快进快退与换集：进退触屏靠双击两侧、鼠标与键盘靠方向键和进度条，
 * 按钮与手势重复，又把画面中央占掉一大片；换集挪进了底栏。
 * 播放键一直在这里，控件收起时它若还在（加载中、有读数）就独自承载加载指示或读数。
 * 显隐由它自己编排（[visible]），不套 AnimatedVisibility，见 PlayPauseButton。
 */
@Composable
internal fun PlayerCenterControls(
    visible: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    indicator: CenterIndicator?,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    loadingSpeedText: String? = null,
    interactive: Boolean = visible,
) {
    PlayPauseButton(
        visible = visible,
        interactive = interactive,
        isPlaying = isPlaying,
        isLoading = isLoading,
        indicator = indicator,
        loadingSpeedText = loadingSpeedText,
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
 * [textTemplate] 是这一种读数最宽时的样子（音量是「100」），胶囊按它定宽、数字在里面靠右，
 * 拖动时宽度不随位数伸缩。
 */
internal data class CenterIndicator(
    val icon: ImageVector,
    val text: String,
    val description: String,
    val progress: Float? = null,
    val textTemplate: String? = null,
    val direction: Int = 0,
)

/** 把数字都换成 0：等宽数字下，同样格式里位数最多的那一个就是最宽的。 */
internal fun widestDigits(text: String): String = text.map { if (it in '0'..'9') '0' else it }.joinToString("")

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
 * 显隐、变形与换内容由这里一处编排，排成先后，不交给 AnimatedVisibility、AnimatedContent 与各自的尺寸动画分头做。
 * 分头做时三者互不知道对方走到哪一步：内容换好了容器还没张开、容器开始收了内容还没撤，都会露出读数被裁掉的中间一截，
 * 补了一处另一处又冒出来。现在的规则：
 * - 从无到有：没有可以变形的起点，直接摆成目标的形状与颜色，整体淡入。
 * - 显示中换一种内容：旧内容先淡出，空着的容器变形到新尺寸，接近到位才淡入新内容。
 * - 同一种读数换数值（拖动、调音量）：内容就地更新，宽度跟着走。
 * - 退场：整体淡出，期间不换内容、不变形，保持退场那一刻的样子。
 *
 * 不用 FilledIconToggleButton：它的形状只在 checked 与 pressed 间切换，接不进另外两种形态，
 * 容器宽度也不能动画。不用 androidx.graphics.shapes 的 Morph：几种形态都是圆角矩形，只差宽度与圆角，
 * RoundedCornerShape 的尺寸插值就够了，也不必为此加依赖。加载中仍可点击，缓冲时暂停是合理操作。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayPauseButton(
    visible: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    indicator: CenterIndicator?,
    size: DpSize,
    iconSize: Dp,
    squareCorner: Dp,
    pressedCorner: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loadingSpeedText: String? = null,
    interactive: Boolean = visible,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val target = when {
        indicator != null -> CenterFace.Readout(indicator)
        isLoading -> CenterFace.Loading
        else -> CenterFace.Icon
    }
    // 读数形态的宽度照内容量出来，胶囊恰好托住它：固定宽度的话「90°」两边空一大截，「01:23:45」又放不下
    val readoutStyle = MaterialTheme.typography.titleMediumEmphasized.tabular
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 文字占的宽度：有模板时取模板与眼前文字中宽的一个
    fun slotWidth(text: String, template: String?, style: TextStyle): Dp = with(density) {
        val measured = textMeasurer.measure(text, style).size.width
        val reserved = template?.let { textMeasurer.measure(it, style).size.width } ?: 0
        maxOf(measured, reserved).toDp()
    }
    fun readoutWidth(shown: CenterIndicator): Dp {
        val bar = if (shown.progress != null) READOUT_BAR_WIDTH + READOUT_GAP else 0.dp
        return READOUT_PADDING * 2 + READOUT_ICON_SIZE + READOUT_GAP + bar + slotWidth(shown.text, shown.textTemplate, readoutStyle)
    }
    // 圆形态（暂停、加载）收成正圆，不是两头圆的胶囊；方角形态（播放中）才展开到 [size] 的宽度，
    // 读数形态再照内容伸长。按下只收紧圆角不动宽度，否则按一下左右抖
    fun widthOf(face: CenterFace): Dp = when (face) {
        is CenterFace.Readout -> maxOf(size.width, readoutWidth(face.indicator))
        CenterFace.Loading -> size.height
        CenterFace.Icon -> if (isPlaying) size.width else size.height
    }
    val roundCorner = size.height / 2
    fun cornerOf(face: CenterFace): Dp = when {
        face != CenterFace.Icon -> roundCorner
        pressed -> pressedCorner
        isPlaying -> squareCorner
        else -> roundCorner
    }
    fun containerColorOf(face: CenterFace): Color = when {
        face is CenterFace.Readout -> colors.tertiary
        face == CenterFace.Loading -> colors.primaryContainer
        else -> colors.primary
    }
    fun contentColorOf(face: CenterFace): Color = when {
        face is CenterFace.Readout -> colors.onTertiary
        face == CenterFace.Loading -> colors.onPrimaryContainer
        else -> colors.onPrimary
    }
    // 按钮的标签只说点下去做什么：读数形态下点击照样是播放或暂停，标签换成读数的话，读屏念完读数却不知道这一下做了什么
    val actionLabel = when {
        isLoading -> tr("加载中", "Loading", "読み込み中", "불러오는 중", "載入中")
        isPlaying -> tr("暂停", "Pause", "一時停止", "일시 중지", "暫停")
        else -> tr("播放", "Play", "再生", "재생", "播放")
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
        MorphContainer(
            targetState = target,
            visible = visible,
            interactive = interactive,
            contentKey = { it.contentKey },
            width = ::widthOf,
            corner = ::cornerOf,
            containerColor = ::containerColorOf,
            contentColor = ::contentColorOf,
            anchorSize = size,
            onClick = onClick,
            interactionSource = interactionSource,
            containerModifier = Modifier
                .handCursor()
                .semantics { contentDescription = actionLabel },
        ) { shown ->
            when (shown) {
                CenterFace.Loading -> PikoLoadingIndicator(
                    size = size.height * LOADING_INDICATOR_FRACTION,
                    color = contentColorOf(shown),
                )
                CenterFace.Icon -> Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
                is CenterFace.Readout -> {
                    val readout = shown.indicator
                    val contentColor = contentColorOf(shown)
                    // 读数按自己的宽度排，不受容器眼下的宽度约束：变形途中它是淡出的，不会露出被裁的一截
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(READOUT_GAP),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                    ) {
                        Icon(readout.icon, contentDescription = null, modifier = Modifier.size(READOUT_ICON_SIZE))
                        readout.progress?.let { progress ->
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                color = contentColor,
                                trackColor = contentColor.copy(alpha = 0.3f),
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                                modifier = Modifier.width(READOUT_BAR_WIDTH),
                            )
                        }
                        Text(
                            text = readout.text,
                            style = readoutStyle,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.width(slotWidth(readout.text, readout.textTemplate, readoutStyle)),
                        )
                    }
                }
            }
        }
        if (visible && isLoading && indicator == null && !loadingSpeedText.isNullOrBlank()) {
            MediaLoadingCaption(
                anchorHalfHeight = size.height / 2,
                subtitle = loadingSpeedText,
            )
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
    positionMillisProvider: () -> Long,
    /** 画面上拖动进度的目标处，不为 null 时进度条与时间停在这里，见 PlayerSeekBar 的 previewMillis。 */
    previewPositionMillis: Long?,
    durationMillis: Long,
    bufferedPositionMillisProvider: () -> Long,
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
    isPlaying: Boolean = false,
    onPlayPause: (() -> Unit)? = null,
    qualityOptions: List<String> = emptyList(),
    currentQuality: String? = null,
    isQualityPopupOpen: Boolean = false,
    onQualityPopupOpenChange: (Boolean) -> Unit = {},
    onQualityChange: (String) -> Unit = {},
    onTracksClick: (() -> Unit)? = null,
    onSettingsClick: (() -> Unit)? = null,
    onScrubbingChange: (Boolean) -> Unit = {},
    /** 当前音量，0 即静音；为 null 时不给音量键与滑块（移动端，或平台没有可调的音量）。 */
    volume: Float? = null,
    onVolumeChange: (Float) -> Unit = {},
    onToggleMute: () -> Unit = {},
    onVolumeDraggingChange: (Boolean) -> Unit = {},
) {
    var scrubPositionMillis by remember { mutableStateOf<Long?>(null) }
    val latestOnScrubbingChange by rememberUpdatedState(onScrubbingChange)
    val handleScrub: (Long?) -> Unit = remember {
        { target ->
            val wasScrubbing = scrubPositionMillis != null
            scrubPositionMillis = target
            if (wasScrubbing != (target != null)) latestOnScrubbingChange(target != null)
        }
    }
    val showTransportPlay = !compactWidth && onPlayPause != null
    val showQualityPill = !compactWidth && qualityOptions.size > 1 && currentQuality != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(BottomScrim))
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (isLandscape) 10.dp else 14.dp),
    ) {
        PlayerBottomSeekBar(
            positionMillisProvider = positionMillisProvider,
            previewPositionMillis = previewPositionMillis,
            durationMillis = durationMillis,
            bufferedPositionMillisProvider = bufferedPositionMillisProvider,
            isPlaying = isPlaying,
            playbackSpeed = playbackSpeed ?: 1f,
            onSeek = onSeek,
            thumbOnHoverOnly = thumbOnHoverOnly,
            onScrub = handleScrub,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showTransportPlay || showEpisodeSkip) {
                    PlayerPillCluster {
                        if (showTransportPlay && onPlayPause != null) {
                            ClusterIconButton(
                                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                label = if (isPlaying) tr("暂停", "Pause", "一時停止", "일시 중지", "暫停") else tr("播放", "Play", "再生", "재생", "播放"),
                                onClick = onPlayPause,
                                iconSize = 22.dp,
                                shortcut = "Space",
                            )
                        }
                        if (showEpisodeSkip) {
                            ClusterIconButton(
                                icon = Icons.Filled.SkipPrevious,
                                label = tr("上一集", "Previous episode", "前のエピソード", "이전 에피소드", "上一集"),
                                onClick = onPrevious,
                                enabled = hasPrevious,
                                shortcut = "PageUp",
                            )
                            ClusterIconButton(
                                icon = Icons.Filled.SkipNext,
                                label = tr("下一集", "Next episode", "次のエピソード", "다음 에피소드", "下一集"),
                                onClick = onNext,
                                enabled = hasNext,
                                shortcut = "PageDown",
                            )
                        }
                    }
                }
                if (volume != null) {
                    PlayerVolumeControl(
                        volume = volume,
                        // 窄窗口只留静音键，滚轮在键上照样能调
                        showSlider = !compactWidth,
                        onVolumeChange = onVolumeChange,
                        onToggleMute = onToggleMute,
                        onDraggingChange = onVolumeDraggingChange,
                    )
                }
                PlayerTimeReadout(
                    positionMillisProvider = positionMillisProvider,
                    scrubPositionMillis = scrubPositionMillis,
                    previewPositionMillis = previewPositionMillis,
                    durationMillis = durationMillis,
                )
            }

            if (showQualityPill && currentQuality != null) {
                PlayerChipButton(
                    text = currentQuality.qualityDisplayLabel,
                    selected = isQualityPopupOpen,
                    onClick = { onQualityPopupOpenChange(!isQualityPopupOpen) },
                    tooltip = if (!isQualityPopupOpen) stringResource(Res.string.player_quality) else null,
                )
            }
            if (playbackSpeed != null) {
                PlayerChipButton(
                    text = formatSpeed(playbackSpeed),
                    selected = isSpeedPopupOpen,
                    onClick = { onSpeedPopupOpenChange(!isSpeedPopupOpen) },
                    tooltip = if (!isSpeedPopupOpen) tr("倍速 ([ / ]，Backspace 恢复原速)", "Speed ([ / ], Backspace resets)", "再生速度 ([ / ]、Backspace で等倍)", "재생 속도 ([ / ], Backspace 기본 속도)", "倍速 ([ / ]，Backspace 還原原速)") else null,
                    modifier = Modifier.semantics { contentDescription = tr("倍速 ${formatSpeed(playbackSpeed)}", "Speed ${formatSpeed(playbackSpeed)}", "再生速度 ${formatSpeed(playbackSpeed)}", "재생 속도 ${formatSpeed(playbackSpeed)}", "倍速 ${formatSpeed(playbackSpeed)}") },
                )
            }
            if (showEpisodes) {
                // 窄窗口只留图标，名字在提示里：带字的这一格约 80dp，省下的地方让旋转按钮常驻，
                // 360dp 的手机竖屏里时间、倍速、选集、旋转、全屏才排得下
                if (compactWidth) {
                    PlayerIconButton(icon = Icons.Outlined.VideoLibrary, label = tr("选集", "Playlist", "再生リスト", "재생 목록", "選集"), onClick = onEpisodesClick)
                } else {
                    PlayerChipButton(text = tr("选集", "Playlist", "再生リスト", "재생 목록", "選集"), icon = Icons.Outlined.VideoLibrary, onClick = onEpisodesClick)
                }
            }
            PlayerPillCluster {
                if (!compactWidth && onTracksClick != null) {
                    ClusterIconButton(
                        icon = Icons.Outlined.Subtitles,
                        label = tr("音轨与字幕", "Audio and subtitles", "音声と字幕", "오디오 및 자막", "音軌與字幕"),
                        onClick = onTracksClick,
                        shortcut = "C",
                    )
                }
                if (!compactWidth && onSettingsClick != null) {
                    ClusterIconButton(
                        icon = Icons.Outlined.Tune,
                        label = tr("播放设置", "Playback settings", "再生設定", "재생 설정", "播放設定"),
                        onClick = onSettingsClick,
                    )
                }
                if (onRotate != null) {
                    ClusterIconButton(
                        icon = Icons.Filled.Rotate90DegreesCw,
                        label = tr("顺时针旋转 90 度", "Rotate 90° clockwise", "右に90度回転", "시계 방향으로 90도 회전", "順時針旋轉 90 度"),
                        onClick = onRotate,
                        shortcut = "R",
                    )
                }
                ClusterIconButton(
                    icon = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    label = if (isFullscreen) tr("退出全屏", "Exit fullscreen", "全画面を終了", "전체 화면 종료", "結束全螢幕") else tr("全屏", "Fullscreen", "全画面", "전체 화면", "全螢幕"),
                    onClick = onToggleFullscreen,
                    shortcut = "F",
                )
            }
        }
    }
}

@Composable
private fun PlayerBottomSeekBar(
    positionMillisProvider: () -> Long,
    previewPositionMillis: Long?,
    durationMillis: Long,
    bufferedPositionMillisProvider: () -> Long,
    isPlaying: Boolean,
    playbackSpeed: Float = 1f,
    onSeek: (Long) -> Unit,
    thumbOnHoverOnly: Boolean,
    onScrub: (Long?) -> Unit,
) {
    PlayerSeekBar(
        positionMillisProvider = positionMillisProvider,
        previewMillis = previewPositionMillis,
        durationMillis = durationMillis,
        bufferedPositionMillisProvider = bufferedPositionMillisProvider,
        isPlaying = isPlaying,
        playbackSpeed = playbackSpeed,
        wavy = false,
        onSeek = onSeek,
        thumbOnHoverOnly = thumbOnHoverOnly,
        onScrub = onScrub,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeGestures.only(WindowInsetsSides.Horizontal)),
    )
}

@Composable
internal fun PlayerTimeReadout(
    positionMillisProvider: () -> Long,
    scrubPositionMillis: Long?,
    previewPositionMillis: Long?,
    durationMillis: Long,
) {
    val latestPositionProvider by rememberUpdatedState(positionMillisProvider)
    val latestScrub by rememberUpdatedState(scrubPositionMillis)
    val latestPreview by rememberUpdatedState(previewPositionMillis)
    val displayedSeconds by remember {
        derivedStateOf {
            val millis = latestScrub ?: latestPreview ?: latestPositionProvider()
            millis.coerceAtLeast(0L) / 1_000L
        }
    }
    val onMedia = LocalFixedColors.current.OnMedia
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatTime(displayedSeconds * 1_000L),
            style = TimeTextStyle().copy(shadow = PlayerTextShadow),
            color = onMedia,
            maxLines = 1,
        )
        Text(
            text = " / ${formatTime(durationMillis)}",
            style = TimeTextStyle().copy(shadow = PlayerTextShadow),
            color = onMedia.copy(alpha = 0.72f),
            maxLines = 1,
        )
    }
}

/**
 * 桌面底栏的音量：静音键加一条常驻的可展开横向滑块，键与滑块上的滚轮都调音量（±5%）。
 * 常驻挂载保证桌面触屏与键盘 Tab 可达，同时悬停或拖动时平滑展开提供更精细的调节行程。
 */
@Composable
internal fun PlayerVolumeControl(
    volume: Float,
    showSlider: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    showBackground: Boolean = true,
    tooltipBelow: Boolean = false,
) {
    val currentVolume by rememberUpdatedState(volume)
    val currentOnVolumeChange by rememberUpdatedState(onVolumeChange)
    val muted = volume <= 0f
    val containerHover = remember { MutableInteractionSource() }
    val containerHovered by containerHover.collectIsHoveredAsState()
    var sliderDragging by remember { mutableStateOf(false) }
    val expanded = containerHovered || sliderDragging

    val scrollModifier = Modifier
        .hoverable(containerHover)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) continue
                    val change = event.changes.firstOrNull() ?: continue
                    val dy = change.scrollDelta.y
                    if (dy == 0f) continue
                    val step = if (dy < 0f) VOLUME_KEY_STEP else -VOLUME_KEY_STEP
                    currentOnVolumeChange((currentVolume + step).coerceIn(0f, 1f))
                    change.consume()
                }
            }
        }

    val content: @Composable RowScope.() -> Unit = {
        ClusterIconButton(
            icon = when {
                muted -> Icons.AutoMirrored.Filled.VolumeOff
                volume < VOLUME_LOW_THRESHOLD -> Icons.AutoMirrored.Filled.VolumeDown
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            label = if (muted) tr("取消静音", "Unmute", "ミュートを解除", "음소거 해제", "取消靜音") else tr("静音", "Mute", "ミュート", "음소거", "靜音"),
            onClick = onToggleMute,
            tooltipBelow = tooltipBelow,
            shortcut = "M",
        )
        if (showSlider) {
            PlayerVolumeSlider(
                volume = volume,
                expanded = expanded,
                onVolumeChange = onVolumeChange,
                onDraggingChange = { dragging ->
                    sliderDragging = dragging
                    onDraggingChange(dragging)
                },
            )
            Spacer(Modifier.width(6.dp))
        }
    }

    if (showBackground) {
        PlayerPillCluster(modifier = scrollModifier, content = content)
    } else {
        Row(
            modifier = scrollModifier.padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 音量滑块，外观同进度条（细轨道、靠近变粗），理由见 [PlayerSeekBar]。与进度条不同，拖动途中即时生效：
 * 改音量没有重开请求的代价，要边拖边听。悬停或拖动时在手柄上方显示百分比。
 */
@Composable
private fun PlayerVolumeSlider(
    volume: Float,
    expanded: Boolean,
    onVolumeChange: (Float) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    val hoverInteraction = remember { MutableInteractionSource() }
    val hovered by hoverInteraction.collectIsHoveredAsState()
    val focusInteraction = remember { MutableInteractionSource() }
    val focused by focusInteraction.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val inactive = scheme.onSurface.copy(alpha = INACTIVE_TRACK_ALPHA)
    val trackColors = SeekTrackColors(active = scheme.primary, inactive = inactive, buffered = inactive)
    val motion = MaterialTheme.motionScheme
    val engaged = dragging || hovered || focused
    val sliderWidth by animateDpAsState(if (expanded || focused) VolumeSliderExpandedWidth else VolumeSliderWidth, motion.fastSpatialSpec())
    val thickness by animateDpAsState(if (engaged) SeekTrackEngagedThickness else SeekTrackThickness, motion.fastSpatialSpec())
    val thumbRadius by animateDpAsState(if (dragging) SeekThumbDraggingRadius else SeekThumbRadius, motion.fastSpatialSpec())
    val focusRingColor = scheme.secondary
    val currentOnVolumeChange by rememberUpdatedState(onVolumeChange)
    val currentOnDraggingChange by rememberUpdatedState(onDraggingChange)
    val percent = (volume * 100).roundToInt()

    Box(
        Modifier
            .width(sliderWidth)
            .height(36.dp)
            .handCursor()
            .hoverable(hoverInteraction)
            .pointerInput(Unit) {
                val inset = SeekThumbDraggingRadius.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    currentOnDraggingChange(true)
                    try {
                        currentOnVolumeChange(seekFractionAt(down.position.x, size.width.toFloat(), inset))
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            currentOnVolumeChange(seekFractionAt(change.position.x, size.width.toFloat(), inset))
                            change.consume()
                        }
                    } finally {
                        dragging = false
                        currentOnDraggingChange(false)
                    }
                }
            }
            .drawBehind {
                drawSeekTrack(
                    fraction = volume,
                    bufferedFraction = 0f,
                    colors = trackColors,
                    thickness = thickness.toPx(),
                    thumbRadius = thumbRadius.toPx(),
                    inset = SeekThumbDraggingRadius.toPx(),
                    focusRing = if (focused) focusRingColor else null,
                )
            }
            .focusable(interactionSource = focusInteraction)
            .semantics {
                contentDescription = tr("音量", "Volume", "音量", "볼륨", "音量")
                stateDescription = "$percent%"
                progressBarRangeInfo = ProgressBarRangeInfo(volume, 0f..1f)
                setProgress { target ->
                    currentOnVolumeChange(target.coerceIn(0f, 1f))
                    true
                }
            },
    ) {
        if (dragging || hovered) {
            val density = LocalDensity.current
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (dragging) scheme.primary else LocalFixedColors.current.ScrimOnMediaStrong,
                contentColor = if (dragging) scheme.onPrimary else LocalFixedColors.current.OnMedia,
                border = if (dragging) null else BorderStroke(1.dp, LocalFixedColors.current.HudSpecularBorder),
                modifier = Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints())
                    val inset = with(density) { SeekThumbDraggingRadius.toPx() }
                    val anchor = inset + volume * (constraints.maxWidth - 2 * inset)
                    val drop = with(density) { VolumeBubbleDrop.roundToPx() }
                    layout(0, 0) { placeable.place((anchor - placeable.width / 2f).roundToInt(), -placeable.height + drop) }
                },
            ) {
                Text(
                    text = if (dragging) "$percent%" else tr("音量 $percent% (↑ / ↓)", "Volume $percent% (↑ / ↓)", "音量 $percent% (↑ / ↓)", "볼륨 $percent% (↑ / ↓)", "音量 $percent% (↑ / ↓)"),
                    style = TimeTextStyle(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private val VolumeSliderWidth = 76.dp
private val VolumeSliderExpandedWidth = 104.dp
private val VolumeBubbleDrop = 6.dp
private const val VOLUME_LOW_THRESHOLD = 0.5f

internal val String.baseQualityLabel: String
    get() = when (this) {
        AUTO_QUALITY -> tr("自动", "Auto", "自動", "자동", "自動")
        ORIGINAL_QUALITY -> tr("原画", "Original", "オリジナル", "원본", "原畫")
        else -> this
    }

internal val String.qualityDisplayLabel: String
    @Composable get() = if (this == AUTO_QUALITY) {
        val resolved = LocalAutoResolvedQuality.current
        if (resolved != null) "${AUTO_QUALITY.baseQualityLabel} (${resolved.baseQualityLabel})" else AUTO_QUALITY.baseQualityLabel
    } else {
        baseQualityLabel
    }

/** 时间码用等宽数字：比例数字随秒数跳动，右侧按钮会跟着左右抖。 */
@Composable
private fun TimeTextStyle(): TextStyle =
    MaterialTheme.typography.labelLarge.tabular

/**
 * 底栏的文字胶囊按钮：半透明高光描边容器，等宽数字防抖动，按压时有形变；
 * 对应单段面板展开时切换为 `primaryContainer` 选中态（与 Bilby `ControlButton` 对齐）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    tooltip: String? = null,
    tooltipBelow: Boolean = false,
    showBackground: Boolean = true,
) {
    val fixed = LocalFixedColors.current
    val colors = MaterialTheme.colorScheme
    val textStyle = if (showBackground || selected) {
        MaterialTheme.typography.labelLarge.tabular
    } else {
        MaterialTheme.typography.labelLarge.tabular.copy(shadow = PlayerTextShadow)
    }
    val button: @Composable () -> Unit = {
        FilledTonalButton(
            onClick = onClick,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = when {
                    selected && showBackground -> colors.primaryContainer
                    showBackground -> playerContainerColor()
                    else -> Color.Transparent
                },
                contentColor = when {
                    selected && showBackground -> colors.onPrimaryContainer
                    selected -> colors.primary
                    else -> fixed.OnMedia
                },
            ),
            border = if (showBackground && !selected) BorderStroke(1.dp, fixed.HudSpecularBorder) else null,
            contentPadding = ButtonDefaults.ExtraSmallContentPadding,
            modifier = modifier.heightIn(min = 36.dp).handCursor(),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.ExtraSmallIconSize))
                Spacer(Modifier.width(ButtonDefaults.ExtraSmallIconSpacing))
            }
            Text(text, style = textStyle, maxLines = 1)
        }
    }
    if (tooltip != null) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
            ),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            button()
        }
    } else {
        button()
    }
}

/**
 * 进度条（移植自 Bilby `SeekBar.kt`）：
 * - 触屏相对拖动（落指不跳针，按水平位移增量微调）、键鼠绝对拖动，单击/轻点独立提交定位避免暂停-恢复顿挫；
 * - M3E `4dp -> 6dp` 轨道与 `5dp -> 8dp` 手柄 `fastSpatialSpec` 形变、已缓冲段与末尾 `2dp` 停止圆点；
 * - 播放中且未交互、未开减少动画且窗口可见时，按 30fps（33ms）在绘制阶段推进波浪进度，零重组；
 * - 零布局高度预览气泡支持实时缩略帧（经 [LocalScrubFrameCache] 共享）与时间/相对偏移胶囊。
 */
@Composable
internal fun PlayerSeekBar(
    positionMillisProvider: () -> Long,
    durationMillis: Long,
    bufferedPositionMillisProvider: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    playbackSpeed: Float = 1f,
    wavy: Boolean = false,
    thumbOnHoverOnly: Boolean = false,
    onScrub: (Long?) -> Unit = {},
    // 在别处拖动进度（播放器画面上的横滑）时的目标处：手柄与时间气泡照拖动进度条本身的样子显示在这里
    previewMillis: Long? = null,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    var scrubOriginMillis by remember { mutableLongStateOf(0L) }
    // 松手到新位置回报之间有一段延迟，这段时间里滑块停在目标处，不回跳到旧位置
    var pendingSeekMillis by remember { mutableStateOf<Long?>(null) }
    var pendingSeekSeq by remember { mutableIntStateOf(0) }
    val latestPositionProvider by rememberUpdatedState(positionMillisProvider)
    val latestBufferedProvider by rememberUpdatedState(bufferedPositionMillisProvider)
    val currentDurationMillis by rememberUpdatedState(durationMillis)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentSpeed by rememberUpdatedState(playbackSpeed.coerceIn(0.25f, 4f))
    val relativeMode by rememberUpdatedState(LocalPointerSource.current.isTouchLike)

    LaunchedEffect(pendingSeekMillis, pendingSeekSeq) {
        val pending = pendingSeekMillis ?: return@LaunchedEffect
        withTimeoutOrNull(SEEK_SETTLE_TIMEOUT_MILLIS) {
            snapshotFlow { latestPositionProvider() }
                .first { abs(it - pending) < SEEK_SETTLE_TOLERANCE_MILLIS }
        }
        pendingSeekMillis = null
    }

    // 捕获横向手势预览起点，供拖动气泡计算相对偏移 ±mm:ss
    LaunchedEffect(previewMillis != null) {
        if (previewMillis != null) {
            scrubOriginMillis = latestPositionProvider()
        }
    }

    val enabled = durationMillis > 0
    fun fractionOf(millis: Long): Float =
        if (enabled) (millis.toFloat() / durationMillis).coerceIn(0f, 1f) else 0f

    // 正在拖向的位置：拖进度条本身，或在画面上横滑
    val scrubFraction = dragFraction ?: previewMillis?.let(::fractionOf)
    val isHovered by remember { derivedStateOf { hoverFraction != null } }
    val focusInteraction = remember { MutableInteractionSource() }
    val isFocused by focusInteraction.collectIsFocusedAsState()
    val isEngaged = scrubFraction != null || isHovered || isFocused
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
            scrubFraction != null -> SeekThumbDraggingRadius
            // 键盘停在进度条上时手柄要露出来，否则看不出焦点在这里、方向键会动它
            thumbOnHoverOnly && !isHovered && !isFocused -> 0.dp
            else -> SeekThumbRadius
        },
        motion.fastSpatialSpec(),
    )

    // 仅音频播放启用 M3E 30fps（33ms）绘制阶段波形推进；视频与信息流保持干脆直轨
    val animateWave = wavy && isPlaying && !isEngaged && enabled && !LocalPikoMotion.current.reduced && LocalWindowVisible.current
    val wavePhase = remember { mutableFloatStateOf(0f) }
    val wavePath = remember { Path() }
    val waveFactor by animateFloatAsState(if (animateWave) 1f else 0f, motion.fastEffectsSpec())
    LaunchedEffect(animateWave) {
        if (!animateWave) return@LaunchedEffect
        while (true) {
            delay(WAVE_FRAME_DELAY_MS)
            val periodMs = (WAVE_PERIOD_MILLIS / currentSpeed).coerceIn(800f, 6_400f)
            val step = TWO_PI * (WAVE_FRAME_DELAY_MS / periodMs)
            wavePhase.floatValue = (wavePhase.floatValue + step) % TWO_PI
        }
    }

    fun commitSeek(target: Float) {
        val millis = (target * currentDurationMillis).toLong().coerceIn(0L, currentDurationMillis.coerceAtLeast(0L))
        pendingSeekMillis = millis
        pendingSeekSeq += 1
        currentOnSeek(millis)
    }

    // 方向键一步 SEEK_STEP_MILLIS，与播放器其余地方的进退同一步长，每按一下即提交，相当于拖动后松手。
    // 连按时从上一步的目标接着算，位置回报还没跟上也不会原地打转
    fun stepBy(deltaMillis: Long): Boolean {
        if (!enabled) return false
        val base = pendingSeekMillis ?: latestPositionProvider()
        commitSeek(fractionOf(base + deltaMillis))
        return true
    }

    // 无障碍语义按整秒（1000ms）粒度经 derivedStateOf 量化，播放期间 1Hz 更新，拖动/预览期间即时更新
    val latestPreview by rememberUpdatedState(previewMillis)
    val semanticsSeconds by remember(enabled, durationMillis) {
        derivedStateOf {
            if (!enabled) {
                0L
            } else {
                val drag = dragFraction
                val preview = latestPreview
                val millis = when {
                    drag != null -> (drag * durationMillis).toLong().coerceIn(0L, durationMillis)
                    preview != null -> preview.coerceIn(0L, durationMillis)
                    else -> (pendingSeekMillis ?: latestPositionProvider()).coerceIn(0L, durationMillis)
                }
                millis / 1_000L
            }
        }
    }
    val semanticsPositionMillis = semanticsSeconds * 1_000L
    val semanticsFraction = fractionOf(semanticsPositionMillis)
    val progressLabel = tr("播放进度", "Playback progress", "再生位置", "재생 위치", "播放進度")
    val semanticsStateDescription = remember(semanticsPositionMillis, durationMillis) {
        "${formatTime(semanticsPositionMillis)} / ${formatTime(durationMillis)}"
    }
    val setSemanticsProgress: (Float) -> Boolean = remember {
        { target ->
            commitSeek(target.coerceIn(0f, 1f))
            true
        }
    }

    val focusRingColor = scheme.secondary
    val barWidthPx = remember { intArrayOf(0) }

    Box(
        modifier = modifier
            .height(SeekBarHeight)
            .onSizeChanged { barWidthPx[0] = it.width },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // 布局仍占 SeekBarHeight，接触摸的这一层撑到 48dp、上下各溢出一截，轨道画在正中不挪位置。
                // 不能指望 Compose 的最小触控区自动外扩：它只在别处都没接住时才生效，而进度条底下铺着整屏的手势层
                .requiredHeight(SeekTouchHeight)
                .handCursor(enabled)
                // 单击/轻点直接定位：不触发拖动态与暂停/恢复顿挫（与 Bilby SeekBar 一致）
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val inset = SeekThumbDraggingRadius.toPx()
                    detectTapGestures { offset ->
                        commitSeek(seekFractionAt(offset.x, size.width.toFloat(), inset))
                    }
                }
                // 横向拖动：触屏走相对位移（抓任意位置不跳针），鼠标走绝对位置（松手才提交 seek）
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val inset = SeekThumbDraggingRadius.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val origin = pendingSeekMillis ?: latestPositionProvider()
                            scrubOriginMillis = origin
                            val startFrac = if (relativeMode) {
                                fractionOf(origin)
                            } else {
                                seekFractionAt(offset.x, size.width.toFloat(), inset)
                            }
                            dragFraction = startFrac
                            currentOnScrub((startFrac * currentDurationMillis).toLong())
                        },
                        onDragEnd = {
                            dragFraction?.let(::commitSeek)
                            dragFraction = null
                            currentOnScrub(null)
                        },
                        onDragCancel = {
                            dragFraction = null
                            currentOnScrub(null)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val usable = (size.width - 2f * inset).coerceAtLeast(1f)
                            val target = if (relativeMode) {
                                ((dragFraction ?: fractionOf(scrubOriginMillis)) + dragAmount / usable).coerceIn(0f, 1f)
                            } else {
                                seekFractionAt(change.position.x, size.width.toFloat(), inset)
                            }
                            dragFraction = target
                            currentOnScrub((target * currentDurationMillis).toLong())
                        },
                    )
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
                    // 高频位置、缓冲与波形相位仅在绘制阶段读取，0 重组
                    val liveFraction = scrubFraction ?: fractionOf(pendingSeekMillis ?: latestPositionProvider())
                    val liveBufferedFraction = fractionOf(latestBufferedProvider())
                    drawSeekTrack(
                        fraction = liveFraction,
                        bufferedFraction = liveBufferedFraction,
                        colors = trackColors,
                        thickness = thickness.toPx(),
                        thumbRadius = thumbRadius.toPx(),
                        inset = SeekThumbDraggingRadius.toPx(),
                        focusRing = if (isFocused) focusRingColor else null,
                        waveAmplitudePx = SeekWaveAmplitude.toPx() * waveFactor,
                        waveLengthPx = SeekWaveLength.toPx(),
                        waveGapPx = SeekWaveGap.toPx() * waveFactor,
                        wavePhase = wavePhase.floatValue,
                        wavePath = wavePath,
                        showStopDot = enabled,
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
                    contentDescription = progressLabel
                    stateDescription = semanticsStateDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(semanticsFraction, 0f..1f)
                    if (enabled) {
                        setProgress(action = setSemanticsProgress)
                    }
                },
        )

        // 拖动或悬停时的预览气泡隔离为子组件：鼠标在轨道上滑动只更新气泡自身，不重组外层进度条
        if (scrubFraction != null || isHovered) {
            SeekPreviewBubble(
                scrubFraction = scrubFraction,
                hoverFractionProvider = { hoverFraction },
                durationMillis = durationMillis,
                originMillis = scrubOriginMillis,
                barWidthPxProvider = { barWidthPx[0] },
            )
        }
    }
}

@Composable
private fun SeekPreviewBubble(
    scrubFraction: Float?,
    hoverFractionProvider: () -> Float?,
    durationMillis: Long,
    originMillis: Long,
    barWidthPxProvider: () -> Int,
) {
    val shown = scrubFraction ?: hoverFractionProvider() ?: return
    val density = LocalDensity.current
    val inset = with(density) { SeekThumbDraggingRadius.toPx() }
    val gapPx = with(density) { SeekBubbleGap.roundToPx() }
    val dragging = scrubFraction != null
    val scheme = MaterialTheme.colorScheme
    val fixed = LocalFixedColors.current
    val targetMillis = (shown * durationMillis).toLong().coerceIn(0L, durationMillis.coerceAtLeast(0L))
    val deltaMillis = targetMillis - originMillis

    val frameCache = LocalScrubFrameCache.current
    val hasStoryboard = frameCache != null && durationMillis > 0L
    val previewFrame = if (frameCache != null && durationMillis > 0L) {
        // 读取 revision 使新解码帧到位后仅刷新气泡自身；O(log N) 真实 PTS 二分查帧 < 1ms，0 网络
        frameCache.revision
        val quantizedBoostMs = (targetMillis / 2_000L) * 2_000L
        LaunchedEffect(frameCache, quantizedBoostMs) {
            frameCache.notifyUserFocus(targetMillis)
        }
        frameCache.frameAt(targetMillis)
    } else {
        null
    }

    // 必须传无界 Constraints()：外层 PlayerSeekBar 布局高度仅 32dp，若继承父 maxHeight 会把 16:9 预览卡压扁成胶囊
    val bubbleModifier = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(Constraints())
        val widthPx = when {
            barWidthPxProvider() > 0 -> barWidthPxProvider()
            constraints.hasBoundedWidth -> constraints.maxWidth
            else -> 0
        }
        val trackWidthPx = (widthPx - 2 * inset).coerceAtLeast(0f)
        val anchorPx = inset + shown * trackWidthPx
        val maxX = (widthPx - placeable.width).coerceAtLeast(0)
        val x = (anchorPx - placeable.width / 2f).roundToInt().coerceIn(0, maxX)
        layout(0, 0) { placeable.place(x, -placeable.height - gapPx) }
    }

    if (hasStoryboard) {
        val barWidthPx = barWidthPxProvider()
        val compactPreview = barWidthPx > 0 && with(density) { barWidthPx.toDp() } < SeekPreviewCompactThreshold
        val previewWidth = if (compactPreview) SeekPreviewWidthCompact else SeekPreviewWidthWide
        val previewHeight = if (compactPreview) SeekPreviewHeightCompact else SeekPreviewHeightWide
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF101218),
            border = BorderStroke(1.dp, if (dragging) scheme.primary else fixed.HudSpecularBorder),
            shadowElevation = 8.dp,
            modifier = bubbleModifier.testTag(SEEK_PREVIEW_CARD_TEST_TAG),
        ) {
            Box(
                modifier = Modifier.size(width = previewWidth, height = previewHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (previewFrame != null) {
                    Image(
                        bitmap = previewFrame,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        tint = fixed.OnMedia.copy(alpha = 0.28f),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.58f to Color.Transparent,
                                1f to Color(0xD9000000),
                            ),
                        ),
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xA6000000))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTime(targetMillis),
                        style = MaterialTheme.typography.labelMedium.tabular.copy(shadow = PlayerTextShadow),
                        color = fixed.OnMedia,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (dragging && abs(deltaMillis) >= 1_000L) {
                        Text(
                            text = formatSeekDelta(deltaMillis),
                            style = MaterialTheme.typography.labelSmall.tabular.copy(shadow = PlayerTextShadow),
                            color = scheme.primaryContainer,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    } else {
        Surface(
            shape = MaterialTheme.shapes.small,
            // 拖动时是要跳过去的位置，用主题色强调；悬停只是看看，用半透明高光深色胶囊
            color = if (dragging) scheme.primary else fixed.ScrimOnMediaStrong,
            contentColor = if (dragging) scheme.onPrimary else fixed.OnMedia,
            border = if (dragging) null else BorderStroke(1.dp, fixed.HudSpecularBorder),
            modifier = bubbleModifier,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(targetMillis),
                    style = TimeTextStyle(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                if (dragging && abs(deltaMillis) >= 1_000L) {
                    Text(
                        text = formatSeekDelta(deltaMillis),
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = scheme.onPrimary.copy(alpha = 0.82f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun formatSeekDelta(deltaMillis: Long): String {
    val sign = if (deltaMillis >= 0L) "+" else "−"
    return "$sign${formatTime(abs(deltaMillis))}"
}

/** 指针横坐标对应的进度。两端各让出手柄放大后的半径，拖到头时手柄不出界。 */
private fun seekFractionAt(x: Float, width: Float, inset: Float): Float =
    ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)

@Immutable
private data class SeekTrackColors(val active: Color, val inactive: Color, val buffered: Color)

/**
 * 轨道从左到右：已播放段（播放中可选 M3E 波浪）、手柄、缓冲段、未播放段与末尾停止圆点。[inset] 是两端给手柄留的边，
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
    waveAmplitudePx: Float = 0f,
    waveLengthPx: Float = 0f,
    waveGapPx: Float = 0f,
    wavePhase: Float = 0f,
    wavePath: Path? = null,
    showStopDot: Boolean = false,
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
    if (showStopDot && fraction < 0.985f && end > thumbX + SeekStopDotRadius.toPx() * 2f) {
        drawCircle(colors.active, SeekStopDotRadius.toPx(), Offset(end, centerY))
    }

    val activeEnd = (thumbX - waveGapPx).coerceAtLeast(start)
    val activeSpan = activeEnd - start
    if (activeSpan > 0f) {
        if (wavePath != null && waveAmplitudePx > 0.05f && waveLengthPx > 0f && activeSpan > waveLengthPx * 0.6f) {
            wavePath.reset()
            val stepPx = 3f
            val taperPx = waveLengthPx * 0.5f
            val freq = TWO_PI / waveLengthPx
            var x = 0f
            wavePath.moveTo(start, centerY)
            while (x < activeSpan) {
                x = (x + stepPx).coerceAtMost(activeSpan)
                val edgeStart = (x / taperPx).coerceIn(0f, 1f)
                val edgeEnd = ((activeSpan - x) / taperPx).coerceIn(0f, 1f)
                val env = minOf(edgeStart, edgeEnd)
                val y = centerY + sin(x * freq - wavePhase) * waveAmplitudePx * env
                wavePath.lineTo(start + x, y)
            }
            drawPath(
                path = wavePath,
                color = colors.active,
                style = Stroke(width = thickness, cap = StrokeCap.Round),
            )
        } else {
            drawLine(colors.active, Offset(start, centerY), Offset(thumbX, centerY), thickness, StrokeCap.Round)
        }
    }
    if (thumbRadius > 0f) drawCircle(colors.active, thumbRadius, Offset(thumbX, centerY))
    // 键盘焦点照 M3 画在手柄外面一圈，隔开一道缝，不与手柄连成一块
    if (focusRing != null) {
        val ringWidth = SeekFocusRingWidth.toPx()
        val ringRadius = thumbRadius + SeekFocusRingGap.toPx() + ringWidth / 2
        drawCircle(focusRing, ringRadius, Offset(thumbX, centerY), style = Stroke(ringWidth))
    }
}

/** 浮在视频上的控件容器色。半透明高光底色：既保证亮画面上的对比度，又不整块挡住画面。 */
@Composable
internal fun playerContainerColor(): Color =
    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = CONTAINER_ALPHA)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun playerIconButtonColors(
    selected: Boolean = false,
    showBackground: Boolean = true,
): IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors(
    containerColor = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        showBackground -> playerContainerColor()
        else -> Color.Transparent
    },
    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else LocalFixedColors.current.OnMedia,
    disabledContainerColor = if (showBackground) playerContainerColor().copy(alpha = CONTAINER_ALPHA / 2) else Color.Transparent,
    disabledContentColor = LocalFixedColors.current.OnMedia.copy(alpha = DISABLED_CONTENT_ALPHA),
)

/**
 * 带长按提示与可选半透明高光描边容器的图标按钮。
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
    selected: Boolean = false,
    showBackground: Boolean = true,
    containerSize: DpSize? = null,
    iconSize: Dp = 24.dp,
    shapes: IconButtonShapes = IconButtonDefaults.shapes(),
    tooltipBelow: Boolean = false,
    // 只写进提示，不进 contentDescription，与 TooltipIconButton 相同
    shortcut: String? = null,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (tooltipBelow) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(if (shortcut != null) "$label ($shortcut)" else label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            shapes = shapes,
            colors = playerIconButtonColors(selected = selected, showBackground = showBackground),
            enabled = enabled,
            modifier = (if (containerSize != null) Modifier.size(containerSize) else Modifier)
                .handCursor(enabled),
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

internal val TopScrim = listOf(
    Color(0x8C000000),
    Color(0x38000000),
    Color.Transparent,
)
internal val BottomScrim = listOf(
    Color.Transparent,
    Color(0x42000000),
    Color(0x9E000000),
)
internal val PlayerTextShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 6f)
private val PlayerCloseHoverRed = Color(0xFFC42B1C)

// 进度条的布局高度与轨道几何。布局高度决定它在底栏里占多少地方，ClipChrome 按它把细线挪到底边，
// 不随触控区改；触控区另取 48dp 的最小触控尺寸，细轨道要能点中
private val SeekBarHeight = 32.dp
private val SeekTouchHeight = 48.dp
private val SeekFocusRingWidth = 3.dp
private val SeekFocusRingGap = 2.dp
private val SeekTrackThickness = 4.dp
private val SeekTrackEngagedThickness = 6.dp
private val SeekThumbRadius = 5.dp
private val SeekThumbDraggingRadius = 8.dp
private val SeekStopDotRadius = 2.dp
private val SeekBubbleGap = 10.dp
private val SeekPreviewWidthWide = 192.dp
private val SeekPreviewHeightWide = 108.dp
private val SeekPreviewWidthCompact = 160.dp
private val SeekPreviewHeightCompact = 90.dp
private val SeekPreviewCompactThreshold = 560.dp
internal const val SEEK_PREVIEW_CARD_TEST_TAG = "seek-preview-card"
private val SeekWaveAmplitude = 3.dp
private val SeekWaveLength = 28.dp
private val SeekWaveGap = 4.dp
private const val WAVE_FRAME_DELAY_MS = 33L
private const val WAVE_PERIOD_MILLIS = 3_200f
private const val TWO_PI = (PI * 2.0).toFloat()
private const val INACTIVE_TRACK_ALPHA = 0.28f
private const val BUFFERED_TRACK_ALPHA = 0.55f

private const val CONTAINER_ALPHA = 0.78f
private const val LOADING_INDICATOR_FRACTION = 0.75f
private const val DISABLED_CONTENT_ALPHA = 0.38f
private const val SEEK_SETTLE_TOLERANCE_MILLIS = 1_500L
private const val SEEK_SETTLE_TIMEOUT_MILLIS = 1_500L

// 公开给 app 模块里的控件测试用。播放器里的实际值来自设置，这是信息流与测试用的默认值
const val SEEK_STEP_MILLIS = PlayerGestureDefaults.SEEK_STEP_SECONDS * 1000L
internal const val MIN_SPEED = 0.5f
internal const val MAX_SPEED = 3.5f
const val LONG_PRESS_BOOST_SPEED = PlayerGestureDefaults.BOOST_SPEED

internal fun formatSpeed(speed: Float): String = formatSpeedPreset(speed) + "x"

// 播放键上的读数用乘号：字号大，字母 x 与数字挤在一起像是一个词
internal fun formatSpeedMultiplier(speed: Float): String = formatSpeedPreset(speed) + "×"

// 按两位小数取整后去掉末尾的 0：1.00 显示为 1，1.50 显示为 1.5
internal fun formatSpeedPreset(speed: Float): String = formatPlaybackSpeed(speed)

internal fun formatTime(millis: Long): String = formatDurationMillis(millis)
