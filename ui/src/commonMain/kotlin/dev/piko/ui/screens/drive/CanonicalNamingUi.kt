package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.ScanStop
import dev.piko.shared.state.CanonicalNamingState
import dev.piko.shared.state.CanonicalNamingState.Phase
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton

// 按番号规范命名一个文件夹的界面，形态与查找重复相同（DuplicatesUi.kt）：宽窗口是标签，窄窗口是底部 sheet 加结果页

/** 宽窗口里规范命名标签上的样子：扫描中转圈，悬停说明进度或结果。 */
internal fun canonicalNamingTab(naming: CanonicalNamingState): SessionTab = SessionTab(
    title = "规范命名：${naming.root.name}",
    tooltip = status(naming) + "。关闭标签页将结束",
    busy = naming.isScanning,
    icon = Icons.Outlined.DriveFileRenameOutline,
)

/**
 * 这一页的主操作（命令栏右端或扩展 FAB）。没选中时是「选中可改的 N 项」，等于默认全勾、有冲突的不勾；
 * 选中了就是「应用所选 N 项」，只算选中里能改的。不在进来时自动选上，理由同查找重复（DriveScreenState.selectOnly）。
 * 扫描与应用当中不给。
 */
internal fun canonicalPrimaryAction(
    naming: CanonicalNamingState,
    selected: Set<String>?,
    select: (Set<String>) -> Unit,
    onApply: (Set<String>) -> Unit,
): SheetAction? {
    if (naming.isApplying || naming.phase != Phase.DONE) return null
    val applicable = naming.applicableIds
    if (selected.isNullOrEmpty()) {
        return applicable.takeIf { it.isNotEmpty() }?.let { SheetAction(Icons.Outlined.Checklist, "选中可改的 ${it.size} 项", { select(it) }) }
    }
    val chosen = selected intersect applicable
    if (chosen.isEmpty()) return null
    return SheetAction(Icons.Outlined.DriveFileRenameOutline, "应用所选 ${chosen.size} 项", { onApply(chosen) })
}

/** 每行下面那一栏：所在的文件夹。行标题已是规范名，原名照网盘页的规矩写在行标题下面（DriveItemName）。 */
internal fun canonicalNotes(naming: CanonicalNamingState?): Map<String, String> {
    naming ?: return emptyMap()
    // 起点自己也可能要改名，它不在自己里面，没有这一栏
    return naming.groups.flatMap { it.rows }.filter { it.file.id != naming.root.id }.associate { row ->
        row.file.id to if (row.folderPath.isEmpty()) naming.root.name else "${naming.root.name}/${row.folderPath}"
    }
}

/**
 * 有建议时列表顶上的一行：起点、建议数与扫描范围；应用中换成进度与停止。
 * 「应用所选」是这一页的主操作（命令栏右端或 FAB），勾选是网盘页的多选；这里只有重新扫描与结束。
 */
