package dev.piko.ui.screens.transfers

import androidx.compose.ui.Modifier

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.piko.shared.state.InstantSaveAttempt
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.SheetAction
import dev.piko.ui.workProgress

internal fun instantAttemptActions(work: InstantSaveAttempt, onShow: (() -> Unit)?, onForget: () -> Unit, onRecheck: (() -> Unit)? = null): List<SheetAction> = buildList {
    if (onRecheck != null) add(SheetAction(Icons.Outlined.Refresh, "重新查询结果", onRecheck))
    if (onShow != null) add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "在网盘中显示", onShow))
    if (!work.isRunning) add(SheetAction(Icons.Outlined.Close, "移除记录", onForget))
}

@Composable
internal fun InstantAttemptDetails(work: InstantSaveAttempt, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    val progress = work.workProgress()
    ItemDetailsContent(modifier = modifier, showIcon = false, title = work.title, headerIcon = { ListLeadingIcon(Icons.Outlined.Bolt) },
        metaParts = listOf(if (work.reference) "引用保存" else "网盘保存", progress.status), actions = actions, extraLines = {
            Text("保存目标：${work.target.name}")
            progress.detail?.let { Text(it) }
            work.receipt?.let { receipt ->
                if (receipt.confirmed.isNotEmpty()) Text("已确认保存：\n${receipt.confirmed.keys.joinToString("\n")}")
                if (receipt.failed.isNotEmpty()) Text("确定未保存：\n${receipt.failed.joinToString("\n")}")
                if (receipt.notAttempted.isNotEmpty()) Text("未执行：\n${receipt.notAttempted.joinToString("\n")}")
                if (receipt.unconfirmed.isNotEmpty()) Text("结果待确认：\n${receipt.unconfirmed.joinToString("\n")}")
                receipt.unconfirmedFolder?.let { Text("目录创建结果待确认：$it") }
                if (receipt.namingUnconfirmed.isNotEmpty()) Text("已保存但名称待确认：\n${receipt.namingUnconfirmed.joinToString("\n")}")
                if (receipt.requiresInspection) Text("重新查询只核对实际保存结果；没有唯一匹配的内容仍保留为待确认。")
            }
            Text("移除记录不会删除文件，也不会取消已经提交的请求。")
        },
    )
}
