package dev.piko.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.piko.ui.theme.IslandCorner
import dev.piko.ui.theme.islandHeader

/**
 * 岛上沿的一个标签，网盘页的位置标签与传输页的类别共用。照资源管理器与浏览器：活动的那个是一块上沿圆角、
 * 下沿向两侧外翻的「书签」，底色与下面那块岛的页眉相同，连成一片，像是从页眉里伸上来的；其余的平铺在外框色上，
 * 悬停时是半透明的同一个形状，彼此用细线隔开。切换时新旧两个各自淡入淡出。
 * 原来网盘页是一排胶囊、传输页是一组连体按钮，活动的只是换了个底色，与下面的内容断开，看不出眼前是哪一个。
 *
 * [first] 是最左的一个：左边不外翻，活动时竖直接上岛的左边，岛的左上角随之不圆，见 [islandTopStart]。
 * 外翻的那一截只是画出来的，不接点击：相邻标签的外翻会叠在一起。
 */
@Composable
fun IslandTab(
    active: Boolean,
    first: Boolean,
    divider: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val hovered by interactionSource.collectIsHoveredAsState()
    val fill by animateFloatAsState(
        targetValue = when {
            active -> 1f
            hovered -> HoverFill
            else -> 0f
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "tabFill",
    )
    Row(
        modifier = modifier
            .height(IslandTabHeight)
            .drawBehind {
                if (fill > 0f) {
                    drawPath(tabOutline(size, TabFlare.toPx(), IslandCorner.toPx(), flushStart = first), colors.islandHeader.copy(alpha = fill))
                }
                if (divider && !hovered) {
                    val inset = 10.dp.toPx()
                    drawLine(colors.outlineVariant, Offset(size.width, inset), Offset(size.width, size.height - inset), strokeWidth = 1.dp.toPx())
                }
            }
            .padding(start = if (first) 0.dp else TabFlare, end = TabFlare)
            .hoverable(interactionSource)
            // 与 M3 的 Tab 相同用 selectable：只用 clickable 时活动标签只是底色不同，读屏说不出哪个是当前的
            .selectable(selected = active, role = Role.Tab, indication = null, interactionSource = interactionSource, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 第一个标签活动时，下面那块岛的左上角不圆，与标签的左边连成一条竖线。 */
fun islandTopStart(firstActive: Boolean) = if (firstActive) 0.dp else IslandCorner

/** 标签栏那一行的高度，标签贴着它的下沿。 */
val IslandTabBarHeight = 44.dp

private val IslandTabHeight = 36.dp
private val TabFlare = 8.dp
private const val HoverFill = 0.5f

/**
 * 上沿两角是 [corner] 的圆角，下沿两侧各向外翻出 [flare] 的一段凹弧，与下面的页眉接上。
 * [flushStart] 时左边不外翻、从底边竖直上去，接着岛的左边。
 */
private fun tabOutline(size: Size, flare: Float, corner: Float, flushStart: Boolean): Path = Path().apply {
    val w = size.width
    val h = size.height
    if (flushStart) {
        moveTo(0f, h)
        lineTo(0f, corner)
        quadraticTo(0f, 0f, corner, 0f)
    } else {
        moveTo(0f, h)
        quadraticTo(flare, h, flare, h - flare)
        lineTo(flare, corner)
        quadraticTo(flare, 0f, flare + corner, 0f)
    }
    lineTo(w - flare - corner, 0f)
    quadraticTo(w - flare, 0f, w - flare, corner)
    lineTo(w - flare, h - flare)
    quadraticTo(w - flare, h, w, h)
    close()
}
