package dev.piko.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * 无协程右键按下检测（基于 [PointerInputModifierNode]）：
 * 消除列表与网格滚动项上挂起式 `Modifier.pointerInput` 的协程分配与 `PointerInputResetException` 抖动。
 * 运行于 [PointerEventPass.Main]（由内向外），嵌套时内层条目先消费，外层空白区域不再误弹。
 */
fun Modifier.onSecondaryPress(
    enabled: Boolean,
    onPress: (Offset) -> Unit,
): Modifier = this then SecondaryPressElement(enabled, onPress)

private class SecondaryPressElement(
    private val enabled: Boolean,
    private val onPress: (Offset) -> Unit,
) : ModifierNodeElement<SecondaryPressNode>() {
    override fun create(): SecondaryPressNode = SecondaryPressNode(enabled, onPress)

    override fun update(node: SecondaryPressNode) {
        node.enabled = enabled
        node.onPress = onPress
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "onSecondaryPress"
        properties["enabled"] = enabled
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecondaryPressElement) return false
        return enabled == other.enabled && onPress == other.onPress
    }

    override fun hashCode(): Int = 31 * enabled.hashCode() + onPress.hashCode()
}

private class SecondaryPressNode(
    var enabled: Boolean,
    var onPress: (Offset) -> Unit,
) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (!enabled || pass != PointerEventPass.Main) return
        if (pointerEvent.type != PointerEventType.Press || !pointerEvent.buttons.isSecondaryPressed) return
        val change = pointerEvent.changes.firstOrNull() ?: return
        if (change.isConsumed) return
        change.consume()
        onPress(change.position)
    }

    override fun onCancelPointerInput() = Unit
}

/**
 * 无协程指针悬停检测（基于 [PointerInputModifierNode]）：
 * 替代每行/每格 `MutableInteractionSource` + `Modifier.hoverable` + `collectIsHoveredAsState()` 协程采集器，
 * 滚动复用或卸载时自动复位悬停态，避免幽灵悬停与协程风暴。
 */
fun Modifier.onPointerHover(
    onHoverChange: (Boolean) -> Unit,
): Modifier = this then PointerHoverElement(onHoverChange)

private class PointerHoverElement(
    private val onHoverChange: (Boolean) -> Unit,
) : ModifierNodeElement<PointerHoverNode>() {
    override fun create(): PointerHoverNode = PointerHoverNode(onHoverChange)

    override fun update(node: PointerHoverNode) {
        node.onHoverChange = onHoverChange
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "onPointerHover"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PointerHoverElement) return false
        return onHoverChange == other.onHoverChange
    }

    override fun hashCode(): Int = onHoverChange.hashCode()
}

private class PointerHoverNode(
    var onHoverChange: (Boolean) -> Unit,
) : Modifier.Node(), PointerInputModifierNode {
    private var hovered = false

    private fun setHovered(next: Boolean) {
        if (hovered != next) {
            hovered = next
            onHoverChange(next)
        }
    }

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        if (pass != PointerEventPass.Main) return
        when (pointerEvent.type) {
            PointerEventType.Enter -> {
                if (pointerEvent.changes.firstOrNull()?.type != PointerType.Touch) {
                    setHovered(true)
                }
            }
            PointerEventType.Exit -> setHovered(false)
        }
    }

    override fun onCancelPointerInput() {
        setHovered(false)
    }

    override fun onDetach() {
        setHovered(false)
    }
}
