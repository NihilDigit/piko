package dev.piko.shared.data

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
            /** 按目录记录移入回收站归档时的条目 ID → 原文件映射；撤销时逐项核对真实文件已回到原位才将条目标为已取回。 */
            val untrashByFolder: Map<String, Map<String, FileStat>> = emptyMap(),
            /** 按目录记录恢复归档时新建或从回收站取回的实体文件 ID；撤销时必须先写回该目录清单引用，才允许移入回收站。 */
            val trashByFolder: Map<String, List<String>> = emptyMap(),
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
        val completedRenameSteps: Int = 0, val completedTrashItems: Int = 0)
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

    private val stoppedUndos = MutableStateFlow<Set<Long>>(emptySet())

    fun requestStopUndo(id: Long): Boolean {
        if (state.value.undo[id]?.status != UndoStatus.RUNNING) return false
        stoppedUndos.update { it + id }
        return true
    }

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
                undo = current.undo + (id to UndoResult(UndoStatus.RUNNING, tr("正在撤销", "Undoing", "元に戻しています", "실행 취소 중", "正在復原"), candidate.affectedFileIds(), candidate.affectedManifestFolders(), candidate.affectedTreeIds(),
                    current.undo[id]?.completedRenameSteps ?: 0, current.undo[id]?.completedTrashItems ?: 0)),
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
                val message = if (result.isSuccess) tr("已撤销", "Undone", "元に戻しました", "실행 취소됨", "已復原") else tr("撤销未完成，请先核对当前文件状态", "Undo incomplete. Check current file states first", "元に戻す操作が未完了です。現在のファイル状態を確認してください", "실행 취소가 완료되지 않았습니다. 현재 파일 상태를 확인해 주세요", "復原未完成，請先核對目前檔案狀態")
                _state.update { current ->
                    if (current.generation != generation) current else {
                        val undo = current.undo + (id to UndoResult(if (result.isSuccess) UndoStatus.UNDONE else UndoStatus.FAILED, message,
                            completedRenameSteps = if (result.isSuccess) 0 else current.undo[id]?.completedRenameSteps ?: 0,
                            completedTrashItems = if (result.isSuccess) 0 else current.undo[id]?.completedTrashItems ?: 0))
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
            } finally { stoppedUndos.update { it - id }; accountGuard.cancel() }
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
            is Change.Trash -> runSuspendCatching {
                var completed = state.value.undo[change.id]?.completedTrashItems ?: 0
                for (batch in change.ids.drop(completed).chunked(100)) {
                    check(change.id !in stoppedUndos.value) { tr("撤销已停止，可继续撤销剩余项目", "Undo stopped; you can continue with remaining items", "元に戻す操作を停止しました。残りの項目は続行できます", "실행 취소를 중지했습니다. 나머지 항목을 계속 취소할 수 있습니다", "復原已停止，可繼續復原剩餘項目") }
                    access.restore(batch).getOrThrow()
                    access.publish(DriveChange.Restored(batch))
                    completed += batch.size
                    _state.update { state ->
                        val undo = state.undo[change.id]
                        if (state.generation != generation || undo == null) state else state.copy(undo = state.undo +
                            (change.id to undo.copy(completedTrashItems = completed)))
                    }
                }
            }
            // 倒着改回去：批量重命名按顺序避开了中间冲突，反过来走同样避开
            is Change.Rename -> runSuspendCatching {
                val completed = state.value.undo[change.id]?.completedRenameSteps ?: 0
                val affectedParents = HashSet<String>()
                try {
                    change.renames.asReversed().drop(completed).forEachIndexed { offset, step ->
                        val current = access.detail(step.id).getOrThrow()
                        check(!current.trashed && (current.name == step.newName || current.name == step.oldName)) { tr("文件名称已发生其他变化", "File name has changed again", "ファイル名がさらに変更されています", "파일 이름이 다른 작업으로 변경되었습니다", "檔案名稱已發生其他變更") }
                        if (current.name != step.oldName) {
                            val written = access.rename(step.id, step.oldName)
                            if (written.isFailure && access.detail(step.id).getOrNull()?.name != step.oldName) written.getOrThrow()
                            affectedParents += current.parentId
                        }
                        // 临时名互换会多次修改同一 ID；重试从已确认的反向步骤之后继续。
                        _state.update { state ->
                            val undo = state.undo[change.id]
                            if (state.generation != generation || undo == null) state else state.copy(undo = state.undo +
                                (change.id to undo.copy(completedRenameSteps = completed + offset + 1)))
                        }
                    }
                } finally {
                    // 半途失败也重列已完成的目录，整批撤销只广播一次，避免列表逐项重排。
                    if (affectedParents.isNotEmpty()) access.publish(DriveChange.ContentsChanged(affectedParents))
                }
            }
            is Change.Vault -> runSuspendCatching {
                val allUntrashIds = buildList {
                    addAll(change.untrashOnRevert)
                    change.untrashByFolder.values.forEach { map -> map.values.forEach { add(it.id) } }
                }.distinct()
                val untrashCall = if (allUntrashIds.isEmpty()) {
                    Result.success(Unit)
                } else {
                    access.restore(allUntrashIds).onSuccess { access.publish(DriveChange.Restored(allUntrashIds)) }
                }
                if (change.untrashByFolder.isEmpty()) {
                    untrashCall.getOrThrow()
                } else {
                    val unrecoveredLock = Mutex()
                    var unrecovered = 0
                    coroutineScope {
                        val folderSlots = Semaphore(12)
                        change.untrashByFolder.map { (folderId, entryOriginals) ->
                            async {
                                folderSlots.withPermit {
                                    val liveById = access.listFolder(folderId).getOrThrow()
                                        .filter { !it.isFolder && !it.trashed }
                                        .associateBy { it.id }
                                    val confirmedRestored = LinkedHashMap<String, FileStat>()
                                    var missingInFolder = 0
                                    for ((entryId, orig) in entryOriginals) {
                                        val live = liveById[orig.id]
                                        if (live != null) {
                                            confirmedRestored[entryId] = live
                                        } else {
                                            missingInFolder++
                                        }
                                    }
                                    if (missingInFolder > 0) {
                                        unrecoveredLock.withLock { unrecovered += missingInFolder }
                                    }
                                    if (confirmedRestored.isNotEmpty()) {
                                        val starredRestored = confirmedRestored.filterKeys(access::isVaultEntryStarred)
                                        access.vault.update(folderId, VaultEdits.restore(confirmedRestored)).getOrThrow()
                                        if (starredRestored.isNotEmpty()) {
                                            access.star(starredRestored.values.map { it.id }, value = true).onSuccess {
                                                access.unstarVaultEntries(starredRestored.keys)
                                            }
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    if (unrecovered > 0) {
                        throw untrashCall.exceptionOrNull()
                            ?: IllegalStateException(tr("$unrecovered 个原文件未能从回收站恢复，已保留在归档中", "${enCount(unrecovered, "file", "files")} could not be restored from trash; kept in archive", "$unrecovered ファイルをゴミ箱から復元できなかったため、アーカイブに保持しました", "파일 ${unrecovered}개를 휴지통에서 복원하지 못해 보관 상태로 유지했습니다", "$unrecovered 個原始檔案未能從資源回收筒還原，已保留於歸檔中"))
                    }
                }
                val resultLock = Mutex()
                var missing = 0
                if (change.recreateOnRevert.isNotEmpty()) {
                    coroutineScope {
                        val folderSlots = Semaphore(12)
                        val recreateSlots = Semaphore(16)
                        change.recreateOnRevert.map { (folderId, entries) ->
                            async {
                                folderSlots.withPermit {
                                    val present = access.listFolder(folderId).getOrThrow()
                                        .filter { !it.trashed && it.phase == TaskPhase.COMPLETE }
                                    val restored = mutableMapOf<String, FileStat>()
                                    val usedPresent = HashSet<String>()
                                    val toRecreate = ArrayList<VaultEntry>()
                                    for (entry in entries) {
                                        val existing = present.firstOrNull { it.id !in usedPresent && entry.matches(it) }
                                        if (existing != null) {
                                            usedPresent += existing.id
                                            restored[entry.id] = existing
                                        } else {
                                            toRecreate += entry
                                        }
                                    }
                                    toRecreate.map { entry ->
                                        async {
                                            recreateSlots.withPermit {
                                                val file = ResolvedFile(path = entry.name, size = entry.size, gcid = entry.gcid)
                                                access.instantCreate(file, folderId, entry.source, notifyChange = false)
                                                    .onSuccess { id ->
                                                        resultLock.withLock {
                                                            restored[entry.id] = entry.toFileStat(folderId).copy(id = id)
                                                        }
                                                    }
                                                    .onFailure {
                                                        resultLock.withLock { missing++ }
                                                    }
                                            }
                                        }
                                    }.awaitAll()
                                    val snapshot = resultLock.withLock { restored.toMap() }
                                    if (snapshot.isNotEmpty()) {
                                        var updatedManifest = false
                                        try {
                                            val starredRestored = snapshot.filterKeys(access::isVaultEntryStarred)
                                            access.vault.update(folderId, VaultEdits.restore(snapshot)).getOrThrow()
                                            updatedManifest = true
                                            if (starredRestored.isNotEmpty()) {
                                                access.star(starredRestored.values.map { it.id }, value = true).onSuccess {
                                                    access.unstarVaultEntries(starredRestored.keys)
                                                }
                                            }
                                        } finally {
                                            if (!updatedManifest) {
                                                access.publish(DriveChange.ContentsChanged(setOf(folderId)))
                                            }
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                check(missing == 0) { tr("$missing 个文件在云端不可用，已保留在归档中", "${enCount(missing, "file", "files")} unavailable in the cloud; kept in archive", "$missing ファイルはクラウドで利用できないため、アーカイブに保持しました", "파일 ${missing}개를 클라우드에서 사용할 수 없어 보관 상태로 유지했습니다", "$missing 個檔案在雲端無法使用，已保留於歸檔中") }
                val standaloneReverts = change.reverts.filterKeys { it !in change.untrashByFolder }
                if (standaloneReverts.isNotEmpty()) {
                    coroutineScope {
                        val folderSlots = Semaphore(12)
                        standaloneReverts.map { (folderId, revert) ->
                            async {
                                folderSlots.withPermit {
                                    access.vault.update(folderId, revert).getOrThrow()
                                    val folderTrash = change.trashByFolder[folderId].orEmpty()
                                    if (folderTrash.isNotEmpty()) {
                                        access.trash(folderTrash)
                                            .onSuccess { access.publish(DriveChange.Trashed(folderTrash, setOf(folderId))) }
                                            .getOrThrow()
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                val handledTrashIds = change.trashByFolder.filterKeys { it in standaloneReverts }.values.flatten().toSet()
                val legacyTrash = change.trashOnRevert.filterNot { it in handledTrashIds }
                if (legacyTrash.isNotEmpty() && standaloneReverts.isNotEmpty()) {
                    access.trash(legacyTrash)
                        .onSuccess { access.publish(DriveChange.Trashed(legacyTrash, standaloneReverts.keys)) }
                        .getOrThrow()
                }
            }
        }
        val kind = change::class.simpleName
        // 撤销是反向再做一次；已完成的改动经仓库通知，重命名按目录合并，半途失败也会通知。
        result.onSuccess { PikoLog.i(TAG, "已撤销：$kind") }.logFailure(TAG, "撤销失败：$kind")
        return result
    }

    private companion object {
        const val TAG = "DriveChangeJournal"
        const val LIMIT = 20
    }
}
