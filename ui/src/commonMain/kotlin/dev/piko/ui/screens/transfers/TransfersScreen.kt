package dev.piko.ui.screens.transfers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SwapVerticalCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.i18n.tr
import dev.piko.shared.state.TransferItem
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransferSections
import dev.piko.shared.state.TransfersState
import dev.piko.shared.upload.UploadStatus
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.LocalRootSnackbar
import dev.piko.ui.ObserveMessages
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.components.DetailHost
import dev.piko.ui.components.DetailPanelKey
import dev.piko.ui.components.DetailPanelSpec
import dev.piko.ui.components.DetailPanelState
import dev.piko.ui.components.FileListSkeleton
import dev.piko.ui.components.IslandPage
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PanelAnchor
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoItemGrid
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.RefreshBox
import dev.piko.ui.components.fullLineItem
import dev.piko.ui.components.itemPanelAnchorArea
import dev.piko.ui.components.marqueeSelection
import dev.piko.ui.components.pageFocusTarget
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.text_clear_cloud_records
import dev.piko.ui.resources.text_clear_completed_alt
import dev.piko.ui.resources.text_file_deleted
import dev.piko.ui.serverWorkNotices
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.tabular
import dev.piko.ui.workbench.LocalWorkspaceContexts
import kotlinx.coroutines.launch

