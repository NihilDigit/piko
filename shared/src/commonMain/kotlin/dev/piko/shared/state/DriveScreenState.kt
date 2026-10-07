package dev.piko.shared.state

import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.data.outsideArchives
import io.github.nihildigit.pikpak.ArchivePasswordException
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.PikPakException
import dev.piko.shared.data.VaultEdits
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.isVaulted
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.data.DriveClipboard
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.ChildFile
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.library
import dev.piko.shared.log.PikoLog
import io.github.nihildigit.pikpak.DriveEvent
import io.github.nihildigit.pikpak.EventPage
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.log.reportFailure
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * 乐观移除失败后，把 [removedIds] 这几项按它们在 [snapshot] 里的位置放回 [current]。
 *
 * 不能直接换回 [snapshot]：几次移除先后进行时，快照里还留着别的操作已经移除成功的条目，
 * 整份换回会让它们重新出现。所以每一项接在快照里它前面、眼下仍在列表中的那一项之后；
 * 眼下已在列表里的（例如期间重列过）不重复放。网盘页的库与我的分享都用它。
 */
internal fun <T> reinsertRemoved(current: List<T>, snapshot: List<T>, removedIds: Set<String>, idOf: (T) -> String): List<T> {
    val result = current.toMutableList()
    val present = current.mapTo(HashSet(), idOf)
    var anchorId: String? = null
    for (item in snapshot) {
        val id = idOf(item)
        if (id in removedIds && id !in present) {
            val at = anchorId?.let { anchor -> result.indexOfFirst { idOf(it) == anchor } + 1 } ?: 0
            result.add(at, item)
            present += id
        }
        if (id in present) anchorId = id
    }
    return result
}

internal fun reinsertRemoved(current: List<FileStat>, snapshot: List<FileStat>, removedIds: Set<String>): List<FileStat> =
    reinsertRemoved(current, snapshot, removedIds) { it.id }

/** 文件夹行在可见区域里停留这么久才预取其内容，见 [DriveScreenState.onFolderVisible]。 */
private const val PREFETCH_DWELL_MILLIS = 400L

/** 高亮的条目不在列表里时，每次静默重列之前等多久。合计约 2.7 秒，盖过实测的列表滞后。 */
private val HIGHLIGHT_RETRY_DELAYS = listOf(300L, 600L, 800L, 1000L)

private const val TAG = "Drive"

/** 对归档条目做只有网盘文件才能做的事时的提示。 */
private const val VAULTED_NEEDS_RESTORE = "已归档的条目需先恢复到网盘"

/**
 * 网盘浏览的全部状态与动作，两端共用。
 *
 * 这里只放与布局无关的东西：文件、选中、搜索、启发式折叠、防窥。列表还是网格、
 * 弹的是 BottomSheet 还是 ContentDialog、提示用 Snackbar 还是 InfoBar，都是各端
 * 自己的事，不进这个类。
 *
 * 状态用 Compose 的 State 而不是 StateFlow：两端的视图层都是 Compose，用 State
 * 可以省掉各写一遍 collectAsState，读取粒度也更细。提示消息反过来用事件流——它是
 * 一次性事件，用状态表达会在重组时重放。
 */
