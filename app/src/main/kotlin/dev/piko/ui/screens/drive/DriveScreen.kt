package dev.piko.ui.screens.drive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.PikoApplication
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.state.DriveScreenState
import dev.piko.ui.components.BreadcrumbBar
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.MoveTargetDialog
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.SegmentDownloadSheet
import dev.piko.ui.screens.instant.InstantSheetContent
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 网盘主界面：目录导航、列表与网格两种视图、防窥遮蔽、秒传入口与批量操作。
 *
 * 布局上把常驻的界面元素压到最少：顶栏之下只有子目录里才出现的面包屑，
 * 排序、视图切换、折叠提示都作为列表的首几项随内容滚走，搜索框只在点开搜索后
 * 取代顶栏标题。原先列表之上常驻顶栏、面包屑、搜索框与折叠横幅四层，首屏约
 * 230dp 被它们占去。
 *
 * Documentation references:
 * - m3-material-mirror/pages/components/app-bars.md（顶栏只放一到两个动作）
 * - m3-material-mirror/pages/components/search.md（搜索为次要动作时用图标按钮入口）
 * - m3-material-mirror/pages/components/bottom-sheets.md（移动端以模态面板代替菜单）
 * - android-docs-mirror/pages/develop/ui/compose/lists.md（key 与 contentType）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    currentFolderId: String = "",
    currentFolderName: String = "网盘",
    onNavigateToFolder: (folderId: String, folderName: String) -> Unit,
    onNavigateToVideoPlayer: (fileId: String, fileName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val driveRepo = PikoApplication.instance.driveRepository
    val instantRepo = PikoApplication.instance.instantMagnetRepository
    val downloadManager = PikoApplication.instance.downloadManager
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 防窥遮蔽开关是渲染选择，不进共享状态
    val sessionManager = PikoApplication.instance.sessionManager
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)

    val state = remember { DriveScreenState(driveRepo, sessionManager, scope) }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    // 视图模式存进偏好，切 Tab 与重启后保持上次的选择
    val isGridMode by sessionManager.gridViewFlow.collectAsStateWithLifecycle(initialValue = false)

    // 目录导航栈：持久化并与全局单例共享，切 Tab / 重启不丢失
    val folderStack by state.folderStack.collectAsStateWithLifecycle()
    val activeFolder = folderStack.lastOrNull() ?: PathBreadcrumb(currentFolderId, currentFolderName)
    val activeFolderId = activeFolder.id

    LaunchedEffect(Unit) {
        val restoredStack = if (folderStack.size == 1 && folderStack[0].id.isEmpty() && currentFolderId.isEmpty()) {
            val (lastId, lastName, serialized) = sessionManager.getLastFolder()
            when {
                lastId.isEmpty() -> emptyList()
                serialized.isEmpty() -> listOf(PathBreadcrumb(lastId, lastName))
                else -> serialized.split(";").mapNotNull { entry ->
                    val parts = entry.split("::")
                    if (parts.size == 2) PathBreadcrumb(parts[0], parts[1]) else null
                }.ifEmpty { listOf(PathBreadcrumb(lastId, lastName)) }
            }
        } else emptyList()

        // restoreFolderStack 自带加载，两条路各触发一次，不能都调。
        if (restoredStack.isNotEmpty()) state.restoreFolderStack(restoredStack) else state.load()
    }

    LaunchedEffect(folderStack) {
        val last = folderStack.lastOrNull()
        if (last != null) {
            val serialized = folderStack.joinToString(";") { "${it.id}::${it.name}" }
            sessionManager.saveLastFolder(last.id, last.name, serialized)
        }
    }

    // 搜索框是否展开。状态类在换目录时会清空搜索词，展开态跟着收起，
    // 否则从搜索结果点进文件夹后，顶栏还停在一个空的搜索框上。
    var isSearchOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(activeFolderId) { isSearchOpen = false }
    fun closeSearch() {
        state.updateSearchQuery("")
        isSearchOpen = false
    }

    // 返回键的优先级：多选 > 搜索 > 上一级目录。后声明的 BackHandler 先收到事件。
    BackHandler(enabled = folderStack.size > 1) { state.navigateUp() }
    BackHandler(enabled = isSearchOpen) { closeSearch() }
    BackHandler(enabled = state.isSelectionMode) { state.exitSelection() }

    // 对话框与面板状态
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTargetFile by remember { mutableStateOf<FileStat?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var segmentTargetFile by remember { mutableStateOf<FileStat?>(null) }
    var actionTargetFile by remember { mutableStateOf<FileStat?>(null) }
    // 待移动的条目。选择器只负责选目录，移动本身与刷新在这里做，
    // 所以单项操作和多选工具栏可以共用同一套状态。
    var moveTargetIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var previewImage by remember { mutableStateOf<FileStat?>(null) }
    var showInstantSheet by remember { mutableStateOf(false) }

    val pendingMagnet by instantRepo.pendingMagnetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMagnet) {
        if (!pendingMagnet.isNullOrBlank()) showInstantSheet = true
    }

    val gridState = rememberLazyGridState()
    val isFabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }

    val displayedFiles = state.displayedFiles
    val highlightedFileIds = state.highlightedFileIds
    // 每一项都要问一次「是否选中」，SnapshotStateList 的 contains 是线性查找
    val selectedIdSet by remember { derivedStateOf { state.selectedFileIds.toSet() } }

    // 渐隐单独一个 effect：并进滚动定位那个的话，视图模式到位会把 8 秒重新计一遍
    LaunchedEffect(highlightedFileIds) {
        if (highlightedFileIds.isEmpty()) return@LaunchedEffect
        delay(8000)
        state.clearHighlight()
    }

    fun enqueueDownload(file: FileStat) {
        downloadManager.enqueue(file)
        scope.launch { snackbarHostState.showSnackbar("已加入本地下载：${file.name}") }
    }

    // 回调对象只建一次，列表项拿到的引用不变；外部传入的导航回调经 rememberUpdatedState 取最新值
    val navigateToPlayer by rememberUpdatedState(onNavigateToVideoPlayer)
    val callbacks = remember(state) {
        DriveItemCallbacks(
            onOpen = { file ->
                when {
                    file.isFolder -> state.openFolder(file.id, file.name)
                    file.isPlayableVideo() -> navigateToPlayer(file.id, file.name)
                    file.isPreviewableImage() && file.thumbnailLink.isNotBlank() -> previewImage = file
                    // 其余类型没有应用内的打开方式，单击等同于下载
                    else -> enqueueDownload(file)
                }
            },
            onMore = { actionTargetFile = it },
            onLongPress = { state.enterSelection(it.id) },
            onSelect = { file, selected -> state.setSelected(file.id, selected) },
            onToggleSpoiler = { state.toggleSpoiler(it.id) },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            when {
                state.isSelectionMode -> DriveSelectionTopBar(
                    selectedCount = state.selectedFileIds.size,
                    onExit = { state.exitSelection() },
                    onSelectAll = { state.toggleSelectAll() },
                    onMove = { moveTargetIds = state.selectedFileIds.toSet() },
                    onTrash = { state.moveToTrash(state.selectedFileIds.toList()) },
                )

                isSearchOpen -> DriveSearchTopBar(
                    query = state.searchQuery,
                    onQueryChange = { state.updateSearchQuery(it) },
                    onClose = { closeSearch() },
                    isGlobalSearching = state.isGlobalSearching,
                    isGlobalSearchActive = state.isGlobalSearchActive,
                    onStartGlobalSearch = { state.startGlobalSearch() },
                    onCancelGlobalSearch = { state.cancelGlobalSearch() },
                )

                else -> PikoTopBar(
                    title = activeFolder.name,
                    navigationIcon = if (folderStack.size > 1) {
                        {
                            IconButton(onClick = { state.navigateUp() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回上一级")
                            }
                        }
                    } else null,
                    actions = {
                        IconButton(onClick = { isSearchOpen = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = {
                            newFolderName = ""
                            showNewFolderDialog = true
                        }) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { showInstantSheet = true },
                    icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                    text = { Text("秒传磁力") },
                    expanded = isFabExpanded,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .consumeWindowInsets(innerPadding),
        ) {
            if (folderStack.size > 1) {
                BreadcrumbBar(
                    breadcrumbs = folderStack.drop(1),
                    // BreadcrumbBar 回调传的已经是完整路径栈的下标（首页按钮传 0），
                    // 再加一是把目标算深了一级，点上级目录会停在它的子目录里。
                    onBreadcrumbClick = { index -> state.navigateToBreadcrumb(index) },
                )
            }

            Crossfade(
                targetState = state.isLoading,
                animationSpec = PikoMotion.StateCrossfadeSpec,
                label = "drive_loading",
            ) { loading ->
                if (loading) {
                    FullScreenLoading()
                    return@Crossfade
                }
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { state.load(refresh = true) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 加载失败时列表留在上一次的内容上。Snackbar 弹完就没了，
                        // 这条横幅常驻到重新加载成功，否则用户无从知道眼前是旧数据。
                        state.loadError?.let { reason ->
                            StaleDataBanner(reason = reason, onRetry = { state.load(refresh = true) })
                        }

                        val bottomPadding = innerPadding.calculateBottomPadding() + FabClearance
                        if (displayedFiles.isEmpty()) {
                            DriveEmptyState(state = state, modifier = Modifier.weight(1f))
                        } else {
                            DriveFileGrid(
                                files = displayedFiles,
                                isGridMode = isGridMode,
                                gridState = gridState,
                                isSelectionMode = state.isSelectionMode,
                                selectedIds = selectedIdSet,
                                highlightedIds = highlightedFileIds,
                                isBlurred = { isSpoilerBlurEnabled && it.id !in state.revealedFileIds },
                                hitLocations = state.hitLocations,
                                callbacks = callbacks,
                                bottomPadding = bottomPadding,
                                header = {
                                    DriveListHeader(
                                        summary = listSummary(state, displayedFiles),
                                        sortOrder = state.sortOrder,
                                        onSortChange = { state.changeSortOrder(it) },
                                        isGridMode = isGridMode,
                                        onToggleGridMode = {
                                            scope.launch { sessionManager.setGridViewEnabled(!isGridMode) }
                                        },
                                    )
                                },
                                foldBanner = foldBannerOrNull(state),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }

    actionTargetFile?.let { target ->
        FileActionsSheet(
            file = target,
            locationLabel = state.hitLocations[target.id],
            onDismiss = { actionTargetFile = null },
            onDownload = { enqueueDownload(target) },
            onDownloadSegment = { segmentTargetFile = target },
            onRename = {
                renameTargetFile = target
                renameNewName = target.name
            },
            onMove = { moveTargetIds = setOf(target.id) },
            onTrash = { state.moveToTrash(listOf(target.id), target.name) },
        )
    }

    // 秒传面板（从 FAB 或外部磁力链唤起）
    if (showInstantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showInstantSheet = false
                instantRepo.clearPendingMagnet()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            InstantSheetContent(
                initialMagnet = pendingMagnet.orEmpty(),
                onDismiss = {
                    showInstantSheet = false
                    instantRepo.clearPendingMagnet()
                },
                onSuccess = { createdIds, targetBread ->
                    showInstantSheet = false
                    instantRepo.clearPendingMagnet()
                    state.navigateToFolder(targetBread)
                    state.highlight(createdIds.toSet())
                    scope.launch {
                        snackbarHostState.showSnackbar("已秒传 ${createdIds.size} 项到 ${targetBread.name}")
                    }
                },
                onOfflineTaskCreated = { targetBread ->
                    showInstantSheet = false
                    instantRepo.clearPendingMagnet()
                    state.navigateToFolder(targetBread)
                    scope.launch {
                        snackbarHostState.showSnackbar("已加入云端离线任务，完成后存入 ${targetBread.name}")
                    }
                },
            )
        }
    }

    if (showNewFolderDialog) {
        NameInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            value = newFolderName,
            onValueChange = { newFolderName = it },
            confirmLabel = "创建",
            confirmEnabled = newFolderName.isNotBlank(),
            onDismiss = { showNewFolderDialog = false },
            onConfirm = {
                showNewFolderDialog = false
                state.createFolder(newFolderName)
            },
        )
    }

    renameTargetFile?.let { target ->
        NameInputDialog(
            title = "重命名",
            label = "新名称",
            value = renameNewName,
            onValueChange = { renameNewName = it },
            confirmLabel = "确定",
            confirmEnabled = renameNewName.isNotBlank() && renameNewName != target.name,
            onDismiss = { renameTargetFile = null },
            onConfirm = {
                val id = target.id
                val newName = renameNewName
                renameTargetFile = null
                state.rename(id, newName)
            },
        )
    }

    if (moveTargetIds.isNotEmpty()) {
        val pendingIds = moveTargetIds
        MoveTargetDialog(
            itemCount = pendingIds.size,
            movingIds = pendingIds,
            sourceParentId = activeFolderId,
            onDismiss = { moveTargetIds = emptySet() },
            onConfirm = { targetId, targetName ->
                moveTargetIds = emptySet()
                state.move(pendingIds.toList(), targetId, targetName)
            },
        )
    }

    segmentTargetFile?.let { target ->
        SegmentDownloadSheet(
            file = target,
            onDismiss = { segmentTargetFile = null },
            onConfirmDownload = { startByte, lengthBytes, timeLabel, startMs, endMs, streamUrl ->
                downloadManager.enqueueSegment(
                    file = target,
                    startMs = startMs,
                    endMs = endMs,
                    timeRangeLabel = timeLabel,
                    streamUrl = streamUrl,
                    startByte = startByte,
                    lengthBytes = lengthBytes,
                )
                segmentTargetFile = null
                scope.launch {
                    snackbarHostState.showSnackbar("已加入段落下载：${target.name} [$timeLabel]")
                }
            },
        )
    }

    previewImage?.let { image ->
        // 查看器里左右翻页的范围是当前列表里能看的图，搜索与折叠筛过之后的那一份，
        // 和用户眼前看到的顺序一致。
        val previewables = remember(displayedFiles) {
            displayedFiles.filter { it.isPreviewableImage() && it.thumbnailLink.isNotBlank() }
        }
        val startIndex = previewables.indexOfFirst { it.id == image.id }
        if (startIndex < 0) {
            // 图片刚被移走或筛没了，没有可停的页
            previewImage = null
        } else {
            ImageViewer(
                images = previewables,
                initialIndex = startIndex,
                onDismiss = { previewImage = null },
            )
        }
    }
}

// 列表末尾为 Extended FAB 留出的空间：56dp 高度加 16dp 外边距，再留一段让最后一项
// 能完整滚出 FAB 的遮挡。
private val FabClearance = 88.dp

private fun listSummary(state: DriveScreenState, files: List<FileStat>): String {
    if (state.isGlobalSearchActive) {
        return if (state.isGlobalSearching) "全盘搜索中，已找到 ${files.size} 项" else "全盘找到 ${files.size} 项"
    }
    if (state.searchQuery.isNotBlank()) return "当前文件夹找到 ${files.size} 项"
    val folderCount = files.count(FileStat::isFolder)
    val fileCount = files.size - folderCount
    return when {
        folderCount == 0 -> "$fileCount 个文件"
        fileCount == 0 -> "$folderCount 个文件夹"
        else -> "$folderCount 个文件夹 · $fileCount 个文件"
    }
}

private fun foldBannerOrNull(state: DriveScreenState): (@Composable () -> Unit)? {
    val hiddenCount = state.potentialHiddenCount
    val applicable = state.isHeuristicFilterEnabled && hiddenCount > 0 &&
        state.searchQuery.isBlank() && !state.isGlobalSearchActive
    if (!applicable) return null
    val isFolded = !state.showAllFilesTemporarily
    return {
        FoldBanner(
            isFolded = isFolded,
            hiddenCount = hiddenCount,
            onToggle = { state.setShowAllFiles(isFolded) },
        )
    }
}

/** 数据可能过期的常驻提示。只在加载失败后出现，不随列表滚走。 */
@Composable
private fun StaleDataBanner(reason: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "内容可能不是最新的：$reason",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/**
 * 空目录与无搜索结果。外层包一层可滚动容器：PullToRefreshBox 依赖子项的嵌套滚动，
 * 不可滚动的空态下拉不会触发刷新，空目录就没法手动刷新。
 */
@Composable
private fun DriveEmptyState(state: DriveScreenState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = viewportHeight),
                verticalArrangement = Arrangement.Center,
            ) {
                if (state.searchQuery.isNotBlank()) {
                    PikoEmptyState(
                        title = if (state.isGlobalSearching) "正在遍历网盘" else "无匹配结果",
                        description = when {
                            state.isGlobalSearching -> "全盘搜索逐层遍历目录，结果会陆续出现"
                            state.isGlobalSearchActive -> "全盘没有名称包含「${state.searchQuery}」的文件"
                            else -> "当前文件夹没有名称包含「${state.searchQuery}」的文件，可点顶栏右侧「全盘」搜索整个网盘"
                        },
                        icon = Icons.Outlined.SearchOff,
                    )
                } else {
                    PikoEmptyState(
                        title = "此文件夹为空",
                        description = "可用右下角「秒传磁力」保存资源，或新建文件夹",
                        icon = Icons.Outlined.FolderOpen,
                    )
                }
            }
        }
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.largeIncreased,
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
