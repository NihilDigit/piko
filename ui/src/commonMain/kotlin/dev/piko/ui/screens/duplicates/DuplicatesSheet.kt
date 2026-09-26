package dev.piko.ui.screens.duplicates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.ScanStop
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateFinderState.Phase
import dev.piko.shared.state.DuplicateGroup
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.state.DuplicateRow
import dev.piko.ui.components.CollapsedSheetHandle
import dev.piko.ui.components.HighlightBadge
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoErrorState
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize
import kotlinx.coroutines.delay

/**
 * 查找重复的面板内容，放在 ModalBottomSheet 里：高度随内容，结果多时撑到面板上限，在列表里滚动。
 * 扫描与勾选都在 DuplicateSession 持有的状态里，面板只是视图，划走不停止扫描。
 *
 * 面板不替内容让导航条：底部有操作栏时由操作栏铺到手势横条下面，没有时补一段空白，
 * 否则操作栏的底色停在横条上方，横条那一截是面板的颜色。宿主须把 contentWindowInsets 设为空。
 */
@Composable
fun DuplicatesSheetContent(state: DuplicateFinderState) {
    var confirming by remember { mutableStateOf(false) }
    // 面板盖在网盘页的 Snackbar 之上，一次性提示就地显示几秒，与添加链接面板相同
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) { state.messages.collect { notice = it } }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

    val report = state.report
    val hasGroups = report.identical.isNotEmpty() || report.versions.isNotEmpty()
    val showsSelectionBar = state.phase == Phase.DONE && hasGroups

    Column(modifier = Modifier.fillMaxWidth()) {
        SheetHeader(state)
        notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
            when (state.phase) {
                Phase.SCANNING, Phase.ANALYZING -> ScanningPane(state)
                Phase.FAILED -> PikoErrorState(
                    title = "扫描失败",
                    message = state.errorMessage.orEmpty(),
                    onRetry = state::rescan,
                    modifier = Modifier.align(Alignment.Center).padding(vertical = 32.dp),
                )
                Phase.DONE -> if (hasGroups) {
                    ResultList(state)
                } else {
                    PikoEmptyState(
                        title = "没有重复文件",
                        description = scanSummary(state),
                        icon = Icons.Outlined.TaskAlt,
                        modifier = Modifier.align(Alignment.Center).padding(vertical = 32.dp),
                    )
                }
            }
        }
        if (showsSelectionBar) {
            SelectionBar(
                count = state.selectedIds.size,
                bytes = state.selectedBytes,
                busy = state.isTrashing,
                onTrash = { confirming = true },
            )
        } else {
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }

    if (confirming) {
        val count = state.selectedIds.size
        val fullyRemoved = state.fullyRemovedGroups
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("移入回收站") },
            text = {
                Text(
                    buildString {
                        append("将 $count 个文件移入回收站，共 ${state.selectedBytes.toReadableSize()}，可在回收站恢复。")
                        if (fullyRemoved > 0) append("\n其中 $fullyRemoved 组的所有副本都已勾选，移走后网盘里不再保留这些内容。")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        state.trashSelected()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("移入回收站") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            },
        )
    }
}

/** 面板收起后的把手：扫描中显示进度，扫完显示结果，关闭即结束这次查重。 */
@Composable
fun DuplicatesSheetHandle(
    state: DuplicateFinderState,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = state.report
    val groups = report.identical.size + report.versions.size
    val status = when (state.phase) {
        Phase.SCANNING -> "已扫描 ${state.scannedFolders} 个文件夹，${state.scannedFiles} 个文件"
        Phase.ANALYZING -> "正在比对"
        Phase.FAILED -> "扫描失败"
        Phase.DONE -> if (groups == 0) "没有重复文件" else "找到 $groups 组，已选 ${state.selectedIds.size} 个"
    }
    CollapsedSheetHandle(
        title = "查找重复：${state.root.name}",
        status = status,
        closeLabel = "结束查找重复",
        onExpand = onExpand,
        onClose = onClose,
        modifier = modifier,
        closeEnabled = !state.isTrashing,
    )
}

/** 标题与范围。面板有拖动条，下滑、点遮罩、返回都能关，与添加链接面板一样不放关闭按钮。 */
@Composable
private fun SheetHeader(state: DuplicateFinderState) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "查找重复", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = state.root.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.phase == Phase.DONE || state.phase == Phase.FAILED) {
            TooltipIconButton(icon = Icons.Outlined.Refresh, label = "重新扫描", onClick = state::rescan)
        }
    }
}

@Composable
private fun ScanningPane(state: DuplicateFinderState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PikoLoadingIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (state.phase == Phase.ANALYZING) "正在比对" else "正在扫描 ${state.root.name}",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "已扫描 ${state.scannedFolders} 个文件夹，${state.scannedFiles} 个文件",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.phase == Phase.SCANNING) {
            Spacer(Modifier.height(24.dp))
            // 停止不丢弃已扫描的部分，按钮文案据此写明
            OutlinedButton(onClick = state::stopScan) { Text("停止并查看结果") }
        }
    }
}

