package dev.piko.ui

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.ArchiveJob
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.InstantSaveExecution
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.screens.transfers.remainingTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.snapshotFlow

/** 后台工作的种类。通知按它挑图标，进度列表按它排序。 */
enum class WorkKind { VAULT, EXTRACT, FILES, DOWNLOAD, UPLOAD, DUPLICATES }

fun dev.piko.shared.upload.UploadPreparation.workProgress() = WorkProgress(
    WorkKind.UPLOAD, tr("上传到「$parentName」", "Upload to “$parentName”", "「$parentName」にアップロード", "“$parentName”에 업로드"), if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    when (result?.completion) {
        null -> tr("正在准备上传", "Preparing upload", "アップロードを準備中", "업로드 준비 중")
        dev.piko.shared.upload.UploadPreparationCompletion.SUCCEEDED -> tr("已入队", "Queued", "キューに追加しました", "대기열에 추가됨")
        dev.piko.shared.upload.UploadPreparationCompletion.PARTIAL -> tr("部分准备未完成", "Some preparation incomplete", "一部の準備が未完了です", "일부 준비 미완료")
        dev.piko.shared.upload.UploadPreparationCompletion.FAILED -> tr("准备失败", "Preparation failed", "準備に失敗しました", "준비 실패")
        dev.piko.shared.upload.UploadPreparationCompletion.UNCONFIRMED -> tr("目录结果待确认", "Folder results unconfirmed", "フォルダーの結果未確認", "폴더 결과 확인 필요")
        dev.piko.shared.upload.UploadPreparationCompletion.STOPPED -> tr("准备已停止", "Preparation stopped", "準備を停止しました", "준비 중지됨")
    }, result?.message ?: tr("正在读取所选的 $selectedCount 项并建立目标目录", "Reading $selectedCount selected items and creating destination folders", "選択した $selectedCount 項目を読み込み、保存先フォルダーを作成中", "선택한 ${selectedCount}개 항목을 읽고 대상 폴더 생성 중"),
)

fun dev.piko.shared.state.OfflineSubmitWork.workProgress(): WorkProgress = WorkProgress(
    WorkKind.FILES, title, if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    when (status) {
        dev.piko.shared.state.OfflineSubmitStatus.SUBMITTING -> tr("正在提交离线", "Submitting cloud download", "クラウドダウンロードを追加中", "클라우드 다운로드 추가 중")
        dev.piko.shared.state.OfflineSubmitStatus.COMPLETE -> tr("已保存", "Saved", "保存しました", "저장했습니다")
        dev.piko.shared.state.OfflineSubmitStatus.REFUSED -> tr("未提交", "Not submitted", "未送信", "제출되지 않음")
        dev.piko.shared.state.OfflineSubmitStatus.UNCONFIRMED -> tr("结果待确认", "Result unconfirmed", "結果未確認", "결과 확인 필요")
    }, message,
)

