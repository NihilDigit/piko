package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.AdaptiveBar
import dev.piko.ui.components.BarItem
import dev.piko.ui.components.PinnedPriority
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.IslandTitle
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.SnailModeToggle

@Composable
internal fun RowScope.TaskIslandHeader(
    state: TransfersState,
    selectedCount: Int,
    actions: List<SheetAction>,
    inlineFilter: Boolean = true,
) {
    AdaptiveBar(
        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
        leading = buildList {
            if (selectedCount > 0) add(iconBarItem(
                Icons.Outlined.Close,
                tr("取消选择", "Deselect", "選択を解除", "선택 해제", "取消選取"),
                state::clearSelection,
                priority = PinnedPriority,
            ))
            add(BarItem("title", PinnedPriority) {
                IslandTitle(if (selectedCount > 0) tr("已选 $selectedCount 项", "${enCount(selectedCount, "item", "items")} selected", "$selectedCount 項目を選択中", "${selectedCount}개 선택됨", "已選 $selectedCount 項") else tr("任务", "Tasks", "タスク", "작업", "任務"))
            })
            if (inlineFilter) add(BarItem("filter", PinnedPriority) { TaskKindFilter(state) })
        },
        trailing = buildList {
            addAll(actions.mapIndexed { index, action ->
                iconBarItem(action.icon, action.label, action.onClick, priority = 50 - index,
                    destructive = action.destructive, key = "action:$index")
            })
            if (selectedCount == 0) add(BarItem("snail", PinnedPriority) { SnailModeToggle() })
        },
        gap = 4.dp,
        dragWindow = true,
    )
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
    }
}
