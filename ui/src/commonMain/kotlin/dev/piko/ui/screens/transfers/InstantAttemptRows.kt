package dev.piko.ui.screens.transfers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.state.InstantSaveAttempt
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun instantAttemptActions(work: InstantSaveAttempt, onShow: (() -> Unit)?, onResume: (() -> Unit)?, onForget: () -> Unit): List<SheetAction> = buildList {
    if (onResume != null) add(SheetAction(Icons.Outlined.Bolt, "继续处理添加链接", onResume))
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中查看", onShow))
    if (!work.isRunning) add(SheetAction(Icons.Outlined.Close, "移除记录", onForget))
}

@Composable
internal fun InstantAttemptSheet(work: InstantSaveAttempt, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val progress = work.workProgress()
    ItemDetailsSheet(title = work.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Bolt) },
        metaParts = listOf("秒传保存", progress.status), actions = actions, onDismiss = onDismiss,
        extraLines = {
            Text("保存目标：${work.target.name}")
            progress.detail?.let { Text(it) }
            work.receipt?.let { receipt ->
                if (receipt.confirmed.isNotEmpty()) Text("已确认保存：\n${receipt.confirmed.keys.joinToString("\n")}")
                if (receipt.failed.isNotEmpty()) Text("确定未保存：\n${receipt.failed.joinToString("\n")}")
                if (receipt.notAttempted.isNotEmpty()) Text("未执行：\n${receipt.notAttempted.joinToString("\n")}")
                if (receipt.unconfirmed.isNotEmpty()) Text("结果待确认：\n${receipt.unconfirmed.joinToString("\n")}")
                receipt.unconfirmedFolder?.let { Text("目录创建结果待确认：$it") }
                if (receipt.namingUnconfirmed.isNotEmpty()) Text("已保存但名称待确认：\n${receipt.namingUnconfirmed.joinToString("\n")}")
                if (receipt.requiresInspection) Text("请先检查实际保存结果。缺少服务端文件 ID 时无法自动确认，也不会重新提交这些请求。")
            }
            Text("移除记录不会删除文件，也不会取消已经提交的请求。")
        },
    )
}
