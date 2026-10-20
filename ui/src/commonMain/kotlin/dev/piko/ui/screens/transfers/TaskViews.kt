package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.IslandHeaderSpace
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.SheetAction
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
    TaskKindFilter(state)
    IslandHeaderSpace()
    actions.forEach { action ->
        TooltipIconButton(
            icon = action.icon,
            label = action.label,
            onClick = action.onClick,
            tint = if (action.destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
    }
}
