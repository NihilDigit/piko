package dev.piko.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*

/**
 * 属性卡片放在哪要用到的几样位置，都在列表这一块（[itemPanelAnchorArea] 挂的那一层）的坐标里。
 * 不进快照：右键按下的点与各条目的位置随点击、滚动一直在变，只在打开属性的那一刻读一次，不必为它们重组。
 */
internal class ItemPanelAnchors {
    private var area: LayoutCoordinates? = null
    private var press: Offset? = null
    // 条目的布局坐标是活的，滚动后读到的仍是眼下的位置；换目录时清掉
    private val items = HashMap<String, LayoutCoordinates>()

    fun placed(id: String, coordinates: LayoutCoordinates) {
        items[id] = coordinates
    }

    fun forgetItems() = items.clear()

    fun forgetPress() {
        press = null
    }

    /** 右键菜单里点的「属性」：放在右键按下的那一点，用过即忘；没有那一点（操作面板里点的）就贴着条目。 */
    fun fromMenu(id: String): PanelAnchor {
        val point = press ?: return beside(id)
        press = null
        return with(density) { PanelAnchor.AtPoint(DpOffset(point.x.toDp(), point.y.toDp())) }
    }

    /** 贴着这一项；它已滚出视野、量不到时放在中央偏上。 */
    fun beside(id: String): PanelAnchor {
        val area = area?.takeIf { it.isAttached } ?: return PanelAnchor.Centered
        val item = items[id]?.takeIf { it.isAttached } ?: return PanelAnchor.Centered
        val box = area.localBoundingBoxOf(item, clipBounds = false)
        return with(density) { PanelAnchor.Beside(DpRect(box.left.toDp(), box.top.toDp(), box.right.toDp(), box.bottom.toDp())) }
    }

    private var density: Density = Density(1f)

    fun attach(coordinates: LayoutCoordinates, density: Density) {
        area = coordinates
        this.density = density
    }

    fun pressed(at: Offset) {
        press = at
    }
}

/** 挂在列表这一块上：记下它的坐标与右键按下的位置，见 [ItemPanelAnchors]。只看不吃，事件照常往下走。 */
internal fun Modifier.itemPanelAnchorArea(anchors: ItemPanelAnchors): Modifier = composed {
    val density = LocalDensity.current
    onPlaced { anchors.attach(it, density) }
        .pointerInput(anchors) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                        event.changes.firstOrNull()?.let { anchors.pressed(it.position) }
                    }
                }
            }
        }
}
