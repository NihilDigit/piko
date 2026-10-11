package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.ScanStop
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateFinderState.Phase
import dev.piko.shared.state.DuplicateKind
import dev.piko.ui.components.connectedToggleShapes
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

@Composable
internal fun DuplicatesBanner(
    finder: DuplicateFinderState,
    selectedKind: DuplicateKind,
    onKindChange: (DuplicateKind) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = finder.report
    val identical = selectedKind == DuplicateKind.IDENTICAL
    val groups = if (identical) report.identical else report.versions
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                DuplicateKind.entries.forEachIndexed { index, kind ->
                    TonalToggleButton(
                        checked = selectedKind == kind,
                        onCheckedChange = { if (selectedKind != kind) onKindChange(kind) },
                        shapes = connectedToggleShapes(index, DuplicateKind.entries.size),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Text(if (kind == DuplicateKind.IDENTICAL) "相同文件" else "不同版本", maxLines = 1)
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                Text("${groups.size} 组", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (identical) "可释放 ${groups.sumOf { it.reclaimableBytes }.toReadableSize()}"
                    else "${groups.sumOf { it.rows.size }} 个版本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TooltipIconButton(Icons.Outlined.MoreVert, "查重选项", { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("重新扫描") }, onClick = { menuOpen = false; finder.rescan() })
                    DropdownMenuItem(text = { Text("结束查重") }, onClick = { menuOpen = false; onEnd() })
                }
            }
        }
        val warning = when (finder.scanStop) {
            ScanStop.CANCELLED -> "扫描已停止，结果不完整"
            ScanStop.FOLDER_LIMIT, ScanStop.FILE_LIMIT -> "已达扫描上限，结果不完整"
            ScanStop.TIMEOUT -> "扫描超时，结果不完整"
            null -> null
        }
        if (warning != null || finder.failedFolders > 0) {
            Text(
                listOfNotNull(warning, "${finder.failedFolders} 个文件夹读取失败".takeIf { finder.failedFolders > 0 }).joinToString("，"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 没有可列的组时整页的样子：还在扫、扫失败、扫完没有重复，或这一次已经结束（重启后停在这里、或别处结束了它）。
 * 结束了的拿不回结果：结果只是扫描那一刻的快照，不存盘。
 */
@Composable
internal fun DuplicatesEmptyState(finder: DuplicateFinderState?, onLeave: () -> Unit, canLeave: Boolean, modifier: Modifier = Modifier, cleaning: Boolean = false, selectedKind: DuplicateKind = DuplicateKind.IDENTICAL) {
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
                    icon = if (cleaning) Icons.Outlined.FileCopy else Icons.Outlined.TaskAlt,
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
                PikoEmptyState(
                    title = if (cleaning) "正在清理所选文件" else if (selectedKind == DuplicateKind.IDENTICAL) "没有相同文件" else "没有不同版本",
                    icon = Icons.Outlined.TaskAlt,
                )
                if (!cleaning) TextButton(onClick = finder::rescan) { Text("重新扫描") }
            }
        }
    }
}
