package dev.piko.shared.state

import dev.piko.shared.data.HistoryWriteAccess
import io.github.nihildigit.pikpak.PikPakException
import dev.piko.shared.data.HistoryKind
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Clock

class HistoryMutations(private val repository: PikoDriveRepository, scope: CoroutineScope) {
    private val executions = AccountExecutionScopes(repository, scope)
    private val ids = MutableStateFlow(0L)
    private val _revisions = MutableStateFlow<Map<HistoryKind, Long>>(emptyMap())
    val revisions = _revisions.asStateFlow()
    private val _outcomes = MutableSharedFlow<HistoryEditOutcome>(extraBufferCapacity = 16)
    val outcomes = _outcomes.asSharedFlow()
    private val pending = MutableStateFlow<Map<Long, Pair<String, HistoryEditCommand>>>(emptyMap())

    fun hidden(kind: HistoryKind, eventId: String): Boolean = pending.value.values.filter { it.first == repository.currentAccount }.map { it.second }.any {
        it.kind == kind && (it == HistoryEditCommand.ClearPlay || it is HistoryEditCommand.Remove && eventId in it.eventIds)
    }

    private fun changed(kind: HistoryKind) {
        _revisions.update { it + (kind to ((it[kind] ?: 0L) + 1)) }
    }

    internal fun submit(request: HistoryEditCommand): Long? {
        val command = if (request is HistoryEditCommand.Remove) request.copy(eventIds = request.eventIds.distinct()) else request
        val account = repository.currentAccount ?: return null
        val access = repository.historyAccess(account) ?: return null
        val id = ids.getAndUpdate { it + 1 } + 1
        pending.update { it + (id to (account to command)) }
        changed(command.kind)
        executions.forAccount(account).launch {
            try {
                val response = when (command) {
                    is HistoryEditCommand.Remove -> access.remove(command.eventIds)
                    HistoryEditCommand.ClearPlay -> access.clearPlay()
                }.logFailure("History", "修改历史记录失败")
                currentCoroutineContext().ensureActive()
                val completion = if (response.isSuccess) HistoryEditCompletion.SUCCEEDED else HistoryEditCompletion.FAILED
                val message = if (response.isSuccess) tr("已移除记录", "History removed", "履歴を削除しました", "기록을 삭제했습니다", "已移除記錄")
                    else tr("未能修改历史记录，请重试", "Could not update history. Try again", "履歴を更新できませんでした。再試行してください", "기록을 수정하지 못했습니다. 다시 시도하세요", "未能修改歷史記錄，請重試")
                _outcomes.tryEmit(HistoryEditOutcome(account, id, command.title, if (response.isSuccess) HistoryEditResult(completion, message,
                    (command as? HistoryEditCommand.Remove)?.eventIds.orEmpty().toSet(), Clock.System.now().toEpochMilliseconds())
                    else verify(access, command, emptySet(), response.exceptionOrNull())))
            } finally {
                pending.update { it - id }
                changed(command.kind)
            }
        }
        return id
    }

    fun clear(account: String) { pending.update { all -> all.filterValues { it.first != account } } }
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
                    HistoryEditCompletion.SUCCEEDED -> tr("播放历史已清空", "Playback history cleared", "再生履歴を消去しました", "재생 기록을 지웠습니다", "播放歷史已清空")
                    HistoryEditCompletion.FAILED -> tr("未能清空播放历史", "Failed to clear playback history", "再生履歴を消去できませんでした", "재생 기록을 지우지 못했습니다", "未能清空播放歷史")
                    else -> tr("清空结果待确认，请先核对播放历史", "Clear result unconfirmed. Check playback history first", "消去の結果が未確認です。先に再生履歴を確認してください", "비우기 결과가 미확인 상태입니다. 먼저 재생 기록을 확인해 주세요", "清空結果待確認，請先核對播放記錄")
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
            HistoryEditCompletion.SUCCEEDED -> tr("已移除记录", "Record removed", "記録を削除しました", "기록을 삭제했습니다", "已移除記錄")
            HistoryEditCompletion.PARTIAL -> tr("已确认移除 ${confirmed.size} 项，其余结果尚未确认", "${confirmed.size} removals confirmed; remaining results unconfirmed", "${confirmed.size} 件の削除を確認しました。残りは未確認です", "${confirmed.size}개 삭제 확인 완료, 나머지 결과 미확인", "已確認移除 ${confirmed.size} 項，其餘結果尚未確認")
            HistoryEditCompletion.FAILED -> tr("未能移除记录", "Failed to remove record", "記録を削除できませんでした", "기록을 삭제하지 못했습니다", "未能移除記錄")
            HistoryEditCompletion.UNCONFIRMED -> tr("暂未确认移除结果，请刷新历史记录", "Removal not confirmed; refresh history", "削除結果が未確認です。タスク画面で再確認できます", "삭제 결과가 미확인 상태입니다. 작업 페이지에서 다시 확인할 수 있습니다", "移除結果待確認，可至任務頁重新查詢")
        }
        return HistoryEditResult(completion, message, confirmed, now())
    }

    private fun failureKind(error: Throwable?): HistoryEditCompletion = if (error is PikPakException && error.httpStatus in setOf(400, 401, 403, 404, 409, 422)) HistoryEditCompletion.FAILED else HistoryEditCompletion.UNCONFIRMED
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}
