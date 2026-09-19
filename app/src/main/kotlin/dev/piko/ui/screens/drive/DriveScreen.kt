package dev.piko.ui.screens.drive

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.MagnetResolutionResult
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.safeDrawingPadding
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.data.repository.FileSortOrder
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.ui.components.BreadcrumbBar
import dev.piko.ui.components.FileItemRow
import dev.piko.ui.components.FolderPickerDialog
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.MoveTargetDialog
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.SegmentDownloadSheet
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.FixedColors
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.mainContentIndices
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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

    // 视图切换：列表模式 vs 网格模式
    var isGridShadowMode by remember { mutableStateOf(false) }

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

    // 刚秒传成功时，自动平滑滚动定位至新添加的项目并在 8 秒后渐隐
    LaunchedEffect(displayedFiles, highlightedFileIds) {
        if (highlightedFileIds.isNotEmpty()) {
            val idx = displayedFiles.indexOfFirst { it.id in highlightedFileIds }
            if (idx >= 0) {
                if (isGridShadowMode) {
                    gridState.animateScrollToItem(idx)
                } else {
                    listState.animateScrollToItem(idx)
                }
            }
            delay(8000)
            state.clearHighlight()
        }
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
                        IconButton(onClick = { isGridShadowMode = !isGridShadowMode }) {
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
                                            } else if (file.name.isPlayableVideo()) {
                                                onNavigateToVideoPlayer(file.id, file.name)
                                            } else if (file.name.isPreviewableImage() && file.thumbnailLink.isNotBlank()) {
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
                                                } else if (file.name.isPlayableVideo()) {
                                                    onNavigateToVideoPlayer(file.id, file.name)
                                                } else if (file.name.isPreviewableImage() && file.thumbnailLink.isNotBlank()) {
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
        Dialog(
            onDismissRequest = { previewImage = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 4.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = image.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { previewImage = null }) {
                                Icon(Icons.Outlined.Close, contentDescription = "关闭预览")
                            }
                        }
                        ZoomableImage(
                            model = image.thumbnailLink,
                            contentDescription = image.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 560.dp)
                                .padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-size image viewer. The old preview only rendered a scaled thumbnail,
 * which made high-resolution posters impossible to inspect. Keep the image in
 * the same dialog, but let touch and pointer gestures control a bounded canvas.
 */
@Composable
private fun ZoomableImage(
    model: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    val transformState = androidx.compose.foundation.gestures.rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offsetX += pan.x
        offsetY += pan.y
    }

    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .heightIn(min = 240.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2f
                        if (scale == 1f) {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    },
                )
            }
            .transformable(transformState)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
    )
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
                        imageVector = if (file.isFolder) Icons.Outlined.CreateNewFolder else Icons.Outlined.Image,
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
                            if (file.name.isPlayableVideo()) {
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

private const val AUTO_RESOLVE_DEBOUNCE_MS = 350L

/**
 * 输入框里的内容归一化成可解析的磁力链，不像磁力链就返回 null，不解析也不报错。
 *
 * 只粘 infohash 的情况不少，所以补全一条磁力链；但限定 40 位十六进制，否则随手敲的
 * 任意长串都会发一次请求。
 */
private fun normalizeMagnet(raw: String): String? {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith("magnet:?xt=urn:btih:") -> trimmed
        trimmed.length == 40 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } ->
            "magnet:?xt=urn:btih:$trimmed"
        else -> null
    }
}