fun dev.piko.shared.state.ShareSaveWork.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress = WorkProgress(
    WorkKind.FILES, title,
    when {
        !isRunning && undo?.status != DriveChangeJournal.UndoStatus.RUNNING -> WorkMeter.None
        phase == dev.piko.shared.state.ShareSavePhase.TRACKING && remoteProgress != null -> WorkMeter.Determinate(requireNotNull(remoteProgress).coerceIn(0, 100) / 100f)
        else -> WorkMeter.Indeterminate
    },
    undo?.message ?: result?.let { when (it.completion) {
        dev.piko.shared.state.ShareSaveCompletion.SUCCEEDED -> tr("已转存", "Share saved", "共有を保存しました", "공유를 저장했습니다")
        dev.piko.shared.state.ShareSaveCompletion.PARTIAL -> tr("部分处理未完成", "Some operations incomplete", "一部の操作が未完了です", "일부 작업이 완료되지 않았습니다")
        dev.piko.shared.state.ShareSaveCompletion.FAILED -> tr("未提交转存", "Share save not submitted", "共有の保存は未送信です", "공유 저장을 제출하지 않았습니다")
        dev.piko.shared.state.ShareSaveCompletion.UNCONFIRMED -> tr("结果待确认", "Result unconfirmed", "結果未確認", "결과 확인 필요")
    } } ?: when (phase) {
        dev.piko.shared.state.ShareSavePhase.PREPARING -> tr("正在准备转存", "Preparing to save share", "共有の保存を準備中", "공유 저장 준비 중")
        dev.piko.shared.state.ShareSavePhase.SUBMITTING -> tr("正在提交转存", "Submitting share save", "共有の保存を送信中", "공유 저장 제출 중")
        dev.piko.shared.state.ShareSavePhase.TRACKING -> tr("云端正在转存", "Saving share in cloud", "クラウドに共有を保存中", "클라우드에 공유 저장 중")
        dev.piko.shared.state.ShareSavePhase.IDENTIFYING -> tr("正在核对转存产物", "Checking saved items", "保存した項目を確認中", "저장된 항목 확인 중")
        dev.piko.shared.state.ShareSavePhase.NAMING -> tr("正在规范命名", "Normalizing names", "ファイル名を整理中", "이름 정리 중")
        dev.piko.shared.state.ShareSavePhase.FINISHED -> tr("正在重新查询", "Checking again", "再確認中", "다시 확인 중")
    }, result?.message,
)

fun dev.piko.shared.state.InstantSaveAttempt.workProgress(): WorkProgress = WorkProgress(
    WorkKind.FILES, title, if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    if (isRunning) tr("正在保存", "Saving", "保存中", "저장 중") else if (isComplete) tr("已完成", "Completed", "完了", "완료") else if (receipt?.requiresInspection == true) tr("结果需要核对", "Results need inspection", "結果の確認が必要です", "결과 확인이 필요합니다") else if (receipt?.confirmed?.isEmpty() != false) tr("未保存", "Not saved", "未保存", "저장되지 않음") else tr("部分保存未完成", "Some saves incomplete", "一部の保存が未完了です", "일부 저장이 완료되지 않았습니다"),
    receipt?.message,
)

fun dev.piko.shared.state.FileWriteWork.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress {
    val status = undo?.message ?: result?.let {
        when (it.completion) {
            dev.piko.shared.state.FileWriteCompletion.SUCCEEDED -> tr("已完成", "Completed", "完了", "완료")
            dev.piko.shared.state.FileWriteCompletion.PARTIAL -> tr("部分完成", "Partially completed", "一部完了", "일부 완료")
            dev.piko.shared.state.FileWriteCompletion.FAILED -> tr("未完成", "Incomplete", "未完了", "미완료")
            dev.piko.shared.state.FileWriteCompletion.UNCONFIRMED -> tr("结果待确认", "Result unconfirmed", "結果未確認", "결과 확인 필요")
        }
    } ?: when (phase) {
        dev.piko.shared.state.FileWritePhase.PREPARING -> tr("正在核对文件", "Checking files", "ファイルを確認中", "파일 확인 중")
        dev.piko.shared.state.FileWritePhase.WRITING -> tr("正在提交修改", "Submitting changes", "変更を送信中", "변경 사항 제출 중")
        dev.piko.shared.state.FileWritePhase.CHECKING -> tr("正在核对结果", "Checking results", "結果を確認中", "결과 확인 중")
        dev.piko.shared.state.FileWritePhase.COPYING -> tr("服务端正在复制", "Server is copying", "サーバーでコピー中", "서버에서 복사 중")
        dev.piko.shared.state.FileWritePhase.FINISHED -> tr("已结束", "Finished", "終了", "종료")
    }
    return WorkProgress(WorkKind.FILES, command.title,
        if (isRunning || undo?.status == DriveChangeJournal.UndoStatus.RUNNING) WorkMeter.Indeterminate else WorkMeter.None,
        status, result?.message?.takeUnless { it == tr("已完成", "Completed", "完了", "완료") },
    )
}

