package dev.piko.shared.data

import dev.piko.shared.util.putIfAbsentCompat
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import dev.piko.shared.i18n.tr

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import io.github.nihildigit.pikpak.FileReactivatingException
import io.github.nihildigit.pikpak.UrlExpiredException
import kotlinx.io.IOException
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileFilter
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
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
import io.github.nihildigit.pikpak.consumeFileChanges
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
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.debounce
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
    private val treeDatabaseFactory: PikoTreeDatabaseFactory? = null,
) {
    private val client get() = clientManager.currentClient.value ?: error("Not logged in")

    // 与进程同寿：仓库本身就是进程级的
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val localMounts by lazy { LocalMounts(preferences, { currentAccount }, { folders ->
        if (folders.isNotEmpty()) {
            normalizeLocations()
            _folderChanges.tryEmit(DriveChange.ContentsChanged(folders))
        }
    }, backgroundScope) }

    // 读写发生在 Dispatchers.Default 的多个线程上，普通 HashMap 并发写会丢项甚至破坏结构。
    // commonMain 没有 ConcurrentHashMap，借 MutableStateFlow.update 的 CAS 做原子替换。
    private val folderMeaninglessCacheFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    protected val folderMeaninglessCache: Map<String, Boolean> get() = folderMeaninglessCacheFlow.value
    private val _quotaFlow = MutableStateFlow<QuotaResponse?>(null)
    val quotaFlow: StateFlow<QuotaResponse?> = _quotaFlow.asStateFlow()

    // 带上取的是哪个账号：取回来之前可能已经换了号，按 quotaFlow 的当下值记会记到新账号头上
    private val _quotaUpdates = MutableSharedFlow<Pair<String, QuotaResponse>>(extraBufferCapacity = 4)
    val quotaUpdates: SharedFlow<Pair<String, QuotaResponse>> = _quotaUpdates.asSharedFlow()
    private val _transferQuotaFlow = MutableStateFlow<TransferQuota?>(null)
    val transferQuotaFlow: StateFlow<TransferQuota?> = _transferQuotaFlow.asStateFlow()
    private data class FolderTitleKey(val id: String, val name: String, val language: String, val contentHash: Int)
    private val folderTitleCache = LinkedHashMap<FolderTitleKey, String?>()
    private val folderTitleLock = PikoLock()

    /**
     * 当前账号是不是免费账号，未取到时为 null。取自流量额度的 vip_status 或配额的 user_type，登录与换号时各取一次。
     * 免费账号的保存与离线规则不同，见 SDK 的 FreeAccountProbeTest 实测结论。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isFreeAccountFlow: StateFlow<Boolean?> =
        clientManager.currentClient.flatMapLatest { active ->
            active?.isFreeAccountFlow ?: _transferQuotaFlow.map { it?.account?.isPremium?.not() }
        }.stateIn(backgroundScope, SharingStarted.Eagerly, clientManager.currentClient.value?.isFreeAccount)
    /** 文件夹的名字与上级，见 [FolderIndex]。下面的路径栈、标签、历史与最近去过都随它改正。 */
    internal val folderIndex = FolderIndex()
    internal var driveTreeIndex = DriveTreeIndex(
        locallyCachedManifestIds = { vaultBinding.value?.store?.parsedFlow?.value?.keys.orEmpty() },
    )
        private set
    private val treeBindingFlow = MutableStateFlow(driveTreeIndex)
    private val treeStateLock = PikoLock()
    private val treeSyncJob = MutableStateFlow<Pair<PikPakClient, Deferred<Unit>>?>(null)
    private val treeBuildMutex = Mutex()
    private var activeBuildToken: Any? = null
    private data class TreeSave(val state: DriveTreeIndex.State, val syncedAt: Long)
    private class TreeWriterBinding(val account: String, val generation: Long, val source: MutableStateFlow<VersionedSnapshot<TreeSave>>, val job: Job, val writer: Deferred<SnapshotWriter<TreeSave>>)
    private var treeWriter: TreeWriterBinding? = null
    private var treeWriterHandoff: Deferred<Unit>? = null
    private var lastCanaryProbeAt: TimeSource.Monotonic.ValueTimeMark? = null

    data class TreeBuildProgress(
        val isBuilding: Boolean = false,
        val isInitialBlocking: Boolean = false,
        val isCheckingUpdates: Boolean = false,
        val foldersRead: Int = 0,
        val filesRead: Int = 0,
        val manifestsRead: Int = 0,
        val manifestsTotal: Int = 0,
    )

    private val _treeBuildProgressFlow = MutableStateFlow(TreeBuildProgress())
    val treeBuildProgressFlow: StateFlow<TreeBuildProgress> = _treeBuildProgressFlow.asStateFlow()

    private val spaceRefreshes = MutableStateFlow<Map<String, Long>>(emptyMap())
    private data class SpaceSyncState(val running: Boolean = false, val error: String? = null)

    fun refreshSpaceFolder(folderId: String) {
        spaceRefreshes.update { it + (folderId to ((it[folderId] ?: 0L) + 1L)) }
    }

    /** 按需异步拉取大文件夹内部更完整的嵌套预览子项列表，消除固定 16 项截断留下的静态余量块。 */
    suspend fun loadSpaceFolderPreview(
        folderId: String,
        includeVaulted: Boolean,
        limit: Int = 128,
    ): List<dev.piko.shared.space.SpacePreviewBlock> = withContext(Dispatchers.Default) {
        val index = withPikoLock(treeStateLock) { driveTreeIndex }
        spaceFolderPreviewChildren(index, folderId, includeVaulted, limit)
    }

    /** Errors stop automatic retries until the user explicitly refreshes this folder. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeSpaceFolder(folderId: String, includeVaulted: Boolean): Flow<dev.piko.shared.space.SpaceFolderSnapshot> =
        clientManager.currentClient.flatMapLatest { asked ->
            flow {
                if (asked != null && clientManager.currentClient.value === asked) enterAccount(asked.account)
                emitAll(treeBindingFlow.flatMapLatest { index ->
                    spaceRefreshes.map { it[folderId] ?: 0L }.distinctUntilChanged().flatMapLatest {
                        kotlinx.coroutines.flow.channelFlow {
                            val status = MutableStateFlow(SpaceSyncState())
                            fun pendingFolders(): Set<String> = withPikoLock(treeStateLock) {
                                val descendants = index.descendantFolders(folderId) + folderId
                                val state = index.flow.value
                                descendants.filterTo(LinkedHashSet()) { it in state.dirtyFolders || it !in state.listedFolders }
                            }
                            val isBuildingFlow = treeBuildProgressFlow.map { it.isBuilding }.distinctUntilChanged()
                            combine(index.flow, isBuildingFlow, status) { _, building, sync ->
                                spaceFolderSnapshot(index, folderId, includeVaulted, sync.error == null && (building || sync.running))
                                    .copy(syncError = sync.error)
                            }.conflate().collect { snapshot ->
                                send(snapshot)
                                if (asked != null && clientManager.currentClient.value === asked &&
                                    driveTreeIndex === index && !status.value.running && status.value.error == null &&
                                    (!index.isComplete || pendingFolders().isNotEmpty())
                                ) {
                                    status.value = SpaceSyncState(running = true)
                                    launch {
                                        try {
                                            var activeSync: Deferred<Unit>? = if (!index.isComplete) ensureTreeSync(asked) else null
                                            var replacements = 0
                                            while (activeSync != null && !index.isComplete) {
                                                currentCoroutineContext().ensureActive()
                                                if (driveTreeIndex !== index || clientManager.currentClient.value !== asked) {
                                                    throw CancellationException("Account changed")
                                                }
                                                val awaited = activeSync
                                                try {
                                                    awaited.await()
                                                    activeSync = null
                                                } catch (cancelled: CancellationException) {
                                                    currentCoroutineContext().ensureActive()
                                                    if (driveTreeIndex !== index || clientManager.currentClient.value !== asked) {
                                                        throw cancelled
                                                    }
                                                    val replacement = treeSyncJob.value
                                                        ?.takeIf { it.first === asked && it.second !== awaited }
                                                        ?.second
                                                    if (replacement != null && ++replacements <= 8) {
                                                        activeSync = replacement
                                                    } else {
                                                        status.value = SpaceSyncState(error = tr(
                                                            "空间统计同步已中断", "Space statistics sync interrupted", "容量統計の同期が中断されました", "공간 통계 동기화가 중단되었습니다", "空間統計同步已中斷",
                                                        ))
                                                        return@launch
                                                    }
                                                }
                                            }
                                            val attempted = HashSet<String>()
                                            while (true) {
                                                val pending = pendingFolders() - attempted
                                                if (pending.isEmpty()) break
                                                for (id in pending) {
                                                    currentCoroutineContext().ensureActive()
                                                    if (driveTreeIndex !== index || clientManager.currentClient.value !== asked) {
                                                        throw CancellationException("Account changed")
                                                    }
                                                    try {
                                                        fetchListing(id, asked)
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
                                            PikoLog.w(TAG, "同步空间分布失败", failure)
                                            status.value = SpaceSyncState(error = tr(
                                                "空间统计同步失败", "Space statistics sync failed", "容量統計の同期に失敗しました", "공간 통계 동기화 실패", "空間統計同步失敗",
                                            ))
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

    enum class CanaryProbeResult { UNCHANGED, INCREMENTAL_PATCH, FULL_REBUILD }

    val currentAccount: String? get() = clientManager.currentClient.value?.account
    val accountChanges: Flow<String?> = clientManager.currentClient.map { it?.account }.distinctUntilChanged()
    private val _clientRevision = MutableStateFlow(0L)
    val clientRevision: StateFlow<Long> = _clientRevision.asStateFlow()
    internal val accountGeneration: Long get() = withPikoLock(treeStateLock) { changeMarks.value.accountResetAt }
    val metadataSink = DriveMetadataSink { owner ->
        withPikoLock(treeStateLock) {
            if (clientManager.currentClient.value !== owner) null else {
                val generation = accountGeneration
                val boundIndex = driveTreeIndex
                DriveDetailObserver { _, detail ->
                    withPikoLock(treeStateLock) {
                        if (clientManager.currentClient.value === owner && generation == accountGeneration) {
                            boundIndex.observeDetail(detail)
                            scheduleTreeSnapshotSave()
                        }
                    }
                }
            }
        }
    }

    internal val sortContextRevision: Long get() = driveTreeIndex.flow.value.revision
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observeSortRevision(): Flow<Long> = treeBindingFlow.flatMapLatest { index ->
        index.flow.map { it.revision }.debounce(50L)
    }

    /** 本地持久树绑定或快照恢复推进时发出，供首帧尚未等到 SQLite 打开的列表页即时补涂本地缓存、提前撤下骨架屏。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun observeTreeCacheReady(): Flow<Long> = treeBindingFlow.flatMapLatest { index ->
        index.flow.map { it.revision }
    }
    internal fun sortContextFor(files: List<FileStat>, order: PikoFileSortOrder? = null): SortContext {
        val index = withPikoLock(treeStateLock) { driveTreeIndex }
        return index.sortContext(files, order)
    }

    fun writeAccess(expectedAccount: String): DriveWriteAccess? = clientManager.currentClient.value
        ?.takeIf { it.account == expectedAccount }?.let { asked ->
            DriveWriteAccess(asked.account, asked, this, vaultForClient(asked))
        }

    internal fun isCurrentClient(asked: PikPakClient): Boolean = clientManager.currentClient.value === asked

    fun historyAccess(expectedAccount: String): HistoryWriteAccess? = clientManager.currentClient.value
        ?.takeIf { it.account == expectedAccount }?.let(::HistoryWriteAccess)

    internal fun applyWriteChange(account: String, change: DriveChange, notifyChange: Boolean = true) {
        if (currentAccount != account) return
        if (change is DriveChange.Renamed) pinnedFolders.renamed(change.id, change.name)
        applyChange(change, notifyChange)
    }

    private val references = MutableStateFlow<Set<FolderReferenceSource>>(emptySet())
    internal fun registerFolderReferences(source: FolderReferenceSource) { references.update { it + source } }
    internal fun unregisterFolderReferences(source: FolderReferenceSource) { references.update { it - source } }

    private val dataAccount = MutableStateFlow<String?>(null)
    private val accountTransitionLock = PikoLock()
    private var accountBindingReady = true
    private val pendingServerDeletes = MutableStateFlow<Set<String>>(emptySet())
    private data class CachedTrashListing(
        val client: PikPakClient,
        val files: List<FileStat>,
        val listedAt: TimeSource.Monotonic.ValueTimeMark,
    )
    private val cachedTrashListing = MutableStateFlow<CachedTrashListing?>(null)
    private val trashListingRequest = MutableStateFlow<Pair<PikPakClient, Deferred<List<FileStat>>>?>(null)

    private fun invalidateTrashCache() {
        cachedTrashListing.value = null
        trashListingRequest.value = null
    }

    fun enterAccount(account: String?) = withPikoLock(accountTransitionLock) {
        val retired = withPikoLock(treeStateLock) {
            val previous = dataAccount.value
            if (previous == account && accountBindingReady) return@withPikoLock null
            if (previous == null && accountBindingReady && treeDatabaseFactory == null) {
                dataAccount.value = account
                return@withPikoLock null
            }
            withPikoLock(vaultStoreLock) {
                vaultBinding.value?.takeIf { it.client.account != account }?.let(::retireVaultStoreLocked)
            }
            if (previous != null) flushPendingTreeSnapshotSave(previous)
            dataAccount.value = null
            accountBindingReady = false
            pendingServerDeletes.value = emptySet()
            invalidateTrashCache()
            val old = driveTreeIndex
            // Publish an empty generation before any fallible or blocking database work.
            driveTreeIndex = DriveTreeIndex()
            resetChangeMarksForAccountSwitch()
            treeBindingFlow.value = driveTreeIndex
            lastCanaryProbeAt = null
            _treeBuildProgressFlow.value = TreeBuildProgress()
            treeSyncJob.update { current ->
                current?.second?.cancel()
                null
            }
            listingRequests.value = emptyMap()
            listings.value = emptyMap()
            folderIndex.clear()
            if (driveTreeIndex.isPersistent) populateDerivedFromTreeLocked()
            folderMeaninglessCacheFlow.value = emptyMap()
            withPikoLock(folderTitleLock) { folderTitleCache.clear() }
            _folderEmptiness.value = emptyMap()
            old
        } ?: return@withPikoLock
        runCatching { retired.close() }.logFailure(TAG, "关闭旧账号文件树失败")
        val opened = runCatching {
            val database = account?.let { treeDatabaseFactory?.open(it) }
            try {
                DriveTreeIndex(
                    locallyCachedManifestIds = { vaultBinding.value?.takeIf { it.client.account == account }?.store?.parsedFlow?.value?.keys.orEmpty() },
                    database = database,
                    ownerAccount = account.orEmpty(),
                )
            } catch (failure: Throwable) {
                try { database?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }.logFailure(TAG, "打开账号文件树失败")
        opened.getOrNull()?.let { ready ->
            withPikoLock(treeStateLock) {
                driveTreeIndex = ready
                treeBindingFlow.value = ready
                dataAccount.value = account
                accountBindingReady = true
                if (ready.isPersistent) populateDerivedFromTreeLocked()
            }
        }
        backgroundScope.launch { dev.piko.shared.state.DriveAnalysisCache.clear() }
        changes.clear()
    }

    private val pinnedFolders = PinnedFolders(preferences, backgroundScope)

    /** 固定到快速访问的文件夹，按固定的先后排，见 [PinnedFolders]。名字经文件夹索引换成眼下的。 */
    val pinnedFoldersFlow: Flow<List<PikoPathBreadcrumb>>
        get() = combine(pinnedFolders.flow, folderIndex.flow, localMounts.flow, accountChanges) { pins, _, mounts, _ ->
            pins.filterNot { LocalLocation.isLocal(it.id) }.map(folderIndex::withCurrentName) +
                mounts.filter { it.account == currentAccount && it.pinned }.map { PikoPathBreadcrumb(it.location.id, it.fallbackName) }
        }

    fun pinFolder(folder: PikoPathBreadcrumb) {
        if (LocalLocation.isLocal(folder.id)) backgroundScope.launch { localMounts.pin(folder.id, true) } else pinnedFolders.pin(folder)
    }

    fun unpinFolder(folderId: String) {
        if (LocalLocation.isLocal(folderId)) backgroundScope.launch { localMounts.pin(folderId, false) } else pinnedFolders.unpin(folderId)
    }

    /** 做过的改动，能撤销的记在这里，见 [DriveChangeJournal]。 */
    val changes = DriveChangeJournal(this, backgroundScope)

    // 用事件流而非 StateFlow：订阅方只需被动收到「哪里变了」，不需要初值，也不该在重组时重放。
    // 订阅方只是据此重列，丢掉最旧的几条好过让发出改动的一方卡住
    private val _folderChanges = MutableSharedFlow<DriveChange>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 网盘里的改动，见 [DriveChange]。网盘页与目录图据此刷新受影响的那几层。 */
    val folderChanges: SharedFlow<DriveChange> = _folderChanges.asSharedFlow()

    /**
     * 仅将内存中的列表缓存标为过期并触发轻量哨兵探针对账，绝不抹除已持久化的完整 SQLite 本地文件树索引。
     * 供托盘唤醒窗口、同账号重连/令牌刷新等无需作废整棵本地树的场景调用。
     */
    fun refreshActiveListings() {
        withPikoLock(treeStateLock) {
            listingRequests.value = emptyMap()
            listings.update { cache -> cache.mapValues { (_, listing) -> listing.expired() } }
        }
        // 通知所有活跃视图与在途首载会话（如 GallerySession）重列受影响视图，不经过 applyChange 以保护已持久化的完整 SQLite 树索引
        _folderChanges.tryEmit(DriveChange.ContentsChanged(null))
        val asked = clientManager.currentClient.value ?: return
        backgroundScope.launch {
            if (driveTreeIndex.isComplete) {
                probeRemoteChanges(asked).logFailure(TAG, "唤醒后增量对账本地文件树失败")
            } else {
                runSuspendCatching { ensureTreeSync(asked).await() }
                    .logFailure(TAG, "唤醒后继续建立本地文件树索引失败")
            }
        }
    }

    /**
     * 网盘里的改动都经这里收口：仓库自己做成的新建、改名、移动、删除，以及界面外的上传、解压、转存、归档。
     * 文件夹索引跟着改，各处存着的路径随之改正；受影响的列表标为过期，在路上的列目录不再合并进来；
     * 最后广播给订阅者。新加改动网盘的入口照此调一次，不要自己清缓存或通知界面。
     */
    private val optimisticWrites = OptimisticWrites()
    private val _optimisticRevision = MutableStateFlow(0L)
    val optimisticRevision = _optimisticRevision.asStateFlow()
    val hasPendingOptimisticWrites: Boolean get() = optimisticWrites.hasPending(currentAccount) ||
        vaultBinding.value?.let { it.client === clientManager.currentClient.value && it.store.hasPendingWrites } == true
    internal fun hasPendingOptimisticWritesFor(account: String): Boolean = optimisticWrites.hasPending(account)
    fun optimisticCandidates(): List<FileStat> = optimisticWrites.candidates(currentAccount)
    fun projectOptimisticFiles(files: List<FileStat>, folderId: String? = null, trash: Boolean = false): List<FileStat> =
        optimisticWrites.project(currentAccount, folderId, files, trash) { id ->
            folderIndex.confirmedNode(id)?.parentId ?: driveTreeIndex.file(id)?.parentId ?: knownConfirmedFile(id)?.parentId
        }

    private fun observeChangeTargetsForQuota(asked: PikPakClient, ids: Collection<String>, before: Map<String, FileStat> = emptyMap()) {
        val fileStats = ArrayList<FileStat>(ids.size)
        for (id in ids) {
            if (LocalLocation.isLocal(id) || VaultEntry.isVaulted(id)) continue
            val known = before[id] ?: knownConfirmedFile(id)
            if (known != null) {
                if (known.isFolder) {
                    val folderBytes = driveTreeIndex.folderUsage(id)?.bytes
                    if (folderBytes != null && folderBytes > 0L) {
                        asked.observeFolderSubtree(id, folderBytes, trashed = known.trashed)
                    }
                } else {
                    fileStats += known
                }
            } else {
                val folderBytes = driveTreeIndex.folderUsage(id)?.bytes
                if (folderBytes != null && folderBytes > 0L) {
                    asked.observeFolderSubtree(id, folderBytes, trashed = false)
                }
            }
        }
        if (fileStats.isNotEmpty()) {
            asked.observeFiles(fileStats)
        }
    }

    internal fun beginOptimisticWrite(account: String, change: DriveChange): OptimisticWrites.Write {
        val before = change.fileIds().mapNotNull { id -> knownFile(id)?.let { id to it } }.toMap()
        val asked = clientManager.currentClient.value?.takeIf { it.account == account }
        val write = optimisticWrites.begin(account, change, before, asked)
        if (currentAccount == account) {
            if (asked != null) {
                observeChangeTargetsForQuota(asked, change.fileIds(), before)
                when (change) {
                    is DriveChange.Trashed -> asked.noteFilesTrashed(change.ids)
                    is DriveChange.Deleted -> asked.noteFilesDeleted(change.ids)
                    is DriveChange.Restored -> asked.noteFilesUntrashed(change.ids)
                    else -> Unit
                }
            }
            withPikoLock(treeStateLock) { markChanged(change.folders) }
            folderIndex.project { optimisticWrites.projectFolders(currentAccount, it) }
            normalizeLocations()
            _optimisticRevision.update { it + 1 }
        }
        return write
    }

    internal fun finishOptimisticWrite(write: OptimisticWrites.Write, succeeded: Boolean) {
        val boundClient = write.client ?: clientManager.currentClient.value?.takeIf { it.account == write.account }
        if (!succeeded && boundClient != null) {
            val revertSnapshots = write.change.fileIds().map { id ->
                write.before[id] ?: FileStat(
                    id = id,
                    trashed = write.change is DriveChange.Restored,
                )
            }
            if (revertSnapshots.isNotEmpty()) {
                boundClient.revertFiles(revertSnapshots)
            }
        }
        if (currentAccount == write.account && succeeded) applyChange(write.change)
        optimisticWrites.finish(write)
        if (!optimisticWrites.hasPending(write.account)) {
            boundClient?.quotaFlow?.value?.let { confirmedQuota ->
                _quotaUpdates.tryEmit(write.account to confirmedQuota)
            }
        }
        if (currentAccount == write.account) {
            withPikoLock(treeStateLock) { markChanged(write.change.folders) }
            folderIndex.project { optimisticWrites.projectFolders(currentAccount, it) }
            normalizeLocations()
            _optimisticRevision.update { it + 1 }
            _folderChanges.tryEmit(DriveChange.ContentsChanged(write.change.folders))
        }
    }

    fun applyChange(change: DriveChange) = applyChange(change, notifyChange = true)

    private fun applyChange(change: DriveChange, notifyChange: Boolean) {
        if (change is DriveChange.ContentsChanged && change.folders?.all(LocalLocation::isLocal) == true) {
            if (notifyChange) _folderChanges.tryEmit(change)
            return
        }
        if (change is DriveChange.Deleted) pendingServerDeletes.update { it + change.ids }
        if (change is DriveChange.Trashed ||
            change is DriveChange.Restored ||
            change is DriveChange.Deleted ||
            (change is DriveChange.ContentsChanged && change.folders == null)
        ) {
            invalidateTrashCache()
        }
        val indexChanged = withPikoLock(treeStateLock) {
            clientManager.currentClient.value?.let { asked ->
                when (change) {
                    is DriveChange.Trashed -> {
                        observeChangeTargetsForQuota(asked, change.ids)
                        asked.noteFilesTrashed(change.ids)
                    }
                    is DriveChange.Deleted -> {
                        observeChangeTargetsForQuota(asked, change.ids)
                        asked.noteFilesDeleted(change.ids)
                    }
                    is DriveChange.Restored -> {
                        observeChangeTargetsForQuota(asked, change.ids)
                        asked.noteFilesUntrashed(change.ids)
                    }
                    else -> Unit
                }
            }
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
                is DriveChange.Copied, is DriveChange.Starred, is DriveChange.ContentsChanged -> false
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
                    change is DriveChange.Trashed || change is DriveChange.Deleted -> Listing(
                        listing.files.filterNot { it.id in change.fileIds() }, listing.listedAt, listing.stale,
                    )
                    change is DriveChange.Moved -> Listing(
                        listing.files.filterNot { it.id in change.ids && id != change.to }
                            .map { if (it.id in change.ids) it.copy(parentId = change.to) else it },
                        listing.listedAt, listing.stale,
                    )
                    change is DriveChange.Renamed && notifyChange -> Listing(
                        listing.files.map { file -> if (file.id == change.id) file.copy(name = change.name) else file },
                        listing.listedAt, listing.stale,
                    )
                    change is DriveChange.Starred && notifyChange -> {
                        val targetIds = change.ids.toSet()
                        if (listing.files.none { it.id in targetIds }) listing
                        else Listing(
                            listing.files.map { file ->
                                if (file.id !in targetIds) file
                                else {
                                    val withoutStar = file.tags.filterNot { it.name.equals("STAR", ignoreCase = true) }
                                    val nextTags = if (change.starred) withoutStar + FileTag(id = "STAR", name = "STAR") else withoutStar
                                    file.copy(starred = change.starred, tags = nextTags)
                                }
                            },
                            listing.listedAt, listing.stale,
                        )
                    }
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
            LocalLocation.of(fileId)?.let { local ->
                val mount = localMounts.find(fileId) ?: error("本地文件夹已取消挂载")
                val host = if (mount.parentFolderId.isEmpty()) listOf(ROOT_BREADCRUMB) else {
                    locateFolder(mount.parentFolderId).getOrThrow() + PikoPathBreadcrumb(mount.parentFolderId,
                        folderIndex.node(mount.parentFolderId)?.name ?: fetchDetail(mount.parentFolderId, asked).name)
                }
                val localParent = local.parent ?: return@runSuspendCatching host
                return@runSuspendCatching host + localMounts.pathTo(localParent.id).orEmpty()
            }
            require(!LocalLocation.isLocal(fileId)) { "本地位置无效" }
            val vaultEntryId = VaultEntry.entryIdOf(fileId)
            if (vaultEntryId != null) {
                val store = vault
                var parentId = driveTreeIndex.file(fileId)?.parentId
                    ?: store.folderOfEntry(vaultEntryId)
                    ?: vaultStars.folderOf(vaultEntryId)
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
                        parentId = driveTreeIndex.file(fileId)?.parentId ?: store.folderOfEntry(vaultEntryId)
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
                            parentId = driveTreeIndex.file(fileId)?.parentId ?: store.folderOfEntry(vaultEntryId)
                            if (parentId != null) break
                        }
                    }
                }
                val resolvedParent = parentId ?: error(tr("未找到归档条目所在的文件夹", "Containing folder of archived item not found", "アーカイブ項目の保存先フォルダーが見つかりません", "보관 항목의 상위 폴더를 찾을 수 없습니다", "找不到歸檔項目所在的資料夾"))
                if (resolvedParent.isEmpty()) return@runSuspendCatching listOf(ROOT_BREADCRUMB)
                val treeFolders = driveTreeIndex.flow.value.entries
                val folderNode = folderIndex.node(resolvedParent)?.takeUnless { it.provisional }
                val treeFolder = treeFolders[resolvedParent]?.takeIf { it.isFolder }
                val knownName = folderNode?.name ?: treeFolder?.name
                val knownUpper = folderNode?.parentId ?: treeFolder?.parentId
                if (knownName != null && knownUpper != null) {
                    val above = if (knownUpper.isEmpty()) listOf(ROOT_BREADCRUMB) else locateFolder(resolvedParent).getOrThrow()
                    return@runSuspendCatching above + PikoPathBreadcrumb(resolvedParent, knownName)
                }
                val detail = fetchDetail(resolvedParent, asked)
                val above = if (detail.parentId.isEmpty()) listOf(ROOT_BREADCRUMB) else locateFolder(resolvedParent).getOrThrow()
                return@runSuspendCatching above + PikoPathBreadcrumb(detail.id, detail.name)
            }
            val chain = ArrayDeque<PikoPathBreadcrumb>()
            val treeFolders = driveTreeIndex.flow.value.entries
            var parentId = knownParentOf(fileId)
                ?: driveTreeIndex.file(fileId)?.parentId
                ?: fetchDetail(fileId, asked).parentId
            repeat(MAX_LOCATE_DEPTH) {
                if (parentId.isEmpty()) return@runSuspendCatching listOf(ROOT_BREADCRUMB) + chain
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

    /** 本地目录从挂载宿主的真实路径拼接，不写入云端文件夹索引。 */
    fun knownLocalPath(folderId: String): List<PikoPathBreadcrumb>? {
        val local = LocalLocation.of(folderId) ?: return null
        val mount = localMounts.findCached(folderId) ?: return null
        val host = folderIndex.pathTo(mount.parentFolderId, provisional = true) ?: return null
        return host + PikoPathBreadcrumb(mount.location.id, mount.fallbackName) + local.segments.mapIndexed { index, name ->
            PikoPathBreadcrumb(LocalLocation(local.mountId, local.segments.take(index + 1)).id, name)
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
    internal fun knownFile(fileId: String): FileStat? = knownConfirmedFile(fileId)?.let {
        projectOptimisticFiles(listOf(it), trash = it.trashed).firstOrNull()
    }

    internal fun knownConfirmedFile(fileId: String): FileStat? = listings.value.values.asSequence().filter { !it.stale }
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
    class FolderNotFoundException(val name: String) : Exception(tr("未找到文件夹「$name」", "Folder “$name” not found", "フォルダー「$name」が見つかりません", "“$name” 폴더를 찾을 수 없습니다", "找不到資料夾「$name」"))

    /**
     * [folderId] 下的文件夹，按名字自然排序，根目录下不含 `.piko`。地址栏的 › 与补全用，与目录图、网盘页读同一份列表缓存，
     * 缓存里没有、已过期或 [fresh] 时才请求。库不是文件夹，下面没有子文件夹，也不拿它的 ID 去请求。
     */
    suspend fun subfolders(folderId: String, fresh: Boolean = false): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            if (!isDriveFolderId(folderId) && !LocalLocation.isLocal(folderId)) return@runSuspendCatching emptyList()
            val files = listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()
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
            val files = listBrowsable(folderId, PikoFileSortOrder.NAME_ASC).getOrThrow()
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
        val weight: Long = 128L + files.sumOf { file ->
            256L + 2L * (file.id.length + file.parentId.length + file.name.length + file.thumbnailLink.length +
                file.params.entries.sumOf { it.key.length + it.value.length })
        }
        fun expired() = if (stale) this else Listing(files, listedAt, stale = true)
    }

    private val listings = MutableStateFlow<Map<String, Listing>>(emptyMap())

    private fun rememberListing(folderId: String, files: List<FileStat>) {
        val kept = locationFolderIds()
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

    private fun locationFolderIds(): Set<String> = references.value.flatMap { it.retainedPaths }.flatMapTo(HashSet()) { stack -> stack.map { it.id } }

    private fun cachedListing(folderId: String): List<FileStat>? = listings.value[folderId]?.files?.let { projectOptimisticFiles(it, folderId) }

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
        require(!LocalLocation.isLocal(parentId)) { "本地目录不支持云端操作" }
        val driveTreeIndex = withPikoLock(treeStateLock) {
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            this.driveTreeIndex
        }
        val request = backgroundScope.async(start = CoroutineStart.LAZY) {
            val serial = withPikoLock(treeStateLock) { changeMarks.value.serial }
            // 补来源放在这里：列表缓存、目录图、地址栏与网盘页读的都是这一份
            val listed = asked.listFiles(parentId)
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            val files = projectOptimisticFiles(sourceLedger.enrich(listed), parentId)
            var indexUpdated = false
            var reparentedFrom = emptySet<String>()
            var shouldSaveSnapshot = false
            withPikoLock(treeStateLock) {
                if (clientManager.currentClient.value === asked && this@PikoDriveRepository.driveTreeIndex === driveTreeIndex && unchangedSince(parentId, serial) && !hasPendingOptimisticWrites) {
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
    private val vaultStoreLock = PikoLock()
    private val vaultHandoffs = HashMap<String, Job>()

    private fun retireVaultStoreLocked(binding: VaultBinding) {
        vaultHandoffs[binding.client.account] = binding.store.close().also { closing ->
            closing.invokeOnCompletion { failure ->
                if (failure != null && failure !is CancellationException) PikoLog.w(TAG, "保存旧账号归档缓存失败", failure)
            }
        }
        if (vaultBinding.value === binding) vaultBinding.value = null
    }
    private val loggedOutVault = VaultStore(object : VaultFolderIo {
        override suspend fun list(folderId: String): List<FileStat> = error(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
        override suspend fun read(fileId: String): ByteArray = error(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
        override suspend fun upload(folderId: String, name: String, bytes: ByteArray): String = error(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
        override suspend fun delete(ids: List<String>): Unit = error(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入"))
    })

    /** 缓存和待写投影随 client 分开；旧事务的所有 IO 始终使用它开始时的 client。 */
    open val vault: VaultStore get() {
        val asked = clientManager.currentClient.value ?: return loggedOutVault
        return storeForClient(asked)
    }

    internal fun vaultForClient(asked: PikPakClient): VaultStore {
        val store = vault
        val binding = vaultBinding.value?.takeIf { it.store === store }
        if (binding == null) return store
        if (binding.client === asked) return store
        return storeForClient(asked)
    }

    private fun storeForClient(asked: PikPakClient): VaultStore = withPikoLock(vaultStoreLock) {
            val current = vaultBinding.value
            if (current?.client === asked) return@withPikoLock current.store
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            current?.let(::retireVaultStoreLocked)
            vaultHandoffs.entries.removeAll { it.value.isCompleted }
            val boundIndex = driveTreeIndex
            val store = VaultStore(
                io = object : VaultFolderIo {
                    override suspend fun list(folderId: String) = withContext(Dispatchers.Default) { asked.listFiles(folderId) }
                    override suspend fun read(fileId: String) = withContext(Dispatchers.Default) { readBytes(asked, fileId) }
                    override suspend fun upload(folderId: String, name: String, bytes: ByteArray) = withContext(Dispatchers.Default) { uploadBytes(asked, folderId, name, bytes) }
                    override suspend fun delete(ids: List<String>) = withContext(Dispatchers.Default) { asked.batchDelete(ids) }
                },
                onChanged = { folder ->
                    withPikoLock(treeStateLock) {
                        if (clientManager.currentClient.value === asked && driveTreeIndex === boundIndex) {
                            val projected = vaultBinding.value?.takeIf { it.client === asked }?.store?.projectedEntries(folder)
                            if (projected == null) applyChange(DriveChange.ContentsChanged(setOf(folder)))
                            else {
                                markChanged(setOf(folder))
                                vaultEntriesKnown(folder, projected, reconcileStars = false)
                                _folderChanges.tryEmit(DriveChange.ContentsChanged(setOf(folder)))
                            }
                        }
                    }
                },
                onWritten = { folder, entries ->
                    withPikoLock(treeStateLock) {
                        if (clientManager.currentClient.value === asked && driveTreeIndex === boundIndex) vaultEntriesKnown(folder, entries)
                    }
                },
                onCommitted = { folder, entries, snapshot ->
                    withPikoLock(treeStateLock) {
                        if (clientManager.currentClient.value === asked && driveTreeIndex === boundIndex) {
                            commitVaultListing(asked, folder, entries, snapshot)
                        }
                    }
                },
                currentFolderSerial = { withPikoLock(treeStateLock) { changeMarks.value.serial } },
                cacheStore = cacheStore,
                account = asked.account,
                scope = backgroundScope,
                predecessor = vaultHandoffs[asked.account],
            )
            vaultBinding.value = VaultBinding(asked, store)
            store
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
        if (LocalLocation.isLocal(parentId)) return runSuspendCatching { sortFiles(localMounts.list(parentId), sortOrder, parentId) }
        localMounts.all() // Ensure device preferences have been loaded before merging roots.
        val asked = clientManager.currentClient.value ?: return Result.failure(IllegalStateException(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入")))
        return listBrowsable(parentId, sortOrder, asked, vault)
    }

    /** 遍历在打开时固定 client 和归档仓库；换号后不能继续拿新账号列旧目录。 */
    internal fun browsableReader(expectedAccount: String): (suspend (String) -> Result<List<FileStat>>)? {
        val asked = clientManager.currentClient.value?.takeIf { it.account == expectedAccount } ?: return null
        val store = vaultForClient(asked)
        if (clientManager.currentClient.value !== asked) return null
        return browsableReaderForClient(asked, store, requireActiveClient = true)
    }

    internal fun browsableReaderForClient(
        asked: PikPakClient,
        store: VaultStore,
        requireActiveClient: Boolean = false,
    ): suspend (String) -> Result<List<FileStat>> = reader@{ folderId ->
        if (clientManager.currentClient.value !== asked && requireActiveClient) throw CancellationException("Account changed")
        if (LocalLocation.isLocal(folderId)) return@reader runSuspendCatching { localMounts.list(folderId) }
        if (clientManager.currentClient.value === asked) {
            val state = driveTreeIndex.flow.value
            if (state.complete && folderId !in state.dirtyFolders && (folderId.isEmpty() || folderId in state.entries)) {
                val real = driveTreeIndex.realChildren(folderId).files
                val indexedVault = driveTreeIndex.vaultEntries(folderId)
                val hasUnresolvedManifest = folderId in state.manifestsByFolder &&
                    indexedVault == null && store.cached(folderId, real) == null
                if (!hasUnresolvedManifest) {
                    val entries = indexedVault ?: store.cached(folderId, real).orEmpty()
                    return@reader Result.success(withVaulted(folderId, real, entries, PikoFileSortOrder.TIME_DESC))
                }
            }
            return@reader listBrowsable(folderId, PikoFileSortOrder.TIME_DESC, asked, store)
        }
        if (requireActiveClient) throw CancellationException("Account changed")
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                val raw = asked.listFiles(folderId)
                val entries = store.read(folderId, raw).getOrDefault(emptyList())
                sortFiles(
                    raw.filterNot { isPikoInternalFolder(it, folderId) || VaultStore.isRecoveryFile(it) || VaultStore.looksLikeManifest(it) } +
                        entries.filter { it.isArchived }.map { it.toFileStat(folderId) },
                    PikoFileSortOrder.TIME_DESC,
                    folderId,
                )
            }
        }
    }

    /** 供查重、规范命名等真实文件扫描器在本地树或单目录已就绪且未脏时直接切片，避免发起网络请求。 */
    internal fun localRealChildrenIfClean(folderId: String): List<FileStat>? {
        val state = driveTreeIndex.flow.value
        if (folderId in state.dirtyFolders) return null
        val children = driveTreeIndex.realChildren(folderId)
        if (children.coverage == DriveTreeIndex.FolderCoverage.COMPLETE) return children.files
        if (!state.complete) return null
        if (folderId.isNotEmpty() && folderId !in state.entries) return null
        return children.files
    }

    /**
     * 扫描专用真实子项读取：命中干净本地树时零网络直出；遇到未同步或脏目录时拉取远端并回填 [DriveTreeIndex]（修复脏态），
     * 同时不写入有容量上限的 [childContents] 免得全盘扫描冲刷目录摘要缓存。
     */
    internal suspend fun scanRealChildren(folderId: String, asked: PikPakClient = client): List<FileStat> {
        requireCloudIds(listOf(folderId))
        val driveTreeIndex = withPikoLock(treeStateLock) {
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            this.driveTreeIndex
        }
        localRealChildrenIfClean(folderId)?.let { return it }
        val serial = withPikoLock(treeStateLock) { changeMarks.value.serial }
        val raw = asked.listFiles(folderId)
        val enriched = sourceLedger.enrich(raw)
        var indexUpdated = false
        withPikoLock(treeStateLock) {
            if (clientManager.currentClient.value === asked && this.driveTreeIndex === driveTreeIndex && unchangedSince(folderId, serial)) {
                val reparented = driveTreeIndex.putFolderListing(folderId, enriched, complete = true)
                markFolderListed(folderId, reparented)
                indexUpdated = folderIndex.listed(folderId, enriched, complete = true)
            }
        }
        if (indexUpdated) normalizeLocations()
        return enriched
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
            val entries = driveTreeIndex.vaultEntries(folderId) ?: vault.cached(folderId, children.files).orEmpty()
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
        val visible = withVaultCovers(projectOptimisticFiles(listing, folderId).filterNot { isPikoInternalFolder(it, folderId) || VaultStore.isRecoveryFile(it) }) + localMounts.cachedRoots(folderId)
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
                    matchById != null && matchByName != null -> if (matchById.first <= matchByName.first) matchById.second else matchByName.second
                    matchById != null -> matchById.second
                    matchByName != null -> matchByName.second
                    else -> null
                }
                matched?.enrich(file, preserveStar = vaultStars.isStarred(matched.id)) ?: file
            }
        }
        return sortFiles(
            enriched + entries.filter { it.isArchived }.map { it.toFileStat(folderId, starred = vaultStars.isStarred(it.id)) },
            sortOrder,
            folderId,
        )
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

    /** Piko 自管的归档条目星标账本，见 [VaultStars]。跨设备的同步在 VaultStarSync。 */
    val vaultStars = VaultStars(cacheStore, backgroundScope)

    /** 秒传文件的来源，列出的文件经它补上 sourceUrl，见 [SourceLedger]。跨设备的同步在 SourceLedgerSync。 */
    val sourceLedger = SourceLedger(cacheStore, backgroundScope)

    private val _folderEmptiness = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val folderEmptiness: StateFlow<Map<String, Boolean>> = _folderEmptiness.asStateFlow()

    /** 完整列举或全量归档成功确认无剩余真实文件；未知目录保留继续归档入口。 */
    val fullyVaultedFolders: StateFlow<Set<String>> by lazy {
        combine(childContents.fullyVaultedFolders, vaultedFolders) { full, marked -> full intersect marked }
            .stateIn(backgroundScope, SharingStarted.Eagerly, emptySet())
    }

    /** 挂归档标记的文件夹：直接放着归档条目的，加上归档时选的那一层到它们之间的各层。 */
    val vaultedFolders: StateFlow<Set<String>> by lazy {
        combine(childContents.vaultedFolders, childContents.listedFolders, vaultTrees.flow, VaultTrees::marked)
            .stateIn(backgroundScope, SharingStarted.Eagerly, emptySet())
    }

    init {
        enterAccount(clientManager.currentClient.value?.account)
        backgroundScope.launch {
            dev.piko.shared.memory.PikoMemoryGovernor.Shared.policy.collect { policy ->
                if (policy != dev.piko.shared.memory.MemoryPolicy.Normal) {
                    val active = references.value.flatMap { it.retainedPaths }.mapNotNullTo(HashSet()) { it.lastOrNull()?.id }
                    listings.update { cache -> cache.filterKeys { it in active } }
                    withPikoLock(folderTitleLock) { folderTitleCache.clear() }
                    dev.piko.shared.state.DriveAnalysisCache.clear()
                }
            }
        }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous: PikPakClient? = null
            clientManager.currentClient.collect { current ->
                val old = previous
                previous = current
                if (old != null && current != null && old !== current && old.account == current.account) {
                    refreshActiveListings()
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
                vaultStars.switchAccount(it?.account)
                sourceLedger.switchAccount(it?.account)
            }
        }
        backgroundScope.launch {
            vaultStars.changedFolders.collect { folders ->
                if (folders.isNotEmpty()) notifyFoldersRefreshed(folders)
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
        // 额度与账号类型属于账号：换号时同步切到新 client 已恢复的快照，断线重连换同一账号的新 client 时继承旧值
        backgroundScope.launch {
            var account: String? = null
            clientManager.currentClient.collectLatest { client ->
                if (client?.account != account) {
                    account = client?.account
                    _quotaFlow.value = client?.currentQuota
                    _transferQuotaFlow.value = client?.currentTransferQuota
                    // Logout seals the database generation as well; the persisted account file remains.
                    enterAccount(client?.account)
                } else if (client != null) {
                    val prevQuota = _quotaFlow.value
                    val prevTq = _transferQuotaFlow.value
                    if (prevQuota != null || prevTq != null) {
                        client.seedAccountState(quota = prevQuota, transferQuota = prevTq)
                    }
                    _quotaFlow.value = client.currentQuota
                    _transferQuotaFlow.value = client.currentTransferQuota
                }
                if (client == null) return@collectLatest
                launch {
                    client.quotaFlow.collect { q ->
                        if (clientManager.currentClient.value === client && q != null) {
                            _quotaFlow.value = q
                            _quotaUpdates.tryEmit(client.account to q)
                            if (driveTreeIndex.isComplete && driveTreeIndex.lastUsageBytes == null) {
                                driveTreeIndex.setLastUsageBytes(q.quota.usageBytes)
                                scheduleTreeSnapshotSave()
                            }
                        }
                    }
                }
                launch {
                    client.transferQuotaFlow.collect { tq ->
                        if (clientManager.currentClient.value === client && tq != null) {
                            _transferQuotaFlow.value = tq
                            cacheStore?.let { store ->
                                backgroundScope.launch {
                                    runCatching { store.write(transferQuotaCacheKey(client.account), QUOTA_JSON.encodeToString(TransferQuota.serializer(), tq)) }
                                }
                            }
                        }
                    }
                }
                // 优先从本机缓存零网络恢复云盘流量与完整文件树快照，不阻塞在任何网络请求之后
                if (cacheStore != null) {
                    if (_transferQuotaFlow.value == null) {
                        val cachedTq = runCatching {
                            cacheStore.read(transferQuotaCacheKey(client.account))
                                ?.let { QUOTA_JSON.decodeFromString(TransferQuota.serializer(), it) }
                        }.getOrNull()
                        if (cachedTq != null && clientManager.currentClient.value === client && _transferQuotaFlow.value == null) {
                            client.seedAccountState(transferQuota = cachedTq)
                            _transferQuotaFlow.value = client.currentTransferQuota ?: cachedTq
                        }
                    }
                    if (driveTreeIndex.isPersistent && driveTreeIndex.isComplete) {
                        val restoredIndex = driveTreeIndex
                        PikoLog.i(
                            TAG,
                            "本地文件树索引已从 SQLite 秒开复用：${restoredIndex.flow.value.entries.size} 个文件夹",
                        )
                        launch {
                            migrateLegacyTreeCache(restoredIndex, client.account, cacheStore)
                            delay(TREE_WARMUP_DELAY)
                            if (clientManager.currentClient.value === client && driveTreeIndex === restoredIndex) {
                                val firstProbe = probeRemoteChanges(client)
                                if (firstProbe.isFailure && clientManager.currentClient.value === client && driveTreeIndex === restoredIndex) {
                                    delay(2_000L)
                                    if (clientManager.currentClient.value === client && driveTreeIndex === restoredIndex) {
                                        probeRemoteChanges(client, force = true).logFailure(TAG, "后台哨兵探针对账失败")
                                    }
                                }
                            }
                        }
                    }
                    if (!driveTreeIndex.isComplete) {
                        launch {
                            val restored = restoreTreeFromCache(client)
                            if (clientManager.currentClient.value !== client) return@launch
                            delay(TREE_WARMUP_DELAY)
                            if (clientManager.currentClient.value !== client) return@launch
                            if (restored) {
                                val firstProbe = probeRemoteChanges(client)
                                if (firstProbe.isFailure && clientManager.currentClient.value === client) {
                                    delay(2_000L)
                                    if (clientManager.currentClient.value === client) {
                                        probeRemoteChanges(client, force = true).logFailure(TAG, "后台哨兵探针对账失败")
                                    }
                                }
                            } else if (!driveTreeIndex.isComplete) {
                                val firstSync = runSuspendCatching { ensureTreeSync(client).await() }
                                if (firstSync.isFailure && clientManager.currentClient.value === client && !driveTreeIndex.isComplete) {
                                    // 启动初期若撞上失效默认域名超时、随后测速已切到可用域名，自动在新域名上补跑一次
                                    delay(2_000L)
                                    if (clientManager.currentClient.value === client && !driveTreeIndex.isComplete) {
                                        runSuspendCatching { ensureTreeSync(client, forceRebuild = true).await() }
                                            .logFailure(TAG, "后台建立本地文件树索引失败")
                                    }
                                } else {
                                    firstSync.logFailure(TAG, "后台建立本地文件树索引失败")
                                }
                            }
                        }
                    }
                }
                // 并行单飞刷新云盘流量与存储配额，避免两项网络请求串行排队
                launch {
                    getTransferQuota().logFailure(TAG, "取账号类型失败")
                }
                launch {
                    getQuota().logFailure(TAG, "取剩余空间失败，借出归档条目暂不设上限").getOrNull()?.quota?.takeIf { it.limitBytes > 0 }?.let { quota ->
                        if (client.leaseBudget != null) {
                            PikoLog.d(TAG, "借出额度：剩余 ${quota.remainingBytes} 字节的九成")
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

    /**
     * 按内容哈希与字节数零网络汇总本机已知的归档条目与本地目录树真实文件元数据（CID、时长、分辨率、MIME），
     * 供「以引用保存」与「归档」共用补齐条目信息。
     */
    internal fun knownVaultMetaOf(gcid: String, size: Long): VaultMediaMeta? {
        if (gcid.isBlank()) return null
        val fromVault = vaultBinding.value?.store?.knownMetaOf(gcid, size)
        if (fromVault != null && fromVault.cid != null && fromVault.duration != null &&
            fromVault.width != null && fromVault.height != null && fromVault.mimeType != null
        ) {
            return fromVault
        }
        val content = ContentKey.of(gcid, size)?.let(driveTreeIndex::contentMetadata)
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

    internal fun rememberFullyVaulted(folderIds: Set<String>) {
        childContents.markFullyVaulted(folderIds, knownVaultParents())
    }

    /**
     * 清单读到或写成之后，按其中还有没有条目更新文件夹的标记。
     * 必须先通过清单身份校验才执行星标对账，且乐观待确认投影（[reconcileStars] 为假）严禁写取消星标墓碑。
     */
    internal fun vaultEntriesKnown(
        folderId: String,
        entries: List<VaultEntry>,
        expectedManifestId: String? = null,
        requireMatch: Boolean = false,
        reconcileStars: Boolean = true,
    ) {
        val starredIds = vaultStars.flow.value.values.asSequence().filter { it.starred }.mapTo(HashSet()) { it.entryId }
        val optimistic = vaultBinding.value?.store?.hasPending(folderId) == true
        if (!driveTreeIndex.putVaultEntries(folderId, entries, expectedManifestId, requireMatch, starredIds, optimistic = optimistic)) return
        if (reconcileStars) {
            vaultStars.reconcileFolder(folderId, entries, expectedAccount = currentAccount)
        }
        childContents.markVaulted(folderId, entries.any { it.isArchived })
        childContents.rememberCover(folderId, entries.coverGcid().orEmpty())
    }

    internal fun knownDescendantFolders(rootFolderId: String): Set<String> =
        if (rootFolderId.isEmpty()) emptySet() else driveTreeIndex.descendantFolders(rootFolderId)

    /**
     * 清单提交确认后，按证据类型更新本地文件树、列表缓存与目录摘要：
     * - [VaultCommitSnapshot.FullListing]：校验列目录发起时的变更序号，期间未发生并发文件改动时才完整写回，否则仅更新归档条目并标脏；
     * - [VaultCommitSnapshot.IncrementalFiles]：新目录快速通道仅原地追加新建的来源文件与清单，保留同目录已创建的嵌套子目录；
     * - null：回退为局部目录失效。
     */
    private fun commitVaultListing(
        asked: PikPakClient,
        folderId: String,
        entries: List<VaultEntry>,
        snapshot: VaultCommitSnapshot?,
    ) {
        when (snapshot) {
            null -> {
                if (currentAccount == asked.account) applyChange(DriveChange.ContentsChanged(setOf(folderId)))
            }
            is VaultCommitSnapshot.IncrementalFiles -> {
                val enrichedAdded = sourceLedger.enrich(snapshot.added)
                val applied = withPikoLock(treeStateLock) {
                    if (clientManager.currentClient.value !== asked) return@withPikoLock false
                    for (added in enrichedAdded) {
                        driveTreeIndex.applyChange(
                            DriveChange.Created(parentId = folderId, id = added.id, name = added.name, isFolder = added.isFolder, stat = added),
                        )
                    }
                    vaultEntriesKnown(folderId, entries)
                    listingRequests.update { it - folderId }
                    listings.update { cache ->
                        cache.mapValues { (id, listing) -> if (id == folderId) listing.expired() else listing }
                    }
                    _folderEmptiness.update { it + (folderId to false) }
                    true
                }
                if (!applied) return
                if (driveTreeIndex.isComplete) scheduleTreeSnapshotSave()
                _folderChanges.tryEmit(DriveChange.ContentsChanged(setOf(folderId)))
            }
            is VaultCommitSnapshot.FullListing -> {
                val enriched = sourceLedger.enrich(snapshot.listing)
                var indexUpdated = false
                var reparented: Set<String> = emptySet()
                var staleConcurrentMutation = false
                val applied = withPikoLock(treeStateLock) {
                    if (clientManager.currentClient.value !== asked) return@withPikoLock false
                    val serialOk = snapshot.listedAtSerial == null || unchangedSince(folderId, snapshot.listedAtSerial)
                    if (!serialOk) {
                        // 挂起清理旧清单期间同目录发生了其他文件变更：只提交归档条目事实，拒绝用旧快照覆盖最新文件节点
                        vaultEntriesKnown(folderId, entries)
                        staleConcurrentMutation = true
                        return@withPikoLock true
                    }
                    reparented = driveTreeIndex.putFolderListing(folderId, enriched, complete = true)
                    markFolderListed(folderId, reparented)
                    listingRequests.update { it - folderId }
                    rememberListing(folderId, enriched)
                    rememberChildContents(folderId, enriched, complete = true)
                    indexUpdated = folderIndex.listed(folderId, enriched, complete = true)
                    vaultEntriesKnown(folderId, entries)
                    true
                }
                if (!applied) return
                if (staleConcurrentMutation) {
                    applyChange(DriveChange.ContentsChanged(setOf(folderId)))
                    return
                }
                if (indexUpdated) normalizeLocations()
                if (driveTreeIndex.isComplete) scheduleTreeSnapshotSave()
                _folderChanges.tryEmit(DriveChange.ContentsChanged(setOf(folderId) + reparented))
            }
        }
    }

    /**
     * 仅通知视图与旧列表缓存刷新指定目录，对已在 [DriveTreeIndex] 同步干净的目录不标脏，且绝不传 null 作废整棵树。
     */
    internal fun notifyFoldersRefreshed(folders: Set<String>) {
        if (folders.isEmpty()) return
        val state = driveTreeIndex.flow.value
        val needsDirty = folders.filterTo(HashSet()) { folderId ->
            !(state.complete && folderId in state.listedFolders && folderId !in state.dirtyFolders)
        }
        if (needsDirty.isNotEmpty()) {
            applyChange(DriveChange.ContentsChanged(needsDirty))
        }
        val cleanOnly = folders - needsDirty
        if (cleanOnly.isNotEmpty()) {
            withPikoLock(treeStateLock) {
                listings.update { cache ->
                    cache.mapValues { (id, listing) -> if (id in cleanOnly) listing.expired() else listing }
                }
            }
            _folderChanges.tryEmit(DriveChange.ContentsChanged(cleanOnly))
        }
    }

    /**
     * 从已就绪且未脏的本地文件树中按相对路径列表（如 ["Season 1", "Season 1/SPs"]）零网络解析已存在的子目录 ID。
     */
    internal fun resolveExistingSubfoldersInTree(
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

    private suspend fun readAndRecordVaultEntries(
        folderId: String,
        listing: List<FileStat>,
        store: VaultStore,
        asked: PikPakClient,
        logFailure: Boolean = false,
        logTagSuffix: String = "",
    ): List<VaultEntry> {
        val boundIndex = withPikoLock(treeStateLock) { driveTreeIndex }
        val startedFromPending = store.hasPending(folderId)
        val expectedManifestId = store.winnerOf(listing)?.id
        val result = store.read(folderId, listing).let {
            if (logFailure) it.logFailure(TAG, "读取归档清单失败$logTagSuffix") else it
        }
        val entries = result.getOrNull() ?: return emptyList()
        withPikoLock(treeStateLock) {
            if (clientManager.currentClient.value !== asked || driveTreeIndex !== boundIndex) return entries
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

    /*
     * 列过的文件夹是不是空的，连子文件夹在内，按 ID；没列过的不在表里。海报墙给空文件夹画空的封面用。
     * 不从 childContents 推：那里只记文件，只有子文件夹的与真空的都是一张空表，分不开；它还跨进程保留，
     * 这里只在这一次运行里有效，重启后按需再探（probeFolderEmptiness）。
     */
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
            val serial = withPikoLock(treeStateLock) { changeMarks.value.serial }
            val files = runSuspendCatching { asked.listFilesPaged(parentId = folderId, pageSize = CHILD_NAME_PAGE).files }
                .getOrNull() ?: return@withPermit null
            val complete = files.size < CHILD_NAME_PAGE
            val indexUpdated = withPikoLock(treeStateLock) {
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
            if (LocalLocation.isLocal(parentId)) return@runSuspendCatching sortFiles(localMounts.list(parentId), sortOrder, parentId) to ""
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
        runSuspendCatching { sortFiles(if (LocalLocation.isLocal(parentId)) localMounts.list(parentId) else fetchListing(parentId), sortOrder, parentId) }
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
    private suspend fun fetchDetail(fileId: String, asked: PikPakClient = client): FileDetail {
        require(!LocalLocation.isLocal(fileId)) { "本地文件不支持云端操作" }
        val observer = metadataSink.capture(asked)
        return asked.getFile(fileId).also { detail ->
            observer?.observe(fileId, detail)
            if (clientManager.currentClient.value === asked) {
                val isFolder = detail.kind == FileKind.FOLDER
                if (folderIndex.learned(detail.id, detail.name, detail.parentId, isFolder, detail.trashed)) normalizeLocations()
            }
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

    suspend fun getQuota(maxAge: Duration = Duration.ZERO): Result<QuotaResponse> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = client
            val deferred = backgroundScope.async {
                asked.getQuota(maxAge).also {
                    if (clientManager.currentClient.value === asked) {
                        _quotaFlow.value = it
                        _quotaUpdates.tryEmit(asked.account to it)
                        if (driveTreeIndex.isComplete && driveTreeIndex.lastUsageBytes == null) {
                            driveTreeIndex.setLastUsageBytes(it.quota.usageBytes)
                            scheduleTreeSnapshotSave()
                        }
                    }
                }
            }
            deferred.await()
        }
    }

    /** [isFreeAccountFlow] 的当前值，登录时那一次还没取到就现取。取不到时为 null。 */
    suspend fun isFreeAccount(): Boolean? =
        isFreeAccountFlow.value ?: getTransferQuota().getOrNull()?.account?.isPremium?.not()

    /** 离线下载、下载、上传三项月度流量额度：由 SDK 单飞合并并发请求，并缓存上次已知快照供打开页面瞬时渲染。 */
    suspend fun getTransferQuota(maxAge: Duration = Duration.ZERO): Result<TransferQuota> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = client
            val deferred = backgroundScope.async {
                asked.getTransferQuota(maxAge).also { tq ->
                    if (clientManager.currentClient.value === asked) {
                        _transferQuotaFlow.value = tq
                        cacheStore?.let { store ->
                            backgroundScope.launch {
                                runCatching { store.write(transferQuotaCacheKey(asked.account), QUOTA_JSON.encodeToString(TransferQuota.serializer(), tq)) }
                            }
                        }
                    }
                }
            }
            deferred.await()
        }
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
        requireCloudIds(listOf(parentId))
        val size = bytes.size.toLong()
        val gcid = PikPakHash.fromSource(Buffer().apply { write(bytes) }, size)
        fun recordUploadedInTree(fileId: String) {
            withPikoLock(treeStateLock) {
                if (clientManager.currentClient.value === asked) {
                    driveTreeIndex.putFiles(
                        listOf(FileStat(id = fileId, parentId = parentId, name = name, kind = FileKind.FILE, hash = gcid, size = size.toString())),
                    )
                }
            }
        }
        val session = try {
            when (val start = asked.startUpload(parentId, name, size, gcid)) {
                is UploadStart.Instant -> return start.fileId.also(::recordUploadedInTree)
                is UploadStart.Pending -> start.session
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PikoLog.w(TAG, "小文件建单或初始化分片失败：size=$size, error=${e::class.simpleName}")
            throw e
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
                        freshSession = attempt == 0,
                    )
                    recordUploadedInTree(session.fileId)
                    return session.fileId
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    if (e is PikPakException || attempt == UPLOAD_BYTES_ATTEMPTS - 1) throw e
                    delay(LINK_RETRY_DELAYS.getOrElse(attempt) { 1.seconds })
                }
            }
            throw lastError ?: error(tr("上传失败", "Upload failed", "アップロードに失敗しました", "업로드 실패", "上傳失敗"))
        } catch (e: Throwable) {
            if (e !is CancellationException) {
                PikoLog.w(TAG, "小文件分片上传失败：host=${session.endpoint}, size=$size, error=${e::class.simpleName}")
            }
            withContext(NonCancellable) { runCatching { asked.cancelUpload(session) } }
            throw e
        }
    }

    /**
     * 读出一个小文件的全部内容，与 [uploadBytes] 配对。
     *
     * 刚传完的文件查详情有时还没有直链，稍后再查就有（设置同步与归档清单都撞到过，SDK 的 instantCreate 也记着同样的现象），
     * 没有直链、冷存储唤醒或传输暂时失败时退避重查详情，避免重复使用已经过期的签名。
     */
    suspend fun readBytes(fileId: String): Result<ByteArray> = withContext(Dispatchers.Default) {
        runSuspendCatching { readBytes(client, fileId) }
    }

    private suspend fun readBytes(asked: PikPakClient, fileId: String): ByteArray {
        requireCloudIds(listOf(fileId))
        return readSmallFileBytes(asked, fileId)
    }

    /** [parentId] 下名为 [name] 的文件夹，没有就新建。返回它的 ID。 */
    suspend fun folderNamed(parentId: String, name: String): Result<String> {
        val existing = listAllFiles(parentId).getOrElse { return Result.failure(it) }
            .firstOrNull { it.isFolder && it.name == name && !it.trashed }
        return existing?.let { Result.success(it.id) } ?: createFolder(parentId, name)
    }

    /** [fileId] 的取样 CID，读 60 KB。归档时记下，日后只读体检用，见 [VaultEntry.cid]。 */
    private fun requireCloudIds(ids: List<String>) {
        require(ids.none(LocalLocation::isLocal)) { "本地文件夹为只读" }
    }

    suspend fun sampleCid(fileId: String): Result<String> = withContext(Dispatchers.Default) {
        runSuspendCatching { requireCloudIds(listOf(fileId)); client.sampleCid(client.getFile(fileId)) }
    }

    /**
     * 按 gcid 秒传出一个文件，恢复归档条目用。[source] 是条目记着的来源，记进来源账本：取回的文件被复制、
     * 移出原文件夹后，清单里的取回记录就对不上了，账本按内容记，仍补得上。
     * [notifyChange] 为假时仍立即更新本地树与来源账本，但不在批量恢复中途逐文件广播列表重排。
     */
    suspend fun instantCreate(
        file: ResolvedFile,
        parentId: String,
        source: String? = null,
        notifyChange: Boolean = true,
    ): Result<String> = withContext(Dispatchers.Default) {
        val asked = client
        runSuspendCatching { requireCloudIds(listOf(parentId)); asked.instantCreate(file, parentId) }.onSuccess { id ->
            if (clientManager.currentClient.value === asked) {
                source?.let { sourceLedger.record(it, listOf(file)) }
                applyChange(DriveChange.Created(parentId, id, file.path.substringAfterLast('/'), isFolder = false), notifyChange)
            }
        }
    }

    suspend fun createFolder(parentId: String, name: String): Result<String> = withContext(Dispatchers.Default) {
        val asked = client
        runSuspendCatching { requireCloudIds(listOf(parentId)); asked.createFolder(parentId, name) }.onSuccess { id ->
            if (clientManager.currentClient.value === asked) {
                applyChange(DriveChange.Created(parentId, id, name, isFolder = true))
            }
        }
    }

    suspend fun rename(fileId: String, name: String, parentId: String? = null, notifyChange: Boolean = true): Result<Unit> = withContext(Dispatchers.Default) {
        val asked = client
        val effectiveParentId = parentId ?: knownParentOf(fileId)
        runSuspendCatching { requireCloudIds(listOf(fileId)); asked.rename(fileId, name) }.onSuccess {
            if (clientManager.currentClient.value === asked) {
                // 快速访问存的名字是别的设备上的回落（经设置同步），跟着改；本机显示的名字取自索引
                pinnedFolders.renamed(fileId, name)
                applyChange(DriveChange.Renamed(fileId, name, effectiveParentId), notifyChange)
            }
        }
    }

    suspend fun trash(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = client
        val from = knownParentsOf(ids)
        runSuspendCatching {
            requireCloudIds(ids)
            observeChangeTargetsForQuota(asked, ids)
            asked.batchTrash(ids)
        }.onSuccess {
            if (clientManager.currentClient.value === asked) applyChange(DriveChange.Trashed(ids, from))
        }.logOperation("移入回收站", ids, started)
    }

    suspend fun restore(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = client
        runSuspendCatching {
            requireCloudIds(ids)
            observeChangeTargetsForQuota(asked, ids)
            asked.batchUntrash(ids)
        }.onSuccess {
            if (clientManager.currentClient.value === asked) applyChange(DriveChange.Restored(ids))
        }.logOperation("从回收站恢复", ids, started)
    }

    suspend fun delete(ids: List<String>): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = client
        val from = ids.mapNotNullTo(HashSet(), ::knownParentOf)
        runSuspendCatching {
            requireCloudIds(ids)
            observeChangeTargetsForQuota(asked, ids)
            asked.batchDelete(ids)
        }.onSuccess {
            if (clientManager.currentClient.value === asked) applyChange(DriveChange.Deleted(ids, from))
        }.logOperation("彻底删除", ids, started)
    }

    suspend fun move(ids: List<String>, parentId: String): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = client
        // 原来在哪要在移动之前问：之后索引里已是新的上级
        val from = knownParentsOf(ids)
        runSuspendCatching { requireCloudIds(ids + parentId); asked.batchMove(ids, parentId) }.onSuccess {
            if (clientManager.currentClient.value === asked) applyChange(DriveChange.Moved(ids, from, parentId))
        }.logOperation("移动到 ${LocalLocation.logIdentity(parentId) ?: parentId.ifEmpty { "根目录" }}", ids, started)
    }

    /**
     * 复制到 [parentId]。服务端按任务执行，小批量在返回时已完成；目标里有同名项时自动改名为「名字(1)」。
     * 复制到自身或自己的子目录里会被拒绝（file_move_or_copy_to_cur）。SDK 已按 id 上限分批。
     */
    suspend fun copy(ids: List<String>, parentId: String): Result<Unit> = withContext(Dispatchers.Default) {
        val started = TimeSource.Monotonic.markNow()
        val asked = client
        runSuspendCatching { requireCloudIds(ids + parentId); asked.batchCopy(ids, parentId); Unit }.onSuccess {
            if (clientManager.currentClient.value === asked) applyChange(DriveChange.Copied(ids, parentId))
        }.logOperation("复制到 ${LocalLocation.logIdentity(parentId) ?: parentId.ifEmpty { "根目录" }}", ids, started)
    }

    /**
     * 批量改动各记一行：几项、前几个 ID、多久。失败只记错误名，堆栈由给用户提示的调用方记，
     * 那边的一行不带 ID，这一行补上是哪几项。逐项调用的（重命名、建文件夹）不在这里记，批量时会刷屏。
     */
    private fun <T> Result<T>.logOperation(action: String, ids: List<String>, started: TimeMark): Result<T> {
        val shown = ids.take(LOGGED_IDS).joinToString(",") { LocalLocation.logIdentity(it) ?: it } + if (ids.size > LOGGED_IDS) " 等" else ""
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
     * 触发后台双缓冲整树重建（如遇到 404/回收站残留或哨兵探针击穿时调用）。
     */
    fun requestTreeRebuild() {
        val asked = clientManager.currentClient.value ?: return
        ensureTreeSync(asked, forceRebuild = true)
    }

    /**
     * 用户在网盘根目录主动下拉刷新时调用：
     * - 若首次全盘建树仍在进行中，绝不取消作废已拉取的大半进度，直接沿用当前单飞建树协程；
     * - 完备的持久树使用服务端游标增量同步；游标缺失或过期时重新建立完整基线。
     */
    fun onRootPullRefresh() {
        val asked = clientManager.currentClient.value ?: return
        if (!driveTreeIndex.isComplete) {
            ensureTreeSync(asked, forceRebuild = false)
            return
        }
        backgroundScope.launch {
            probeRemoteChanges(asked, force = true).logFailure(TAG, "下拉刷新对账本地文件树失败")
        }
    }

    private val remoteDeltaMutex = Mutex()

    private suspend fun syncRemoteDelta(asked: PikPakClient, index: DriveTreeIndex): CanaryProbeResult = remoteDeltaMutex.withLock {
        fun ensureCurrent() {
            if (clientManager.currentClient.value !== asked || driveTreeIndex !== index) throw CancellationException("Account changed")
        }
        ensureCurrent()
        val cursor = index.syncCursor
        if (cursor == null) {
            ensureTreeSync(asked, forceRebuild = true).await()
            return@withLock CanaryProbeResult.FULL_REBUILD
        }
        val (serial, sequence) = withPikoLock(treeStateLock) {
            if (hasPendingOptimisticWrites) return@withLock CanaryProbeResult.UNCHANGED
            changeMarks.value.serial to index.currentMutationSeq
        }
        index.beginDelta()
        try {
            val checkpoint = asked.consumeFileChanges(cursor) { page ->
                ensureCurrent()
                index.appendDelta(sourceLedger.enrich(page.files))
            }
            val oldFolders = index.flow.value.entries
            val oldManifestFolders = index.flow.value.manifestsByFolder.keys
            val affected = withPikoLock(treeStateLock) {
                ensureCurrent()
                if (hasPendingOptimisticWrites || changeMarks.value.serial != serial || index.currentMutationSeq != sequence) {
                    return@withLock CanaryProbeResult.UNCHANGED
                }
                index.commitDelta(checkpoint.syncTime).also { parents ->
                    folderIndex.removed(oldFolders.keys.filter { index.file(it) == null }, emptySet())
                    populateDerivedFromTreeLocked()
                    normalizeLocations()
                    markCanaryPatched(parents)
                    listingRequests.update { it - parents }
                    listings.update { cache -> cache.mapValues { (id, listing) -> if (id in parents) listing.expired() else listing } }
                }
            }
            val store = vaultForClient(asked)
            val unresolved = index.flow.value.manifestsByFolder.keys.filter { index.vaultEntries(it) == null }
            for (parent in affected + unresolved) {
                ensureCurrent()
                val children = index.realChildren(parent).files
                rememberChildContents(parent, children, complete = true)
                if (children.any(VaultStore::looksLikeManifest) || parent in oldManifestFolders) {
                    runSuspendCatching { readAndRecordVaultEntries(parent, children, store, asked) }
                        .logFailure(TAG, "增量同步归档清单失败")
                }
            }
            if (affected.isNotEmpty() || unresolved.isNotEmpty()) _folderChanges.tryEmit(DriveChange.ContentsChanged(affected + unresolved))
            if (affected.isEmpty()) CanaryProbeResult.UNCHANGED else CanaryProbeResult.INCREMENTAL_PATCH
        } catch (_: io.github.nihildigit.pikpak.FileSyncVersionOutdatedException) {
            ensureCurrent()
            ensureTreeSync(asked, forceRebuild = true).await()
            CanaryProbeResult.FULL_REBUILD
        } finally {
            index.abortDelta()
        }
    }

    suspend fun probeRemoteChanges(
        asked: PikPakClient = client,
        force: Boolean = false,
    ): Result<CanaryProbeResult> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val driveTreeIndex = withPikoLock(treeStateLock) {
                if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
                this@PikoDriveRepository.driveTreeIndex
            }
            fun ensureGeneration() {
                if (clientManager.currentClient.value !== asked || this@PikoDriveRepository.driveTreeIndex !== driveTreeIndex) {
                    throw CancellationException("Account generation changed")
                }
            }
            if (!force) {
                val last = lastCanaryProbeAt
                if (last != null && last.elapsedNow() < CANARY_PROBE_COOLDOWN) {
                    return@runSuspendCatching CanaryProbeResult.UNCHANGED
                }
            }
            lastCanaryProbeAt = TimeSource.Monotonic.markNow()
            _treeBuildProgressFlow.update { if (it.isBuilding) it else it.copy(isCheckingUpdates = true) }
            try {
            if (driveTreeIndex.isPersistent && driveTreeIndex.isComplete) {
                return@runSuspendCatching syncRemoteDelta(asked, driveTreeIndex)
            }
            var shouldFullRebuild = true
            for (attempt in 0 until 4) {
                shouldFullRebuild = true
                if (!driveTreeIndex.isComplete) {
                    ensureTreeSync(asked).await()
                    return@runSuspendCatching CanaryProbeResult.FULL_REBUILD
                }
                val (probeStartSerial, probeStartSeq, state) = withPikoLock(treeStateLock) {
                    Triple(changeMarks.value.serial, driveTreeIndex.currentMutationSeq, driveTreeIndex.flow.value)
                }
                val quota = getQuota().getOrNull()?.quota
                val usageBytes = quota?.usageBytes
                val firstPage = asked.listFilesPaged(parentId = "*", pageSize = CANARY_WINDOW_SIZE)
                ensureGeneration()
                val prevUsage = state.lastUsageBytes
                val fullSnapshotVisible = firstPage.nextPageToken.isEmpty()

                val localCache = HashMap<String, FileStat?>()
                fun preloadLocal(items: Collection<FileStat>) {
                    val missingIds = items.map { it.id }.filterNot { localCache.containsKey(it) }
                    if (missingIds.isEmpty()) return
                    val fetched = driveTreeIndex.filesByIds(missingIds)
                    for (id in missingIds) localCache[id] = fetched[id]
                }
                fun localOf(id: String): FileStat? =
                    if (localCache.containsKey(id)) localCache[id] else driveTreeIndex.file(id).also { localCache[id] = it }

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
                        driveTreeIndex.hasPhysicalMissingFrom(remoteIds)
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
                    // 仅当本地已有真实目录从远端消失（被删入回收站或彻底删除）时才记为缺失；
                    // 新增、改名或移动的远端目录直接并入增量补丁，避免新增几个文件夹就触发 60 秒全盘重扫
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
                    driveTreeIndex.hasPhysicalMissingFrom(recent.mapTo(HashSet()) { it.id })
                val localPhysicalMatchesQuota = !fullSnapshotVisible && usageBytes != null && prevUsage != null &&
                    usageBytes != prevUsage && driveTreeIndex.totalPhysicalBytes() == usageBytes
                if (!hasMissingLocalEntries && !hasReconcilableDeletions && changedItems.isEmpty() &&
                    (prevUsage == null || usageBytes == null || usageBytes == prevUsage || localPhysicalMatchesQuota)
                ) {
                    val publishedUnchanged = withPikoLock(treeStateLock) {
                        ensureGeneration()
                        if (changeMarks.value.serial != probeStartSerial || driveTreeIndex.currentMutationSeq != probeStartSeq) {
                            false
                        } else {
                            if ((prevUsage == null || localPhysicalMatchesQuota) && usageBytes != null) {
                                driveTreeIndex.setLastUsageBytes(usageBytes)
                            }
                            true
                        }
                    }
                    if (!publishedUnchanged) {
                        shouldFullRebuild = false
                        continue
                    }
                    if ((prevUsage == null || localPhysicalMatchesQuota) && usageBytes != null) {
                        scheduleTreeSnapshotSave()
                    }
                    return@runSuspendCatching CanaryProbeResult.UNCHANGED
                }

                if (!hasMissingLocalEntries && !windowExceeded && prevUsage != null && usageBytes != null && changedItems.isNotEmpty()) {
                    val patchStarted = TimeSource.Monotonic.markNow()
                    val addedBytes = changedItems.sumOf { item ->
                        if (item.isFolder) 0L else item.sizeBytes - (localOf(item.id)?.sizeBytes ?: 0L)
                    }
                    if (!hasReconcilableDeletions && usageBytes >= prevUsage && usageBytes - prevUsage == addedBytes) {
                        val enriched = sourceLedger.enrich(changedItems)
                        val affectedParents = HashSet<String>()
                        val manifestFolders = HashSet<String>()
                        var indexUpdated = false
                        val publishedPatch = withPikoLock(treeStateLock) {
                            ensureGeneration()
                            if (changeMarks.value.serial != probeStartSerial || driveTreeIndex.currentMutationSeq != probeStartSeq) {
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
                        if (!publishedPatch) {
                            shouldFullRebuild = false
                            continue
                        }
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
                        PikoLog.i(TAG, "本地文件树索引增量更新完成：变更 ${enriched.size} 项，历时 ${patchStarted.elapsedNow().inWholeMilliseconds} ms")
                        return@runSuspendCatching CanaryProbeResult.INCREMENTAL_PATCH
                    }

                    // 跨设备 .piko/ 同步轮换旧文件、归档更新 .piko-vault-* 清单并删原文件或跨目录移动后，
                    // 被替换/删除的旧文件不在 recent 中，导致总额差值与单向 addedBytes 不等（甚至 usageBytes 下降）。
                    // 只要涉及的父目录数可控，直接并发重列这几个父目录即可精确算出净容量变化并原地完成增量对账，免去全盘 4 万文件重扫。
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
                                        folderId to sourceLedger.enrich(asked.listFiles(folderId).filterNot { it.trashed })
                                    }
                                }
                            }.awaitAll().toMap()
                        }
                        ensureGeneration()
                        var netBytesDelta = 0L
                        for ((folderId, liveChildren) in reconciledByFolder) {
                            val localPhysicalBytes = driveTreeIndex.realChildren(folderId).files
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
                            val enrichedChanged = sourceLedger.enrich(changedItems)
                            val affectedParents = HashSet<String>(reconciledByFolder.keys)
                            val manifestFolders = HashSet<String>()
                            var indexUpdated = false
                            val publishedReconciled = withPikoLock(treeStateLock) {
                                ensureGeneration()
                                if (changeMarks.value.serial != probeStartSerial || driveTreeIndex.currentMutationSeq != probeStartSeq) {
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
                                    if (liveChildren.any(VaultStore::looksLikeManifest) || driveTreeIndex.flow.value.manifestsByFolder.containsKey(folderId)) {
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
                                listingRequests.update { it - affectedParents }
                                if (outsideItems.isNotEmpty()) {
                                    driveTreeIndex.applyCanaryPatch(outsideItems, usageBytes = null)
                                }
                                for ((folderId, liveChildren) in reconciledByFolder) {
                                    driveTreeIndex.putFolderListing(folderId, liveChildren, complete = true)
                                    rememberListing(folderId, liveChildren)
                                    rememberChildContents(folderId, liveChildren, complete = true)
                                }
                                driveTreeIndex.setLastUsageBytes(usageBytes)
                                listings.update { cache ->
                                    cache.mapValues { (id, listing) -> if (id in affectedParents && id !in reconciledByFolder) listing.expired() else listing }
                                }
                                indexUpdated = learned
                                true
                            }
                            if (!publishedReconciled) {
                                shouldFullRebuild = false
                                continue
                            }
                            if (indexUpdated) normalizeLocations()
                            val store = vault
                            for (folderId in manifestFolders) {
                                val children = reconciledByFolder[folderId] ?: driveTreeIndex.realChildren(folderId).files
                                runSuspendCatching {
                                    readAndRecordVaultEntries(folderId, children, store, asked)
                                }
                            }
                            scheduleTreeSnapshotSave()
                            _folderChanges.tryEmit(DriveChange.ContentsChanged(affectedParents))
                            PikoLog.i(
                                TAG,
                                "本地文件树索引增量对账完成：变更 ${enrichedChanged.size} 项，重列目录 ${reconciledByFolder.size} 个，" +
                                    "历时 ${patchStarted.elapsedNow().inWholeMilliseconds} ms",
                            )
                            return@runSuspendCatching CanaryProbeResult.INCREMENTAL_PATCH
                        }
                    }
                }
                break
            }

            if (!shouldFullRebuild) {
                return@runSuspendCatching CanaryProbeResult.UNCHANGED
            }
            ensureTreeSync(asked, forceRebuild = true).await()
            CanaryProbeResult.FULL_REBUILD
            } finally {
                _treeBuildProgressFlow.update { it.copy(isCheckingUpdates = false) }
            }
        }
    }

    private suspend fun restoreTreeFromCache(asked: PikPakClient): Boolean = withContext(Dispatchers.Default) {
        val account = asked.account.takeIf { it.isNotEmpty() } ?: return@withContext false
        val (index, generation) = withPikoLock(treeStateLock) { driveTreeIndex to accountGeneration }
        fun isCurrent() = clientManager.currentClient.value === asked && accountGeneration == generation && driveTreeIndex === index
        if (index.isPersistent) {
            if (!isCurrent()) return@withContext false
            val imported = !index.isComplete && migrateLegacyTreeCache(index, account, cacheStore)
            if (imported && isCurrent()) {
                val vaultStore = vaultForClient(asked)
                vaultStore.awaitLoaded()
                for ((folderId, manifest) in index.flow.value.manifestsByFolder) {
                    if (!isCurrent()) return@withContext false
                    val entries = vaultStore.parsedFlow.value[manifest.id] ?: continue
                    index.putVaultEntries(folderId, entries, expectedManifestId = manifest.id, requireMatch = true)
                }
            }
            return@withContext withPikoLock(treeStateLock) {
                if (!isCurrent()) return@withPikoLock false
                if (populateDerivedFromTreeLocked()) normalizeLocations()
                index.isComplete
            }
        }
        val store = cacheStore ?: return@withContext false
        val handoff = withPikoLock(treeStateLock) { treeWriterHandoff }
        // The former generation must commit before this generation reads the same account key.
        // Waiting after admission would deadlock the former writer's persistence permit.
        handoff?.join()
        if (clientManager.currentClient.value !== asked || accountGeneration != generation) return@withContext false
        val vaultStore = vaultForClient(asked)
        vaultStore.awaitLoaded()
        val cachedManifests = vaultStore.parsedFlow.value
        val permit = dev.piko.shared.memory.PikoMemoryGovernor.Shared.acquire(dev.piko.shared.memory.MemoryWorkKind.SnapshotRead, 0)
        try {
        val snapshot = store.readStream(DriveTreeIndex.snapshotKeyOf(account), DriveTreeIndex::readSnapshot) ?: return@withContext false
        var indexUpdated = false
        val restored = withPikoLock(treeStateLock) {
            if (!isCurrent()) return@withPikoLock false
            if (!index.restoreSnapshot(snapshot, cachedManifests)) return@withPikoLock false
            indexUpdated = populateDerivedFromTreeLocked()
            true
        }
        if (!restored) return@withContext false
        withPikoLock(treeStateLock) {
            if (!isCurrent()) return@withContext false
            if (indexUpdated) normalizeLocations()
        }
        PikoLog.d(TAG, "已从本地快照恢复文件树：${snapshot.entries.size} 个节点，归档缓存 ${cachedManifests.size} 份")
        true
        } finally { permit.release() }
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

    private fun scheduleTreeSnapshotSave() = withPikoLock(treeStateLock) {
        if (driveTreeIndex.isPersistent) return@withPikoLock
        val store = cacheStore ?: return@withPikoLock
        val owner = currentAccount?.takeIf { it.isNotEmpty() } ?: return@withPikoLock
        val state = driveTreeIndex.flow.value
        if (!state.complete) return@withPikoLock
        val generation = accountGeneration
        val snapshot = VersionedSnapshot(PersistVersion(generation, state.revision), TreeSave(state, driveTreeIndex.lastFullSyncAtMillis))
        val binding = treeWriter?.takeIf { it.account == owner && it.generation == generation } ?: run {
            val source = MutableStateFlow(snapshot)
            val job = SupervisorJob()
            val writerScope = CoroutineScope(job + Dispatchers.Default)
            val predecessor = treeWriterHandoff
            val writer = writerScope.async {
                // Wait before creating a writer, not from capture/persist while holding its memory permit.
                predecessor?.join()
                SnapshotWriter(
                    scope = writerScope, accountGeneration = generation,
                    source = SnapshotSource { source.value }, debounceMillis = TREE_SNAPSHOT_SAVE_DELAY,
                ) { saved: TreeSave ->
                    try {
                        store.writeStream(DriveTreeIndex.snapshotKeyOf(owner)) { sink ->
                            DriveTreeIndex.writeSnapshot(sink, saved.state, kotlin.time.Clock.System.now().toEpochMilliseconds(), saved.syncedAt)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        PikoLog.w(TAG, "保存文件树快照失败", failure)
                        throw failure
                    }
                }
            }
            writerScope.launch {
                val ready = writer.await()
                source.collect { ready.markDirty(it.version) }
            }
            TreeWriterBinding(owner, generation, source, job, writer).also { treeWriter = it }
        }
        binding.source.value = snapshot
    }

    private fun flushPendingTreeSnapshotSave(previousAccount: String) = withPikoLock(treeStateLock) {
        if (driveTreeIndex.isPersistent) return@withPikoLock
        val binding = treeWriter?.takeIf { it.account == previousAccount } ?: return@withPikoLock
        treeWriter = null
        // The writer's source is sealed here; it never reads the new account's live tree.
        val state = driveTreeIndex.flow.value
        if (state.complete) binding.source.value = VersionedSnapshot(
            PersistVersion(binding.generation, state.revision), TreeSave(state, driveTreeIndex.lastFullSyncAtMillis),
        )
        treeWriterHandoff = backgroundScope.async {
            try {
                runSuspendCatching { binding.writer.await().flushThrough(binding.source.value.version) }
                    .logFailure(TAG, "保存旧账号文件树失败")
            } finally {
                binding.job.cancelAndJoin()
            }
            Unit
        }
    }

    private fun ensureTreeSync(asked: PikPakClient, forceRebuild: Boolean = false): Deferred<Unit> {
        while (true) {
            val existing = treeSyncJob.value
            if (!forceRebuild && existing != null && existing.first === asked && existing.second.isActive) {
                return existing.second
            }
            val token = Any()
            val (index, generation) = withPikoLock(treeStateLock) { driveTreeIndex to accountGeneration }
            fun isCurrent() = clientManager.currentClient.value === asked && accountGeneration == generation && driveTreeIndex === index
            val store = vaultForClient(asked)
            val isShadowRebuild = index.isComplete
            val job = backgroundScope.async(start = CoroutineStart.LAZY) {
                existing?.second?.cancelAndJoin()
                treeBuildMutex.withLock {
                if (!isCurrent()) return@withLock
                store.awaitLoaded()
                if (!isCurrent()) return@withLock
                if (index.isPersistent) {
                    val started = TimeSource.Monotonic.markNow()
                    val (candidate, serial) = withPikoLock(treeStateLock) {
                        if (!isCurrent()) return@withLock
                        activeBuildToken = token
                        _treeBuildProgressFlow.value = TreeBuildProgress(
                            isBuilding = true, isInitialBlocking = !isShadowRebuild,
                            foldersRead = 0, filesRead = 0,
                        )
                        index.beginCandidate() to changeMarks.value.serial
                    }
                    var committed = false
                    try {
                        var folderCount = 0
                        var fileCount = 0
                        val manifestByFolder = LinkedHashMap<String, MutableList<FileStat>>()
                        val resolvedVaultMeta = ArrayList<Pair<String, List<VaultEntry>>>()
                        var totalManifestFolders = 0
                        var syncCursor: String? = null
                        coroutineScope {
                            // 流水线并发预热归档清单：翻页扫描 4 万文件的同时后台即刻并发预拉取已发现的 .piko-vault-* 清单，消除两阶段串行等待
                            val prefetchQueue = kotlinx.coroutines.channels.Channel<FileStat>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                            val queuedManifestIds = HashSet<String>()
                            val prefetchCountLock = PikoLock()
                            var prefetchedCount = 0
                            fun incrementPrefetchedCount(): Int = withPikoLock(prefetchCountLock) { ++prefetchedCount }
                            val prefetchWorkers = List(MANIFEST_WARMUP_CONCURRENCY) {
                                launch {
                                    for (manifestStat in prefetchQueue) {
                                        if (!isCurrent()) break
                                        store.prefetchManifest(manifestStat)
                                        val done = incrementPrefetchedCount()
                                        _treeBuildProgressFlow.update { cur ->
                                            if (cur.manifestsTotal > 0) cur.copy(manifestsRead = done.coerceAtMost(cur.manifestsTotal)) else cur
                                        }
                                    }
                                }
                            }
                            try {
                                syncCursor = asked.consumeFileChanges(pageSize = 500) { page ->
                                    currentCoroutineContext().ensureActive()
                                    if (!isCurrent()) throw CancellationException("Account changed")
                                    val enriched = sourceLedger.enrich(page.files.filterNot { it.trashed })
                                    if (!isCurrent()) throw CancellationException("Account changed")
                                    index.appendCandidate(candidate, enriched)
                                    var learnedFolders = false
                                    val touchedManifestFolders = ArrayList<String>()
                                    withPikoLock(treeStateLock) {
                                        if (!isCurrent()) throw CancellationException("Account changed")
                                        for (entry in enriched) {
                                            if (entry.isFolder) {
                                                folderCount++
                                                if (!isShadowRebuild && folderIndex.learned(entry.id, entry.name, entry.parentId, isFolder = true, trashed = entry.trashed)) {
                                                    learnedFolders = true
                                                }
                                            } else {
                                                fileCount++
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
                                        if (store.cachedCommitted(listOf(winner)) != null) {
                                            if (queuedManifestIds.add(winner.id)) {
                                                val done = incrementPrefetchedCount()
                                                _treeBuildProgressFlow.update { cur ->
                                                    cur.copy(manifestsRead = done.coerceAtMost(cur.manifestsTotal.coerceAtLeast(done)))
                                                }
                                            }
                                        } else if (queuedManifestIds.add(winner.id)) {
                                            prefetchQueue.trySend(winner)
                                        }
                                    }
                                }.syncTime
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
                                        manifestPermits.withPermit {
                                            if (!isCurrent()) throw CancellationException("Account changed")
                                            val entries = store.read(folderId, manifests).getOrElse { throw it }
                                            withPikoLock(resolvedVaultLock) {
                                                doneManifestFolders++
                                                _treeBuildProgressFlow.update { it.copy(manifestsRead = doneManifestFolders) }
                                            }
                                            folderId to entries
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
                        // 先在锁外执行 SQLite commitCandidate 与子树聚合重算，严禁长时间占用 treeStateLock 阻塞主线程 UI
                        val preInvalidated = withPikoLock(treeStateLock) {
                            if (!isCurrent()) return@withLock
                            changeMarks.value.treeInvalidatedAt > serial
                        }
                        if (preInvalidated) {
                            requestTreeRebuild()
                            return@withLock
                        }
                        val changed = index.commitCandidate(
                            candidate, _quotaFlow.value?.quota?.usageBytes ?: index.lastUsageBytes,
                            kotlin.time.Clock.System.now().toEpochMilliseconds(),
                            syncCursor = syncCursor,
                        )
                        committed = true
                        var committedSuccessfully = false
                        withPikoLock(treeStateLock) {
                            if (!isCurrent()) return@withPikoLock
                            if (changeMarks.value.treeInvalidatedAt > serial) {
                                requestTreeRebuild()
                                return@withPikoLock
                            }
                            committedSuccessfully = true
                            for ((folderId, entries) in resolvedVaultMeta) {
                                vaultStars.reconcileFolder(folderId, entries, expectedAccount = currentAccount)
                                childContents.markVaulted(folderId, entries.any { it.isArchived })
                                childContents.rememberCover(folderId, entries.coverGcid().orEmpty())
                            }
                            if (populateDerivedFromTreeLocked()) normalizeLocations()
                            if (changed.isNotEmpty()) {
                                markChanged(changed)
                                listingRequests.update { it - changed }
                                listings.update { cache ->
                                    cache.mapValues { (id, listing) -> if (id in changed) listing.expired() else listing }
                                }
                                _folderChanges.tryEmit(DriveChange.ContentsChanged(changed))
                            }
                        }
                        if (committedSuccessfully) {
                            PikoLog.i(
                                TAG,
                                "本地文件树索引就绪：$folderCount 个文件夹，$fileCount 个文件，" +
                                    "归档目录 $totalManifestFolders 个，历时 ${started.elapsedNow().inWholeMilliseconds} ms",
                            )
                        }
                    } catch (failure: Exception) {
                        if (isCurrent()) throw failure
                    } finally {
                        if (!committed) index.abortCandidate(candidate)
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
                val permit = dev.piko.shared.memory.PikoMemoryGovernor.Shared.acquire(dev.piko.shared.memory.MemoryWorkKind.TreeBuild, 0)
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
                    if (isCurrent()) _treeBuildProgressFlow.value = TreeBuildProgress(
                        isBuilding = true,
                        isInitialBlocking = !isShadowRebuild,
                        foldersRead = 0,
                        filesRead = 0,
                    )
                }
                try {
                    val folders = asked.listAllDriveFolders(pageSize = 500)
                    if (!isCurrent()) throw CancellationException("Account changed")
                    withPikoLock(treeStateLock) {
                        if (isCurrent()) _treeBuildProgressFlow.update { it.copy(foldersRead = folders.size) }
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
                    asked.listAllDriveEntriesFlow(pageSize = 500, extraFilters = mapOf(FileFilter.kind(FileKind.FILE))).collect { page ->
                        if (!isCurrent()) throw CancellationException("Account changed")
                        fileCount += page.size
                        val enriched = withPikoLock(treeStateLock) {
                            if (!isCurrent()) throw CancellationException("Account changed")
                            _treeBuildProgressFlow.update { it.copy(filesRead = fileCount) }
                            sourceLedger.enrich(page)
                        }
                        allFiles.addAll(enriched)
                        if (!isShadowRebuild) {
                            withPikoLock(treeStateLock) {
                                if (isCurrent()) {
                                    index.putFiles(enriched)
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
                    val uncachedManifestFolders = ArrayList<Pair<String, List<FileStat>>>()
                    for ((folderId, manifests) in manifestByFolder) {
                        if (!isCurrent()) throw CancellationException("Account changed")
                        val winnerId = VaultStore.winnerOf(manifests)?.id
                        val cachedManifest = store.cachedCommitted(manifests)
                        if (cachedManifest != null) {
                            vaultByFolder[folderId] = cachedManifest
                            if (!isShadowRebuild) {
                                withPikoLock(treeStateLock) {
                                    if (isCurrent()) vaultEntriesKnown(folderId, cachedManifest, expectedManifestId = winnerId, requireMatch = !store.hasPending(folderId))
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
                                    manifestPermits.withPermit {
                                        if (!isCurrent()) throw CancellationException("Account changed")
                                        val entries = store.read(folderId, manifests).getOrDefault(emptyList())
                                        withPikoLock(treeStateLock) {
                                            if (isCurrent() && !store.hasPending(folderId)) {
                                                vaultEntriesKnown(folderId, entries, expectedManifestId = VaultStore.winnerOf(manifests)?.id, requireMatch = true)
                                            }
                                        }
                                        folderId to entries
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
                                val usageBytes = _quotaFlow.value?.quota?.usageBytes ?: index.lastUsageBytes
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
                    withPikoLock(treeStateLock) {
                        if (isCurrent()) {
                            if (shouldRestartRebuild) requestTreeRebuild()
                            else if (committed) {
                                if (indexUpdated) normalizeLocations()
                                scheduleTreeSnapshotSave()
                                if (isShadowRebuild && changedFolders.isNotEmpty()) {
                                    _folderChanges.tryEmit(DriveChange.ContentsChanged(changedFolders))
                                }
                            }
                        }
                    }
                    if (committed) {
                        PikoLog.d(
                            TAG,
                            "本地文件树索引就绪：${folders.size} 个文件夹，$fileCount 个文件，" +
                                "归档目录 ${manifestByFolder.size} 个，历时 ${started.elapsedNow().inWholeMilliseconds} ms",
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
        val checkedMissIds = HashSet<String>()

        fun extraFolderTitle(folder: FileStat): String? {
            val children = childContents.get(folder.id) ?: return null
            val key = FolderTitleKey(folder.id, folder.name, dev.piko.shared.i18n.PikoLocale.effectiveLanguage.name, children.hashCode())
            withPikoLock(folderTitleLock) {
                if (folderTitleCache.containsKey(key)) {
                    val cached = folderTitleCache.remove(key)
                    folderTitleCache[key] = cached
                    return cached
                }
            }
            val computed = describeFolder(folder.name, children.map { MediaFileInput(it.name, 0, it.category?.toFileKind()) }).title
            withPikoLock(folderTitleLock) {
                folderTitleCache[key] = computed
                var weight = folderTitleCache.entries.sumOf { (k, v) -> 96L + 2L * (k.id.length + k.name.length + (v?.length ?: 0)) }
                while (folderTitleCache.size > 4096 || weight > 1024L * 1024) {
                    val oldest = folderTitleCache.entries.first()
                    weight -= 96L + 2L * (oldest.key.id.length + oldest.key.name.length + (oldest.value?.length ?: 0))
                    folderTitleCache.remove(oldest.key)
                }
            }
            return computed
        }

        fun enrichFile(file: FileStat): FileStat =
            if (file.isFolder) withVaultCovers(listOf(file)).first() else sourceLedger.enrich(file)

        suspend fun emitNewMatches(incrementalSkip: MutableSet<String>? = null, excludeDirty: Boolean = false) {
            val dirtyNow = if (excludeDirty) driveTreeIndex.dirtyFolders else emptySet()
            val hits = driveTreeIndex.searchLocal(
                query = cleanQuery,
                parentId = if (hasPendingOptimisticWrites) "" else parentId,
                folderIndex = folderIndex,
                extraFolderTitle = ::extraFolderTitle,
                transformFile = ::enrichFile,
                skipIds = if (dirtyNow.isEmpty()) incrementalSkip else null,
            )
            val projectedHits = if (!hasPendingOptimisticWrites) hits else {
                val folders = projectOptimisticFiles(driveTreeIndex.flow.value.entries.values.toList()).associateBy { it.id }
                projectOptimisticFiles((hits.map { it.file } + optimisticCandidates()).distinctBy { it.id }).mapNotNull { file ->
                    val score = dev.piko.shared.search.cjkFuzzyScore(cleanQuery, file.name)
                        ?: extraFolderTitle(file).takeIf { file.isFolder }?.let { dev.piko.shared.search.cjkFuzzyScore(cleanQuery, it) }
                    if (score == null || isPikoInternalFolder(file, file.parentId) || VaultStore.looksLikeManifest(file) || VaultStore.isRecoveryFile(file)) null
                    else DriveTreeIndex.resolveBreadcrumb(file, parentId, folders, folderIndex.flow.value.nodes)?.let {
                        SearchHit(enrichFile(file), it)
                    }
                }
            }
            for (hit in projectedHits) {
                if (dirtyNow.isNotEmpty() && (hit.file.parentId in dirtyNow || hit.file.id in dirtyNow)) continue
                if (emittedIds.add(hit.file.id)) emit(hit)
            }
        }

        emitNewMatches(checkedMissIds, excludeDirty = true)

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
            delay(250)
            val currentRev = driveTreeIndex.flow.value.revision
            if (currentRev != lastSeenRev) {
                lastSeenRev = currentRev
                emitNewMatches(checkedMissIds)
            }
        }
        sync.await()
        emitNewMatches()
    }.flowOn(Dispatchers.Default)

    suspend fun trashFiles(fresh: Boolean = true): Result<List<FileStat>> = trashFilesForClient(client, fresh)

    internal suspend fun trashFilesForClient(asked: PikPakClient, fresh: Boolean = true): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            if (!fresh) {
                cachedTrashListing.value
                    ?.takeIf { it.client === asked && it.listedAt.elapsedNow() < TRASH_CACHE_FRESH }
                    ?.let { return@runSuspendCatching if (clientManager.currentClient.value === asked) projectOptimisticFiles((it.files + optimisticCandidates()).distinctBy { file -> file.id }, trash = true) else it.files }
            }
            var winner: Deferred<List<FileStat>>? = null
            val created = backgroundScope.async(start = CoroutineStart.LAZY) {
                val listed = asked.listTrash()
                if (clientManager.currentClient.value === asked) {
                    cachedTrashListing.value = CachedTrashListing(asked, listed, TimeSource.Monotonic.markNow())
                }
                listed
            }
            trashListingRequest.update { existing ->
                if (!fresh && existing != null && existing.first === asked && existing.second.isActive) {
                    winner = existing.second
                    existing
                } else {
                    winner = created
                    asked to created
                }
            }
            val active = winner ?: created
            if (active !== created) {
                created.cancel()
            } else {
                created.invokeOnCompletion {
                    trashListingRequest.update { cur -> if (cur?.second === created) null else cur }
                }
                created.start()
            }
            active.await().let { files ->
                if (clientManager.currentClient.value === asked) projectOptimisticFiles((files + optimisticCandidates()).distinctBy { it.id }, trash = true) else files
            }
        }
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

    /** 分享里某个文件夹的全部内容（自动翻页，超过 [maxPages] 页时返回截断标记）。子目录只能经它打开，GET /share 不认 parent_id。 */
    suspend fun shareFolderFull(
        shareId: String,
        passCodeToken: String,
        parentId: String,
        maxPages: Int = 20,
    ): Result<Pair<List<FileStat>, Boolean>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = client
            val all = ArrayList<FileStat>()
            var token = ""
            var pages = 0
            while (pages < maxPages) {
                val page = asked.listShareFiles(shareId, passCodeToken, parentId = parentId, pageToken = token)
                all += page.files
                token = page.nextPageToken
                pages++
                if (token.isEmpty()) break
            }
            all to token.isNotEmpty()
        }
    }

    suspend fun shareFolder(shareId: String, passCodeToken: String, parentId: String): Result<List<FileStat>> =
        shareFolderFull(shareId, passCodeToken, parentId).map { it.first }

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
                if (task.phase == TaskPhase.ERROR) error(task.message.ifBlank { tr("转存失败", "Failed to save share", "共有の保存に失敗しました", "공유 저장 실패", "轉存失敗") })
                delay(RESTORE_POLL_INTERVAL_MILLIS)
            }
            error(tr("转存超时", "Share save timed out", "共有の保存がタイムアウトしました", "공유 저장 시간이 초과되었습니다", "轉存逾時"))
        }.onSuccess { if (clientManager.currentClient.value === boundClient) applyChange(DriveChange.ContentsChanged(setOf(toParentId))) }
    }

    /**
     * 把 [fileIds] 分享为一条链接，文件与文件夹可以混在一起、不必同目录。
     * [passCode] 为 null 是公开链接，空串由服务端生成四位提取码，否则用它。[expirationDays] 为 -1 永久有效。
     */
    suspend fun createShare(fileIds: List<String>, passCode: String?, expirationDays: Int): Result<CreatedShare> =
        withContext(Dispatchers.Default) {
            runSuspendCatching {
                requireCloudIds(fileIds)
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

    /** 全盘的星标文件与文件夹（含服务端真实星标与 Piko 自管的归档条目星标）。 */
    suspend fun starredFiles(): Result<List<FileStat>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val remote = sourceLedger.enrich(client.listStarred())
            val vaulted = sourceLedger.enrich(vaultStars.starredFileStats())
            val combined = projectOptimisticFiles((remote + vaulted + optimisticCandidates()).distinctBy { it.id })
                .filter { file -> file.isStarred || vaulted.any { it.id == file.id } }
            sortFiles(combined, PikoFileSortOrder.TIME_DESC, "")
        }
    }

    private fun resolveVaultedStat(virtualId: String, knownStats: Map<String, FileStat> = emptyMap()): FileStat? {
        knownStats[virtualId]?.let { return it }
        driveTreeIndex.file(virtualId)?.let { return it }
        val entryId = VaultEntry.entryIdOf(virtualId)
        if (entryId != null) {
            vaultStars.flow.value[entryId]?.toFileStat()?.let { return it }
            val folderId = vault.folderOfEntry(entryId).orEmpty()
            val resolved = VaultEntry.resolvedFileOf(virtualId)
            if (resolved != null && !resolved.gcid.isNullOrBlank()) {
                return FileStat(
                    kind = FileKind.FILE,
                    id = virtualId,
                    parentId = folderId,
                    name = resolved.path,
                    size = resolved.size.toString(),
                    hash = resolved.gcid!!,
                )
            }
        }
        return null
    }

    /** 立即更新 Piko 自管的归档条目星标并原地刷新树与列表缓存。 */
    fun setVaultStarred(stats: Collection<FileStat>, starred: Boolean) {
        val affected = vaultStars.setStarredFromStats(stats, starred)
        if (affected.isNotEmpty()) {
            val resolvedParents = stats.map { stat ->
                stat.parentId.takeIf { it.isNotEmpty() || stat.id in listings.value[""]?.files.orEmpty().map(FileStat::id) }
                    ?: VaultEntry.entryIdOf(stat.id)?.let { vaultStars.folderOf(it) ?: vault.folderOfEntry(it) }
                    ?: stat.parentId
            }
            val folders: Set<String>? = if (resolvedParents.isEmpty()) null else resolvedParents.toHashSet()
            if (folders != null) {
                for (folderId in folders) {
                    val existing = driveTreeIndex.vaultEntries(folderId)
                    if (existing != null) vaultEntriesKnown(folderId, existing)
                }
            }
            applyChange(DriveChange.Starred(affected, starred, folders))
        }
    }

    suspend fun setStarred(
        ids: List<String>,
        starred: Boolean,
        knownStats: Map<String, FileStat> = emptyMap(),
    ): Result<Unit> = withContext(Dispatchers.Default) {
        val (vaultedIds, realIds) = ids.distinct().filterNot(LocalLocation::isLocal).partition(VaultEntry::isVaulted)
        if (vaultedIds.isNotEmpty()) {
            val stats = vaultedIds.mapNotNull { resolveVaultedStat(it, knownStats) }
            setVaultStarred(stats, starred)
        }
        if (realIds.isEmpty()) return@withContext Result.success(Unit)
        val asked = client
        val parents = knownParentsOf(realIds)
        runSuspendCatching { if (starred) asked.starFiles(realIds) else asked.unstarFiles(realIds) }
            .onSuccess { if (clientManager.currentClient.value === asked) applyChange(DriveChange.Starred(realIds, starred, parents)) }
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
        val access = currentAccount?.let(::writeAccess) ?: return Result.failure(IllegalStateException(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入")))
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
     * 默认文件夹在前，各自按 [order] 排，根目录的 My Pack 固定排第一。
     * 关闭 [foldersFirst] 后所有条目按同一比较器混排，My Pack 不再单独置顶。
     */
    fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder, parentId: String, foldersFirst: Boolean = true): List<FileStat> =
        sortFiles(files, order, parentId, driveTreeIndex.sortContext(files, order), foldersFirst)

    /** 仅读取内存已物化子树统计的无锁快速排序，适用于主线程即时响应；实例级原始时间与媒体时长由后台排序流异步补齐。 */
    internal fun sortFilesFast(files: List<FileStat>, order: PikoFileSortOrder, parentId: String, foldersFirst: Boolean = true): List<FileStat> =
        sortFiles(files, order, parentId, driveTreeIndex.fastSortContext(), foldersFirst)

    internal fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder, parentId: String, context: SortContext, foldersFirst: Boolean = true): List<FileStat> {
        val (folders, regularFiles) = files.partition { it.isFolder }
        fun durationOf(file: FileStat): Long? = if (file.isFolder) {
            context.subtreeStats[file.id]?.let { (it.physical + it.vaulted).durationMillis.takeIf { value -> value > 0 } }
        } else ContentKey.of(file)
            ?.let { context.contentMetadata[it]?.durationMillis }?.takeIf { it > 0 }
            ?: file.params["duration"]?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000).toLong() }
        val times = files.associate { it.id to (it.params["piko_original_created_at"]?.toLongOrNull()
            ?: context.createdAtMillis[it.id] ?: treeEpochMillis(it.createdTime)) }
        val sizes = files.associate { file -> file.id to if (file.isFolder) {
            context.subtreeStats[file.id]?.let { (it.physical + it.vaulted).bytes } ?: file.sizeBytes
        } else file.sizeBytes }
        fun compareKnown(a: Long?, b: Long?, descending: Boolean): Int = when {
            a == null -> if (b == null) 0 else 1
            b == null -> -1
            descending -> b.compareTo(a)
            else -> a.compareTo(b)
        }
        val nameComparator = compareBy(NaturalOrder, FileStat::name).thenBy(FileStat::id)
        val comparator = when (order) {
            // 自然顺序：第 2 集排在第 10 集前面
            PikoFileSortOrder.NAME_ASC -> nameComparator
            PikoFileSortOrder.NAME_DESC -> compareByDescending(NaturalOrder, FileStat::name).thenBy(FileStat::id)
            // 按创建时间而非 modified_time：目录的 modified_time 不随其中内容变动而更新，
            // 与创建时间相同（2026-09-23 实测），按它排序只会让「修改时间」名不副实
            PikoFileSortOrder.TIME_DESC, PikoFileSortOrder.TIME_ASC -> Comparator<FileStat> { a, b ->
                compareKnown(times[a.id], times[b.id], order == PikoFileSortOrder.TIME_DESC)
            }.then(nameComparator)
            PikoFileSortOrder.SIZE_DESC, PikoFileSortOrder.SIZE_ASC -> Comparator<FileStat> { a, b ->
                compareKnown(sizes[a.id], sizes[b.id], order == PikoFileSortOrder.SIZE_DESC)
            }.then(nameComparator)
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
                        if (descending) sizes.getValue(b.id).compareTo(sizes.getValue(a.id))
                        else sizes.getValue(a.id).compareTo(sizes.getValue(b.id))
                    } else 0
                    duration.takeIf { it != 0 } ?: size.takeIf { it != 0 } ?: nameComparator.compare(a, b)
                }
            }
        }
        if (!foldersFirst) return files.sortedWith(comparator)
        val folderComparator = if (order == PikoFileSortOrder.DURATION_DESC || order == PikoFileSortOrder.DURATION_ASC) {
            val durations = folders.associate { folder -> folder.id to context.subtreeStats[folder.id]?.let {
                val total = it.physical + it.vaulted
                total.durationMillis.takeIf { value -> value > 0 }
            } }
            Comparator<FileStat> { a, b ->
                val durationOrder = compareKnown(durations[a.id], durations[b.id], order == PikoFileSortOrder.DURATION_DESC)
                if (durationOrder != 0) durationOrder
                else if (durations[a.id] != null && durations[b.id] != null) {
                    compareKnown(sizes[a.id], sizes[b.id], order == PikoFileSortOrder.DURATION_DESC)
                } else 0
            }.then(nameComparator)
        } else comparator
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
        private val LINK_RETRY_DELAYS = listOf(500.milliseconds, 1.seconds, 2.seconds, 3.seconds, 5.seconds)

        /** 每轮都重取详情；注入等待函数供测试验证退避而不实际睡眠。 */
        internal suspend fun readSmallFileBytes(
            asked: PikPakClient,
            fileId: String,
            wait: suspend (Duration) -> Unit = { delay(it) },
        ): ByteArray {
            var detail: FileDetail? = null
            var lastError: Throwable? = null
            fun diagnostic(attempts: Int): String =
                "id=$fileId, phase=${detail?.phase}, size=${detail?.size}, links=${detail?.links?.keys}, " +
                    "hasWebLink=${detail?.webContentLink?.isNotBlank() == true}, attempts=$attempts"

            for (attempt in 0..LINK_RETRY_DELAYS.size) {
                lastError = null
                try {
                    val current = asked.getFile(fileId)
                    detail = current
                    val url = current.downloadUrl
                        ?: current.links.values.firstOrNull { it.url.isNotBlank() }?.url
                        ?: current.webContentLink.takeIf { it.isNotBlank() }
                    if (url != null) {
                        val size = current.size.toLongOrNull() ?: error(tr("文件大小未知", "Unknown file size", "ファイルサイズが不明です", "파일 크기를 알 수 없습니다", "檔案大小未知"))
                        if (size == 0L) return ByteArray(0)
                        return asked.streamRangeFromUrl(url, start = 0L, length = size, {}) { stream -> stream.channel.toByteArray() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (!isRetryableSmallFileFailure(e)) {
                        PikoLog.w(TAG, "读取小文件失败：${diagnostic(attempt + 1)}, error=${e::class.simpleName}")
                        throw e
                    }
                }
                if (attempt == LINK_RETRY_DELAYS.size) {
                    PikoLog.w(TAG, "读取小文件失败：${diagnostic(attempt + 1)}, error=${lastError?.let { it::class.simpleName }}")
                    throw lastError ?: IllegalStateException(tr("未获取到下载链接", "Download link unavailable", "ダウンロードリンクを取得できません", "다운로드 링크를 가져올 수 없습니다", "無法取得下載連結"))
                }
                PikoLog.d(TAG, "重试读取小文件：${diagnostic(attempt + 1)}, error=${lastError?.let { it::class.simpleName }}")
                wait(LINK_RETRY_DELAYS[attempt])
            }
            error("Unreachable")
        }

        private fun isRetryableSmallFileFailure(error: Exception): Boolean = when (error) {
            is PikPakException -> when {
                error.errorMessage == "file_in_recycle_bin" || error.errorMessage == "file_not_found" || error.httpStatus == 404 -> false
                error is FileReactivatingException || error is UrlExpiredException -> true
                else -> error.httpStatus == 403 || error.httpStatus == 429 || error.httpStatus in 500..599
            }
            is IOException -> true
            else -> false
        }
        private const val UPLOAD_BYTES_ATTEMPTS = 3
        private const val MAX_LOCATE_DEPTH = 64
        private const val CHILD_NAME_PAGE = 20
        private const val CHILD_NAME_CONCURRENCY = 2
        private const val MANIFEST_WARMUP_CONCURRENCY = 24
        private const val MAX_REMEMBERED_CHILD_NAMES = 200
        private val QUOTA_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        private fun transferQuotaCacheKey(account: String) =
            "transfer-quota-" + account.replace(Regex("[^A-Za-z0-9._@-]"), "_") + ".json"

        // 外层文件夹的封面只要一张，其下文件夹多半都有视频，读几个就够；读不到的不必为一张封面列遍整棵树
        private const val COVER_PROBE_MEMBERS = 3

        // 目录图一次展开十几层、每层几十个文件夹，再加网盘页走过的，一百多个够覆盖一次浏览
        private const val RECENT_LISTINGS = 128
        private const val MAX_LISTING_BYTES = 8L * 1024 * 1024

        // 目录图、›、补全直接用最近列过的而不重取的期限。网盘页不看它：网盘页总是先显示缓存、随即刷新
        private val RECENT_LISTING_FRESH = 60.seconds
        private val TRASH_CACHE_FRESH = 300.seconds

        // 登录后让首屏列根目录与会话恢复先行，再在后台拉齐整棵本地文件树
        private val TREE_WARMUP_DELAY = 1500.milliseconds
        private val CANARY_PROBE_COOLDOWN = 60.seconds
        private const val CANARY_WINDOW_SIZE = 500
        private const val MAX_CANARY_PAGES = 4
        private const val CANARY_TAIL_SETTLED_COUNT = 32
        private const val MAX_INCREMENTAL_RECONCILE_FOLDERS = 16
        private const val TREE_SNAPSHOT_SAVE_DELAY = 2_000L

        private inline fun firstPageOrTailUnchangedCount(
            items: List<FileStat>,
            matchesLocal: (FileStat) -> Boolean,
        ): Int {
            var count = 0
            for (i in items.lastIndex downTo 0) {
                if (matchesLocal(items[i])) count++ else break
            }
            return count
        }

        private const val MY_PACKS_FOLDER_NAME = "My Packs"

        // 转存任务实测一秒内完成；给大目录留到一分钟
        private const val RESTORE_POLL_LIMIT = 60
        private const val RESTORE_POLL_INTERVAL_MILLIS = 1_000L

        // PikPak 各端自动建的保存目录名不一，官方客户端建过的也算
        private val MY_PACKS_FOLDER_NAMES = setOf("my pack", "my packs", "我的资源", "我的离线")
    }
}
