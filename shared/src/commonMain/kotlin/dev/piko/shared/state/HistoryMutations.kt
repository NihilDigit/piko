package dev.piko.shared.state

import dev.piko.shared.data.HistoryWriteAccess
import io.github.nihildigit.pikpak.PikPakException
import dev.piko.shared.data.HistoryKind
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.media.player.PlayHistoryReporter
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

    init {
        scope.launch {
            PlayHistoryReporter.reportedEvents.collect { event ->
                notifyPlayHistoryReported(event.account)
            }
        }
    }

    /** 云端播放历史成功上报后递增 [HistoryKind.PLAY] 修订号，使已打开的历史记录页即时刷新。 */
    fun notifyPlayHistoryReported(account: String? = null) {
        if (account != null && repository.currentAccount != account) return
        changed(HistoryKind.PLAY)
    }

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
                val actionDesc = when (command) {
                    is HistoryEditCommand.Remove -> "remove(kind=${command.kind}, items=${command.eventIds.size})"
                    HistoryEditCommand.ClearPlay -> "clearPlay"
                }
                val response = when (command) {
                    is HistoryEditCommand.Remove -> access.remove(command.eventIds)
                    HistoryEditCommand.ClearPlay -> access.clearPlay()
                }
                currentCoroutineContext().ensureActive()
                val editResult = if (response.isSuccess) {
                    HistoryEditResult(
                        HistoryEditCompletion.SUCCEEDED,
                        tr("已移除记录", "History removed", "履歴を削除しました", "기록을 삭제했습니다", "已移除記錄"),
                        (command as? HistoryEditCommand.Remove)?.eventIds.orEmpty().toSet(),
                        Clock.System.now().toEpochMilliseconds(),
                    )
                } else {
                    val verified = verify(access, command, emptySet(), response.exceptionOrNull(), id)
                    if (verified.completion == HistoryEditCompletion.SUCCEEDED) {
                        PikoLog.i(TAG, "History $actionDesc confirmed after transport failure (work=$id)")
                    } else {
                        response.logFailure(TAG, "Failed to update history: $actionDesc (work=$id, completion=${verified.completion})")
                    }
                    verified
                }
                _outcomes.tryEmit(HistoryEditOutcome(account, id, command.title, editResult))
            } finally {
                pending.update { it - id }
                changed(command.kind)
            }
        }
        return id
    }

    fun clear(account: String) { pending.update { all -> all.filterValues { it.first != account } } }
    private suspend fun verify(
        access: HistoryWriteAccess,
        command: HistoryEditCommand,
        previous: Set<String>,
        error: Throwable?,
        workId: Long,
    ): HistoryEditResult {
        val targets = (command as? HistoryEditCommand.Remove)?.eventIds.orEmpty().toSet() - previous
        val present = mutableSetOf<String>()
        var token = ""
        val tokens = mutableSetOf<String>()
        var complete = false
        repeat(10) {
            val page = access.list(command.kind, token).getOrElse { listErr ->
                PikoLog.d(TAG, "History verification list failed (kind=${command.kind}, work=$workId): ${listErr.message}")
                return result(targets, present, previous, complete)
            }
            if (command == HistoryEditCommand.ClearPlay) {
                val completion = if (page.events.isEmpty() && page.nextPageToken.isEmpty()) HistoryEditCompletion.SUCCEEDED else HistoryEditCompletion.FAILED
                val message = when (completion) {
                    HistoryEditCompletion.SUCCEEDED -> tr("播放历史已清空", "Playback history cleared", "再生履歴を消去しました", "재생 기록을 지웠습니다", "播放歷史已清空")
                    else -> tr("未能清空播放历史", "Failed to clear playback history", "再生履歴を消去できませんでした", "재생 기록을 지우지 못했습니다", "未能清空播放歷史")
                }
                return HistoryEditResult(completion, message, finishedAtMs = now())
            }
            present += page.events.map { it.id }.filter { it in targets }
            if (page.nextPageToken.isEmpty()) { complete = true; return result(targets, present, previous, complete) }
            if (present.containsAll(targets) || !tokens.add(page.nextPageToken)) return result(targets, present, previous, complete)
            token = page.nextPageToken
        }
        return result(targets, present, previous, complete)
    }

    private fun result(targets: Set<String>, present: Set<String>, previous: Set<String>, complete: Boolean): HistoryEditResult {
        val confirmed = previous + if (complete) targets - present else emptySet()
        val completion = when {
            complete && present.isEmpty() -> HistoryEditCompletion.SUCCEEDED
            confirmed.isNotEmpty() -> HistoryEditCompletion.PARTIAL
            else -> HistoryEditCompletion.FAILED
        }
        val message = when (completion) {
            HistoryEditCompletion.SUCCEEDED -> tr("已移除记录", "Record removed", "記録を削除しました", "기록을 삭제했습니다", "已移除記錄")
            HistoryEditCompletion.PARTIAL -> tr("已移除 ${confirmed.size} 项，其余失败", "${confirmed.size} removed; others failed", "${confirmed.size} 件削除しましたが、残りは失敗しました", "${confirmed.size}개 삭제됨, 나머지 실패", "已移除 ${confirmed.size} 項，其餘失敗")
            HistoryEditCompletion.FAILED -> tr("未能移除记录", "Failed to remove record", "記録を削除できませんでした", "기록을 삭제하지 못했습니다", "未能移除記錄")
        }
        return HistoryEditResult(completion, message, confirmed, now())
    }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val TAG = "HistoryMutations"
    }
}
