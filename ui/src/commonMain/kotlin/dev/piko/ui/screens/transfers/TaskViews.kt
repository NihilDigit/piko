package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
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
    SnailModeToggle()
    Spacer(Modifier.width(4.dp))
    actions.forEach { action ->
        TooltipIconButton(
            icon = action.icon,
            label = action.label,
            onClick = action.onClick,
            tint = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}