fun dev.piko.shared.state.HistoryEditWork.workProgress(): WorkProgress = WorkProgress(
    WorkKind.FILES, command.title, if (isRunning) WorkMeter.Indeterminate else WorkMeter.None,
    when (result?.completion) {
        null -> tr("正在处理历史记录", "Updating history", "履歴を更新中", "기록 처리 중")
        dev.piko.shared.state.HistoryEditCompletion.SUCCEEDED -> tr("已完成", "Completed", "完了", "완료")
        dev.piko.shared.state.HistoryEditCompletion.PARTIAL -> tr("部分完成", "Partially completed", "一部完了", "일부 완료")
        dev.piko.shared.state.HistoryEditCompletion.FAILED -> tr("未完成", "Incomplete", "未完了", "미완료")
        dev.piko.shared.state.HistoryEditCompletion.UNCONFIRMED -> tr("结果待确认", "Result unconfirmed", "結果未確認", "결과 확인 필요")
    }, result?.message,
)

fun dev.piko.shared.state.VaultEntryEditWork.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress = WorkProgress(
    WorkKind.FILES, command.title,
    if (isRunning || undo?.status == DriveChangeJournal.UndoStatus.RUNNING) WorkMeter.Indeterminate else WorkMeter.None,
    undo?.message ?: when (result?.completion) {
        null -> tr("正在编辑归档清单", "Editing archive manifest", "アーカイブ一覧を編集中", "보관 목록 편집 중")
        dev.piko.shared.state.VaultEntryEditCompletion.SUCCEEDED -> tr("已完成", "Completed", "完了", "완료")
        dev.piko.shared.state.VaultEntryEditCompletion.PARTIAL -> tr("部分完成", "Partially completed", "一部完了", "일부 완료")
        dev.piko.shared.state.VaultEntryEditCompletion.FAILED -> tr("未完成", "Incomplete", "未完了", "미완료")
        dev.piko.shared.state.VaultEntryEditCompletion.UNCONFIRMED -> tr("结果待确认", "Result unconfirmed", "結果未確認", "결과 확인 필요")
    }, result?.message,
)

/** 进度条画成什么样。 */
sealed interface WorkMeter {
    data class Determinate(val fraction: Float) : WorkMeter

    /** 在做，但总量或用时未知。 */
    data object Indeterminate : WorkMeter

    /** 停着等人处理（待输密码），不画进度条。 */
    data object None : WorkMeter
}

/**
 * 一件后台工作此刻的进度描述。传输页的行与详情面板、桌面的浮动卡片、Android 的进度通知都从这里取文案与进度值，
 * 不各拼一份：曾经通知只写「进行中」、行上写「准备归档 3 / 40」，同一件事两处说法对不上。
 *
 * [title] 是带对象的一句（归档「电影」），通知标题与浮动卡片用；传输页的行另以对象名作标题、前面挂类别标签，不读它。
 */
data class WorkProgress(
    val kind: WorkKind,
    val title: String,
    val meter: WorkMeter,
    /** 主状态：「已归档 132 / 480 个文件」。 */
    val status: String,
    /** 次要说明：当前项、速度、停止中的说明。 */
    val detail: String? = null,
)

private val STOPPING_STATUS: String get() = tr("正在停止", "Stopping", "停止中", "중지 중")
private val STOPPING_DETAIL: String get() = tr("进行中的文件夹处理完即停", "Stops after the current folder finishes", "現在のフォルダーの処理後に停止します", "현재 폴더 처리 후 중지합니다")

private fun fraction(done: Int, total: Int?): WorkMeter =
    if (total != null && total > 0) WorkMeter.Determinate((done.toFloat() / total).coerceIn(0f, 1f)) else WorkMeter.Indeterminate

/**
 * 归档的进度。每个文件先取样内容标识（[FolderVaultSession.Progress.prepared]），整个文件夹写成清单、处置原文件后
 * 才计入 done，两段各占进度条的一半：只按 done 算时，取样那几分钟进度条一直停在 0。
 */
