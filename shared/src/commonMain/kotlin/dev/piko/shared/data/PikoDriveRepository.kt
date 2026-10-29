package dev.piko.shared.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.io.Buffer
import io.ktor.utils.io.toByteArray
import io.github.nihildigit.pikpak.UploadStart
import io.github.nihildigit.pikpak.cancelUpload
import io.github.nihildigit.pikpak.continueUpload
import io.github.nihildigit.pikpak.startUpload
import io.github.nihildigit.pikpak.streamRangeFromUrl
import io.github.nihildigit.pikpak.upload
import io.github.nihildigit.pikpak.PikPakHash
import dev.piko.data.repository.NaturalOrder
import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.data.auth.PikoUserPreferences
import io.github.nihildigit.pikpak.EventPage
import io.github.nihildigit.pikpak.EventType
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileFilter
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.QuotaResponse
import io.github.nihildigit.pikpak.LeaseBudget
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.instantCreate
import io.github.nihildigit.pikpak.sampleCid
import io.github.nihildigit.pikpak.SearchHit
import io.github.nihildigit.pikpak.CreatedShare
import io.github.nihildigit.pikpak.ShareInfo
import io.github.nihildigit.pikpak.ShareListPage
import io.github.nihildigit.pikpak.createShare
import io.github.nihildigit.pikpak.deleteShares
import io.github.nihildigit.pikpak.listMyShares
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.TransferQuota
import io.github.nihildigit.pikpak.batchCopy
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.batchMove
import io.github.nihildigit.pikpak.batchTrash
import io.github.nihildigit.pikpak.batchUntrash
import io.github.nihildigit.pikpak.clearEvents
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.deleteEvents
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.getShareInfo
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.listShareFiles
import io.github.nihildigit.pikpak.restoreShare
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.getTransferQuota
import io.github.nihildigit.pikpak.listAllDriveEntriesFlow
import io.github.nihildigit.pikpak.listAllDriveFolders
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listFilesPaged
import io.github.nihildigit.pikpak.listPlayHistory
import io.github.nihildigit.pikpak.listEvents
import io.github.nihildigit.pikpak.listStarred
import io.github.nihildigit.pikpak.listTrash
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.searchFiles
import io.github.nihildigit.pikpak.starFiles
import io.github.nihildigit.pikpak.unstarFiles
import io.github.nihildigit.pikpak.thumbnailUrlOf
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.describeFolder
import dev.piko.shared.naming.toFileKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class PikoFileSortOrder { NAME_ASC, NAME_DESC, TIME_DESC, TIME_ASC, SIZE_DESC, SIZE_ASC, DURATION_DESC, DURATION_ASC }

data class PikoPathBreadcrumb(val id: String, val name: String)

/** 列表的滚动位置：首个可见项的下标与它已滚出顶端的像素数。 */
data class ScrollAnchor(val index: Int, val offset: Int)

/** 目录的递归统计。[progress] 区分仍在统计、统计完、因到达上限而中止三种。 */
data class FolderUsage(val fileCount: Int, val bytes: Long, val progress: Progress) {
    enum class Progress { COUNTING, COMPLETE, TRUNCATED }
}

private const val FOLDER_USAGE_CONCURRENCY = 4

