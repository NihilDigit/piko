package dev.piko.shared.presentation

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.HistoryEditCompletion
import dev.piko.shared.state.HistoryEditCommand
import dev.piko.shared.state.HistoryEditSession

class HistoryCommands(private val repository: PikoDriveRepository, val session: HistoryEditSession,
    private val account: String? = repository.currentAccount, private val acceptsActions: () -> Boolean = { true }) {
    fun availability(command: HistoryEditCommand): ActionAvailability {
        if (account == null || !acceptsActions() || account != repository.currentAccount) return ActionAvailability.Blocked(tr("当前浏览上下文已结束", "This browsing session has ended", "閲覧セッションは終了しました", "현재 탐색 세션이 종료되었습니다"))
        if (command is HistoryEditCommand.Remove && (command.eventIds.isEmpty() || command.eventIds.any { it.isBlank() })) return ActionAvailability.Blocked(tr("没有可移除的记录", "No records to remove", "削除できる記録がありません", "삭제할 기록이 없습니다"))
        val pending = session.works.value.firstOrNull { work ->
            work.account == account && work.command.kind == command.kind && (work.isRunning || work.result?.completion in setOf(HistoryEditCompletion.PARTIAL, HistoryEditCompletion.UNCONFIRMED)) &&
                (command == HistoryEditCommand.ClearPlay || work.command == HistoryEditCommand.ClearPlay ||
                    (command is HistoryEditCommand.Remove && work.command is HistoryEditCommand.Remove && command.eventIds.any { it in work.command.eventIds && it !in work.result?.confirmedEvents.orEmpty() }))
        }
        return if (pending == null) ActionAvailability.Available else ActionAvailability.Blocked(tr("相关历史记录有进行中或待确认的操作，请先在任务页处理", "History operations are running or unconfirmed. Check Tasks first", "関連履歴の操作が進行中か未確認です。先にタスク画面で確認してください", "관련 기록 작업이 진행 중이거나 미확인입니다. 먼저 작업 페이지에서 확인해 주세요"))
    }
    fun submit(command: HistoryEditCommand): HistoryCommandResult = when (val state = availability(command)) {
        is ActionAvailability.Blocked -> HistoryCommandResult.Rejected(state.reason)
        ActionAvailability.Available -> session.submit(command)?.let(HistoryCommandResult::Accepted) ?: HistoryCommandResult.Rejected(tr("账号已改变，尚未提交操作", "Account changed; operation not submitted", "アカウントが変わったため、操作は送信されていません", "계정이 변경되어 작업을 제출하지 않았습니다"))
    }
}

sealed interface HistoryCommandResult {
    data class Accepted(val workId: Long) : HistoryCommandResult
    data class Rejected(val reason: String) : HistoryCommandResult
}
