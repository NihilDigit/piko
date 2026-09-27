package dev.piko.shared.data

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网盘里做过的改动，能撤回的记在这里：移动、移入回收站、重命名与批量重命名。完成一次改动的地方调 [record]，
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
        val summary: String

        /** [from] 是每一项原来所在的目录。 */
        class Move(val from: Map<String, String>, val targetId: String, override val summary: String) : Change

        class Trash(val ids: List<String>, override val summary: String) : Change

        class Rename(val renames: List<Renamed>, override val summary: String) : Change
    }

    class Renamed(val id: String, val oldName: String, val newName: String)

    /** 做完一次改动或撤销一次，给用户看的一句话。[change] 不为 null 时这一条可以撤销。 */
    class Event(val message: String, val change: Change?)

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val done = ArrayDeque<Change>()
    private val lock = Mutex()

    private val _latest = MutableStateFlow<Change?>(null)

    /** 眼下 Ctrl+Z 会撤掉的那一次；没有可撤销的为 null。状态栏据此给出「撤销」。 */
    val latest: StateFlow<Change?> = _latest.asStateFlow()

    fun record(change: Change) {
        scope.launch {
            lock.withLock {
                done.addLast(change)
                while (done.size > LIMIT) done.removeFirst()
                _latest.value = change
            }
            _events.emit(Event(change.summary, change))
        }
    }

    /** 撤销最近一次还没撤销的改动。没有可撤销的返回 false。 */
    fun undoLast(): Boolean {
        if (done.isEmpty()) return false
        scope.launch {
            val change = lock.withLock { done.removeLastOrNull().also { _latest.value = done.lastOrNull() } } ?: return@launch
            revert(change)
        }
        return true
    }

    /** 撤销 [change]。它已经撤过、或被挤出记录时什么也不做。 */
    fun undo(change: Change) {
        scope.launch {
            val removed = lock.withLock { done.remove(change).also { _latest.value = done.lastOrNull() } }
            if (removed) revert(change)
        }
    }

    private suspend fun revert(change: Change) {
        val result = when (change) {
            is Change.Move -> runCatching {
                // 按原目录分组移回去，每组一次请求
                change.from.entries.groupBy({ it.value }, { it.key }).forEach { (parentId, ids) ->
                    driveRepo.move(ids, parentId).getOrThrow()
                }
            }
            is Change.Trash -> driveRepo.restore(change.ids)
            // 倒着改回去：批量重命名按顺序避开了中间冲突，反过来走同样避开
            is Change.Rename -> runCatching {
                change.renames.asReversed().forEach { driveRepo.rename(it.id, it.oldName).getOrThrow() }
            }
        }
        result.logFailure(TAG, "撤销失败")
        driveRepo.requestRefresh()
        _events.emit(Event(if (result.isSuccess) "已撤销" else "撤销失败，可能已被别处改动", null))
    }

    private companion object {
        const val TAG = "DriveChangeJournal"
        const val LIMIT = 20
    }
}
