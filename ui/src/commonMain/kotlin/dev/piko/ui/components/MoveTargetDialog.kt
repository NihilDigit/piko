package dev.piko.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.piko.data.repository.PathBreadcrumb
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.WidthClass
import dev.piko.ui.adaptive.currentWidthClass
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

/**
 * 全屏目录选择器。维护自己的路径栈，不触碰 driveRepo.folderStackFlow，
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
    val content: @Composable () -> Unit = {
        FolderPickerContent(
            title = title,
            confirmLabel = confirmLabel,
            blockedFolderIds = blockedFolderIds,
            blockedFolderHint = blockedFolderHint,
            confirmBlockedReason = confirmBlockedReason,
            recentTargets = recentTargets,
            onDismiss = onDismiss,
            onConfirm = { path ->
                onConfirmPath(path)
                onConfirm(path.last().id, path.last().name)
            },
        )
    }
    // M3 的全屏对话框只用于 compact 窗口；更宽时铺满整个窗口反而难以聚焦，改为居中的基本对话框
    if (currentWidthClass() == WidthClass.Compact) {
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
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(0.9f)
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
            val childFolders = list.filter(FileStat::isFolder)
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
        // 回到顶部必须排在加载之后：加载期间显示的是全屏指示器，LazyColumn
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            PikoTopBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "取消")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            newFolderName = ""
                            showNewFolderDialog = true
                        },
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹")
                    }
                },
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
                onNewFolder = {
                    newFolderName = ""
                    showNewFolderDialog = true
                },
                onConfirm = { onConfirm(path) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            BreadcrumbBar(
                breadcrumbs = path.drop(1),
                onBreadcrumbClick = { index -> path = path.take(index + 1) },
            )
            // 面包屑固定、下面的列表滚动，两者之间没有容器可以区分，只能靠这条线。
            // 不用 alpha 兑色：对比度会随底下是 surface 还是对话框的容器色变，深色主题下几乎看不见
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Crossfade(
                targetState = isLoading,
                animationSpec = PikoMotion.StateCrossfadeSpec,
                label = "folder_picker_loading",
            ) { loading ->
                if (loading) {
                    FullScreenLoading()
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
                            bottom = innerPadding.calculateBottomPadding() + 8.dp,
                        ),
                    ) {
                        items(items = folders, key = { it.id }) { folder ->
                            val isBlocked = folder.id in blockedFolderIds
                            FolderPickerRow(
                                name = folder.name,
                                enabled = !isBlocked,
                                hint = if (isBlocked) blockedFolderHint else null,
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

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { if (!isCreatingFolder) showNewFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.largeIncreased,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newFolderName.isNotBlank() && !isCreatingFolder,
                    onClick = {
                        val name = newFolderName.trim()
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
                    },
                ) {
                    Text("创建")
                }
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
}

/** 底栏：最近目标、目标位置、新建与确认。最近目标放在这里，拇指够得着，点完就在确认键上方。 */
@Composable
private fun FolderPickerActionBar(
    recentTargets: List<List<PathBreadcrumb>>,
    currentId: String,
    onRecentSelect: (List<PathBreadcrumb>) -> Unit,
    pathLabel: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    disabledReason: String?,
    onNewFolder: () -> Unit,
    onConfirm: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
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
            FolderPickerActionContent(pathLabel, confirmLabel, confirmEnabled, disabledReason, onNewFolder, onConfirm)
        }
    }
}

@Composable
private fun FolderPickerActionContent(
    pathLabel: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    disabledReason: String?,
    onNewFolder: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "目标位置",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = pathLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (disabledReason != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = disabledReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onNewFolder,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    imageVector = Icons.Outlined.CreateNewFolder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("新建文件夹")
            }
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
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
    LazyRow(
        modifier = modifier.fillMaxWidth(),
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

@Composable
private fun FolderPickerRow(
    name: String,
    enabled: Boolean,
    hint: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
