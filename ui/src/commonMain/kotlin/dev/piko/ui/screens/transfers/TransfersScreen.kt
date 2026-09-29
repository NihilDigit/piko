package dev.piko.ui.screens.transfers

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import dev.piko.ui.components.PikoItemGrid
import dev.piko.ui.components.marqueeSelection
import dev.piko.ui.components.fullLineItem
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.piko.ui.components.PikoScaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.sourceUrl
import dev.piko.shared.state.TransferItem
import dev.piko.shared.state.TransferKind
import dev.piko.shared.state.TransfersState
import dev.piko.shared.upload.UploadStatus
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.adaptive.readableSidePadding
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.pageFocusTarget
import dev.piko.ui.components.FileListSkeleton
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.SheetAction
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.ShortcutModifier
import io.github.nihildigit.pikpak.DriveTask
import kotlinx.coroutines.launch

/**
 * 传输页：本地下载、上传与云端离线任务合为一个列表，按「进行中」「需要处理」「已完成」分段，可按类型筛选。
 * 分段、筛选与选中在 [TransfersState]，这里只负责渲染与输入。
 *
 * 宽窗口（expanded）一行一项、各列对齐，照 FDM；更窄时是原来的两行列表项。点选照网盘页：鼠标单击选中，
 * 主修饰键加选，Shift 连选，双击执行主操作；触屏轻点执行、长按进多选。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TransfersScreen(
    onNavigateToVideoPlayer: (fileId: String, fileName: String, localPath: String?) -> Unit,
    onNavigateToInstant: () -> Unit = {},
    /** 跳到网盘里该文件所在目录。找不到（已移动或删除）时返回 false，由本页提示。 */
    onOpenCloudFile: suspend (fileId: String, fileName: String) -> Boolean = { _, _ -> false },
    /** 再点一次底栏的「传输」时加一：回到列表顶部。 */
    scrollToTopRequests: Int = 0,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val client by services.clientManager.currentClient.collectAsStateWithLifecycle()
    val account = client?.account.orEmpty()
    val state = remember(account) {
        TransfersState(
            services.downloadManager,
            services.taskRepository,
            services.offlinePacks,
            scope,
            services.driveRepository,
            services.uploadManager,
            services.instantSaveRecords,
            account,
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }
    // 找不到文件的提示走本页的 Snackbar，不用系统 Toast：Toast 不跟随 M3 主题与配色
    val openCloudFileById = { fileId: String, fileName: String ->
        scope.launch {
            if (!onOpenCloudFile(fileId, fileName)) {
                snackbarHostState.showSnackbar("文件已不存在", withDismissAction = true)
            }
        }
        Unit
    }
    val openCloudFile = { task: DriveTask -> openCloudFileById(task.fileId, task.fileName) }
    // 下载与网盘同受防窥开关约束。逐项揭示只在本次查看内有效，与网盘页的做法一致
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val revealedKeys = rememberSaveable(saver = listSaver({ it.toList() }, { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    fun hasPreview(task: DownloadTask) = task.thumbnailLink.isNotEmpty() || task.destinationPath.isNotEmpty()
    fun blurred(item: TransferItem) = isSpoilerBlurEnabled && item.key !in revealedKeys

    // 只在本页可见期间轮询。挂在 STARTED 上：应用退到后台时 LaunchedEffect
    // 并不会取消，只靠它的话后台每 4 秒照样发一次请求
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.whileVisible()
        }
    }
    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    // 只记本次查看：这一组是低价值的历史，默认收起
    var deletedExpanded by rememberSaveable { mutableStateOf(false) }

    // 片段的 fileId 是源视频的，播放器找不到本地文件时会按它退回云端，放出来的是整段原片。
    // 片段只该播本地文件，不给 fileId，打不开就报错
    val playLocal = { task: DownloadTask ->
        onNavigateToVideoPlayer(if (task.isSegment) "" else task.fileId, task.fileName, task.destinationPath)
    }
    val resubmitAction = { task: DriveTask -> task.sourceUrl?.let { { state.resubmitCloud(task) } } }

    // 记 key 而不是条目本身：进度每半秒刷新，面板要跟着显示最新状态；条目被移除时面板随之关闭
    var detailsKey by rememberSaveable { mutableStateOf<String?>(null) }

    val localFiles = platform.localFiles

    // 一项的全部操作，详情面板与右键菜单共用：两处给的总是同一组
    fun actionsFor(item: TransferItem): List<SheetAction> = when (item) {
        is TransferItem.Local -> localTransferActions(
            task = item.task,
            files = localFiles,
            onPlay = { playLocal(item.task) },
            onStart = { state.resumeLocal(item.task.taskId) },
            onPause = { state.pauseLocal(item.task.taskId) },
            onRemove = { state.removeLocal(item.task.taskId) },
            previewHidden = if (isSpoilerBlurEnabled && hasPreview(item.task)) item.key !in revealedKeys else null,
            onTogglePreview = { if (!revealedKeys.remove(item.key)) revealedKeys.add(item.key) },
        )
        is TransferItem.Upload -> uploadTransferActions(
            task = item.task,
            onOpen = { item.task.fileId?.let { openCloudFileById(it, item.task.fileName) } },
            onResume = { state.resumeUpload(item.task.taskId) },
            onPause = { state.pauseUpload(item.task.taskId) },
            onRemove = { state.removeUpload(item.task.taskId) },
        )
        is TransferItem.Cloud -> cloudTransferActions(
            task = item.task,
            onResubmit = resubmitAction(item.task),
            onDelete = { state.deleteCloud(item.task.id) },
            onOpen = { openCloudFile(item.task) },
        )
        is TransferItem.Pack -> packTransferActions(
            item = item,
            onOpen = { openCloudFileById(item.job.outputId, item.job.folderName) },
            onRetry = { state.retryPack(item.job.taskId) },
            onDiscard = { state.discardPack(item.job.taskId) },
        )
        is TransferItem.Instant -> instantTransferActions(
            onOpen = { openCloudFileById(item.record.locateId, item.record.name) },
            onRemove = { state.removeInstant(item.record.id) },
        )
    }

    // 点按一项做的事，轻点与鼠标双击共用
    fun primaryActionFor(item: TransferItem): (() -> Unit)? = when (item) {
        is TransferItem.Local -> localPrimaryAction(
            task = item.task,
            files = localFiles,
            onPlay = { playLocal(item.task) },
            onStart = { state.resumeLocal(item.task.taskId) },
            onPause = { state.pauseLocal(item.task.taskId) },
        )
        is TransferItem.Upload -> uploadPrimaryAction(
            task = item.task,
            onOpen = { item.task.fileId?.let { openCloudFileById(it, item.task.fileName) } },
            onResume = { state.resumeUpload(item.task.taskId) },
            onPause = { state.pauseUpload(item.task.taskId) },
        )
        is TransferItem.Cloud -> cloudPrimaryAction(item.task, resubmitAction(item.task)) { openCloudFile(item.task) }
        is TransferItem.Pack -> packPrimaryAction(
            item = item,
            onOpen = { openCloudFileById(item.job.outputId, item.job.folderName) },
            onRetry = { state.retryPack(item.job.taskId) },
        )
        is TransferItem.Instant -> { { openCloudFileById(item.record.locateId, item.record.name) } }
    }

    // 眼前列出的先后，连选与全选按它。收起的「文件已删除」组不在其中
    val visibleOrder = (state.inProgress + state.needsAttention + state.completed +
        if (deletedExpanded) state.outputDeleted else emptyList()).map { it.key }
    val selectionActive = state.selectedKeys.isNotEmpty()
    var confirmingDelete by remember { mutableStateOf(false) }

    // 列表的键盘焦点：鼠标按进列表时取得（pageFocusTarget），Delete、Esc 与全选才有处可去
    val listFocus = remember { FocusRequester() }
    fun focusList() = runCatching { listFocus.requestFocus() }

    val haptic = LocalHapticFeedback.current
    fun rowSelection(item: TransferItem) = RowSelection(
        active = state.checkboxMode,
        selected = item.key in state.selectedKeys,
        onToggle = { state.toggleSelected(item.key) },
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            state.toggleSelected(item.key)
        },
    )

    // 一行一项的宽表格已去掉：宽窗口也用这一种行，排进多栏网格，与最近添加、星标、回收站这些页一样。
    // 表格的一行横跨整个卡片，名字与数字之间隔着上千 dp；多栏时每一行都不超过一栏宽
    @Composable
    fun TransferRow(item: TransferItem) {
        val selection = rowSelection(item)
        when (item) {
            is TransferItem.Local -> LocalTransferRow(
                task = item.task,
                onPlay = { playLocal(item.task) },
                onStart = { state.resumeLocal(item.task.taskId) },
                onPause = { state.pauseLocal(item.task.taskId) },
                onMoreClick = { detailsKey = item.key },
                isSpoilerBlurred = blurred(item),
                selection = selection,
            )
            is TransferItem.Upload -> UploadTransferRow(
                task = item.task,
                onOpen = { item.task.fileId?.let { openCloudFileById(it, item.task.fileName) } },
                onResume = { state.resumeUpload(item.task.taskId) },
                onPause = { state.pauseUpload(item.task.taskId) },
                onMoreClick = { detailsKey = item.key },
                selection = selection,
            )
            is TransferItem.Cloud -> CloudTransferRow(
                task = item.task,
                thumbnail = state.thumbnailOf(item.task.fileId),
                isSpoilerBlurred = blurred(item),
                onResubmit = resubmitAction(item.task),
                onOpen = { openCloudFile(item.task) },
                onMoreClick = { detailsKey = item.key },
                selection = selection,
            )
            is TransferItem.Pack -> PackTransferRow(
                item = item,
                thumbnail = state.thumbnailOf(item.job.outputId),
                isSpoilerBlurred = blurred(item),
                onOpen = { openCloudFileById(item.job.outputId, item.job.folderName) },
                onRetry = { state.retryPack(item.job.taskId) },
                onMoreClick = { detailsKey = item.key },
                selection = selection,
            )
            is TransferItem.Instant -> InstantTransferRow(
                record = item.record,
                onOpen = { openCloudFileById(item.record.locateId, item.record.name) },
                onMoreClick = { detailsKey = item.key },
                selection = selection,
            )
        }
    }

    // 页头是顶栏还是一行、按钮带不带字、底栏排什么，按窗口宽度定；行本身不分宽窄
    val widthClass = currentWidthClass()
    val compact = widthClass == WidthClass.Compact
    val wide = widthClass == WidthClass.Expanded

    // 右键弹出与详情面板相同的操作。animateItem 这类条目修饰挂在外层，菜单锚点才跟着条目走
    val selectedTint = MaterialTheme.colorScheme.secondaryContainer
    val renderItem: @Composable (TransferItem, Modifier) -> Unit = { item, itemModifier ->
        // 鼠标单选时不进多选、不画复选框，盖一层底色标出选中的是哪一项
        val singleSelected = !state.checkboxMode && item.key in state.selectedKeys
        ContextMenuArea(actions = { actionsFor(item) }, modifier = itemModifier) {
            Box(
                Modifier
                    .then(if (singleSelected) Modifier.selectedOverlay(selectedTint) else Modifier)
                    .transferClicks(
                        // 多选态下单击照网盘页是勾选；平时只选中这一项
                        onSelect = {
                            if (state.checkboxMode) state.toggleSelected(item.key) else state.selectOnly(item.key)
                            focusList()
                        },
                        onToggle = {
                            state.toggleSelected(item.key)
                            focusList()
                        },
                        onExtend = {
                            state.selectRange(item.key, visibleOrder)
                            focusList()
                        },
                        onOpen = { primaryActionFor(item)?.invoke() },
                        trailingPassThrough = NarrowRowTrailingWidth,
                    ),
            ) {
                TransferRow(item)
            }
        }
    }

    val gridState = rememberLazyGridState()
    // 列表离开顶端，页头据此换色；derivedStateOf 让滚动中每帧的偏移变化只在跨过顶端时才触发重组
    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 } }
    // 只响应进页之后的变化：计数器由主界面持有，切回本页时它已是旧值，不该再滚一次
    val initialScrollRequests = remember { scrollToTopRequests }
    LaunchedEffect(scrollToTopRequests) {
        if (scrollToTopRequests != initialScrollRequests) gridState.animateScrollToItem(0)
    }

    // 触屏上退出多选靠返回键；键盘的 Esc 在下面的 onKeyEvent 里先接住
    BackHandler(enabled = selectionActive) { state.clearSelection() }

    val selected = state.selectedItems
    val pauseSelected = selected.filter(state::isPausable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::pause) } }
    val resumeSelected = selected.filter(state::isResumable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::resume) } }

    // 页头是筛选与批量操作（TransfersHeader），放在顶栏的位置上，有外框时与别的页一样落在外框色上，
    // 下面的卡片里才是列表。只有 compact 带标题「传输」，更宽时标题与侧边的导航项逐字重复
    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { TransfersFooter(state = state, sidePadding = SidePadding, compact = compact, wide = wide) },
        topBar = {
            TransfersHeader(
                state = state,
                selectedCount = selected.size,
                compact = compact,
                wide = wide,
                sidePadding = SidePadding,
                showFilter = !state.isEmpty,
                onPauseSelected = pauseSelected,
                onResumeSelected = resumeSelected,
                onDeleteSelected = { confirmingDelete = true },
                scrolled = scrolled,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .swipeBetweenKinds(current = { state.filter }, onChange = state::changeFilter),
        ) {
            val sidePadding = SidePadding
            Column(Modifier.fillMaxSize()) {
                val phase = when {
                    !state.isEmpty -> TransfersPhase.CONTENT
                    // 云端列表首次取回之前不下结论，免得空状态一闪而过
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
                        TransfersPhase.CONTENT -> TransfersList(
                            state = state,
                            sidePadding = sidePadding,
                            deletedExpanded = deletedExpanded,
                            onToggleDeleted = { deletedExpanded = !deletedExpanded },
                            renderItem = renderItem,
                            gridState = gridState,
                            modifier = Modifier
                                .fillMaxSize()
                                .pageFocusTarget(listFocus)
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    val primary = platform.shortcutModifier.isPressed(event)
                                    val deleteKey = event.key == Key.Delete ||
                                        (platform.shortcutModifier == ShortcutModifier.Command && primary && event.key == Key.Backspace)
                                    when {
                                        deleteKey && selectionActive -> confirmingDelete = true
                                        event.key == Key.Escape && selectionActive -> state.clearSelection()
                                        primary && event.key == Key.A -> state.selectAll(visibleOrder)
                                        else -> return@onKeyEvent false
                                    }
                                    true
                                },
                        )
                        // 骨架与真实列表对齐：同样的两侧留白，头一格让出分段标题那一行
                        TransfersPhase.LOADING -> FileListSkeleton(
                            modifier = Modifier.padding(start = sidePadding, end = sidePadding, top = 8.dp + SectionHeaderHeight),
                        )
                        TransfersPhase.EMPTY -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            PikoEmptyState(
                                title = "暂无传输任务",
                                description = "下载、上传、离线与秒传将显示于此",
                                icon = Icons.Outlined.SyncAlt,
                                actionText = "新建离线任务",
                                onActionClick = onNavigateToInstant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        DeleteSelectedDialog(
            items = selected,
            onConfirm = {
                confirmingDelete = false
                state.removeSelected()
            },
            onDismiss = { confirmingDelete = false },
        )
    }

    val detailsItem = detailsKey?.let { key ->
        sequenceOf(state.inProgress, state.needsAttention, state.completed, state.outputDeleted)
            .flatten()
            .firstOrNull { it.key == key }
    }
    val closeDetails = { detailsKey = null }
    when (detailsItem) {
        null -> Unit
        is TransferItem.Local -> LocalTransferSheet(detailsItem.task, actionsFor(detailsItem), closeDetails)
        is TransferItem.Upload -> UploadTransferSheet(detailsItem.task, actionsFor(detailsItem), closeDetails)
        is TransferItem.Cloud -> CloudTransferSheet(detailsItem.task, actionsFor(detailsItem), closeDetails)
        is TransferItem.Pack -> PackTransferSheet(detailsItem, actionsFor(detailsItem), closeDetails)
        is TransferItem.Instant -> InstantTransferSheet(detailsItem.record, actionsFor(detailsItem), closeDetails)
    }
}

@Composable
private fun TransfersList(
    state: TransfersState,
    sidePadding: Dp,
    deletedExpanded: Boolean,
    onToggleDeleted: () -> Unit,
    renderItem: @Composable (TransferItem, Modifier) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    val filteredEmpty = state.inProgress.isEmpty() && state.needsAttention.isEmpty() &&
        state.completed.isEmpty() && state.outputDeleted.isEmpty()
    Box(modifier) {
        if (filteredEmpty) {
            // 筛到一项不剩时说清是这一类没有，而不是整页空白像没加载出来
            Text(
                text = "没有${state.filter.label}任务",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        // 能框选的只有条目，分组标题的 key 带 header: 前缀，不算
        val selectable = remember(state.inProgress, state.needsAttention, state.completed, state.outputDeleted, deletedExpanded) {
            (state.inProgress + state.needsAttention + state.completed + if (deletedExpanded) state.outputDeleted else emptyList())
                .mapTo(HashSet()) { it.key }
        }
        PikoItemGrid(
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = 8.dp),
            // 照网盘页的框选：在空白或条目上按住拖动拉框。传输没有拖放，按在条目上拖也是框选
            gridModifier = Modifier.marqueeSelection(
                gridState = gridState,
                selectedIds = state.selectedKeys,
                boxedKey = { key -> (key as? String)?.takeIf { it in selectable } },
                onSelect = state::selectBoxed,
                onBackgroundClick = state::clearSelection,
                movable = { false },
            ),
        ) {
            transferSection("进行中", state.inProgress, renderItem)
            transferSection("需要处理", state.needsAttention, renderItem, onClearCloud = state::clearFailedCloud)
            transferSection("已完成", state.completed, renderItem)
            deletedOutputSection(
                items = state.outputDeleted,
                expanded = deletedExpanded,
                onToggle = onToggleDeleted,
                renderItem = renderItem,
            )
        }
    }
}

/**
 * 删除所选之前确认一次。批量删除一次动的不止一项，本地下载还会连文件从本机删掉，
 * 这一步又没有撤销；逐项删除仍在各自的菜单里，不经这一问。
 */
@Composable
private fun DeleteSelectedDialog(items: List<TransferItem>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val deletesFiles = items.any { it is TransferItem.Local && it.task.status == DownloadStatus.COMPLETED }
    val cancelsUploads = items.any { it is TransferItem.Upload && it.task.status != UploadStatus.COMPLETED }
    val notes = listOfNotNull(
        "已下载的文件会从本机删除。".takeIf { deletesFiles },
        "未传完的上传会取消，网盘里上传到一半的文件随之删除。".takeIf { cancelsUploads },
        "云端任务只删记录，已保存到网盘的文件不受影响。".takeIf { items.any { TransferKind.CLOUD.matches(it) } },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除所选的 ${items.size} 项？") },
        text = if (notes.isEmpty()) null else ({ Text(notes.joinToString("\n")) }),
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 选中的窄行上盖一层半透明底色。盖在内容之上而不是垫在下面：列表项自己铺着不透明的容器色，垫在下面看不见。
 * 左右各缩 4dp，与列表项按压时变圆的容器对齐。
 */
private fun Modifier.selectedOverlay(color: Color): Modifier = drawWithContent {
    drawContent()
    val inset = 4.dp.toPx()
    drawRoundRect(
        color = color.copy(alpha = 0.6f),
        topLeft = Offset(inset, 0f),
        size = Size(size.width - inset * 2, size.height),
        cornerRadius = CornerRadius(12.dp.toPx()),
    )
}

private enum class TransfersPhase { LOADING, EMPTY, CONTENT }

/** 分段标题一行的高度：上边距 8 加最小高度 40。骨架要让出同样的位置，内容换上来时才不跳。 */
private val SectionHeaderHeight = 48.dp

/**
 * 页头、列表与底栏两侧的留白，三处取同一个值，页头的按钮与列表的列对齐。列表是多栏网格（PikoItemGrid），
 * 与网盘页的列表一样铺满卡片，只留一点边距；不再收在居中的阅读宽度里。
 */
private val SidePadding = 8.dp

private fun LazyGridScope.transferSection(
    title: String,
    items: List<TransferItem>,
    renderItem: @Composable (TransferItem, Modifier) -> Unit,
    /** 清除这一段的云端任务记录。只在段内有云端任务时给出，本地下载不受影响。 */
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (onClearCloud != null && hasCloud) {
                TextButton(onClick = onClearCloud) { Text("清除云端记录") }
            }
        }
    }
    items(items, key = { it.key }) { item ->
        renderItem(item, Modifier.animateItem())
    }
}

/**
 * 「文件已删除」组，排在最后，标题弱化且可收起。
 *
 * 展开与收起靠条目进出列表，由各项的 animateItem 做淡入淡出与位移。没有把条目包进
 * AnimatedVisibility：那样收起后条目仍留在列表里，只是高度为零。
 */
private fun LazyGridScope.deletedOutputSection(
    items: List<TransferItem>,
    expanded: Boolean,
    onToggle: () -> Unit,
    renderItem: @Composable (TransferItem, Modifier) -> Unit,
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
                .clickable(onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "文件已删除",
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
            renderItem(item, Modifier.animateItem())
        }
    }
}

/**
 * 手指在列表上左右横划，在页头的四个类别之间切换：左划下一个，右划上一个，到头就停，照 M3 tabs 的「内容区横划切换」。
 *
 * 只认触屏：鼠标在列表上按住拖动是框选（marqueeSelection），不能抢。先过横向的触摸阈值才算横划，
 * 竖向先过阈值的交给列表自己滚动（它在 Main 阶段比这里先收到事件，竖向拖动会被它消费掉）。
 * 横划满 [SwipeThreshold] 才换，短的一下只当作手抖，免得竖着滑时稍一带歪就换了类别。
 */
private fun Modifier.swipeBetweenKinds(current: () -> TransferKind, onChange: (TransferKind) -> Unit): Modifier =
    pointerInput(Unit) {
        val threshold = SwipeThreshold.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Touch) return@awaitEachGesture
            var total = 0f
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume()
                total += over
            } ?: return@awaitEachGesture
            horizontalDrag(drag.id) { change ->
                total += change.positionChange().x
                change.consume()
            }
            if (abs(total) < threshold) return@awaitEachGesture
            val kinds = TransferKind.entries
            val next = kinds.indexOf(current()) + if (total < 0) 1 else -1
            kinds.getOrNull(next)?.let(onChange)
        }
    }

private val SwipeThreshold = 72.dp
