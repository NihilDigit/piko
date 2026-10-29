package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.state.InstantSaveRecord
import dev.piko.ui.components.ItemDetailsContent
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.toReadableSize

internal val InstantSaveRecord.canPlayVideo: Boolean
    get() = locateId.isNotEmpty() && name.isPlayableVideo()

internal fun InstantSaveRecord.details(): List<String> = buildList {
    // 一个视频连同字幕时文件数大于 1，只存一个文件时不必再说「1 个文件」
    if (fileCount > 1) add(tr("$fileCount 个文件", "${enCount(fileCount, "file", "files")}", "$fileCount ファイル", "파일 ${fileCount}개"))
    add(totalBytes.toReadableSize())
}


/** 秒传项的详情面板：完整名称、保存位置与操作。 */
@Composable
internal fun InstantTransferDetails(record: InstantSaveRecord, actions: List<SheetAction>, modifier: Modifier = Modifier) {
    ItemDetailsContent(modifier = modifier, showIcon = false,
        title = record.name,
        headerIcon = {
            if (record.fileCount > 1 && !record.name.contains('.')) TaskFolderVisual(record.name)
            else TaskFileTypeIcon(record.name, Icons.Outlined.Bolt)
        },
        actions = actions,
        metaParts = listOf(tr("秒传", "Instant save", "インスタント保存", "즉시 저장"), tr("已保存", "Saved", "保存しました", "저장했습니다")) + record.details(),
        extraLines = {
            Text(text = tr("保存到 ${record.targetName}", "Saved to ${record.targetName}", "保存先 ${record.targetName}", "저장 위치 ${record.targetName}"), color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

/** 秒传记录的全部操作，详情面板与右键菜单共用。 */
internal fun instantTransferActions(
    record: InstantSaveRecord,
    onPlay: (() -> Unit)?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
): List<SheetAction> = buildList {
    if (record.canPlayVideo && onPlay != null) {
        add(SheetAction(Icons.Outlined.PlayArrow, tr("播放", "Play", "再生", "재생"), onPlay))
    }
    if (record.locateId.isNotEmpty()) {
        add(SheetAction(Icons.AutoMirrored.Outlined.OpenInNew, tr("在网盘中显示", "Show in drive", "ドライブで表示", "드라이브에서 보기"), onOpen))
    }
    add(SheetAction(Icons.Outlined.Delete, tr("移除记录", "Remove record", "記録を削除", "기록 삭제"), onRemove, destructive = true))
}
