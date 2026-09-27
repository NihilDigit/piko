package dev.piko.ui.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.download.DownloadStatus
import dev.piko.shared.sync.PikoSettingsSync
import dev.piko.shared.upload.UploadStatus
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize

/** 进行中的一项传输，状态栏与活动面板共用。 */
private class Activity(val name: String, val icon: ImageVector, val done: Long, val total: Long, val speed: Long)

@Composable
private fun activities(): List<Activity> {
    val services = LocalPikoServices.current
    val downloads by services.downloadManager.tasks.collectAsStateWithLifecycle()
    val uploads by services.uploadManager.tasks.collectAsStateWithLifecycle()
    return downloads.values
        .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
        .map { Activity(it.fileName, Icons.Outlined.Download, it.downloadedBytes, it.totalBytes, it.speedBytesPerSec) } +
        uploads.values
            .filter { it.status == UploadStatus.UPLOADING || it.status == UploadStatus.HASHING || it.status == UploadStatus.QUEUED }
            .map { Activity(it.fileName, Icons.Outlined.Upload, it.processedBytes, it.size, it.speedBytesPerSec) }
}

/**
 * 大窗口底部的状态栏，照 IDE：左边是进行中的传输（点开下面的活动面板）与最近一次能撤销的改动，
 * 右边是设置同步与空间用量。只在有侧边栏的大窗口出现，一行字，不抢内容的位置。
 */
@Composable
internal fun StatusBar(activityOpen: Boolean, onActivityToggle: () -> Unit, modifier: Modifier = Modifier) {
    val services = LocalPikoServices.current
    val items = activities()
    val latestChange by services.driveRepository.changes.latest.collectAsStateWithLifecycle()
    val syncStatus by services.settingsSync.status.collectAsStateWithLifecycle()
    val syncEnabled by services.preferences.settingsSyncFlow.collectAsStateWithLifecycle(initialValue = false)
    val quota by services.driveRepository.quotaFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (quota == null) services.driveRepository.getQuota() }

    Row(
        modifier = modifier.fillMaxWidth().height(StatusBarHeight).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val downloads = items.count { it.icon == Icons.Outlined.Download }
        val uploads = items.size - downloads
        val speed = items.sumOf { it.speed }
        val summary = when {
            items.isEmpty() -> "没有进行中的传输"
            else -> listOfNotNull(
                downloads.takeIf { it > 0 }?.let { "下载 $it 项" },
                uploads.takeIf { it > 0 }?.let { "上传 $it 项" },
                speed.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" },
            ).joinToString(" · ")
        }
        StatusItem(
            icon = if (activityOpen) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            text = summary,
            onClick = onActivityToggle,
        )
        latestChange?.let { change ->
            StatusItem(
                icon = Icons.AutoMirrored.Outlined.Undo,
                text = "撤销：${change.summary}",
                onClick = { services.driveRepository.changes.undo(change) },
            )
        }
        Spacer(Modifier.weight(1f))
        if (syncEnabled) {
            val (icon, text) = when (syncStatus) {
                PikoSettingsSync.Status.SYNCING -> Icons.Outlined.CloudSync to "正在同步设置"
                PikoSettingsSync.Status.FAILED -> Icons.Outlined.CloudOff to "设置同步失败"
                else -> Icons.Outlined.CloudDone to "设置已同步"
            }
            StatusItem(icon = icon, text = text, onClick = null)
        }
        quota?.quota?.let { q ->
            StatusItem(icon = Icons.Outlined.Storage, text = "${q.usageBytes.toReadableSize()} / ${q.limitBytes.toReadableSize()}", onClick = null)
        }
    }
}

@Composable
private fun StatusItem(icon: ImageVector, text: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * 状态栏上方展开的活动面板：进行中的下载与上传，边浏览网盘边看进度，不必切到传输页。
 * 暂停、重试这些操作仍在传输页，这里只给一个过去的入口。
 */
@Composable
internal fun ActivityPanel(onOpenTransfers: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val items = activities()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().height(ActivityPanelHeight).padding(horizontal = 8.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("进行中的传输", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenTransfers) { Text("打开传输页") }
                TooltipIconButton(Icons.Outlined.Close, "收起", onClose)
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "没有进行中的下载或上传",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items) { item -> ActivityRow(item) }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: Activity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Spacer(Modifier.height(4.dp))
            val fraction = if (item.total > 0) (item.done.toFloat() / item.total).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = listOfNotNull(
                "${item.done.toReadableSize()} / ${item.total.toReadableSize()}",
                item.speed.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" },
            ).joinToString("  "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp).width(160.dp),
        )
    }
}

private val StatusBarHeight = 32.dp
private val ActivityPanelHeight = 220.dp
