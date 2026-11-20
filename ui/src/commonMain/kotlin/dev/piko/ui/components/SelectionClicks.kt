package dev.piko.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier

/**
 * 文件管理器的点选，资源管理器与 Finder 一致：
 * - 按着主修饰键（Ctrl，mac 上是 ⌘）点一项是加选或取消这一项，按着 Shift 是从上次点选的那一项选到这里。
 * - [onDoubleClick] 不为 null 时，鼠标单击只是选中（条目取得焦点，由外层处理），双击才调它打开。
 *
 * 触屏不经这一层：没有修饰键，轻点照旧打开，长按照旧进入多选。
 *
 * 修饰键点选在 Initial 阶段截下按下并消费掉，条目自己的单击只认未被消费的按下，于是这一下不会同时打开它。
 * 普通单击截的是松开而不是按下：拖放（fileDragSource）要看到未被消费的按下才起拖，按下截走就拖不动了。
 * 松开被消费后，条目自己的点击判定为取消，不会打开。
 *
 * 合并为单个零协程 [PointerInputModifierNode] + [GlobalPositionAwareModifierNode]，消除滚动网格中
 * 每单元格的挂起协程与 `PointerInputResetException` 抖动。
 */
@Composable
fun Modifier.selectionClicks(
    onToggle: () -> Unit,
    onExtend: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    /** 条目里自己接点击的控件登记的范围，按在这里的一下不截，见 [OwnClicks]。 */
    ownClicks: OwnClicks? = null,
    /** 鼠标中键点击（可选，合并进同一指针节点以减少每单元格节点数）。 */
    onMiddleClick: (() -> Unit)? = null,
): Modifier {
    val shortcut = LocalPikoPlatform.current.shortcutModifier
    return this then SelectionClicksElement(
        shortcut = shortcut,
        ownClicks = ownClicks,
        onToggle = onToggle,
        onExtend = onExtend,
        onDoubleClick = onDoubleClick,
        onFocus = onFocus,
        onMiddleClick = onMiddleClick,
    )
}

private class SelectionClicksElement(
    private val shortcut: ShortcutModifier,
    private val ownClicks: OwnClicks?,
    private val onToggle: () -> Unit,
    private val onExtend: () -> Unit,
    private val onDoubleClick: (() -> Unit)?,
    private val onFocus: (() -> Unit)?,
    private val onMiddleClick: (() -> Unit)?,
) : ModifierNodeElement<SelectionClicksNode>() {
    override fun create(): SelectionClicksNode = SelectionClicksNode(
        shortcut = shortcut,
        ownClicks = ownClicks,
        onToggle = onToggle,
        onExtend = onExtend,
        onDoubleClick = onDoubleClick,
        onFocus = onFocus,
        onMiddleClick = onMiddleClick,
    )

    override fun update(node: SelectionClicksNode) {
        node.shortcut = shortcut
        node.ownClicks = ownClicks
        node.onToggle = onToggle
        node.onExtend = onExtend
        node.onDoubleClick = onDoubleClick
        node.onFocus = onFocus
        node.onMiddleClick = onMiddleClick
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "selectionClicks"
        properties["shortcut"] = shortcut
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SelectionClicksElement) return false
        return shortcut == other.shortcut &&
            ownClicks == other.ownClicks &&
            onToggle == other.onToggle &&
            onExtend == other.onExtend &&
            onDoubleClick == other.onDoubleClick &&
            onFocus == other.onFocus &&
            onMiddleClick == other.onMiddleClick
    }

    override fun hashCode(): Int {
        var result = shortcut.hashCode()
        result = 31 * result + (ownClicks?.hashCode() ?: 0)
        result = 31 * result + onToggle.hashCode()
        result = 31 * result + onExtend.hashCode()
        result = 31 * result + (onDoubleClick?.hashCode() ?: 0)
        result = 31 * result + (onFocus?.hashCode() ?: 0)
        result = 31 * result + (onMiddleClick?.hashCode() ?: 0)
        return result
    }
}

