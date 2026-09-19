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
import dev.piko.desktop.ui.components.getFileIcon
import dev.piko.desktop.ui.components.isImageFile
import dev.piko.desktop.ui.components.isVideoFile
import dev.piko.desktop.ui.player.VideoPlayerWindow
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
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
    themeColors: Colors,
) {
    val scope = rememberCoroutineScope()

    // Folder Stack & Path
    val folderStack by repository.folderStackFlow.collectAsState()
    val currentCrumb = folderStack.lastOrNull() ?: PikoPathBreadcrumb("", "网盘")
    val parentId = currentCrumb.id

    var files by remember { mutableStateOf<List<FileStat>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var successMsg by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

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
    var deleteTargetFile by remember { mutableStateOf<FileStat?>(null) }
    var permanentDeleteTargetFile by remember { mutableStateOf<FileStat?>(null) }

    var query by remember { mutableStateOf("") }
    var showTrash by remember { mutableStateOf(false) }

    suspend fun reload() {
        isLoading = true
        val result: Result<List<FileStat>> = when {
            showTrash -> repository.trashFiles().onSuccess { files = it; error = null }
            query.isNotBlank() -> repository.search(query.trim()).onSuccess { files = it; error = null }
            else -> repository.listFiles(parentId = parentId, sortOrder = PikoFileSortOrder.NAME_ASC)
                .map { it.first }
        }
        result.onSuccess { files = it; error = null }
            .onFailure { error = it.message ?: "读取网盘失败" }
        isLoading = false
    }

    LaunchedEffect(repository, parentId, showTrash) {
        reload()
    }

    fun handleFileClick(file: FileStat) {
        if (file.isFolder) {
            repository.pushFolder(file.id, file.name)
        } else if (isVideoFile(file.name)) {
            activePlayingFile = file
        } else if (isImageFile(file.name) && file.thumbnailLink.isNotBlank()) {
            selectedImage = file
            imageScale = 1f
            imageOffsetX = 0f
            imageOffsetY = 0f
        } else {
            downloadCoordinator.enqueue(file)
            successMsg = "已加入下载任务：${file.name}"
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
                        onClick = {
                            repository.popFolder()
                        },
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
                    onClick = { scope.launch { reload() } },
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
                        query = ""
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
                            onClick = {
                                repository.popToBreadcrumb(index)
                            },
                        ) {
                            Text(crumb.name)
                        }
                    }
                }
            }
        }

        // Search Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索当前网盘文件…") },
                leadingIcon = { Icon(Icons.Regular.Search, contentDescription = "搜索", modifier = Modifier.size(16.dp)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { scope.launch { reload() } }) {
                Text("搜索")
            }
        }

        // Notifications
        error?.let {
            InfoBar(
                title = { Text("提示") },
                message = { Text(it) },
                severity = InfoBarSeverity.Critical,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                closeAction = {
                    InfoBarDefaults.CloseActionButton(onClick = { error = null })
                },
            )
        }
        successMsg?.let {
            InfoBar(
                title = { Text("操作成功") },
                message = { Text(it) },
                severity = InfoBarSeverity.Success,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                closeAction = {
                    InfoBarDefaults.CloseActionButton(onClick = { successMsg = null })
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
                    text = if (showTrash) "回收站为空" else "当前文件夹为空",
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
                                }

                                if (!file.isFolder && !showTrash) {
                                    SubtleButton(
                                        onClick = {
                                            downloadCoordinator.enqueue(file)
                                            successMsg = "已加入下载任务：${file.name}"
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
                                        onClick = {
                                            scope.launch {
                                                repository.restore(listOf(file.id))
                                                    .onSuccess {
                                                        successMsg = "已恢复文件：${file.name}"
                                                        reload()
                                                    }
                                                    .onFailure { error = it.message ?: "恢复失败" }
                                            }
                                        },
                                    ) {
                                        Text("恢复")
                                    }

                                    SubtleButton(
                                        onClick = {
                                            permanentDeleteTargetFile = file
                                        },
                                    ) {
                                        Text("删除")
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

                                if (!file.isFolder && !showTrash) {
                                    SubtleButton(
                                        onClick = {
                                            downloadCoordinator.enqueue(file)
                                            successMsg = "已加入下载任务：${file.name}"
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
                                        onClick = {
                                            scope.launch {
                                                repository.restore(listOf(file.id))
                                                    .onSuccess {
                                                        successMsg = "已恢复文件：${file.name}"
                                                        reload()
                                                    }
                                                    .onFailure { error = it.message ?: "恢复失败" }
                                            }
                                        },
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
                            scope.launch {
                                repository.createFolder(parentId, name)
                                    .onSuccess {
                                        newFolderName = ""
                                        showCreateFolder = false
                                        successMsg = "文件夹创建成功"
                                        reload()
                                    }
                                    .onFailure { error = it.message ?: "创建文件夹失败" }
                            }
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
                            scope.launch {
                                repository.rename(target.id, name)
                                    .onSuccess {
                                        renameTargetFile = null
                                        successMsg = "已重命名为: $name"
                                        reload()
                                    }
                                    .onFailure { error = it.message ?: "重命名失败" }
                            }
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

    // Move to Trash Confirmation
    deleteTargetFile?.let { target ->
        ContentDialog(
            title = "移入回收站",
            visible = true,
            primaryButtonText = "确定移入",
            closeButtonText = "取消",
            onButtonClick = { button ->
                when (button) {
                    ContentDialogButton.Primary -> {
                        scope.launch {
                            repository.trash(listOf(target.id))
                                .onSuccess {
                                    deleteTargetFile = null
                                    successMsg = "已将 ${target.name} 移入回收站"
                                    reload()
                                }
                                .onFailure { error = it.message ?: "操作失败" }
                        }
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
                        scope.launch {
                            repository.delete(listOf(target.id))
                                .onSuccess {
                                    permanentDeleteTargetFile = null
                                    successMsg = "已彻底删除 ${target.name}"
                                    reload()
                                }
                                .onFailure { error = it.message ?: "删除失败" }
                        }
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
