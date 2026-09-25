package dev.piko.ui.screens.duplicates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.ScanStop
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateFinderState.Phase
import dev.piko.shared.state.DuplicateGroup
import dev.piko.shared.state.DuplicateKind
import dev.piko.shared.state.DuplicateRow
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.HighlightBadge
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform

/**
 * 在 [root] 下查找重复文件。状态随对话框创建与销毁：关掉即停止扫描，下次打开重新扫描。
 * 扫描结果依赖网盘此刻的内容，留着旧结果反而可能按过期的列表去删。
 */
@Composable
fun DuplicatesDialog(root: PikoPathBreadcrumb, onDismiss: () -> Unit) {
    val services = LocalPikoServices.current
    val scope = rememberCoroutineScope()
    val state = remember(root.id) {
        DuplicateFinderState(services.clientManager, services.driveRepository, scope, root)
    }
    DuplicatesDialog(state, onDismiss)
}

@Composable
fun DuplicatesDialog(state: DuplicateFinderState, onDismiss: () -> Unit) {
    // 与目录选择器相同：compact 下全屏，更宽时是居中的大对话框
    if (currentWidthClass() == WidthClass.Compact) {
        LocalPikoPlatform.current.FullscreenDialog(
            onDismiss = onDismiss,
            immersive = false,
            systemBarsVisible = true,
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(modifier = Modifier.safeDrawingPadding()) { DuplicatesContent(state, onDismiss) }
            }
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DuplicatesContent(state, onDismiss)
            }
        }
    }
}

@Composable
private fun DuplicatesContent(state: DuplicateFinderState, onDismiss: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    var confirming by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }
    BackHandler(onBack = onDismiss)

    val report = state.report
    val hasGroups = report.identical.isNotEmpty() || report.versions.isNotEmpty()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PikoTopBar(
                title = "查找重复",
                navigationIcon = {
                    TooltipIconButton(icon = Icons.Outlined.Close, label = "关闭", shortcut = "Esc", onClick = onDismiss)
                },
                actions = {
                    if (state.phase == Phase.DONE || state.phase == Phase.FAILED) {
                        TooltipIconButton(icon = Icons.Outlined.Refresh, label = "重新扫描", onClick = state::rescan)
                    }
                },
            )
        },
        bottomBar = {
            if (state.phase == Phase.DONE && hasGroups) {
                SelectionBar(
                    count = state.selectedIds.size,
                    bytes = state.selectedBytes,
                    busy = state.isTrashing,
                    onTrash = { confirming = true },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.phase) {
                Phase.SCANNING, Phase.ANALYZING -> ScanningPane(state)
                Phase.FAILED -> PikoEmptyState(
                    title = "扫描失败",
                    description = state.errorMessage,
                    icon = Icons.Outlined.ErrorOutline,
                    actionText = "重试",
                    onActionClick = state::rescan,
                    modifier = Modifier.align(Alignment.Center),
                )
                Phase.DONE -> if (hasGroups) {
                    ResultList(state)
                } else {
                    PikoEmptyState(
                        title = "没有重复文件",
                        description = scanSummary(state),
                        icon = Icons.Outlined.TaskAlt,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
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
                Button(
                    onClick = {
                        confirming = false
                        state.trashSelected()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("移入回收站") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScanningPane(state: DuplicateFinderState) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
                description = "内容一致，默认只保留最早存入的一份，同时存入时保留路径最短的",
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
        item(key = "$prefix:divider") {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
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
                if (busy) PikoLoadingIndicator(size = 20.dp) else Text("移入回收站")
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
