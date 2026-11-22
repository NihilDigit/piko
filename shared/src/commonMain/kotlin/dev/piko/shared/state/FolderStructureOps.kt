package dev.piko.shared.state

import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.isLocal
import dev.piko.shared.data.isVaulted
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logFolder
import dev.piko.shared.notice.PikoErrorMapper
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class FolderStructureMode {
    /** 拆开（上提一层）：移出所选文件夹内容至当前目录，并清理已清空的壳文件夹。 */
    UNWRAP,

    /** 全部铺平：递归提取所选文件夹内的全部文件至当前目录，并清理已清空的文件夹。 */
    FLATTEN,

    /** 合并文件夹（需选中 2 个及以上文件夹）：将其他所选文件夹的直接子项移入目标文件夹。 */
    MERGE,
}

enum class CollisionStrategy {
    /** 安全跳过同名冲突项（并保留其所在文件夹不移入回收站）。 */
    SKIP,

    /** 自动为冲突项追加编号后缀（如 `name (2).ext`）后再移动。 */
    RENAME_SUFFIX,
}

data class FolderStructureOptions(
    val mode: FolderStructureMode,
    val unwrapSingleChildChain: Boolean = true,
    val mergeTargetId: String? = null,
    val collisionStrategy: CollisionStrategy = CollisionStrategy.SKIP,
)

data class PlannedMove(
    val item: FileStat,
    val sourceParentId: String,
    /** Selected root folder ID and any traversed intermediate folder IDs above [item]. */
    val ancestorFolderIds: Set<String>,
    val renamedTo: String? = null,
)

data class PlannedCollision(
    val item: FileStat,
    val sourceParentId: String,
    val resolvedName: String?,
)

data class FolderStructurePlan(
    val options: FolderStructureOptions,
    val workingFolderId: String,
    val targetFolderId: String,
    val targetFolderName: String,
    val selectedFolders: List<FileStat>,
    val moves: List<PlannedMove>,
    val skippedCollisions: List<PlannedCollision>,
    val renamedCollisions: List<PlannedCollision>,
    val foldersToTrash: List<FileStat>,
    val penetratedWrapperCount: Int,
    /** Direct child counts per selected folder (used to pick & display Merge targets). */
    val directChildCounts: Map<String, Int>,
    val blockedReason: String? = null,
) {
    val totalCollisionCount: Int get() = skippedCollisions.size + renamedCollisions.size
    val canExecute: Boolean get() = blockedReason == null && (moves.isNotEmpty() || foldersToTrash.isNotEmpty())
}

enum class FolderStructurePhase {
    RENAMING,
    MOVING,
    TRASHING,
}

data class FolderStructureProgress(
    val phase: FolderStructurePhase,
    val completed: Int,
    val total: Int,
)

data class FolderStructureOutcome(
    val mode: FolderStructureMode,
    val movedCount: Int,
    val renamedCount: Int,
    val skippedCount: Int,
    val trashedFolderCount: Int,
    val failedCount: Int,
    val errorMessage: String? = null,
) {
    val isSuccess: Boolean get() = failedCount == 0 && errorMessage == null
}

/** Storage/repository seam for [FolderStructureOps], cleanly testable without live network. */
interface FolderStructureBackend {
    suspend fun listChildren(folderId: String): Result<List<FileStat>>

    suspend fun moveFiles(ids: List<String>, targetParentId: String): Result<Unit>

    suspend fun renameFile(fileId: String, newName: String, parentId: String?): Result<Unit>

    suspend fun trashFiles(ids: List<String>): Result<Unit>

    /**
     * Moves archived [VaultEntry] items (`sourceParentId -> (entryId -> optionalNewName)`) into
     * [targetFolderId]'s manifest first, then removes them from each source folder's manifest.
     * Returns the exact set of `(sourceParentId, entryId)` pairs confirmed migrated.
     */
    suspend fun moveVaultEntries(
        targetFolderId: String,
        movesBySourceFolder: Map<String, Map<String, String?>>,
    ): Result<Set<Pair<String, String>>> = Result.success(emptySet())

    fun isVaultedFolder(folderId: String): Boolean = false
}

