package dev.piko.ui.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.DriveTab
import dev.piko.shared.presentation.WorkspaceContext
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.drive.*
import dev.piko.ui.screens.instant.InstantSheetContent
import dev.piko.ui.screens.instant.summary
import io.github.nihildigit.pikpak.FileStat

@Composable
internal fun WorkspaceContextHost(
    model: WorkspaceViewModel,
    onPlay: (FileStat, List<FileStat>) -> Unit,
    onReveal: (FileStat) -> Unit,
    onTransfers: () -> Unit,
    fileContent: @Composable () -> Unit,
) {
    val contexts by model.contexts.contextsFlow.collectAsStateWithLifecycle()
    val active by model.contexts.activeContextId.collectAsStateWithLifecycle()
    val states = rememberSaveableStateHolder()
    val retained = remember { mutableSetOf<Long>() }
    LaunchedEffect(contexts.map { it.id }) {
        val live = contexts.mapTo(mutableSetOf()) { it.id }
        (retained - live).forEach(states::removeState)
        retained.clear()
        retained.addAll(live)
    }

    @Composable
    fun ContextContent(context: WorkspaceContext) {
        states.SaveableStateProvider(context.id) {
            when (context) {
                is WorkspaceContext.File -> fileContent()
                is WorkspaceContext.Duplicate -> {
                    if (context.id in model.duplicateSessions) DriveScreen(
                        onNavigateToFolder = { _, _ -> },
                        onNavigateToVideoPlayer = onPlay,
                        browserSession = model.duplicateBrowser(context.id),
                        onRevealInDrive = onReveal,
                        onOpenTransfers = onTransfers,
                        onCloseContext = { model.requestCloseContext(context.id) },
                        interactionEnabled = model.desktop || (model.sheetExpanded && model.sheetContextId == context.id),
                        onLibraryLeft = {
                            if (!model.desktop) {
                                if (model.sheetDetailed) model.returnToSheet() else model.setSheetExpansion(false)
                            }
                        },
                    )
                }
                is WorkspaceContext.ImportDraft -> model.importModels[context.id]?.state?.let { draft ->
                    Box(Modifier.fillMaxSize().padding(horizontal = if (model.desktop) 24.dp else 0.dp)) {
                        InstantSheetContent(draft, headerShown = !model.desktop, onPreview = { id, name ->
                            onPlay(FileStat(id = id, name = name), emptyList())
                        })
                    }
                }
            }
        }
    }

    if (model.desktop) {
        val workspace = requireNotNull(model.desktopWorkspace)
        val files by workspace.tabsFlow.collectAsStateWithLifecycle()
        val tabs = contexts.map { context ->
            when (context) {
                is WorkspaceContext.File -> files.firstOrNull { it.id == context.id } ?: DriveTab(context.id, emptyList())
                is WorkspaceContext.Duplicate -> DriveTab(context.id, listOf(PikoPathBreadcrumb("context:${context.id}", "查重：${context.root.name}")))
                is WorkspaceContext.ImportDraft -> DriveTab(context.id, listOf(PikoPathBreadcrumb("context:${context.id}", "添加链接")))
            }
        }
        val shortcut = LocalPikoPlatform.current.shortcutModifier
        Column(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else if (shortcut.isPressed(event) && event.key == Key.Tab) {
                model.cycleContext(if (event.isShiftPressed) -1 else 1); true
            } else false
        }.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !shortcut.isPressed(event)) false
            else when (event.key) {
                Key.W -> { model.requestCloseContext(active); true }
                Key.T -> { workspace.openTab(workspace.folderStackFlow.value); true }
                else -> false
            }
        }) {
            DriveTabBar(
                tabs = tabs, activeId = active,
                onSelect = model::activateContext, onClose = model::requestCloseContext,
                feedTabId = model.feed.detourTab,
                canClose = { id -> contexts.firstOrNull { it.id == id } !is WorkspaceContext.File || contexts.count { it is WorkspaceContext.File } > 1 },
                onNewTab = { workspace.openTab(workspace.folderStackFlow.value) },
                newTabShortcut = shortcut.label("T"),
                sessionTab = { tab ->
                    when (contexts.firstOrNull { it.id == tab.id }) {
                        is WorkspaceContext.Duplicate -> model.duplicateSessions[tab.id]?.state?.let(::duplicatesTab)
                        is WorkspaceContext.ImportDraft -> {
                            val summary = model.importModels[tab.id]?.state?.summary()
                            SessionTab(summary?.title ?: "添加链接", summary?.status ?: "导入草稿", summary?.busy == true, Icons.Outlined.Bolt)
                        }
                        else -> null
                    }
                },
            )
            Box(Modifier.weight(1f)) { contexts.firstOrNull { it.id == active }?.let { ContextContent(it) } }
        }
    } else {
        val pages = contexts.filter { it !is WorkspaceContext.File }.map { context ->
            val finder = model.duplicateSessions[context.id]?.state
            val draft = model.importModels[context.id]?.state
            val summary = draft?.summary()
            val title = when (context) {
                is WorkspaceContext.Duplicate -> "查重：${context.root.name}"
                else -> summary?.title ?: "添加链接"
            }
            val status = if (finder != null) {
                when {
                    finder.isScanning -> "正在查找重复"
                    finder.errorMessage != null -> "扫描未完成"
                    finder.scanStop != null || finder.failedFolders > 0 -> "部分扫描结果"
                    else -> "扫描完成"
                }
            } else summary?.status ?: "导入草稿"
            TaskSheetModel(
                key = context.id, title = title, summary = status,
                expanded = model.sheetExpanded,
                onExpandedChange = model::setSheetExpansion,
                header = {
                    ContextPeekHeader(
                        title, status,
                        if (finder != null) Icons.Outlined.ContentCopy else Icons.Outlined.Bolt,
                        if (context is WorkspaceContext.Duplicate) "结束查重" else "放弃草稿",
                    ) { model.requestCloseContext(context.id) }
                },
                body = { ContextContent(context) },
                tabTitle = when (context) {
                    is WorkspaceContext.Duplicate -> "查重：${context.root.name}"
                    else -> draft?.resolution?.resource?.name ?: if (model.importDrafts.size > 1) {
                        "添加链接 ${model.importDrafts[context.id]?.id ?: ""}"
                    } else "添加链接"
                },
                actions = {
                    ContextActions(title, if (context is WorkspaceContext.Duplicate) "结束查重" else "放弃草稿") {
                        model.requestCloseContext(context.id)
                    }
                },
            )
        }
        val detailed = contexts.firstOrNull { it.id == model.sheetContextId && it !is WorkspaceContext.File }
        if (model.sheetDetailed && detailed != null) {
            BackHandler { model.returnToSheet() }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = model::returnToSheet) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回面板")
                    }
                    Text(
                        pages.first { it.key == detailed.id }.title,
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { model.requestCloseContext(detailed.id) }) {
                        Icon(Icons.Outlined.Close, "关闭上下文")
                    }
                }
                Box(Modifier.weight(1f)) { ContextContent(detailed) }
            }
        } else TaskSheetScaffold(
            models = pages, activeKey = model.sheetContextId, expanded = model.sheetExpanded,
            onActiveKeyChange = { model.sheetContextId = it as Long; model.contexts.activate(it) },
            onExpandedChange = model::setSheetExpansion,
            onViewDetails = { model.viewSheetDetails(it as Long) },
            content = fileContent,
        )
    }
}

