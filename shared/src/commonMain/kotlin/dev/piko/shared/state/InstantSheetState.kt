package dev.piko.shared.state

import dev.piko.shared.i18n.tr

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.InstantSaveReceipt
import dev.piko.shared.data.InstantSaveIncomplete
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.reportFailure
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.MagnetResolutionResult
import dev.piko.shared.data.OfflinePackTracker
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.PreviewTempFolder
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.naming.FileKind
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.av.AvInfo
import dev.piko.shared.naming.av.AvNamingItem
import dev.piko.shared.naming.av.canonicalAvNames
import dev.piko.shared.naming.av.canonicalResourceName
import dev.piko.shared.naming.parseMediaName
import io.github.nihildigit.pikpak.InstantContentUnavailableException
import io.github.nihildigit.pikpak.QuotaResponse
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.shareIdFromUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class NameGroupSummary(val selected: Int, val total: Int, val bytes: Long, val unindexed: Int)

enum class InstantActionKind {
    INSTANT_SAVE,

    OFFLINE_PACK,

    SUBMIT_OFFLINE,
}

data class InstantPrimaryAction(val kind: InstantActionKind, val fileCount: Int, val enabled: Boolean)

sealed interface InstantSaveOutcome {
    val target: PikoPathBreadcrumb

    data class InstantSaved(
        val createdIds: List<String>,
        override val target: PikoPathBreadcrumb,
    ) : InstantSaveOutcome

    data class OfflineTaskCreated(
        override val target: PikoPathBreadcrumb,
        val submittedCount: Int = 1,
    ) : InstantSaveOutcome
}

data class InstantPreviewRequest(val fileId: String, val fileName: String)

internal class InstantSharedContext {
    val saveAsReference = mutableStateOf(false)
    @Volatile var saveAsReferenceTouched = false

    val target = mutableStateOf<PikoPathBreadcrumb?>(null)
    val targetNotice = mutableStateOf<String?>(null)

    @Volatile
    var targetChosen = false
    val targetHeld = MutableStateFlow(false)
    val previewedIds = mutableMapOf<String, String>()
    var previewLease: PreviewTempFolder.Session? = null
    val resolvePermits = Semaphore(RESOLVE_CONCURRENCY)

    val account = mutableStateOf(SaveAccount())

    val canonicalNames = mutableStateOf(true)

    fun updateAccount(free: Boolean?, quota: QuotaResponse) {
        account.value = SaveAccount(free = free == true, offlineLeft = quota.quotas.cloudDownload.remaining)
    }

    private companion object {
        const val RESOLVE_CONCURRENCY = 3
    }
}