fun PikoDriveRepository.asFolderStructureBackend(): FolderStructureBackend = object : FolderStructureBackend {
    override suspend fun listChildren(folderId: String): Result<List<FileStat>> =
        listBrowsableStrict(folderId, PikoFileSortOrder.NAME_ASC)

    override suspend fun moveFiles(ids: List<String>, targetParentId: String): Result<Unit> =
        move(ids, targetParentId)

    override suspend fun renameFile(fileId: String, newName: String, parentId: String?): Result<Unit> =
        rename(fileId, newName, parentId = parentId)

    override suspend fun trashFiles(ids: List<String>): Result<Unit> =
        trash(ids)

    override suspend fun moveVaultEntries(
        targetFolderId: String,
        movesBySourceFolder: Map<String, Map<String, String?>>,
    ): Result<Set<Pair<String, String>>> = runSuspendCatching {
        if (movesBySourceFolder.isEmpty()) return@runSuspendCatching emptySet()
        val account = currentAccount ?: error("Not signed in")
        val store = vaultAccess(account) ?: error("Vault store unavailable")
        val movedEntries = ArrayList<VaultEntry>()
        val sourceRemovals = LinkedHashMap<String, Set<String>>()
        val migratedPairs = LinkedHashSet<Pair<String, String>>()

        for ((sourceFolderId, entryRenames) in movesBySourceFolder) {
            if (entryRenames.isEmpty()) continue
            val currentEntries = store.committed(sourceFolderId).getOrThrow()
            val byId = currentEntries.associateBy { it.id }
            val matchedIds = LinkedHashSet<String>()
            for ((entryId, newName) in entryRenames) {
                val entry = byId[entryId]?.takeIf { it.isArchived } ?: continue
                movedEntries += if (newName != null && newName != entry.name) entry.copy(name = newName) else entry
                matchedIds += entryId
                migratedPairs += sourceFolderId to entryId
            }
            if (matchedIds.isNotEmpty()) {
                sourceRemovals[sourceFolderId] = matchedIds
            }
        }
        if (movedEntries.isEmpty()) return@runSuspendCatching emptySet()

        // 1. Commit all moved entries into destination manifest in a single batch first (zero data loss if interrupted).
        store.update(targetFolderId, VaultEdits.add(movedEntries)).getOrThrow()

        // 2. Remove moved entry IDs from each source folder manifest.
        for ((sourceFolderId, removeIds) in sourceRemovals) {
            if (sourceFolderId == targetFolderId) continue
            store.update(sourceFolderId, VaultEdits.remove(removeIds)).getOrThrow()
        }

        val touched = buildSet {
            add(targetFolderId)
            addAll(sourceRemovals.keys)
        }
        applyChange(DriveChange.ContentsChanged(touched))
        migratedPairs
    }

    override fun isVaultedFolder(folderId: String): Boolean =
        folderId in vaultedFolders.value
}

/**
 * Pure-commonMain planner & executor for folder structure operations:
 * - Mode A: Unwrap (拆开 / 上提一层, with optional single-child wrapper chain penetration)
 * - Mode B: Flatten (全部铺平)
 * - Mode C: Merge folders (合并文件夹)
 *
 * Plan phase inspects [DriveTreeIndex] via [FolderStructureBackend.listChildren] (falling back
 * to live listing for unindexed/dirty folders) and computes an exact dry-run preview without
 * mutating anything. Execute phase performs batched renames, moves, and empty-folder trash
 * while routing all mutations through [PikoDriveRepository] (`applyChange`).
 */
