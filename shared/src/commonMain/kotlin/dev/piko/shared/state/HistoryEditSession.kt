package dev.piko.shared.state

import dev.piko.shared.data.HistoryKind
import dev.piko.shared.data.HistoryWriteAccess
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.log.logFailure
import io.github.nihildigit.pikpak.PikPakException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.time.Clock

sealed interface HistoryEditCommand {
    val kind: HistoryKind
    val title: String
    data class Remove(override val kind: HistoryKind, val eventIds: List<String>) : HistoryEditCommand {
        override val title get() = "从${kind.title}移除 ${eventIds.size} 项记录"
    }
    data object ClearPlay : HistoryEditCommand {
        override val kind = HistoryKind.PLAY
        override val title = "清空播放历史"
    }
}

enum class HistoryEditCompletion { SUCCEEDED, PARTIAL, FAILED, UNCONFIRMED }

data class HistoryEditResult(
    val completion: HistoryEditCompletion,
    val message: String,
    val confirmedEvents: Set<String> = emptySet(),
    val finishedAtMs: Long,
)
data class HistoryEditWork(val id: Long, val account: String, val command: HistoryEditCommand,
    val createdAtMs: Long, val result: HistoryEditResult? = null) {
    val isRunning: Boolean get() = result == null
}
data class HistoryEditOutcome(val account: String, val id: Long, val title: String, val result: HistoryEditResult)

private val historyEditIds = MutableStateFlow(0L)

/** 删除事件与文件写入分别执行。保留结果；响应丢失后的查询不会再次删除或清空。 */
class HistoryEditSession(private val repository: PikoDriveRepository, private val scope: CoroutineScope) {
    private val executionScopes = AccountExecutionScopes(repository, scope)
    private val _works = MutableStateFlow<List<HistoryEditWork>>(emptyList())
    val works = _works.asStateFlow()
    private val jobs = MutableStateFlow<Map<Long, Job>>(emptyMap())
    private val _revisions = MutableStateFlow<Map<HistoryKind, Long>>(emptyMap())
    val revisions = _revisions.asStateFlow()
    private val _outcomes = MutableSharedFlow<HistoryEditOutcome>(extraBufferCapacity = 16)
    val outcomes = _outcomes.asSharedFlow()

    internal fun submit(command: HistoryEditCommand): Long? {
        if (!scope.isActive) return null
        val account = repository.currentAccount ?: return null
        val access = repository.historyAccess(account) ?: return null
        val frozen = if (command is HistoryEditCommand.Remove) command.copy(eventIds = command.eventIds.distinct()) else command
        val work = HistoryEditWork(historyEditIds.getAndUpdate { it + 1 } + 1, account, frozen, now())
        _works.update { it + work }
        execute(work, access, recheck = false)
        return work.id
    }

    fun canRecheck(id: Long): Boolean = _works.value.any { it.id == id && it.account == repository.currentAccount &&
        it.result?.completion in setOf(HistoryEditCompletion.UNCONFIRMED, HistoryEditCompletion.PARTIAL) }

    fun recheck(id: Long): Boolean {
        if (!canRecheck(id) || jobs.value[id]?.isActive == true) return false
        val work = _works.value.firstOrNull { it.id == id } ?: return false
        val access = repository.historyAccess(work.account) ?: return false
        execute(work, access, recheck = true)
        return true
    }

    fun forget(id: Long): Boolean {
        if (_works.value.none { it.id == id && !it.isRunning }) return false
        _works.update { it.filterNot { work -> work.id == id } }
        return true
    }

    fun clear(account: String) {
        val ids = _works.value.filter { it.account == account }.mapTo(HashSet()) { it.id }
        _works.update { it.filterNot { work -> work.account == account } }
        jobs.getAndUpdate { it - ids }.filterKeys { it in ids }.values.forEach { it.cancel() }
    }

    private fun active(work: HistoryEditWork): Boolean = repository.currentAccount == work.account && _works.value.any { it.id == work.id }

