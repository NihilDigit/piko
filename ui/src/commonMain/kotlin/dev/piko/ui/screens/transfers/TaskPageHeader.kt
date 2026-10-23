package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.*
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.theme.LocalFramed

internal fun taskPageActions(
    state: TransfersState, selectedCount: Int, onSelectAll: () -> Unit, onDelete: () -> Unit,
    onPause: (() -> Unit)?, onResume: (() -> Unit)?, pauseCount: Int, resumeCount: Int,
    onRequestClearCompleted: () -> Unit = state::clearCompleted,
): List<SheetAction> = buildList {
    if (selectedCount > 0) {
        onPause?.let { add(SheetAction(Icons.Outlined.Pause, "暂停 $pauseCount 项", it)) }
        onResume?.let { add(SheetAction(Icons.Outlined.PlayArrow, "继续 $resumeCount 项", it)) }
        add(SheetAction(Icons.Outlined.SelectAll, "全选当前筛选结果", onSelectAll))
        add(SheetAction(Icons.Outlined.Delete, "删除所选 $selectedCount 项", onDelete, destructive = true))
    } else {
        if (state.canPauseAll) add(SheetAction(Icons.Outlined.Pause, "全部暂停", state::pauseAll))
        if (state.canResumeAll) add(SheetAction(Icons.Outlined.PlayArrow, "全部继续", state::resumeAll))
        if (state.canClearCompleted) add(SheetAction(Icons.Outlined.ClearAll, "清除已完成", onRequestClearCompleted))
        add(SheetAction(Icons.Outlined.Refresh, "刷新", state::refresh))
    }
}

@Composable
internal fun TaskPageHeader(state: TransfersState, selectedCount: Int, actions: List<SheetAction>) {
    Column(Modifier.fillMaxWidth()) {
        PikoTopBar(
            title = if (selectedCount > 0) "已选 $selectedCount 项" else "任务",
            navigationIcon = if (selectedCount > 0) ({ TooltipIconButton(Icons.Outlined.Close, "取消选择", state::clearSelection) }) else null,
            actions = buildList {
                addAll(actions.mapIndexed { index, action ->
                    iconBarItem(action.icon, action.label, action.onClick, priority = PinnedPriority, destructive = action.destructive, key = "${action.label}:$index")
                })
                if (selectedCount == 0) {
                    add(BarItem(key = "snail", priority = PinnedPriority) { SnailModeToggle() })
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TaskKindFilter(
                state = state,
                compact = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun TaskRateBar(state: TransfersState) {
    if (state.downloadSpeed <= 0 && state.uploadSpeed <= 0 && state.downloadEtaSeconds == null) return
    val framed = LocalFramed.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = if (framed) 20.dp else 12.dp, end = if (framed) 16.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Rates(state, compact = !framed)
        if (isDesktopLayout()) DownloadEta(state)
    }
}
