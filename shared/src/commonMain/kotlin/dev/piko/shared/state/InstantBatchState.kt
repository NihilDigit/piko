package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.failureText
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val TAG = "Instant"

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
    NEEDS_REVIEW,
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
            s.saveBlockReason != null || (s.saveIssue?.requiresInspection == true && s.retryFileCount == 0) -> InstantBatchRowStatus.NEEDS_REVIEW
            // 防抖期间还没开始请求，同样算解析中
            s.isResolving || (!s.needsSharePassCode && s.resolution == null && !s.isUnindexed && s.errorMessage == null) ->
                InstantBatchRowStatus.RESOLVING
            s.needsSharePassCode -> InstantBatchRowStatus.FAILED
            s.resolution == null && s.isUnindexed -> InstantBatchRowStatus.WHOLE_OFFLINE
            s.resolution == null -> InstantBatchRowStatus.FAILED
            s.selectedItems.isEmpty() -> InstantBatchRowStatus.NOTHING_SELECTED
            s.willCreateFolder && s.folderNameToSave.isBlank() -> InstantBatchRowStatus.NEEDS_NAME
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
 * 「全部保存」按各行自己的路线并发提交。空间按全部整包离线的合计检查：各自放得下、
 * 合起来放不下的情况逐条查是拦不住的。全部成功才发 outcome，调用方随之结束会话并跳转；
 * 部分失败时成功的移出列表，失败的留着并标出原因，可以再点一次。
 */