@Composable
private fun ResultList(state: DuplicateFinderState) {
    val report = state.report
    // 不铺满：结果少时面板只有内容那么高，多了才撑到面板上限、在列表里滚动
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "summary") {
            Text(
                text = scanSummary(state),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.scanStop != null || state.failedFolders > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (report.identical.isNotEmpty()) {
            sectionHeader(
                key = "identical",
                title = "完全相同 ${report.identical.size} 组",
                description = "内容一致，默认保留名字里没有「(1)」「副本」这类标记的一份，其次最早存入的",
            )
            groups(report.identical, state)
        }
        if (report.versions.isNotEmpty()) {
            sectionHeader(
                key = "versions",
                title = "同集不同版本 ${report.versions.size} 组",
                description = "按文件名判断为同一集，内容不同。默认不勾选，比较后自行决定",
            )
            groups(report.versions, state)
        }
    }
}

private fun LazyListScope.sectionHeader(key: String, title: String, description: String) {
    item(key = "header:$key") {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.groups(groups: List<DuplicateGroup>, state: DuplicateFinderState) {
    groups.forEach { group ->
        val prefix = "${group.kind}:${group.key}"
        item(key = "$prefix:head") {
            GroupHeader(group, state)
        }
        items(group.rows, key = { "$prefix:${it.file.id}" }) { row ->
            DuplicateRowItem(
                row = row,
                rootName = state.root.name,
                checked = row.file.id in state.selectedIds,
                kept = row.file.id == group.keptId && row.file.id !in state.selectedIds,
                enabled = !state.isTrashing,
                onToggle = { state.toggle(row.file.id) },
            )
        }
        // 组与组之间没有容器区分，分割线是唯一的边界。不用 alpha 兑色：兑出来的对比度取决于底下是
        // surface 还是对话框的 surfaceContainerHigh，深色主题下几乎看不见
        item(key = "$prefix:divider") {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun GroupHeader(group: DuplicateGroup, state: DuplicateFinderState) {
    val ids = group.rows.map { it.file.id }
    val noneSelected = ids.none { it in state.selectedIds }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (group.kind) {
                    DuplicateKind.IDENTICAL -> "${group.rows.size} 份，可释放 ${group.reclaimableBytes.toReadableSize()}"
                    DuplicateKind.VERSIONS -> "${group.rows.size} 个版本，共 ${group.totalBytes.toReadableSize()}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            !noneSelected -> TextButton(onClick = { state.keepAll(group) }, enabled = !state.isTrashing) { Text("全部保留") }
            group.kind == DuplicateKind.IDENTICAL -> {
                TextButton(onClick = { state.selectDefault(group) }, enabled = !state.isTrashing) { Text("只留一份") }
            }
        }
    }
}

@Composable
private fun DuplicateRowItem(
    row: DuplicateRow,
    rootName: String,
    checked: Boolean,
    kept: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val file = row.file
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (kept) {
                    Spacer(Modifier.width(8.dp))
                    HighlightBadge("保留")
                }
            }
            // 路径可能很长，省略开头：离文件最近的几层目录最能说明它在哪
            Text(
                text = if (file.folderPath.isEmpty()) rootName else "$rootName/${file.folderPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
            MetaRow(
                parts = buildList {
                    add(file.size.toReadableSize())
                    if (file.width != null && file.height != null) add("${file.width}×${file.height}")
                    file.durationSeconds?.let { add(formatDuration(it)) }
                    if (file.modifiedTime.isNotEmpty()) add(file.modifiedTime.take(10))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val extras = buildList {
                addAll(row.details)
                if (row.sameCopies > 0) add("另有 ${row.sameCopies} 份相同副本")
            }
            if (extras.isNotEmpty()) {
                MetaRow(
                    parts = extras,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(count: Int, bytes: Long, busy: Boolean, onTrash: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (count == 0) "未选择文件" else "已选 $count 个，共 ${bytes.toReadableSize()}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onTrash,
                enabled = count > 0 && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                if (busy) InlineLoadingIndicator(color = LocalContentColor.current) else Text("移入回收站")
            }
        }
    }
}

private fun scanSummary(state: DuplicateFinderState): String = buildString {
    append("扫描了 ${state.scannedFolders} 个文件夹，${state.scannedFiles} 个文件")
    when (state.scanStop) {
        ScanStop.CANCELLED -> append("。扫描已停止，结果只含已扫描的部分")
        ScanStop.FOLDER_LIMIT, ScanStop.FILE_LIMIT -> append("。已达扫描上限，结果只含已扫描的部分，可进入子文件夹分别查找")
        ScanStop.TIMEOUT -> append("。扫描超时，结果只含已扫描的部分，可进入子文件夹分别查找")
        null -> Unit
    }
    if (state.failedFolders > 0) append("。${state.failedFolders} 个文件夹读取失败，已跳过")
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    fun Long.pad() = toString().padStart(2, '0')
    return if (h > 0) "$h:${m.pad()}:${s.pad()}" else "$m:${s.pad()}"
}