fun vaultArchiveProgress(progress: FolderVaultSession.Progress, stopping: Boolean): WorkProgress {
    val total = progress.total
    val meter = if (total > 0) {
        WorkMeter.Determinate(((progress.prepared + progress.done).toFloat() / (total * 2)).coerceIn(0f, 1f))
    } else {
        WorkMeter.Indeterminate
    }
    val status = when {
        stopping -> STOPPING_STATUS
        progress.prepared < total -> tr("准备归档 ${progress.prepared} / $total 个文件", "Preparing to archive ${progress.prepared} / ${enCount(total, "file", "files")}", "アーカイブを準備中 ${progress.prepared} / $total ファイル", "보관 준비 중 ${progress.prepared} / ${total}개 파일")
        else -> tr("已归档 ${progress.done} / $total 个文件", "Archived ${progress.done} / ${enCount(total, "file", "files")}", "アーカイブ済み ${progress.done} / $total ファイル", "보관 완료 ${progress.done} / ${total}개 파일")
    }
    return WorkProgress(WorkKind.VAULT, tr("归档「${progress.folderName}」", "Archive “${progress.folderName}”", "「${progress.folderName}」をアーカイブ", "“${progress.folderName}” 보관"), meter, status, STOPPING_DETAIL.takeIf { stopping })
}

/** 取消归档的进度。总数要扫完整棵树才知道，之前是不定进度，报已扫描的文件夹数。 */
fun vaultRestoreProgress(progress: FolderVaultSession.RestoreProgress, stopping: Boolean): WorkProgress {
    val title = tr("取消归档「${progress.folderName}」", "Undo archiving “${progress.folderName}”", "「${progress.folderName}」のアーカイブを元に戻す", "“${progress.folderName}” 보관 취소")
    val total = progress.total
    if (stopping) return WorkProgress(WorkKind.VAULT, title, fraction(progress.done, total), STOPPING_STATUS, STOPPING_DETAIL)
    if (total == null) {
        val scanned = progress.scannedFolders.takeIf { it > 0 }?.let { tr("已扫描 $it 个文件夹", "Scanned ${enCount(it, "folder", "folders")}", "$it フォルダーを検索済み", "${it}개 폴더 검색 완료") }
        return WorkProgress(WorkKind.VAULT, title, WorkMeter.Indeterminate, progress.stage, scanned)
    }
    // 还没恢复出一个时阶段词（检查空间、开始恢复）才有信息量，之后计数已经说明了
    return WorkProgress(
        WorkKind.VAULT,
        title,
        fraction(progress.done, total),
        tr("已恢复 ${progress.done} / $total 个文件", "Restored ${progress.done} / ${enCount(total, "file", "files")}", "復元済み ${progress.done} / $total ファイル", "복원 완료 ${progress.done} / ${total}개 파일"),
        progress.stage.takeIf { progress.done == 0 },
    )
}

/** 进程级归档会话眼下的那一件，没有在做时为 null。 */
fun FolderVaultSession.workProgress(): WorkProgress? =
    progress?.let { vaultArchiveProgress(it, stopping) } ?: restoreProgress?.let { vaultRestoreProgress(it, stopping) }
        ?: activeWork?.let { WorkProgress(WorkKind.VAULT, tr("归档「${it.name}」", "Archive “${it.name}”", "「${it.name}」をアーカイブ", "“${it.name}” 보관"), WorkMeter.Indeterminate, if (stopping) STOPPING_STATUS else tr("正在清点文件", "Counting files", "ファイル数を確認中", "파일 수 확인 중")) }

fun FolderVaultSession.Record.workProgress(undo: DriveChangeJournal.UndoResult? = null): WorkProgress = WorkProgress(
    WorkKind.VAULT,
    if (work.restoring) tr("恢复归档「${work.name}」", "Restore archive “${work.name}”", "「${work.name}」のアーカイブを復元", "“${work.name}” 보관 복원")
    else tr("归档「${work.name}」", "Archive “${work.name}”", "「${work.name}」をアーカイブ", "“${work.name}” 보관"),
    if (undo?.status == DriveChangeJournal.UndoStatus.RUNNING) WorkMeter.Indeterminate else WorkMeter.None,
    when (undo?.status) {
        DriveChangeJournal.UndoStatus.RUNNING -> tr("正在撤销", "Undoing", "元に戻しています", "실행 취소 중")
        DriveChangeJournal.UndoStatus.UNDONE -> tr("已撤销", "Undone", "元に戻しました", "실행 취소됨")
        DriveChangeJournal.UndoStatus.FAILED -> tr("撤销未全部完成", "Undo partially completed", "一部を元に戻せませんでした", "일부 실행 취소 미완료")
        null -> title
    },
    if (undo == null) message else "$message\n${undo.message}",
)

