package dev.piko.ui.screens.transfers

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.state.OfflineSubmitWork
import dev.piko.shared.state.OfflineSubmitStatus
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun offlineSubmitActions(work: OfflineSubmitWork, onShow: (() -> Unit)?, onForget: () -> Unit): List<SheetAction> = buildList {
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中查看", onShow))
    if (!work.isRunning) add(SheetAction(Icons.Outlined.Close, "移除记录", onForget))
}

@Composable
internal fun OfflineSubmitDetails(work: OfflineSubmitWork, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false, title = work.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Bolt) },
        metaParts = listOf("离线提交", progress.status), actions = actions, extraLines = {
            Text("保存目标：${work.target.name}")
            Text(work.message)
            if (work.status == OfflineSubmitStatus.UNCONFIRMED) Text("没有收到远端任务引用，无法自动核对。请检查云端任务和实际目录；这里不会再次提交请求，也不会对疑似产出执行整包清理。")
            Text("移除记录不取消请求、不删除产出，也不证明可以重新提交。")
        })
}
