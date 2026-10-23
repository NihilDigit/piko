package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.SnailModeToggle
import dev.piko.ui.components.TooltipIconButton

@Composable
internal fun RowScope.TaskIslandHeader(
    state: TransfersState,
    selectedCount: Int,
    actions: List<SheetAction>,
) {
    if (selectedCount > 0) {
        TooltipIconButton(Icons.Outlined.Close, "取消选择", state::clearSelection)
    }
    IslandTitle(if (selectedCount > 0) "已选 $selectedCount 项" else "任务")
    Spacer(Modifier.width(8.dp))
    TaskKindFilter(state)
    IslandHeaderSpace()
    if (selectedCount == 0 && (state.downloadSpeed > 0 || state.uploadSpeed > 0 || state.downloadEtaSeconds != null)) {
        Rates(state, compact = true)
        DownloadEta(state)
        Spacer(Modifier.width(8.dp))
    }
    actions.forEach { action ->
        if (action.icon == Icons.Outlined.ClearAll) {
            TextButton(onClick = action.onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(action.label, maxLines = 1)
            }
        } else {
            TooltipIconButton(
                icon = action.icon,
                label = action.label,
                onClick = action.onClick,
                tint = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        }
    }
    if (selectedCount == 0) {
        Spacer(Modifier.width(4.dp))
        SnailModeToggle()
    }
}
