@file:JvmMultifileClass
@file:JvmName("PikoDriveRepositoryKt")

package dev.piko.shared.data

import androidx.compose.runtime.Stable
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.i18n.tr
import dev.piko.shared.space.SpaceFolderSnapshot
import dev.piko.shared.space.SpacePreviewBlock
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.CreatedShare
import io.github.nihildigit.pikpak.EventPage
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.QuotaResponse
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.SearchHit
import io.github.nihildigit.pikpak.ShareFolderPage
import io.github.nihildigit.pikpak.ShareInfo
import io.github.nihildigit.pikpak.ShareListPage
import io.github.nihildigit.pikpak.TransferQuota
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex

enum class PikoFileSortOrder { NAME_ASC, NAME_DESC, TIME_DESC, TIME_ASC, SIZE_DESC, SIZE_ASC, DURATION_DESC, DURATION_ASC }

data class PikoPathBreadcrumb(val id: String, val name: String)

/** 列表的滚动位置：首个可见项的下标与它已滚出顶端的像素数。 */
data class ScrollAnchor(val index: Int, val offset: Int)

/** 供旧版播放缓存按精确字节数与 GCID 反查真实文件名的轻量索引行。 */
data class CachedFileLookupRow(
    val id: String,
    val name: String,
    val gcid: String,
    val sizeBytes: Long,
)

/** 目录的递归统计。[progress] 区分仍在统计、统计完、因到达上限而中止三种。 */
data class FolderUsage(val fileCount: Int, val bytes: Long, val progress: Progress) {
    enum class Progress { COUNTING, COMPLETE, TRUNCATED }
}

/**
 * 网盘领域服务窄接口（ABI 防火墙）：隔离目录分页拉取、SQLite/内存目录树同步、乐观写对账、
 * 资料库搜索与归档虚拟覆盖层实现细节。
 */
@Stable
interface DriveService {
    val localMounts: LocalMounts
    val quotaFlow: StateFlow<QuotaResponse?>
    val quotaUpdates: SharedFlow<Pair<String, QuotaResponse>>
    val transferQuotaFlow: StateFlow<TransferQuota?>
    val isFreeAccountFlow: StateFlow<Boolean?>
    val treeBuildProgressFlow: StateFlow<PikoDriveRepository.TreeBuildProgress>
    val currentAccount: String?
    val accountChanges: Flow<String?>
    val clientRevision: StateFlow<Long>
    val metadataSink: DriveMetadataSink
    val pinnedFoldersFlow: Flow<List<PikoPathBreadcrumb>>
    val changes: DriveChangeJournal
    val folderChanges: SharedFlow<DriveChange>
    val optimisticRevision: StateFlow<Long>
    val hasPendingOptimisticWrites: Boolean
    val vault: VaultStore
    val vaultCoverChanges: Flow<Unit>
    val vaultTrees: VaultTrees
    val vaultStars: VaultStars
    val sourceLedger: SourceLedger
    val folderEmptiness: StateFlow<Map<String, Boolean>>
    val fullyVaultedFolders: StateFlow<Set<String>>
    val vaultedFolders: StateFlow<Set<String>>
    val childContentsFlow: StateFlow<Map<String, List<ChildFile>>>

    fun refreshSpaceFolder(folderId: String)
    suspend fun loadSpaceFolderPreview(folderId: String, includeVaulted: Boolean, limit: Int = 128): List<SpacePreviewBlock>
    fun observeSpaceFolder(folderId: String, includeVaulted: Boolean): Flow<SpaceFolderSnapshot>

    fun contentMetadataOf(gcid: String, sizeBytes: Long): ContentMetadata?
    fun contentMetadataOf(file: FileStat): ContentMetadata?
    fun contentMetadataBatch(keys: Collection<ContentKey>): Map<ContentKey, ContentMetadata>
    fun originalCreatedAtMillisBatch(ids: Collection<String>): Map<String, Long>
    fun contentMetadataByFileId(fileId: String): ContentMetadata?
    fun originalCreatedAtMillis(fileId: String): Long?
    fun folderSubtreeStats(folderId: String): SubtreeStats?
    fun isSubtreeClean(rootFolderId: String): Boolean
    fun allDescendantFolders(rootFolderId: String): List<FileStat>

