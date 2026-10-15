package dev.piko.ui.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Minimize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.isHeightCompact
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.screens.instant.summary

internal data class WorkspaceSurface(val contextId: Long, val minimize: () -> Unit)
internal val LocalWorkspaceSurface = staticCompositionLocalOf<WorkspaceSurface?> { null }
internal val LocalShowOngoingWork = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun WorkspaceControls(compact: Boolean = false) {
    val model = LocalWorkspaceContexts.current ?: return
    if (model.desktop) return
    val show = LocalShowOngoingWork.current ?: return
    val contexts by model.contexts.contextsFlow.collectAsStateWithLifecycle()
    val count = contexts.count { it !is WorkspaceContext.File }
    val surface = LocalWorkspaceSurface.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (surface == null) {
            if (compact) TooltipIconButton(Icons.Outlined.Layers, "正在进行", show)
            else TextButton(onClick = show, modifier = Modifier.semantics { contentDescription = "正在进行" }) {
                Icon(Icons.Outlined.Layers, null, Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (count == 0) "正在进行" else "正在进行 $count", maxLines = 1)
            }
        } else {
            TooltipIconButton(Icons.Outlined.Layers, "正在进行", show)
            TooltipIconButton(Icons.Outlined.Minimize, "最小化", surface.minimize)
            TooltipIconButton(Icons.Outlined.Close, "关闭当前工作", { model.requestCloseContext(surface.contextId) })
        }
    }
}

internal fun workspaceTitle(model: WorkspaceViewModel, context: WorkspaceContext): String = when (context) {
    is WorkspaceContext.File -> "文件"
    is WorkspaceContext.Duplicate -> "查重：${context.root.name}"
    is WorkspaceContext.ImportDraft -> model.importModels[context.id]?.state?.summary()?.title ?: "添加链接"
}

@Composable
internal fun OngoingWorkSheet(model: WorkspaceViewModel, onDismiss: () -> Unit) {
    if (model.desktop) return
    if (currentWidthClass() == WidthClass.Compact || isHeightCompact()) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) { OngoingWorkList(model, onDismiss) }
    } else {
        val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() - 48.dp }
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight.coerceAtLeast(0.dp)),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) { OngoingWorkList(model, onDismiss, dialog = true) }
        }
    }
}

@Composable
private fun OngoingWorkList(model: WorkspaceViewModel, onDismiss: () -> Unit, dialog: Boolean = false) {
    val contexts by model.contexts.contextsFlow.collectAsStateWithLifecycle()
    val active by model.contexts.activeContextId.collectAsStateWithLifecycle()
    val entries = contexts.filter { it !is WorkspaceContext.File }
    Column(Modifier.fillMaxWidth().padding(top = if (dialog) 12.dp else 0.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("正在进行", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) Text("${entries.size} 项", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TooltipIconButton(Icons.Outlined.Close, "关闭工作列表", onDismiss)
        }
        if (entries.isEmpty()) {
            Text("没有保留的工作", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = if (dialog) 16.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val groups = listOf("导入草稿" to entries.filterIsInstance<WorkspaceContext.ImportDraft>(), "查重" to entries.filterIsInstance<WorkspaceContext.Duplicate>())
            groups.filter { it.second.isNotEmpty() }.forEach { (label, group) ->
                item(key = label) {
                    Text(label, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(group, key = { it.id }) { context ->
                    val title = workspaceTitle(model, context)
                    val displayTitle = (context as? WorkspaceContext.Duplicate)?.root?.name ?: title
                    val finder = model.duplicateSessions[context.id]?.state
                    val draft = model.importModels[context.id]?.state
                    val groups = finder?.let { it.report.identical.size + it.report.versions.size } ?: 0
                    val status = if (finder != null) when {
                        finder.isScanning -> "正在扫描 · ${finder.scannedFolders} 个文件夹"
                        finder.errorMessage != null -> "扫描未完成"
                        finder.scanStop != null || finder.failedFolders > 0 -> "部分结果 · $groups 组重复"
                        groups == 0 -> "未发现重复文件"
                        else -> "发现 $groups 组重复文件"
                    } else draft?.summary()?.status?.takeUnless { it == "导入草稿" }
                    Surface(shape = MaterialTheme.shapes.medium,
                        color = if (active == context.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f)
                                    .clickable(onClickLabel = if (draft != null) "继续编辑" else "查看查重", onClick = { onDismiss(); model.activateContext(context.id) })
                                    .semantics { contentDescription = title }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                                    draft?.target?.let { Text("保存到：${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(
                                onClick = { model.requestCloseContext(context.id) },
                                modifier = Modifier.semantics { contentDescription = "关闭：$title" },
                            ) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }
}
