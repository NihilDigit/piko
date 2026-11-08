package dev.piko.shared.presentation

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.HistoryEditCompletion
import dev.piko.shared.state.HistoryEditCommand
import dev.piko.shared.state.HistoryMutations

class HistoryCommands(private val repository: PikoDriveRepository, val session: HistoryMutations,
    private val account: String? = repository.currentAccount, private val acceptsActions: () -> Boolean = { true }) {
    fun availability(command: HistoryEditCommand): ActionAvailability {
        if (account == null || !acceptsActions() || account != repository.currentAccount) return ActionAvailability.Blocked(tr("当前浏览会话已结束", "This browsing session has ended", "閲覧セッションは終了しました", "현재 탐색 세션이 종료되었습니다", "目前的瀏覽工作階段已結束"))
        if (command is HistoryEditCommand.Remove && (command.eventIds.isEmpty() || command.eventIds.any { it.isBlank() })) return ActionAvailability.Blocked(tr("没有可移除的记录", "No records to remove", "削除できる記録がありません", "삭제할 기록이 없습니다", "沒有可移除的記錄"))
        return ActionAvailability.Available
    }
    fun submit(command: HistoryEditCommand): HistoryCommandResult = when (val state = availability(command)) {
        is ActionAvailability.Blocked -> HistoryCommandResult.Rejected(state.reason)
        ActionAvailability.Available -> session.submit(command)?.let(HistoryCommandResult::Accepted) ?: HistoryCommandResult.Rejected(tr("账号已切换，未提交操作", "Account changed; operation not submitted", "アカウントが切り替わったため、操作は送信されませんでした", "계정이 전환되어 작업을 제출하지 않았습니다", "帳號已切換，未提交操作"))
    }
}

sealed interface HistoryCommandResult {
    data class Accepted(val workId: Long) : HistoryCommandResult
    data class Rejected(val reason: String) : HistoryCommandResult
}
