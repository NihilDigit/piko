package dev.piko.ui

import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.state.ArchiveJob
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.data.DriveChangeJournal
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow

enum class WorkKind { VAULT, EXTRACT, FILES, DOWNLOAD, UPLOAD, DUPLICATES }

fun dev.piko.shared.upload.UploadPreparation.workProgress() = WorkProgress(
    WorkKind.UPLOAD, tr("上传到「$parentName」", "Upload to “$parentName”", "「$parentName」にアップロード", "“$parentName”에 업로드", "上傳至「$parentName」"), if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    when (result?.completion) {
        null -> tr("正在准备上传", "Preparing upload", "アップロードを準備中", "업로드 준비 중", "正在準備上傳")
        dev.piko.shared.upload.UploadPreparationCompletion.SUCCEEDED -> tr("已入队", "Queued", "キューに追加しました", "대기열에 추가됨", "已加入佇列")
        dev.piko.shared.upload.UploadPreparationCompletion.PARTIAL -> tr("部分准备未完成", "Some preparation incomplete", "一部の準備が未完了です", "일부 준비 미완료", "部分準備未完成")
        dev.piko.shared.upload.UploadPreparationCompletion.FAILED -> tr("准备失败", "Preparation failed", "準備に失敗しました", "준비 실패", "準備失敗")
        dev.piko.shared.upload.UploadPreparationCompletion.UNCONFIRMED -> tr("目录结果待确认", "Folder results unconfirmed", "フォルダーの結果未確認", "폴더 결과 확인 필요", "資料夾結果待確認")
        dev.piko.shared.upload.UploadPreparationCompletion.STOPPED -> tr("准备已停止", "Preparation stopped", "準備を停止しました", "준비 중지됨", "準備已停止")
    }, result?.message ?: tr("正在读取所选的 $selectedCount 项并建立目标目录", "Reading $selectedCount selected items and creating destination folders", "選択した $selectedCount 項目を読み込み、保存先フォルダーを作成中", "선택한 ${selectedCount}개 항목을 읽고 대상 폴더 생성 중", "正在讀取所選的 $selectedCount 個項目並建立目標資料夾"),
)

fun dev.piko.shared.state.OfflineSubmitWork.workProgress(): WorkProgress = WorkProgress(
    WorkKind.FILES, title, if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    when (status) {
        dev.piko.shared.state.OfflineSubmitStatus.SUBMITTING -> tr("正在提交离线下载", "Submitting cloud download", "クラウドダウンロードを送信中", "클라우드 다운로드 제출 중", "正在提交離線下載")
        dev.piko.shared.state.OfflineSubmitStatus.COMPLETE -> tr("已保存", "Saved", "保存しました", "저장했습니다", "已儲存")
        dev.piko.shared.state.OfflineSubmitStatus.REFUSED -> tr("未提交", "Not submitted", "未送信", "제출되지 않음", "未提交")
        dev.piko.shared.state.OfflineSubmitStatus.UNCONFIRMED -> tr("结果待确认", "Result unconfirmed", "結果未確認", "결과 확인 필요", "結果待確認")
    }, message,
)

fun dev.piko.shared.state.InstantSaveAttempt.workProgress(): WorkProgress = WorkProgress(
    WorkKind.FILES, title, if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    if (isRunning) tr("正在保存", "Saving", "保存中", "저장 중", "正在儲存") else if (isComplete) tr("已完成", "Completed", "完了", "완료", "已完成") else if (receipt?.requiresInspection == true) tr("结果需要核对", "Results need inspection", "結果の確認が必要です", "결과 확인이 필요합니다", "結果需要核對") else if (receipt?.confirmed?.isEmpty() != false) tr("未保存", "Not saved", "未保存", "저장되지 않음", "未儲存") else tr("部分保存未完成", "Some saves incomplete", "一部の保存が未完了です", "일부 저장이 완료되지 않았습니다", "部分儲存未完成"),
    receipt?.message,
)