class FolderStructureOps(
    private val backend: FolderStructureBackend,
) {
    suspend fun plan(
        workingFolderId: String,
        workingFolderName: String,
        selectedFolders: List<FileStat>,
        options: FolderStructureOptions,
    ): Result<FolderStructurePlan> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val validationError = validateInputs(workingFolderId, selectedFolders, options)
            if (validationError != null) {
                return@runSuspendCatching emptyPlan(
                    options = options,
                    workingFolderId = workingFolderId,
                    workingFolderName = workingFolderName,
                    selectedFolders = selectedFolders,
                    blockedReason = validationError,
                )
            }

            // Cache folder listings within this single plan pass to avoid redundant I/O.
            val childrenCache = LinkedHashMap<String, List<FileStat>>()
            suspend fun childrenOf(folderId: String): List<FileStat> {
                childrenCache[folderId]?.let { return it }
                currentCoroutineContext().ensureActive()
                val loaded = backend.listChildren(folderId).getOrThrow()
                    .filterNot { it.trashed || VaultStore.looksLikeManifest(it) || VaultStore.isRecoveryFile(it) }
                childrenCache[folderId] = loaded
                return loaded
            }

            // Compute direct child counts for all selected folders (also drives default Merge target).
            val directChildCounts = LinkedHashMap<String, Int>(selectedFolders.size)
            for (folder in selectedFolders) {
                val direct = childrenOf(folder.id)
                directChildCounts[folder.id] = direct.size
            }

            val mergeTarget: FileStat? = if (options.mode == FolderStructureMode.MERGE) {
                selectedFolders.firstOrNull { it.id == options.mergeTargetId }
                    ?: selectedFolders.maxByOrNull { directChildCounts[it.id] ?: 0 }
                    ?: selectedFolders.first()
            } else null

            val effectiveOptions = if (mergeTarget != null && options.mergeTargetId != mergeTarget.id) {
                options.copy(mergeTargetId = mergeTarget.id)
            } else options

            val targetFolderId = mergeTarget?.id ?: workingFolderId
            val targetFolderName = mergeTarget?.name ?: workingFolderName

            // Destination's existing names (moves happen BEFORE trashing, so all current destination items occupy names).
            val destinationChildren = childrenOf(targetFolderId)
            val occupiedNames = destinationChildren.mapTo(HashSet()) { it.name.lowercase() }
            val sourceSiblingNames = LinkedHashMap<String, MutableSet<String>>()
            fun siblingsOf(parentId: String): MutableSet<String> = sourceSiblingNames.getOrPut(parentId) {
                childrenCache[parentId].orEmpty().mapTo(HashSet()) { it.name.lowercase() }
            }

            val plannedMoves = ArrayList<PlannedMove>()
            val skippedCollisions = ArrayList<PlannedCollision>()
            val renamedCollisions = ArrayList<PlannedCollision>()
            val foldersToTrash = ArrayList<FileStat>()
            var penetratedWrapperCount = 0

            fun scheduleItemMove(
                item: FileStat,
                sourceParentId: String,
                ancestors: Set<String>,
            ): Boolean {
                val lower = item.name.lowercase()
                if (lower !in occupiedNames) {
                    occupiedNames.add(lower)
                    plannedMoves += PlannedMove(item, sourceParentId, ancestors, renamedTo = null)
                    return true
                }
                return when (effectiveOptions.collisionStrategy) {
                    CollisionStrategy.SKIP -> {
                        skippedCollisions += PlannedCollision(item, sourceParentId, resolvedName = null)
                        false
                    }
                    CollisionStrategy.RENAME_SUFFIX -> {
                        val siblings = siblingsOf(sourceParentId)
                        val uniqueName = uniqueSuffixName(item.name, item.isFolder, occupiedNames, siblings)
                        occupiedNames.add(uniqueName.lowercase())
                        siblings.add(uniqueName.lowercase())
                        renamedCollisions += PlannedCollision(item, sourceParentId, resolvedName = uniqueName)
                        plannedMoves += PlannedMove(item, sourceParentId, ancestors, renamedTo = uniqueName)
                        true
                    }
                }
            }

            when (effectiveOptions.mode) {
                FolderStructureMode.UNWRAP -> {
                    for (root in selectedFolders) {
                        var current = root
                        val chain = LinkedHashSet<String>().apply { add(root.id) }
                        var currentChildren = childrenOf(current.id)
                        if (effectiveOptions.unwrapSingleChildChain) {
                            var depth = 0
                            while (currentChildren.size == 1 && currentChildren[0].isFolder && depth < MAX_UNWRAP_DEPTH) {
                                val nextFolder = currentChildren[0]
                                if (!chain.add(nextFolder.id)) break
                                val nextChildren = childrenOf(nextFolder.id)
                                current = nextFolder
                                currentChildren = nextChildren
                                penetratedWrapperCount++
                                depth++
                            }
                        }
                        var anySkipped = false
                        for (child in currentChildren) {
                            if (!scheduleItemMove(child, current.id, chain.toSet())) {
                                anySkipped = true
                            }
                        }
                        if (!anySkipped) {
                            // Trashing [root] safely trashes the entire emptied wrapper chain.
                            foldersToTrash += root
                        }
                    }
                }

                FolderStructureMode.FLATTEN -> {
                    for (root in selectedFolders) {
                        val visited = HashSet<String>().apply { add(root.id) }
                        val childFoldersByParent = LinkedHashMap<String, MutableList<FileStat>>()
                        val hasSkippedDescendant = HashSet<String>()

                        suspend fun walk(folder: FileStat, ancestors: Set<String>): Boolean {
                            val children = childrenOf(folder.id)
                            var skippedHere = false
                            for (child in children) {
                                if (child.isFolder) {
                                    if (!visited.add(child.id)) continue
                                    childFoldersByParent.getOrPut(folder.id) { mutableListOf() }.add(child)
                                    val subSkipped = walk(child, ancestors + child.id)
                                    if (subSkipped) skippedHere = true
                                } else {
                                    if (!scheduleItemMove(child, folder.id, ancestors)) {
                                        skippedHere = true
                                    }
                                }
                            }
                            if (skippedHere) hasSkippedDescendant.add(folder.id)
                            return skippedHere
                        }

                        val rootHasSkipped = walk(root, setOf(root.id))
                        if (!rootHasSkipped) {
                            foldersToTrash += root
                        } else {
                            // Only trash inner subtrees that became completely empty!
                            fun collectEmptySubtrees(parentId: String) {
                                for (sub in childFoldersByParent[parentId].orEmpty()) {
                                    if (sub.id !in hasSkippedDescendant) {
                                        foldersToTrash += sub
                                    } else {
                                        collectEmptySubtrees(sub.id)
                                    }
                                }
                            }
                            collectEmptySubtrees(root.id)
                        }
                    }
                }

                FolderStructureMode.MERGE -> {
                    val target = requireNotNull(mergeTarget)
                    val sources = selectedFolders.filter { it.id != target.id }
                    for (source in sources) {
                        val children = childrenOf(source.id)
                        var anySkipped = false
                        for (child in children) {
                            if (!scheduleItemMove(child, source.id, setOf(source.id))) {
                                anySkipped = true
                            }
                        }
                        if (!anySkipped) {
                            foldersToTrash += source
                        }
                    }
                }
            }

            FolderStructurePlan(
                options = effectiveOptions,
                workingFolderId = workingFolderId,
                targetFolderId = targetFolderId,
                targetFolderName = targetFolderName,
                selectedFolders = selectedFolders,
                moves = plannedMoves,
                skippedCollisions = skippedCollisions,
                renamedCollisions = renamedCollisions,
                foldersToTrash = foldersToTrash,
                penetratedWrapperCount = penetratedWrapperCount,
                directChildCounts = directChildCounts,
                blockedReason = null,
            )
        }
    }

    suspend fun execute(
        plan: FolderStructurePlan,
        onProgress: (FolderStructureProgress) -> Unit = {},
    ): FolderStructureOutcome = withContext(Dispatchers.Default) {
        if (!plan.canExecute) {
            return@withContext FolderStructureOutcome(
                mode = plan.options.mode,
                movedCount = 0,
                renamedCount = 0,
                skippedCount = plan.skippedCollisions.size,
                trashedFolderCount = 0,
                failedCount = 0,
                errorMessage = plan.blockedReason,
            )
        }

        val failedAncestors = HashSet<String>()
        var renamedCount = 0
        var movedCount = 0
        var trashedCount = 0
        var failedCount = 0
        var firstError: String? = null

        try {
            val (vaultPlanned, realPlanned) = plan.moves.partition { it.item.isVaulted }

            // Phase 1: Rename colliding real items that use RENAME_SUFFIX before moving.
            // (Archived VaultEntry renames are applied atomically inside Phase 2's manifest write.)
            val movesToRename = realPlanned.filter { it.renamedTo != null }
            val readyRealMoves = ArrayList<PlannedMove>(realPlanned.size)
            val renameFailedIds = HashSet<String>()

            if (movesToRename.isNotEmpty()) {
                onProgress(FolderStructureProgress(FolderStructurePhase.RENAMING, 0, movesToRename.size))
                movesToRename.forEachIndexed { idx, planned ->
                    currentCoroutineContext().ensureActive()
                    val newName = requireNotNull(planned.renamedTo)
                    val result = backend.renameFile(planned.item.id, newName, planned.sourceParentId)
                    if (result.isSuccess) {
                        renamedCount++
                    } else {
                        val err = result.exceptionOrNull()
                        if (err is CancellationException) throw err
                        PikoLog.w(TAG, "FolderStructure rename failed for ${logFile(planned.item.id, planned.item.name)}", err)
                        renameFailedIds.add(planned.item.id)
                        failedAncestors.addAll(planned.ancestorFolderIds)
                        failedCount++
                        if (firstError == null && err != null) {
                            firstError = PikoErrorMapper.failure(tr("重命名", "Rename", "名前を変更", "이름 변경", "重新命名"), err)
                        }
                    }
                    onProgress(FolderStructureProgress(FolderStructurePhase.RENAMING, idx + 1, movesToRename.size))
                }
            }

            for (planned in realPlanned) {
                if (planned.item.id !in renameFailedIds) {
                    readyRealMoves += planned
                }
            }

            // Phase 2: Batch-move real items & atomically migrate archived VaultEntry manifests to plan.targetFolderId.
            val totalMoveItems = readyRealMoves.size + vaultPlanned.size
            if (totalMoveItems > 0) {
                onProgress(FolderStructureProgress(FolderStructurePhase.MOVING, 0, totalMoveItems))
                var processed = 0
                for (chunk in readyRealMoves.chunked(BATCH_LIMIT)) {
                    currentCoroutineContext().ensureActive()
                    val ids = chunk.map { it.item.id }
                    val result = backend.moveFiles(ids, plan.targetFolderId)
                    if (result.isSuccess) {
                        movedCount += ids.size
                    } else {
                        val err = result.exceptionOrNull()
                        if (err is CancellationException) throw err
                        PikoLog.w(TAG, "FolderStructure move batch failed (count=${ids.size}, target=${logFolder(plan.targetFolderId)})", err)
                        for (planned in chunk) {
                            failedAncestors.addAll(planned.ancestorFolderIds)
                        }
                        failedCount += ids.size
                        if (firstError == null && err != null) {
                            firstError = PikoErrorMapper.failure(tr("移动", "Move", "移動", "이동", "移動"), err)
                        }
                    }
                    processed += chunk.size
                    onProgress(FolderStructureProgress(FolderStructurePhase.MOVING, processed, totalMoveItems))
                }

                if (vaultPlanned.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val movesBySource = LinkedHashMap<String, MutableMap<String, String?>>()
                    for (planned in vaultPlanned) {
                        val entryId = VaultEntry.entryIdOf(planned.item.id)
                        if (entryId == null) {
                            failedAncestors.addAll(planned.ancestorFolderIds)
                            failedCount++
                            continue
                        }
                        movesBySource.getOrPut(planned.sourceParentId) { LinkedHashMap() }[entryId] = planned.renamedTo
                    }
                    val vaultResult = backend.moveVaultEntries(plan.targetFolderId, movesBySource)
                    if (vaultResult.isSuccess) {
                        val migratedPairs = vaultResult.getOrDefault(emptySet())
                        for (planned in vaultPlanned) {
                            val entryId = VaultEntry.entryIdOf(planned.item.id)
                            if (entryId != null && (planned.sourceParentId to entryId) in migratedPairs) {
                                movedCount++
                                if (planned.renamedTo != null) renamedCount++
                            } else {
                                failedAncestors.addAll(planned.ancestorFolderIds)
                                failedCount++
                            }
                        }
                    } else {
                        val err = vaultResult.exceptionOrNull()
                        if (err is CancellationException) throw err
                        PikoLog.w(TAG, "FolderStructure vault entry migration failed (count=${vaultPlanned.size}, target=${logFolder(plan.targetFolderId)})", err)
                        for (planned in vaultPlanned) {
                            failedAncestors.addAll(planned.ancestorFolderIds)
                        }
                        failedCount += vaultPlanned.size
                        if (firstError == null && err != null) {
                            firstError = PikoErrorMapper.failure(tr("移动归档条目", "Move archived items", "アーカイブ項目を移動", "보관 항목 이동", "移動歸檔項目"), err)
                        }
                    }
                    processed += vaultPlanned.size
                    onProgress(FolderStructureProgress(FolderStructurePhase.MOVING, processed, totalMoveItems))
                }
            }

            // Phase 3: Safely trash only folders that had zero failed/skipped items.
            val safeToTrash = plan.foldersToTrash.filter { it.id !in failedAncestors }
            if (safeToTrash.isNotEmpty()) {
                onProgress(FolderStructureProgress(FolderStructurePhase.TRASHING, 0, safeToTrash.size))
                var processed = 0
                for (chunk in safeToTrash.chunked(BATCH_LIMIT)) {
                    currentCoroutineContext().ensureActive()
                    val ids = chunk.map { it.id }
                    val result = backend.trashFiles(ids)
                    if (result.isSuccess) {
                        trashedCount += ids.size
                    } else {
                        val err = result.exceptionOrNull()
                        if (err is CancellationException) throw err
                        PikoLog.w(TAG, "FolderStructure trash batch failed (count=${ids.size})", err)
                        failedCount += ids.size
                        if (firstError == null && err != null) {
                            firstError = PikoErrorMapper.failure(tr("清理空文件夹", "Clean up empty folders", "空フォルダーを削除", "빈 폴더 정리", "清理空資料夾"), err)
                        }
                    }
                    processed += chunk.size
                    onProgress(FolderStructureProgress(FolderStructurePhase.TRASHING, processed, safeToTrash.size))
                }
            }

            PikoLog.i(
                TAG,
                "FolderStructure ${plan.options.mode} finished (target=${logFolder(plan.targetFolderId)}, " +
                    "moved=$movedCount, renamed=$renamedCount, skipped=${plan.skippedCollisions.size}, " +
                    "trashed=$trashedCount, failed=$failedCount)",
            )

            FolderStructureOutcome(
                mode = plan.options.mode,
                movedCount = movedCount,
                renamedCount = renamedCount,
                skippedCount = plan.skippedCollisions.size,
                trashedFolderCount = trashedCount,
                failedCount = failedCount,
                errorMessage = firstError,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        }
    }

    private fun validateInputs(
        workingFolderId: String,
        selectedFolders: List<FileStat>,
        options: FolderStructureOptions,
    ): String? {
        if (!isDriveFolderId(workingFolderId)) {
            return tr("当前位置不支持此操作", "This operation is not supported in this location", "この場所ではこの操作を実行できません", "현재 위치에서는 이 작업을 지원하지 않습니다", "目前位置不支援此操作")
        }
        if (selectedFolders.isEmpty()) {
            return tr("请选择至少一个文件夹", "Select at least one folder", "フォルダーを 1 つ以上選択してください", "폴더를 하나 이상 선택해 주세요", "請至少選取一個資料夾")
        }
        if (selectedFolders.any { !isEligibleCloudFolder(it) }) {
            return tr("仅支持网盘中的普通文件夹", "Only standard cloud folders are supported", "ドライブ内の通常フォルダーのみ対応しています", "드라이브의 일반 폴더만 지원합니다", "僅支援雲端硬碟中的一般資料夾")
        }
        if (options.mode == FolderStructureMode.MERGE && selectedFolders.size < 2) {
            return tr("合并需要至少选择两个文件夹", "Select at least two folders to merge", "統合するにはフォルダーを 2 つ以上選択してください", "병합하려면 폴더를 2개 이상 선택해 주세요", "合併至少需要選取兩個資料夾")
        }
        return null
    }

    private fun emptyPlan(
        options: FolderStructureOptions,
        workingFolderId: String,
        workingFolderName: String,
        selectedFolders: List<FileStat>,
        directChildCounts: Map<String, Int> = emptyMap(),
        blockedReason: String?,
    ) = FolderStructurePlan(
        options = options,
        workingFolderId = workingFolderId,
        targetFolderId = workingFolderId,
        targetFolderName = workingFolderName,
        selectedFolders = selectedFolders,
        moves = emptyList(),
        skippedCollisions = emptyList(),
        renamedCollisions = emptyList(),
        foldersToTrash = emptyList(),
        penetratedWrapperCount = 0,
        directChildCounts = directChildCounts,
        blockedReason = blockedReason,
    )

    companion object {
        private const val TAG = "FolderStructure"
        private const val BATCH_LIMIT = 500
        private const val MAX_UNWRAP_DEPTH = 64

        /** Checks whether [file] is a real cloud folder eligible for unwrap/flatten/merge. */
        fun isEligibleCloudFolder(file: FileStat): Boolean =
            file.isFolder &&
                !file.trashed &&
                !file.isLocal &&
                !file.isVaulted &&
                !file.isUploading &&
                !file.isImportPreview &&
                !ArchiveEntryId.isEntry(file.id) &&
                isDriveFolderId(file.id)

        internal fun uniqueSuffixName(
            originalName: String,
            isFolder: Boolean,
            occupiedLower: Set<String>,
            siblingLower: Set<String>,
        ): String {
            val dotIndex = if (isFolder) -1 else originalName.lastIndexOf('.').takeIf { it > 0 } ?: -1
            val stem = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
            val ext = if (dotIndex > 0) originalName.substring(dotIndex) else ""
            var n = 2
            while (true) {
                val candidate = "$stem ($n)$ext"
                val key = candidate.lowercase()
                if (key !in occupiedLower && key !in siblingLower) return candidate
                n++
            }
        }
    }
}
