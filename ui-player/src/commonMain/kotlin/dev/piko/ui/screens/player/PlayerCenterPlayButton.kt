package dev.piko.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.MediaLoadingCaption
import dev.piko.ui.components.MorphContainer
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular

internal enum class PlayButtonSurfaceStyle {
    /** 视频画面叠层：半透明暗色玻璃底配微光描边，避免厚重色块遮挡画面主体。 */
    OnMediaScrim,

    /** 实心主题色容器：在纯色页面或关闭半透明底色时保持鲜明主操作重心。 */
    ThemeFilled,

    /** 听视频页面半透明底色：主题色低透玻璃底配细描边，兼容浅色与深色背景。 */
    ThemeTonalScrim,
}

/**
 * 画面中央只有播放键。原先两侧还有快进快退与换集：进退触屏靠双击两侧、鼠标与键盘靠方向键和进度条，
 * 按钮与手势重复，又把画面中央占掉一大片；换集挪进了底栏。
 * 播放键一直在这里，控件收起时它若还在（加载中、有读数）就独自承载加载指示或读数。
 * 显隐由它自己编排（[visible]），不套 AnimatedVisibility，见 PlayPauseButton。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val translucent = LocalPlayerLayoutPrefs.current.translucentPlayButton
    PlayPauseButton(
        visible = visible,
        interactive = interactive,
        isPlaying = isPlaying,
        isLoading = isLoading,
        indicator = indicator,
        loadingSpeedText = loadingSpeedText,
        size = PlayContainerSize,
        iconSize = 28.dp,
        squareCorner = 18.dp,
        pressedCorner = 14.dp,
        surfaceStyle = if (translucent) PlayButtonSurfaceStyle.OnMediaScrim else PlayButtonSurfaceStyle.ThemeFilled,
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
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayPauseButton(
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
    surfaceStyle: PlayButtonSurfaceStyle = PlayButtonSurfaceStyle.OnMediaScrim,
    loadingSpeedText: String? = null,
    interactive: Boolean = visible,
) {
    val colors = MaterialTheme.colorScheme
    val fixed = LocalFixedColors.current
    val isEInk = LocalAppearance.current.isEInk
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
        isEInk && surfaceStyle == PlayButtonSurfaceStyle.ThemeFilled ->
            if (isPlaying) colors.onSurface else colors.surface
        isEInk -> colors.surface
        surfaceStyle == PlayButtonSurfaceStyle.OnMediaScrim -> when {
            face is CenterFace.Readout -> Color.Black.copy(alpha = 0.72f)
            pressed -> Color.Black.copy(alpha = 0.68f)
            else -> fixed.ScrimOnMediaStrong
        }
        surfaceStyle == PlayButtonSurfaceStyle.ThemeTonalScrim -> when {
            face is CenterFace.Readout -> colors.tertiaryContainer.copy(alpha = 0.85f)
            pressed -> colors.primary.copy(alpha = 0.24f)
            else -> colors.primary.copy(alpha = 0.16f)
        }
        face is CenterFace.Readout -> colors.tertiary
        face == CenterFace.Loading -> colors.primaryContainer
        else -> colors.primary
    }
    fun contentColorOf(face: CenterFace): Color = when {
        isEInk && surfaceStyle == PlayButtonSurfaceStyle.ThemeFilled ->
            if (isPlaying) colors.surface else colors.onSurface
        isEInk -> colors.onSurface
        surfaceStyle == PlayButtonSurfaceStyle.OnMediaScrim -> fixed.OnMedia
        surfaceStyle == PlayButtonSurfaceStyle.ThemeTonalScrim -> when {
            face is CenterFace.Readout -> colors.onTertiaryContainer
            else -> colors.primary
        }
        face is CenterFace.Readout -> colors.onTertiary
        face == CenterFace.Loading -> colors.onPrimaryContainer
        else -> colors.onPrimary
    }
    fun borderOf(face: CenterFace): BorderStroke? = when {
        isEInk -> BorderStroke(1.dp, colors.outline)
        surfaceStyle == PlayButtonSurfaceStyle.OnMediaScrim -> BorderStroke(1.dp, fixed.HudSpecularBorder)
        surfaceStyle == PlayButtonSurfaceStyle.ThemeTonalScrim -> BorderStroke(1.dp, colors.primary.copy(alpha = 0.36f))
        else -> null
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
            border = ::borderOf,
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
private const val LOADING_INDICATOR_FRACTION = 0.75f

// Medium 的 56 高，宽度取标准 56 与宽版 72 之间：方形显得局促，宽版又抢过了两侧。方角与按压圆角取自
// icon button 规格的 16 与 12
private val PlayContainerSize = DpSize(68.dp, 56.dp)