fun dev.piko.shared.state.FileWriteWork.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress {
    val status = undo?.message ?: result?.let {
        when (it.completion) {
            dev.piko.shared.state.FileWriteCompletion.SUCCEEDED -> tr("已完成", "Completed", "完了", "완료", "已完成")
            dev.piko.shared.state.FileWriteCompletion.PARTIAL -> tr("部分完成", "Partially completed", "一部完了", "일부 완료", "部分完成")
            dev.piko.shared.state.FileWriteCompletion.FAILED -> tr("未完成", "Incomplete", "未完了", "미완료", "未完成")
            dev.piko.shared.state.FileWriteCompletion.UNCONFIRMED -> tr("结果待确认", "Result unconfirmed", "結果未確認", "결과 확인 필요", "結果待確認")
        }
    } ?: when (phase) {
        dev.piko.shared.state.FileWritePhase.PREPARING -> tr("正在核对文件", "Checking files", "ファイルを確認中", "파일 확인 중", "正在核對檔案")
        dev.piko.shared.state.FileWritePhase.COPYING -> tr("服务端正在复制", "Server is copying", "サーバーでコピー中", "서버에서 복사 중", "伺服器正在複製")
        dev.piko.shared.state.FileWritePhase.FINISHED -> tr("已结束", "Finished", "終了", "종료", "已結束")
    }
    return WorkProgress(WorkKind.FILES, command.title,
        if (isRunning || undo?.status == DriveChangeJournal.UndoStatus.RUNNING) WorkMeter.Indeterminate else WorkMeter.None,
        status, result?.message?.takeUnless { it == tr("已完成", "Completed", "完了", "완료", "已完成") },
    )
}

enum class WorkUnit { BYTES, ITEMS }
data class WorkMeasure(val completed: Long, val total: Long?, val unit: WorkUnit)

sealed interface WorkMeter {
    data class Determinate(val fraction: Float) : WorkMeter

    data object Indeterminate : WorkMeter

    data object None : WorkMeter
}

data class WorkProgress(
    val kind: WorkKind,
    val title: String,
    val meter: WorkMeter,
    val status: String,
    val detail: String? = null,
    val measure: WorkMeasure? = null,
)

private val STOPPING_STATUS: String get() = tr("正在停止", "Stopping", "停止中", "중지 중", "正在停止")
private val STOPPING_DETAIL: String get() = tr("当前文件夹处理完成后停止", "Stops after the current folder finishes", "現在のフォルダーの処理完了後に停止します", "현재 폴더 처리 완료 후 중지합니다", "目前資料夾處理完成後停止")

private fun fraction(done: Int, total: Int?): WorkMeter =
    if (total != null && total > 0) WorkMeter.Determinate((done.toFloat() / total).coerceIn(0f, 1f)) else WorkMeter.Indeterminate

fun vaultArchiveProgress(progress: FolderVaultSession.Progress, stopping: Boolean): WorkProgress {
    val total = progress.total
    val phaseDone = if (progress.prepared < total) progress.prepared else progress.done
    val meter = fraction(phaseDone, total)
    val status = when {
        stopping -> STOPPING_STATUS
        progress.prepared < total -> tr("准备归档 ${progress.prepared} / $total 个文件", "Preparing to archive ${progress.prepared} / ${enCount(total, "file", "files")}", "アーカイブを準備中 ${progress.prepared} / $total ファイル", "보관 준비 중 ${progress.prepared} / ${total}개 파일", "準備歸檔 ${progress.prepared} / $total 個檔案")
        else -> tr("已归档 ${progress.done} / $total 个文件", "Archived ${progress.done} / ${enCount(total, "file", "files")}", "アーカイブ済み ${progress.done} / $total ファイル", "보관 완료 ${progress.done} / ${total}개 파일", "已歸檔 ${progress.done} / $total 個檔案")
    }
    return WorkProgress(WorkKind.VAULT, tr("归档「${progress.folderName}」", "Archive “${progress.folderName}”", "「${progress.folderName}」をアーカイブ", "“${progress.folderName}” 보관", "歸檔「${progress.folderName}」"), meter, status, STOPPING_DETAIL.takeIf { stopping }, WorkMeasure(phaseDone.toLong(), total.toLong().takeIf { it > 0 }, WorkUnit.ITEMS))
}

