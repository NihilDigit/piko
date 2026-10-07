package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.FolderUsage
import dev.piko.shared.state.DriveItemName
import dev.piko.ui.components.FileTypeIcon
import dev.piko.ui.components.ItemDetailsSheet
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.metaParts
import dev.piko.ui.components.toReadableSize
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * 网盘条目的操作面板，列表与海报墙共用。外壳是 [ItemDetailsSheet]。
 *
 * [actions] 与这一项的右键菜单是同一份（DriveScreen 的 itemActions），这里只管头部。
 * folderUsage 只对文件夹给出，面板打开期间收集，关闭即取消统计。
 * 标题与卡片上是同一个名字（集号前带上作品名），真实名称在它下面，都可选中复制，见 DriveItemName。
 * 离线下载与分享转存来的条目，头部注明来源。
 */
@Composable
internal fun FileActionsSheet(
    name: DriveItemName,
    locationLabel: String?,
    folderUsage: Flow<FolderUsage>?,
    actions: List<SheetAction>,
    onDismiss: () -> Unit,
) {
    val file = name.file
    val usage by produceState<FolderUsageResult?>(null, folderUsage) {
        folderUsage ?: return@produceState
        try {
            folderUsage.collect { value = FolderUsageResult.Counted(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            value = FolderUsageResult.Failed
        }
    }

    ItemDetailsSheet(
        title = name.headingText,
        subtitle = name.headingOriginal,
        headerIcon = { FileTypeIcon(file = file, iconSize = 24.dp, modifier = Modifier.fillMaxSize()) },
        actions = actions,
        onDismiss = onDismiss,
        // 类型、大小与修改时间排成一行。文件夹的类型已由图标表明，只留时间
        metaParts = sheetMetaParts(file, usage),
        extraLines = {
            if (!locationLabel.isNullOrEmpty()) {
                Text(text = locationLabel, color = MaterialTheme.colorScheme.primary)
            }
            file.source?.let { Text(text = it.label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

/** 网盘里一项的操作要做的事。为 null 的表示这里做不了，不给那一项。 */
internal class FileActionHandlers(
    val toggleStar: () -> Unit,
    val download: () -> Unit,
    val share: () -> Unit,
    val rename: () -> Unit,
    val move: () -> Unit,
    val copy: () -> Unit,
    val trash: () -> Unit,
    val extract: () -> Unit,
    val findDuplicates: () -> Unit,
    val downloadSegment: () -> Unit,
    /** 视频的「下载」「下载指定段落」上按下或悬停时提前查各档，见 [SheetAction.onPrepare]。 */
    val prepareQualities: () -> Unit,
    val copySource: () -> Unit,
    val openSource: () -> Unit,
    /** 平台交不出去时为 null。 */
    val openInExternalPlayer: (() -> Unit)?,
    /** 没有标签栏（移动端）时为 null。 */
    val openInNewTab: (() -> Unit)?,
    /** 固定或取消固定到快速访问；没有快速访问可去（移动端）时为 null。 */
    val togglePin: (() -> Unit)?,
    val isPinned: Boolean,
    /** 把文件夹里的文件换成归档记录，腾出空间。库里列的是散落各处的条目，不在原地，为 null。 */
    val vault: (() -> Unit)?,
    /** 文件夹挂着归档标记时才有。 */
    val unvault: (() -> Unit)?,
    /** 没有可切换的预览（防窥关闭或没有缩略图）时为 null。 */
    val previewHidden: Boolean?,
    val togglePreview: () -> Unit,
)

/**
 * 网盘里一项（不在回收站、压缩包里，也不是归档条目）的操作。通用的几样做不做得了看 [commands]，
 * 与命令栏同一份规则；这里只加各类条目自己的。先后即各档里的先后，见 [DriveActions]。
 */
internal fun fileActions(file: FileStat, commands: ItemCommands, on: FileActionHandlers): List<SheetAction> = buildList {
    val video = !file.isFolder && file.isPlayableVideo()
    // 文件夹连同子文件夹整个下载；单个视频点了先选画质，各档大小提前查
    if (commands.download) add(DriveActions.download(onPrepare = on.prepareQualities.takeIf { video }, onClick = on.download))
    if (commands.share) add(DriveActions.share(on.share))
    add(DriveActions.star(file.isStarred, on.toggleStar))
    if (commands.rename) add(DriveActions.rename(on.rename))
    if (file.isFolder) on.openInNewTab?.let { add(DriveActions.openInNewTab(it)) }
    if (video) on.openInExternalPlayer?.let { add(DriveActions.openInExternalPlayer(it)) }
    if (commands.extract) add(DriveActions.extract(on.extract))
    if (commands.moveCopyTo) {
        add(DriveActions.moveTo(on.move))
        add(DriveActions.copyTo(on.copy))
    }
    if (file.isFolder) on.togglePin?.let { add(DriveActions.pin(on.isPinned, it)) }
    if (video) add(DriveActions.downloadSegment(on.downloadSegment, on.prepareQualities))
    if (file.isFolder) {
        add(DriveActions.findDuplicates(on.findDuplicates))
        on.vault?.let { add(DriveActions.vault(it)) }
        on.unvault?.let { add(DriveActions.unvault(it)) }
    }
    on.previewHidden?.let { add(DriveActions.previewVisibility(it, on.togglePreview)) }
    addAll(DriveActions.sourceActions(file.source, on.copySource, on.openSource))
    if (commands.moveToTrash) add(DriveActions.moveToTrash(on.trash))
}

/**
 * 条目从哪来。列目录接口的 params.url 记着来源：离线下载的是原始磁力链接，从分享转存的是
 * mypikpak.com/s/ 分享链接；自己上传或新建的没有。离线任务生成的顶层文件夹与其中的文件都带着
 */
internal enum class FileSource(val label: String) { Magnet("来源：离线下载"), Share("来源：从分享转存") }

internal val FileStat.source: FileSource?
    get() {
        val url = sourceUrl?.takeIf { it.isNotBlank() } ?: return null
        return when {
            url.startsWith("magnet:", ignoreCase = true) -> FileSource.Magnet
            url.startsWith("http", ignoreCase = true) -> FileSource.Share
            else -> null
        }
    }

private sealed interface FolderUsageResult {
    data class Counted(val usage: FolderUsage) : FolderUsageResult
    data object Failed : FolderUsageResult
}

/**
 * 头部元信息排成一行：文件为类型、大小、修改时间；文件夹为递归统计的文件数与总大小、修改时间。
 * 统计中的数字逐步增长并注明「统计中」，因上限中止的标注「至少」，失败则只留时间。
 */
private fun sheetMetaParts(file: FileStat, usage: FolderUsageResult?): List<String> {
    // ISO 8601 取到分钟：2024-05-01T12:34:56.789+08:00 -> 2024-05-01 12:34
    val modified = file.modifiedTime.takeIf { it.isNotEmpty() }?.take(16)?.replace('T', ' ')
    if (!file.isFolder) return file.metaParts(includeDate = false) + listOfNotNull(modified)
    val counted = (usage as? FolderUsageResult.Counted)?.usage
        ?: return listOfNotNull(if (usage == null) "统计中" else null, modified)
    val count = "${counted.fileCount} 个文件"
    val size = counted.bytes.toReadableSize()
    return when (counted.progress) {
        FolderUsage.Progress.COUNTING -> listOf(count, size, "统计中")
        FolderUsage.Progress.COMPLETE -> listOfNotNull(count, size, modified)
        FolderUsage.Progress.TRUNCATED -> listOfNotNull("至少 $count", "至少 $size", modified)
    }
}
