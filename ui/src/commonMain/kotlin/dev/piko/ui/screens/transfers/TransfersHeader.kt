package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * 任务类别连体切换按钮组：
 * 宽屏完整展示全部图标、文字与计数；中等宽度省去未选中项图标；窄屏（手机）只展开当前选中项的文字，未选中项仅显示图标与计数，
 * 保证五个类别在任何宽度下都能一屏完整放下，绝不截断最后一项。
 */
@Composable
internal fun TaskKindFilter(
    state: TransfersState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    compact: Boolean = false,
) {
    val kinds = TransferKind.entries
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
            val showText = !compact
            val label = when {
                showText && count > 0 -> "${kind.label} $count"
                showText -> kind.label
                count > 0 -> "$count"
                else -> ""
            }
            val button: @Composable () -> Unit = {
                TonalToggleButton(
                    checked = selected,
                    onCheckedChange = { if (!selected) state.changeFilter(kind) },
                    shapes = connectedToggleShapes(index, kinds.size),
                    contentPadding = PaddingValues(
                        horizontal = if (showText) 12.dp else 10.dp,
                        vertical = 0.dp,
                    ),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    Icon(
                        imageVector = kind.icon(selected),
                        contentDescription = if (showText) null else kind.label,
                        modifier = Modifier.size(18.dp),
                    )
                    if (label.isNotEmpty()) {
                        Spacer(Modifier.width(if (showText) 6.dp else 4.dp))
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
            if (!showText) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                    tooltip = { PlainTooltip { Text(if (count > 0) "${kind.label} $count" else kind.label) } },
                    state = rememberTooltipState(),
                ) { button() }
            } else {
                button()
            }
        }
    }
}
