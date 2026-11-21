@file:JvmMultifileClass
@file:JvmName("PikoDriveRepositoryKt")

package dev.piko.shared.data

import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import dev.piko.shared.memory.MemoryPolicy
import dev.piko.shared.memory.MemoryWorkKind
import dev.piko.shared.memory.PikoMemoryGovernor
import dev.piko.shared.runtime.PikoWorkloadGovernor
import dev.piko.shared.state.DriveAnalysisCache
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.ApiRequestPriority
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileFilter
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileListPage
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileSyncVersionOutdatedException
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.consumeFileChanges
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.listAllDriveEntriesFlow
import io.github.nihildigit.pikpak.listAllDriveFolders
import io.github.nihildigit.pikpak.listFileChangesPage
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listFilesPaged
import io.github.nihildigit.pikpak.withApiPriority
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal const val DRIVE_REPO_TAG = "DriveRepository"

/**
 * 文件夹索引（[FolderIndex]）、本地文件树（[DriveTreeIndex]）生命周期与双缓冲/断点续建、
 * 增量同步与哨兵探针对账、变更序号栅栏（`ChangeMarks`）以及按 ID 逐级上溯路径定位。
 */
internal class FolderIndexCoordinator(
    private val ctx: DriveRepoContext,
) {
    private class ChangeMarks(
        val serial: Long = 0,
        val accountResetAt: Long = 0,
        val treeInvalidatedAt: Long = 0,
        val unknownAt: Long = 0,
        val folderAt: Map<String, Long> = emptyMap(),
        val patchedAt: Map<String, Long> = emptyMap(),
        val listedAt: Map<String, Long> = emptyMap(),
        val removedAt: Map<String, Long> = emptyMap(),
        val renamedAt: Map<String, Pair<Long, String>> = emptyMap(),
        val movedAt: Map<String, Pair<Long, String>> = emptyMap(),
    )

    val folderIndex = FolderIndex()

    var driveTreeIndex = DriveTreeIndex(
        locallyCachedManifestIds = { ctx.overlay.currentParsedManifestIds() },
    )
        private set

    internal val treeBindingFlow = MutableStateFlow(driveTreeIndex)
    internal val treeStateLock = PikoLock()
    internal val treeSyncJob = MutableStateFlow<Pair<PikPakClient, Deferred<Unit>>?>(null)
    private val treeBuildMutex = Mutex()
    private var activeBuildToken: Any? = null
    private var lastCanaryProbeAt: TimeSource.Monotonic.ValueTimeMark? = null
    private val remoteDeltaMutex = Mutex()

    private val _treeBuildProgressFlow = MutableStateFlow(PikoDriveRepository.TreeBuildProgress())
    val treeBuildProgressFlow: StateFlow<PikoDriveRepository.TreeBuildProgress> = _treeBuildProgressFlow.asStateFlow()

    private val changeMarks = MutableStateFlow(ChangeMarks())
    val accountGeneration: Long get() = changeMarks.value.accountResetAt
    val currentSerial: Long get() = changeMarks.value.serial

    val syncFolderCache = PikoDriveRepository.SyncFolderCache()

    private val references = MutableStateFlow<Set<FolderReferenceSource>>(emptySet())
    private val dataAccount = MutableStateFlow<String?>(null)
    private val accountTransitionLock = PikoLock()
    private var accountBindingReady = true

    private val metadataObserveQueue = Channel<() -> Unit>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val metadataSink: DriveMetadataSink = object : DriveMetadataSink {
        override fun capture(owner: PikPakClient): DriveDetailObserver? =
            withPikoLock(treeStateLock) {
                if (ctx.clientManager.currentClient.value !== owner) {
                    null
                } else {
                    val generation = accountGeneration
                    val boundIndex = driveTreeIndex
                    object : BatchDriveDetailObserver {
                        private inline fun enqueueObservation(crossinline write: () -> Unit) {
                            metadataObserveQueue.trySend {
                                val stillValid = withPikoLock(treeStateLock) {
                                    ctx.clientManager.currentClient.value === owner &&
                                        generation == accountGeneration &&
                                        driveTreeIndex === boundIndex
                                }
                                if (stillValid) write()
                            }
                        }

                        override fun observe(originalFileId: String, detail: FileDetail) {
                            enqueueObservation { boundIndex.observeDetail(detail) }
                        }

                        override fun observeBatch(details: Collection<FileDetail>) {
                            if (details.isEmpty()) return
                            val snapshot = details.toList()
                            enqueueObservation { boundIndex.observeDetails(snapshot) }
                        }

                        override fun observeTranscodeSize(
                            gcid: String,
                            fileSizeBytes: Long,
                            mediaId: String,
                            streamSizeBytes: Long?,
                            unreadable: Boolean,
                        ) {
                            enqueueObservation {
                                boundIndex.observeTranscodeSize(gcid, fileSizeBytes, mediaId, streamSizeBytes, unreadable)
                            }
                        }
                    }
                }
            }

        override fun lookupFile(owner: PikPakClient, fileId: String): FileStat? =
            if (ctx.clientManager.currentClient.value !== owner) null else knownFile(fileId)

        override fun lookupContent(owner: PikPakClient, gcid: String, sizeBytes: Long): ContentMetadata? =
            if (ctx.clientManager.currentClient.value !== owner) null else ctx.listing.contentMetadataOf(gcid, sizeBytes)

        override fun lookupContentByFileId(owner: PikPakClient, fileId: String): ContentMetadata? =
            if (ctx.clientManager.currentClient.value !== owner) null else ctx.listing.contentMetadataByFileId(fileId)
    }

    val sortContextRevision: Long get() = driveTreeIndex.flow.value.revision

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun observeSortRevision(): Flow<Long> = treeBindingFlow.flatMapLatest { index ->
        index.flow.map { it.revision }.debounce(50L)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTreeCacheReady(): Flow<Long> = treeBindingFlow.flatMapLatest { index ->
        index.flow.map { it.revision }
    }

    fun sortContextFor(files: List<FileStat>, order: PikoFileSortOrder? = null): SortContext {
        val index = withPikoLock(treeStateLock) { driveTreeIndex }
        return index.sortContext(files, order)
    }

    fun registerFolderReferences(source: FolderReferenceSource) {
        references.update { it + source }
    }

    fun unregisterFolderReferences(source: FolderReferenceSource) {
        references.update { it - source }
    }

    internal fun referencedNodeIds(): Set<String> = references.value
        .flatMap { it.referencedPaths }
        .flatMapTo(HashSet()) { stack -> stack.mapNotNull { FolderIndex.nodeIdOf(it.id) } }

    internal fun locationFolderIds(): Set<String> = references.value
        .flatMap { it.retainedPaths }
        .flatMapTo(HashSet()) { stack -> stack.map { it.id } }

    internal fun normalizeLocations() {
        references.value.forEach { it.refreshFolderReferences() }
    }

    private fun resetChangeMarksForAccountSwitch() = changeMarks.update { marks ->
        val serial = marks.serial + 1
        ChangeMarks(serial = serial, accountResetAt = serial, treeInvalidatedAt = serial, unknownAt = serial)
    }

    internal fun markChanged(
        folders: Set<String>?,
        knownTouchedFolders: Set<String> = folders.orEmpty(),
        removedIds: Collection<String> = emptyList(),
        renamed: Pair<String, String>? = null,
        moved: Pair<Collection<String>, String>? = null,
        invalidatesWholeTree: Boolean = false,
    ) = changeMarks.update { marks ->
        val serial = marks.serial + 1
        val nextFolderAt = marks.folderAt + (folders.orEmpty() + knownTouchedFolders).associateWith { serial }
        val nextRemovedAt = if (removedIds.isEmpty()) marks.removedAt else marks.removedAt + removedIds.associateWith { serial }
        val nextRenamedAt = if (renamed == null) {
            marks.renamedAt - removedIds.toSet()
        } else {
            (marks.renamedAt - removedIds.toSet()) + (renamed.first to (serial to renamed.second))
        }
        val nextMovedAt = if (moved == null) {
            marks.movedAt - removedIds.toSet()
        } else {
            (marks.movedAt - removedIds.toSet()) + moved.first.associateWith { serial to moved.second }
        }
        ChangeMarks(
            serial = serial,
            accountResetAt = marks.accountResetAt,
            treeInvalidatedAt = if (invalidatesWholeTree) serial else marks.treeInvalidatedAt,
            unknownAt = if (folders == null) serial else marks.unknownAt,
            folderAt = nextFolderAt,
            patchedAt = marks.patchedAt,
            listedAt = marks.listedAt,
            removedAt = nextRemovedAt,
            renamedAt = nextRenamedAt,
            movedAt = nextMovedAt,
        )
    }

    private fun markCanaryPatched(folders: Set<String>) = changeMarks.update { marks ->
        val serial = marks.serial + 1
        ChangeMarks(
            serial = serial,
            accountResetAt = marks.accountResetAt,
            treeInvalidatedAt = marks.treeInvalidatedAt,
            unknownAt = marks.unknownAt,
            folderAt = marks.folderAt,
            patchedAt = if (folders.isEmpty()) marks.patchedAt else marks.patchedAt + folders.associateWith { serial },
            listedAt = marks.listedAt,
            removedAt = marks.removedAt,
            renamedAt = marks.renamedAt,
            movedAt = marks.movedAt,
        )
    }

    internal fun markFolderListed(folderId: String, reparentedFrom: Set<String>) = changeMarks.update { marks ->
        val serial = marks.serial + 1
        ChangeMarks(
            serial = serial,
            accountResetAt = marks.accountResetAt,
            treeInvalidatedAt = marks.treeInvalidatedAt,
            unknownAt = marks.unknownAt,
            folderAt = if (reparentedFrom.isEmpty()) marks.folderAt else marks.folderAt + reparentedFrom.associateWith { serial },
            patchedAt = marks.patchedAt,
            listedAt = marks.listedAt + (folderId to serial) + reparentedFrom.associateWith { serial },
            removedAt = marks.removedAt,
            renamedAt = marks.renamedAt,
            movedAt = marks.movedAt,
        )
    }

    internal fun unchangedSince(folderId: String, serial: Long): Boolean {
        val marks = changeMarks.value
        return marks.unknownAt <= serial &&
            (marks.folderAt[folderId] ?: 0) <= serial &&
            (marks.patchedAt[folderId] ?: 0) <= serial
    }

    fun enterAccount(account: String?) = withPikoLock(accountTransitionLock) {
        val retired = withPikoLock(treeStateLock) {
            val previous = dataAccount.value
            if (previous == account && accountBindingReady) return@withPikoLock null
            if (previous == null && accountBindingReady && ctx.treeDatabaseFactory == null) {
                dataAccount.value = account
                return@withPikoLock null
            }
            ctx.overlay.retireVaultOnAccountSwitchLocked(account)
            dataAccount.value = null
            accountBindingReady = false
            ctx.library.resetOnAccountSwitchLocked()
            val old = driveTreeIndex
            driveTreeIndex = DriveTreeIndex()
            resetChangeMarksForAccountSwitch()
            treeBindingFlow.value = driveTreeIndex
            lastCanaryProbeAt = null
            _treeBuildProgressFlow.value = PikoDriveRepository.TreeBuildProgress()
            treeSyncJob.update { current ->
                current?.second?.cancel()
                null
            }
            ctx.listing.resetOnAccountSwitchLocked()
            syncFolderCache.clear()
            folderIndex.clear()
            if (driveTreeIndex.isPersistent) populateDerivedFromTreeLocked()
            old
        } ?: return@withPikoLock
        runCatching { retired.close() }.logFailure(DRIVE_REPO_TAG, "Failed to close previous account tree index")
        val opened = runCatching {
            val database = account?.let { ctx.treeDatabaseFactory?.open(it) }
            try {
                DriveTreeIndex(
                    locallyCachedManifestIds = { ctx.overlay.parsedManifestIdsForAccount(account) },
                    database = database,
                    ownerAccount = account.orEmpty(),
                )
            } catch (failure: Throwable) {
                try {
                    database?.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }.logFailure(DRIVE_REPO_TAG, "Failed to open tree index for acct=${logAccountSlot(account)}; staying on in-memory fallback")
        opened.getOrNull()?.let { ready ->
            withPikoLock(treeStateLock) {
                driveTreeIndex = ready
                treeBindingFlow.value = ready
                dataAccount.value = account
                accountBindingReady = true
                if (ready.isPersistent) populateDerivedFromTreeLocked()
            }
        }
        ctx.backgroundScope.launch { DriveAnalysisCache.clear() }
        ctx.clearChangesJournal()
    }

    fun refreshActiveListings() {
        withPikoLock(treeStateLock) {
            ctx.listing.listingRequests.value = emptyMap()
            ctx.listing.listings.update { cache -> cache.mapValues { (_, listing) -> listing.expired() } }
        }
        ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(null))
        val asked = ctx.clientManager.currentClient.value ?: return
        val slot = logAccountSlot(asked.account)
        ctx.backgroundScope.launch {
            if (driveTreeIndex.isComplete) {
                probeRemoteChanges(asked).logFailure(DRIVE_REPO_TAG, "Post-wakeup incremental tree reconciliation failed (acct=$slot)")
            } else {
                runSuspendCatching { ensureTreeSync(asked).await() }
                    .logFailure(DRIVE_REPO_TAG, "Post-wakeup tree index build failed (acct=$slot)")
            }
        }
    }

    suspend fun locateFolder(fileId: String): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = ctx.requireClient()
            LocalLocation.of(fileId)?.let { local ->
                val mount = ctx.overlay.localMounts.find(fileId) ?: error("本地文件夹已取消挂载")
                val host = if (mount.parentFolderId.isEmpty()) {
                    listOf(PikoDriveRepository.ROOT_BREADCRUMB)
                } else {
                    locateFolder(mount.parentFolderId).getOrThrow() + PikoPathBreadcrumb(
                        mount.parentFolderId,
                        folderIndex.node(mount.parentFolderId)?.name ?: fetchDetail(mount.parentFolderId, asked).name,
                    )
                }
                val localParent = local.parent ?: return@runSuspendCatching host
                return@runSuspendCatching host + ctx.overlay.localMounts.pathTo(localParent.id).orEmpty()
            }
            require(!LocalLocation.isLocal(fileId)) { "本地位置无效" }
            val vaultEntryId = VaultEntry.entryIdOf(fileId)
            if (vaultEntryId != null) {
                val store = ctx.vaultAccessor()
                var parentId = driveTreeIndex.file(fileId)?.parentId
                    ?: store.folderOfEntry(vaultEntryId)
                    ?: ctx.overlay.vaultStars.folderOf(vaultEntryId)
                if (parentId == null) {
                    val candidates = buildList {
                        add("")
                        addAll(ctx.listing.childContents.vaultedFolders.value)
                        addAll(ctx.overlay.vaultTrees.flow.value.values.flatten())
                        addAll(ctx.overlay.vaultTrees.flow.value.keys)
                    }.distinct()
                    for (candidateId in candidates) {
                        runSuspendCatching {
                            val listing = ctx.listing.cachedListing(candidateId) ?: ctx.listing.fetchListing(candidateId, asked)
                            ctx.overlay.readAndRecordVaultEntries(candidateId, listing, store, asked)
                        }
                        parentId = driveTreeIndex.file(fileId)?.parentId ?: store.folderOfEntry(vaultEntryId)
                        if (parentId != null) break
                    }
                    if (parentId == null) {
                        val discovered = runSuspendCatching { asked.listFiles(parentId = "*", pageSize = 500) }
                            .getOrDefault(emptyList()).filter(VaultStore::looksLikeManifest)
                            .map { it.parentId }.distinct().filterNot { it in candidates }
                        for (candidateId in discovered) {
                            runSuspendCatching {
                                val listing = ctx.listing.fetchListing(candidateId, asked)
                                ctx.overlay.readAndRecordVaultEntries(candidateId, listing, store, asked)
                            }
                            parentId = driveTreeIndex.file(fileId)?.parentId ?: store.folderOfEntry(vaultEntryId)
                            if (parentId != null) break
                        }
                    }
                }
                val resolvedParent = parentId ?: error(
                    tr(
                        "未找到归档条目所在的文件夹",
                        "Containing folder of archived item not found",
                        "アーカイブ項目の保存先フォルダーが見つかりません",
                        "보관 항목의 상위 폴더를 찾을 수 없습니다",
                        "找不到歸檔項目所在的資料夾",
                    ),
                )
                if (resolvedParent.isEmpty()) return@runSuspendCatching listOf(PikoDriveRepository.ROOT_BREADCRUMB)
                val treeFolders = driveTreeIndex.flow.value.entries
                val folderNode = folderIndex.node(resolvedParent)?.takeUnless { it.provisional }
                val treeFolder = treeFolders[resolvedParent]?.takeIf { it.isFolder }
                val knownName = folderNode?.name ?: treeFolder?.name
                val knownUpper = folderNode?.parentId ?: treeFolder?.parentId
                if (knownName != null && knownUpper != null) {
                    val above = if (knownUpper.isEmpty()) {
                        listOf(PikoDriveRepository.ROOT_BREADCRUMB)
                    } else {
                        locateFolder(resolvedParent).getOrThrow()
                    }
                    return@runSuspendCatching above + PikoPathBreadcrumb(resolvedParent, knownName)
                }
                val detail = fetchDetail(resolvedParent, asked)
                val above = if (detail.parentId.isEmpty()) {
                    listOf(PikoDriveRepository.ROOT_BREADCRUMB)
                } else {
                    locateFolder(resolvedParent).getOrThrow()
                }
                return@runSuspendCatching above + PikoPathBreadcrumb(detail.id, detail.name)
            }
            val chain = ArrayDeque<PikoPathBreadcrumb>()
            val treeFolders = driveTreeIndex.flow.value.entries
            var parentId = knownParentOf(fileId)
                ?: driveTreeIndex.file(fileId)?.parentId
                ?: fetchDetail(fileId, asked).parentId
            repeat(MAX_LOCATE_DEPTH) {
                if (parentId.isEmpty()) return@runSuspendCatching listOf(PikoDriveRepository.ROOT_BREADCRUMB) + chain
                val known = folderIndex.node(parentId)?.takeUnless { it.provisional }
                val treeKnown = treeFolders[parentId]?.takeIf { it.isFolder }
                val resolvedName = known?.name ?: treeKnown?.name
                val knownParent = known?.parentId ?: treeKnown?.parentId
                if (resolvedName != null && knownParent != null) {
                    chain.addFirst(PikoPathBreadcrumb(parentId, resolvedName))
                    parentId = knownParent
                } else {
                    val folder = fetchDetail(parentId, asked)
                    chain.addFirst(PikoPathBreadcrumb(folder.id, folder.name))
                    parentId = folder.parentId
                }
            }
            error(tr("文件夹层级过深", "Folder hierarchy is too deep", "フォルダーの階層が深すぎます", "폴더 계층이 너무 깊습니다", "資料夾層級過深"))
        }
    }

    fun knownLocalPath(folderId: String): List<PikoPathBreadcrumb>? {
        val local = LocalLocation.of(folderId) ?: return null
        val mount = ctx.overlay.localMounts.findCached(folderId) ?: return null
        val host = folderIndex.pathTo(mount.parentFolderId, provisional = true) ?: return null
        return host + PikoPathBreadcrumb(mount.location.id, mount.fallbackName) + local.segments.mapIndexed { index, name ->
            PikoPathBreadcrumb(LocalLocation(local.mountId, local.segments.take(index + 1)).id, name)
        }
    }

    fun seedFolders(names: Map<String, String>, parents: Map<String, String>) {
        folderIndex.seed(names, parents)
    }

    fun knownParentOf(fileId: String): String? {
        folderIndex.node(fileId)?.takeUnless { it.provisional }?.parentId?.let { return it }
        driveTreeIndex.file(fileId)?.takeIf { isParentFreshInTree(it.parentId) }?.parentId?.let { return it }
        return ctx.listing.listings.value.entries
            .firstOrNull { (_, listing) -> !listing.stale && listing.files.any { it.id == fileId } }?.key
    }

    fun knownFile(fileId: String): FileStat? = knownConfirmedFile(fileId)?.let {
        ctx.mutations.projectOptimisticFiles(listOf(it), trash = it.trashed).firstOrNull()
    }

    internal fun knownConfirmedFile(fileId: String): FileStat? =
        ctx.listing.listings.value.values.asSequence().filter { !it.stale }
            .mapNotNull { it.files.firstOrNull { file -> file.id == fileId } }.firstOrNull()
            ?: driveTreeIndex.file(fileId)?.takeIf { isParentFreshInTree(it.parentId) }

    private fun isParentFreshInTree(parentId: String): Boolean {
        val state = driveTreeIndex.flow.value
        if (parentId in state.dirtyFolders) return false
        return parentId in state.listedFolders || state.complete
    }

    internal fun knownParentsOf(ids: Collection<String>): Set<String>? =
        ids.mapTo(HashSet()) { knownParentOf(it) ?: return null }

    suspend fun resolveFolderPath(
        names: List<String>,
        from: List<PikoPathBreadcrumb> = listOf(PikoDriveRepository.ROOT_BREADCRUMB),
    ): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val current = from.takeIf { it.library == null } ?: listOf(PikoDriveRepository.ROOT_BREADCRUMB)
            val shared = current.drop(1).zip(names).takeWhile { (crumb, name) -> crumb.name == name }.size
            val stack = current.take(1 + shared).toMutableList()
            for (name in names.drop(shared)) {
                val parentId = stack.last().id
                stack += findFolder(ctx.listing.subfolders(parentId).getOrThrow(), name)
                    ?: findFolder(ctx.listing.subfolders(parentId, fresh = true).getOrThrow(), name)
                    ?: throw PikoDriveRepository.FolderNotFoundException(name)
            }
            stack
        }
    }

    private fun findFolder(folders: List<PikoPathBreadcrumb>, name: String): PikoPathBreadcrumb? =
        folders.firstOrNull { it.name == name } ?: folders.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun knownDescendantFolders(rootFolderId: String): Set<String> =
        if (rootFolderId.isEmpty()) emptySet() else driveTreeIndex.descendantFolders(rootFolderId)

    fun resolveExistingSubfoldersInTree(
        rootParentId: String,
        relativeDirectories: List<String>,
    ): Map<String, String> {
        if (relativeDirectories.isEmpty()) return emptyMap()
        val state = driveTreeIndex.flow.value
        val resolved = LinkedHashMap<String, String>()
        resolved[""] = rootParentId
        for (dir in relativeDirectories.sortedBy { it.count { c -> c == '/' } }) {
            val parentPath = dir.substringBeforeLast('/', "")
            val parentId = resolved[parentPath] ?: continue
            if (parentId in state.dirtyFolders || (!state.complete && parentId !in state.listedFolders)) continue
            val name = dir.substringAfterLast('/')
            val match = state.childrenByParent[parentId].orEmpty().asSequence()
                .mapNotNull { state.entries[it] }
                .firstOrNull { it.isFolder && !it.trashed && it.name == name }
            if (match != null) {
                resolved[dir] = match.id
            }
        }
        resolved.remove("")
        return resolved
    }

    suspend fun getFileDetail(fileId: String): Result<FileDetail> = withContext(Dispatchers.Default) {
        runSuspendCatching { fetchDetail(fileId, ctx.requireClient()) }
    }

    internal suspend fun fetchDetail(fileId: String, asked: PikPakClient = ctx.requireClient()): FileDetail {
        require(!LocalLocation.isLocal(fileId)) { "本地文件不支持云端操作" }
        val observer = metadataSink.capture(asked)
        return asked.getFile(fileId).also { detail ->
            observer?.observe(fileId, detail)
            if (ctx.clientManager.currentClient.value === asked) {
                val isFolder = detail.kind == FileKind.FOLDER
                if (folderIndex.learned(detail.id, detail.name, detail.parentId, isFolder, detail.trashed)) {
                    normalizeLocations()
                }
            }
        }
    }

    suspend fun isFolderGone(folderId: String): Boolean =
        getFileDetail(folderId).fold(onSuccess = { it.trashed }, onFailure = { it is PikPakException }).also { gone ->
            if (gone) {
                driveTreeIndex.applyChange(DriveChange.Trashed(listOf(folderId), null))
                if (driveTreeIndex.isComplete) requestTreeRebuild()
            }
        }

    suspend fun warmDriveTree(): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { ensureTreeSync(ctx.requireClient()).await() }
    }

    fun requestTreeRebuild() {
        val asked = ctx.clientManager.currentClient.value ?: return
        ensureTreeSync(asked, forceRebuild = true)
    }

    fun onRootPullRefresh() {
        val asked = ctx.clientManager.currentClient.value ?: return
        if (!driveTreeIndex.isComplete) {
            ensureTreeSync(asked, forceRebuild = false)
            return
        }
        ctx.backgroundScope.launch {
            probeRemoteChanges(asked, force = true)
                .logFailure(DRIVE_REPO_TAG, "Pull-to-refresh tree reconciliation failed (acct=${logAccountSlot(asked.account)})")
        }
    }

    private suspend fun syncRemoteDelta(asked: PikPakClient, index: DriveTreeIndex): PikoDriveRepository.CanaryProbeResult =
        remoteDeltaMutex.withLock {
            val started = TimeSource.Monotonic.markNow()
            val acctSlot = logAccountSlot(asked.account)
            fun ensureCurrent() {
                if (ctx.clientManager.currentClient.value !== asked || driveTreeIndex !== index) {
                    throw CancellationException("Account changed")
                }
            }
            ensureCurrent()
            val cursor = index.syncCursor
            if (cursor == null) {
                PikoLog.i(DRIVE_REPO_TAG, "Delta sync skipped: missing sync cursor; falling back to full tree build (acct=$acctSlot)")
                ensureTreeSync(asked, forceRebuild = true).await()
                return@withLock PikoDriveRepository.CanaryProbeResult.FULL_REBUILD
            }
            val (serial, sequence) = withPikoLock(treeStateLock) {
                if (ctx.mutations.hasPendingOptimisticWrites) {
                    PikoLog.d(DRIVE_REPO_TAG, "Delta sync deferred: optimistic writes in flight (acct=$acctSlot)")
                    return@withLock PikoDriveRepository.CanaryProbeResult.UNCHANGED
                }
                changeMarks.value.serial to index.currentMutationSeq
            }
            var deltaPages = 0
            var deltaItems = 0
            index.beginDelta()
            try {
                val checkpoint = asked.consumeFileChanges(cursor) { page ->
                    ensureCurrent()
                    deltaPages++
                    deltaItems += page.files.size
                    index.appendDelta(ctx.overlay.sourceLedger.enrich(page.files))
                }
                val oldFolders = index.flow.value.entries
                val oldManifestFolders = index.flow.value.manifestsByFolder.keys
                val affected = withPikoLock(treeStateLock) {
                    ensureCurrent()
                    if (ctx.mutations.hasPendingOptimisticWrites || changeMarks.value.serial != serial || index.currentMutationSeq != sequence) {
                        PikoLog.d(
                            DRIVE_REPO_TAG,
                            "Delta sync discarded staged batch due to concurrent local write (acct=$acctSlot, pages=$deltaPages, items=$deltaItems)",
                        )
                        return@withLock PikoDriveRepository.CanaryProbeResult.UNCHANGED
                    }
                    index.commitDelta(checkpoint.syncTime).also { parents ->
                        folderIndex.removed(oldFolders.keys.filter { index.file(it) == null }, emptySet())
                        populateDerivedFromTreeLocked()
                        normalizeLocations()
                        markCanaryPatched(parents)
                        ctx.listing.listingRequests.update { it - parents }
                        ctx.listing.listings.update { cache ->
                            cache.mapValues { (id, listing) -> if (id in parents) listing.expired() else listing }
                        }
                    }
                }
                val store = ctx.overlay.vaultForClient(asked)
                val unresolved = index.flow.value.manifestsByFolder.keys.filter { index.vaultEntries(it) == null }
                for (parent in affected + unresolved) {
                    ensureCurrent()
                    val children = index.realChildren(parent).files
                    ctx.listing.rememberChildContents(parent, children, complete = true)
                    if (children.any(VaultStore::looksLikeManifest) || parent in oldManifestFolders) {
                        runSuspendCatching { ctx.overlay.readAndRecordVaultEntries(parent, children, store, asked) }
                            .logFailure(DRIVE_REPO_TAG, "Delta sync failed to read vault manifest in ${logFolder(parent)} (acct=$acctSlot)")
                    }
                }
                if (affected.isNotEmpty() || unresolved.isNotEmpty()) {
                    ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(affected + unresolved))
                }
                if (deltaItems > 0 || affected.isNotEmpty() || unresolved.isNotEmpty()) {
                    PikoLog.i(
                        DRIVE_REPO_TAG,
                        "Delta sync applied (acct=$acctSlot, pages=$deltaPages, items=$deltaItems, affectedFolders=${affected.size}, " +
                            "unresolvedManifestFolders=${unresolved.size}, cursorAdvanced=${checkpoint.syncTime != cursor}, elapsedMs=${started.elapsedNow().inWholeMilliseconds})",
                    )
                }
                if (affected.isEmpty()) {
                    PikoDriveRepository.CanaryProbeResult.UNCHANGED
                } else {
                    PikoDriveRepository.CanaryProbeResult.INCREMENTAL_PATCH
                }
            } catch (outdated: FileSyncVersionOutdatedException) {
                ensureCurrent()
                PikoLog.w(
                    DRIVE_REPO_TAG,
                    "Delta sync cursor expired; falling back to full tree rebuild (acct=$acctSlot, elapsedMs=${started.elapsedNow().inWholeMilliseconds})",
                    outdated,
                )
                ensureTreeSync(asked, forceRebuild = true).await()
                PikoDriveRepository.CanaryProbeResult.FULL_REBUILD
            } finally {
                index.abortDelta()
            }
        }

    suspend fun probeRemoteChanges(
        asked: PikPakClient = ctx.requireClient(),
        force: Boolean = false,
    ): Result<PikoDriveRepository.CanaryProbeResult> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val targetIndex = withPikoLock(treeStateLock) {
                if (ctx.clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
                driveTreeIndex
            }
            fun ensureGeneration() {
                if (ctx.clientManager.currentClient.value !== asked || driveTreeIndex !== targetIndex) {
                    throw CancellationException("Account generation changed")
                }
            }
            if (!force) {
                val last = lastCanaryProbeAt
                if (last != null && last.elapsedNow() < CANARY_PROBE_COOLDOWN) {
                    return@runSuspendCatching PikoDriveRepository.CanaryProbeResult.UNCHANGED
                }
            }
            lastCanaryProbeAt = TimeSource.Monotonic.markNow()
            _treeBuildProgressFlow.update { if (it.isBuilding) it else it.copy(isCheckingUpdates = true) }
            try {
                if (targetIndex.isPersistent && targetIndex.isComplete) {
                    return@runSuspendCatching syncRemoteDelta(asked, targetIndex)
                }
                var shouldFullRebuild = true
                for (attempt in 0 until 4) {
                    shouldFullRebuild = true
                    if (!targetIndex.isComplete) {
                        ensureTreeSync(asked).await()
                        return@runSuspendCatching PikoDriveRepository.CanaryProbeResult.FULL_REBUILD
                    }
                    val (probeStartSerial, probeStartSeq, state) = withPikoLock(treeStateLock) {
                        Triple(changeMarks.value.serial, targetIndex.currentMutationSeq, targetIndex.flow.value)
                    }
                    val quota = ctx.mutations.getQuota().getOrNull()?.quota
                    val usageBytes = quota?.usageBytes
                    val firstPage = asked.listFilesPaged(parentId = "*", pageSize = CANARY_WINDOW_SIZE)
                    ensureGeneration()
                    val prevUsage = state.lastUsageBytes
                    val fullSnapshotVisible = firstPage.nextPageToken.isEmpty()

                    val localCache = HashMap<String, FileStat?>()
                    fun preloadLocal(items: Collection<FileStat>) {
                        val missingIds = items.map { it.id }.filterNot { localCache.containsKey(it) }
                        if (missingIds.isEmpty()) return
                        val fetched = targetIndex.filesByIds(missingIds)
                        for (id in missingIds) localCache[id] = fetched[id]
                    }
                    fun localOf(id: String): FileStat? =
                        if (localCache.containsKey(id)) localCache[id] else targetIndex.file(id).also { localCache[id] = it }

                    fun matchesLocal(remote: FileStat): Boolean {
                        val local = localOf(remote.id) ?: return false
                        return local.parentId == remote.parentId &&
                            local.name == remote.name &&
                            local.modifiedTime == remote.modifiedTime &&
                            local.size == remote.size
                    }

                    val recent = ArrayList<FileStat>(firstPage.files.size)
                    recent.addAll(firstPage.files.filterNot { it.trashed })
                    preloadLocal(recent)
                    var nextToken = firstPage.nextPageToken
                    var pagesFetched = 1
                    var windowExceeded = false
                    if (!fullSnapshotVisible) {
                        while (nextToken.isNotEmpty() && pagesFetched < MAX_CANARY_PAGES) {
                            val tailUnchanged = firstPageOrTailUnchangedCount(recent, ::matchesLocal)
                            if (tailUnchanged >= CANARY_TAIL_SETTLED_COUNT) break
                            val more = asked.listFilesPaged(parentId = "*", pageSize = CANARY_WINDOW_SIZE, pageToken = nextToken)
                            ensureGeneration()
                            val filtered = more.files.filterNot { it.trashed }
                            preloadLocal(filtered)
                            recent.addAll(filtered)
                            nextToken = more.nextPageToken
                            pagesFetched++
                        }
                        if (nextToken.isNotEmpty() && firstPageOrTailUnchangedCount(recent, ::matchesLocal) == 0) {
                            windowExceeded = true
                        }
                    }

                    var changedFoldersFromScan: List<FileStat> = emptyList()
                    val rawChangedRecent = recent.filterNot(::matchesLocal)
                    val candidateReconcileParents = rawChangedRecent.mapNotNullTo(HashSet()) { item ->
                        item.parentId.takeIf { !item.isFolder }
                    }
                    val hasMissingLocalEntries = if (fullSnapshotVisible) {
                        val remoteIds = recent.mapTo(HashSet()) { it.id }
                        if (candidateReconcileParents.isEmpty()) {
                            targetIndex.hasPhysicalMissingFrom(remoteIds)
                        } else {
                            state.entries.values.any { entry ->
                                !VaultEntry.isVaulted(entry.id) &&
                                    entry.id !in remoteIds &&
                                    (entry.isFolder || entry.parentId !in candidateReconcileParents)
                            }
                        }
                    } else {
                        val remoteFolders = asked.listAllDriveFolders(pageSize = 500).filterNot { it.trashed }
                        ensureGeneration()
                        val remoteFolderIds = remoteFolders.mapTo(HashSet()) { it.id }
                        val localFoldersMissing = state.entries.values.any { entry ->
                            entry.id.isNotEmpty() && entry.isFolder && !VaultEntry.isVaulted(entry.id) && entry.id !in remoteFolderIds
                        }
                        if (!localFoldersMissing) {
                            for (folder in remoteFolders) {
                                if (!localCache.containsKey(folder.id)) localCache[folder.id] = state.entries[folder.id]
                            }
                            changedFoldersFromScan = remoteFolders.filterNot(::matchesLocal)
                        }
                        localFoldersMissing
                    }

                    val changedItems = (rawChangedRecent + changedFoldersFromScan).distinctBy { it.id }
                    val hasReconcilableDeletions = fullSnapshotVisible && candidateReconcileParents.isNotEmpty() &&
                        targetIndex.hasPhysicalMissingFrom(recent.mapTo(HashSet()) { it.id })
                    val localPhysicalMatchesQuota = !fullSnapshotVisible && usageBytes != null && prevUsage != null &&
                        usageBytes != prevUsage && targetIndex.totalPhysicalBytes() == usageBytes
                    if (!hasMissingLocalEntries && !hasReconcilableDeletions && changedItems.isEmpty() &&
                        (prevUsage == null || usageBytes == null || usageBytes == prevUsage || localPhysicalMatchesQuota)
                    ) {
                        val publishedUnchanged = withPikoLock(treeStateLock) {
                            ensureGeneration()
                            if (changeMarks.value.serial != probeStartSerial || targetIndex.currentMutationSeq != probeStartSeq) {
                                false
                            } else {
                                if ((prevUsage == null || localPhysicalMatchesQuota) && usageBytes != null) {
                                    targetIndex.setLastUsageBytes(usageBytes)
                                }
                                true
                            }
                        }
                        if (!publishedUnchanged) {
                            shouldFullRebuild = false
                            continue
                        }
                        return@runSuspendCatching PikoDriveRepository.CanaryProbeResult.UNCHANGED
                    }

                    if (!hasMissingLocalEntries && !windowExceeded && prevUsage != null && usageBytes != null && changedItems.isNotEmpty()) {
                        val patchStarted = TimeSource.Monotonic.markNow()
                        val addedBytes = changedItems.sumOf { item ->
                            if (item.isFolder) 0L else item.sizeBytes - (localOf(item.id)?.sizeBytes ?: 0L)
                        }
                        if (!hasReconcilableDeletions && usageBytes >= prevUsage && usageBytes - prevUsage == addedBytes) {
                            val enriched = ctx.overlay.sourceLedger.enrich(changedItems)
                            val affectedParents = HashSet<String>()
                            val manifestFolders = HashSet<String>()
                            var indexUpdated = false
                            val publishedPatch = withPikoLock(treeStateLock) {
                                ensureGeneration()
                                if (changeMarks.value.serial != probeStartSerial || targetIndex.currentMutationSeq != probeStartSeq) {
                                    return@withPikoLock false
                                }
                                var learned = false
                                for (item in enriched) {
                                    affectedParents += item.parentId
                                    localOf(item.id)?.parentId?.let(affectedParents::add)
                                    if (VaultStore.looksLikeManifest(item)) {
                                        manifestFolders += item.parentId
                                        localOf(item.id)?.parentId?.let(manifestFolders::add)
                                    }
                                    if (folderIndex.learned(item.id, item.name, item.parentId, item.isFolder, item.trashed)) {
                                        learned = true
                                    }
                                }
                                markCanaryPatched(affectedParents)
                                ctx.listing.listingRequests.update { it - affectedParents }
                                targetIndex.applyCanaryPatch(enriched, usageBytes)
                                for (folderId in affectedParents) {
                                    val children = targetIndex.realChildren(folderId).files
                                    ctx.listing.rememberChildContents(folderId, children, complete = true)
                                }
                                ctx.listing.listings.update { cache ->
                                    cache.mapValues { (id, listing) -> if (id in affectedParents) listing.expired() else listing }
                                }
                                indexUpdated = learned
                                true
                            }
                            if (!publishedPatch) {
                                shouldFullRebuild = false
                                continue
                            }
                            if (indexUpdated) normalizeLocations()
                            val store = ctx.vaultAccessor()
                            for (folderId in manifestFolders) {
                                val children = targetIndex.realChildren(folderId).files
                                runSuspendCatching {
                                    ctx.overlay.readAndRecordVaultEntries(folderId, children, store, asked, logFailure = true, logTagSuffix = " (canary patch)")
                                }
                            }
                            ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(affectedParents))
                            PikoLog.i(
                                DRIVE_REPO_TAG,
                                "Canary incremental patch applied (acct=${logAccountSlot(asked.account)}, items=${enriched.size}, " +
                                    "folders=${affectedParents.size}, elapsedMs=${patchStarted.elapsedNow().inWholeMilliseconds})",
                            )
                            return@runSuspendCatching PikoDriveRepository.CanaryProbeResult.INCREMENTAL_PATCH
                        }

                        val candidateParents = LinkedHashSet<String>()
                        for (item in changedItems) {
                            if (!item.isFolder) {
                                candidateParents += item.parentId
                                localOf(item.id)?.parentId?.let(candidateParents::add)
                            } else {
                                localOf(item.id)?.parentId?.takeIf { it != item.parentId }?.let(candidateParents::add)
                            }
                        }
                        if (candidateParents.isNotEmpty() && candidateParents.size <= MAX_INCREMENTAL_RECONCILE_FOLDERS) {
                            val permits = Semaphore(6)
                            val reconciledByFolder = coroutineScope {
                                candidateParents.map { folderId ->
                                    async {
                                        permits.withPermit {
                                            ensureGeneration()
                                            folderId to ctx.overlay.sourceLedger.enrich(asked.listFiles(folderId).filterNot { it.trashed })
                                        }
                                    }
                                }.awaitAll().toMap()
                            }
                            ensureGeneration()
                            var netBytesDelta = 0L
                            for ((folderId, liveChildren) in reconciledByFolder) {
                                val localPhysicalBytes = targetIndex.realChildren(folderId).files
                                    .sumOf { if (it.isFolder) 0L else it.sizeBytes }
                                val remotePhysicalBytes = liveChildren
                                    .sumOf { if (it.isFolder) 0L else it.sizeBytes }
                                netBytesDelta += (remotePhysicalBytes - localPhysicalBytes)
                            }
                            for (item in changedItems) {
                                if (!item.isFolder && item.parentId !in reconciledByFolder) {
                                    val prev = localOf(item.id)
                                    val prevBytes = if (prev != null && prev.parentId !in reconciledByFolder) prev.sizeBytes else 0L
                                    netBytesDelta += (item.sizeBytes - prevBytes)
                                }
                            }
                            if (prevUsage + netBytesDelta == usageBytes) {
                                val enrichedChanged = ctx.overlay.sourceLedger.enrich(changedItems)
                                val affectedParents = HashSet<String>(reconciledByFolder.keys)
                                val manifestFolders = HashSet<String>()
                                var indexUpdated = false
                                val publishedReconciled = withPikoLock(treeStateLock) {
                                    ensureGeneration()
                                    if (changeMarks.value.serial != probeStartSerial || targetIndex.currentMutationSeq != probeStartSeq) {
                                        return@withPikoLock false
                                    }
                                    var learned = false
                                    for (item in enrichedChanged) {
                                        affectedParents += item.parentId
                                        localOf(item.id)?.parentId?.let(affectedParents::add)
                                        if (VaultStore.looksLikeManifest(item)) {
                                            manifestFolders += item.parentId
                                        }
                                        if (folderIndex.learned(item.id, item.name, item.parentId, item.isFolder, item.trashed)) {
                                            learned = true
                                        }
                                    }
                                    for ((folderId, liveChildren) in reconciledByFolder) {
                                        if (liveChildren.any(VaultStore::looksLikeManifest) || targetIndex.flow.value.manifestsByFolder.containsKey(folderId)) {
                                            manifestFolders += folderId
                                        }
                                        for (child in liveChildren) {
                                            if (child.isFolder && folderIndex.learned(child.id, child.name, folderId, isFolder = true, trashed = child.trashed)) {
                                                learned = true
                                            }
                                        }
                                    }
                                    val outsideItems = enrichedChanged.filter { it.isFolder || it.parentId !in reconciledByFolder }
                                    markCanaryPatched(affectedParents)
                                    ctx.listing.listingRequests.update { it - affectedParents }
                                    if (outsideItems.isNotEmpty()) {
                                        targetIndex.applyCanaryPatch(outsideItems, usageBytes = null)
                                    }
                                    for ((folderId, liveChildren) in reconciledByFolder) {
                                        targetIndex.putFolderListing(folderId, liveChildren, complete = true)
                                        ctx.listing.rememberListing(folderId, liveChildren)
                                        ctx.listing.rememberChildContents(folderId, liveChildren, complete = true)
                                    }
                                    targetIndex.setLastUsageBytes(usageBytes)
                                    ctx.listing.listings.update { cache ->
                                        cache.mapValues { (id, listing) ->
                                            if (id in affectedParents && id !in reconciledByFolder) listing.expired() else listing
                                        }
                                    }
                                    indexUpdated = learned
                                    true
                                }
                                if (!publishedReconciled) {
                                    shouldFullRebuild = false
                                    continue
                                }
                                if (indexUpdated) normalizeLocations()
                                val store = ctx.vaultAccessor()
                                for (folderId in manifestFolders) {
                                    val children = reconciledByFolder[folderId] ?: targetIndex.realChildren(folderId).files
                                    runSuspendCatching {
                                        ctx.overlay.readAndRecordVaultEntries(folderId, children, store, asked, logFailure = true, logTagSuffix = " (canary reconcile)")
                                    }
                                }
                                ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(affectedParents))
                                PikoLog.i(
                                    DRIVE_REPO_TAG,
                                    "Canary folder reconciliation applied (acct=${logAccountSlot(asked.account)}, items=${enrichedChanged.size}, " +
                                        "reconciledFolders=${reconciledByFolder.size}, elapsedMs=${patchStarted.elapsedNow().inWholeMilliseconds})",
                                )
                                return@runSuspendCatching PikoDriveRepository.CanaryProbeResult.INCREMENTAL_PATCH
                            }
                        }
                    }
                    break
                }

                if (!shouldFullRebuild) {
                    return@runSuspendCatching PikoDriveRepository.CanaryProbeResult.UNCHANGED
                }
                ensureTreeSync(asked, forceRebuild = true).await()
                PikoDriveRepository.CanaryProbeResult.FULL_REBUILD
            } finally {
                _treeBuildProgressFlow.update { it.copy(isCheckingUpdates = false) }
            }
        }
    }

    internal suspend fun restoreTreeFromCache(asked: PikPakClient): Boolean = withContext(Dispatchers.Default) {
        val account = asked.account.takeIf { it.isNotEmpty() } ?: return@withContext false
        val (index, generation) = withPikoLock(treeStateLock) { driveTreeIndex to accountGeneration }
        fun isCurrent() = ctx.clientManager.currentClient.value === asked && accountGeneration == generation && driveTreeIndex === index
        if (index.isPersistent) {
            if (!isCurrent()) return@withContext false
            val imported = !index.isComplete && migrateLegacyTreeCache(index, account, ctx.cacheStore)
            if ((imported || index.isComplete) && isCurrent()) {
                val manifests = index.flow.value.manifestsByFolder
                val unresolved = manifests.filterKeys { index.vaultEntries(it) == null }
                if (unresolved.isNotEmpty()) {
                    val vaultStore = ctx.overlay.vaultForClient(asked)
                    vaultStore.awaitLoaded()
                    val starredIds = ctx.overlay.vaultStars.flow.value.values.asSequence()
                        .filter { it.starred }
                        .mapTo(HashSet()) { it.entryId }
                    for ((folderId, manifest) in unresolved) {
                        if (!isCurrent()) return@withContext false
                        val entries = vaultStore.parsedFlow.value[manifest.id] ?: continue
                        if (index.putVaultEntries(folderId, entries, expectedManifestId = manifest.id, requireMatch = true, starredEntryIds = starredIds)) {
                            ctx.listing.childContents.markVaulted(folderId, entries.any { it.isArchived })
                            ctx.listing.childContents.rememberCover(folderId, entries.coverGcid().orEmpty())
                        }
                    }
                }
            }
            return@withContext withPikoLock(treeStateLock) {
                if (!isCurrent()) return@withPikoLock false
                if (populateDerivedFromTreeLocked()) normalizeLocations()
                index.isComplete
            }
        }
        false
    }

    private fun populateDerivedFromTreeLocked(): Boolean {
        val state = driveTreeIndex.flow.value
        var indexUpdated = false
        for (entry in state.entries.values) {
            if (!entry.isFolder || VaultEntry.isVaulted(entry.id)) continue
            if (folderIndex.learned(entry.id, entry.name, entry.parentId, entry.isFolder, entry.trashed)) {
                indexUpdated = true
            }
        }
        return indexUpdated
    }

    internal fun ensureTreeSync(asked: PikPakClient, forceRebuild: Boolean = false): Deferred<Unit> {
        while (true) {
            val existing = treeSyncJob.value
            if (!forceRebuild && existing != null && existing.first === asked && existing.second.isActive) {
                return existing.second
            }
            val token = Any()
            val (index, generation) = withPikoLock(treeStateLock) { driveTreeIndex to accountGeneration }
            fun isCurrent() = ctx.clientManager.currentClient.value === asked && accountGeneration == generation && driveTreeIndex === index
            val store = ctx.overlay.vaultForClient(asked)
            val isShadowRebuild = index.isComplete
            val job = ctx.backgroundScope.async(start = CoroutineStart.LAZY) {
                existing?.second?.cancelAndJoin()
                treeBuildMutex.withLock {
                    if (!isCurrent()) return@withLock
                    store.awaitLoaded()
                    if (!isCurrent()) return@withLock
                    if (index.isPersistent) {
                        val started = TimeSource.Monotonic.markNow()
                        val acctSlot = logAccountSlot(asked.account)
                        val initialResume = if (!isShadowRebuild && !forceRebuild) index.resumableCandidate() else null
                        if (initialResume == null && forceRebuild) {
                            index.resumableCandidate()?.let { stale -> index.abortCandidate(stale.generation) }
                        }
                        val (initialCandidate, serial) = withPikoLock(treeStateLock) {
                            if (!isCurrent()) return@withLock
                            activeBuildToken = token
                            val gen = initialResume?.generation ?: index.beginCandidate()
                            _treeBuildProgressFlow.value = PikoDriveRepository.TreeBuildProgress(
                                isBuilding = true,
                                isInitialBlocking = !isShadowRebuild,
                                foldersRead = initialResume?.foldersRead ?: 0,
                                filesRead = initialResume?.filesRead ?: 0,
                            )
                            gen to changeMarks.value.serial
                        }
                        var candidate = initialCandidate
                        var committed = false
                        var abortOnExit = isShadowRebuild
                        var hasSavedCheckpoint = initialResume != null
                        var resumedFromCheckpoint = initialResume != null
                        if (initialResume != null) {
                            PikoLog.i(
                                DRIVE_REPO_TAG,
                                "Resuming interrupted SQLite WAL tree build (acct=$acctSlot, folders=${initialResume.foldersRead}, files=${initialResume.filesRead})",
                            )
                        }
                        try {
                            var folderCount = initialResume?.foldersRead ?: 0
                            var fileCount = initialResume?.filesRead ?: 0
                            val manifestByFolder = LinkedHashMap<String, MutableList<FileStat>>()
                            if (initialResume != null) {
                                for (manifest in index.candidateManifests(candidate)) {
                                    manifestByFolder.getOrPut(manifest.parentId) { mutableListOf() }.add(manifest)
                                }
                            }
                            val resolvedVaultMeta = ArrayList<Pair<String, List<VaultEntry>>>()
                            var totalManifestFolders = 0
                            var firstSyncTime = initialResume?.firstSyncTime.orEmpty()
                            var lastSyncTime = ""
                            var syncCursor: String? = null
                            coroutineScope {
                                val prefetchQueue = Channel<FileStat>(Channel.UNLIMITED)
                                val queuedManifestIds = HashSet<String>()
                                val prefetchCountLock = PikoLock()
                                var prefetchedCount = 0
                                fun incrementPrefetchedCount(): Int = withPikoLock(prefetchCountLock) { ++prefetchedCount }
                                val prefetchWorkers = List(MANIFEST_WARMUP_CONCURRENCY) {
                                    launch {
                                        withApiPriority(ApiRequestPriority.BACKGROUND) {
                                            for (manifestStat in prefetchQueue) {
                                                if (!isCurrent()) break
                                                PikoWorkloadGovernor.Shared.awaitNoUrgentPlayback()
                                                if (!isCurrent()) break
                                                store.prefetchManifest(manifestStat)
                                                val done = incrementPrefetchedCount()
                                                _treeBuildProgressFlow.update { cur ->
                                                    if (cur.manifestsTotal > 0) {
                                                        cur.copy(manifestsRead = done.coerceAtMost(cur.manifestsTotal))
                                                    } else {
                                                        cur
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                var pagesFetchedInPass = 0
                                suspend fun runPaginatedIngest(startToken: String) {
                                    pagesFetchedInPass = 0
                                    val pageChannel = Channel<FileListPage>(capacity = 4)
                                    coroutineScope {
                                        val producer = launch {
                                            withApiPriority(ApiRequestPriority.BACKGROUND) {
                                                try {
                                                    var nextToken = startToken
                                                    val seenTokens = HashSet<String>()
                                                    if (nextToken.isNotEmpty()) seenTokens.add(nextToken)
                                                    do {
                                                        currentCoroutineContext().ensureActive()
                                                        if (!isCurrent()) throw CancellationException("Account changed")
                                                        val page = asked.listFileChangesPage(sinceSyncTime = "", pageToken = nextToken, pageSize = 500)
                                                        pagesFetchedInPass++
                                                        pageChannel.send(page)
                                                        nextToken = page.nextPageToken
                                                        check(nextToken.isEmpty() || seenTokens.add(nextToken)) { "Repeated file sync page token" }
                                                    } while (nextToken.isNotEmpty())
                                                    pageChannel.close()
                                                } catch (t: Throwable) {
                                                    pageChannel.close(t)
                                                    throw t
                                                }
                                            }
                                        }
                                        try {
                                            for (page in pageChannel) {
                                                currentCoroutineContext().ensureActive()
                                                if (!isCurrent()) throw CancellationException("Account changed")
                                                if (firstSyncTime.isEmpty() && page.syncTime.isNotBlank()) firstSyncTime = page.syncTime
                                                if (page.syncTime.isNotBlank()) lastSyncTime = page.syncTime
                                                val enriched = ctx.overlay.sourceLedger.enrich(page.files.filterNot { it.trashed })
                                                if (!isCurrent()) throw CancellationException("Account changed")
                                                var pageFolders = 0
                                                var pageFiles = 0
                                                for (entry in enriched) {
                                                    if (entry.isFolder) pageFolders++ else pageFiles++
                                                }
                                                val nextFolderCount = folderCount + pageFolders
                                                val nextFileCount = fileCount + pageFiles
                                                val checkpoint = if (!isShadowRebuild && page.nextPageToken.isNotBlank()) {
                                                    DriveTreeIndex.CandidateResumeCheckpoint(
                                                        generation = candidate,
                                                        pageToken = page.nextPageToken,
                                                        firstSyncTime = firstSyncTime,
                                                        foldersRead = nextFolderCount,
                                                        filesRead = nextFileCount,
                                                    )
                                                } else {
                                                    null
                                                }
                                                if (isShadowRebuild) {
                                                    PikoWorkloadGovernor.Shared.awaitNoUrgentPlayback()
                                                    if (!isCurrent()) throw CancellationException("Account changed")
                                                }
                                                index.appendCandidate(candidate, enriched, checkpoint)
                                                if (checkpoint != null) hasSavedCheckpoint = true
                                                var learnedFolders = false
                                                val touchedManifestFolders = ArrayList<String>()
                                                withPikoLock(treeStateLock) {
                                                    if (!isCurrent()) throw CancellationException("Account changed")
                                                    folderCount = nextFolderCount
                                                    fileCount = nextFileCount
                                                    for (entry in enriched) {
                                                        if (entry.isFolder && !isShadowRebuild &&
                                                            folderIndex.learned(entry.id, entry.name, entry.parentId, isFolder = true, trashed = entry.trashed)
                                                        ) {
                                                            learnedFolders = true
                                                        }
                                                        if (VaultStore.looksLikeManifest(entry)) {
                                                            manifestByFolder.getOrPut(entry.parentId) { mutableListOf() }.add(entry)
                                                            touchedManifestFolders += entry.parentId
                                                        }
                                                    }
                                                    _treeBuildProgressFlow.update {
                                                        it.copy(
                                                            foldersRead = folderCount,
                                                            filesRead = fileCount,
                                                            manifestsTotal = manifestByFolder.size,
                                                        )
                                                    }
                                                    if (learnedFolders) normalizeLocations()
                                                }
                                                for (parentId in touchedManifestFolders) {
                                                    val candidates = manifestByFolder[parentId] ?: continue
                                                    val winner = store.winnerOf(candidates) ?: continue
                                                    val added = withPikoLock(prefetchCountLock) { queuedManifestIds.add(winner.id) }
                                                    if (!added) continue
                                                    if (store.cachedCommitted(listOf(winner)) != null) {
                                                        val done = incrementPrefetchedCount()
                                                        _treeBuildProgressFlow.update { cur ->
                                                            cur.copy(manifestsRead = done.coerceAtMost(cur.manifestsTotal.coerceAtLeast(done)))
                                                        }
                                                    } else {
                                                        prefetchQueue.trySend(winner)
                                                    }
                                                }
                                            }
                                            producer.join()
                                        } finally {
                                            producer.cancel()
                                        }
                                    }
                                }
                                try {
                                    if (initialResume != null) {
                                        try {
                                            runPaginatedIngest(initialResume.pageToken)
                                        } catch (err: Exception) {
                                            if (err is CancellationException || !isCurrent()) throw err
                                            if (isResumedCheckpointRejected(err, pagesFetchedInPass)) {
                                                PikoLog.i(
                                                    DRIVE_REPO_TAG,
                                                    "Saved tree build checkpoint rejected by server on first resumed page; restarting full scan (acct=$acctSlot, error=${err::class.simpleName})",
                                                )
                                                index.abortCandidate(candidate)
                                                candidate = index.beginCandidate()
                                                hasSavedCheckpoint = false
                                                resumedFromCheckpoint = false
                                                folderCount = 0
                                                fileCount = 0
                                                firstSyncTime = ""
                                                lastSyncTime = ""
                                                manifestByFolder.clear()
                                                withPikoLock(prefetchCountLock) {
                                                    prefetchedCount = 0
                                                    queuedManifestIds.clear()
                                                }
                                                _treeBuildProgressFlow.update {
                                                    it.copy(foldersRead = 0, filesRead = 0, manifestsTotal = 0, manifestsRead = 0)
                                                }
                                                runPaginatedIngest("")
                                            } else {
                                                throw err
                                            }
                                        }
                                    } else {
                                        runPaginatedIngest("")
                                    }
                                    syncCursor = (if (resumedFromCheckpoint && firstSyncTime.isNotBlank()) firstSyncTime else lastSyncTime)
                                        .takeIf { it.isNotBlank() }
                                } finally {
                                    prefetchQueue.close()
                                }
                                prefetchWorkers.forEach { it.join() }

                                totalManifestFolders = manifestByFolder.size
                                var doneManifestFolders = 0
                                if (totalManifestFolders > 0) {
                                    _treeBuildProgressFlow.update {
                                        it.copy(manifestsTotal = totalManifestFolders, manifestsRead = doneManifestFolders)
                                    }
                                }
                                val uncachedManifestFolders = ArrayList<Pair<String, List<FileStat>>>()
                                val readyBatch = ArrayList<Pair<String, List<VaultEntry>>>(32)
                                val resolvedVaultLock = PikoLock()
                                for ((folderId, manifests) in manifestByFolder) {
                                    if (!isCurrent()) throw CancellationException("Account changed")
                                    val cachedEntries = store.cachedCommitted(manifests)
                                    if (cachedEntries != null) {
                                        readyBatch += folderId to cachedEntries
                                        resolvedVaultMeta += folderId to cachedEntries
                                        doneManifestFolders++
                                        if (readyBatch.size >= 32) {
                                            index.appendCandidateVaultBatch(candidate, readyBatch)
                                            readyBatch.clear()
                                            _treeBuildProgressFlow.update { it.copy(manifestsRead = doneManifestFolders) }
                                        }
                                    } else {
                                        uncachedManifestFolders += folderId to manifests
                                    }
                                }
                                if (readyBatch.isNotEmpty()) {
                                    index.appendCandidateVaultBatch(candidate, readyBatch)
                                    readyBatch.clear()
                                    _treeBuildProgressFlow.update { it.copy(manifestsRead = doneManifestFolders) }
                                }
                                if (uncachedManifestFolders.isNotEmpty()) {
                                    val manifestPermits = Semaphore(MANIFEST_WARMUP_CONCURRENCY)
                                    val fetched = uncachedManifestFolders.map { (folderId, manifests) ->
                                        async {
                                            withApiPriority(ApiRequestPriority.BACKGROUND) {
                                                manifestPermits.withPermit {
                                                    if (!isCurrent()) throw CancellationException("Account changed")
                                                    PikoWorkloadGovernor.Shared.awaitNoUrgentPlayback()
                                                    if (!isCurrent()) throw CancellationException("Account changed")
                                                    val entries = store.read(folderId, manifests).getOrElse { throw it }
                                                    withPikoLock(resolvedVaultLock) {
                                                        doneManifestFolders++
                                                        _treeBuildProgressFlow.update { it.copy(manifestsRead = doneManifestFolders) }
                                                    }
                                                    folderId to entries
                                                }
                                            }
                                        }
                                    }.awaitAll()
                                    for (chunk in fetched.chunked(32)) {
                                        if (!isCurrent()) throw CancellationException("Account changed")
                                        index.appendCandidateVaultBatch(candidate, chunk)
                                        resolvedVaultMeta += chunk
                                    }
                                }
                            }
                            currentCoroutineContext().ensureActive()
                            val preInvalidated = withPikoLock(treeStateLock) {
                                if (!isCurrent()) return@withLock
                                changeMarks.value.treeInvalidatedAt > serial
                            }
                            if (preInvalidated) {
                                abortOnExit = true
                                PikoLog.d(DRIVE_REPO_TAG, "SQLite tree build superseded by concurrent tree invalidation; scheduling rebuild (acct=$acctSlot)")
                                requestTreeRebuild()
                                return@withLock
                            }
                            val changed = index.commitCandidate(
                                candidate,
                                ctx.mutations.quotaFlow.value?.quota?.usageBytes ?: index.lastUsageBytes,
                                Clock.System.now().toEpochMilliseconds(),
                                syncCursor = syncCursor,
                            )
                            committed = true
                            var committedSuccessfully = false
                            withPikoLock(treeStateLock) {
                                if (!isCurrent()) return@withPikoLock
                                if (changeMarks.value.treeInvalidatedAt > serial) {
                                    PikoLog.d(DRIVE_REPO_TAG, "SQLite tree commit superseded by concurrent tree invalidation; scheduling rebuild (acct=$acctSlot)")
                                    requestTreeRebuild()
                                    return@withPikoLock
                                }
                                committedSuccessfully = true
                                for ((folderId, entries) in resolvedVaultMeta) {
                                    ctx.overlay.vaultStars.reconcileFolder(folderId, entries, expectedAccount = ctx.currentAccount)
                                    ctx.listing.childContents.markVaulted(folderId, entries.any { it.isArchived })
                                    ctx.listing.childContents.rememberCover(folderId, entries.coverGcid().orEmpty())
                                }
                                if (populateDerivedFromTreeLocked()) normalizeLocations()
                                if (changed.isNotEmpty()) {
                                    markChanged(changed)
                                    ctx.listing.listingRequests.update { it - changed }
                                    ctx.listing.listings.update { cache ->
                                        cache.mapValues { (id, listing) -> if (id in changed) listing.expired() else listing }
                                    }
                                    ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(changed))
                                }
                            }
                            if (committedSuccessfully) {
                                val readyState = index.flow.value
                                PikoLog.i(
                                    DRIVE_REPO_TAG,
                                    "SQLite WAL tree index ready (acct=$acctSlot, folders=$folderCount, files=$fileCount, " +
                                        "manifestFolders=$totalManifestFolders, complete=${readyState.complete}, dirtyFolders=${readyState.dirtyFolders.size}, " +
                                        "hasCursor=${syncCursor != null}, shadowRebuild=$isShadowRebuild, resumed=$resumedFromCheckpoint, elapsedMs=${started.elapsedNow().inWholeMilliseconds})",
                                )
                            }
                        } catch (failure: Exception) {
                            if (isCurrent()) throw failure
                        } finally {
                            val preserveCheckpoint = !abortOnExit && hasSavedCheckpoint && !isShadowRebuild
                            if (!committed && !preserveCheckpoint) index.abortCandidate(candidate)
                            if (index.isComplete) index.pruneMutationHistory(index.currentMutationSeq)
                            withPikoLock(treeStateLock) {
                                if (isCurrent() && activeBuildToken === token) {
                                    activeBuildToken = null
                                    _treeBuildProgressFlow.update {
                                        it.copy(isBuilding = false, isInitialBlocking = false, manifestsRead = 0, manifestsTotal = 0)
                                    }
                                }
                            }
                        }
                        return@withLock
                    }

                    val acctSlot = logAccountSlot(asked.account)
                    val permit = PikoMemoryGovernor.Shared.acquire(MemoryWorkKind.TreeBuild, 0)
                    withPikoLock(treeStateLock) {
                        if (!isCurrent()) {
                            permit.release()
                            return@withLock
                        }
                        activeBuildToken = token
                    }
                    val started = TimeSource.Monotonic.markNow()
                    val (serial, historyToken) = withPikoLock(treeStateLock) {
                        changeMarks.value.serial to index.retainMutationHistory()
                    }
                    val rebuildStartSeq = historyToken.sequence
                    withPikoLock(treeStateLock) {
                        if (isCurrent()) {
                            _treeBuildProgressFlow.value = PikoDriveRepository.TreeBuildProgress(
                                isBuilding = true,
                                isInitialBlocking = !isShadowRebuild,
                                foldersRead = 0,
                                filesRead = 0,
                            )
                        }
                    }
                    try {
                        val folders = withApiPriority(ApiRequestPriority.BACKGROUND) { asked.listAllDriveFolders(pageSize = 500) }
                        if (!isCurrent()) throw CancellationException("Account changed")
                        val activeFolderCount = folders.count { !it.trashed }
                        withPikoLock(treeStateLock) {
                            if (isCurrent()) _treeBuildProgressFlow.update { it.copy(foldersRead = activeFolderCount) }
                        }
                        if (!isShadowRebuild) {
                            var indexUpdated = false
                            withPikoLock(treeStateLock) {
                                if (isCurrent()) {
                                    index.putFolders(folders)
                                    for (folder in folders) {
                                        if (folderIndex.learned(folder.id, folder.name, folder.parentId, isFolder = true, trashed = folder.trashed)) {
                                            indexUpdated = true
                                        }
                                    }
                                }
                            }
                            withPikoLock(treeStateLock) {
                                if (isCurrent() && indexUpdated) normalizeLocations()
                            }
                        }

                        var fileCount = 0
                        val allFiles = ArrayList<FileStat>()
                        val manifestByFolder = LinkedHashMap<String, MutableList<FileStat>>()
                        withApiPriority(ApiRequestPriority.BACKGROUND) {
                            asked.listAllDriveEntriesFlow(pageSize = 500, extraFilters = mapOf(FileFilter.kind(FileKind.FILE))).collect { page ->
                                if (!isCurrent()) throw CancellationException("Account changed")
                                val activePage = page.filterNot { it.trashed }
                                fileCount += activePage.size
                                val enriched = withPikoLock(treeStateLock) {
                                    if (!isCurrent()) throw CancellationException("Account changed")
                                    _treeBuildProgressFlow.update { it.copy(filesRead = fileCount) }
                                    ctx.overlay.sourceLedger.enrich(activePage)
                                }
                                allFiles.addAll(enriched)
                                if (!isShadowRebuild) {
                                    withPikoLock(treeStateLock) {
                                        if (isCurrent()) index.putFiles(enriched)
                                    }
                                }
                                for (entry in enriched) {
                                    if (VaultStore.looksLikeManifest(entry)) {
                                        manifestByFolder.getOrPut(entry.parentId) { mutableListOf() }.add(entry)
                                    }
                                }
                            }
                        }

                        val vaultByFolder = LinkedHashMap<String, List<VaultEntry>>()
                        val uncachedManifestFolders = ArrayList<Pair<String, List<FileStat>>>()
                        for ((folderId, manifests) in manifestByFolder) {
                            if (!isCurrent()) throw CancellationException("Account changed")
                            val winnerId = VaultStore.winnerOf(manifests)?.id
                            val cachedManifest = store.cachedCommitted(manifests)
                            if (cachedManifest != null) {
                                vaultByFolder[folderId] = cachedManifest
                                if (!isShadowRebuild) {
                                    withPikoLock(treeStateLock) {
                                        if (isCurrent()) {
                                            ctx.overlay.vaultEntriesKnown(
                                                folderId,
                                                cachedManifest,
                                                expectedManifestId = winnerId,
                                                requireMatch = !store.hasPending(folderId),
                                            )
                                        }
                                    }
                                }
                            } else {
                                uncachedManifestFolders += folderId to manifests
                            }
                        }
                        if (uncachedManifestFolders.isNotEmpty()) {
                            val manifestPermits = Semaphore(MANIFEST_WARMUP_CONCURRENCY)
                            val loaded = coroutineScope {
                                uncachedManifestFolders.map { (folderId, manifests) ->
                                    async {
                                        withApiPriority(ApiRequestPriority.BACKGROUND) {
                                            manifestPermits.withPermit {
                                                if (!isCurrent()) throw CancellationException("Account changed")
                                                PikoWorkloadGovernor.Shared.awaitNoUrgentPlayback()
                                                if (!isCurrent()) throw CancellationException("Account changed")
                                                val entries = store.read(folderId, manifests)
                                                    .logFailure(DRIVE_REPO_TAG, "In-memory tree build failed to read vault manifest in ${logFolder(folderId)} (acct=$acctSlot)")
                                                    .getOrDefault(emptyList())
                                                withPikoLock(treeStateLock) {
                                                    if (isCurrent() && !store.hasPending(folderId)) {
                                                        ctx.overlay.vaultEntriesKnown(
                                                            folderId,
                                                            entries,
                                                            expectedManifestId = VaultStore.winnerOf(manifests)?.id,
                                                            requireMatch = true,
                                                        )
                                                    }
                                                }
                                                folderId to entries
                                            }
                                        }
                                    }
                                }.awaitAll()
                            }
                            for ((folderId, entries) in loaded) {
                                if (entries.isNotEmpty()) vaultByFolder[folderId] = entries
                            }
                        }

                        currentCoroutineContext().ensureActive()
                        var shouldRestartRebuild = false
                        var indexUpdated = false
                        var changedFolders = emptySet<String>()
                        var committed = false
                        withPikoLock(treeStateLock) {
                            val marks = changeMarks.value
                            if (isCurrent()) {
                                if (marks.treeInvalidatedAt > serial) {
                                    shouldRestartRebuild = true
                                } else {
                                    val interveningDirty = marks.folderAt.filter { (folderId, dirtySeq) ->
                                        dirtySeq > serial && dirtySeq >= (marks.listedAt[folderId] ?: 0L)
                                    }.keys
                                    val interveningListed = marks.listedAt.filter { (folderId, listSeq) ->
                                        listSeq > serial && listSeq > (marks.folderAt[folderId] ?: 0L)
                                    }.keys
                                    val interveningRemoved = marks.removedAt.filterValues { it > serial }.keys
                                    val interveningRenamed = marks.renamedAt.mapNotNull { (id, pair) ->
                                        if (pair.first > serial) id to pair.second else null
                                    }.toMap()
                                    val interveningMoved = marks.movedAt.mapNotNull { (id, pair) ->
                                        if (pair.first > serial) id to pair.second else null
                                    }.toMap()
                                    val usageBytes = ctx.mutations.quotaFlow.value?.quota?.usageBytes ?: index.lastUsageBytes
                                    changedFolders = index.replaceTree(
                                        folders = folders,
                                        files = allFiles,
                                        vaultByFolder = vaultByFolder,
                                        usageBytes = usageBytes,
                                        sinceMutationSeq = rebuildStartSeq,
                                        keepDirtyFolders = interveningDirty,
                                        freshlyListedFolders = interveningListed,
                                        removedIds = interveningRemoved,
                                        renamedNames = interveningRenamed,
                                        movedParents = interveningMoved,
                                        syncedAtMillis = Clock.System.now().toEpochMilliseconds(),
                                    )
                                    indexUpdated = populateDerivedFromTreeLocked()
                                    if (changedFolders.isNotEmpty()) {
                                        markChanged(changedFolders)
                                        ctx.listing.listingRequests.update { it - changedFolders }
                                    }
                                    if (isShadowRebuild && changedFolders.isNotEmpty()) {
                                        ctx.listing.listings.update { cache ->
                                            cache.mapValues { (id, listing) -> if (id in changedFolders) listing.expired() else listing }
                                        }
                                    }
                                    committed = true
                                }
                            }
                        }
                        withPikoLock(treeStateLock) {
                            if (isCurrent()) {
                                if (shouldRestartRebuild) {
                                    PikoLog.d(DRIVE_REPO_TAG, "In-memory tree build superseded by concurrent tree invalidation; scheduling rebuild (acct=$acctSlot)")
                                    requestTreeRebuild()
                                } else if (committed) {
                                    if (indexUpdated) normalizeLocations()
                                    if (isShadowRebuild && changedFolders.isNotEmpty()) {
                                        ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(changedFolders))
                                    }
                                }
                            }
                        }
                        if (committed) {
                            val readyState = index.flow.value
                            PikoLog.i(
                                DRIVE_REPO_TAG,
                                "In-memory fallback tree index ready (acct=$acctSlot, folders=$activeFolderCount, files=$fileCount, " +
                                    "manifestFolders=${manifestByFolder.size}, complete=${readyState.complete}, dirtyFolders=${readyState.dirtyFolders.size}, " +
                                    "hasCursor=false, shadowRebuild=$isShadowRebuild, elapsedMs=${started.elapsedNow().inWholeMilliseconds})",
                            )
                        }
                    } catch (failure: Exception) {
                        if (isCurrent()) throw failure
                    } finally {
                        historyToken.close()
                        if (index.isComplete) index.pruneMutationHistory(index.currentMutationSeq)
                        permit.release()
                        withPikoLock(treeStateLock) {
                            if (isCurrent() && activeBuildToken === token) {
                                activeBuildToken = null
                                _treeBuildProgressFlow.update { it.copy(isBuilding = false, isInitialBlocking = false) }
                            }
                        }
                    }
                }
            }
            if (treeSyncJob.compareAndSet(existing, asked to job)) {
                job.invokeOnCompletion {
                    treeSyncJob.update { current -> if (current?.second === job) null else current }
                }
                job.start()
                return job
            }
            job.cancel()
        }
    }

    fun startBackgroundWorkers() {
        ctx.backgroundScope.launch {
            for (task in metadataObserveQueue) {
                runCatching(task).logFailure(DRIVE_REPO_TAG, "Async metadata observation failed")
            }
        }
        ctx.backgroundScope.launch {
            PikoMemoryGovernor.Shared.policy.collect { policy ->
                if (policy != MemoryPolicy.Normal) {
                    val active = references.value.flatMap { it.retainedPaths }
                        .mapNotNullTo(HashSet()) { it.lastOrNull()?.id }
                    ctx.listing.evictInactiveListings(active)
                    ctx.library.clearFolderTitleCache()
                    DriveAnalysisCache.clear()
                }
            }
        }
        ctx.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous: PikPakClient? = null
            ctx.clientManager.currentClient.collect { current ->
                val old = previous
                previous = current
                if (old != null && current != null && old !== current && old.account == current.account) {
                    refreshActiveListings()
                    ctx.clientRevision.update { it + 1 }
                }
            }
        }
    }

    private companion object {
        const val MAX_LOCATE_DEPTH = 64
        const val MANIFEST_WARMUP_CONCURRENCY = 4
        val CANARY_PROBE_COOLDOWN = 60.seconds
        const val CANARY_WINDOW_SIZE = 500
        const val MAX_CANARY_PAGES = 4
        const val CANARY_TAIL_SETTLED_COUNT = 32
        const val MAX_INCREMENTAL_RECONCILE_FOLDERS = 16

        inline fun firstPageOrTailUnchangedCount(
            items: List<FileStat>,
            matchesLocal: (FileStat) -> Boolean,
        ): Int {
            var count = 0
            for (i in items.lastIndex downTo 0) {
                if (matchesLocal(items[i])) count++ else break
            }
            return count
        }
    }
}

/**
 * 判断断点续扫遇到的异常是否属于「服务端明确拒绝旧分页游标」，决定是否作废已落盘断点并从头重扫。
 */
internal fun isResumedCheckpointRejected(err: Throwable, resumedPagesFetched: Int): Boolean {
    if (resumedPagesFetched > 0) return false
    if (err is FileSyncVersionOutdatedException) return true
    if (err is PikPakException) {
        val status = err.httpStatus
        if (status != null && status in 400..499 && status != 408 && status != 429) return true
    }
    return false
}