/** 一个压缩包的解压进度。 */
fun ArchiveJob.workProgress(): WorkProgress {
    val title = tr("解压「${file.name}」", "Extract “${file.name}”", "「${file.name}」を解凍", "“${file.name}” 압축 해제")
    return when (val status = status) {
        ArchiveJobStatus.Waiting -> if (remoteTaskId == null) {
            WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Determinate(0f), tr("排队中", "Queued", "待機中", "대기 중"))
        } else WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, tr("等待重新查询", "Waiting to check again", "再確認待ち", "다시 확인 대기 중"))
        ArchiveJobStatus.Submitting -> WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, tr("正在提交", "Submitting", "送信中", "제출 중"))
        ArchiveJobStatus.Checking -> WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Indeterminate, tr("正在查询结果", "Checking results", "結果を確認中", "결과 확인 중"))
        is ArchiveJobStatus.Extracting ->
            WorkProgress(WorkKind.EXTRACT, title, WorkMeter.Determinate(status.progress.coerceIn(0, 100) / 100f), tr("已解压 ${status.progress}%", "Extracted ${status.progress}%", "解凍済み ${status.progress}%", "압축 해제 ${status.progress}%"))
        is ArchiveJobStatus.NeedsPassword -> WorkProgress(
            WorkKind.EXTRACT,
            title,
            WorkMeter.None,
            if (status.incorrect) tr("密码错误", "Incorrect password", "パスワードが違います", "비밀번호가 틀렸습니다") else tr("需要密码", "Password required", "パスワードが必要です", "비밀번호가 필요합니다"),
            tr("输入密码后继续，或放弃解压", "Enter a password to continue, or cancel extraction", "パスワードを入力して続行するか、解凍を中止してください", "비밀번호를 입력하여 계속하거나 압축 해제를 취소해 주세요"),
        )
        is ArchiveJobStatus.Finished -> WorkProgress(
            WorkKind.EXTRACT, title, WorkMeter.None,
            when (status.outcome) {
                ArchiveCompletion.SUCCEEDED -> tr("解压完成", "Extraction complete", "解凍完了", "압축 해제 완료")
                ArchiveCompletion.FAILED -> tr("解压失败", "Extraction failed", "解凍失敗", "압축 해제 실패")
                ArchiveCompletion.UNCONFIRMED -> tr("结果待确认", "Result unconfirmed", "結果未確認", "결과 확인 필요")
            },
            status.message,
        )
    }
}

/**
 * 几个压缩包合成一条，给通知与浮动卡片：进度与状态取眼下在解的那个，待输密码的另计一句。
 * 解压逐个提交，同一时刻只有一个在跑，合计各包的百分比没有意义。
 */
fun List<ArchiveJob>.extractProgress(): WorkProgress? {
    val current = firstOrNull { it.status !is ArchiveJobStatus.NeedsPassword } ?: firstOrNull() ?: return null
    val single = current.workProgress()
    if (size == 1) return single
    val needPassword = count { it.status is ArchiveJobStatus.NeedsPassword && it !== current }
    return single.copy(
        title = tr("解压 $size 个压缩包", "Extract $size archives", "$size 個のアーカイブを解凍", "압축 파일 ${size}개 해제"),
        detail = listOfNotNull(current.file.name, needPassword.takeIf { it > 0 }?.let { tr("另有 $it 个需要密码", "$it more require passwords", "ほか $it 個はパスワードが必要です", "추가 ${it}개에 비밀번호 필요") }).joinToString(tr("，", ", ", "、", ", ")),
    )
}

internal fun ArchiveJobStatus.hasExecutingWork(): Boolean = this !is ArchiveJobStatus.NeedsPassword && this !is ArchiveJobStatus.Finished