class InstantBatchState internal constructor(
    links: List<PastedLink>,
    private val newRow: (PastedLink, CoroutineScope) -> InstantSheetState,
    private val driveRepo: PikoDriveRepository,
    private val shared: InstantSharedContext,
    private val scope: CoroutineScope,
    private val saveScope: CoroutineScope,
    private val emitOutcome: suspend (InstantSaveOutcome) -> Unit,
    private val emitMessage: suspend (String) -> Unit,
    private val onEmpty: () -> Unit,
) {
    var saveAsReference by mutableStateOf(false)
        private set

    var rows by mutableStateOf(links.map(::createRow))
        private set

    /** 点开的那一行，为 null 时显示列表。 */
    var openedRow by mutableStateOf<InstantBatchRow?>(null)
        private set

    var isSaving by mutableStateOf(false)
        private set

    private var stopRequested = false

    fun stopSaving() {
        stopRequested = true
    }

    /** 网盘剩余空间，打开列表时与保存前各查一次。null 表示未知，此时不拦。 */
    var remainingBytes by mutableStateOf<Long?>(null)
        private set

    /** 上一次「全部保存」里失败的行，键为 [InstantBatchRow.key]。 */
    val saveErrors = mutableStateMapOf<String, String>()

    private val submittableRows: List<InstantBatchRow> by derivedStateOf {
        rows.filter {
            (it.status == InstantBatchRowStatus.READY || it.status == InstantBatchRowStatus.WHOLE_OFFLINE) &&
                (!it.state.hasInstantRetry || it.state.retryFileCount > 0) &&
                (it.state.saveIssue?.requiresInspection != true || it.state.retryFileCount > 0)
                && it.state.saveBlockReason == null && (!saveAsReference || it.state.referenceItemsToSave.isNotEmpty())
        }
    }

    private val readyPlans: List<SavePlan> by derivedStateOf {
        submittableRows.filter { it.status == InstantBatchRowStatus.READY && !it.state.hasInstantRetry }.mapNotNull { it.state.savePlan }
    }

    /** 全部要落进网盘的合计：秒传是选中的文件，整包离线是整包，下完才删掉没选的。 */
    val neededBytes: Long by derivedStateOf { readyPlans.sumOf { it.neededBytes } + submittableRows.sumOf { it.state.retryNeededBytes } }

    val lacksSpace: Boolean by derivedStateOf { remainingBytes?.let { neededBytes > it } == true }

    /** 要建的离线任务数：整包离线的行与整条交给离线的行各一个。 */
    val offlineCount: Int by derivedStateOf {
        readyPlans.count { it.route == SaveRoute.OFFLINE_PACK } +
            submittableRows.count { it.status == InstantBatchRowStatus.WHOLE_OFFLINE }
    }

    val offlineLeft: Int? get() = shared.account.value.offlineLeft

    val confirmsOffline: Boolean get() = shared.account.value.free

    val lacksOfflineCount: Boolean by derivedStateOf { offlineLeft?.let { offlineCount > it } == true }

    val submittableCount: Int by derivedStateOf { submittableRows.size }

    /** 为什么还不能保存。空间不足另有说明，不在这里。 */
    val blockedReason: String? by derivedStateOf {
        val resolving = rows.count { it.status == InstantBatchRowStatus.RESOLVING }
        val failed = rows.count { it.status == InstantBatchRowStatus.FAILED }
        val unnamed = rows.count { it.status == InstantBatchRowStatus.NEEDS_NAME }
        when {
            resolving > 0 -> tr("$resolving 项正在解析", "Resolving ${enCount(resolving, "item", "items")}", "$resolving 項目を解析中", "${resolving}개 항목 분석 중")
            failed > 0 -> tr("$failed 项解析失败，重试或移除后再保存", "Failed to resolve ${enCount(failed, "item", "items")}. Retry or remove before saving", "$failed 項目の解析に失敗しました。再試行または削除してから保存してください", "${failed}개 항목 분석 실패. 다시 시도하거나 제거한 후 저장하세요")
            unnamed > 0 -> tr("$unnamed 项的文件夹名为空", "${enCount(unnamed, "item", "items")} have an empty folder name", "$unnamed 項目のフォルダー名が空です", "${unnamed}개 항목의 폴더 이름이 비어 있습니다")
            saveAsReference && rows.none { it.state.referenceItemsToSave.isNotEmpty() } ->
                tr("所选内容均缺少引用，无法以引用保存", "Selected items have no references and cannot be saved as references", "選択した項目に参照がないため、参照として保存できません", "선택한 항목에 참조가 없어 참조로 저장할 수 없습니다")
            rows.any { it.state.savePlan?.incompleteCatalog == true } -> tr("有不完整的文件清单，请逐项检查并选择可保存的内容", "Some file catalogs are incomplete. Inspect each item and select savable content", "不完全なファイル一覧があります。各項目を確認して保存できる内容を選択してください", "불완전한 파일 목록이 있습니다. 각 항목을 확인하고 저장 가능한 내용을 선택하세요")
            submittableRows.isEmpty() && rows.any { it.status == InstantBatchRowStatus.NEEDS_REVIEW } -> rows.firstNotNullOfOrNull { it.state.saveBlockReason } ?: tr("保存结果需要先在任务页核对", "Check save results in Tasks first", "先にタスクページで保存結果を確認してください", "먼저 작업 페이지에서 저장 결과를 확인하세요")
            submittableRows.isEmpty() -> tr("没有要保存的项", "No items to save", "保存する項目がありません", "저장할 항목이 없습니다")
            else -> null
        }
    }

    val canSaveAll: Boolean by derivedStateOf {
        scope.isActive && !isSaving && shared.target.value != null && blockedReason == null && !lacksSpace && !lacksOfflineCount
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

    fun updateSaveAsReference(value: Boolean) {
        if (isSaving || rows.any { !it.state.canEdit || it.state.hasRetainedInstantAttempt }) return
        saveAsReference = value
        rows.forEach { it.state.updateSaveAsReference(value) }
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
        val needed = neededBytes
        isSaving = true
        stopRequested = false
        saveErrors.clear()
        saveScope.launch {
            try {
                // 解析时查到的余量与次数可能已经过时，而离线一旦提交就是整包落盘
                val remaining = refreshRemainingBytes()
                if (remaining != null && needed > remaining) {
                    PikoLog.i(TAG, "批量保存：空间不足，需要 $needed 字节，剩余 $remaining 字节")
                    return@launch
                }
                if (lacksOfflineCount) {
                    PikoLog.i(TAG, "批量保存：今日离线次数不够")
                    return@launch
                }
                val createdIds = mutableListOf<String>()
                var allInstant = true
                val succeeded = mutableListOf<InstantBatchRow>()
                val permits = Semaphore(3)
                val results = coroutineScope {
                    toSubmit.map { row ->
                        async {
                            permits.withPermit {
                                if (stopRequested) null else row.state.submitForBatch(target)
                            }
                        }
                    }.awaitAll()
                }
                // 按输入顺序汇总，完成先后不改变回执和错误的顺序。
                for ((row, result) in toSubmit.zip(results)) {
                    if (result == null) continue
                    result
                        .onSuccess { ids ->
                            if (ids == null) allInstant = false else createdIds += ids
                            succeeded += row
                        }
                        // 行里的保存已经记过日志，这里只换成给列表看的一句
                        .onFailure { saveErrors[row.key] = failureText(tr("保存", "Save", "保存", "저장"), it) }
                }
                rows.filter { it !in succeeded && it.status == InstantBatchRowStatus.NEEDS_REVIEW }.forEach { row ->
                    saveErrors[row.key] = row.state.saveBlockReason ?: row.state.saveIssue?.message ?: tr("保存结果需要核对", "Save results need inspection", "保存結果の確認が必要です", "저장 결과 확인 필요")
                }
                PikoLog.i(TAG, "批量保存：${toSubmit.size} 条链接，成功 ${succeeded.size} 条（秒传出 ${createdIds.size} 个文件），失败 ${saveErrors.size} 条")
                if (saveErrors.isEmpty() && !stopRequested) {
                    emitOutcome(
                        if (allInstant) {
                            InstantSaveOutcome.InstantSaved(createdIds, target)
                        } else {
                            InstantSaveOutcome.OfflineTaskCreated(target, submittedCount = succeeded.size)
                        },
                    )
                } else {
                    succeeded.forEach(::dropRow)
                    emitMessage(
                        if (stopRequested) {
                            tr("已停止，已提交 ${succeeded.size} 项", "Stopped; submitted ${enCount(succeeded.size, "item", "items")}", "停止しました。${succeeded.size} 項目を送信しました", "중지됨. ${succeeded.size}개 항목 제출 완료")
                        } else {
                            tr("已提交 ${succeeded.size} 项，${saveErrors.size} 项需要处理", "Submitted ${enCount(succeeded.size, "item", "items")}; ${enCount(saveErrors.size, "item", "items")} need attention", "${succeeded.size} 項目を送信しました。${saveErrors.size} 項目の確認が必要です", "${succeeded.size}개 항목 제출 완료, ${saveErrors.size}개 항목 확인 필요")
                        },
                    )
                }
            } finally {
                isSaving = false
            }
        }
    }

    /** 整个列表被换掉时调用：各行的子作用域挂在会话作用域下，不取消的话旧列表的解析会一直跑到会话结束。 */
    internal fun dispose() {
        stopSaving()
        rows.forEach { it.scope.cancel() }
    }

    private fun createRow(link: PastedLink): InstantBatchRow {
        // 每行一个子作用域：移除时连同进行中的解析一起取消，不影响别的行
        val rowScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        return InstantBatchRow(link, newRow(link, rowScope).apply { updateSaveAsReference(this@InstantBatchState.saveAsReference) }, rowScope)
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
            shared.updateAccount(driveRepo.isFreeAccount(), response)
        }
        return remainingBytes
    }
}
