package dev.piko.ui.screens.drive

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.FolderUsage
import dev.piko.shared.state.DriveParsedField
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
 * previewHidden 为 null 表示没有可切换的预览（防窥关闭或没有缩略图），不显示该项。
 * folderUsage 只对文件夹给出，面板打开期间收集，关闭即取消统计。
 * parsedTitle 与 parsedFields 是文件名解析的结果，认不出或开了原始文件名时为空，标题退回原名。
 */
@Composable
internal fun FileActionsSheet(
    file: FileStat,
    locationLabel: String?,
    previewHidden: Boolean?,
    folderUsage: Flow<FolderUsage>?,
    onTogglePreview: () -> Unit,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onDownloadSegment: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    parsedTitle: String? = null,
    parsedFields: List<DriveParsedField> = emptyList(),
) {
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

    val actions = fileActions(
        file = file,
        previewHidden = previewHidden,
        onTogglePreview = onTogglePreview,
        onDownload = onDownload,
        onDownloadSegment = onDownloadSegment,
        onRename = onRename,
        onMove = onMove,
        onTrash = onTrash,
    )

    ItemDetailsSheet(
        title = parsedTitle ?: file.name,
        headerIcon = { FileTypeIcon(file = file, iconSize = 24.dp, modifier = Modifier.fillMaxSize()) },
        actions = actions,
        onDismiss = onDismiss,
        // 类型、大小与修改时间排成一行。文件夹的类型已由图标表明，只留时间
        metaParts = sheetMetaParts(file, usage),
        extraLines = {
            if (!locationLabel.isNullOrEmpty()) {
                Text(text = locationLabel, color = MaterialTheme.colorScheme.primary)
            }
        },
        parsedFields = parsedFields.map { it.label to it.value },
        originalName = file.name.takeIf { parsedFields.isNotEmpty() || parsedTitle != null },
    )
}

/** 网盘条目的操作。底部面板与桌面的右键菜单用同一份，两处不会漏项。 */
internal fun fileActions(
    file: FileStat,
    previewHidden: Boolean?,
    onTogglePreview: () -> Unit,
    onDownload: () -> Unit,
    onDownloadSegment: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
): List<SheetAction> = buildList {
    if (previewHidden != null) {
        add(
            SheetAction(
                icon = if (previewHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                label = if (previewHidden) "显示预览" else "隐藏预览",
                onClick = onTogglePreview,
            ),
        )
    }
    if (!file.isFolder) {
        add(SheetAction(Icons.Outlined.Download, "下载到本地", onDownload))
        if (file.isPlayableVideo()) add(SheetAction(Icons.Outlined.ContentCut, "下载指定段落", onDownloadSegment))
    }
    add(SheetAction(Icons.Outlined.Edit, "重命名", onRename))
    add(SheetAction(Icons.Outlined.DriveFileMove, "移动到", onMove))
    // 移入回收站单独成组，不紧挨着「移动到」被误触
    add(SheetAction(Icons.Outlined.Delete, "移入回收站", onTrash, destructive = true))
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
