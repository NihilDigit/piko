@file:JvmMultifileClass
@file:JvmName("PikoDriveRepositoryKt")

package dev.piko.shared.data

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.putIfAbsentCompat
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.thumbnailUrlOf
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal class DriveRepoContext(
    val clientManager: PikoClientProvider,
    val preferences: PikoUserPreferences?,
    val cacheStore: PikoCacheStore?,
    val treeDatabaseFactory: PikoTreeDatabaseFactory?,
    val backgroundScope: CoroutineScope,
    val folderChanges: MutableSharedFlow<DriveChange>,
    val clientRevision: MutableStateFlow<Long>,
    val vaultAccessor: () -> VaultStore,
    val clearChangesJournal: () -> Unit,
) {
    val currentAccount: String? get() = clientManager.currentClient.value?.account
    fun requireClient(): PikPakClient = clientManager.currentClient.value ?: error("Not logged in")

    lateinit var overlay: DriveVaultAndMountOverlay
    lateinit var index: FolderIndexCoordinator
    lateinit var listing: DriveListingEngine
    lateinit var mutations: DriveMutationExecutor
    lateinit var library: DriveLibraryAndSearchSource
}

/**
 * 归档虚拟覆盖层（[VaultStore]、[VaultTrees]、[VaultStars]）、来源账本（[SourceLedger]）、本地目录挂载（[LocalMounts]）、
 * 快速访问固定目录（[PinnedFolders]）以及 `.piko` 隐藏目录过滤。
 */
