package dev.piko.ui.screens.drive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import dev.piko.ui.components.LocalAntiDiagonalResizeCursor
import dev.piko.ui.components.LocalDiagonalResizeCursor
import dev.piko.ui.components.LocalHorizontalResizeCursor
import dev.piko.ui.components.LocalVerticalResizeCursor

/** 面板第一次出现时放在哪，坐标都在列表这一块里。 */
internal sealed interface PanelAnchor {
    /** 右键按下的那一点：左上角放在这里，右边或下边放不下就翻到点的另一侧，同右键菜单。 */
    data class AtPoint(val point: DpOffset) : PanelAnchor

    /** 一个条目占的地方：贴在它右边，放不下放左边，上沿与它对齐。 */
    data class Beside(val rect: DpRect) : PanelAnchor

    /** 列表这一块的中央偏上，不对着哪一项时用。 */
    data object Centered : PanelAnchor
}

/**
 * 浮在网盘页列表上的一块面板的位置与大小，目录图与属性卡片共用。
 *
 * [position] 的 y 是上沿离列表上沿的距离；x 在 [followsEnd] 时是右沿离列表右沿的距离，否则是左沿离列表左沿的距离。
 * 目录图默认停在右上角，按右沿记，窗口拉宽拉窄时跟着右沿走，不会被留在列表中间；属性卡片放在触发它的地方旁边，按左沿记，
 * 换了内容、宽度变了，左上角不动。
 * null 为还没放过，第一次排版时照 [anchor] 取位置后定下来，此后另一块面板开合也不再挪它。
 */
@Stable
internal class FloatingPanelState(width: Dp, followsEnd: Boolean = true) {
    var position by mutableStateOf<DpOffset?>(null)
    var width by mutableStateOf(width)

    var followsEnd by mutableStateOf(followsEnd)
        private set

    /** 列表这一块的大小，还没排过版时为 null。 */
    var area by mutableStateOf<DpSize?>(null)
        internal set

    /** 拖过上下边之后的高度，没拖过为 null，随内容长短。 */
    var height by mutableStateOf<Dp?>(null)

    /** 下一次放置照它，null 为右上角。 */
    var anchor by mutableStateOf<PanelAnchor?>(null)
        private set

    /**
     * 眼下在列表这一块里占的地方，另一块面板据此错开默认位置；关着时为 null。
     * 收起（[FloatingPanel] 的 visible 为 false）时仍是收起前的位置，别的面板照旧按它避让，不因开合跳动。
     */
    var bounds by mutableStateOf<DpRect?>(null)
        internal set

    /**
     * 改为按哪一侧记位置，屏幕上的位置不变。停靠在左沿的面板要跟着左沿走，否则窗口一拉宽它就离开了左沿。
     * 还没放过或没排过版时只改记法。
     */
    fun followEdge(end: Boolean) {
        if (end == followsEnd) return
        val at = position
        val areaWidth = area?.width
        // 宽度取 bounds 的：拖动不改宽度。位置取 position 本身，bounds 要等下一帧才跟上
        val panelWidth = bounds?.width
        if (at != null && areaWidth != null && panelWidth != null) {
            // 两种记法互为镜像：左沿距离 + 宽度 + 右沿距离 = 列表宽度
            position = DpOffset(areaWidth - at.x - panelWidth, at.y)
        }
        followsEnd = end
    }

    /** 关掉之后再开：忘掉拖到的位置，照新的触发点重新放。 */
    fun placeAt(anchor: PanelAnchor?) {
        this.anchor = anchor
        position = null
    }
}

/**
 * 浮在列表这一块上的面板：标题行按住拖动挪位置，右端是 × 关闭，都夹在列表这一块里。[resizable] 时四条边与四个角
 * 拖着改大小，否则宽度在 [minWidth] 与 [maxWidth] 之间随内容。
 * 由调用方铺满列表这一块；同在这一块里的几块面板由调用方用 zIndex 排先后，[onActivate] 在面板上按下鼠标或手指时调，
 * 调用方据此把它提到最上面。[onSettled] 在拖动或改大小松手时调，要记住位置的在这里存。
 *
 * 位置见 [PanelAnchor]；没有锚点时停在右上角，[avoid] 给出另一块开着的面板占的地方，与它重叠时改停在它左边，两块不叠在一起。
 *
 * [visible] 为 false 时卡片照 [exit] 消失、离开组合，位置、大小与 [FloatingPanelState.bounds] 留着，再出现时照 [enter] 在原处出现。
 * [onGesture] 在开始拖标题行或改大小时以 true 调、松手或取消时以 false 调：目录图在手势进行中不自动收起。
 * [fixedSide] 那一侧的边与两个角不给拖：贴边停靠的面板那一侧本来就顶着列表边沿。
 *
 * 用普通 Layout 而不是 Popup：不抢焦点的 Popup 收不到键盘，目录图的方向键与过滤框都用不了。
 * 也不是 BoxWithConstraints：里面有提示气泡这类弹层，测量时组合的布局在桌面端会撞上弹层销毁的崩溃（desktopApp/CLAUDE.md）。
 */
