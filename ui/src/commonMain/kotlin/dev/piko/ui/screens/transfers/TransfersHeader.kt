package dev.piko.ui.screens.transfers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedToggleButton
import androidx.compose.material3.OutlinedToggleButtonDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.connectedToggleShapes
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular

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
 * - 宽屏岛页眉内联模式（[compact] == false）采用连体 `OutlinedToggleButton`，避免高饱和色块与主操作抢权重；
 * - 移动端与窄窗口次级栏（[compact] == true，配合下方五页 HorizontalPager）采用清爽无底框的 M3 标签栏（底部主色指示条加胶囊计数），
 *   避免与顶栏「蜗牛模式」按钮上下叠成两排厚重灰块。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val toggleColors = OutlinedToggleButtonDefaults.colors(
        containerColor = if (isEInk) Color.White else Color.Transparent,
        contentColor = if (isEInk) Color.Black else colors.onSurfaceVariant,
        checkedContainerColor = if (isEInk) Color.Black else colors.secondaryContainer,
        checkedContentColor = if (isEInk) Color.White else colors.onSecondaryContainer,
    )
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
            OutlinedToggleButton(
                checked = selected,
                onCheckedChange = { if (!selected) state.changeFilter(kind) },
                shapes = connectedToggleShapes(index, kinds.size),
                colors = toggleColors,
                border = if (isEInk) BorderStroke(1.dp, Color.Black) else OutlinedToggleButtonDefaults.border(enabled = true, checked = selected),
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
                    style = MaterialTheme.typography.labelLarge.tabular,
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
    val isEInk = LocalAppearance.current.isEInk
    val selectedIndex = kinds.indexOf(state.filter).coerceAtLeast(0)
    PrimaryScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = modifier.fillMaxWidth(),
        containerColor = Color.Transparent,
        contentColor = if (isEInk) Color.Black else colors.primary,
        edgePadding = 12.dp,
        divider = {},
    ) {
        kinds.forEach { kind ->
            val selected = state.filter == kind
            val count = state.counts[kind] ?: 0
            Tab(
                selected = selected,
                onClick = { if (!selected) state.changeFilter(kind) },
                selectedContentColor = if (isEInk) Color.Black else colors.primary,
                unselectedContentColor = if (isEInk) Color.Black else colors.onSurfaceVariant,
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = kind.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                        if (count > 0) {
                            Surface(
                                shape = CircleShape,
                                color = when {
                                    isEInk -> if (selected) Color.Black else Color.White
                                    selected -> colors.primaryContainer
                                    else -> colors.surfaceContainerHigh
                                },
                                contentColor = when {
                                    isEInk -> if (selected) Color.White else Color.Black
                                    selected -> colors.onPrimaryContainer
                                    else -> colors.onSurfaceVariant
                                },
                                border = if (isEInk) BorderStroke(1.dp, Color.Black) else null,
                            ) {
                                Text(
                                    text = "$count",
                                    style = MaterialTheme.typography.labelSmall.tabular,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}
