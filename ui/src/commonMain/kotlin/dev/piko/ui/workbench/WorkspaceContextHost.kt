package dev.piko.ui.workbench

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.frame
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
    onMinimize: () -> Unit = {},
    retainedStates: androidx.compose.runtime.saveable.SaveableStateHolder? = null,
    fileContent: @Composable () -> Unit,
) {
    val wide = dev.piko.ui.adaptive.isDesktopLayout()
    val contexts by model.contexts.contextsFlow.collectAsStateWithLifecycle()
    val active by model.contexts.activeContextId.collectAsStateWithLifecycle()
    val states = retainedStates ?: rememberSaveableStateHolder()
    val retained = remember { mutableSetOf<Long>() }
    LaunchedEffect(contexts.map { it.id }) {
        val live = contexts.mapTo(mutableSetOf()) { it.id }
        (retained - live).forEach(states::removeState)
        retained.clear()
        retained.addAll(live)
        model.pageSnapshots.retain(live)
    }

    @Composable
    fun ContextContent(context: WorkspaceContext) {
        key(context.id) {
            states.SaveableStateProvider(context.id) {
                WorkspacePageSnapshot(context.id, enabled = !wide, onSnapshot = { model.retainPageSnapshot(context.id, it) }) {
                    when (context) {
                        is WorkspaceContext.File -> fileContent()
                        is WorkspaceContext.Duplicate -> {
                            val requestCloseDuplicate = { model.requestCloseContext(context.id) }
                            CompositionLocalProvider(
                                LocalWorkspaceSurface provides if (!wide) WorkspaceSurface(context.id, requestCloseDuplicate) else null,
                            ) {
                                BackHandler(enabled = !wide, onBack = requestCloseDuplicate)
                                Box(Modifier.fillMaxSize().then(if (!wide) Modifier.imePadding() else Modifier)) {
                                    if (context.id in model.duplicateSessions) DriveScreen(
                                        onNavigateToFolder = { _, _ -> },
                                        onNavigateToVideoPlayer = onPlay,
                                        browserSession = model.duplicateBrowser(context.id),
                                        onRevealInDrive = onReveal,
                                        onOpenTransfers = onTransfers,
                                        onCloseContext = requestCloseDuplicate,
                                        rootTitle = workspaceTitle(model, context),
                                        interactionEnabled = true,
                                        onLibraryLeft = requestCloseDuplicate,
                                    )
                                }
                            }
                        }
                        is WorkspaceContext.ImportDraft -> model.importModels[context.id]?.state?.let { draft ->
                            val content: @Composable () -> Unit = {
                                InstantSheetContent(draft, view = model.importView(context.id), headerShown = true, fillHeight = true, onPreview = { id, name ->
                                    onPlay(FileStat(id = id, name = name), emptyList())
                                })
                            }
                            if (wide) {
                                val summary = draft.summary()
                                dev.piko.ui.components.IslandSurface(
                                    connection = if (contexts.firstOrNull()?.id == active) dev.piko.ui.components.IslandConnection.FirstTab else dev.piko.ui.components.IslandConnection.OtherTab,
                                    modifier = Modifier.fillMaxSize().padding(end = dev.piko.ui.theme.IslandGap, bottom = dev.piko.ui.theme.FrameCardBottomMargin),
                                    header = {
                                        Row(
                                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Icon(Icons.Outlined.Bolt, contentDescription = null)
                                            Text(summary.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                                            summary.status?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                                        }
                                    },
                                    content = content,
                                )
                            } else {
                                CompositionLocalProvider(LocalWorkspaceSurface provides WorkspaceSurface(context.id, onMinimize)) {
                                    BackHandler(onBack = onMinimize)
                                    val topTitle = draft.resolution?.resource?.name
                                        ?: draft.batch?.let { "${it.rows.size} 条链接" }
                                        ?: "添加链接"
                                    dev.piko.ui.components.PikoScaffold(
                                        modifier = Modifier.fillMaxSize().imePadding(),
                                        topBar = {
                                            dev.piko.ui.components.PikoTopBar(
                                                title = topTitle,
                                                navigationIcon = { dev.piko.ui.components.TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回来源", onMinimize) },
                                                persistentActions = { WorkspaceControls() },
                                            )
                                        },
                                        snackbarHost = { SnackbarHost(dev.piko.ui.LocalRootSnackbar.current) },
                                    ) { padding ->
                                        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val activeContext = contexts.firstOrNull { it.id == active }
    Column(Modifier.fillMaxSize().then(if (wide) Modifier.background(MaterialTheme.colorScheme.frame) else Modifier)) {
        if (wide) {
            val workspace = requireNotNull(model.driveWorkspace)
            val files by workspace.tabsFlow.collectAsStateWithLifecycle()
            val tabs = contexts.map { context ->
                when (context) {
                    is WorkspaceContext.File -> files.firstOrNull { it.id == context.id } ?: DriveTab(context.id, emptyList())
                    is WorkspaceContext.Duplicate -> DriveTab(context.id, listOf(PikoPathBreadcrumb("context:${context.id}", "查重：${context.root.name}")))
                    is WorkspaceContext.ImportDraft -> DriveTab(context.id, listOf(PikoPathBreadcrumb("context:${context.id}", "添加链接")))
                }
            }
            val shortcut = LocalPikoPlatform.current.shortcutModifier
            DriveTabBar(
                tabs = tabs, activeId = active,
                onSelect = model::activateContext, onClose = model::requestCloseContext,
                feedTabId = model.feed.detourTab,
                canClose = model::canCloseContext,
                onCloseOthers = model::requestCloseOtherContexts,
                onMove = model::moveContext,
                onNewTab = model::newFileContext,
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
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (activeContext != null) ContextContent(activeContext)
        }
    }
}

@Composable
internal fun WorkspaceContextDialogs(model: WorkspaceViewModel) {
    model.blockedCloseMessage?.let { message ->
        PikoDialog(
            onDismissRequest = model::dismissBlockedClose,
            title = { Text("无法关闭标签页") },
            text = { Text(message) },
            confirmButton = { TextButton(model::dismissBlockedClose) { Text("知道了") } },
        )
        return
    }
    if (model.pendingCloseOtherIds.isNotEmpty()) {
        PikoDialog(
            onDismissRequest = model::cancelCloseOtherContexts,
            title = { Text("关闭其他 ${model.pendingCloseOtherIds.size} 个标签页？") },
            text = { Text("将保留当前标签，关闭其余 ${model.pendingCloseOtherIds.size} 个标签页。" +
                if (model.pendingCloseOtherNeedsAttention) "未提交草稿和查重结果将不再保留，扫描将停止。已提交的任务继续执行。" else "") },
            confirmButton = { PikoDialogConfirm("关闭其他标签", model::confirmCloseOtherContexts, destructive = true) },
            dismissButton = { TextButton(model::cancelCloseOtherContexts) { Text("取消") } },
        )
        return
    }
    val context = model.contexts.contextsFlow.value.firstOrNull { it.id == model.pendingCloseId } ?: return
    val duplicate = context is WorkspaceContext.Duplicate
    val scanning = model.duplicateSessions[context.id]?.state?.isScanning == true
    val name = workspaceTitle(model, context)
    PikoDialog(
        onDismissRequest = model::cancelCloseContext,
        title = { Text(if (context is WorkspaceContext.File) "关闭标签页？" else if (duplicate) "关闭查重？" else "关闭草稿？") },
        text = { Text(if (context is WorkspaceContext.File) "关闭此标签页将一并放弃当前输入的链接和勾选的文件。" else "「$name」" + if (duplicate) (if (scanning) "的扫描将停止，已有结果不再保留。已执行的文件操作不会撤销。" else "的本次查重结果将不再保留。已执行的文件操作不会撤销。")
            else "中未提交的内容将不再保留。已提交的保存继续执行，结果可在任务页查看。") },
        confirmButton = { PikoDialogConfirm("关闭", model::confirmCloseContext, destructive = true,
            modifier = Modifier.semantics { contentDescription = "确认关闭工作" }) },
        dismissButton = { TextButton(model::cancelCloseContext) { Text("取消") } },
    )
}