class InstantSheetState private constructor(
    private val instantRepo: InstantMagnetRepository,
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val previewFolder: PreviewTempFolder,
    private val packTracker: OfflinePackTracker,
    private val saveRecords: InstantSaveRecords,
    private val scope: CoroutineScope,
    private val saveScope: CoroutineScope,
    initialMagnet: String,
    private val shared: InstantSharedContext,
    private val isRoot: Boolean,
    private val saveTargetContext: SaveTargetContext,
) {
    constructor(
        instantRepo: InstantMagnetRepository,
        driveRepo: PikoDriveRepository,
        preferences: PikoUserPreferences,
        previewFolder: PreviewTempFolder,
        packTracker: OfflinePackTracker,
        saveRecords: InstantSaveRecords,
        scope: CoroutineScope,
        initialMagnet: String = "",
        saveTargetContext: SaveTargetContext = SaveTargetContext.fixed(),
        saveScope: CoroutineScope = scope,
    ) : this(
        instantRepo, driveRepo, preferences, previewFolder, packTracker, saveRecords, scope, saveScope, initialMagnet,
        InstantSharedContext(), isRoot = true, saveTargetContext,
    )

    var input by mutableStateOf(initialMagnet)
        private set

    var needsSharePassCode by mutableStateOf(false)
        private set
    var sharePassCode by mutableStateOf("")
        private set

    var isResolving by mutableStateOf(false)
        private set

    var isAnalyzing by mutableStateOf(false)
        private set

    internal var onSaveAccepted: (() -> Unit)? = null
    private var magnetSaveRunning by mutableStateOf(false)
    var isSaving: Boolean
        get() = magnetSaveRunning
        private set(value) {
            magnetSaveRunning = value
        }
    private fun acceptSave() { onSaveAccepted?.also { onSaveAccepted = null }?.invoke() }
    internal val hasLocalSave: Boolean get() = magnetSaveRunning || batch?.isSaving == true
    private var submittingOfflineAt by mutableStateOf<PikoPathBreadcrumb?>(null)
    internal val hasTrackedOfflineSubmission: Boolean
        get() = submittingOfflineAt?.let { destination ->
            saveAccount?.let { account ->
                instantRepo.offlineSubmissions.conflict(account, submittedUrl(), destination.id)?.isRunning == true
            }
        } == true
    internal val hasTrackedInstantAttempt: Boolean
        get() = saveRecords.attempts.value.any { containsInstantAttempt(it.id) }
    internal val hasUntrackedLocalSave: Boolean
        get() = (magnetSaveRunning && !hasTrackedOfflineSubmission) ||
            (batch?.isSaving == true && batch?.rows?.none { it.state.hasTrackedOfflineSubmission } == true)
    private val saveAccount = driveRepo.currentAccount
    private var instantAttemptId: String? = null
    private var saveAttempts by mutableStateOf(saveRecords.attempts.value)
    private var offlineWorkId: Long? = null
    private var offlineWorks by mutableStateOf(instantRepo.offlineSubmissions.works.value)
    private var targetRulesVersion by mutableStateOf(0L)
    val hasPendingOfflineSubmission: Boolean get() = offlineWorks.any { it.id == offlineWorkId && it.status == OfflineSubmitStatus.UNCONFIRMED }
    fun containsOfflineSubmission(id: Long): Boolean = offlineWorkId == id || batch?.rows?.any { it.state.containsOfflineSubmission(id) } == true
    val hasRetainedOfflineSubmission: Boolean get() = offlineWorks.any { containsOfflineSubmission(it.id) }
    private var savedContainer: PikoPathBreadcrumb? = null
    private var saveParent: PikoPathBreadcrumb? = null
    var saveIssue by mutableStateOf<InstantSaveReceipt?>(null)
        private set
    private var instantRetry by mutableStateOf<InstantRetryPlan?>(null)
    val hasInstantRetry: Boolean get() = instantRetry != null
    fun containsInstantAttempt(id: String): Boolean = instantAttemptId == id || batch?.rows?.any { it.state.containsInstantAttempt(id) } == true
    val hasRetainedInstantAttempt: Boolean get() = saveAttempts.any { containsInstantAttempt(it.id) }
    val retryFileCount: Int get() = instantRetry?.let { retry ->
        if (retry.receipt.unconfirmedFolder != null) 0 else retry.files.count { it.file.path in (retry.receipt.failed + retry.receipt.notAttempted) }
    } ?: 0
    val retryNeededBytes: Long get() = instantRetry?.let { retry ->
        retry.files.filter { it.file.path in (retry.receipt.failed + retry.receipt.notAttempted) }.sumOf { it.file.size }
    } ?: 0L
    val canEdit: Boolean get() = !isSaving && !hasPendingOfflineSubmission && batch?.isSaving != true && instantRetry == null && saveIssue?.requiresInspection != true &&
        batch?.rows?.none { it.state.hasInstantRetry || it.state.hasPendingOfflineSubmission || it.state.saveIssue?.requiresInspection == true } != false

    private fun resetEditableAttempt() {
        if (saveIssue == null) return
        instantAttemptId = null
        saveIssue = null
        savedContainer = null
        saveParent = null
        shared.targetHeld.value = false
    }
    val saveBlockReason: String? by derivedStateOf {
        targetRulesVersion
        val target = target
        val account = saveAccount
        if (target != null) (saveTargetContext.availability(target.id) as? dev.piko.shared.presentation.ActionAvailability.Blocked)?.let { return@derivedStateOf it.reason }
        if (target != null && account != null && instantRepo.offlineSubmissions.conflict(account, submittedUrl(), target.id, offlineWorks) != null)
            return@derivedStateOf tr("此文件夹有待确认的离线下载请求，请先在任务页核对", "Cloud download submission in this folder is unconfirmed. Check Tasks first", "このフォルダーのクラウドダウンロード送信結果が未確認です。先にタスク画面で確認してください", "이 폴더의 클라우드 다운로드 제출 결과가 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "此資料夾有待確認的離線下載請求，請先至任務頁核對")
        if (target != null && account != null && saveRecords.pendingConflict(account, target.id, itemsToSave.map { it.file },
                excluding = instantAttemptId, snapshot = saveAttempts, containerName = if (willCreateFolder) driveFolderName(folderNameToSave) else null,
                names = SaveNaming(itemsToSave).fileNames, keepStructure = willCreateFolder)) tr("目标位置有进行中或待确认的保存任务，请先在任务页处理", "A save to this destination is running or unconfirmed. Check Tasks first", "同じ保存先への保存が進行中または未確認です。先にタスク画面で確認してください", "같은 대상에 저장이 진행 중이거나 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "目標位置有進行中或待確認的儲存任務，請先至任務頁處理") else null
    }
    var resolution by mutableStateOf<MagnetResolutionResult?>(null)
        private set
    var selectedIndices by mutableStateOf<Set<Int>>(emptySet())
        private set
    private var selectionPicked by mutableStateOf(false)

    val hasPickedFiles: Boolean
        get() = saveIssue != null || hasPendingOfflineSubmission || (selectionPicked && selectedIndices.isNotEmpty()) || batch?.rows?.any { it.state.hasPickedFiles } == true

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var isUnindexed by mutableStateOf(false)
        private set

    var target: PikoPathBreadcrumb? by shared.target
        private set

    var targetNotice: String? by shared.targetNotice
        private set

    var batch by mutableStateOf<InstantBatchState?>(null)
        private set

    var folderName by mutableStateOf("")
        private set

    var saveAsReference: Boolean by shared.saveAsReference
        private set
    val supportsReferenceSave: Boolean by derivedStateOf { normalizedMagnet != null || findShareLink(input) != null }
    val effectiveSaveAsReference: Boolean by derivedStateOf { saveAsReference && supportsReferenceSave }

    fun updateSaveAsReference(value: Boolean) {
        if (!canEdit || instantRetry != null || saveIssue != null) return
        shared.saveAsReferenceTouched = true
        saveAsReference = value
        if (isRoot) scope.launch { preferences.setSaveAsReference(value) }
    }

    var useCanonicalNames: Boolean by shared.canonicalNames
        private set

    fun updateUseCanonicalNames(enabled: Boolean) {
        if (!canEdit) return
        resetEditableAttempt()
        useCanonicalNames = enabled
    }

    private class CanonicalNames(val titled: List<String>, val bare: List<String>, val codes: List<String?>, val videos: List<AvInfo?>)
    private var canonical by mutableStateOf<CanonicalNames?>(null)

    val offersCanonicalNames: Boolean get() = canonical != null
    private var folderNameEdited by mutableStateOf(false)

    private val selectedVideos: List<AvInfo> by derivedStateOf {
        val videos = canonical?.videos ?: return@derivedStateOf emptyList()
        selectedIndices.sorted().mapNotNull { videos.getOrNull(it) }
    }

    private val folderCode: String? by derivedStateOf {
        selectedVideos.map { it.code }.distinct().singleOrNull().takeIf { willCreateFolder }
    }

    fun nameToSave(index: Int): String {
        val original = items[index].file.name
        val names = canonical?.takeIf { useCanonicalNames } ?: return original
        return fileNameIn(names, index, folderCode, original)
    }

    fun renamedLabel(row: InstantRow): String? =
        nameToSave(row.index).takeIf { useCanonicalNames && it != items[row.index].file.name }
    private val folderNamedByCode: Boolean by derivedStateOf { useCanonicalNames && !folderNameEdited && folderCode != null }

    val folderNameToSave: String by derivedStateOf {
        if (folderNamedByCode) canonicalResourceName(folderName, selectedVideos) ?: folderName else folderName
    }

    private inner class SaveNaming(toSave: List<InstantFileItem>) {
        val folderNameToSave = this@InstantSheetState.folderNameToSave

        val fileNames: Map<String, String> = canonical?.takeIf { useCanonicalNames }?.let { names ->
            val indexOf = items.withIndex().associate { (index, item) -> item.file.path to index }
            toSave.mapNotNull { item ->
                val index = indexOf[item.file.path] ?: return@mapNotNull null
                fileNameIn(names, index, folderCode, item.file.name).takeIf { it != item.file.name }?.let { item.file.path to it }
            }.toMap()
        }.orEmpty()
    }

    var remainingBytes by mutableStateOf<Long?>(null)
        private set

    var previewingIndex by mutableStateOf<Int?>(null)
        private set

    private val previewedIds get() = shared.previewedIds

    private val _outcomes = MutableSharedFlow<InstantSaveOutcome>()
    val outcomes: SharedFlow<InstantSaveOutcome> = _outcomes.asSharedFlow()

    private val _previewRequests = MutableSharedFlow<InstantPreviewRequest>(extraBufferCapacity = 1)
    val previewRequests: SharedFlow<InstantPreviewRequest> = _previewRequests.asSharedFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val normalizedMagnet: String? by derivedStateOf { normalizeMagnet(input) }

    val items: List<InstantFileItem> by derivedStateOf { resolution?.items.orEmpty() }

    val isBlank: Boolean
        get() = input.isBlank() && resolution == null && batch == null && !isResolving && !isSaving

    val selectedItems: List<InstantFileItem> by derivedStateOf {
        selectedIndices.sorted().mapNotNull(items::getOrNull)
    }

    private var saveAttachedSubtitles by mutableStateOf(true)
    private var sourceSelectionEdited by mutableStateOf(false)

    private val itemsToSave: List<InstantFileItem> by derivedStateOf {
        sourceSelectedIndices.sorted().mapNotNull(items::getOrNull)
    }

    val referenceItemsToSave: List<InstantFileItem> by derivedStateOf { itemsToSave.filter { it.file.gcid != null } }
    val missingReferenceCount: Int by derivedStateOf { itemsToSave.size - referenceItemsToSave.size }

    val sourceSelectedIndices: Set<Int> by derivedStateOf {
        if (saveAttachedSubtitles || sourceSelectionEdited) selectedIndices
        else selectedIndices - tree?.rows?.flatMap { it.subtitleIndices }?.toSet().orEmpty()
    }

    val projectedSaveFiles: List<io.github.nihildigit.pikpak.ResolvedFile> by derivedStateOf {
        projectedSaveEntries.map { it.second }
    }

    val projectedSaveEntries: List<Pair<Int, io.github.nihildigit.pikpak.ResolvedFile>> by derivedStateOf {
        val batchRows = batch?.rows
        if (batchRows != null) {
            batchRows.flatMapIndexed { rowIndex, row ->
                row.state.projectedSaveEntries.map { (idx, file) -> (rowIndex * BATCH_INDEX_STRIDE + idx) to file }
            }
        } else {
            sourceSelectedIndices.sorted().filter { !effectiveSaveAsReference || items[it].file.gcid != null }.map { index ->
                val item = items[index]
                val name = nameToSave(index)
                val relative = if (willCreateFolder) item.file.path.substringBeforeLast('/', "").let { dir ->
                    if (dir.isEmpty()) name else "$dir/$name"
                } else name
                index to item.file.copy(path = if (willCreateFolder) "$folderNameToSave/$relative" else relative)
            }
        }
    }

    val willCreateFolder: Boolean by derivedStateOf { resolution != null && selectedEntryCount > 1 }

    val canSaveSelection: Boolean by derivedStateOf {
        !isSaving && selectedItems.isNotEmpty() && (!effectiveSaveAsReference || referenceItemsToSave.isNotEmpty()) && target != null && saveBlockReason == null &&
            !(willCreateFolder && folderNameToSave.isBlank())
    }

    val offlineLeft: Int? get() = shared.account.value.offlineLeft

    val confirmsOffline: Boolean get() = shared.account.value.free

    var contentMissing by mutableStateOf(false)
        private set

    val savePlan: SavePlan? by derivedStateOf {
        if (effectiveSaveAsReference && selectedItems.isNotEmpty()) SavePlan(SaveRoute.INSTANT, referenceItemsToSave.size, skippedCount = missingReferenceCount)
        else if (findShareLink(input) != null && itemsToSave.isNotEmpty() && itemsToSave.all { it.file.gcid != null })
            planSave(items, selectedIndices, selectedEntryCount, remainingBytes, shared.account.value.copy(free = true))
        else planSave(items, selectedIndices, selectedEntryCount, remainingBytes, shared.account.value,
            catalogComplete = resolution?.resource?.truncated == false)
    }

    val isAllSelected: Boolean by derivedStateOf {
        items.isNotEmpty() && selectedIndices.size == items.size
    }

    var tree by mutableStateOf<InstantTree?>(null)
        private set

    val entryCount: Int by derivedStateOf { tree?.rows?.size ?: 0 }
    val selectedEntryCount: Int by derivedStateOf { tree?.rows?.count { it.index in selectedIndices } ?: 0 }
    private val expandedGroups = mutableStateMapOf<String, Boolean>()

    fun isGroupExpanded(group: InstantGroup): Boolean = expandedGroups[group.key] ?: group.defaultExpanded

    fun toggleGroupExpanded(group: InstantGroup) {
        expandedGroups[group.key] = !isGroupExpanded(group)
    }

    fun isUnindexed(row: InstantRow): Boolean = row.indices.any { !items[it].isInstantReady }

    val unindexedEntryCount: Int by derivedStateOf { tree?.rows?.count(::isUnindexed) ?: 0 }

    var showsOnlyUnindexed by mutableStateOf(false)
        private set

    val isUnindexedFilterActive: Boolean by derivedStateOf { showsOnlyUnindexed && unindexedEntryCount > 0 }

    fun toggleOnlyUnindexed() {
        showsOnlyUnindexed = !isUnindexedFilterActive
    }

    val treeRows: List<InstantTreeRow> by derivedStateOf {
        val tree = tree ?: return@derivedStateOf emptyList()
        if (isUnindexedFilterActive) tree.flattenMatching(::isUnindexed) else tree.flatten(::isGroupExpanded)
    }

    fun summaryOf(group: InstantGroup): NameGroupSummary = NameGroupSummary(
        selected = group.rows.count { it.index in selectedIndices },
        total = group.rows.size,
        bytes = group.indices.sumOf { items[it].file.size },
        unindexed = group.rows.count(::isUnindexed),
    )

    val primaryAction: InstantPrimaryAction? by derivedStateOf {
        when {
            instantRetry != null -> InstantPrimaryAction(InstantActionKind.INSTANT_SAVE, retryFileCount, !isSaving && retryFileCount > 0 && saveBlockReason == null)
            saveIssue?.requiresInspection == true -> InstantPrimaryAction(InstantActionKind.INSTANT_SAVE, 0, false)
            resolution != null && contentMissing -> InstantPrimaryAction(
                kind = InstantActionKind.SUBMIT_OFFLINE,
                fileCount = 0,
                enabled = target != null && !isSaving && saveBlockReason == null && shared.account.value.offlineLeft != 0,
            )
            resolution != null -> {
                val plan = savePlan
                InstantPrimaryAction(
                    kind = when {
                        !effectiveSaveAsReference && normalizedMagnet == null && findShareLink(input) == null -> InstantActionKind.SUBMIT_OFFLINE
                        plan?.route == SaveRoute.OFFLINE_PACK -> InstantActionKind.OFFLINE_PACK
                        else -> InstantActionKind.INSTANT_SAVE
                    },
                    fileCount = plan?.fileCount ?: 0,
                    enabled = canSaveSelection && plan?.blocked != true,
                )
            }
            input.isBlank() || needsSharePassCode -> null
            resolvedKey != null && errorMessage == null -> null
            else -> InstantPrimaryAction(
                kind = InstantActionKind.SUBMIT_OFFLINE,
                fileCount = 0,
                enabled = target != null && !isSaving && !isResolving && saveBlockReason == null && shared.account.value.offlineLeft != 0,
            )
        }
    }

    fun performPrimaryAction() {
        when (primaryAction?.kind) {
            InstantActionKind.INSTANT_SAVE, InstantActionKind.OFFLINE_PACK -> saveSelection()
            InstantActionKind.SUBMIT_OFFLINE -> submitOfflineTask()
            null -> Unit
        }
    }

    private var resolveJob: Job? = null
    private var resolvedKey: String? = null

    init {
        scope.launch { saveRecords.attempts.collect { attempts ->
            saveAttempts = attempts
            if (!isSaving) attempts.firstOrNull { it.id == instantAttemptId }?.let { work ->
                work.receipt?.let { receipt ->
                    val hadIssue = saveIssue != null
                    saveIssue = receipt.takeIf { it.requiresInspection || it.failed.isNotEmpty() || it.notAttempted.isNotEmpty() }
                    instantRetry = instantRetry?.copy(receipt = receipt)
                    if (hadIssue && work.isComplete) {
                        val destination = savedContainer ?: work.target
                        val ids = work.files.mapNotNull { receipt.confirmed[it.path] }
                        instantRetry = null
                        instantAttemptId = null
                        savedContainer = null
                        saveParent = null
                        _outcomes.emit(InstantSaveOutcome.InstantSaved(ids, destination))
                    }
                }
            }
        } }
        scope.launch { instantRepo.offlineSubmissions.works.collect { offlineWorks = it } }
        scope.launch { saveTargetContext.actionChanges.collect { targetRulesVersion++ } }
        if (isRoot) {
            scope.launch {
                val remembered = preferences.saveAsReferenceFlow.first()
                if (!shared.saveAsReferenceTouched) shared.saveAsReference.value = remembered
            }
            val previews = previewFolder.session()
            shared.previewLease = previews
            saveScope.coroutineContext[Job]?.invokeOnCompletion {
                previews.close()
            }
            scope.launch { followDriveFolder() }
            scope.launch { refreshRemainingBytes() }
        }
        scope.launch { preferences.bundleSubtitlesFlow.collect { saveAttachedSubtitles = it } }
        refreshShare()
        if (initialMagnet.isNotBlank() && !startBatchIfMany()) {
            scheduleResolve()
        }
    }

    fun updateInput(value: String) {
        if (!scope.isActive || !canEdit || batch?.isSaving == true) return
        if (input != value) { offlineWorkId = null; instantAttemptId = null; saveIssue = null; savedContainer = null; saveParent = null; shared.targetHeld.value = false }
        input = value
        refreshShare()
        scheduleResolve()
        startBatchIfMany()
    }

    fun updateSharePassCode(value: String) { if (canEdit) sharePassCode = value.trim() }
    fun submitSharePassCode() { if (canEdit) { resolvedKey = null; scheduleResolve(false) } }
    private fun refreshShare() {
        sharePassCode = findSharePassCode(input).orEmpty()
        needsSharePassCode = false
    }

    private suspend fun resolveShare(url: String): Result<MagnetResolutionResult?> = runSuspendCatching {
        val id = requireNotNull(shareIdFromUrl(url))
        val info = driveRepo.shareInfo(id, sharePassCode).getOrThrow()
        val root = info.files.singleOrNull()?.takeIf { it.isFolder }
        val items = mutableListOf<InstantFileItem>()
        val visited = mutableSetOf<String>()
        var truncated = info.nextPageToken.isNotEmpty()
        var frontier = info.files.map { it to if (it == root) "" else it.name }
        while (frontier.isNotEmpty()) {
            val foldersToExpand = mutableListOf<Pair<io.github.nihildigit.pikpak.FileStat, String>>()
            for ((file, path) in frontier) {
                if (file.isFolder) {
                    if (!visited.add(file.id)) continue
                    if (visited.size > 64) {
                        truncated = true
                        continue
                    }
                    foldersToExpand += file to path
                } else {
                    val resolved = io.github.nihildigit.pikpak.ResolvedFile(path, file.sizeBytes, file.hash.ifBlank { null })
                    val meta = dev.piko.shared.data.VaultMediaMeta.fromFileStat(file).takeUnless { it.isEmpty }
                    items += InstantFileItem(resolved, resolved.gcid != null, meta = meta)
                }
            }
            if (foldersToExpand.isEmpty()) break
            val nextFrontier = mutableListOf<Pair<io.github.nihildigit.pikpak.FileStat, String>>()
            for (chunk in foldersToExpand.chunked(8)) {
                val listings = coroutineScope {
                    chunk.map { (folder, path) ->
                        async {
                            path to driveRepo.shareFolderFull(id, info.passCodeToken, folder.id).getOrThrow()
                        }
                    }.awaitAll()
                }
                for ((parentPath, page) in listings) {
                    val (children, subTruncated) = page
                    if (subTruncated) truncated = true
                    for (child in children) {
                        nextFrontier += child to if (parentPath.isEmpty()) child.name else "$parentPath/${child.name}"
                    }
                }
            }
            frontier = nextFrontier
        }
        val files = items.map { it.file }
        MagnetResolutionResult(io.github.nihildigit.pikpak.MagnetResource(root?.name ?: info.title, files, truncated),
            items, items.count { it.isInstantReady }, items.size)
    }

    private fun startBatchIfMany(): Boolean {
        if (!isRoot) return false
        val links = extractLinks(input)
        if (links.size < 2) return false
        batch?.dispose()
        batch = InstantBatchState(
            links = links,
            newRow = ::newBatchRow,
            driveRepo = driveRepo,
            shared = shared,
            scope = scope,
            saveScope = saveScope,
            emitOutcome = { _outcomes.emit(it) },
            emitMessage = { _messages.emit(it) },
            onEmpty = ::leaveBatch,
            onSaveAsReferenceChanged = { value -> scope.launch { preferences.setSaveAsReference(value) } },
        )
        return true
    }

    private fun newBatchRow(link: PastedLink, rowScope: CoroutineScope) = InstantSheetState(
        instantRepo, driveRepo, preferences, previewFolder, packTracker, saveRecords, rowScope, saveScope, link.uri, shared, isRoot = false, saveTargetContext,
    ).also { it.onSaveAccepted = ::acceptSave }

    private fun leaveBatch() {
        batch = null
        input = ""
    }

    fun retryResolve() {
        resolvedKey = null
        scheduleResolve(debounce = false)
    }

    fun toggleItem(index: Int) {
        setItemSelected(index, index !in selectedIndices)
    }

    fun setItemSelected(index: Int, selected: Boolean) {
        setItemsSelected(listOf(index), selected)
    }

    fun setSourceSelected(indices: Set<Int>, selected: Boolean) {
        if (!canEdit) return
        val batchRows = batch?.rows
        if (batchRows != null) {
            for ((rowIndex, group) in indices.groupBy { it / BATCH_INDEX_STRIDE }) {
                batchRows.getOrNull(rowIndex)?.state?.setSourceSelected(group.mapTo(HashSet()) { it % BATCH_INDEX_STRIDE }, selected)
            }
            return
        }
        resetEditableAttempt()
        val valid = indices.filterTo(HashSet()) { it in items.indices }
        val current = sourceSelectedIndices
        sourceSelectionEdited = true
        selectedIndices = if (selected) current + valid else current - valid
        selectionPicked = true
    }

    fun setGroupSelected(group: InstantGroup, selected: Boolean) {
        setItemsSelected(group.indices, selected)
    }

    private fun setItemsSelected(indices: Collection<Int>, selected: Boolean) {
        if (!canEdit) return
        resetEditableAttempt()
        val affected = indices.flatMap { index -> tree?.rowOf(index)?.indices ?: listOf(index) }.toSet()
        selectedIndices = if (selected) selectedIndices + affected else selectedIndices - affected
        selectionPicked = true
    }

    fun deselectUnindexed() {
        setSourceSelected(items.indices.filter { items[it].file.gcid == null }.toSet(), false)
    }

    fun toggleSelectAll() {
        if (!canEdit) return
        resetEditableAttempt()
        selectedIndices = if (isAllSelected) emptySet() else items.indices.toSet()
        selectionPicked = true
    }

    fun updateFolderName(value: String) {
        if (!canEdit) return
        resetEditableAttempt()
        folderName = value
        folderNameEdited = true
    }

    fun changeTarget(breadcrumb: PikoPathBreadcrumb) {
        if (!canEdit) return
        resetEditableAttempt()
        shared.targetChosen = true
        target = breadcrumb
        targetNotice = null
    }

    fun canPreview(index: Int): Boolean {
        val item = items.getOrNull(index) ?: return false
        return item.isInstantReady && item.file.name.fileCategory() == FileCategory.VIDEO
    }

    fun preview(index: Int) {
        val item = items.getOrNull(index) ?: return
        previewFile(item.file, index)
    }

    suspend fun leaseForArchiveBrowse(file: io.github.nihildigit.pikpak.ResolvedFile): Result<String> {
        val gcid = file.gcid ?: return Result.failure(IllegalStateException(tr("未收录的压缩包需先通过离线下载保存至网盘", "Save this unindexed archive via cloud download before opening", "未収録の圧縮ファイルは、先にクラウドダウンロードでドライブに保存してください", "미등록 압축 파일은 먼저 클라우드 다운로드로 드라이브에 저장해 주세요", "未收錄的壓縮檔需先透過離線下載儲存至雲端硬碟")))
        previewedIds[gcid]?.let { return Result.success(it) }
        val lease = shared.previewLease ?: return Result.failure(IllegalStateException(tr("预览会话未就绪", "Preview session is not ready", "プレビューの準備ができていません", "미리 보기 세션이 준비되지 않았습니다", "預覽工作階段尚未就緒")))
        return lease.put(file).onSuccess { fileId -> previewedIds[gcid] = fileId }
    }

    private fun previewFile(file: io.github.nihildigit.pikpak.ResolvedFile, index: Int) {
        val gcid = file.gcid ?: return
        previewedIds[gcid]?.let { fileId ->
            _previewRequests.tryEmit(InstantPreviewRequest(fileId, file.name))
            return
        }
        if (previewingIndex != null) return
        previewingIndex = index
        scope.launch {
            try {
                requireNotNull(shared.previewLease).put(file)
                    .onSuccess { fileId ->
                        previewedIds[gcid] = fileId
                        _previewRequests.emit(InstantPreviewRequest(fileId, file.name))
                    }
                    .logFailure(TAG, tr("预览失败", "Preview failed", "プレビューに失敗しました", "미리 보기 실패", "預覽失敗"))
                    .onFailure { _messages.emit(dev.piko.shared.notice.PikoErrorMapper.failure(tr("预览", "Preview", "プレビュー", "미리 보기", "預覽"), it)) }
            } finally {
                previewingIndex = null
            }
        }
    }

    fun saveSelection() {
        if (effectiveSaveAsReference && itemsToSave.isNotEmpty() && referenceItemsToSave.isEmpty()) { errorMessage = tr("所选项目均缺少引用，无法以引用保存", "Selected items lack references and cannot be saved as references", "選択した項目に参照情報がないため、参照として保存できません", "선택한 항목에 참조가 없어 참조로 저장할 수 없습니다", "所選項目均缺少引用，無法以引用儲存"); return }
        if (!effectiveSaveAsReference && normalizedMagnet == null && findShareLink(input) == null) { submitOfflineTask(); return }
        if (!scope.isActive || isSaving) return
        saveBlockReason?.let { errorMessage = it; return }
        if (instantRetry != null) {
            if (retryFileCount == 0) { errorMessage = tr("请先在任务页核对本次保存结果，避免重复提交", "Check this save result in Tasks before submitting again", "再送信する前にタスク画面で今回の保存結果を確認してください", "다시 제출하기 전에 작업 페이지에서 이번 저장 결과를 확인해 주세요", "請先至任務頁核對本次儲存結果，避免重複提交"); return }
            isSaving = true
            saveScope.launch {
                try {
                    val target = requireNotNull(instantRetry).target
                    resumeInstant().onSuccess { _outcomes.emit(InstantSaveOutcome.InstantSaved(it, target)) }
                } finally { isSaving = false }
            }
            return
        }
        if (saveIssue?.requiresInspection == true) return
        val plan = savePlan ?: return
        if (isSaving || plan.blocked) return
        val toSave = if (effectiveSaveAsReference) referenceItemsToSave else itemsToSave
        isSaving = true
        saveScope.launch {
            try {
                val targetBread = target ?: resolveTarget()
                when (plan.route) {
                    SaveRoute.INSTANT -> if (willCreateFolder) {
                        saveIntoNewFolder(targetBread, toSave).onSuccess { _outcomes.emit(it) }
                    } else {
                        saveInstantOrOffline(targetBread, toSave).onSuccess { ids ->
                            _outcomes.emit(
                                if (ids != null) InstantSaveOutcome.InstantSaved(ids, targetBread) else InstantSaveOutcome.OfflineTaskCreated(targetBread),
                            )
                        }
                    }
                    SaveRoute.OFFLINE_PACK -> {
                        val remaining = refreshRemainingBytes()
                        if (remaining != null && plan.packBytes > remaining) {
                            PikoLog.i(TAG, "空间不足，不提交整包离线：需要 ${plan.packBytes} 字节，剩余 $remaining 字节")
                            return@launch
                        }
                        if (shared.account.value.offlineLeft == 0) {
                            PikoLog.i(TAG, tr("今日离线次数已用完，不提交整包离线", "Daily cloud download limit reached; batch not submitted", "本日のクラウドダウンロード回数を使い切ったため、一括ダウンロードは未送信です", "오늘의 클라우드 다운로드 횟수를 모두 사용하여 전체 다운로드를 제출하지 않았습니다", "今日離線下載次數已用完，不提交整包離線下載"))
                            return@launch
                        }
                        packSave(targetBread, toSave)
                            .onSuccess { _outcomes.emit(InstantSaveOutcome.OfflineTaskCreated(targetBread)) }
                    }
                }
            } finally {
                isSaving = false
            }
        }
    }

    fun saveSelectionInstantly() {
        if (!scope.isActive) return
        saveBlockReason?.let { errorMessage = it; return }
        if (instantRetry != null) { saveSelection(); return }
        if (saveIssue?.requiresInspection == true) return
        if (isSaving || savePlan?.fallback == null) return
        val toSave = itemsToSave.filter { it.isInstantReady }
        isSaving = true
        saveScope.launch {
            try {
                val targetBread = target ?: resolveTarget()
                val remaining = refreshRemainingBytes()
                if (remaining != null && toSave.sumOf { it.file.size } > remaining) {
                    PikoLog.i(TAG, "空间不足，不秒传：需要 ${toSave.sumOf { it.file.size }} 字节，剩余 $remaining 字节")
                    errorMessage = tr("网盘空间不足，无法保存所选文件", "Not enough cloud storage to save selected files", "選択したファイルを保存する空き容量がありません", "선택한 파일을 저장할 클라우드 공간이 부족합니다", "雲端硬碟空間不足，無法儲存所選檔案")
                    return@launch
                }
                val saved = saveIntoNewFolder(targetBread, toSave)
                saved.onSuccess { _outcomes.emit(it) }
            } finally {
                isSaving = false
            }
        }
    }

    private suspend fun saveIntoNewFolder(
        target: PikoPathBreadcrumb,
        toSave: List<InstantFileItem>,
    ): Result<InstantSaveOutcome.InstantSaved> {
        val naming = SaveNaming(toSave)
        val name = driveFolderName(naming.folderNameToSave)
        if (!startInstantAttempt(target, toSave, name, containerName = name, names = naming.fileNames, keepStructure = true)) return Result.failure(IllegalStateException(errorMessage))
        saveParent = target
        val folderId = driveRepo.createFolder(target.id, name).getOrElse { err ->
            PikoLog.w(TAG, "新建保存目录失败", err)
            errorMessage = dev.piko.shared.notice.PikoErrorMapper.failure(tr("新建文件夹", "Create folder", "フォルダーを作成", "폴더 만들기", "新增資料夾"), err)
            val definitelyRefused = err is PikPakException && err.httpStatus in setOf(400, 401, 403, 404, 409, 422)
            val receipt = InstantSaveReceipt(requireNotNull(saveAccount), target.id, notAttempted = toSave.mapTo(HashSet()) { it.file.path },
                unconfirmedFolder = name.takeUnless { definitelyRefused })
            errorMessage = receipt.message
            saveIssue = receipt
            instantAttemptId?.let { saveRecords.finishAttempt(it, receipt) }
            driveRepo.applyChange(dev.piko.shared.data.DriveChange.ContentsChanged(setOf(target.id)))
            return Result.failure(InstantSaveIncomplete(receipt, err))
        }
        val folder = PikoPathBreadcrumb(folderId, name)
        savedContainer = folder
        instantAttemptId?.let { saveRecords.observeContainer(it, requireNotNull(saveAccount), folderId) }
        return instantSave(folder, toSave, naming, keepStructure = true, targetIsFreshFolder = true).map { ids ->
            saveRecords.add(name, ids.size, toSave.sumOf { it.file.size }, target.name, locateId = folderId,
                id = instantAttemptId, expectedAccount = saveAccount)
            instantAttemptId = null
            savedContainer = null
            saveParent = null
            InstantSaveOutcome.InstantSaved(ids, folder)
        }
    }

    fun submitOfflineTask() {
        if (!scope.isActive) return
        if (instantRetry != null || saveIssue?.requiresInspection == true || saveBlockReason != null) return
        if (isSaving || input.isBlank()) return
        isSaving = true
        saveScope.launch {
            try {
                val targetBread = target ?: resolveTarget()
                submitWhole(targetBread)
                    .onSuccess { _outcomes.emit(InstantSaveOutcome.OfflineTaskCreated(targetBread)) }
            } finally {
                isSaving = false
            }
        }
    }

    internal suspend fun submitForBatch(target: PikoPathBreadcrumb): Result<List<String>?> {
        val plan = savePlan
        val toSave = if (effectiveSaveAsReference) referenceItemsToSave else itemsToSave
        isSaving = true
        try {
            return when {
                instantRetry != null -> resumeInstant().map { it }
                saveBlockReason != null -> Result.failure(IllegalStateException(saveBlockReason))
                saveIssue?.requiresInspection == true -> Result.failure(IllegalStateException(tr("本次保存结果待确认，请先在任务页核对", "This save result is unconfirmed. Check Tasks first", "今回の保存結果は未確認です。先にタスク画面で確認してください", "이번 저장 결과가 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "本次儲存結果待確認，請先在任務頁核對")))
                effectiveSaveAsReference && toSave.isEmpty() -> Result.failure(IllegalStateException(tr("所选项目均缺少引用，无法以引用保存", "Selected items lack references and cannot be saved as references", "選択した項目に参照情報がないため、参照として保存できません", "선택한 항목에 참조가 없어 참조로 저장할 수 없습니다", "所選項目均缺少引用，無法以引用儲存")))
                resolution == null || (!effectiveSaveAsReference && normalizedMagnet == null && findShareLink(input) == null) -> submitWhole(target).map { null }
                plan == null -> Result.failure(IllegalStateException(tr("未勾选文件", "No files selected", "ファイルが選択されていません", "선택한 파일이 없습니다", "未勾選檔案")))
                plan.route == SaveRoute.INSTANT && willCreateFolder -> saveIntoNewFolder(target, toSave).map { it.createdIds }
                plan.route == SaveRoute.INSTANT -> saveInstantOrOffline(target, toSave)
                else -> packSave(target, toSave).map { null }
            }
        } finally {
            isSaving = false
        }
    }

    private suspend fun submitWhole(target: PikoPathBreadcrumb): Result<Unit> {
        if (!canReceiveAt(target)) return Result.failure(IllegalStateException(errorMessage))
        submittingOfflineAt = target
        acceptSave()
        return try {
            instantRepo.enqueueOfflineTask(submittedUrl(), target.id, target.name, folderNameToSave.ifBlank { tr("离线下载", "Cloud download", "クラウドダウンロード", "클라우드 다운로드", "離線下載") }, saveAccount)
                .map { }
                .onSuccess { instantAttemptId?.let(saveRecords::forgetAttempt); instantAttemptId = null; saveIssue = null }
                .reportSaveFailure()
        } finally {
            submittingOfflineAt = null
        }
    }

    private fun scheduleResolve(debounce: Boolean = true) {
        val magnet = extractLinks(input).singleOrNull()?.uri
        if (magnet == resolvedKey) return
        resolvedKey = magnet
        resolveJob?.cancel()
        resolution = null
        tree = null
        canonical = null
        selectedIndices = emptySet()
        sourceSelectionEdited = false
        selectionPicked = false
        errorMessage = null
        isUnindexed = false
        needsSharePassCode = false
        contentMissing = false
        if (magnet == null) return
        resolveJob = scope.launch {
            if (debounce) delay(AUTO_RESOLVE_DEBOUNCE_MS)
            isResolving = true
            try {
                shared.resolvePermits.withPermit {
                    when {
                        findShareLink(magnet) != null -> resolveShare(magnet)
                        magnet.startsWith("ed2k://", true) -> Result.success(dev.piko.shared.data.ed2kResource(magnet))
                        magnet.startsWith("http", true) -> instantRepo.probeHttpResource(magnet)
                        normalizeMagnet(magnet) != null -> instantRepo.resolve(magnet)
                        else -> Result.success(null)
                    }
                }
                    .onSuccess { data -> applyResolution(data) }
                    .onFailure { err ->
                        needsSharePassCode = (err as? io.github.nihildigit.pikpak.ShareUnavailableException)?.needsPassCode == true
                        if (!needsSharePassCode) {
                            PikoLog.w(TAG, tr("解析链接失败", "Failed to resolve link", "リンクを解析できませんでした", "링크를 분석하지 못했습니다", "解析連結失敗"), err)
                            errorMessage = dev.piko.shared.notice.PikoErrorMapper.failure(tr("解析", "Resolve", "解析", "분석", "解析"), err)
                        }
                    }
            } finally {
                isResolving = false
                isAnalyzing = false
            }
        }
    }

    private suspend fun applyResolution(data: MagnetResolutionResult?) {
        if (data == null) {
            isUnindexed = true
            errorMessage = tr("云端未收录，可使用离线下载", "Not indexed in the cloud; cloud download available", "クラウドに未収録です。クラウドダウンロードを利用できます", "클라우드에 미등록된 항목입니다. 클라우드 다운로드를 이용할 수 있습니다", "雲端未收錄，可使用離線下載")
            return
        }
        isAnalyzing = true
        val inputs = data.items.map { MediaFileInput(it.file.path, it.file.size) }
        val parse = preferences.nameParsingFlow.first()
        val built = withContext(Dispatchers.Default) {
            if (parse) buildInstantTree(inputs, data.resource.name) else buildRawInstantTree(inputs, data.resource.name)
        }
        val names = if (parse) withContext(Dispatchers.Default) { canonicalNamesOf(data) } else null
        tree = built
        canonical = names
        folderNameEdited = false
        resolution = data
        expandedGroups.clear()
        showsOnlyUnindexed = false
        folderName = built.folderName
        selectedIndices = if (data.items.size == 1 && normalizedMagnet == null) setOf(0) else built.defaultSelection
        selectionPicked = false
        if (isRoot) scope.launch { refreshRemainingBytes() }
    }

    private suspend fun refreshRemainingBytes(): Long? {
        driveRepo.getQuota().onSuccess { response ->
            remainingBytes = response.quota.takeIf { it.limitBytes > 0 }?.remainingBytes
            shared.updateAccount(driveRepo.isFreeAccount(), response)
        }.logFailure(TAG, "查询网盘余量失败，沿用上次的 $remainingBytes 字节")
        return remainingBytes
    }
    private fun referenceSource(): String? = findShareLink(input)?.let { it + if (sharePassCode.isNotBlank()) "?pwd=$sharePassCode" else "" } ?: resolvedKey

    private fun submittedUrl(): String = normalizedMagnet ?: extractLinks(input).singleOrNull()?.uri ?: input.trim()

    private suspend fun followDriveFolder() {
        combine(
            saveTargetContext.folders,
            shared.targetHeld,
            snapshotFlow { isSaving || hasPendingOfflineSubmission || batch?.rows?.any { it.state.hasPendingOfflineSubmission } == true },
        ) { folder, held, busy -> folder to (held || busy) }
            .distinctUntilChanged()
            .collectLatest { (folder, held) ->
                if (held || shared.targetChosen) return@collectLatest
                val resolved = targetFor(folder)
                if (!shared.targetChosen && !shared.targetHeld.value) target = resolved
            }
    }

    private suspend fun resolveTarget(): PikoPathBreadcrumb = targetFor(saveTargetContext.currentFolder())

    private suspend fun targetFor(folder: PikoPathBreadcrumb): PikoPathBreadcrumb {
        if (folder.id.isEmpty() || !driveRepo.isFolderGone(folder.id)) {
            targetNotice = null
            return folder
        }
        PikoLog.i(TAG, "保存目标 ${folder.id} 已不存在，改存 My Packs")
        targetNotice = tr("原目标文件夹已不存在，已改为保存至 My Packs", "Destination folder no longer exists; saving to My Packs instead", "保存先フォルダーが存在しないため、My Packsに保存します", "대상 폴더가 존재하지 않아 My Packs에 저장합니다", "原目標資料夾已不存在，已改為儲存至 My Packs")
        return driveRepo.getOrCreateMyPacksFolder().logFailure(TAG, "取 My Packs 失败，改存根目录")
            .getOrDefault(PikoPathBreadcrumb("", "My Packs"))
    }

    private suspend fun instantSave(
        target: PikoPathBreadcrumb,
        toSave: List<InstantFileItem>,
        naming: SaveNaming,
        keepStructure: Boolean,
        targetIsFreshFolder: Boolean = false,
    ): Result<List<String>> = rawInstantSave(target, toSave, naming, keepStructure, targetIsFreshFolder).reportSaveFailure()

    private suspend fun rawInstantSave(
        target: PikoPathBreadcrumb,
        toSave: List<InstantFileItem>,
        naming: SaveNaming,
        keepStructure: Boolean,
        targetIsFreshFolder: Boolean = false,
    ): Result<List<String>> {
        if (!startInstantAttempt(target, toSave, naming.folderNameToSave, names = naming.fileNames, keepStructure = keepStructure)) return Result.failure(IllegalStateException(errorMessage))
        return executeInstant(
            plan = InstantRetryPlan(target, toSave.toList(), naming.fileNames.toMap(), keepStructure, InstantSaveReceipt(requireNotNull(saveAccount), target.id)),
            submitted = toSave,
            targetIsFreshFolder = targetIsFreshFolder,
        )
    }

    private fun startInstantAttempt(target: PikoPathBreadcrumb, files: List<InstantFileItem>, title: String,
        containerName: String? = savedContainer?.name, names: Map<String, String> = emptyMap(), keepStructure: Boolean = false): Boolean {
        if (!canReceiveAt(target)) return false
        val account = saveAccount?.takeIf { it == driveRepo.currentAccount } ?: return false
        if (saveRecords.pendingConflict(account, saveParent?.id ?: target.id, files.map { it.file }, excluding = instantAttemptId,
                containerName = containerName, names = names, keepStructure = keepStructure)) {
            errorMessage = tr("目标位置有进行中或待确认的保存任务，请先在任务页处理", "A save to this destination is running or unconfirmed. Check Tasks first", "同じ保存先への保存が進行中または未確認です。先にタスク画面で確認してください", "같은 대상에 저장이 진행 중이거나 미확인입니다. 먼저 작업 페이지에서 확인해 주세요", "目標位置有進行中或待確認的儲存任務，請先至任務頁處理")
            return false
        }
        instantAttemptId = saveRecords.beginAttempt(account, title, target, files.map { it.file }, instantAttemptId,
            containerName = containerName, names = names, keepStructure = keepStructure, reference = effectiveSaveAsReference)
        if (instantAttemptId != null) { shared.targetHeld.value = true; acceptSave() }
        return instantAttemptId != null
    }

    private suspend fun executeInstant(
        plan: InstantRetryPlan,
        submitted: List<InstantFileItem>,
        targetIsFreshFolder: Boolean = false,
    ): Result<List<String>> {
        val execution = InstantSaveExecutor(instantRepo, driveRepo, requireNotNull(saveAccount)).execute(
            plan, submitted, targetIsFreshFolder, effectiveSaveAsReference, previewedIds.toMap(),
            referenceSource(), normalizedMagnet ?: findShareLink(input), saveParent?.id,
        )
        val result = execution.response
        val receipt = execution.receipt
        if (!effectiveSaveAsReference) submitted.filter { it.file.path in execution.confirmedPaths }
            .forEach { item -> item.file.gcid?.let(previewedIds::remove) }
        instantAttemptId?.let { saveRecords.finishAttempt(it, receipt) }
        val complete = receipt.confirmed.keys.containsAll(plan.files.filter { it.file.gcid != null }.map { it.file.path }) && !receipt.requiresInspection
        return if (complete) {
            saveIssue = null
            instantRetry = null
            Result.success(plan.files.filter { it.file.gcid != null }.map { receipt.confirmed.getValue(it.file.path) })
        } else {
            saveIssue = receipt
            val missing = result.exceptionOrNull() is InstantContentUnavailableException && plan.files.size == 1
            instantRetry = if (missing) null else plan.copy(receipt = receipt)
            if (missing) result else Result.failure(InstantSaveIncomplete(receipt, result.exceptionOrNull() ?: IllegalStateException(tr("仍有待确认的保存结果", "Some save results are unconfirmed", "未確認の保存結果があります", "아직 미확인 저장 결과가 있습니다", "仍有待確認的儲存結果"))))
        }
    }

    internal fun retrySubmittedAttempt(): Boolean {
        if (!saveScope.isActive || isSaving || retryFileCount == 0 || saveAccount != driveRepo.currentAccount) return false
        isSaving = true
        saveScope.launch {
            try {
                val destination = requireNotNull(instantRetry).target
                resumeInstant().onSuccess { _outcomes.emit(InstantSaveOutcome.InstantSaved(it, destination)) }
            } finally { isSaving = false }
        }
        return true
    }

    private suspend fun resumeInstant(): Result<List<String>> {
        val plan = instantRetry ?: return Result.failure(IllegalStateException(tr("没有可重试的保存", "No saves to retry", "再試行できる保存がありません", "다시 시도할 저장 작업이 없습니다", "沒有可重試的儲存")))
        if (!canReceiveAt(plan.target)) return Result.failure(IllegalStateException(errorMessage))
        if (plan.receipt.unconfirmedFolder != null) return Result.failure(IllegalStateException(tr("文件夹创建结果待确认，暂无法重复创建", "Folder creation is unconfirmed; cannot recreate yet", "フォルダーの作成結果が未確認のため、再作成できません", "폴더 생성 결과가 미확인 상태이므로 다시 만들 수 없습니다", "資料夾建立結果待確認，暫無法重複建立")))
        val remaining = plan.files.filter { it.file.path in (plan.receipt.failed + plan.receipt.notAttempted) }
        if (remaining.isEmpty()) return Result.failure(IllegalStateException(tr("无已确认失败的文件，请先在任务页核对结果", "No confirmed failed files. Check results in Tasks first", "未保存と確認されたファイルがありません。先にタスク画面で結果を確認してください", "저장 실패로 확인된 파일이 없습니다. 먼저 작업 페이지에서 결과를 확인해 주세요", "無已確認失敗的檔案，請先至任務頁核對結果")))
        instantAttemptId?.let { id -> saveRecords.beginAttempt(requireNotNull(saveAccount), plan.target.name, plan.target, plan.files.map { it.file }, id) }
        return executeInstant(plan, remaining).onSuccess { ids ->
            val container = savedContainer
            if (container == null) recordSingleEntry(plan.target, plan.files, ids) else {
                saveRecords.add(container.name, ids.size, plan.files.sumOf { it.file.size }, saveParent?.name ?: plan.target.name,
                    locateId = container.id, id = instantAttemptId, expectedAccount = saveAccount)
                instantAttemptId = null
            }
            savedContainer = null
            saveParent = null
        }.reportSaveFailure()
    }

    private suspend fun saveInstantOrOffline(
        target: PikoPathBreadcrumb,
        toSave: List<InstantFileItem>,
    ): Result<List<String>?> {
        val naming = SaveNaming(toSave)
        val instant = rawInstantSave(target, toSave, naming, keepStructure = false)
        val missing = instant.exceptionOrNull() as? InstantContentUnavailableException
        if (items.size == 1 && missing != null) {
            if (shared.account.value.free) {
                PikoLog.i(TAG, "云端没有这个文件的内容，等用户确认离线")
                contentMissing = true
                errorMessage = tr("云端未收录此文件，请使用离线下载", "File not indexed in the cloud. Use cloud download", "クラウドに未収録のファイルです。クラウドダウンロードを利用してください", "클라우드에 등록되지 않은 파일입니다. 클라우드 다운로드를 이용해 주세요", "雲端未收錄此檔案，請使用離線下載")
                return Result.failure(missing)
            }
            PikoLog.i(TAG, "云端没有这个文件的内容，改交离线任务")
            return submitWhole(target).map { null }
        }
        return instant.onSuccess { ids -> recordSingleEntry(target, toSave, ids) }.reportSaveFailure()
    }

    private fun recordSingleEntry(target: PikoPathBreadcrumb, toSave: List<InstantFileItem>, ids: List<String>) {
        val saved = toSave.filter { it.file.gcid != null }.zip(ids)
        val (main, mainId) = saved.maxByOrNull { (item, _) -> item.file.size } ?: return
        saveRecords.add(main.file.name, saved.size, saved.sumOf { (item, _) -> item.file.size }, target.name, locateId = mainId,
            id = instantAttemptId, expectedAccount = saveAccount)
        instantAttemptId = null
    }

    private fun <T> Result<T>.reportSaveFailure(): Result<T> {
        val offline = exceptionOrNull() as? OfflineSubmitException
        if (offline != null) {
            offlineWorkId = offline.workId
            errorMessage = offline.message
            return this
        }
        val incomplete = exceptionOrNull() as? InstantSaveIncomplete
        return if (incomplete == null) reportFailure(TAG, tr("保存", "Save", "保存", "저장", "儲存")) { errorMessage = it } else {
            errorMessage = incomplete.receipt.message
            logFailure(TAG, tr("保存未全部确认，保留逐文件回执", "Some save results are unconfirmed; file receipts retained", "一部の保存結果が未確認です。ファイルごとの結果を保持しています", "일부 저장 결과가 확인되지 않아 파일별 결과를 유지합니다", "儲存尚未全部確認，保留個別檔案回執"))
        }
    }

    private suspend fun packSave(target: PikoPathBreadcrumb, toSave: List<InstantFileItem>): Result<Unit> {
        if (resolution?.resource?.truncated != false) {
            errorMessage = tr("文件清单不完整，无法提交自动裁剪的整包离线下载", "Incomplete file list; cannot submit a full cloud download with automatic trimming", "ファイル一覧が不完全なため、自動整理付きの一括クラウドダウンロードを送信できません", "파일 목록이 불완전하여 자동 정리 전체 클라우드 다운로드를 제출할 수 없습니다", "檔案清單不完整，無法提交自動裁剪的整包離線下載")
            return Result.failure(IllegalStateException(errorMessage))
        }
        if (!canReceiveAt(target)) return Result.failure(IllegalStateException(errorMessage))
        val allItems = items
        val packBytes = allItems.sumOf { it.file.size }
        submittingOfflineAt = target
        acceptSave()
        return try {
            packTracker.submit(
                url = submittedUrl(),
                targetId = target.id,
                folderName = driveFolderName(folderNameToSave),
                keep = toSave.map { it.file.path }.toSet(),
                totalFiles = allItems.size,
                totalBytes = packBytes,
                keptBytes = toSave.sumOf { it.file.size },
            )
                .map { }
                .reportSaveFailure()
        } finally {
            submittingOfflineAt = null
        }
    }

    private fun canReceiveAt(target: PikoPathBreadcrumb): Boolean {
        if (saveAccount == null || driveRepo.currentAccount != saveAccount || !saveScope.isActive) {
            errorMessage = tr("当前保存会话已结束", "This save session has ended", "保存セッションは終了しました", "현재 저장 세션이 종료되었습니다", "目前的儲存工作階段已結束")
            return false
        }
        val blocked = saveTargetContext.availability(target.id) as? dev.piko.shared.presentation.ActionAvailability.Blocked
        if (blocked != null) { errorMessage = blocked.reason; return false }
        return true
    }

    companion object {
        private const val TAG = "Instant"
        private const val AUTO_RESOLVE_DEBOUNCE_MS = 350L
        private const val BATCH_INDEX_STRIDE = 1_000_000

        private fun canonicalNamesOf(data: MagnetResolutionResult): CanonicalNames? {
            val files = data.items.map { it.file }
            val inputs = files.map { AvNamingItem(it.name, group = it.path.substringBeforeLast('/', "")) }
            val titled = canonicalAvNames(inputs)
            if (titled.indices.none { titled[it] != files[it].name }) return null
            val bare = canonicalAvNames(inputs) { _, _ -> false }
            val codes = titled.map { parseMediaName(it).av?.code }
            val videos = files.map { file -> parseMediaName(file.name).takeIf { it.fileKind == FileKind.VIDEO }?.av }
            return CanonicalNames(titled, bare, codes, videos)
        }

        private fun fileNameIn(names: CanonicalNames, index: Int, folderCode: String?, original: String): String {
            val inNamedFolder = names.codes.getOrNull(index)?.let { it == folderCode } == true
            return (if (inNamedFolder) names.bare else names.titled).getOrNull(index) ?: original
        }

        fun normalizeMagnet(raw: String): String? = extractLinks(raw).singleOrNull()?.takeIf { it.isMagnet }?.uri

        fun findShareLink(text: String): String? {
            val webId = SHARE_LINK.find(text)?.groupValues?.get(1)
            val appId = SHARE_APP_LINK.findAll(text).firstNotNullOfOrNull { link ->
                SHARE_ID.find(link.value)?.groupValues?.get(1)
            }
            return (webId ?: appId)?.let { "https://mypikpak.com/s/$it" }
        }

        fun findSharePassCode(text: String): String? =
            SHARE_QUERY_PASS_CODE.find(text)?.groupValues?.get(1)
                ?: SHARE_PASS_CODE.find(text)?.groupValues?.get(1)

        private val SHARE_LINK = Regex("""https?://(?:[A-Za-z0-9-]+\.)*(?:mypikpak\.com|mypikpak\.net|pikpak\.me|pikpakdrive\.com|pickpackapp\.com)/s/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        private val SHARE_APP_LINK = Regex("""pikpakapp://[^\s/?#]+/drive/share_watch\?[^\s#]*""", RegexOption.IGNORE_CASE)
        private val SHARE_ID = Regex("""[?&]share_id=([A-Za-z0-9_-]+)(?=[&#\s]|$)""", RegexOption.IGNORE_CASE)
        private val SHARE_QUERY_PASS_CODE = Regex("""[?&](?:pwd|passcode|pass_code)=([A-Za-z0-9]{4,10})(?=[&#\s]|$)""", RegexOption.IGNORE_CASE)
        private val SHARE_PASS_CODE = Regex("""(?<![A-Za-z0-9_?&])(?:提取码|密码|访问码|pwd|passcode|pass_code)(?:\s*[:：=]\s*|\s+)([A-Za-z0-9]{4,10})(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)
    }
}
