package dev.piko.ui.screens.trash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.PikoApplication
import dev.piko.ui.components.FileLeadingVisual
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.displayTitle
import dev.piko.ui.components.metaParts
import dev.piko.ui.theme.PikoMotion
import dev.piko.shared.state.TrashScreenState
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

    // 列表、多选与恢复删除动作都在共享状态类里，这里只剩 Material 的布局与外观
    val state = remember { TrashScreenState(PikoApplication.instance.driveRepository, scope) }
    val files = state.files
    val isActionRunning = state.isActionRunning
    val isSelectionMode = state.isSelectionMode
    val selectedFileIds = state.selectedFileIds

    // 回收站里的缩略图同样受防窥开关约束。揭示只在本页内有效，是纯视图状态
    val isSpoilerBlurEnabled by PikoApplication.instance.sessionManager.spoilerBlurFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val revealedIds = remember { mutableStateListOf<String>() }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var deleteRequest by remember { mutableStateOf<PermanentDeleteRequest?>(null) }

    val exitSelection = { state.exitSelection() }
    val restoreFiles = { ids: List<String> -> state.restore(ids) }
    val deleteFiles = { ids: List<String> -> state.deletePermanently(ids) }

    LaunchedEffect(state) {
        state.load()
        state.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    BackHandler(enabled = isSelectionMode) {
        exitSelection()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            PikoTopBar(
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
                    } else {
                        IconButton(
                            onClick = { state.enterSelection() },
                            enabled = files.isNotEmpty(),
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = "进入多选")
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("清空回收站", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.DeleteSweep,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    enabled = files.isNotEmpty() && !isActionRunning,
                                    onClick = {
                                        showOverflowMenu = false
                                        deleteRequest = PermanentDeleteRequest(
                                            ids = files.map { it.id },
                                            isEmptyingTrash = true,
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
            )
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
                label = "trash_loading",
            ) { loading ->
                if (loading) {
                    FullScreenLoading()
                } else {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { state.load(refresh = true) },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // 空态也放进 LazyColumn，否则没有可滚动的子项，下拉刷新在空回收站里无法触发
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                bottom = innerPadding.calculateBottomPadding() + 24.dp,
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
                                        isSpoilerBlurred = isSpoilerBlurEnabled && file.id !in revealedIds,
                                        onToggleSpoiler = {
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
                Button(
                    onClick = {
                        val ids = request.ids
                        deleteRequest = null
                        deleteFiles(ids)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
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

// 与 FileItemRow 相同：64dp 行高（两行列表 72dp 降两档密度），外侧 4dp 让变圆的容器不贴边，
// 内侧起始 12dp 使内容落在 16dp 页边距上，末端 0 让尾部图标按钮自带的内边距对齐页边距
private val TrashRowMinHeight = 64.dp
private val TrashRowContentPadding = PaddingValues(start = 12.dp, end = 0.dp, top = 8.dp, bottom = 8.dp)

/**
 * 回收站列表项。外观规则与 FileItemRow 一致：交互式 ListItem、名字两行、扩展名在副文本行、
 * 多选时整行是复选项且复选框占更多按钮的位置。行尾操作换成恢复与彻底删除。
 *
 * 回收站里的文件不能打开，平时单击整行等同于点更多按钮，弹出恢复与彻底删除；
 * 单击不直接执行其中任何一项，避免误触。
 */
@Composable
private fun TrashItemRow(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isSpoilerBlurred: Boolean,
    onToggleSpoiler: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    val itemModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp)
        .heightIn(min = TrashRowMinHeight)

    val leading: @Composable () -> Unit = {
        FileLeadingVisual(
            file = file,
            isSpoilerBlurred = isSpoilerBlurred,
            onToggleSpoiler = onToggleSpoiler,
        )
    }
    // FileStat 没有独立的回收站时间字段，PikPak 在移入回收站时更新 modified_time，
    // 故以它作为删除时间
    val supporting: @Composable () -> Unit = {
        val parts = file.metaParts(includeDate = false) +
            listOfNotNull(file.modifiedTime.takeIf { it.isNotEmpty() }?.let { "删除于 ${it.take(10)}" })
        MetaRow(parts = parts)
    }
    val headline: @Composable () -> Unit = {
        Text(
            text = file.displayTitle(),
            fontWeight = if (file.isFolder) FontWeight.Medium else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (isSelectionMode) {
        ListItem(
            checked = isSelected,
            onCheckedChange = onSelectToggle,
            modifier = itemModifier,
            leadingContent = leading,
            trailingContent = {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            },
            supportingContent = supporting,
            contentPadding = TrashRowContentPadding,
            content = headline,
        )
    } else {
        ListItem(
            onClick = { showMenu = true },
            modifier = itemModifier,
            leadingContent = leading,
            trailingContent = {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("恢复") },
                            leadingIcon = {
                                Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                onRestore()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("彻底删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.DeleteForever,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDeleteForever()
                            },
                        )
                    }
                }
            },
            supportingContent = supporting,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            },
            onLongClickLabel = "多选",
            contentPadding = TrashRowContentPadding,
            content = headline,
        )
    }
}
