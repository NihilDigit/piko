package dev.piko.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.PikoSeekableMediaData
import dev.piko.download.DownloadTask
import io.github.composefluent.FluentTheme
import io.github.composefluent.background.Mica
import io.github.composefluent.component.Button as FluentButton
import io.github.nihildigit.pikpak.FileStat
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.togglePlayWhenReady

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Piko",
    ) {
        FluentTheme {
            Mica(Modifier.fillMaxSize()) {
                val scope = rememberCoroutineScope()
                val manager = remember { PikoClientManager(FilePikoSessionStore(), scope) }
                val client by manager.currentClient.collectAsState()
                val initializing by manager.isInitializing.collectAsState()
                when {
                    initializing -> LoadingView()
                    client == null -> LoginView(manager)
                    else -> DriveView(manager, PikoDriveRepository(manager), PikoMediaRepository(manager))
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun LoginView(manager: PikoClientManager) {
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoggingIn by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Piko", style = MaterialTheme.typography.headlineLarge)
        Text("Windows 版 PikPak 客户端", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = account,
            onValueChange = { account = it },
            label = { Text("账号") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.55f),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.55f),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FluentButton(
            disabled = account.isBlank() || password.isEmpty() || isLoggingIn,
            onClick = {
                isLoggingIn = true
                error = null
                scope.launch {
                    manager.login(account.trim()) { password }.onFailure {
                        error = it.message ?: "登录失败"
                    }
                    isLoggingIn = false
                }
            },
        ) { Text(if (isLoggingIn) "登录中…" else "登录") }
    }
}

@Composable
@OptIn(ExperimentalMediampApi::class)
private fun DriveView(
    manager: PikoClientManager,
    repository: PikoDriveRepository,
    mediaRepository: PikoMediaRepository,
) {
    val scope = rememberCoroutineScope()
    val player = rememberMediampPlayer()
    val playerState by player.state.collectAsState()
    val settings = remember { DesktopSettingsStore() }
    val preferences = remember { DesktopPikoPreferences(settings) }
    val downloadManager = remember(manager) {
        PikoDownloadCoordinator(manager, preferences, DesktopPikoDownloadStorage(settings.downloadDirectory), scope)
    }
    val downloadTasks by downloadManager.tasks.collectAsState()
    var files by remember { mutableStateOf<List<FileStat>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var currentData by remember { mutableStateOf<PikoSeekableMediaData?>(null) }
    var openingFileId by remember { mutableStateOf<String?>(null) }
    var selectedImage by remember { mutableStateOf<FileStat?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var imageScale by remember { mutableStateOf(1f) }
    var imageOffsetX by remember { mutableStateOf(0f) }
    var imageOffsetY by remember { mutableStateOf(0f) }
    val imageTransformState = rememberTransformableState { zoom, pan, _ ->
        imageScale = (imageScale * zoom).coerceIn(1f, 6f)
        imageOffsetX += pan.x
        imageOffsetY += pan.y
    }
    var parentId by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var showTrash by remember { mutableStateOf(false) }
    var downloadingFileId by remember { mutableStateOf<String?>(null) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val latestData by rememberUpdatedState(currentData)

    DisposableEffect(player) {
        onDispose {
            latestData?.close()
            player.close()
        }
    }

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

    LaunchedEffect(repository, parentId, showTrash) { reload() }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("网盘", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FluentButton(onClick = { showCreateFolder = true }) { Text("新建文件夹") }
                FluentButton(
                    disabled = parentId.isEmpty(),
                    onClick = {
                        parentId = repository.popFolder()?.let { repository.folderStackFlow.value.lastOrNull()?.id ?: "" } ?: ""
                    },
                ) { Text("返回") }
                FluentButton(onClick = { scope.launch { reload() } }) { Text("刷新") }
                FluentButton(onClick = { scope.launch { manager.logout() } }) { Text("退出登录") }
                FluentButton(onClick = { showSettings = true }) { Text("设置") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索文件") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FluentButton(onClick = { scope.launch { reload() } }) { Text("搜索") }
            FluentButton(onClick = {
                query = ""
                showTrash = !showTrash
            }) { Text(if (showTrash) "返回网盘" else "回收站") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        downloadStatus?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        currentData?.let {
            Box(Modifier.fillMaxWidth().height(360.dp).padding(top = 16.dp)) {
                MediampPlayerSurface(player, Modifier.fillMaxSize())
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FluentButton(onClick = { player.togglePlayWhenReady() }) {
                    Text(if (playerState.playWhenReady) "暂停" else "播放")
                }
                FluentButton(onClick = { player.seekTo((player.currentPositionMillis.value - 10_000L).coerceAtLeast(0L)) }) {
                    Text("后退 10 秒")
                }
                FluentButton(onClick = { player.seekTo(player.currentPositionMillis.value + 10_000L) }) {
                    Text("前进 10 秒")
                }
            }
        }
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.id }) { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (file.isFolder) "📁 ${file.name}" else file.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    enabled = openingFileId == null,
                                    onClick = {
                                    if (file.isFolder) {
                                        repository.pushFolder(file.id, file.name)
                                        parentId = file.id
                                    } else if (isVideoFile(file.name)) {
                                        openingFileId = file.id
                                        scope.launch {
                                            mediaRepository.createMediaData(file.id)
                                                .onSuccess { (_, data) ->
                                                    currentData?.close()
                                                    currentData = data
                                                    player.setMediaData(data, playWhenReady = true)
                                                    error = null
                                                }
                                                .onFailure { error = it.message ?: "无法打开媒体" }
                                            openingFileId = null
                                        }
                                    } else if (isImageFile(file.name) && file.thumbnailLink.isNotBlank()) {
                                        selectedImage = file
                                        imageScale = 1f
                                        imageOffsetX = 0f
                                        imageOffsetY = 0f
                                    }
                                    },
                                )
                                .padding(12.dp),
                        )
                        if (!file.isFolder && !showTrash) {
                            FluentButton(
                                disabled = downloadingFileId != null,
                                onClick = {
                                    downloadingFileId = file.id
                                    downloadStatus = "下载中：${file.name}"
                                    scope.launch {
                                        downloadManager.enqueue(file)
                                        downloadStatus = "已加入下载：${file.name}"
                                        downloadingFileId = null
                                    }
                                },
                            ) { Text(if (downloadingFileId == file.id) "下载中" else "下载") }
                        }
                    }
                }
            }
        }
        DownloadTaskPanel(downloadTasks.values.toList())
    }

    selectedImage?.let { image ->
        Dialog(
            onDismissRequest = { selectedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().clickable { selectedImage = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = image.thumbnailLink,
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .clip(MaterialTheme.shapes.large)
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

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                FluentButton(
                    disabled = newFolderName.isBlank(),
                    onClick = {
                        val name = newFolderName.trim()
                        scope.launch {
                            repository.createFolder(parentId, name)
                                .onSuccess {
                                    newFolderName = ""
                                    showCreateFolder = false
                                    reload()
                                }
                                .onFailure { error = it.message ?: "创建文件夹失败" }
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                FluentButton(onClick = { showCreateFolder = false }) { Text("取消") }
            },
        )
    }

    if (showSettings) {
        var directoryText by remember { mutableStateOf(settings.downloadDirectory.absolutePath) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("设置") },
            text = {
                OutlinedTextField(
                    value = directoryText,
                    onValueChange = { directoryText = it },
                    label = { Text("下载目录") },
                    singleLine = true,
                )
            },
            confirmButton = {
                FluentButton(onClick = {
                    settings.downloadDirectory = java.io.File(directoryText.trim())
                    showSettings = false
                }) { Text("保存") }
            },
            dismissButton = {
                FluentButton(onClick = { showSettings = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DownloadTaskPanel(tasks: List<DownloadTask>) {
    if (tasks.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("下载任务", style = MaterialTheme.typography.titleMedium)
        tasks.take(3).forEach { task ->
            val progress = if (task.totalBytes > 0) {
                "${task.downloadedBytes}/${task.totalBytes} B"
            } else {
                ""
            }
            Text(
                text = "${task.fileName}  ${task.status}${if (progress.isEmpty()) "" else " $progress"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun isVideoFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf(
        "3g2", "3gp", "asf", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4",
        "mpeg", "mpg", "mts", "mxf", "ogm", "rm", "rmvb", "ts", "vob", "webm", "wmv",
    )

private fun isImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf(
        "avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "webp",
    )
