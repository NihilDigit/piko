package dev.piko.ui

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.snapshotFlow
import dev.piko.download.DownloadBatch
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.presentation.localizedName
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.components.toReadableSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** 一件后台工作的结局，给不在眼前的用户看：Android 发系统通知，桌面发 Toast。 */
class WorkNotice(val title: String, val message: String)

/**
 * 下载、上传、解压、查找重复、归档与恢复的结局汇成一条流。何时呈现由各端决定：应用或窗口在前台时，
 * 列表与 Snackbar 已经说明了，通常不必再发。只报订阅之后结束的工作，启动时读回的旧记录不报。
 */
fun PikoServices.workNotices(): Flow<WorkNotice> = merge(
    downloadNotices(downloadManager),
    uploadNotices(uploadManager, clientManager),
    uploadManager.preparationOutcomes.filter { it.account == driveRepository.currentAccount }.map { WorkNotice(tr("上传准备未完成", "Upload preparation incomplete", "アップロードの準備が未完了です", "업로드 준비 미완료"), it.result?.message.orEmpty()) },
    serverWorkNotices(),
    sessionNotices({ duplicateSessions.toList() }) { session, preexisting ->
        duplicateNotices(session, if (preexisting) session.state?.takeIf { it.phase.isFinished() } else null)
    },
    fileWrites.outcomes.filter { outcome ->
        outcome.account == driveRepository.currentAccount &&
            (outcome.result.completion != dev.piko.shared.state.FileWriteCompletion.SUCCEEDED ||
                fileWrites.works.value.any { it.id == outcome.workId && it.showsInTaskPage })
    }.map { WorkNotice(it.title, it.result.message) },
    historyEdits.outcomes.filter { it.account == driveRepository.currentAccount && it.result.completion != dev.piko.shared.state.HistoryEditCompletion.SUCCEEDED }.map { WorkNotice(it.title, it.result.message) },
    vaultEntryEdits.outcomes.filter { outcome ->
        outcome.account == driveRepository.currentAccount &&
            (outcome.result.completion != dev.piko.shared.state.VaultEntryEditCompletion.SUCCEEDED ||
                vaultEntryEdits.works.value.any { it.id == outcome.id && it.showsInTaskPage })
    }.map { WorkNotice(it.title, it.result.message) },
    shareSaves.outcomes.filter { it.account == driveRepository.currentAccount }.map { WorkNotice(it.title, it.result.message) },
    instantSaveRecords.outcomes.filter { it.account == driveRepository.currentAccount }.map { WorkNotice(it.title, it.message) },
    instantMagnetRepository.offlineSubmissions.outcomes.filter { it.account == driveRepository.currentAccount && it.status != dev.piko.shared.state.OfflineSubmitStatus.COMPLETE }.map { WorkNotice(it.title, it.message) },
)

/**
 * 服务端解压与归档、取消归档的结局。移动端传输页列着它们，做完时在页内另说一遍，见 TransfersScreen。
 * [withUndoable] 为假时略去已记进改动日志的归档结局：它们由主界面带「撤销」提示过（showChangeNotices）。
 */
fun PikoServices.serverWorkNotices(withUndoable: Boolean = true): Flow<WorkNotice> = merge(
    archiveExtractSession.outcomes.map {
        WorkNotice(when (it.outcome) {
            dev.piko.shared.state.ArchiveCompletion.SUCCEEDED -> tr("解压完成", "Extraction complete", "解凍完了", "압축 해제 완료")
            dev.piko.shared.state.ArchiveCompletion.FAILED -> tr("解压失败", "Extraction failed", "解凍失敗", "압축 해제 실패")
            dev.piko.shared.state.ArchiveCompletion.UNCONFIRMED -> tr("解压结果待确认", "Extraction result unconfirmed", "解凍結果は未確認です", "압축 해제 결과 미확인")
        }, it.message)
    },
    folderVaultSession.outcomes.filter { withUndoable || !it.undoable }.map { WorkNotice(it.title, it.message) },
)

