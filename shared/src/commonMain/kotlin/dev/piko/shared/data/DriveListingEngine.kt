@file:JvmMultifileClass
@file:JvmName("PikoDriveRepositoryKt")

package dev.piko.shared.data

import dev.piko.data.repository.NaturalOrder
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFolder
import dev.piko.shared.space.SpaceFolderSnapshot
import dev.piko.shared.space.SpacePreviewBlock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listFilesPaged
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 分页与全量列目录、单飞去重请求表、容量有界目录列表缓存、子文件摘要采样、空目录探测、
 * 子树统计、空间地图快照与自然序/时长多维排序引擎。
 */
internal class DriveListingEngine(
    private val ctx: DriveRepoContext,
) {
    internal class Listing(
        val files: List<FileStat>,
        val listedAt: TimeSource.Monotonic.ValueTimeMark,
        val stale: Boolean = false,
    ) {
        val weight: Long = 128L + files.sumOf { file ->
            256L + 2L * (
                file.id.length + file.parentId.length + file.name.length + file.thumbnailLink.length +
                    file.params.entries.sumOf { it.key.length + it.value.length }
                )
        }

        fun expired(): Listing = if (stale) this else Listing(files, listedAt, stale = true)
    }

    internal val listings = MutableStateFlow<Map<String, Listing>>(emptyMap())
    internal val listingRequests = MutableStateFlow<Map<String, Deferred<List<FileStat>>>>(emptyMap())

    private val folderMeaninglessCacheFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val folderMeaninglessCache: Map<String, Boolean> get() = folderMeaninglessCacheFlow.value

    internal val childContents = FolderContentMemory(ctx.cacheStore, ctx.backgroundScope)
    internal val childNameFetches = Semaphore(CHILD_NAME_CONCURRENCY)

    val childContentsFlow: StateFlow<Map<String, List<ChildFile>>> get() = childContents.state

    private val _folderEmptiness = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val folderEmptiness: StateFlow<Map<String, Boolean>> = _folderEmptiness.asStateFlow()

    private val spaceRefreshes = MutableStateFlow<Map<String, Long>>(emptyMap())
    private data class SpaceSyncState(val running: Boolean = false, val error: String? = null)

    fun resetOnAccountSwitchLocked() {
        listingRequests.value = emptyMap()
        listings.value = emptyMap()
        folderMeaninglessCacheFlow.value = emptyMap()
        _folderEmptiness.value = emptyMap()
    }

    fun markFolderNotEmpty(folderId: String) {
        _folderEmptiness.update { it + (folderId to false) }
    }

    fun invalidateFolderEmptiness(folders: Set<String>?) {
        _folderEmptiness.update { known -> if (folders == null) emptyMap() else known - folders }
    }

    fun evictInactiveListings(activeFolderIds: Set<String>) {
        listings.update { cache -> cache.filterKeys { it in activeFolderIds } }
    }

    fun refreshSpaceFolder(folderId: String) {
        spaceRefreshes.update { it + (folderId to ((it[folderId] ?: 0L) + 1L)) }
    }

    suspend fun loadSpaceFolderPreview(
        folderId: String,
        includeVaulted: Boolean,
        limit: Int = 128,
    ): List<SpacePreviewBlock> = withContext(Dispatchers.Default) {
        val index = withPikoLock(ctx.index.treeStateLock) { ctx.index.driveTreeIndex }
        spaceFolderPreviewChildren(index, folderId, includeVaulted, limit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSpaceFolder(folderId: String, includeVaulted: Boolean): Flow<SpaceFolderSnapshot> =
        ctx.clientManager.currentClient.flatMapLatest { asked ->
            flow {
                if (asked != null && ctx.clientManager.currentClient.value === asked) {
                    ctx.index.enterAccount(asked.account)
                }
                emitAll(ctx.index.treeBindingFlow.flatMapLatest { index ->
                    spaceRefreshes.map { it[folderId] ?: 0L }.distinctUntilChanged().flatMapLatest {
                        channelFlow {
                            val status = MutableStateFlow(SpaceSyncState())
                            fun pendingFolders(): Set<String> = withPikoLock(ctx.index.treeStateLock) {
                                val state = index.flow.value
                                if (state.complete && state.dirtyFolders.isEmpty()) return@withPikoLock emptySet()
                                val descendants = index.descendantFolders(folderId) + folderId
                                descendants.filterTo(LinkedHashSet()) {
                                    it in state.dirtyFolders || (!state.complete && it !in state.listedFolders)
                                }
                            }
                            val isBuildingFlow = ctx.index.treeBuildProgressFlow.map { it.isBuilding }.distinctUntilChanged()
                            combine(index.flow, isBuildingFlow, status, ctx.mutations.optimisticRevision) { _, building, sync, _ ->
                                val needsSync = asked != null && ctx.clientManager.currentClient.value === asked &&
                                    ctx.index.driveTreeIndex === index && sync.error == null &&
                                    (!index.isComplete || pendingFolders().isNotEmpty())
                                val project: ((List<FileStat>, String?) -> List<FileStat>)? =
                                    if (ctx.mutations.hasPendingOptimisticWrites) {
                                        { files, parent -> ctx.mutations.projectOptimisticFiles(files, parent) }
                                    } else {
                                        null
                                    }
                                spaceFolderSnapshot(
                                    index = index,
                                    folderId = folderId,
                                    includeVaulted = includeVaulted,
                                    isBuilding = sync.error == null && (building || sync.running || needsSync),
                                    projectOptimistic = project,
                                ).copy(syncError = sync.error)
                            }.conflate().collect { snapshot ->
                                send(snapshot)
                                if (asked != null && ctx.clientManager.currentClient.value === asked &&
                                    ctx.index.driveTreeIndex === index && !status.value.running && status.value.error == null &&
                                    (!index.isComplete || pendingFolders().isNotEmpty())
                                ) {
                                    status.value = SpaceSyncState(running = true)
                                    launch {
                                        try {
                                            var activeSync: Deferred<Unit>? =
                                                if (!index.isComplete) ctx.index.ensureTreeSync(asked) else null
                                            var replacements = 0
                                            while (activeSync != null && !index.isComplete) {
                                                currentCoroutineContext().ensureActive()
                                                if (ctx.index.driveTreeIndex !== index || ctx.clientManager.currentClient.value !== asked) {
                                                    throw CancellationException("Account changed")
                                                }
                                                val awaited = activeSync
                                                try {
                                                    awaited.await()
                                                    activeSync = null
                                                } catch (cancelled: CancellationException) {
                                                    currentCoroutineContext().ensureActive()
                                                    if (ctx.index.driveTreeIndex !== index || ctx.clientManager.currentClient.value !== asked) {
                                                        throw cancelled
                                                    }
                                                    val replacement = ctx.index.treeSyncJob.value
                                                        ?.takeIf { it.first === asked && it.second !== awaited }
                                                        ?.second
                                                    if (replacement != null && ++replacements <= 8) {
                                                        activeSync = replacement
                                                    } else {
                                                        status.value = SpaceSyncState(
                                                            error = tr(
                                                                "空间统计同步已中断",
                                                                "Space statistics sync interrupted",
                                                                "容量統計の同期が中断されました",
                                                                "공간 통계 동기화가 중단되었습니다",
                                                                "空間統計同步已中斷",
                                                            ),
                                                        )
                                                        return@launch
                                                    }
                                                }
                                            }
                                            val store = ctx.overlay.vaultForClient(asked)
                                            val attempted = HashSet<String>()
                                            while (true) {
                                                val pending = pendingFolders() - attempted
                                                if (pending.isEmpty()) break
                                                for (id in pending) {
                                                    currentCoroutineContext().ensureActive()
                                                    if (ctx.index.driveTreeIndex !== index || ctx.clientManager.currentClient.value !== asked) {
                                                        throw CancellationException("Account changed")
                                                    }
                                                    try {
                                                        val listing = fetchListing(id, asked)
                                                        if (listing.any(VaultStore::looksLikeManifest)) {
                                                            runSuspendCatching {
                                                                ctx.overlay.readAndRecordVaultEntries(id, listing, store, asked)
                                                            }
                                                        }
                                                    } catch (cancelled: CancellationException) {
                                                        currentCoroutineContext().ensureActive()
                                                        throw IllegalStateException("Listing interrupted", cancelled)
                                                    }
                                                    attempted += id
                                                }
                                            }
                                            check(index.isComplete && pendingFolders().isEmpty()) { "Tree reconciliation incomplete" }
                                            status.value = SpaceSyncState()
                                        } catch (cancelled: CancellationException) {
                                            status.update { if (it.running) it.copy(running = false) else it }
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            currentCoroutineContext().ensureActive()
                                            PikoLog.w(
                                                DRIVE_REPO_TAG,
                                                "Space statistics sync failed for ${logFolder(folderId)} (acct=${logAccountSlot(asked?.account)})",
                                                failure,
                                            )
                                            status.value = SpaceSyncState(
                                                error = tr(
                                                    "空间统计同步失败",
                                                    "Space statistics sync failed",
                                                    "容量統計の同期に失敗しました",
                                                    "공간 통계 동기화 실패",
                                                    "空間統計同步失敗",
                                                ),
                                            )
                                        } finally {
                                            status.update { if (it.running) it.copy(running = false) else it }
                                        }
                                    }
                                }
                            }
                        }
                    }
                })
            }
        }.flowOn(Dispatchers.Default)

    fun contentMetadataOf(gcid: String, sizeBytes: Long): ContentMetadata? =
        ContentKey.of(gcid, sizeBytes)?.let(ctx.index.driveTreeIndex::contentMetadata)

    fun contentMetadataOf(file: FileStat): ContentMetadata? =
        ContentKey.of(file)?.let(ctx.index.driveTreeIndex::contentMetadata)

    fun contentMetadataBatch(keys: Collection<ContentKey>): Map<ContentKey, ContentMetadata> =
        ctx.index.driveTreeIndex.contentMetadataBatch(keys)

    fun originalCreatedAtMillisBatch(ids: Collection<String>): Map<String, Long> =
        ctx.index.driveTreeIndex.originalCreatedAtMillisBatch(ids)

    fun contentMetadataByFileId(fileId: String): ContentMetadata? {
        if (fileId.isBlank()) return null
        val resolved = VaultEntry.resolvedFileOf(fileId) ?: LeasedFile.resolvedFileOf(fileId)
        if (resolved != null && !resolved.gcid.isNullOrBlank() && resolved.size >= 0L) {
            contentMetadataOf(resolved.gcid!!, resolved.size)?.let { return it }
        }
        val known = ctx.index.knownFile(fileId) ?: ctx.index.driveTreeIndex.file(fileId) ?: return null
        return contentMetadataOf(known)
    }

    fun originalCreatedAtMillis(fileId: String): Long? =
        ctx.index.driveTreeIndex.originalCreatedAtMillis(fileId)

    fun folderSubtreeStats(folderId: String): SubtreeStats? =
        ctx.index.driveTreeIndex.subtreeStats(folderId)

    fun isSubtreeClean(rootFolderId: String): Boolean {
        val stats = ctx.index.driveTreeIndex.subtreeStats(rootFolderId) ?: return false
        return stats.structuralValid && stats.physical.listingComplete && stats.vaulted.listingComplete
    }

    fun allDescendantFolders(rootFolderId: String): List<FileStat> {
        val driveTreeIndex = ctx.index.driveTreeIndex
        val state = driveTreeIndex.flow.value
        val ids = if (rootFolderId.isEmpty()) {
            state.entries.values.asSequence()
                .filter { it.isFolder && !it.trashed && !isPikoInternalFolder(it, it.parentId) }
                .map { it.id }
                .toList()
        } else {
            driveTreeIndex.descendantFolders(rootFolderId).toList()
        }
        return ids.mapNotNull { id ->
            state.entries[id]?.takeIf { it.isFolder && !it.trashed && !isPikoInternalFolder(it, it.parentId) }
        }
    }

    suspend fun subfolders(folderId: String, fresh: Boolean = false): Result<List<PikoPathBreadcrumb>> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                if (!isDriveFolderId(folderId) && !LocalLocation.isLocal(folderId)) return@runSuspendCatching emptyList()
                val files = if (!fresh) {
                    ctx.overlay.cachedBrowsable(folderId, PikoFileSortOrder.NAME_ASC)
                        ?: ctx.overlay.listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()
                } else {
                    ctx.overlay.listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()
                }
                files.asSequence()
                    .filter { it.isFolder && !it.trashed && !isPikoInternalFolder(it, folderId) }
                    .sortedWith(compareBy(NaturalOrder) { it.name })
                    .map { PikoPathBreadcrumb(it.id, it.name) }
                    .toList()
            }
        }

    suspend fun folderMapLevel(folderId: String): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val files = ctx.overlay.listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()
            files.filter {
                !it.trashed && !isPikoInternalFolder(it, folderId) &&
                    !VaultStore.looksLikeManifest(it) && !VaultStore.isRecoveryFile(it)
            }.sortedWith(compareBy<FileStat> { !it.isFolder }.thenBy(NaturalOrder) { it.name })
        }
    }

    internal fun rememberListing(folderId: String, files: List<FileStat>) {
        val kept = ctx.index.locationFolderIds()
        listings.update { cache ->
            val next = LinkedHashMap(cache - folderId)
            val listing = Listing(files, TimeSource.Monotonic.markNow())
            if (listing.weight <= MAX_LISTING_BYTES) next[folderId] = listing
            var weight = next.values.sumOf { it.weight }
            val evictionOrder = next.keys.filter { it !in kept } + next.keys.filter { it in kept }
            for (id in evictionOrder) {
                if (next.size <= RECENT_LISTINGS && weight <= MAX_LISTING_BYTES) break
                weight -= next.remove(id)?.weight ?: 0L
            }
            next
        }
    }

    internal fun cachedListing(folderId: String): List<FileStat>? =
        listings.value[folderId]?.files?.let { ctx.mutations.projectOptimisticFiles(it, folderId) }

    internal fun freshListing(folderId: String): List<FileStat>? {
        val listing = listings.value[folderId]?.takeUnless { it.stale }
        if (listing != null && (listing.listedAt.elapsedNow() < RECENT_LISTING_FRESH || folderId in ctx.index.locationFolderIds())) {
            return listing.files
        }
        val driveTreeIndex = ctx.index.driveTreeIndex
        val state = driveTreeIndex.flow.value
        val children = driveTreeIndex.realChildren(folderId)
        if (folderId !in state.dirtyFolders && (
                children.coverage == DriveTreeIndex.FolderCoverage.COMPLETE ||
                    (state.complete && (folderId.isEmpty() || folderId in state.entries))
                )
        ) {
            return children.files
        }
        return null
    }

    internal suspend fun fetchListing(parentId: String, asked: PikPakClient = ctx.requireClient()): List<FileStat> {
        require(!LocalLocation.isLocal(parentId)) { "本地目录不支持云端操作" }
        val driveTreeIndex = withPikoLock(ctx.index.treeStateLock) {
            if (ctx.clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            ctx.index.driveTreeIndex
        }
        val request = ctx.backgroundScope.async(start = CoroutineStart.LAZY) {
            val serial = withPikoLock(ctx.index.treeStateLock) { ctx.index.currentSerial }
            val listed = asked.listFiles(parentId)
            if (ctx.clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            val files = ctx.mutations.projectOptimisticFiles(ctx.overlay.sourceLedger.enrich(listed), parentId)
            var indexUpdated = false
            var reparentedFrom = emptySet<String>()
            withPikoLock(ctx.index.treeStateLock) {
                if (ctx.clientManager.currentClient.value === asked &&
                    ctx.index.driveTreeIndex === driveTreeIndex &&
                    ctx.index.unchangedSince(parentId, serial) &&
                    !ctx.mutations.hasPendingOptimisticWrites
                ) {
                    rememberListing(parentId, files)
                    rememberChildContents(parentId, files)
                    reparentedFrom = driveTreeIndex.putFolderListing(parentId, files)
                    ctx.index.markFolderListed(parentId, reparentedFrom)
                    if (reparentedFrom.isNotEmpty()) {
                        childContents.invalidate(reparentedFrom)
                        listingRequests.update { it - reparentedFrom }
                        listings.update { cache ->
                            cache.mapValues { (id, listing) -> if (id in reparentedFrom) listing.expired() else listing }
                        }
                        _folderEmptiness.update { it - reparentedFrom }
                    }
                    indexUpdated = ctx.index.folderIndex.listed(parentId, files)
                }
            }
            if (reparentedFrom.isNotEmpty()) {
                ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(reparentedFrom))
            }
            if (indexUpdated) ctx.index.normalizeLocations()
            files
        }
        var pending: Deferred<List<FileStat>>? = null
        listingRequests.update { requests ->
            pending = requests[parentId]
            if (pending != null) requests else requests + (parentId to request)
        }
        pending?.let {
            request.cancel()
            return it.await()
        }
        request.invokeOnCompletion {
            listingRequests.update { requests -> if (requests[parentId] === request) requests - parentId else requests }
        }
        request.start()
        return request.await()
    }

    fun cachedFiles(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? =
        cachedListing(folderId)?.let { sortFiles(it, sortOrder, folderId) }

    fun localRealChildrenIfClean(folderId: String): List<FileStat>? {
        val driveTreeIndex = ctx.index.driveTreeIndex
        val state = driveTreeIndex.flow.value
        if (folderId in state.dirtyFolders) return null
        val children = driveTreeIndex.realChildren(folderId)
        if (children.coverage == DriveTreeIndex.FolderCoverage.COMPLETE) return children.files
        if (!state.complete) return null
        if (folderId.isNotEmpty() && folderId !in state.entries) return null
        return children.files
    }

    suspend fun scanRealChildren(folderId: String, asked: PikPakClient = ctx.requireClient()): List<FileStat> {
        requireCloudFileIds(listOf(folderId))
        val driveTreeIndex = withPikoLock(ctx.index.treeStateLock) {
            if (ctx.clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            ctx.index.driveTreeIndex
        }
        localRealChildrenIfClean(folderId)?.let { return it }
        val serial = withPikoLock(ctx.index.treeStateLock) { ctx.index.currentSerial }
        val raw = asked.listFiles(folderId)
        val enriched = ctx.overlay.sourceLedger.enrich(raw)
        var indexUpdated = false
        withPikoLock(ctx.index.treeStateLock) {
            if (ctx.clientManager.currentClient.value === asked &&
                ctx.index.driveTreeIndex === driveTreeIndex &&
                ctx.index.unchangedSince(folderId, serial)
            ) {
                val reparented = driveTreeIndex.putFolderListing(folderId, enriched, complete = true)
                ctx.index.markFolderListed(folderId, reparented)
                indexUpdated = ctx.index.folderIndex.listed(folderId, enriched, complete = true)
            }
        }
        if (indexUpdated) ctx.index.normalizeLocations()
        return enriched
    }

    fun knownChildContents(folderId: String): List<ChildFile>? = childContents.get(folderId)

    internal fun rememberChildContents(folderId: String, files: List<FileStat>, complete: Boolean = true) {
        val (manifests, real) = files.partition(VaultStore::looksLikeManifest)
        val visibleReal = real.filterNot { it.trashed || VaultStore.isRecoveryFile(it) }
        val resolvedVaultEntries = if (manifests.isNotEmpty()) {
            ctx.vaultAccessor().cached(folderId, files) ?: ctx.index.driveTreeIndex.vaultEntries(folderId)
        } else {
            emptyList()
        }
        if (manifests.isEmpty() || resolvedVaultEntries != null) {
            val childFiles = buildList {
                visibleReal.asSequence().filterNot(FileStat::isFolder).take(MAX_REMEMBERED_CHILD_NAMES).forEach { add(ChildFile.of(it)) }
                if (size < MAX_REMEMBERED_CHILD_NAMES && !resolvedVaultEntries.isNullOrEmpty()) {
                    resolvedVaultEntries.asSequence()
                        .filter { it.isArchived }
                        .take(MAX_REMEMBERED_CHILD_NAMES - size)
                        .forEach { add(ChildFile.of(it.toFileStat(folderId))) }
                }
            }
            childContents.put(folderId, childFiles)
        }
        val hasEntries = manifests.isNotEmpty() && resolvedVaultEntries?.any { it.isArchived } != false
        if (hasEntries || complete) childContents.markVaulted(folderId, hasEntries)
        val subfolders = visibleReal.filter { it.isFolder }.mapTo(mutableSetOf()) { it.id }
        val hasRealFiles = visibleReal.any { !it.isFolder && !it.isVaulted }
        childContents.rememberCompleteness(folderId, hasRealFiles, subfolders, complete)
        val marked = VaultTrees.marked(childContents.vaultedFolders.value, childContents.state.value.keys, ctx.overlay.vaultTrees.flow.value)
        if (complete && folderId.isNotEmpty() && visibleReal.none { !it.isFolder && !it.isVaulted } &&
            subfolders.isNotEmpty() && subfolders.all { it in marked }
        ) {
            ctx.overlay.vaultTrees.merge(mapOf(folderId to subfolders))
        }
        _folderEmptiness.update { it + (folderId to (visibleReal.isEmpty() && !hasEntries)) }
    }

    suspend fun probeFolderEmptiness(folderId: String) = withContext(Dispatchers.Default) {
        if (folderId in _folderEmptiness.value) return@withContext
        val driveTreeIndex = ctx.index.driveTreeIndex
        val treeChildren = driveTreeIndex.realChildren(folderId)
        val state = driveTreeIndex.flow.value
        if (treeChildren.coverage == DriveTreeIndex.FolderCoverage.COMPLETE ||
            (state.complete && treeChildren.coverage != DriveTreeIndex.FolderCoverage.DIRTY && (folderId.isEmpty() || folderId in state.entries))
        ) {
            _folderEmptiness.update { it + (folderId to treeChildren.files.isEmpty()) }
            return@withContext
        }
        childNameFetches.withPermit {
            if (folderId in _folderEmptiness.value) return@withPermit
            val files = runSuspendCatching { ctx.requireClient().listFilesPaged(parentId = folderId, pageSize = 1).files }
                .getOrNull() ?: return@withPermit
            _folderEmptiness.update { it + (folderId to files.isEmpty()) }
        }
    }

    suspend fun fetchChildContents(folderId: String): List<ChildFile>? = withContext(Dispatchers.Default) {
        childContents.get(folderId)?.let { return@withContext it }
        val driveTreeIndex = ctx.index.driveTreeIndex
        val treeChildren = driveTreeIndex.realChildren(folderId)
        val state = driveTreeIndex.flow.value
        if (treeChildren.coverage == DriveTreeIndex.FolderCoverage.COMPLETE ||
            (state.complete && treeChildren.coverage != DriveTreeIndex.FolderCoverage.DIRTY && (folderId.isEmpty() || folderId in state.entries))
        ) {
            val treeFiles = treeChildren.files
            val (askedBefore, serialBefore) = withPikoLock(ctx.index.treeStateLock) {
                ctx.clientManager.currentClient.value to ctx.index.currentSerial
            }
            if (askedBefore == null) return@withContext null
            if (treeFiles.any(VaultStore::looksLikeManifest) &&
                ctx.vaultAccessor().cached(folderId, treeFiles) == null &&
                driveTreeIndex.vaultEntries(folderId) == null
            ) {
                childNameFetches.withPermit {
                    childContents.get(folderId)?.let { return@withPermit }
                    if (ctx.clientManager.currentClient.value !== askedBefore) return@withPermit
                    runSuspendCatching {
                        ctx.overlay.readAndRecordVaultEntries(folderId, treeFiles, ctx.vaultAccessor(), askedBefore, logFailure = false)
                    }
                }
            }
            withPikoLock(ctx.index.treeStateLock) {
                if (ctx.clientManager.currentClient.value !== askedBefore ||
                    ctx.index.driveTreeIndex !== driveTreeIndex ||
                    !ctx.index.unchangedSince(folderId, serialBefore)
                ) {
                    return@withContext childContents.get(folderId)
                }
                rememberChildContents(folderId, treeFiles, complete = true)
            }
            return@withContext childContents.get(folderId)
        }
        childNameFetches.withPermit {
            childContents.get(folderId)?.let { return@withPermit it }
            val asked = ctx.requireClient()
            val serial = withPikoLock(ctx.index.treeStateLock) { ctx.index.currentSerial }
            val files = runSuspendCatching { asked.listFilesPaged(parentId = folderId, pageSize = CHILD_NAME_PAGE).files }
                .getOrNull() ?: return@withPermit null
            if (files.any(VaultStore::looksLikeManifest) && ctx.vaultAccessor().cached(folderId, files) == null) {
                runSuspendCatching {
                    ctx.overlay.readAndRecordVaultEntries(folderId, files, ctx.vaultAccessor(), asked, logFailure = false)
                }
            }
            val complete = files.size < CHILD_NAME_PAGE
            val indexUpdated = withPikoLock(ctx.index.treeStateLock) {
                if (ctx.clientManager.currentClient.value !== asked || !ctx.index.unchangedSince(folderId, serial)) {
                    return@withPermit null
                }
                rememberChildContents(folderId, files, complete)
                ctx.index.folderIndex.listed(folderId, files, complete)
            }
            if (indexUpdated) ctx.index.normalizeLocations()
            childContents.get(folderId)
        }
    }

    suspend fun listFiles(
        parentId: String = "",
        pageToken: String = "",
        sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    ): Result<Pair<List<FileStat>, String>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            if (LocalLocation.isLocal(parentId)) {
                return@runSuspendCatching sortFiles(ctx.overlay.localMounts.list(parentId), sortOrder, parentId) to ""
            }
            val page = ctx.requireClient().listFilesPaged(parentId = parentId, pageToken = pageToken, pageSize = 100)
            sortFiles(page.files, sortOrder, parentId) to page.nextPageToken
        }
    }

    suspend fun listAllFiles(
        parentId: String = "",
        sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    ): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val raw = if (LocalLocation.isLocal(parentId)) ctx.overlay.localMounts.list(parentId) else fetchListing(parentId)
            sortFiles(raw, sortOrder, parentId)
        }
    }

    fun folderUsage(
        folderId: String,
        maxFolders: Int = 2_000,
        timeout: Duration = 30.seconds,
    ): Flow<FolderUsage> = flow {
        ctx.index.driveTreeIndex.folderUsage(folderId)?.let { usage ->
            emit(usage)
            return@flow
        }
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var files = 0
        var bytes = 0L
        var listed = 0
        var level = listOf(folderId)
        while (level.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (batch in level.chunked(FOLDER_USAGE_CONCURRENCY)) {
                if (deadline.hasPassedNow() || listed >= maxFolders) {
                    emit(FolderUsage(files, bytes, FolderUsage.Progress.TRUNCATED))
                    return@flow
                }
                val budgeted = batch.take(maxFolders - listed)
                listed += budgeted.size
                val listings = coroutineScope { budgeted.map { async { ctx.requireClient().listFiles(it) } }.awaitAll() }
                for (entry in listings.flatten()) {
                    if (entry.isFolder) {
                        next += entry.id
                    } else {
                        files++
                        bytes += entry.sizeBytes
                    }
                }
                emit(FolderUsage(files, bytes, FolderUsage.Progress.COUNTING))
            }
            level = next
        }
        emit(FolderUsage(files, bytes, FolderUsage.Progress.COMPLETE))
    }.flowOn(Dispatchers.Default)

    fun folderMeaningless(folderId: String): Boolean? = folderMeaninglessCache[folderId]

    fun cacheFolderMeaningless(folderId: String, value: Boolean) {
        folderMeaninglessCacheFlow.update { it + (folderId to value) }
    }

    suspend fun findMyPacksFolder(): Result<PikoPathBreadcrumb?> {
        localRealChildrenIfClean("")?.let { files ->
            return Result.success(files.firstOrNull { it.isMyPacksFolder() }?.let { PikoPathBreadcrumb(it.id, it.name) })
        }
        return listAllFiles().map { files -> files.firstOrNull { it.isMyPacksFolder() }?.let { PikoPathBreadcrumb(it.id, it.name) } }
    }

    suspend fun getOrCreateMyPacksFolder(access: DriveWriteAccess): Result<PikoPathBreadcrumb> {
        val rootFiles = access.listFolder("").getOrElse { return Result.failure(it) }
        val existing = rootFiles.firstOrNull { it.isMyPacksFolder() }
        return if (existing != null) {
            Result.success(PikoPathBreadcrumb(existing.id, existing.name))
        } else {
            access.createFolder("", MY_PACKS_FOLDER_NAME).map {
                access.publish(DriveChange.Created("", it, MY_PACKS_FOLDER_NAME, isFolder = true))
                PikoPathBreadcrumb(it, MY_PACKS_FOLDER_NAME)
            }
        }
    }

    suspend fun isFolderMeaningless(folderId: String, thresholdBytes: Long, forceRefresh: Boolean = false): Boolean =
        withContext(Dispatchers.Default) {
            if (!forceRefresh) {
                folderMeaninglessCache[folderId]?.let { return@withContext it }
                localRealChildrenIfClean(folderId)?.let { local ->
                    val empty = local.isEmpty() && ctx.index.driveTreeIndex.vaultEntries(folderId).orEmpty().none { it.isArchived }
                    cacheFolderMeaningless(folderId, empty)
                    return@withContext empty
                }
            }
            val result = listFiles(folderId, sortOrder = PikoFileSortOrder.TIME_DESC)
                .getOrDefault(emptyList<FileStat>() to "").first
                .isEmpty()
            cacheFolderMeaningless(folderId, result)
            result
        }

    fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder, parentId: String, foldersFirst: Boolean = true): List<FileStat> =
        sortFiles(files, order, parentId, ctx.index.driveTreeIndex.sortContext(files, order), foldersFirst)

    fun sortFilesFast(files: List<FileStat>, order: PikoFileSortOrder, parentId: String, foldersFirst: Boolean = true): List<FileStat> =
        sortFiles(files, order, parentId, ctx.index.driveTreeIndex.fastSortContext(), foldersFirst)

    fun sortFiles(
        files: List<FileStat>,
        order: PikoFileSortOrder,
        parentId: String,
        context: SortContext,
        foldersFirst: Boolean = true,
    ): List<FileStat> {
        val (folders, regularFiles) = files.partition { it.isFolder }
        fun durationOf(file: FileStat): Long? = if (file.isFolder) {
            context.subtreeStats[file.id]?.let { (it.physical + it.vaulted).durationMillis.takeIf { value -> value > 0 } }
        } else {
            ContentKey.of(file)
                ?.let { context.contentMetadata[it]?.durationMillis }?.takeIf { it > 0 }
                ?: file.params["duration"]?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000).toLong() }
        }
        val times = files.associate {
            it.id to (it.params["piko_original_created_at"]?.toLongOrNull()
                ?: context.createdAtMillis[it.id] ?: treeEpochMillis(it.createdTime))
        }
        val sizes = files.associate { file ->
            file.id to if (file.isFolder) {
                context.subtreeStats[file.id]?.let { (it.physical + it.vaulted).bytes } ?: file.sizeBytes
            } else {
                file.sizeBytes
            }
        }
        fun compareKnown(a: Long?, b: Long?, descending: Boolean): Int = when {
            a == null -> if (b == null) 0 else 1
            b == null -> -1
            descending -> b.compareTo(a)
            else -> a.compareTo(b)
        }
        val nameComparator = compareBy(NaturalOrder, FileStat::name).thenBy(FileStat::id)
        val comparator = when (order) {
            PikoFileSortOrder.NAME_ASC -> nameComparator
            PikoFileSortOrder.NAME_DESC -> compareByDescending(NaturalOrder, FileStat::name).thenBy(FileStat::id)
            PikoFileSortOrder.TIME_DESC, PikoFileSortOrder.TIME_ASC -> Comparator<FileStat> { a, b ->
                compareKnown(times[a.id], times[b.id], order == PikoFileSortOrder.TIME_DESC)
            }.then(nameComparator)
            PikoFileSortOrder.SIZE_DESC, PikoFileSortOrder.SIZE_ASC -> Comparator<FileStat> { a, b ->
                compareKnown(sizes[a.id], sizes[b.id], order == PikoFileSortOrder.SIZE_DESC)
            }.then(nameComparator)
            PikoFileSortOrder.DURATION_DESC, PikoFileSortOrder.DURATION_ASC -> Comparator { a, b ->
                val da = durationOf(a)
                val db = durationOf(b)
                val ta = if (da != null) 0 else if (a.isPlayableVideo()) 1 else 2
                val tb = if (db != null) 0 else if (b.isPlayableVideo()) 1 else 2
                val descending = order == PikoFileSortOrder.DURATION_DESC
                val tier = ta.compareTo(tb)
                if (tier != 0) {
                    tier
                } else {
                    val duration = if (ta == 0) {
                        if (descending) db!!.compareTo(da!!) else da!!.compareTo(db!!)
                    } else {
                        0
                    }
                    val size = if (ta < 2) {
                        if (descending) sizes.getValue(b.id).compareTo(sizes.getValue(a.id))
                        else sizes.getValue(a.id).compareTo(sizes.getValue(b.id))
                    } else {
                        0
                    }
                    duration.takeIf { it != 0 } ?: size.takeIf { it != 0 } ?: nameComparator.compare(a, b)
                }
            }
        }
        if (!foldersFirst) return files.sortedWith(comparator)
        val sortedFolders = folders.sortedWith(comparator)
        val pinned = if (parentId.isEmpty()) sortedFolders.firstOrNull { it.isMyPacksFolder() } else null
        val orderedFolders = if (pinned == null) sortedFolders else listOf(pinned) + (sortedFolders - pinned)
        return orderedFolders + regularFiles.sortedWith(comparator)
    }

    private fun FileStat.isMyPacksFolder(): Boolean = isFolder && name.lowercase() in MY_PACKS_FOLDER_NAMES

    internal companion object {
        const val FOLDER_USAGE_CONCURRENCY = 4
        const val CHILD_NAME_PAGE = 20
        const val CHILD_NAME_CONCURRENCY = 2
        const val MAX_REMEMBERED_CHILD_NAMES = 200
        const val RECENT_LISTINGS = 128
        const val MAX_LISTING_BYTES = 8L * 1024 * 1024
        val RECENT_LISTING_FRESH = 60.seconds
        const val MY_PACKS_FOLDER_NAME = "My Packs"
        val MY_PACKS_FOLDER_NAMES = setOf("my pack", "my packs", "我的资源", "我的离线")
    }
}
