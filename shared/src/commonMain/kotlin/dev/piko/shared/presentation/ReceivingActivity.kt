package dev.piko.shared.presentation

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.OfflinePackStage
import dev.piko.shared.state.ArchiveCompletion
import dev.piko.shared.state.ArchiveExtractSession
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.shared.state.InstantSaveRecords
import dev.piko.shared.state.OfflineSubmitSession
import dev.piko.shared.state.OfflineSubmitStatus
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.shared.upload.UploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 动作规则读取接收任务的已知依赖，不决定业务执行或释放资源。 */
class ReceivingActivity(private val instant: InstantSaveRecords, private val uploads: PikoUploadCoordinator,
    private val offline: OfflineSubmitSession? = null, private val extracts: ArchiveExtractSession? = null,
    private val packs: OfflinePackTracker? = null, scope: CoroutineScope? = null) {
    private var revision by mutableLongStateOf(0L)
    val changes: Flow<Unit> = merge(
        instant.attempts.map { Unit }, uploads.preparations.map { Unit }, uploads.tasks.map { Unit },
        offline?.works?.map { Unit } ?: emptyFlow(), packs?.jobs?.map { Unit } ?: emptyFlow(),
        offline?.receivingTargets?.map { Unit } ?: emptyFlow(),
        snapshotFlow { extracts?.let { it.jobs + it.completedJobs } }.map { Unit },
    )
    init { scope?.launch { changes.collect { revision++ } } }

    fun targets(account: String): Set<String> = revision.let { buildSet {
        instant.attempts.value.filter { it.account == account && (it.isRunning || it.receipt?.requiresInspection == true) }.forEach {
            add(it.target.id); addAll(it.receipt?.folders?.values.orEmpty())
        }
        uploads.preparations.value.filter { it.account == account && (it.isRunning || it.hasUnconfirmedDirectory) }.forEach {
            add(it.parentId); addAll(it.directories.map { directory -> directory.id })
        }
        uploads.tasks.value.values.filter { it.account == account && it.status != UploadStatus.COMPLETED }.forEach { add(it.parentId) }
        offline?.works?.value.orEmpty().filter { it.account == account && (it.isRunning || it.status == OfflineSubmitStatus.UNCONFIRMED) }.forEach { add(it.target.id) }
        offline?.receivingTargets?.value.orEmpty().filter { it.account == account }.forEach { add(it.parentId); it.fileId.takeIf(String::isNotBlank)?.let(::add) }
        packs?.jobs?.value.orEmpty().filter { it.account == account && it.isActive }.forEach { add(it.targetId); it.outputId.takeIf(String::isNotBlank)?.let(::add) }
        archiveJobs().forEach { add(it.file.parentId) }
    } }

    fun files(account: String): Set<String> = revision.let { buildSet {
        instant.attempts.value.filter { it.account == account }.forEach { work ->
            val receipt = work.receipt ?: return@forEach
            receipt.confirmed.forEach { (path, id) -> if (work.isRunning || path in receipt.namingUnconfirmed) add(id) }
        }
        uploads.tasks.value.values.filter { it.account == account && it.status != UploadStatus.COMPLETED }.forEach {
            (it.session?.fileId ?: it.fileId)?.takeIf(String::isNotBlank)?.let(::add)
        }
        archiveJobs().forEach { add(it.file.id) }
        offline?.receivingTargets?.value.orEmpty().filter { it.account == account }.forEach { it.fileId.takeIf(String::isNotBlank)?.let(::add) }
    } }

    /** 裁剪按原始相对路径永久删除未选内容，期间整棵产出树必须保持名称和位置。 */
    fun exclusiveRoots(account: String): Set<String> = revision.let {
        packs?.jobs?.value.orEmpty().filter { it.account == account && it.stage == OfflinePackStage.PRUNING }
            .mapNotNullTo(HashSet()) { it.outputId.takeIf(String::isNotBlank) }
    }

    private fun archiveJobs() = extracts?.let { session ->
        session.jobs + session.completedJobs.filter { (it.status as? ArchiveJobStatus.Finished)?.outcome == ArchiveCompletion.UNCONFIRMED }
    }.orEmpty()
}