/**
 * 下载与上传合成一条，给通知：传输页逐个列出，通知里只放得下一份总量。只有一个文件时写它的名字。
 * 片段事先不知道产物大小，字节总数为 0，进度不定。
 */
fun transferProgress(downloads: List<DownloadTask>, uploads: List<UploadTask>): WorkProgress? {
    val active = downloads.filter { it.status == DownloadStatus.DOWNLOADING }
    val sending = uploads.filter { it.status.isActive }
    if (active.isEmpty() && sending.isEmpty()) return null
    val kind = if (active.isEmpty()) WorkKind.UPLOAD else WorkKind.DOWNLOAD
    val title = (active.map { it.fileName } + sending.map { it.fileName }).singleOrNull()
        ?: listOfNotNull(
            active.size.takeIf { it > 0 }?.let { tr("下载 $it 个文件", "Download ${enCount(it, "file", "files")}", "$it ファイルをダウンロード", "파일 ${it}개 다운로드") },
            sending.size.takeIf { it > 0 }?.let { tr("上传 $it 个文件", "Upload ${enCount(it, "file", "files")}", "$it ファイルをアップロード", "파일 ${it}개 업로드") },
        ).joinToString(tr("，", ", ", "、", ", "))
    val processed = active.sumOf { it.downloadedBytes } + sending.sumOf { it.processedBytes }
    val total = active.sumOf { it.totalBytes } + sending.sumOf { it.size }
    val speed = active.sumOf { it.speedBytesPerSec } + sending.sumOf { it.speedBytesPerSec }
    val speedText = speed.takeIf { it > 0 }?.let { "${it.toReadableSize()}/s" }
    if (total <= 0) return WorkProgress(kind, title, WorkMeter.Indeterminate, tr("进行中", "In progress", "処理中", "진행 중"), speedText)
    val bytes = "${processed.toReadableSize()} / ${total.toReadableSize()}"
    return WorkProgress(
        kind,
        title,
        WorkMeter.Determinate((processed.toDouble() / total).toFloat().coerceIn(0f, 1f)),
        listOfNotNull(bytes, remainingTime(total - processed, speed)?.let { tr("剩余 $it", "$it remaining", "残り $it", "$it 남음") }).joinToString(tr("，", ", ", "、", ", ")),
        speedText,
    )
}

/** 查找重复的扫描。与 sheet 头上的说法相同；总量事先不知道，进度不定。 */
fun DuplicateFinderState.workProgress(): WorkProgress? {
    if (!isScanning) return null
    val scanned = tr("已扫描 $scannedFolders 个文件夹、$scannedFiles 个文件", "Scanned ${enCount(scannedFolders, "folder", "folders")} and ${enCount(scannedFiles, "file", "files")}", "$scannedFolders フォルダー、$scannedFiles ファイルを検索済み", "폴더 ${scannedFolders}개, 파일 ${scannedFiles}개 검색 완료")
    val title = tr("查找「${root.localizedName}」中的重复", "Find duplicates in “${root.localizedName}”", "「${root.localizedName}」の重複を検索", "“${root.localizedName}”에서 중복 찾기")
    return if (phase == DuplicateFinderState.Phase.ANALYZING) {
        WorkProgress(WorkKind.DUPLICATES, title, WorkMeter.Indeterminate, tr("正在比对", "Comparing", "照合中", "비교 중"), scanned)
    } else {
        WorkProgress(WorkKind.DUPLICATES, title, WorkMeter.Indeterminate, scanned)
    }
}

internal fun importSaveProgress(executions: List<InstantSaveExecution>): List<WorkProgress> =
    executions.filterNot { it.trackedOffline }.map {
        WorkProgress(WorkKind.FILES, tr("保存添加链接", "Save added links", "追加したリンクを保存", "추가한 링크 저장"), WorkMeter.Indeterminate, tr("正在保存", "Saving", "保存中", "저장 중"))
    }