/**
 * 传输页：连接账号级 [TransfersState] 状态源，将顶栏筛选、分组网格、单行槽位与任务详情分发给
 * 顶层无状态可跳过组件，避免传输进度跳动时触发全表不可跳过重组。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransfersScreen(
    state: TransfersState,
    onNavigateToVideoPlayer: (fileId: String, fileName: String, localPath: String?) -> Unit,
    onCreateImport: (String) -> Unit = {},
    onOpenCloudFile: suspend (fileId: String, fileName: String) -> Boolean = { _, _ -> false },
    scrollToTopRequests: Int = 0,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val desktop = isDesktopLayout()
    val vaultSession = services.folderVaultSession
    val snackbarHostState = LocalRootSnackbar.current
    val haptic = LocalHapticFeedback.current

    val openCloudFileById: (String, String) -> Unit = { fileId, fileName ->
        scope.launch {
            if (!onOpenCloudFile(fileId, fileName)) {
                snackbarHostState.showSnackbar(
                    tr("文件已不存在", "File no longer exists", "ファイルは存在しません", "파일이 더 이상 존재하지 않습니다", "檔案已不存在"),
                    withDismissAction = true,
                )
            }
        }
    }

    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsStateWithLifecycle(
        initialValue = services.preferences.currentSnapshot.spoilerBlurEnabled,
    )
    val revealedKeys = rememberSaveable(saver = listSaver({ it.toList() }, { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { state.whileVisible() }
    }
    ObserveMessages(state.messages, snackbarHostState)
    if (!desktop) {
        LaunchedEffect(services, lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                services.serverWorkNotices(withUndoable = false).collect {
                    snackbarHostState.showSnackbar("${it.title}\n${it.message}", withDismissAction = true)
                }
            }
        }
    }
    LaunchedEffect(services) {
        services.transfersNotice.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    var deletedExpanded by rememberSaveable { mutableStateOf(false) }
    val expandedBatches = rememberSaveable(saver = listSaver({ it.toList() }, { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    fun toggleBatch(key: String) {
        if (!expandedBatches.remove(key)) expandedBatches.add(key)
    }

    fun copySourceUrl(url: String) {
        platform.copyToClipboard(tr("来源链接", "Source link", "ソースリンク", "출처 링크", "來源連結"), url)
        scope.launch {
            snackbarHostState.showSnackbar(
                if (url.startsWith("magnet:", true)) tr("已复制磁力链接", "Magnet link copied", "マグネットリンクをコピーしました", "마그넷 링크를 복사했습니다", "已複製磁力連結")
                else tr("已复制来源链接", "Source link copied", "ソースリンクをコピーしました", "출처 링크를 복사했습니다", "已複製來源連結"),
                withDismissAction = true,
            )
        }
    }

    val playLocal: (DownloadTask) -> Unit = { task ->
        onNavigateToVideoPlayer(
            if (task.isSegment) "" else task.fileId,
            task.fileName,
            task.destinationPath.takeIf { task.status == DownloadStatus.COMPLETED },
        )
    }
    val playCloud: (String, String) -> Unit = { fileId, fileName ->
        onNavigateToVideoPlayer(fileId, fileName, null)
    }

    val detailState = LocalWorkspaceContexts.current?.detailPanels?.stateFor(state, TaskDetailKey)
        ?: rememberSaveable(saver = DetailPanelState.keySaver(TaskDetailSpec)) { DetailPanelState<String>(TaskDetailSpec) }
    val detailsKey = detailState.target
    fun showDetails(key: String) {
        val anchor = detailState.anchors.beside(key)
        val at = if (anchor is PanelAnchor.Beside) {
            PanelAnchor.AtPoint(DpOffset((anchor.rect.right - TaskDetailSpec.maxWidth).coerceAtLeast(12.dp), anchor.rect.top))
        } else anchor
        detailState.show(key, at)
    }

    var confirmingDeleteKeys by remember { mutableStateOf<Set<String>?>(null) }
    var confirmingClearCompleted by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    var taskContentWidth by remember { mutableIntStateOf(0) }
    val taskLayout = remember(taskContentWidth, density) {
        TaskRowLayout.forWidth(with(density) { taskContentWidth.toDp() })
    }
    val taskPresenter = remember(state, vaultSession) { TaskPresenter(state, vaultSession) }

    val selectionActive = state.selectedKeys.isNotEmpty()
    val checkboxActive = state.checkboxMode
    val liveSections by remember(state) { derivedStateOf { state.sectionsOf(state.filter) } }
    val frozenOrder = remember(state.filter, selectionActive) { if (selectionActive) TaskListOrder(liveSections) else null }
    val currentSections = frozenOrder?.apply(liveSections) ?: liveSections
    val completedExpansion = remember { mutableStateMapOf<TransferKind, Boolean>() }
    fun completedExpanded(kind: TransferKind, sections: TransferSections) = completedExpansion[kind]
        ?: (sections.needsAttention.isEmpty() && sections.inProgress.isEmpty())
    val visibleOrder by remember(state, selectionActive, deletedExpanded, expandedBatches) {
        derivedStateOf {
            val activeKind = state.filter
            if (!selectionActive && expandedBatches.isEmpty()) {
                val keys = state.sectionKeysOf(activeKind)
                val showCompleted = completedExpansion[activeKind]
                    ?: (keys.needsAttention.isEmpty() && keys.inProgress.isEmpty())
                buildList(
                    keys.paused.size + keys.needsAttention.size + keys.inProgress.size +
                        (if (showCompleted) keys.completed.size else 0) +
                        (if (deletedExpanded) keys.outputDeleted.size else 0),
                ) {
                    addAll(keys.paused)
                    addAll(keys.needsAttention)
                    addAll(keys.inProgress)
                    if (showCompleted) addAll(keys.completed)
                    if (deletedExpanded) addAll(keys.outputDeleted)
                }
            } else {
                val sections = frozenOrder?.apply(state.sectionsOf(activeKind)) ?: state.sectionsOf(activeKind)
                (sections.paused + sections.needsAttention + sections.inProgress +
                    (if (completedExpanded(activeKind, sections)) sections.completed else emptyList()) +
                    if (deletedExpanded) sections.outputDeleted else emptyList())
                    .withBatchChildren(expandedBatches)
                    .map { it.key }
            }
        }
    }

    val listFocus = remember { FocusRequester() }
    fun focusList() = runCatching { listFocus.requestFocus() }

    val bulkSelected = state.selectedItems
    val pauseSelected = bulkSelected.filter(state::isPausable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::pause) } }
    val resumeSelected = bulkSelected.filter(state::isResumable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::resume) } }

    val latestOpenCloudFileById by rememberUpdatedState(openCloudFileById)
    val latestPlayLocal by rememberUpdatedState(playLocal)
    val latestPlayCloud by rememberUpdatedState(playCloud)
    val latestCreateImport by rememberUpdatedState(onCreateImport)
    val latestCheckboxActive by rememberUpdatedState(checkboxActive)
    val latestVisibleOrder by rememberUpdatedState(visibleOrder)
    val latestBulkSelected by rememberUpdatedState(bulkSelected)
    val latestPauseSelected by rememberUpdatedState(pauseSelected)
    val latestResumeSelected by rememberUpdatedState(resumeSelected)
    val latestHaptic by rememberUpdatedState(haptic)

    val actionCtx = remember(state, services, platform.localFiles, isSpoilerBlurEnabled, taskLayout.wide, detailState) {
        lateinit var ctx: TransferActionContext
        ctx = TransferActionContext(
            state = state,
            services = services,
            localFiles = platform.localFiles,
            isSpoilerBlurEnabled = isSpoilerBlurEnabled,
            wideLayout = taskLayout.wide,
            isBatchExpanded = { it in expandedBatches },
            isKeyRevealed = { it in revealedKeys },
            onToggleBatch = ::toggleBatch,
            onTogglePreviewReveal = { key -> if (!revealedKeys.remove(key)) revealedKeys.add(key) },
            onOpenBatchFolder = { batch, open -> scope.launch { state.batchFolderPath(batch)?.let(open) } },
            onOpenCloudFileById = { fileId, fileName -> latestOpenCloudFileById(fileId, fileName) },
            onCopySourceUrl = ::copySourceUrl,
            onPlayLocal = { latestPlayLocal(it) },
            onPlayCloud = { fileId, fileName -> latestPlayCloud(fileId, fileName) },
            onCreateImport = { latestCreateImport(it) },
            onShowDetails = ::showDetails,
            onConfirmSingleSideEffectDelete = { key ->
                detailState.close()
                if (key !in state.selectedKeys) state.selectOnly(key)
                confirmingDeleteKeys = setOf(key)
            },
            onPlacedRow = { key, coords -> detailState.anchors.placed(key, coords) },
            onSelectRow = { key ->
                if (latestCheckboxActive) state.toggleSelected(key) else state.selectOnly(key)
                focusList()
            },
            onToggleSelectRow = { key ->
                state.toggleSelected(key)
                focusList()
            },
            onExtendSelectRow = { key ->
                state.selectRange(key, latestVisibleOrder)
                focusList()
            },
            onLongClickRow = { key ->
                latestHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showDetails(key)
            },
            buildRowContextActions = { rowItem ->
                buildTransferContextActions(
                    item = rowItem,
                    ctx = ctx,
                    bulkSelected = latestBulkSelected,
                    visibleOrder = latestVisibleOrder,
                    pauseSelected = latestPauseSelected,
                    resumeSelected = latestResumeSelected,
                    onConfirmBulkDelete = { confirmingDeleteKeys = state.selectedKeys.toSet() },
                )
            },
        )
        ctx
    }

    // 复用 TransfersState 缓存的结构化键集合，避免每 500ms 进度刷新重建 HashSet
    val completedKeys = state.completedKeys
    val attentionKeys = state.attentionKeys

    val renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit = { item, itemModifier, index, count ->
        TransferItemRowSlot(
            item = item,
            index = index,
            count = count,
            checkboxActive = checkboxActive,
            isSelected = item.key in state.selectedKeys,
            isCompleted = when (item) {
                is TransferItem.Local -> item.task.status == DownloadStatus.COMPLETED
                is TransferItem.Upload -> item.task.status == UploadStatus.COMPLETED
                else -> item.key in completedKeys
            },
            isAttention = item.key in attentionKeys || when (item) {
                is TransferItem.Local -> item.task.status == DownloadStatus.FAILED
                is TransferItem.Upload -> item.task.status == UploadStatus.FAILED
                else -> false
            },
            isBlurred = isSpoilerBlurEnabled && item.key !in revealedKeys,
            isBatchExpanded = (item is TransferItem.LocalBatch || item is TransferItem.UploadBatch) && item.key in expandedBatches,
            taskLayout = taskLayout,
            taskPresenter = taskPresenter,
            actionCtx = actionCtx,
            modifier = itemModifier,
        )
    }

    val kinds = TransferKind.entries
    val gridStates = remember { kinds.associateWith { LazyGridState() } }
    val initialScrollRequests = remember { scrollToTopRequests }
    LaunchedEffect(scrollToTopRequests) {
        if (scrollToTopRequests != initialScrollRequests) gridStates.getValue(state.filter).animateScrollToItem(0)
    }

    val pagerState = rememberPagerState(initialPage = kinds.indexOf(state.filter)) { kinds.size }
    LaunchedEffect(state, pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (kinds[page] != state.filter) state.changeFilter(kinds[page])
        }
    }
    LaunchedEffect(state, state.filter) {
        val page = kinds.indexOf(state.filter)
        if (pagerState.targetPage != page) pagerState.animateScrollToPage(page)
    }

    BackHandler(enabled = selectionActive) { state.clearSelection() }

    // O(1) 哈希索引查找详情项，避免每 500ms 线性扫描 5 个分组
    val detailsItem = detailsKey?.let(state::findItemByKey)
    LaunchedEffect(detailsKey, detailsItem) {
        if (detailsKey != null && detailsItem == null) detailState.close()
    }

    val allVisibleSelected by remember(state, visibleOrder) {
        derivedStateOf { visibleOrder.isNotEmpty() && visibleOrder.all { it in state.selectedKeys } }
    }
    val pauseCount = bulkSelected.count(state::isPausable)
    val resumeCount = bulkSelected.count(state::isResumable)
    val headerActions = taskPageActions(
        state = state,
        selectedCount = bulkSelected.size,
        allSelected = allVisibleSelected,
        onToggleSelectAll = {
            if (allVisibleSelected) state.clearSelection() else state.selectAll(visibleOrder)
        },
        onRequestClearCompleted = { confirmingClearCompleted = true },
    )

    val inlineIslandFilter = with(density) { taskContentWidth.toDp() } >= 820.dp
    PikoScaffold(
        modifier = modifier.fillMaxSize().onPreviewKeyEvent(detailState::handleKey),
        topBar = { TaskPageHeader(state, bulkSelected.size, headerActions) },
        island = IslandPage(
            header = { TaskIslandHeader(state, bulkSelected.size, headerActions, inlineFilter = inlineIslandFilter) },
            belowHeader = {
                if (!inlineIslandFilter) TaskIslandSubHeader(state)
                TaskRateBar(state)
            },
        ),
    ) { padding ->
        val framed = LocalFramed.current
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!framed) TaskRateBar(state)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { taskContentWidth = it.width }
                    .itemPanelAnchorArea(detailState.anchors),
            ) {
                Box(Modifier.fillMaxSize().semantics { contentDescription = tr("任务列表", "Task list", "タスク一覧", "작업 목록", "任務清單") }) {
                    RefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = state::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val phase = when {
                            !state.isEmpty -> TransfersPhase.CONTENT
                            state.isLoading -> TransfersPhase.LOADING
                            else -> TransfersPhase.EMPTY
                        }
                        Crossfade(
                            targetState = phase,
                            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            modifier = Modifier.fillMaxSize(),
                            label = "transfersPhase",
                        ) { current ->
                            when (current) {
                                TransfersPhase.CONTENT -> HorizontalPager(
                                    state = pagerState,
                                    userScrollEnabled = LocalPointerSource.current.isTouchLike,
                                    key = { kinds[it] },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pageFocusTarget(listFocus)
                                        .onKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                            val primary = platform.shortcutModifier.isPressed(event)
                                            val deleteKey = event.key == Key.Delete ||
                                                (platform.shortcutModifier == ShortcutModifier.Command && primary && event.key == Key.Backspace)
                                            when {
                                                deleteKey && selectionActive -> confirmingDeleteKeys = state.selectedKeys.toSet()
                                                event.key == Key.Escape && selectionActive -> state.clearSelection()
                                                primary && event.key == Key.A -> state.selectAll(visibleOrder)
                                                else -> return@onKeyEvent false
                                            }
                                            true
                                        },
                                ) { page ->
                                    val kind = kinds[page]
                                    val sections = if (kind == state.filter) currentSections else state.sectionsOf(kind)
                                    TransfersList(
                                        state = state,
                                        kind = kind,
                                        sections = sections,
                                        completedExpanded = completedExpanded(kind, sections),
                                        onToggleCompleted = { completedExpansion[kind] = !completedExpanded(kind, sections) },
                                        sidePadding = SidePadding,
                                        bottomPadding = if (selectionActive) 88.dp else 8.dp,
                                        deletedExpanded = deletedExpanded,
                                        onToggleDeleted = { deletedExpanded = !deletedExpanded },
                                        expandedBatches = expandedBatches,
                                        renderItem = renderItem,
                                        gridState = gridStates.getValue(kind),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                TransfersPhase.LOADING -> FileListSkeleton(
                                    modifier = Modifier.padding(start = SidePadding, end = SidePadding, top = 8.dp + SectionHeaderHeight),
                                )
                                TransfersPhase.EMPTY -> PullableCentered {
                                    PikoEmptyState(
                                        title = tr("暂无任务", "No tasks", "タスクはありません", "작업이 없습니다", "暫無任務"),
                                        description = tr("已提交的传输和文件处理将显示于此", "Submitted transfers and file operations appear here", "送信した転送やファイル操作を表示します", "제출된 전송 및 파일 작업이 여기에 표시됩니다", "已提交的傳輸和檔案處理將顯示於此"),
                                        icon = Icons.Outlined.SwapVerticalCircle,
                                        actionText = tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"),
                                        onActionClick = { onCreateImport("") },
                                    )
                                }
                            }
                        }
                    }
                }
                TaskSelectionFloatingToolbar(
                    visible = selectionActive,
                    selectedCount = bulkSelected.size,
                    pauseCount = pauseCount,
                    resumeCount = resumeCount,
                    onPause = pauseSelected,
                    onResume = resumeSelected,
                    onDelete = { confirmingDeleteKeys = state.selectedKeys.toSet() },
                    applyNavBarsPadding = !framed,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                DetailHost(detailState) { _, contentModifier ->
                    TransferTaskDetailsPane(
                        item = detailsItem,
                        state = state,
                        vaultSession = vaultSession,
                        checkboxActive = checkboxActive,
                        rawActions = detailsItem?.let { buildTransferItemActions(it, actionCtx) }.orEmpty(),
                        onCloseDetails = detailState::close,
                        onEnterSelection = state::enterSelection,
                        modifier = contentModifier,
                    )
                }
            }
        }
    }

    if (confirmingClearCompleted) {
        ClearCompletedTasksDialog(
            onConfirm = {
                confirmingClearCompleted = false
                state.clearCompleted()
            },
            onDismiss = { confirmingClearCompleted = false },
        )
    }

    confirmingDeleteKeys?.let { keys ->
        DeleteSelectedTasksDialog(
            items = state.itemsForKeys(keys),
            onConfirm = {
                confirmingDeleteKeys = null
                state.removeKeys(keys)
            },
            onDismiss = { confirmingDeleteKeys = null },
        )
    }
}

@Composable
private fun PullableCentered(content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val viewportHeight = with(density) { viewportHeightPx.toDp() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height },
    ) {
        Box(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .then(if (viewportHeightPx > 0) Modifier.height(viewportHeight) else Modifier),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

private fun emptyCategoryTitle(kind: TransferKind): String = when (kind) {
    TransferKind.ALL -> tr("没有任务", "No tasks", "タスクはありません", "작업이 없습니다", "沒有任務")
    TransferKind.DOWNLOAD -> tr("没有下载任务", "No download tasks", "ダウンロードタスクはありません", "다운로드 작업이 없습니다", "沒有下載任務")
    TransferKind.UPLOAD -> tr("没有上传任务", "No upload tasks", "アップロードタスクはありません", "업로드 작업이 없습니다", "沒有上傳任務")
    TransferKind.CLOUD -> tr("没有云端任务", "No cloud tasks", "クラウドタスクはありません", "클라우드 작업이 없습니다", "沒有雲端任務")
    TransferKind.FILES -> tr("没有文件处理任务", "No file operation tasks", "ファイル操作タスクはありません", "파일 작업이 없습니다", "沒有檔案處理任務")
}

@Composable
private fun TransfersList(
    state: TransfersState,
    kind: TransferKind,
    sections: TransferSections,
    completedExpanded: Boolean,
    onToggleCompleted: () -> Unit,
    sidePadding: Dp,
    bottomPadding: Dp,
    deletedExpanded: Boolean,
    onToggleDeleted: () -> Unit,
    expandedBatches: List<String>,
    renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (sections.isEmpty) {
            PullableCentered {
                PikoEmptyState(
                    title = emptyCategoryTitle(kind),
                    icon = if (kind == TransferKind.ALL) Icons.Outlined.SwapVerticalCircle else kind.icon(false),
                )
            }
            return@Box
        }
        val expanded = expandedBatches.toSet()
        val selectable = remember(sections, completedExpanded, deletedExpanded, expanded) {
            (sections.paused + sections.needsAttention + sections.inProgress +
                (if (completedExpanded) sections.completed else emptyList()) +
                if (deletedExpanded) sections.outputDeleted else emptyList())
                .withBatchChildren(expanded)
                .mapTo(HashSet()) { it.key }
        }
        PikoItemGrid(
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = sidePadding, end = sidePadding, top = 8.dp, bottom = bottomPadding),
            gridModifier = Modifier.marqueeSelection(
                gridState = gridState,
                selectedIds = state.selectedKeys,
                boxedKey = { key -> (key as? String)?.takeIf { it in selectable } },
                onSelect = state::selectBoxed,
                onBackgroundClick = state::clearSelection,
                movable = { false },
            ),
        ) {
            transferSection(
                tr("需要处理", "Needs attention", "対応が必要", "처리 필요", "需要處理"),
                sections.needsAttention,
                expanded,
                renderItem,
                attention = true,
                onClearCloud = state::clearFailedCloud,
            )
            transferSection(tr("进行中", "In progress", "処理中", "진행 중", "進行中"), sections.inProgress, expanded, renderItem)
            transferSection(tr("已暂停", "Paused", "一時停止中", "일시 중지됨", "已暫停"), sections.paused, expanded, renderItem)
            transferSection(
                tr("最近完成", "Recently completed", "最近の完了", "최근 완료", "最近完成"),
                sections.completed,
                expanded,
                renderItem,
                sectionExpanded = completedExpanded,
                onToggle = onToggleCompleted,
            )
            deletedOutputSection(
                items = sections.outputDeleted,
                expanded = deletedExpanded,
                onToggle = onToggleDeleted,
                renderItem = renderItem,
            )
        }
    }
}

private enum class TransfersPhase { LOADING, EMPTY, CONTENT }

private val SectionHeaderHeight = 48.dp
private val SidePadding = 8.dp

private fun List<TransferItem>.withBatchChildren(expanded: Collection<String>): List<TransferItem> = flatMap { item ->
    if (item.key in expanded) listOf(item) + item.children else listOf(item)
}

private fun LazyGridScope.transferItems(
    items: List<TransferItem>,
    expanded: Set<String>,
    renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit,
) {
    if (expanded.isEmpty() || items.none { it.key in expanded && it.children.isNotEmpty() }) {
        itemsIndexed(
            items = items,
            key = { _, item -> item.key },
            contentType = { _, item -> item::class },
        ) { index, item ->
            renderItem(item, Modifier.animateItem(), index, items.size)
        }
        return
    }
    items.forEachIndexed { index, item ->
        if (item.key in expanded && item.children.isNotEmpty()) {
            fullLineItem(key = item.key, contentType = item::class) {
                renderItem(item, Modifier.animateItem(), index, items.size)
            }
            itemsIndexed(
                items = item.children,
                key = { _, child -> child.key },
                contentType = { _, child -> child::class },
            ) { childIndex, child ->
                renderItem(child, Modifier.animateItem().padding(start = 16.dp), childIndex, item.children.size)
            }
            fullLineItem(key = "${item.key}:end", contentType = "batch_spacer") { Spacer(Modifier.height(4.dp)) }
        } else {
            item(key = item.key, contentType = item::class) {
                renderItem(item, Modifier.animateItem(), index, items.size)
            }
        }
    }
}

private fun LazyGridScope.transferSection(
    title: String,
    items: List<TransferItem>,
    expanded: Set<String>,
    renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit,
    attention: Boolean = false,
    sectionExpanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    onClearCompleted: (() -> Unit)? = null,
    canClearCompleted: Boolean = false,
    onClearCloud: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val hasCloud = items.any { it is TransferItem.Cloud || it is TransferItem.Pack }
    fullLineItem(key = "header:$title", contentType = "header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem()
                .padding(start = 16.dp, end = 8.dp)
                .padding(top = 8.dp)
                .heightIn(min = SectionHeaderHeight - 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (onToggle != null) Modifier.clickable(
                            onClickLabel = if (sectionExpanded) tr("收起已完成", "Collapse completed", "完了済みを折りたたむ", "완료 항목 접기", "收合已完成") else tr("展开已完成", "Expand completed", "完了済みを展開", "완료 항목 펼치기", "展開已完成"),
                            onClick = onToggle,
                        ) else Modifier,
                    )
                    .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onToggle != null) {
                    Icon(if (sectionExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    Spacer(Modifier.width(8.dp))
                }
                Surface(
                    color = if (attention) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                    contentColor = if (attention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = tr("$title（${items.size}）", "$title (${items.size})", "$title（${items.size}）", "$title (${items.size})", "$title（${items.size}）"),
                        style = MaterialTheme.typography.titleSmall.tabular,
                        modifier = Modifier.padding(horizontal = if (attention) 10.dp else 0.dp, vertical = 6.dp),
                    )
                }
            }
            if (onClearCompleted != null) {
                TextButton(
                    onClick = onClearCompleted,
                    enabled = canClearCompleted,
                    modifier = Modifier.semantics {
                        contentDescription = tr("清空所有类别的已完成记录，保留本地下载", "Clear completed records in all categories; keep local downloads", "すべての種類の完了済み記録を消去し、ダウンロード済みファイルは保持", "모든 유형의 완료 기록 지우기, 로컬 다운로드 유지", "清空所有類別的已完成記錄，保留本機下載")
                    },
                ) { Text(stringResource(Res.string.text_clear_completed_alt)) }
            }
            if (onClearCloud != null && hasCloud) {
                TextButton(onClick = onClearCloud) { Text(stringResource(Res.string.text_clear_cloud_records)) }
            }
        }
    }
    if (sectionExpanded) transferItems(items, expanded, renderItem)
}

private fun LazyGridScope.deletedOutputSection(
    items: List<TransferItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit,
) {
    if (items.isEmpty()) return
    fullLineItem(key = "header:deleted", contentType = "header") {
        val chevronRotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "chevron",
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .animateItem()
                .padding(top = 8.dp)
                .clickable(onClickLabel = if (expanded) tr("收起", "Collapse", "折りたたむ", "접기", "收合") else tr("展开", "Expand", "展開", "펼치기", "展開"), onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.text_file_deleted),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${items.size}",
                style = MaterialTheme.typography.labelMedium.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation),
            )
        }
    }
    if (expanded) {
        itemsIndexed(
            items = items,
            key = { _, item -> item.key },
            contentType = { _, item -> item::class },
        ) { index, item ->
            renderItem(item, Modifier.animateItem(), index, items.size)
        }
    }
}

private val TaskDetailSpec = DetailPanelSpec({ tr("任务详情", "Task details", "タスクの詳細", "작업 세부 정보", "任務詳細資料") }, { tr("关闭任务详情", "Close task details", "タスクの詳細を閉じる", "작업 세부 정보 닫기", "關閉任務詳細資料") }, 320.dp, 560.dp)
private val TaskDetailKey = DetailPanelKey<String>(TaskDetailSpec)
