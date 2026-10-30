package dev.piko.ui.workbench

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

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
import dev.piko.shared.presentation.localizedName
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
                                        ?: draft.batch?.let { tr("${it.rows.size} 条链接", "${enCount(it.rows.size, "link", "links")}", "${it.rows.size} 件のリンク", "링크 ${it.rows.size}개", "${it.rows.size} 條連結") }
                                        ?: tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結")
                                    dev.piko.ui.components.PikoScaffold(
                                        modifier = Modifier.fillMaxSize().imePadding(),
                                        topBar = {
                                            dev.piko.ui.components.PikoTopBar(
                                                title = topTitle,
                                                navigationIcon = { dev.piko.ui.components.TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回来源", "Back to source", "ソースに戻る", "원본으로 돌아가기", "返回來源"), onMinimize) },
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
            val tabs = remember(contexts, files, dev.piko.shared.i18n.PikoLocale.effectiveLanguage) {
                val fileById = files.associateBy { it.id }
                contexts.map { context ->
                    when (context) {
                        is WorkspaceContext.File -> fileById[context.id] ?: DriveTab(context.id, emptyList())
                        is WorkspaceContext.Duplicate -> DriveTab(context.id, listOf(PikoPathBreadcrumb("context:${context.id}", tr("查重：${context.root.localizedName}", "Duplicates: ${context.root.localizedName}", "重複検索：${context.root.localizedName}", "중복 검색: ${context.root.localizedName}", "查重：${context.root.localizedName}"))))
                        is WorkspaceContext.ImportDraft -> DriveTab(context.id, listOf(PikoPathBreadcrumb("context:${context.id}", tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"))))
                    }
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
                            SessionTab(summary?.title ?: tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"), summary?.status ?: tr("导入草稿", "Import draft", "インポートの下書き", "가져오기 초안", "匯入草稿"), summary?.busy == true, Icons.Outlined.Bolt)
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
            title = { Text(stringResource(Res.string.text_unable_to_close_tab)) },
            text = { Text(message) },
            confirmButton = { TextButton(model::dismissBlockedClose) { Text(stringResource(Res.string.common_got_it)) } },
        )
        return
    }
    if (model.pendingCloseOtherIds.isNotEmpty()) {
        PikoDialog(
            onDismissRequest = model::cancelCloseOtherContexts,
            title = { Text(tr("关闭其他 ${model.pendingCloseOtherIds.size} 个标签页？", "Close ${model.pendingCloseOtherIds.size} other tabs?", "ほかの ${model.pendingCloseOtherIds.size} タブを閉じますか？", "다른 탭 ${model.pendingCloseOtherIds.size}개를 닫을까요?", "關閉其他 ${model.pendingCloseOtherIds.size} 個分頁？")) },
            text = { Text(tr("将保留当前标签，关闭其余 ${model.pendingCloseOtherIds.size} 个标签页。", "Keep this tab and close the other ${model.pendingCloseOtherIds.size} tabs.", "現在のタブを残し、ほかの ${model.pendingCloseOtherIds.size} タブを閉じます。", "현재 탭을 유지하고 다른 탭 ${model.pendingCloseOtherIds.size}개를 닫습니다.", "將保留目前分頁，關閉其餘 ${model.pendingCloseOtherIds.size} 個分頁。") +
                if (model.pendingCloseOtherNeedsAttention) tr("未提交草稿和查重结果将不再保留，扫描将停止。已提交的任务继续执行。", "Unsaved drafts and duplicate results will be discarded, and scans stopped. Submitted tasks continue.", "未送信の下書きと重複検索結果を破棄し、検索を停止します。送信済みタスクは続行します。", "미제출 초안과 중복 검색 결과를 버리고 검색을 중지합니다. 제출된 작업은 계속됩니다.", "未提交的草稿和重複項目搜尋結果將不再保留，掃描將停止。已提交的任務會繼續執行。") else "") },
            confirmButton = { PikoDialogConfirm(tr("关闭其他标签", "Close other tabs", "ほかのタブを閉じる", "다른 탭 닫기", "關閉其他分頁"), model::confirmCloseOtherContexts, destructive = true) },
            dismissButton = { TextButton(model::cancelCloseOtherContexts) { Text(stringResource(Res.string.common_cancel)) } },
        )
        return
    }
    val context = model.contexts.contextsFlow.value.firstOrNull { it.id == model.pendingCloseId } ?: return
    val duplicate = context is WorkspaceContext.Duplicate
    val scanning = model.duplicateSessions[context.id]?.state?.isScanning == true
    val name = workspaceTitle(model, context)
    val hasAddLinkOnTab = context is WorkspaceContext.File && model.hasUncommittedAddLinkOnTab(context.id)
    val hasGalleryOnTab = context is WorkspaceContext.File && model.hasGalleryOnTab(context.id)
    val fileCloseText = when {
        hasAddLinkOnTab && hasGalleryOnTab -> tr("关闭此标签页将退出瀑布流浏览，并一并放弃当前输入的链接和勾选的文件。", "Closing exits the gallery and discards entered links and selected files.", "このタブを閉じるとギャラリーを終了し、入力したリンクと選択したファイルを破棄します。", "탭을 닫으면 갤러리를 종료하고 입력한 링크와 선택한 파일을 버립니다.", "關閉此分頁將結束瀑布流瀏覽，並一併捨棄目前輸入的連結和勾選的檔案。")
        hasGalleryOnTab -> tr("将退出瀑布流浏览并关闭此标签页，回到文件列表。", "Exit the gallery, close this tab and return to files.", "ギャラリーを終了してこのタブを閉じ、ファイル一覧に戻ります。", "갤러리를 종료하고 이 탭을 닫아 파일 목록으로 돌아갑니다.", "將結束瀑布流瀏覽並關閉此分頁，返回檔案清單。")
        else -> tr("关闭此标签页将一并放弃当前输入的链接和勾选的文件。", "Closing discards entered links and selected files.", "このタブを閉じると入力したリンクと選択したファイルを破棄します。", "탭을 닫으면 입력한 링크와 선택한 파일을 버립니다.", "關閉此分頁將一併捨棄目前輸入的連結和勾選的檔案。")
    }
    PikoDialog(
        onDismissRequest = model::cancelCloseContext,
        title = { Text(if (context is WorkspaceContext.File) tr("关闭标签页？", "Close tab?", "タブを閉じますか？", "탭을 닫을까요?", "關閉分頁？") else if (duplicate) tr("关闭查重？", "Close duplicate search?", "重複検索を閉じますか？", "중복 검색을 닫을까요?", "關閉查重？") else tr("关闭草稿？", "Close draft?", "下書きを閉じますか？", "초안을 닫을까요?", "關閉草稿？")) },
        text = { Text(if (context is WorkspaceContext.File) fileCloseText else "「$name」" + if (duplicate) (if (scanning) tr("的扫描将停止，已有结果不再保留。已执行的文件操作不会撤销。", " will stop scanning and discard results. Completed file operations are kept.", "の検索を停止して結果を破棄します。実行済みのファイル操作は元に戻しません。", " 검색을 중지하고 결과를 버립니다. 실행된 파일 작업은 취소되지 않습니다.", "的掃描將停止，已有結果不再保留。已執行的檔案操作不會復原。") else tr("的本次查重结果将不再保留。已执行的文件操作不会撤销。", " will discard duplicate results. Completed file operations are kept.", "の今回の重複検索結果を破棄します。実行済みのファイル操作は元に戻しません。", " 중복 검색 결과를 버립니다. 실행된 파일 작업은 취소되지 않습니다.", "的本次重複項目搜尋結果將不再保留。已執行的檔案操作不會復原。"))
            else tr("中未提交的内容将不再保留。已提交的保存继续执行，结果可在任务页查看。", " will discard unsubmitted contents. Submitted saves continue; check results in Tasks.", "の未送信の内容を破棄します。送信済みの保存は続行し、タスク画面で結果を確認できます。", " 미제출 내용을 버립니다. 제출된 저장은 계속되며 작업 페이지에서 결과를 확인할 수 있습니다.", "中未提交的內容將不再保留。已提交的儲存作業會繼續執行，結果可在任務頁查看。")) },
        confirmButton = { PikoDialogConfirm(tr("关闭", "Close", "閉じる", "닫기", "關閉"), model::confirmCloseContext, destructive = true,
            modifier = Modifier.semantics { contentDescription = tr("确认关闭工作", "Confirm closing work", "作業を閉じる確認", "작업 닫기 확인", "確認關閉工作") }) },
        dismissButton = { TextButton(model::cancelCloseContext) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
