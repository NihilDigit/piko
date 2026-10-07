package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.ListLeadingIcon
import dev.piko.ui.components.ListLeadingMedia
import dev.piko.ui.components.ListLeadingSize
import androidx.compose.ui.unit.Dp
import dev.piko.ui.components.ListMoreButton
import dev.piko.ui.components.MetaRow
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalFileActions
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.LocalStatusColors

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac")

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")

private fun String.isImageName(): Boolean = substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

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
    // 转码档下完后在本机转为 MP4，这一段不走网络，速度一栏是空的，要写明在做什么
    DownloadStatus.DOWNLOADING -> if (converting) "转换中" else null
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.PENDING -> "等待中"
    DownloadStatus.FAILED -> "下载失败"
}

/** 状态词之后的各段数据，片段任务以时间段打头。失败原因不在这里，见 [shortFailureReason]。 */
private fun DownloadTask.statusDetails(): List<String> {
    // 片段事先不知道产物大小，totalBytes 为 0，字节进度只会是「0 B / 0 B」，改报抽取比例。
    // 比例为 0 时抽取器还在从头逐簇扫到起点（系统 MKV 解析器用不上 Cues），这一段可能要几分钟
    val progress = when {
        converting -> "${(this.progress * 100).toInt()}%"
        !isSegment -> "${downloadedBytes.toReadableSize()} / ${totalBytes.toReadableSize()}"
        this.progress <= 0f -> "正在定位起点"
        else -> "${(this.progress * 100).toInt()}%"
    }
    val segment = listOfNotNull(timeRangeLabel?.takeIf { isSegment }?.let { "段落 $it" })
    return segment + when (status) {
        DownloadStatus.COMPLETED -> listOf(totalBytes.toReadableSize())
        DownloadStatus.PENDING -> if (isSegment) emptyList() else listOf(totalBytes.toReadableSize())
        DownloadStatus.DOWNLOADING -> buildList {
            add(progress)
            if (speedBytesPerSec > 0) add("${speedBytesPerSec.toReadableSize()}/s")
            // 片段事先不知道产物大小，剩余时间无从算起
            if (!isSegment && !converting) remainingTime(totalBytes - downloadedBytes, speedBytesPerSec)?.let { add("剩余 $it") }
        }
        DownloadStatus.PAUSED -> listOf(progress)
        DownloadStatus.FAILED -> emptyList()
    }
}

/** 进行中的任务才有进度条。失败与完成的行不放，行高不随状态变化。 */
private val DownloadTask.showsProgress: Boolean
    get() = status == DownloadStatus.PENDING || status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSED

private fun DownloadTask.failureReason(): String = errorMessage?.takeIf { it.isNotBlank() } ?: "网络中断"

/** 列表行里的失败原因只取首行，由 MetaRow 截断在一行内；完整信息在详情面板里。 */
private fun DownloadTask.shortFailureReason(): String? =
    if (status == DownloadStatus.FAILED) failureReason().lineSequence().first() else null

/** 列表与面板共用的外部动作：用其他应用打开、分享、打开所在文件夹。 */
private class LocalFileIntents(private val files: LocalFileActions, private val task: DownloadTask, private val isMedia: Boolean) {
    fun openExternal() = files.openExternally(task.destinationPath, isMedia)

    fun share() = files.share(task.destinationPath, isMedia)

    fun openContainingFolder() = files.openContainingFolder(task.destinationPath)
}

@Composable
private fun DownloadTask.statusColor() = when (status) {
    DownloadStatus.COMPLETED -> LocalStatusColors.current.success
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 本地下载的列表项。
 *
 * 尾部保留一个与状态对应的快捷按钮（暂停、继续、重试），其余操作在详情面板里。
 * 已完成的项不放播放按钮：点按整行即播放，前导图像上也有播放标记。
 */
@Composable
internal fun LocalTransferRow(
    task: DownloadTask,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onMoreClick: () -> Unit,
    isSpoilerBlurred: Boolean,
    modifier: Modifier = Modifier,
    selection: RowSelection = RowSelection.None,
) {
    val files = LocalPikoPlatform.current.localFiles
    val isMedia = task.isMedia()
    val statusColor = task.statusColor()

    FileListItem(
        headline = task.displayName,
        leading = { LocalTransferVisual(task = task, isMedia = isMedia, isSpoilerBlurred = isSpoilerBlurred) },
        onClick = { localPrimaryAction(task, files, onPlay, onStart, onPause)?.invoke() },
        onMoreClick = onMoreClick,
        modifier = modifier,
        headlineMaxLines = if (task.showsProgress) 1 else 2,
        onLongClick = selection.onLongClick,
        isSelectionMode = selection.active,
        isSelected = selection.selected,
        onSelectToggle = { selection.onToggle() },
        supporting = {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 完成的不写「已完成」，所在的分组已经说了；只写需要留意或正在进行的状态
                    task.statusLabel()?.takeIf { task.status != DownloadStatus.COMPLETED }?.let { label ->
                        Text(text = label, color = statusColor, maxLines = 1)
                    }
                    MetaRow(
                        parts = task.statusDetails() + listOfNotNull(task.shortFailureReason()),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (task.showsProgress) {
                    Spacer(modifier = Modifier.height(4.dp))
                    if (task.isSegment && task.status == DownloadStatus.DOWNLOADING && task.progress <= 0f) {
                        // 片段还在定位起点，用时未知，给不确定进度条
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { task.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    DownloadStatus.COMPLETED -> Unit
                    DownloadStatus.DOWNLOADING -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = "暂停")
                    }
                    DownloadStatus.PAUSED, DownloadStatus.PENDING -> IconButton(onClick = onStart) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "继续下载")
                    }
                    DownloadStatus.FAILED -> IconButton(onClick = onStart) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重试")
                    }
                }
                ListMoreButton(onClick = onMoreClick)
            }
        },
    )
}

