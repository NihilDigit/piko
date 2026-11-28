package dev.piko.ui.components

import dev.piko.shared.i18n.enCount

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.data.FolderSearchHit
import dev.piko.shared.data.FolderSearchSnapshot
import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.adaptive.isHeightCompact
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.isEInk
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 目录选择器，compact 下全屏，更宽时是居中的基本对话框。维护自己的路径栈，不触碰 services.driveWorkspace.folderStackFlow，
 * 否则选目标会把网盘主界面的位置一并改掉。
 *
 * onConfirm 返回命令是否被接受；接受后才回传 onConfirmPath，用于记录最近目标。
 * 后续动作、刷新与提示由调用方负责。
 *
 * blockedFolderIds 里的目录既不能进入也不能选中，列表里置灰并显示 blockedFolderHint；
 * confirmBlockedReason 只管当前目录能不能确认，返回非空即禁用确认并把原因显示在底栏。
 * 两者分开是因为「移动」需要允许进入源目录（要穿过它去子目录）却不允许选中它。
 *
 * recentTargets 是最近用过的目标路径，排成一行 chip，点一下直接进到那一层；确认仍要再点一次，
 * 底栏先把完整路径摆出来，误点了还来得及。onConfirmPath 回传确认时的完整路径，供调用方记下来。
 */
