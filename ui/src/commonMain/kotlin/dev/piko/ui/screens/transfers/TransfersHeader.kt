package dev.piko.ui.screens.transfers

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.connectedToggleShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.SnailModeToggle
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.components.verticalWheelScrollsRow
import dev.piko.ui.platform.rememberCaptionSlot
import dev.piko.ui.platform.windowDragArea

/**
 * 传输页的页头。速度与蜗牛模式在底栏（TransfersFooter）。
 *
 * compact 是手机的样子：标题「传输」、类型筛选与「全部继续」「清除已完成」图标按钮同在一行，见 [CompactTransfersHeader]；
 * 有选中项时顶栏换成上下文顶栏（关闭、「已选择 N 项」与批量操作），与网盘页的多选顶栏同一形状。
 *
 * 更宽时只有一行：左边是类型筛选，右边是操作，有选中项时换成「已选 N 项」与批量操作。宽窗口是带字的按钮，
 * medium 放不下，换成图标按钮，名字在悬停提示里。整行铺满窗口宽、内容按 [sidePadding] 缩进：
 * 这一行就是贴着窗口右上角的那一行，标题栏并进内容时窗口按钮画在它末尾，中间的空白也是拖动区。
 *
 * 操作做不了时不出现（没有暂停的就没有「全部继续」），不摆灰按钮。
 */
@Composable
internal fun TransfersHeader(
    state: TransfersState,
    selectedCount: Int,
    compact: Boolean,
    wide: Boolean,
    sidePadding: Dp,
    /** 一项传输也没有时不给筛选：四个 0 只是占地方。 */
    showFilter: Boolean,
    onPauseSelected: (() -> Unit)?,
    onResumeSelected: (() -> Unit)?,
    onDeleteSelected: () -> Unit,
    /** 列表已离开顶端。compact 的页头据此换成 surfaceContainer，与内容分开（M3 top app bar 的 on scroll 状态）。 */
    scrolled: Boolean = false,
) {
    if (compact) {
        CompactTransfersHeader(state, selectedCount, showFilter, scrolled, onPauseSelected, onResumeSelected, onDeleteSelected)
        return
    }
    val caption = rememberCaptionSlot()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(caption.modifier)
            // 手机与平板是 edge-to-edge，这一行不是 TopAppBar，状态栏要自己让开；桌面端这份内边距为零。
            // 挂在 caption.modifier 之后：它按这一行的上沿是否贴着窗口顶判断要不要画窗口按钮
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .heightIn(min = 56.dp)
            .padding(start = sidePadding + 12.dp, end = if (caption.buttons != null) 8.dp else sidePadding + 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 筛选不带权重：与后面的拖动空白各带一份权重时两者平分剩余宽度，筛选用不完的那一半空在行尾，
        // 把右边的按钮连同窗口按钮一起推离窗口右上角（实测）
        if (showFilter) {
            KindFilter(
                current = state.filter,
                counts = state.counts,
                onChange = state::changeFilter,
            )
        }
        Spacer(Modifier.weight(1f).height(40.dp).windowDragArea())
        if (selectedCount > 0) {
            Text(
                "已选 $selectedCount 项",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            onPauseSelected?.let { HeaderAction(Icons.Outlined.Pause, "暂停", wide, it) }
            onResumeSelected?.let { HeaderAction(Icons.Outlined.PlayArrow, "继续", wide, it) }
            HeaderAction(Icons.Outlined.Delete, "删除", wide, onDeleteSelected, destructive = true, shortcut = "Delete")
            TooltipIconButton(Icons.Outlined.Close, "取消选择", state::clearSelection, shortcut = "Esc")
        } else {
            if (state.canResumeAll) HeaderAction(Icons.Outlined.PlayArrow, "全部继续", wide, state::resumeAll)
            if (state.canClearCompleted) HeaderAction(Icons.Outlined.ClearAll, "清除已完成", wide, state::clearCompleted)
        }
        caption.buttons?.invoke()
    }
}

/**
 * 手机上的页头只有一行：标题「传输」、类型筛选、操作。筛选原来另起一行，页头连状态栏占去一百多 dp，
 * 手机上一屏本就放不下几项传输。筛选夹在中间，四个按钮等宽铺满标题与操作之间的宽度。
 *
 * 列表滚离顶端时底色换成 surfaceContainer（[scrolled]），与 M3 top app bar 的 on scroll 状态相同，
 * 页头与滚到它下面的内容分得开；颜色渐变过去，不跳。有选中项时仍是上下文顶栏，同样随滚动换色。
 */
@Composable
private fun CompactTransfersHeader(
    state: TransfersState,
    selectedCount: Int,
    showFilter: Boolean,
    scrolled: Boolean,
    onPauseSelected: (() -> Unit)?,
    onResumeSelected: (() -> Unit)?,
    onDeleteSelected: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (scrolled) colors.surfaceContainer else colors.surface,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    if (selectedCount > 0) {
        PikoTopBar(
            title = "已选择 $selectedCount 项",
            navigationIcon = { TooltipIconButton(Icons.Outlined.Close, "取消选择", state::clearSelection, shortcut = "Esc") },
            actions = {
                onPauseSelected?.let { HeaderAction(Icons.Outlined.Pause, "暂停", wide = false, it) }
                onResumeSelected?.let { HeaderAction(Icons.Outlined.PlayArrow, "继续", wide = false, it) }
                HeaderAction(Icons.Outlined.Delete, "删除", wide = false, onDeleteSelected, destructive = true, shortcut = "Delete")
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = container, titleContentColor = colors.onSurface),
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "传输",
            style = MaterialTheme.typography.titleLargeEmphasized,
            maxLines = 1,
        )
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            // 四类按钮等宽铺满标题与操作之间的这一段：手机宽度正好放得下，不必滚动；各按内容定宽时
            // 挤在左边、宽窄不一，右边空出一截，连体按钮看着像没摆完
            if (showFilter) {
                KindFilter(
                    current = state.filter,
                    counts = state.counts,
                    onChange = state::changeFilter,
                    contentPadding = PaddingValues(end = 8.dp),
                    itemPadding = 8.dp,
                    fillWidth = true,
                )
            }
        }
        if (state.canResumeAll) HeaderAction(Icons.Outlined.PlayArrow, "全部继续", wide = false, state::resumeAll)
        if (state.canClearCompleted) HeaderAction(Icons.Outlined.ClearAll, "清除已完成", wide = false, state::clearCompleted)
    }
}

