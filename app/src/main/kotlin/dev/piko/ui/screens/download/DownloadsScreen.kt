package dev.piko.ui.screens.download

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.piko.PikoApplication
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.ui.components.PikoEmptyState
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.LocalFixedColors
import java.io.File

@Composable
fun DownloadsScreen(
    modifier: Modifier = Modifier,
) {
    val downloadManager = PikoApplication.instance.downloadManager
    val tasksMap by downloadManager.tasks.collectAsState()
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
                description = "在云盘中点击文件操作菜单，选择“下载到本地”开启多连接并发下载",
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
                items(tasks, key = { it.fileId }) { task ->
                    Box(modifier = Modifier.animateItem()) {
                        DownloadTaskCard(
                            task = task,
                            onStart = { downloadManager.startDownload(task.fileId) },
                            onPause = { downloadManager.pauseDownload(task.fileId) },
                            onCancel = { downloadManager.cancelDownload(task.fileId) },
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
    onStart: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val statusSubtitle = buildString {
                        append("${task.downloadedBytes.toReadableSize()} / ${task.totalBytes.toReadableSize()}")
                        if (task.status == DownloadStatus.DOWNLOADING && task.speedBytesPerSec > 0) {
                            append(" · ")
                            append("${(task.speedBytesPerSec / (1024 * 1024f)).formatTwoDecimals()} MB/s (多连接分块)")
                        }
                    }
                    Text(
                        text = statusSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (task.status) {
                        DownloadStatus.DOWNLOADING -> {
                            IconButton(onClick = onPause) {
                                Icon(Icons.Outlined.Pause, contentDescription = "Pause")
                            }
                        }
                        DownloadStatus.PAUSED, DownloadStatus.PENDING, DownloadStatus.FAILED -> {
                            IconButton(onClick = onStart) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = "Resume")
                            }
                        }
                        DownloadStatus.COMPLETED -> {
                            IconButton(onClick = {
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
                            }) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = "Open")
                            }
                        }
                    }

                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun Float.formatTwoDecimals(): String = String.format("%.2f", this)
