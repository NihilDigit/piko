package dev.piko.ui.components

import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.adaptive.isHeightCompact
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalPikoMotion
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

/**
 * 目录选择器，compact 下全屏，更宽时是居中的基本对话框。维护自己的路径栈，不触碰 driveRepo.folderStackFlow，
 * 否则选目标会把网盘主界面的位置一并改掉。
 *
 * onConfirm 只回传选中的目标目录，后续动作、刷新与提示由调用方负责。
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
    onConfirm: (targetId: String, targetName: String) -> Unit,
    blockedFolderIds: Set<String> = emptySet(),
    blockedFolderHint: String? = null,
    confirmBlockedReason: (PathBreadcrumb) -> String? = { null },
    recentTargets: List<List<PathBreadcrumb>> = emptyList(),
    onConfirmPath: (List<PathBreadcrumb>) -> Unit = {},
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
            fullscreen = fullscreen,
            onDismiss = onDismiss,
            onConfirm = { path ->
                onConfirmPath(path)
                onConfirm(path.last().id, path.last().name)
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
            // 高度写死比例：Android 的对话框按内容定高，换一层目录、文件夹数一变整个对话框就跳。
            // 底色照 M3 对话框取 surfaceContainerHigh：取 surface 时深色主题下与变暗的背景分不开。文件夹行因此高一级，见 FolderPickerRow
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
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
    onConfirm: (targetId: String, targetName: String) -> Unit,
) {
    val history = LocalPikoServices.current.moveHistory
    val recent by history.targets.collectAsState(initial = emptyList())
    val reachable = recent.filter { path -> path.last().id != sourceParentId && path.none { it.id in movingIds } }
    FolderPickerDialog(
        recentTargets = reachable,
        onConfirmPath = history::remember,
        title = "移动 $itemCount 项",
        confirmLabel = "移动到这里",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
        blockedFolderIds = movingIds,
        blockedFolderHint = "待移动项",
        confirmBlockedReason = { current ->
            when {
                current.id == sourceParentId -> "已在此目录"
                current.id in movingIds -> "不能移动到自身"
                else -> null
            }
        },
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
) {
    val driveRepo = LocalPikoServices.current.driveRepository
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var path by remember { mutableStateOf(listOf(PathBreadcrumb("", "网盘"))) }
    val current = path.last()

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
    val autoCleanNames by LocalPikoServices.current.preferences.autoCleanNamesFlow.collectAsStateWithLifecycle(initialValue = false)

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
                loadError = error.localizedMessage ?: "未知错误"
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
            message = "加载失败",
            actionLabel = "重试",
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            reloadTrigger++
        }
    }

    // 返回键先退回上一级，与面包屑保持同一套导航；根目录才关闭选择器。
    BackHandler(enabled = path.size > 1) {
        path = path.dropLast(1)
    }

    val blockedReason = confirmBlockedReason(current)
    val confirmEnabled = !isLoading && blockedReason == null

    // 关闭照 M3：全屏形态（compact）左上角放关闭，浮着的对话框不画关闭，底栏「取消」在主按钮左边。
    // 新建文件夹只放顶栏：底栏再放一个就是同一动作的两个入口，还把确认键挤窄
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            PikoTopBar(
                title = title,
                navigationIcon = if (fullscreen) {
                    {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭")
                        }
                    }
                } else {
                    null
                },
                actions = listOf(
                    iconBarItem(
                        icon = Icons.Outlined.CreateNewFolder,
                        label = "新建文件夹",
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
                recentTargets = recentTargets,
                currentId = current.id,
                onRecentSelect = { target -> path = target },
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
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            // 面包屑固定、下面的列表滚动，不画分隔线：文件夹行各有底色，滚到面包屑下沿就被裁掉，层次已经分开
            BreadcrumbBar(
                breadcrumbs = path,
                onBreadcrumbClick = { index -> path = path.take(index + 1) },
            )

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
                            title = "此文件夹没有子文件夹",
                            description = "可直接选择这里，或新建文件夹后再选",
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
                            bottom = innerPadding.calculateBottomPadding() + 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        itemsIndexed(items = folders, key = { _, folder -> folder.id }) { index, folder ->
                            val isBlocked = folder.id in blockedFolderIds
                            FolderPickerRow(
                                name = folder.name,
                                enabled = !isBlocked,
                                hint = if (isBlocked) blockedFolderHint else null,
                                shapes = ListItemDefaults.segmentedShapes(index = index, count = folders.size),
                                // 全屏形态铺在 surface 上，照设置页取 surfaceContainer；浮着的对话框底是 surfaceContainerHigh，行再高一级
                                containerColor = if (fullscreen) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
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
                    snackbarHostState.showSnackbar("新建文件夹失败", withDismissAction = true)
                }
        }
    }

    if (showNewFolderDialog) {
        val unfixable = isUnfixableDriveName(newFolderName)
        val nameFocus = remember { FocusRequester() }
        PikoDialog(
            onDismissRequest = { if (!isCreatingFolder) showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                // 在对话框自己的组合里要焦点：放在外面时对话框的内容还没挂上
                LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
                Column {
                    Text(
                        text = "创建于 ${current.name}，创建后自动进入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("文件夹名称") },
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
                    label = "创建",
                    enabled = newFolderName.isNotBlank() && !isCreatingFolder && !unfixable,
                    onClick = { pendingFolderName = submitDriveName(newFolderName, autoCleanNames, ::createFolder) },
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewFolderDialog = false },
                    enabled = !isCreatingFolder,
                ) {
                    Text("取消")
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
) {
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
        if (recentTargets.isNotEmpty()) {
            // 横滑的一行要贴到两边，不能放进下面带内边距的那一栏，否则滑到头会在边距处被裁掉
            RecentTargetsRow(
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
                    text = disabledReason ?: "目标位置",
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
                TextButton(onClick = onCancel) { Text("取消") }
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
) {
    val rowState = rememberLazyListState()
    LazyRow(
        state = rowState,
        modifier = modifier.fillMaxWidth().verticalWheelScrollsRow(rowState),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = targets, key = { it.last().id }) { target ->
            FilterChip(
                selected = target.last().id == currentId,
                onClick = { onSelect(target) },
                label = { Text(target.last().name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}

/** 一个子文件夹，设置页那样的分段行，点了进去。 */
@Composable
private fun FolderPickerRow(
    name: String,
    enabled: Boolean,
    hint: String?,
    shapes: ListItemShapes,
    containerColor: Color,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        enabled = enabled,
        shapes = shapes,
        // 禁用时底色照旧，只淡化内容：默认的禁用底色在对话框底上是一块突兀的亮条
        colors = ListItemDefaults.segmentedColors(containerColor = containerColor, disabledContainerColor = containerColor),
        leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        trailingContent = {
            if (hint != null) {
                Text(hint, style = MaterialTheme.typography.labelMedium)
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        },
        content = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
    )
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