    fun writeAccess(expectedAccount: String): DriveWriteAccess?
    fun historyAccess(expectedAccount: String): HistoryWriteAccess?
    fun enterAccount(account: String?)
    fun pinFolder(folder: PikoPathBreadcrumb)
    fun unpinFolder(folderId: String)
    fun refreshActiveListings()
    fun optimisticCandidates(): List<FileStat>
    fun projectOptimisticFiles(files: List<FileStat>, folderId: String? = null, trash: Boolean = false): List<FileStat>
    fun applyChange(change: DriveChange)

    suspend fun locateFolder(fileId: String): Result<List<PikoPathBreadcrumb>>
    fun knownLocalPath(folderId: String): List<PikoPathBreadcrumb>?
    fun seedFolders(names: Map<String, String>, parents: Map<String, String>)
    fun knownParentOf(fileId: String): String?
    fun knownFile(fileId: String): FileStat?
    fun findFilesByByteSizes(sizes: Collection<Long>): List<CachedFileLookupRow> = emptyList()
    suspend fun resolveFolderPath(names: List<String>, from: List<PikoPathBreadcrumb> = listOf(PikoDriveRepository.ROOT_BREADCRUMB)): Result<List<PikoPathBreadcrumb>>
    suspend fun subfolders(folderId: String, fresh: Boolean = false): Result<List<PikoPathBreadcrumb>>
    suspend fun folderMapLevel(folderId: String): Result<List<FileStat>>
    fun cachedFiles(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>?

    fun vaultAccess(expectedAccount: String): VaultStore?
    suspend fun listBrowsable(parentId: String, sortOrder: PikoFileSortOrder): Result<List<FileStat>>
    fun cachedBrowsable(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>?
    fun withVaultCovers(files: List<FileStat>): List<FileStat>
    suspend fun fetchVaultCover(folderId: String)
    fun knownChildContents(folderId: String): List<ChildFile>?
    suspend fun probeFolderEmptiness(folderId: String)
    suspend fun fetchChildContents(folderId: String): List<ChildFile>?

    suspend fun listFiles(parentId: String = "", pageToken: String = "", sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC): Result<Pair<List<FileStat>, String>>
    suspend fun listAllFiles(parentId: String = "", sortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC): Result<List<FileStat>>
    fun folderUsage(folderId: String, maxFolders: Int = 2_000, timeout: Duration = 30.seconds): Flow<FolderUsage>
    suspend fun getFileDetail(fileId: String): Result<FileDetail>
    suspend fun isFolderGone(folderId: String): Boolean

    suspend fun getQuota(maxAge: Duration = Duration.ZERO): Result<QuotaResponse>
    suspend fun isFreeAccount(): Boolean?
    suspend fun getTransferQuota(maxAge: Duration = Duration.ZERO): Result<TransferQuota>
    suspend fun originalImageUrl(fileId: String): String?
    suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray): Result<String>
    suspend fun readBytes(fileId: String): Result<ByteArray>
    suspend fun folderNamed(parentId: String, name: String): Result<String>
    suspend fun sampleCid(fileId: String): Result<String>
    suspend fun instantCreate(file: ResolvedFile, parentId: String, source: String? = null, notifyChange: Boolean = true): Result<String>
    suspend fun createFolder(parentId: String, name: String): Result<String>
    suspend fun rename(fileId: String, name: String, parentId: String? = null, notifyChange: Boolean = true): Result<Unit>
    suspend fun trash(ids: List<String>): Result<Unit>
    suspend fun restore(ids: List<String>): Result<Unit>
    suspend fun delete(ids: List<String>): Result<Unit>
    suspend fun move(ids: List<String>, parentId: String): Result<Unit>
    suspend fun copy(ids: List<String>, parentId: String): Result<Unit>

    suspend fun search(query: String): Result<List<FileStat>>
    suspend fun warmDriveTree(): Result<Unit>
    fun requestTreeRebuild()
    fun onRootPullRefresh()
    fun searchRecursive(query: String, parentId: String = ""): Flow<SearchHit>

    suspend fun trashFiles(fresh: Boolean = true): Result<List<FileStat>>
    fun reconcileTrashListing(files: List<FileStat>): Boolean
    suspend fun shareInfo(shareId: String, passCode: String): Result<ShareInfo>
    suspend fun shareFolderFull(shareId: String, passCodeToken: String, parentId: String, maxPages: Int = 20): Result<Pair<List<FileStat>, Boolean>>
    suspend fun shareFolderPage(shareId: String, passCodeToken: String, parentId: String = "", pageToken: String = ""): Result<ShareFolderPage>
    suspend fun shareFolder(shareId: String, passCodeToken: String, parentId: String): Result<List<FileStat>>
    suspend fun restoreFromShare(shareId: String, passCodeToken: String, fileIds: List<String>, toParentId: String, ancestorIds: List<String>): Result<Unit>
    suspend fun createShare(fileIds: List<String>, passCode: String?, expirationDays: Int): Result<CreatedShare>
    suspend fun myShares(pageToken: String = ""): Result<ShareListPage>
    suspend fun cancelShares(shareIds: List<String>): Result<Unit>

    suspend fun starredFiles(): Result<List<FileStat>>
    fun setVaultStarred(stats: Collection<FileStat>, starred: Boolean)
    suspend fun setStarred(ids: List<String>, starred: Boolean, knownStats: Map<String, FileStat> = emptyMap()): Result<Unit>
    suspend fun playHistory(pageToken: String = ""): Result<EventPage>
    suspend fun recentlyAdded(pageToken: String = ""): Result<EventPage>
    suspend fun deleteEvents(eventIds: List<String>): Result<Unit>
    suspend fun clearPlayHistory(): Result<Unit>

    fun folderMeaningless(folderId: String): Boolean?
    fun cacheFolderMeaningless(folderId: String, value: Boolean)
    suspend fun findMyPacksFolder(): Result<PikoPathBreadcrumb?>
    suspend fun getOrCreateMyPacksFolder(): Result<PikoPathBreadcrumb>
    suspend fun isFolderMeaningless(folderId: String, thresholdBytes: Long, forceRefresh: Boolean = false): Boolean
    fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder, parentId: String, foldersFirst: Boolean = true): List<FileStat>
}