class DriveScreenState(
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
    /**
     * 会话位置（查找重复，见 [DriveLibrary.isSession]）眼下列的内容，会话没开着时为 null，那里是空的。
     * 读的是会话的 Compose 状态，结果变了列表跟着变。
     */
    private val sessionListing: (DriveLibrary) -> SessionListing? = { null },
    /** 把压缩包当文件夹看，见 [ArchiveLocation]。为 null 时进不了压缩包。 */
    private val archives: ArchiveBrowser? = null,
    initialSortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
) {
    var files by mutableStateOf<List<FileStat>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var isRefreshing by mutableStateOf(false)
        private set

    /** 上次加载失败的原因，成功后清空。列表此时仍是旧数据，各端据此提示。 */
    var loadError by mutableStateOf<String?>(null)
        private set
    var sortOrder by mutableStateOf(initialSortOrder)
        private set

    var searchQuery by mutableStateOf("")
        private set
    var isGlobalSearching by mutableStateOf(false)
        private set

    /**
     * 是否处于全盘搜索结果态。与命中数无关：搜完一无所获也要显示「全盘未找到」，
     * 而不是悄悄退回目录内过滤。
     */
    var isGlobalSearchActive by mutableStateOf(false)
        private set
    private val globalSearchHits = mutableStateListOf<SearchHit>()
    private var globalSearchJob: Job? = null

    val selectedFileIds = mutableStateListOf<String>()

    /** 移动端长按进入的多选：取消到一项不剩仍留在多选里，由顶栏的 ✕ 退出。 */
    private var selectionHeldOpen by mutableStateOf(false)

    /**
     * 是否在多选里，由选中集合推出，不由各入口自己置位。全选曾只填集合、未置标志：桌面上条目描了边却没有勾选框，
     * 命令栏未换成多选，换筛选时这些选中也未清除。
     */
    val isSelectionMode: Boolean by derivedStateOf { selectionHeldOpen || selectedFileIds.isNotEmpty() }

    val revealedFileIds = mutableStateListOf<String>()

    var highlightedFileIds by mutableStateOf(driveRepo.takePendingHighlight())
        private set
    var highlightRevision by mutableStateOf(0)
        private set

    /**
     * 「显示全部」按目录记住：规则仍可能误判，用户在某个目录里点开过，回到这里时应当还是展开的。
     * 记在进程内存里而不是偏好里：目录 id 会越积越多，重启后按默认折叠也说得过去。
     */
    val showAllFilesTemporarily: Boolean by derivedStateOf { DriveViewMemory.showAll[activeFolderId] == true }

    var isHeuristicFilterEnabled by mutableStateOf(true)
        private set

    var isNameParsing by mutableStateOf(true)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** 面向用户的一次性提示，各端自己决定怎么呈现。 */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val folderStack get() = driveRepo.folderStackFlow
    val activeFolder: PikoPathBreadcrumb
        get() = driveRepo.folderStackFlow.value.lastOrNull() ?: PikoPathBreadcrumb("", "网盘")

    private var activeFolderId by mutableStateOf(driveRepo.folderStackFlow.value.lastOrNull()?.id.orEmpty())

    /**
     * 眼前列的是哪个库（见 [DriveLibrary]），列的是文件夹时为 null。从库里进了子文件夹就是文件夹：
     * 那里的内容与操作都与网盘里无异。
     */
    val libraryView: DriveLibrary? by derivedStateOf { DriveLibrary.of(activeFolderId) }

    /** 眼前列的是压缩包里的哪一层，不在压缩包里时为 null。 */
    val archiveView: ArchiveLocation? by derivedStateOf { ArchiveLocation.of(activeFolderId) }

    /**
     * 眼前不是网盘里的文件夹：库或压缩包。这里平铺不解析、不折叠，也不能新建、上传、粘贴或接住拖放。
     * 各库、压缩包各自多出或少掉的操作，看 [libraryView] 与 [archiveView]。
     */
    val isVirtualPlace: Boolean by derivedStateOf { libraryView != null || archiveView != null }

    /**
     * 最近添加与播放历史里每个文件对应的那条记录，按文件 ID。列表的副文本（何时添加、看到哪里）
     * 与「从列表中移除」都要它。
     */
    var libraryEvents by mutableStateOf<Map<String, DriveEvent>>(emptyMap())
        private set

    /**
     * [files] 的分析结果。只在 [analyzedFiles] 与 [files] 是同一个列表时可用：换目录后、新结果
     * 算出来之前，不能拿上一个目录的结构去排这一个目录的文件。
     */
    private var analyzedFiles by mutableStateOf<List<FileStat>?>(null)
    private var analysis by mutableStateOf<DriveStructure?>(null)

    private val currentAnalysis: DriveStructure? by derivedStateOf { analysis?.takeIf { analyzedFiles === files } }
    val isDisplayStructureReady: Boolean by derivedStateOf { isSearching || isVirtualPlace || currentAnalysis != null }

    /** 列过的文件夹空不空，见 PikoDriveRepository.folderEmptiness。 */
    val folderEmptiness get() = driveRepo.folderEmptiness

    /** 挂归档标记的文件夹，见 PikoDriveRepository.vaultedFolders。 */
    val vaultedFolders get() = driveRepo.vaultedFolders

    /** 按文件夹 id 的显示信息，后台算好逐个填入。解析关闭时界面不读它。 */
    val folderViews = mutableStateMapOf<String, DriveFolderView>()

    // 以下都用 derivedStateOf 而不是 getter：这些值每帧会被读到多次（列表、空态判断、
    // 全选、折叠提示各读一次），纯 getter 意味着同一帧内把千项目录过滤好几遍。
    // 整层都是次要项时不折叠（原盘的 CLIPINF/ 全是结构文件）：折光了列表为空，连折叠横幅也没处放
    private val isFoldingActive: Boolean by derivedStateOf {
        val folded = currentAnalysis?.foldedIds ?: return@derivedStateOf false
        isHeuristicFilterEnabled && isNameParsing && !isVirtualPlace && folded.size < files.size && isFoldingScope(files)
    }

    val potentialHiddenCount: Int by derivedStateOf {
        if (isFoldingActive) currentAnalysis?.foldedIds?.size ?: 0 else 0
    }

    /**
     * 按类型筛选，null 为不筛。作用于眼前这份列表：目录内容、目录内搜索或全盘搜索的结果。
     * 筛选时只留该类文件、去掉文件夹并平铺，与搜索一样不分作品与分区：分区是按整个目录算的，
     * 只剩一类文件时大半分区是空的。换目录时清掉，与搜索词一样只属于当前这一眼。
     */
    var typeFilter by mutableStateOf<FileCategory?>(null)
        private set

    private val isSearching: Boolean by derivedStateOf { isGlobalSearchActive || searchQuery.isNotBlank() || typeFilter != null }

    /** 筛选之前、搜索之后的文件，类型筛选与可选类型都从这一份算。 */
    private val searchedFiles: List<FileStat> by derivedStateOf {
        when {
            isGlobalSearchActive -> globalSearchHits.map { it.file }
            searchQuery.isNotBlank() -> searchQuery.trim().let { query -> files.filter { listedName(it).matches(query) } }
            else -> files
        }
    }

    /** 眼前这份列表里出现过的类型及其数量，按 [FileCategory] 的声明顺序。筛选菜单只列这些，免得选出空列表。 */
    val availableTypes: List<Pair<FileCategory, Int>> by derivedStateOf {
        val counts = searchedFiles.filterNot { it.isFolder }.groupingBy { it.fileCategory() }.eachCount()
        FileCategory.entries.mapNotNull { category -> counts[category]?.let { category to it } }
    }

    /**
     * 只列有缩略图的文件，文件夹照留，图库视图用，由界面按视图设置。放在这一层而不是只在界面上不画：
     * 全选、移动所选与图片翻页都读 [displayedFiles]，看不见的文件混在里面就会被一并移走或删掉。
     */
    var thumbnailsOnly by mutableStateOf(false)
        private set

    fun updateThumbnailsOnly(value: Boolean) {
        if (value == thumbnailsOnly) return
        thumbnailsOnly = value
        if (isSelectionMode) exitSelection()
    }

    private fun isHiddenByThumbnails(file: FileStat) = thumbnailsOnly && !file.isFolder && file.thumbnailLink.isEmpty()

    /** 列表项：作品头、分区标题与文件。搜索、筛选与解析关闭时照原样平铺，认不出任何作品时也平铺。 */
    val displayItems: List<DriveListItem> by derivedStateOf {
        if (thumbnailsOnly) hideFiles(unfilteredItems, ::isHiddenByThumbnails) else unfilteredItems
    }

    private val unfilteredItems: List<DriveListItem> by derivedStateOf {
        val structure = currentAnalysis
        val hideFolded = isFoldingActive && !showAllFilesTemporarily
        val filter = typeFilter
        when {
            filter != null -> searchedFiles.filter { !it.isFolder && it.fileCategory() == filter }.map { DriveListItem.File(it, null) }
            isGlobalSearchActive || searchQuery.isNotBlank() -> searchedFiles.map { DriveListItem.File(it, null) }
            libraryView?.isSession == true -> sessionItems { blockId -> isSessionGroupExpanded(blockId) }
            // 库里的条目散在全盘各处，按作品与分区归拢的是一个目录里的东西，这里照原来的先后平铺；
            // 压缩包里的也平铺、不折叠：在包里多半是挑要解压的几项，折起来的次要文件正是要挑的一部分
            structure == null || isVirtualPlace -> files.map { DriveListItem.File(it, null) }
            !isNameParsing || structure.blocks.isEmpty() ->
                filterDriveFiles(files, structure.foldedIds, enabled = hideFolded, revealAll = false).map { DriveListItem.File(it, null) }
            else -> buildDriveItems(files, structure, hideFolded) { block -> isBlockExpanded(block) }
        }
    }

    /** 眼前按作品与分区排：不在搜索、不在库或压缩包里、解析开着且认出了作品。 */
    private val isStructured: Boolean by derivedStateOf {
        val structure = currentAnalysis
        !isSearching && !isVirtualPlace && isNameParsing && structure != null && structure.blocks.isNotEmpty()
    }

    /**
     * 列表里文件的每一行，顺序与界面一致，收起的分区当作展开。Shift 连选按它数一段，框选与点选按它把行换成文件。
     *
     * 行与文件不总是一一对应：查找重复里同一个文件可能在两组各占一行，行的 key 带着组。选中的单位是文件，
     * 界面交来的是行（[DriveListItem.File.key]），在这里换算；把行的 key 当文件 ID 存进选中集合，
     * 计数对得上、勾选框却一个也不亮，删除时交给服务端的也不是文件 ID。
     */
    private val fileRows: List<DriveListItem.File> by derivedStateOf {
        val structure = currentAnalysis
        val rows = when {
            isStructured && structure != null -> buildDriveItems(files, structure, isFoldingActive && !showAllFilesTemporarily) { true }
            !isSearching && libraryView?.isSession == true -> sessionItems { true }
            else -> displayItems
        }
        rows.filterIsInstance<DriveListItem.File>().filterNot { isHiddenByThumbnails(it.file) }
    }

    private val fileByRowKey: Map<String, FileStat> by derivedStateOf { fileRows.associate { it.key to it.file } }

    /**
     * 当前可见的文件，顺序与界面一致。收起的分区与挂在视频下的附件也算在内：它们只是没单独占一行，
     * 全选、播放列表与图片翻页都该包括它们。
     */
    val displayedFiles: List<FileStat> by derivedStateOf {
        val shown = fileRows.map { it.file }
        val hosts = if (isStructured) currentAnalysis?.attachedTo.orEmpty() else emptyMap()
        val shownIds = shown.mapTo(HashSet()) { it.id }
        val attachments = files.filter { file -> hosts[file.id]?.let { it in shownIds } == true && !isHiddenByThumbnails(file) }
        // 一个文件可能占两行：字幕与音轨既挂在宿主下、又在自己那一栏里，查找重复里同一个文件在两组。全选与翻页只算一次
        (shown + attachments).distinctBy { it.id }
    }

    /**
     * 列表里替 [highlightedFileIds] 亮起的行。同一内容的其他版本不单独占一行，挂在最大的那个版本的行上，
     * 要标出它们只能标那一行；只找它们自己的行，滚动与描边都会落空（信息流、传输页定位到这种版本时）。
     */
    val highlightedRowIds: Set<String> by derivedStateOf {
        val ids = highlightedFileIds
        if (ids.isEmpty()) return@derivedStateOf emptySet()
        val rows = displayItems.mapNotNullTo(HashSet()) { (it as? DriveListItem.File)?.file?.id }
        val hosts = currentAnalysis?.attachedTo.orEmpty()
        ids.mapNotNullTo(HashSet()) { id -> id.takeIf { it in rows } ?: hosts[id]?.takeIf { it in rows } }
    }

    /** 列表项里的分区标题及其下标。顶栏副标题按首个可见项反查，分区菜单据此跳转。 */
    val sectionHeaders: List<IndexedValue<DriveListItem.SectionHeader>> by derivedStateOf {
        displayItems.withIndex().mapNotNull { (index, item) -> (item as? DriveListItem.SectionHeader)?.let { IndexedValue(index, it) } }
    }

    private fun isBlockExpanded(block: DriveBlock): Boolean =
        DriveViewMemory.expanded[expandKey(block.id)] ?: block.defaultExpanded

    private fun expandKey(blockId: String) = "$activeFolderId|$blockId"

    fun toggleSection(blockId: String) {
        // 先看记下的状态，再看块的默认值：「次要文件」块由 buildDriveItems 临时拼出，不在 blocks 里，
        // 只按 blocks 查的话它永远当作展开，每点一次都写成收起，收起后就再也展不开
        val key = expandKey(blockId)
        val current = DriveViewMemory.expanded[key]
            ?: currentAnalysis?.blocks?.firstOrNull { it.id == blockId }?.defaultExpanded
            ?: true
        DriveViewMemory.expanded[key] = !current
    }

    fun expandSection(blockId: String) {
        DriveViewMemory.expanded[expandKey(blockId)] = true
    }

    /** 各行带的显示信息，按文件 ID。查找重复里一个文件占两行，两行的显示信息相同。 */
    private val rowViews: Map<String, DriveFileView> by derivedStateOf {
        fileRows.mapNotNull { row -> row.view?.let { row.file.id to it } }.toMap()
    }

    /** 文件夹的解析结果，解析关闭或还没算出时为 null。 */
    fun folderView(file: FileStat): DriveFolderView? = if (isNameParsing && file.isFolder) folderViews[file.id] else null

    /** 一项眼下在列表里叫什么，见 [DriveItemName]。不在眼前列表里的照原样。 */
    fun itemName(file: FileStat): DriveItemName = driveItemName(file, rowViews[file.id], folderView(file))

    /**
     * 不搜索时这一项在列表里叫什么，目录内搜索按它与真实名称匹配。搜索结果平铺、没有作品头，行上不带解析结果，
     * [itemName] 在搜索时取不到集号前的作品名，所以另从这个目录的分析结果取。
     */
    private fun listedName(file: FileStat): DriveItemName =
        driveItemName(file, if (isNameParsing) currentAnalysis?.views?.get(file.id) else null, folderView(file))

    /** 文件的解析结果，详情面板用。解析关闭或未识别时为 null。 */
    fun fileView(fileId: String): DriveFileView? = if (!isNameParsing) null else currentAnalysis?.views?.get(fileId)

    /**
     * 全盘命中所在的目录路径。SDK 给的 parentPath 不含根，根目录下的命中拿到的是
     * 空串，这里补上，否则那一行整个不显示。
     */
    val hitLocations: Map<String, String> by derivedStateOf {
        if (isGlobalSearchActive) {
            globalSearchHits.associate { it.file.id to it.parentPath.ifEmpty { "网盘" } }
        } else {
            emptyMap()
        }
    }

    init {
        scope.launch {
            preferences.heuristicFilterFlow.collect { isHeuristicFilterEnabled = it }
        }
        scope.launch {
            preferences.nameParsingFlow.collect { isNameParsing = it }
        }
        // 目录一变就重新加载，不管是谁改的栈。只由这里负责：界面外的跳转（「在网盘中显示」）改栈时
        // 网盘页可能一直开着、不会重建，让各导航入口自己调加载的话，这条路就漏掉了
        scope.launch {
            driveRepo.folderStackFlow.collect { stack ->
                val id = stack.lastOrNull()?.id.orEmpty()
                if (id == activeFolderId) return@collect
                activeFolderId = id
                onFolderChanged()
            }
        }
        // 查重的结果随扫描、删除、改名与撤销变化，不经 load：停在那个位置时跟着重画
        scope.launch {
            snapshotFlow { libraryView?.takeIf { it.isSession }?.let { it to sessionListing(it)?.files } }.collect { shown ->
                if (shown != null) showSession(shown.first)
            }
        }
        // 同理，高亮请求随时可能来，不只在网盘页建出来的那一刻
        scope.launch {
            driveRepo.pendingHighlights.collect { ids -> if (ids.isNotEmpty()) highlight(driveRepo.takePendingHighlight()) }
        }
        scope.launch {
            snapshotFlow { highlightedFileIds to activeFolderId }.collectLatest { (ids, folderId) ->
                if (ids.isNotEmpty()) catchUpWithHighlight(ids, folderId)
            }
        }
        // 要定位的条目若在收起的分区里、或被启发式折叠藏着，列表里就没有它可滚动：展开它所在的分区并显示全部
        scope.launch {
            snapshotFlow { highlightedFileIds to currentAnalysis }.collect { (ids, structure) ->
                if (ids.isEmpty() || structure == null) return@collect
                revealHighlightedItems(structure, ids)
            }
        }
        // 解析放到后台：上千个文件的目录要算几秒。按内容缓存，重组、刷新与返回上级都不重算
        scope.launch {
            snapshotFlow { files }.collectLatest { list ->
                val key = DriveViewMemory.fingerprint(list)
                val structure = DriveViewMemory.structure(key)
                    ?: withContext(Dispatchers.Default) { analyzeDriveFolder(list) }.also { DriveViewMemory.putStructure(key, it) }
                Snapshot.withMutableSnapshot {
                    revealHighlightedItems(structure, highlightedFileIds)
                    analysis = structure
                    analyzedFiles = list
                }
            }
        }
        scope.launch {
            // 记下的文件夹内容启动后才从磁盘载入完，载入后再描述一遍
            combine(snapshotFlow { files }, driveRepo.childContentLoads) { list, _ -> list }
                .collectLatest { list -> describeFolders(list.filter(FileStat::isFolder)) }
        }
        // 文件夹的归档封面在列表画出之后才读到（可见时补读、清单写成、从磁盘载入），读到就补进眼前的列表，不重列
        scope.launch {
            driveRepo.vaultCoverChanges.collect {
                val patched = driveRepo.withVaultCovers(files)
                if (patched !== files) files = patched
            }
        }
        // 网盘里的改动（这一页自己做的与界面外的上传、解压、撤销都算）由仓库层广播过来，订阅放在这里，
        // 免得每个平台的视图各订阅一遍；各个改动入口因此也不必自己重列
        scope.launch {
            // 连着来的改动（批量重命名一项一条）不各重列一次：正在重列时来的合成一次，等这次列完再列
            val reloads = Channel<Unit>(Channel.CONFLATED)
            launch { driveRepo.folderChanges.collect { change -> if (isAffectedBy(change)) reloads.trySend(Unit) } }
            for (reload in reloads) {
                load()
                loadJob?.join()
            }
        }
    }

    /** 眼前这一页受不受 [change] 影响。库是从全盘挑出来的，哪里改了都可能有它；会话位置与压缩包里的不经网络重列。 */
    private fun isAffectedBy(change: DriveChange): Boolean = when {
        libraryView?.isSession == true || archiveView != null -> false
        libraryView != null -> true
        else -> change.affects(activeFolderId)
    }

    /** 恢复上次退出时开着的几个标签，见 [PikoDriveRepository.restoreTabs]。恢复了返回 true。 */
    suspend fun restoreTabs(): Boolean {
        val unchangedBefore = activeFolderId
        if (!driveRepo.restoreTabs()) return false
        // 活动标签就停在根目录时栈顶没变，栈的监听不会触发，加载由这里补上
        if (driveRepo.folderStackFlow.value.lastOrNull()?.id.orEmpty() == unchangedBefore) onFolderChanged()
        return true
    }

    /**
     * 恢复上次退出时的目录栈。走这里而不是让视图直接调仓库，是因为恢复同样要
     * 触发一次加载；视图直接改栈会绕过加载，表现为进来是空列表。
     */
    fun restoreFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isEmpty()) return
        // 栈顶没变时栈的监听不会触发，这一次加载由这里补上
        val unchanged = stack.last().id == activeFolderId
        driveRepo.restoreFolderStack(stack)
        if (unchanged) onFolderChanged()
    }

    /**
     * [files] 当前是哪个目录的内容。视图据此恢复滚动位置：必须等列表真的换成目标目录的
     * 内容再恢复，否则会把旧列表的位置记到新目录名下。
     */
    var loadedFolderId by mutableStateOf<String?>(null)
        private set

    private var loadJob: Job? = null

    /**
     * 路径栈上的目录有缓存时先显示缓存，不进加载态，再在后台刷新；返回上级与切回网盘页
     * 因此是即时的。下拉刷新不用缓存。
     *
     * 新的加载取消旧的：进入 A 后未等返回就进了 B，A 晚到的结果不能盖掉 B。
     */
    fun load(refresh: Boolean = false) = load(useCache = !refresh, showRefreshing = refresh)

    // 根目录里的同步与临时文件夹不进入浏览视图。
    private fun withoutInternalFolders(folderId: String, listing: List<FileStat>): List<FileStat> =
        listing.filterNot { isPikoInternalFolder(it, folderId) }

    /** [useCache] 与 [showRefreshing] 都为 false 是静默重列：不用缓存，也不出任何加载指示，见 [catchUpWithHighlight]。 */
    private fun load(useCache: Boolean, showRefreshing: Boolean) {
        val folderId = activeFolder.id
        DriveLibrary.of(folderId)?.let { library ->
            loadLibrary(library, useCache, showRefreshing)
            return
        }
        ArchiveLocation.of(folderId)?.let { location ->
            loadArchive(location, useCache, showRefreshing)
            return
        }
        val cached = if (useCache) driveRepo.cachedBrowsable(folderId, sortOrder) else null
        when {
            cached != null -> {
                files = withoutInternalFolders(folderId, cached)
                loadedFolderId = folderId
                isLoading = false
            }
            showRefreshing -> isRefreshing = true
            useCache -> isLoading = true
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val started = TimeSource.Monotonic.markNow()
            val listing = driveRepo.listBrowsable(parentId = folderId, sortOrder = sortOrder)
            val elapsed = started.elapsedNow().inWholeMilliseconds
            listing.onSuccess { PikoLog.d(TAG, "列出文件夹 ${folderId.ifEmpty { "根目录" }}：${it.size} 项，$elapsed ms${if (cached != null) "，先显示了缓存" else ""}") }
            // 空列表可能是目录已经不在了：上次退出时停在的目录后来被删，或在别的客户端进了回收站。
            // 这时退回上一级，而不是把一个不存在的目录画成「此文件夹为空」。上一级也不在的话，
            // 它的加载会再退一级。只在列表为空时才多查一次详情，平常的目录不多花请求
            val empty = listing.getOrNull()?.isEmpty() == true
            if (empty && folderId.isNotEmpty() && driveRepo.isFolderGone(folderId)) {
                PikoLog.i(TAG, "文件夹 $folderId 已不存在，返回上一级")
                isLoading = false
                isRefreshing = false
                if (navigateUp()) _messages.tryEmit("文件夹已不存在，已返回上一级")
                return@launch
            }
            listing
                .onSuccess {
                    files = withoutInternalFolders(folderId, it)
                    loadedFolderId = folderId
                    loadError = null
                }
                .logFailure(TAG, "读取目录失败：文件夹 ${folderId.ifEmpty { "根目录" }}，$elapsed ms，${if (files.isEmpty()) "无旧内容" else "保留旧内容 ${files.size} 项"}")
                .onFailure {
                    // 消息是一次性的，弹完就没了；而列表此刻显示的是上一次的内容，
                    // 界面需要一个持续的标记才能说明「这是陈旧数据」
                    loadError = it.message ?: "读取网盘失败"
                    _messages.tryEmit("加载失败")
                }
            isLoading = false
            isRefreshing = false
        }
    }

    /** 库上一次列出的内容，返回库时先显示它再刷新，与路径栈上的目录一样。只在这个网盘页的寿命里有效。 */
    private class LibraryListing(val files: List<FileStat>, val events: Map<String, DriveEvent>)

    private val libraryListings = mutableMapOf<DriveLibrary, LibraryListing>()

    private fun loadLibrary(library: DriveLibrary, useCache: Boolean, showRefreshing: Boolean) {
        if (library.isSession) {
            loadJob?.cancel()
            isRefreshing = false
            showSession(library)
            return
        }
        val cached = if (useCache) libraryListings[library] else null
        when {
            cached != null -> {
                showLibrary(library, cached)
                isLoading = false
            }
            showRefreshing -> isRefreshing = true
            useCache -> isLoading = true
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            fetchLibrary(library)
                .onSuccess { listing ->
                    libraryListings[library] = listing
                    showLibrary(library, listing)
                    loadError = null
                }
                .logFailure(TAG, "读取${library.title}失败")
                .onFailure {
                    loadError = it.message ?: "读取${library.title}失败"
                    _messages.tryEmit("加载失败")
                }
            isLoading = false
            isRefreshing = false
        }
    }

    // region 压缩包

    /** 这个压缩包要密码，界面弹密码框；输了交给 [submitArchivePassword]，不输就 [dismissArchivePassword]。 */
    class ArchivePasswordRequest(val archiveName: String, val incorrect: Boolean)

    var archivePasswordRequest by mutableStateOf<ArchivePasswordRequest?>(null)
        private set

    /** 正在为打开而准备的包内文件（见 [prepareArchiveEntry]），界面在那一行上转圈。 */
    var preparingEntryId by mutableStateOf<String?>(null)
        private set

    /** 把压缩包当文件夹打开。gcid 还没算出来的（刚传完）列不了目录，与解压同一句提示。 */
    fun openArchive(file: FileStat) {
        if (archives == null) return
        if (file.hash.isEmpty()) {
            _messages.tryEmit("文件尚未完成校验，稍后再试")
            return
        }
        openFolder(ArchiveLocation(file.id, file.hash, "").id, file.name)
    }

    private fun loadArchive(location: ArchiveLocation, useCache: Boolean, showRefreshing: Boolean, password: String? = null) {
        val browser = archives ?: return
        archivePasswordRequest = null
        val cached = if (useCache) browser.cached(location) else null
        when {
            cached != null -> {
                files = cached
                loadedFolderId = location.id
                isLoading = false
            }
            showRefreshing -> isRefreshing = true
            else -> isLoading = true
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            browser.list(location, password)
                .onSuccess {
                    files = it
                    loadedFolderId = location.id
                    loadError = null
                }
                .onFailure { error ->
                    if (error is ArchivePasswordException) {
                        // 列表留空：这一层还没列出来过，没有旧内容可显示
                        files = emptyList()
                        loadedFolderId = location.id
                        archivePasswordRequest = ArchivePasswordRequest(archiveName(), incorrect = password != null)
                    } else {
                        PikoLog.w(TAG, "读取压缩包失败", error)
                        // 没列出来过的一层，眼前还是上一个目录的内容，不能当成包里的
                        if (cached == null) {
                            files = emptyList()
                            loadedFolderId = location.id
                        }
                        loadError = archiveFailure(error)
                        _messages.tryEmit(loadError!!)
                    }
                }
            isLoading = false
            isRefreshing = false
        }
    }

    fun submitArchivePassword(password: String) {
        val location = archiveView ?: return
        loadArchive(location, useCache = false, showRefreshing = false, password = password)
    }

    /** 不输密码就退出这个压缩包，回到它所在的文件夹。 */
    fun dismissArchivePassword() {
        archivePasswordRequest = null
        val stack = driveRepo.folderStackFlow.value
        val outside = stack.outsideArchives()
        if (outside.isNotEmpty() && outside.size < stack.size) driveRepo.popToBreadcrumb(outside.size - 1)
    }

    /**
     * 包里的文件交给播放器、预览或下载之前先变成借得出来的样子，见 [ArchiveBrowser.prepare]。
     * 从没解压过的包第一次要等几秒，失败时提示并返回 null。不在压缩包里的文件原样返回。
     */
    suspend fun prepareArchiveEntry(file: FileStat): FileStat? {
        if (!ArchiveEntryId.isEntry(file.id)) return file
        val browser = archives ?: return null
        if (preparingEntryId != null) return null
        preparingEntryId = file.id
        try {
            val location = archiveView
            return browser.prepare(file, files)
                .onSuccess {
                    // 引导解压之后这一层重列过，各项都有了 gcid，换上新的一份，播放列表里的下一集也借得出来
                    if (location != null && archiveView == location) browser.cached(location)?.let { files = it }
                }
                .logFailure(TAG, "准备压缩包里的文件失败")
                .onFailure { _messages.tryEmit("无法打开：${archiveFailure(it)}") }
                .getOrNull()
        } finally {
            preparingEntryId = null
        }
    }

    /**
     * 眼前所在的压缩包，解压选中的几项时交给解压会话。名字取路径栈上压缩包那一级：
     * 解压出来的文件夹以它命名，提示里也写它。
     */
    fun currentArchive(): Pair<FileStat, String>? {
        val location = archiveView ?: return null
        val file = FileStat(kind = FileKind.FILE, id = location.archiveId, name = archiveName(), hash = location.gcid)
        return file to archives?.password(location.archiveId).orEmpty()
    }

    /** 包里这几项的包内路径，解压选中的几项用。文件夹取它那一层的路径，连同里面的一起解压。 */
    fun archivePaths(items: List<FileStat>): List<String> = items.mapNotNull { item ->
        ArchiveLocation.of(item.id)?.path ?: ArchiveEntryId.pathOf(item.id)?.second
    }

    private fun archiveName(): String {
        val stack = driveRepo.folderStackFlow.value
        return stack.getOrNull(stack.outsideArchives().size)?.name ?: "压缩包"
    }

    private fun archiveFailure(error: Throwable): String = when {
        error is PikPakException && error.errorMessage == "INVALID_FILE_FORMAT" -> "格式无法识别，压缩包可能已损坏或是分卷"
        error is PikPakException -> error.errorDescription?.takeIf { it.isNotBlank() } ?: error.errorMessage
        error is IllegalStateException -> error.message ?: "未知错误"
        else -> "网络异常"
    }

    // endregion

    private fun showLibrary(library: DriveLibrary, listing: LibraryListing) {
        files = listing.files
        libraryEvents = listing.events
        loadedFolderId = library.id
    }

    // 事件记录只取第一页，至多 100 条，按时间倒序：再往前的播放与添加，到这里来找的人不多
    private suspend fun fetchLibrary(library: DriveLibrary): Result<LibraryListing> = when (library) {
        DriveLibrary.STARRED -> driveRepo.starredFiles().map { LibraryListing(it, emptyMap()) }
        DriveLibrary.TRASH -> driveRepo.trashFiles().map { LibraryListing(it, emptyMap()) }
        DriveLibrary.RECENT -> driveRepo.recentlyAdded().map(::eventListing)
        DriveLibrary.HISTORY -> driveRepo.playHistory().map(::eventListing)
        DriveLibrary.DUPLICATES -> error("${library.title}的内容来自会话，不经网络")
    }

    // region 会话位置：查找重复

    /** 会话的结果换了（扫完、移走或改了名、撤销）就重画。files 只放去重后的文件，供按 ID 找回与空态判断。 */
    private fun showSession(library: DriveLibrary) {
        files = sessionListing(library)?.files.orEmpty()
        libraryEvents = emptyMap()
        loadedFolderId = library.id
        loadError = null
        isLoading = false
    }

    /**
     * 选中 [ids]（查重建议移走的），之后就是普通的多选。不在进来时自动选上：曾经那样做，一进来就在多选里，
     * 单击变成勾选、播不了也看不了预览，点一下空白处整组勾选又没了。
     */
    fun selectOnly(ids: Set<String>) {
        if (ids.isEmpty()) return
        exitSelection()
        selectedFileIds.addAll(ids)
    }

    private fun isSessionGroupExpanded(blockId: String): Boolean = DriveViewMemory.expanded[expandKey(blockId)] ?: true

    private fun sessionItems(isExpanded: (blockId: String) -> Boolean): List<DriveListItem> =
        libraryView?.let(sessionListing)?.items(isExpanded).orEmpty()

    // endregion

    /**
     * 文件已删除的记录服务端照样返回，只是不再内嵌文件；移进回收站的仍内嵌着。两种都不列：
     * 网盘页的条目要能打开、能操作，一行打不开的记录放在这里只会被当成坏了。同一文件的几条记录只留最新的。
     */
    private fun eventListing(page: EventPage): LibraryListing {
        val live = page.events.mapNotNull { event -> event.file?.takeIf { !it.trashed }?.let { it to event } }.distinctBy { it.first.id }
        return LibraryListing(live.map { it.first }, live.associate { (file, event) -> file.id to event })
    }

    /** 在网盘里打开条目所在的文件夹并标出它，库里的条目用。文件夹也是在上级里标出，而不是进去。 */
    fun revealInDrive(file: FileStat) {
        scope.launch {
            driveRepo.locateFolder(file.id)
                .logFailure(TAG, "定位条目失败")
                .onSuccess { parents ->
                    // 被拦下问过之后，标出那一步要跟着换栈一起重走
                    driveRepo.navigateThen(parents) {
                        driveRepo.updateFolderStack(parents)
                        highlight(setOf(file.id))
                    }
                }
                .onFailure { _messages.tryEmit("找不到它所在的文件夹") }
        }
    }

    /** 从最近添加或播放历史里移除这几项的记录，文件本身不动。先从列表里拿掉，失败再放回来。 */
    fun removeFromLibrary(ids: Collection<String>) {
        val library = libraryView?.takeIf { it.isEventLog } ?: return
        val eventIds = ids.mapNotNull { libraryEvents[it]?.id }
        if (eventIds.isEmpty()) return
        val before = files
        val removedIds = ids.toSet()
        val generation = libraryGeneration
        files = files.filterNot { it.id in removedIds }
        exitSelection()
        scope.launch {
            driveRepo.deleteEvents(eventIds)
                .onSuccess {
                    libraryListings.remove(library)
                    _messages.tryEmit(if (ids.size == 1) "已从${library.title}中移除" else "已从${library.title}中移除 ${ids.size} 项")
                }
                .logFailure(TAG, "移除记录失败")
                .onFailure {
                    if (activeFolderId == library.id && generation == libraryGeneration) {
                        files = reinsertRemoved(files, before, removedIds)
                    }
                    _messages.tryEmit("移除失败")
                }
        }
    }

    /**
     * 库列表的代次，清空时加一。清空之前发出的单项移除晚于清空失败时不再放回：
     * 那条记录已随清空没了，放回就是往清空的列表里塞一条不存在的记录。
     * 只看位置不够，清空前后都停在播放历史里。
     */
    private var libraryGeneration = 0

    /** 清空播放历史。服务端没有撤销，官方客户端里的历史一起没了，由界面先确认。 */
    fun clearPlayHistory() {
        val before = files
        libraryGeneration++
        files = emptyList()
        exitSelection()
        scope.launch {
            driveRepo.clearPlayHistory()
                .onSuccess {
                    libraryListings.remove(DriveLibrary.HISTORY)
                    _messages.tryEmit("已清空播放历史")
                }
                .logFailure(TAG, "清空播放历史失败")
                .onFailure {
                    if (activeFolderId == DriveLibrary.HISTORY.id) files = reinsertRemoved(files, before, before.mapTo(HashSet()) { it.id })
                    _messages.tryEmit("清空失败")
                }
        }
    }

    /** 回收站里有恢复或彻底删除在进行。两者改的是同一份列表，并发执行会让选中与结果对不上。 */
    var isTrashActionRunning by mutableStateOf(false)
        private set

    fun restoreFromTrash(ids: List<String>) = trashAction(ids) {
        driveRepo.restore(ids)
            .onSuccess {
                exitSelection()
                _messages.tryEmit(if (ids.size == 1) "已恢复" else "已恢复 ${ids.size} 项")
            }
            .logFailure(TAG, "恢复失败")
            .onFailure { _messages.tryEmit("恢复失败") }
    }

    /** 彻底删除，不能撤销，由界面先确认。 */
    fun deletePermanently(ids: List<String>) = trashAction(ids) {
        driveRepo.delete(ids)
            .onSuccess {
                exitSelection()
                _messages.tryEmit(if (ids.size == 1) "已彻底删除" else "已彻底删除 ${ids.size} 项")
            }
            .logFailure(TAG, "彻底删除失败")
            .onFailure { _messages.tryEmit("删除失败") }
    }

    private fun trashAction(ids: List<String>, action: suspend () -> Unit) {
        if (ids.isEmpty() || isTrashActionRunning) return
        isTrashActionRunning = true
        scope.launch {
            try {
                action()
            } finally {
                isTrashActionRunning = false
                load(useCache = false, showRefreshing = false)
            }
        }
    }

    fun changeSortOrder(order: PikoFileSortOrder) {
        if (order == sortOrder) return
        sortOrder = order
        load()
    }

    // 以下几个导航只改栈，重新加载与清掉搜索、选中这些由栈的监听统一做，见 init

    fun openFolder(id: String, name: String) {
        driveRepo.pushFolder(id, name)
    }

    fun navigateUp(): Boolean = driveRepo.popFolder() != null

    /** 浏览历史里的后退与前进，见 PikoDriveRepository.historyFlow。 */
    val history get() = driveRepo.historyFlow

    fun goBack(): Boolean = driveRepo.goBack()

    fun goForward(): Boolean = driveRepo.goForward()

    fun navigateToBreadcrumb(index: Int) {
        driveRepo.popToBreadcrumb(index)
    }

    /**
     * 地址栏里输入的路径，写法见 [addressPathNames]。
     * 找到了就跳过去（记进浏览历史），返回 true；找不到哪一层就提示哪一层，返回 false，地址栏留着让用户改。
     */
    suspend fun goToPath(text: String): Boolean {
        val names = addressPathNames(text, addressBase())
        return driveRepo.resolveFolderPath(names).fold(
            onSuccess = { stack ->
                driveRepo.updateFolderStack(stack)
                true
            },
            onFailure = { error ->
                _messages.emit(
                    if (error is PikoDriveRepository.FolderNotFoundException) error.message!! else "打不开这个路径，请检查网络后重试",
                )
                false
            },
        )
    }

    /**
     * 地址栏边输边给的补全：最后一个分隔符之前是上级（写法同 [goToPath]），之后是正在输的一段，
     * 列出上级里名字含这一段的文件夹，开头相同的在前，不分大小写。
     * 还没输分隔符时上级是根，眼前不在根上的话，当前文件夹里匹配的也列上：多半是想往下走。
     * 上级找不到时 [AddressCompletion.parentFound] 为 false，界面据此改给搜索。
     * 列目录经仓库的子文件夹表，同一层连着敲字不会反复请求；调用方负责防抖与取消旧的一次。
     */
    suspend fun addressCompletions(text: String): AddressCompletion {
        val current = addressBase()
        val cut = text.indexOfLast { it == '/' || it == '\\' }
        val partial = text.substring(cut + 1).trim()
        val parentText = if (cut < 0) "" else text.substring(0, cut + 1)
        val parent = driveRepo.resolveFolderPath(addressPathNames(parentText, current)).getOrNull()
            ?: return AddressCompletion(partial, parentFound = false, matches = emptyList())
        val bases = if (cut < 0 && current.size > 1) listOf(current, parent) else listOf(parent)
        val matches = bases.flatMap { base ->
            val folders = driveRepo.subfolders(base.last().id).getOrNull().orEmpty()
            rankByName(folders, partial).map { base + it }
        }.distinctBy { it.last().id }.take(ADDRESS_COMPLETION_LIMIT)
        return AddressCompletion(partial, parentFound = true, matches = matches)
    }

    // 相对路径与补全以眼前的位置为起点；人在库里时那不是网盘里的一条路径，改从根起。
    // 在压缩包里时从包所在的文件夹起：地址栏解析的是网盘里的路径，进不了包里
    private fun addressBase(): List<PikoPathBreadcrumb> =
        driveRepo.folderStackFlow.value.outsideArchives().takeIf { it.library == null } ?: listOf(PikoDriveRepository.ROOT_BREADCRUMB)

    /** 某一级下的全部文件夹，地址栏里路径段后面的 › 点开用。压缩包里的一层列包里的文件夹，与目录图同一份。 */
    suspend fun subfoldersOf(folderId: String): Result<List<PikoPathBreadcrumb>> {
        if (ArchiveLocation.of(folderId) == null) return driveRepo.subfolders(folderId)
        return when (val level = folderMapLevel(folderId)) {
            is FolderMapLevel.Loaded -> Result.success(level.nodes.filter { it.expandable }.map { it.crumb })
            FolderMapLevel.NeedsPassword -> Result.failure(IllegalStateException("需要密码"))
            is FolderMapLevel.Failed -> Result.failure(IllegalStateException(level.message))
        }
    }

    /**
     * 目录图里一个节点下的一层（见 [FolderMapLevel]），文件夹与文件都列。网盘里能当文件夹打开的压缩包是可展开的节点；
     * 压缩包与包里的文件夹列出包里的内容，密码先逐个试存过的（见 [ArchiveBrowser.list]），都不对时交给界面，
     * 点了进到包里再输。库不是文件夹层级，下面是空的。
     */
    suspend fun folderMapLevel(id: String): FolderMapLevel {
        ArchiveLocation.of(id)?.let { location ->
            val browser = archives ?: return FolderMapLevel.Loaded(emptyList())
            return browser.list(location).fold(
                onSuccess = { entries ->
                    FolderMapLevel.Loaded(entries.map { entry ->
                        FolderMapNode(PikoPathBreadcrumb(entry.id, entry.name), isArchive = false, file = entry.takeUnless { it.isFolder })
                    })
                },
                onFailure = { error -> if (error is ArchivePasswordException) FolderMapLevel.NeedsPassword else FolderMapLevel.Failed(archiveFailure(error)) },
            )
        }
        if (DriveLibrary.of(id) != null) return FolderMapLevel.Loaded(emptyList())
        return driveRepo.folderMapLevel(id).fold(
            onSuccess = { files ->
                FolderMapLevel.Loaded(files.map { file ->
                    when {
                        file.isFolder -> FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false)
                        archives != null && file.isExtractableArchive && file.hash.isNotEmpty() ->
                            FolderMapNode(PikoPathBreadcrumb(ArchiveLocation(file.id, file.hash, "").id, file.name), isArchive = true)
                        else -> FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false, file = file)
                    }
                })
            },
            onFailure = { FolderMapLevel.Failed("列不出这个文件夹，请检查网络") },
        )
    }

    /** 地址栏历史里的快速访问项：只存 ID 与名字，上级逐层查出来再跳，与侧边栏的快速访问相同。 */
    fun openPinned(folder: PikoPathBreadcrumb) {
        scope.launch {
            driveRepo.locateFolder(folder.id)
                .logFailure(TAG, "地址栏定位固定的文件夹失败")
                .onSuccess { parents -> driveRepo.updateFolderStack(parents + folder) }
                .onFailure { _messages.emit("找不到这个文件夹，它可能已被删除") }
        }
    }

    fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) {
        driveRepo.navigateToFolder(breadcrumb)
    }

    fun updateTypeFilter(value: FileCategory?) {
        typeFilter = value
        // 选中项可能已被筛掉，留着会让「移动所选」动到看不见的文件
        if (isSelectionMode) exitSelection()
    }

    /** 换了目录：搜索态、选中态、防窥揭示都不该跨目录留存。 */
    private fun onFolderChanged() {
        typeFilter = null
        searchQuery = ""
        stopGlobalSearch()
        exitSelection()
        revealedFileIds.clear()
        load()
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
        stopGlobalSearch()
    }

    /**
     * 全盘搜索。PikPak 没有服务端按名搜索，只能逐层遍历，所以结果边走边到，
     * 中途可以停下并保留已找到的部分。
     */
    fun startGlobalSearch() {
        val keyword = searchQuery.trim()
        if (keyword.isEmpty()) return
        globalSearchHits.clear()
        isGlobalSearchActive = true
        isGlobalSearching = true
        globalSearchJob = scope.launch {
            try {
                driveRepo.searchRecursive(keyword).collect { globalSearchHits.add(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PikoLog.w(TAG, "全盘搜索失败，已找到 ${globalSearchHits.size} 项", e)
                _messages.tryEmit("全盘搜索失败")
            } finally {
                isGlobalSearching = false
            }
        }
    }

    /** 只停止遍历，已找到的结果留在列表里。 */
    fun cancelGlobalSearch() {
        globalSearchJob?.cancel()
    }

    fun stopGlobalSearch() {
        globalSearchJob?.cancel()
        globalSearchJob = null
        isGlobalSearching = false
        isGlobalSearchActive = false
        globalSearchHits.clear()
    }

    fun setShowAllFiles(value: Boolean) {
        DriveViewMemory.showAll[activeFolderId] = value
    }

    private suspend fun describeFolders(folders: List<FileStat>) {
        folders.forEach { folder -> folderViews[folder.id] = folderView(folder, driveRepo.knownChildContents(folder.id)) }
    }

    private suspend fun folderView(folder: FileStat, content: List<ChildFile>?): DriveFolderView {
        val key = DriveViewMemory.folderKey(folder, content)
        return DriveViewMemory.folderView(key)
            ?: withContext(Dispatchers.Default) { describeDriveFolder(folder.name, content) }.also { DriveViewMemory.putFolderView(key, it) }
    }

    /**
     * 文件夹行在可见区域里时调用，挂起到预取完成；行离开可见区域，调用方的协程取消，请求随之作罢。
     *
     * 不知道里面有什么的文件夹补取一页文件，记下来描述文件夹行用：列表接口不给文件夹里的文件名，
     * 不预取的话文件夹要点进去一次才认得出作品名。记下的内容跨进程保留（FolderContentMemory），
     * 所以同一个文件夹只取一次。停留不到 [PREFETCH_DWELL_MILLIS] 的不取：快速滑过的一屏文件夹不该各发一个请求。
     * 搜索结果散在各处，不预取。
     *
     * 服务端没给封面的文件夹另读一次归档清单补封面（PikoDriveRepository.fetchVaultCover），解析关着时也读：
     * 内容归档后的文件夹服务端不再给封面。没进过的文件夹先取一页，才知道里面有没有清单。
     */
    suspend fun onFolderVisible(folder: FileStat) {
        // 压缩包里的文件夹不是网盘里的，列不了
        if (isSearching || !isDriveFolderId(folder.id)) return
        val known = driveRepo.knownChildContents(folder.id)
        val wantsCover = folder.thumbnailLink.isEmpty()
        // 记下的是空表时分不清真空还是只有子文件夹，海报墙要据此画空文件夹，探一下
        val wantsEmptiness = isNameParsing && known?.isEmpty() == true
        val wantsContent = known == null && (isNameParsing || wantsCover)
        if (!wantsContent && !wantsEmptiness && !wantsCover) return
        delay(PREFETCH_DWELL_MILLIS)
        if (wantsEmptiness) driveRepo.probeFolderEmptiness(folder.id)
        if (wantsContent) {
            val content = driveRepo.fetchChildContents(folder.id)
            if (content != null && isNameParsing) folderViews[folder.id] = folderView(folder, content)
        }
        if (wantsCover) driveRepo.fetchVaultCover(folder.id)
    }

    fun toggleSpoiler(fileId: String) {
        if (!revealedFileIds.remove(fileId)) revealedFileIds.add(fileId)
    }

    fun enterSelection(fileId: String? = null) {
        selectionHeldOpen = true
        if (fileId != null && fileId !in selectedFileIds) selectedFileIds.add(fileId)
    }

    fun exitSelection() {
        selectionHeldOpen = false
        selectedFileIds.clear()
        selectionAnchor = null
    }

    fun setSelected(fileId: String, selected: Boolean) {
        if (selected) {
            if (fileId !in selectedFileIds) selectedFileIds.add(fileId)
        } else {
            selectedFileIds.remove(fileId)
        }
    }

    /**
     * Shift 点选的起点：最近一次单独点选的那一行。记行而不记文件：同一个文件占两行时，从哪一行数起结果不同。
     * 换目录、退出多选后作废。
     */
    private var selectionAnchor: String? = null

    /**
     * 桌面的 Ctrl（⌘）点选：切换 [rowKey] 那一行的文件，不在多选时先进入多选。这一行成为 Shift 点选的起点。
     * 以下几个桌面的选择手势都交行的 key（[DriveListItem.File.key]），选中的是行上的文件，见 [fileRows]。
     */
    fun toggleSelected(rowKey: String) {
        val fileId = fileByRowKey[rowKey]?.id ?: return
        setSelected(fileId, fileId !in selectedFileIds)
        selectionAnchor = rowKey
        if (selectedFileIds.isEmpty()) exitSelection()
    }

    /**
     * 桌面的 Shift 点选：把起点到 [rowKey] 之间（按眼前各行的顺序，含两端）的文件全部选上，起点不动，
     * 连续 Shift 点选以同一个起点伸缩。没有起点时只选这一项，与文件管理器相同。
     */
    fun selectRange(rowKey: String) {
        val rows = fileRows
        val to = rows.indexOfFirst { it.key == rowKey }.takeIf { it >= 0 } ?: return
        val from = selectionAnchor?.let { anchor -> rows.indexOfFirst { it.key == anchor } }?.takeIf { it >= 0 }
        if (from == null) {
            setSelected(rows[to].file.id, true)
            selectionAnchor = rowKey
            return
        }
        rows.subList(minOf(from, to), maxOf(from, to) + 1).forEach { setSelected(it.file.id, true) }
    }

    /**
     * 桌面的框选：选中的换成 [base] 加上框住的各行 [boxedRows] 上的文件。拖动时每动一下调一次，[base] 是按下时已选的文件
     * （按着主修饰键开始框选时保留原来的选择，否则为空）。结果为空就退出多选。
     */
    fun selectBoxed(base: Set<String>, boxedRows: Collection<String>) {
        val next = LinkedHashSet(base).apply { boxedRows.mapNotNullTo(this) { fileByRowKey[it]?.id } }
        if (next.isEmpty()) {
            exitSelection()
            return
        }
        if (selectedFileIds.toSet() != next) {
            selectedFileIds.clear()
            selectedFileIds.addAll(next)
        }
    }

    fun toggleSelectAll() {
        val visible = displayedFiles.map { it.id }
        if (selectedFileIds.size == visible.size) {
            selectedFileIds.clear()
        } else {
            selectedFileIds.clear()
            selectedFileIds.addAll(visible)
        }
    }

    fun highlight(ids: Set<String>) {
        Snapshot.withMutableSnapshot {
            currentAnalysis?.let { revealHighlightedItems(it, ids) }
            highlightedFileIds = ids
            highlightRevision++
        }
    }

    private fun revealHighlightedItems(structure: DriveStructure, ids: Set<String>) {
        if (ids.isEmpty()) return
        if (ids.any { it in structure.foldedIds }) setShowAllFiles(true)
        structure.blocks.filter { block -> block.fileIds.any { it in ids } }.forEach { expandSection(it.id) }
    }

    fun clearHighlight() {
        highlightedFileIds = emptySet()
    }

    /**
     * 要高亮的条目常是刚写进网盘的（秒传、恢复），列表却还没有它们：存进眼前这个目录时栈没变，
     * 不会重新加载；存进别的目录时那一次加载先给缓存、再列一次，而列表接口比写入晚 0.1 到 0.8 秒
     * 才看得到新文件（2026-09-27 实测），那一次多半扑空。这里等手头的加载结束，仍缺就静默重列，
     * 按 [HIGHLIGHT_RETRY_DELAYS] 退避，齐了或换了目录（collectLatest 取消这里）即停。
     * 不改成保存后固定等一会儿再列：延迟因次而异，等短了照样扑空，等长了每次都白等。
     * 条目在子目录里（保留目录结构的秒传）时永远等不齐，重试有上限，只多花几次请求。
     */
    private suspend fun catchUpWithHighlight(ids: Set<String>, folderId: String) {
        for (wait in HIGHLIGHT_RETRY_DELAYS) {
            loadJob?.join()
            if (activeFolderId != folderId) return
            val present = files.mapTo(HashSet()) { it.id }
            if (ids.all { it in present }) return
            delay(wait)
            load(useCache = false, showRefreshing = false)
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank() || isVirtualPlace) return
        val trimmed = name.trim()
        scope.launch {
            driveRepo.createFolder(activeFolder.id, trimmed)
                .onSuccess { _messages.tryEmit("已新建文件夹") }
                .logFailure(TAG, "新建文件夹失败：在 ${activeFolder.id.ifEmpty { "根目录" }} 下")
                .onFailure { _messages.tryEmit("新建文件夹失败") }
        }
    }

    /**
     * 压缩包里的条目不在网盘里，改名、移动、删除、星标都无从做起。在这几个入口拦而不是只在按钮上：
     * Delete、F2、剪切这些键位也走到这里。
     */
    private fun refusedInArchive(ids: Collection<String>): Boolean {
        if (ids.none { ArchiveEntryId.isEntry(it) || ArchiveLocation.of(it) != null }) return false
        _messages.tryEmit("压缩包里的文件只能打开或解压")
        return true
    }

    fun rename(fileId: String, newName: String) {
        if (newName.isBlank() || refusedInArchive(listOf(fileId))) return
        val trimmed = newName.trim()
        if (VaultEntry.isVaulted(fileId)) {
            renameInVault(fileId, trimmed)
            return
        }
        val listedName = knownFile(fileId)?.name
        scope.launch {
            // 撤销要用旧名字。列表刚被别的改动刷走、还没列回来时找不到这一项，改之前向服务端查一次
            val oldName = listedName ?: driveRepo.getFileDetail(fileId).getOrNull()?.name
            driveRepo.rename(fileId, trimmed)
                .onSuccess {
                    if (oldName != null && oldName != trimmed) {
                        driveRepo.changes.record(DriveChangeJournal.Change.Rename(listOf(DriveChangeJournal.Renamed(fileId, oldName, trimmed)), "已重命名"))
                    } else {
                        _messages.tryEmit("已重命名")
                    }
                }
                .logFailure(TAG, "重命名失败：${logFile(fileId, oldName ?: trimmed)}，新名 ${trimmed.encodeToByteArray().size} 字节")
                .onFailure { _messages.tryEmit("重命名失败") }
        }
    }

    /** 加或去星标。星标只体现在列表条目的 tags 里，这一项所在的那一层由仓库通知重列。 */
    fun setStarred(file: FileStat, starred: Boolean) {
        if (refusedInArchive(listOf(file.id))) return
        if (file.isVaulted) {
            _messages.tryEmit(VAULTED_NEEDS_RESTORE)
            return
        }
        scope.launch {
            driveRepo.setStarred(listOf(file.id), starred)
                .onSuccess { _messages.tryEmit(if (starred) "已添加星标" else "已取消星标") }
                .logFailure(TAG, "修改星标失败：${logFile(file.id, file.name)}")
                .onFailure { _messages.tryEmit(if (starred) "添加星标失败" else "取消星标失败") }
        }
    }

    /** 归档条目没有文件可进回收站，删它就是从清单里移除，见 [removeFromVault]。 */
    fun moveToTrash(ids: List<String>) {
        if (refusedInArchive(ids)) return
        val (vaulted, real) = ids.partition(VaultEntry::isVaulted)
        removeFromVault(vaulted)
        if (real.isEmpty()) return
        scope.launch {
            driveRepo.trash(real)
                .onSuccess {
                    exitSelection()
                    driveRepo.changes.record(
                        DriveChangeJournal.Change.Trash(real, if (real.size == 1) "已移入回收站" else "已将 ${real.size} 项移入回收站"),
                    )
                }
                .logFailure(TAG, "移入回收站失败")
                .onFailure { _messages.tryEmit("移入回收站失败") }
        }
    }

    /**
     * [sources] 是各项原来所在的文件夹，撤销时移回去用。眼前列表里有的从列表取；剪切后换了目录再粘贴时
     * 它们已不在列表里，由剪贴板带过来，否则会被当成原本就在目标里。
     */
    fun move(ids: List<String>, targetId: String, targetName: String, sources: Map<String, String> = emptyMap()) {
        fun sourceOf(id: String) = knownFile(id)?.parentId ?: sources[id]
        // 已经在目标里的不动：拖回原处、把文件夹拖到它自己上面，服务端要么白做一次、要么拒绝
        val moving = withoutVaulted(ids).filter { it != targetId && sourceOf(it) != targetId }
        if (moving.isEmpty()) return
        val from = moving.associateWith { sourceOf(it) ?: activeFolderId }
        scope.launch {
            driveRepo.move(moving, targetId)
                .onSuccess {
                    exitSelection()
                    val summary = if (moving.size == 1) "已移至 $targetName" else "已将 ${moving.size} 项移至 $targetName"
                    driveRepo.changes.record(DriveChangeJournal.Change.Move(from, targetId, summary))
                }
                .logFailure(TAG, "移动失败")
                .onFailure { _messages.tryEmit("移动失败") }
        }
    }

    /** 撤销最近一次移动、移入回收站或重命名，见 [DriveChangeJournal]。 */
    fun undoLast(): Boolean = driveRepo.changes.undoLast()

    // 眼前列表里的那一项：全盘搜索的结果不在当前目录的列表里，两处都找
    private fun knownFile(id: String): FileStat? = displayedFiles.firstOrNull { it.id == id } ?: files.firstOrNull { it.id == id }

    /** 剪切或复制到剪贴板，照资源管理器：换个目录粘贴，见 [paste]。 */
    fun putOnClipboard(requested: List<String>, cut: Boolean) {
        val ids = withoutVaulted(requested)
        if (ids.isEmpty()) return
        driveRepo.setClipboard(DriveClipboard(ids.associateWith { knownFile(it)?.parentId ?: activeFolderId }, cut))
        val verb = if (cut) "剪切" else "复制"
        _messages.tryEmit(if (ids.size == 1) "已$verb，到目标文件夹粘贴" else "已$verb ${ids.size} 项，到目标文件夹粘贴")
    }

    /** 粘贴到眼前的文件夹：剪切的移过来（剪贴板随即清空，与资源管理器相同），复制的复制一份过来。 */
    fun paste() {
        val clip = driveRepo.clipboardFlow.value ?: return
        val target = driveRepo.folderStackFlow.value.lastOrNull()?.takeIf { isDriveFolderId(it.id) } ?: return
        val ids = clip.sources.keys.toList()
        if (clip.cut) {
            driveRepo.setClipboard(null)
            move(ids, target.id, target.name, clip.sources)
        } else {
            copy(ids, target.id, target.name)
        }
    }

    fun copy(requested: List<String>, targetId: String, targetName: String) {
        val ids = withoutVaulted(requested)
        if (ids.isEmpty()) return
        scope.launch {
            driveRepo.copy(ids, targetId)
                .onSuccess {
                    exitSelection()
                    _messages.tryEmit("已复制到 $targetName")
                }
                .logFailure(TAG, "复制失败")
                .onFailure { _messages.tryEmit("复制失败") }
        }
    }

    /**
     * 去掉归档条目并提示一句。移动、复制与剪切要的是网盘里的文件，归档条目只是清单里的一行；
     * 在这里拦而不是在各个按钮上：键盘、拖放与命令栏都走到这几个入口。
     */
    private fun withoutVaulted(ids: List<String>): List<String> {
        if (refusedInArchive(ids)) return emptyList()
        val real = ids.filterNot(VaultEntry::isVaulted)
        if (real.size < ids.size) _messages.tryEmit(VAULTED_NEEDS_RESTORE)
        return real
    }

    /** 眼前列表里的归档条目，按所在文件夹分组：清单一个文件夹一份。值是列表里的虚拟 ID。 */
    private fun vaultedByFolder(ids: Collection<String>): Map<String, Set<String>> =
        ids.filter(VaultEntry::isVaulted)
            .groupBy { knownFile(it)?.parentId ?: activeFolderId }
            .mapValues { it.value.toSet() }

    private fun entryIds(virtualIds: Collection<String>): Set<String> = virtualIds.mapNotNullTo(HashSet(), VaultEntry::entryIdOf)

    /** 从清单里去掉这几条，可以撤销。网盘里本来就没有它们的文件，这是删除归档条目的唯一含义。 */
    fun removeFromVault(ids: Collection<String>) {
        val byFolder = vaultedByFolder(ids)
        if (byFolder.isEmpty()) return
        scope.launch {
            val removed = mutableMapOf<String, List<VaultEntry>>()
            val result = runSuspendCatching {
                for ((folderId, virtualIds) in byFolder) {
                    val entryIds = entryIds(virtualIds)
                    val write = driveRepo.vault.update(folderId, VaultEdits.remove(entryIds)).getOrThrow()
                    removed[folderId] = write.before.filter { it.id in entryIds }
                }
            }
            // 部分文件夹已经改成功也要记：撤销时把已移除的那些写回去
            val count = removed.values.sumOf { it.size }
            PikoLog.i(TAG, "从归档移除：${byFolder.values.sumOf { it.size }} 项，${byFolder.size} 个文件夹，已移除 $count 项")
            if (count > 0) {
                exitSelection()
                driveRepo.changes.record(
                    DriveChangeJournal.Change.Vault(
                        removed.mapValues { (_, entries) -> VaultEdits.add(entries) },
                        if (count == 1) "已从归档移除" else "已从归档移除 $count 项",
                    ),
                )
            }
            result.reportFailure(TAG, "从归档移除") { _messages.tryEmit(it) }
        }
    }

    private fun renameInVault(fileId: String, newName: String) {
        val folderId = knownFile(fileId)?.parentId ?: activeFolderId
        val entryId = VaultEntry.entryIdOf(fileId) ?: return
        scope.launch {
            driveRepo.vault.update(folderId, VaultEdits.rename(entryId, newName))
                .onSuccess { write ->
                    val oldName = write.before.firstOrNull { it.id == entryId }?.name
                    if (oldName != null && oldName != newName) {
                        val revert = mapOf(folderId to VaultEdits.rename(entryId, oldName))
                        driveRepo.changes.record(DriveChangeJournal.Change.Vault(revert, "已重命名"))
                    } else {
                        _messages.tryEmit("已重命名")
                    }
                }
                .reportFailure(TAG, "重命名") { _messages.tryEmit(it) }
        }
    }
}

/**
 * 目录图里的一个节点。能展开的（网盘里的文件夹、能当文件夹打开的压缩包、包里的文件夹）[crumb] 的 ID 就是路径栈上
 * 那一级的 ID，点了即可压栈；其余是文件，[file] 是它在列表里的那一行，点了在它所在的文件夹里打开。
 */
class FolderMapNode(val crumb: PikoPathBreadcrumb, val isArchive: Boolean, val file: FileStat? = null) {
    val expandable: Boolean get() = file == null
}

/** 目录图里一个节点下的一层，见 [DriveScreenState.folderMapLevel]。 */
sealed interface FolderMapLevel {
    class Loaded(val nodes: List<FolderMapNode>) : FolderMapLevel

    /** 加密的压缩包，存过的密码都不对。 */
    data object NeedsPassword : FolderMapLevel

    class Failed(val message: String) : FolderMapLevel
}

/**
 * 地址栏补全的结果。[matches] 是完整路径栈（含根），末项是匹配 [partial] 的文件夹。
 * [parentFound] 为 false 时输入的上级不存在，这时的输入多半不是路径。
 */
class AddressCompletion(
    val partial: String,
    val parentFound: Boolean,
    val matches: List<List<PikoPathBreadcrumb>>,
)

private const val ADDRESS_COMPLETION_LIMIT = 50

/**
 * 地址栏的输入换成从根起的文件夹名。分隔符 / 与 \ 都认；以 .. 或 . 开头的相对当前位置，
 * 其余一律从根起，开头的 / 与「网盘」可写可不写。.. 可以叠用、也可以写在中间，退过根就停在根。
 */
fun addressPathNames(text: String, current: List<PikoPathBreadcrumb>): List<String> {
    val segments = text.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }
    val relative = !text.trimStart().startsWith('/') && segments.firstOrNull().let { it == ".." || it == "." }
    val names = if (relative) current.drop(1).mapTo(ArrayList()) { it.name } else ArrayList()
    segments.forEachIndexed { index, segment ->
        when {
            segment == ".." -> names.removeLastOrNull()
            segment == "." -> Unit
            index == 0 && !relative && segment == PikoDriveRepository.ROOT_BREADCRUMB.name -> Unit
            else -> names += segment
        }
    }
    return names
}

/** 名字含 [partial] 的，开头相同的在前，各自保持原来的顺序；[partial] 为空时全部。 */
private fun rankByName(folders: List<PikoPathBreadcrumb>, partial: String): List<PikoPathBreadcrumb> {
    if (partial.isEmpty()) return folders
    val (prefixed, rest) = folders.filter { it.name.contains(partial, ignoreCase = true) }
        .partition { it.name.startsWith(partial, ignoreCase = true) }
    return prefixed + rest
}