fun vaultRestoreProgress(progress: FolderVaultSession.RestoreProgress, stopping: Boolean): WorkProgress {
    val title = tr("取消归档「${progress.folderName}」", "Undo archiving “${progress.folderName}”", "「${progress.folderName}」のアーカイブを解除", "“${progress.folderName}” 보관 취소", "取消歸檔「${progress.folderName}」")
    val total = progress.total
    if (stopping) return WorkProgress(WorkKind.VAULT, title, fraction(progress.done, total), STOPPING_STATUS, STOPPING_DETAIL)
    if (total == null) {
        val scanned = progress.scannedFolders.takeIf { it > 0 }?.let { tr("已扫描 $it 个文件夹", "Scanned ${enCount(it, "folder", "folders")}", "$it フォルダーを検索済み", "${it}개 폴더 검색 완료", "已掃描 $it 個資料夾") }
        return WorkProgress(WorkKind.VAULT, title, WorkMeter.Indeterminate, progress.stage, scanned)
    }
    // 还没恢复出一个时阶段词（检查空间、开始恢复）才有信息量，之后计数已经说明了
    return WorkProgress(
        WorkKind.VAULT,
        title,
        fraction(progress.done, total),
        tr("已恢复 ${progress.done} / $total 个文件", "Restored ${progress.done} / ${enCount(total, "file", "files")}", "復元済み ${progress.done} / $total ファイル", "복원 완료 ${progress.done} / ${total}개 파일", "已還原 ${progress.done} / $total 個檔案"),
        progress.stage.takeIf { progress.done == 0 },
    )
}

fun FolderVaultSession.workProgress(): WorkProgress? =
    progress?.let { vaultArchiveProgress(it, stopping) } ?: restoreProgress?.let { vaultRestoreProgress(it, stopping) }
        ?: activeWork?.let { WorkProgress(WorkKind.VAULT, tr("归档「${it.name}」", "Archive “${it.name}”", "「${it.name}」をアーカイブ", "“${it.name}” 보관", "歸檔「${it.name}」"), WorkMeter.Indeterminate, if (stopping) STOPPING_STATUS else tr("正在清点文件", "Counting files", "ファイル数を確認中", "파일 수 확인 중", "正在清點檔案")) }

fun FolderVaultSession.Record.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress = WorkProgress(
    WorkKind.VAULT,
    if (work.restoring) tr("恢复归档「${work.name}」", "Restore archive “${work.name}”", "「${work.name}」のアーカイブを復元", "“${work.name}” 보관 복원", "還原歸檔「${work.name}」")
    else tr("归档「${work.name}」", "Archive “${work.name}”", "「${work.name}」をアーカイブ", "“${work.name}” 보관", "歸檔「${work.name}」"),
    if (undo?.status == DriveChangeJournal.UndoStatus.RUNNING) WorkMeter.Indeterminate else WorkMeter.None,
    when (undo?.status) {
        DriveChangeJournal.UndoStatus.RUNNING -> tr("正在撤销", "Undoing", "元に戻しています", "실행 취소 중", "正在復原")
        DriveChangeJournal.UndoStatus.UNDONE -> tr("已撤销", "Undone", "元に戻しました", "실행 취소됨", "已復原")
        DriveChangeJournal.UndoStatus.FAILED -> tr("撤销未全部完成", "Undo partially completed", "一部を元に戻せませんでした", "일부 실행 취소 미완료", "復原未全部完成")
        null -> title
    },
    if (undo == null) message else "$message\n${undo.message}",
)