    private fun execute(initial: HistoryEditWork, access: HistoryWriteAccess, recheck: Boolean) {
        val job = executionScopes.forAccount(initial.account).launch(start = CoroutineStart.LAZY) {
            val execution = currentCoroutineContext()[Job]
            var work = initial.copy(result = null)
            fun publish() { _works.update { all -> all.map { if (it.id == work.id) work else it } } }
            try {
                if (!active(work)) return@launch
                publish()
                val response = if (recheck) null else when (val command = work.command) {
                    is HistoryEditCommand.Remove -> access.remove(command.eventIds)
                    HistoryEditCommand.ClearPlay -> access.clearPlay()
                }.logFailure("History", "修改历史记录失败，保留结果并核对")
                currentCoroutineContext().ensureActive()
                if (!active(work)) return@launch
                val result = if (response?.isSuccess == true) {
                    HistoryEditResult(HistoryEditCompletion.SUCCEEDED, "已完成", (work.command as? HistoryEditCommand.Remove)?.eventIds.orEmpty().toSet(), now())
                } else verify(access, work.command, initial.result?.confirmedEvents.orEmpty(), response?.exceptionOrNull())
                currentCoroutineContext().ensureActive()
                if (!active(work)) return@launch
                work = work.copy(result = result)
                jobs.update { if (it[work.id] === execution) it - work.id else it }
                publish()
                _revisions.update { it + (work.command.kind to ((it[work.command.kind] ?: 0) + 1)) }
                _works.update { all ->
                    val retained = all.filter { it.result?.completion in setOf(HistoryEditCompletion.SUCCEEDED, HistoryEditCompletion.FAILED) }.takeLast(100).mapTo(HashSet()) { it.id }
                    all.filter { it.isRunning || it.result?.completion in setOf(HistoryEditCompletion.PARTIAL, HistoryEditCompletion.UNCONFIRMED) || it.id in retained }
                }
                _outcomes.tryEmit(HistoryEditOutcome(work.account, work.id, work.command.title, result))
            } finally {
                jobs.update { if (it[work.id] === execution) it - work.id else it }
            }
        }
        jobs.update { it + (initial.id to job) }
        job.start()
    }

    private suspend fun verify(access: HistoryWriteAccess, command: HistoryEditCommand, previous: Set<String>, error: Throwable?): HistoryEditResult {
        val targets = (command as? HistoryEditCommand.Remove)?.eventIds.orEmpty().toSet() - previous
        val present = mutableSetOf<String>()
        var token = ""
        val tokens = mutableSetOf<String>()
        var complete = false
        repeat(10) {
            val page = access.list(command.kind, token).getOrNull() ?: return result(targets, present, previous, complete, error)
            if (command == HistoryEditCommand.ClearPlay) {
                val completion = if (page.events.isEmpty() && page.nextPageToken.isEmpty()) HistoryEditCompletion.SUCCEEDED else failureKind(error)
                val message = when (completion) {
                    HistoryEditCompletion.SUCCEEDED -> "播放历史已清空"
                    HistoryEditCompletion.FAILED -> "未能清空播放历史"
                    else -> "清空结果尚未确认，请检查播放历史后再决定下一步"
                }
                return HistoryEditResult(completion, message, finishedAtMs = now())
            }
            present += page.events.map { it.id }.filter { it in targets }
            if (page.nextPageToken.isEmpty()) { complete = true; return result(targets, present, previous, complete, error) }
            if (present.containsAll(targets) || !tokens.add(page.nextPageToken)) return result(targets, present, previous, complete, error)
            token = page.nextPageToken
        }
        return result(targets, present, previous, complete, error)
    }

    private fun result(targets: Set<String>, present: Set<String>, previous: Set<String>, complete: Boolean, error: Throwable?): HistoryEditResult {
        val confirmed = previous + if (complete) targets - present else emptySet()
        val completion = when {
            complete && present.isEmpty() -> HistoryEditCompletion.SUCCEEDED
            confirmed.isNotEmpty() -> HistoryEditCompletion.PARTIAL
            else -> failureKind(error)
        }
        val message = when (completion) {
            HistoryEditCompletion.SUCCEEDED -> "已移除记录"
            HistoryEditCompletion.PARTIAL -> "已确认移除 ${confirmed.size} 项，其余结果尚未确认"
            HistoryEditCompletion.FAILED -> "未能移除记录"
            HistoryEditCompletion.UNCONFIRMED -> "结果尚未确认，重新查询不会再次提交删除"
        }
        return HistoryEditResult(completion, message, confirmed, now())
    }

    private fun failureKind(error: Throwable?): HistoryEditCompletion = if (error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)) HistoryEditCompletion.FAILED else HistoryEditCompletion.UNCONFIRMED
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}