/**
 * 眼下在后台跑的全部工作，按 [WorkKind] 的次序。读的是 Compose 状态，在 snapshotFlow 或组合里调用才会随之更新。
 * 下载与上传另读 StateFlow 的当前值，调用方要自己订阅它们的变化。
 */
fun PikoServices.runningWork(): List<WorkProgress> = (listOfNotNull(
    folderVaultSession.workProgress(),
    archiveExtractSession.jobs.extractProgress(),
    transferProgress(downloadManager.tasks.value.values.toList(), currentAccountUploads()),
    trashSession.progress?.let { progress ->
        WorkProgress(WorkKind.FILES, tr("移入回收站", "Move to trash", "ゴミ箱に移動", "휴지통으로 이동"), fraction(progress.completed, progress.total), tr("已处理 ${progress.completed} / ${progress.total} 项", "Processed ${progress.completed} / ${enCount(progress.total, "item", "items")}", "処理済み ${progress.completed} / ${progress.total} 項目", "처리 완료 ${progress.completed} / ${progress.total}개 항목"))
    },
) + duplicateSessions.mapNotNull { it.state?.workProgress() } +
    importSaveProgress(instantSession.executingSaves) +
    fileWrites.works.value.filter { it.account == driveRepository.currentAccount && it.isRunning && it.showsInTaskPage }.map { it.workProgress() } +
    vaultEntryEdits.works.value.filter { it.account == driveRepository.currentAccount && it.isRunning && it.showsInTaskPage }.map { it.workProgress() } +
    shareSaves.works.value.filter { it.account == driveRepository.currentAccount && it.isRunning }.map { it.workProgress() } +
    instantMagnetRepository.offlineSubmissions.works.value.filter { it.account == driveRepository.currentAccount && it.isRunning }.map { it.workProgress() } +
    uploadManager.preparations.value.filter { it.account == driveRepository.currentAccount && it.isRunning }.map { it.workProgress() } +
    offlinePacks.jobs.value.filter { it.account == driveRepository.currentAccount && dev.piko.shared.data.OfflinePackRef(it.account, it.taskId) in offlinePacks.busy.value }.map {
        WorkProgress(WorkKind.FILES, it.folderName, WorkMeter.Indeterminate, tr("正在处理云端任务", "Processing cloud task", "クラウドタスクを処理中", "클라우드 작업 처리 중"))
    } +
    downloadManager.listings.value.values.filter { it.account == driveRepository.currentAccount && it.error == null }.map {
        WorkProgress(WorkKind.DOWNLOAD, tr("下载「${it.batch.folderName}」", "Download “${it.batch.folderName}”", "「${it.batch.folderName}」をダウンロード", "“${it.batch.folderName}” 다운로드"),
            if (it.isListing) WorkMeter.Indeterminate else WorkMeter.None,
            if (it.isListing) tr("正在列出文件夹", "Listing folder", "フォルダーの内容を取得中", "폴더 목록 불러오는 중") else tr("等待确认下载额度", "Waiting for download quota confirmation", "ダウンロード枠の確認待ち", "다운로드 할당량 확인 대기 중"),
            tr("已找到 ${it.filesFound} 个文件", "Found ${enCount(it.filesFound, "file", "files")}", "${it.filesFound} ファイルが見つかりました", "파일 ${it.filesFound}개 찾음"),
        )
    } + listOfNotNull(driveRepository.changes.state.value.undo.values.count { it.status == DriveChangeJournal.UndoStatus.RUNNING }.takeIf { it > 0 }?.let {
        WorkProgress(WorkKind.FILES, tr("撤销文件改动", "Undo file changes", "ファイルの変更を元に戻す", "파일 변경 실행 취소"), WorkMeter.Indeterminate, tr("正在撤销 $it 项改动", "Undoing $it changes", "$it 件の変更を元に戻しています", "변경 ${it}건 실행 취소 중"))
    })).sortedBy { it.kind.ordinal }

