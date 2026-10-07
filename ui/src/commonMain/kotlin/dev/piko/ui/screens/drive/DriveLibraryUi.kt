package dev.piko.ui.screens.drive

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.StarOutline
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import dev.piko.shared.data.DriveLibrary
import dev.piko.ui.components.PikoTopBar
import dev.piko.ui.components.asBarItem
import dev.piko.ui.components.iconBarItem
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.player.formatTime
import io.github.nihildigit.pikpak.DriveEvent
import io.github.nihildigit.pikpak.EventType
import io.github.nihildigit.pikpak.FileStat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 库里一行的附注，写在列表行的位置那一栏：最近添加是何时怎么加进来的，播放历史是何时看到哪里，
 * 回收站是何时彻底清除。星标没有可说的，为 null。
 */
internal fun libraryNote(library: DriveLibrary, file: FileStat, event: DriveEvent?): String? = when (library) {
    // 查找重复的位置要从扫描结果取，见 duplicateLocations
    DriveLibrary.STARRED, DriveLibrary.DUPLICATES -> null
    // delete_time 是服务端排定的彻底清除时间。实测为移入回收站后 15 天，但期限由服务端决定，不在这里按固定天数推算
    DriveLibrary.TRASH -> file.deleteTime.takeIf { it.isNotEmpty() }?.let { "将于 ${it.take(10)} 彻底删除" }
    DriveLibrary.RECENT -> event?.let {
        val how = if (it.type == EventType.UPLOAD) "上传" else "离线或秒传"
        listOfNotNull(formatEventTime(it.updatedTime), how).joinToString(" ")
    }
    DriveLibrary.HISTORY -> event?.let {
        val watched = it.playSeconds?.let { seconds ->
            val at = formatTime(seconds * 1000)
            it.playDuration?.takeIf { d -> d > 0 }?.let { d -> "播放至 $at / ${formatTime(d * 1000)}" } ?: "播放至 $at"
        }
        listOfNotNull(formatEventTime(it.updatedTime), watched).joinToString(" ").ifEmpty { null }
    }
}

/** 今天、昨天写到分钟，更早的只写日期；跨年时带上年份。解析不了时不写。 */
private fun formatEventTime(rfc3339: String): String? = runCatching {
    val time = OffsetDateTime.parse(rfc3339).atZoneSameInstant(ZoneId.systemDefault())
    val today = LocalDate.now()
    val date = time.toLocalDate()
    val clock = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    when {
        date == today -> "今天 $clock"
        date == today.minusDays(1) -> "昨天 $clock"
        date.year == today.year -> time.format(DateTimeFormatter.ofPattern("M 月 d 日", Locale.getDefault()))
        else -> time.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日", Locale.getDefault()))
    }
}.getOrNull()

internal class LibraryEmpty(val title: String, val description: String, val icon: ImageVector)

/**
 * 库的图标，侧边栏、「我的」、命令面板、路径首段与空状态都用这一套，选中时换 [selectedIcon]。
 * 挑的是实心与描边长得不一样的：History 两种写法同形，播放历史换成 PlayCircle。
 */
internal val DriveLibrary.icon: ImageVector
    get() = when (this) {
        DriveLibrary.RECENT -> Icons.Outlined.NewReleases
        DriveLibrary.STARRED -> Icons.Outlined.StarOutline
        DriveLibrary.HISTORY -> Icons.Outlined.PlayCircle
        DriveLibrary.TRASH -> Icons.Outlined.Delete
        DriveLibrary.DUPLICATES -> Icons.Outlined.FileCopy
    }

internal val DriveLibrary.selectedIcon: ImageVector
    get() = when (this) {
        DriveLibrary.RECENT -> Icons.Filled.NewReleases
        DriveLibrary.STARRED -> Icons.Filled.Star
        DriveLibrary.HISTORY -> Icons.Filled.PlayCircle
        DriveLibrary.TRASH -> Icons.Filled.Delete
        DriveLibrary.DUPLICATES -> Icons.Filled.FileCopy
    }

