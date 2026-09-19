package dev.piko.ui.screens.download

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalFixedColors
import java.io.File

/**
 * Downloads screen showing active and completed downloads.
 *
 * Documentation references:
 * - Lifecycle-aware flow collection: `android-docs-mirror/pages/develop/ui/compose/state.md`
 * - Lazy lists and key stability: `android-docs-mirror/pages/develop/ui/compose/lists.md`
 * - Material 3 cards and expressive lists: `m3-material-mirror/pages/components/card.md`
 */
@Composable
fun DownloadsScreen(
    onPlayVideo: (DownloadTask) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val downloadManager = PikoApplication.instance.downloadManager
    val tasksMap by downloadManager.tasks.collectAsStateWithLifecycle()
    val tasks = tasksMap.values.toList()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            PikoTopBar(title = "本地下载")
        },
    ) { innerPadding ->
        if (tasks.isEmpty()) {
            PikoEmptyState(
                title = "暂无下载任务",
                description = "在云盘中点击文件操作菜单，选择“下载到本地”将文件保存至本地",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(tasks, key = { it.taskId }) { task ->
                    Box(modifier = Modifier.animateItem()) {
                        DownloadTaskCard(
                            task = task,
                            onPlay = { onPlayVideo(task) },
                            onStart = { downloadManager.startDownload(task.taskId) },
                            onPause = { downloadManager.pauseDownload(task.taskId) },
                            onCancel = { downloadManager.cancelDownload(task.taskId) },
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
fun DownloadTaskCard(
    task: DownloadTask,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val isMedia = task.fileName.endsWith(".mp4", ignoreCase = true) ||
        task.fileName.endsWith(".mkv", ignoreCase = true) ||
        task.fileName.endsWith(".mov", ignoreCase = true) ||
        task.fileName.endsWith(".avi", ignoreCase = true) ||
        task.fileName.endsWith(".webm", ignoreCase = true) ||
        task.fileName.endsWith(".ts", ignoreCase = true) ||
        task.fileName.endsWith(".mp3", ignoreCase = true) ||
        task.fileName.endsWith(".flac", ignoreCase = true) ||
        task.fileName.endsWith(".wav", ignoreCase = true) ||
        task.fileName.endsWith(".m4a", ignoreCase = true) ||
        task.fileName.endsWith(".aac", ignoreCase = true)

    val openExternalFile = {
        val file = File(task.destinationPath)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (isMedia) "video/*" else "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    val openFolderOrFile = {
        val file = File(task.destinationPath)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    val shareFile = {
        val file = File(task.destinationPath)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (isMedia) "video/*" else "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "分享文件")) }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (task.status == DownloadStatus.COMPLETED) {
                    if (isMedia) onPlay() else openExternalFile()
                } else if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
                    onStart()
                } else if (task.status == DownloadStatus.DOWNLOADING) {
                    onPause()
                }
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 预览图片 / 视频缩略图展示
                Surface(
                    modifier = Modifier
                        .size(width = 80.dp, height = 52.dp)
                        .clip(MaterialTheme.shapes.small),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val imageModel = when {
                            task.thumbnailLink.isNotEmpty() -> task.thumbnailLink
                            File(task.destinationPath).exists() -> File(task.destinationPath)
                            else -> null
                        }

                        if (imageModel != null) {
                            AsyncImage(
                                model = imageModel,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            val icon = when {
                                task.fileName.endsWith(".mp4", ignoreCase = true) ||
                                    task.fileName.endsWith(".mkv", ignoreCase = true) ||
                                    task.fileName.endsWith(".mov", ignoreCase = true) ||
                                    task.fileName.endsWith(".avi", ignoreCase = true) ||
                                    task.fileName.endsWith(".webm", ignoreCase = true) ||
                                    task.fileName.endsWith(".ts", ignoreCase = true) -> Icons.Outlined.Movie
                                task.fileName.endsWith(".mp3", ignoreCase = true) ||
                                    task.fileName.endsWith(".flac", ignoreCase = true) ||
                                    task.fileName.endsWith(".wav", ignoreCase = true) -> Icons.Outlined.AudioFile
                                else -> Icons.Outlined.Description
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                        }

                        // 若已下载完成且为媒体，浮显半透明播放指示
                        if (task.status == DownloadStatus.COMPLETED && isMedia) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 文件名与状态详情
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))

                    if (task.isSegment && task.timeRangeLabel != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(bottom = 2.dp),
                        ) {
                            Text(
                                text = "截取段落 ${task.timeRangeLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }

                    val statusSubtitle = when (task.status) {
                        DownloadStatus.COMPLETED -> buildString {
                            append("已完成 · ${task.totalBytes.toReadableSize()}")
                        }
                        DownloadStatus.DOWNLOADING -> buildString {
                            append("${task.downloadedBytes.toReadableSize()} / ${task.totalBytes.toReadableSize()}")
                            if (task.speedBytesPerSec > 0) {
                                append(" · ")
                                append("${(task.speedBytesPerSec / (1024 * 1024f)).formatTwoDecimals()} MB/s")
                            }
                        }
                        DownloadStatus.PAUSED -> "已暂停 · ${task.downloadedBytes.toReadableSize()} / ${task.totalBytes.toReadableSize()}"
                        DownloadStatus.PENDING -> "等待中 · ${task.totalBytes.toReadableSize()}"
                        DownloadStatus.FAILED -> "下载失败: ${task.errorMessage ?: "网络中断"}"
                    }

                    Text(
                        text = statusSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.status == DownloadStatus.COMPLETED) {
                            LocalFixedColors.current.InstantMatchGreen
                        } else if (task.status == DownloadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 按钮控制区：播放键直接显示，删除和打开文件夹收纳进详情菜单
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (task.status) {
                        DownloadStatus.COMPLETED -> {
                            // 播放键直接显示
                            if (isMedia) {
                                FilledTonalIconButton(
                                    onClick = onPlay,
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "播放",
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }

                            // 详情按钮 (包含打开文件夹、外部应用打开、分享、删除本地文件)
                            Box {
                                IconButton(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreVert,
                                        contentDescription = "详情",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("打开文件夹") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                                        },
                                        onClick = {
                                            showMenu = false
                                            openFolderOrFile()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("外部应用打开") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                                        },
                                        onClick = {
                                            showMenu = false
                                            openExternalFile()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("系统分享") },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Share, contentDescription = null)
                                        },
                                        onClick = {
                                            showMenu = false
                                            shareFile()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除本地文件", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            onCancel()
                                        },
                                    )
                                }
                            }
                        }
                        DownloadStatus.DOWNLOADING -> {
                            IconButton(onClick = onPause) {
                                Icon(Icons.Outlined.Pause, contentDescription = "暂停")
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "详情")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("取消并删除下载", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        },
                                        onClick = {
                                            showMenu = false
                                            onCancel()
                                        },
                                    )
                                }
                            }
                        }
                        DownloadStatus.PAUSED, DownloadStatus.PENDING, DownloadStatus.FAILED -> {
                            IconButton(onClick = onStart) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = "继续下载")
                            }
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "详情")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("取消并删除任务", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                        },
                                        onClick = {
                                            showMenu = false
                                            onCancel()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (task.status != DownloadStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun Float.formatTwoDecimals(): String = String.format("%.2f", this)