private class SelectionClicksNode(
    var shortcut: ShortcutModifier,
    var ownClicks: OwnClicks?,
    var onToggle: () -> Unit,
    var onExtend: () -> Unit,
    var onDoubleClick: (() -> Unit)?,
    var onFocus: (() -> Unit)?,
    var onMiddleClick: (() -> Unit)?,
) : Modifier.Node(), PointerInputModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var originX = 0f
    private var originY = 0f
    private var pressed: PointerId? = null
    private var pressedAt = Offset.Zero
    private var lastClickAt = 0L
    private var lastClickPosition = Offset.Zero

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val pos = coordinates.positionInRoot()
        originX = pos.x
        originY = pos.y
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (pass != PointerEventPass.Initial) return
        val change = pointerEvent.changes.firstOrNull() ?: return
        when (pointerEvent.type) {
            PointerEventType.Press -> {
                pressed = null
                if (ownClicks?.contains(Offset(originX, originY) + change.position) == true) return
                if (!change.isConsumed && pointerEvent.buttons.isTertiaryPressed) {
                    onMiddleClick?.invoke()
                    return
                }
                if (change.type == PointerType.Mouse && pointerEvent.buttons.isSecondaryPressed && onDoubleClick != null) {
                    onFocus?.invoke()
                    return
                }
                if (!pointerEvent.buttons.isPrimaryPressed) return
                val modifiers = pointerEvent.keyboardModifiers
                val action = when {
                    modifiers.isShiftPressed -> onExtend
                    shortcut.isPressed(modifiers) -> onToggle
                    else -> null
                }
                if (action != null) {
                    pointerEvent.changes.forEach { it.consume() }
                    action()
                } else if (change.type == PointerType.Mouse && onDoubleClick != null) {
                    pressed = change.id
                    pressedAt = change.position
                }
            }
            PointerEventType.Move -> {
                val slopPx = with(currentValueOf(LocalDensity)) { MouseClickSlopDp.dp.toPx() }
                if (change.id == pressed && (change.isConsumed || (change.position - pressedAt).getDistance() > slopPx)) {
                    pressed = null
                }
            }
            PointerEventType.Release -> {
                if (change.id != pressed) return
                pressed = null
                val open = onDoubleClick ?: return
                val slopPx = with(currentValueOf(LocalDensity)) { MouseClickSlopDp.dp.toPx() }
                // 挪过了就是拖动或框选，不算一次点击
                if (change.isConsumed || (change.position - pressedAt).getDistance() > slopPx) return
                change.consume()
                onFocus?.invoke()
                val now = change.uptimeMillis
                val doubleTapTimeout = currentValueOf(LocalViewConfiguration).doubleTapTimeoutMillis
                val isDouble = now - lastClickAt <= doubleTapTimeout &&
                    (change.position - lastClickPosition).getDistance() <= slopPx
                if (isDouble) {
                    // 清零：连点三下是一次双击加一次单击，不是两次双击
                    lastClickAt = 0L
                    open()
                } else {
                    lastClickAt = now
                    lastClickPosition = change.position
                }
            }
        }
    }

    override fun onCancelPointerInput() {
        pressed = null
    }

    override fun onDetach() {
        pressed = null
    }
}

/**
 * 条目里自己接点击的控件（详情按钮）的范围，按窗口坐标记。[selectionClicks] 在 Initial 阶段截走条目上的单击，
 * Initial 由外向内传，里面的按钮收到时松开已被消费，点击判为取消：条目上的三点按钮用鼠标从来点不动。
 * 按钮经 [ownsClicks] 登记到所在条目，那一下就交给它。
 */
class OwnClicks {
    private val areas = mutableMapOf<Any, Rect>()

    internal fun put(key: Any, bounds: Rect) {
        areas[key] = bounds
    }

    internal fun remove(key: Any) {
        areas.remove(key)
    }

    fun contains(point: Offset): Boolean = areas.values.any { it.contains(point) }
}

/** 所在条目的 [OwnClicks]，由条目提供；不在条目里时为 null，[ownsClicks] 什么也不做。 */
val LocalOwnClicks = staticCompositionLocalOf<OwnClicks?> { null }

/** 把这个控件登记为自己接点击，见 [OwnClicks]。 */
@Composable
fun Modifier.ownsClicks(): Modifier {
    val owner = LocalOwnClicks.current ?: return this
    val key = remember { Any() }
    DisposableEffect(owner) { onDispose { owner.remove(key) } }
    return onGloballyPositioned { owner.put(key, it.boundsInRoot()) }
}

private const val MouseClickSlopDp = 4
