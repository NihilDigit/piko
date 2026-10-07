package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.DropdownMenuPopupPositionProvider
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import dev.piko.ui.adaptive.isDesktopLayout

/**
 * 在指针处弹出的右键菜单，包住一个条目。菜单项与该条目的操作面板相同，只是换成桌面上
 * 更顺手的呈现：不必先点「更多」再在底部面板里找。
 *
 * 鼠标只认副键按下，只消费这一下：条目自己的单击与悬停照常工作。桌面上触屏与笔按住不放也弹这份菜单，
 * 照 Windows 的惯例按住等于右键，见 [detectTouchHold]；移动端的长按是进多选，由条目自己处理（[longPressSelects]）。
 */
@Composable
fun ContextMenuArea(
    actions: () -> List<SheetAction>,
    modifier: Modifier = Modifier,
    /** 为 false 时右键不弹菜单，例如多选时：那时的操作针对选中的全部条目，不是这一行。 */
    enabled: Boolean = true,
    /**
     * 不为 null 时菜单末尾加一项「选择」，进入多选并选中这一项。多选状态下传 null。
     * 桌面上按住不再进多选，触屏用户要从这里进去；鼠标另有主修饰键、Shift 与框选。
     */
    onSelect: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val holdOpensMenu = enabled && isDesktopLayout()
    val enclosing = LocalEnclosingHold.current
    val hold = remember { HoldClaim() }
    Box(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        // 嵌套时里层先收到（Main 阶段由内向外）：条目的菜单弹出后标为已消费，外面网格空白处的那一层就不再弹
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !change.isConsumed) {
                            menuAt = change.position
                            change.consume()
                        }
                    }
                }
            }
            .then(
                if (holdOpensMenu) {
                    Modifier.pointerInput(hold, enclosing) { detectTouchHold(hold, enclosing) { menuAt = it } }
                } else {
                    Modifier
                },
            ),
    ) {
        CompositionLocalProvider(LocalEnclosingHold provides hold, content = content)
        val position = menuAt
        if (position != null) {
            val positionProvider = remember(position) { PointerMenuPositionProvider(position) }
            MenuMotion {
                ActionMenu(actions = withSelectAction(actions(), onSelect), positionProvider = positionProvider, onDismiss = { menuAt = null })
            }
        }
    }
}

/** 「选择」单独一组，排在属性与危险项之前，见 [layoutActions]。 */
private fun withSelectAction(actions: List<SheetAction>, onSelect: (() -> Unit)?): List<SheetAction> =
    if (onSelect == null) actions else actions + SheetAction(Icons.Outlined.CheckBox, "选择", onSelect, group = ActionGroup.Select)

/**
 * 长按是进多选还是弹菜单。移动端照 Android 进多选；桌面上按住不放等于右键，由 [ContextMenuArea] 弹菜单，
 * 鼠标左键按住什么也不做，条目不要再挂长按。
 */
@Composable
@ReadOnlyComposable
fun longPressSelects(): Boolean = !isDesktopLayout()

/**
 * 嵌套的菜单区（网格空白处包着条目）里，一次按住只归最里面的那一层。Main 阶段由内向外传，里层先收到按下，
 * 把指针 ID 记到外层的 [yielded] 上，外层随后收到同一个按下时就放手。不能靠消费来让：条目自己的点击已把按下消费掉，
 * 这里本就不看消费与否。
 */
private class HoldClaim {
    var yielded: PointerId? = null
}

private val LocalEnclosingHold = staticCompositionLocalOf<HoldClaim?> { null }

/**
 * 触屏与笔按住不放，抬起时在按下处弹菜单。照 Windows：按住到时不弹，抬起才弹，手指不会正好落在刚冒出来的菜单上，
 * 弹层也不必接住一个在它出现之前就按下的指针。挪过了触摸阈值（滚动）、多指（捏合）或到时之前抬起都不算。
 *
 * 到时之后余下的事件在 Initial 阶段全部消费：条目自己的点击没有超时，不消费的话抬起时它照样打开条目。
 */
private suspend fun PointerInputScope.detectTouchHold(own: HoldClaim, enclosing: HoldClaim?, onHold: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type != PointerType.Touch && down.type != PointerType.Stylus) return@awaitEachGesture
        if (own.yielded == down.id) return@awaitEachGesture
        enclosing?.yielded = down.id
        val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || event.changes.size > 1) break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        }
        if (endedEarly != null) return@awaitEachGesture
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        onHold(down.position)
    }
}

