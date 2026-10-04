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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
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
import dev.piko.ui.components.CollapsedSheetHandle
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize

/** 查找重复里每一行的位置，写在行下面那一栏：从查找的起点算起的文件夹。 */
internal fun duplicateLocations(finder: DuplicateFinderState?): Map<String, String> {
    val report = finder?.report ?: return emptyMap()
    return (report.identical + report.versions).flatMap { it.rows }.associate { row ->
        val folder = row.file.folderPath
        row.file.id to if (folder.isEmpty()) finder.root.name else "${finder.root.name}/$folder"
    }
}

/**
 * 有结果时列表顶上的一行：查的是哪里、扫了多少、能腾出多少，结果不全时用错误色。
 * 勾选与移入回收站是网盘页的多选，这里只有重新扫描与结束。
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
                    append("「${finder.root.name}」找到 $groups 组")
                    if (reclaimable > 0) append("，建议移走的可腾出 ${reclaimable.toReadableSize()}")
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = scanSummary(finder),
                style = MaterialTheme.typography.bodySmall,
                color = if (finder.scanStop != null || finder.failedFolders > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
internal fun DuplicatesEmptyState(finder: DuplicateFinderState?, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (finder?.phase) {
            null -> {
                PikoEmptyState(
                    title = "这次查找已结束",
                    description = "结果不保留。在文件夹里点「查找重复」重新查找",
                    icon = Icons.Outlined.TaskAlt,
                )
                TextButton(onClick = onLeave) { Text("回到网盘") }
            }
            Phase.SCANNING, Phase.ANALYZING -> {
                PikoLoadingIndicator()
                Spacer(Modifier.height(16.dp))
                Text(
                    text = if (finder.phase == Phase.ANALYZING) "正在比对" else "正在扫描「${finder.root.name}」",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已扫描 ${finder.scannedFolders} 个文件夹，${finder.scannedFiles} 个文件。可以先去别处，扫完会提示",
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
                PikoEmptyState(title = "没有重复文件", description = scanSummary(finder), icon = Icons.Outlined.TaskAlt)
                TextButton(onClick = finder::rescan) { Text("重新扫描") }
            }
        }
    }
}

/** 窄窗口里不在查重结果页时，底部留一条把手回去；宽窗口由命令栏的「收着的东西」承担。 */
@Composable
internal fun DuplicatesHandle(finder: DuplicateFinderState, onOpen: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val report = finder.report
    val groups = report.identical.size + report.versions.size
    CollapsedSheetHandle(
        title = "查找重复：${finder.root.name}",
        status = when (finder.phase) {
            Phase.SCANNING -> "已扫描 ${finder.scannedFolders} 个文件夹，${finder.scannedFiles} 个文件"
            Phase.ANALYZING -> "正在比对"
            Phase.FAILED -> "扫描失败"
            Phase.DONE -> if (groups == 0) "没有重复文件" else "找到 $groups 组"
        },
        closeLabel = "结束查找重复",
        onExpand = onOpen,
        onClose = onEnd,
        modifier = modifier,
    )
}

private fun scanSummary(finder: DuplicateFinderState): String = buildString {
    append("扫描了 ${finder.scannedFolders} 个文件夹，${finder.scannedFiles} 个文件")
    when (finder.scanStop) {
        ScanStop.CANCELLED -> append("。已停止，结果不全")
        ScanStop.FOLDER_LIMIT, ScanStop.FILE_LIMIT -> append("。已达上限，结果不全，可分子文件夹查找")
        ScanStop.TIMEOUT -> append("。已超时，结果不全，可分子文件夹查找")
        null -> Unit
    }
    if (finder.failedFolders > 0) append("。${finder.failedFolders} 个文件夹读取失败")
}