internal val DriveLibrary.empty: LibraryEmpty
    get() = when (this) {
        DriveLibrary.RECENT -> LibraryEmpty("暂无最近添加", "上传、离线下载与秒传的文件显示于此", icon)
        DriveLibrary.STARRED -> LibraryEmpty("暂无星标", "在文件菜单中添加星标后显示于此", icon)
        DriveLibrary.HISTORY -> LibraryEmpty("暂无播放记录", "包含 PikPak 各客户端的播放记录", icon)
        DriveLibrary.TRASH -> LibraryEmpty("回收站为空", "移入回收站的文件显示于此，可恢复或彻底删除", icon)
        // 实际画的是 DuplicatesEmptyState，要分扫描中、失败与已结束
        DriveLibrary.DUPLICATES -> LibraryEmpty("未发现重复文件", "", Icons.Outlined.FolderOpen)
    }

/**
 * 回收站里的条目只能恢复与彻底删除：打不开、查不了详情（服务端回 file_in_recycle_bin），
 * 移动、改名、分享也都无从谈起。
 */
internal fun trashActions(onRestore: () -> Unit, onDelete: () -> Unit): List<SheetAction> = listOf(
    DriveActions.restoreFromTrash(onRestore),
    DriveActions.deleteForever(onDelete),
)

/** 回收站与播放历史里不能撤销的操作，先确认。 */
internal sealed interface LibraryConfirm {
    val title: String
    val message: String
    val confirmLabel: String

    class DeleteForever(val ids: List<String>, val emptying: Boolean) : LibraryConfirm {
        override val title get() = if (emptying) "清空回收站" else "彻底删除"
        override val message
            get() = if (emptying) "将彻底删除回收站中的全部 ${ids.size} 项，删除后无法恢复。" else "将彻底删除所选的 ${ids.size} 项，删除后无法恢复。"
        override val confirmLabel get() = "彻底删除"
    }

    data object ClearHistory : LibraryConfirm {
        override val title get() = "清空播放历史"
        override val message get() = "将删除全部播放记录，官方客户端同步清空，无法恢复。"
        override val confirmLabel get() = "清空"
    }
}

@Composable
internal fun LibraryConfirmDialog(request: LibraryConfirm, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PikoDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = { PikoDialogConfirm(request.confirmLabel, onClick = onConfirm, destructive = true) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 回收站那一类库的整页操作：清空回收站、清空播放历史。其余库没有。 */
internal fun libraryPageActions(library: DriveLibrary?, isEmpty: Boolean, onConfirm: (LibraryConfirm) -> Unit, allIds: () -> List<String>): List<SheetAction> =
    when {
        isEmpty -> emptyList()
        library == DriveLibrary.TRASH ->
            listOf(SheetAction(Icons.Outlined.DeleteSweep, "清空回收站", { onConfirm(LibraryConfirm.DeleteForever(allIds(), emptying = true)) }, destructive = true))
        library == DriveLibrary.HISTORY ->
            listOf(SheetAction(Icons.Outlined.DeleteSweep, "清空播放历史", { onConfirm(LibraryConfirm.ClearHistory) }, destructive = true))
        else -> emptyList()
    }

/** 窄窗口里回收站的多选顶栏：网盘的那一条是移动、复制、移入回收站，这里只有恢复与彻底删除。 */
@Composable
internal fun TrashSelectionTopBar(
    scrollBehavior: TopAppBarScrollBehavior,
    selectedCount: Int,
    enabled: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    PikoTopBar(
        scrollBehavior = scrollBehavior,
        title = "已选择 $selectedCount 项",
        navigationIcon = { TooltipIconButton(Icons.Outlined.Close, "退出多选", onExit, shortcut = "Esc") },
        actions = run {
            val shortcutModifier = LocalPikoPlatform.current.shortcutModifier
            val canAct = enabled && selectedCount > 0
            listOf(
                iconBarItem(Icons.Outlined.SelectAll, "全选", onSelectAll, priority = 10, shortcut = shortcutModifier.label("A")),
                DriveActions.restoreFromTrash(onRestore).asBarItem(priority = 30, enabled = canAct),
                DriveActions.deleteForever(onDelete).asBarItem(priority = 20, enabled = canAct),
            )
        },
    )
}
