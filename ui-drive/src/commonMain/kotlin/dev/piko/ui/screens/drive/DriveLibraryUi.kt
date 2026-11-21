package dev.piko.ui.screens.drive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.encodeFileContextNote
import dev.piko.ui.format.formatDurationMillis
import dev.piko.ui.format.formatLibraryEventTime
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.*
import io.github.nihildigit.pikpak.DriveEvent
import io.github.nihildigit.pikpak.EventType
import io.github.nihildigit.pikpak.FileStat

/**
 * 资料库条目的结构化上下文分段：列表行、海报卡片与瀑布流统一经 `MetaRow`（`.tabular` 等宽数字）渲染，
 * 避免用空格硬拼导致时间读数跳动或被按路径从头截断。
 */
internal fun libraryNoteParts(library: DriveLibrary, file: FileStat, event: DriveEvent?): List<String> = when (library) {
    DriveLibrary.STARRED, DriveLibrary.DUPLICATES -> emptyList()
    DriveLibrary.TRASH -> file.deleteTime.takeIf { it.isNotEmpty() }?.let {
        listOf(
            tr(
                "将于 ${it.take(10)} 彻底删除",
                "Permanently deleted on ${it.take(10)}",
                "${it.take(10)} に完全削除",
                "${it.take(10)}에 영구 삭제 예정",
                "將於 ${it.take(10)} 永久刪除",
            ),
        )
    }.orEmpty()
    DriveLibrary.RECENT -> event?.let {
        val how = if (it.type == EventType.UPLOAD) {
            tr("上传", "Uploads", "アップロード", "업로드", "上傳")
        } else {
            tr("离线或秒传", "Cloud download or instant save", "クラウドダウンロード、インスタント保存", "클라우드 다운로드 또는 즉시 저장", "離線下載或秒傳")
        }
        listOfNotNull(formatEventTime(it.updatedTime), how)
    }.orEmpty()
    DriveLibrary.HISTORY -> event?.let {
        val watched = it.playSeconds?.let { seconds ->
            val at = formatDurationMillis(seconds * 1000)
            it.playDuration?.takeIf { d -> d > 0 }?.let { d ->
                val total = formatDurationMillis(d * 1000)
                tr("播放至 $at / $total", "Played $at / $total", "再生位置 $at / $total", "재생 위치 $at / $total", "播放至 $at / $total")
            } ?: tr("播放至 $at", "Played to $at", "再生位置 $at", "재생 위치 $at", "播放至 $at")
        }
        listOfNotNull(formatEventTime(it.updatedTime), watched)
    }.orEmpty()
}

internal fun libraryNote(library: DriveLibrary, file: FileStat, event: DriveEvent?): String? =
    encodeFileContextNote(libraryNoteParts(library, file, event))

private fun formatEventTime(rfc3339: String): String? = formatLibraryEventTime(rfc3339)

internal class LibraryEmpty(val title: String, val description: String, val icon: ImageVector)

internal val DriveLibrary.empty: LibraryEmpty
    get() = when (this) {
        DriveLibrary.RECENT -> LibraryEmpty(
            tr("暂无最近添加", "No recently added files", "最近追加したファイルはありません", "최근 추가 파일이 없습니다", "尚無最近新增的檔案"),
            tr("上传、离线下载与秒传的文件显示于此", "Uploads, cloud downloads and instant saves appear here", "アップロード、クラウドダウンロード、インスタント保存したファイルを表示します", "업로드, 클라우드 다운로드 및 즉시 저장 파일이 여기에 표시됩니다", "上傳、離線下載與秒傳的檔案會顯示於此"),
            icon,
        )
        DriveLibrary.STARRED -> LibraryEmpty(
            tr("暂无星标", "No starred items", "スター付きの項目はありません", "별표 항목이 없습니다", "尚無星號項目"),
            tr("在文件菜单中添加星标后显示于此", "Star items from the file menu to see them here", "ファイルメニューでスターを付けると表示されます", "파일 메뉴에서 별표를 추가하면 여기에 표시됩니다", "在檔案選單中新增星號後會顯示於此"),
            icon,
        )
        DriveLibrary.HISTORY -> LibraryEmpty(
            tr("暂无播放记录", "No playback history", "再生履歴はありません", "재생 기록이 없습니다", "尚無播放記錄"),
            tr("包含 PikPak 各客户端的播放记录", "Includes playback from all PikPak apps", "PikPakの各アプリの再生履歴を含みます", "모든 PikPak 앱의 재생 기록을 포함합니다", "包含 PikPak 各用戶端的播放記錄"),
            icon,
        )
        DriveLibrary.TRASH -> LibraryEmpty(
            tr("回收站为空", "Trash is empty", "ゴミ箱は空です", "휴지통이 비어 있습니다", "資源回收筒是空的"),
            tr("移入回收站的文件显示于此，可恢复或彻底删除", "Trashed files appear here for restoration or permanent deletion", "ゴミ箱に移動したファイルを表示します。復元または完全に削除できます", "휴지통의 파일을 여기에서 복원하거나 영구 삭제할 수 있습니다", "移至資源回收筒的檔案會顯示於此，可還原或永久刪除"),
            icon,
        )
        DriveLibrary.DUPLICATES -> LibraryEmpty(
            tr("未发现重复文件", "No duplicate files found", "重複ファイルはありません", "중복 파일이 없습니다", "未發現重複檔案"),
            "",
            Icons.Outlined.FolderOpen,
        )
    }