/**
 * 右键菜单以指针为原点，照桌面惯例：左上角落在指针处向右下展开，右边放不下向左、下边放不下向上。
 * 不用 DropdownMenu 的下拉规则（MenuAnchorPosition.Below）：它挂在锚点下方，下面放不下时整个翻到锚点上沿以上，
 * 锚点是一整个条目乃至整片网格，靠窗口下半部右键时菜单跳到离指针很远的地方。
 *
 * @param pointer 指针在锚点（ContextMenuArea 那一层）里的位置。
 */
private class PointerMenuPositionProvider(private val pointer: Offset) : DropdownMenuPopupPositionProvider {
    // 按展开方向取角，菜单若有缩放就从指针那一角长出来。在测量之后才知道方向，读的时机晚于这里写
    override var transformOrigin: TransformOrigin by mutableStateOf(TransformOrigin(0f, 0f))
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val inWindow = IntOffset(anchorBounds.left + pointer.x.roundToInt(), anchorBounds.top + pointer.y.roundToInt())
        val position = pointerMenuPosition(inWindow, popupContentSize, windowSize)
        transformOrigin = TransformOrigin(
            pivotFractionX = if (position.x < inWindow.x) 1f else 0f,
            pivotFractionY = if (position.y < inWindow.y) 1f else 0f,
        )
        return position
    }
}

/**
 * 以窗口坐标下的指针 [pointer] 为原点摆一个 [menu] 大小的菜单。两个方向各自判断：先向右（下），放不下向左（上），
 * 两边都放不下就贴着窗口的右（下）边，比窗口还大时贴左（上）边，菜单自己能滚动。
 */
fun pointerMenuPosition(pointer: IntOffset, menu: IntSize, window: IntSize): IntOffset =
    IntOffset(
        x = placeAlong(pointer.x, menu.width, window.width),
        y = placeAlong(pointer.y, menu.height, window.height),
    )

private fun placeAlong(pointer: Int, size: Int, window: Int): Int = when {
    pointer + size <= window -> pointer
    pointer - size >= 0 -> pointer - size
    else -> (window - size).coerceAtLeast(0)
}

/**
 * 按 [layoutActions] 排，与操作面板同一套，只是不收「更多」：顶上一排纯图标（悬停出提示），下面的列表按组留空隔开，
 * 然后是「属性」，危险项垫底。「更多」做过原地换上的一页，菜单只在桌面上、窗口放得下一整列，收起来只多一步，已去掉；
 * 弹出式子菜单也不做，material3 没有，自己做要处理悬停延时与斜穿。
 * 用 DropdownMenuPopup 自己摆，不走 [PikoDropdownMenu]，为的是菜单项形状与分组自己定。
 *
 * 菜单向上翻转（指针下方放不下）时次序不反过来。Apple HIG 的 context menus 建议按菜单出现在内容上方还是下方
 * 反转次序，让常用项靠近手指，那是 iOS 从内容上长出来的菜单；Windows 与 macOS 的右键菜单都不反转，
 * 反转后图标行沉到底、危险项升到顶，键盘从第一项起走，位置记忆也随窗口位置变。
 *
 * 桌面端 DropdownMenu 额外做的两件事 DropdownMenuPopup 没有，这里补上：超出窗口时滚动；方向键在菜单项间
 * 移动焦点。后者照 M3 menus 的 Keyboard navigation 一节：菜单一打开焦点就在第一项上，上下键逐项移动，
 * 左右键在图标行里移动，回车执行，Esc 关掉。
 * 焦点落进菜单之后，按键先经过这里的 onPreviewKeyEvent，Popup 本身不必暴露按键回调。
 */
@Composable
private fun ActionMenu(actions: List<SheetAction>, positionProvider: DropdownMenuPopupPositionProvider, onDismiss: () -> Unit) {
    val layout = layoutActions(actions, foldMore = false)
    val firstItem = remember { FocusRequester() }
    DropdownMenuPopup(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.verticalScroll(rememberScrollState()),
        popupPositionProvider = positionProvider,
    ) {
        // 弹层是另一层，有自己的焦点。要在这里面取：在外面取到的是窗口主层的，方向键会去挪列表里的焦点
        val focusManager = LocalFocusManager.current
        LaunchedEffect(Unit) { runCatching { firstItem.requestFocus() } }
        // 一个固定形状的容器，组与组之间只留空，不画线。各组各带容器、之间留缝（M3E 竖向菜单的分组）在四五组时像一摞碎块；
        // 也不用 DropdownMenuGroup：它的形状随悬停与焦点在两种圆角间变形，指针一动圆角就闪。
        // 宽度固定：按内容定宽时不同条目的菜单宽窄不一
        Surface(
            // 取分组容器的圆角，只要它静止时的那一个：MenuDefaults.shape 是基线菜单的 4dp，与菜单项的大圆角对不上
            shape = MenuDefaults.groupShape(0, 1).shape,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
            modifier = Modifier.width(ActionMenuWidth).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                    else -> false
                }
            },
        ) {
            Column(Modifier.padding(vertical = MenuGroupGap / 2)) {
                MenuRows(layout, firstItem, onDismiss)
            }
        }
    }
}