@Composable
fun FolderPickerDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (targetId: String, targetName: String) -> Boolean,
    blockedFolderIds: Set<String> = emptySet(),
    blockedFolderHint: String? = null,
    confirmBlockedReason: (PathBreadcrumb) -> String? = { null },
    recentTargets: List<List<PathBreadcrumb>> = emptyList(),
    onConfirmPath: (List<PathBreadcrumb>) -> Unit = {},
    onRemoveRecent: ((targetId: String) -> Unit)? = null,
    onClearRecent: (() -> Unit)? = null,
) {
    // 高度 compact 时居中的对话框只剩两三行可滚，也全屏
    val fullscreen = currentWidthClass() == WidthClass.Compact || isHeightCompact()
    val content: @Composable () -> Unit = {
        FolderPickerContent(
            title = title,
            confirmLabel = confirmLabel,
            blockedFolderIds = blockedFolderIds,
            blockedFolderHint = blockedFolderHint,
            confirmBlockedReason = confirmBlockedReason,
            recentTargets = recentTargets,
            onRemoveRecent = onRemoveRecent,
            onClearRecent = onClearRecent,
            fullscreen = fullscreen,
            onDismiss = onDismiss,
            onConfirm = { path ->
                if (onConfirm(path.last().id, path.last().name)) onConfirmPath(path)
            },
        )
    }
    // M3 的全屏对话框只用于 compact 窗口；更宽时铺满整个窗口反而难以聚焦，改为居中的基本对话框
    if (fullscreen) {
        LocalPikoPlatform.current.FullscreenDialog(
            onDismiss = onDismiss,
            immersive = false,
            systemBarsVisible = true,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                // 底部不在这里让：底栏的底色要铺到手势横条下面，由底栏自己把内容让上去
                Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    content()
                }
            }
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            dev.piko.ui.platform.ModalCaptionBlocker()
            val topInset = dev.piko.ui.platform.LocalDesktopTitleBarInset.current
            androidx.compose.runtime.CompositionLocalProvider(dev.piko.ui.platform.LocalWindowCaption provides null) {
                // 高度写死比例：Android 的对话框按内容定高，换一层目录、文件夹数一变整个对话框就跳。
                // 底色照 M3 对话框取 surfaceContainerHigh：取 surface 时深色主题下与变暗的背景分不开。文件夹行因此高一级，见 FolderPickerRow
                Surface(
                    modifier = Modifier
                        .padding(top = topInset)
                        .widthIn(max = 680.dp)
                        .fillMaxWidth(0.92f)
                        .fillMaxHeight(0.85f),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 移动专用的薄包装：禁止选中源目录与待移动项自身，并记下移动历史。
 * 历史里去不了的目标（就是源目录，或路径穿过待移动的文件夹）不显示，点了也只能看到禁用的确认键。
 */
@Composable
fun MoveTargetDialog(
    itemCount: Int,
    movingIds: Set<String>,
    sourceParentId: String,
    onDismiss: () -> Unit,
    onConfirm: (targetId: String, targetName: String) -> Boolean,
) {
    val history = LocalPikoServices.current.moveHistory
    val recent by history.targets.collectAsState(initial = emptyList())
    val reachable = recent.filter { path -> path.last().id != sourceParentId && path.none { it.id in movingIds } }
    FolderPickerDialog(
        recentTargets = reachable,
        onConfirmPath = history::remember,
        onRemoveRecent = history::remove,
        onClearRecent = history::clear,
        title = tr("移动 $itemCount 项", "Move ${enCount(itemCount, "item", "items")}", "$itemCount 項目を移動", "${itemCount}개 항목 이동", "移動 $itemCount 項"),
        confirmLabel = tr("移动到此处", "Move here", "ここに移動", "여기로 이동", "移至此處"),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        blockedFolderIds = movingIds,
        blockedFolderHint = tr("待移动项", "Items to move", "移動する項目", "이동할 항목", "待移動項目"),
        confirmBlockedReason = { current ->
            when {
                current.id == sourceParentId -> tr("已在此文件夹中", "Already in this folder", "既にこのフォルダーにあります", "이미 이 폴더에 있습니다", "已在此資料夾中")
                current.id in movingIds -> tr("不能移动到自身", "Cannot move into itself", "自身には移動できません", "자기 자신으로 이동할 수 없습니다", "不能移動至自身")
                else -> null
            }
        },
    )
}

@Composable
fun CopyTargetDialog(
    itemCount: Int,
    copyingIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (targetId: String, targetName: String) -> Boolean,
) {
    val history = LocalPikoServices.current.moveHistory
    val recent by history.targets.collectAsState(initial = emptyList())
    FolderPickerDialog(
        title = tr("复制 $itemCount 项", "Copy ${enCount(itemCount, "item", "items")}", "$itemCount 項目をコピー", "${itemCount}개 항목 복사", "複製 $itemCount 項"),
        confirmLabel = tr("复制到此处", "Copy here", "ここにコピー", "여기로 복사", "複製至此處"),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        recentTargets = recent.filter { path -> path.none { it.id in copyingIds } },
        onConfirmPath = history::remember,
        onRemoveRecent = history::remove,
        onClearRecent = history::clear,
        blockedFolderIds = copyingIds,
        blockedFolderHint = tr("待复制项", "Items to copy", "コピーする項目", "복사할 항목", "待複製項目"),
        confirmBlockedReason = { current -> tr("不能复制到自身", "Cannot copy into itself", "自身にはコピーできません", "자기 자신으로 복사할 수 없습니다", "不能複製至自身").takeIf { current.id in copyingIds } },
    )
}

@Composable
private fun FolderPickerContent(
    title: String,
    confirmLabel: String,
    blockedFolderIds: Set<String>,
    blockedFolderHint: String?,
    confirmBlockedReason: (PathBreadcrumb) -> String?,
    recentTargets: List<List<PathBreadcrumb>>,
    fullscreen: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (path: List<PathBreadcrumb>) -> Unit,
    onRemoveRecent: ((targetId: String) -> Unit)? = null,
    onClearRecent: (() -> Unit)? = null,
) {
    val driveRepo = LocalPikoServices.current.driveRepository
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val searchListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val searchFocusRequester = remember { FocusRequester() }

    val rootName = tr("我的网盘", "My drive", "マイドライブ", "내 드라이브", "我的雲端硬碟")
    var path by remember { mutableStateOf(listOf(PathBreadcrumb("", rootName))) }
    LaunchedEffect(dev.piko.shared.i18n.PikoLocale.effectiveLanguage) {
        path = path.map { crumb ->
            if (crumb.id.isEmpty()) crumb.copy(name = rootName) else crumb
        }
    }
    val current = path.last()

    var searchQuery by remember { mutableStateOf("") }
    var searchSnapshot by remember { mutableStateOf(FolderSearchSnapshot()) }
    val isSearching = searchQuery.isNotBlank()

    var folders by remember { mutableStateOf<List<FileStat>>(emptyList()) }
    var pageToken by remember { mutableStateOf("") }
    var hasMore by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableStateOf(0) }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    var pendingFolderName by remember { mutableStateOf<String?>(null) }
    val preferences = LocalPikoServices.current.preferences
    val autoCleanNames by preferences.autoCleanNamesFlow.collectAsStateWithLifecycle(initialValue = false)
    val isSpoilerBlurEnabled by preferences.spoilerBlurFlow.collectAsStateWithLifecycle(initialValue = preferences.currentSnapshot.spoilerBlurEnabled)

    LaunchedEffect(searchQuery, reloadTrigger) {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            searchSnapshot = FolderSearchSnapshot()
            return@LaunchedEffect
        }
        delay(50)
        driveRepo.searchLocalFolders(trimmed).collect { snapshot ->
            searchSnapshot = snapshot
        }
    }

    fun selectSearchHit(hit: FolderSearchHit) {
        val rootCrumb = path.firstOrNull()?.takeIf { it.id.isEmpty() } ?: PathBreadcrumb("", rootName)
        path = listOf(rootCrumb) + hit.path
        searchQuery = ""
        focusManager.clearFocus()
    }

    suspend fun loadPage(reset: Boolean) {
        if (!reset && (isLoadingMore || !hasMore)) return
        if (reset) {
            folders = emptyList()
            pageToken = ""
            hasMore = true
            isLoading = true
        }
        isLoadingMore = true
        var token = pageToken
        var added = 0
        // 一页 100 条可能全是文件，只靠触底加载永远翻不到后面的文件夹，
        // 因此本轮零收获时继续向后翻，直到拿到文件夹或翻完为止。
        while (true) {
            val page = driveRepo.listFiles(parentId = current.id, pageToken = token).getOrElse { error ->
                loadError = error.message ?: tr("未知错误", "Unknown error", "不明なエラー", "알 수 없는 오류", "未知錯誤")
                isLoading = false
                isLoadingMore = false
                return
            }
            val (list, next) = page
            // 根目录里放同步设置的 .piko 不列出来，与网盘页同一条规则（DriveScreenState.withoutSyncFolder）
            val childFolders = list.filter { it.isFolder && !isPikoInternalFolder(it, current.id) }
            if (childFolders.isNotEmpty()) {
                folders = folders + childFolders
                added += childFolders.size
            }
            token = next
            if (next.isEmpty()) {
                hasMore = false
                break
            }
            if (added > 0) break
        }
        pageToken = token
        isLoading = false
        isLoadingMore = false
    }

    LaunchedEffect(current.id, reloadTrigger) {
        loadPage(reset = true)
        // 回到顶部必须排在加载之后：加载期间显示的是骨架，LazyColumn
        // 没有被组合，scrollToItem 会一直挂起等一个不会到来的布局，把加载也堵死。
        if (folders.isNotEmpty()) listState.scrollToItem(0)
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= folders.size - 3
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, isLoadingMore) {
        if (shouldLoadMore && hasMore && !isLoadingMore && !isLoading) {
            loadPage(reset = false)
        }
    }

    LaunchedEffect(loadError) {
        if (loadError == null) return@LaunchedEffect
        loadError = null
        val result = snackbarHostState.showSnackbar(
            message = tr("加载失败", "Failed to load", "読み込みに失敗しました", "불러오지 못했습니다", "載入失敗"),
            actionLabel = tr("重试", "Retry", "再試行", "다시 시도", "重試"),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            reloadTrigger++
        }
    }

    // 返回键分层：有搜索词时先退出搜索，再退回上一级，根目录才关闭选择器。
    BackHandler(enabled = searchQuery.isNotEmpty() || path.size > 1) {
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
            focusManager.clearFocus()
        } else {
            path = path.dropLast(1)
        }
    }

    val blockedReason = confirmBlockedReason(current)
    val confirmEnabled = !isLoading && blockedReason == null

    // 关闭照 M3：全屏形态（compact）左上角放关闭，浮着的对话框不画关闭，底栏「取消」在主按钮左边。
    // 新建文件夹只放顶栏：底栏再放一个就是同一动作的两个入口，还把确认键挤窄
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.F && (event.isCtrlPressed || event.isMetaPressed)) {
                    runCatching { searchFocusRequester.requestFocus() }
                    true
                } else {
                    false
                }
            },
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            PikoTopBar(
                title = title,
                navigationIcon = if (fullscreen) {
                    {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = tr("关闭", "Close", "閉じる", "닫기", "關閉"))
                        }
                    }
                } else {
                    null
                },
                actions = listOf(
                    iconBarItem(
                        icon = Icons.Outlined.CreateNewFolder,
                        label = tr("新建文件夹", "New folder", "新規フォルダー", "새 폴더", "新增資料夾"),
                        onClick = {
                            newFolderName = ""
                            showNewFolderDialog = true
                        },
                        priority = PinnedPriority,
                    ),
                ),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            FolderPickerActionBar(
                onRemoveRecent = onRemoveRecent,
                onClearRecent = onClearRecent,
                recentTargets = recentTargets,
                currentId = current.id,
                onRecentSelect = { target ->
                    path = target
                    if (searchQuery.isNotEmpty()) {
                        searchQuery = ""
                        focusManager.clearFocus()
                    }
                },
                pathLabel = path.joinToString(" / ") { it.name },
                confirmLabel = confirmLabel,
                confirmEnabled = confirmEnabled,
                disabledReason = blockedReason,
                onCancel = onDismiss.takeUnless { fullscreen },
                onConfirm = { onConfirm(path) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            FolderPickerSearchField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                fullscreen = fullscreen,
                focusRequester = searchFocusRequester,
                onSubmit = {
                    val selectable = searchSnapshot.hits.filter { hit -> hit.path.none { it.id in blockedFolderIds } }
                    if (selectable.size == 1) {
                        selectSearchHit(selectable.first())
                    } else {
                        focusManager.clearFocus()
                    }
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
            )

            // 搜索态收起面包屑，为命中列表腾出纵向空间
            AnimatedVisibility(
                visible = !isSearching,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                BreadcrumbBar(
                    breadcrumbs = path,
                    onBreadcrumbClick = { index -> path = path.take(index + 1) },
                )
            }

            val rowContainerColor = if (fullscreen) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }

            Crossfade(
                targetState = isSearching,
                animationSpec = LocalPikoMotion.current.stateCrossfade,
                label = "folder_picker_mode",
                modifier = Modifier.weight(1f),
            ) { searching ->
                if (searching) {
                    FolderSearchResultsList(
                        snapshot = searchSnapshot,
                        rootName = rootName,
                        listState = searchListState,
                        blockedFolderIds = blockedFolderIds,
                        blockedFolderHint = blockedFolderHint,
                        containerColor = rowContainerColor,
                        isSpoilerBlurred = isSpoilerBlurEnabled,
                        onSelect = ::selectSearchHit,
                    )
                } else {
                    Crossfade(
                        targetState = isLoading,
                        animationSpec = LocalPikoMotion.current.stateCrossfade,
                        label = "folder_picker_loading",
                    ) { loading ->
                        if (loading) {
                            FolderPickerSkeleton()
                        } else if (folders.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                PikoEmptyState(
                                    title = tr("此文件夹没有子文件夹", "No subfolders", "サブフォルダーはありません", "하위 폴더가 없습니다", "此資料夾沒有子資料夾"),
                                    description = tr("可直接选择当前文件夹，或新建子文件夹", "Choose this folder or create a subfolder", "このフォルダーを選択するか、新しいフォルダーを作成してください", "이 폴더를 선택하거나 하위 폴더를 새로 만드세요", "可直接選擇目前資料夾，或新增子資料夾"),
                                    icon = Icons.Outlined.Folder,
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = 8.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                            ) {
                                itemsIndexed(items = folders, key = { _, folder -> folder.id }) { index, folder ->
                                    val isBlocked = folder.id in blockedFolderIds
                                    FolderPickerRow(
                                        folder = folder,
                                        enabled = !isBlocked,
                                        hint = if (isBlocked) blockedFolderHint else null,
                                        shapes = ListItemDefaults.segmentedShapes(index = index, count = folders.size),
                                        // 全屏形态铺在 surface 上，照设置页取 surfaceContainer；浮着的对话框底是 surfaceContainerHigh，行再高一级
                                        containerColor = rowContainerColor,
                                        isSpoilerBlurred = isSpoilerBlurEnabled,
                                        onClick = { path = path + PathBreadcrumb(folder.id, folder.name) },
                                    )
                                }
                                if (hasMore) {
                                    item(key = "loading_more") {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(56.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            InlineLoadingIndicator()
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

    fun createFolder(name: String) {
        val parentId = current.id
        isCreatingFolder = true
        scope.launch {
            driveRepo.createNewFolder(parentId, name)
                .onSuccess { id ->
                    isCreatingFolder = false
                    showNewFolderDialog = false
                    path = path + PathBreadcrumb(id, name)
                }
                .onFailure { error ->
                    isCreatingFolder = false
                    showNewFolderDialog = false
                    snackbarHostState.showSnackbar(tr("新建文件夹失败", "Failed to create folder", "フォルダーを作成できませんでした", "폴더를 만들지 못했습니다", "新增資料夾失敗"), withDismissAction = true)
                }
        }
    }

    if (showNewFolderDialog) {
        val unfixable = isUnfixableDriveName(newFolderName)
        val nameFocus = remember { FocusRequester() }
        PikoDialog(
            onDismissRequest = { if (!isCreatingFolder) showNewFolderDialog = false },
            title = { Text(stringResource(Res.string.text_new_folder)) },
            text = {
                // 在对话框自己的组合里要焦点：放在外面时对话框的内容还没挂上
                LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
                Column {
                    Text(
                        text = tr("创建于 ${current.name}，创建后自动进入", "Create in ${current.name} and open it", "${current.name} に作成し、自動的に開きます", "${current.name}에 만들고 자동으로 열기", "建立於 ${current.name}，建立後自動進入"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text(stringResource(Res.string.text_folder_name)) },
                        singleLine = true,
                        enabled = !isCreatingFolder,
                        isError = unfixable,
                        supportingText = {
                            Text(driveNameHint(newFolderName, autoCleanNames), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                        shape = MaterialTheme.shapes.largeIncreased,
                    )
                }
            },
            confirmButton = {
                PikoDialogConfirm(
                    label = tr("创建", "Create", "作成", "만들기", "建立"),
                    enabled = newFolderName.isNotBlank() && !isCreatingFolder && !unfixable,
                    onClick = { pendingFolderName = submitDriveName(newFolderName, autoCleanNames, ::createFolder) },
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewFolderDialog = false },
                    enabled = !isCreatingFolder,
                ) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }

    pendingFolderName?.let { name ->
        UnsupportedNameDialog(
            name = name,
            onUseCleaned = { cleaned ->
                pendingFolderName = null
                createFolder(cleaned)
            },
            onDismiss = { pendingFolderName = null },
        )
    }
}

/**
 * 底栏：最近目标一行，下面左边是目标位置与不能确认的原因，右边是操作。最近目标放在这里，拇指够得着，点完就在确认键上方。
 * [onCancel] 为 null 时不放「取消」，全屏形态由左上角的关闭代替。
 */
@Composable
private fun FolderPickerActionBar(
    recentTargets: List<List<PathBreadcrumb>>,
    currentId: String,
    onRecentSelect: (List<PathBreadcrumb>) -> Unit,
    pathLabel: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    disabledReason: String?,
    onCancel: (() -> Unit)?,
    onConfirm: () -> Unit,
    onRemoveRecent: ((targetId: String) -> Unit)? = null,
    onClearRecent: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (recentTargets.isNotEmpty()) {
            // 横滑的一行要贴到两边，不能放进下面带内边距的那一栏，否则滑到头会在边距处被裁掉
            RecentTargetsRow(
                onRemoveRecent = onRemoveRecent,
                onClearRecent = onClearRecent,
                targets = recentTargets,
                currentId = currentId,
                onSelect = onRecentSelect,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 不能确认的原因顶替「目标位置」这一行，不另起一行：底栏高度随之变化的话，进出源目录时列表会上下跳
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = disabledReason ?: tr("目标位置", "Destination", "保存先", "대상 위치", "目標位置"),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (disabledReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pathLabel,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
            }
            Button(onClick = onConfirm, enabled = confirmEnabled, shape = MaterialTheme.shapes.medium) {
                Text(confirmLabel)
            }
        }
    }
}

/** 最近用过的目标，一行横滑。正在看的那个标为选中，与底栏的目标位置对应。 */
@Composable
private fun RecentTargetsRow(
    targets: List<List<PathBreadcrumb>>,
    currentId: String,
    onSelect: (List<PathBreadcrumb>) -> Unit,
    modifier: Modifier = Modifier,
    onRemoveRecent: ((targetId: String) -> Unit)? = null,
    onClearRecent: (() -> Unit)? = null,
) {
    val rowState = rememberLazyListState()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.text_recent_destinations), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (onClearRecent != null) TextButton(onClick = onClearRecent) { Text(stringResource(Res.string.text_clear_history)) }
        }
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().verticalWheelScrollsRow(rowState),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = targets, key = { it.last().id }) { target ->
                FilterChip(
                    selected = target.last().id == currentId,
                    onClick = { onSelect(target) },
                    label = { Text(target.last().name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = onRemoveRecent?.let { remove -> {
                        IconButton(onClick = { remove(target.last().id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = tr("移除最近位置", "Remove recent location", "最近の場所から削除", "최근 위치에서 제거", "移除最近位置"), modifier = Modifier.size(16.dp))
                        }
                    } },
                    modifier = Modifier.widthIn(max = 240.dp),
                )
            }
        }
    }
}

/**
 * M3 Expressive Contained 风格常驻搜索胶囊：宽屏弹窗底色为 surfaceContainerHigh 时取 surfaceContainerHighest，
 * 全屏底色为 surface 时取 surfaceContainerHigh，聚焦态呈现 1.5dp 主色动态外环。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderPickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    fullscreen: Boolean,
    focusRequester: FocusRequester,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val isEInk = LocalAppearance.current.isEInk
    var focused by remember { mutableStateOf(false) }

    val focusBorderColor by animateColorAsState(
        targetValue = when {
            focused -> colors.primary
            isEInk -> colors.outline
            else -> Color.Transparent
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "folder_picker_search_ring",
    )
    val containerColor = when {
        isEInk -> Color.White
        fullscreen -> colors.surfaceContainerHigh
        else -> colors.surfaceContainerHighest
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(if (isEInk && !focused) 1.dp else 1.5.dp, focusBorderColor, CircleShape)
            .clip(CircleShape)
            .background(containerColor)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = if (focused) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Escape) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        if (query.isNotEmpty()) onQueryChange("") else focusManager.clearFocus()
                    }
                    true
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = tr("搜索目标文件夹", "Search destination folders", "移動先フォルダーを検索", "대상 폴더 검색", "搜尋目標資料夾"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = tr("清除搜索", "Clear search", "検索をクリア", "검색 지우기", "清除搜尋"),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderSearchResultsList(
    snapshot: FolderSearchSnapshot,
    rootName: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    blockedFolderIds: Set<String>,
    blockedFolderHint: String?,
    containerColor: Color,
    isSpoilerBlurred: Boolean,
    onSelect: (FolderSearchHit) -> Unit,
) {
    if (snapshot.hits.isEmpty() && !snapshot.isSyncing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            PikoEmptyState(
                title = tr("未找到匹配的文件夹", "No matching folders", "一致するフォルダーが見つかりません", "일치하는 폴더가 없습니다", "找不到符合的資料夾"),
                description = tr("可尝试其他关键词，或清空搜索手动浏览目录", "Try another keyword or clear search to browse manually", "別のキーワードを試すか、検索をクリアして手動で参照してください", "다른 키워드로 검색하거나 검색어를 지우고 직접 탐색하세요", "可嘗試其他關鍵字，或清除搜尋手動瀏覽目錄"),
                icon = Icons.Outlined.Search,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        itemsIndexed(items = snapshot.hits, key = { _, hit -> hit.folder.id }) { index, hit ->
            val isBlocked = hit.path.any { it.id in blockedFolderIds }
            val parentPath = if (hit.path.size > 1) {
                buildString {
                    append(rootName)
                    for (crumb in hit.path.dropLast(1)) {
                        append(" / ")
                        append(crumb.name)
                    }
                }
            } else {
                rootName
            }
            FolderPickerRow(
                folder = hit.folder,
                enabled = !isBlocked,
                hint = if (isBlocked) blockedFolderHint else null,
                subtitle = parentPath,
                shapes = ListItemDefaults.segmentedShapes(index = index, count = snapshot.hits.size),
                containerColor = containerColor,
                isSpoilerBlurred = isSpoilerBlurred,
                onClick = { onSelect(hit) },
            )
        }
        if (snapshot.isSyncing) {
            item(key = "folder_search_syncing") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InlineLoadingIndicator()
                    Text(
                        text = tr("正在同步本地目录索引，结果将自动补充", "Syncing local folder index, results will update automatically", "ローカルフォルダー索引を同期中、結果は自動的に追加されます", "로컬 폴더 색인을 동기화하는 중이며 결과가 자동으로 추가됩니다", "正在同步本機目錄索引，結果將自動補充"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 一个子文件夹，设置页那样的分段行，点了进去。 */
@Composable
fun FolderPickerRow(
    folder: FileStat,
    enabled: Boolean,
    hint: String?,
    shapes: ListItemShapes,
    containerColor: Color,
    isSpoilerBlurred: Boolean = false,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    val onSurface = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
    // 禁用时底色照旧，只淡化内容：默认的禁用底色在对话框底上是一块突兀的亮条
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = shapes.shape,
        color = containerColor,
        contentColor = onSurface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileListLeadingVisual(
                file = folder,
                size = 36.dp,
                isSpoilerBlurred = isSpoilerBlurred,
                modifier = if (enabled) Modifier else Modifier.alpha(contentAlpha),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = onSurface,
                    maxLines = if (subtitle != null) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CompositionLocalProvider(LocalContentColor provides onSurfaceVariant) {
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.labelMedium)
                } else {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

/**
 * 列目录时的骨架：一列 [FolderPickerRow]，边距、行距与分段圆角照抄上面的 LazyColumn。
 * 单行的列表项高 56dp（图标与一行正文加上下内边距不到这个数，由列表项的最小高度撑着）。
 * 整行画成一块色块，不在里面再画图标与文字的占位：浮着的对话框里行的底色就是骨架色，里面的占位会看不见。
 */
@Composable
private fun FolderPickerSkeleton(rows: Int = 8) {
    SkeletonGroup(Modifier.fillMaxSize().clipToBounds()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            repeat(rows) { index ->
                SkeletonBlock(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = ListItemDefaults.segmentedShapes(index = index, count = rows).shape,
                )
            }
        }
    }
}
