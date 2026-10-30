package dev.piko.shared.presentation

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.ImportPreviewEntryId
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.affectedFileIds
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.affectedManifestFolders
import dev.piko.shared.state.FileWriteCommand
import dev.piko.shared.state.FileWriteSession
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** 菜单、快捷键和拖放共用的动作入口。只阻止已知冲突，不把不同文件串成一个执行队列。 */
class FileCommands(
    private val repository: PikoDriveRepository,
    val session: FileWriteSession,
    private val account: String? = repository.currentAccount,
    private val cleanup: dev.piko.shared.state.TrashSession? = null,
    private val vault: dev.piko.shared.state.FolderVaultSession? = null,
    private val vaultEdits: dev.piko.shared.state.VaultEntryEditSession? = null,
    private val shareSaves: dev.piko.shared.state.ShareSaveSession? = null,
    private val receiving: ReceivingActivity? = null,
    private val acceptsActions: () -> Boolean = { true },
) {
    val receivingChanges: Flow<Unit> get() = merge(
        session.works.map { Unit }, repository.changes.state.map { Unit },
        shareSaves?.works?.map { Unit } ?: emptyFlow(),
        receiving?.changes ?: emptyFlow(),
        snapshotFlow { vault?.activeTargets to cleanup?.projectedIds?.takeIf { cleanup.progress != null || cleanup.isUndoing } }.map { Unit },
    )

    /** 新文件写入可与星标、目录改名及无关文件处理并行；目标整树的处置需要先结束。 */
    fun receivingAvailability(folder: String): ActionAvailability {
        if (!acceptsActions() || account == null || repository.currentAccount != account) return ActionAvailability.Blocked(tr("当前工作区已结束", "This workspace has ended", "このワークスペースは終了しました", "현재 작업 공간이 종료되었습니다", "目前的工作區已結束"))
        if (!isDriveFolderId(folder)) return ActionAvailability.Blocked(tr("请选择网盘中的目标文件夹", "Choose a destination folder in the drive", "ドライブ内の保存先フォルダーを選択してください", "드라이브에서 대상 폴더를 선택해 주세요", "請選擇雲端硬碟中的目標資料夾"))
        if (receiving?.exclusiveRoots(account)?.any { contains(it, folder) } == true)
            return ActionAvailability.Blocked(tr("目标文件夹正在裁剪整包内容，请待完成后再保存", "Destination contents are being trimmed. Save after it finishes", "保存先の一括ダウンロードを整理中です。完了後に保存してください", "대상 폴더의 전체 다운로드를 정리 중입니다. 완료 후 저장해 주세요", "目標資料夾正在裁剪整包內容，請待完成後再儲存"))
        if (repository.changes.state.value.undo.values.any { undo ->
                undo.activeTreeIds.any { contains(it, folder) }
            }) return ActionAvailability.Blocked(tr("目标文件夹正在撤销相关改动，请待完成后再保存", "Destination changes are being reverted. Save after it finishes", "保存先の変更を元に戻しています。完了後に保存してください", "대상 폴더의 변경 사항을 되돌리는 중입니다. 완료 후 저장해 주세요", "目標資料夾正在復原相關變更，請待完成後再儲存"))
        vault?.activeTargets?.let { targets ->
            if ((targets.roots + targets.files).any { contains(it, folder) })
                return ActionAvailability.Blocked(tr("目标文件夹正在归档或恢复，请待完成后再保存", "Destination is being archived or restored. Save after it finishes", "保存先をアーカイブまたは復元中です。完了後に保存してください", "대상 폴더를 보관 또는 복원 중입니다. 완료 후 저장해 주세요", "目標資料夾正在歸檔或還原，請待完成後再儲存"))
        }
        if ((cleanup?.progress != null || cleanup?.isUndoing == true) && cleanup.projectedIds.any { contains(it, folder) })
            return ActionAvailability.Blocked(tr("目标文件夹正在移入回收站或恢复，请待完成后再保存", "Destination is being trashed or restored. Save after it finishes", "保存先をゴミ箱に移動または復元中です。完了後に保存してください", "대상 폴더를 휴지통으로 이동하거나 복원 중입니다. 완료 후 저장해 주세요", "目標資料夾正在移至資源回收筒或還原，請待完成後再儲存"))
        if (shareBlocked(emptyList(), folder, false)) return ActionAvailability.Blocked(tr("目标文件夹中的分享文件正在规范命名，请先在任务页处理", "Shared files at the destination are being renamed. Check Tasks first", "保存先の共有ファイル名を整理中です。先にタスク画面で確認してください", "대상 폴더의 공유 파일 이름을 정리 중입니다. 먼저 작업 페이지에서 확인해 주세요", "目標資料夾中的分享檔案正在規範命名，請先至任務頁處理"))
        val conflict = session.works.value.firstOrNull { work ->
            work.account == account && (work.isRunning || work.result?.unconfirmed?.isNotEmpty() == true) &&
                changesTree(work.command) && pendingCommand(work).ids.any { contains(it, folder) }
        }
        return if (conflict == null) ActionAvailability.Available else ActionAvailability.Blocked(
            if (conflict.isRunning) tr("目标文件夹正在移动、删除或恢复，请待完成后再保存", "Destination is being moved, deleted or restored. Save after it finishes", "保存先を移動、削除または復元中です。完了後に保存してください", "대상 폴더를 이동, 삭제 또는 복원 중입니다. 완료 후 저장해 주세요", "目標資料夾正在移動、刪除或還原，請待完成後再儲存") else tr("目标文件夹的上次修改尚未确认，请先在任务页核对", "Previous destination changes are unconfirmed. Check Tasks first", "保存先の前回の変更が未確認です。先にタスク画面で確認してください", "대상 폴더의 이전 변경 결과가 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "目標資料夾的上次變更尚未確認，請先至任務頁核對"),
        )
    }

    fun availability(command: FileWriteCommand): ActionAvailability {
        fun blocked(reason: String) = ActionAvailability.Blocked(reason)
        if (!acceptsActions() || account == null || repository.currentAccount != account) return blocked(tr("当前浏览会话已结束，请重新打开", "This browsing session ended. Open it again", "閲覧セッションが終了しました。開き直してください", "탐색 세션이 종료되었습니다. 다시 열어 주세요", "目前的瀏覽工作階段已結束，請重新開啟"))
        if (command.ids.any {
                it.isBlank() || VaultEntry.isVaulted(it) || ArchiveEntryId.isEntry(it) ||
                    ArchiveLocation.of(it) != null || ImportPreviewEntryId.isEntry(it) || ImportPreviewLocation.of(it) != null
            }) {
            return blocked(tr("此操作只适用于网盘中的真实文件", "This action requires files stored in the drive", "この操作はドライブ内の実ファイルにのみ適用できます", "이 작업은 드라이브에 저장된 실제 파일에만 사용할 수 있습니다", "此操作僅適用於雲端硬碟中的實體檔案"))
        }
        if (command !is FileWriteCommand.CreateFolder && command.ids.isEmpty()) return blocked(tr("请先选择文件", "Select files first", "先にファイルを選択してください", "먼저 파일을 선택해 주세요", "請先選擇檔案"))
        if (command.target?.let { !isDriveFolderId(it.id) } == true) return blocked(tr("请选择网盘中的目标文件夹", "Choose a destination folder in the drive", "ドライブ内の保存先フォルダーを選択してください", "드라이브에서 대상 폴더를 선택해 주세요", "請選擇雲端硬碟中的目標資料夾"))
        if ((command is FileWriteCommand.CreateFolder && command.name.isBlank()) ||
            (command is FileWriteCommand.Rename && command.name.isBlank())) return blocked(tr("名称不能为空", "Name cannot be empty", "名前を入力してください", "이름을 입력해 주세요", "名稱不可為空"))
        if (command is FileWriteCommand.Move || command is FileWriteCommand.Copy) {
            val target = requireNotNull(command.target).id
            if (command.ids.any { contains(it, target) }) return blocked(tr("不能将文件夹放入自身或其子目录", "Cannot move a folder into itself or its subfolders", "フォルダーを自身やそのサブフォルダーに移動できません", "폴더를 자기 자신이나 하위 폴더로 이동할 수 없습니다", "不能將資料夾放入自身或其子資料夾"))
        }
        if (command !is FileWriteCommand.Star && shareBlocked(command.ids, command.target?.id, command is FileWriteCommand.Rename))
            return blocked(tr("相关文件正在转存或规范命名，或名称修改待确认，请先在任务页处理", "Related files are being saved or renamed, or renames are unconfirmed. Check Tasks first", "関連ファイルを保存または改名中か、改名結果が未確認です。先にタスク画面で確認してください", "관련 파일을 저장하거나 이름을 변경 중이거나 결과가 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "相關檔案正在轉存或規範命名，或名稱變更待確認，請先至任務頁處理"))
        if (command !is FileWriteCommand.Star &&
            (receivingBlocked(if (command is FileWriteCommand.Copy) emptyList() else command.ids, changesTree(command)) ||
                command.target?.let { target -> receiving?.exclusiveRoots(account)?.any { contains(it, target.id) } } == true))
            return blocked(tr("相关文件或文件夹有保存、上传或解压任务，请先在任务页完成、核对或移除相关任务", "Related files or folders have save, upload or extraction tasks. Complete, check or remove them in Tasks first", "関連ファイルやフォルダーに保存・アップロード・解凍タスクがあります。先にタスク画面で完了、確認または削除してください", "관련 파일이나 폴더에 저장, 업로드 또는 압축 해제 작업이 있습니다. 먼저 작업 페이지에서 완료, 확인 또는 삭제해 주세요", "相關檔案或資料夾有儲存、上傳或解壓縮任務，請先至任務頁完成、核對或移除相關任務"))
        if (repository.changes.state.value.undo.values.any { undo -> overlaps(command.ids, undo.activeFileIds) ||
                command.target?.let { target -> undo.activeTreeIds.any { contains(it, target.id) } } == true }) return blocked(tr("相关文件正在撤销，完成后再试", "Related files are being reverted. Try again when finished", "関連ファイルの操作を元に戻しています。完了後にお試しください", "관련 파일의 작업을 취소 중입니다. 완료 후 다시 시도해 주세요", "相關檔案正在復原，請待完成後再試"))
        if (changesTree(command) && repository.changes.state.value.undo.values.any { touchesFolders(command.ids, it.activeManifestFolders) }) return blocked(tr("相关文件夹的归档清单正在撤销，请待完成后重试", "The folder manifest is being reverted. Try again when finished", "フォルダーのアーカイブ一覧を元に戻しています。完了後にお試しください", "폴더의 보관 목록을 되돌리는 중입니다. 완료 후 다시 시도해 주세요", "相關資料夾的歸檔清單正在復原，請待完成後重試"))
        val targets = vault?.activeTargets
        if (targets != null && (affects(command, targets.files) || command.ids.any { id -> targets.roots.any { contains(it, id) || contains(id, it) } } ||
            command.target?.let { folder -> targets.roots.any { contains(it, folder.id) } } == true ||
            (changesTree(command) && touchesFolders(command.ids, targets.manifests)))) return blocked(tr("相关文件正在归档或恢复，请待完成后重试", "Related files are being archived or restored. Try again when finished", "関連ファイルをアーカイブまたは復元中です。完了後にお試しください", "관련 파일을 보관 또는 복원 중입니다. 완료 후 다시 시도해 주세요", "相關檔案正在歸檔或還原，請待完成後重試"))
        if (changesTree(command) && vaultEdits?.works?.value.orEmpty().any { it.account == account && touchesFolders(command.ids, it.pendingFolders) }) return blocked(tr("相关文件夹的归档清单正在编辑或待确认，请待完成后重试", "Folder archive manifests are being edited or awaiting confirmation. Try again when finished", "関連フォルダーのアーカイブ一覧を編集中か確認待ちです。完了後にお試しください", "관련 폴더의 보관 목록이 편집 중이거나 확인 대기 중입니다. 완료 후 다시 시도해 주세요", "相關資料夾的歸檔清單正在編輯或待確認，請待完成後重試"))
        if ((cleanup?.progress != null || cleanup?.isUndoing == true) && affects(command, cleanup.projectedIds)) return blocked(tr("相关文件正在移入回收站或恢复，完成后再试", "Related files are being trashed or restored. Try again when finished", "関連ファイルをゴミ箱に移動または復元中です。完了後にお試しください", "관련 파일을 휴지통으로 이동하거나 복원 중입니다. 완료 후 다시 시도해 주세요", "相關檔案正在移至資源回收筒或還原，請待完成後再試"))
        val conflict = session.works.value.firstOrNull { work ->
            work.account == account && (work.isRunning || work.result?.unconfirmed?.isNotEmpty() == true) && conflicts(command, pendingCommand(work))
        }
        return if (conflict == null) ActionAvailability.Available else blocked(
            if (conflict.isRunning) tr("「${conflict.command.title}」正在处理相关文件，请待完成后重试", "“${conflict.command.title}” is processing related files. Try again when finished", "「${conflict.command.title}」で関連ファイルを処理中です。完了後にお試しください", "“${conflict.command.title}”에서 관련 파일을 처리 중입니다. 완료 후 다시 시도해 주세요", "「${conflict.command.title}」正在處理相關檔案，請待完成後再試")
            else tr("相关文件的上次操作尚未确认，请先在任务页查询或检查结果", "Previous file operations are unconfirmed. Check results in Tasks first", "関連ファイルの前回の操作が未確認です。先にタスク画面で結果を確認してください", "관련 파일의 이전 작업 결과가 미확인입니다. 먼저 작업 페이지에서 결과를 확인해 주세요", "相關檔案的上次操作尚未確認，請先至任務頁查詢或檢查結果"),
        )
    }

    fun filesAvailability(ids: Collection<String>): ActionAvailability = filesAvailability(ids, tree = true)

    private fun filesAvailability(ids: Collection<String>, tree: Boolean): ActionAvailability {
        if (!acceptsActions() || account == null || repository.currentAccount != account) return ActionAvailability.Blocked(tr("当前工作区已结束", "This workspace has ended", "このワークスペースは終了しました", "현재 작업 공간이 종료되었습니다", "目前的工作區已結束"))
        if (shareBlocked(ids, null, !tree)) return ActionAvailability.Blocked(tr("相关文件有进行中或待确认的分享转存，请先在任务页处理", "Related share saves are running or unconfirmed. Check Tasks first", "関連ファイルの共有保存が進行中か未確認です。先にタスク画面で確認してください", "관련 파일의 공유 저장이 진행 중이거나 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "相關檔案有進行中或待確認的分享轉存，請先至任務頁處理"))
        if (receivingBlocked(ids, tree)) return ActionAvailability.Blocked(tr("相关文件夹有保存、上传或解压任务，请先在任务页处理", "Related folders have save, upload or extraction tasks. Check Tasks first", "関連フォルダーに保存・アップロード・解凍タスクがあります。先にタスク画面で確認してください", "관련 폴더에 저장, 업로드 또는 압축 해제 작업이 있습니다. 먼저 작업 페이지에서 확인해 주세요", "相關資料夾有儲存、上傳或解壓縮任務，請先至任務頁處理"))
        if (repository.changes.state.value.undo.values.any { overlaps(ids, it.activeFileIds) }) return ActionAvailability.Blocked(tr("相关文件正在撤销，完成后再试", "Related files are being reverted. Try again when finished", "関連ファイルの操作を元に戻しています。完了後にお試しください", "관련 파일의 작업을 취소 중입니다. 완료 후 다시 시도해 주세요", "相關檔案正在復原，請待完成後再試"))
        if (repository.changes.state.value.undo.values.any { touchesFolders(ids, it.activeManifestFolders) }) return ActionAvailability.Blocked(tr("相关归档清单正在撤销，请待完成后重试", "The archive manifest is being reverted. Try again when finished", "アーカイブ一覧を元に戻しています。完了後にお試しください", "보관 목록을 되돌리는 중입니다. 완료 후 다시 시도해 주세요", "相關歸檔清單正在復原，請待完成後重試"))
        vault?.activeTargets?.let { targets ->
            if (overlaps(ids, targets.files) || overlaps(ids, targets.roots) || touchesFolders(ids, targets.manifests)) return ActionAvailability.Blocked(tr("相关文件正在归档或恢复，请待完成后重试", "Related files are being archived or restored. Try again when finished", "関連ファイルをアーカイブまたは復元中です。完了後にお試しください", "관련 파일을 보관 또는 복원 중입니다. 완료 후 다시 시도해 주세요", "相關檔案正在歸檔或還原，請待完成後重試"))
        }
        if (vaultEdits?.works?.value.orEmpty().any { it.account == account && touchesFolders(ids, it.pendingFolders) }) return ActionAvailability.Blocked(tr("相关归档清单正在编辑或待确认，请待完成后重试", "Related archive manifests are being edited or unconfirmed. Try again when finished", "関連アーカイブ一覧を編集中か未確認です。完了後にお試しください", "관련 보관 목록이 편집 중이거나 미확인입니다. 완료 후 다시 시도해 주세요", "相關歸檔清單正在編輯或待確認，請待完成後重試"))
        if ((cleanup?.progress != null || cleanup?.isUndoing == true) && overlaps(ids, cleanup.projectedIds)) return ActionAvailability.Blocked(tr("相关文件正在移入回收站或恢复，完成后再试", "Related files are being trashed or restored. Try again when finished", "関連ファイルをゴミ箱に移動または復元中です。完了後にお試しください", "관련 파일을 휴지통으로 이동하거나 복원 중입니다. 완료 후 다시 시도해 주세요", "相關檔案正在移至資源回收筒或還原，請待完成後再試"))
        val work = session.works.value.firstOrNull {
            it.account == account && (it.isRunning || it.result?.unconfirmed?.isNotEmpty() == true) &&
                pendingCommand(it).let { command -> overlaps(ids, command.ids) || command.target?.let { target -> ids.any { contains(it, target.id) } } == true }
        }
        return if (work == null) ActionAvailability.Available else ActionAvailability.Blocked(tr("相关文件有进行中或待确认的操作，请先在任务页处理", "Related file operations are running or unconfirmed. Check Tasks first", "関連ファイルの操作が進行中か未確認です。先にタスク画面で確認してください", "관련 파일 작업이 진행 중이거나 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "相關檔案有進行中或待確認的操作，請先至任務頁處理"))
    }

    fun manifestAvailability(folders: Set<String>): ActionAvailability {
        if (!acceptsActions() || account == null || repository.currentAccount != account) return ActionAvailability.Blocked(tr("当前工作区已结束", "This workspace has ended", "このワークスペースは終了しました", "현재 작업 공간이 종료되었습니다", "目前的工作區已結束"))
        if (repository.changes.state.value.undo.values.any { undo -> folders.any { it in undo.activeManifestFolders } || undo.activeFileIds.any { id -> folders.any { contains(id, it) } } }) return ActionAvailability.Blocked(tr("相关归档清单正在撤销，请待完成后重试", "The archive manifest is being reverted. Try again when finished", "アーカイブ一覧を元に戻しています。完了後にお試しください", "보관 목록을 되돌리는 중입니다. 완료 후 다시 시도해 주세요", "相關歸檔清單正在復原，請待完成後重試"))
        vault?.activeTargets?.let { targets ->
            if (folders.any { it in targets.manifests || targets.roots.any { root -> contains(root, it) } }) return ActionAvailability.Blocked(tr("相关文件夹正在归档或恢复，请待完成后重试", "Related folders are being archived or restored. Try again when finished", "関連フォルダーをアーカイブまたは復元中です。完了後にお試しください", "관련 폴더를 보관 또는 복원 중입니다. 완료 후 다시 시도해 주세요", "相關資料夾正在歸檔或還原，請待完成後重試"))
        }
        if (session.works.value.any { it.account == account && (it.isRunning || it.result?.unconfirmed?.isNotEmpty() == true) && changesTree(it.command) && touchesFolders(pendingCommand(it).ids, folders) }) return ActionAvailability.Blocked(tr("相关文件夹有进行中或待确认的修改，请先在任务页处理", "Related folder changes are running or unconfirmed. Check Tasks first", "関連フォルダーの変更が進行中か未確認です。先にタスク画面で確認してください", "관련 폴더 변경이 진행 중이거나 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "相關資料夾有進行中或待確認的變更，請先至任務頁處理"))
        if (vaultEdits?.works?.value.orEmpty().any { it.account == account && it.pendingFolders.any { folder -> folder in folders } }) return ActionAvailability.Blocked(tr("同一归档清单正在编辑或待确认，请先在任务页处理", "This archive manifest is being edited or unconfirmed. Check Tasks first", "同じアーカイブ一覧を編集中か未確認です。先にタスク画面で確認してください", "같은 보관 목록이 편집 중이거나 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "同一份歸檔清單正在編輯或待確認，請先至任務頁處理"))
        return ActionAvailability.Available
    }

    fun vaultAvailability(targets: dev.piko.shared.state.VaultWorkTargets): ActionAvailability {
        val files = filesAvailability(targets.roots + targets.files)
        if (files is ActionAvailability.Blocked) return files
        val manifests = manifestAvailability(targets.manifests)
        if (manifests is ActionAvailability.Blocked) return manifests
        if (vaultEdits?.works?.value.orEmpty().any { it.account == account && touchesFolders(targets.roots, it.pendingFolders) }) {
            return ActionAvailability.Blocked(tr("选中文件夹中的归档清单正在编辑或待确认，请先在任务页处理", "Selected folder manifests are being edited or unconfirmed. Check Tasks first", "選択したフォルダーのアーカイブ一覧を編集中か未確認です。先にタスク画面で確認してください", "선택한 폴더의 보관 목록이 편집 중이거나 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "所選資料夾中的歸檔清單正在編輯或待確認，請先至任務頁處理"))
        }
        return ActionAvailability.Available
    }

    fun undoAvailability(change: DriveChangeJournal.Change): ActionAvailability {
        if (shareSaves?.works?.value.orEmpty().any { it.account == account && change.id in it.undoChangeIds && it.pendingRenames.isNotEmpty() })
            return ActionAvailability.Blocked(tr("本次重命名仍有待确认的步骤，请先在任务页查询结果", "Some rename steps are unconfirmed. Check results in Tasks first", "今回の改名には未確認の手順があります。先にタスク画面で結果を確認してください", "이번 이름 변경에 미확인 단계가 있습니다. 먼저 작업 페이지에서 결과를 확인해 주세요", "本次重新命名仍有待確認的步驟，請先至任務頁查詢結果"))
        if (receivingBlocked(change.affectedFileIds(), change !is DriveChangeJournal.Change.Rename))
            return ActionAvailability.Blocked(tr("相关文件夹有保存、上传或解压任务，请先在任务页处理", "Related folders have save, upload or extraction tasks. Check Tasks first", "関連フォルダーに保存・アップロード・解凍タスクがあります。先にタスク画面で確認してください", "관련 폴더에 저장, 업로드 또는 압축 해제 작업이 있습니다. 먼저 작업 페이지에서 확인해 주세요", "相關資料夾有儲存、上傳或解壓縮任務，請先至任務頁處理"))
        val files = filesAvailability(change.affectedFileIds(), tree = change !is DriveChangeJournal.Change.Rename)
        if (files is ActionAvailability.Blocked) return files
        return manifestAvailability(change.affectedManifestFolders())
    }

    fun undo(changeId: Long): FileCommandResult {
        val change = repository.changes.state.value.available.firstOrNull { it.id == changeId }
            ?: return FileCommandResult.Rejected(tr("撤销记录已不可用，或正在撤销", "Undo record is unavailable or already being reverted", "元に戻す記録が無効か、元に戻している途中です", "실행 취소 기록이 없거나 이미 취소 중입니다", "復原記錄已無法使用，或正在復原"))
        return when (val state = undoAvailability(change)) {
            is ActionAvailability.Blocked -> FileCommandResult.Rejected(state.reason)
            ActionAvailability.Available -> if (repository.changes.undo(change)) FileCommandResult.Accepted(change.id)
                else FileCommandResult.Rejected(tr("撤销记录已不可用，或正在撤销", "Undo record is unavailable or already being reverted", "元に戻す記録が無効か、元に戻している途中です", "실행 취소 기록이 없거나 이미 취소 중입니다", "復原記錄已無法使用，或正在復原"))
        }
    }

    private fun overlaps(a: Collection<String>, b: Collection<String>): Boolean =
        a.any { x -> b.any { y -> contains(x, y) || contains(y, x) } }

    private fun receivingBlocked(ids: Collection<String>, tree: Boolean): Boolean {
        val owner = account ?: return false
        val activity = receiving ?: return false
        val files = activity.files(owner)
        val targets = if (tree) activity.targets(owner) else emptySet()
        val exclusive = activity.exclusiveRoots(owner)
        return ids.any { it in files } || ids.any { id -> exclusive.any { contains(it, id) } || targets.any { contains(id, it) } }
    }

    private fun shareBlocked(ids: Collection<String>, target: String?, metadataOnly: Boolean): Boolean =
        shareSaves?.works?.value.orEmpty().filter { it.account == account }.any { work ->
            val pending = work.pendingRenames.mapTo(HashSet()) { it.id }
            val roots = work.command.sources.filter { it.folder && it.id in work.blockedSources }.mapNotNull { work.associatedOutputs[it.id] }
            val subjects = if (work.isRunning) work.associatedOutputs.values.toSet() + pending else pending
            val touches = ids.any { id -> roots.any { contains(it, id) } } ||
                if (work.isRunning) (if (metadataOnly) ids.any { it in subjects } else overlaps(ids, subjects)) || ids.any { newShareFile(work, it) } else if (metadataOnly) ids.any { it in pending }
                else ids.any { id -> (pending + roots).any { contains(id, it) } }
            touches || (target != null && roots.any { contains(it, target) }) || (work.isRunning && target != null && subjects.any { contains(it, target) }) ||
                (!metadataOnly && (work.isRunning || work.result?.unconfirmedSources?.isNotEmpty() == true) && ids.any { contains(it, work.command.target.id) })
        }

    private fun newShareFile(work: dev.piko.shared.state.ShareSaveWork, id: String): Boolean {
        val before = work.beforeIds ?: return false
        if (!work.command.canonicalNames) return false
        var candidate = id
        repeat(64) {
            val file = repository.knownFile(candidate) ?: return false
            if (file.id !in before && file.parentId == work.command.target.id && file.params["original_share_id"] == work.command.shareId &&
                file.params["original_file_id"] in work.sourceIds) return true
            if (file.parentId.isEmpty() || file.parentId == candidate) return false
            candidate = file.parentId
        }
        return false
    }

    private fun affects(command: FileWriteCommand, subjects: Set<String>): Boolean = overlaps(command.ids, subjects) ||
        command.target?.let { target -> subjects.any { contains(it, target.id) } } == true

    private fun touchesFolders(ids: Collection<String>, folders: Set<String>) = ids.any { id -> folders.any { contains(id, it) } }
    private fun changesTree(command: FileWriteCommand) = command is FileWriteCommand.Move || command is FileWriteCommand.Delete || command is FileWriteCommand.Restore

    private fun pendingCommand(work: dev.piko.shared.state.FileWriteWork): FileWriteCommand {
        if (work.isRunning) return work.command
        val ids = work.result?.unconfirmed.orEmpty().toList()
        return when (val command = work.command) {
            is FileWriteCommand.Move -> command.copy(ids = ids)
            is FileWriteCommand.Copy -> command.copy(ids = ids)
            is FileWriteCommand.Restore -> command.copy(ids = ids)
            is FileWriteCommand.Delete -> command.copy(ids = ids)
            is FileWriteCommand.Star -> command.copy(ids = ids)
            else -> command
        }
    }

    fun submit(command: FileWriteCommand): FileCommandResult = when (val state = availability(command)) {
        is ActionAvailability.Blocked -> FileCommandResult.Rejected(state.reason)
        ActionAvailability.Available -> session.submit(command)?.let(FileCommandResult::Accepted)
            ?: FileCommandResult.Rejected(tr("账号已切换，未提交操作", "Account changed; operation not submitted", "アカウントが切り替わったため、操作は送信されませんでした", "계정이 전환되어 작업을 제출하지 않았습니다", "帳號已切換，未提交操作"))
    }

    private fun contains(ancestor: String, child: String): Boolean = ancestor == child ||
        repository.folderIndex.pathTo(child)?.any { it.id == ancestor } == true ||
        repository.knownParentOf(child)?.let { parent -> parent == ancestor || repository.folderIndex.pathTo(parent)?.any { it.id == ancestor } == true } == true

    private fun conflicts(a: FileWriteCommand, b: FileWriteCommand): Boolean {
        if (a is FileWriteCommand.CreateFolder && b is FileWriteCommand.CreateFolder) {
            return a.target.id == b.target.id && a.name.trim() == b.name.trim()
        }
        // 星标与名称、位置、读取相互独立，删除/恢复则改变条目的存在状态。
        if (a is FileWriteCommand.Star && b !is FileWriteCommand.Star && b !is FileWriteCommand.Delete && b !is FileWriteCommand.Restore) return false
        if (b is FileWriteCommand.Star && a !is FileWriteCommand.Star && a !is FileWriteCommand.Delete && a !is FileWriteCommand.Restore) return false
        if (a is FileWriteCommand.Copy && b is FileWriteCommand.Copy && a.target.id != b.target.id) return false
        val sourcesOverlap = a.ids.any { x -> b.ids.any { y -> contains(x, y) || contains(y, x) } }
        // 目标目录的改名不会破坏按 ID 提交的写入；移动、删除或恢复它会改变执行的语境。
        return sourcesOverlap ||
            (changesTree(a) && b.target?.let { target -> a.ids.any { contains(it, target.id) } } == true) ||
            (changesTree(b) && a.target?.let { target -> b.ids.any { contains(it, target.id) } } == true)
    }
}

sealed interface FileCommandResult {
    data class Accepted(val workId: Long) : FileCommandResult
    data class Rejected(val reason: String) : FileCommandResult
}
