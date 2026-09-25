package dev.piko.ui.screens.trash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.TrashScreenState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.adaptive.readableSidePadding
import dev.piko.ui.components.FileLeadingVisual
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.FileListSkeleton
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.FirstScreenState
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.RefreshBox
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.displayTitle
import dev.piko.ui.components.metaParts
import io.github.nihildigit.pikpak.FileStat

/**
 * 彻底删除的待确认请求。清空回收站与逐项删除共用同一个 AlertDialog，
 * 仅文案不同，因此用一个字段区分来源，而不是两套对话框状态。
 */
private data class PermanentDeleteRequest(
    val ids: List<String>,
    val isEmptyingTrash: Boolean = false,
)

/**
 * 回收站页面。
 *
 * 展示云端回收站内容，支持恢复、彻底删除、批量操作与清空回收站。
 * 列表项不复用 FileItemRow：后者的操作是下载、重命名、移入回收站，
 * 在回收站语境下都无效。行的外观规则与 FileItemRow 相同。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val isFabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    // 列表、多选与恢复删除动作都在共享状态类里，这里只剩 Material 的布局与外观
    val services = LocalPikoServices.current
    val state = remember { TrashScreenState(services.driveRepository, scope) }
    val files = state.files
    val isActionRunning = state.isActionRunning
    val isSelectionMode = state.isSelectionMode
    val selectedFileIds = state.selectedFileIds

    // 回收站里的缩略图同样受防窥开关约束。揭示只在本页内有效，是纯视图状态
    val isSpoilerBlurEnabled by services.preferences.spoilerBlurFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val revealedIds = remember { mutableStateListOf<String>() }

    var deleteRequest by remember { mutableStateOf<PermanentDeleteRequest?>(null) }

    val exitSelection = { state.exitSelection() }
    val restoreFiles = { ids: List<String> -> state.restore(ids) }
    val deleteFiles = { ids: List<String> -> state.deletePermanently(ids) }

    LaunchedEffect(state) {
        state.load()
        state.messages.collect { snackbarHostState.showSnackbar(it, withDismissAction = true) }
    }

    BackHandler(enabled = isSelectionMode) {
        exitSelection()
    }

    val topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(topBarScrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            PikoTopBar(
                scrollBehavior = topBarScrollBehavior,
                title = if (isSelectionMode) "已选择 ${selectedFileIds.size} 项" else "回收站",
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = exitSelection) {
                            Icon(Icons.Outlined.Close, contentDescription = "退出多选")
                        }
                    } else {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = { state.toggleSelectAll() }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "全选")
                        }
                        IconButton(
                            onClick = { restoreFiles(selectedFileIds.toList()) },
                            enabled = selectedFileIds.isNotEmpty() && !isActionRunning,
                        ) {
                            Icon(Icons.Outlined.RestoreFromTrash, contentDescription = "恢复所选")
                        }
                        IconButton(
                            onClick = { deleteRequest = PermanentDeleteRequest(selectedFileIds.toList()) },
                            enabled = selectedFileIds.isNotEmpty() && !isActionRunning,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteForever,
                                contentDescription = "彻底删除所选",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        // 清空是本页唯一的页面级动作，放在 FAB 而不是藏进溢出菜单。多选只经长按进入，与网盘页一致，
        // 不再在顶栏另设入口
        floatingActionButton = {
            if (!isSelectionMode && files.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        deleteRequest = PermanentDeleteRequest(ids = files.map { it.id }, isEmptyingTrash = true)
                    },
                    icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                    text = { Text("清空回收站") },
                    expanded = isFabExpanded,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.large,
                )
            }
        },
    ) { innerPadding ->
        // 宽窗口里行内容收窄居中，列表本身仍铺满，两侧空白处也能滚动
        var containerWidth by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        val sidePadding = readableSidePadding(containerWidth)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
                .consumeWindowInsets(innerPadding)
                .onSizeChanged { containerWidth = with(density) { it.width.toDp() } },
        ) {
            FirstScreenState(
                isLoading = state.isLoading,
                error = state.loadError,
                isEmpty = files.isEmpty(),
                onRetry = { state.load() },
                skeleton = { FileListSkeleton(Modifier.padding(horizontal = sidePadding)) },
            ) {
                RefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { state.load(refresh = true) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 空态也放进 LazyColumn，否则没有可滚动的子项，下拉刷新在空回收站里无法触发
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = sidePadding,
                            end = sidePadding,
                            // 末项上方留出 FAB 的高度，否则最后一行被它盖住
                            bottom = innerPadding.calculateBottomPadding() + 88.dp,
                        ),
                    ) {
                        if (files.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillParentMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PikoEmptyState(
                                        title = "回收站为空",
                                        description = "移入回收站的文件会显示在这里，可随时恢复或彻底删除",
                                    )
                                }
                            }
                        } else {
                            items(
                                items = files,
                                key = { it.id },
                                contentType = { if (it.isFolder) "folder" else "file" },
                            ) { file ->
                                val isSelected = selectedFileIds.contains(file.id)
                                TrashItemRow(
                                    file = file,
                                    isSelectionMode = isSelectionMode,
                                    isSelected = isSelected,
                                    previewHidden = if (isSpoilerBlurEnabled && file.thumbnailLink.isNotEmpty()) {
                                        file.id !in revealedIds
                                    } else {
                                        null
                                    },
                                    onTogglePreview = {
                                        if (!revealedIds.remove(file.id)) revealedIds.add(file.id)
                                    },
                                    onLongClick = { state.enterSelection(file.id) },
                                    onSelectToggle = { selected -> state.setSelected(file.id, selected) },
                                    onRestore = { restoreFiles(listOf(file.id)) },
                                    onDeleteForever = {
                                        deleteRequest = PermanentDeleteRequest(listOf(file.id))
                                    },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { deleteRequest = null },
            title = { Text(if (request.isEmptyingTrash) "清空回收站" else "彻底删除") },
            text = {
                Text(
                    if (request.isEmptyingTrash) {
                        "将彻底删除回收站中的全部 ${request.ids.size} 项，删除后无法恢复。"
                    } else {
                        "将彻底删除所选的 ${request.ids.size} 项，删除后无法恢复。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ids = request.ids
                        deleteRequest = null
                        deleteFiles(ids)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("彻底删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRequest = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 回收站列表项，外观与网盘列表同为 [FileListItem]。
 *
 * 回收站里的文件不能打开，单击整行与点更多按钮一样，打开详情面板，恢复与彻底删除都在里面；
 * 单击不直接执行其中任何一项，避免误触。面板顶部同时是长文件名唯一读得全的地方。
 */