/** 文件处理、撤销和目录展开用 StateFlow 提供变化信号，平台据此重读后台工作状态。 */
fun PikoServices.fileWorkChanges(): Flow<Unit> = merge(
    combine(fileWrites.works, historyEdits.works, vaultEntryEdits.works, driveRepository.changes.state, downloadManager.listings) { _, _, _, _, _ -> Unit },
    shareSaves.works.map { Unit },
    instantMagnetRepository.offlineSubmissions.works.map { Unit },
    offlinePacks.busy.map { Unit },
    uploadManager.preparations.map { Unit },
)

/** 上传队列只跑当前账号的，别的账号排着的任务一直是 QUEUED，算进来就永远有活，见 [anyActiveFor]。 */
private fun PikoServices.currentAccountUploads(): List<UploadTask> {
    val account = clientManager.currentClient.value?.account
    return uploadManager.tasks.value.values.filter { it.account == account }
}

/**
 * 有没有要在后台跑下去的工作，Android 据此保持前台服务。比 [runningWork] 宽：排着队、还在查本地长度的下载也算，
 * 否则两个文件之间的空档会让前台服务退下，后台里再拉起会被系统拒绝。
 */
fun PikoServices.hasBackgroundWork(): Boolean =
    folderVaultSession.isRunning ||
        archiveExtractSession.jobs.any { it.status.hasExecutingWork() } ||
        duplicateSessions.any { it.state?.isScanning == true } ||
        trashSession.progress != null || trashSession.isUndoing ||
        instantSession.executingSaves.isNotEmpty() ||
        fileWrites.works.value.any { it.account == driveRepository.currentAccount && it.isRunning && it.showsInTaskPage } ||
        vaultEntryEdits.works.value.any { it.account == driveRepository.currentAccount && it.isRunning && it.showsInTaskPage } ||
        shareSaves.works.value.any { it.account == driveRepository.currentAccount && it.isRunning } ||
        instantMagnetRepository.offlineSubmissions.works.value.any { it.account == driveRepository.currentAccount && it.isRunning } ||
        offlinePacks.busy.value.any { it.account == driveRepository.currentAccount } ||
        driveRepository.changes.state.value.undo.values.any { it.status == DriveChangeJournal.UndoStatus.RUNNING } ||
        downloadManager.listings.value.values.any { it.account == driveRepository.currentAccount && it.isListing } ||
        downloadManager.tasks.value.values.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING } ||
        currentAccountUploads().any { it.status.isActive } ||
        uploadManager.preparations.value.any { it.account == driveRepository.currentAccount && it.isRunning }

/** 平台共同订阅业务执行状态；等待密码、确认或结果待查不会单独维持后台执行。 */
fun PikoServices.backgroundWorkChanges(): Flow<Boolean> = combine(
    snapshotFlow { hasBackgroundWork() }, downloadManager.tasks, uploadManager.tasks, fileWorkChanges(),
) { _, _, _, _ -> hasBackgroundWork() }.distinctUntilChanged()

/** 进度通知的内容。只有一件时原样照搬它；几件并行时标题与进度取第一件，展开后逐件一行。 */
data class WorkNotificationContent(
    val title: String,
    val text: String,
    val subText: String?,
    val meter: WorkMeter,
    /** 展开后的逐件列表，只有一件时为空。 */
    val lines: List<String>,
    /** 全是上传，通知图标用上传的箭头。 */
    val uploadOnly: Boolean,
)

fun workNotificationContent(work: List<WorkProgress>): WorkNotificationContent {
    val first = work.firstOrNull()
        // 服务刚拉起、下载还在查本地长度（PENDING）时，什么进度都还没有
        ?: return WorkNotificationContent(tr("正在准备", "Preparing", "準備中", "준비 중"), "", null, WorkMeter.Indeterminate, emptyList(), uploadOnly = false)
    val others = work.size - 1
    return WorkNotificationContent(
        title = if (others == 0) first.title else tr("${first.title}，另有 $others 项", "${first.title}, plus $others more", "${first.title}、ほか $others 件", "${first.title}, 추가 ${others}건"),
        text = first.status,
        subText = first.detail,
        meter = first.meter,
        lines = if (others == 0) emptyList() else work.map { "${it.title}：${it.status}" },
        uploadOnly = work.all { it.kind == WorkKind.UPLOAD },
    )
}
