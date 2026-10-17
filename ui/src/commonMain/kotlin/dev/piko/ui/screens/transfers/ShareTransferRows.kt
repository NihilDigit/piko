package dev.piko.ui.screens.transfers

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.state.ShareSaveWork
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun shareTransferActions(work: ShareSaveWork, undo: DriveChangeJournal.UndoResult?, canUndo: Boolean, canRecheck: Boolean,
    onUndo: () -> Unit, onRecheck: () -> Unit, onForget: () -> Unit, onShow: (() -> Unit)?): List<SheetAction> = buildList {
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中查看", onShow))
    if (!work.isRunning && undo?.status != DriveChangeJournal.UndoStatus.RUNNING) {
        if (canUndo) add(SheetAction(Icons.Outlined.Refresh, if (work.undoLimited) "撤销可用的命名记录" else "撤销本次命名", onUndo))
        if (canRecheck) add(SheetAction(Icons.Outlined.Refresh, "重新查询结果", onRecheck))
        add(SheetAction(Icons.Outlined.Close, "移除记录", onForget))
    }
}

@Composable
internal fun ShareTransferDetails(work: ShareSaveWork, undo: DriveChangeJournal.UndoResult?, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress(undo)
    ItemDetailsContent(modifier = modifier, showIcon = false, title = work.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Bolt) }, actions = actions,
        metaParts = listOf("分享转存", progress.status), extraLines = {
            progress.detail?.let { Text(it) }
            work.result?.let { result ->
                val names = work.command.sources.associate { it.id to it.name }
                if (result.confirmedSources.isNotEmpty()) Text("已确认转存：\n${result.confirmedSources.joinToString("\n") { names[it] ?: it }}")
                if (result.unconfirmedSources.isNotEmpty()) Text("结果待确认：\n${result.unconfirmedSources.joinToString("\n") { names[it] ?: it }}")
                if (result.failedSources.isNotEmpty() || result.notAttemptedSources.isNotEmpty()) Text("未转存：\n${(result.failedSources + result.notAttemptedSources).joinToString("\n") { names[it] ?: it }}")
            }
            if (work.pendingRenames.isNotEmpty()) Text("部分名称修改尚未确认。重新查询只读取实际名称，不再次改名或转存。")
            if (work.undoLimited) Text("较早的命名撤销数据已过期，相关部分无法完整撤销。")
            if (work.command.canonicalNames && !work.baselineAvailable) Text("未能核对提交前的目录，转存后保留原名。")
            else if (work.namingSkipped > 0 && work.associatedOutputs.size < work.command.sources.size) Text("未能唯一关联部分新增条目，未自动修改它们的名称。")
            if (work.remoteTaskId == null && work.result?.unconfirmedSources?.isNotEmpty() == true)
                Text("没有可核对的远端任务引用，请先查看实际保存目录和云端任务列表。")
            Text("撤销仅回退本次已确认的规范命名，保留转存文件。移除记录不会取消已经提交的请求。")
        },
    )
}
