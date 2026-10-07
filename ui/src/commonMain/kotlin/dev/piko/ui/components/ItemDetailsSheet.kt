package dev.piko.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 单个条目的详情与操作面板，网盘列表、海报墙与传输列表共用。
 *
 * 用模态面板（[PikoSheet]，宽窗口是侧边面板）而不是锚在更多按钮上的 DropdownMenu：面板顶部能放下完整、
 * 可选中复制的标题与元信息，列表里被截成两行的长名字在这里总能看全；操作项的
 * 触控区也按列表项给足。
 *
 * 头部 [headerIcon] 填满 40dp 的圆角方块，调用方应只放类型图标，不放缩略图：
 * 从这里绕过防窥遮蔽看到画面不符合用户预期。[metaParts] 由 [MetaRow] 排成一行，
 * [extraLines] 放在其下，默认是 bodyMedium，调用方自定颜色。
 *
 * 操作按 [layoutActions] 排，与右键菜单同一套：顶上一排图标加短标签，下面是 M3 Expressive 的分段列表，
 * 一组一段，「更多」是列表末尾一行、点开就地展开，然后是「属性」，危险项垫底。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailsSheet(
    title: String,
    headerIcon: @Composable () -> Unit,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
    metaParts: List<String> = emptyList(),
    extraLines: @Composable ColumnScope.() -> Unit = {},
    /** 标题下面的一行，网盘条目在标题是显示名时写真实名称。与标题一起可选中复制。 */
    subtitle: String? = null,
) {
    PikoSheet(onDismissRequest = onDismiss) {
        val sheet = this
        // 矮屏上放不下全部操作，整块可滚动
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(MaterialTheme.shapes.small),
                ) {
                    headerIcon()
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SelectionContainer {
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (subtitle != null) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    if (metaParts.isNotEmpty()) {
                        MetaRow(
                            parts = metaParts,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        extraLines()
                    }
                }
            }
            SheetActions(layoutActions(actions), onAction = { sheet.hideThen(it) })
        }
    }
}

@Composable
private fun SheetActions(layout: ActionLayout, onAction: (() -> Unit) -> Unit) {
    var moreExpanded by remember { mutableStateOf(false) }
    val moreRow = MoreRow(moreExpanded, onToggle = { moreExpanded = !moreExpanded }).takeIf { layout.more.isNotEmpty() }
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(SheetGroupGap),
    ) {
        if (layout.quick.isNotEmpty()) {
            // 面板是 surfaceContainerLow，与下面的分段同取高两级
            ActionIconRow(layout.quick, onAction = { onAction(it.onClick) }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
        // 「更多」接在列表最后一段的末尾，不自成一段：单独一段的话，加上属性与危险项，面板里尽是一行一块的碎块
        layout.sections.forEachIndexed { index, section ->
            SheetActionGroup(section, onAction, more = moreRow.takeIf { index == layout.sections.lastIndex })
        }
        if (layout.sections.isEmpty() && moreRow != null) SheetActionGroup(emptyList(), onAction, more = moreRow)
        AnimatedVisibility(visible = moreExpanded && moreRow != null) {
            Column(verticalArrangement = Arrangement.spacedBy(SheetGroupGap)) {
                layout.more.forEach { section -> SheetActionGroup(section, onAction) }
            }
        }
        if (layout.properties.isNotEmpty()) SheetActionGroup(layout.properties, onAction)
        if (layout.danger.isNotEmpty()) SheetActionGroup(layout.danger, onAction)
    }
}

private class MoreRow(val expanded: Boolean, val onToggle: () -> Unit)

private val SheetGroupGap = 12.dp

@Composable
private fun SheetActionGroup(actions: List<SheetAction>, onAction: (() -> Unit) -> Unit, more: MoreRow? = null) {
    val count = actions.size + if (more != null) 1 else 0
    // 面板是 surfaceContainerLow，段取高两级才看得出分段
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        actions.forEachIndexed { index, action ->
            val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            SegmentedListItem(
                onClick = { onAction(action.onClick) },
                modifier = Modifier.prepareOnPointer(action),
                shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = containerColor,
                    contentColor = color,
                    leadingContentColor = if (action.destructive) color else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                leadingContent = { Icon(action.icon, contentDescription = null) },
                content = { Text(action.label) },
            )
        }
        if (more != null) {
            SegmentedListItem(
                onClick = more.onToggle,
                shapes = ListItemDefaults.segmentedShapes(index = count - 1, count = count),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = containerColor,
                    leadingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    trailingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                leadingContent = { Icon(Icons.Outlined.MoreHoriz, contentDescription = null) },
                trailingContent = {
                    Icon(if (more.expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                },
                content = { Text("更多") },
            )
        }
    }
}

/**
 * 一排等分的图标，图标在上、短标签（[SheetAction.shortLabel]）在下，操作面板顶上与播放器设置面板共用。
 * 四项在 360dp 宽的竖屏里也排得下。[containerColor] 为透明时不垫底色，用在这排操作只是陪衬的地方。
 */
@Composable
internal fun ActionIconRow(
    actions: List<SheetAction>,
    onAction: (SheetAction) -> Unit,
    containerColor: Color = Color.Transparent,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            Surface(
                onClick = { onAction(action) },
                shape = MaterialTheme.shapes.large,
                color = containerColor,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).prepareOnPointer(action),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                ) {
                    Icon(action.icon, contentDescription = null)
                    Text(action.shortLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
