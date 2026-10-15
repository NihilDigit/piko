package dev.piko.ui.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    mobileContextId: Long? = null,
    onMinimize: () -> Unit = {},
    retainedStates: androidx.compose.runtime.saveable.SaveableStateHolder? = null,
    fileContent: @Composable () -> Unit,
) {
    val contexts by model.contexts.contextsFlow.collectAsStateWithLifecycle()
    val active by model.contexts.activeContextId.collectAsStateWithLifecycle()
    val states = retainedStates ?: rememberSaveableStateHolder()
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
                        rootTitle = workspaceTitle(model, context),
                        interactionEnabled = true,
                        onLibraryLeft = {
                            if (!model.desktop) {
                                onMinimize()
                            }
                        },
                    )
                }
                is WorkspaceContext.ImportDraft -> model.importModels[context.id]?.state?.let { draft ->
                    Box(Modifier.fillMaxSize().padding(horizontal = if (model.desktop) 24.dp else 0.dp)) {
                        InstantSheetContent(draft, headerShown = !model.desktop, fillHeight = !model.desktop, onPreview = { id, name ->
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
    } else if (mobileContextId != null) {
        val context = contexts.firstOrNull { it.id == mobileContextId } ?: return
        CompositionLocalProvider(LocalWorkspaceSurface provides WorkspaceSurface(context.id, onMinimize)) {
            BackHandler(onBack = onMinimize)
            if (context is WorkspaceContext.ImportDraft) {
                dev.piko.ui.components.PikoScaffold(
                    modifier = Modifier.fillMaxSize().imePadding(),
                    topBar = {
                        dev.piko.ui.components.PikoTopBar(
                            title = "添加链接",
                            persistentActions = { WorkspaceControls() },
                        )
                    },
                    snackbarHost = { SnackbarHost(dev.piko.ui.LocalRootSnackbar.current) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { ContextContent(context) }
                }
            } else Box(Modifier.fillMaxSize().imePadding()) { ContextContent(context) }
        }
    } else fileContent()
}

@Composable
internal fun WorkspaceContextDialogs(model: WorkspaceViewModel) {
    val context = model.contexts.contextsFlow.value.firstOrNull { it.id == model.pendingCloseId } ?: return
    val duplicate = context is WorkspaceContext.Duplicate
    val scanning = model.duplicateSessions[context.id]?.state?.isScanning == true
    val name = workspaceTitle(model, context)
    PikoDialog(
        onDismissRequest = model::cancelCloseContext,
        title = { Text(if (duplicate) "关闭查重？" else "关闭草稿？") },
        text = { Text("「$name」" + if (duplicate) (if (scanning) "的扫描将停止，已有结果不再保留。已执行的文件操作不会撤销。" else "的本次查重结果将不再保留。已执行的文件操作不会撤销。")
            else "中未提交的内容将不再保留。已提交的保存继续执行，结果可在任务页查看。") },
        confirmButton = { PikoDialogConfirm("关闭", model::confirmCloseContext, destructive = true,
            modifier = Modifier.semantics { contentDescription = "确认关闭工作" }) },
        dismissButton = { TextButton(model::cancelCloseContext) { Text("取消") } },
    )
}