open class PikoDriveRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences? = null,
    private val cacheStore: PikoCacheStore? = null,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    // 与进程同寿：仓库本身就是进程级的
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 读写发生在 Dispatchers.Default 的多个线程上，普通 HashMap 并发写会丢项甚至破坏结构。
    // commonMain 没有 ConcurrentHashMap，借 MutableStateFlow.update 的 CAS 做原子替换。
    private val folderMeaninglessCacheFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    protected val folderMeaninglessCache: Map<String, Boolean> get() = folderMeaninglessCacheFlow.value
    private val _quotaFlow = MutableStateFlow<QuotaResponse?>(null)
    val quotaFlow: StateFlow<QuotaResponse?> = _quotaFlow.asStateFlow()

    // 带上取的是哪个账号：取回来之前可能已经换了号，按 quotaFlow 的当下值记会记到新账号头上
    private val _quotaUpdates = MutableSharedFlow<Pair<String, QuotaResponse>>(extraBufferCapacity = 4)
    val quotaUpdates: SharedFlow<Pair<String, QuotaResponse>> = _quotaUpdates.asSharedFlow()
    // 不落盘：与存储配额不同，官方也没有把它算进「上次已知值」这类离线展示的必要
    private val _transferQuotaFlow = MutableStateFlow<TransferQuota?>(null)
    val transferQuotaFlow: StateFlow<TransferQuota?> = _transferQuotaFlow.asStateFlow()

    /**
     * 当前账号是不是免费账号，未取到时为 null。取自流量额度的 vip_status，登录与换号时各取一次。
     * 免费账号的保存与离线规则不同，见 SDK 的 FreeAccountProbeTest 实测结论。
     */
    val isFreeAccountFlow: StateFlow<Boolean?> =
        _transferQuotaFlow.map { it?.account?.isPremium?.not() }.stateIn(backgroundScope, SharingStarted.Eagerly, null)
    /** 文件夹的名字与上级，见 [FolderIndex]。下面的路径栈、标签、历史与最近去过都随它改正。 */
    internal val folderIndex = FolderIndex()
    internal val driveTreeIndex = DriveTreeIndex()
    private val treeStateLock = Any()
    private val treeSyncJob = MutableStateFlow<Pair<PikPakClient, Deferred<Unit>>?>(null)
    private var pendingTreeSave: kotlinx.coroutines.Job? = null
    private var lastCanaryProbeAt: TimeSource.Monotonic.ValueTimeMark? = null

    data class TreeBuildProgress(
        val isBuilding: Boolean = false,
        val isInitialBlocking: Boolean = false,
        val foldersRead: Int = 0,
        val filesRead: Int = 0,
    )

    private val _treeBuildProgressFlow = MutableStateFlow(TreeBuildProgress())
    val treeBuildProgressFlow: StateFlow<TreeBuildProgress> = _treeBuildProgressFlow.asStateFlow()

    enum class CanaryProbeResult { UNCHANGED, INCREMENTAL_PATCH, FULL_REBUILD }

    val currentAccount: String? get() = clientManager.currentClient.value?.account
    val accountChanges: Flow<String?> = clientManager.currentClient.map { it?.account }.distinctUntilChanged()
    private val _clientRevision = MutableStateFlow(0L)
    val clientRevision: StateFlow<Long> = _clientRevision.asStateFlow()

    fun writeAccess(expectedAccount: String): DriveWriteAccess? = clientManager.currentClient.value
        ?.takeIf { it.account == expectedAccount }?.let { DriveWriteAccess(it.account, it, this) }

    fun historyAccess(expectedAccount: String): HistoryWriteAccess? = clientManager.currentClient.value
        ?.takeIf { it.account == expectedAccount }?.let(::HistoryWriteAccess)

    internal fun applyWriteChange(account: String, change: DriveChange) {
        if (currentAccount != account) return
        if (change is DriveChange.Renamed) pinnedFolders.renamed(change.id, change.name)
        applyChange(change)
    }

    private val references = MutableStateFlow<Set<FolderReferenceSource>>(emptySet())
    internal fun registerFolderReferences(source: FolderReferenceSource) { references.update { it + source } }
    internal fun unregisterFolderReferences(source: FolderReferenceSource) { references.update { it - source } }

    private val dataAccount = MutableStateFlow<String?>(null)
    private val pendingServerDeletes = MutableStateFlow<Set<String>>(emptySet())

    fun enterAccount(account: String?) {
        vaultBinding.value?.takeIf { it.client.account != account }?.store?.flushPendingSave()
        vaultBinding.update { it?.takeIf { binding -> binding.client.account == account } }
        while (true) {
            val previous = dataAccount.value
            if (previous == account) return
            if (!dataAccount.compareAndSet(previous, account)) continue
            pendingServerDeletes.value = emptySet()
            if (previous == null) return
            flushPendingTreeSnapshotSave(previous)
            break
        }
        // 还在路上的列目录属于上一个账号，回来的结果不能记到新账号名下
        synchronized(treeStateLock) {
            resetChangeMarksForAccountSwitch()
            lastCanaryProbeAt = null
            _treeBuildProgressFlow.value = TreeBuildProgress()
            treeSyncJob.update { current ->
                current?.second?.cancel()
                null
            }
            listingRequests.value = emptyMap()
            listings.value = emptyMap()
            folderIndex.clear()
            driveTreeIndex.clear()
            folderMeaninglessCacheFlow.value = emptyMap()
            _folderEmptiness.value = emptyMap()
        }
        changes.clear()
    }

    private val pinnedFolders = PinnedFolders(preferences, backgroundScope)

    /** 固定到快速访问的文件夹，按固定的先后排，见 [PinnedFolders]。名字经文件夹索引换成眼下的。 */
    val pinnedFoldersFlow: Flow<List<PikoPathBreadcrumb>>
        get() = combine(pinnedFolders.flow, folderIndex.flow) { pins, _ -> pins.map(folderIndex::withCurrentName) }

    fun pinFolder(folder: PikoPathBreadcrumb) = pinnedFolders.pin(folder)

    fun unpinFolder(folderId: String) = pinnedFolders.unpin(folderId)

    /** 做过的改动，能撤销的记在这里，见 [DriveChangeJournal]。 */
    val changes = DriveChangeJournal(this, backgroundScope)

    // 用事件流而非 StateFlow：订阅方只需被动收到「哪里变了」，不需要初值，也不该在重组时重放。
    // 订阅方只是据此重列，丢掉最旧的几条好过让发出改动的一方卡住
    private val _folderChanges = MutableSharedFlow<DriveChange>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 网盘里的改动，见 [DriveChange]。网盘页与目录图据此刷新受影响的那几层。 */
    val folderChanges: SharedFlow<DriveChange> = _folderChanges.asSharedFlow()

    /**
     * 网盘里的改动都经这里收口：仓库自己做成的新建、改名、移动、删除，以及界面外的上传、解压、转存、归档。
     * 文件夹索引跟着改，各处存着的路径随之改正；受影响的列表标为过期，在路上的列目录不再合并进来；
     * 最后广播给订阅者。新加改动网盘的入口照此调一次，不要自己清缓存或通知界面。
     */
    fun applyChange(change: DriveChange) = applyChange(change, notifyChange = true)

    private fun applyChange(change: DriveChange, notifyChange: Boolean) {
        if (change is DriveChange.Deleted) pendingServerDeletes.update { it + change.ids }
        val indexChanged = synchronized(treeStateLock) {
            val knownParentsBeforeChange: Set<String> = when (change) {
                is DriveChange.Renamed -> setOfNotNull(change.parentId ?: knownParentOf(change.id) ?: driveTreeIndex.file(change.id)?.parentId)
                is DriveChange.Moved -> change.folders.orEmpty() + change.ids.mapNotNullTo(HashSet()) { knownParentOf(it) ?: driveTreeIndex.file(it)?.parentId } + change.to
                is DriveChange.Trashed -> change.folders.orEmpty() + change.ids.mapNotNullTo(HashSet()) { knownParentOf(it) ?: driveTreeIndex.file(it)?.parentId }
                is DriveChange.Deleted -> change.folders.orEmpty() + change.ids.mapNotNullTo(HashSet()) { knownParentOf(it) ?: driveTreeIndex.file(it)?.parentId }
                else -> change.folders.orEmpty()
            }
            val changed = when (change) {
                is DriveChange.Created -> folderIndex.learned(change.id, change.name, change.parentId, change.isFolder, trashed = false)
                is DriveChange.Renamed -> folderIndex.renamed(change.id, change.name, always = change.id in referencedNodeIds())
                is DriveChange.Moved -> folderIndex.moved(change.ids, change.to)
                is DriveChange.Trashed -> folderIndex.removed(change.ids, referencedNodeIds())
                is DriveChange.Deleted -> folderIndex.removed(change.ids, referencedNodeIds())
                is DriveChange.Restored -> folderIndex.restored(change.ids)
                is DriveChange.Copied, is DriveChange.ContentsChanged -> false
            }
            val folders = change.folders
            childContents.invalidate(folders)
            driveTreeIndex.applyChange(change)
            markChanged(
                folders = if (folders == null) null else folders + knownParentsBeforeChange,
                knownTouchedFolders = knownParentsBeforeChange,
                removedIds = when (change) {
                    is DriveChange.Trashed -> change.ids
                    is DriveChange.Deleted -> change.ids
                    else -> emptyList()
                },
                renamed = (change as? DriveChange.Renamed)?.let { it.id to it.name },
                moved = (change as? DriveChange.Moved)?.let { it.ids to it.to },
                invalidatesWholeTree = change is DriveChange.Restored || (change is DriveChange.ContentsChanged && change.folders == null),
            )
            val fencedFolders = if (folders == null) null else folders + knownParentsBeforeChange
            listingRequests.update { requests -> if (fencedFolders == null) emptyMap() else requests - fencedFolders }
            listings.update { cache -> cache.mapValues { (id, listing) ->
                when {
                    change is DriveChange.Renamed && notifyChange -> Listing(
                        listing.files.map { file -> if (file.id == change.id) file.copy(name = change.name) else file },
                        listing.listedAt, listing.stale,
                    )
                    folders == null || id in folders -> listing.expired()
                    else -> listing
                }
            } }
            _folderEmptiness.update { known -> if (folders == null) emptyMap() else known - folders }
            changed
        }
        if (indexChanged) normalizeLocations()
        if (driveTreeIndex.isComplete) scheduleTreeSnapshotSave()
        if (notifyChange) _folderChanges.tryEmit(change)
    }

    /**
     * 文件所在目录的完整路径栈（含根）。先查文件夹索引与列过的目录，缺哪一级才向服务端查那一级：
     * 服务端没有按 id 取路径的接口，只能沿 parent_id 逐级上溯，每级一次请求。查到的顺手记进索引。
     */
    suspend fun locateFolder(fileId: String): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = client
            val vaultEntryId = VaultEntry.entryIdOf(fileId)
            if (vaultEntryId != null) {
                val store = vault
                var parentId = store.folderOfEntry(vaultEntryId)
                if (parentId == null) {
                    val candidates = buildList {
                        add("")
                        addAll(childContents.vaultedFolders.value)
                        addAll(vaultTrees.flow.value.values.flatten())
                        addAll(vaultTrees.flow.value.keys)
                    }.distinct()
                    for (candidateId in candidates) {
                        runSuspendCatching {
                            val listing = cachedListing(candidateId) ?: fetchListing(candidateId, asked)
                            readAndRecordVaultEntries(candidateId, listing, store, asked)
                        }
                        parentId = store.folderOfEntry(vaultEntryId)
                        if (parentId != null) break
                    }
                    if (parentId == null) {
                        val discovered = runSuspendCatching { asked.listFiles(parentId = "*", pageSize = 500) }
                            .getOrDefault(emptyList()).filter(VaultStore::looksLikeManifest)
                            .map { it.parentId }.distinct().filterNot { it in candidates }
                        for (candidateId in discovered) {
                            runSuspendCatching {
                                val listing = fetchListing(candidateId, asked)
                                readAndRecordVaultEntries(candidateId, listing, store, asked)
                            }
                            parentId = store.folderOfEntry(vaultEntryId)
                            if (parentId != null) break
                        }
                    }
                }
                val resolvedParent = parentId ?: error("找不到归档条目所在的文件夹")
                if (resolvedParent.isEmpty()) return@runSuspendCatching listOf(ROOT_BREADCRUMB)
                val folder = folderIndex.node(resolvedParent)?.takeUnless { it.provisional }
                if (folder != null) {
                    val above = if (folder.parentId.isNullOrEmpty()) listOf(ROOT_BREADCRUMB) else locateFolder(resolvedParent).getOrThrow()
                    return@runSuspendCatching above + PikoPathBreadcrumb(resolvedParent, folder.name)
                }
                val detail = fetchDetail(resolvedParent, asked)
                val above = if (detail.parentId.isEmpty()) listOf(ROOT_BREADCRUMB) else locateFolder(resolvedParent).getOrThrow()
                return@runSuspendCatching above + PikoPathBreadcrumb(detail.id, detail.name)
            }
            val chain = ArrayDeque<PikoPathBreadcrumb>()
            var parentId = knownParentOf(fileId) ?: fetchDetail(fileId, asked).parentId
            repeat(MAX_LOCATE_DEPTH) {
                if (parentId.isEmpty()) return@runSuspendCatching listOf(ROOT_BREADCRUMB) + chain
                val known = folderIndex.node(parentId)?.takeUnless { it.provisional }
                val knownParent = known?.parentId
                if (known != null && knownParent != null) {
                    chain.addFirst(PikoPathBreadcrumb(parentId, known.name))
                    parentId = knownParent
                } else {
                    val folder = fetchDetail(parentId, asked)
                    chain.addFirst(PikoPathBreadcrumb(folder.id, folder.name))
                    parentId = folder.parentId
                }
            }
            error("目录层级过深")
        }
    }

    /** 信息流存盘的目录名与上级，只补索引的空缺，不用来改正路径栈，见 FolderIndex.seed。 */
    fun seedFolders(names: Map<String, String>, parents: Map<String, String>) {
        folderIndex.seed(names, parents)
    }

    /**
     * [fileId] 所在的文件夹：文件夹看索引，文件看列过的目录里有没有它。过期的列表不认：标为过期正是因为里面的东西挪过。
     * 都没有为 null。
     */
    internal fun knownParentOf(fileId: String): String? {
        folderIndex.node(fileId)?.takeUnless { it.provisional }?.parentId?.let { return it }
        driveTreeIndex.file(fileId)?.takeIf { isParentFreshInTree(it.parentId) }?.parentId?.let { return it }
        return listings.value.entries.firstOrNull { (_, listing) -> !listing.stale && listing.files.any { it.id == fileId } }?.key
    }
    internal fun knownFile(fileId: String): FileStat? = listings.value.values.asSequence().filter { !it.stale }
        .mapNotNull { it.files.firstOrNull { file -> file.id == fileId } }.firstOrNull()
        ?: driveTreeIndex.file(fileId)?.takeIf { isParentFreshInTree(it.parentId) }

    private fun isParentFreshInTree(parentId: String): Boolean {
        val state = driveTreeIndex.flow.value
        if (parentId in state.dirtyFolders) return false
        return parentId in state.listedFolders || state.complete
    }

    /** 这几项各自所在的文件夹；有一项不知道就是 null，订阅者按说不清处理。 */
    private fun knownParentsOf(ids: Collection<String>): Set<String>? = ids.mapTo(HashSet()) { knownParentOf(it) ?: return null }

    private fun referencedNodeIds(): Set<String> = references.value
        .flatMap { it.referencedPaths }
        .flatMapTo(HashSet()) { stack -> stack.mapNotNull { FolderIndex.nodeIdOf(it.id) } }

    private fun normalizeLocations() {
        references.value.forEach { it.refreshFolderReferences() }
    }

    /**
     * 地址栏输入的路径：从根起按名字逐层找文件夹，返回完整路径栈（含根）。
     * 服务端没有按路径取文件夹的接口，只能每层列一次目录。与眼前路径栈重合的前缀直接沿用，
     * 从当前位置往下走一两层时只多列那一两层。
     * 名字先比完全相同，没有再忽略大小写；同名的文件夹 PikPak 允许有几个，取第一个。
     */
    suspend fun resolveFolderPath(names: List<String>, from: List<PikoPathBreadcrumb> = listOf(ROOT_BREADCRUMB)): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            // 人在库里时路径栈的第一级不是根，地址栏里写的路径仍从根起
            val current = from.takeIf { it.library == null } ?: listOf(ROOT_BREADCRUMB)
            val shared = current.drop(1).zip(names).takeWhile { (crumb, name) -> crumb.name == name }.size
            val stack = current.take(1 + shared).toMutableList()
            for (name in names.drop(shared)) {
                val parentId = stack.last().id
                // 记下的表可能早于刚建、刚移进来的文件夹，找不到时重列一次再下结论
                stack += findFolder(subfolders(parentId).getOrThrow(), name)
                    ?: findFolder(subfolders(parentId, fresh = true).getOrThrow(), name)
                    ?: throw FolderNotFoundException(name)
            }
            stack
        }
    }

    private fun findFolder(folders: List<PikoPathBreadcrumb>, name: String) =
        folders.firstOrNull { it.name == name } ?: folders.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** [resolveFolderPath] 在某一层找不到 [name]。 */
    class FolderNotFoundException(val name: String) : Exception("找不到文件夹「$name」")

    /**
     * [folderId] 下的文件夹，按名字自然排序，根目录下不含 `.piko`。地址栏的 › 与补全用，与目录图、网盘页读同一份列表缓存，
     * 缓存里没有、已过期或 [fresh] 时才请求。库不是文件夹，下面没有子文件夹，也不拿它的 ID 去请求。
     */
    suspend fun subfolders(folderId: String, fresh: Boolean = false): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            if (!isDriveFolderId(folderId)) return@runSuspendCatching emptyList()
            val files = (if (fresh) null else freshListing(folderId)) ?: fetchListing(folderId)
            files.asSequence()
                .filter { it.isFolder && !it.trashed && !isPikoInternalFolder(it, folderId) }
                .sortedWith(compareBy(NaturalOrder) { it.name })
                .map { PikoPathBreadcrumb(it.id, it.name) }
                .toList()
        }
    }

    /** 目录图（见 FolderMap）里的一层：文件夹与文件，文件夹在前、各自按名字自然排序。列表来源同 [subfolders]。 */
    suspend fun folderMapLevel(folderId: String): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val files = freshListing(folderId) ?: fetchListing(folderId)
            files.filter { !it.trashed && !isPikoInternalFolder(it, folderId) && !VaultStore.looksLikeManifest(it) && !VaultStore.isRecoveryFile(it) }
                .sortedWith(compareBy<FileStat> { !it.isFolder }.thenBy(NaturalOrder) { it.name })
        }
    }

    /*
     * 列过的目录，按文件夹 ID，网盘页、目录图、地址栏的 › 与补全、命令面板读的都是这一份，不各存一份。
     * 放在仓库层而不是界面状态里，是因为网盘页切走再切回时界面状态会整个重建，列表要留下：
     * 返回上级、切回标签时先显示缓存，再在后台刷新。路径栈、各标签与浏览历史上的目录一直留着，
     * 其余的按最近列出的先后至多留 RECENT_LISTINGS 个，不跨进程。
     * 网盘里有改动时受影响的标为过期（applyChange）：先显示无妨，网盘页随即会重列；
     * 拿它当眼下内容用的（目录图、›、补全、定位）不认过期的，见 [freshListing]。
     * 按插入先后淘汰：Map 的 + 保留插入顺序，重列时挪到末尾，最久没列的排在最前。
     */
    private class Listing(val files: List<FileStat>, val listedAt: TimeSource.Monotonic.ValueTimeMark, val stale: Boolean = false) {
        fun expired() = if (stale) this else Listing(files, listedAt, stale = true)
    }

    private val listings = MutableStateFlow<Map<String, Listing>>(emptyMap())

    private fun rememberListing(folderId: String, files: List<FileStat>) {
        val kept = locationFolderIds()
        listings.update { cache ->
            val next = (cache - folderId) + (folderId to Listing(files, TimeSource.Monotonic.markNow()))
            val evictable = next.keys.filter { it !in kept }
            if (evictable.size <= RECENT_LISTINGS) next else next - evictable.take(evictable.size - RECENT_LISTINGS).toSet()
        }
    }

    private fun locationFolderIds(): Set<String> = references.value.flatMap { it.retainedPaths }.flatMapTo(HashSet()) { stack -> stack.map { it.id } }

    private fun cachedListing(folderId: String): List<FileStat>? = listings.value[folderId]?.files

    /**
     * 可以当作眼下内容的缓存：没过期，且在路径上（网盘页一直在刷新它们）或列出不到 [RECENT_LISTING_FRESH]。
     * 目录图再展开、换位置后重新展开到眼前的文件夹时，各层因此是当场出来的，不再每层一个请求。
     */
    private fun freshListing(folderId: String): List<FileStat>? {
        val listing = listings.value[folderId]?.takeUnless { it.stale }
        if (listing != null && (listing.listedAt.elapsedNow() < RECENT_LISTING_FRESH || folderId in locationFolderIds())) {
            return listing.files
        }
        val state = driveTreeIndex.flow.value
        if (state.complete && folderId !in state.dirtyFolders && (folderId.isEmpty() || folderId in state.entries)) {
            return driveTreeIndex.realChildren(folderId).files
        }
        return null
    }

    /*
     * 改动的序号，判断一次列目录的结果还能不能合并进缓存与索引：请求发出之后、回来之前它所在的文件夹有过改动，
     * 回来的多半是改动之前的样子，记下的话刚改的名字又被旧列表改回去。结果照样交给调用方，只是不记。
     * 同时记录后台双缓冲整树重建期间发生的目录变更、重列、删除、改名与移动，避免整树切换把重建期间的本地变更冲掉。
     */
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

    private val changeMarks = MutableStateFlow(ChangeMarks())

    private fun resetChangeMarksForAccountSwitch() = changeMarks.update { marks ->
        val serial = marks.serial + 1
        ChangeMarks(serial = serial, accountResetAt = serial, treeInvalidatedAt = serial, unknownAt = serial)
    }

    private fun markChanged(
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

    private fun markFolderListed(folderId: String, reparentedFrom: Set<String>) = changeMarks.update { marks ->
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

    private fun unchangedSince(folderId: String, serial: Long): Boolean {
        val marks = changeMarks.value
        return marks.unknownAt <= serial &&
            (marks.folderAt[folderId] ?: 0) <= serial &&
            (marks.patchedAt[folderId] ?: 0) <= serial
    }

    // 同一个目录同时有几处要列（网盘页重列、目录图刷新这一层）时合成一个请求
    private val listingRequests = MutableStateFlow<Map<String, Deferred<List<FileStat>>>>(emptyMap())

    /**
     * 向服务端列出 [parentId] 的全部条目，记进列表缓存与文件夹索引。请求挂在仓库的作用域上：
     * 先来的调用方被取消（网盘页换了目录），等着同一个结果的其余调用方不受连累。
     */
    private suspend fun fetchListing(parentId: String, asked: PikPakClient = client): List<FileStat> {
        val request = backgroundScope.async(start = CoroutineStart.LAZY) {
            val serial = synchronized(treeStateLock) { changeMarks.value.serial }
            // 补来源放在这里：列表缓存、目录图、地址栏与网盘页读的都是这一份
            val listed = asked.listFiles(parentId)
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            val files = sourceLedger.enrich(listed)
            var indexUpdated = false
            var reparentedFrom = emptySet<String>()
            var shouldSaveSnapshot = false
            synchronized(treeStateLock) {
                if (clientManager.currentClient.value === asked && unchangedSince(parentId, serial)) {
                    rememberListing(parentId, files)
                    rememberChildContents(parentId, files)
                    reparentedFrom = driveTreeIndex.putFolderListing(parentId, files)
                    markFolderListed(parentId, reparentedFrom)
                    if (reparentedFrom.isNotEmpty()) {
                        childContents.invalidate(reparentedFrom)
                        listingRequests.update { it - reparentedFrom }
                        listings.update { cache ->
                            cache.mapValues { (id, listing) -> if (id in reparentedFrom) listing.expired() else listing }
                        }
                        _folderEmptiness.update { it - reparentedFrom }
                    }
                    indexUpdated = folderIndex.listed(parentId, files)
                    shouldSaveSnapshot = driveTreeIndex.isComplete
                }
            }
            if (reparentedFrom.isNotEmpty()) {
                _folderChanges.tryEmit(DriveChange.ContentsChanged(reparentedFrom))
            }
            if (indexUpdated) normalizeLocations()
            if (shouldSaveSnapshot) scheduleTreeSnapshotSave()
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
        request.invokeOnCompletion { listingRequests.update { requests -> if (requests[parentId] === request) requests - parentId else requests } }
        request.start()
        return request.await()
    }

    /** 某个目录的缓存列表，按 [sortOrder] 排好：路径栈上的，或最近列过的。没有时为 null。 */
    fun cachedFiles(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? =
        cachedListing(folderId)?.let { sortFiles(it, sortOrder, folderId) }

    private data class VaultBinding(val client: PikPakClient, val store: VaultStore)
    private val vaultBinding = MutableStateFlow<VaultBinding?>(null)
    private val loggedOutVault = VaultStore(object : VaultFolderIo {
        override suspend fun list(folderId: String): List<FileStat> = error("未登录")
        override suspend fun read(fileId: String): ByteArray = error("未登录")
        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String = error("未登录")
        override suspend fun delete(ids: List<String>): Unit = error("未登录")
    })

    /** 缓存和待写投影随 client 分开；旧事务的所有 IO 始终使用它开始时的 client。 */
    open val vault: VaultStore get() {
        while (true) {
            val asked = clientManager.currentClient.value ?: return loggedOutVault
            val current = vaultBinding.value
            if (current?.client === asked) return current.store
            val store = VaultStore(
                io = object : VaultFolderIo {
                    override suspend fun list(folderId: String) = withContext(Dispatchers.Default) { asked.listFiles(folderId) }
                    override suspend fun read(fileId: String) = withContext(Dispatchers.Default) { readBytes(asked, fileId) }
                    override suspend fun upload(folderId: String, name: String, bytes: ByteArray) = withContext(Dispatchers.Default) { uploadBytes(asked, folderId, name, bytes) }
                    override suspend fun delete(ids: List<String>) = withContext(Dispatchers.Default) { asked.batchDelete(ids) }
                },
                onChanged = { folder ->
                    if (currentAccount == asked.account) applyChange(DriveChange.ContentsChanged(setOf(folder)))
                },
                onWritten = { folder, entries ->
                    if (clientManager.currentClient.value === asked) vaultEntriesKnown(folder, entries)
                },
                cacheStore = cacheStore,
                account = asked.account,
                scope = backgroundScope,
            )
            if (vaultBinding.compareAndSet(current, VaultBinding(asked, store)) && clientManager.currentClient.value === asked) return store
        }
    }

    fun vaultAccess(expectedAccount: String): VaultStore? {
        val asked = clientManager.currentClient.value?.takeIf { it.account == expectedAccount } ?: return null
        val store = vault
        val binding = vaultBinding.value?.takeIf { it.store === store }
        return store.takeIf { clientManager.currentClient.value === asked && (binding == null || binding.client === asked) }
    }

    /**
     * 网盘页看到的列表：清单文件不列，换成其中的归档条目。清单读不出来时照样列出真实文件，
     * 只少了归档的那几行，不让整个目录打不开。
     */
    suspend fun listBrowsable(parentId: String, sortOrder: PikoFileSortOrder): Result<List<FileStat>> {
        val asked = clientManager.currentClient.value ?: return Result.failure(IllegalStateException("未登录"))
        return listBrowsable(parentId, sortOrder, asked, vault)
    }

    /** 遍历在打开时固定 client 和归档仓库；换号后不能继续拿新账号列旧目录。 */
    internal fun browsableReader(expectedAccount: String): (suspend (String) -> Result<List<FileStat>>)? {
        val asked = clientManager.currentClient.value?.takeIf { it.account == expectedAccount } ?: return null
        val store = vault
        if (clientManager.currentClient.value !== asked) return null
        return reader@{ folderId ->
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            val state = driveTreeIndex.flow.value
            if (state.complete && folderId !in state.dirtyFolders && (folderId.isEmpty() || folderId in state.entries)) {
                val real = driveTreeIndex.realChildren(folderId).files
                val hasUnresolvedManifest = folderId in state.manifestsByFolder &&
                    folderId !in state.vaultEntriesByFolder &&
                    store.cached(folderId, real) == null
                if (!hasUnresolvedManifest) {
                    val entries = state.vaultEntriesByFolder[folderId] ?: store.cached(folderId, real).orEmpty()
                    return@reader Result.success(withVaulted(folderId, real, entries, PikoFileSortOrder.TIME_DESC))
                }
            }
            listBrowsable(folderId, PikoFileSortOrder.TIME_DESC, asked, store)
        }
    }

    /** 供查重等纯真实文件扫描器在本地树就绪且目录未脏时直接切片，避免发起网络请求。 */
    internal fun localRealChildrenIfClean(folderId: String): List<FileStat>? {
        val state = driveTreeIndex.flow.value
        if (!state.complete || folderId in state.dirtyFolders) return null
        if (folderId.isNotEmpty() && folderId !in state.entries) return null
        return driveTreeIndex.realChildren(folderId).files
    }

    private suspend fun listBrowsable(
        parentId: String,
        sortOrder: PikoFileSortOrder,
        asked: PikPakClient,
        store: VaultStore,
    ): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            val listing = fetchListing(parentId, asked)
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            val entries = readAndRecordVaultEntries(parentId, listing, store, asked, logFailure = true)
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            withVaulted(parentId, listing, entries, sortOrder)
        }
    }

    /** [cachedFiles] 的 [listBrowsable] 版本。优先读会话目录缓存，未命中时从已就绪的本地文件树直出。 */
    fun cachedBrowsable(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? {
        cachedListing(folderId)?.let { listing ->
            return withVaulted(folderId, listing, vault.cached(folderId, listing).orEmpty(), sortOrder)
        }
        val state = driveTreeIndex.flow.value
        val children = driveTreeIndex.realChildren(folderId)
        if (children.coverage == DriveTreeIndex.FolderCoverage.COMPLETE ||
            (state.complete && children.coverage != DriveTreeIndex.FolderCoverage.DIRTY && (folderId.isEmpty() || folderId in state.entries))
        ) {
            val entries = state.vaultEntriesByFolder[folderId] ?: vault.cached(folderId, children.files).orEmpty()
            return withVaulted(folderId, children.files, entries, sortOrder)
        }
        return null
    }

    private fun withVaulted(
        folderId: String,
        listing: List<FileStat>,
        entries: List<VaultEntry>,
        sortOrder: PikoFileSortOrder,
    ): List<FileStat> {
        val visible = withVaultCovers(listing.filterNot { isPikoInternalFolder(it, folderId) || VaultStore.isRecoveryFile(it) })
        if (entries.isEmpty() && visible.none(VaultStore::looksLikeManifest)) return sortFiles(visible, sortOrder, folderId)
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
                    byFileId.putIfAbsent("$gcidKey|id:$fileId", index to entry)
                }
                byName.putIfAbsent("$gcidKey|name:${entry.name}", index to entry)
            }
            real.map { file ->
                if (file.isFolder) return@map file
                val gcidKey = "${file.hash.uppercase()}:${file.sizeBytes}"
                val matchById = byFileId["$gcidKey|id:${file.id}"]
                val matchByName = byName["$gcidKey|name:${file.name}"]
                val matched = when {
                    matchById != null && matchByName != null -> if (matchById.first <= matchByName.first) matchById.second else matchByName.second
                    matchById != null -> matchById.second
                    matchByName != null -> matchByName.second
                    else -> null
                }
                matched?.enrich(file) ?: file
            }
        }
        return sortFiles(enriched + entries.filter { it.isArchived }.map { it.toFileStat(folderId) }, sortOrder, folderId)
    }

    /**
     * 已归档文件夹优先使用归档视频封面，避免残留小文件抢占；其他文件夹只补空封面。
     * 归档时选的那一层往往只有子文件夹，取 [VaultTrees] 里其下文件夹的。没有可补的时原样返回同一个列表。
     */
    fun withVaultCovers(files: List<FileStat>): List<FileStat> {
        if (files.none { it.isFolder && (it.thumbnailLink.isEmpty() || it.id in vaultedFolders.value) && vaultCoverOf(it.id) != null }) return files
        return files.map { file ->
            if (!file.isFolder || (file.thumbnailLink.isNotEmpty() && file.id !in vaultedFolders.value)) return@map file
            vaultCoverOf(file.id)?.let { cover ->
                val url = if (cover.startsWith("img:")) imageThumbnailUrlOf(cover.removePrefix("img:")) else thumbnailUrlOf(cover)
                file.copy(thumbnailLink = url)
            } ?: file
        }
    }

    /** 归档封面有变：读到了清单、写成了清单或从磁盘载入。网盘页据此给眼前的文件夹补封面。 */
    val vaultCoverChanges: Flow<Unit> get() = combine(childContents.vaultCovers, vaultTrees.flow) { _, _ -> }

    private fun vaultCoverOf(folderId: String): String? {
        val covers = childContents.vaultCovers.value
        val visited = mutableSetOf<String>()
        fun cover(id: String): String? {
            if (!visited.add(id)) return null
            return covers[id]?.ifEmpty { null }
                ?: vaultTrees.flow.value[id]?.sorted()?.firstNotNullOfOrNull(::cover)
        }
        return cover(folderId)
    }

    /**
     * 文件夹在可见区域里、服务端没给封面时调用：直接放着归档条目的读一次清单，归档时选的外层读其下至多
     * [COVER_PROBE_MEMBERS] 个文件夹的清单，读到为止。不借出任何对象，封面只靠 gcid，会员与免费账号一样。
     * 读过的记进 FolderContentMemory，同一个文件夹以后不再读。
     */
    suspend fun fetchVaultCover(folderId: String) = withContext(Dispatchers.Default) {
        if (vaultCoverOf(folderId) != null) return@withContext
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
            childNameFetches.withPermit {
                if (candidate in childContents.vaultCovers.value) return@withPermit
                val asked = client
                val listing = listAllFiles(candidate).getOrNull() ?: return@withPermit
                readAndRecordVaultEntries(candidate, listing, vault, asked, logFailure = true, logTagSuffix = "（补封面）")
            }
            if (vaultCoverOf(folderId) != null) return@withContext
        }
    }

    /**
     * 文件夹里的文件，只供文件夹行解析作品名（describeFolder）。列表接口只给文件夹的缩略图，
     * 不给其中的文件名，所以来源只有两处：列过的目录，以及 [fetchChildContents] 补取的一页。
     * 与列表缓存（[listings]）独立限制容量，内容改动时失效，下次可见时重新采样。跨进程保留，见 [FolderContentMemory]。
     */
    private val childContents = FolderContentMemory(cacheStore, backgroundScope)
    private val childNameFetches = Semaphore(CHILD_NAME_CONCURRENCY)

    /** 整棵归档过的文件夹，见 [VaultTrees]。跨设备的同步在 VaultTreeSync。 */
    val vaultTrees = VaultTrees(cacheStore, backgroundScope)

    /** 秒传文件的来源，列出的文件经它补上 sourceUrl，见 [SourceLedger]。跨设备的同步在 SourceLedgerSync。 */
    val sourceLedger = SourceLedger(cacheStore, backgroundScope)

    init {
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous: PikPakClient? = null
            clientManager.currentClient.collect { current ->
                val old = previous
                previous = current
                if (old != null && current != null && old !== current && old.account == current.account) {
                    applyChange(DriveChange.ContentsChanged(null))
                    _clientRevision.update { it + 1 }
                }
            }
        }
        // 构造结束前先切到当前账号，避免首次收集清掉刚记下的目录内容、归档树与来源。
        // 磁盘缓存仍在后台载入；换号时换一份，退出登录只清内存。
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            clientManager.currentClient.collect {
                childContents.switchAccount(it?.account)
                vaultTrees.switchAccount(it?.account)
                sourceLedger.switchAccount(it?.account)
            }
        }
        // 账本从磁盘载入或从别的设备合并进来时，已缓存的列表是按旧账本补的，含有这些内容的那几层重列一次
        backgroundScope.launch {
            sourceLedger.arrivals.collect { keys ->
                val folders = listings.value.filterValues { listing ->
                    listing.files.any { !it.isFolder && it.sourceUrl.isNullOrBlank() && SourceLedger.keyOf(it.hash, it.sizeBytes) in keys }
                }.keys
                if (folders.isNotEmpty()) applyChange(DriveChange.ContentsChanged(folders))
            }
        }
        // 额度与账号类型属于账号：换号时先清掉，否则新账号在取到之前沿用上一个账号的，
        // 免费与会员的规则会用反。断线重连换的是同一账号的新 client，不清
        backgroundScope.launch {
            var account: String? = null
            clientManager.currentClient.collectLatest { client ->
                if (client?.account != account) {
                    account = client?.account
                    _quotaFlow.value = null
                    _transferQuotaFlow.value = null
                    // 退出到登录页不算换号：同一账号登回来时位置还在，别的账号登进来时再换
                    if (client != null) enterAccount(client.account)
                }
                if (client != null && _transferQuotaFlow.value == null) {
                    getTransferQuota().logFailure(TAG, "取账号类型失败")
                }
                // 所有账号打开归档条目都受剩余空间约束，同时借出的总量不能超过剩余，
                // 否则信息流一批并行核对时后面的秒传直接失败。留一成余量给清单这类小文件
                if (client != null && client.leaseBudget == null) {
                    getQuota().logFailure(TAG, "取剩余空间失败，借出归档条目暂不设上限").getOrNull()?.quota?.takeIf { it.limitBytes > 0 }?.let { quota ->
                        client.leaseBudget = LeaseBudget(quota.remainingBytes.coerceAtLeast(0) * 9 / 10)
                        PikoLog.d(TAG, "借出额度：剩余 ${quota.remainingBytes} 字节的九成")
                    }
                }
                if (client != null && cacheStore != null && !driveTreeIndex.isComplete) {
                    launch {
                        val restored = restoreTreeFromCache(client)
                        if (clientManager.currentClient.value !== client) return@launch
                        delay(TREE_WARMUP_DELAY)
                        if (clientManager.currentClient.value !== client) return@launch
                        if (restored) {
                            probeRemoteChanges(client).logFailure(TAG, "后台哨兵探针对账失败")
                        } else if (!driveTreeIndex.isComplete) {
                            runSuspendCatching { ensureTreeSync(client).await() }
                                .logFailure(TAG, "后台建立本地文件树索引失败")
                        }
                    }
                }
            }
        }
    }

    val childContentsFlow: StateFlow<Map<String, List<ChildFile>>> get() = childContents.state

    fun knownChildContents(folderId: String): List<ChildFile>? = childContents.get(folderId)

    /** [complete] 为假时 [files] 只是一页：其中没有清单不说明文件夹里没有，只加标记、不去掉。 */
    private fun rememberChildContents(folderId: String, files: List<FileStat>, complete: Boolean = true) {
        // 清单文件与归档来源兜底文件不是用户的文件，不拿来解析作品名；有没有清单说明这个文件夹里有没有归档条目。
        // 清单读过、确认已经空了的不算
        val (manifests, real) = files.partition(VaultStore::looksLikeManifest)
        val visibleReal = real.filterNot { it.trashed || VaultStore.isRecoveryFile(it) }
        childContents.put(folderId, visibleReal.filterNot(FileStat::isFolder).take(MAX_REMEMBERED_CHILD_NAMES).map(ChildFile::of))
        val hasEntries = manifests.isNotEmpty() && vault.cached(folderId, files)?.any { it.isArchived } != false
        if (hasEntries || complete) childContents.markVaulted(folderId, hasEntries)
        val subfolders = visibleReal.filter { it.isFolder }.mapTo(mutableSetOf()) { it.id }
        val hasRealFiles = visibleReal.any { !it.isFolder && !it.isVaulted }
        childContents.rememberCompleteness(folderId, hasRealFiles, subfolders, complete)
        val marked = VaultTrees.marked(childContents.vaultedFolders.value, childContents.state.value.keys, vaultTrees.flow.value)
        if (complete && folderId.isNotEmpty() && visibleReal.none { !it.isFolder && !it.isVaulted } &&
            subfolders.isNotEmpty() && subfolders.all { it in marked }) {
            vaultTrees.merge(mapOf(folderId to subfolders))
        }
        _folderEmptiness.update { it + (folderId to files.isEmpty()) }
    }

    internal fun knownVaultParents(): Map<String, String> = folderIndex.flow.value.nodes
        .filterValues { !it.provisional }.mapNotNull { (id, node) -> node.parentId?.let { id to it } }.toMap()

    internal fun rememberFullyVaulted(folderIds: Set<String>) {
        childContents.markFullyVaulted(folderIds, knownVaultParents())
    }

    /** 完整列举或全量归档成功确认无剩余真实文件；未知目录保留继续归档入口。 */
    val fullyVaultedFolders: StateFlow<Set<String>> by lazy {
        combine(childContents.fullyVaultedFolders, vaultedFolders) { full, marked -> full intersect marked }
            .stateIn(backgroundScope, SharingStarted.Eagerly, emptySet())
    }

    /** 挂归档标记的文件夹：直接放着归档条目的，加上归档时选的那一层到它们之间的各层，见 VaultTrees.marked。 */
    val vaultedFolders: StateFlow<Set<String>> by lazy {
        combine(childContents.vaultedFolders, childContents.listedFolders, vaultTrees.flow, VaultTrees::marked)
            .stateIn(backgroundScope, SharingStarted.Eagerly, emptySet())
    }

    /** 清单读到或写成之后，按其中还有没有条目更新文件夹的标记。 */
    internal fun vaultEntriesKnown(
        folderId: String,
        entries: List<VaultEntry>,
        expectedManifestId: String? = null,
        requireMatch: Boolean = false,
    ) {
        if (!driveTreeIndex.putVaultEntries(folderId, entries, expectedManifestId, requireMatch)) return
        childContents.markVaulted(folderId, entries.any { it.isArchived })
        childContents.rememberCover(folderId, entries.coverGcid().orEmpty())
    }

    private suspend fun readAndRecordVaultEntries(
        folderId: String,
        listing: List<FileStat>,
        store: VaultStore,
        asked: PikPakClient,
        logFailure: Boolean = false,
        logTagSuffix: String = "",
    ): List<VaultEntry> {
        val startedFromPending = store.hasPending(folderId)
        val expectedManifestId = VaultStore.winnerOf(listing)?.id
        val result = store.read(folderId, listing).let {
            if (logFailure) it.logFailure(TAG, "读取归档清单失败$logTagSuffix") else it
        }
        val entries = result.getOrNull() ?: return emptyList()
        if (clientManager.currentClient.value !== asked) return entries
        val currentlyPending = store.hasPending(folderId)
        if (startedFromPending) {
            if (currentlyPending) {
                vaultEntriesKnown(folderId, entries)
            }
        } else if (!currentlyPending) {
            vaultEntriesKnown(folderId, entries, expectedManifestId = expectedManifestId, requireMatch = true)
        }
        return entries
    }

    /*
     * 列过的文件夹是不是空的，连子文件夹在内，按 ID；没列过的不在表里。海报墙给空文件夹画空的封面用。
     * 不从 childContents 推：那里只记文件，只有子文件夹的与真空的都是一张空表，分不开；它还跨进程保留，
     * 这里只在这一次运行里有效，重启后按需再探（probeFolderEmptiness）。
     */
    private val _folderEmptiness = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val folderEmptiness: StateFlow<Map<String, Boolean>> = _folderEmptiness.asStateFlow()

    /**
     * 只取一项，看文件夹空不空。记下的内容是空表、而这一次运行里还没列过它时才需要：那张空表可能是真空，
     * 也可能只有子文件夹。一项的页不记进 childContents，免得把只取了一项当成全部内容。
     */
    suspend fun probeFolderEmptiness(folderId: String) = withContext(Dispatchers.Default) {
        if (folderId in _folderEmptiness.value) return@withContext
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
            val files = runSuspendCatching { client.listFilesPaged(parentId = folderId, pageSize = 1).files }.getOrNull() ?: return@withPermit
            _folderEmptiness.update { it + (folderId to files.isEmpty()) }
        }
    }

    /**
     * 补取一页文件。只取一页、至多 [CHILD_NAME_PAGE] 项，并发至多 [CHILD_NAME_CONCURRENCY]。
     * 失败返回 null 且不记下：记下的内容跨进程保留，一次网络失败不该让这个文件夹从此被当成空的。
     */
    suspend fun fetchChildContents(folderId: String): List<ChildFile>? = withContext(Dispatchers.Default) {
        childContents.get(folderId)?.let { return@withContext it }
        val treeChildren = driveTreeIndex.realChildren(folderId)
        val state = driveTreeIndex.flow.value
        if (treeChildren.coverage == DriveTreeIndex.FolderCoverage.COMPLETE ||
            (state.complete && treeChildren.coverage != DriveTreeIndex.FolderCoverage.DIRTY && (folderId.isEmpty() || folderId in state.entries))
        ) {
            rememberChildContents(folderId, treeChildren.files, complete = true)
            return@withContext childContents.get(folderId)
        }
        childNameFetches.withPermit {
            // 排队期间可能已有同一目录的请求完成
            childContents.get(folderId)?.let { return@withPermit it }
            val asked = client
            val serial = synchronized(treeStateLock) { changeMarks.value.serial }
            val files = runSuspendCatching { asked.listFilesPaged(parentId = folderId, pageSize = CHILD_NAME_PAGE).files }
                .getOrNull() ?: return@withPermit null
            val complete = files.size < CHILD_NAME_PAGE
            val indexUpdated = synchronized(treeStateLock) {
                if (clientManager.currentClient.value !== asked || !unchangedSince(folderId, serial)) {
                    return@withPermit null
                }
                rememberChildContents(folderId, files, complete)
                folderIndex.listed(folderId, files, complete)
            }
            if (indexUpdated) normalizeLocations()
            childContents.get(folderId)
        }
    }

    suspend fun listFiles(
        parentId: String = "",
        pageToken: String = "",
        sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    ): Result<Pair<List<FileStat>, String>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val page = client.listFilesPaged(parentId = parentId, pageToken = pageToken, pageSize = 100)
            sortFiles(page.files, sortOrder, parentId) to page.nextPageToken
        }
    }

    /**
     * 列出目录下的全部条目，翻页由 SDK 负责。
     *
     * 按页取有两处坑：只取第一页会让超过一页的目录被静默截断；排序又发生在
     * 客户端，分页取回时每页各自有序、整体无序。两者都要求先取全再排。
     */
    suspend fun listAllFiles(
        parentId: String = "",
        sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    ): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { sortFiles(fetchListing(parentId), sortOrder, parentId) }
    }

    /**
     * 递归统计目录下的文件数与总大小，边统计边发出累计值。
     *
     * 服务端不提供目录大小：列表与详情里目录的 size 都是 "0"，也没有子项计数
     * （2026-09-23 实测），只能逐个目录列出来加总。SDK 的 searchFilesRecursive 走同样的
     * 遍历，但要求非空关键词，且到了上限静默结束，调用方分不清统计完没完。
     * 到达 [maxFolders] 或 [timeout] 时以 TRUNCATED 结束，界面据此标注「至少」。
     */
    fun folderUsage(
        folderId: String,
        maxFolders: Int = 2_000,
        timeout: Duration = 30.seconds,
    ): Flow<FolderUsage> = flow {
        driveTreeIndex.folderUsage(folderId)?.let { usage ->
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
                val listings = coroutineScope { budgeted.map { async { client.listFiles(it) } }.awaitAll() }
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

    suspend fun getFileDetail(fileId: String): Result<FileDetail> = withContext(Dispatchers.Default) {
        runSuspendCatching { fetchDetail(fileId, client) }
    }

    /** 查详情，是文件夹就记进索引（仅当请求归属的 client 仍为当前活跃 client 时）。 */
    private suspend fun fetchDetail(fileId: String, asked: PikPakClient = client): FileDetail = asked.getFile(fileId).also { detail ->
        if (clientManager.currentClient.value === asked) {
            val isFolder = detail.kind == FileKind.FOLDER
            if (folderIndex.learned(detail.id, detail.name, detail.parentId, isFolder, detail.trashed)) normalizeLocations()
        }
    }

    /**
     * 目录已被删除或移入回收站。列一个不存在的目录不报错，只回一个空列表，与空目录分不开，
     * 只能另查详情：彻底删除的查不到，回收站里的回 file_in_recycle_bin，也可能查到但带着 trashed。
     * 只认服务端明确的拒绝，网络失败说不准，按还在处理，免得断一下网就把人退出目录。
     */
    suspend fun isFolderGone(folderId: String): Boolean =
        getFileDetail(folderId).fold(onSuccess = { it.trashed }, onFailure = { it is PikPakException }).also { gone ->
            if (gone) {
                driveTreeIndex.applyChange(DriveChange.Trashed(listOf(folderId), null))
                if (driveTreeIndex.isComplete) requestTreeRebuild()
            }
        }

    suspend fun getQuota(): Result<QuotaResponse> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = client
            asked.getQuota().also {
                _quotaFlow.value = it
                _quotaUpdates.tryEmit(asked.account to it)
                if (driveTreeIndex.isComplete && driveTreeIndex.lastUsageBytes == null) {
                    driveTreeIndex.setLastUsageBytes(it.quota.usageBytes)
                    scheduleTreeSnapshotSave()
                }
            }
        }
    }

    /** [isFreeAccountFlow] 的当前值，登录时那一次还没取到就现取。取不到时为 null。 */
    suspend fun isFreeAccount(): Boolean? =
        isFreeAccountFlow.value ?: getTransferQuota().getOrNull()?.account?.isPremium?.not()

    /** 离线下载、下载、上传三项月度流量额度，见 [TransferQuota] 上的计费实测结论。 */
    suspend fun getTransferQuota(): Result<TransferQuota> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getTransferQuota().also { _transferQuotaFlow.value = it } }
    }

    /**
     * 全屏查看图片用的原图直链。
     *
     * 列表里的 thumbnailLink 是压过的小图，放大到全屏就是一团马赛克；原图链接只有
     * getFileDetail 才带。链接是签过名的，过期后 CDN 直接 403，所以不缓存，每次打开现取。
     */
    suspend fun originalImageUrl(fileId: String): String? =
        if (LeasedFile.isLeased(fileId)) {
            LeasedFile.resolvedFileOf(fileId)?.gcid?.takeIf { it.isNotBlank() }?.let { imageThumbnailUrlOf(it, large = true) }
        } else getFileDetail(fileId).getOrNull()?.downloadUrl

    /**
     * 把一小段内容传成网盘里的文件，返回新文件的 ID。只给配置同步这类几 KB 的东西用：整段在内存里，
     * 算 gcid 与上传各读一遍。同名文件不会被覆盖，调用方自己删旧的。
     */
    suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { uploadBytes(client, parentId, name, bytes) }
    }

    private suspend fun uploadBytes(asked: PikPakClient, parentId: String, name: String, bytes: ByteArray): String {
        val size = bytes.size.toLong()
        val gcid = PikPakHash.fromSource(Buffer().apply { write(bytes) }, size)
        val session = when (val start = asked.startUpload(parentId, name, size, gcid)) {
            is UploadStart.Instant -> return start.fileId
            is UploadStart.Pending -> start.session
        }
        var lastError: Throwable? = null
        try {
            for (attempt in 0 until UPLOAD_BYTES_ATTEMPTS) {
                try {
                    asked.continueUpload(
                        session,
                        open = { offset: Long ->
                            val startOffset = offset.toInt().coerceIn(0, bytes.size)
                            Buffer().apply { write(bytes, startOffset, bytes.size) }
                        },
                        onProgress = {},
                    )
                    return session.fileId
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    if (e is PikPakException || attempt == UPLOAD_BYTES_ATTEMPTS - 1) throw e
                    delay(LINK_RETRY_DELAYS.getOrElse(attempt) { 1.seconds })
                }
            }
            throw lastError ?: error("上传失败")
        } catch (e: Throwable) {
            withContext(NonCancellable) { runCatching { asked.cancelUpload(session) } }
            throw e
        }
    }

    /**
     * 读出一个小文件的全部内容，与 [uploadBytes] 配对。
     *
     * 刚传完的文件查详情有时还没有直链，稍后再查就有（设置同步与归档清单都撞到过，SDK 的 instantCreate 也记着同样的现象），
     * 所以没有直链时隔一会儿再查，几次都没有才算失败。
     */
    suspend fun readBytes(fileId: String): Result<ByteArray> = withContext(Dispatchers.Default) {
        runSuspendCatching { readBytes(client, fileId) }
    }

    private suspend fun readBytes(asked: PikPakClient, fileId: String): ByteArray {
        var detail = asked.getFile(fileId)
        for (wait in LINK_RETRY_DELAYS) {
            if (detail.downloadUrl != null) break
            delay(wait)
            detail = asked.getFile(fileId)
        }
        val url = detail.downloadUrl ?: error("没有下载链接")
        val size = detail.size.toLongOrNull() ?: error("大小未知")
        if (size == 0L) return ByteArray(0)
        return asked.streamRangeFromUrl(url, start = 0L, length = size, {}) { stream -> stream.channel.toByteArray() }
    }

    /** [parentId] 下名为 [name] 的文件夹，没有就新建。返回它的 ID。 */
    suspend fun folderNamed(parentId: String, name: String): Result<String> {
        val existing = listAllFiles(parentId).getOrElse { return Result.failure(it) }
            .firstOrNull { it.isFolder && it.name == name && !it.trashed }
        return existing?.let { Result.success(it.id) } ?: createFolder(parentId, name)
    }

    /** [fileId] 的取样 CID，读 60 KB。归档时记下，日后只读体检用，见 [VaultEntry.cid]。 */
    suspend fun sampleCid(fileId: String): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.sampleCid(client.getFile(fileId)) }
    }

    /**
     * 按 gcid 秒传出一个文件，恢复归档条目用。[source] 是条目记着的来源，记进来源账本：取回的文件被复制、
     * 移出原文件夹后，清单里的取回记录就对不上了，账本按内容记，仍补得上。
     */
    suspend fun instantCreate(file: ResolvedFile, parentId: String, source: String? = null): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.instantCreate(file, parentId) }.onSuccess { id ->
            source?.let { sourceLedger.record(it, listOf(file)) }
            applyChange(DriveChange.Created(parentId, id, file.path.substringAfterLast('/'), isFolder = false))
        }
    }

    suspend fun createFolder(parentId: String, name: String): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.createFolder(parentId, name) }.onSuccess { id ->
            applyChange(DriveChange.Created(parentId, id, name, isFolder = true))
        }
    }

    suspend fun rename(fileId: String, name: String, parentId: String? = null, notifyChange: Boolean = true): Result<Unit> = withContext(Dispatchers.Default) {
        val effectiveParentId = parentId ?: knownParentOf(fileId)
        runSuspendCatching { client.rename(fileId, name) }.onSuccess {
            // 快速访问存的名字是别的设备上的回落（经设置同步），跟着改；本机显示的名字取自索引
            pinnedFolders.renamed(fileId, name)
            applyChange(DriveChange.Renamed(fileId, name, effectiveParentId), notifyChange)
        }
    }

    suspend fun trash(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val from = knownParentsOf(ids)
        runSuspendCatching { client.batchTrash(ids) }.onSuccess { applyChange(DriveChange.Trashed(ids, from)) }
            .logOperation("移入回收站", ids, started)
    }

    suspend fun restore(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        runSuspendCatching { client.batchUntrash(ids) }.onSuccess { applyChange(DriveChange.Restored(ids)) }
            .logOperation("从回收站恢复", ids, started)
    }

    suspend fun delete(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val from = ids.mapNotNullTo(HashSet(), ::knownParentOf)
        runSuspendCatching { client.batchDelete(ids) }.onSuccess { applyChange(DriveChange.Deleted(ids, from)) }
            .logOperation("彻底删除", ids, started)
    }

    suspend fun move(ids: List<String>, parentId: String): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        // 原来在哪要在移动之前问：之后索引里已是新的上级
        val from = knownParentsOf(ids)
        runSuspendCatching { client.batchMove(ids, parentId) }.onSuccess { applyChange(DriveChange.Moved(ids, from, parentId)) }
            .logOperation("移动到 ${parentId.ifEmpty { "根目录" }}", ids, started)
    }

    /**
     * 复制到 [parentId]。服务端按任务执行，小批量在返回时已完成；目标里有同名项时自动改名为「名字(1)」。
     * 复制到自身或自己的子目录里会被拒绝（file_move_or_copy_to_cur）。SDK 已按 id 上限分批。
     */
    suspend fun copy(ids: List<String>, parentId: String): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        runSuspendCatching { client.batchCopy(ids, parentId); Unit }.onSuccess { applyChange(DriveChange.Copied(ids, parentId)) }
            .logOperation("复制到 ${parentId.ifEmpty { "根目录" }}", ids, started)
    }

    /**
     * 批量改动各记一行：几项、前几个 ID、多久。失败只记错误名，堆栈由给用户提示的调用方记，
     * 那边的一行不带 ID，这一行补上是哪几项。逐项调用的（重命名、建文件夹）不在这里记，批量时会刷屏。
     */
    private fun <T> Result<T>.logOperation(action: String, ids: List<String>, started: TimeMark): Result<T> {
        val shown = ids.take(LOGGED_IDS).joinToString(",") + if (ids.size > LOGGED_IDS) " 等" else ""
        val elapsed = started.elapsedNow().inWholeMilliseconds
        onSuccess { PikoLog.i(TAG, "$action：${ids.size} 项（$shown），$elapsed ms") }
        onFailure { error ->
            val reason = (error as? PikPakException)?.let { "${it.errorCode} ${it.errorMessage}" } ?: error::class.simpleName
            PikoLog.w(TAG, "${action}失败：${ids.size} 项（$shown），$elapsed ms，$reason")
        }
        return this
    }

    suspend fun search(query: String): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { sourceLedger.enrich(client.searchFiles(query)) }
    }

    /**
     * 后台或按需拉齐当前账号的完整本地文件树与归档清单索引。
     * 同一 client 的多次调用（例如登录后后台预热与用户立即触发全盘搜索）共用同一个单飞协程。
     */
    suspend fun warmDriveTree(): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { ensureTreeSync(client).await() }
    }

    /**
     * 触发后台双缓冲整树重建（如用户在根目录手动刷新 F5、遇到 404/回收站残留或哨兵探针击穿时调用）。
     */
    fun requestTreeRebuild() {
        val asked = clientManager.currentClient.value ?: return
        ensureTreeSync(asked, forceRebuild = true)
    }

    /**
     * 跨目录哨兵探针（Canary Probe，增量 Patch 与自动升级整树重建）：
     * 1. 比对 `getQuota()` 的 `usageBytes` 与本地树保存的 `lastUsageBytes`；
     * 2. 拉取最新 100 条修改记录 `listFilesPaged(parentId = "*", pageSize = 100)`；
     * 3. 完全未变则直接结束；小幅新增/修改且容量差额吻合则原地增量 Patch；若窗口击穿或发生外部删除则升级为后台双缓冲整树重建。
     */
    suspend fun probeRemoteChanges(
        asked: PikPakClient = client,
        force: Boolean = false,
    ): Result<CanaryProbeResult> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            if (!force) {
                val last = lastCanaryProbeAt
                if (last != null && last.elapsedNow() < CANARY_PROBE_COOLDOWN) {
                    return@runSuspendCatching CanaryProbeResult.UNCHANGED
                }
            }
            lastCanaryProbeAt = TimeSource.Monotonic.markNow()
            for (attempt in 0 until 2) {
                if (!driveTreeIndex.isComplete) {
                    ensureTreeSync(asked).await()
                    return@runSuspendCatching CanaryProbeResult.FULL_REBUILD
                }
                val (probeStartSerial, probeStartSeq, state) = synchronized(treeStateLock) {
                    Triple(changeMarks.value.serial, driveTreeIndex.currentMutationSeq, driveTreeIndex.flow.value)
                }
                val quota = getQuota().getOrNull()?.quota
                val usageBytes = quota?.usageBytes
                val page = asked.listFilesPaged(parentId = "*", pageSize = CANARY_WINDOW_SIZE)
                if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
                val recent = page.files.filterNot { it.trashed }
                val prevUsage = state.lastUsageBytes
                val fullSnapshotVisible = page.nextPageToken.isEmpty()

                fun matchesLocal(remote: FileStat): Boolean {
                    val local = state.entries[remote.id] ?: return false
                    return local.parentId == remote.parentId &&
                        local.name == remote.name &&
                        local.modifiedTime == remote.modifiedTime &&
                        local.size == remote.size
                }

                val hasMissingLocalEntries = if (fullSnapshotVisible) {
                    val remoteIds = recent.mapTo(HashSet()) { it.id }
                    state.entries.keys.any { id -> !VaultEntry.isVaulted(id) && id !in remoteIds }
                } else {
                    val nowMillis = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    val lastSync = driveTreeIndex.lastFullSyncAtMillis
                    val expiredForPaginated = lastSync <= 0L || (nowMillis - lastSync) >= MAX_PAGINATED_CANARY_AGE_MILLIS
                    if (expiredForPaginated) {
                        true
                    } else {
                        val remoteFolders = asked.listAllDriveFolders(pageSize = 500).filterNot { it.trashed }
                        if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
                        val remoteFolderIds = remoteFolders.mapTo(HashSet()) { it.id }
                        val localFoldersMissingOrChanged = state.entries.values.any { entry ->
                            entry.isFolder && !VaultEntry.isVaulted(entry.id) && entry.id !in remoteFolderIds
                        } || remoteFolders.any { folder -> !matchesLocal(folder) }
                        localFoldersMissingOrChanged
                    }
                }

                val changedItems = recent.filterNot(::matchesLocal)
                if (!hasMissingLocalEntries && changedItems.isEmpty() && (prevUsage == null || usageBytes == null || usageBytes == prevUsage)) {
                    val publishedUnchanged = synchronized(treeStateLock) {
                        if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
                        if (changeMarks.value.serial != probeStartSerial || driveTreeIndex.currentMutationSeq != probeStartSeq) {
                            false
                        } else {
                            if (prevUsage == null && usageBytes != null) {
                                driveTreeIndex.setLastUsageBytes(usageBytes)
                            }
                            true
                        }
                    }
                    if (!publishedUnchanged) continue
                    if (prevUsage == null && usageBytes != null) {
                        scheduleTreeSnapshotSave()
                    }
                    return@runSuspendCatching CanaryProbeResult.UNCHANGED
                }

                val windowExceeded = recent.size >= CANARY_WINDOW_SIZE && changedItems.size == recent.size
                if (!hasMissingLocalEntries && !windowExceeded && prevUsage != null && usageBytes != null && usageBytes >= prevUsage) {
                    val addedBytes = changedItems.sumOf { item ->
                        if (item.isFolder) 0L else item.sizeBytes - (state.entries[item.id]?.sizeBytes ?: 0L)
                    }
                    if (usageBytes - prevUsage == addedBytes && changedItems.isNotEmpty()) {
                        val enriched = sourceLedger.enrich(changedItems)
                        val affectedParents = HashSet<String>()
                        val manifestFolders = HashSet<String>()
                        var indexUpdated = false
                        val publishedPatch = synchronized(treeStateLock) {
                            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
                            if (changeMarks.value.serial != probeStartSerial || driveTreeIndex.currentMutationSeq != probeStartSeq) {
                                return@synchronized false
                            }
                            val currentState = driveTreeIndex.flow.value
                            var learned = false
                            for (item in enriched) {
                                affectedParents += item.parentId
                                currentState.entries[item.id]?.parentId?.let(affectedParents::add)
                                if (VaultStore.looksLikeManifest(item)) {
                                    manifestFolders += item.parentId
                                    currentState.entries[item.id]?.parentId?.let(manifestFolders::add)
                                }
                                if (folderIndex.learned(item.id, item.name, item.parentId, item.isFolder, item.trashed)) {
                                    learned = true
                                }
                            }
                            markCanaryPatched(affectedParents)
                            listingRequests.update { it - affectedParents }
                            driveTreeIndex.applyCanaryPatch(enriched, usageBytes)
                            for (folderId in affectedParents) {
                                val children = driveTreeIndex.realChildren(folderId).files
                                rememberChildContents(folderId, children, complete = true)
                            }
                            listings.update { cache ->
                                cache.mapValues { (id, listing) -> if (id in affectedParents) listing.expired() else listing }
                            }
                            indexUpdated = learned
                            true
                        }
                        if (!publishedPatch) continue
                        if (indexUpdated) normalizeLocations()
                        val store = vault
                        for (folderId in manifestFolders) {
                            val children = driveTreeIndex.realChildren(folderId).files
                            runSuspendCatching {
                                readAndRecordVaultEntries(folderId, children, store, asked)
                            }
                        }
                        scheduleTreeSnapshotSave()
                        _folderChanges.tryEmit(DriveChange.ContentsChanged(affectedParents))
                        return@runSuspendCatching CanaryProbeResult.INCREMENTAL_PATCH
                    }
                }
                break
            }

            ensureTreeSync(asked, forceRebuild = true).await()
            CanaryProbeResult.FULL_REBUILD
        }
    }

    private suspend fun restoreTreeFromCache(asked: PikPakClient): Boolean = withContext(Dispatchers.Default) {
        val store = cacheStore ?: return@withContext false
        val account = asked.account.takeIf { it.isNotEmpty() } ?: return@withContext false
        val snapshotText = store.read(DriveTreeIndex.snapshotKeyOf(account)) ?: return@withContext false
        val snapshot = DriveTreeIndex.decodeSnapshot(snapshotText) ?: return@withContext false
        val manifestText = store.read(VaultStore.cacheKeyOf(account))
        val cachedManifests = manifestText?.let(VaultStore::decodeParsed).orEmpty()
        var indexUpdated = false
        val restored = synchronized(treeStateLock) {
            if (clientManager.currentClient.value !== asked) return@synchronized false
            vault.restoreParsed(cachedManifests)
            if (!driveTreeIndex.restoreSnapshot(snapshot, cachedManifests)) return@synchronized false
            indexUpdated = populateDerivedFromTreeLocked()
            true
        }
        if (!restored) return@withContext false
        if (indexUpdated) normalizeLocations()
        PikoLog.d(TAG, "已从本地快照恢复文件树：${snapshot.entries.size} 个节点，归档缓存 ${cachedManifests.size} 份")
        true
    }

    private fun populateDerivedFromTreeLocked(): Boolean {
        val state = driveTreeIndex.flow.value
        var indexUpdated = false
        for (entry in state.entries.values) {
            if (VaultEntry.isVaulted(entry.id)) continue
            if (folderIndex.learned(entry.id, entry.name, entry.parentId, entry.isFolder, entry.trashed)) {
                indexUpdated = true
            }
        }
        for ((folderId, vEntries) in state.vaultEntriesByFolder) {
            childContents.markVaulted(folderId, vEntries.any { it.isArchived })
            childContents.rememberCover(folderId, vEntries.coverGcid().orEmpty())
        }
        val allFolders = buildSet {
            add("")
            for (entry in state.entries.values) {
                if (entry.isFolder) add(entry.id)
            }
        }
        for (folderId in allFolders) {
            if (folderId in state.dirtyFolders) continue
            val children = state.childrenByParent[folderId].orEmpty().mapNotNull { id ->
                state.entries[id]?.takeUnless { VaultEntry.isVaulted(id) }
            }
            rememberChildContents(folderId, children, complete = true)
        }
        return indexUpdated
    }

    private fun scheduleTreeSnapshotSave() {
        val store = cacheStore ?: return
        val owner = currentAccount?.takeIf { it.isNotEmpty() } ?: return
        if (!driveTreeIndex.isComplete) return
        if (pendingTreeSave?.isActive == true) return
        pendingTreeSave = backgroundScope.launch {
            delay(TREE_SNAPSHOT_SAVE_DELAY)
            val snapshotState = driveTreeIndex.flow.value
            if (currentAccount == owner && snapshotState.complete) {
                val encoded = DriveTreeIndex.encodeSnapshot(
                    state = snapshotState,
                    savedAtMillis = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                    lastFullSyncAtMillis = driveTreeIndex.lastFullSyncAtMillis,
                )
                store.write(DriveTreeIndex.snapshotKeyOf(owner), encoded)
            }
        }
    }

    private fun flushPendingTreeSnapshotSave(previousAccount: String) {
        val pending = pendingTreeSave ?: return
        pendingTreeSave = null
        if (!pending.isActive) return
        pending.cancel()
        val store = cacheStore ?: return
        val snapshotState = driveTreeIndex.flow.value
        val fullSyncAt = driveTreeIndex.lastFullSyncAtMillis
        if (!snapshotState.complete) return
        backgroundScope.launch {
            val encoded = DriveTreeIndex.encodeSnapshot(
                state = snapshotState,
                savedAtMillis = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                lastFullSyncAtMillis = fullSyncAt,
            )
            store.write(DriveTreeIndex.snapshotKeyOf(previousAccount), encoded)
        }
    }

    private fun ensureTreeSync(asked: PikPakClient, forceRebuild: Boolean = false): Deferred<Unit> {
        while (true) {
            val existing = treeSyncJob.value
            if (!forceRebuild && existing != null && existing.first === asked && existing.second.isActive) {
                return existing.second
            }
            if (forceRebuild) {
                existing?.second?.cancel()
            }
            val store = vault
            val isShadowRebuild = driveTreeIndex.isComplete
            val job = backgroundScope.async(start = CoroutineStart.LAZY) {
                val started = TimeSource.Monotonic.markNow()
                val (serial, rebuildStartSeq) = synchronized(treeStateLock) {
                    changeMarks.value.serial to driveTreeIndex.currentMutationSeq
                }
                _treeBuildProgressFlow.value = TreeBuildProgress(
                    isBuilding = true,
                    isInitialBlocking = !isShadowRebuild,
                    foldersRead = 0,
                    filesRead = 0,
                )
                try {
                    val folders = asked.listAllDriveFolders(pageSize = 500)
                    if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
                    _treeBuildProgressFlow.update { it.copy(foldersRead = folders.size) }
                    if (!isShadowRebuild) {
                        var indexUpdated = false
                        synchronized(treeStateLock) {
                            if (clientManager.currentClient.value === asked && changeMarks.value.accountResetAt <= serial) {
                                driveTreeIndex.putFolders(folders)
                                for (folder in folders) {
                                    if (folderIndex.learned(folder.id, folder.name, folder.parentId, isFolder = true, trashed = folder.trashed)) {
                                        indexUpdated = true
                                    }
                                }
                            }
                        }
                        if (indexUpdated) normalizeLocations()
                    }

                    var fileCount = 0
                    val allFiles = ArrayList<FileStat>()
                    val manifestByFolder = LinkedHashMap<String, MutableList<FileStat>>()
                    asked.listAllDriveEntriesFlow(pageSize = 500, extraFilters = mapOf(FileFilter.kind(FileKind.FILE))).collect { page ->
                        if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
                        fileCount += page.size
                        _treeBuildProgressFlow.update { it.copy(filesRead = fileCount) }
                        val enriched = sourceLedger.enrich(page)
                        allFiles.addAll(enriched)
                        if (!isShadowRebuild) {
                            synchronized(treeStateLock) {
                                if (clientManager.currentClient.value === asked && changeMarks.value.accountResetAt <= serial) {
                                    driveTreeIndex.putFiles(enriched)
                                }
                            }
                        }
                        for (entry in enriched) {
                            if (VaultStore.looksLikeManifest(entry)) {
                                manifestByFolder.getOrPut(entry.parentId) { mutableListOf() }.add(entry)
                            }
                        }
                    }

                    val vaultByFolder = LinkedHashMap<String, List<VaultEntry>>()
                    for ((folderId, manifests) in manifestByFolder) {
                        if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
                        val winnerId = VaultStore.winnerOf(manifests)?.id
                        val cachedManifest = store.cachedCommitted(manifests)
                        if (cachedManifest != null) {
                            vaultByFolder[folderId] = cachedManifest
                            if (!isShadowRebuild) {
                                vaultEntriesKnown(folderId, cachedManifest, expectedManifestId = winnerId, requireMatch = !store.hasPending(folderId))
                            }
                            continue
                        }
                        runSuspendCatching {
                            val entries = readAndRecordVaultEntries(folderId, manifests, store, asked)
                            if (clientManager.currentClient.value === asked && entries.isNotEmpty()) {
                                vaultByFolder[folderId] = entries
                            }
                        }
                    }

                    var shouldRestartRebuild = false
                    var indexUpdated = false
                    var changedFolders = emptySet<String>()
                    var committed = false
                    synchronized(treeStateLock) {
                        val marks = changeMarks.value
                        if (clientManager.currentClient.value === asked && marks.accountResetAt <= serial) {
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
                                val usageBytes = _quotaFlow.value?.quota?.usageBytes ?: driveTreeIndex.lastUsageBytes
                                changedFolders = driveTreeIndex.replaceTree(
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
                                    syncedAtMillis = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                                )
                                indexUpdated = populateDerivedFromTreeLocked()
                                if (changedFolders.isNotEmpty()) {
                                    markChanged(changedFolders)
                                    listingRequests.update { it - changedFolders }
                                }
                                if (isShadowRebuild && changedFolders.isNotEmpty()) {
                                    listings.update { cache ->
                                        cache.mapValues { (id, listing) -> if (id in changedFolders) listing.expired() else listing }
                                    }
                                }
                                committed = true
                            }
                        }
                    }
                    if (shouldRestartRebuild) {
                        requestTreeRebuild()
                    } else if (committed) {
                        if (indexUpdated) normalizeLocations()
                        scheduleTreeSnapshotSave()
                        if (isShadowRebuild && changedFolders.isNotEmpty()) {
                            _folderChanges.tryEmit(DriveChange.ContentsChanged(changedFolders))
                        }
                        PikoLog.d(
                            TAG,
                            "本地文件树索引就绪：${folders.size} 个文件夹，$fileCount 个文件，" +
                                "归档目录 ${manifestByFolder.size} 个，历时 ${started.elapsedNow().inWholeMilliseconds} ms",
                        )
                    }
                } finally {
                    _treeBuildProgressFlow.update { it.copy(isBuilding = false, isInitialBlocking = false) }
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

    /**
     * 全盘/子树按名搜索（支持 CJK 简繁、日本新字体、平片假名及有界子序列模糊匹配，并包含已归档的虚拟条目）。
     *
     * 1. 先立即在内存本地文件树（[driveTreeIndex]）中检索并发出命中，0 网络等待；
     * 2. 若登录后后台预热已完成（[DriveTreeIndex.isComplete]），仅对有改动的脏目录做增量重列后立即结束；
     * 3. 若预热仍在进行或尚未开始，复用单飞同步任务 [ensureTreeSync]，随页面到达实时发出新增命中。
     */
    fun searchRecursive(query: String, parentId: String = ""): Flow<SearchHit> = flow {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@flow
        val asked = client
        val store = vault
        val emittedIds = HashSet<String>()

        fun extraFolderTitle(folder: FileStat): String? =
            childContents.get(folder.id)?.let { children ->
                describeFolder(folder.name, children.map { MediaFileInput(it.name, 0, it.category?.toFileKind()) }).title
            }

        fun enrichFile(file: FileStat): FileStat =
            if (file.isFolder) withVaultCovers(listOf(file)).first() else sourceLedger.enrich(file)

        suspend fun emitNewMatches() {
            val hits = driveTreeIndex.searchLocal(
                query = cleanQuery,
                parentId = parentId,
                folderIndex = folderIndex,
                extraFolderTitle = ::extraFolderTitle,
                transformFile = ::enrichFile,
            )
            for (hit in hits) {
                if (emittedIds.add(hit.file.id)) emit(hit)
            }
        }

        emitNewMatches()

        if (driveTreeIndex.isComplete) {
            val dirty = driveTreeIndex.dirtyFolders
            for (folderId in dirty) {
                if (clientManager.currentClient.value !== asked) return@flow
                val listing = runSuspendCatching { fetchListing(folderId, asked) }.getOrNull() ?: continue
                runSuspendCatching {
                    readAndRecordVaultEntries(folderId, listing, store, asked)
                }
                emitNewMatches()
            }
            return@flow
        }

        val sync = ensureTreeSync(asked)
        var lastSeenRev = driveTreeIndex.flow.value.revision
        while (sync.isActive) {
            delay(50)
            val currentRev = driveTreeIndex.flow.value.revision
            if (currentRev != lastSeenRev) {
                lastSeenRev = currentRev
                emitNewMatches()
            }
        }
        sync.await()
        emitNewMatches()
    }.flowOn(Dispatchers.Default)

    suspend fun trashFiles(): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listTrash() }
    }

    /** 删除请求成功后，服务端仍可能在后台清理；只用成功取得的完整回收站列表核对。 */
    fun reconcileTrashListing(files: List<FileStat>): Boolean {
        val presentIds = files.mapTo(HashSet()) { it.id }
        return pendingServerDeletes.updateAndGet { it intersect presentIds }.isNotEmpty()
    }

    /**
     * 读分享的顶层与提取码令牌。没带提取码、提取码错、分享已取消时服务端仍回 200，
     * SDK 据状态抛 [io.github.nihildigit.pikpak.ShareUnavailableException]，这里原样交给调用方分辨。
     */
    suspend fun shareInfo(shareId: String, passCode: String): Result<ShareInfo> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.getShareInfo(shareId, passCode) }
    }

    /** 分享里某个文件夹的内容。子目录只能经它打开，GET /share 不认 parent_id。 */
    suspend fun shareFolder(shareId: String, passCodeToken: String, parentId: String): Result<List<FileStat>> =
        withContext(Dispatchers.Default) {
            runSuspendCatching { client.listShareFiles(shareId, passCodeToken, parentId = parentId).files }
        }

    /**
     * 把分享里的条目转存到 [toParentId]，等任务结束才返回。[ancestorIds] 是条目所在的各级分享目录，
     * 转存子目录里的条目时要带上。实测 2026-09-25：文件直接落在目标目录下，不带上级目录；
     * 秒级完成；任务 params 里没有新旧 id 的映射，要找新文件只能列目标目录。
     */
    suspend fun restoreFromShare(
        shareId: String,
        passCodeToken: String,
        fileIds: List<String>,
        toParentId: String,
        ancestorIds: List<String>,
    ): Result<Unit> = withContext(Dispatchers.Default) {
        val boundClient = client
        runSuspendCatching {
            val restore = boundClient.restoreShare(shareId, passCodeToken, fileIds, toParentId = toParentId, ancestorIds = ancestorIds)
            if (restore.restoreTaskId.isEmpty()) return@runSuspendCatching
            repeat(RESTORE_POLL_LIMIT) {
                val task = boundClient.getTask(restore.restoreTaskId)
                if (task.phase == TaskPhase.COMPLETE) return@runSuspendCatching
                if (task.phase == TaskPhase.ERROR) error(task.message.ifBlank { "转存失败" })
                delay(RESTORE_POLL_INTERVAL_MILLIS)
            }
            error("转存超时")
        }.onSuccess { if (clientManager.currentClient.value === boundClient) applyChange(DriveChange.ContentsChanged(setOf(toParentId))) }
    }

    /**
     * 把 [fileIds] 分享为一条链接，文件与文件夹可以混在一起、不必同目录。
     * [passCode] 为 null 是公开链接，空串由服务端生成四位提取码，否则用它。[expirationDays] 为 -1 永久有效。
     */
    suspend fun createShare(fileIds: List<String>, passCode: String?, expirationDays: Int): Result<CreatedShare> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                client.createShare(
                    fileIds = fileIds,
                    requirePassCode = passCode != null,
                    customPassCode = passCode.orEmpty(),
                    expirationDays = expirationDays,
                )
            }
        }

    /** 自己的分享，一页，新的在前。取消了的不在里面；文件被删的仍在，状态是 DELETED。 */
    suspend fun myShares(pageToken: String = ""): Result<ShareListPage> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listMyShares(pageToken = pageToken) }
    }

    /** 取消分享，文件留在网盘里。 */
    suspend fun cancelShares(shareIds: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.deleteShares(shareIds) }
    }

    /** 全盘的星标文件与文件夹。服务端按 parent_id=* 一次返回全部，不分页。 */
    suspend fun starredFiles(): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching { sourceLedger.enrich(client.listStarred()) }
    }

    suspend fun setStarred(ids: List<String>, starred: Boolean): Result<Unit> = withContext(Dispatchers.Default) {
        // 星标只体现在列表条目的 tags 里，所在的那一层要重列才看得到
        val folders = knownParentsOf(ids)
        runSuspendCatching { if (starred) client.starFiles(ids) else client.unstarFiles(ids) }
            .onSuccess { applyChange(DriveChange.ContentsChanged(folders)) }
    }

    /** 播放历史的一页，按最近播放倒序，与官方客户端共用同一份。 */
    suspend fun playHistory(pageToken: String = ""): Result<EventPage> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listPlayHistory(pageToken = pageToken).withSources() }
    }

    /**
     * 最近添加的一页：上传与离线、秒传加进网盘的文件，按时间倒序，与官方客户端「最近添加」同一份。
     * 明写这两种类型，不用不带过滤的查询：不带过滤时服务端恰好只给这两种（SDK 的 listEvents 实测），
     * 但那是服务端眼下的默认，以后多一种事件就会混进来。
     */
    suspend fun recentlyAdded(pageToken: String = ""): Result<EventPage> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.listEvents(listOf(EventType.UPLOAD, EventType.RESTORE), pageToken = pageToken).withSources() }
    }

    private fun EventPage.withSources() = copy(events = events.map { event -> event.copy(file = event.file?.let(sourceLedger::enrich)) })

    /** 删掉几条事件记录（播放历史或最近添加里的一行），文件本身不动。 */
    suspend fun deleteEvents(eventIds: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.deleteEvents(eventIds) }
    }

    suspend fun clearPlayHistory(): Result<Unit> = withContext(Dispatchers.Default) {
        runSuspendCatching { client.clearEvents(listOf(EventType.PLAY)) }
    }

    fun folderMeaningless(folderId: String): Boolean? = folderMeaninglessCache[folderId]

    fun cacheFolderMeaningless(folderId: String, value: Boolean) {
        folderMeaninglessCacheFlow.update { it + (folderId to value) }
    }

    /**
     * 秒传与离线任务的默认保存目录。根目录里已有同类目录就沿用，没有才新建。
     *
     * 要列全根目录再找：只看第一页的话，根目录条目一多，已有的那个目录落在后面几页，
     * 每次都会再建一个同名目录。
     */
    /** 根目录里的 My Pack，没有时为 null，不新建：侧边栏只为显示它不该往网盘里添一个文件夹。 */
    suspend fun findMyPacksFolder(): Result<PikoPathBreadcrumb?> =
        listAllFiles().map { files -> files.firstOrNull { it.isMyPacksFolder() }?.let { PikoPathBreadcrumb(it.id, it.name) } }

    suspend fun getOrCreateMyPacksFolder(): Result<PikoPathBreadcrumb> {
        val access = currentAccount?.let(::writeAccess) ?: return Result.failure(IllegalStateException("未登录"))
        return getOrCreateMyPacksFolder(access)
    }

    internal suspend fun getOrCreateMyPacksFolder(access: DriveWriteAccess): Result<PikoPathBreadcrumb> {
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
            if (!forceRefresh) folderMeaninglessCache[folderId]?.let { return@withContext it }
            val result = listFiles(folderId, sortOrder = PikoFileSortOrder.TIME_DESC)
                .getOrDefault(emptyList<FileStat>() to "").first
                .isEmpty()
            cacheFolderMeaningless(folderId, result)
            result
        }

    /**
     * 文件夹在前，各自按 [order] 排。根目录的 My Pack 不论哪种排序都排第一：
     * 秒传与离线任务默认存进它，是根目录里最常进的目录。
     */
    fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder, parentId: String): List<FileStat> {
        val (folders, regularFiles) = files.partition { it.isFolder }
        fun durationOf(file: FileStat): Double? = file.params["duration"]?.toDoubleOrNull()?.takeIf { it > 0.0 }
        val nameComparator = compareBy(NaturalOrder, FileStat::name)
        val comparator = when (order) {
            // 自然顺序：第 2 集排在第 10 集前面
            PikoFileSortOrder.NAME_ASC -> nameComparator
            PikoFileSortOrder.NAME_DESC -> nameComparator.reversed()
            // 按创建时间而非 modified_time：目录的 modified_time 不随其中内容变动而更新，
            // 与创建时间相同（2026-09-23 实测），按它排序只会让「修改时间」名不副实
            PikoFileSortOrder.TIME_DESC -> compareByDescending(FileStat::createdTime)
            PikoFileSortOrder.TIME_ASC -> compareBy(FileStat::createdTime)
            PikoFileSortOrder.SIZE_DESC -> compareByDescending(FileStat::sizeBytes)
            PikoFileSortOrder.SIZE_ASC -> compareBy(FileStat::sizeBytes)
            // 分层后再比较，避免视频按大小、其他文件按名称导致比较关系成环。
            PikoFileSortOrder.DURATION_DESC, PikoFileSortOrder.DURATION_ASC -> Comparator { a, b ->
                val da = durationOf(a)
                val db = durationOf(b)
                val ta = if (da != null) 0 else if (a.isPlayableVideo()) 1 else 2
                val tb = if (db != null) 0 else if (b.isPlayableVideo()) 1 else 2
                val descending = order == PikoFileSortOrder.DURATION_DESC
                val tier = ta.compareTo(tb)
                if (tier != 0) tier else {
                    val duration = if (ta == 0) {
                        if (descending) db!!.compareTo(da!!) else da!!.compareTo(db!!)
                    } else 0
                    val size = if (ta < 2) {
                        if (descending) b.sizeBytes.compareTo(a.sizeBytes) else a.sizeBytes.compareTo(b.sizeBytes)
                    } else 0
                    duration.takeIf { it != 0 } ?: size.takeIf { it != 0 } ?: nameComparator.compare(a, b)
                }
            }
        }
        val folderComparator = if (order == PikoFileSortOrder.DURATION_DESC || order == PikoFileSortOrder.DURATION_ASC) nameComparator else comparator
        val sortedFolders = folders.sortedWith(folderComparator)
        val pinned = if (parentId.isEmpty()) sortedFolders.firstOrNull { it.isMyPacksFolder() } else null
        val orderedFolders = if (pinned == null) sortedFolders else listOf(pinned) + (sortedFolders - pinned)
        return orderedFolders + regularFiles.sortedWith(comparator)
    }

    private fun FileStat.isMyPacksFolder() = isFolder && name.lowercase() in MY_PACKS_FOLDER_NAMES

    companion object {
        val ROOT_BREADCRUMB = PikoPathBreadcrumb("", "我的网盘")
        private const val TAG = "DriveRepository"

        // 批量操作的日志里列出的 ID 个数：够在网盘里对上是哪几项，几百项的移动不至于占满一屏
        private const val LOGGED_IDS = 5
        private val LINK_RETRY_DELAYS = listOf(500.milliseconds, 1.seconds, 2.seconds)
        private const val UPLOAD_BYTES_ATTEMPTS = 3
        private const val MAX_LOCATE_DEPTH = 64
        private const val CHILD_NAME_PAGE = 20
        private const val CHILD_NAME_CONCURRENCY = 2
        private const val MAX_REMEMBERED_CHILD_NAMES = 200

        // 外层文件夹的封面只要一张，其下文件夹多半都有视频，读几个就够；读不到的不必为一张封面列遍整棵树
        private const val COVER_PROBE_MEMBERS = 3

        // 目录图一次展开十几层、每层几十个文件夹，再加网盘页走过的，一百多个够覆盖一次浏览
        private const val RECENT_LISTINGS = 128

        // 目录图、›、补全直接用最近列过的而不重取的期限。网盘页不看它：网盘页总是先显示缓存、随即刷新
        private val RECENT_LISTING_FRESH = 60.seconds

        // 登录后让首屏列根目录与会话恢复先行，再在后台拉齐整棵本地文件树
        private val TREE_WARMUP_DELAY = 1500.milliseconds
        private val CANARY_PROBE_COOLDOWN = 60.seconds
        private const val CANARY_WINDOW_SIZE = 100
        private const val MAX_PAGINATED_CANARY_AGE_MILLIS = 15 * 60 * 1_000L
        private const val TREE_SNAPSHOT_SAVE_DELAY = 2_000L

        private const val MY_PACKS_FOLDER_NAME = "My Packs"

        // 转存任务实测一秒内完成；给大目录留到一分钟
        private const val RESTORE_POLL_LIMIT = 60
        private const val RESTORE_POLL_INTERVAL_MILLIS = 1_000L

        // PikPak 各端自动建的保存目录名不一，官方客户端建过的也算
        private val MY_PACKS_FOLDER_NAMES = setOf("my pack", "my packs", "我的资源", "我的离线")
    }
}
