package dev.piko.ui.screens.transfers

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.state.HistoryEditWork
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun historyTransferActions(work: HistoryEditWork, canRecheck: Boolean, onRecheck: () -> Unit, onForget: () -> Unit): List<SheetAction> = buildList {
    if (!work.isRunning) {
        if (canRecheck) add(SheetAction(Icons.Outlined.Refresh, "重新查询结果", onRecheck))
        add(SheetAction(Icons.Outlined.Close, "移除记录", onForget))
    }
}

@Composable
internal fun HistoryTransferDetails(work: HistoryEditWork, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false, title = progress.title, headerIcon = { ListLeadingIcon(Icons.Outlined.History) },
        actions = actions, metaParts = listOf("历史记录", progress.status),
        extraLines = {
            progress.detail?.let { Text(it) }
            Text("只修改历史事件，不删除网盘文件；删除播放记录也会移除对应续播位置，无法撤销。")
            if (work.result?.completion in setOf(dev.piko.shared.state.HistoryEditCompletion.PARTIAL, dev.piko.shared.state.HistoryEditCompletion.UNCONFIRMED)) Text("重新查询只核对结果，不再次删除或清空。")
        },
    )
}
