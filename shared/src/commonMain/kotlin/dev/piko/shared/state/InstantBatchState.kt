package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class InstantBatchRowStatus {
    /** 等待或正在解析。 */
    RESOLVING,

    /** 已解析，按勾选保存。 */
    READY,

    /** 已解析但一项也没勾，保存时跳过。 */
    NOTHING_SELECTED,

    /** 要新建文件夹，名字却是空的。 */
    NEEDS_NAME,

    /** 非磁力链接，或云端未收录：整条交给离线任务。 */
    WHOLE_OFFLINE,

    /** 解析失败，要重试或移除。 */
    FAILED,
}

/** 批量列表里的一行。[state] 就是单条链接的工作台，点进去看到的与只粘一条时相同。 */
class InstantBatchRow internal constructor(
    val link: PastedLink,
    val state: InstantSheetState,
    internal val scope: CoroutineScope,
) {
    val key: String get() = link.key

    val status: InstantBatchRowStatus by derivedStateOf {
        val s = state
        when {
            // 防抖期间还没开始请求，同样算解析中
            s.isResolving || (s.normalizedMagnet != null && s.resolution == null && s.errorMessage == null) ->
                InstantBatchRowStatus.RESOLVING
            s.resolution == null && (s.isUnindexed || s.normalizedMagnet == null) -> InstantBatchRowStatus.WHOLE_OFFLINE
            s.resolution == null -> InstantBatchRowStatus.FAILED
            s.selectedItems.isEmpty() -> InstantBatchRowStatus.NOTHING_SELECTED
            s.willCreateFolder && s.folderName.isBlank() -> InstantBatchRowStatus.NEEDS_NAME
            else -> InstantBatchRowStatus.READY
        }
    }

    /** 勾选部分的大小，列表行里显示。 */
    val selectedBytes: Long by derivedStateOf { state.selectedItems.sumOf { it.file.size } }
}

/**
 * 一次粘进多条链接时的列表。
 *
 * 每条链接一个完整的 [InstantSheetState]，各自解析（并发数由共享的信号量限制）、各自勾选，
 * 点开一行就是单条时的工作台，返回列表后勾选仍在。保存目标全部共用。
 *
 * 「全部保存」按各行自己的路线逐条提交。空间按全部整包离线的合计检查：各自放得下、
 * 合起来放不下的情况逐条查是拦不住的。全部成功才发 outcome，调用方随之结束会话并跳转；
 * 部分失败时成功的移出列表，失败的留着并标出原因，可以再点一次。
 */
