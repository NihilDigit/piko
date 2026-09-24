package dev.piko.ui.screens.drive

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
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
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.ScrollAnchor
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.InstantSaveOutcome
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.components.BreadcrumbBar
import dev.piko.ui.components.FileNameField
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.MoveTargetDialog
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.SegmentDownloadSheet
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.screens.instant.InstantSheetContent
import dev.piko.ui.screens.instant.InstantSheetHandle
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 网盘主界面：目录导航、列表与海报墙两种视图、防窥遮蔽、秒传入口与批量操作。
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
    /** playlist 是当前列表里可播的视频，顺序与眼前看到的一致。 */
    onNavigateToVideoPlayer: (file: FileStat, playlist: List<FileStat>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val driveRepo = LocalPikoServices.current.driveRepository
    val instantRepo = LocalPikoServices.current.instantMagnetRepository
    val instantSession = LocalPikoServices.current.instantSession
    val downloadManager = LocalPikoServices.current.downloadManager
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 防窥遮蔽开关是渲染选择，不进共享状态
    val sessionManager = LocalPikoServices.current.preferences
    val isSpoilerBlurEnabled by sessionManager.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = true)

    val state = remember { DriveScreenState(driveRepo, sessionManager, scope) }

    LaunchedEffect(state) {
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    // 视图模式存进偏好，切 Tab 与重启后保持上次的选择
    // 初值同步读：异步给默认值的话，选了列表的用户每次进来都先闪一帧海报墙。DataStore 在
    // MainActivity 读外观时已载入，这里只是取内存里的值
    val initialPosterMode = remember { runBlocking { sessionManager.gridViewFlow.first() } }
    val isPosterMode by sessionManager.gridViewFlow.collectAsStateWithLifecycle(initialPosterMode)

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
    // 外部打开的磁力链是一次明确的新请求：开新会话并就地取走，面板收起后不再靠它续命
    val pendingMagnet by instantRepo.pendingMagnetFlow.collectAsStateWithLifecycle()
    LaunchedEffect(pendingMagnet) {
        val magnet = pendingMagnet
        if (!magnet.isNullOrBlank()) {
            instantSession.start(magnet)
            instantRepo.clearPendingMagnet()
        }
    }

    // 保存结果在这里收而不在面板里：面板可能正收起着
    val instantState = instantSession.state
    LaunchedEffect(instantState) {
        instantState?.outcomes?.collect { outcome ->
            instantSession.end()
            state.navigateToFolder(outcome.target)
            when (outcome) {
                is InstantSaveOutcome.InstantSaved -> {
                    state.highlight(outcome.createdIds.toSet())
                    snackbarHostState.showSnackbar("已保存 ${outcome.createdIds.size} 个文件", withDismissAction = true)
                }
                is InstantSaveOutcome.OfflineTaskCreated ->
                    snackbarHostState.showSnackbar("已加入离线任务", withDismissAction = true)
            }
        }
    }

    // 每个目录一份列表状态，按「列表此刻显示的目录」重建，初值取仓库里记下的位置：
    // 新目录的第一帧就落在该在的地方。共用一份再在加载后 scrollToItem 的话，第一帧会
    // 先停在上一个目录的位置上，返回上级也会带回子目录的偏移。
    val loadedFolderId = state.loadedFolderId
    val gridState = remember(loadedFolderId) {
        val anchor = loadedFolderId?.let(driveRepo::scrollAnchor)
        LazyStaggeredGridState(anchor?.index ?: 0, anchor?.offset ?: 0)
    }
    LaunchedEffect(gridState) {
        val folderId = loadedFolderId ?: return@LaunchedEffect
        snapshotFlow { ScrollAnchor(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
            .collect { driveRepo.saveScrollAnchor(folderId, it) }
    }
    var isFabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = isFabMenuExpanded) { isFabMenuExpanded = false }

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

    val platform = LocalPikoPlatform.current

    // 磁力链接与分享链接都只复制；复制完给个回执，剪贴板本身看不见
    fun copySource(file: FileStat) {
        val url = file.sourceUrl ?: return
        platform.copyToClipboard("来源链接", url)
        scope.launch { snackbarHostState.showSnackbar(if (url.startsWith("magnet:", true)) "已复制磁力链接" else "已复制分享链接", withDismissAction = true) }
    }

    fun enqueueDownload(file: FileStat) {
        downloadManager.enqueue(file)
        scope.launch { snackbarHostState.showSnackbar("已加入下载", withDismissAction = true) }
    }

    // 回调对象只建一次，列表项拿到的引用不变；外部传入的导航回调经 rememberUpdatedState 取最新值
    val navigateToPlayer by rememberUpdatedState(onNavigateToVideoPlayer)
    val callbacks = remember(state) {
        DriveItemCallbacks(
            onOpen = { file ->
                when {
                    file.isFolder -> state.openFolder(file.id, file.name)
                    file.isPlayableVideo() -> navigateToPlayer(file, state.displayedFiles.filter { it.isPlayableVideo() })
                    file.isPreviewableImage() && file.thumbnailLink.isNotBlank() -> previewImage = file
                    // 其余类型没有应用内的打开方式，单击等同于下载
                    else -> enqueueDownload(file)
                }
            },
            onMore = { actionTargetFile = it },
            onLongPress = { state.enterSelection(it.id) },
            onSelect = { file, selected -> state.setSelected(file.id, selected) },
            contextActions = { file ->
                fileActions(
                    file = file,
                    previewHidden = if (isSpoilerBlurEnabled && file.thumbnailLink.isNotEmpty()) {
                        file.id !in state.revealedFileIds
                    } else {
                        null
                    },
                    onTogglePreview = { state.toggleSpoiler(file.id) },
                    onDownload = { enqueueDownload(file) },
                    onDownloadSegment = { segmentTargetFile = file },
                    onRename = {
                        renameTargetFile = file
                        renameNewName = file.name
                    },
                    onMove = { moveTargetIds = setOf(file.id) },
                    onTrash = { state.moveToTrash(listOf(file.id)) },
                    onCopySource = { copySource(file) },
                    onOpenSource = { file.sourceUrl?.let(platform::openUrl) },
                )
            },
            onToggleSection = state::toggleSection,
            onFolderVisible = state::onFolderVisible,
        )
    }

    // 顶栏副标题：首个可见项之前最近的分区标题。停在文件夹或作品头上时取第一个分区
    val foldBanner = foldBannerOrNull(state)
    val leadingItemCount = driveLeadingItemCount(foldBanner != null)
    val currentSection by remember(gridState, leadingItemCount) {
        derivedStateOf {
            val headers = state.sectionHeaders
            val first = gridState.firstVisibleItemIndex - leadingItemCount
            (headers.lastOrNull { it.index <= first } ?: headers.firstOrNull())?.value?.menuLabel
        }
    }
    fun jumpToSection(position: Int) {
        val target = state.sectionHeaders.getOrNull(position) ?: return
        state.expandSection(target.value.blockId)
        scope.launch { gridState.animateScrollToItem(leadingItemCount + target.index) }
    }

    // 桌面快捷键。挂在页面根上的 onKeyEvent 收的是冒泡上来的事件：搜索框有焦点时，
    // 退格与 Ctrl+A 先由输入框处理，不会误删文件或全选列表
    val shortcutFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { shortcutFocus.requestFocus() } }
    fun handleShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val ctrl = event.isCtrlPressed
        when {
            ctrl && event.key == Key.F -> isSearchOpen = true
            event.key == Key.F5 || (ctrl && event.key == Key.R) -> state.load(refresh = true)
            ctrl && event.key == Key.A -> state.toggleSelectAll()
            event.key == Key.Delete && state.isSelectionMode && state.selectedFileIds.isNotEmpty() ->
                state.moveToTrash(state.selectedFileIds.toList())
            (event.key == Key.Backspace || (event.isAltPressed && event.key == Key.DirectionLeft)) &&
                folderStack.size > 1 -> state.navigateUp()
            else -> return false
        }
        return true
    }

    // 列表滚动后顶栏换上填充色与内容分开，M3 app bar 规范的滚动态
    val topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 当前目录名已在顶栏标题上，面包屑只列上级。一级目录的唯一上级是根，返回键已足够
    val ancestorCrumbs = folderStack.drop(1).dropLast(1)
    val breadcrumbs: @Composable () -> Unit = {
        if (ancestorCrumbs.isNotEmpty()) {
            BreadcrumbBar(
                breadcrumbs = ancestorCrumbs,
                endsWithCurrent = false,
                // 回调给的是完整路径栈的下标（首页按钮传 0），与 ancestorCrumbs 的偏移已在组件里处理
                onBreadcrumbClick = { index -> state.navigateToBreadcrumb(index) },
            )
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(shortcutFocus)
            .focusable()
            .onKeyEvent(::handleShortcut)
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (instantState != null && !instantSession.isSheetOpen) {
                InstantSheetHandle(
                    state = instantState,
                    onExpand = instantSession::reopen,
                    onClose = instantSession::end,
                )
            }
        },
        topBar = {
            when {
                state.isSelectionMode -> DriveSelectionTopBar(
                    scrollBehavior = topBarScrollBehavior,
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

                else -> DriveBrowseTopBar(
                    scrollBehavior = topBarScrollBehavior,
                    title = activeFolder.name,
                    currentSection = currentSection,
                    sections = state.sectionHeaders.map { it.value.menuLabel },
                    onSectionSelected = ::jumpToSection,
                    navigationIcon = if (folderStack.size > 1) {
                        {
                            TooltipIconButton(
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                label = "返回上一级",
                                onClick = { state.navigateUp() },
                                shortcut = "Backspace",
                            )
                        }
                    } else null,
                    actions = {
                        // 顶栏只留搜索：M3 顶栏放一到两个动作，新建与秒传同属「往网盘里添东西」，
                        // 一起收进 FAB 菜单；排序与视图切换作用于列表，放在列表页眉
                        TooltipIconButton(Icons.Outlined.Search, "搜索", { isSearchOpen = true }, shortcut = "Ctrl+F")
                        // 下拉刷新只在触屏上用得了；宽窗口多半用鼠标，给一个按钮
                        if (currentWidthClass() != WidthClass.Compact) {
                            TooltipIconButton(Icons.Outlined.Refresh, "刷新", { state.load(refresh = true) }, shortcut = "F5")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!state.isSelectionMode) {
                // FAB 菜单自带 16dp 的右边距与下边距，Scaffold 的 FAB 槽位又留了 16dp，
                // 不抵消的话按钮离屏幕角是 32dp。偏移而不是挪出槽位，系统栏避让仍由 Scaffold 处理
                FloatingActionButtonMenu(
                    expanded = isFabMenuExpanded,
                    modifier = Modifier.offset(x = 16.dp, y = 16.dp),
                    button = {
                        ToggleFloatingActionButton(
                            checked = isFabMenuExpanded,
                            onCheckedChange = { isFabMenuExpanded = it },
                        ) {
                            val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add } }
                            Icon(
                                imageVector = icon,
                                contentDescription = if (isFabMenuExpanded) "收起" else "添加",
                                modifier = Modifier.animateIcon({ checkedProgress }),
                            )
                        }
                    },
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = {
                            isFabMenuExpanded = false
                            instantSession.start()
                        },
                        icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                        text = { Text("添加链接") },
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            isFabMenuExpanded = false
                            newFolderName = ""
                            showNewFolderDialog = true
                        },
                        icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                        text = { Text("新建文件夹") },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .consumeWindowInsets(innerPadding),
        ) {
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
                        if (state.displayItems.isEmpty()) {
                            // 空目录没有列表页眉，面包屑单独放在空状态上方
                            breadcrumbs()
                            DriveEmptyState(state = state, modifier = Modifier.weight(1f))
                        } else {
                            DriveFileGrid(
                                items = state.displayItems,
                                folderView = { if (!state.isNameParsing) null else state.folderViews[it.id] },
                                isPosterMode = isPosterMode,
                                gridState = gridState,
                                isSelectionMode = state.isSelectionMode,
                                selectedIds = selectedIdSet,
                                highlightedIds = highlightedFileIds,
                                isBlurred = { isSpoilerBlurEnabled && it.id !in state.revealedFileIds },
                                hitLocations = state.hitLocations,
                                callbacks = callbacks,
                                bottomPadding = bottomPadding,
                                header = {
                                    // 面包屑随列表滚走，而不是钉在顶栏下方：顶栏滚动后换了填充色，
                                    // 钉住的面包屑会在它下面留一条底色不同的带子
                                    Column {
                                        breadcrumbs()
                                        // 起始只留 4dp：排序是 TextButton，自带 12dp 内边距，合起来图标落在 16dp
                                        // 页边距上。末端的视图切换是 ToggleButton，没有内边距，要给足 16dp
                                        Box(modifier = Modifier.padding(start = 4.dp, end = 16.dp)) {
                                            DriveListHeader(
                                                summary = searchSummary(state, displayedFiles),
                                                sortOrder = state.sortOrder,
                                                onSortChange = { state.changeSortOrder(it) },
                                                isPosterMode = isPosterMode,
                                                onTogglePosterMode = {
                                                    scope.launch { sessionManager.setGridViewEnabled(!isPosterMode) }
                                                },
                                            )
                                        }
                                    }
                                },
                                foldBanner = foldBanner,
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
            previewHidden = if (isSpoilerBlurEnabled && target.thumbnailLink.isNotEmpty()) {
                target.id !in state.revealedFileIds
            } else {
                null
            },
            // remember 住同一个 flow：每次重组新建的话，produceState 会把统计从头再跑一遍
            folderUsage = remember(target.id) { if (target.isFolder) driveRepo.folderUsage(target.id) else null },
            onTogglePreview = { state.toggleSpoiler(target.id) },
            onDismiss = { actionTargetFile = null },
            onDownload = { enqueueDownload(target) },
            onDownloadSegment = { segmentTargetFile = target },
            onRename = {
                renameTargetFile = target
                renameNewName = target.name
            },
            onMove = { moveTargetIds = setOf(target.id) },
            onTrash = { state.moveToTrash(listOf(target.id)) },
            onCopySource = { copySource(target) },
            onOpenSource = { target.sourceUrl?.let(platform::openUrl) },
        )
    }

    // 秒传面板。划走只是收起，会话还在，底部留把手，见 InstantSession
    if (instantState != null && instantSession.isSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = instantSession::collapse,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            InstantSheetContent(
                state = instantState,
                // 先收起面板：Android 上它是独立窗口，会盖在应用内的播放器上面
                onPreview = { fileId, fileName ->
                    instantSession.collapse()
                    navigateToPlayer(FileStat(id = fileId, name = fileName), emptyList())
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
                    snackbarHostState.showSnackbar("已加入段落下载", withDismissAction = true)
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

/**
 * 页眉左侧的说明，只在搜索时出现：全盘搜索是逐层遍历，需要告诉用户仍在进行、已找到多少。
 * 平时不显示条目计数，文件夹与文件的区分由各行的图标承担。
 */
private fun searchSummary(state: DriveScreenState, files: List<FileStat>): String? = when {
    state.isGlobalSearchActive ->
        if (state.isGlobalSearching) "全盘搜索中，已找到 ${files.size} 项" else "全盘找到 ${files.size} 项"
    state.searchQuery.isNotBlank() -> "当前文件夹找到 ${files.size} 项"
    else -> null
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
                        description = "可用右下角「添加链接」保存资源，或新建文件夹",
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
            FileNameField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                modifier = Modifier.fillMaxWidth(),
                onDone = { if (confirmEnabled) onConfirm() },
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