/**
 * 点按一项做的事：完成的播放或用其他应用打开，暂停与失败的继续，下载中的暂停。窄行的轻点、宽行的轻点与双击共用。
 * 等待中的没有可做的，为 null。
 */
internal fun localPrimaryAction(
    task: DownloadTask,
    files: LocalFileActions,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
): (() -> Unit)? {
    val isMedia = task.isMedia()
    return when (task.status) {
        DownloadStatus.COMPLETED -> if (isMedia) onPlay else LocalFileIntents(files, task, isMedia)::openExternal
        DownloadStatus.PAUSED, DownloadStatus.FAILED -> onStart
        DownloadStatus.DOWNLOADING -> onPause
        DownloadStatus.PENDING -> null
    }
}

/** 本地下载项的全部操作，详情面板与右键菜单共用。 */
internal fun localTransferActions(
    task: DownloadTask,
    files: LocalFileActions,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onRemove: () -> Unit,
    /** 为 null 表示没有可切换的预览（防窥关闭或没有缩略图），不显示该项。 */
    previewHidden: Boolean?,
    onTogglePreview: () -> Unit,
): List<SheetAction> {
    val isMedia = task.isMedia()
    val intents = LocalFileIntents(files, task, isMedia)
    return buildList {
        // 边下边播只认原画的暂存；转码档的暂存是转封装前的 TS，播放走不到它
        if (task.status != DownloadStatus.COMPLETED && !task.isSegment && task.mediaId == null && isMedia && task.sparseCache && task.downloadedBytes > 0) {
            add(SheetAction(Icons.Outlined.PlayArrow, "播放", onPlay))
        }
        if (previewHidden != null) {
            add(
                SheetAction(
                    icon = if (previewHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    label = if (previewHidden) "显示预览" else "隐藏预览",
                    onClick = onTogglePreview,
                ),
            )
        }
        when (task.status) {
            DownloadStatus.COMPLETED -> {
                if (isMedia) add(SheetAction(Icons.Outlined.PlayArrow, "播放", onPlay))
                add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, "用其他应用打开", intents::openExternal))
                add(SheetAction(Icons.Outlined.FolderOpen, "打开所在文件夹", intents::openContainingFolder))
                if (files.canShare) add(SheetAction(Icons.Outlined.Share, "分享", intents::share))
            }
            DownloadStatus.DOWNLOADING -> add(SheetAction(Icons.Outlined.Pause, "暂停", onPause))
            DownloadStatus.PAUSED, DownloadStatus.PENDING -> add(SheetAction(Icons.Outlined.PlayArrow, "继续下载", onStart))
            DownloadStatus.FAILED -> add(SheetAction(Icons.Outlined.Refresh, "重试", onStart))
        }
        val removeLabel = when (task.status) {
            DownloadStatus.COMPLETED -> "删除本地文件"
            DownloadStatus.FAILED -> "移除"
            else -> "取消并删除"
        }
        add(SheetAction(Icons.Outlined.Delete, removeLabel, onRemove, destructive = true))
    }
}

/** 本地下载项的详情面板：完整文件名、状态与全部操作。 */
@Composable
internal fun LocalTransferSheet(task: DownloadTask, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val statusColor = task.statusColor()
    ItemDetailsSheet(
        title = task.displayName,
        headerIcon = { ListLeadingIcon(task.typeIcon()) },
        actions = actions,
        onDismiss = onDismiss,
        metaParts = listOfNotNull(task.statusLabel()) + task.statusDetails(),
        extraLines = {
            if (task.status == DownloadStatus.FAILED) Text(text = task.failureReason(), color = statusColor)
        },
    )
}

@Composable
private fun LocalTransferVisual(task: DownloadTask, isMedia: Boolean, isSpoilerBlurred: Boolean, size: Dp = ListLeadingSize) {
    val files = LocalPikoPlatform.current.localFiles
    // 本机缩略图只对图片与视频要：压缩包、文档交给平台也会得到一个模型，加载失败时画面是空的，退不回类型图标
    val imageModel = remember(task.thumbnailLink, task.destinationPath) {
        task.thumbnailLink.ifEmpty { null }
            ?: files.thumbnailModel(task.destinationPath).takeIf { isMedia || task.fileName.isImageName() }
    }
    ListLeadingMedia(
        thumbnail = imageModel,
        fallback = { ListLeadingIcon(task.typeIcon()) },
        isSpoilerBlurred = isSpoilerBlurred,
        showPlayOverlay = task.status == DownloadStatus.COMPLETED && isMedia,
        size = size,
    )
}