private fun downloadNotices(downloads: PikoDownloadCoordinator): Flow<WorkNotice> = flow {
    var previousStatuses: Map<String, DownloadStatus>? = null
    downloads.tasks.collect { tasks ->
        val previous = previousStatuses
        previousStatuses = tasks.mapValues { it.value.status }
        if (previous == null) return@collect
        val changedBatches = mutableSetOf<DownloadBatch>()
        tasks.forEach { (id, task) ->
            val before = previous[id] ?: return@forEach
            if (before == task.status) return@forEach
            // 文件夹下载逐个报会刷屏，整批停下时汇总成一条
            task.batch?.let {
                changedBatches += it
                return@forEach
            }
            when (task.status) {
                DownloadStatus.COMPLETED -> emit(WorkNotice(tr("下载完成", "Download complete", "ダウンロード完了", "다운로드 완료"), tr("${task.fileName} 已下载到本机", "${task.fileName} downloaded to this device", "${task.fileName} をこの端末にダウンロードしました", "${task.fileName}을(를) 기기에 다운로드했습니다")))
                DownloadStatus.FAILED -> emit(WorkNotice(tr("下载失败", "Download failed", "ダウンロード失敗", "다운로드 실패"), "${task.fileName}：${task.errorMessage ?: tr("未知错误", "Unknown error", "不明なエラー", "알 수 없는 오류")}"))
                else -> Unit
            }
        }
        changedBatches.forEach { batch ->
            val members = tasks.values.filter { it.batch?.id == batch.id }
            // 还有在下、排着或暂停的，这一批就还没结束
            if (members.any { it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.FAILED }) return@forEach
            val failed = members.count { it.status == DownloadStatus.FAILED }
            emit(
                if (failed == 0) {
                    WorkNotice(tr("下载完成", "Download complete", "ダウンロード完了", "다운로드 완료"), tr("${batch.folderName} 已下载到本机，共 ${members.size} 个文件", "${batch.folderName} downloaded: ${enCount(members.size, "file", "files")}", "${batch.folderName} をダウンロードしました（${members.size} ファイル）", "${batch.folderName} 다운로드 완료: 파일 ${members.size}개"))
                } else {
                    WorkNotice(tr("下载失败", "Download failed", "ダウンロード失敗", "다운로드 실패"), tr("${batch.folderName}：$failed 个文件下载失败", "${batch.folderName}: ${enCount(failed, "download", "downloads")} failed", "${batch.folderName}：$failed ファイルのダウンロードに失敗しました", "${batch.folderName}: 파일 ${failed}개 다운로드 실패"))
                },
            )
        }
    }
}

/**
 * 一个文件夹常有成百个文件，秒传时一秒完成好几个，逐个报会刷屏，所以攒到队列排空时汇总成一条；
 * 只有一个文件时写出文件名。
 */
private fun uploadNotices(uploads: PikoUploadCoordinator, clients: PikoClientProvider): Flow<WorkNotice> = flow {
    val accumulator = UploadNoticeAccumulator(Clock.System.now().toEpochMilliseconds())
    combine(uploads.tasks, uploads.preparations, clients.currentClient) { tasks, preparing, client -> Triple(tasks, preparing, client?.account) }
        .collect { (tasks, preparing, account) ->
            accumulator.accept(tasks, preparing, account)?.let { emit(it) }
        }
}

/** 队列暂空不等于准备结束；跨账号切换也不能交付上一个账号的汇总。 */
internal class UploadNoticeAccumulator(private val startedAtMs: Long) {
    private var previousStatuses = emptyMap<String, UploadStatus>()
    private val finished = mutableMapOf<String, UploadTask>()
    private var previousAccount: String? = null

    fun accept(tasks: Map<String, UploadTask>, preparing: List<dev.piko.shared.upload.UploadPreparation>, account: String?): WorkNotice? {
        if (account != previousAccount) { finished.clear(); previousAccount = account }
        tasks.forEach { (id, task) ->
            if (task.account != account) return@forEach
            val previous = previousStatuses[id]
            if (previous == task.status) return@forEach
            // 没见过的任务有两种：启动时读回的旧记录，不该报；刚入队就在两次收集之间秒传完的，要报
            if (previous == null && task.createdAtMs < startedAtMs) return@forEach
            if (task.status == UploadStatus.COMPLETED || task.status == UploadStatus.FAILED) {
                finished[id] = task
            } else {
                // 失败后重试，等它再次结束
                finished -= id
            }
        }
        finished.keys.retainAll(tasks.keys)
        previousStatuses = tasks.mapValues { it.value.status }
        if (finished.isEmpty() || tasks.values.anyActiveFor(account) || preparing.any { it.account == account && it.isRunning }) return null
        val notice = uploadSummary(finished.values.toList())
        finished.clear()
        return notice
    }
}

/**
 * 上传队列只跑当前账号的任务，换号时正在传的转为暂停，其余账号排着的一直是 QUEUED。
 * 不按账号过滤的话，换过号就永远有活跃任务，汇总永远等不到队列排空。
 */
fun Collection<UploadTask>.anyActiveFor(account: String?): Boolean =
    any { it.account == account && it.status.isActive }

