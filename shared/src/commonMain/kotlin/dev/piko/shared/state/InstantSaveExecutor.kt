package dev.piko.shared.state

import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.InstantSaveIncomplete
import dev.piko.shared.data.InstantSaveReceipt
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import dev.piko.shared.work.DurableInstantItem
import dev.piko.shared.work.DurableInstantSaveJobPayload
import dev.piko.shared.work.WorkJobStatus
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.io.IOException

internal data class InstantRetryPlan(
    val target: PikoPathBreadcrumb,
    val files: List<InstantFileItem>,
    val names: Map<String, String>,
    val keepStructure: Boolean,
    val receipt: InstantSaveReceipt,
)

internal data class InstantExecutionResult(
    val response: Result<List<String>>,
    val receipt: InstantSaveReceipt,
    val confirmedPaths: Set<String>,
)

/** Receipts and side effects are bound to the submitted account and immutable plan. */
internal class InstantSaveExecutor(
    private val instantRepo: InstantMagnetRepository,
    private val driveRepo: PikoDriveRepository,
    private val saveAccount: String,
    private val boundClient: PikPakClient? = instantRepo.clientForAccount(saveAccount),
) {
    suspend fun execute(
        plan: InstantRetryPlan,
        submitted: List<InstantFileItem>,
        targetIsFreshFolder: Boolean,
        reference: Boolean,
        previewed: Map<String, String>,
        referenceSource: String?,
        ledgerSource: String?,
        parentId: String?,
        attemptId: String? = null,
        title: String = "",
        containerName: String? = null,
        alreadyMovedPreviews: Map<String, String> = emptyMap(),
        forceCheckExisting: Boolean = false,
        onProgress: (done: Int, total: Int, status: String?, detail: String?) -> Unit = { _, _, _, _ -> },
    ): InstantExecutionResult {
        val preResolvedFolders = if (!targetIsFreshFolder && plan.keepStructure) {
            val neededDirs = submitted.map { it.file }.filter { it.gcid != null }
                .flatMap { path ->
                    val dir = path.path.substringBeforeLast('/', "")
                    if (dir.isEmpty()) emptyList() else {
                        val parts = dir.split('/')
                        parts.indices.map { i -> parts.subList(0, i + 1).joinToString("/") }
                    }
                }.distinct()
            driveRepo.resolveExistingSubfoldersInTree(plan.target.id, neededDirs) + plan.receipt.folders
        } else {
            plan.receipt.folders
        }
        var resolvedFoldersSnapshot = preResolvedFolders + ("" to plan.target.id)
        val recordNewFolder: (String, String, String) -> Unit = { pId, id, name ->
            if (driveRepo.currentAccount == saveAccount) {
                driveRepo.applyChange(dev.piko.shared.data.DriveChange.Created(pId, id, name, isFolder = true))
            }
        }

        val journal = if (!reference && attemptId != null) driveRepo.workJournal else null
        val pathIndex = plan.files.mapIndexed { idx, item -> item.file.path to idx }.toMap()
        val checkpointLock = PikoLock()
        val checkpointConfirmed = HashMap<Int, String>().apply {
            plan.receipt.confirmed.forEach { (path, fileId) ->
                pathIndex[path]?.let { idx -> put(idx, fileId) }
            }
        }
        val movedPreviewsByPath = HashMap<String, String>(alreadyMovedPreviews)

        fun buildPayloadSnapshot(): DurableInstantSaveJobPayload? {
            val id = attemptId ?: return null
            val snapshotConfirmed = withPikoLock(checkpointLock) { checkpointConfirmed.toMap() }
            return DurableInstantSaveJobPayload(
                attemptId = id,
                account = saveAccount,
                magnet = ledgerSource.orEmpty(),
                title = title.ifBlank { plan.target.name },
                targetFolderId = plan.target.id,
                targetFolderDisplayPath = plan.target.name,
                saveMode = "PHYSICAL",
                items = plan.files.mapIndexed { idx, item ->
                    DurableInstantItem(
                        index = idx,
                        name = item.file.name,
                        path = item.file.path,
                        size = item.file.size,
                        gcid = item.file.gcid.orEmpty(),
                        mimeType = item.file.mimeType.orEmpty(),
                    )
                },
                names = plan.names.mapNotNull { (path, customName) ->
                    pathIndex[path]?.let { idx -> idx to customName }
                }.toMap(),
                reusableIds = previewed.toMap(),
                keepStructure = plan.keepStructure,
                requestedParentFolderId = parentId,
                containerFolderName = containerName,
                containerFolderId = containerName?.let { plan.target.id },
                folders = resolvedFoldersSnapshot,
                confirmed = snapshotConfirmed,
            )
        }

        val checkpointWriteLock = PikoLock()
        fun persistPlanCheckpoint(status: WorkJobStatus): Boolean = withPikoLock(checkpointWriteLock) {
            val targetJournal = journal ?: return@withPikoLock true
            if (!targetJournal.isPersistentFor(saveAccount)) return@withPikoLock false
            val payload = buildPayloadSnapshot() ?: return@withPikoLock true
            targetJournal.saveInstantSaveJob(payload, status = status)
        }

        if (!persistPlanCheckpoint(WorkJobStatus.IN_PROGRESS)) {
            val persistErr = IOException("Failed to persist initial instant-save plan")
            val failReceipt = InstantSaveReceipt(
                saveAccount,
                plan.target.id,
                notAttempted = submitted.mapTo(HashSet()) { it.file.path },
                folders = resolvedFoldersSnapshot,
            )
            return InstantExecutionResult(Result.failure(InstantSaveIncomplete(failReceipt, persistErr)), failReceipt, emptySet())
        }

        var stoppedByTransientOutage = false
        val result = if (reference) {
            val ledger = driveRepo.vaultLedger
            val ready = submitted.filter { it.file.gcid != null }
            if (ledger != null) {
                if (driveRepo.currentAccount != saveAccount) {
                    throw CancellationException(tr("账号会话已结束", "Account session ended", "アカウントのセッションが終了しました", "계정 세션이 종료되었습니다", "帳號工作階段已結束"))
                }
                onProgress(0, ready.size, tr("正在以引用保存", "Saving as references", "参照として保存中", "참조로 저장 중", "正在以引用儲存"), null)
                ledger.importTree(
                    account = saveAccount,
                    anchorFolderId = plan.target.id,
                    containerName = null,
                    items = ready,
                    names = plan.names,
                    keepStructure = plan.keepStructure,
                    source = referenceSource,
                    knownMetaLookup = driveRepo.knownVaultMetaLookupFor(ready),
                ).map { outcome ->
                    resolvedFoldersSnapshot = resolvedFoldersSnapshot +
                        outcome.touchedFolders.associateWith { it } +
                        ("" to plan.target.id)
                    onProgress(ready.size, ready.size, tr("正在以引用保存", "Saving as references", "参照として保存中", "참조로 저장 중", "正在以引用儲存"), null)
                    ready.map { outcome.confirmedByPath.getValue(it.file.path) }
                }
            } else {
                val vault = driveRepo.vaultAccess(saveAccount)
                    ?: throw CancellationException(tr("账号会话已结束", "Account session ended", "アカウントのセッションが終了しました", "계정 세션이 종료되었습니다", "帳號工作階段已結束"))
                instantRepo.saveReferences(
                    items = ready,
                    targetParentId = plan.target.id,
                    keepStructure = plan.keepStructure,
                    names = plan.names,
                    existingFolders = preResolvedFolders,
                    vault = vault,
                    source = referenceSource,
                    targetIsFreshFolder = targetIsFreshFolder,
                    knownMetaLookup = driveRepo.knownVaultMetaLookupFor(ready),
                    onFolderCreated = recordNewFolder,
                    onFoldersResolved = { resolvedFoldersSnapshot = it },
                    onFreshVaultFolders = { freshIds ->
                        if (driveRepo.currentAccount == saveAccount) driveRepo.rememberFullyVaulted(freshIds)
                    },
                    onProgress = onProgress,
                ) { members ->
                    if (driveRepo.currentAccount == saveAccount) driveRepo.vaultTrees.merge(members)
                }
            }
        } else {
            val client = boundClient?.takeIf { it.account == saveAccount }
                ?: return InstantExecutionResult(
                    Result.failure(CancellationException("Account client unavailable")),
                    InstantSaveReceipt(saveAccount, plan.target.id, notAttempted = submitted.mapTo(HashSet()) { it.file.path }),
                    emptySet(),
                )
            var remainingItems = submitted
            var currentFolders = preResolvedFolders
            var currentReuse = previewed
            var checkExisting = forceCheckExisting ||
                (!targetIsFreshFolder && (plan.receipt.failed.isNotEmpty() || plan.receipt.notAttempted.isNotEmpty() || plan.receipt.confirmed.isNotEmpty()))
            val accumulatedIdsByPath = LinkedHashMap<String, String>()
            var lastAttemptResult: Result<List<String>> = Result.success(emptyList())
            var transientAttempt = 0
            while (true) {
                val reservedIds = withPikoLock(checkpointLock) {
                    buildSet {
                        addAll(plan.receipt.confirmed.values)
                        addAll(accumulatedIdsByPath.values)
                        addAll(checkpointConfirmed.values)
                    }
                }
                val snapshotMovedPreviews = withPikoLock(checkpointLock) { movedPreviewsByPath.toMap() }
                lastAttemptResult = instantRepo.instantSave(
                    remainingItems,
                    plan.target.id,
                    reuse = currentReuse,
                    keepStructure = plan.keepStructure,
                    names = plan.names,
                    existingFolders = currentFolders,
                    checkExistingInTarget = checkExisting,
                    reservedFileIds = reservedIds,
                    alreadyMovedPreviews = snapshotMovedPreviews,
                    clientOverride = client,
                    onFolderCreated = recordNewFolder,
                    onFoldersResolved = { folders ->
                        resolvedFoldersSnapshot = folders
                        currentFolders = folders
                        if (journal != null && attemptId != null) {
                            folders.forEach { (rel, fid) ->
                                if (rel.isNotEmpty() && fid.isNotEmpty()) {
                                    journal.recordReceipt(saveAccount, attemptId, "$RECEIPT_DIR_PREFIX$rel", fid)
                                }
                            }
                        }
                    },
                    onPreviewMoved = { path, movedId ->
                        withPikoLock(checkpointLock) { movedPreviewsByPath[path] = movedId }
                        if (journal != null && attemptId != null) {
                            pathIndex[path]?.let { idx ->
                                journal.recordReceipt(saveAccount, attemptId, "$RECEIPT_PREVIEW_PREFIX$idx", movedId)
                            }
                        }
                    },
                    onFileSaved = { path, savedId ->
                        val itemIndex = withPikoLock(checkpointLock) {
                            accumulatedIdsByPath[path] = savedId
                            pathIndex[path]?.also { idx -> checkpointConfirmed[idx] = savedId }
                        }
                        if (journal != null && attemptId != null && itemIndex != null) {
                            journal.recordReceipt(saveAccount, attemptId, "$RECEIPT_FILE_PREFIX$itemIndex", savedId)
                        }
                    },
                    onProgress = onProgress,
                )
                val incomplete = lastAttemptResult.exceptionOrNull() as? InstantSaveIncomplete
                if (incomplete != null) {
                    val newlyConfirmed = ArrayList<Pair<Int, String>>()
                    withPikoLock(checkpointLock) {
                        accumulatedIdsByPath.putAll(incomplete.receipt.confirmed)
                        incomplete.receipt.confirmed.forEach { (path, id) ->
                            pathIndex[path]?.let { idx ->
                                if (checkpointConfirmed[idx] != id) {
                                    checkpointConfirmed[idx] = id
                                    newlyConfirmed += idx to id
                                }
                            }
                        }
                    }
                    if (journal != null && attemptId != null) {
                        newlyConfirmed.forEach { (idx, id) ->
                            journal.recordReceipt(saveAccount, attemptId, "$RECEIPT_FILE_PREFIX$idx", id)
                        }
                    }
                    currentFolders = currentFolders + incomplete.receipt.folders
                    resolvedFoldersSnapshot = currentFolders + ("" to plan.target.id)
                }
                if (lastAttemptResult.isSuccess) {
                    val readyPaths = remainingItems.filter { it.file.gcid != null }.map { it.file.path }
                    readyPaths.zip(lastAttemptResult.getOrThrow()).forEach { (path, id) ->
                        accumulatedIdsByPath[path] = id
                    }
                    val allOrdered = submitted.filter { it.file.gcid != null }.mapNotNull { accumulatedIdsByPath[it.file.path] }
                    lastAttemptResult = Result.success(allOrdered)
                    break
                }
                val rootCause = incomplete?.cause ?: lastAttemptResult.exceptionOrNull()
                val isTransient = rootCause != null && isTransientSaveFailure(rootCause)
                if (journal == null || !isTransient || transientAttempt >= MAX_TRANSIENT_RETRIES) {
                    if (isTransient && journal != null) {
                        stoppedByTransientOutage = true
                    }
                    if (incomplete != null && accumulatedIdsByPath.isNotEmpty()) {
                        val mergedReceipt = incomplete.receipt.copy(
                            confirmed = accumulatedIdsByPath.toMap(),
                            failed = incomplete.receipt.failed - accumulatedIdsByPath.keys,
                            notAttempted = incomplete.receipt.notAttempted - accumulatedIdsByPath.keys,
                            folders = resolvedFoldersSnapshot,
                        )
                        lastAttemptResult = Result.failure(
                            InstantSaveIncomplete(mergedReceipt, incomplete.cause ?: IllegalStateException("Instant save incomplete")),
                        )
                    }
                    break
                }
                val donePaths = withPikoLock(checkpointLock) { accumulatedIdsByPath.keys.toSet() }
                remainingItems = submitted.filter { it.file.gcid != null && it.file.path !in donePaths }
                if (remainingItems.isEmpty()) {
                    val allOrdered = submitted.filter { it.file.gcid != null }.mapNotNull { accumulatedIdsByPath[it.file.path] }
                    lastAttemptResult = Result.success(allOrdered)
                    break
                }
                checkExisting = true
                currentReuse = currentReuse.filterValues { previewId -> previewId !in accumulatedIdsByPath.values }
                val delayMs = 1_000L shl transientAttempt.coerceAtMost(3)
                transientAttempt++
                delay(delayMs)
            }
            lastAttemptResult
        }

        if (driveRepo.currentAccount != saveAccount) {
            throw CancellationException(tr("账号会话已结束", "Account session ended", "アカウントのセッションが終了しました", "계정 세션이 종료되었습니다", "帳號工作階段已結束"))
        }
        val current = (result.exceptionOrNull() as? InstantSaveIncomplete)?.receipt ?: if (result.isSuccess) {
            InstantSaveReceipt(
                saveAccount,
                plan.target.id,
                confirmed = submitted.filter { it.file.gcid != null }.map { it.file.path }.zip(result.getOrThrow()).toMap(),
                folders = resolvedFoldersSnapshot,
            )
        } else {
            InstantSaveReceipt(
                saveAccount,
                plan.target.id,
                failed = submitted.mapTo(HashSet()) { it.file.path },
                folders = resolvedFoldersSnapshot,
            )
        }
        val attempted = submitted.mapTo(HashSet()) { it.file.path }
        val confirmed = plan.receipt.confirmed + current.confirmed
        val receipt = current.copy(
            confirmed = confirmed,
            failed = ((plan.receipt.failed - attempted) + current.failed) - confirmed.keys,
            notAttempted = ((plan.receipt.notAttempted - attempted) + current.notAttempted) - confirmed.keys,
            folders = plan.receipt.folders + current.folders,
        )
        val allDone = receipt.failed.isEmpty() && receipt.notAttempted.isEmpty() && result.isSuccess
        val terminalStatus = when {
            allDone -> WorkJobStatus.COMPLETED
            stoppedByTransientOutage -> WorkJobStatus.IN_PROGRESS
            else -> WorkJobStatus.FAILED
        }
        persistPlanCheckpoint(terminalStatus)

        val touchedFolders = receipt.folders.values.toSet() + plan.target.id + listOfNotNull(parentId)
        if (reference && result.isSuccess) {
            driveRepo.notifyFoldersRefreshed(touchedFolders)
        } else {
            driveRepo.applyChange(dev.piko.shared.data.DriveChange.ContentsChanged(touchedFolders))
        }
        ledgerSource?.let { source ->
            driveRepo.sourceLedger.record(source, submitted.filter { it.file.path in current.confirmed }.map { it.file })
        }

        return InstantExecutionResult(result, receipt, current.confirmed.keys)
    }

    private fun isTransientSaveFailure(error: Throwable): Boolean = when (error) {
        is IOException -> true
        is PikPakException -> {
            val code = error.httpStatus ?: 0
            code == 408 || code == 429 || code >= 500 || code == 0
        }
        else -> false
    }

    companion object {
        private const val MAX_TRANSIENT_RETRIES = 3
        internal const val RECEIPT_FILE_PREFIX = "file:"
        internal const val RECEIPT_DIR_PREFIX = "dir:"
        internal const val RECEIPT_PREVIEW_PREFIX = "preview:"

        internal fun persistPendingContainerPlan(
            driveRepo: PikoDriveRepository,
            account: String,
            attemptId: String,
            parentTarget: PikoPathBreadcrumb,
            containerName: String,
            items: List<InstantFileItem>,
            names: Map<String, String>,
            reusableIds: Map<String, String>,
            ledgerSource: String?,
        ): Boolean {
            val journal = driveRepo.workJournal ?: return true
            if (!journal.isPersistentFor(account)) return false
            val pathIndex = items.mapIndexed { idx, item -> item.file.path to idx }.toMap()
            val payload = DurableInstantSaveJobPayload(
                attemptId = attemptId,
                account = account,
                magnet = ledgerSource.orEmpty(),
                title = containerName,
                targetFolderId = parentTarget.id,
                targetFolderDisplayPath = parentTarget.name,
                saveMode = "PHYSICAL",
                items = items.mapIndexed { idx, item ->
                    DurableInstantItem(
                        index = idx,
                        name = item.file.name,
                        path = item.file.path,
                        size = item.file.size,
                        gcid = item.file.gcid.orEmpty(),
                        mimeType = item.file.mimeType.orEmpty(),
                    )
                },
                names = names.mapNotNull { (path, customName) ->
                    pathIndex[path]?.let { idx -> idx to customName }
                }.toMap(),
                reusableIds = reusableIds.toMap(),
                keepStructure = true,
                requestedParentFolderId = parentTarget.id,
                containerFolderName = containerName,
                containerFolderId = null,
            )
            return journal.saveInstantSaveJob(payload, status = WorkJobStatus.IN_PROGRESS)
        }
    }
}

