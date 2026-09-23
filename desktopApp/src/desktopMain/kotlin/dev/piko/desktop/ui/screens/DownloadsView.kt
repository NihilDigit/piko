package dev.piko.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.desktop.ui.components.formatBytes
import dev.piko.desktop.ui.components.getFileIcon
import dev.piko.desktop.winrt.WinRTSupport
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.download.PikoDownloadCoordinator
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressBar
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowDownload
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.icons.regular.Open
import io.github.composefluent.icons.regular.Pause
import io.github.composefluent.icons.regular.Play
import io.github.composefluent.surface.Card
import java.io.File

@Composable
fun DownloadsView(
    downloadCoordinator: PikoDownloadCoordinator,
    settingsStore: DesktopSettingsStore,
    modifier: Modifier = Modifier,
) {
    val tasksMap by downloadCoordinator.tasks.collectAsState()
    val tasks = tasksMap.values.toList()

    var filterStatus by remember { mutableStateOf<DownloadStatus?>(null) }
    val filteredTasks = remember(tasks, filterStatus) {
        if (filterStatus == null) tasks else tasks.filter { it.status == filterStatus }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "下载管理",
                    style = FluentTheme.typography.title,
                )
                Text(
                    text = "共 ${tasks.size} 个任务 (${tasks.count { it.status == DownloadStatus.DOWNLOADING }} 进行中)",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        WinRTSupport.openFolder(settingsStore.downloadDirectory)
                    },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Regular.Folder,
                            contentDescription = "打开目录",
                            modifier = Modifier.size(16.dp),
                        )
                        Text("打开下载目录")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterTab(
                label = "全部 (${tasks.size})",
                selected = filterStatus == null,
                onClick = { filterStatus = null },
            )
            FilterTab(
                label = "下载中 (${tasks.count { it.status == DownloadStatus.DOWNLOADING }})",
                selected = filterStatus == DownloadStatus.DOWNLOADING,
                onClick = { filterStatus = DownloadStatus.DOWNLOADING },
            )
            FilterTab(
                label = "已完成 (${tasks.count { it.status == DownloadStatus.COMPLETED }})",
                selected = filterStatus == DownloadStatus.COMPLETED,
                onClick = { filterStatus = DownloadStatus.COMPLETED },
            )
            FilterTab(
                label = "已暂停/失败 (${tasks.count { it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.FAILED }})",
                selected = filterStatus == DownloadStatus.PAUSED || filterStatus == DownloadStatus.FAILED,
                onClick = { filterStatus = DownloadStatus.PAUSED },
            )
        }

        Spacer(Modifier.height(16.dp))

        // Content
        if (filteredTasks.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Regular.ArrowDownload,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = FluentTheme.colors.text.text.tertiary,
                    )
                    Text(
                        text = if (tasks.isEmpty()) "暂无下载任务" else "无符合筛选的任务",
                        style = FluentTheme.typography.bodyLarge,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredTasks, key = { it.taskId }) { task ->
                    DownloadTaskCard(
                        task = task,
                        settingsStore = settingsStore,
                        onPause = { downloadCoordinator.pauseDownload(task.taskId) },
                        onResume = { downloadCoordinator.startDownload(task.taskId) },
                        onCancel = { downloadCoordinator.cancelDownload(task.taskId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(label)
        }
    } else {
        SubtleButton(onClick = onClick) {
            Text(label)
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    settingsStore: DesktopSettingsStore,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Title & Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = getFileIcon(name = task.fileName, isFolder = false),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Column {
                        Text(
                            text = task.fileName,
                            style = FluentTheme.typography.bodyStrong,
                        )
                        // 状态词与各段数据各占一个 Text，靠间距分开，不拼分隔符
                        val statusParts = when (task.status) {
                            DownloadStatus.DOWNLOADING -> buildList {
                                val totalStr = if (task.totalBytes > 0) formatBytes(task.totalBytes) else "未知"
                                add("下载中")
                                add("${formatBytes(task.downloadedBytes)} / $totalStr")
                                if (task.speedBytesPerSec > 0) add("${formatBytes(task.speedBytesPerSec)}/s")
                            }
                            DownloadStatus.COMPLETED -> listOf(
                                "已完成",
                                formatBytes(task.totalBytes.coerceAtLeast(task.downloadedBytes)),
                            )
                            DownloadStatus.PAUSED -> listOf(
                                "已暂停",
                                "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}",
                            )
                            DownloadStatus.FAILED -> listOf("失败", task.errorMessage ?: "未知错误")
                            DownloadStatus.PENDING -> listOf("等待中")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            statusParts.forEach { part ->
                                Text(
                                    text = part,
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.text.text.secondary,
                                )
                            }
                        }
                    }
                }

                // Action buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (task.status) {
                        DownloadStatus.DOWNLOADING -> {
                            SubtleButton(onClick = onPause) {
                                Icon(Icons.Regular.Pause, contentDescription = "暂停", modifier = Modifier.size(16.dp))
                            }
                        }
                        DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                            SubtleButton(onClick = onResume) {
                                Icon(Icons.Regular.Play, contentDescription = "继续", modifier = Modifier.size(16.dp))
                            }
                        }
                        DownloadStatus.COMPLETED -> {
                            val targetFile = File(settingsStore.downloadDirectory, task.fileName)
                            SubtleButton(
                                onClick = { WinRTSupport.openFile(targetFile) },
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Regular.Open, contentDescription = "打开", modifier = Modifier.size(14.dp))
                                    Text("打开", style = FluentTheme.typography.caption)
                                }
                            }
                            SubtleButton(
                                onClick = {
                                    if (targetFile.exists()) {
                                        WinRTSupport.openFolder(targetFile.parentFile ?: settingsStore.downloadDirectory)
                                    } else {
                                        WinRTSupport.openFolder(settingsStore.downloadDirectory)
                                    }
                                },
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Regular.Folder, contentDescription = "定位", modifier = Modifier.size(14.dp))
                                    Text("定位", style = FluentTheme.typography.caption)
                                }
                            }
                        }
                        DownloadStatus.PENDING -> Unit
                    }

                    SubtleButton(onClick = onCancel) {
                        Icon(Icons.Regular.Dismiss, contentDescription = "取消/删除", modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Progress bar
            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    if (task.totalBytes > 0) {
                        ProgressBar(
                            progress = task.progress,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        ProgressBar(modifier = Modifier.fillMaxWidth())
                    }
                }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                    ProgressBar(
                        progress = task.progress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DownloadStatus.COMPLETED -> {
                    ProgressBar(
                        progress = 1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DownloadStatus.PENDING -> {
                    ProgressBar(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