@Composable
private fun ContextPeekHeader(title: String, status: String?, icon: ImageVector, closeLabel: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
        ContextActions(title, closeLabel, onClose)
    }
}

@Composable
private fun ContextActions(title: String, closeLabel: String, onClose: () -> Unit) {
    var menuShown by remember(title) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuShown = true }) { Icon(Icons.Outlined.MoreVert, "操作：$title") }
        DropdownMenu(expanded = menuShown, onDismissRequest = { menuShown = false }) {
            DropdownMenuItem(text = { Text(closeLabel) }, onClick = { menuShown = false; onClose() })
        }
    }
}

@Composable
internal fun WorkspaceContextDialogs(model: WorkspaceViewModel) {
    model.pendingDuplicateRoot?.let { root ->
        PikoDialog(
            onDismissRequest = model::cancelDuplicateReplacement,
            title = { Text("替换当前查重？") },
            text = { Text("当前扫描会停止，结果会被丢弃，然后查找「${root.name}」中的重复文件。") },
            confirmButton = { PikoDialogConfirm("替换", model::confirmDuplicateReplacement, destructive = true) },
            dismissButton = { TextButton(model::cancelDuplicateReplacement) { Text("取消") } },
        )
    }
    if (model.pendingCloseId != null) PikoDialog(
        onDismissRequest = model::cancelCloseContext,
        title = { Text("放弃添加？") },
        text = { Text("未提交的内容将被丢弃。已提交的保存继续执行，结果可在任务页查看。") },
        confirmButton = { PikoDialogConfirm("放弃", model::confirmCloseContext, destructive = true) },
        dismissButton = { TextButton(model::cancelCloseContext) { Text("取消") } },
    )
}