private fun uploadSummary(tasks: List<UploadTask>): WorkNotice {
    val completed = tasks.filter { it.status == UploadStatus.COMPLETED }
    val failed = tasks.filter { it.status == UploadStatus.FAILED }
    tasks.singleOrNull()?.let { task ->
        return when {
            failed.isNotEmpty() -> WorkNotice(tr("上传失败", "Upload failed", "アップロード失敗", "업로드 실패"), "${task.fileName}：${task.errorMessage ?: tr("未知错误", "Unknown error", "不明なエラー", "알 수 없는 오류")}")
            task.isInstant -> WorkNotice(tr("秒传完成", "Instant upload complete", "インスタントアップロード完了", "즉시 업로드 완료"), tr("${task.fileName} 已秒传到「${task.parentName}」", "${task.fileName} uploaded instantly to “${task.parentName}”", "${task.fileName} を「${task.parentName}」にインスタントアップロードしました", "${task.fileName}을(를) “${task.parentName}”에 즉시 업로드했습니다"))
            else -> WorkNotice(tr("上传完成", "Upload complete", "アップロード完了", "업로드 완료"), tr("${task.fileName} 已上传到「${task.parentName}」", "${task.fileName} uploaded to “${task.parentName}”", "${task.fileName} を「${task.parentName}」にアップロードしました", "${task.fileName}을(를) “${task.parentName}”에 업로드했습니다"))
        }
    }
    val title = when {
        failed.isEmpty() -> tr("上传完成", "Upload complete", "アップロード完了", "업로드 완료")
        completed.isEmpty() -> tr("上传失败", "Upload failed", "アップロード失敗", "업로드 실패")
        else -> tr("上传结束", "Uploads finished", "アップロード終了", "업로드 종료")
    }
    val instantCount = completed.count { it.isInstant }
    val completedPart = when {
        completed.isEmpty() -> null
        instantCount == completed.size -> tr("${completed.size} 个文件已秒传", "${enCount(completed.size, "file", "files")} uploaded instantly", "${completed.size} ファイルをインスタントアップロードしました", "파일 ${completed.size}개 즉시 업로드 완료")
        instantCount > 0 -> tr("${completed.size} 个文件已上传，其中 $instantCount 个秒传", "${enCount(completed.size, "file", "files")} uploaded, including $instantCount instant uploads", "${completed.size} ファイルをアップロードしました（うち $instantCount 件はインスタント）", "파일 ${completed.size}개 업로드 완료, ${instantCount}개 즉시 업로드")
        else -> tr("${completed.size} 个文件已上传", "${enCount(completed.size, "file", "files")} uploaded", "${completed.size} ファイルをアップロードしました", "파일 ${completed.size}개 업로드 완료")
    }
    val failedPart = failed.takeIf { it.isNotEmpty() }?.let { tr("${it.size} 个失败", "${it.size} failed", "${it.size} 件失敗", "${it.size}개 실패") }
    return WorkNotice(title, listOfNotNull(completedPart, failedPart).joinToString(tr("；", "; ", "、", ", ")))
}

/** Keep each session's observer across unrelated additions; removal ends only that observer. */
internal fun <T : Any, R> sessionNotices(sessions: () -> List<T>, notices: (T, Boolean) -> Flow<R>): Flow<R> = channelFlow {
    val observers = mutableMapOf<T, Job>()
    var first = true
    snapshotFlow { sessions() }.collect { current ->
        observers.keys.filter { it !in current }.forEach { removed -> observers.remove(removed)?.cancel() }
        current.filter { it !in observers }.forEach { session ->
            val preexisting = first
            observers[session] = launch { notices(session, preexisting).collect { send(it) } }
        }
        first = false
    }
}

private fun DuplicateFinderState.Phase.isFinished() =
    this == DuplicateFinderState.Phase.DONE || this == DuplicateFinderState.Phase.FAILED

/** 每开一次查找重复，等它扫完报一次；开着面板时结果就在眼前，由呈现的一端决定报不报。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun duplicateNotices(session: DuplicateSession, skipExisting: DuplicateFinderState?): Flow<WorkNotice> =
    snapshotFlow { session.state }.filterNotNull().flatMapLatest { finder ->
        if (finder === skipExisting) emptyFlow() else flow {
            snapshotFlow { finder.phase }.first { it.isFinished() }
            emit(duplicateSummary(finder))
        }
    }

private fun duplicateSummary(finder: DuplicateFinderState): WorkNotice {
    val rootName = finder.root.localizedName
    if (finder.phase == DuplicateFinderState.Phase.FAILED) {
        return WorkNotice(tr("查找重复失败", "Duplicate search failed", "重複検索に失敗しました", "중복 검색 실패"), "「$rootName」：${finder.errorMessage ?: tr("未知错误", "Unknown error", "不明なエラー", "알 수 없는 오류")}")
    }
    val report = finder.report
    val groups = report.identical.size + report.versions.size
    val message = if (groups == 0) {
        tr("「$rootName」中未发现重复文件", "No duplicates in “$rootName”", "「$rootName」に重複ファイルはありません", "“$rootName”에 중복 파일이 없습니다")
    } else {
        val reclaimable = report.identical.sumOf { it.reclaimableBytes }
        tr("「$rootName」中有 $groups 组重复文件", "$groups duplicate groups in “$rootName”", "「$rootName」に $groups 組の重複ファイルがあります", "“$rootName”에 중복 파일 ${groups}개 그룹이 있습니다") +if (reclaimable > 0) tr("，可释放 ${reclaimable.toReadableSize()}", "; ${reclaimable.toReadableSize()} can be freed", "、${reclaimable.toReadableSize()} を解放できます", ", ${reclaimable.toReadableSize()} 확보 가능") else ""
    }
    return WorkNotice(tr("查找重复完成", "Duplicate search complete", "重複検索完了", "중복 검색 완료"), message)
}