internal fun trashActions(onRestore: () -> Unit, onDelete: () -> Unit): List<SheetAction> = listOf(
    DriveActions.restoreFromTrash(onRestore),
    DriveActions.deleteForever(onDelete),
)

internal sealed interface LibraryConfirm {
    val title: String
    val message: String
    val confirmLabel: String

    class DeleteForever(val ids: List<String>, val emptying: Boolean) : LibraryConfirm {
        override val title get() = if (emptying) tr("清空回收站", "Empty trash", "ゴミ箱を空にする", "휴지통 비우기", "清空資源回收筒") else tr("彻底删除", "Delete permanently", "完全に削除", "영구 삭제", "永久刪除")
        override val message
            get() = if (emptying) tr("将彻底删除回收站中的全部 ${ids.size} 项，删除后无法恢复。", "Permanently delete all ${enCount(ids.size, "item", "items")} in trash. This cannot be undone.", "ゴミ箱の全 ${ids.size} 項目を完全に削除します。元に戻せません。", "휴지통의 모든 ${ids.size}개 항목을 영구 삭제합니다. 복원할 수 없습니다.", "將永久刪除資源回收筒中的全部 ${ids.size} 項，刪除後無法還原。") else tr("将彻底删除所选的 ${ids.size} 项，删除后无法恢复。", "Permanently delete ${ids.size} selected items. This cannot be undone.", "選択した ${ids.size} 項目を完全に削除します。元に戻せません。", "선택한 ${ids.size}개 항목을 영구 삭제합니다. 복원할 수 없습니다.", "將永久刪除所選的 ${ids.size} 項，刪除後無法還原。")
        override val confirmLabel get() = tr("彻底删除", "Delete permanently", "完全に削除", "영구 삭제", "永久刪除")
    }

    data object ClearHistory : LibraryConfirm {
        override val title get() = tr("清空播放历史", "Clear playback history", "再生履歴を消去", "재생 기록 지우기", "清空播放歷史")
        override val message get() = tr("将删除全部播放记录，官方客户端同步清空，无法恢复。", "Delete all playback history, including official apps. This cannot be undone.", "すべての再生履歴を削除し、公式アプリにも反映します。元に戻せません。", "모든 재생 기록을 삭제하며 공식 앱에도 반영됩니다. 복원할 수 없습니다.", "將刪除全部播放記錄，官方用戶端也會同步清空，無法還原。")
        override val confirmLabel get() = tr("清空", "Clear all", "すべて消去", "모두 지우기", "清空")
    }
}

@Composable
internal fun LibraryConfirmDialog(request: LibraryConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = { PikoDialogConfirm(request.confirmLabel, onClick = onConfirm, destructive = true) },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

internal fun libraryPageActions(library: DriveLibrary?, isEmpty: Boolean, onConfirm: (LibraryConfirm) -> Unit, allIds: () -> List<String>): List<SheetAction> =
    when {
        isEmpty -> emptyList()
        library == DriveLibrary.TRASH ->
            listOf(SheetAction(Icons.Outlined.DeleteSweep, tr("清空回收站", "Empty trash", "ゴミ箱を空にする", "휴지통 비우기", "清空資源回收筒"), { onConfirm(LibraryConfirm.DeleteForever(allIds(), emptying = true)) }, destructive = true))
        library == DriveLibrary.HISTORY ->
            listOf(SheetAction(Icons.Outlined.DeleteSweep, tr("清空播放历史", "Clear playback history", "再生履歴を消去", "재생 기록 지우기", "清空播放歷史"), { onConfirm(LibraryConfirm.ClearHistory) }, destructive = true))
        else -> emptyList()
    }