@Composable
internal fun FloatingPanel(
    state: FloatingPanelState,
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    minWidth: Dp,
    maxWidth: Dp,
    /** 没拖过上下边时，随内容长短的面板最高多高。另外总不超出列表这一块。 */
    autoMaxHeight: Dp,
    modifier: Modifier = Modifier,
    resizable: Boolean = true,
    avoid: () -> DpRect? = { null },
    onActivate: () -> Unit = {},
    onSettled: () -> Unit = {},
    onGesture: (Boolean) -> Unit = {},
    fixedSide: DockSide? = null,
    visible: Boolean = true,
    enter: EnterTransition = EnterTransition.None,
    exit: ExitTransition = ExitTransition.None,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var area by remember { mutableStateOf(DpSize.Zero) }
    LaunchedEffect(area) { if (area != DpSize.Zero) state.area = area }
    var size by remember { mutableStateOf(DpSize.Zero) }
    val activate by rememberUpdatedState(onActivate)
    val avoiding by rememberUpdatedState(avoid)
    // 改大小的拖动区一半压在卡片外面，外面那一半要有地方放，卡片四周因此各让出这么多
    val outset = if (resizable) ResizeGrab / 2 else 0.dp

    fun leftOf(position: DpOffset, panelWidth: Dp) = if (state.followsEnd) area.width - position.x - panelWidth else position.x
    fun xOf(left: Dp, panelWidth: Dp) = if (state.followsEnd) area.width - left - panelWidth else left

    // 按左上角夹进列表这一块，四边各留 EdgeClearance；放不下时贴着左上
    fun clampedLeftTop(left: Dp, top: Dp, panel: DpSize) = DpOffset(
        left.coerceAtMost(area.width - panel.width - EdgeClearance).coerceAtLeast(EdgeClearance),
        top.coerceAtMost(area.height - panel.height - EdgeClearance).coerceAtLeast(EdgeClearance),
    )

    fun clamp(position: DpOffset, panel: DpSize): DpOffset {
        val at = clampedLeftTop(leftOf(position, panel.width), position.y, panel)
        return DpOffset(xOf(at.x, panel.width), at.y)
    }

    fun cornerPosition(panel: DpSize): DpOffset {
        val left = area.width - PanelEndMargin - panel.width
        val other = avoiding()
        val overlaps = other != null && left < other.right && left + panel.width > other.left &&
            PanelTopMargin < other.bottom && PanelTopMargin + panel.height > other.top
        val shifted = if (overlaps) other!!.left - PanelGap - panel.width else left
        return DpOffset(xOf(shifted, panel.width), PanelTopMargin)
    }

    fun anchoredPosition(anchor: PanelAnchor, panel: DpSize): DpOffset {
        val (left, top) = when (anchor) {
            is PanelAnchor.AtPoint -> {
                val p = anchor.point
                val fitsRight = p.x + panel.width <= area.width - EdgeClearance
                val fitsBelow = p.y + panel.height <= area.height - EdgeClearance
                (if (fitsRight) p.x else p.x - panel.width) to (if (fitsBelow) p.y else p.y - panel.height)
            }
            is PanelAnchor.Beside -> {
                val r = anchor.rect
                val fitsRight = r.right + PanelGap + panel.width <= area.width - EdgeClearance
                (if (fitsRight) r.right + PanelGap else r.left - PanelGap - panel.width) to r.top
            }
            PanelAnchor.Centered -> (area.width - panel.width) / 2 to (area.height - panel.height) / 5
        }
        return DpOffset(xOf(left, panel.width), top)
    }

    fun position(panel: DpSize): DpOffset {
        val placed = state.position ?: state.anchor?.let { anchoredPosition(it, panel) } ?: cornerPosition(panel)
        return clamp(placed, panel)
    }

    fun moveBy(dx: Dp, dy: Dp) {
        val from = position(size)
        state.position = clamp(DpOffset(if (state.followsEnd) from.x - dx else from.x + dx, from.y + dy), size)
    }

    // 拖边与拖角：被拖的边跟着手走，夹到上下限或列表边缘就停，对边始终不动。一帧里可能来几次位移，
    // 所以从状态现算当前的边，不用上一次排版量到的 size
    fun resizeBy(edges: ResizeEdges, dx: Dp, dy: Dp) {
        val panelHeight = state.height ?: size.height
        val at = position(DpSize(state.width, panelHeight))
        var left = leftOf(at, state.width)
        var right = left + state.width
        var top = at.y
        var bottom = top + panelHeight
        if (edges.start) left = (left + dx).coerceAtMost(right - minWidth).coerceAtLeast(maxOf(EdgeClearance, right - maxWidth))
        if (edges.end) right = (right + dx).coerceAtLeast(left + minWidth).coerceAtMost(minOf(area.width - EdgeClearance, left + maxWidth))
        if (edges.top) top = (top + dy).coerceAtMost(bottom - PanelMinHeight).coerceAtLeast(maxOf(EdgeClearance, bottom - PanelMaxHeight))
        if (edges.bottom) {
            bottom = (bottom + dy).coerceAtLeast(top + PanelMinHeight).coerceAtMost(minOf(area.height - EdgeClearance, top + PanelMaxHeight))
        }
        state.width = right - left
        if (edges.top || edges.bottom) state.height = bottom - top
        state.position = DpOffset(xOf(left, right - left), top)
    }

    // 位置在量出大小后就定下来：一直现算的话，另一块面板一开一关、内容一换，这一块就跟着跳。
    // 两块同时出现（启动时都开着）时，先组合的那块先定下、登记好 bounds，后一块的这里才读得到它，所以 avoid 是现读的函数
    LaunchedEffect(area, size) {
        if (state.position == null && area != DpSize.Zero && size != DpSize.Zero) state.position = position(size)
    }
    LaunchedEffect(area, size, state.position) {
        if (area == DpSize.Zero || size == DpSize.Zero) return@LaunchedEffect
        val at = position(size)
        val left = leftOf(at, size.width)
        state.bounds = DpRect(left, at.y, left + size.width, at.y + size.height)
    }
    DisposableEffect(state) { onDispose { state.bounds = null } }

    val card = @Composable {
        Box(
            Modifier
                // 先于里面的按钮看到按下，不吃掉事件：点在哪都算碰过这块面板
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) activate()
                        }
                    }
                }
                .semantics { paneTitle = title },
        ) {
            Surface(
                modifier = Modifier
                    .padding(outset)
                    .onSizeChanged { size = with(density) { DpSize(it.width.toDp(), it.height.toDp()) } },
                shape = MaterialTheme.shapes.large,
                color = colors.surfaceContainerLow,
                border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = 3.dp,
            ) {
                PanelFrame(
                    state = state,
                    title = title,
                    closeLabel = closeLabel,
                    onClose = onClose,
                    resizable = resizable,
                    minWidth = minWidth,
                    maxWidth = maxWidth,
                    autoMaxHeight = autoMaxHeight,
                    onMove = ::moveBy,
                    onSettled = onSettled,
                    onGesture = onGesture,
                    headerActions = headerActions,
                    content = content,
                )
            }
            if (resizable) {
                ResizeHandles(Modifier.matchParentSize(), fixedSide, onResize = ::resizeBy, onSettled = onSettled, onGesture = onGesture)
            }
        }
    }

    Layout(
        modifier = modifier.fillMaxSize().onSizeChanged { area = with(density) { DpSize(it.width.toDp(), it.height.toDp()) } },
        content = { AnimatedVisibility(visible = visible, enter = enter, exit = exit) { card() } },
    ) { measurables, constraints ->
        // 收起后卡片离开组合，这一块里什么都不放
        val measurable = measurables.firstOrNull() ?: return@Layout layout(constraints.maxWidth, constraints.maxHeight) {}
        val inset = (EdgeClearance * 2 - outset * 2).roundToPx()
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = 0,
                minHeight = 0,
                maxWidth = (constraints.maxWidth - inset).coerceAtLeast(0),
                maxHeight = (constraints.maxHeight - inset).coerceAtLeast(0),
            ),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val panel = DpSize(placeable.width.toDp() - outset * 2, placeable.height.toDp() - outset * 2)
            val at = position(panel)
            placeable.place((leftOf(at, panel.width) - outset).roundToPx(), (at.y - outset).roundToPx())
        }
    }
}

