package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
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
    inlineFilter: Boolean = true,
) {
    if (selectedCount > 0) {
        TooltipIconButton(Icons.Outlined.Close, tr("取消选择", "Deselect", "選択を解除", "선택 해제", "取消選取"), state::clearSelection)
    }
    IslandTitle(if (selectedCount > 0) tr("已选 $selectedCount 项", "${enCount(selectedCount, "item", "items")} selected", "$selectedCount 項目を選択中", "${selectedCount}개 선택됨", "已選 $selectedCount 項") else tr("任务", "Tasks", "タスク", "작업", "任務"))
    if (inlineFilter) {
        Spacer(Modifier.width(8.dp))
        TaskKindFilter(state)
    }
    IslandHeaderSpace()
    if (inlineFilter && selectedCount == 0 && (state.downloadSpeed > 0 || state.uploadSpeed > 0 || state.downloadEtaSeconds != null)) {
        Rates(state, compact = true)
        DownloadEta(state)
        Spacer(Modifier.width(8.dp))
    }
    actions.forEach { action ->
        if (action.icon == Icons.Outlined.ClearAll && inlineFilter) {
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

@Composable
internal fun TaskIslandSubHeader(state: TransfersState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TaskKindFilter(
            state = state,
            compact = true,
            modifier = Modifier.weight(1f),
        )
        if (state.downloadSpeed > 0 || state.uploadSpeed > 0 || state.downloadEtaSeconds != null) {
            Rates(state, compact = true)
        }
    }
}
