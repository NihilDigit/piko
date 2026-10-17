package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransfersState
import dev.piko.shared.state.TransferKind
import dev.piko.ui.components.*
import dev.piko.ui.adaptive.isDesktopLayout

internal fun taskPageActions(
    state: TransfersState, selectedCount: Int, onSelectAll: () -> Unit, onDelete: () -> Unit,
    onPause: (() -> Unit)?, onResume: (() -> Unit)?, pauseCount: Int, resumeCount: Int,
): List<SheetAction> = buildList {
    add(SheetAction(Icons.Outlined.SelectAll, "全选当前筛选结果", onSelectAll))
    if (selectedCount > 0) {
        onPause?.let { add(SheetAction(Icons.Outlined.Pause, "暂停 $pauseCount 项", it)) }
        onResume?.let { add(SheetAction(Icons.Outlined.PlayArrow, "继续 $resumeCount 项", it)) }
        add(SheetAction(Icons.Outlined.Delete, "删除所选 $selectedCount 项", onDelete, destructive = true))
    } else {
        if (state.canResumeAll) add(SheetAction(Icons.Outlined.PlayArrow, "全部继续", state::resumeAll))
        if (state.canClearCompleted) add(SheetAction(Icons.Outlined.ClearAll, "清除已完成", state::clearCompleted))
        add(SheetAction(Icons.Outlined.Refresh, "刷新", state::refresh))
    }
}

@Composable
internal fun TaskPageHeader(state: TransfersState, selectedCount: Int, actions: List<SheetAction>) {
    PikoTopBar(
        title = if (selectedCount > 0) "已选 $selectedCount 项" else "任务",
        navigationIcon = if (selectedCount > 0) ({ TooltipIconButton(Icons.Outlined.Close, "取消选择", state::clearSelection) }) else null,
        actions = listOf(BarItem("task-kind", 100, TransferKind.entries.map { kind ->
            SheetAction(Icons.Outlined.FilterList, kind.label, { state.changeFilter(kind) }, checked = state.filter == kind)
        }) { TaskKindFilter(state) }) + actions.mapIndexed { index, action ->
            iconBarItem(action.icon, action.label, action.onClick, priority = 30 - index, destructive = action.destructive)
        },
    )
}

@Composable
internal fun TaskRateBar(state: TransfersState) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Rates(state, Modifier.weight(1f), compact = true)
        if (isDesktopLayout()) DownloadEta(state)
        SnailModeToggle()
    }
}