/** 拖的是哪几条边：边上的拖动区一条，角上的两条。 */
private class ResizeEdges(val start: Boolean = false, val end: Boolean = false, val top: Boolean = false, val bottom: Boolean = false)

/** 面板里的东西：标题行与内容。 */
@Composable
private fun PanelFrame(
    state: FloatingPanelState,
    title: String,
    closeLabel: String,
    onClose: () -> Unit,
    resizable: Boolean,
    minWidth: Dp,
    maxWidth: Dp,
    autoMaxHeight: Dp,
    onMove: (Dp, Dp) -> Unit,
    onSettled: () -> Unit,
    onGesture: (Boolean) -> Unit,
    headerActions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val settled by rememberUpdatedState(onSettled)
    val gesture by rememberUpdatedState(onGesture)
    val header = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(PanelHeaderHeight)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { gesture(true) },
                        onDragEnd = {
                            gesture(false)
                            settled()
                        },
                        onDragCancel = { gesture(false) },
                    ) { change, delta ->
                        change.consume()
                        onMove(delta.x.toDp(), delta.y.toDp())
                    }
                }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            headerActions()
            PanelHeaderToggle(Icons.Outlined.Close, Icons.Outlined.Close, closeLabel, checked = false, onClick = onClose)
        }
    }
    if (!resizable) {
        ContentWidthFrame(minWidth, maxWidth, autoMaxHeight, header) { Column { content() } }
        return
    }
    val height = state.height
    val sized = if (height != null) Modifier.height(height) else Modifier.heightIn(max = autoMaxHeight)
    Column(Modifier.width(state.width).then(sized)) {
        header()
        content()
    }
}

