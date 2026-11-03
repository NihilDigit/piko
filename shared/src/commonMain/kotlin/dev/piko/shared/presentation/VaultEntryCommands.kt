package dev.piko.shared.presentation

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.state.VaultEntryEditCommand
import dev.piko.shared.state.VaultEntryEditSession

sealed interface VaultEntryCommandResult {
    data class Accepted(val workId: Long) : VaultEntryCommandResult
    data class Rejected(val reason: String) : VaultEntryCommandResult
}

class VaultEntryCommands(private val repository: PikoDriveRepository, val session: VaultEntryEditSession,
    private val fileCommands: FileCommands, private val account: String? = repository.currentAccount,
    private val acceptsActions: () -> Boolean = { true }) {
    fun availability(command: VaultEntryEditCommand): ActionAvailability {
        if (account == null || repository.currentAccount != account || !acceptsActions()) return ActionAvailability.Blocked(tr("当前浏览会话已结束", "This browsing session has ended", "閲覧セッションは終了しました", "현재 탐색 세션이 종료되었습니다", "目前的瀏覽工作階段已結束"))
        if (command.entries.isEmpty() || command.entries.any { !isDriveFolderId(it.key) || it.value.isEmpty() || it.value.any(String::isBlank) }) return ActionAvailability.Blocked(tr("无可编辑的归档条目", "No archive items to edit", "編集可能なアーカイブ項目がありません", "편집할 보관 항목이 없습니다", "無可編輯的歸檔項目"))
        if (command is VaultEntryEditCommand.Rename && command.name.isBlank()) return ActionAvailability.Blocked(tr("名称不能为空", "Name cannot be empty", "名前を入力してください", "이름을 입력해 주세요", "名稱不可為空"))
        if (session.works.value.any { it.account == account && it.pendingFolders.any { folder -> folder in command.entries } }) return ActionAvailability.Blocked(tr("此文件夹的归档清单正在编辑或待确认，请先在任务页处理", "This archive manifest is being edited or unconfirmed. Check Tasks first", "同じアーカイブ一覧を編集中または未確認です。先にタスク画面で確認してください", "동일한 보관 목록이 편집 중이거나 미확인 상태입니다. 먼저 작업 페이지에서 확인해 주세요", "此資料夾的歸檔清單正在編輯或待確認，請先至任務頁處理"))
        return fileCommands.manifestAvailability(command.entries.keys)
    }
    fun submit(command: VaultEntryEditCommand): VaultEntryCommandResult = when (val state = availability(command)) {
        is ActionAvailability.Blocked -> VaultEntryCommandResult.Rejected(state.reason)
        ActionAvailability.Available -> session.submit(command)?.let(VaultEntryCommandResult::Accepted) ?: VaultEntryCommandResult.Rejected(tr("账号已切换，未提交修改", "Account changed; edit not submitted", "アカウントが切り替わったため、編集は送信されませんでした", "계정이 전환되어 편집을 제출하지 않았습니다", "帳號已切換，未提交修改"))
    }
}