class InstantBatchState internal constructor(
    links: List<PastedLink>,
    private val newRow: (PastedLink, CoroutineScope) -> InstantSheetState,
    private val driveRepo: PikoDriveRepository,
    private val shared: InstantSharedContext,
    private val scope: CoroutineScope,
    private val emitOutcome: suspend (InstantSaveOutcome) -> Unit,
    private val emitMessage: suspend (String) -> Unit,
    private val onEmpty: () -> Unit,
) {
    var rows by mutableStateOf(links.map(::createRow))
        private set

    /** 点开的那一行，为 null 时显示列表。 */
    var openedRow by mutableStateOf<InstantBatchRow?>(null)
        private set

    var isSaving by mutableStateOf(false)
        private set

    /** 网盘剩余空间，打开列表时与保存前各查一次。null 表示未知，此时不拦。 */
    var remainingBytes by mutableStateOf<Long?>(null)
        private set

    /** 上一次「全部保存」里失败的行，键为 [InstantBatchRow.key]。 */
    val saveErrors = mutableStateMapOf<String, String>()

    private val submittableRows: List<InstantBatchRow> by derivedStateOf {
        rows.filter { it.status == InstantBatchRowStatus.READY || it.status == InstantBatchRowStatus.WHOLE_OFFLINE }
    }

    /** 全部整包离线的合计大小。离线要先把整包落进网盘，比的是整包，不是勾选的部分。 */
    val packBytes: Long by derivedStateOf {
        submittableRows
            .filter { it.status == InstantBatchRowStatus.READY }
            .mapNotNull { it.state.savePlan }
            .filter { it.route == SaveRoute.OFFLINE_PACK }
            .sumOf { it.packBytes }
    }

    val lacksSpace: Boolean by derivedStateOf { remainingBytes?.let { packBytes > it } == true }

    val submittableCount: Int by derivedStateOf { submittableRows.size }

    /** 为什么还不能保存。空间不足另有说明，不在这里。 */
    val blockedReason: String? by derivedStateOf {
        val resolving = rows.count { it.status == InstantBatchRowStatus.RESOLVING }
        val failed = rows.count { it.status == InstantBatchRowStatus.FAILED }
        val unnamed = rows.count { it.status == InstantBatchRowStatus.NEEDS_NAME }
        when {
            resolving > 0 -> "$resolving 项正在解析"
            failed > 0 -> "$failed 项解析失败，重试或移除后再保存"
            unnamed > 0 -> "$unnamed 项的文件夹名为空"
            submittableRows.isEmpty() -> "没有要保存的项"
            else -> null
        }
    }

    val canSaveAll: Boolean by derivedStateOf {
        !isSaving && shared.target.value != null && blockedReason == null && !lacksSpace
    }

    init {
        scope.launch { refreshRemainingBytes() }
    }

    fun open(row: InstantBatchRow) {
        openedRow = row
    }

    fun closeRow() {
        openedRow = null
    }

    fun remove(row: InstantBatchRow) {
        if (isSaving) return
        dropRow(row)
        if (rows.isEmpty()) onEmpty()
    }

    fun saveAll() {
        if (!canSaveAll) return
        val target = shared.target.value ?: return
        val toSubmit = submittableRows
        val needed = packBytes
        isSaving = true
        saveErrors.clear()
        scope.launch {
            try {
                // 解析时查到的余量可能已经过时，而离线一旦提交就是整包落盘
                val remaining = refreshRemainingBytes()
                if (remaining != null && needed > remaining) return@launch
                val createdIds = mutableListOf<String>()
                var allInstant = true
                val succeeded = mutableListOf<InstantBatchRow>()
                // 逐条提交：秒传内部已按文件并发，行与行之间不再叠一层
                for (row in toSubmit) {
                    row.state.submitForBatch(target)
                        .onSuccess { ids ->
                            if (ids == null) allInstant = false else createdIds += ids
                            succeeded += row
                        }
                        .onFailure { saveErrors[row.key] = "保存失败：${it.message}" }
                }
                if (saveErrors.isEmpty()) {
                    emitOutcome(
                        if (allInstant) {
                            InstantSaveOutcome.InstantSaved(createdIds, target)
                        } else {
                            InstantSaveOutcome.OfflineTaskCreated(target, submittedCount = succeeded.size)
                        },
                    )
                } else {
                    succeeded.forEach(::dropRow)
                    emitMessage("已提交 ${succeeded.size} 项，${saveErrors.size} 项失败")
                }
            } finally {
                isSaving = false
            }
        }
    }

    /** 整个列表被换掉时调用：各行的子作用域挂在会话作用域下，不取消的话旧列表的解析会一直跑到会话结束。 */
    internal fun dispose() {
        rows.forEach { it.scope.cancel() }
    }

    private fun createRow(link: PastedLink): InstantBatchRow {
        // 每行一个子作用域：移除时连同进行中的解析一起取消，不影响别的行
        val rowScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        return InstantBatchRow(link, newRow(link, rowScope), rowScope)
    }

    private fun dropRow(row: InstantBatchRow) {
        row.scope.cancel()
        rows = rows - row
        saveErrors.remove(row.key)
        if (openedRow == row) openedRow = null
    }

    private suspend fun refreshRemainingBytes(): Long? {
        driveRepo.getQuota().onSuccess { response ->
            remainingBytes = response.quota.takeIf { it.limitBytes > 0 }?.remainingBytes
        }
        return remainingBytes
    }
}
