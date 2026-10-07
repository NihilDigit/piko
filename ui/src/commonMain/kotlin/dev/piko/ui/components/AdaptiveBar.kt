package dev.piko.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.ui.platform.windowDragArea

/**
 * 横排里的一样东西。[priority] 越大越晚收起，[PinnedPriority] 的一直摆着。
 * [overflow] 是收起后在「更多」里的样子，为空的收起即不显示。
 */
class BarItem(
    val key: String,
    val priority: Int,
    val overflow: List<SheetAction> = emptyList(),
    val isDivider: Boolean = false,
    val content: @Composable () -> Unit,
)

const val PinnedPriority = Int.MAX_VALUE

/** 把别处定义好的一项操作摆成图标按钮，名字、图标与是否危险照它，与它在菜单里的样子一致。 */
fun SheetAction.asBarItem(priority: Int, shortcut: String? = null, enabled: Boolean = true): BarItem =
    iconBarItem(icon, label, onClick, priority, shortcut = shortcut, destructive = destructive, enabled = enabled)

/** 最常见的一项：图标按钮，收起后是菜单里同名的一项。 */
fun iconBarItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    priority: Int,
    shortcut: String? = null,
    destructive: Boolean = false,
    /** 做不了时按钮变灰；收进「更多」后不列出。 */
    enabled: Boolean = true,
    key: String = label,
): BarItem = BarItem(
    key = key,
    priority = priority,
    overflow = if (enabled) listOf(SheetAction(icon, label, onClick, destructive = destructive)) else emptyList(),
) {
    TooltipIconButton(
        icon = icon,
        label = label,
        onClick = onClick,
        shortcut = shortcut,
        enabled = enabled,
        tint = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            destructive -> MaterialTheme.colorScheme.error
            else -> Color.Unspecified
        },
    )
}

/** 布局时算出的「更多」里的内容。菜单打开时才读，不必经过状态：读它的那次重组总在布局之后。 */
private class OverflowHolder {
    var actions: List<SheetAction> = emptyList()
}

/**
 * 一切横排的容器：工具栏、命令栏、各页顶栏都经它排，保证整行不溢出。
 *
 * 排布：[leading] 从左往右，[middle] 在中间，[trailing] 贴右，[end] 在最右（窗口按钮）。放不下时照 M3 toolbars 的
 * Container 与 Adaptive design 两节：容器要整个露在屏幕上，放不下的操作收进 overflow 菜单，窗口变宽再放出来。
 * 按 [BarItem.priority] 从低往高收，同级的先收靠后的；收起的进「更多」，排在 [moreActions] 前面。
 * 收哪几项只由宽度决定，与上一次的结果无关，拖动窗口边缘时不会来回跳。
 *
 * 原来只有宽窗口的命令栏这样排，各页顶栏是一排 Row 加上 TopAppBar：TopAppBar 先量动作、标题拿剩下的，
 * 动作一多（多选栏七八个按钮再加窗口按钮）标题与导航按钮被挤没，窗口按钮也只露出一个。
 *
 * 用自定义的 Layout 而不是 Row：要先量出各项的宽度，才知道「更多」里放什么、要不要摆出来。
 * 不用 SubcomposeLayout：它在测量时才组合各项，里面的菜单、提示若恰在那时离开组合，
 * 就是在测量途中销毁一层弹层，桌面端整个窗口抛 RootNodeOwner is already disposed（见 desktopApp 的 PikoWindow）。
 */
