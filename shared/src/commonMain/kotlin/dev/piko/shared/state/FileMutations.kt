package dev.piko.shared.state

import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

/** Projection and undo are committed before dependent edits may run. */
class FileMutations(private val repository: PikoDriveRepository, scope: CoroutineScope) {
    private val executions = AccountExecutionScopes(repository, scope)
    private val _active = MutableStateFlow<Map<Long, FileWriteCommand>>(emptyMap())
    val active = _active.asStateFlow()
    private val _outcomes = MutableSharedFlow<FileWriteOutcome>(extraBufferCapacity = 32)
    val outcomes = _outcomes.asSharedFlow()
    private val accounts = mutableMapOf<Long, String>()
    private val results = mutableMapOf<Long, Deferred<FileWriteResult>>()

    internal fun submit(id: Long, command: FileWriteCommand): Long? {
        require(command !is FileWriteCommand.Copy)
        val account = repository.currentAccount ?: return null
        val access = repository.writeAccess(account) ?: return null
        accounts[id] = account
        _active.update { it + (id to command) }
        val parts = command.ids.distinct().chunked(if (command is FileWriteCommand.Star) 100 else 500)
        fun change(ids: List<String>): DriveChange = when (command) {
            is FileWriteCommand.Rename -> DriveChange.Renamed(command.id, command.name, repository.knownParentOf(command.id))
            is FileWriteCommand.Move -> DriveChange.Moved(ids, ids.mapNotNullTo(HashSet(), repository::knownParentOf), command.target.id)
            is FileWriteCommand.Trash -> DriveChange.Trashed(ids, ids.mapNotNullTo(HashSet(), repository::knownParentOf))
            is FileWriteCommand.Restore -> DriveChange.Restored(ids)
            is FileWriteCommand.Delete -> DriveChange.Deleted(ids, ids.mapNotNullTo(HashSet(), repository::knownParentOf))
            is FileWriteCommand.Star -> DriveChange.Starred(ids, command.starred, ids.mapNotNullTo(HashSet(), repository::knownParentOf))
            else -> error("Creation has no existing entity")
        }
        val writes = parts.map { repository.beginOptimisticWrite(account, change(it)) }
        val settled = BooleanArray(writes.size)
        val succeededParts = BooleanArray(writes.size)
        val baselines = arrayOfNulls<Map<String, FileBefore>>(writes.size)
        val job = executions.forAccount(account).async(start = CoroutineStart.LAZY) {
            val confirmed = mutableSetOf<String>()
            val failed = mutableSetOf<String>()
            val undoIds = mutableListOf<Long>()
            var created: String? = null
            try {
                if (command is FileWriteCommand.CreateFolder) {
                    val response = access.createFolder(command.target.id, command.name)
                        .logFailure("Mutation", "创建文件夹失败")
                    created = response.getOrNull()
                    created?.let {
                        access.publish(DriveChange.Created(command.target.id, it, command.name, true))
                        confirmed += it
                    } ?: run { failed += "create" }
                } else {
                    val permits = Semaphore(8)
                    coroutineScope {
                        parts.mapIndexed { index, ids -> async {
                            val write = writes[index]
                            try {
                                write.awaitTurn()
                                val before = ids.mapNotNull { key -> repository.knownConfirmedFile(key)?.let { key to FileBefore(it.name, it.parentId) } }.toMap()
                                baselines[index] = before
                                val response = permits.withPermit {
                                    when (command) {
                                        is FileWriteCommand.Rename -> access.rename(command.id, command.name)
                                        is FileWriteCommand.Move -> access.move(ids, command.target.id, awaitCompletion = false)
                                        is FileWriteCommand.Trash -> access.trash(ids, awaitCompletion = false)
                                        is FileWriteCommand.Restore -> access.restore(ids, awaitCompletion = false)
                                        is FileWriteCommand.Delete -> access.delete(ids, awaitCompletion = false)
                                        is FileWriteCommand.Star -> access.star(ids, command.starred)
                                        else -> error("Unsupported mutation")
                                    }
                                }.logFailure("Mutation", "文件修改失败")
                                ensureActive()
                                succeededParts[index] = response.isSuccess || (!definiteRefusal(response.exceptionOrNull()) && verifyMutation(command, ids, access))
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                dev.piko.shared.log.PikoLog.w("Mutation", "文件修改未完成", error)
                            }

                        } }.awaitAll()
                    }
                }
                parts.forEachIndexed { index, ids ->
                    if (succeededParts[index]) confirmed += ids else failed += ids
                }
                val before = baselines.filterNotNull().fold(emptyMap<String, FileBefore>()) { all, next -> all + next }
                val inverse = when (command) {
                    is FileWriteCommand.Move -> confirmed.mapNotNull { key -> (before[key] ?: command.knownBefore[key])?.let { key to it.parentId } }.toMap()
                        .takeIf { it.isNotEmpty() }?.let { DriveChangeJournal.Change.Move(it, command.target.id, command.title) }
                    is FileWriteCommand.Rename -> (before[command.id] ?: command.knownBefore)?.takeIf { command.id in confirmed && it.name != command.name }?.let {
                        DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(command.id, it.name, command.name)), command.title)
                    }
                    is FileWriteCommand.Trash -> confirmed.takeIf { it.isNotEmpty() }?.let { DriveChangeJournal.Change.Trash(it.toList(), command.title) }
                    else -> null
                }
                inverse?.let { access.recordChange(it); undoIds += it.id }
                writes.forEachIndexed { index, write ->
                    repository.finishOptimisticWrite(write, succeededParts[index])
                    settled[index] = true
                }
                val completion = when {
                    failed.isEmpty() -> FileWriteCompletion.SUCCEEDED
                    confirmed.isEmpty() -> FileWriteCompletion.FAILED
                    else -> FileWriteCompletion.PARTIAL
                }
                val message = if (failed.isEmpty()) tr("已完成", "Completed", "完了", "완료", "已完成")
                    else tr("${failed.size} 项修改未完成，请刷新后重试", "${failed.size} edits did not complete. Refresh and retry", "${failed.size} 件の変更が未完了です。更新して再試行してください", "변경 ${failed.size}개가 완료되지 않았습니다. 새로고침 후 다시 시도하세요", "${failed.size} 項修改未完成，請重新整理後重試")
                FileWriteResult(completion, message, confirmed = confirmed, failed = failed, undoChangeIds = undoIds,
                    createdId = created, finishedAtMs = Clock.System.now().toEpochMilliseconds()).also {
                    _outcomes.tryEmit(FileWriteOutcome(account, id, command.title, it))
                }
            } finally {
                writes.forEachIndexed { index, write ->
                    if (!settled[index]) {
                        repository.finishOptimisticWrite(write, succeededParts[index])
                        settled[index] = true
                    }
                }
                _active.update { it - id }
            }
        }
        results[id] = job
        job.invokeOnCompletion {
            accounts -= id
            writes.forEachIndexed { index, write -> if (!settled[index]) repository.finishOptimisticWrite(write, false) }
            _active.update { it - id }
        }
        results.entries.removeAll { it.key != id && it.value.isCompleted && results.size > 64 }
        job.start()
        return id
    }

    private fun definiteRefusal(error: Throwable?): Boolean = error is io.github.nihildigit.pikpak.PikPakException &&
        error.httpStatus in setOf(400, 401, 403, 404, 409, 422)

    private suspend fun verifyMutation(command: FileWriteCommand, ids: List<String>, access: dev.piko.shared.data.DriveWriteAccess): Boolean = when (command) {
        is FileWriteCommand.Trash -> access.authoritativeTrashFiles().getOrNull()?.map { it.id }?.containsAll(ids) == true
        is FileWriteCommand.Star -> access.starredIds().getOrNull()?.let { starred -> ids.all { (it in starred) == command.starred } } == true
        is FileWriteCommand.Rename -> access.detail(command.id).getOrNull()?.name == command.name
        is FileWriteCommand.Move -> ids.all { access.detail(it).getOrNull()?.let { file -> !file.trashed && file.parentId == command.target.id } == true }
        is FileWriteCommand.Restore -> ids.all { access.detail(it).getOrNull()?.trashed == false }
        is FileWriteCommand.Delete -> ids.all { (access.detail(it).exceptionOrNull() as? io.github.nihildigit.pikpak.PikPakException)?.errorMessage == "file_not_found" }
        else -> false
    }

    fun clear(account: String?) {
        accounts.filterValues { account == null || it == account }.keys.toList().forEach { id ->
            results[id]?.cancel()
            _active.update { it - id }
        }
    }

    suspend fun awaitResult(id: Long): FileWriteResult? = results[id]?.await()
}
