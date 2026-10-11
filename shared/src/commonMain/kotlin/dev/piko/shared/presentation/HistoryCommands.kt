package dev.piko.shared.presentation

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.state.HistoryEditCompletion
import dev.piko.shared.state.HistoryEditCommand
import dev.piko.shared.state.HistoryEditSession

class HistoryCommands(private val repository: PikoDriveRepository, val session: HistoryEditSession,
    private val account: String? = repository.currentAccount, private val acceptsActions: () -> Boolean = { true }) {
    fun availability(command: HistoryEditCommand): ActionAvailability {
        if (account == null || !acceptsActions() || account != repository.currentAccount) return ActionAvailability.Blocked("当前浏览上下文已结束")
        if (command is HistoryEditCommand.Remove && (command.eventIds.isEmpty() || command.eventIds.any { it.isBlank() })) return ActionAvailability.Blocked("没有可移除的记录")
        val pending = session.works.value.firstOrNull { work ->
            work.account == account && work.command.kind == command.kind && (work.isRunning || work.result?.completion in setOf(HistoryEditCompletion.PARTIAL, HistoryEditCompletion.UNCONFIRMED)) &&
                (command == HistoryEditCommand.ClearPlay || work.command == HistoryEditCommand.ClearPlay ||
                    (command is HistoryEditCommand.Remove && work.command is HistoryEditCommand.Remove && command.eventIds.any { it in work.command.eventIds && it !in work.result?.confirmedEvents.orEmpty() }))
        }
        return if (pending == null) ActionAvailability.Available else ActionAvailability.Blocked("相关历史记录有进行中或待确认的操作，请先在任务页处理")
    }
    fun submit(command: HistoryEditCommand): HistoryCommandResult = when (val state = availability(command)) {
        is ActionAvailability.Blocked -> HistoryCommandResult.Rejected(state.reason)
        ActionAvailability.Available -> session.submit(command)?.let(HistoryCommandResult::Accepted) ?: HistoryCommandResult.Rejected("账号已改变，尚未提交操作")
    }
}

sealed interface HistoryCommandResult {
    data class Accepted(val workId: Long) : HistoryCommandResult
    data class Rejected(val reason: String) : HistoryCommandResult
}