fun ArchiveJob.workProgress(): WorkProgress {
    val title = tr("解压「${file.name}」", "Extract “${file.name}”", "「${file.name}」を解凍", "“${file.name}” 압축 해제", "解壓縮「${file.name}」")
    return when (val status = status) {
        ArchiveJobStatus.Waiting -> if (remoteTaskId == null) {
            WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Determinate(0f), tr("排队中", "Queued", "待機中", "대기 중", "排隊中"))
        } else WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, tr("等待重新查询", "Waiting to check again", "再確認待ち", "다시 확인 대기 중", "等待重新查詢"))
        ArchiveJobStatus.Submitting -> WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, tr("正在提交", "Submitting", "送信中", "제출 중", "正在提交"))
        ArchiveJobStatus.Checking -> WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, tr("正在查询结果", "Checking results", "結果を確認中", "결과 확인 중", "正在查詢結果"))
        is ArchiveJobStatus.Extracting ->
            WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Determinate(status.progress.coerceIn(0, 100) / 100f), tr("已解压 ${status.progress}%", "Extracted ${status.progress}%", "解凍済み ${status.progress}%", "압축 해제 ${status.progress}%", "已解壓縮 ${status.progress}%"))
        is ArchiveJobStatus.NeedsPassword -> WorkProgress(
            WorkKind.EXTRACT,
            title,
            WorkMeter.None,
            if (status.incorrect) tr("密码错误", "Incorrect password", "パスワードが違います", "비밀번호가 틀렸습니다", "密碼錯誤") else tr("需要密码", "Password required", "パスワードが必要です", "비밀번호가 필요합니다", "需要密碼"),
            tr("输入密码后继续，或放弃解压", "Enter a password to continue, or cancel extraction", "パスワードを入力して続行するか、解凍を中止してください", "비밀번호를 입력하여 계속하거나 압축 해제를 취소해 주세요", "輸入密碼後繼續，或放棄解壓縮"),
        )
        is ArchiveJobStatus.Finished -> WorkProgress(
            WorkKind.EXTRACT, title, WorkMeter.None,
            when (status.outcome) {
                ArchiveCompletion.SUCCEEDED -> tr("解压完成", "Extraction complete", "解凍完了", "압축 해제 완료", "解壓縮完成")
                ArchiveCompletion.FAILED -> tr("解压失败", "Extraction failed", "解凍失敗", "압축 해제 실패", "解壓縮失敗")
                ArchiveCompletion.UNCONFIRMED -> tr("结果待确认", "Result unconfirmed", "結果未確認", "결과 확인 필요", "結果待確認")
            },
            status.message,
        )
    }
}

fun PikoServices.runningWork(): List<WorkProgress> {
    if (driveRepository.currentAccount == null) return emptyList()
    val state = taskCenter
    val presenter = dev.piko.ui.screens.transfers.TaskPresenter(state, folderVaultSession)
    return state.activity.active.map { item ->
        val row = presenter.present(item)
        val kind = when {
            dev.piko.shared.state.TransferKind.DOWNLOAD.matches(item) -> WorkKind.DOWNLOAD
            dev.piko.shared.state.TransferKind.UPLOAD.matches(item) -> WorkKind.UPLOAD
            else -> WorkKind.FILES
        }
        WorkProgress(kind, row.title, row.meter, row.status, row.detail)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun PikoServices.fileWorkChanges(): Flow<Unit> = clientManager.currentClient.flatMapLatest { client ->
    if (client == null) flowOf(Unit) else snapshotFlow { taskCenter.activity }.map { Unit }
}

fun PikoServices.hasBackgroundWork(): Boolean = driveRepository.currentAccount != null &&
    taskCenter.activity.executionRequired

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun PikoServices.backgroundWorkChanges(): Flow<Boolean> = clientManager.currentClient.flatMapLatest { client ->
    if (client == null) flowOf(false) else snapshotFlow { hasBackgroundWork() }
}.distinctUntilChanged()

data class WorkNotificationContent(
    val title: String,
    val text: String,
    val subText: String?,
    val meter: WorkMeter,
    val lines: List<String>,
    val uploadOnly: Boolean,
)

fun workNotificationContent(work: List<WorkProgress>): WorkNotificationContent {
    val first = work.firstOrNull()
        // 服务刚拉起、下载还在查本地长度（PENDING）时，什么进度都还没有
        ?: return WorkNotificationContent(tr("正在准备", "Preparing", "準備中", "준비 중", "正在準備"), "", null, WorkMeter.Indeterminate, emptyList(), uploadOnly = false)
    val others = work.size - 1
    return WorkNotificationContent(
        title = if (others == 0) first.title else tr("${first.title}，另有 $others 项", "${first.title}, plus $others more", "${first.title}、ほか $others 件", "${first.title}, 추가 ${others}건", "${first.title}，另有 $others 項"),
        text = first.status,
        subText = first.detail,
        meter = if (others == 0) first.meter else WorkMeter.None,
        lines = if (others == 0) emptyList() else work.map { "${it.title}：${it.status}" },
        uploadOnly = work.all { it.kind == WorkKind.UPLOAD },
    )
}
