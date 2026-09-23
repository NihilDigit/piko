package dev.piko.ui.screens.download

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.piko.PikoApplication
import dev.piko.data.repository.isPlayableVideo
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.LocalStatusColors
import java.io.File

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac")

/**
 * 本地下载列表。
 *
 * 每个任务是一个列表项而不是一张卡片：原先 16dp 页边距、12dp 卡片内边距与 10dp 卡片间隔
 * 叠在一起，一屏只放得下五六项；列表项沿用网盘列表的 16dp 页边距与行高规则。
 *
 * Documentation references:
 * - Lifecycle-aware flow collection: `android-docs-mirror/pages/develop/ui/compose/state.md`
 * - Lazy lists and key stability: `android-docs-mirror/pages/develop/ui/compose/lists.md`
 * - Material 3 lists: `m3-material-mirror/pages/components/lists.md`
 */
@Composable
fun DownloadsScreen(
    onPlayVideo: (DownloadTask) -> Unit = {},
    topBarActions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val downloadManager = PikoApplication.instance.downloadManager
    val tasksMap by downloadManager.tasks.collectAsStateWithLifecycle()
    val tasks = remember(tasksMap) { tasksMap.values.toList() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            PikoTopBar(title = "本地下载", actions = topBarActions)
        },
    ) { innerPadding ->
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                PikoEmptyState(
                    title = "暂无下载任务",
                    description = "在网盘中打开文件的操作面板，选择「下载到本地」",
                    icon = Icons.Outlined.Download,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(tasks, key = { it.taskId }) { task ->
                    DownloadTaskRow(
                        task = task,
                        onPlay = { onPlayVideo(task) },
                        onStart = { downloadManager.startDownload(task.taskId) },
                        onPause = { downloadManager.pauseDownload(task.taskId) },
                        onCancel = { downloadManager.cancelDownload(task.taskId) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

private fun DownloadTask.isMedia(): Boolean =
    fileName.isPlayableVideo() || fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

private fun DownloadTask.typeIcon(): ImageVector = when {
    fileName.isPlayableVideo() -> Icons.Outlined.Movie
    fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS -> Icons.Outlined.AudioFile
    else -> Icons.Outlined.Description
}

/** 状态词。下载中没有状态词：进度条与速度已经说明它在下载。 */
private fun DownloadTask.statusLabel(): String? = when (status) {
    DownloadStatus.COMPLETED -> "已完成"
    DownloadStatus.DOWNLOADING -> null
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.PENDING -> "等待中"
    DownloadStatus.FAILED -> "下载失败"
}

/** 状态词之后的各段数据。失败原因可能很长，不在这里，单独占一行。 */
private fun DownloadTask.statusDetails(): List<String> {
    val progress = "${downloadedBytes.toReadableSize()} / ${totalBytes.toReadableSize()}"
    return when (status) {
        DownloadStatus.COMPLETED, DownloadStatus.PENDING -> listOf(totalBytes.toReadableSize())
        DownloadStatus.DOWNLOADING -> buildList {
            add(progress)
            if (speedBytesPerSec > 0) {
                add("${String.format("%.2f", speedBytesPerSec / (1024 * 1024f))} MB/s")
            }
        }
        DownloadStatus.PAUSED -> listOf(progress)
        DownloadStatus.FAILED -> emptyList()
    }
}

@Composable
private fun DownloadTaskRow(
    task: DownloadTask,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val isMedia = task.isMedia()

    val openExternalFile = {
        downloadUri(context, task.destinationPath)?.let { uri ->
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (isMedia) "video/*" else "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    val shareFile = {
        downloadUri(context, task.destinationPath)?.let { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (isMedia) "video/*" else "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "分享文件")) }
        }
    }

    val statusColor = when (task.status) {
        DownloadStatus.COMPLETED -> LocalStatusColors.current.success
        DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ListItem(
        onClick = {
            when (task.status) {
                DownloadStatus.COMPLETED -> if (isMedia) onPlay() else openExternalFile()
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> onStart()
                DownloadStatus.DOWNLOADING -> onPause()
                DownloadStatus.PENDING -> Unit
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .heightIn(min = 64.dp),
        leadingContent = { DownloadThumbnail(task = task, isMedia = isMedia) },
        supportingContent = {
            Column {
                if (task.isSegment && task.timeRangeLabel != null) {
                    Text(
                        text = "段落 ${task.timeRangeLabel}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    task.statusLabel()?.let { label ->
                        Text(text = label, color = statusColor, maxLines = 1)
                    }
                    MetaRow(
                        parts = task.statusDetails(),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (task.status == DownloadStatus.FAILED) {
                    Text(
                        text = task.errorMessage ?: "网络中断",
                        color = statusColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (task.status != DownloadStatus.COMPLETED) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { task.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    DownloadStatus.COMPLETED -> if (isMedia) {
                        FilledTonalIconButton(onClick = onPlay) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "播放")
                        }
                    }
                    DownloadStatus.DOWNLOADING -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = "暂停")
                    }
                    DownloadStatus.PAUSED, DownloadStatus.PENDING, DownloadStatus.FAILED -> IconButton(onClick = onStart) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "继续下载")
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (task.status == DownloadStatus.COMPLETED) {
                            MenuAction(Icons.Outlined.FolderOpen, "打开所在文件夹") {
                                showMenu = false
                                openContainingFolder(context, task.destinationPath)
                            }
                            MenuAction(Icons.Outlined.OpenInNew, "用其他应用打开") {
                                showMenu = false
                                openExternalFile()
                            }
                            MenuAction(Icons.Outlined.Share, "分享") {
                                showMenu = false
                                shareFile()
                            }
                        }
                        MenuAction(
                            icon = Icons.Outlined.Delete,
                            label = if (task.status == DownloadStatus.COMPLETED) "删除本地文件" else "取消并删除",
                            destructive = true,
                        ) {
                            showMenu = false
                            onCancel()
                        }
                    }
                }
            }
        },
        contentPadding = PaddingValues(start = 12.dp, end = 0.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            text = task.fileName,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(label, color = color) },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = if (destructive) color else MaterialTheme.colorScheme.onSurfaceVariant)
        },
        onClick = onClick,
    )
}

/** 16:10 缩略图，与网格卡片封面同比例。 */
@Composable
private fun DownloadThumbnail(task: DownloadTask, isMedia: Boolean) {
    Surface(
        modifier = Modifier
            .size(width = 64.dp, height = 40.dp)
            .clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val imageModel = remember(task.thumbnailLink, task.destinationPath) {
                when {
                    task.thumbnailLink.isNotEmpty() -> task.thumbnailLink
                    task.destinationPath.startsWith("content:") -> task.destinationPath
                    File(task.destinationPath).exists() -> File(task.destinationPath)
                    else -> null
                }
            }
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = task.typeIcon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (task.status == DownloadStatus.COMPLETED && isMedia && imageModel != null) {
                val fixed = LocalFixedColors.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(fixed.ScrimOnMedia.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = fixed.OnMedia,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

private fun downloadUri(context: android.content.Context, path: String): Uri? {
    if (path.startsWith("content:")) return Uri.parse(path)
    val file = File(path)
    if (!file.exists()) return null
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun openContainingFolder(context: android.content.Context, path: String) {
    val uri = if (path.startsWith("content:")) {
        val fileUri = Uri.parse(path)
        val authority = fileUri.authority ?: return
        val documentId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return
        val parentId = documentId.substringBeforeLast('/', documentId)
        DocumentsContract.buildTreeDocumentUri(authority, parentId)
    } else {
        val file = File(path)
        if (!file.exists()) return
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file.parentFile ?: file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "vnd.android.document/directory")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}
