package dev.piko.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

/**
 * 宽窗口的主区加右侧侧栏，开关、拖宽与记住宽度只有这一份。
 *
 * 侧栏取 M3 standard side sheet 的 detached 形态（side-sheets.md）：与主区在同一平面（MDC 的 coplanar 款，
 * elevation 0），离窗口边缘 16dp（规格 measurements 表的 Margins (when detached)），整块是一张卡（[panelCard]）。
 * 关闭按钮常驻，规格原话是没有它就看不出这块是临时的还是固定的。Compose 没有这个组件（那一页实现表里
 * Jetpack Compose 一栏是 UNAVAILABLE），按规格自行搭建。
 *
 * 与规格不同的三处：宽度可以超过规格的 400dp 上限，因为它能拖宽；与主区之间是一段带拖动手柄的间隔
 * （见 [PanelDragHandle]），不是分割线；栏名与列表页眉同一档字号（[PaneTitle]），不用规格那种大一号的标题，
 * 两块内容的名字一大一小时，分不清主次是版式给的还是内容给的。
 *
 * 读屏把它当作有名字的区域（paneTitle），不当 Dialog：规格给的角色是 Dialog，但这块不抢焦点、不挡主区，
 * 读成对话框会让人以为要先关掉它才能回到主区。
 *
 * 放不放得下由调用方先用 [sidePanelFits] 判断，放不下时改用别的形态，这里不再退让：上限压到下限之后
 * 主区仍会被挤到 [MainPaneMinWidth] 以下。
 *
 * @param open 侧栏此刻该不该出现。
 * @param savedWidthDp 设置里存的宽度，null 为从未拖过，取 [defaultWidth]。
 * @param ready 开关已经判得出来。头一次组合时 AnimatedVisibility 不播进场动画，值到了再组合：
 *   开着的人看到它直接在那儿，关掉过的人不会看到它弹一下。
 * @param headerActions 栏名与关闭按钮之间的其他按钮。
 * @param showHeader 为 false 时不画栏名那一行，整张卡交给 [panel]，关闭与其他按钮由内容自己放：
 *   信息流是一整块黑底的竖屏画面，上面再压一条浅色栏名就成了两层顶栏。
 */