@Composable
private fun HeaderAction(
    icon: ImageVector,
    label: String,
    wide: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    shortcut: String? = null,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified
    if (wide) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(icon, contentDescription = null, tint = if (destructive) color else MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = if (destructive) color else Color.Unspecified)
        }
    } else {
        TooltipIconButton(icon, label, onClick, shortcut = shortcut, tint = color)
    }
}

/**
 * 类型筛选，M3 连体按钮。某一类为空时照样列出、数目为 0：按钮随任务出现消失的话，一行的位置老在变。
 * 形状不随按下与选中变：库里的连体按钮在几种形状间用弹簧过渡，内侧小圆角冲过头算出负值，
 * CornerBasedShape 直接抛异常（网盘页的视图按钮实测崩过）。
 */
@Composable
private fun KindFilter(
    current: TransferKind,
    counts: Map<TransferKind, Int>,
    onChange: (TransferKind) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    /** 每个按钮两侧的内边距。手机上与标题挤在一行，收窄一些。 */
    itemPadding: Dp = 16.dp,
    /** 为 true 时各按钮等宽铺满可用宽度，不滚动；否则按内容定宽，放不下时横向滚动。 */
    fillWidth: Boolean = false,
) {
    val kinds = TransferKind.entries
    val scroll = rememberScrollState()
    Row(
        modifier = if (fillWidth) {
            modifier.fillMaxWidth().padding(contentPadding)
        } else {
            modifier.verticalWheelScrollsRow(scroll).horizontalScroll(scroll).padding(contentPadding)
        },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        kinds.forEachIndexed { index, kind ->
            ToggleButton(
                checked = kind == current,
                onCheckedChange = { if (kind != current) onChange(kind) },
                shapes = connectedToggleShapes(index, kinds.size),
                contentPadding = PaddingValues(horizontal = itemPadding),
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
            ) {
                FilterLabel(kind.label, counts[kind] ?: 0)
            }
        }
    }
}

@Composable
private fun RowScope.FilterLabel(label: String, count: Int) {
    Text(label, maxLines = 1)
    Spacer(Modifier.width(6.dp))
    Text(count.toString(), style = MaterialTheme.typography.labelMedium, maxLines = 1)
}