/** 保存位置胶囊。目标还没取到时不可点，也不拿 My Packs 顶替，免得闪一个可能是错的名字。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstantTargetChip(
    target: PathBreadcrumb?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled && target != null,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (target == null) "正在确认保存位置" else "目标：${target.name}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (target != null) {
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "更换保存位置",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * 嵌入在 BottomSheet 里的秒传与磁力确认工作台
 * 粘上磁力链自动解析，保存目标可点胶囊更换并被记住，未配置过时默认 My Packs
 *
 * 这里只保存、不导航：目标目录随回调交给调用方，由它经状态类切过去，顺带清掉
 * 搜索与选中。自己调仓库切目录会绕过这一步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstantSheetContent(
    initialMagnet: String = "",
    onDismiss: () -> Unit,
    onSuccess: (createdIds: List<String>, targetBread: PathBreadcrumb) -> Unit,
    onOfflineTaskCreated: (targetBread: PathBreadcrumb) -> Unit,
) {
    val driveRepo = PikoApplication.instance.driveRepository
    val instantRepo = PikoApplication.instance.instantMagnetRepository
    val sessionManager = PikoApplication.instance.sessionManager
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var magnetInput by remember { mutableStateOf(initialMagnet) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var isResolving by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var resolutionResult by remember { mutableStateOf<MagnetResolutionResult?>(null) }
    var items by remember { mutableStateOf<List<InstantFileItem>>(emptyList()) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var targetBreadcrumb by remember { mutableStateOf<PathBreadcrumb?>(null) }
    var targetNotice by remember { mutableStateOf<String?>(null) }

    // 勾选项里只要有一项云端没收录，整单就走离线，不做「能秒传的先秒传、其余离线」。
    // createUrlFile 只收整条磁力 URL，ResolvedFile 也不带文件索引，离线任务没法只取
    // 选中的那几个；两者并用会把刚秒传的文件再下一遍，目录里留下重复项。取舍是用户
    // 定的：宁可放弃那几项的秒传，也不要重复。
    val selectedItems = selectedIndices.sorted().map { items[it] }
    val canInstantSaveAll = selectedItems.isNotEmpty() && selectedItems.all { it.isInstantReady }

    // 只在这次保存真的会建目录时才让人改名字。走离线那条路目录是 PikPak 自己建的，摆一个
    // 可编辑的名字只会让人以为能生效。
    val willCreateFolder = canInstantSaveAll && selectedItems.size > 1
    var folderNameInput by remember { mutableStateOf("") }
    val resourceName = resolutionResult?.resource?.name
    // 换一条磁力链要重新预填，否则输入框里留着上一条资源的名字
    LaunchedEffect(resourceName) {
        folderNameInput = resourceName?.let { FileNameSanitizer.sanitize(it) }.orEmpty()
    }

    // 外部分享进来的磁力链已经在用户手上，输入框只是让他把同一件事再确认一遍，所以收起。
    // 解析失败时再放出来：否则他既看不到那串链接，也没法改、没法重试。
    var showMagnetEditor by remember { mutableStateOf(initialMagnet.isBlank()) }

    // 归一化后的磁力链兼作解析的触发键：同一条链不会重复解析，改成别的链会取消上一次。
    // 非磁力的输入（http 直链、ed2k）在这里是 null，自动解析不触发，由按钮手动提交。
    val pendingMagnet = remember(magnetInput) { normalizeMagnet(magnetInput) }
    var resolveTrigger by remember { mutableStateOf(0) }

    // 记住过的目标优先；没配置过才退回 My Packs。只取一次而不是持续收集，
    // 否则用户在本次会话里改完目标，写回 DataStore 的那次发射会再盖一遍。
    suspend fun resolveTarget(): PathBreadcrumb {
        val saved = sessionManager.instantTargetFlow.first()
        if (saved != null) {
            // 记下的目录可能已经被删或进了回收站。不验的话要等保存时才暴露，报的还是
            // 一句原始 API 错误。根目录是空 id，没有对应的 FileDetail，不验。
            val alive = saved.folderId.isEmpty() ||
                driveRepo.getFileDetail(saved.folderId).map { !it.trashed }.getOrDefault(false)
            if (alive) return PathBreadcrumb(saved.folderId, saved.folderName)
            targetNotice = "原保存目标已不存在，已切换到 My Packs"
        }
        return driveRepo.getOrCreateMyPacksFolder().getOrDefault(PathBreadcrumb("", "My Packs"))
    }

    LaunchedEffect(Unit) {
        targetBreadcrumb = resolveTarget()
    }

    // 把当前输入整条交给云端离线任务。磁力以外的链接只有这一条路：createUrlFile 收任意
    // URL，但 resolveMagnet 只认磁力，所以这些输入不会有文件列表可勾。
    fun submitOfflineTask() {
        isSaving = true
        scope.launch {
            val targetBread = targetBreadcrumb ?: resolveTarget()
            instantRepo.enqueueOfflineTask(magnetInput.trim(), targetBread.id)
                .onSuccess { onOfflineTaskCreated(targetBread) }
                .onFailure { errorMsg = "保存失败: ${it.localizedMessage}" }
            isSaving = false
        }
    }

    // 解析的唯一实现。外部唤起、手动粘贴、按钮重试都走这里：resolveTrigger 让按钮能对
    // 同一条链再来一次，key 不变时不会重复解析。
    LaunchedEffect(pendingMagnet, resolveTrigger) {
        resolutionResult = null
        items = emptyList()
        selectedIndices = emptySet()
        errorMsg = null
        if (pendingMagnet == null) return@LaunchedEffect
        // 防抖。粘贴一次就是一条完整的链，等待只为压掉手敲时中途的半条链接，所以取短值。
        delay(AUTO_RESOLVE_DEBOUNCE_MS)
        isResolving = true
        try {
            instantRepo.resolve(pendingMagnet)
                .onSuccess { data ->
                    if (data == null) {
                        errorMsg = "PikPak 索引暂未收录该资源，可直接提交云端离线任务"
                        showMagnetEditor = true
                    } else {
                        resolutionResult = data
                        items = data.items
                        // 与网盘列表的启发式折叠同一套判据：剔掉 sample/subs 这类次要目录里的
                        // 文件，再按最大文件的十分之一卡一道门槛。用户仍可手改。
                        selectedIndices = mainContentIndices(
                            data.items.map { it.file.path },
                            data.items.map { it.file.size },
                        )
                    }
                }
                .onFailure { err ->
                    errorMsg = "解析失败: ${err.localizedMessage}"
                    showMagnetEditor = true
                }
        } finally {
            // 取消也要走到这里，否则换链后指示器会一直转
            isResolving = false
        }
    }

    // 外部唤起的链不合法时自动解析不会发生，而输入框又是收起的，不兜住就是一个空 Sheet。
    LaunchedEffect(initialMagnet) {
        if (initialMagnet.isNotBlank() && normalizeMagnet(initialMagnet) == null) {
            errorMsg = "这不是一条可解析的磁力链接，可直接提交云端离线任务"
            showMagnetEditor = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "秒传与磁力直通",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "毫秒级探测云端秒传与离线下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showMagnetEditor) {
            // 磁力输入框
            OutlinedTextField(
                value = magnetInput,
                onValueChange = { magnetInput = it },
                label = { Text("磁力链接或下载地址") },
                placeholder = { Text("magnet:?xt=urn:btih:... 或 http://...") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.largeIncreased,
                maxLines = 2,
                trailingIcon = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                        if (!clip.isNullOrBlank()) magnetInput = clip.trim()
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "从剪贴板粘贴")
                    }
                },
            )

            // 已经出结果时按钮没有可触发的东西。解析中与解析失败都留着，否则没有重试手段。
            if (resolutionResult == null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { if (pendingMagnet != null) resolveTrigger++ else submitOfflineTask() },
                    enabled = !isResolving && !isSaving && magnetInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (isResolving) {
                        PikoLoadingIndicator(size = 20.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在毫秒级探测云端索引...")
                    } else {
                        Icon(
                            imageVector = if (pendingMagnet != null) Icons.Outlined.Bolt else Icons.Outlined.CloudDownload,
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (pendingMagnet != null) "重新解析磁力资源" else "提交离线下载")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InstantTargetChip(
                target = targetBreadcrumb,
                enabled = !isSaving,
                onClick = { showTargetPicker = true },
            )
            // 外部那条路没有解析按钮，进度只能落在这里
            if (isResolving && !showMagnetEditor) {
                Spacer(modifier = Modifier.width(10.dp))
                PikoLoadingIndicator(size = 16.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "正在探测云端索引...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        targetNotice?.let { notice ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        errorMsg?.let { err ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 解析成功展示文件确认界面
        resolutionResult?.let { result ->
            Spacer(modifier = Modifier.height(14.dp))

            // 资源标题卡片。多文件秒传会以这个名字建一层目录，所以这一行本身就是那个
            // 目录名，直接在原地改，不另起一个输入框。
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (willCreateFolder) {
                        val isBlank = folderNameInput.isBlank()
                        val nameColor = if (isBlank) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                        BasicTextField(
                            value = folderNameInput,
                            onValueChange = { folderNameInput = it },
                            enabled = !isSaving,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = nameColor),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (isBlank) {
                                    Text(
                                        text = "目录名不能为空",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                inner()
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = "修改新建目录的名称",
                            tint = nameColor,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Text(
                            text = result.resource.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 文件选择控制栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "待保存文件 (${selectedIndices.size} / ${items.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(
                    onClick = {
                        selectedIndices = if (selectedIndices.size == items.size) emptySet() else items.indices.toSet()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (selectedIndices.size == items.size) "全不选" else "全选")
                }
            }

            // 文件列表（可滚动）
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    val isChecked = selectedIndices.contains(index)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIndices = if (isChecked) selectedIndices - index else selectedIndices + index
                            },
                        color = if (isChecked) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { chk ->
                                    selectedIndices = if (chk) selectedIndices + index else selectedIndices - index
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = item.file.size.toReadableSize(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            Icon(
                                imageVector = if (item.isInstantReady) Icons.Outlined.Check else Icons.Outlined.ErrorOutline,
                                contentDescription = if (item.isInstantReady) "云端已有，可秒传" else "云端没有，需下载",
                                tint = if (item.isInstantReady) {
                                    LocalFixedColors.current.InstantMatchGreen
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            suspend fun runInstantSave(target: PathBreadcrumb, toSave: List<InstantFileItem>) {
                // 多个文件平铺进目标目录会把它和别的资源混在一起，先建一层再存。名字取自
                // 输入框，仍要过一遍 sanitize：用户可能敲进 / : * 这类建不出来的字符。
                val saveTarget = if (toSave.size > 1) {
                    val folderName = FileNameSanitizer.sanitize(folderNameInput)
                    val folderId = driveRepo.createNewFolder(target.id, folderName).getOrElse { err ->
                        isSaving = false
                        errorMsg = "新建文件夹失败: ${err.localizedMessage}"
                        return
                    }
                    PathBreadcrumb(folderId, folderName)
                } else {
                    target
                }
                val saveRes = instantRepo.instantSave(toSave, saveTarget.id)
                isSaving = false
                saveRes
                    .onSuccess { createdIds -> onSuccess(createdIds, saveTarget) }
                    .onFailure { errorMsg = "保存失败: ${it.localizedMessage}" }
            }

            Button(
                onClick = {
                    isSaving = true
                    scope.launch {
                        val targetBread = targetBreadcrumb ?: resolveTarget()
                        if (canInstantSaveAll) {
                            runInstantSave(targetBread, selectedItems)
                        } else {
                            val taskRes = instantRepo.enqueueOfflineTask(magnetInput.trim(), targetBread.id)
                            isSaving = false
                            taskRes
                                .onSuccess { onOfflineTaskCreated(targetBread) }
                                .onFailure { errorMsg = "保存失败: ${it.localizedMessage}" }
                        }
                    }
                },
                enabled = !isSaving && selectedItems.isNotEmpty() && targetBreadcrumb != null &&
                    !(willCreateFolder && folderNameInput.isBlank()),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (isSaving) {
                    PikoLoadingIndicator(size = 18.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("保存中...")
                } else {
                    Icon(
                        imageVector = if (canInstantSaveAll) Icons.Outlined.Bolt else Icons.Outlined.CloudDownload,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("保存 ${selectedItems.size} 项到 ${targetBreadcrumb?.name.orEmpty()}")
                }
            }
        } ?: run {
            // 云端没有收录时，磁力本身仍然可以直接交给离线下载。非磁力的输入不在这里出口：
            // 顶部那个按钮已经是它唯一的提交入口，两个一样的按钮只会让人犹豫点哪个。
            if (pendingMagnet != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { submitOfflineTask() },
                    enabled = !isSaving && !isResolving && targetBreadcrumb != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (isSaving) {
                        PikoLoadingIndicator(size = 18.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("保存中...")
                    } else {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("保存到 ${targetBreadcrumb?.name.orEmpty()}")
                    }
                }
            }
        }
    }

    if (showTargetPicker) {
        FolderPickerDialog(
            title = "选择保存位置",
            confirmLabel = "存到这里",
            onDismiss = { showTargetPicker = false },
            onConfirm = { targetId, targetName ->
                showTargetPicker = false
                targetNotice = null
                targetBreadcrumb = PathBreadcrumb(targetId, targetName)
                scope.launch { sessionManager.saveInstantTarget(targetId, targetName) }
            },
        )
    }
}

/**
 * 云端离线任务抽屉内容 (从顶部状态图标展开)
 */
@Composable
fun CloudTasksSheetContent(
    runningTasks: List<OfflineTask>,
    onNewTask: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "云端离线任务 (${runningTasks.size})",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            TextButton(onClick = onNewTask) {
                Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("新建")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (runningTasks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CloudDone,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "当前没有正在进行的云端离线任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(runningTasks, key = { it.id }) { task ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = task.name.ifEmpty { "离线任务" },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = if (task.phase == TaskPhase.RUNNING) "下载中" else "排队准备中",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "${task.progress}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            LinearProgressIndicator(
                                progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

