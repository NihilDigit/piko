package dev.piko.shared.state

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.localizedName

/** 普通文件写操作的不可变参数；不包含页面、导航或 UI 回调。 */
sealed interface FileWriteCommand {
    val ids: List<String>
    val target: PikoPathBreadcrumb? get() = null
    val title: String

    data class CreateFolder(override val target: PikoPathBreadcrumb, val name: String) : FileWriteCommand {
        override val ids = emptyList<String>()
        override val title get() = tr("新建文件夹「$name」", "Create folder “$name”", "フォルダー「$name」を作成", "폴더 “$name” 만들기", "新增資料夾「$name」")
    }
    data class Rename(val id: String, val name: String, val knownBefore: FileBefore? = null) : FileWriteCommand {
        override val ids = listOf(id)
        override val title get() = tr("重命名为「$name」", "Rename to “$name”", "「$name」に名前を変更", "“$name”(으)로 이름 변경", "重新命名為「$name」")
    }
    data class Move(override val ids: List<String>, override val target: PikoPathBreadcrumb, val knownBefore: Map<String, FileBefore> = emptyMap()) : FileWriteCommand {
        override val title get() = tr("移动 ${ids.size} 项到「${target.localizedName}」", "Move ${enCount(ids.size, "item", "items")} to “${target.localizedName}”", "${ids.size} 項目を「${target.localizedName}」に移動", "${ids.size}개 항목을 “${target.localizedName}”(으)로 이동", "移動 ${ids.size} 項至「${target.localizedName}」")
    }
    data class Copy(override val ids: List<String>, override val target: PikoPathBreadcrumb) : FileWriteCommand {
        override val title get() = tr("复制 ${ids.size} 项到「${target.localizedName}」", "Copy ${enCount(ids.size, "item", "items")} to “${target.localizedName}”", "${ids.size} 項目を「${target.localizedName}」にコピー", "${ids.size}개 항목을 “${target.localizedName}”에 복사", "複製 ${ids.size} 項至「${target.localizedName}」")
    }
    data class Restore(override val ids: List<String>) : FileWriteCommand { override val title get() = tr("恢复 ${ids.size} 项", "Restore ${enCount(ids.size, "item", "items")}", "${ids.size} 項目を復元", "${ids.size}개 항목 복원", "還原 ${ids.size} 項") }
    data class Delete(override val ids: List<String>) : FileWriteCommand { override val title get() = tr("彻底删除 ${ids.size} 项", "Permanently delete ${enCount(ids.size, "item", "items")}", "${ids.size} 項目を完全に削除", "${ids.size}개 항목 영구 삭제", "永久刪除 ${ids.size} 項") }
    data class Star(override val ids: List<String>, val starred: Boolean) : FileWriteCommand {
        override val title get() = if (starred) {
            tr("添加 ${ids.size} 项星标", "Star ${enCount(ids.size, "item", "items")}", "${ids.size} 項目にスターを付ける", "${ids.size}개 항목에 별표 추가", "為 ${ids.size} 項加上星號")
        } else {
            tr("取消 ${ids.size} 项星标", "Unstar ${enCount(ids.size, "item", "items")}", "${ids.size} 項目のスターを外す", "${ids.size}개 항목의 별표 해제", "取消 ${ids.size} 項的星號")
        }
    }
}

enum class FileWritePhase { PREPARING, WRITING, CHECKING, COPYING, FINISHED }
enum class FileWriteCompletion { SUCCEEDED, PARTIAL, FAILED, UNCONFIRMED }
data class FileBefore(val name: String, val parentId: String)
data class FileCopyTask(val id: String, val sources: List<String>, val phase: String = "", val progress: Int = 0)
data class FileWriteResult(
    val completion: FileWriteCompletion,
    val message: String,
    val confirmed: Set<String> = emptySet(),
    val unconfirmed: Set<String> = emptySet(),
    val notAttempted: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    val undoChangeIds: List<Long> = emptyList(),
    val createdId: String? = null,
    val finishedAtMs: Long,
)
data class FileWriteWork(
    val id: Long,
    val account: String,
    val command: FileWriteCommand,
    val createdAtMs: Long,
    val phase: FileWritePhase = FileWritePhase.PREPARING,
    val checked: Int = 0,
    val completedCount: Int = 0,
    val totalCount: Int = command.ids.size.coerceAtLeast(1),
    val stopping: Boolean = false,
    val before: Map<String, FileBefore> = emptyMap(),
    val copies: List<FileCopyTask> = emptyList(),
    val result: FileWriteResult? = null,
) {
    val total: Int get() = command.ids.size.coerceAtLeast(1)
    val totalSteps: Int get() = (totalCount + 99) / 100
    val isRunning: Boolean get() = result == null

    /** 轻量操作由前台反馈；仅长批次、复制和需要核对的结局进入任务页。 */
    val showsInTaskPage: Boolean
        get() = when (command) {
            is FileWriteCommand.Star, is FileWriteCommand.CreateFolder -> false
            is FileWriteCommand.Copy -> true
            is FileWriteCommand.Rename, is FileWriteCommand.Move,
            is FileWriteCommand.Restore, is FileWriteCommand.Delete -> if (isRunning) totalSteps > 1
                else result?.completion == FileWriteCompletion.PARTIAL || result?.completion == FileWriteCompletion.UNCONFIRMED
        }
}

data class FileWriteOutcome(val account: String, val workId: Long, val title: String, val result: FileWriteResult)
