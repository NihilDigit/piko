package dev.piko.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.piko.desktop.ui.components.formatBytes
import dev.piko.desktop.ui.components.SegmentDialog
import dev.piko.desktop.ui.components.formatSegmentTime
import dev.piko.desktop.ui.components.getFileIcon
import dev.piko.desktop.ui.components.isImageFile
import dev.piko.desktop.ui.components.isVideoFile
import dev.piko.desktop.ui.components.parseSegmentTime
import dev.piko.desktop.ui.player.VideoPlayerWindow
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.state.DriveScreenState
import dev.piko.shared.state.TrashScreenState
import io.github.composefluent.Colors
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarDefaults
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.ListItem
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Add
import io.github.composefluent.icons.regular.ArrowDownload
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync
import io.github.composefluent.icons.regular.Cut
import io.github.composefluent.icons.regular.Delete
import io.github.composefluent.icons.regular.Edit
import io.github.composefluent.icons.regular.Grid
import io.github.composefluent.icons.regular.List
import io.github.composefluent.icons.regular.Play
import io.github.composefluent.icons.regular.Search
import io.github.composefluent.surface.Card
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.launch

@Composable
fun DriveView(
    manager: PikoClientManager,
    repository: PikoDriveRepository,
    mediaRepository: PikoMediaRepository,
    downloadCoordinator: PikoDownloadCoordinator,
    preferences: PikoUserPreferences,
    themeColors: Colors,
    openTrashSignal: Int = 0,
) {
    val scope = rememberCoroutineScope()

    // 浏览、搜索、增删改的状态都在共享状态类里，Desktop 这边只剩 Fluent 的布局与外观。
    // Desktop 目前不提供排序入口，固定按名称升序。
    val state = remember(repository, preferences) {
        DriveScreenState(repository, preferences, scope, PikoFileSortOrder.NAME_ASC)
    }

    val folderStack by state.folderStack.collectAsState()

    // 回收站的列表与恢复、彻底删除动作在另一个共享状态类里，与 Android 的回收站页同一份逻辑
    val trashState = remember(repository) { TrashScreenState(repository, scope) }

    // 共享状态类的提示成功失败走同一条流，这里按文案里的「失败」分流到 InfoBar 的
    // 两种 severity。分成两个变量的话，一次操作的成功与失败提示会同时挂在界面上。
    var notice by remember { mutableStateOf<String?>(null) }

    // 视图模式：列表模式 (false) vs 网格模式 (true)
    var isGridView by remember { mutableStateOf(false) }

    // 独立播放器窗口状态
    var activePlayingFile by remember { mutableStateOf<FileStat?>(null) }

    // Image Zoom State
    var selectedImage by remember { mutableStateOf<FileStat?>(null) }
    var imageScale by remember { mutableFloatStateOf(1f) }
    var imageOffsetX by remember { mutableFloatStateOf(0f) }
    var imageOffsetY by remember { mutableFloatStateOf(0f) }
    val imageTransformState = rememberTransformableState { zoom, pan, _ ->
        imageScale = (imageScale * zoom).coerceIn(1f, 6f)
        imageOffsetX += pan.x
        imageOffsetY += pan.y
    }

    // Dialogs State
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renameTargetFile by remember { mutableStateOf<FileStat?>(null) }
    var renameNewName by remember { mutableStateOf("") }
    var segmentTargetFile by remember { mutableStateOf<FileStat?>(null) }
    var deleteTargetFile by remember { mutableStateOf<FileStat?>(null) }
    var permanentDeleteTargetFile by remember { mutableStateOf<FileStat?>(null) }

    var showTrash by remember { mutableStateOf(false) }

    // 「我的」页点回收站进来：信号每+1 翻一次回收站（值为 0 的初始态不触发）。
    LaunchedEffect(openTrashSignal) {
        if (openTrashSignal > 0) showTrash = true
    }

    LaunchedEffect(state, showTrash) {
        if (showTrash) trashState.load() else state.load()
    }

    LaunchedEffect(state) {
        state.messages.collect { notice = it }
    }

    LaunchedEffect(trashState) {
        trashState.messages.collect { notice = it }
    }

    val files = if (showTrash) trashState.files else state.displayedFiles
    // 回收站的刷新也用整页加载指示：它没有网盘列表那样的「内容可能不是最新的」提示条
    val isLoading = if (showTrash) trashState.isLoading || trashState.isRefreshing else state.isLoading

    fun handleFileClick(file: FileStat) {
        if (file.isFolder) {
            state.openFolder(file.id, file.name)
        } else if (isVideoFile(file.name)) {
            activePlayingFile = file
        } else if (isImageFile(file.name) && file.thumbnailLink.isNotBlank()) {
            selectedImage = file
            imageScale = 1f
            imageOffsetX = 0f
            imageOffsetY = 0f
        } else {
            downloadCoordinator.enqueue(file)
            notice = "已加入下载任务：${file.name}"
            WinRTSupport.showNotification("已添加下载任务", file.name)
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        // Toolbar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (folderStack.size > 1 && !showTrash) {
                    SubtleButton(
                        onClick = { state.navigateUp() },
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Regular.ArrowLeft, contentDescription = "返回上一级", modifier = Modifier.size(16.dp))
                            Text("返回")
                        }
                    }
                }
                Text(
                    text = if (showTrash) "回收站" else "我的网盘",
                    style = FluentTheme.typography.title,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 视图切换按钮 (List / Grid)
                Button(
                    onClick = { isGridView = !isGridView },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isGridView) Icons.Regular.List else Icons.Regular.Grid,
                            contentDescription = if (isGridView) "列表视图" else "网格视图",
                            modifier = Modifier.size(16.dp),
                        )
                        Text(if (isGridView) "列表" else "网格")
                    }
                }

                if (!showTrash) {
                    AccentButton(
                        onClick = { showCreateFolder = true },
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Regular.Add, contentDescription = "新建文件夹", modifier = Modifier.size(16.dp))
                            Text("新建文件夹")
                        }
                    }
                }
                Button(
                    onClick = {
                        if (showTrash) trashState.load(refresh = true) else state.load(refresh = true)
                    },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Regular.ArrowSync, contentDescription = "刷新", modifier = Modifier.size(16.dp))
                        Text("刷新")
                    }
                }
                Button(
                    onClick = {
                        state.updateSearchQuery("")
                        showTrash = !showTrash
                    },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Regular.Delete, contentDescription = if (showTrash) "返回网盘" else "回收站", modifier = Modifier.size(16.dp))
                        Text(if (showTrash) "返回网盘" else "回收站")
                    }
                }
            }
        }

        // Breadcrumb Bar
        if (!showTrash && folderStack.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                folderStack.forEachIndexed { index, crumb ->
                    if (index > 0) {
                        Text(
                            text = " / ",
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.tertiary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    val isCurrent = index == folderStack.lastIndex
                    if (isCurrent) {
                        Text(
                            text = crumb.name,
                            style = FluentTheme.typography.bodyStrong,
                            color = FluentTheme.colors.text.text.primary,
                        )
                    } else {
                        SubtleButton(
                            onClick = { state.navigateToBreadcrumb(index) },
                        ) {
                            Text(crumb.name)
                        }
                    }
                }
            }
        }

        // Search Bar
        // 回收站列表是它自己那份，过滤与全盘搜索都作用不到，搜索框在回收站模式下不出现。
        if (!showTrash) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = state.searchQuery,
                    onValueChange = { state.updateSearchQuery(it) },
                    placeholder = { Text("在当前文件夹内过滤…") },
                    leadingIcon = { Icon(Icons.Regular.Search, contentDescription = "搜索", modifier = Modifier.size(16.dp)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (state.isGlobalSearching) {
                    ProgressRing(size = ProgressRingSize.Small)
                    Button(onClick = { state.cancelGlobalSearch() }) {
                        Text("停止")
                    }
                } else {
                    Button(
                        onClick = { state.startGlobalSearch() },
                        disabled = state.searchQuery.isBlank(),
                    ) {
                        Text("全盘搜索")
                    }
                }
            }
        }

        // Notifications
        // loadError 与 notice 分开：前者说明列表里现在这份数据是旧的，要一直挂着直到加载
        // 成功；后者是一次性的操作结果。并成一条的话，下一次操作的提示会把陈旧提示冲掉。
        if (!showTrash) {
            state.loadError?.let {
                InfoBar(
                    title = { Text("内容可能不是最新的") },
                    message = { Text(it) },
                    severity = InfoBarSeverity.Warning,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    action = {
                        Button(onClick = { state.load(refresh = true) }) {
                            Text("重试")
                        }
                    },
                )
            }
        }
        notice?.let {
            val failed = it.contains("失败")
            InfoBar(
                title = { Text(if (failed) "提示" else "操作成功") },
                message = { Text(it) },
                severity = if (failed) InfoBarSeverity.Critical else InfoBarSeverity.Success,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                closeAction = {
                    InfoBarDefaults.CloseActionButton(onClick = { notice = null })
                },
            )
        }

        // File Content Area (List vs Grid)
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ProgressRing(size = ProgressRingSize.Large)
            }
        } else if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        showTrash -> "回收站为空"
                        state.isGlobalSearchActive -> if (state.isGlobalSearching) "正在全盘搜索…" else "全盘未找到匹配的文件"
                        state.searchQuery.isNotBlank() -> "当前文件夹内没有匹配的文件"
                        else -> "当前文件夹为空"
                    },
                    style = FluentTheme.typography.bodyLarge,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        } else if (isGridView) {
            // Fluent 风格 GridView 网格视图
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(files, key = { it.id }) { file ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { handleFileClick(file) },
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                        ) {
                            // 缩略图或图标展示框
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 10f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(FluentTheme.colors.controlAlt.tertiary),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isImageFile(file.name) && file.thumbnailLink.isNotBlank()) {
                                    AsyncImage(
                                        model = file.thumbnailLink,
                                        contentDescription = file.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Icon(
                                        imageVector = getFileIcon(file.name, file.isFolder),
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = if (file.isFolder) FluentTheme.colors.fillAccent.default else FluentTheme.colors.text.text.primary,
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // 名称与属性
                            Text(
                                text = file.name,
                                style = FluentTheme.typography.bodyStrong,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(Modifier.height(2.dp))

                            Text(
                                text = if (file.isFolder) "文件夹" else formatBytes(file.sizeBytes),
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                            )

                            // 全盘结果横跨多个目录，不显示所在位置的话同名文件分不清。
                            state.hitLocations[file.id]?.let { location ->
                                Text(
                                    text = location,
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.text.text.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            Spacer(Modifier.height(6.dp))

                            // 快捷操作栏
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isVideoFile(file.name)) {
                                    SubtleButton(
                                        onClick = { activePlayingFile = file },
                                    ) {
                                        Icon(Icons.Regular.Play, contentDescription = "播放", modifier = Modifier.size(14.dp))
                                    }
                                    SubtleButton(
                                        onClick = { segmentTargetFile = file },
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(Icons.Regular.Cut, contentDescription = "片段", modifier = Modifier.size(14.dp))
                                            Text("片段")
                                        }
                                    }
                                }

                                if (!file.isFolder && !showTrash) {
                                    SubtleButton(
                                        onClick = {
                                            downloadCoordinator.enqueue(file)
                                            notice = "已加入下载任务：${file.name}"
                                            WinRTSupport.showNotification("已添加下载任务", file.name)
                                        },
                                    ) {
                                        Icon(Icons.Regular.ArrowDownload, contentDescription = "下载", modifier = Modifier.size(14.dp))
                                    }
                                }

                                if (!showTrash) {
                                    SubtleButton(
                                        onClick = {
                                            renameTargetFile = file
                                            renameNewName = file.name
                                        },
                                    ) {
                                        Icon(Icons.Regular.Edit, contentDescription = "重命名", modifier = Modifier.size(14.dp))
                                    }

                                    SubtleButton(
                                        onClick = {
                                            deleteTargetFile = file
                                        },
                                    ) {
                                        Icon(Icons.Regular.Delete, contentDescription = "移至回收站", modifier = Modifier.size(14.dp))
                                    }
                                } else {
                                    SubtleButton(
                                        onClick = { trashState.restore(listOf(file.id)) },
                                        disabled = trashState.isActionRunning,
                                    ) {
                                        Text("恢复")
                                    }

                                    SubtleButton(
                                        onClick = {
                                            permanentDeleteTargetFile = file
                                        },
                                    ) {
                                        Text("彻底删除")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Fluent 风格 ListView 列表视图
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(files, key = { it.id }) { file ->
                    ListItem(
                        onClick = { handleFileClick(file) },
                        text = {
                            Column {
                                Text(
                                    text = file.name,
                                    style = FluentTheme.typography.bodyStrong,
                                )
                                if (!file.isFolder) {
                                    Text(
                                        text = formatBytes(file.sizeBytes),
                                        style = FluentTheme.typography.caption,
                                        color = FluentTheme.colors.text.text.secondary,
                                    )
                                }
                                state.hitLocations[file.id]?.let { location ->
                                    Text(
                                        text = location,
                                        style = FluentTheme.typography.caption,
                                        color = FluentTheme.colors.text.text.tertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        icon = {
                            if (isImageFile(file.name) && file.thumbnailLink.isNotBlank()) {
                                AsyncImage(
                                    model = file.thumbnailLink,
                                    contentDescription = file.name,
                                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(
                                    imageVector = getFileIcon(file.name, file.isFolder),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (file.isFolder) FluentTheme.colors.fillAccent.default else FluentTheme.colors.text.text.primary,
                                )
                            }
                        },
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isVideoFile(file.name)) {
                                    SubtleButton(
                                        onClick = { activePlayingFile = file },
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(Icons.Regular.Play, contentDescription = "播放", modifier = Modifier.size(14.dp))
                                            Text("播放")
                                        }
                                    }
                                }

                                if (isImageFile(file.name) && file.thumbnailLink.isNotBlank()) {
                                    SubtleButton(
                                        onClick = {
                                            selectedImage = file
                                            imageScale = 1f
                                            imageOffsetX = 0f
                                            imageOffsetY = 0f
                                        },
                                    ) {
                                        Text("预览")
                                    }
                                }

                                // 视频片段：起止时间切片，无损输出 MP4（与 Android 分段下载同语义）。
                                if (isVideoFile(file.name)) {
                                    SubtleButton(
                                        onClick = { segmentTargetFile = file },
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(Icons.Regular.Cut, contentDescription = "片段", modifier = Modifier.size(14.dp))
                                            Text("片段")
                                        }
                                    }
                                }

                                if (!file.isFolder && !showTrash) {
                                    SubtleButton(
                                        onClick = {
                                            downloadCoordinator.enqueue(file)
                                            notice = "已加入下载任务：${file.name}"
                                            WinRTSupport.showNotification("已添加下载任务", file.name)
                                        },
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(Icons.Regular.ArrowDownload, contentDescription = "下载", modifier = Modifier.size(14.dp))
                                            Text("下载")
                                        }
                                    }
                                }

                                if (!showTrash) {
                                    SubtleButton(
                                        onClick = {
                                            renameTargetFile = file
                                            renameNewName = file.name
                                        },
                                    ) {
                                        Icon(Icons.Regular.Edit, contentDescription = "重命名", modifier = Modifier.size(14.dp))
                                    }

                                    SubtleButton(
                                        onClick = {
                                            deleteTargetFile = file
                                        },
                                    ) {
                                        Icon(Icons.Regular.Delete, contentDescription = "移至回收站", modifier = Modifier.size(14.dp))
                                    }
                                } else {
                                    SubtleButton(
                                        onClick = { trashState.restore(listOf(file.id)) },
                                        disabled = trashState.isActionRunning,
                                    ) {
                                        Text("恢复")
                                    }

                                    SubtleButton(
                                        onClick = {
                                            permanentDeleteTargetFile = file
                                        },
                                    ) {
                                        Text("彻底删除")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // 弹出独立视频播放器窗口（支持手势触控）
    activePlayingFile?.let { videoFile ->
        VideoPlayerWindow(
            file = videoFile,
            mediaRepository = mediaRepository,
            themeColors = themeColors,
            onClose = { activePlayingFile = null },
            downloadCoordinator = downloadCoordinator,
            // 同目录里的视频都进播放列表，换片不用退回网盘。
            playlist = files.filter { isVideoFile(it.name) },
        )
    }

    // Image Zoom Dialog
    selectedImage?.let { image ->
        Dialog(
            onDismissRequest = { selectedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable { selectedImage = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = image.thumbnailLink,
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .clip(FluentTheme.shapes.overlay)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    imageScale = if (imageScale > 1f) 1f else 2f
                                    if (imageScale == 1f) {
                                        imageOffsetX = 0f
                                        imageOffsetY = 0f
                                    }
                                },
                            )
                        }
                        .transformable(imageTransformState)
                        .graphicsLayer {
                            scaleX = imageScale
                            scaleY = imageScale
                            translationX = imageOffsetX
                            translationY = imageOffsetY
                        },
                )
            }
        }
    }

    // Create Folder Dialog
    if (showCreateFolder) {
        ContentDialog(
            title = "新建文件夹",
            visible = showCreateFolder,
            primaryButtonText = "创建",
            closeButtonText = "取消",
            onButtonClick = { button ->
                when (button) {
                    ContentDialogButton.Primary -> {
                        val name = newFolderName.trim()
                        if (name.isNotEmpty()) {
                            state.createFolder(name)
                            newFolderName = ""
                            showCreateFolder = false
                        }
                    }
                    ContentDialogButton.Close -> {
                        showCreateFolder = false
                    }
                    else -> {}
                }
            },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入文件夹名称", style = FluentTheme.typography.caption)
                    TextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        placeholder = { Text("新建文件夹") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }

    // Rename Dialog
    renameTargetFile?.let { target ->
        ContentDialog(
            title = "重命名文件",
            visible = true,
            primaryButtonText = "保存",
            closeButtonText = "取消",
            onButtonClick = { button ->
                when (button) {
                    ContentDialogButton.Primary -> {
                        val name = renameNewName.trim()
                        if (name.isNotEmpty()) {
                            state.rename(target.id, name)
                            renameTargetFile = null
                        }
                    }
                    ContentDialogButton.Close -> {
                        renameTargetFile = null
                    }
                    else -> {}
                }
            },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入新的文件名称", style = FluentTheme.typography.caption)
                    TextField(
                        value = renameNewName,
                        onValueChange = { renameNewName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }

    // 视频片段下载：起止时间切片，无损输出 MP4。选区间在对话框里做（带双路预览），
    // 确认时重新拿播放链接（直链有时效），经 coordinator 走片段任务通道。
    segmentTargetFile?.let { target ->
        SegmentDialog(
            file = target,
            mediaRepository = mediaRepository,
            onDismiss = { segmentTargetFile = null },
            onConfirm = { startMs, endMs, label ->
                segmentTargetFile = null
                scope.launch {
                    mediaRepository.prepareMedia(target.id)
                        .onSuccess { info ->
                            val totalMs = info.durationSeconds * 1000L
                            if (totalMs > 0 && endMs > totalMs) {
                                notice = "结束时间超出片长（全片约 ${formatSegmentTime(totalMs)}）"
                            } else {
                                downloadCoordinator.enqueueSegment(
                                    target,
                                    startMs,
                                    endMs,
                                    label,
                                    info.currentUrl,
                                )
                                notice = "已加入片段任务：${target.name} [$label]"
                            }
                        }
                        .onFailure { notice = it.message ?: "获取播放链接失败" }
                }
            },
        )
    }

    // Move to Trash Confirmation
    deleteTargetFile?.let { target ->        ContentDialog(
            title = "移入回收站",
            visible = true,
            primaryButtonText = "确定移入",
            closeButtonText = "取消",
            onButtonClick = { button ->
                when (button) {
                    ContentDialogButton.Primary -> {
                        state.moveToTrash(listOf(target.id))
                        deleteTargetFile = null
                    }
                    ContentDialogButton.Close -> {
                        deleteTargetFile = null
                    }
                    else -> {}
                }
            },
            content = {
                Text("确定要将 \"${target.name}\" 移至回收站吗？您稍后可以在回收站中恢复它。")
            },
        )
    }

    // Permanent Delete Confirmation
    permanentDeleteTargetFile?.let { target ->
        ContentDialog(
            title = "彻底删除文件",
            visible = true,
            primaryButtonText = "彻底删除",
            closeButtonText = "取消",
            onButtonClick = { button ->
                when (button) {
                    ContentDialogButton.Primary -> {
                        permanentDeleteTargetFile = null
                        trashState.deletePermanently(listOf(target.id))
                    }
                    ContentDialogButton.Close -> {
                        permanentDeleteTargetFile = null
                    }
                    else -> {}
                }
            },
            content = {
                Text("警告：此操作不可撤销，文件 \"${target.name}\" 将从云端永久移除！")
            },
        )
    }
}
