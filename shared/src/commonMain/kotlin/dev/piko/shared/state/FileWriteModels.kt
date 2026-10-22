package dev.piko.shared.state

import dev.piko.shared.data.PikoPathBreadcrumb

/** 普通文件写操作的不可变参数；不包含页面、导航或 UI 回调。 */
sealed interface FileWriteCommand {
    val ids: List<String>
    val target: PikoPathBreadcrumb? get() = null
    val title: String

    data class CreateFolder(override val target: PikoPathBreadcrumb, val name: String) : FileWriteCommand {
        override val ids = emptyList<String>()
        override val title get() = "新建文件夹「$name」"
    }
    data class Rename(val id: String, val name: String, val knownBefore: FileBefore? = null) : FileWriteCommand {
        override val ids = listOf(id)
        override val title get() = "重命名为「$name」"
    }
    data class Move(override val ids: List<String>, override val target: PikoPathBreadcrumb, val knownBefore: Map<String, FileBefore> = emptyMap()) : FileWriteCommand {
        override val title get() = "移动 ${ids.size} 项到「${target.name}」"
    }
    data class Copy(override val ids: List<String>, override val target: PikoPathBreadcrumb) : FileWriteCommand {
        override val title get() = "复制 ${ids.size} 项到「${target.name}」"
    }
    data class Restore(override val ids: List<String>) : FileWriteCommand { override val title get() = "恢复 ${ids.size} 项" }
    data class Delete(override val ids: List<String>) : FileWriteCommand { override val title get() = "彻底删除 ${ids.size} 项" }
    data class Star(override val ids: List<String>, val starred: Boolean) : FileWriteCommand {
        override val title get() = "${if (starred) "添加" else "取消"} ${ids.size} 项星标"
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