@Composable
fun AdaptiveBar(
    modifier: Modifier = Modifier,
    leading: List<BarItem> = emptyList(),
    trailing: List<BarItem> = emptyList(),
    /** 中间一格：顶栏的标题、命令栏的分区跳转。 */
    middle: (@Composable () -> Unit)? = null,
    /**
     * 中间一格至少多宽才摆。[reserveMiddle] 为真时收起动作也要给它留出这么宽（标题），
     * 否则只是放不下就不摆（分区跳转）。
     */
    middleMinWidth: Dp = 0.dp,
    middleMaxWidth: Dp = Dp.Infinity,
    reserveMiddle: Boolean = false,
    /** 中间一格撑满两侧之间的空白（标题），否则按内容定宽，余下的是拖动区。 */
    fillMiddle: Boolean = false,
    end: (@Composable () -> Unit)? = null,
    /** 「更多」跟在 [leading] 后面（命令栏），否则在 [trailing] 后面、[end] 前面（顶栏照 M3）。 */
    moreAfterLeading: Boolean = false,
    /** 不论收没收起都给「更多」，里面至少有 [moreActions]。 */
    alwaysMore: Boolean = false,
    moreActions: List<SheetAction> = emptyList(),
    /** 横排的工具栏用横向三点，顶栏照 M3 用竖向三点。 */
    moreIcon: ImageVector = Icons.Outlined.MoreVert,
    gap: Dp = 0.dp,
    /** 中间的空白登记成窗口拖动区。只给属于标题栏的行（贴着窗口顶，或宽窗口的命令栏）。 */
    dragWindow: Boolean = false,
) {
    val overflow = remember { OverflowHolder() }
    val items = leading + trailing
    Layout(
        modifier = modifier,
        content = {
            items.forEach { item -> key(item.key) { Box(contentAlignment = Alignment.Center) { item.content() } } }
            MoreButton(moreIcon) { overflow.actions }
            Box(contentAlignment = Alignment.CenterStart) { middle?.invoke() }
            // 中间余下的空白也能拖动窗口（标题栏并进内容时），只在 [dragWindow] 的行上：列表页眉这类不贴窗口顶的行，
            // 按住空白拖动窗口就错了。一直摆着，没有空白时宽度为 0，登记的拖动区随之为空
            Box(if (dragWindow) Modifier.fillMaxSize().windowDragArea() else Modifier.fillMaxSize())
            Box(contentAlignment = Alignment.Center) { end?.invoke() }
        },
    ) { measurables, constraints ->
        // 高度按内容定，再夹进约束：TopAppBar 给标题格的高度上限是整个窗口，取上限的话顶栏撑满全屏（实测）；
        // 命令栏外面写死了 48dp，上下限相同，照旧是 48
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val placeables = measurables.subList(0, items.size).map { it.measure(loose) }
        val more = measurables[items.size].measure(loose)
        val middleMeasurable = measurables[items.size + 1]
        val dragMeasurable = measurables[items.size + 2]
        val endPlaceable = measurables[items.size + 3].measure(loose)
        val contentHeight = (placeables + more + endPlaceable).maxOf { it.height }
        val gapPx = gap.roundToPx()
        val reserved = endPlaceable.width + if (reserveMiddle && middle != null) middleMinWidth.roundToPx() else 0

        val shown = BooleanArray(items.size) { true }
        fun needsMore() = alwaysMore || items.indices.any { !shown[it] && items[it].overflow.isNotEmpty() }
        // 分隔只在 leading 里，两边都有东西时才摆；左段最后一道的右边是「更多」
        fun dividerShown(index: Int): Boolean {
            if (index >= leading.size || (0 until index).none { !items[it].isDivider && shown[it] }) return false
            var next = index + 1
            while (next < leading.size && !items[next].isDivider) {
                if (shown[next]) return true
                next++
            }
            return next == leading.size && moreAfterLeading && needsMore()
        }
        fun visible(index: Int) = if (items[index].isDivider) dividerShown(index) else shown[index]
        fun totalWidth(): Int {
            var width = 0
            var count = 0
            for (index in items.indices) {
                if (!visible(index)) continue
                width += placeables[index].width
                count++
            }
            if (needsMore()) {
                width += more.width
                count++
            }
            return width + gapPx * (count - 1).coerceAtLeast(0) + reserved
        }
        while (totalWidth() > constraints.maxWidth) {
            val victim = items.indices
                .filter { shown[it] && !items[it].isDivider && items[it].priority != PinnedPriority }
                .minWithOrNull(compareBy<Int>({ items[it].priority }, { -it }))
                ?: break
            shown[victim] = false
        }
        val showMore = needsMore()
        overflow.actions = items.indices.filter { !shown[it] }.flatMap { items[it].overflow } + moreActions

        val leadingIndices = leading.indices.filter(::visible)
        val trailingIndices = (leading.size until items.size).filter(::visible)
        fun rowWidth(indices: List<Int>, withMore: Boolean): Int {
            val widths = indices.map { placeables[it].width } + if (withMore) listOf(more.width) else emptyList()
            return widths.sum() + gapPx * (widths.size - 1).coerceAtLeast(0)
        }
        val leadingWidth = rowWidth(leadingIndices, showMore && moreAfterLeading)
        val leadingEnd = if (leadingWidth > 0) leadingWidth + gapPx else 0
        val trailingWidth = rowWidth(trailingIndices, showMore && !moreAfterLeading)
        val endStart = constraints.maxWidth - endPlaceable.width
        val trailingStart = (endStart - trailingWidth - if (trailingWidth > 0 && endPlaceable.width > 0) gapPx else 0)
            .coerceAtLeast(leadingEnd)
        val room = trailingStart - leadingEnd
        val middlePlaceable = when {
            middle == null || room < middleMinWidth.roundToPx() && !reserveMiddle -> null
            fillMiddle -> middleMeasurable.measure(
                Constraints(minWidth = room.coerceAtLeast(0), maxWidth = room.coerceAtLeast(0), maxHeight = constraints.maxHeight),
            )
            else -> {
                val maxWidth = if (middleMaxWidth == Dp.Infinity) room else minOf(room, middleMaxWidth.roundToPx())
                middleMeasurable.measure(Constraints(maxWidth = maxWidth.coerceAtLeast(0), maxHeight = constraints.maxHeight))
            }
        }
        val height = maxOf(contentHeight, middlePlaceable?.height ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        val dragStart = leadingEnd + (middlePlaceable?.width ?: 0)
        val dragArea = dragMeasurable.measure(Constraints.fixed((trailingStart - dragStart).coerceAtLeast(0), height))
        layout(constraints.maxWidth, height) {
            fun Placeable.placeAt(x: Int) = place(x, (height - this.height) / 2)
            var x = 0
            for (index in leadingIndices) {
                placeables[index].placeAt(x)
                x += placeables[index].width + gapPx
            }
            if (showMore && moreAfterLeading) more.placeAt(x)
            middlePlaceable?.placeAt(leadingEnd)
            dragArea.place(dragStart, 0)
            x = trailingStart
            for (index in trailingIndices) {
                placeables[index].placeAt(x)
                x += placeables[index].width + gapPx
            }
            if (showMore && !moreAfterLeading) more.placeAt(x)
            endPlaceable.placeAt(endStart)
        }
    }
}

@Composable
private fun MoreButton(icon: ImageVector, actions: () -> List<SheetAction>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(icon, "更多", { expanded = true })
        OverflowMenu(expanded, { expanded = false }, if (expanded) actions() else emptyList())
    }
}

/** 下拉菜单里的一组操作。组号变了画一道细线，收进「更多」的几组与它原有的几项由此分开；几选一的当前项打勾。 */
@Composable
fun OverflowMenu(expanded: Boolean, onDismiss: () -> Unit, actions: List<SheetAction>) = MenuMotion {
    PikoDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        actions.forEachIndexed { index, action ->
            if (index > 0 && actions[index - 1].group != action.group) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            val tint = when {
                action.destructive -> MaterialTheme.colorScheme.error
                action.checked == true -> MaterialTheme.colorScheme.primary
                else -> Color.Unspecified
            }
            DropdownMenuItem(
                text = { Text(action.label, color = tint) },
                leadingIcon = { Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (action.checked == true) {
                    { Icon(Icons.Outlined.Check, contentDescription = "当前", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                } else {
                    null
                },
                shape = menuItemShape(index, actions.size),
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}