@Composable
private fun TrashItemRow(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    previewHidden: Boolean?,
    onTogglePreview: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDetails by remember { mutableStateOf(false) }
    val metaParts = trashMetaParts(file)

    FileListItem(
        headline = file.displayTitle(),
        headlineFontWeight = if (file.isFolder) FontWeight.Medium else null,
        leading = { FileLeadingVisual(file = file, isSpoilerBlurred = previewHidden == true) },
        supporting = { MetaRow(parts = metaParts) },
        onClick = { showDetails = true },
        onMoreClick = { showDetails = true },
        onLongClick = onLongClick,
        isSelectionMode = isSelectionMode,
        isSelected = isSelected,
        onSelectToggle = onSelectToggle,
        modifier = modifier,
    )

    if (showDetails) {
        ItemDetailsSheet(
            title = file.name,
            headerIcon = { FileTypeIcon(file = file, iconSize = 24.dp, modifier = Modifier.fillMaxSize()) },
            metaParts = metaParts,
            onDismiss = { showDetails = false },
            actions = buildList {
                if (previewHidden != null) {
                    add(
                        SheetAction(
                            icon = if (previewHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            label = if (previewHidden) "显示预览" else "隐藏预览",
                            onClick = onTogglePreview,
                        ),
                    )
                }
                add(SheetAction(Icons.Outlined.RestoreFromTrash, "恢复", onRestore))
                add(SheetAction(Icons.Outlined.DeleteForever, "彻底删除", onDeleteForever, destructive = true))
            },
        )
    }
}

// delete_time 是服务端排定的彻底清除时间。实测为移入回收站后 15 天，但期限由服务端决定，
// 不在这里按固定天数推算
private fun trashMetaParts(file: FileStat): List<String> =
    file.metaParts(includeDate = false) +
        listOfNotNull(file.deleteTime.takeIf { it.isNotEmpty() }?.let { "将于 ${it.take(10)} 删除" })
