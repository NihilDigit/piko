package dev.piko.ui.screens.transfers

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TonalToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.connectedToggleShapes

/** 类别切换按钮上的图标：未选中用描边，选中用实心。 */
internal fun TransferKind.icon(selected: Boolean): ImageVector = when (this) {
    TransferKind.ALL -> if (selected) Icons.Filled.Inbox else Icons.Outlined.Inbox
    TransferKind.DOWNLOAD -> if (selected) Icons.Filled.Download else Icons.Outlined.Download
    TransferKind.UPLOAD -> if (selected) Icons.Filled.Upload else Icons.Outlined.Upload
    TransferKind.CLOUD -> if (selected) Icons.Filled.Cloud else Icons.Outlined.Cloud
    TransferKind.FILES -> if (selected) Icons.Filled.Inventory2 else Icons.Outlined.Inventory2
}

/**
 * 任务类别切换栏：
 * - 宽屏岛页眉内联模式（[compact] == false）保持紧凑连体切换按钮；
 * - 移动端与窄窗口次级栏（[compact] == true，配合下方五页 HorizontalPager）采用清爽无底框的 M3 标签栏（底部主色指示条 + 胶囊计数），
 *   避免与顶栏「蜗牛模式」按钮上下叠成两排厚重灰块。
 */
@Composable
internal fun TaskKindFilter(
    state: TransfersState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    compact: Boolean = false,
) {
    val kinds = TransferKind.entries
    if (compact) {
        CompactTaskTabStrip(
            state = state,
            kinds = kinds,
            modifier = modifier.padding(contentPadding),
        )
        return
    }
    Row(
        modifier = modifier
            .padding(contentPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        kinds.forEachIndexed { index, kind ->
            val selected = state.filter == kind
            val count = state.counts[kind] ?: 0
            val label = if (count > 0) "${kind.label} $count" else kind.label
            TonalToggleButton(
                checked = selected,
                onCheckedChange = { if (!selected) state.changeFilter(kind) },
                shapes = connectedToggleShapes(index, kinds.size),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                if (selected) {
                    Icon(
                        imageVector = kind.icon(true),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CompactTaskTabStrip(
    state: TransfersState,
    kinds: List<TransferKind>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        AdaptiveEvenOrScrollableTabs(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
                .selectableGroup(),
        ) {
            kinds.forEach { kind ->
                val selected = state.filter == kind
                val count = state.counts[kind] ?: 0
                val textColor = if (selected) colors.primary else colors.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { if (!selected) state.changeFilter(kind) },
                        )
                        .padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = kind.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = textColor,
                            maxLines = 1,
                        )
                        if (count > 0) {
                            Spacer(Modifier.width(4.dp))
                            Surface(
                                shape = CircleShape,
                                color = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
                                contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            ) {
                                Text(
                                    text = "$count",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (selected) colors.primary else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = colors.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

/** 五个标签总自然宽放得下时均分铺满整行，西文长词或大字号超出屏宽时自然转为横向滚动，不截断任何一项。 */
@Composable
private fun AdaptiveEvenOrScrollableTabs(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val naturalWidths = measurables.map { it.maxIntrinsicWidth(44.dp.roundToPx()) }
        val sumNatural = naturalWidths.sum()
        val viewport = constraints.minWidth
        val extraPerTab = if (viewport > sumNatural && measurables.isNotEmpty()) {
            (viewport - sumNatural) / measurables.size
        } else {
            0
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            val targetWidth = naturalWidths[index] + extraPerTab
            measurable.measure(
                Constraints(
                    minWidth = targetWidth,
                    maxWidth = targetWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                ),
            )
        }
        val totalWidth = placeables.sumOf { it.width }.coerceAtLeast(viewport)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(totalWidth, height) {
            var x = 0
            for (placeable in placeables) {
                placeable.placeRelative(x, height - placeable.height)
                x += placeable.width
            }
        }
    }
}