@Composable
internal fun CanonicalNamingBanner(naming: CanonicalNamingState, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val run = naming.applyRun
    Row(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            if (naming.isApplying && run != null) {
                Text("正在改名 ${run.processed}/${run.total}", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(
                    progress = { if (run.total == 0) 0f else run.processed.toFloat() / run.total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            } else {
                Text(status(naming), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = scanSummary(naming),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (naming.scanStop != null || naming.failedFolders > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (naming.isApplying) {
            TextButton(onClick = naming::stopApplying) { Text("停止") }
        } else {
            TooltipIconButton(Icons.Outlined.Refresh, "重新扫描", naming::rescan)
            TooltipIconButton(Icons.Outlined.Close, "结束按番号规范命名", onEnd)
        }
    }
}

/** 没有建议可列时整页的样子：还在扫或查片名、扫失败、没有要改的，或这一次已经结束。 */
@Composable
internal fun CanonicalNamingEmptyState(naming: CanonicalNamingState?, onLeave: () -> Unit, canLeave: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (naming?.phase) {
            null -> {
                PikoEmptyState(
                    title = "已结束",
                    description = "改名建议不会保存。如需重新整理，请对文件夹选择「按番号规范命名」",
                    icon = Icons.Outlined.TaskAlt,
                )
                TextButton(onClick = onLeave) { Text("返回网盘") }
            }
            Phase.SCANNING, Phase.TITLES -> {
                LinearProgressIndicator(Modifier.widthIn(max = 240.dp).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(status(naming), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已扫描 ${naming.scannedFolders} 个文件夹，${naming.scannedFiles} 个文件" + if (canLeave) "。离开此页后扫描将继续" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                ScanButton(naming)
            }
            Phase.FAILED -> PikoErrorState(title = "扫描失败", message = naming.errorMessage.orEmpty(), onRetry = naming::rescan)
            Phase.DONE -> {
                PikoEmptyState(title = "没有需要改名的项", description = scanSummary(naming), icon = Icons.Outlined.TaskAlt)
                TextButton(onClick = naming::rescan) { Text("重新扫描") }
            }
        }
    }
}

/** 扫描中是「停止并查看结果」，查片名时是「跳过」：两者都不丢已有的部分，按钮文案据此写明。 */
@Composable
private fun ScanButton(naming: CanonicalNamingState) {
    when (naming.phase) {
        Phase.SCANNING -> OutlinedButton(onClick = naming::stopScan) { Text("停止并查看结果") }
        Phase.TITLES -> OutlinedButton(onClick = naming::skipTitles) { Text("跳过查询片名") }
        else -> Unit
    }
}

/** 窄窗口里网盘页底部 sheet 里的样子（[TaskSheetScaffold]）：扫描、查片名、扫完与失败都在这里，扫完有建议才进结果页。 */
internal fun canonicalNamingSheet(
    naming: CanonicalNamingState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenResults: () -> Unit,
    onEnd: () -> Unit,
): TaskSheetModel = TaskSheetModel(
    key = naming,
    expanded = expanded,
    onExpandedChange = onExpandedChange,
    header = {
        TaskSheetHeader(
            title = "按番号规范命名「${naming.root.name}」",
            status = status(naming),
            closeLabel = "结束按番号规范命名",
            onClose = onEnd,
            progress = naming.isScanning,
            // 收起着扫完不把人拽进结果页，给一个够得着的「查看」
            action = if (naming.phase == Phase.DONE && naming.suggestionCount > 0) "查看" to onOpenResults else null,
        )
    },
    body = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val note = when (naming.phase) {
                Phase.SCANNING, Phase.TITLES -> "收起后扫描将继续。离开此文件夹将结束"
                Phase.FAILED -> naming.errorMessage ?: "未知错误"
                Phase.DONE -> scanSummary(naming)
            }
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                when (naming.phase) {
                    Phase.SCANNING, Phase.TITLES -> ScanButton(naming)
                    Phase.FAILED -> Button(onClick = naming::rescan) { Text("重试") }
                    Phase.DONE -> if (naming.suggestionCount > 0) {
                        Button(onClick = onOpenResults) { Text("查看建议") }
                    } else {
                        TextButton(onClick = naming::rescan) { Text("重新扫描") }
                        Button(onClick = onEnd) { Text("完成") }
                    }
                }
            }
        }
    },
)

/** 一句话的状态：扫描、查片名的进度，或扫完后的结果。 */
private fun status(naming: CanonicalNamingState): String = when (naming.phase) {
    Phase.SCANNING -> "已扫描 ${naming.scannedFolders} 个文件夹，${naming.scannedFiles} 个文件"
    Phase.TITLES -> naming.titlesProgress?.let { (done, total) -> "正在从 MetaTube 查询片名 $done/$total" } ?: "正在从 MetaTube 查询片名"
    Phase.FAILED -> "扫描失败"
    Phase.DONE -> {
        val blocked = naming.suggestionCount - naming.applicableIds.size
        when {
            naming.suggestionCount == 0 -> "没有需要改名的项"
            blocked > 0 -> "${naming.suggestionCount} 项可按番号规范命名，其中 $blocked 项无法改名"
            else -> "${naming.suggestionCount} 项可按番号规范命名"
        }
    }
}

private fun scanSummary(naming: CanonicalNamingState): String = buildString {
    append("已扫描「${naming.root.name}」中的 ${naming.scannedFolders} 个文件夹，${naming.scannedFiles} 个文件")
    when (naming.scanStop) {
        ScanStop.CANCELLED -> append("。扫描已停止，结果可能不完整")
        ScanStop.FOLDER_LIMIT, ScanStop.FILE_LIMIT -> append("。已达扫描上限，结果可能不完整，可按子文件夹分别整理")
        ScanStop.TIMEOUT -> append("。扫描超时，结果可能不完整，可按子文件夹分别整理")
        null -> Unit
    }
    if (naming.failedFolders > 0) append("。${naming.failedFolders} 个文件夹读取失败")
}
