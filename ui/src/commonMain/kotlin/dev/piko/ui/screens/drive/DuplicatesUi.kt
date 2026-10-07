package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.foundation.layout.widthIn
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
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateFinderState.Phase
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize

/**
 * 查找重复里每一行的位置，写在行下面那一栏：从查找的起点算起的文件夹。建议移走的在后面注明，
 * 不进多选也看得出建议的是哪几份。
 */
internal fun duplicateLocations(finder: DuplicateFinderState?): Map<String, String> {
    val report = finder?.report ?: return emptyMap()
    val suggested = finder.suggestedIds
    return (report.identical + report.versions).flatMap { it.rows }.associate { row ->
        val folder = row.file.folderPath
        val location = if (folder.isEmpty()) finder.root.name else "${finder.root.name}/$folder"
        row.file.id to if (row.file.id in suggested) "$location，建议移走" else location
    }
}

/** 宽窗口里查重标签上的样子：扫描中转圈，悬停说明进度或结果。 */
internal fun duplicatesTab(finder: DuplicateFinderState): SessionTab {
    val groups = finder.report.identical.size + finder.report.versions.size
    val tooltip = when {
        finder.isScanning -> "已扫描 ${finder.scannedFolders} 个文件夹"
        groups == 0 -> "未发现重复文件"
        else -> "$groups 组重复文件"
    }
    return SessionTab("查重：${finder.root.name}", tooltip, busy = finder.isScanning, icon = Icons.Outlined.FileCopy)
}

/**
 * 有结果时列表顶上的一行：几组、能腾出多少，下面一行是查的哪里、扫了多少、没扫完的原因。没扫完不用错误色，结果照样能用。
 * 「选中建议移走的」是这一页的主操作（命令栏右端或 FAB），勾选与移入回收站是网盘页的多选；这里只有重新扫描与结束。
 */
@Composable
internal fun DuplicatesBanner(finder: DuplicateFinderState, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val report = finder.report
    val groups = report.identical.size + report.versions.size
    val reclaimable = report.identical.sumOf { it.reclaimableBytes }
    Row(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildString {
                    append("$groups 组重复文件")
                    if (reclaimable > 0) append("，可释放 ${reclaimable.toReadableSize()}")
                },
                style = MaterialTheme.typography.titleSmall,
            )
            // 没扫完不用错误色：结果照样能用，只是不全，说明写在同一行里即可
            Text(
                text = scanSummary(finder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TooltipIconButton(Icons.Outlined.Refresh, "重新扫描", finder::rescan)
        TooltipIconButton(Icons.Outlined.Close, "结束查找重复", onEnd)
    }
}

/**
 * 没有可列的组时整页的样子：还在扫、扫失败、扫完没有重复，或这一次已经结束（重启后停在这里、或别处结束了它）。
 * 结束了的拿不回结果：结果只是扫描那一刻的快照，不存盘。
 */
@Composable
internal fun DuplicatesEmptyState(finder: DuplicateFinderState?, onLeave: () -> Unit, canLeave: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (finder?.phase) {
            null -> {
                PikoEmptyState(
                    title = "查找已结束",
                    description = "扫描结果未保存",
                    icon = Icons.Outlined.TaskAlt,
                )
                TextButton(onClick = onLeave) { Text("返回网盘") }
            }
            Phase.SCANNING, Phase.ANALYZING -> {
                // 要等几分钟、总量事先不知道：不确定的线性进度条，与窄窗口 sheet 里的同一种（progress-indicators.md）
                LinearProgressIndicator(Modifier.widthIn(max = 240.dp).fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (finder.phase == Phase.ANALYZING) "正在比对" else "正在扫描「${finder.root.name}」",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // 窄窗口里离开即结束查找，见 TaskSlot
                    text = "已扫描 ${finder.scannedFolders} 个文件夹、${finder.scannedFiles} 个文件" + if (canLeave) "，离开此页后继续" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (finder.phase == Phase.SCANNING) {
                    Spacer(Modifier.height(24.dp))
                    // 停止不丢弃已扫描的部分，按钮文案据此写明
                    OutlinedButton(onClick = finder::stopScan) { Text("停止并查看结果") }
                }
            }
            Phase.FAILED -> PikoErrorState(title = "扫描失败", message = finder.errorMessage.orEmpty(), onRetry = finder::rescan)
            Phase.DONE -> {
                PikoEmptyState(title = "未发现重复文件", description = scanSummary(finder), icon = Icons.Outlined.TaskAlt)
                TextButton(onClick = finder::rescan) { Text("重新扫描") }
            }
        }
    }
}

/**
 * 窄窗口里查找重复在网盘页底部 sheet 里的样子（[TaskSheetScaffold]）：扫描、比对、扫完与失败都在这里，
 * 扫完有结果才进结果页。宽窗口是查重标签，不经这里。
 */
internal fun duplicatesSheet(
    finder: DuplicateFinderState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenResults: () -> Unit,
    onEnd: () -> Unit,
): TaskSheetModel {
    val groups = finder.report.identical.size + finder.report.versions.size
    return TaskSheetModel(
        key = finder,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        header = {
            TaskSheetHeader(
                title = "查找重复「${finder.root.name}」",
                status = when (finder.phase) {
                    Phase.SCANNING -> "${finder.scannedFolders} 个文件夹，${finder.scannedFiles} 个文件"
                    Phase.ANALYZING -> "正在比对"
                    Phase.FAILED -> "扫描失败"
                    Phase.DONE -> if (groups == 0) "未发现重复文件" else "$groups 组重复文件"
                },
                closeLabel = "结束查找重复",
                onClose = onEnd,
                progress = finder.isScanning,
                // 收起着扫完不把人拽进结果页，给一个够得着的「查看」
                action = if (finder.phase == Phase.DONE && groups > 0) "查看" to onOpenResults else null,
            )
        },
        body = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val note = when (finder.phase) {
                    Phase.SCANNING, Phase.ANALYZING -> "收起后继续扫描，离开此文件夹则结束"
                    Phase.FAILED -> finder.errorMessage ?: "未知错误"
                    Phase.DONE -> scanSummary(finder)
                }
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    when (finder.phase) {
                        // 停止不丢弃已扫描的部分，按钮文案据此写明
                        Phase.SCANNING -> OutlinedButton(onClick = finder::stopScan) { Text("停止并查看结果") }
                        Phase.ANALYZING -> Unit
                        Phase.FAILED -> Button(onClick = finder::rescan) { Text("重试") }
                        Phase.DONE -> if (groups > 0) {
                            Button(onClick = onOpenResults) { Text("查看结果") }
                        } else {
                            TextButton(onClick = finder::rescan) { Text("重新扫描") }
                            Button(onClick = onEnd) { Text("完成") }
                        }
                    }
                }
            }
        },
    )
}

private fun scanSummary(finder: DuplicateFinderState): String = buildString {
    append("「${finder.root.name}」：已扫描 ${finder.scannedFolders} 个文件夹、${finder.scannedFiles} 个文件")
    when (finder.scanStop) {
        ScanStop.CANCELLED -> append("，扫描已停止")
        ScanStop.FOLDER_LIMIT, ScanStop.FILE_LIMIT -> append("，已达扫描上限")
        ScanStop.TIMEOUT -> append("，扫描超时")
        null -> Unit
    }
    if (finder.failedFolders > 0) append("，${finder.failedFolders} 个文件夹读取失败")
}
