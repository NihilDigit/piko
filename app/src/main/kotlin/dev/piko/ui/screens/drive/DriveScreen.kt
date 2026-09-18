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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import dev.piko.data.repository.FileNameSanitizer
import dev.piko.data.repository.InstantFileItem
import dev.piko.data.repository.MagnetResolutionResult
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.data.repository.FileSortOrder
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.ui.components.BreadcrumbBar
import dev.piko.ui.components.FileItemRow
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoLoadingIndicator
import dev.piko.ui.components.SegmentDownloadSheet
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.FixedColors
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.OfflineTask
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

    var files by remember { mutableStateOf<List<FileStat>>(emptyList()) }
    var runningTasks by remember { mutableStateOf<List<OfflineTask>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf(FileSortOrder.TIME_DESC) }
    var showSortMenu by remember { mutableStateOf(false) }

    // 视图切换：列表模式 vs 网格模式
    var isGridShadowMode by remember { mutableStateOf(false) }

    // 防窥/Spoiler 模糊遮蔽与启发式筛选 (可在设置中切换)
    val sessionManager = PikoApplication.instance.sessionManager
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsState(initial = true)
    val isHeuristicFilterEnabled by sessionManager.heuristicFilterFlow.collectAsState(initial = true)
    val revealedFileIds = remember { mutableStateListOf<String>() }

    // 启发式过滤单文件夹临时展开状态 (切文件夹时自动重置)
    var showAllFilesTemporarily by remember(currentFolderId) { mutableStateOf(false) }

    // 多选模式
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedFileIds = remember { mutableStateListOf<String>() }

    // 目录导航栈：持久化与全局单例共享，记住当前打开的位置，切 Tab / 重启不丢失
    val folderStack by driveRepo.folderStackFlow.collectAsState()
    val activeFolder = folderStack.lastOrNull() ?: PathBreadcrumb(currentFolderId, currentFolderName)
    val activeFolderId = activeFolder.id
    val activeFolderName = activeFolder.name

    LaunchedEffect(activeFolderId) {
        showAllFilesTemporarily = false
    }

    LaunchedEffect(Unit) {
        if (folderStack.size == 1 && folderStack[0].id.isEmpty() && currentFolderId.isEmpty()) {
            val (lastId, lastName, serialized) = sessionManager.getLastFolder()
            if (lastId.isNotEmpty()) {
                val restoredStack = if (serialized.isNotEmpty()) {
                    serialized.split(";").mapNotNull { entry ->
                        val parts = entry.split("::")
                        if (parts.size == 2) PathBreadcrumb(parts[0], parts[1]) else null
                    }
                } else emptyList()
                if (restoredStack.isNotEmpty()) {
                    driveRepo.updateFolderStack(restoredStack)
                } else {
                    driveRepo.updateFolderStack(listOf(PathBreadcrumb(lastId, lastName)))
                }
            }
        }
    }

    LaunchedEffect(folderStack) {
        val last = folderStack.lastOrNull()
        if (last != null) {
            val serialized = folderStack.joinToString(";") { "${it.id}::${it.name}" }
            sessionManager.saveLastFolder(last.id, last.name, serialized)
        }
    }

    BackHandler(enabled = folderStack.size > 1) {
        driveRepo.popFolder()
    }

    // 对话框与 Sheet 状态
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTargetFile by remember { mutableStateOf<FileStat?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var segmentTargetFile by remember { mutableStateOf<FileStat?>(null) }

    // 秒传与离线任务 BottomSheet
    var showInstantSheet by remember { mutableStateOf(false) }
    var showCloudTasksSheet by remember { mutableStateOf(false) }

    val pendingMagnet by instantRepo.pendingMagnetFlow.collectAsState()
    LaunchedEffect(pendingMagnet) {
        if (!pendingMagnet.isNullOrBlank()) {
            showInstantSheet = true
        }
    }

    // 搜索状态 (支持当前目录 0ms 即时过滤与全盘云端检索)
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isGlobalSearching by remember { mutableStateOf(false) }
    var globalSearchResults by remember { mutableStateOf<List<FileStat>?>(null) }

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

    var highlightedFileIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var folderMeaninglessMap by remember(activeFolderId) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    // 启发式量级筛选：当主体大文件与次要小文件差 10 倍以上时，默认折叠低量级小文件与无意义文件夹
    val nonFolderFiles = remember(files) { files.filter { !it.isFolder } }
    val maxFileSize = remember(nonFolderFiles) { nonFolderFiles.maxOfOrNull { it.sizeBytes } ?: 0L }
    val threshold = remember(maxFileSize) { if (maxFileSize >= 5 * 1024 * 1024L) maxFileSize / 10L else 0L }

    fun isLikelyNoiseFolderName(name: String): Boolean {
        val clean = name.trim().lowercase()
        val noiseNames = setOf(
            "sample", "samples", "proof", "proofs", "screens", "screen", "screenshot", "screenshots",
            "subs", "sub", "subtitle", "subtitles", "extra", "extras", "nfo", "trailer", "trailers",
            "bonus", "featurette", "featurettes", "cover", "covers", "metadata"
        )
        if (clean in noiseNames) return true
        if (clean.startsWith("sample") || clean.startsWith("screen") || clean.startsWith("proof") || clean.startsWith("sub")) return true
        return false
    }

    LaunchedEffect(files, threshold, isHeuristicFilterEnabled) {
        if (threshold > 0L && isHeuristicFilterEnabled) {
            val folders = files.filter { it.isFolder }
            folders.forEach { folder ->
                if (!folderMeaninglessMap.containsKey(folder.id)) {
                    val fastNoise = isLikelyNoiseFolderName(folder.name)
                    if (fastNoise) {
                        folderMeaninglessMap = folderMeaninglessMap + (folder.id to true)
                    }
                    launch(Dispatchers.IO) {
                        val isMeaningless = driveRepo.isFolderMeaningless(folder.id, threshold)
                        folderMeaninglessMap = folderMeaninglessMap + (folder.id to isMeaningless)
                    }
                }
            }
        }
    }

    val potentialHiddenCount = remember(
        files,
        isHeuristicFilterEnabled,
        threshold,
        folderMeaninglessMap,
    ) {
        if (!isHeuristicFilterEnabled || threshold <= 0L) {
            0
        } else {
            val filtered = files.filter { file ->
                if (file.isFolder) {
                    val isMeaningless = folderMeaninglessMap[file.id] ?: isLikelyNoiseFolderName(file.name)
                    !isMeaningless
                } else {
                    file.sizeBytes >= threshold
                }
            }
            (files.size - filtered.size).coerceAtLeast(0)
        }
    }

    val heuristicFilteredFiles = remember(
        files,
        isHeuristicFilterEnabled,
        showAllFilesTemporarily,
        threshold,
        folderMeaninglessMap,
    ) {
        if (!isHeuristicFilterEnabled || showAllFilesTemporarily || threshold <= 0L) {
            files
        } else {
            files.filter { file ->
                if (file.isFolder) {
                    val isMeaningless = folderMeaninglessMap[file.id] ?: isLikelyNoiseFolderName(file.name)
                    !isMeaningless
                } else {
                    file.sizeBytes >= threshold
                }
            }
        }
    }

    val displayedFiles = remember(files, heuristicFilteredFiles, searchQuery, globalSearchResults) {
        if (globalSearchResults != null) {
            globalSearchResults!!
        } else if (searchQuery.isBlank()) {
            heuristicFilteredFiles
        } else {
            files.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }

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
            highlightedFileIds = emptySet()
        }
    }

    LaunchedEffect(activeFolderId) {
        searchQuery = ""
        globalSearchResults = null
    }

    val loadFiles = {
        scope.launch {
            val result = driveRepo.listFiles(parentId = activeFolderId, sortOrder = sortOrder)
            isLoading = false
            isRefreshing = false
            result.onSuccess { (list, _) ->
                files = list
            }.onFailure { error ->
                snackbarHostState.showSnackbar("加载失败: ${error.localizedMessage}")
            }
        }
    }

    // 监听云端离线任务并就地刷新
    LaunchedEffect(Unit) {
        while (isActive) {
            taskRepo.getTasks().onSuccess { resp ->
                runningTasks = resp.tasks.filter { it.phase == TaskPhase.RUNNING || it.phase == TaskPhase.PENDING }
            }
            delay(4000)
        }
    }

    LaunchedEffect(activeFolderId, sortOrder) {
        isLoading = true
        loadFiles()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            PikoTopBar(
                title = if (isSelectionMode) "已选择 ${selectedFileIds.size} 项" else activeFolderName,
                navigationIcon = if (isSelectionMode) {
                    {
                        IconButton(onClick = {
                            isSelectionMode = false
                            selectedFileIds.clear()
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Exit selection")
                        }
                    }
                } else if (folderStack.size > 1) {
                    {
                        IconButton(onClick = { driveRepo.popFolder() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回上一级")
                        }
                    }
                } else null,
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            if (selectedFileIds.size == files.size) {
                                selectedFileIds.clear()
                            } else {
                                selectedFileIds.clear()
                                selectedFileIds.addAll(files.map { it.id })
                            }
                        }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    driveRepo.moveToTrash(selectedFileIds.toList())
                                    snackbarHostState.showSnackbar("已移入回收站")
                                    isSelectionMode = false
                                    selectedFileIds.clear()
                                    loadFiles()
                                }
                            },
                            enabled = selectedFileIds.isNotEmpty(),
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

                        // 云端离线任务入口 (带任务角标)
                        IconButton(onClick = { showCloudTasksSheet = true }) {
                            BadgedBox(
                                badge = {
                                    if (runningTasks.isNotEmpty()) {
                                        Badge {
                                            Text("${runningTasks.size}")
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.CloudSync,
                                    contentDescription = "云端离线任务",
                                    tint = if (runningTasks.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                                onClick = { sortOrder = FileSortOrder.TIME_DESC; showSortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("按文件名称 (A-Z)") },
                                onClick = { sortOrder = FileSortOrder.NAME_ASC; showSortMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("按文件大小 (从大到小)") },
                                onClick = { sortOrder = FileSortOrder.SIZE_DESC; showSortMenu = false },
                            )
                        }

                        IconButton(onClick = {
                            isSelectionMode = true
                        }) {
                            Icon(Icons.Outlined.Check, contentDescription = "Enter selection")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!isSelectionMode) {
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
                .padding(innerPadding),
        ) {
            if (folderStack.size > 1) {
                BreadcrumbBar(
                    breadcrumbs = folderStack.drop(1),
                    onBreadcrumbClick = { index ->
                        driveRepo.popToBreadcrumb(index + 1)
                    },
                )
            }

            // 搜索栏 (支持当前目录 0ms 即时过滤与全盘云端检索)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    globalSearchResults = null
                },
                placeholder = {
                    Text(
                        if (globalSearchResults != null) "全盘搜索: $searchQuery"
                        else "搜索当前目录 (${files.size} 项)..."
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
                        if (isGlobalSearching) {
                            PikoLoadingIndicator(size = 18.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        } else if (searchQuery.isNotBlank() && globalSearchResults == null) {
                            TextButton(
                                onClick = {
                                    isGlobalSearching = true
                                    scope.launch {
                                        val res = driveRepo.search(searchQuery.trim())
                                        isGlobalSearching = false
                                        res.onSuccess {
                                            globalSearchResults = it
                                        }.onFailure {
                                            snackbarHostState.showSnackbar("全盘搜索失败: ${it.localizedMessage}")
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text("全盘搜", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (searchQuery.isNotEmpty() || globalSearchResults != null) {
                            IconButton(onClick = {
                                searchQuery = ""
                                globalSearchResults = null
                            }) {
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
                targetState = isLoading,
                animationSpec = PikoMotion.StateCrossfadeSpec,
                label = "drive_loading",
            ) { loading ->
                if (loading) {
                    FullScreenLoading()
                } else {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            isRefreshing = true
                            loadFiles()
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // 启发式量级折叠提示胶囊/横幅
                            if (isHeuristicFilterEnabled && searchQuery.isBlank() && globalSearchResults == null) {
                                if (!showAllFilesTemporarily && potentialHiddenCount > 0) {
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
                                                onClick = { showAllFilesTemporarily = true },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            ) {
                                                Text("显示全部", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                } else if (showAllFilesTemporarily && potentialHiddenCount > 0) {
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
                                                onClick = { showAllFilesTemporarily = false },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            ) {
                                                Text("恢复折叠", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                if (displayedFiles.isEmpty() && runningTasks.isEmpty()) {
                                    if (searchQuery.isNotBlank()) {
                                        PikoEmptyState(
                                            title = "未找到相关文件",
                                            description = if (globalSearchResults != null) "全盘未找到包含「$searchQuery」的文件" else "当前文件夹未找到「$searchQuery」，可点击搜索框右侧「全盘搜」进行全局查找",
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
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(displayedFiles, key = { it.id }) { file ->
                                    val isBlurred = isSpoilerBlurEnabled && !revealedFileIds.contains(file.id)
                                    ShadowFileCard(
                                        file = file,
                                        isSpoilerBlurred = isBlurred,
                                        isHighlighted = highlightedFileIds.contains(file.id),
                                        onToggleSpoiler = {
                                            if (revealedFileIds.contains(file.id)) {
                                                revealedFileIds.remove(file.id)
                                            } else {
                                                revealedFileIds.add(file.id)
                                            }
                                        },
                                        onClick = {
                                            if (file.isFolder) {
                                                driveRepo.pushFolder(file.id, file.name)
                                            } else if (file.name.endsWith(".mp4", ignoreCase = true) ||
                                                file.name.endsWith(".mkv", ignoreCase = true)
                                            ) {
                                                onNavigateToVideoPlayer(file.id, file.name)
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
                                    )
                                }
                            }
                        } else {
                            // 列表视图 (云端离线任务已收纳至顶栏 IconButton)
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 90.dp),
                            ) {
                                items(displayedFiles, key = { it.id }) { file ->
                                    val isSelected = selectedFileIds.contains(file.id)
                                    val isBlurred = isSpoilerBlurEnabled && !revealedFileIds.contains(file.id)
                                    Box(modifier = Modifier.animateItem()) {
                                        FileItemRow(
                                            file = file,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = isSelected,
                                            isSpoilerBlurred = isBlurred,
                                            isHighlighted = highlightedFileIds.contains(file.id),
                                            onToggleSpoiler = {
                                                if (revealedFileIds.contains(file.id)) {
                                                    revealedFileIds.remove(file.id)
                                                } else {
                                                    revealedFileIds.add(file.id)
                                                }
                                            },
                                            onClick = {
                                                if (file.isFolder) {
                                                    driveRepo.pushFolder(file.id, file.name)
                                                } else if (file.name.endsWith(".mp4", ignoreCase = true) ||
                                                    file.name.endsWith(".mkv", ignoreCase = true) ||
                                                    file.name.endsWith(".mov", ignoreCase = true) ||
                                                    file.name.endsWith(".avi", ignoreCase = true)
                                                ) {
                                                    onNavigateToVideoPlayer(file.id, file.name)
                                                } else {
                                                    downloadManager.enqueue(file)
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("已加入本地下载: ${file.name}")
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                isSelectionMode = true
                                                if (!isSelected) selectedFileIds.add(file.id)
                                            },
                                            onSelectToggle = { selected ->
                                                if (selected) selectedFileIds.add(file.id) else selectedFileIds.remove(file.id)
                                            },
                                            onRename = {
                                                renameTargetFile = file
                                                renameNewName = file.name
                                            },
                                            onDelete = {
                                                scope.launch {
                                                    driveRepo.moveToTrash(listOf(file.id))
                                                    snackbarHostState.showSnackbar("已移入回收站: ${file.name}")
                                                    loadFiles()
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
                    highlightedFileIds = createdIds.toSet()
                    driveRepo.navigateToFolder(targetBread)
                    loadFiles()
                    scope.launch {
                        snackbarHostState.showSnackbar("成功秒传 ${createdIds.size} 项并进入 ${targetBread.name}！")
                    }
                },
                onOfflineTaskCreated = { folderName ->
                    showInstantSheet = false
                    instantRepo.clearPendingMagnet()
                    scope.launch {
                        snackbarHostState.showSnackbar("已加入云端离线任务并进入 $folderName！")
                    }
                },
            )
        }
    }

    // 云端离线任务 ModalBottomSheet（从顶栏 IconButton 唤起）
    if (showCloudTasksSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCloudTasksSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = MaterialTheme.shapes.large,
        ) {
            CloudTasksSheetContent(
                runningTasks = runningTasks,
                onNewTask = {
                    showCloudTasksSheet = false
                    showInstantSheet = true
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
                            scope.launch {
                                driveRepo.createNewFolder(activeFolderId, newFolderName.trim())
                                loadFiles()
                            }
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
                            renameTargetFile = null
                            scope.launch {
                                driveRepo.renameItem(id, renameNewName.trim())
                                loadFiles()
                            }
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
}

/**
 * 带 Spoiler 防窥模糊与卡片投影的大图卡片视图，利用 Coil 3 加载缩略图
 */
@Composable
fun ShadowFileCard(
    file: FileStat,
    isSpoilerBlurred: Boolean = false,
    isHighlighted: Boolean = false,
    onToggleSpoiler: () -> Unit = {},
    onClick: () -> Unit,
    onDownload: () -> Unit = {},
    onDownloadSegment: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (file.thumbnailLink.isNotEmpty()) 4.dp else 1.dp,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
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
                    if (isSpoilerBlurred) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                            modifier = Modifier
                                .clickable { onToggleSpoiler() }
                                .padding(8.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Visibility,
                                    contentDescription = "查看预览",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "已遮蔽",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
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
                Column(modifier = Modifier.weight(1f)) {
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
                                    text = "刚秒传",
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

                var showCardMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { showCardMenu = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "更多",
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
                            val isVideo = file.name.endsWith(".mp4", ignoreCase = true) ||
                                file.name.endsWith(".mkv", ignoreCase = true) ||
                                file.name.endsWith(".mov", ignoreCase = true) ||
                                file.name.endsWith(".avi", ignoreCase = true) ||
                                file.name.endsWith(".webm", ignoreCase = true) ||
                                file.name.endsWith(".ts", ignoreCase = true)
                            if (isVideo) {
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

/**
 * 嵌入在 BottomSheet 里的秒传与磁力确认工作台
 * 默认保存至 My Packs，提供全量文件勾选与秒传/离线操作
 */
@Composable
fun InstantSheetContent(
    initialMagnet: String = "",
    onDismiss: () -> Unit,
    onSuccess: (createdIds: List<String>, targetBread: PathBreadcrumb) -> Unit,
    onOfflineTaskCreated: (folderName: String) -> Unit,
) {
    val driveRepo = PikoApplication.instance.driveRepository
    val instantRepo = PikoApplication.instance.instantMagnetRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var magnetInput by remember { mutableStateOf(initialMagnet) }
    var isResolving by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var resolutionResult by remember { mutableStateOf<MagnetResolutionResult?>(null) }
    var items by remember { mutableStateOf<List<InstantFileItem>>(emptyList()) }
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var targetBreadcrumb by remember { mutableStateOf<PathBreadcrumb?>(null) }

    fun doResolve(magnet: String) {
        val trimmed = magnet.trim()
        if (trimmed.startsWith("magnet:?xt=urn:btih:") || trimmed.length == 40 || trimmed.startsWith("magnet:")) {
            isResolving = true
            errorMsg = null
            scope.launch {
                val formattedMagnet = if (!trimmed.startsWith("magnet:")) "magnet:?xt=urn:btih:$trimmed" else trimmed
                val result = instantRepo.resolve(formattedMagnet)
                isResolving = false
                result.onSuccess { data ->
                    if (data == null) {
                        errorMsg = "PikPak 索引暂未收录该资源，可直接提交云端离线任务"
                    } else {
                        resolutionResult = data
                        items = data.items
                        selectedIndices = data.items.mapIndexedNotNull { idx, item ->
                            if (item.isSelected) idx else null
                        }.toSet()
                    }
                }.onFailure { err ->
                    errorMsg = "解析失败: ${err.localizedMessage}"
                }
            }
        }
    }

    // 默认加载或获取 "My Packs" 目录
    LaunchedEffect(Unit) {
        val packs = driveRepo.getOrCreateMyPacksFolder().getOrNull()
        targetBreadcrumb = packs ?: PathBreadcrumb("", "My Packs")
    }

    // 若有初始外部传入磁链，自动触发解析
    LaunchedEffect(initialMagnet) {
        if (initialMagnet.isNotBlank()) {
            magnetInput = initialMagnet
            doResolve(initialMagnet)
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
                    text = "默认存至 My Packs，毫秒级探测云端秒传与离线下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 磁力输入框
        OutlinedTextField(
            value = magnetInput,
            onValueChange = { magnetInput = it },
            label = { Text("Magnet 磁力链接") },
            placeholder = { Text("magnet:?xt=urn:btih:...") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.largeIncreased,
            maxLines = 2,
            trailingIcon = {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!clip.isNullOrBlank()) {
                        magnetInput = clip.trim()
                        doResolve(clip.trim())
                    }
                }) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                }
            },
        )

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = { doResolve(magnetInput) },
            enabled = !isResolving && magnetInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (isResolving) {
                PikoLoadingIndicator(size = 20.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("正在毫秒级探测云端索引...")
            } else {
                Icon(Icons.Outlined.Bolt, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("解析磁力资源")
            }
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

            // 资源标题卡片与目标目录提示
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = result.resource.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
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
                                    text = "目标：${targetBreadcrumb?.name ?: "My Packs"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = LocalFixedColors.current.InstantMatchGreen,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "秒传 ${result.instantReadyCount} / ${result.totalCount} 项",
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalFixedColors.current.InstantMatchGreen,
                            )
                        }
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

                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = if (item.isInstantReady) LocalFixedColors.current.InstantMatchGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    text = if (item.isInstantReady) "秒传" else "需离线",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.isInstantReady) LocalFixedColors.current.InstantMatchGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 保存与离线按钮
            val readySelectedItems = selectedIndices.map { items[it] }.filter { it.isInstantReady }
            val hasSelected = selectedIndices.isNotEmpty()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        if (readySelectedItems.isNotEmpty()) {
                            isSaving = true
                            scope.launch {
                                val targetBread = targetBreadcrumb ?: driveRepo.getOrCreateMyPacksFolder().getOrDefault(PathBreadcrumb("", "My Packs"))
                                val saveRes = instantRepo.instantSave(readySelectedItems, targetBread.id)
                                isSaving = false
                                saveRes.onSuccess { createdIds ->
                                    driveRepo.navigateToFolder(targetBread)
                                    onSuccess(createdIds, targetBread)
                                }.onFailure {
                                    errorMsg = "秒传保存失败: ${it.localizedMessage}"
                                }
                            }
                        }
                    },
                    enabled = !isSaving && readySelectedItems.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (isSaving) {
                        PikoLoadingIndicator(size = 18.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("保存中...")
                    } else {
                        Icon(Icons.Outlined.Bolt, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("秒传到 My Packs (${readySelectedItems.size})")
                    }
                }

                OutlinedButton(
                    onClick = {
                        isSaving = true
                        scope.launch {
                            val targetBread = targetBreadcrumb ?: driveRepo.getOrCreateMyPacksFolder().getOrDefault(PathBreadcrumb("", "My Packs"))
                            val taskRes = instantRepo.enqueueOfflineTask(magnetInput.trim(), targetBread.id)
                            isSaving = false
                            taskRes.onSuccess {
                                driveRepo.navigateToFolder(targetBread)
                                onOfflineTaskCreated(targetBread.name)
                            }.onFailure {
                                errorMsg = "离线任务提交失败: ${it.localizedMessage}"
                            }
                        }
                    },
                    enabled = !isSaving && (hasSelected || magnetInput.isNotBlank()),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("离线下载")
                }
            }
        } ?: run {
            // 当未解析或未命中云端索引时，如果输入了磁力，也可以直接提交离线任务
            if (magnetInput.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        isSaving = true
                        scope.launch {
                            val targetBread = targetBreadcrumb ?: driveRepo.getOrCreateMyPacksFolder().getOrDefault(PathBreadcrumb("", "My Packs"))
                            val taskRes = instantRepo.enqueueOfflineTask(magnetInput.trim(), targetBread.id)
                            isSaving = false
                            taskRes.onSuccess {
                                driveRepo.navigateToFolder(targetBread)
                                onOfflineTaskCreated(targetBread.name)
                            }.onFailure {
                                errorMsg = "离线任务提交失败: ${it.localizedMessage}"
                            }
                        }
                    },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("直接提交云端离线到 My Packs")
                }
            }
        }
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

