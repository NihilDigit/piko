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
import io.github.nihildigit.pikpak.listFiles
import io.github.nihildigit.pikpak.listFilesPaged
import io.github.nihildigit.pikpak.listPlayHistory
import io.github.nihildigit.pikpak.listEvents
import io.github.nihildigit.pikpak.listStarred
import io.github.nihildigit.pikpak.listTrash
import io.github.nihildigit.pikpak.rename
import io.github.nihildigit.pikpak.searchFiles
import io.github.nihildigit.pikpak.searchFilesRecursive
import io.github.nihildigit.pikpak.starFiles
import io.github.nihildigit.pikpak.unstarFiles
import io.github.nihildigit.pikpak.thumbnailUrlOf
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

enum class PikoFileSortOrder { NAME_ASC, NAME_DESC, TIME_DESC, TIME_ASC, SIZE_DESC, SIZE_ASC }

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
        vaultBinding.update { it?.takeIf { binding -> binding.client.account == account } }
        while (true) {
            val previous = dataAccount.value
            if (previous == account) return
            if (!dataAccount.compareAndSet(previous, account)) continue
            pendingServerDeletes.value = emptySet()
            if (previous == null) return
            break
        }
        // 还在路上的列目录属于上一个账号，回来的结果不能记到新账号名下
        markChanged(null)
        listingRequests.value = emptyMap()
        listings.value = emptyMap()
        folderIndex.clear()
        folderMeaninglessCacheFlow.value = emptyMap()
        _folderEmptiness.value = emptyMap()
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
        val indexChanged = when (change) {
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
        markChanged(folders)
        listingRequests.update { requests -> if (folders == null) emptyMap() else requests - folders }
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
        if (indexChanged) normalizeLocations()
        if (notifyChange) _folderChanges.tryEmit(change)
    }

    /**
     * 文件所在目录的完整路径栈（含根）。先查文件夹索引与列过的目录，缺哪一级才向服务端查那一级：
     * 服务端没有按 id 取路径的接口，只能沿 parent_id 逐级上溯，每级一次请求。查到的顺手记进索引。
     */
    suspend fun locateFolder(fileId: String): Result<List<PikoPathBreadcrumb>> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val chain = ArrayDeque<PikoPathBreadcrumb>()
            var parentId = knownParentOf(fileId) ?: fetchDetail(fileId).parentId
            repeat(MAX_LOCATE_DEPTH) {
                if (parentId.isEmpty()) return@runSuspendCatching listOf(ROOT_BREADCRUMB) + chain
                val known = folderIndex.node(parentId)?.takeUnless { it.provisional }
                val knownParent = known?.parentId
                if (known != null && knownParent != null) {
                    chain.addFirst(PikoPathBreadcrumb(parentId, known.name))
                    parentId = knownParent
                } else {
                    val folder = fetchDetail(parentId)
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
        return listings.value.entries.firstOrNull { (_, listing) -> !listing.stale && listing.files.any { it.id == fileId } }?.key
    }
    internal fun knownFile(fileId: String): FileStat? = listings.value.values.asSequence().filter { !it.stale }
        .mapNotNull { it.files.firstOrNull { file -> file.id == fileId } }.firstOrNull()

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
        val listing = listings.value[folderId]?.takeUnless { it.stale } ?: return null
        return listing.files.takeIf { listing.listedAt.elapsedNow() < RECENT_LISTING_FRESH || folderId in locationFolderIds() }
    }

    /*
     * 改动的序号，判断一次列目录的结果还能不能合并进缓存与索引：请求发出之后、回来之前它所在的文件夹有过改动，
     * 回来的多半是改动之前的样子，记下的话刚改的名字又被旧列表改回去。结果照样交给调用方，只是不记。
     */
    private class ChangeMarks(val serial: Long = 0, val unknownAt: Long = 0, val folderAt: Map<String, Long> = emptyMap())

    private val changeMarks = MutableStateFlow(ChangeMarks())

    private fun markChanged(folders: Set<String>?) = changeMarks.update { marks ->
        val serial = marks.serial + 1
        // 说不清哪些变了的那一次盖过此前按文件夹记的，那张表就不必留着
        if (folders == null) ChangeMarks(serial, serial) else ChangeMarks(serial, marks.unknownAt, marks.folderAt + folders.associateWith { serial })
    }

    private fun unchangedSince(folderId: String, serial: Long): Boolean {
        val marks = changeMarks.value
        return marks.unknownAt <= serial && (marks.folderAt[folderId] ?: 0) <= serial
    }

    // 同一个目录同时有几处要列（网盘页重列、目录图刷新这一层）时合成一个请求
    private val listingRequests = MutableStateFlow<Map<String, Deferred<List<FileStat>>>>(emptyMap())

    /**
     * 向服务端列出 [parentId] 的全部条目，记进列表缓存与文件夹索引。请求挂在仓库的作用域上：
     * 先来的调用方被取消（网盘页换了目录），等着同一个结果的其余调用方不受连累。
     */
    private suspend fun fetchListing(parentId: String, asked: PikPakClient = client): List<FileStat> {
        val request = backgroundScope.async(start = CoroutineStart.LAZY) {
            val serial = changeMarks.value.serial
            // 补来源放在这里：列表缓存、目录图、地址栏与网盘页读的都是这一份
            val listed = asked.listFiles(parentId)
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            val files = sourceLedger.enrich(listed)
            if (unchangedSince(parentId, serial)) listingFetched(parentId, files)
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

    private fun listingFetched(parentId: String, files: List<FileStat>) {
        rememberListing(parentId, files)
        rememberChildContents(parentId, files)
        if (folderIndex.listed(parentId, files)) normalizeLocations()
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
            val store = VaultStore(object : VaultFolderIo {
                override suspend fun list(folderId: String) = withContext(Dispatchers.Default) { asked.listFiles(folderId) }
                override suspend fun read(fileId: String) = withContext(Dispatchers.Default) { readBytes(asked, fileId) }
                override suspend fun upload(folderId: String, name: String, bytes: ByteArray) = withContext(Dispatchers.Default) { uploadBytes(asked, folderId, name, bytes) }
                override suspend fun delete(ids: List<String>) = withContext(Dispatchers.Default) { asked.batchDelete(ids) }
            }, onChanged = { folder ->
                if (currentAccount == asked.account) applyChange(DriveChange.ContentsChanged(setOf(folder)))
            }, onWritten = { folder, entries ->
                if (clientManager.currentClient.value === asked) vaultEntriesKnown(folder, entries)
            })
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
        return { folderId -> listBrowsable(folderId, PikoFileSortOrder.TIME_DESC, asked, store) }
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
            val manifest = store.read(parentId, listing).logFailure(TAG, "读取归档清单失败")
            if (clientManager.currentClient.value !== asked) throw CancellationException("Account changed")
            manifest.onSuccess { vaultEntriesKnown(parentId, it) }
            val entries = manifest.getOrDefault(emptyList())
            withVaulted(parentId, listing, entries, sortOrder)
        }
    }

    /** [cachedFiles] 的 [listBrowsable] 版本。清单还没读过时先不列归档条目，等随后的刷新补上。 */
    fun cachedBrowsable(folderId: String, sortOrder: PikoFileSortOrder): List<FileStat>? =
        cachedListing(folderId)?.let { listing ->
            withVaulted(folderId, listing, vault.cached(folderId, listing).orEmpty(), sortOrder)
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
            vaultCoverOf(file.id)?.let { file.copy(thumbnailLink = thumbnailUrlOf(it)) } ?: file
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
                val listing = listAllFiles(candidate).getOrNull() ?: return@withPermit
                vault.read(candidate, listing).logFailure(TAG, "读取归档清单失败（补封面）").onSuccess { vaultEntriesKnown(candidate, it) }
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
    internal fun vaultEntriesKnown(folderId: String, entries: List<VaultEntry>) {
        childContents.markVaulted(folderId, entries.any { it.isArchived })
        childContents.rememberCover(folderId, entries.coverGcid().orEmpty())
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
        childNameFetches.withPermit {
            // 排队期间可能已有同一目录的请求完成
            childContents.get(folderId)?.let { return@withPermit it }
            val asked = client
            val serial = changeMarks.value.serial
            val files = runSuspendCatching { asked.listFilesPaged(parentId = folderId, pageSize = CHILD_NAME_PAGE).files }
                .getOrNull() ?: return@withPermit null
            if (clientManager.currentClient.value !== asked || !unchangedSince(folderId, serial)) return@withPermit null
            val complete = files.size < CHILD_NAME_PAGE
            rememberChildContents(folderId, files, complete)
            if (folderIndex.listed(folderId, files, complete)) normalizeLocations()
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
        runSuspendCatching { fetchDetail(fileId) }
    }

    /** 查详情，是文件夹就记进索引。 */
    private suspend fun fetchDetail(fileId: String): FileDetail = client.getFile(fileId).also { detail ->
        val isFolder = detail.kind == FileKind.FOLDER
        if (folderIndex.learned(detail.id, detail.name, detail.parentId, isFolder, detail.trashed)) normalizeLocations()
    }

    /**
     * 目录已被删除或移入回收站。列一个不存在的目录不报错，只回一个空列表，与空目录分不开，
     * 只能另查详情：彻底删除的查不到，回收站里的回 file_in_recycle_bin，也可能查到但带着 trashed。
     * 只认服务端明确的拒绝，网络失败说不准，按还在处理，免得断一下网就把人退出目录。
     */
    suspend fun isFolderGone(folderId: String): Boolean =
        getFileDetail(folderId).fold(onSuccess = { it.trashed }, onFailure = { it is PikPakException })

    suspend fun getQuota(): Result<QuotaResponse> = withContext(Dispatchers.Default) {
        runSuspendCatching {
            val asked = client
            asked.getQuota().also {
                _quotaFlow.value = it
                _quotaUpdates.tryEmit(asked.account to it)
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
        getFileDetail(fileId).getOrNull()?.downloadUrl

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
     * 全盘按名搜索。PikPak 没有服务端搜索接口，只能逐层遍历目录树，
     * 所以结果是流式的：大网盘走完一轮要几十秒，不能等遍历结束才给结果。
     * 遍历的深度、目录数与超时上限由 SDK 的默认值兜底。
     */
    fun searchRecursive(query: String, parentId: String = ""): Flow<SearchHit> =
        client.searchFilesRecursive(query, parentId).filter { hit ->
            !isPikoInternalFolder(hit.file) && !VaultStore.looksLikeManifest(hit.file) && !VaultStore.isRecoveryFile(hit.file) &&
                !(parentId.isEmpty() && hit.breadcrumb.firstOrNull()?.let(::isPikoInternalFolderName) == true)
        }.map { hit -> hit.copy(file = sourceLedger.enrich(hit.file)) }

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
        val comparator = when (order) {
            // 自然顺序：第 2 集排在第 10 集前面
            PikoFileSortOrder.NAME_ASC -> compareBy(NaturalOrder, FileStat::name)
            PikoFileSortOrder.NAME_DESC -> compareBy(NaturalOrder, FileStat::name).reversed()
            // 按创建时间而非 modified_time：目录的 modified_time 不随其中内容变动而更新，
            // 与创建时间相同（2026-09-23 实测），按它排序只会让「修改时间」名不副实
            PikoFileSortOrder.TIME_DESC -> compareByDescending(FileStat::createdTime)
            PikoFileSortOrder.TIME_ASC -> compareBy(FileStat::createdTime)
            PikoFileSortOrder.SIZE_DESC -> compareByDescending(FileStat::sizeBytes)
            PikoFileSortOrder.SIZE_ASC -> compareBy(FileStat::sizeBytes)
        }
        val sortedFolders = folders.sortedWith(comparator)
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

        private const val MY_PACKS_FOLDER_NAME = "My Packs"

        // 转存任务实测一秒内完成；给大目录留到一分钟
        private const val RESTORE_POLL_LIMIT = 60
        private const val RESTORE_POLL_INTERVAL_MILLIS = 1_000L

        // PikPak 各端自动建的保存目录名不一，官方客户端建过的也算
        private val MY_PACKS_FOLDER_NAMES = setOf("my pack", "my packs", "我的资源", "我的离线")
    }
}
