package dev.piko.ui

import androidx.compose.runtime.snapshotFlow
import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.DuplicateSession
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.UploadStatus
import dev.piko.shared.upload.UploadTask
import dev.piko.ui.components.toReadableSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlin.time.Clock

/** 一件后台工作的结局，给不在眼前的用户看：Android 发系统通知，桌面发 Toast。 */
class WorkNotice(val title: String, val message: String)

/**
 * 下载、上传、解压、查找重复的结局汇成一条流。何时呈现由各端决定：应用或窗口在前台时，
 * 列表与 Snackbar 已经说明了，通常不必再发。只报订阅之后结束的工作，启动时读回的旧记录不报。
 */
fun PikoServices.workNotices(): Flow<WorkNotice> = merge(
    downloadNotices(downloadManager),
    uploadNotices(uploadManager, clientManager),
    archiveExtractSession.outcomes.map { WorkNotice(if (it.succeeded) "解压完成" else "解压失败", it.message) },
    duplicateNotices(duplicateSession),
)

private fun downloadNotices(downloads: PikoDownloadCoordinator): Flow<WorkNotice> = flow {
    var previousStatuses: Map<String, DownloadStatus>? = null
    downloads.tasks.collect { tasks ->
        val previous = previousStatuses
        previousStatuses = tasks.mapValues { it.value.status }
        if (previous == null) return@collect
        tasks.forEach { (id, task) ->
            val before = previous[id] ?: return@forEach
            if (before == task.status) return@forEach
            when (task.status) {
                DownloadStatus.COMPLETED -> emit(WorkNotice("下载完成", "${task.fileName} 已下载到本机"))
                DownloadStatus.FAILED -> emit(WorkNotice("下载失败", "${task.fileName}：${task.errorMessage ?: "未知错误"}"))
                else -> Unit
            }
        }
    }
}

/**
 * 一个文件夹常有成百个文件，秒传时一秒完成好几个，逐个报会刷屏，所以攒到队列排空时汇总成一条；
 * 只有一个文件时写出文件名。
 */
private fun uploadNotices(uploads: PikoUploadCoordinator, clients: PikoClientProvider): Flow<WorkNotice> = flow {
    val startedAtMs = Clock.System.now().toEpochMilliseconds()
    var previousStatuses = emptyMap<String, UploadStatus>()
    val finished = mutableMapOf<String, UploadTask>()
    uploads.tasks.collect { tasks ->
        tasks.forEach { (id, task) ->
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
        val account = clients.currentClient.value?.account
        if (finished.isEmpty() || tasks.values.anyActiveFor(account)) return@collect
        emit(uploadSummary(finished.values.toList()))
        finished.clear()
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
            failed.isNotEmpty() -> WorkNotice("上传失败", "${task.fileName}：${task.errorMessage ?: "未知错误"}")
            task.isInstant -> WorkNotice("秒传完成", "${task.fileName} 已秒传到「${task.parentName}」")
            else -> WorkNotice("上传完成", "${task.fileName} 已上传到「${task.parentName}」")
        }
    }
    val title = when {
        failed.isEmpty() -> "上传完成"
        completed.isEmpty() -> "上传失败"
        else -> "上传结束"
    }
    val instantCount = completed.count { it.isInstant }
    val completedPart = when {
        completed.isEmpty() -> null
        instantCount == completed.size -> "${completed.size} 个文件已秒传"
        instantCount > 0 -> "${completed.size} 个文件已上传，其中 $instantCount 个秒传"
        else -> "${completed.size} 个文件已上传"
    }
    val failedPart = failed.takeIf { it.isNotEmpty() }?.let { "${it.size} 个失败" }
    return WorkNotice(title, listOfNotNull(completedPart, failedPart).joinToString("；"))
}

/** 每开一次查找重复，等它扫完报一次；开着面板时结果就在眼前，由呈现的一端决定报不报。 */
private fun duplicateNotices(session: DuplicateSession): Flow<WorkNotice> =
    snapshotFlow { session.state }.filterNotNull().flatMapLatest { finder ->
        flow {
            snapshotFlow { finder.phase }.first { it == DuplicateFinderState.Phase.DONE || it == DuplicateFinderState.Phase.FAILED }
            emit(duplicateSummary(finder))
        }
    }

private fun duplicateSummary(finder: DuplicateFinderState): WorkNotice {
    if (finder.phase == DuplicateFinderState.Phase.FAILED) {
        return WorkNotice("查找重复失败", "「${finder.root.name}」：${finder.errorMessage ?: "未知错误"}")
    }
    val report = finder.report
    val groups = report.identical.size + report.versions.size
    val message = if (groups == 0) {
        "「${finder.root.name}」里没有重复文件"
    } else {
        val reclaimable = report.identical.sumOf { it.reclaimableBytes }
        "「${finder.root.name}」找到 $groups 组重复" + if (reclaimable > 0) "，可腾出 ${reclaimable.toReadableSize()}" else ""
    }
    return WorkNotice("查找重复完成", message)
}
