package dev.piko.ui.screens.transfers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.SyncAlt
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
import dev.piko.shared.i18n.enCount
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

    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)
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
    val taskLayout = TaskRowLayout.forWidth(with(density) { taskContentWidth.toDp() })
    val taskPresenter = remember(state, vaultSession) { TaskPresenter(state, vaultSession) }

    val actionCtx = TransferActionContext(
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
        onOpenCloudFileById = openCloudFileById,
        onCopySourceUrl = ::copySourceUrl,
        onPlayLocal = playLocal,
        onPlayCloud = playCloud,
        onCreateImport = onCreateImport,
        onShowDetails = ::showDetails,
        onConfirmSingleSideEffectDelete = { key ->
            detailState.close()
            if (key !in state.selectedKeys) state.selectOnly(key)
            confirmingDeleteKeys = setOf(key)
        },
    )

    val selectionActive = state.selectedKeys.isNotEmpty()
    val checkboxActive = state.checkboxMode
    val liveSections by remember(state) { derivedStateOf { state.sectionsOf(state.filter) } }
    val frozenOrder = remember(state.filter, selectionActive) { if (selectionActive) TaskListOrder(liveSections) else null }
    val currentSections = frozenOrder?.apply(liveSections) ?: liveSections
    val completedExpansion = remember { mutableStateMapOf<TransferKind, Boolean>() }
    fun completedExpanded(kind: TransferKind, sections: TransferSections) = completedExpansion[kind]
        ?: (sections.needsAttention.isEmpty() && sections.inProgress.isEmpty())
    val visibleOrder by remember(state, currentSections, deletedExpanded, expandedBatches) {
        derivedStateOf {
            (currentSections.paused + currentSections.needsAttention + currentSections.inProgress +
                (if (completedExpanded(state.filter, currentSections)) currentSections.completed else emptyList()) +
                if (deletedExpanded) currentSections.outputDeleted else emptyList())
                .withBatchChildren(expandedBatches)
                .map { it.key }
        }
    }

    val listFocus = remember { FocusRequester() }
    fun focusList() = runCatching { listFocus.requestFocus() }

    val bulkSelected = state.selectedItems
    val pauseSelected = bulkSelected.filter(state::isPausable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::pause) } }
    val resumeSelected = bulkSelected.filter(state::isResumable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::resume) } }

    val rowSections by remember(state) { derivedStateOf { state.sectionsOf(TransferKind.ALL) } }
    val completedKeys by remember(rowSections) { derivedStateOf { (rowSections.completed + rowSections.outputDeleted).mapTo(HashSet()) { it.key } } }
    val attentionKeys by remember(rowSections) { derivedStateOf { rowSections.needsAttention.mapTo(HashSet()) { it.key } } }

    val renderItem: @Composable (TransferItem, Modifier, Int, Int) -> Unit = { item, itemModifier, index, count ->
        val primary = buildTransferPrimaryAction(item, actionCtx)
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
            taskLayout = taskLayout,
            state = state,
            taskPresenter = taskPresenter,
            localFiles = platform.localFiles,
            quickAction = buildTransferQuickAction(item, actionCtx),
            extraAction = buildTransferExtraAction(item, actionCtx),
            playAction = buildTransferPlayAction(item, actionCtx),
            contextActions = {
                buildTransferContextActions(
                    item = item,
                    ctx = actionCtx,
                    bulkSelected = bulkSelected,
                    visibleOrder = visibleOrder,
                    pauseSelected = pauseSelected,
                    resumeSelected = resumeSelected,
                    onConfirmBulkDelete = { confirmingDeleteKeys = state.selectedKeys.toSet() },
                )
            },
            onPlaced = { detailState.anchors.placed(item.key, it) },
            onSelect = {
                if (checkboxActive) state.toggleSelected(item.key) else state.selectOnly(item.key)
                focusList()
            },
            onToggleSelect = {
                state.toggleSelected(item.key)
                focusList()
            },
            onExtendSelect = {
                state.selectRange(item.key, visibleOrder)
                focusList()
            },
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showDetails(item.key)
            },
            onOpen = { primary?.invoke() ?: showDetails(item.key) },
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

    val detailsItem = detailsKey?.let { key ->
        sequenceOf(state.paused, state.inProgress, state.needsAttention, state.completed, state.outputDeleted)
            .flatten()
            .flatMap { item -> sequenceOf(item) + item.children }
            .firstOrNull { it.key == key }
    }
    LaunchedEffect(detailsKey, detailsItem) {
        if (detailsKey != null && detailsItem == null) detailState.close()
    }

    val headerActions = taskPageActions(
        state = state,
        selectedCount = bulkSelected.size,
        onSelectAll = { state.selectAll(visibleOrder) },
        onDelete = { confirmingDeleteKeys = state.selectedKeys.toSet() },
        onPause = pauseSelected,
        onResume = resumeSelected,
        pauseCount = bulkSelected.count(state::isPausable),
        resumeCount = bulkSelected.count(state::isResumable),
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!LocalFramed.current) TaskRateBar(state)
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
                                        icon = Icons.Outlined.SyncAlt,
                                        actionText = tr("添加链接", "Add link", "リンクを追加", "링크 추가", "新增連結"),
                                        onActionClick = { onCreateImport("") },
                                    )
                                }
                            }
                        }
                    }
                }
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .height(maxHeight),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun TransfersList(
    state: TransfersState,
    kind: TransferKind,
    sections: TransferSections,
    completedExpanded: Boolean,
    onToggleCompleted: () -> Unit,
    sidePadding: Dp,
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
                Text(
                    text = tr("没有${kind.label}任务", "No ${enCount(kind.label, "task", "tasks")}", "${kind.label}タスクはありません", "${kind.label} 작업이 없습니다", "沒有${kind.label}任務"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = 8.dp),
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
    items.forEachIndexed { index, item ->
        if (item.key in expanded && item.children.isNotEmpty()) {
            fullLineItem(key = item.key) { renderItem(item, Modifier.animateItem(), index, items.size) }
            itemsIndexed(item.children, key = { _, child -> child.key }) { childIndex, child ->
                renderItem(child, Modifier.animateItem().padding(start = 16.dp), childIndex, item.children.size)
            }
            fullLineItem(key = "${item.key}:end") { Spacer(Modifier.height(4.dp)) }
        } else {
            item(key = item.key) { renderItem(item, Modifier.animateItem(), index, items.size) }
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
                        style = MaterialTheme.typography.titleSmall,
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
                style = MaterialTheme.typography.labelMedium,
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
        items(items, key = { it.key }) { item ->
            renderItem(item, Modifier.animateItem(), items.indexOf(item), items.size)
        }
    }
}

private val TaskDetailSpec = DetailPanelSpec({ tr("任务详情", "Task details", "タスクの詳細", "작업 세부 정보", "任務詳細資料") }, { tr("关闭任务详情", "Close task details", "タスクの詳細を閉じる", "작업 세부 정보 닫기", "關閉任務詳細資料") }, 320.dp, 560.dp)
private val TaskDetailKey = DetailPanelKey<String>(TaskDetailSpec)
