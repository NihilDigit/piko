package dev.piko.ui.screens.drive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.data.repository.FileSortOrder
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.ui.components.BreadcrumbBar
import dev.piko.ui.components.FileItemRow
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.MoveTargetDialog
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.SegmentDownloadSheet
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.toReadableSize
import dev.piko.shared.state.DriveScreenState
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main cloud drive file manager screen.
 *
 * Provides breadcrumb folder navigation, list/grid dual view layouts,
 * heuristic spoiler blur, instant magnet creation, and batch file actions.
 *
 * Documentation References:
 * - Android Compose State: android-docs-mirror/pages/develop/ui/compose/state.md
 *   "Consuming flows safely in Jetpack Compose with collectAsStateWithLifecycle"
 * - Android Compose Lists: android-docs-mirror/pages/develop/ui/compose/lists.md
 *   "Control item position and layout stability with keys and item animations"
 * - Material 3 Components: m3-material-mirror/pages/components.md
 *   (Scaffold, PullToRefreshBox, ExtendedFloatingActionButton, ModalBottomSheet, AlertDialog)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DriveScreen(
    currentFolderId: String = "",
    currentFolderName: String = "网盘",
    onNavigateToFolder: (folderId: String, folderName: String) -> Unit,
    onNavigateToVideoPlayer: (fileId: String, fileName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val driveRepo = PikoApplication.instance.driveRepository
    val taskRepo = PikoApplication.instance.taskRepository
    val instantRepo = PikoApplication.instance.instantMagnetRepository
    val downloadManager = PikoApplication.instance.downloadManager
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 防窥/Spoiler 模糊遮蔽 (可在设置中切换)。是渲染选择，不进共享状态。
    val sessionManager = PikoApplication.instance.sessionManager
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)

    val state = remember { DriveScreenState(driveRepo, sessionManager, scope) }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    var showSortMenu by remember { mutableStateOf(false) }

    // 视图切换：列表模式 vs 网格模式。存进偏好，切 Tab 与重启后保持上次的选择。
    val isGridShadowMode by sessionManager.gridViewFlow.collectAsStateWithLifecycle(initialValue = false)

    // 目录导航栈：持久化与全局单例共享，记住当前打开的位置，切 Tab / 重启不丢失
    val folderStack by state.folderStack.collectAsStateWithLifecycle()
    val activeFolder = folderStack.lastOrNull() ?: PathBreadcrumb(currentFolderId, currentFolderName)
    val activeFolderId = activeFolder.id
    val activeFolderName = activeFolder.name

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

    BackHandler(enabled = folderStack.size > 1) {
        state.navigateUp()
    }

    // 对话框与 Sheet 状态
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTargetFile by remember { mutableStateOf<FileStat?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var segmentTargetFile by remember { mutableStateOf<FileStat?>(null) }
    // 待移动的条目。选择器只负责选目录，移动本身与刷新在这里做，
    // 所以单项菜单和多选工具栏可以共用同一套状态。
    var moveTargetIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var previewImage by remember { mutableStateOf<FileStat?>(null) }

    // 秒传与离线任务 BottomSheet
    var showInstantSheet by remember { mutableStateOf(false) }

    val pendingMagnet by instantRepo.pendingMagnetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMagnet) {
        if (!pendingMagnet.isNullOrBlank()) {
            showInstantSheet = true
        }
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val isFabExpanded by remember {
        derivedStateOf {
            if (isGridShadowMode) {
                gridState.firstVisibleItemIndex == 0
            } else {
                listState.firstVisibleItemIndex == 0
            }
        }
    }

    val displayedFiles = state.displayedFiles
    val hitLocations = state.hitLocations
    val highlightedFileIds = state.highlightedFileIds

    // 刚秒传成功时，自动平滑滚动定位至新添加的项目。视图模式是异步读出来的偏好，
    // 首帧拿到的还是默认值，所以它也要进 key——否则真值到达前的高亮会滚错那一个列表
    LaunchedEffect(displayedFiles, highlightedFileIds, isGridShadowMode) {
        if (highlightedFileIds.isEmpty()) return@LaunchedEffect
        val idx = displayedFiles.indexOfFirst { it.id in highlightedFileIds }
        if (idx < 0) return@LaunchedEffect
        if (isGridShadowMode) {
            gridState.animateScrollToItem(idx)
        } else {
            listState.animateScrollToItem(idx)
        }
    }

    // 渐隐单独一个 effect：并进上面那个的话，视图模式到位会把 8 秒重新计一遍
    LaunchedEffect(highlightedFileIds) {
        if (highlightedFileIds.isEmpty()) return@LaunchedEffect
        delay(8000)
        state.clearHighlight()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            PikoTopBar(
                title = if (state.isSelectionMode) "已选择 ${state.selectedFileIds.size} 项" else activeFolderName,
                navigationIcon = if (state.isSelectionMode) {
                    {
                        IconButton(onClick = { state.exitSelection() }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Exit selection")
                        }
                    }
                } else if (folderStack.size > 1) {
                    {
                        IconButton(onClick = { state.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回上一级")
                        }
                    }
                } else null,
                actions = {
                    if (state.isSelectionMode) {
                        IconButton(onClick = { state.toggleSelectAll() }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(
                            onClick = { moveTargetIds = state.selectedFileIds.toSet() },
                            enabled = state.selectedFileIds.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.DriveFileMove, contentDescription = "移动所选")
                        }
                        IconButton(
                            onClick = { state.moveToTrash(state.selectedFileIds.toList()) },
                            enabled = state.selectedFileIds.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        // 新建文件夹
                        IconButton(onClick = {
                            newFolderName = ""
                            showNewFolderDialog = true
                        }) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹")
                        }

                        // 切换网格视图 / 列表视图
                        IconButton(onClick = { scope.launch { sessionManager.setGridViewEnabled(!isGridShadowMode) } }) {
                            Icon(
                                imageVector = if (isGridShadowMode) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                                contentDescription = "Toggle Grid/List",
                            )
                        }

                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("按修改时间 (最新)") },
                                onClick = { state.changeSortOrder(FileSortOrder.TIME_DESC); showSortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("按文件名称 (A-Z)") },
                                onClick = { state.changeSortOrder(FileSortOrder.NAME_ASC); showSortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("按文件大小 (从大到小)") },
                                onClick = { state.changeSortOrder(FileSortOrder.SIZE_DESC); showSortMenu = false },
                            )
                        }

                        // 多选统一由长按进入，顶栏不再单列入口
                    }
                },
            )
        },
        floatingActionButton = {
            if (!state.isSelectionMode) {
                // 规范的标准 M3 ExtendedFloatingActionButton，滚动时自适应收起/展开，添加下边距防误触
                ExtendedFloatingActionButton(
                    onClick = { showInstantSheet = true },
                    icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                    text = { Text("秒传磁力") },
                    expanded = isFabExpanded,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.padding(bottom = 8.dp),
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
                    onBreadcrumbClick = { index ->
                        // BreadcrumbBar 回调传的已经是完整路径栈的下标（首页按钮传 0），
                        // 再加一是把目标算深了一级，点上级目录会停在它的子目录里。
                        state.navigateToBreadcrumb(index)
                    },
                )
            }

            // 搜索栏 (支持当前目录 0ms 即时过滤与全盘云端检索)
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { state.updateSearchQuery(it) },
                placeholder = {
                    Text(
                        if (state.isGlobalSearchActive) "全盘搜索: ${state.searchQuery}"
                        else "搜索当前目录 (${state.files.size} 项)..."
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.isGlobalSearching) {
                            PikoLoadingIndicator(size = 18.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                // 只取消，已找到的结果留在列表里
                                onClick = { state.cancelGlobalSearch() },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("停止", style = MaterialTheme.typography.labelMedium)
                            }
                        } else if (state.searchQuery.isNotBlank() && !state.isGlobalSearchActive) {
                            TextButton(
                                onClick = { state.startGlobalSearch() },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("全盘搜", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (state.searchQuery.isNotEmpty() || state.isGlobalSearchActive) {
                            IconButton(onClick = { state.updateSearchQuery("") }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清除")
                            }
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Crossfade(
                targetState = state.isLoading,
                animationSpec = PikoMotion.StateCrossfadeSpec,
                label = "drive_loading",
            ) { loading ->
                if (loading) {
                    FullScreenLoading()
                } else {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { state.load(refresh = true) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 加载失败时列表留在上一次的内容上。Snackbar 弹完就没了，
                            // 这条横幅常驻到重新加载成功，否则用户无从知道眼前是旧数据。
                            state.loadError?.let { reason ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.ErrorOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "内容可能不是最新的：$reason",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        TextButton(
                                            onClick = { state.load(refresh = true) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        ) {
                                            Text("重试", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }

                            // 启发式量级折叠提示胶囊/横幅
                            val potentialHiddenCount = state.potentialHiddenCount
                            if (state.isHeuristicFilterEnabled && state.searchQuery.isBlank() && !state.isGlobalSearchActive) {
                                if (!state.showAllFilesTemporarily && potentialHiddenCount > 0) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.AutoAwesome,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "已智能折叠 $potentialHiddenCount 个次要文件及文件夹",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                            }
                                            TextButton(
                                                onClick = { state.setShowAllFiles(true) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            ) {
                                                Text("显示全部", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                } else if (state.showAllFilesTemporarily && potentialHiddenCount > 0) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 4.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Folder,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "正在显示全部文件 (含 $potentialHiddenCount 个次要文件及文件夹)",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            TextButton(
                                                onClick = { state.setShowAllFiles(false) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            ) {
                                                Text("恢复折叠", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (displayedFiles.isEmpty()) {
                                    if (state.searchQuery.isNotBlank()) {
                                        PikoEmptyState(
                                            title = if (state.isGlobalSearching) "正在遍历网盘" else "未找到相关文件",
                                            description = when {
                                                state.isGlobalSearching -> "全盘搜索需要逐层遍历目录，结果会边搜边出现"
                                                state.isGlobalSearchActive -> "全盘未找到包含「${state.searchQuery}」的文件"
                                                else -> "当前文件夹未找到「${state.searchQuery}」，可点击搜索框右侧「全盘搜」进行全局查找"
                                            },
                                        )
                                    } else {
                                        PikoEmptyState(
                                            title = "此文件夹为空",
                                            description = "点击右下角「秒传磁力」秒速保存资源，或新建文件夹",
                                        )
                                    }
                                } else if (isGridShadowMode) {
                            // 网格视图
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Adaptive(minSize = 130.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    top = 16.dp,
                                    end = 16.dp,
                                    bottom = innerPadding.calculateBottomPadding() + 96.dp,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(
                                    items = displayedFiles,
                                    key = { it.id },
                                    contentType = { if (it.isFolder) "folder" else "file" },
                                ) { file ->
                                    val isBlurred = isSpoilerBlurEnabled && !state.revealedFileIds.contains(file.id)
                                    ShadowFileCard(
                                        file = file,
                                        isSelectionMode = state.isSelectionMode,
                                        isSelected = state.selectedFileIds.contains(file.id),
                                        isSpoilerBlurred = isBlurred,
                                        isHighlighted = highlightedFileIds.contains(file.id),
                                        onToggleSpoiler = { state.toggleSpoiler(file.id) },
                                        onClick = {
                                            if (state.isSelectionMode) {
                                                state.setSelected(file.id, file.id !in state.selectedFileIds)
                                            } else if (file.isFolder) {
                                                state.openFolder(file.id, file.name)
                                            } else if (file.isPlayableVideo()) {
                                                onNavigateToVideoPlayer(file.id, file.name)
                                            } else if (file.isPreviewableImage() && file.thumbnailLink.isNotBlank()) {
                                                previewImage = file
                                            }
                                        },
                                        onDownload = {
                                            downloadManager.enqueue(file)
                                            scope.launch {
                                                snackbarHostState.showSnackbar("已加入本地下载: ${file.name}")
                                            }
                                        },
                                        onDownloadSegment = {
                                            segmentTargetFile = file
                                        },
                                        onRename = {
                                            renameTargetFile = file
                                            renameNewName = file.name
                                        },
                                        onMove = { moveTargetIds = setOf(file.id) },
                                        onDelete = { state.moveToTrash(listOf(file.id), file.name) },
                                        onLongClick = { state.enterSelection(file.id) },
                                        onSelectToggle = { selected -> state.setSelected(file.id, selected) },
                                    )
                                }
                            }
                        } else {
                            // 列表视图 (云端离线任务已收纳至顶栏 IconButton)
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    bottom = innerPadding.calculateBottomPadding() + 96.dp,
                                ),
                            ) {
                                items(
                                    items = displayedFiles,
                                    key = { it.id },
                                    contentType = { if (it.isFolder) "folder" else "file" },
                                ) { file ->
                                    val isSelected = state.selectedFileIds.contains(file.id)
                                    val isBlurred = isSpoilerBlurEnabled && !state.revealedFileIds.contains(file.id)
                                    Box(modifier = Modifier.animateItem()) {
                                        FileItemRow(
                                            file = file,
                                            isSelectionMode = state.isSelectionMode,
                                            isSelected = isSelected,
                                            isSpoilerBlurred = isBlurred,
                                            isHighlighted = highlightedFileIds.contains(file.id),
                                            locationLabel = hitLocations[file.id],
                                            onToggleSpoiler = { state.toggleSpoiler(file.id) },
                                            onClick = {
                                                if (file.isFolder) {
                                                    state.openFolder(file.id, file.name)
                                                } else if (file.isPlayableVideo()) {
                                                    onNavigateToVideoPlayer(file.id, file.name)
                                                } else if (file.isPreviewableImage() && file.thumbnailLink.isNotBlank()) {
                                                    previewImage = file
                                                } else {
                                                    downloadManager.enqueue(file)
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("已加入本地下载: ${file.name}")
                                                    }
                                                }
                                            },
                                            onLongClick = { state.enterSelection(file.id) },
                                            onSelectToggle = { selected -> state.setSelected(file.id, selected) },
                                            onRename = {
                                                renameTargetFile = file
                                                renameNewName = file.name
                                            },
                                            onMove = { moveTargetIds = setOf(file.id) },
                                            onDelete = { state.moveToTrash(listOf(file.id), file.name) },
                                            onDownload = {
                                                downloadManager.enqueue(file)
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("已加入本地下载: ${file.name}")
                                                }
                                            },
                                            onDownloadSegment = {
                                                segmentTargetFile = file
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
    }

    // 秒传 ModalBottomSheet（从 Extended FAB 或外部磁力链唤起，存入 My Packs 并导航进入）
    if (showInstantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showInstantSheet = false
                instantRepo.clearPendingMagnet()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = MaterialTheme.shapes.large,
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
                        snackbarHostState.showSnackbar("成功秒传 ${createdIds.size} 项并进入 ${targetBread.name}！")
                    }
                },
                onOfflineTaskCreated = { targetBread ->
                    showInstantSheet = false
                    instantRepo.clearPendingMagnet()
                    state.navigateToFolder(targetBread)
                    scope.launch {
                        snackbarHostState.showSnackbar("已加入云端离线任务并进入 ${targetBread.name}！")
                    }
                },
            )
        }
    }

    // 新建文件夹对话框
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("文件夹名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.largeIncreased,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            showNewFolderDialog = false
                            state.createFolder(newFolderName)
                        }
                    },
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 重命名对话框
    renameTargetFile?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTargetFile = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameNewName,
                    onValueChange = { renameNewName = it },
                    label = { Text("新名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.largeIncreased,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameNewName.isNotBlank() && renameNewName != target.name) {
                            val id = target.id
                            val newName = renameNewName
                            renameTargetFile = null
                            state.rename(id, newName)
                        }
                    },
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetFile = null }) {
                    Text("取消")
                }
            },
        )
    }

    // 移动目标选择器
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

    // 视频段落下载工作台
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
                    snackbarHostState.showSnackbar("已加入段落下载: ${target.name} [$timeLabel]")
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


/**
 * 带 Spoiler 防窥模糊与卡片投影的大图卡片视图，利用 Coil 3 加载缩略图
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShadowFileCard(
    file: FileStat,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    isSpoilerBlurred: Boolean = false,
    isHighlighted: Boolean = false,
    highlightBadgeText: String = "刚秒传",
    onToggleSpoiler: () -> Unit = {},
    onClick: () -> Unit,
    onDownload: () -> Unit = {},
    onDownloadSegment: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    onMove: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onSelectToggle: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val primaryActionModifier = Modifier.combinedClickable(
        onClick = {
            if (isSelectionMode) onSelectToggle(!isSelected) else onClick()
        },
        onLongClick = onLongClick,
    )

    // 网格视图不叠眼睛按钮：整块封面本身就是热区，模糊即提示。
    // 首次点击揭示，再点才打开，避免误触把遮蔽形同虚设。
    val coverIsSpoiler = isSpoilerBlurred && file.thumbnailLink.isNotEmpty()
    val coverActionModifier = Modifier.combinedClickable(
        onClickLabel = if (coverIsSpoiler) "显示预览" else null,
        onClick = {
            when {
                isSelectionMode -> onSelectToggle(!isSelected)
                coverIsSpoiler -> onToggleSpoiler()
                else -> onClick()
            }
        },
        onLongClick = onLongClick,
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (file.thumbnailLink.isNotEmpty()) 4.dp else 1.dp,
                shape = MaterialTheme.shapes.medium,
            ),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                isHighlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .then(coverActionModifier)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (file.thumbnailLink.isNotEmpty()) {
                    AsyncImage(
                        model = file.thumbnailLink,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isSpoilerBlurred) Modifier.blur(24.dp) else Modifier),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        imageVector = if (file.isFolder) Icons.Outlined.Folder else Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(primaryActionModifier),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isHighlighted) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Text(
                                    text = highlightBadgeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (file.isFolder) "文件夹" else file.sizeBytes.toReadableSize(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = onSelectToggle,
                    )
                } else {
                    var showCardMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showCardMenu = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "更多操作",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    DropdownMenu(
                        expanded = showCardMenu,
                        onDismissRequest = { showCardMenu = false },
                    ) {
                        if (!file.isFolder) {
                            DropdownMenuItem(
                                text = { Text("下载到本地") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Download, contentDescription = null)
                                },
                                onClick = {
                                    showCardMenu = false
                                    onDownload()
                                },
                            )
                            if (file.isPlayableVideo()) {
                                DropdownMenuItem(
                                    text = { Text("下载指定段落") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.ContentCut,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        showCardMenu = false
                                        onDownloadSegment()
                                    },
                                )
                            }
                        }
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Edit, contentDescription = null)
                            },
                            onClick = {
                                showCardMenu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("移动到") },
                            leadingIcon = {
                                Icon(Icons.Outlined.DriveFileMove, contentDescription = null)
                            },
                            onClick = {
                                showCardMenu = false
                                onMove()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("移入回收站", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showCardMenu = false
                                onDelete()
                            },
                        )
                    }
                }
                }
            }
        }
    }
}