@Composable
fun SidePanelLayout(
    open: Boolean,
    savedWidthDp: Float?,
    title: String,
    closeDescription: String,
    onClose: () -> Unit,
    onWidthChange: (Float) -> Unit,
    defaultWidth: Dp,
    minWidth: Dp,
    modifier: Modifier = Modifier,
    ready: Boolean = true,
    headerActions: @Composable RowScope.() -> Unit = {},
    showHeader: Boolean = true,
    main: @Composable () -> Unit,
    panel: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        // 拖过的宽度留在这里，松手才交给设置保存，每帧写盘没有必要。松手后也不清掉：
        // 清掉的话在设置写回来之前会先退回旧宽度，侧栏弹一下
        var draggingWidth by remember { mutableStateOf<Dp?>(null) }
        // 上限让主区至少还留 MainPaneMinWidth；窗口再窄时上限压到下限，侧栏就不再能拖宽
        val maxPanelWidth = (maxWidth - PanelSpacer - PanelMargin - MainPaneMinWidth).coerceAtLeast(minWidth)
        val savedWidth = savedWidthDp?.dp ?: defaultWidth
        val panelWidth = (draggingWidth ?: savedWidth).coerceIn(minWidth, maxPanelWidth)
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { main() }
            if (ready) {
                AnimatedVisibility(
                    visible = open,
                    // 打开时主区收窄让位（side-sheets.md 的 Adaptive design 一节），所以动的是宽度，
                    // 不是在主区上面滑进一块。内容贴着左沿，随左沿一起往左推出来
                    enter = expandHorizontally(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        expandFrom = Alignment.Start,
                    ),
                    exit = shrinkHorizontally(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        shrinkTowards = Alignment.Start,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxHeight()
                            // 主区的 Scaffold 自己避让系统栏，侧栏在它外面，要自己让
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)),
                    ) {
                        PanelDragHandle(
                            // 在状态上累加，不从 panelWidth 算：panelWidth 是上一次重组时的值，
                            // 一帧里来几次位移就有几次从同一个旧宽度起算，只剩最后一次生效，
                            // 侧栏跟不上鼠标。状态写入当场可读，连着几次也不会丢
                            onDrag = { deltaDp ->
                                draggingWidth = ((draggingWidth ?: panelWidth) - deltaDp)
                                    .coerceIn(minWidth, maxPanelWidth)
                            },
                            onDragStopped = { draggingWidth?.let { onWidthChange(it.value) } },
                        )
                        Column(
                            modifier = Modifier
                                // 上边也留 16dp：Piko 没有横贯两栏的顶栏，侧栏从窗口顶上开始
                                .padding(top = PanelMargin, end = PanelMargin, bottom = PanelMargin)
                                .width(panelWidth)
                                .fillMaxHeight()
                                .panelCard()
                                .semantics { paneTitle = title },
                        ) {
                            if (showHeader) Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // 关闭按钮的触摸区比图标宽 12dp，右边留 4dp，图标的右沿落在 16dp 线上
                                modifier = Modifier.padding(end = 4.dp),
                            ) {
                                PaneTitle(title, Modifier.weight(1f))
                                headerActions()
                                IconButton(onClick = onClose) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = closeDescription,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) { panel() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * [availableWidth] 里能否同时放下至少 [MainPaneMinWidth] 的主区与 [panelMinWidth] 的侧栏。
 * 放不下时调用方改用全屏形态，不把主区挤到排不下一列的宽度。
 */
fun sidePanelFits(availableWidth: Dp, panelMinWidth: Dp): Boolean =
    availableWidth >= MainPaneMinWidth + PanelSpacer + panelMinWidth + PanelMargin

/**
 * 可拖宽分隔处的悬停光标，由平台提供，null 为不换。Compose 公共代码里的 PointerIcon 只有默认、十字、
 * 文本、手型四种，左右调整大小的光标要从 AWT 取，桌面入口在 PikoApp 外面提供。Android 上不换：
 * 触屏没有悬停，接鼠标的平板也有拖动手柄本身的形状可认。
 */
val LocalHorizontalResizeCursor = staticCompositionLocalOf<PointerIcon?> { null }

/** 侧栏那一整张卡。取 surfaceContainerLow，比窗口底色高一档，与主区分得开。 */
@Composable
fun Modifier.panelCard(): Modifier =
    clip(MaterialTheme.shapes.largeIncreased).background(MaterialTheme.colorScheme.surfaceContainerLow)

/**
 * 一栏的栏名，48dp 高，与列表页眉同高。只有一栏、无可切换时用它，不画成标签：一个孤零零的选中态标签
 * 读起来像另外几个没加载出来。
 */
@Composable
fun PaneTitle(text: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(PaneTitleHeight)
            .padding(horizontal = 16.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 主区与侧栏之间的间隔，中间是 M3 的拖动手柄（layout-overview.md 的 Spacers 一节：两栏之间 24dp，
 * 手柄放在间隔里）。手柄既标出了边界，又说明这条边能拖。
 *
 * [onDrag] 收到的位移按阅读方向计，正值朝行尾。侧栏在行尾，所以往行尾拖是收窄；RTL 下侧栏到了左边，
 * 同一个换算仍然成立，调用处不必再判方向。
 */
@Composable
private fun PanelDragHandle(onDrag: (Dp) -> Unit, onDragStopped: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val resizeCursor = LocalHorizontalResizeCursor.current
    val dragState = rememberDraggableState { deltaPx ->
        val towardsEnd = if (rtl) -deltaPx else deltaPx
        onDrag(with(density) { towardsEnd.toDp() })
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(PanelSpacer)
            .fillMaxHeight()
            .then(if (resizeCursor != null) Modifier.pointerHoverIcon(resizeCursor) else Modifier)
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                interactionSource = interactions,
                onDragStopped = { onDragStopped() },
            ),
    ) {
        VerticalDragHandle(interactionSource = interactions)
    }
}

/** 两栏之间的间隔宽度（layout-overview.md 的 Spacers 一节）。 */
private val PanelSpacer = 24.dp

/** 侧栏离窗口边缘的距离（side-sheets.md 的 Margins (when detached)）。 */
private val PanelMargin = 16.dp

/** 给主区留的最小宽度：列表视图的一列（下限 360dp）连同页眉的排序、筛选与视图切换还排得下。 */
private val MainPaneMinWidth = 440.dp

private val PaneTitleHeight = 48.dp