/**
 * 宽度随内容的面板：先量内容（宽在上下限之间、高不超过余下的地方），再按它的宽度量标题行。标题行铺满给它的宽度，
 * 先量它的话面板总在上限那么宽。
 */
@Composable
private fun ContentWidthFrame(minWidth: Dp, maxWidth: Dp, maxHeight: Dp, header: @Composable () -> Unit, body: @Composable () -> Unit) {
    Layout(contents = listOf(header, body)) { (headerMeasurables, bodyMeasurables), constraints ->
        val headerHeight = PanelHeaderHeight.roundToPx()
        val widest = minOf(maxWidth.roundToPx(), constraints.maxWidth)
        val narrowest = minWidth.roundToPx().coerceAtMost(widest)
        val tallest = if (maxHeight == Dp.Unspecified) constraints.maxHeight else minOf(maxHeight.roundToPx(), constraints.maxHeight)
        val bodyPlaceable = bodyMeasurables.first().measure(
            Constraints(minWidth = narrowest, maxWidth = widest, maxHeight = (tallest - headerHeight).coerceAtLeast(0)),
        )
        val width = bodyPlaceable.width
        val headerPlaceable = headerMeasurables.first().measure(Constraints.fixed(width, headerHeight))
        layout(width, headerHeight + bodyPlaceable.height) {
            headerPlaceable.place(0, 0)
            bodyPlaceable.place(0, headerHeight)
        }
    }
}

/**
 * 四条边与四个角上看不见的拖动区，由调用方铺满卡片连同四周让出的那一圈：每条拖动区一半在卡片外、一半压在卡片边上，
 * 照 Windows 窗口的边框，鼠标不必对准一两个像素。不画把手，悬停时换成对应方向的改大小光标（桌面端提供，Android 上为 null）。
 * 曾在左沿单画一道把手，只有一条边有，看着像只有那条边能拖。
 */
