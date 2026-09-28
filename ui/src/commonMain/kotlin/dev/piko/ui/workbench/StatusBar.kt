package dev.piko.ui.workbench

import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.outlined.CloudDownload
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Tune
import dev.piko.data.auth.SnailMode
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.SheetAction
import dev.piko.ui.screens.settings.SnailModeDialog
import kotlinx.coroutines.launch
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

/** 进行中的一项传输，状态栏与活动面板共用。云端的离线任务只有百分比（[percent]），没有字节数。 */
internal class Activity(
    val name: String,
    val kind: Kind,
    val done: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val percent: Int? = null,
) {
    enum class Kind(val icon: ImageVector) { DOWNLOAD(Icons.Outlined.Download), UPLOAD(Icons.Outlined.Upload), CLOUD(Icons.Outlined.CloudDownload) }
}

/**
 * 眼下进行中的传输：本机的下载与上传，加上云端的离线任务。离线任务不像下载那样有进程级的状态，
 * 这里每 [CLOUD_POLL_MS] 取一次第一页；状态栏在时才取，调用方只调一次，状态栏与活动面板共用结果。
 */
@Composable
internal fun rememberActivities(): List<Activity> {
    val services = LocalPikoServices.current
    val downloads by services.downloadManager.tasks.collectAsStateWithLifecycle()
    val uploads by services.uploadManager.tasks.collectAsStateWithLifecycle()
    val cloud by produceState(services.taskRepository.cachedTasks().orEmpty(), services) {
        while (true) {
            services.taskRepository.getTasks().onSuccess { value = it.tasks }
            delay(CLOUD_POLL_MS)
        }
    }
    return downloads.values
        .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
        .map { Activity(it.fileName, Activity.Kind.DOWNLOAD, it.downloadedBytes, it.totalBytes, it.speedBytesPerSec) } +
        uploads.values
            .filter { it.status == UploadStatus.UPLOADING || it.status == UploadStatus.HASHING || it.status == UploadStatus.QUEUED }
            .map { Activity(it.fileName, Activity.Kind.UPLOAD, it.processedBytes, it.size, it.speedBytesPerSec) } +
        cloud
            .filter { it.phase == TaskPhase.RUNNING || it.phase == TaskPhase.PENDING }
            .map { Activity(it.name, Activity.Kind.CLOUD, total = it.fileSize.toLongOrNull() ?: 0, percent = it.progress) }
}

/**
 * 大窗口底部的状态栏，照 IDE：左边是蜗牛模式、上下行速度（点开下面的活动面板）与最近一次能撤销的改动，
 * 右边是设置同步与空间用量。只在有侧边栏的大窗口出现，一行字，不抢内容的位置。
 */
@Composable
internal fun StatusBar(items: List<Activity>, activityOpen: Boolean, onActivityToggle: () -> Unit, modifier: Modifier = Modifier) {
    val services = LocalPikoServices.current
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
        SnailToggle()
        TransferReadout(items = items, activityOpen = activityOpen, onClick = onActivityToggle)
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

/**
 * 上下行的总速度，照 FDM 常驻显示：没有传输时是 0 B/s，看得出眼下确实没在跑。
 * 云端离线任务没有速度，有的时候在后面补一句项数。点它展开活动面板。
 */
@Composable
private fun TransferReadout(items: List<Activity>, activityOpen: Boolean, onClick: () -> Unit) {
    fun speed(kind: Activity.Kind) = items.filter { it.kind == kind }.sumOf { it.speed }
    val cloudCount = items.count { it.kind == Activity.Kind.CLOUD }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = if (activityOpen) "收起活动面板" else "展开活动面板", onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        @Composable
        fun Rate(icon: ImageVector, bytesPerSecond: Long) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(
                text = "${bytesPerSecond.toReadableSize()}/s",
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                // 数字跳动时宽度不变，右边的东西不跟着晃
                modifier = Modifier.widthIn(min = 64.dp),
            )
        }
        Rate(Icons.Outlined.ArrowDownward, speed(Activity.Kind.DOWNLOAD))
        Rate(Icons.Outlined.ArrowUpward, speed(Activity.Kind.UPLOAD))
        if (cloudCount > 0) {
            Text("云端 $cloudCount 项", style = MaterialTheme.typography.labelMedium, color = tint)
        }
        Icon(
            imageVector = if (activityOpen) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 蜗牛模式的开关，照 FDM 放在速度旁边：单击开关，右键改上限。开着时填上底色，一眼看得出速度是被压着的。
 */
@Composable
private fun SnailToggle() {
    val preferences = LocalPikoServices.current.preferences
    val scope = rememberCoroutineScope()
    val mode by preferences.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    var editing by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    ContextMenuArea(actions = { listOf(SheetAction(Icons.Outlined.Tune, "设置上限", { editing = true })) }) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(if (mode.enabled) colors.secondaryContainer else Color.Transparent)
                .clickable(onClickLabel = if (mode.enabled) "关闭蜗牛模式" else "开启蜗牛模式") {
                    scope.launch { preferences.setSnailMode(mode.copy(enabled = !mode.enabled)) }
                }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (mode.enabled) colors.onSecondaryContainer else colors.onSurfaceVariant
            Icon(Icons.Outlined.SlowMotionVideo, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(
                text = if (mode.enabled) "限速中" else "蜗牛模式",
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
    if (editing) {
        SnailModeDialog(
            current = mode,
            onSave = { next ->
                editing = false
                scope.launch { preferences.setSnailMode(next) }
            },
            onDismiss = { editing = false },
        )
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
internal fun ActivityPanel(items: List<Activity>, onOpenTransfers: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
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
                        "没有进行中的下载、上传或离线任务",
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
        Icon(item.kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Spacer(Modifier.height(4.dp))
            val fraction = when {
                item.percent != null -> item.percent / 100f
                item.total > 0 -> (item.done.toFloat() / item.total).coerceIn(0f, 1f)
                else -> 0f
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = if (item.percent != null) {
                listOfNotNull("云端 ${item.percent}%", item.total.takeIf { it > 0 }?.toReadableSize()).joinToString("  ")
            } else {
                listOfNotNull(
                    "${item.done.toReadableSize()} / ${item.total.toReadableSize()}",
                    item.speed.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" },
                ).joinToString("  ")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp).width(160.dp),
        )
    }
}

private const val CLOUD_POLL_MS = 15_000L
private val StatusBarHeight = 32.dp
private val ActivityPanelHeight = 220.dp
