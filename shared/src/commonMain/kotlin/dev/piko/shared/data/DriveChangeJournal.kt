package dev.piko.shared.data

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate

private val changeIds = MutableStateFlow(0L)
private fun nextChangeId(): Long = changeIds.getAndUpdate { it + 1 } + 1

/**
 * 网盘里做过的改动，能撤回的记在这里：移动、移入回收站、重命名与批量重命名、从归档移除。完成一次改动的地方调 [record]，
 * 界面收 [events] 弹出带「撤销」的提示，撤销也可以是 Ctrl+Z（[undoLast]）。
 *
 * 一次操作记一条，批量的也是一条：撤销「移动 30 项」是一步，不是三十步。以后的自动重命名、按刮削结果整理，
 * 都是生成一批改动再交给同一套执行与撤销。
 *
 * 只在进程内有效，不落盘：服务端的状态随时可能被别的客户端改掉，隔了一次重启再撤销，原位置可能早已不在。
 * PikPak 没有撤销接口，撤销就是反向再做一次：移回原目录、从回收站恢复、改回原名。
 */
class DriveChangeJournal internal constructor(
    private val driveRepo: PikoDriveRepository,
    private val scope: CoroutineScope,
) {
    /** 一次能撤回的改动。[summary] 是做完时给用户看的那一句。 */
    sealed interface Change {
        /** 运行期内的稳定标识。结果保存标识，不延长反向操作数据的保留期限。 */
        val id: Long
        val summary: String

        /** [from] 是每一项原来所在的目录。 */
        class Move(val from: Map<String, String>, val targetId: String, override val summary: String) : Change {
            override val id: Long = nextChangeId()
        }

        class Trash(val ids: List<String>, override val summary: String) : Change {
            override val id: Long = nextChangeId()
        }

        class Rename private constructor(val renames: List<Renamed>, override val summary: String, override val id: Long) : Change {
            constructor(renames: List<Renamed>, summary: String) : this(renames, summary, nextChangeId())
            internal fun appended(more: List<Renamed>) = Rename(renames + more, summary, id)
        }

        /**
         * 归档清单上的改动：移除、改名。清单的改动本身是纯函数，撤销也就是一个反向的 [VaultEdit]，
         * 按文件夹记下，撤销时照样经可信写入套上去，不必为每种改动各写一种撤销。
         */
        class Vault(
            val reverts: Map<String, VaultEdit>,
            override val summary: String,
            /** 恢复到网盘造出的文件，撤销时先把条目写回清单，再把它们移进回收站：写回失败时不会两头落空。 */
            val trashOnRevert: List<String> = emptyList(),
            /** 归档时移进回收站的原文件，撤销时先从回收站恢复，再去掉清单里的条目，理由同上。 */
            val untrashOnRevert: List<String> = emptyList(),
            /**
             * 归档时选择永久删除原文件的条目，按文件夹分。撤销时按 gcid 秒传回去，
             * 只去掉秒传成功的那几条：云端已不存的留在清单里，引用不丢。
             */
            val recreateOnRevert: Map<String, List<VaultEntry>> = emptyMap(),
        ) : Change {
            override val id: Long = nextChangeId()
        }
    }

    class Renamed(val id: String, val oldName: String, val newName: String)

    /**
     * 做完一次改动或撤销一次，给用户看的一句话。[change] 不为 null 时这一条可以撤销。
     * [undone] 是撤销成功的那一次，记着网盘快照的（查找重复）据此把撤回来的文件补回去。
     */
    class Event(val message: String, val change: Change?, val undone: Change? = null, val failedChange: Change? = null, val generation: Long? = null)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    enum class UndoStatus { RUNNING, UNDONE, FAILED }
    data class UndoResult(val status: UndoStatus, val message: String, val activeFileIds: Set<String> = emptySet(), val activeManifestFolders: Set<String> = emptySet(), val activeTreeIds: Set<String> = emptySet(),
        val completedRenameSteps: Int = 0)
    data class JournalState(
        val generation: Long = 0,
        val available: List<Change> = emptyList(),
        val undo: Map<Long, UndoResult> = emptyMap(),
    )
    private val _state = MutableStateFlow(JournalState())
    val state: StateFlow<JournalState> = _state.asStateFlow()

    /** 眼下 Ctrl+Z 会撤掉的那一次；没有可撤销的为 null。状态栏据此给出「撤销」。 */
    val latest: StateFlow<Change?> = state.map { it.available.lastOrNull() }.stateIn(scope, SharingStarted.Eagerly, null)

    fun record(change: Change) {
        record(change, _state.value.generation)
    }

    fun record(change: Change, generation: Long): Boolean {
        if (!scope.isActive) return false
        while (true) {
            val current = _state.value
            if (current.generation != generation) return false
            val next = current.copy(available = (current.available + change).takeLast(LIMIT))
            if (_state.compareAndSet(current, next)) break
        }
        scope.launch {
            if (_state.value.generation == generation) _events.emit(Event(change.summary, change, generation = generation))
        }
        return true
    }

    /** 同一次命名的迟到确认延长原反向步骤，保留身份与执行顺序；已开始撤销或过期的记录不修改。 */
    fun appendRename(id: Long, more: List<Renamed>, generation: Long = _state.value.generation): Boolean {
        if (more.isEmpty()) return true
        while (true) {
            val current = _state.value
            if (current.generation != generation || current.undo[id] != null) return false
            val previous = current.available.filterIsInstance<Change.Rename>().firstOrNull { it.id == id } ?: return false
            val next = current.copy(available = current.available.map { if (it === previous) previous.appended(more) else it })
            if (_state.compareAndSet(current, next)) return true
        }
    }

    /** 失败后继续清理时合并原记录，撤销始终针对本次清理。 */
    suspend fun recordTrash(previousChange: Change.Trash?, ids: List<String>, summary: String): Change.Trash {
        val change = Change.Trash(ids, summary)
        val generation = _state.value.generation
        _state.update { current ->
            if (current.generation != generation) current else current.copy(available =
                (current.available.filterNot { it === previousChange } + change).takeLast(LIMIT))
        }
        if (_state.value.generation == generation) _events.emit(Event(summary, change, generation = generation))
        if (_state.value.generation != generation) throw kotlinx.coroutines.CancellationException("账号已改变")
        return change
    }

    /** 换号时丢掉全部记录：改动里的 ID 属于上一个账号。 */
    fun clear() {
        _state.update { JournalState(generation = it.generation + 1) }
    }

    /** 撤销最近一次还没撤销的改动。没有可撤销的返回 false。 */
    fun undoLast(): Boolean {
        val current = _state.value
        if (current.undo.values.any { it.status == UndoStatus.RUNNING }) return false
        return current.available.lastOrNull()?.let(::undo) ?: false
    }

    /** 撤销 [change]。它已经撤过、或被挤出记录时什么也不做。 */
    fun undo(change: Change): Boolean = undo(change.id)

    fun undo(id: Long): Boolean {
        if (!scope.isActive) return false
        val access = driveRepo.currentAccount?.let(driveRepo::writeAccess) ?: return false
        val generation: Long
        val change: Change
        while (true) {
            val current = _state.value
            val candidate = current.available.firstOrNull { it.id == id } ?: return false
            if (current.undo[id]?.status == UndoStatus.RUNNING) return false
            val next = current.copy(
                available = current.available.filterNot { it.id == id },
                undo = current.undo + (id to UndoResult(UndoStatus.RUNNING, "正在撤销", candidate.affectedFileIds(), candidate.affectedManifestFolders(), candidate.affectedTreeIds(),
                    current.undo[id]?.completedRenameSteps ?: 0)),
            )
            if (_state.compareAndSet(current, next)) { generation = current.generation; change = candidate; break }
        }
        scope.launch {
            val execution = requireNotNull(currentCoroutineContext()[Job])
            val accountGuard = launch(start = CoroutineStart.UNDISPATCHED) {
                state.first { it.generation != generation }
                execution.cancel()
            }
            try {
                val result = revert(change, access, generation)
                val message = if (result.isSuccess) "已撤销" else "撤销未完成，请检查文件现状后再处理"
                _state.update { current ->
                    if (current.generation != generation) current else {
                        val undo = current.undo + (id to UndoResult(if (result.isSuccess) UndoStatus.UNDONE else UndoStatus.FAILED, message,
                            completedRenameSteps = if (result.isSuccess) 0 else current.undo[id]?.completedRenameSteps ?: 0))
                        val running = undo.filterValues { it.status == UndoStatus.RUNNING }
                        val finished = undo.filterValues { it.status != UndoStatus.RUNNING }.entries.toList().takeLast(100).associate { it.toPair() }
                        current.copy(
                            available = if (result.isFailure) (current.available + change).takeLast(LIMIT) else current.available,
                            undo = running + finished,
                        )
                    }
                }
                if (_state.value.generation == generation) _events.emit(
                    if (result.isSuccess) Event(message, null, undone = change, generation = generation) else Event(message, null, failedChange = change, generation = generation),
                )
            } finally { accountGuard.cancel() }
        }
        return true
    }

    private suspend fun revert(change: Change, access: DriveWriteAccess, generation: Long): Result<Unit> {
        val result = when (change) {
            is Change.Move -> runSuspendCatching {
                // 按原目录分组移回去，每组一次请求
                change.from.entries.groupBy({ it.value }, { it.key }).forEach { (parentId, ids) ->
                    access.move(ids, parentId).getOrThrow()
                    access.publish(DriveChange.Moved(ids, setOf(change.targetId), parentId))
                }
            }
            is Change.Trash -> access.restore(change.ids).onSuccess { access.publish(DriveChange.Restored(change.ids)) }
            // 倒着改回去：批量重命名按顺序避开了中间冲突，反过来走同样避开
            is Change.Rename -> runSuspendCatching {
                val completed = state.value.undo[change.id]?.completedRenameSteps ?: 0
                change.renames.asReversed().drop(completed).forEachIndexed { offset, step ->
                    val current = access.detail(step.id).getOrThrow()
                    check(!current.trashed && (current.name == step.newName || current.name == step.oldName)) { "文件名称已发生其他变化" }
                    if (current.name != step.oldName) {
                        val written = access.rename(step.id, step.oldName)
                        if (written.isFailure && access.detail(step.id).getOrNull()?.name != step.oldName) written.getOrThrow()
                        access.publish(DriveChange.Renamed(step.id, step.oldName, current.parentId))
                    }
                    // 临时名互换会多次修改同一 ID；重试从已确认的反向步骤之后继续。
                    _state.update { state ->
                        val undo = state.undo[change.id]
                        if (state.generation != generation || undo == null) state else state.copy(undo = state.undo +
                            (change.id to undo.copy(completedRenameSteps = completed + offset + 1)))
                    }
                }
            }
            is Change.Vault -> runSuspendCatching {
                if (change.untrashOnRevert.isNotEmpty()) driveRepo.restore(change.untrashOnRevert).getOrThrow()
                var missing = 0
                change.recreateOnRevert.forEach { (folderId, entries) ->
                    val present = driveRepo.listAllFiles(folderId).getOrThrow().filter { !it.trashed && it.phase == TaskPhase.COMPLETE }
                    val restored = mutableMapOf<String, FileStat>()
                    for (entry in entries) {
                        val existing = present.firstOrNull { entry.matches(it) }
                        if (existing != null) {
                            restored[entry.id] = existing
                            continue
                        }
                        val file = ResolvedFile(path = entry.name, size = entry.size, gcid = entry.gcid)
                        driveRepo.instantCreate(file, folderId, entry.source).onSuccess { id ->
                            restored[entry.id] = entry.toFileStat(folderId).copy(id = id)
                        }.onFailure { missing++ }
                    }
                    if (restored.isNotEmpty()) driveRepo.vault.update(folderId, VaultEdits.restore(restored)).getOrThrow()
                }
                check(missing == 0) { "$missing 个文件云端已无内容，仍留在归档里" }
                change.reverts.forEach { (folderId, revert) -> driveRepo.vault.update(folderId, revert).getOrThrow() }
                if (change.trashOnRevert.isNotEmpty()) driveRepo.trash(change.trashOnRevert).getOrThrow()
            }
        }
        val kind = change::class.simpleName
        // 撤销是反向再做一次，做成的每一步各自经仓库的 applyChange 通知过了，半途失败的也只通知了做成的那些
        result.onSuccess { PikoLog.i(TAG, "已撤销：$kind") }.logFailure(TAG, "撤销失败：$kind")
        return result
    }

    private companion object {
        const val TAG = "DriveChangeJournal"
        const val LIMIT = 20
    }
}