@Composable
private fun ResizeHandles(
    modifier: Modifier,
    fixedSide: DockSide?,
    onResize: (ResizeEdges, Dp, Dp) -> Unit,
    onSettled: () -> Unit,
    onGesture: (Boolean) -> Unit,
) {
    val horizontal = LocalHorizontalResizeCursor.current
    val vertical = LocalVerticalResizeCursor.current
    val diagonal = LocalDiagonalResizeCursor.current
    val antiDiagonal = LocalAntiDiagonalResizeCursor.current
    // 面板位置按屏幕左右记（见 FloatingPanelState），start 即左
    val start = fixedSide != DockSide.Left
    val end = fixedSide != DockSide.Right
    Box(modifier) {
        // 边让出两端的角，角压在边上面
        if (start) ResizeHandle(Modifier.align(Alignment.CenterStart).width(ResizeGrab).fillMaxHeight().padding(vertical = CornerGrab), ResizeEdges(start = true), horizontal, onResize, onSettled, onGesture)
        if (end) ResizeHandle(Modifier.align(Alignment.CenterEnd).width(ResizeGrab).fillMaxHeight().padding(vertical = CornerGrab), ResizeEdges(end = true), horizontal, onResize, onSettled, onGesture)
        ResizeHandle(Modifier.align(Alignment.TopCenter).height(ResizeGrab).fillMaxWidth().padding(horizontal = CornerGrab), ResizeEdges(top = true), vertical, onResize, onSettled, onGesture)
        ResizeHandle(Modifier.align(Alignment.BottomCenter).height(ResizeGrab).fillMaxWidth().padding(horizontal = CornerGrab), ResizeEdges(bottom = true), vertical, onResize, onSettled, onGesture)
        if (start) ResizeHandle(Modifier.align(Alignment.TopStart).size(CornerGrab), ResizeEdges(start = true, top = true), diagonal, onResize, onSettled, onGesture)
        if (end) ResizeHandle(Modifier.align(Alignment.BottomEnd).size(CornerGrab), ResizeEdges(end = true, bottom = true), diagonal, onResize, onSettled, onGesture)
        if (end) ResizeHandle(Modifier.align(Alignment.TopEnd).size(CornerGrab), ResizeEdges(end = true, top = true), antiDiagonal, onResize, onSettled, onGesture)
        if (start) ResizeHandle(Modifier.align(Alignment.BottomStart).size(CornerGrab), ResizeEdges(start = true, bottom = true), antiDiagonal, onResize, onSettled, onGesture)
    }
}

@Composable
private fun ResizeHandle(
    modifier: Modifier,
    edges: ResizeEdges,
    cursor: PointerIcon?,
    onResize: (ResizeEdges, Dp, Dp) -> Unit,
    onSettled: () -> Unit,
    onGesture: (Boolean) -> Unit,
) {
    // 手势协程只起一次，拿最新的回调：回调里读的宽高每拖一下都在变
    val resize by rememberUpdatedState(onResize)
    val settled by rememberUpdatedState(onSettled)
    val gesture by rememberUpdatedState(onGesture)
    Box(
        modifier = modifier
            .then(if (cursor != null) Modifier.pointerHoverIcon(cursor) else Modifier)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { gesture(true) },
                    onDragEnd = {
                        gesture(false)
                        settled()
                    },
                    onDragCancel = { gesture(false) },
                ) { change, delta ->
                    change.consume()
                    resize(edges, delta.x.toDp(), delta.y.toDp())
                }
            },
    )
}

/** 标题行右端的开关，选中时换实心并着强调色。大小见 [PanelHeaderButton]。 */
@Composable
internal fun PanelHeaderToggle(icon: ImageVector, checkedIcon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    PanelHeaderButton(
        icon = if (checked) checkedIcon else icon,
        label = label,
        tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
    )
}

/** 标题行右端的小按钮，比标准图标按钮小一号，与 44dp 的标题行相称。 */
@Composable
internal fun PanelHeaderButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

// 没拖过时离列表右沿与顶上的距离：贴着右沿时与网格最右一栏的滚动条、条目右上角的标签挤在一起
private val PanelEndMargin = 24.dp
internal val PanelTopMargin = 12.dp

// 让开另一块面板时两块之间留的距离
private val PanelGap = 12.dp

// 面板离列表四边至少留的距离
private val EdgeClearance = 12.dp

private val PanelHeaderHeight = 44.dp
private val PanelMinHeight = 160.dp
private val PanelMaxHeight = 1200.dp

// 边上拖动区的厚度，一半在卡片外；角上的拖动区是边长 CornerGrab 的方块，比边宽一些，斜着拖好对准
private val ResizeGrab = 8.dp
private val CornerGrab = 14.dp