@Stable
open class PikoDriveRepository(
    private val clientManager: PikoClientProvider,
    private val preferences: PikoUserPreferences? = null,
    private val cacheStore: PikoCacheStore? = null,
    private val treeDatabaseFactory: PikoTreeDatabaseFactory? = null,
) : DriveService {
    private val client: PikPakClient get() = clientManager.currentClient.value ?: error("Not logged in")
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _folderChanges = MutableSharedFlow<DriveChange>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    final override val folderChanges: SharedFlow<DriveChange> = _folderChanges.asSharedFlow()

    private val _clientRevision = MutableStateFlow(0L)
    final override val clientRevision: StateFlow<Long> = _clientRevision.asStateFlow()

    private val ctx = DriveRepoContext(
        clientManager = clientManager,
        preferences = preferences,
        cacheStore = cacheStore,
        treeDatabaseFactory = treeDatabaseFactory,
        backgroundScope = backgroundScope,
        folderChanges = _folderChanges,
        clientRevision = _clientRevision,
        vaultAccessor = { vault },
        clearChangesJournal = { changes.clear() },
    )

    private val overlay = DriveVaultAndMountOverlay(ctx).also { ctx.overlay = it }
    private val indexCoordinator = FolderIndexCoordinator(ctx).also { ctx.index = it }
    private val listingEngine = DriveListingEngine(ctx).also { ctx.listing = it }
    private val mutationExecutor = DriveMutationExecutor(ctx).also { ctx.mutations = it }
    private val librarySource = DriveLibraryAndSearchSource(ctx).also { ctx.library = it }

    final override val changes = DriveChangeJournal(this, backgroundScope)

    init {
        indexCoordinator.enterAccount(clientManager.currentClient.value?.account)
        indexCoordinator.startBackgroundWorkers()
        overlay.startBackgroundObservers()
        mutationExecutor.startAccountAndQuotaSync()
    }

    data class TreeBuildProgress(
        val isBuilding: Boolean = false,
        val isInitialBlocking: Boolean = false,
        val isCheckingUpdates: Boolean = false,
        val foldersRead: Int = 0,
        val filesRead: Int = 0,
        val manifestsRead: Int = 0,
        val manifestsTotal: Int = 0,
    )

    enum class CanaryProbeResult { UNCHANGED, INCREMENTAL_PATCH, FULL_REBUILD }

    internal class SyncFolderCache {
        private data class CachedListing(val folderId: String, val entries: List<FileStat>, val fetchedAt: TimeMark)

        val resolveLock = Mutex()
        private val stateLock = PikoLock()
        private val folderIds = HashMap<String, String>()
        private val listings = HashMap<String, CachedListing>()

        fun folderId(account: String): String? = withPikoLock(stateLock) { folderIds[account] }
        fun rememberFolderId(account: String, folderId: String) { withPikoLock(stateLock) { folderIds[account] = folderId } }
        fun freshListing(account: String, ttl: Duration): Pair<String, List<FileStat>>? = withPikoLock(stateLock) {
            val cached = listings[account] ?: return@withPikoLock null
            if (cached.fetchedAt.elapsedNow() > ttl) null else cached.folderId to cached.entries
        }
        fun rememberListing(account: String, folderId: String, entries: List<FileStat>) {
            withPikoLock(stateLock) {
                folderIds[account] = folderId
                listings[account] = CachedListing(folderId, entries, TimeSource.Monotonic.markNow())
            }
        }
        fun invalidateListing(account: String) { withPikoLock(stateLock) { listings.remove(account) } }
        fun invalidateAccount(account: String) { withPikoLock(stateLock) { folderIds.remove(account); listings.remove(account) } }
        fun clear() { withPikoLock(stateLock) { folderIds.clear(); listings.clear() } }
    }

    class FolderNotFoundException(val name: String) : Exception(
        tr("未找到文件夹「$name」", "Folder “$name” not found", "フォルダー「$name」が見つかりません", "“$name” 폴더를 찾을 수 없습니다", "找不到資料夾「$name」"),
    )

    final override val localMounts: LocalMounts get() = overlay.localMounts
    protected val folderMeaninglessCache: Map<String, Boolean> get() = listingEngine.folderMeaninglessCache
    final override val quotaFlow: StateFlow<QuotaResponse?> get() = mutationExecutor.quotaFlow
    final override val quotaUpdates: SharedFlow<Pair<String, QuotaResponse>> get() = mutationExecutor.quotaUpdates
    final override val transferQuotaFlow: StateFlow<TransferQuota?> get() = mutationExecutor.transferQuotaFlow
    final override val isFreeAccountFlow: StateFlow<Boolean?> get() = mutationExecutor.isFreeAccountFlow
    internal val folderIndex: FolderIndex get() = indexCoordinator.folderIndex
    internal val driveTreeIndex: DriveTreeIndex get() = indexCoordinator.driveTreeIndex
    final override val treeBuildProgressFlow: StateFlow<TreeBuildProgress> get() = indexCoordinator.treeBuildProgressFlow
    override val currentAccount: String? get() = clientManager.currentClient.value?.account
    override val accountChanges: Flow<String?> = clientManager.currentClient.map { it?.account }.distinctUntilChanged()
    internal val accountGeneration: Long get() = indexCoordinator.accountGeneration
    internal val syncFolderCache: SyncFolderCache get() = indexCoordinator.syncFolderCache
    override val metadataSink: DriveMetadataSink get() = indexCoordinator.metadataSink
    override val pinnedFoldersFlow: Flow<List<PikoPathBreadcrumb>> get() = overlay.pinnedFoldersFlow
    override val optimisticRevision: StateFlow<Long> get() = mutationExecutor.optimisticRevision
    override val hasPendingOptimisticWrites: Boolean get() = mutationExecutor.hasPendingOptimisticWrites
    override val vault: VaultStore get() = overlay.defaultVault()
    override val vaultCoverChanges: Flow<Unit> get() = overlay.vaultCoverChanges
    override val vaultTrees: VaultTrees get() = overlay.vaultTrees
    override val vaultStars: VaultStars get() = overlay.vaultStars
    override val sourceLedger: SourceLedger get() = overlay.sourceLedger
    override val folderEmptiness: StateFlow<Map<String, Boolean>> get() = listingEngine.folderEmptiness
    override val fullyVaultedFolders: StateFlow<Set<String>> get() = overlay.fullyVaultedFolders
    override val vaultedFolders: StateFlow<Set<String>> get() = overlay.vaultedFolders
    override val childContentsFlow: StateFlow<Map<String, List<ChildFile>>> get() = listingEngine.childContentsFlow
    internal val sortContextRevision: Long get() = indexCoordinator.sortContextRevision

    internal fun observeSortRevision(): Flow<Long> = indexCoordinator.observeSortRevision()
    internal fun observeTreeCacheReady(): Flow<Long> = indexCoordinator.observeTreeCacheReady()
    internal fun sortContextFor(files: List<FileStat>, order: PikoFileSortOrder? = null): SortContext = indexCoordinator.sortContextFor(files, order)

    override fun refreshSpaceFolder(folderId: String) = listingEngine.refreshSpaceFolder(folderId)
    override suspend fun loadSpaceFolderPreview(folderId: String, includeVaulted: Boolean, limit: Int): List<SpacePreviewBlock> =
        listingEngine.loadSpaceFolderPreview(folderId, includeVaulted, limit)
    override fun observeSpaceFolder(folderId: String, includeVaulted: Boolean): Flow<SpaceFolderSnapshot> =
        listingEngine.observeSpaceFolder(folderId, includeVaulted)

    override fun contentMetadataOf(gcid: String, sizeBytes: Long): ContentMetadata? = listingEngine.contentMetadataOf(gcid, sizeBytes)
    override fun contentMetadataOf(file: FileStat): ContentMetadata? = listingEngine.contentMetadataOf(file)
    override fun contentMetadataBatch(keys: Collection<ContentKey>): Map<ContentKey, ContentMetadata> = listingEngine.contentMetadataBatch(keys)
    override fun originalCreatedAtMillisBatch(ids: Collection<String>): Map<String, Long> = listingEngine.originalCreatedAtMillisBatch(ids)
    override fun contentMetadataByFileId(fileId: String): ContentMetadata? = listingEngine.contentMetadataByFileId(fileId)
    override fun originalCreatedAtMillis(fileId: String): Long? = listingEngine.originalCreatedAtMillis(fileId)
    override fun folderSubtreeStats(folderId: String): SubtreeStats? = listingEngine.folderSubtreeStats(folderId)
    override fun isSubtreeClean(rootFolderId: String): Boolean = listingEngine.isSubtreeClean(rootFolderId)
    override fun allDescendantFolders(rootFolderId: String): List<FileStat> = listingEngine.allDescendantFolders(rootFolderId)

    override fun writeAccess(expectedAccount: String): DriveWriteAccess? = clientManager.currentClient.value
        ?.takeIf { it.account == expectedAccount }?.let { asked -> DriveWriteAccess(asked.account, asked, this, vaultForClient(asked)) }
    internal fun isCurrentClient(asked: PikPakClient): Boolean = clientManager.currentClient.value === asked
    override fun historyAccess(expectedAccount: String): HistoryWriteAccess? = clientManager.currentClient.value
        ?.takeIf { it.account == expectedAccount }?.let(::HistoryWriteAccess)
    internal fun applyWriteChange(account: String, change: DriveChange, notifyChange: Boolean = true) =
        mutationExecutor.applyWriteChange(account, change, notifyChange)
    internal fun registerFolderReferences(source: FolderReferenceSource) = indexCoordinator.registerFolderReferences(source)
    internal fun unregisterFolderReferences(source: FolderReferenceSource) = indexCoordinator.unregisterFolderReferences(source)

    override fun enterAccount(account: String?) = indexCoordinator.enterAccount(account)
    override fun pinFolder(folder: PikoPathBreadcrumb) = overlay.pinFolder(folder)
    override fun unpinFolder(folderId: String) = overlay.unpinFolder(folderId)
    override fun refreshActiveListings() = indexCoordinator.refreshActiveListings()
    internal fun hasPendingOptimisticWritesFor(account: String): Boolean = mutationExecutor.hasPendingOptimisticWritesFor(account)
    override fun optimisticCandidates(): List<FileStat> = mutationExecutor.optimisticCandidates()
    override fun projectOptimisticFiles(files: List<FileStat>, folderId: String?, trash: Boolean): List<FileStat> =
        mutationExecutor.projectOptimisticFiles(files, folderId, trash)
    internal fun beginOptimisticWrite(account: String, change: DriveChange): OptimisticWrites.Write = mutationExecutor.beginOptimisticWrite(account, change)
    internal fun beginOptimisticWrites(account: String, changes: List<DriveChange>): List<OptimisticWrites.Write> = mutationExecutor.beginOptimisticWrites(account, changes)
    internal fun finishOptimisticWrite(write: OptimisticWrites.Write, succeeded: Boolean) = mutationExecutor.finishOptimisticWrite(write, succeeded)
    internal fun finishOptimisticWrites(writes: List<OptimisticWrites.Write>, succeeded: BooleanArray) = mutationExecutor.finishOptimisticWrites(writes, succeeded)
    override fun applyChange(change: DriveChange) = mutationExecutor.applyChange(change, notifyChange = true)

    override suspend fun locateFolder(fileId: String): Result<List<PikoPathBreadcrumb>> = indexCoordinator.locateFolder(fileId)
    override fun knownLocalPath(folderId: String): List<PikoPathBreadcrumb>? = indexCoordinator.knownLocalPath(folderId)
    override fun seedFolders(names: Map<String, String>, parents: Map<String, String>) = indexCoordinator.seedFolders(names, parents)
    override fun knownParentOf(fileId: String): String? = indexCoordinator.knownParentOf(fileId)
    internal fun knownParentMap(ids: Collection<String>): Map<String, String> = indexCoordinator.knownParentMap(ids)
    override fun knownFile(fileId: String): FileStat? = indexCoordinator.knownFile(fileId)
    override fun findFilesByByteSizes(sizes: Collection<Long>): List<CachedFileLookupRow> = indexCoordinator.findFilesByByteSizes(sizes)
    internal fun knownConfirmedFile(fileId: String): FileStat? = indexCoordinator.knownConfirmedFile(fileId)
    override suspend fun resolveFolderPath(names: List<String>, from: List<PikoPathBreadcrumb>): Result<List<PikoPathBreadcrumb>> =
        indexCoordinator.resolveFolderPath(names, from)
    override suspend fun subfolders(folderId: String, fresh: Boolean): Result<List<PikoPathBreadcrumb>> = listingEngine.subfolders(folderId, fresh)
    override suspend fun folderMapLevel(folderId: String): Result<List<FileStat>> = listingEngine.folderMapLevel(folderId)
    override fun cachedFiles(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? = listingEngine.cachedFiles(folderId, sortOrder)

    internal fun vaultForClient(asked: PikPakClient): VaultStore = overlay.vaultForClient(asked)
    override fun vaultAccess(expectedAccount: String): VaultStore? = overlay.vaultAccess(expectedAccount)
    override suspend fun listBrowsable(parentId: String, sortOrder: PikoFileSortOrder): Result<List<FileStat>> =
        overlay.listBrowsable(parentId, sortOrder)
    internal suspend fun listBrowsableStrict(parentId: String, sortOrder: PikoFileSortOrder): Result<List<FileStat>> =
        overlay.listBrowsable(parentId, sortOrder, strictVaultRead = true)
    internal fun browsableReader(expectedAccount: String): (suspend (String) -> Result<List<FileStat>>)? = overlay.browsableReader(expectedAccount)
    internal fun browsableReaderForClient(asked: PikPakClient, store: VaultStore, requireActiveClient: Boolean = false): suspend (String) -> Result<List<FileStat>> =
        overlay.browsableReaderForClient(asked, store, requireActiveClient)
    internal fun localRealChildrenIfClean(folderId: String): List<FileStat>? = listingEngine.localRealChildrenIfClean(folderId)
    internal suspend fun scanRealChildren(folderId: String, asked: PikPakClient = client): List<FileStat> = listingEngine.scanRealChildren(folderId, asked)
    override fun cachedBrowsable(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? = overlay.cachedBrowsable(folderId, sortOrder)
    override fun withVaultCovers(files: List<FileStat>): List<FileStat> = overlay.withVaultCovers(files)
    override suspend fun fetchVaultCover(folderId: String) = overlay.fetchVaultCover(folderId)
    override fun knownChildContents(folderId: String): List<ChildFile>? = listingEngine.knownChildContents(folderId)
    internal fun knownVaultParents(): Map<String, String> = overlay.knownVaultParents()
    internal fun knownVaultMetaOf(gcid: String, size: Long): VaultMediaMeta? = overlay.knownVaultMetaOf(gcid, size)
    internal fun knownVaultMetaLookupFor(items: Collection<InstantFileItem>): (String, Long) -> VaultMediaMeta? =
        overlay.knownVaultMetaLookupFor(items)
    internal fun rememberFullyVaulted(folderIds: Set<String>) = overlay.rememberFullyVaulted(folderIds)
    internal fun vaultEntriesKnown(folderId: String, entries: List<VaultEntry>, expectedManifestId: String? = null, requireMatch: Boolean = false, reconcileStars: Boolean = true) =
        overlay.vaultEntriesKnown(folderId, entries, expectedManifestId, requireMatch, reconcileStars)
    internal fun knownDescendantFolders(rootFolderId: String): Set<String> = indexCoordinator.knownDescendantFolders(rootFolderId)
    internal fun notifyFoldersRefreshed(folders: Set<String>) = mutationExecutor.notifyFoldersRefreshed(folders)
    internal fun resolveExistingSubfoldersInTree(rootParentId: String, relativeDirectories: List<String>): Map<String, String> =
        indexCoordinator.resolveExistingSubfoldersInTree(rootParentId, relativeDirectories)

    override suspend fun probeFolderEmptiness(folderId: String) = listingEngine.probeFolderEmptiness(folderId)
    override suspend fun fetchChildContents(folderId: String): List<ChildFile>? = listingEngine.fetchChildContents(folderId)
    override suspend fun listFiles(parentId: String, pageToken: String, sortOrder: PikoFileSortOrder): Result<Pair<List<FileStat>, String>> =
        listingEngine.listFiles(parentId, pageToken, sortOrder)
    override suspend fun listAllFiles(parentId: String, sortOrder: PikoFileSortOrder): Result<List<FileStat>> = listingEngine.listAllFiles(parentId, sortOrder)
    override fun folderUsage(folderId: String, maxFolders: Int, timeout: Duration): Flow<FolderUsage> = listingEngine.folderUsage(folderId, maxFolders, timeout)
    override suspend fun getFileDetail(fileId: String): Result<FileDetail> = indexCoordinator.getFileDetail(fileId)
    override suspend fun isFolderGone(folderId: String): Boolean = indexCoordinator.isFolderGone(folderId)

    override suspend fun getQuota(maxAge: Duration): Result<QuotaResponse> = mutationExecutor.getQuota(maxAge)
    override suspend fun isFreeAccount(): Boolean? = mutationExecutor.isFreeAccount()
    override suspend fun getTransferQuota(maxAge: Duration): Result<TransferQuota> = mutationExecutor.getTransferQuota(maxAge)
    override suspend fun originalImageUrl(fileId: String): String? = mutationExecutor.originalImageUrl(fileId)
    override suspend fun uploadBytes(parentId: String, name: String, bytes: ByteArray): Result<String> = mutationExecutor.uploadBytes(parentId, name, bytes)
    override suspend fun readBytes(fileId: String): Result<ByteArray> = mutationExecutor.readBytes(fileId)
    override suspend fun folderNamed(parentId: String, name: String): Result<String> = mutationExecutor.folderNamed(parentId, name)
    override suspend fun sampleCid(fileId: String): Result<String> = mutationExecutor.sampleCid(fileId)
    override suspend fun instantCreate(file: ResolvedFile, parentId: String, source: String?, notifyChange: Boolean): Result<String> =
        mutationExecutor.instantCreate(file, parentId, source, notifyChange)
    override suspend fun createFolder(parentId: String, name: String): Result<String> = mutationExecutor.createFolder(parentId, name)
    override suspend fun rename(fileId: String, name: String, parentId: String?, notifyChange: Boolean): Result<Unit> =
        mutationExecutor.rename(fileId, name, parentId, notifyChange)
    override suspend fun trash(ids: List<String>): Result<Unit> = mutationExecutor.trash(ids)
    override suspend fun restore(ids: List<String>): Result<Unit> = mutationExecutor.restore(ids)
    override suspend fun delete(ids: List<String>): Result<Unit> = mutationExecutor.delete(ids)
    override suspend fun move(ids: List<String>, parentId: String): Result<Unit> = mutationExecutor.move(ids, parentId)
    override suspend fun copy(ids: List<String>, parentId: String): Result<Unit> = mutationExecutor.copy(ids, parentId)

    override suspend fun search(query: String): Result<List<FileStat>> = librarySource.search(query)
    override suspend fun warmDriveTree(): Result<Unit> = indexCoordinator.warmDriveTree()
    override fun requestTreeRebuild() = indexCoordinator.requestTreeRebuild()
    override fun onRootPullRefresh() = indexCoordinator.onRootPullRefresh()
    suspend fun probeRemoteChanges(asked: PikPakClient = client, force: Boolean = false): Result<CanaryProbeResult> =
        indexCoordinator.probeRemoteChanges(asked, force)
    override fun searchRecursive(query: String, parentId: String): Flow<SearchHit> = librarySource.searchRecursive(query, parentId)

    override suspend fun trashFiles(fresh: Boolean): Result<List<FileStat>> = librarySource.trashFiles(fresh)
    internal suspend fun trashFilesForClient(asked: PikPakClient, fresh: Boolean = true): Result<List<FileStat>> = librarySource.trashFilesForClient(asked, fresh)
    override fun reconcileTrashListing(files: List<FileStat>): Boolean = librarySource.reconcileTrashListing(files)

    override suspend fun shareInfo(shareId: String, passCode: String): Result<ShareInfo> = librarySource.shareInfo(shareId, passCode)
    override suspend fun shareFolderFull(shareId: String, passCodeToken: String, parentId: String, maxPages: Int): Result<Pair<List<FileStat>, Boolean>> =
        librarySource.shareFolderFull(shareId, passCodeToken, parentId, maxPages)
    override suspend fun shareFolderPage(shareId: String, passCodeToken: String, parentId: String, pageToken: String): Result<ShareFolderPage> =
        librarySource.shareFolderPage(shareId, passCodeToken, parentId, pageToken)
    override suspend fun shareFolder(shareId: String, passCodeToken: String, parentId: String): Result<List<FileStat>> =
        librarySource.shareFolder(shareId, passCodeToken, parentId)
    override suspend fun restoreFromShare(shareId: String, passCodeToken: String, fileIds: List<String>, toParentId: String, ancestorIds: List<String>): Result<Unit> =
        librarySource.restoreFromShare(shareId, passCodeToken, fileIds, toParentId, ancestorIds)
    override suspend fun createShare(fileIds: List<String>, passCode: String?, expirationDays: Int): Result<CreatedShare> =
        librarySource.createShare(fileIds, passCode, expirationDays)
    override suspend fun myShares(pageToken: String): Result<ShareListPage> = librarySource.myShares(pageToken)
    override suspend fun cancelShares(shareIds: List<String>): Result<Unit> = librarySource.cancelShares(shareIds)

    override suspend fun starredFiles(): Result<List<FileStat>> = librarySource.starredFiles()
    override fun setVaultStarred(stats: Collection<FileStat>, starred: Boolean) = mutationExecutor.setVaultStarred(stats, starred)
    override suspend fun setStarred(ids: List<String>, starred: Boolean, knownStats: Map<String, FileStat>): Result<Unit> =
        mutationExecutor.setStarred(ids, starred, knownStats)
    override suspend fun playHistory(pageToken: String): Result<EventPage> = librarySource.playHistory(pageToken)
    override suspend fun recentlyAdded(pageToken: String): Result<EventPage> = librarySource.recentlyAdded(pageToken)
    override suspend fun deleteEvents(eventIds: List<String>): Result<Unit> = librarySource.deleteEvents(eventIds)
    override suspend fun clearPlayHistory(): Result<Unit> = librarySource.clearPlayHistory()

    override fun folderMeaningless(folderId: String): Boolean? = listingEngine.folderMeaningless(folderId)
    override fun cacheFolderMeaningless(folderId: String, value: Boolean) = listingEngine.cacheFolderMeaningless(folderId, value)
    override suspend fun findMyPacksFolder(): Result<PikoPathBreadcrumb?> = listingEngine.findMyPacksFolder()
    override suspend fun getOrCreateMyPacksFolder(): Result<PikoPathBreadcrumb> {
        val access = currentAccount?.let(::writeAccess) ?: return Result.failure(
            IllegalStateException(tr("未登录", "Not signed in", "ログインしていません", "로그인하지 않았습니다", "尚未登入")),
        )
        return getOrCreateMyPacksFolder(access)
    }
    internal suspend fun getOrCreateMyPacksFolder(access: DriveWriteAccess): Result<PikoPathBreadcrumb> = listingEngine.getOrCreateMyPacksFolder(access)
    override suspend fun isFolderMeaningless(folderId: String, thresholdBytes: Long, forceRefresh: Boolean): Boolean =
        listingEngine.isFolderMeaningless(folderId, thresholdBytes, forceRefresh)

    override fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder, parentId: String, foldersFirst: Boolean): List<FileStat> =
        listingEngine.sortFiles(files, order, parentId, foldersFirst)
    internal fun sortFilesFast(files: List<FileStat>, order: PikoFileSortOrder, parentId: String, foldersFirst: Boolean = true): List<FileStat> =
        listingEngine.sortFilesFast(files, order, parentId, foldersFirst)
    internal fun sortFiles(files: List<FileStat>, order: PikoFileSortOrder, parentId: String, context: SortContext, foldersFirst: Boolean = true): List<FileStat> =
        listingEngine.sortFiles(files, order, parentId, context, foldersFirst)

    companion object {
        val ROOT_BREADCRUMB = PikoPathBreadcrumb("", "我的网盘")

        internal suspend fun readSmallFileBytes(
            asked: PikPakClient,
            fileId: String,
            wait: suspend (Duration) -> Unit = { delay(it) },
        ): ByteArray = DriveMutationExecutor.readSmallFileBytes(asked, fileId, wait)
    }
}
