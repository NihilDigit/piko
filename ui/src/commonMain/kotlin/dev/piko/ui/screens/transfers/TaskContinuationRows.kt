package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.presentation.TaskContinuations
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.TransferKind
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.WorkMeterBar
import dev.piko.ui.components.fullLineItem
import dev.piko.ui.screens.instant.rememberDiscardAddLink
import dev.piko.ui.screens.instant.summary
import dev.piko.ui.workProgress
import dev.piko.ui.workbench.CleanupTaskCard

internal fun LazyGridScope.continuationItems(
    tasks: TaskContinuations?,
    kind: TransferKind,
    onResumeAddLink: () -> Unit,
    onOpenDuplicateReport: () -> Unit,
) {
    tasks ?: return
    if (kind == TransferKind.ALL && tasks.hasDraft) fullLineItem(key = "continuation:draft") {
        val draft = tasks.addLink.state ?: return@fullLineItem
        val summary = draft.summary()
        val discard = rememberDiscardAddLink(tasks.addLink)
        FileListItem(
            headline = summary.title,
            leading = { ListLeadingIcon(Icons.Outlined.Bolt) },
            onClick = onResumeAddLink,
            onMoreClick = discard,
            supporting = { Text(summary.status ?: "继续编辑添加链接", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailing = { TooltipIconButton(Icons.Outlined.Close, "放弃添加链接", discard) },
        )
    }
    if (kind != TransferKind.ALL && kind != TransferKind.FILES) return
    tasks.duplicate?.let { finder ->
        fullLineItem(key = "continuation:duplicates:${finder.root.id}") {
            DuplicateTaskRow(tasks, finder, onOpenDuplicateReport)
        }
    }
    if (tasks.hasCleanup) fullLineItem(key = "continuation:cleanup") {
        CleanupTaskCard(tasks.cleanup, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

@Composable
private fun DuplicateTaskRow(tasks: TaskContinuations, finder: DuplicateFinderState, onOpenReport: () -> Unit) {
    var details by remember(finder) { mutableStateOf(false) }
    var discard by remember(finder) { mutableStateOf(false) }
    val groups = finder.report.identical.size + finder.report.versions.size
    val work = finder.workProgress()
    val status = when {
        finder.isScanning -> "已扫描 ${finder.scannedFolders} 个文件夹、${finder.scannedFiles} 个文件"
        finder.phase == DuplicateFinderState.Phase.FAILED -> finder.errorMessage ?: "查找重复失败"
        finder.scanStop != null || finder.failedFolders > 0 -> "部分扫描结果，发现 $groups 组重复文件"
        groups > 0 -> "发现 $groups 组重复文件"
        else -> "未发现重复文件"
    }
    FileListItem(
        headline = "查找重复：${finder.root.name}",
        leading = { ListLeadingIcon(Icons.Outlined.FileCopy) },
        onClick = onOpenReport,
        onMoreClick = { details = true },
        supporting = {
            Column {
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                work?.let { WorkMeterBar(it.meter, it.title, Modifier.fillMaxWidth()) }
            }
        },
    )
    if (details) ItemDetailsSheet(
        title = "查找重复：${finder.root.name}",
        headerIcon = { ListLeadingIcon(Icons.Outlined.FileCopy) },
        metaParts = listOf(status),
        actions = buildList {
            add(SheetAction(Icons.Outlined.FileCopy, "查看报告", onOpenReport))
            if (finder.isScanning) add(SheetAction(Icons.Outlined.Stop, "停止扫描并保留已扫描结果", { tasks.stopDuplicate(finder) }))
            add(SheetAction(Icons.Outlined.Close, if (finder.isScanning) "结束本次查重" else "移除报告", { details = false; discard = true }, destructive = true))
        },
        onDismiss = { details = false },
    )
    if (discard) PikoDialog(
        onDismissRequest = { discard = false },
        title = { Text("结束本次查重？") },
        text = { Text("本次扫描结果将被丢弃。已移入回收站的文件不会因此恢复。") },
        confirmButton = { PikoDialogConfirm("结束查重", onClick = { discard = false; tasks.discardDuplicate(finder) }, destructive = true) },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("取消") } },
    )
}
