package dev.piko.ui.screens.trash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.PikoMotion
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

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
 * 列表项不复用 FileItemRow：后者的溢出菜单是下载/重命名/移入回收站，
 * 在回收站语境下这三项都无效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val driveRepo = PikoApplication.instance.driveRepository
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var files by remember { mutableStateOf<List<FileStat>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isActionRunning by remember { mutableStateOf(false) }

    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedFileIds = remember { mutableStateListOf<String>() }

    var showOverflowMenu by remember { mutableStateOf(false) }
    var deleteRequest by remember { mutableStateOf<PermanentDeleteRequest?>(null) }

    val loadFiles = {
        scope.launch {
            val result = driveRepo.getTrashFiles()
            isLoading = false
            isRefreshing = false
            result.onSuccess { list ->
                files = list
                // 刷新后已被移除的条目不应继续留在选中集合里，否则批量操作会带上失效 id
                selectedFileIds.retainAll(list.mapTo(hashSetOf()) { it.id })
            }.onFailure { error ->
                snackbarHostState.showSnackbar("加载失败: ${error.localizedMessage}")
            }
        }
    }

    val exitSelection = {
        isSelectionMode = false
        selectedFileIds.clear()
    }

    val restoreFiles = { ids: List<String> ->
        if (ids.isNotEmpty() && !isActionRunning) {
            isActionRunning = true
            scope.launch {
                val result = driveRepo.restoreFromTrash(ids)
                isActionRunning = false
                result.onSuccess {
                    exitSelection()
                    // 恢复出的文件回到原目录，网盘界面此刻仍在后台组合中，不会自己重新拉取
                    driveRepo.requestRefresh()
                    // showSnackbar 会挂起到提示消失，刷新必须排在它前面，否则列表要等几秒才更新
                    loadFiles()
                    snackbarHostState.showSnackbar("已恢复 ${ids.size} 项")
                }.onFailure { error ->
                    snackbarHostState.showSnackbar("恢复失败: ${error.localizedMessage}")
                }
            }
        }
        Unit
    }

    val deleteFiles = { ids: List<String> ->
        if (ids.isNotEmpty() && !isActionRunning) {
            isActionRunning = true
            scope.launch {
                val result = driveRepo.deletePermanently(ids)
                isActionRunning = false
                result.onSuccess {
                    exitSelection()
                    loadFiles()
                    snackbarHostState.showSnackbar("已彻底删除 ${ids.size} 项")
                }.onFailure { error ->
                    snackbarHostState.showSnackbar("删除失败: ${error.localizedMessage}")
                }
            }
        }
        Unit
    }

    LaunchedEffect(Unit) {
        loadFiles()
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
                        IconButton(onClick = {
                            if (selectedFileIds.size == files.size) {
                                selectedFileIds.clear()
                            } else {
                                selectedFileIds.clear()
                                selectedFileIds.addAll(files.map { it.id })
                            }
                        }) {
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
                            onClick = { isSelectionMode = true },
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
                targetState = isLoading,
                animationSpec = PikoMotion.StateCrossfadeSpec,
                label = "trash_loading",
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
                                    Box(modifier = Modifier.animateItem()) {
                                        TrashItemRow(
                                            file = file,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = isSelected,
                                            onClick = {
                                                if (isSelectionMode) {
                                                    if (isSelected) {
                                                        selectedFileIds.remove(file.id)
                                                    } else {
                                                        selectedFileIds.add(file.id)
                                                    }
                                                }
                                            },
                                            onLongClick = {
                                                isSelectionMode = true
                                                if (!isSelected) selectedFileIds.add(file.id)
                                            },
                                            onSelectToggle = { selected ->
                                                if (selected) {
                                                    selectedFileIds.add(file.id)
                                                } else {
                                                    selectedFileIds.remove(file.id)
                                                }
                                            },
                                            onRestore = { restoreFiles(listOf(file.id)) },
                                            onDeleteForever = {
                                                deleteRequest = PermanentDeleteRequest(listOf(file.id))
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

/**
 * 回收站列表项。视觉密度与 FileItemRow 对齐（min height 72dp、48dp 图标、horizontal 16dp padding），
 * 行尾操作换成恢复与彻底删除。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashItemRow(
    file: FileStat,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSelectToggle: (Boolean) -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) onSelectToggle(!isSelected) else onClick()
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectToggle(it) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                if (file.thumbnailLink.isNotEmpty()) {
                    AsyncImage(
                        model = file.thumbnailLink,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = MaterialTheme.shapes.small,
                        color = if (file.isFolder) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val icon = when {
                                file.isFolder -> Icons.Outlined.Folder
                                file.name.isPlayableVideo() -> Icons.Outlined.Movie
                                file.name.endsWith(".mp3", ignoreCase = true) ||
                                    file.name.endsWith(".flac", ignoreCase = true) ||
                                    file.name.endsWith(".wav", ignoreCase = true) -> Icons.Outlined.AudioFile
                                file.name.isPreviewableImage() -> Icons.Outlined.Image
                                file.name.endsWith(".zip", ignoreCase = true) ||
                                    file.name.endsWith(".rar", ignoreCase = true) ||
                                    file.name.endsWith(".7z", ignoreCase = true) -> Icons.Outlined.FolderZip
                                else -> Icons.Outlined.Description
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (file.isFolder) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // FileStat 没有独立的回收站时间字段，PikPak 在移入回收站时更新 modified_time，
                // 故以它作为删除时间
                val subtitle = buildString {
                    append(if (file.isFolder) "文件夹" else file.sizeBytes.toReadableSize())
                    if (file.modifiedTime.isNotEmpty()) {
                        append(" · 删除于 ")
                        append(file.modifiedTime.take(10))
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            }
        }
    }
}
