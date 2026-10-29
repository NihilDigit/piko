package dev.piko.shared.state

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DriveWriteAccess
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

private val fileWriteIds = MutableStateFlow(0L)

/** 普通文件写操作的执行与运行期结果。页面只提交参数；重新查询永远不重新提交写请求。 */
class FileWriteSession(
    private val repository: PikoDriveRepository,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 3_000,
) {
    private val executions = AccountExecutionScopes(repository, scope)
    private val _works = MutableStateFlow<List<FileWriteWork>>(emptyList())
    val works = _works.asStateFlow()
    private val _outcomes = MutableSharedFlow<FileWriteOutcome>(extraBufferCapacity = 16)
    val outcomes = _outcomes.asSharedFlow()
    private val generation = MutableStateFlow(0L)
    private val accountGenerations = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val jobs = MutableStateFlow<Map<Long, Job>>(emptyMap())

    internal fun submit(command: FileWriteCommand): Long? {
        val account = repository.currentAccount ?: return null
        val access = repository.writeAccess(account) ?: return null
        val work = FileWriteWork(fileWriteIds.getAndUpdate { it + 1 } + 1, account, command.frozen(), now())
        _works.update { it + work }
        execute(work, access, recheck = false)
        return work.id
    }

    fun canRecheck(id: Long): Boolean {
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        if (work.account != repository.currentAccount || work.result?.unconfirmed.isNullOrEmpty()) return false
        if (jobs.value[id]?.isActive == true) return false
        // 新建请求丢失应答时没有可查询的文件标识，不能把同目录中别人创建的文件认作本次产出。
        if (work.command is FileWriteCommand.CreateFolder) return false
        return work.command !is FileWriteCommand.Copy || work.copies.any { copy ->
            copy.phase != TaskPhase.ERROR && copy.sources.any { it in work.result.unconfirmed }
        }
    }

    fun recheck(id: Long): Boolean {
        if (!canRecheck(id)) return false
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        val access = repository.writeAccess(work.account) ?: return false
        _works.update { all -> all.map { if (it.id == id) it.copy(stopping = false) else it } }
        execute(work.copy(stopping = false), access, recheck = true)
        return true
    }

    fun requestStop(id: Long): Boolean {
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        if (!work.isRunning || work.account != repository.currentAccount) return false
        _works.update { all -> all.map { if (it.id == id) it.copy(stopping = true) else it } }
        return true
    }

    fun forget(id: Long): Boolean {
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        if (work.isRunning || jobs.value[id]?.isActive == true) return false
        if (work.result?.undoChangeIds.orEmpty().any {
            repository.changes.state.value.undo[it]?.status == DriveChangeJournal.UndoStatus.RUNNING
        }) return false
        _works.update { it.filterNot { item -> item.id == id } }
        return true
    }

    fun clear(account: String? = null) {
        if (account == null) generation.update { it + 1 }
        else accountGenerations.update { it + (account to (it[account].orZero() + 1)) }
        val ids = _works.value.filter { account == null || it.account == account }.mapTo(HashSet()) { it.id }
        jobs.getAndUpdate { all -> if (account == null) emptyMap() else all - ids }.filterKeys { account == null || it in ids }.values.forEach { it.cancel() }
        _works.update { all -> if (account == null) emptyList() else all.filterNot { it.account == account } }
    }

    private fun execute(initial: FileWriteWork, access: DriveWriteAccess, recheck: Boolean) {
        val epoch = generation.value
        val accountEpoch = accountGenerations.value[initial.account].orZero()
        val owner = executions.forAccount(initial.account)
        val job = owner.launch(start = CoroutineStart.LAZY) {
            val execution = requireNotNull(currentCoroutineContext()[Job])
            val guard = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.accountChanges.first { it != initial.account }
                execution.cancel()
            }
            fun active(): Boolean = epoch == generation.value && accountEpoch == accountGenerations.value[initial.account].orZero() && repository.currentAccount == initial.account
            suspend fun check() { currentCoroutineContext().ensureActive(); check(active()) }
            var work = initial.copy(result = null, phase = if (recheck) FileWritePhase.CHECKING else FileWritePhase.PREPARING)
            fun publish() { if (active()) _works.update { all -> all.map {
                if (it.id == work.id) work.copy(stopping = it.stopping || work.stopping).also { updated -> work = updated } else it
            } } }
            val previous = initial.result
            val confirmed = previous?.confirmed.orEmpty().toMutableSet()
            val unknown = previous?.unconfirmed.orEmpty().toMutableSet()
            val failed = previous?.failed.orEmpty().toMutableSet()
            val notAttempted = previous?.notAttempted.orEmpty().toMutableSet()
            val undo = previous?.undoChangeIds.orEmpty().toMutableList()
            var createdId = previous?.createdId
            fun finish(message: String? = null) {
                val completion = when {
                    unknown.isNotEmpty() && confirmed.isEmpty() -> FileWriteCompletion.UNCONFIRMED
                    unknown.isNotEmpty() || (confirmed.isNotEmpty() && (failed.isNotEmpty() || notAttempted.isNotEmpty())) -> FileWriteCompletion.PARTIAL
                    failed.isNotEmpty() || notAttempted.isNotEmpty() -> FileWriteCompletion.FAILED
                    else -> FileWriteCompletion.SUCCEEDED
                }
                val text = message ?: when (completion) {
                    FileWriteCompletion.SUCCEEDED -> tr("已完成", "Completed", "完了", "완료")
                    FileWriteCompletion.PARTIAL -> tr("已确认 ${confirmed.size} 项完成；${unknown.size} 项待确认，${failed.size} 项失败，${notAttempted.size} 项未执行", "${confirmed.size} completed; ${unknown.size} unconfirmed, ${failed.size} failed, ${notAttempted.size} not attempted", "${confirmed.size} 項目完了、${unknown.size} 項目未確認、${failed.size} 項目失敗、${notAttempted.size} 項目未実行", "${confirmed.size}개 완료, ${unknown.size}개 미확인, ${failed.size}개 실패, ${notAttempted.size}개 미실행")
                    FileWriteCompletion.FAILED -> tr("操作未完成；${failed.size} 项失败，${notAttempted.size} 项未执行", "Operation incomplete; ${failed.size} failed, ${notAttempted.size} not attempted", "操作未完了。${failed.size} 項目失敗、${notAttempted.size} 項目未実行", "작업 미완료. ${failed.size}개 실패, ${notAttempted.size}개 미실행")
                    FileWriteCompletion.UNCONFIRMED -> tr("结果尚未确认，请查询任务或检查文件，避免重复提交", "Result unconfirmed. Check Tasks or files to avoid duplicate submissions", "結果は未確認です。二重送信を避けるため、タスクかファイルを確認してください", "결과가 미확인입니다. 중복 제출을 피하려면 작업이나 파일을 확인해 주세요")
                }
                val result = FileWriteResult(completion, text, confirmed.toSet(), unknown.toSet(), notAttempted.toSet(),
                    failed.toSet(), undo.toList(), createdId, now())
                work = work.copy(phase = FileWritePhase.FINISHED, result = result,
                    before = work.before.filterKeys { it in unknown })
                if (active()) jobs.update { if (it[initial.id] === execution) it - initial.id else it }
                publish()
                if (active()) {
                    _works.update { all ->
                        val retained = all.filter { it.result != null && it.result.unconfirmed.isEmpty() }.takeLast(100).mapTo(HashSet()) { it.id }
                        all.filter { it.result == null || it.result.unconfirmed.isNotEmpty() || it.id in retained }
                    }
                    _outcomes.tryEmit(FileWriteOutcome(work.account, work.id, work.command.title, result))
                }
            }
            fun record(newlyConfirmed: Set<String>) {
                if (newlyConfirmed.isEmpty() || !active()) return
                val command = work.command
                val change = when (command) {
                    is FileWriteCommand.Move -> DriveChangeJournal.Change.Move(
                        newlyConfirmed.associateWith { work.before.getValue(it).parentId }, command.target.id, tr("已移动 ${newlyConfirmed.size} 项", "Moved ${enCount(newlyConfirmed.size, "item", "items")}", "${newlyConfirmed.size} 項目を移動しました", "${newlyConfirmed.size}개 항목 이동 완료"))
                    is FileWriteCommand.Rename -> work.before[command.id]?.takeIf { it.name != command.name }?.let {
                        DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(command.id, it.name, command.name)), tr("已重命名", "Renamed", "名前を変更しました", "이름을 변경했습니다"))
                    }
                    else -> null
                }
                change?.let { repository.changes.record(it); undo += it.id }
                val ids = newlyConfirmed.toList()
                val fact = when (command) {
                    is FileWriteCommand.Move -> DriveChange.Moved(ids, ids.mapTo(HashSet()) { work.before.getValue(it).parentId }, command.target.id)
                    is FileWriteCommand.Rename -> DriveChange.Renamed(command.id, command.name, work.before[command.id]?.parentId)
                    is FileWriteCommand.Restore -> DriveChange.Restored(ids)
                    is FileWriteCommand.Delete -> DriveChange.Deleted(ids, emptySet())
                    is FileWriteCommand.Copy -> DriveChange.Copied(ids, command.target.id)
                    is FileWriteCommand.Star -> DriveChange.ContentsChanged(null)
                    is FileWriteCommand.CreateFolder -> return
                }
                access.publish(fact)
            }
            suspend fun verify(ids: List<String>): Set<String> {
                work = work.copy(phase = FileWritePhase.CHECKING, checked = 0); publish()
                val command = work.command
                if (command is FileWriteCommand.Star) {
                    val starred = access.starredIds().getOrNull() ?: return emptySet()
                    // 缺席只能确认取消星标；详情接口的 starred 字段不可靠。
                    return ids.filterTo(HashSet()) { (it in starred) == command.starred }
                }
                val listed = if (command is FileWriteCommand.Move && ids.size > 4) {
                    access.listFolder(command.target.id).getOrNull().orEmpty()
                        .filter { it.id in ids && !it.trashed && it.parentId == command.target.id }.mapTo(HashSet()) { it.id }
                } else emptySet()
                check()
                val limit = Semaphore(4)
                return listed + coroutineScope {
                    ids.filterNot { it in listed }.map { id -> async {
                        limit.withPermit {
                            val detail = access.detail(id)
                            check()
                            val value = detail.getOrNull()
                            val matches = when (command) {
                                is FileWriteCommand.Move -> value != null && !value.trashed && value.parentId == command.target.id
                                is FileWriteCommand.Rename -> value != null && !value.trashed && value.name == command.name
                                is FileWriteCommand.Restore -> value != null && !value.trashed
                                is FileWriteCommand.Delete -> (detail.exceptionOrNull() as? PikPakException)?.errorMessage == "file_not_found"
                                else -> false
                            }
                            id.takeIf { matches }
                        }
                    } }.awaitAll().filterNotNull().toSet()
                }
            }
            suspend fun followCopies() {
                work = work.copy(phase = FileWritePhase.COPYING); publish()
                var queryFailures = 0
                while (true) {
                    check()
                    if (_works.value.firstOrNull { it.id == work.id }?.stopping == true) return
                    var pending = false
                    var unreadable = false
                    val newly = mutableSetOf<String>()
                    val copies = work.copies.map { copy ->
                        if (copy.sources.all { it in confirmed } || copy.phase == TaskPhase.ERROR) return@map copy
                        val task = access.task(copy.id).getOrNull()
                        check()
                        if (task == null) { unreadable = true; pending = true; return@map copy }
                        when (task.phase) {
                            TaskPhase.COMPLETE -> newly += copy.sources
                            TaskPhase.ERROR -> Unit // 任务报错可能已经复制了部分内容，不能断言全部未执行。
                            else -> pending = true
                        }
                        copy.copy(phase = task.phase, progress = task.progress)
                    }
                    work = work.copy(copies = copies, completedCount = (confirmed + newly).size); publish()
                    newly.removeAll(confirmed)
                    confirmed += newly; unknown -= newly; record(newly)
                    queryFailures = if (unreadable) queryFailures + 1 else 0
                    if (!pending || queryFailures >= 5) return
                    delay(pollMillis)
                }
            }
            try {
                publish()
                if (recheck) {
                    if (work.command is FileWriteCommand.Copy) followCopies() else {
                        val newly = verify(unknown.toList())
                        confirmed += newly; unknown -= newly; record(newly)
                    }
                    check(); finish(); return@launch
                }
                val command = work.command
                if (command is FileWriteCommand.CreateFolder) {
                    work = work.copy(phase = FileWritePhase.WRITING); publish()
                    val result = access.createFolder(command.target.id, command.name)
                    check()
                    createdId = result.getOrNull()
                    if (createdId != null) {
                        confirmed += createdId
                        access.publish(DriveChange.Created(command.target.id, createdId, command.name, true))
                    } else if (definiteRefusal(result.exceptionOrNull())) failed += "create" else unknown += "create"
                    finish(); return@launch
                }
                val ids = command.ids
                notAttempted += ids
                if (command is FileWriteCommand.Move || command is FileWriteCommand.Rename) {
                    val slots = Semaphore(4)
                    val details = coroutineScope { ids.map { id -> async {
                        slots.withPermit {
                            val provided = when (command) {
                                is FileWriteCommand.Move -> command.knownBefore[id]
                                is FileWriteCommand.Rename -> command.knownBefore
                                else -> null
                            }
                            val cached = if (initial.account == repository.currentAccount) repository.knownFile(id)?.takeUnless { it.trashed } else null
                            id to (provided ?: cached?.let { FileBefore(it.name, it.parentId) }
                                ?: access.detail(id).getOrNull()?.takeUnless { it.trashed }?.let { FileBefore(it.name, it.parentId) })
                        }
                    } }.awaitAll() }
                    check()
                    if (details.any { it.second == null }) { finish(tr("未能确认文件的原位置或名称，尚未提交修改", "Cannot confirm the original file location or name; changes not submitted", "元のファイルの場所や名前を確認できないため、変更は未送信です", "파일의 원래 위치나 이름을 확인할 수 없어 변경을 제출하지 않았습니다")); return@launch }
                    work = work.copy(before = details.associate { (id, before) -> id to requireNotNull(before) }); publish()
                }
                for (batch in ids.chunked(100)) {
                    check()
                    if (_works.value.firstOrNull { it.id == work.id }?.stopping == true) break
                    work = work.copy(phase = FileWritePhase.WRITING); publish()
                    notAttempted -= batch.toSet()
                    val result = when (command) {
                        is FileWriteCommand.Move -> access.move(batch, command.target.id)
                        is FileWriteCommand.Copy -> access.copy(batch, command.target.id).map { taskIds ->
                            work = work.copy(copies = work.copies + taskIds.map { FileCopyTask(it, batch) }); publish()
                        }
                        is FileWriteCommand.Rename -> access.rename(command.id, command.name)
                        is FileWriteCommand.Restore -> access.restore(batch)
                        is FileWriteCommand.Delete -> access.delete(batch)
                        is FileWriteCommand.Star -> access.star(batch, command.starred)
                        is FileWriteCommand.CreateFolder -> error("handled above")
                    }
                    result.logFailure("FileWrite", "文件操作提交失败，继续核对实际结果")
                    check()
                    if (command is FileWriteCommand.Copy) {
                        if (result.isSuccess && work.copies.any { it.sources == batch }) {
                            unknown += batch
                        } else if (definiteRefusal(result.exceptionOrNull())) failed += batch else unknown += batch
                    } else {
                        val verified = if (command is FileWriteCommand.Delete && result.isSuccess) batch.toSet() else verify(batch)
                        confirmed += verified; record(verified)
                        val remaining = batch.toSet() - verified
                        if (definiteRefusal(result.exceptionOrNull())) failed += remaining else unknown += remaining
                    }
                    if (command !is FileWriteCommand.Copy) work = work.copy(completedCount = work.completedCount + batch.size)
                    publish()
                    if (result.isFailure) break
                }
                if (command is FileWriteCommand.Copy) followCopies()
                check(); finish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                check()
                dev.piko.shared.log.PikoLog.w("FileWrite", "文件处理异常，保留待确认结果", error)
                unknown += work.command.ids.toSet() - confirmed - failed - notAttempted
                if (work.command is FileWriteCommand.CreateFolder && confirmed.isEmpty()) unknown += "create"
                finish(tr("未能确认最终结果，请检查文件后再决定下一步", "Cannot confirm the final result. Check files before continuing", "最終結果を確認できません。次の操作の前にファイルを確認してください", "최종 결과를 확인할 수 없습니다. 다음 작업 전에 파일을 확인해 주세요"))
            } finally {
                guard.cancel()
                if (epoch == generation.value) jobs.update { if (it[initial.id] === execution) it - initial.id else it }
            }
        }
        jobs.update { it + (initial.id to job) }
        job.start()
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
    private fun definiteRefusal(error: Throwable?): Boolean =
        error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)
}

private fun Long?.orZero(): Long = this ?: 0L

private fun FileWriteCommand.frozen(): FileWriteCommand = when (this) {
    is FileWriteCommand.CreateFolder -> copy(name = name.trim())
    is FileWriteCommand.Rename -> copy(name = name.trim())
    is FileWriteCommand.Move -> copy(ids = ids.distinct(), knownBefore = knownBefore.toMap())
    is FileWriteCommand.Copy -> copy(ids = ids.distinct())
    is FileWriteCommand.Restore -> copy(ids = ids.distinct())
    is FileWriteCommand.Delete -> copy(ids = ids.distinct())
    is FileWriteCommand.Star -> copy(ids = ids.distinct())
}
