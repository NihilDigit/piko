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
import dev.piko.ui.components.PikoItemGrid
import dev.piko.ui.components.marqueeSelection
import dev.piko.ui.components.fullLineItem
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SyncAlt
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.piko.ui.components.PikoScaffold
import dev.piko.ui.components.islandTopStart
import dev.piko.ui.theme.FrameCardShape
import dev.piko.ui.theme.IslandCorner
import dev.piko.ui.theme.IslandInnerCorner
import dev.piko.ui.theme.LocalFramed
import dev.piko.ui.theme.islandHeader
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.snapshotFlow
import dev.piko.shared.state.TransferSections
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.RefreshBox
import dev.piko.ui.components.showsRefreshButton
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
import dev.piko.ui.adaptive.isDesktopLayout
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
    val desktop = isDesktopLayout()
    val state = remember(account, desktop) {
        TransfersState(
            services.downloadManager,
            services.taskRepository,
            services.offlinePacks,
            scope,
            services.driveRepository,
            services.uploadManager,
            services.instantSaveRecords,
            account,
            services.archiveExtractSession,
            services.folderVaultSession,
            listsServerWork = !desktop,
        )
    }
    val vaultSession = services.folderVaultSession
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
    // 展开了的文件夹下载，按组的 key。默认收起：一组可能有上千个文件
    val expandedBatches = rememberSaveable(saver = listSaver({ it.toList() }, { it.toMutableStateList() })) {
        mutableStateListOf<String>()
    }
    fun toggleBatch(key: String) {
        if (!expandedBatches.remove(key)) expandedBatches.add(key)
    }

    // 片段的 fileId 是源视频的，播放器找不到本地文件时会按它退回云端，放出来的是整段原片。
    // 片段只该播本地文件，不给 fileId，打不开就报错
    val playLocal = { task: DownloadTask ->
        onNavigateToVideoPlayer(if (task.isSegment) "" else task.fileId, task.fileName,
            task.destinationPath.takeIf { task.status == dev.piko.download.DownloadStatus.COMPLETED })
    }
    val resubmitAction = { task: DriveTask -> task.sourceUrl?.let { { state.resubmitCloud(task) } } }

    // 记 key 而不是条目本身：进度每半秒刷新，面板要跟着显示最新状态；条目被移除时面板随之关闭
    var detailsKey by rememberSaveable { mutableStateOf<String?>(null) }

    val localFiles = platform.localFiles

    fun batchIntents(item: TransferItem.LocalBatch): LocalBatchIntents {
        val id = item.batch.id
        return LocalBatchIntents(
            onToggleExpand = { toggleBatch(item.key) },
            onPause = { state.pauseBatch(id) },
            onResume = { state.resumeBatch(id) },
            onRetryListing = { state.retryListing(id) },
            onConfirm = { state.confirmListing(id) },
            onOpenFolder = { state.withBatchFolder(item.batch) { localFiles.openExternally(it, isMedia = false) } },
            onRevealFolder = { state.withBatchFolder(item.batch) { localFiles.openContainingFolder(it) } },
            onRemove = { state.removeBatch(id) },
        )
    }

    // 一项的全部操作，详情面板与右键菜单共用：两处给的总是同一组
    fun actionsFor(item: TransferItem): List<SheetAction> = when (item) {
        is TransferItem.LocalBatch -> localBatchActions(item, item.key in expandedBatches, batchIntents(item))
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
        is TransferItem.Extract -> extractTransferActions(
            item = item,
            onShowInDrive = { openCloudFileById(item.job.file.id, item.job.file.name) },
            onSkip = { state.skipExtract(item.job.id) },
        )
        is TransferItem.Vault -> vaultTransferActions(state.isVaultStopping, state::stopVault)
    }

    // 点按一项做的事，轻点与鼠标双击共用
    fun primaryActionFor(item: TransferItem): (() -> Unit)? = when (item) {
        is TransferItem.LocalBatch -> localBatchPrimaryAction(item, batchIntents(item)) { detailsKey = item.key }
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
        is TransferItem.Extract -> { { openCloudFileById(item.job.file.id, item.job.file.name) } }
        // 归档的文件夹只记着名字，没有可跳的位置；点按看详情
        is TransferItem.Vault -> { { detailsKey = item.key } }
    }

    // 眼前列出的先后，连选与全选按它。收起的「文件已删除」组不在其中，展开的文件夹下载连同其中的文件
    val visibleOrder = (state.inProgress + state.needsAttention + state.completed +
        if (deletedExpanded) state.outputDeleted else emptyList()).withBatchChildren(expandedBatches).map { it.key }
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
            state.enterSelection(item.key)
        },
    )

    // 一行一项的宽表格已去掉：宽窗口也用这一种行，排进多栏网格，与最近添加、星标、回收站这些页一样。
    // 表格的一行横跨整个卡片，名字与数字之间隔着上千 dp；多栏时每一行都不超过一栏宽
    @Composable
    fun TransferRow(item: TransferItem) {
        val selection = rowSelection(item)
        when (item) {
            is TransferItem.LocalBatch -> LocalBatchRow(
                item = item,
                expanded = item.key in expandedBatches,
                intents = batchIntents(item),
                onMoreClick = { detailsKey = item.key },
                selection = selection,
            )
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
            is TransferItem.Extract -> ExtractTransferRow(
                item = item,
                onShowInDrive = { openCloudFileById(item.job.file.id, item.job.file.name) },
                onMoreClick = { detailsKey = item.key },
                selection = selection,
            )
            is TransferItem.Vault -> VaultTransferRow(
                item = item,
                session = vaultSession,
                onMoreClick = { detailsKey = item.key },
                selection = selection,
            )
        }
    }

    // 页头是顶栏还是一行、按钮带不带字、底栏排什么，按窗口宽度定；行本身不分宽窄
    val widthClass = currentWidthClass()
    val wide = widthClass == WidthClass.Expanded
    // medium 按大号手机，与 compact 同一套
    val compact = !wide

    // 右键弹出与详情面板相同的操作。animateItem 这类条目修饰挂在外层，菜单锚点才跟着条目走
    val selectedTint = MaterialTheme.colorScheme.secondaryContainer
    val renderItem: @Composable (TransferItem, Modifier) -> Unit = { item, itemModifier ->
        // 鼠标单选时不进多选、不画复选框，盖一层底色标出选中的是哪一项
        val singleSelected = !state.checkboxMode && item.key in state.selectedKeys
        ContextMenuArea(
            actions = { actionsFor(item) },
            onSelect = if (state.checkboxMode) null else ({ state.enterSelection(item.key) }),
            modifier = itemModifier,
        ) {
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
                        trailingPassThrough = if (item is TransferItem.LocalBatch) BatchRowTrailingWidth else NarrowRowTrailingWidth,
                    ),
            ) {
                TransferRow(item)
            }
        }
    }

    // 每一类一页、各有滚动位置：横划时相邻那一页跟着手指进来，切回来还停在原处
    val kinds = TransferKind.entries
    val gridStates = remember { kinds.associateWith { LazyGridState() } }
    val gridState = gridStates.getValue(state.filter)
    // 列表离开顶端，页头据此换色；derivedStateOf 让滚动中每帧的偏移变化只在跨过顶端时才触发重组
    val scrolled by remember(gridState) {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0 }
    }
    // 只响应进页之后的变化：计数器由主界面持有，切回本页时它已是旧值，不该再滚一次
    val initialScrollRequests = remember { scrollToTopRequests }
    LaunchedEffect(scrollToTopRequests) {
        if (scrollToTopRequests != initialScrollRequests) gridState.animateScrollToItem(0)
    }

    // 页与筛选双向同步：划停在哪一页就筛哪一类，点页头的筛选则滑到那一页。筛选取 settledPage 而不是
    // currentPage：划到一半就换筛选会清掉选中项，手指退回原页时选中已经没了
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

    // 触屏上退出多选靠返回键；键盘的 Esc 在下面的 onKeyEvent 里先接住
    BackHandler(enabled = selectionActive) { state.clearSelection() }

    val selected = state.selectedItems
    val pauseSelected = selected.filter(state::isPausable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::pause) } }
    val resumeSelected = selected.filter(state::isResumable).takeIf { it.isNotEmpty() }?.let { items -> { items.forEach(state::resume) } }

    val transfersBody: @Composable (PaddingValues) -> Unit = { innerPadding ->
        // 下拉刷新包住三种样子：空状态与骨架也要能拉，刚装好、一项传输也没有时正想看看云端有没有
        RefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = state::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
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
                        // 横划切换类别，照 M3 tabs 的内容区横划：相邻一类跟着手指进来。只认手指：鼠标在列表上
                        // 按住拖动是框选（marqueeSelection），分页不能抢
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
                                        deleteKey && selectionActive -> confirmingDelete = true
                                        event.key == Key.Escape && selectionActive -> state.clearSelection()
                                        primary && event.key == Key.A -> state.selectAll(visibleOrder)
                                        else -> return@onKeyEvent false
                                    }
                                    true
                                },
                        ) { page ->
                            val kind = kinds[page]
                            val sections by remember(state, kind) { derivedStateOf { state.sectionsOf(kind) } }
                            TransfersList(
                                state = state,
                                kind = kind,
                                sections = sections,
                                sidePadding = sidePadding,
                                deletedExpanded = deletedExpanded,
                                onToggleDeleted = { deletedExpanded = !deletedExpanded },
                                expandedBatches = expandedBatches,
                                renderItem = renderItem,
                                gridState = gridStates.getValue(kind),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // 骨架与真实列表对齐：同样的两侧留白，头一格让出分段标题那一行
                        TransfersPhase.LOADING -> FileListSkeleton(
                            modifier = Modifier.padding(start = sidePadding, end = sidePadding, top = 8.dp + SectionHeaderHeight),
                        )
                        TransfersPhase.EMPTY -> PullableCentered {
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

    // 有外框时与网盘页同一套：顶上一排类别标签，下面一块岛，岛的上半段是速度、容量与操作，下面是列表。
    // 原来筛选与操作在外框色上的一行、速度与容量在窗口底部另一行，列表是夹在中间的一张卡片，与网盘页的岛对不上
    val island = LocalFramed.current
    val showTabs = !state.isEmpty
    PikoScaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = { if (!island) TransfersFooter(state = state, sidePadding = SidePadding, compact = compact, wide = wide) },
        topBar = {
            if (island) {
                TransfersTabRow(state, showTabs = showTabs)
            } else {
                // 只有 compact 带标题「传输」，更宽时标题与侧边的导航项逐字重复
                TransfersHeader(
                    state = state,
                    selectedCount = selected.size,
                    showFilter = showTabs,
                    onPauseSelected = pauseSelected,
                    onResumeSelected = resumeSelected,
                    onDeleteSelected = { confirmingDelete = true },
                    onRefresh = if (showsRefreshButton()) state::refresh else null,
                    scrolled = scrolled,
                )
            }
        },
        cardShape = if (island) {
            RoundedCornerShape(
                topStart = islandTopStart(firstActive = showTabs && state.filter == TransferKind.entries.first()),
                topEnd = IslandCorner,
                bottomStart = IslandCorner,
                bottomEnd = IslandCorner,
            )
        } else {
            FrameCardShape
        },
    ) { innerPadding ->
        if (!island) {
            transfersBody(innerPadding)
            return@PikoScaffold
        }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.islandHeader)) {
            TransfersActionBar(
                state = state,
                selectedCount = selected.size,
                wide = wide,
                onPauseSelected = pauseSelected,
                onResumeSelected = resumeSelected,
                onDeleteSelected = { confirmingDelete = true },
                onRefresh = if (showsRefreshButton()) state::refresh else null,
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 与网盘页相同：上沿是小圆角、露出页眉的底色，下沿就是岛的下沿
                    .clip(RoundedCornerShape(topStart = IslandInnerCorner, topEnd = IslandInnerCorner, bottomStart = IslandCorner, bottomEnd = IslandCorner))
                    .background(MaterialTheme.colorScheme.surface),
            ) { transfersBody(innerPadding) }
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

    // 文件夹下载里的文件不在各段里，要到各组里找
    val detailsItem = detailsKey?.let { key ->
        sequenceOf(state.inProgress, state.needsAttention, state.completed, state.outputDeleted)
            .flatten()
            .flatMap { item -> sequenceOf(item) + ((item as? TransferItem.LocalBatch)?.tasks.orEmpty().map { TransferItem.Local(it) }) }
            .firstOrNull { it.key == key }
    }
    val closeDetails = { detailsKey = null }
    when (detailsItem) {
        null -> Unit
        is TransferItem.LocalBatch -> LocalBatchSheet(detailsItem, actionsFor(detailsItem), closeDetails)
        is TransferItem.Local -> LocalTransferSheet(detailsItem.task, actionsFor(detailsItem), closeDetails)
        is TransferItem.Upload -> UploadTransferSheet(detailsItem.task, actionsFor(detailsItem), closeDetails)
        is TransferItem.Cloud -> CloudTransferSheet(detailsItem.task, actionsFor(detailsItem), closeDetails)
        is TransferItem.Pack -> PackTransferSheet(detailsItem, actionsFor(detailsItem), closeDetails)
        is TransferItem.Instant -> InstantTransferSheet(detailsItem.record, actionsFor(detailsItem), closeDetails)
        is TransferItem.Extract -> ExtractTransferSheet(detailsItem, actionsFor(detailsItem), closeDetails)
        is TransferItem.Vault -> VaultTransferSheet(detailsItem, vaultSession, actionsFor(detailsItem), closeDetails)
    }
}

/**
 * 居中放一段说明，整块可以竖着拉动。下拉刷新只认嵌套滚动传上来的量，不能滚的内容拉不动；
 * 在 verticalScroll 里高度不设上限，按外面量到的高度撑满才居中得了。
 */
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
    sidePadding: Dp,
    deletedExpanded: Boolean,
    onToggleDeleted: () -> Unit,
    expandedBatches: List<String>,
    renderItem: @Composable (TransferItem, Modifier) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (sections.isEmpty) {
            // 筛到一项不剩时说清是这一类没有，而不是整页空白像没加载出来
            PullableCentered {
                Text(
                    text = "没有${kind.label}任务",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Box
        }
        // 能框选的只有条目，分组标题的 key 带 header: 前缀，不算
        val expanded = expandedBatches.toSet()
        val selectable = remember(sections, deletedExpanded, expanded) {
            (sections.inProgress + sections.needsAttention + sections.completed +
                if (deletedExpanded) sections.outputDeleted else emptyList())
                .withBatchChildren(expanded)
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
            transferSection("进行中", sections.inProgress, expanded, renderItem)
            transferSection("需要处理", sections.needsAttention, expanded, renderItem, onClearCloud = state::clearFailedCloud)
            transferSection("已完成", sections.completed, expanded, renderItem)
            deletedOutputSection(
                items = sections.outputDeleted,
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
    val deletesFiles = items.any {
        (it is TransferItem.Local && it.task.status == DownloadStatus.COMPLETED) || (it is TransferItem.LocalBatch && it.completedCount > 0)
    }
    val cancelsUploads = items.any { it is TransferItem.Upload && it.task.status != UploadStatus.COMPLETED }
    val notes = listOfNotNull(
        "已下载的文件会从本机删除。".takeIf { deletesFiles },
        "未传完的上传会取消，网盘里上传到一半的文件随之删除。".takeIf { cancelsUploads },
        "云端任务只删记录，已保存到网盘的文件不受影响。".takeIf { items.any { TransferKind.CLOUD.matches(it) } },
    )
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除所选的 ${items.size} 项？") },
        text = if (notes.isEmpty()) null else ({ Text(notes.joinToString("\n")) }),
        confirmButton = { PikoDialogConfirm("删除", onClick = onConfirm, destructive = true) },
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

/** 展开的文件夹下载后面接上其中的各文件，其余照旧。 */
private fun List<TransferItem>.withBatchChildren(expanded: Collection<String>): List<TransferItem> = flatMap { item ->
    if (item is TransferItem.LocalBatch && item.key in expanded) listOf(item) + item.tasks.map { TransferItem.Local(it) } else listOf(item)
}

/**
 * 一段的条目。展开的文件夹下载占满一行作组头，其中的文件接在下面，末尾再用一个占满一行的空项断开：
 * 网格是多栏的，不断开的话段里下一项会挤进最后一个文件旁边的空格，看着像是组里的。
 */
private fun LazyGridScope.transferItems(
    items: List<TransferItem>,
    expanded: Set<String>,
    renderItem: @Composable (TransferItem, Modifier) -> Unit,
) {
    var start = 0
    fun flushUntil(end: Int) {
        if (end > start) items(items.subList(start, end), key = { it.key }) { item -> renderItem(item, Modifier.animateItem()) }
    }
    items.forEachIndexed { index, item ->
        if (item !is TransferItem.LocalBatch || item.key !in expanded) return@forEachIndexed
        flushUntil(index)
        start = index + 1
        fullLineItem(key = item.key) { renderItem(item, Modifier.animateItem()) }
        items(item.tasks, key = { "local:${it.taskId}" }) { task -> renderItem(TransferItem.Local(task), Modifier.animateItem()) }
        fullLineItem(key = "end:${item.key}") {}
    }
    flushUntil(items.size)
}

private fun LazyGridScope.transferSection(
    title: String,
    items: List<TransferItem>,
    expanded: Set<String>,
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
    transferItems(items, expanded, renderItem)
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