@Composable
private fun MenuRows(layout: ActionLayout, firstItem: FocusRequester, onDismiss: () -> Unit) {
    val blocks = layout.sections + listOf(layout.properties, layout.danger).filter { it.isNotEmpty() }
    // 图标行算一行，首末两行的选中底色才贴着容器的圆角
    val firstListRow = if (layout.quick.isNotEmpty()) 1 else 0
    val rowCount = firstListRow + blocks.sumOf { it.size }
    var row = 0
    // 打开时焦点落在列表的第一行，不在图标行：图标按钮得了焦点就弹出提示，盖住下面一行。按上键能回到图标行
    if (layout.quick.isNotEmpty()) {
        MenuIconRow(layout.quick, firstItem.takeIf { blocks.isEmpty() }, onDismiss)
        row++
    }
    blocks.forEach { block ->
        if (row > 0) MenuDivider()
        block.forEach { action ->
            ActionMenuItem(
                action = action,
                shape = menuItemShape(row, rowCount),
                onDismiss = onDismiss,
                modifier = if (row == firstListRow) Modifier.focusRequester(firstItem) else Modifier,
            )
            row++
        }
    }
}

/** 顶上一排纯图标，名字在悬停提示里，照 HIG 菜单顶部的小号图标行（四项、只有图标）。 */
@Composable
private fun MenuIconRow(actions: List<SheetAction>, firstItem: FocusRequester?, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        actions.forEachIndexed { index, action ->
            TooltipIconButton(
                icon = action.icon,
                label = action.label,
                onClick = {
                    onDismiss()
                    action.onClick()
                },
                modifier = Modifier
                    .prepareOnPointer(action)
                    .then(if (index == 0 && firstItem != null) Modifier.focusRequester(firstItem) else Modifier),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 组与组之间的空隙，代替分隔线。 */
@Composable
private fun MenuDivider() {
    Spacer(Modifier.height(MenuGroupGap))
}

private val MenuGroupGap = 6.dp

private val MenuItemHeight = 40.dp

// 行高压到 40dp 后，条目默认的上下内边距会把图标挤小，只留左右的
private val MenuItemPadding = PaddingValues(horizontal = 12.dp)

private val MenuIconSize = 20.dp

@Composable
private fun MenuItemIcon(icon: ImageVector, tint: Color = LocalContentColor.current) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(MenuIconSize))
}

// 放得下最长的条目（「从快速访问取消固定」）与图标行的四个按钮
private val ActionMenuWidth = 224.dp

/**
 * 行高 40dp（[MenuItemHeight]），图标与字号用组件库的默认值。M3 menus 的 48dp 是按手指定的，整份菜单十来行时
 * 在桌面上显得臃肿；36dp 一档试过，低视力用户与平板上接鼠标的点不准，取两者之间。
 */
@Composable
private fun ActionMenuItem(action: SheetAction, shape: Shape, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tint = when {
        action.destructive -> colors.error
        action.checked == true -> colors.primary
        else -> colors.onSurfaceVariant
    }
    DropdownMenuItem(
        text = { Text(action.label) },
        shape = shape,
        modifier = modifier.height(MenuItemHeight).prepareOnPointer(action),
        contentPadding = MenuItemPadding,
        leadingIcon = { MenuItemIcon(action.icon, tint) },
        trailingIcon = if (action.checked == true) {
            { Icon(Icons.Outlined.Check, contentDescription = "当前", tint = colors.primary, modifier = Modifier.size(MenuIconSize)) }
        } else {
            null
        },
        colors = if (action.destructive) {
            MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error)
        } else {
            MenuDefaults.itemColors()
        },
        onClick = {
            onDismiss()
            action.onClick()
        },
    )
}

/** 按钮锚定的操作菜单，与右键菜单使用相同条目样式。 */
@Composable
fun ActionDropdownMenu(expanded: Boolean, actions: List<SheetAction>, onDismiss: () -> Unit) {
    PikoDropdownMenu(expanded, onDismiss) {
        actions.forEachIndexed { index, action ->
            ActionMenuItem(action, menuItemShape(index, actions.size), onDismiss)
        }
    }
}