internal class DriveVaultAndMountOverlay(
    private val ctx: DriveRepoContext,
) {
    internal data class VaultBinding(val client: PikPakClient, val store: VaultStore)

    internal val vaultBinding = MutableStateFlow<VaultBinding?>(null)
    internal val vaultStoreLock = PikoLock()
    private val vaultHandoffs = HashMap<String, Job>()

    val localMounts: LocalMounts by lazy {
        LocalMounts(
            ctx.preferences,
            { ctx.currentAccount },
            { folders ->
                if (folders.isNotEmpty()) {
                    ctx.index.normalizeLocations()
                    ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(folders))
                }
            },
            ctx.backgroundScope,
        )
    }

    val pinnedFolders = PinnedFolders(ctx.preferences, ctx.backgroundScope)

    private val accountChanges: Flow<String?> =
        ctx.clientManager.currentClient.map { it?.account }.distinctUntilChanged()

    val pinnedFoldersFlow: Flow<List<PikoPathBreadcrumb>>
        get() = combine(
            pinnedFolders.flow,
            ctx.index.folderIndex.flow,
            localMounts.flow,
            accountChanges,
        ) { pins, _, mounts, _ ->
            pins.filterNot { LocalLocation.isLocal(it.id) }.map(ctx.index.folderIndex::withCurrentName) +
                mounts.filter { it.account == ctx.currentAccount && it.pinned }
                    .map { PikoPathBreadcrumb(it.location.id, it.fallbackName) }
        }

    fun pinFolder(folder: PikoPathBreadcrumb) {
        if (LocalLocation.isLocal(folder.id)) {
            ctx.backgroundScope.launch { localMounts.pin(folder.id, true) }
        } else {
            pinnedFolders.pin(folder)
        }
    }

    fun unpinFolder(folderId: String) {
        if (LocalLocation.isLocal(folderId)) {
            ctx.backgroundScope.launch { localMounts.pin(folderId, false) }
        } else {
            pinnedFolders.unpin(folderId)
        }
    }

    val vaultTrees = VaultTrees(ctx.cacheStore, ctx.backgroundScope)
    val vaultStars = VaultStars(ctx.cacheStore, ctx.backgroundScope)
    val sourceLedger = SourceLedger(ctx.cacheStore, ctx.backgroundScope)

    val fullyVaultedFolders: StateFlow<Set<String>> by lazy {
        combine(ctx.listing.childContents.fullyVaultedFolders, vaultedFolders) { full, marked -> full intersect marked }
            .stateIn(ctx.backgroundScope, SharingStarted.Eagerly, emptySet())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val vaultedFolders: StateFlow<Set<String>> by lazy {
        combine(
            ctx.listing.childContents.vaultedFolders,
            ctx.listing.childContents.listedFolders,
            vaultTrees.flow,
            ctx.index.treeBindingFlow.flatMapLatest { it.flow },
        ) { direct, listed, trees, treeState ->
            val base = VaultTrees.marked(direct, listed, trees).toMutableSet()
            val stats = treeState.sortProjection?.subtreeStats ?: SortContext.of(treeState).subtreeStats
            for ((folderId, subtree) in stats) {
                if (folderId.isEmpty() || !subtree.structuralValid) continue
                if (subtree.vaulted.fileCount > 0L) {
                    base += folderId
                } else if (subtree.vaulted.listingComplete) {
                    base -= folderId
                }
            }
            base
        }.stateIn(ctx.backgroundScope, SharingStarted.Eagerly, emptySet())
    }

    val vaultCoverChanges: Flow<Unit>
        get() = combine(ctx.listing.childContents.vaultCovers, vaultTrees.flow, vaultedFolders) { _, _, _ -> }

    private val loggedOutVault = VaultStore(object : VaultFolderIo {
        override suspend fun list(folderId: String): List<FileStat> =
            error(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
        override suspend fun read(fileId: String): ByteArray =
            error(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String =
            error(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
        override suspend fun delete(ids: List<String>): Unit =
            error(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
    })

    fun currentParsedManifestIds(): Set<String> =
        vaultBinding.value?.store?.parsedFlow?.value?.keys.orEmpty()

    fun parsedManifestIdsForAccount(account: String?): Set<String> =
        vaultBinding.value?.takeIf { it.client.account == account }?.store?.parsedFlow?.value?.keys.orEmpty()

    val hasPendingBoundVaultWrites: Boolean
        get() = vaultBinding.value?.let {
            it.client === ctx.clientManager.currentClient.value && it.store.hasPendingWrites
        } == true

    internal fun retireVaultOnAccountSwitchLocked(newAccount: String?) {
        withPikoLock(vaultStoreLock) {
            vaultBinding.value?.takeIf { it.client.account != newAccount }?.let(::retireVaultStoreLocked)
        }
    }

    private fun retireVaultStoreLocked(binding: VaultBinding) {
        val slot = logAccountSlot(binding.client.account)
        vaultHandoffs[binding.client.account] = binding.store.close().also { closing ->
            closing.invokeOnCompletion { failure ->
                if (failure != null && failure !is kotlinx.coroutines.CancellationException) {
                    PikoLog.w(DRIVE_REPO_TAG, "Failed to flush retiring vault store (acct=$slot)", failure)
                }
            }
        }
        if (vaultBinding.value === binding) vaultBinding.value = null
    }

    fun defaultVault(): VaultStore {
        val asked = ctx.clientManager.currentClient.value ?: return loggedOutVault
        return storeForClient(asked)
    }

    fun vaultForClient(asked: PikPakClient): VaultStore {
        val store = ctx.vaultAccessor()
        val binding = vaultBinding.value?.takeIf { it.store === store }
        if (binding == null) return store
        if (binding.client === asked) return store
        return storeForClient(asked)
    }

    private fun storeForClient(asked: PikPakClient): VaultStore = withPikoLock(vaultStoreLock) {
        val current = vaultBinding.value
        if (current?.client === asked) return@withPikoLock current.store
        if (ctx.clientManager.currentClient.value !== asked) throw kotlinx.coroutines.CancellationException("Account changed")
        current?.let(::retireVaultStoreLocked)
        vaultHandoffs.entries.removeAll { it.value.isCompleted }
        val boundIndex = ctx.index.driveTreeIndex
        val store = VaultStore(
            io = object : VaultFolderIo {
                override suspend fun list(folderId: String) = withContext(Dispatchers.Default) { asked.listFiles(folderId) }
                override suspend fun read(fileId: String) = withContext(Dispatchers.Default) { ctx.mutations.readBytes(asked, fileId) }
                override suspend fun upload(folderId: String, name: String, bytes: ByteArray) =
                    withContext(Dispatchers.Default) { ctx.mutations.uploadBytes(asked, folderId, name, bytes) }
                override suspend fun delete(ids: List<String>) = withContext(Dispatchers.Default) { asked.batchDelete(ids) }
            },
            onChanged = { folder ->
                withPikoLock(ctx.index.treeStateLock) {
                    if (ctx.clientManager.currentClient.value === asked && ctx.index.driveTreeIndex === boundIndex) {
                        val projected = vaultBinding.value?.takeIf { it.client === asked }?.store?.projectedEntries(folder)
                        if (projected == null) {
                            ctx.mutations.applyChange(DriveChange.ContentsChanged(setOf(folder)))
                        } else {
                            ctx.index.markChanged(setOf(folder))
                            vaultEntriesKnown(folder, projected, reconcileStars = false)
                            ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(setOf(folder)))
                        }
                    }
                }
            },
            onCommitted = { folder, entries, snapshot ->
                withPikoLock(ctx.index.treeStateLock) {
                    if (ctx.clientManager.currentClient.value === asked && ctx.index.driveTreeIndex === boundIndex) {
                        commitVaultListing(asked, folder, entries, snapshot)
                    }
                }
            },
            currentFolderSerial = { withPikoLock(ctx.index.treeStateLock) { ctx.index.currentSerial } },
            cacheStore = ctx.cacheStore,
            account = asked.account,
            scope = ctx.backgroundScope,
            predecessor = vaultHandoffs[asked.account],
        )
        vaultBinding.value = VaultBinding(asked, store)
        store
    }

    fun vaultAccess(expectedAccount: String): VaultStore? {
        val asked = ctx.clientManager.currentClient.value?.takeIf { it.account == expectedAccount } ?: return null
        val store = ctx.vaultAccessor()
        val binding = vaultBinding.value?.takeIf { it.store === store }
        return store.takeIf { ctx.clientManager.currentClient.value === asked && (binding == null || binding.client === asked) }
    }

    suspend fun listBrowsable(
        parentId: String,
        sortOrder: PikoFileSortOrder,
        strictVaultRead: Boolean = false,
    ): Result<List<FileStat>> {
        if (LocalLocation.isLocal(parentId)) {
            return runSuspendCatching { ctx.listing.sortFiles(localMounts.list(parentId), sortOrder, parentId) }
        }
        localMounts.all()
        val asked = ctx.clientManager.currentClient.value ?: return Result.failure(
            IllegalStateException(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入")),
        )
        return listBrowsable(parentId, sortOrder, asked, ctx.vaultAccessor(), strictVaultRead = strictVaultRead)
    }

    fun browsableReader(expectedAccount: String): (suspend (String) -> Result<List<FileStat>>)? {
        val asked = ctx.clientManager.currentClient.value?.takeIf { it.account == expectedAccount } ?: return null
        val store = vaultForClient(asked)
        if (ctx.clientManager.currentClient.value !== asked) return null
        return browsableReaderForClient(asked, store, requireActiveClient = true)
    }

    fun browsableReaderForClient(
        asked: PikPakClient,
        store: VaultStore,
        requireActiveClient: Boolean = false,
    ): suspend (String) -> Result<List<FileStat>> = reader@{ folderId ->
        if (ctx.clientManager.currentClient.value !== asked && requireActiveClient) {
            throw kotlinx.coroutines.CancellationException("Account changed")
        }
        if (LocalLocation.isLocal(folderId)) return@reader runSuspendCatching { localMounts.list(folderId) }
        if (ctx.clientManager.currentClient.value === asked) {
            val driveTreeIndex = ctx.index.driveTreeIndex
            val state = driveTreeIndex.flow.value
            val children = driveTreeIndex.realChildren(folderId)
            val isFolderClean = folderId !in state.dirtyFolders &&
                ctx.listing.listings.value[folderId]?.stale != true && (
                    children.coverage == DriveTreeIndex.FolderCoverage.COMPLETE ||
                        (state.complete && (folderId.isEmpty() || folderId in state.entries))
                )
            if (isFolderClean) {
                val real = children.files
                val winnerManifest = store.winnerOf(real) ?: state.manifestsByFolder[folderId]
                val hasManifest = winnerManifest != null
                val pending = store.hasPending(folderId)
                val indexedVault = driveTreeIndex.vaultEntries(folderId)
                val cachedVault = store.cached(folderId, real)
                val hasUnresolvedManifest = hasManifest && !pending && indexedVault == null && cachedVault == null
                if (!hasUnresolvedManifest) {
                    val entries = when {
                        pending -> cachedVault ?: indexedVault.orEmpty()
                        indexedVault != null -> indexedVault
                        cachedVault != null -> {
                            if (hasManifest && winnerManifest != null) {
                                withPikoLock(ctx.index.treeStateLock) {
                                    if (ctx.clientManager.currentClient.value === asked) {
                                        vaultEntriesKnown(
                                            folderId,
                                            cachedVault,
                                            expectedManifestId = winnerManifest.id,
                                            requireMatch = true,
                                        )
                                    }
                                }
                            }
                            cachedVault
                        }
                        else -> emptyList()
                    }
                    return@reader Result.success(withVaulted(folderId, real, entries, PikoFileSortOrder.TIME_DESC))
                }
            }
            return@reader listBrowsable(folderId, PikoFileSortOrder.TIME_DESC, asked, store)
        }
        if (requireActiveClient) throw kotlinx.coroutines.CancellationException("Account changed")
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val raw = asked.listFiles(folderId)
                val entries = store.read(folderId, raw).getOrDefault(emptyList())
                ctx.listing.sortFiles(
                    raw.filterNot { isPikoInternalFolder(it, folderId) || VaultStore.isRecoveryFile(it) || VaultStore.looksLikeManifest(it) } +
                        entries.filter { it.isArchived }.map { it.toFileStat(folderId) },
                    PikoFileSortOrder.TIME_DESC,
                    folderId,
                )
            }
        }
    }

    internal suspend fun listBrowsable(
        parentId: String,
        sortOrder: PikoFileSortOrder,
        asked: PikPakClient,
        store: VaultStore,
        strictVaultRead: Boolean = false,
    ): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            if (ctx.clientManager.currentClient.value !== asked) throw kotlinx.coroutines.CancellationException("Account changed")
            val listing = ctx.listing.fetchListing(parentId, asked)
            if (ctx.clientManager.currentClient.value !== asked) throw kotlinx.coroutines.CancellationException("Account changed")
            val entries = readAndRecordVaultEntries(
                parentId,
                listing,
                store,
                asked,
                logFailure = true,
                rethrowFailure = strictVaultRead,
            )
            if (ctx.clientManager.currentClient.value !== asked) throw kotlinx.coroutines.CancellationException("Account changed")
            withVaulted(parentId, listing, entries, sortOrder)
        }
    }

    fun cachedBrowsable(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? {
        val store = ctx.vaultAccessor()
        ctx.listing.cachedListing(folderId)?.let { listing ->
            return withVaulted(folderId, listing, store.cached(folderId, listing).orEmpty(), sortOrder)
        }
        val driveTreeIndex = ctx.index.driveTreeIndex
        val state = driveTreeIndex.flow.value
        val children = driveTreeIndex.realChildren(folderId)
        if (children.coverage == DriveTreeIndex.FolderCoverage.COMPLETE ||
            (state.complete && children.coverage != DriveTreeIndex.FolderCoverage.DIRTY && (folderId.isEmpty() || folderId in state.entries))
        ) {
            val entries = driveTreeIndex.vaultEntries(folderId) ?: store.cached(folderId, children.files).orEmpty()
            return withVaulted(folderId, children.files, entries, sortOrder)
        }
        return null
    }

    internal fun withVaulted(
        folderId: String,
        listing: List<FileStat>,
        entries: List<VaultEntry>,
        sortOrder: PikoFileSortOrder,
    ): List<FileStat> {
        val visible = withVaultCovers(
            ctx.mutations.projectOptimisticFiles(listing, folderId)
                .filterNot { isPikoInternalFolder(it, folderId) || VaultStore.isRecoveryFile(it) },
        ) + localMounts.cachedRoots(folderId)
        if (entries.isEmpty() && visible.none(VaultStore::looksLikeManifest)) {
            return ctx.listing.sortFiles(visible, sortOrder, folderId)
        }
        val real = visible.filterNot(VaultStore::looksLikeManifest)
        val restoredMetadata = entries.filterNot { it.isArchived }
        val enriched = if (restoredMetadata.isEmpty()) {
            real
        } else {
            val byFileId = HashMap<String, Pair<Int, VaultEntry>>()
            val byName = HashMap<String, Pair<Int, VaultEntry>>()
            restoredMetadata.forEachIndexed { index, entry ->
                val gcidKey = "${entry.gcid.uppercase()}:${entry.size}"
                val fileId = entry.restoredFileId
                if (!fileId.isNullOrEmpty()) {
                    byFileId.putIfAbsentCompat("$gcidKey|id:$fileId", index to entry)
                }
                byName.putIfAbsentCompat("$gcidKey|name:${entry.name}", index to entry)
            }
            real.map { file ->
                if (file.isFolder) return@map file
                val gcidKey = "${file.hash.uppercase()}:${file.sizeBytes}"
                val matchById = byFileId["$gcidKey|id:${file.id}"]
                val matchByName = byName["$gcidKey|name:${file.name}"]
                val matched = when {
                    matchById != null && matchByName != null ->
                        if (matchById.first <= matchByName.first) matchById.second else matchByName.second
                    matchById != null -> matchById.second
                    matchByName != null -> matchByName.second
                    else -> null
                }
                matched?.enrich(file, preserveStar = vaultStars.isStarred(matched.id)) ?: file
            }
        }
        return ctx.listing.sortFiles(
            enriched + entries.filter { it.isArchived }.map { it.toFileStat(folderId, starred = vaultStars.isStarred(it.id)) },
            sortOrder,
            folderId,
        )
    }

    fun withVaultCovers(files: List<FileStat>): List<FileStat> {
        if (files.none { it.isFolder && (it.thumbnailLink.isEmpty() || it.id in vaultedFolders.value) && vaultCoverOf(it.id) != null }) {
            return files
        }
        return files.map { file ->
            if (!file.isFolder || (file.thumbnailLink.isNotEmpty() && file.id !in vaultedFolders.value)) return@map file
            vaultCoverOf(file.id)?.let { cover ->
                val url = if (cover.startsWith("img:")) imageThumbnailUrlOf(cover.removePrefix("img:")) else thumbnailUrlOf(cover)
                file.copy(thumbnailLink = url)
            } ?: file
        }
    }

    private fun vaultCoverOf(folderId: String): String? {
        val covers = ctx.listing.childContents.vaultCovers.value
        val driveTreeIndex = ctx.index.driveTreeIndex
        val treeState = driveTreeIndex.flow.value
        val subtreeStats = treeState.sortProjection?.subtreeStats ?: driveTreeIndex.fastSortContext().subtreeStats
        val indexNodes = ctx.index.folderIndex.flow.value.nodes
        val visited = mutableSetOf<String>()
        fun isMovedAway(childId: String, expectedParent: String): Boolean {
            val actualParent = indexNodes[childId]?.takeUnless { it.provisional }?.parentId
                ?: treeState.entries[childId]?.parentId
            return actualParent != null && actualParent != expectedParent
        }
        fun cover(id: String): String? {
            if (!visited.add(id)) return null
            covers[id]?.ifEmpty { null }?.let { return it }
            vaultTrees.flow.value[id]?.sorted()?.firstNotNullOfOrNull { childId ->
                if (isMovedAway(childId, id)) null else cover(childId)
            }?.let { return it }
            val treeChildren = treeState.childrenByParent[id]
            if (!treeChildren.isNullOrEmpty()) {
                return treeChildren.sorted().firstNotNullOfOrNull { childId ->
                    val childStats = subtreeStats[childId]
                    val hasVaultInSubtree = (childStats?.structuralValid == true && childStats.vaulted.fileCount > 0L) || childId in covers
                    if (hasVaultInSubtree) cover(childId) else null
                }
            }
            return null
        }
        return cover(folderId)
    }

    suspend fun fetchVaultCover(folderId: String) = withContext(Dispatchers.Default) {
        if (vaultCoverOf(folderId) != null) return@withContext
        val childContents = ctx.listing.childContents
        val known = childContents.vaultCovers.value
        val direct = folderId.takeIf { it in childContents.vaultedFolders.value && it !in known }
        val descendants = mutableSetOf<String>()
        fun visit(id: String) {
            if (!descendants.add(id)) return
            vaultTrees.flow.value[id].orEmpty().forEach(::visit)
        }
        visit(folderId)
        val members = descendants.sorted().filter { it != folderId && it !in known }
        for (candidate in listOfNotNull(direct) + members.take(COVER_PROBE_MEMBERS)) {
            ctx.listing.childNameFetches.withPermit {
                if (candidate in childContents.vaultCovers.value) return@withPermit
                val asked = ctx.requireClient()
                val listing = ctx.listing.listAllFiles(candidate).getOrNull() ?: return@withPermit
                readAndRecordVaultEntries(
                    candidate,
                    listing,
                    ctx.vaultAccessor(),
                    asked,
                    logFailure = true,
                    logTagSuffix = " (cover probe)",
                )
            }
            if (vaultCoverOf(folderId) != null) return@withContext
        }
    }

    fun knownVaultParents(): Map<String, String> = ctx.index.folderIndex.flow.value.nodes
        .filterValues { !it.provisional }
        .mapNotNull { (id, node) -> node.parentId?.let { id to it } }
        .toMap()

    fun knownVaultMetaOf(gcid: String, size: Long): VaultMediaMeta? {
        if (gcid.isBlank()) return null
        val fromVault = vaultBinding.value?.store?.knownMetaOf(gcid, size)
        if (fromVault != null && fromVault.cid != null && fromVault.duration != null &&
            fromVault.width != null && fromVault.height != null && fromVault.mimeType != null
        ) {
            return fromVault
        }
        val content = ContentKey.of(gcid, size)?.let(ctx.index.driveTreeIndex::contentMetadata)
        val fromTree = content?.let {
            VaultMediaMeta(
                cid = it.cid,
                duration = it.durationMillis?.let { millis -> (millis / 1000.0).toString() },
                width = it.width?.toString(),
                height = it.height?.toString(),
                mimeType = it.mimeType,
            )
        }
        return (fromVault ?: VaultMediaMeta()).mergeWith(fromTree).takeUnless { it.isEmpty }
    }

    /** 按整批条目一次性批量预取 SQLite 内容元数据，避免数千条引用保存逐行触发单键查询。 */
    fun knownVaultMetaLookupFor(items: Collection<InstantFileItem>): (String, Long) -> VaultMediaMeta? {
        val store = vaultBinding.value?.store
        val missingTreeKeys = HashSet<ContentKey>()
        for (item in items) {
            val base = item.meta
            if (base != null && base.cid != null && base.duration != null &&
                base.width != null && base.height != null && base.mimeType != null
            ) {
                continue
            }
            val gcid = item.file.gcid ?: continue
            val key = ContentKey.of(gcid, item.file.size) ?: continue
            val fromVault = store?.knownMetaOf(gcid, item.file.size)
            val combined = (base ?: VaultMediaMeta()).mergeWith(fromVault)
            if (combined.cid == null || combined.duration == null ||
                combined.width == null || combined.height == null || combined.mimeType == null
            ) {
                missingTreeKeys += key
            }
        }
        val treeBatch = if (missingTreeKeys.isEmpty()) {
            emptyMap()
        } else {
            ctx.index.driveTreeIndex.contentMetadataBatch(missingTreeKeys)
        }
        return lookup@{ gcid, size ->
            if (gcid.isBlank()) return@lookup null
            val fromVault = store?.knownMetaOf(gcid, size)
            val fromTree = ContentKey.of(gcid, size)?.let(treeBatch::get)?.let {
                VaultMediaMeta(
                    cid = it.cid,
                    duration = it.durationMillis?.let { millis -> (millis / 1000.0).toString() },
                    width = it.width?.toString(),
                    height = it.height?.toString(),
                    mimeType = it.mimeType,
                )
            }
            (fromVault ?: VaultMediaMeta()).mergeWith(fromTree).takeUnless { it.isEmpty }
        }
    }

    fun rememberFullyVaulted(folderIds: Set<String>) {
        ctx.listing.childContents.markFullyVaulted(folderIds, knownVaultParents())
    }

    fun vaultEntriesKnown(
        folderId: String,
        entries: List<VaultEntry>,
        expectedManifestId: String? = null,
        requireMatch: Boolean = false,
        reconcileStars: Boolean = true,
    ) {
        val starredIds = vaultStars.flow.value.values.asSequence().filter { it.starred }.mapTo(HashSet()) { it.entryId }
        val optimistic = vaultBinding.value?.store?.hasPending(folderId) == true
        if (!ctx.index.driveTreeIndex.putVaultEntries(folderId, entries, expectedManifestId, requireMatch, starredIds, optimistic = optimistic)) {
            if (requireMatch) {
                PikoLog.d(DRIVE_REPO_TAG, "Skipped stale vault entries for ${logFolder(folderId)} (expectedManifest=${expectedManifestId ?: "none"})")
            }
            return
        }
        if (reconcileStars) {
            vaultStars.reconcileFolder(folderId, entries, expectedAccount = ctx.currentAccount)
        }
        val hasArchived = entries.any { it.isArchived }
        ctx.listing.childContents.markVaulted(folderId, hasArchived)
        ctx.listing.childContents.rememberCover(folderId, entries.coverGcid().orEmpty())
        if (folderId.isNotEmpty()) {
            val maxNames = DriveListingEngine.MAX_REMEMBERED_CHILD_NAMES
            val realChildren = ctx.index.driveTreeIndex.realChildren(folderId).files
                .filterNot { it.isFolder || it.trashed || VaultStore.looksLikeManifest(it) || VaultStore.isRecoveryFile(it) }
            val childFiles = buildList {
                realChildren.asSequence().take(maxNames).forEach { add(ChildFile.of(it)) }
                if (size < maxNames && hasArchived) {
                    entries.asSequence()
                        .filter { it.isArchived }
                        .take(maxNames - size)
                        .forEach { add(ChildFile.of(it.toFileStat(folderId))) }
                }
            }
            if (childFiles.isNotEmpty() || ctx.listing.childContents.get(folderId) != null) {
                ctx.listing.childContents.put(folderId, childFiles)
            }
        }
    }

    internal fun commitVaultListing(
        asked: PikPakClient,
        folderId: String,
        entries: List<VaultEntry>,
        snapshot: VaultCommitSnapshot?,
    ) {
        when (snapshot) {
            null -> {
                if (ctx.currentAccount == asked.account) {
                    vaultEntriesKnown(folderId, entries)
                    ctx.mutations.applyChange(DriveChange.ContentsChanged(setOf(folderId)))
                }
            }
            is VaultCommitSnapshot.IncrementalFiles -> {
                val enrichedAdded = sourceLedger.enrich(snapshot.added)
                val applied = withPikoLock(ctx.index.treeStateLock) {
                    if (ctx.clientManager.currentClient.value !== asked) return@withPikoLock false
                    for (added in enrichedAdded) {
                        ctx.index.driveTreeIndex.applyChange(
                            DriveChange.Created(parentId = folderId, id = added.id, name = added.name, isFolder = added.isFolder, stat = added),
                        )
                    }
                    vaultEntriesKnown(folderId, entries)
                    ctx.listing.listingRequests.update { it - folderId }
                    ctx.listing.listings.update { cache ->
                        cache.mapValues { (id, listing) -> if (id == folderId) listing.expired() else listing }
                    }
                    ctx.listing.markFolderNotEmpty(folderId)
                    true
                }
                if (!applied) return
                ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(setOf(folderId)))
            }
            is VaultCommitSnapshot.FullListing -> {
                val enriched = sourceLedger.enrich(snapshot.listing)
                var indexUpdated = false
                var reparented: Set<String> = emptySet()
                var staleConcurrentMutation = false
                val applied = withPikoLock(ctx.index.treeStateLock) {
                    if (ctx.clientManager.currentClient.value !== asked) return@withPikoLock false
                    val serialOk = snapshot.listedAtSerial == null || ctx.index.unchangedSince(folderId, snapshot.listedAtSerial)
                    if (!serialOk) {
                        vaultEntriesKnown(folderId, entries)
                        staleConcurrentMutation = true
                        return@withPikoLock true
                    }
                    reparented = ctx.index.driveTreeIndex.putFolderListing(folderId, enriched, complete = true)
                    ctx.index.markFolderListed(folderId, reparented)
                    ctx.listing.listingRequests.update { it - folderId }
                    ctx.listing.rememberListing(folderId, enriched)
                    ctx.listing.rememberChildContents(folderId, enriched, complete = true)
                    indexUpdated = ctx.index.folderIndex.listed(folderId, enriched, complete = true)
                    vaultEntriesKnown(folderId, entries)
                    true
                }
                if (!applied) return
                if (staleConcurrentMutation) {
                    ctx.mutations.applyChange(DriveChange.ContentsChanged(setOf(folderId)))
                    return
                }
                if (indexUpdated) ctx.index.normalizeLocations()
                ctx.folderChanges.tryEmit(DriveChange.ContentsChanged(setOf(folderId) + reparented))
            }
        }
    }

    internal suspend fun readAndRecordVaultEntries(
        folderId: String,
        listing: List<FileStat>,
        store: VaultStore,
        asked: PikPakClient,
        logFailure: Boolean = false,
        logTagSuffix: String = "",
        rethrowFailure: Boolean = false,
    ): List<VaultEntry> {
        val boundIndex = withPikoLock(ctx.index.treeStateLock) { ctx.index.driveTreeIndex }
        val startedFromPending = store.hasPending(folderId)
        val expectedManifestId = store.winnerOf(listing)?.id
        val result = store.read(folderId, listing).let {
            if (logFailure) {
                it.logFailure(DRIVE_REPO_TAG, "Failed to read vault manifest in ${logFolder(folderId)} (manifest=${expectedManifestId ?: "none"})$logTagSuffix")
            } else {
                it
            }
        }
        val entries = if (rethrowFailure) result.getOrThrow() else (result.getOrNull() ?: return emptyList())
        withPikoLock(ctx.index.treeStateLock) {
            if (ctx.clientManager.currentClient.value !== asked || ctx.index.driveTreeIndex !== boundIndex) return entries
            val currentlyPending = store.hasPending(folderId)
            if (startedFromPending) {
                if (currentlyPending) {
                    vaultEntriesKnown(folderId, entries, reconcileStars = false)
                }
            } else if (!currentlyPending) {
                vaultEntriesKnown(folderId, entries, expectedManifestId = expectedManifestId, requireMatch = true, reconcileStars = true)
            }
        }
        return entries
    }

    fun startBackgroundObservers() {
        ctx.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            ctx.clientManager.currentClient.collect {
                ctx.listing.childContents.switchAccount(it?.account)
                vaultTrees.switchAccount(it?.account)
                vaultStars.switchAccount(it?.account)
                sourceLedger.switchAccount(it?.account)
            }
        }
        ctx.backgroundScope.launch {
            vaultStars.changedFolders.collect { folders ->
                if (folders.isNotEmpty()) ctx.mutations.notifyFoldersRefreshed(folders)
            }
        }
        ctx.backgroundScope.launch {
            sourceLedger.arrivals.collect { keys ->
                val folders = ctx.listing.listings.value.filterValues { listing ->
                    listing.files.any {
                        !it.isFolder && it.sourceUrl.isNullOrBlank() && SourceLedger.keyOf(it.hash, it.sizeBytes) in keys
                    }
                }.keys
                if (folders.isNotEmpty()) ctx.mutations.applyChange(DriveChange.ContentsChanged(folders))
            }
        }
    }

    private companion object {
        const val COVER_PROBE_MEMBERS = 3
    }
}