object InstantSaveRecovery {
    private const val TAG = "InstantSaveRecovery"

    suspend fun resumeInProgress(
        account: String,
        driveRepo: PikoDriveRepository,
        instantRepo: InstantMagnetRepository,
        saveRecords: InstantSaveRecords,
    ) {
        val client = instantRepo.clientForAccount(account) ?: return
        resumeInProgress(client, driveRepo, instantRepo, saveRecords)
    }

    suspend fun resumeInProgress(
        client: PikPakClient,
        driveRepo: PikoDriveRepository,
        instantRepo: InstantMagnetRepository,
        saveRecords: InstantSaveRecords,
    ) {
        val account = client.account
        val journal = driveRepo.workJournal ?: return
        val pending = journal.loadInProgressInstantSaveJobs(account)
        if (pending.isEmpty()) return
        for ((_, payload) in pending) {
            if (driveRepo.currentAccount != account) return
            if (!saveRecords.tryClaimExecution(account, payload.attemptId)) continue
            try {
                val itemByIndex = payload.items.associateBy { it.index }
                val allItems = payload.items.sortedBy { it.index }.map { dto ->
                    InstantFileItem(
                        file = ResolvedFile(
                            path = dto.path,
                            size = dto.size,
                            gcid = dto.gcid.takeIf { it.isNotBlank() },
                            mimeType = dto.mimeType.takeIf { it.isNotBlank() },
                        ),
                        isInstantReady = dto.gcid.isNotBlank(),
                        isSelected = true,
                    )
                }
                val namesByPath = payload.names.mapNotNull { (idx, name) ->
                    itemByIndex[idx]?.path?.let { path -> path to name }
                }.toMap()
                val storedReceipts = journal.loadReceipts(account, payload.attemptId)
                val mergedConfirmedByIndex = HashMap<Int, String>(payload.confirmed)
                val mergedFolders = LinkedHashMap<String, String>(payload.folders)
                val movedPreviewsByPath = HashMap<String, String>()
                for ((key, value) in storedReceipts) {
                    when {
                        key.startsWith(InstantSaveExecutor.RECEIPT_FILE_PREFIX) -> {
                            key.removePrefix(InstantSaveExecutor.RECEIPT_FILE_PREFIX).toIntOrNull()?.let { idx ->
                                mergedConfirmedByIndex[idx] = value
                            }
                        }
                        key.startsWith(InstantSaveExecutor.RECEIPT_DIR_PREFIX) -> {
                            val rel = key.removePrefix(InstantSaveExecutor.RECEIPT_DIR_PREFIX)
                            if (rel.isNotEmpty()) mergedFolders[rel] = value
                        }
                        key.startsWith(InstantSaveExecutor.RECEIPT_PREVIEW_PREFIX) -> {
                            key.removePrefix(InstantSaveExecutor.RECEIPT_PREVIEW_PREFIX).toIntOrNull()?.let { idx ->
                                itemByIndex[idx]?.path?.let { path -> movedPreviewsByPath[path] = value }
                            }
                        }
                    }
                }
                val confirmedByPath = mergedConfirmedByIndex.mapNotNull { (idx, fileId) ->
                    itemByIndex[idx]?.path?.let { path -> path to fileId }
                }.toMap()

                var resolvedContainerId = payload.containerFolderId?.takeIf { it.isNotBlank() }
                val targetBread = if (payload.containerFolderName != null && resolvedContainerId == null) {
                    val parentFolderId = payload.requestedParentFolderId ?: payload.targetFolderId
                    val claimed = driveRepo.createOrClaimFolder(parentFolderId, payload.containerFolderName).getOrElse { err ->
                        PikoLog.w(TAG, "Deferred instant-save container recovery for attempt=${payload.attemptId}", err)
                        continue
                    }
                    resolvedContainerId = claimed.first
                    val updatedPayload = payload.copy(
                        targetFolderId = claimed.first,
                        containerFolderId = claimed.first,
                    )
                    journal.saveInstantSaveJob(updatedPayload, status = WorkJobStatus.IN_PROGRESS)
                    PikoPathBreadcrumb(claimed.first, payload.containerFolderName)
                } else if (resolvedContainerId != null && payload.containerFolderName != null) {
                    PikoPathBreadcrumb(resolvedContainerId, payload.containerFolderName)
                } else {
                    PikoPathBreadcrumb(payload.targetFolderId, payload.targetFolderDisplayPath)
                }

                val remaining = allItems.filter { it.file.gcid != null && it.file.path !in confirmedByPath }
                fun finalizeCompleted(finalConfirmed: Map<String, String>) {
                    val locateId = resolvedContainerId
                        ?: finalConfirmed.values.firstOrNull().orEmpty()
                    val targetDisplayName = if (payload.containerFolderName != null) {
                        payload.targetFolderDisplayPath
                    } else {
                        targetBread.name
                    }
                    payload.magnet.takeIf { it.isNotBlank() }?.let { source ->
                        driveRepo.sourceLedger.record(
                            source,
                            allItems.filter { it.file.path in finalConfirmed }.map { it.file },
                            expectedAccount = account,
                        )
                    }
                    saveRecords.add(
                        name = payload.title,
                        fileCount = allItems.count { it.file.gcid != null },
                        totalBytes = allItems.filter { it.file.gcid != null }.sumOf { it.file.size },
                        targetName = targetDisplayName,
                        locateId = locateId,
                        id = payload.attemptId,
                        expectedAccount = account,
                    )
                    journal.saveInstantSaveJob(
                        payload.copy(
                            targetFolderId = targetBread.id,
                            containerFolderId = resolvedContainerId,
                            confirmed = mergedConfirmedByIndex,
                            folders = mergedFolders,
                        ),
                        status = WorkJobStatus.COMPLETED,
                    )
                }

                if (remaining.isEmpty()) {
                    finalizeCompleted(confirmedByPath)
                    continue
                }
                val acceptedId = saveRecords.beginAttempt(
                    account = account,
                    title = payload.title,
                    target = targetBread,
                    files = allItems.map { it.file },
                    id = payload.attemptId,
                    containerName = payload.containerFolderName,
                    names = namesByPath,
                    keepStructure = payload.keepStructure,
                    reference = false,
                ) ?: continue
                PikoLog.i(TAG, "Resuming interrupted instant save attempt=$acceptedId (remaining=${remaining.size}/${allItems.size})")
                val initialReceipt = InstantSaveReceipt(
                    account = account,
                    targetId = targetBread.id,
                    confirmed = confirmedByPath,
                    notAttempted = remaining.mapTo(HashSet()) { it.file.path },
                    folders = mergedFolders,
                )
                val plan = InstantRetryPlan(
                    target = targetBread,
                    files = allItems,
                    names = namesByPath,
                    keepStructure = payload.keepStructure,
                    receipt = initialReceipt,
                )
                val exec = InstantSaveExecutor(instantRepo, driveRepo, account, boundClient = client).execute(
                    plan = plan,
                    submitted = remaining,
                    targetIsFreshFolder = false,
                    reference = false,
                    previewed = payload.reusableIds,
                    referenceSource = payload.magnet.takeIf { it.isNotBlank() },
                    ledgerSource = payload.magnet.takeIf { it.isNotBlank() },
                    parentId = payload.requestedParentFolderId,
                    attemptId = acceptedId,
                    title = payload.title,
                    containerName = payload.containerFolderName,
                    alreadyMovedPreviews = movedPreviewsByPath,
                    forceCheckExisting = true,
                    onProgress = { done, total, status, detail ->
                        saveRecords.updateAttemptProgress(acceptedId, account, confirmedByPath.size + done, allItems.size, status, detail)
                    },
                )
                saveRecords.finishAttempt(acceptedId, exec.receipt)
                if (exec.response.isSuccess && exec.receipt.failed.isEmpty() && exec.receipt.notAttempted.isEmpty()) {
                    finalizeCompleted(exec.receipt.confirmed)
                }
            } finally {
                saveRecords.releaseExecution(account, payload.attemptId)
            }
        }
    }
}
