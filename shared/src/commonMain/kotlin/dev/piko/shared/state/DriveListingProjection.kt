@file:JvmMultifileClass
@file:JvmName("DriveScreenStateKt")

package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.ChildFile
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.HistoryKind
import dev.piko.shared.data.ImportPreviewEntryId
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.shared.data.outsideArchives
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFolder
import dev.piko.shared.notice.PikoErrorMapper
import dev.piko.shared.presentation.TabNavigation
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.ArchivePasswordException
import io.github.nihildigit.pikpak.DriveEvent
import io.github.nihildigit.pikpak.EventPage
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.SearchHit
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import io.github.nihildigit.pikpak.thumbnailUrlOf
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网盘目录、资料库、压缩包浏览与导入草稿虚拟预览的加载、多维排序、启发式剧集/作品折叠分析、
 * `WorkHeader`/`SectionHeader` 行投影及文件夹标题解析协作者。
 */
internal class DriveListingProjection(
    private val ctx: DriveScreenContext,
    initialSortOrder: PikoFileSortOrder,
) {
    private class FolderAnalysis(
        val folderId: String?,
        val source: List<FileStat>,
        val structure: DriveStructure,
    )

    private class LibraryListing(
        val files: List<FileStat>,
        val events: Map<String, DriveEvent>,
    )

    private data class LocalSortInput(
        val folderId: String,
        val loadedFolderId: String?,
        val files: List<FileStat>,
        val remote: List<FileStat>,
        val order: PikoFileSortOrder,
        val foldersFirst: Boolean,
        val hits: List<SearchHit>,
    )

    private val projection = DriveListProjection(ctx.driveRepo)
    private val listingSource = DriveListingSource(ctx.driveRepo)
    private var loadJob: Job? = null

    private var baseFiles by mutableStateOf<List<FileStat>>(emptyList())
    private var remoteFiles by mutableStateOf<List<FileStat>>(emptyList())
    internal var optimisticRevision by mutableStateOf(0L)
        private set
    private var historyRevision by mutableStateOf(0L)

    var isLoading by mutableStateOf(true)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    var sortOrder by mutableStateOf(initialSortOrder)
        private set
    var perFolderSort by mutableStateOf(false)
        private set
    var foldersFirst by mutableStateOf(true)
        private set
    private var globalSortOrder = initialSortOrder
    private val folderSortLock = PikoLock()
    private val folderSortOrders = LinkedHashMap<String, PikoFileSortOrder>()

    private val showAllDirectories = mutableStateMapOf<String, Boolean>()
    private val expandedSections = mutableStateMapOf<String, Boolean>()
    val showAllFilesTemporarily: Boolean by derivedStateOf { showAllDirectories[ctx.activeFolderId()] == true }

    var isHeuristicFilterEnabled by mutableStateOf(true)
        private set
    var isNameParsing by mutableStateOf(true)
        private set
    var thumbnailsOnly by mutableStateOf(false)
        private set

    var loadedFolderId by mutableStateOf<String?>(null)
        private set

    var libraryEvents by mutableStateOf<Map<String, DriveEvent>>(emptyMap())
        private set
    private val libraryListings = mutableMapOf<DriveLibrary, LibraryListing>()

    var archivePasswordRequest by mutableStateOf<DriveScreenState.ArchivePasswordRequest?>(null)
        private set
    var preparingEntryId by mutableStateOf<String?>(null)
        private set
    private val previewArchiveIds = mutableSetOf<String>()

    val isArchiveInImportPreview: Boolean by derivedStateOf {
        val loc = ctx.archiveView()
        loc != null && (loc.archiveId in previewArchiveIds || ctx.activeFolderStack().any { ImportPreviewLocation.of(it.id) != null })
    }

    val files: List<FileStat> by derivedStateOf {
        historyRevision
        optimisticRevision
        val library = ctx.libraryView()
        if (ctx.isLocalPlace() || ctx.archiveView() != null || ctx.importPreviewView() != null || library?.isSession == true) {
            baseFiles
        } else {
            val projected = projection.files(baseFiles, ctx.activeFolderId(), library)
            if (library == DriveLibrary.STARRED) {
                projected
            } else if (library == DriveLibrary.HISTORY || library == DriveLibrary.RECENT) {
                val kind = if (library == DriveLibrary.HISTORY) HistoryKind.PLAY else HistoryKind.RECENT
                projected.filterNot { file -> libraryEvents[file.id]?.let { ctx.historyActions.session.hidden(kind, it.id) } == true }
            } else {
                projected
            }
        }
    }

    private var analysis by mutableStateOf<FolderAnalysis?>(null)

    internal val currentAnalysis: DriveStructure? by derivedStateOf {
        val activeFolderId = ctx.activeFolderId()
        analysis?.takeIf {
            it.source === files || (it.folderId != null && it.folderId == loadedFolderId && it.folderId == activeFolderId)
        }?.structure
    }

    val isDisplayStructureReady: Boolean by derivedStateOf {
        ctx.search.isSearching || (ctx.isVirtualPlace() && !ctx.isLocalPlace() && !ctx.usesFolderStructure()) || currentAnalysis != null
    }

    private val _folderViews = mutableStateMapOf<String, DriveFolderView>()
    val folderViews: Map<String, DriveFolderView> get() = _folderViews

    private val isFoldingActive: Boolean by derivedStateOf {
        val folded = currentAnalysis?.foldedIds ?: return@derivedStateOf false
        isHeuristicFilterEnabled && isNameParsing && (!ctx.isVirtualPlace() || ctx.isLocalPlace()) &&
            folded.size < files.size && isFoldingScope(files)
    }

    val potentialHiddenCount: Int by derivedStateOf {
        if (isFoldingActive) currentAnalysis?.foldedIds?.size ?: 0 else 0
    }

    fun updateThumbnailsOnly(value: Boolean) {
        if (value == thumbnailsOnly) return
        thumbnailsOnly = value
        if (ctx.selection.isSelectionMode) ctx.selection.exitSelection()
    }

    private fun isHiddenByThumbnails(file: FileStat): Boolean =
        thumbnailsOnly && !file.isFolder && file.thumbnailLink.isEmpty()

    val displayItems: List<DriveListItem> by derivedStateOf {
        if (thumbnailsOnly) hideFiles(unfilteredItems, ::isHiddenByThumbnails) else unfilteredItems
    }

    private val unfilteredItems: List<DriveListItem> by derivedStateOf {
        val structure = currentAnalysis
        val hideFolded = isFoldingActive && !showAllFilesTemporarily
        val filter = ctx.search.typeFilter
        when {
            filter != null -> ctx.search.searchedFiles
                .filter { !it.isFolder && it.fileCategory() == filter }
                .map { DriveListItem.File(it, null) }
            ctx.search.isGlobalSearchActive || ctx.search.searchQuery.isNotBlank() ->
                ctx.search.searchedFiles.map { DriveListItem.File(it, null) }
            ctx.usesFolderStructure() -> folderSessionItems { block -> isBlockExpanded(block) }
            ctx.libraryView()?.isSession == true -> sessionItems { blockId -> isSessionGroupExpanded(blockId) }
            structure == null || (ctx.isVirtualPlace() && !ctx.isLocalPlace() && ctx.importPreviewView() == null) ->
                files.map { DriveListItem.File(it, null) }
            else -> projection.items(files, structure, hideFolded, isNameParsing, foldersFirst, sortOrder, ::isBlockExpanded)
        }
    }

    private val isParsedProjection: Boolean by derivedStateOf {
        !ctx.search.isSearching &&
            (!ctx.isVirtualPlace() || ctx.isLocalPlace() || ctx.importPreviewView() != null) &&
            isNameParsing &&
            currentAnalysis != null
    }

    internal val fileRows: List<DriveListItem.File> by derivedStateOf {
        val structure = currentAnalysis
        val rows = when {
            isParsedProjection && structure != null ->
                projection.items(files, structure, isFoldingActive && !showAllFilesTemporarily, isNameParsing, foldersFirst, sortOrder) { true }
            !ctx.search.isSearching && ctx.usesFolderStructure() -> folderSessionItems { true }
            !ctx.search.isSearching && ctx.libraryView()?.isSession == true -> sessionItems { true }
            else -> displayItems
        }
        rows.filterIsInstance<DriveListItem.File>().filterNot { isHiddenByThumbnails(it.file) }
    }

    val displayedFiles: List<FileStat> by derivedStateOf {
        val shown = fileRows.map { it.file }
        val hosts = if (isParsedProjection) currentAnalysis?.attachedTo.orEmpty() else emptyMap()
        val shownIds = shown.mapTo(HashSet()) { it.id }
        val attachments = files.filter { file -> hosts[file.id]?.let { it in shownIds } == true && !isHiddenByThumbnails(file) }
        (shown + attachments).distinctBy { it.id }
    }

    internal val filesById: Map<String, FileStat> by derivedStateOf {
        if (files.isEmpty()) emptyMap() else files.associateBy { it.id }
    }

    private val displayedFilesById: Map<String, FileStat> by derivedStateOf {
        if (displayedFiles.isEmpty()) emptyMap() else displayedFiles.associateBy { it.id }
    }

    private var cachedSupplementalSource: List<FileStat>? = null
    private var cachedSupplementalById: Map<String, FileStat> = emptyMap()

    private fun supplementalFile(id: String): FileStat? {
        val list = ctx.supplementalFiles()
        if (list.isEmpty()) return null
        val byId = if (list === cachedSupplementalSource) {
            cachedSupplementalById
        } else {
            list.associateBy { it.id }.also {
                cachedSupplementalSource = list
                cachedSupplementalById = it
            }
        }
        return byId[id]
    }

    fun knownFile(id: String): FileStat? = displayedFilesById[id]
        ?: filesById[id]
        ?: supplementalFile(id)
        ?: ctx.driveRepo.knownFile(id)

    val sectionHeaders: List<IndexedValue<DriveListItem.SectionHeader>> by derivedStateOf {
        displayItems.withIndex().mapNotNull { (index, item) ->
            (item as? DriveListItem.SectionHeader)?.let { IndexedValue(index, it) }
        }
    }

    private fun isBlockExpanded(block: DriveBlock): Boolean =
        expandedSections[expandKey(block.id)] ?: block.defaultExpanded

    private fun expandKey(blockId: String): String = "${ctx.activeFolderId()}|$blockId"

    fun toggleSection(blockId: String) {
        val key = expandKey(blockId)
        val current = expandedSections[key]
            ?: currentAnalysis?.blocks?.firstOrNull { it.id == blockId }?.defaultExpanded
            ?: true
        expandedSections[key] = !current
    }

    fun expandSection(blockId: String) {
        expandedSections[expandKey(blockId)] = true
    }

    fun setShowAllFiles(value: Boolean) {
        showAllDirectories[ctx.activeFolderId()] = value
    }

    private val rowViews: Map<String, DriveFileView> by derivedStateOf {
        fileRows.mapNotNull { row -> row.view?.let { row.file.id to it } }.toMap()
    }

    fun folderView(file: FileStat): DriveFolderView? =
        if (isNameParsing && file.isFolder) _folderViews[file.id] else null

    fun itemName(file: FileStat): DriveItemName =
        driveItemName(file, rowViews[file.id], folderView(file))

    internal fun listedName(file: FileStat): DriveItemName =
        driveItemName(file, if (isNameParsing) currentAnalysis?.views?.get(file.id) else null, folderView(file))

    fun fileView(fileId: String): DriveFileView? =
        if (!isNameParsing) null else currentAnalysis?.views?.get(fileId)

    internal suspend fun awaitLoad() {
        loadJob?.join()
    }

    internal fun remoteFilesSnapshot(): List<Pair<String, String>> =
        remoteFiles.map { it.id to it.modifiedTime }

    fun applyEffectiveSortOnFolderChange() {
        sortOrder = effectiveSortOrderFor(ctx.activeFolderId())
    }

    private fun withoutInternalFolders(folderId: String, listing: List<FileStat>): List<FileStat> =
        listing.filterNot { isPikoInternalFolder(it, folderId) }

    internal fun importPreviewFilesFor(folderId: String): List<FileStat> {
        val tabNav = ctx.navigation as? TabNavigation
        val ownerTab = ctx.addLinkOwnerTabId()
        if (tabNav != null && ownerTab != null && tabNav.tabId != ownerTab) return emptyList()
        val draft = ctx.instantSession?.state ?: return emptyList()
        val targetId = draft.target?.id ?: return emptyList()
        val entries = draft.projectedSaveEntries
        if (entries.isEmpty()) return emptyList()
        val previewLoc = ImportPreviewLocation.of(folderId)
        val prefix = when {
            previewLoc != null -> if (previewLoc.targetFolderId == targetId) "${previewLoc.path.trim('/')}/" else return emptyList()
            folderId == targetId -> ""
            else -> return emptyList()
        }
        val childFolders = LinkedHashMap<String, MutableList<Pair<Int, ResolvedFile>>>()
        val directFiles = mutableListOf<FileStat>()
        for ((sourceIndex, resolved) in entries) {
            val clean = resolved.path.trim('/')
            if (clean.isEmpty()) continue
            val rest = if (prefix.isEmpty()) {
                clean
            } else {
                if (!clean.startsWith(prefix)) continue
                clean.removePrefix(prefix)
            }
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val childName = rest.substring(0, slash)
                if (childName.isNotEmpty()) childFolders.getOrPut(childName) { mutableListOf() }.add(sourceIndex to resolved)
            } else {
                val ext = rest.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                val gcid = resolved.gcid.orEmpty()
                val base = FileStat(
                    kind = FileKind.FILE,
                    id = ImportPreviewEntryId.of(sourceIndex, resolved.gcid, resolved.size, rest),
                    parentId = folderId,
                    name = rest,
                    size = resolved.size.toString(),
                    fileExtension = ext,
                    hash = gcid,
                )
                directFiles += when {
                    gcid.isBlank() -> base
                    base.isPlayableVideo() -> base.copy(thumbnailLink = videoThumbnailUrlOf(gcid))
                    base.fileCategory() == FileCategory.IMAGE -> base.copy(thumbnailLink = imageThumbnailUrlOf(gcid))
                    else -> base
                }
            }
        }
        val folderStats = childFolders.map { (name, children) ->
            val subPath = if (prefix.isEmpty()) name else "$prefix$name"
            val coverGcid = children.map { it.second }.firstOrNull { it.gcid != null && it.name.isPlayableVideo() }?.gcid
            FileStat(
                kind = FileKind.FOLDER,
                id = ImportPreviewLocation(targetId, subPath).id,
                parentId = folderId,
                name = name,
                size = children.sumOf { it.second.size }.toString(),
                thumbnailLink = if (coverGcid != null) thumbnailUrlOf(coverGcid) else "",
            )
        }
        return ctx.driveRepo.sortFilesFast(folderStats + directFiles, sortOrder, folderId, foldersFirst = foldersFirst)
    }

    private fun withImportPreviews(folderId: String, realFiles: List<FileStat>): List<FileStat> {
        val virtuals = importPreviewFilesFor(folderId)
        if (virtuals.isEmpty()) return realFiles
        val (virtualFolders, virtualFiles) = virtuals.partition(FileStat::isFolder)
        val (realFolders, realFilesOnly) = realFiles.partition(FileStat::isFolder)
        val combined = virtualFolders + realFolders + virtualFiles + realFilesOnly
        return if (foldersFirst) combined else ctx.driveRepo.sortFilesFast(combined, sortOrder, folderId, foldersFirst = false)
    }

    fun load(refresh: Boolean = false) = load(useCache = !refresh, showRefreshing = refresh)

    internal fun load(useCache: Boolean, showRefreshing: Boolean) {
        if (!ctx.canAcceptAction()) return
        val folderId = ctx.activeFolder().id
        ctx.libraryView()?.let { library ->
            loadLibrary(library, useCache, showRefreshing)
            return
        }
        ArchiveLocation.of(folderId)?.let { location ->
            loadArchive(location, useCache, showRefreshing)
            return
        }
        if (ImportPreviewLocation.of(folderId) != null) {
            loadJob?.cancel()
            val virtualList = importPreviewFilesFor(folderId)
            loadJob = ctx.scope.launch {
                val structure = DriveAnalysisCache.analyze(virtualList)
                Snapshot.withMutableSnapshot {
                    baseFiles = virtualList
                    loadedFolderId = folderId
                    analysis = FolderAnalysis(folderId, virtualList, structure)
                    loadError = null
                    isLoading = false
                    isRefreshing = false
                }
            }
            return
        }
        if (showRefreshing && folderId.isEmpty()) {
            ctx.driveRepo.onRootPullRefresh()
        }
        if (showRefreshing) {
            isRefreshing = true
        }
        loadJob?.cancel()
        loadJob = ctx.scope.launch {
            listingSource.observe(folderId, sortOrder, useCache).collect { result ->
                if (ctx.activeFolderId() != folderId) return@collect
                when (result) {
                    DriveListingResult.Loading -> if (useCache && !showRefreshing) isLoading = true
                    DriveListingResult.Gone -> {
                        isLoading = false
                        isRefreshing = false
                        if (ctx.navigateUp()) {
                            ctx.notify(
                                tr(
                                    "文件夹已不存在，已返回上一级",
                                    "Folder no longer exists; returned to parent",
                                    "フォルダーが存在しないため、上の階層に戻りました",
                                    "폴더가 없어 상위 폴더로 돌아갔습니다",
                                    "資料夾已不存在，已返回上一層",
                                ),
                            )
                        }
                    }
                    is DriveListingResult.Failure -> {
                        PikoLog.w(DRIVE_SCREEN_TAG, "Failed to load drive folder ${logFolder(folderId)} (useCache=$useCache, refresh=$showRefreshing)", result.error)
                        loadError = result.error.message ?: tr(
                            "读取网盘失败",
                            "Failed to load drive",
                            "ドライブを読み込めませんでした",
                            "드라이브를 불러오지 못했습니다",
                            "讀取雲端硬碟失敗",
                        )
                        isLoading = false
                        isRefreshing = false
                        ctx.notify(tr("加载失败", "Failed to load", "読み込みに失敗しました", "불러오지 못했습니다", "載入失敗"))
                    }
                    is DriveListingResult.Content -> {
                        val order = sortOrder
                        val first = foldersFirst
                        val (raw, merged, structure) = withContext(Dispatchers.Default) {
                            val sorted = ctx.driveRepo.sortFiles(withoutInternalFolders(folderId, result.files), order, folderId, foldersFirst = first)
                            val visible = withImportPreviews(folderId, sorted)
                            Triple(sorted, visible, DriveAnalysisCache.analyze(visible))
                        }
                        if (ctx.activeFolderId() != folderId) return@collect
                        Snapshot.withMutableSnapshot {
                            remoteFiles = raw
                            baseFiles = merged
                            loadedFolderId = folderId
                            ctx.selection.revealHighlightedItems(structure, ctx.selection.highlightedFileIds)
                            analysis = FolderAnalysis(folderId, merged, structure)
                            isLoading = false
                            if (!result.cached) {
                                loadError = null
                                isRefreshing = false
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadLibrary(library: DriveLibrary, useCache: Boolean, showRefreshing: Boolean) {
        if (!ctx.canAcceptAction()) return
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
        loadJob = ctx.scope.launch {
            fetchLibrary(library)
                .onSuccess { listing ->
                    libraryListings[library] = listing
                    showLibrary(library, listing)
                    loadError = null
                }
                .logFailure(DRIVE_SCREEN_TAG, "Failed to load drive library ${library.name}")
                .onFailure {
                    loadError = it.message ?: tr(
                        "读取${library.title}失败",
                        "Failed to load ${library.title}",
                        "${library.title}を読み込めませんでした",
                        "${library.title} 불러오기 실패",
                        "讀取${library.title}失敗",
                    )
                    ctx.notify(tr("加载失败", "Failed to load", "読み込みに失敗しました", "불러오지 못했습니다", "載入失敗"))
                }
            isLoading = false
            isRefreshing = false
        }
    }

    fun openArchive(file: FileStat) {
        if (ctx.archives == null) return
        if (file.hash.isEmpty()) {
            ctx.notify(
                if (file.isImportPreview) {
                    tr(
                        "未收录的压缩包需先通过离线下载保存至网盘",
                        "Save this unindexed archive via cloud download before opening",
                        "未収録の圧縮ファイルは、先にクラウドダウンロードでドライブに保存してください",
                        "미등록 압축 파일은 먼저 클라우드 다운로드로 드라이브에 저장해 주세요",
                        "未收錄的壓縮檔需先透過離線下載儲存至雲端硬碟",
                    )
                } else {
                    tr(
                        "文件正在校验，请稍后重试",
                        "File verification in progress. Try again later",
                        "ファイルを検証中です。しばらくしてから再試行してください",
                        "파일을 확인 중입니다. 잠시 후 다시 시도해 주세요",
                        "檔案正在驗證，請稍後重試",
                    )
                },
            )
            return
        }
        if (ImportPreviewEntryId.isEntry(file.id)) {
            val resolved = ImportPreviewEntryId.resolvedFileOf(file.id)
            val draft = ctx.instantSession?.state
            if (resolved == null || draft == null) {
                ctx.notify(
                    tr(
                        "未收录的压缩包需先通过离线下载保存至网盘",
                        "Save this unindexed archive via cloud download before opening",
                        "未収録の圧縮ファイルは、先にクラウドダウンロードでドライブに保存してください",
                        "미등록 압축 파일은 먼저 클라우드 다운로드로 드라이브에 저장해 주세요",
                        "未收錄的壓縮檔需先透過離線下載儲存至雲端硬碟",
                    ),
                )
                return
            }
            if (preparingEntryId != null) return
            preparingEntryId = file.id
            val expectedFolderId = ctx.activeFolderId()
            ctx.scope.launch {
                try {
                    draft.leaseForArchiveBrowse(resolved)
                        .logFailure(DRIVE_SCREEN_TAG, "Failed to lease import-preview archive for browsing (gcid=${file.hash.take(8)}, bytes=${resolved.size})")
                        .onSuccess { leasedFileId ->
                            previewArchiveIds += leasedFileId
                            if (ctx.activeFolderId() == expectedFolderId && ctx.canAcceptAction()) {
                                ctx.openFolder(ArchiveLocation(leasedFileId, file.hash, "").id, file.name)
                            }
                        }
                        .onFailure {
                            ctx.notify(
                                PikoErrorMapper.failure(
                                    tr("打开压缩包", "Open archive", "圧縮ファイルを開く", "압축 파일 열기", "開啟壓縮檔"),
                                    it,
                                ),
                            )
                        }
                } finally {
                    preparingEntryId = null
                }
            }
            return
        }
        ctx.openFolder(ArchiveLocation(file.id, file.hash, "").id, file.name)
    }

    private fun loadArchive(
        location: ArchiveLocation,
        useCache: Boolean,
        showRefreshing: Boolean,
        password: String? = null,
    ) {
        if (!ctx.canAcceptAction()) return
        val browser = ctx.archives ?: return
        archivePasswordRequest = null
        val cached = if (useCache) browser.cached(location) else null
        when {
            cached != null -> {
                baseFiles = cached
                loadedFolderId = location.id
                isLoading = false
            }
            showRefreshing -> isRefreshing = true
            else -> isLoading = true
        }
        loadJob?.cancel()
        loadJob = ctx.scope.launch {
            browser.list(location, password)
                .onSuccess {
                    baseFiles = it
                    loadedFolderId = location.id
                    loadError = null
                }
                .onFailure { error ->
                    if (error is ArchivePasswordException) {
                        baseFiles = emptyList()
                        loadedFolderId = location.id
                        archivePasswordRequest = DriveScreenState.ArchivePasswordRequest(archiveName(), incorrect = password != null)
                    } else {
                        PikoLog.w(
                            DRIVE_SCREEN_TAG,
                            "Failed to list archive ${location.archiveId} (${if (location.path.isEmpty()) "root" else "subpath"}, hasPassword=${password != null})",
                            error,
                        )
                        if (cached == null) {
                            baseFiles = emptyList()
                            loadedFolderId = location.id
                        }
                        loadError = archiveFailure(error)
                        ctx.notify(loadError!!)
                    }
                }
            isLoading = false
            isRefreshing = false
        }
    }

    fun submitArchivePassword(password: String) {
        val location = ctx.archiveView() ?: return
        loadArchive(location, useCache = false, showRefreshing = false, password = password)
    }

    fun dismissArchivePassword() {
        archivePasswordRequest = null
        val stack = ctx.navigation.folderStackFlow.value
        val firstArchive = stack.indexOfFirst { ArchiveLocation.of(it.id) != null }
        if (firstArchive > 0) ctx.navigation.popToBreadcrumb(firstArchive - 1)
    }

    suspend fun prepareArchiveEntry(file: FileStat): FileStat? {
        if (!ArchiveEntryId.isEntry(file.id)) return file
        val browser = ctx.archives ?: return null
        if (preparingEntryId != null) return null
        preparingEntryId = file.id
        try {
            val location = ctx.archiveView()
            val archiveId = ArchiveEntryId.pathOf(file.id)?.first ?: location?.archiveId ?: "unknown"
            return browser.prepare(file, files)
                .onSuccess {
                    if (location != null && ctx.archiveView() == location) browser.cached(location)?.let { baseFiles = it }
                }
                .logFailure(DRIVE_SCREEN_TAG, "Failed to prepare entry in archive $archiveId (bytes=${file.sizeBytes})")
                .onFailure {
                    ctx.notify(
                        tr(
                            "无法打开：${archiveFailure(it)}",
                            "Unable to open: ${archiveFailure(it)}",
                            "開けません：${archiveFailure(it)}",
                            "열 수 없습니다: ${archiveFailure(it)}",
                            "無法開啟：${archiveFailure(it)}",
                        ),
                    )
                }
                .getOrNull()
        } finally {
            preparingEntryId = null
        }
    }

    fun currentArchive(): Pair<FileStat, String>? {
        val location = ctx.archiveView() ?: return null
        val file = FileStat(kind = FileKind.FILE, id = location.archiveId, name = archiveName(), hash = location.gcid)
        return file to ctx.archives?.password(location.archiveId).orEmpty()
    }

    fun archivePaths(items: List<FileStat>): List<String> = items.mapNotNull { item ->
        ArchiveLocation.of(item.id)?.path ?: ArchiveEntryId.pathOf(item.id)?.second
    }

    private fun archiveName(): String {
        val stack = ctx.navigation.folderStackFlow.value
        val firstArchive = stack.indexOfFirst { ArchiveLocation.of(it.id) != null }
        return if (firstArchive >= 0) stack[firstArchive].name else tr("压缩包", "Archive", "圧縮ファイル", "압축 파일", "壓縮檔")
    }

    internal fun archiveFailure(error: Throwable): String = PikoErrorMapper.archive(error)

    suspend fun subfoldersOf(folderId: String): Result<List<PikoPathBreadcrumb>> {
        if (ctx.usesFolderStructure()) {
            val sessionFiles = ctx.libraryView()?.let(ctx.sessionListing)?.filesInFolder(folderId).orEmpty()
            return Result.success(sessionFiles.filter { it.isFolder }.map { PikoPathBreadcrumb(it.id, it.name) })
        }
        val previewFolders = importPreviewFilesFor(folderId).filter { it.isFolder }.map { PikoPathBreadcrumb(it.id, it.name) }
        if (ImportPreviewLocation.of(folderId) != null) return Result.success(previewFolders)
        if (ArchiveLocation.of(folderId) == null) return ctx.driveRepo.subfolders(folderId).map { previewFolders + it }
        return when (val level = folderMapLevel(folderId)) {
            is FolderMapLevel.Loaded -> Result.success(level.nodes.filter { it.expandable }.map { it.crumb })
            FolderMapLevel.NeedsPassword -> Result.failure(IllegalStateException(tr("需要密码", "Password required", "パスワードが必要です", "비밀번호가 필요합니다", "需要密碼")))
            is FolderMapLevel.Failed -> Result.failure(IllegalStateException(level.message))
        }
    }

    suspend fun folderMapLevel(id: String): FolderMapLevel {
        if (ctx.usesFolderStructure()) {
            val sessionFiles = ctx.libraryView()?.let(ctx.sessionListing)?.filesInFolder(id).orEmpty()
            return FolderMapLevel.Loaded(sessionFiles.map { file ->
                FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false, file = file.takeUnless { it.isFolder })
            })
        }
        val previewNodes = importPreviewFilesFor(id).map { file ->
            FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false, file = file.takeUnless { it.isFolder })
        }
        if (ImportPreviewLocation.of(id) != null) return FolderMapLevel.Loaded(previewNodes)
        ArchiveLocation.of(id)?.let { location ->
            val browser = ctx.archives ?: return FolderMapLevel.Loaded(emptyList())
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
        return ctx.driveRepo.folderMapLevel(id).fold(
            onSuccess = { loaded ->
                FolderMapLevel.Loaded(previewNodes + loaded.map { file ->
                    when {
                        file.isFolder -> FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false)
                        ctx.archives != null && file.isExtractableArchive && file.hash.isNotEmpty() ->
                            FolderMapNode(PikoPathBreadcrumb(ArchiveLocation(file.id, file.hash, "").id, file.name), isArchive = true)
                        else -> FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false, file = file)
                    }
                })
            },
            onFailure = { error ->
                PikoLog.w(DRIVE_SCREEN_TAG, "Failed to load folder map level for ${logFolder(id)}", error)
                FolderMapLevel.Failed(tr("无法加载此文件夹，请检查网络连接", "Unable to load this folder. Check your network connection", "このフォルダーを読み込めません。ネットワーク接続を確認して再試行してください", "이 폴더를 불러올 수 없습니다. 네트워크 연결을 확인해 주세요", "無法載入此資料夾，請檢查網路連線"))
            },
        )
    }

    private fun showLibrary(library: DriveLibrary, listing: LibraryListing) = Snapshot.withMutableSnapshot {
        baseFiles = listing.files
        if (!ctx.search.isGlobalSearchActive) {
            ctx.selection.retainSelected(listing.files.mapTo(HashSet()) { it.id })
        }
        libraryEvents = listing.events
        loadedFolderId = library.id
    }

    private suspend fun fetchLibrary(library: DriveLibrary): Result<LibraryListing> = when (library) {
        DriveLibrary.STARRED -> ctx.driveRepo.starredFiles().map { LibraryListing(it, emptyMap()) }
        DriveLibrary.TRASH -> ctx.driveRepo.trashFiles().map { trashed ->
            if (ctx.driveRepo.reconcileTrashListing(trashed)) {
                ctx.notify(
                    tr(
                        "项目较多，服务端正在后台清理，存储空间将稍后释放",
                        "The server is cleaning up in the background. Storage space will be freed shortly",
                        "項目数が多いため、サーバーがバックグラウンドで削除しています。空き容量は順次反映されます",
                        "항목이 많아 서버에서 백그라운드로 정리 중입니다. 저장 공간이 곧 확보됩니다",
                        "項目較多，伺服器正在背景清理，儲存空間將稍後釋放",
                    ),
                )
            }
            LibraryListing(trashed, emptyMap())
        }
        DriveLibrary.RECENT -> ctx.driveRepo.recentlyAdded().map(::eventListing)
        DriveLibrary.HISTORY -> ctx.driveRepo.playHistory().map(::eventListing)
        DriveLibrary.DUPLICATES -> error("${library.title}的内容来自会话，不经网络")
    }

    private fun showSession(library: DriveLibrary) {
        baseFiles = ctx.sessionListing(library)?.files.orEmpty()
        libraryEvents = emptyMap()
        loadedFolderId = ctx.activeFolderId()
        loadError = null
        isLoading = false
    }

    private fun isSessionGroupExpanded(blockId: String): Boolean =
        expandedSections[expandKey(blockId)] ?: true

    private fun sessionItems(isExpanded: (blockId: String) -> Boolean): List<DriveListItem> =
        ctx.libraryView()?.let(ctx.sessionListing)?.items(isExpanded).orEmpty().map { item ->
            if (!isNameParsing && item is DriveListItem.File) DriveListItem.File(item.file, null, item.key) else item
        }

    private fun folderSessionItems(isExpanded: (DriveBlock) -> Boolean): List<DriveListItem> {
        val listing = ctx.libraryView()?.let(ctx.sessionListing) ?: return emptyList()
        val structure = currentAnalysis
        if (!isNameParsing || structure == null) return files.map { DriveListItem.File(it, null) }
        val items = projection.items(
            files,
            structure,
            hideFolded = false,
            parseNames = true,
            foldersFirst = foldersFirst,
            order = sortOrder,
            isExpanded = isExpanded,
        )
        val rows = items.filterIsInstance<DriveListItem.File>().mapTo(HashSet()) { it.file.id }
        val attachmentSectionIds = structure.attachmentBlocks.flatMapTo(HashSet()) { it.fileIds }
        val related = files
            .filter { it.id !in rows && it.id !in attachmentSectionIds && it.id in structure.attachedTo }
            .groupBy { structure.attachedTo[it.id] }
        val groupedIds = structure.blocks.flatMapTo(HashSet()) { it.fileIds } + structure.secondaryIds + structure.attachedTo.keys
        return buildList {
            items.forEach { item ->
                add(item)
                if (item is DriveListItem.File) {
                    related[item.file.id].orEmpty().forEach { file -> add(DriveListItem.File(file, structure.views[file.id])) }
                }
            }
            files.filter { !it.isFolder && it.id !in groupedIds && it.id !in rows }
                .forEach { add(DriveListItem.File(it, structure.views[it.id])) }
        }.map { if (it is DriveListItem.File) listing.decorate(it) else it }
    }

    private fun eventListing(page: EventPage): LibraryListing {
        val live = page.events.mapNotNull { event ->
            event.file?.takeIf { !it.trashed && !VaultStore.looksLikeManifest(it) && !VaultStore.isRecoveryFile(it) }?.let { it to event }
        }.distinctBy { it.first.id }
        return LibraryListing(live.map { it.first }, live.associate { (file, event) -> file.id to event })
    }

    private fun usesPerFolderSort(folderId: String = ctx.activeFolderId()): Boolean =
        perFolderSort && isDriveFolderId(folderId) && ctx.libraryView() == null && ctx.archiveView() == null && !ctx.search.isGlobalSearchActive

    private fun effectiveSortOrderFor(folderId: String): PikoFileSortOrder =
        if (perFolderSort && isDriveFolderId(folderId) && ctx.libraryView() == null && ctx.archiveView() == null) {
            withPikoLock(folderSortLock) { folderSortOrders[folderId] } ?: globalSortOrder
        } else {
            globalSortOrder
        }

    fun changeSortOrder(order: PikoFileSortOrder) {
        val activeFolderId = ctx.activeFolderId()
        if (usesPerFolderSort(activeFolderId)) {
            val serialized = withPikoLock(folderSortLock) {
                folderSortOrders.remove(activeFolderId)
                folderSortOrders[activeFolderId] = order
                while (folderSortOrders.size > MAX_FOLDER_SORT_ENTRIES) {
                    val eldest = folderSortOrders.keys.firstOrNull() ?: break
                    folderSortOrders.remove(eldest)
                }
                encodeFolderSortOrders(folderSortOrders)
            }
            ctx.scope.launch { ctx.preferences.saveFolderSortOrders(serialized) }
        } else {
            globalSortOrder = order
        }
        if (order == sortOrder && !ctx.search.isGlobalSearchActive) return
        if (ctx.search.isGlobalSearchActive) {
            sortOrder = order
            ctx.search.reorderGlobalHits(order, foldersFirst)
        } else if (loadedFolderId != activeFolderId || ctx.archiveView() != null || ctx.libraryView()?.isSession == true) {
            sortOrder = order
            load()
        } else {
            val nextRemote = ctx.driveRepo.sortFilesFast(remoteFiles, order, activeFolderId, foldersFirst = foldersFirst)
            Snapshot.withMutableSnapshot {
                sortOrder = order
                remoteFiles = nextRemote
                baseFiles = if (importPreviewFilesFor(activeFolderId).isEmpty()) {
                    ctx.driveRepo.sortFilesFast(baseFiles, order, activeFolderId, foldersFirst = foldersFirst)
                } else {
                    withImportPreviews(activeFolderId, nextRemote)
                }
            }
        }
    }

    fun updateFoldersFirst(enabled: Boolean) {
        applyFoldersFirst(enabled)
        ctx.scope.launch { ctx.preferences.setFoldersFirst(enabled) }
    }

    private fun applyFoldersFirst(enabled: Boolean) {
        if (foldersFirst == enabled) return
        if (ctx.archiveView() != null || ctx.libraryView()?.isSession == true) {
            foldersFirst = enabled
            return
        }
        val activeFolderId = ctx.activeFolderId()
        val nextRemote = ctx.driveRepo.sortFilesFast(remoteFiles, sortOrder, activeFolderId, foldersFirst = enabled)
        Snapshot.withMutableSnapshot {
            foldersFirst = enabled
            remoteFiles = nextRemote
            baseFiles = if (importPreviewFilesFor(activeFolderId).isEmpty()) {
                ctx.driveRepo.sortFilesFast(baseFiles, sortOrder, activeFolderId, foldersFirst = enabled)
            } else {
                withImportPreviews(activeFolderId, nextRemote)
            }
        }
        ctx.search.reorderGlobalHits(sortOrder, enabled)
    }

    fun setPerFolderSortEnabled(enabled: Boolean) {
        if (perFolderSort == enabled) return
        perFolderSort = enabled
        val activeFolderId = ctx.activeFolderId()
        if (enabled && isDriveFolderId(activeFolderId) && ctx.libraryView() == null && ctx.archiveView() == null) {
            val serialized = withPikoLock(folderSortLock) {
                if (activeFolderId !in folderSortOrders) {
                    folderSortOrders[activeFolderId] = sortOrder
                    encodeFolderSortOrders(folderSortOrders)
                } else {
                    null
                }
            }
            if (serialized != null) {
                ctx.scope.launch { ctx.preferences.saveFolderSortOrders(serialized) }
            }
            val desired = effectiveSortOrderFor(activeFolderId)
            if (sortOrder != desired) {
                sortOrder = desired
                load()
            }
        } else if (!enabled) {
            globalSortOrder = sortOrder
        }
        ctx.scope.launch { ctx.preferences.setPerFolderSort(enabled) }
    }

    private suspend fun describeFolders(folders: List<Pair<FileStat, List<ChildFile>?>>) {
        folders.forEach { (folder, content) -> _folderViews[folder.id] = computeFolderView(folder, content) }
    }

    private suspend fun computeFolderView(folder: FileStat, content: List<ChildFile>?): DriveFolderView {
        while (true) {
            val revision = PikoLocale.revision
            val cacheGeneration = DriveAnalysisCache.generation
            val language = PikoLocale.effectiveLanguage
            val key = DriveAnalysisCache.folderKey(folder, content, language)
            DriveAnalysisCache.folderView(key)?.let { return it }
            val computed = withContext(Dispatchers.Default) { describeDriveFolder(folder.name, content) }
            if (PikoLocale.revision == revision && PikoLocale.effectiveLanguage == language) {
                DriveAnalysisCache.putFolderView(key, computed, cacheGeneration)
                return computed
            }
        }
    }

    suspend fun onFolderVisible(folder: FileStat) {
        if (ctx.search.isSearching || ctx.usesFolderStructure() || folder.trashed ||
            ctx.libraryView() == DriveLibrary.TRASH || !isDriveFolderId(folder.id)
        ) {
            return
        }
        val known = ctx.driveRepo.knownChildContents(folder.id)
        val wantsCover = folder.thumbnailLink.isEmpty()
        val wantsEmptiness = isNameParsing && known?.isEmpty() == true
        val wantsContent = known == null && (isNameParsing || wantsCover)
        if (!wantsContent && !wantsEmptiness && !wantsCover) return
        delay(PREFETCH_DWELL_MILLIS)
        if (wantsEmptiness) ctx.driveRepo.probeFolderEmptiness(folder.id)
        if (wantsContent) {
            val content = ctx.driveRepo.fetchChildContents(folder.id)
            if (content != null && isNameParsing) _folderViews[folder.id] = computeFolderView(folder, content)
        }
        if (wantsCover) ctx.driveRepo.fetchVaultCover(folder.id)
    }

    private fun isAffectedBy(change: DriveChange): Boolean = when {
        ctx.libraryView()?.isSession == true || ctx.archiveView() != null || ctx.importPreviewView() != null -> false
        ctx.libraryView() != null -> true
        else -> change.affects(ctx.activeFolderId())
    }

    fun start() {
        val initialRevision = ctx.driveRepo.clientRevision.value
        ctx.scope.launch {
            var previous = initialRevision
            ctx.driveRepo.clientRevision.collect { revision ->
                if (revision != previous && ctx.canAcceptAction()) {
                    libraryListings.clear()
                    if (ctx.archiveView() != null) load()
                }
                previous = revision
            }
        }
        var previousHistoryRevisions = ctx.historyEditSession.revisions.value
        ctx.scope.launch {
            ctx.historyEditSession.revisions.collect { revisions ->
                historyRevision++
                val changed = revisions.keys.filter { revisions[it] != previousHistoryRevisions[it] }.map {
                    if (it == HistoryKind.PLAY) DriveLibrary.HISTORY else DriveLibrary.RECENT
                }
                previousHistoryRevisions = revisions
                changed.forEach(libraryListings::remove)
                ctx.libraryView()?.takeIf { it in changed }?.let { loadLibrary(it, useCache = false, showRefreshing = false) }
            }
        }
        ctx.scope.launch {
            ctx.driveRepo.optimisticRevision.collect { optimisticRevision = it }
        }
        ctx.scope.launch {
            ctx.preferences.heuristicFilterFlow.collect { isHeuristicFilterEnabled = it }
        }
        ctx.scope.launch {
            ctx.preferences.nameParsingFlow.collect { isNameParsing = it }
        }
        ctx.scope.launch {
            ctx.preferences.foldersFirstFlow.collect { enabled -> applyFoldersFirst(enabled) }
        }
        ctx.scope.launch {
            ctx.preferences.perFolderSortFlow.collect { enabled ->
                if (perFolderSort == enabled) return@collect
                perFolderSort = enabled
                val activeFolderId = ctx.activeFolderId()
                val desired = effectiveSortOrderFor(activeFolderId)
                if (sortOrder != desired) {
                    sortOrder = desired
                    if (loadedFolderId != activeFolderId) load()
                }
            }
        }
        ctx.scope.launch {
            ctx.preferences.folderSortOrdersFlow.collect { serialized ->
                val parsed = decodeFolderSortOrders(serialized)
                withPikoLock(folderSortLock) {
                    folderSortOrders.clear()
                    folderSortOrders.putAll(parsed)
                }
                val activeFolderId = ctx.activeFolderId()
                if (usesPerFolderSort(activeFolderId)) {
                    val desired = effectiveSortOrderFor(activeFolderId)
                    if (sortOrder != desired) {
                        sortOrder = desired
                        if (loadedFolderId != activeFolderId) load()
                    }
                }
            }
        }
        ctx.scope.launch {
            snapshotFlow { ctx.libraryView()?.takeIf { it.isSession }?.let { it to ctx.sessionListing(it)?.files } }.collect { shown ->
                if (shown != null) showSession(shown.first)
            }
        }
        ctx.scope.launch {
            combine(ctx.driveRepo.observeSortRevision(), snapshotFlow {
                LocalSortInput(
                    ctx.activeFolderId(),
                    loadedFolderId,
                    baseFiles,
                    remoteFiles,
                    sortOrder,
                    foldersFirst,
                    ctx.search.globalSearchHits.toList(),
                )
            }) { _, input -> input }.collectLatest { input ->
                if (!ctx.canAcceptAction() || ctx.archiveView() != null || ctx.libraryView()?.isSession == true) return@collectLatest
                val generation = ctx.driveRepo.accountGeneration
                val (context, sorted) = withContext(Dispatchers.Default) {
                    val context = ctx.driveRepo.sortContextFor(input.files + input.remote + input.hits.map { it.file }, input.order)
                    context to Triple(
                        ctx.driveRepo.sortFiles(input.files, input.order, input.folderId, context, foldersFirst = input.foldersFirst),
                        ctx.driveRepo.sortFiles(input.remote, input.order, input.folderId, context, foldersFirst = input.foldersFirst),
                        ctx.driveRepo.sortFiles(input.hits.map { it.file }, input.order, "", context, foldersFirst = input.foldersFirst),
                    )
                }
                Snapshot.withMutableSnapshot {
                    if (ctx.canAcceptAction() &&
                        ctx.driveRepo.accountGeneration == generation &&
                        ctx.driveRepo.sortContextRevision == context.revision &&
                        ctx.activeFolderId() == input.folderId &&
                        loadedFolderId == input.loadedFolderId &&
                        baseFiles === input.files &&
                        remoteFiles === input.remote &&
                        sortOrder == input.order &&
                        foldersFirst == input.foldersFirst &&
                        ctx.search.globalSearchHits.toList() == input.hits
                    ) {
                        if (baseFiles != sorted.first) baseFiles = sorted.first
                        if (remoteFiles != sorted.second) remoteFiles = sorted.second
                        ctx.search.replaceGlobalHitsIfChanged(input.hits.associateBy { it.file.id }, sorted.third)
                    }
                }
            }
        }
        ctx.scope.launch {
            snapshotFlow { Triple(loadedFolderId, files, PikoLocale.effectiveLanguage) }.collectLatest { (folderId, list, language) ->
                val structure = DriveAnalysisCache.analyze(list)
                Snapshot.withMutableSnapshot {
                    if (ctx.canAcceptAction() && files === list && loadedFolderId == folderId && PikoLocale.effectiveLanguage == language) {
                        ctx.selection.revealHighlightedItems(structure, ctx.selection.highlightedFileIds)
                        analysis = FolderAnalysis(folderId, list, structure)
                    }
                }
            }
        }
        ctx.scope.launch {
            combine(snapshotFlow { files to PikoLocale.effectiveLanguage }, ctx.driveRepo.childContentsFlow) { (list, language), content ->
                language to list.filter(FileStat::isFolder).map { folder -> folder to content[folder.id] }
            }.distinctUntilChanged().collectLatest { (_, folders) -> describeFolders(folders) }
        }
        ctx.scope.launch {
            ctx.driveRepo.vaultCoverChanges.collect {
                Snapshot.withMutableSnapshot {
                    remoteFiles = ctx.driveRepo.withVaultCovers(remoteFiles)
                    val patched = ctx.driveRepo.withVaultCovers(baseFiles)
                    if (patched !== baseFiles) baseFiles = patched
                }
            }
        }
        val instantSession = ctx.instantSession
        if (instantSession != null) {
            ctx.scope.launch {
                snapshotFlow {
                    val draft = instantSession.state
                    val targetId = draft?.target?.id
                    val entries = draft?.projectedSaveEntries.orEmpty()
                    val ownerTab = ctx.addLinkOwnerTabId()
                    listOf(ctx.activeFolderStack(), draft, targetId, entries, ownerTab)
                }.collectLatest {
                    val folderId = ctx.activeFolderId()
                    val draft = instantSession.state
                    val tabNav = ctx.navigation as? TabNavigation
                    val ownerTab = ctx.addLinkOwnerTabId()
                    val wrongTab = tabNav != null && ownerTab != null && tabNav.tabId != ownerTab
                    val previewLocations = ctx.activeFolderStack().mapNotNull { ImportPreviewLocation.of(it.id) }
                    val inPreview = previewLocations.isNotEmpty() || ctx.archiveView()?.archiveId in previewArchiveIds
                    if (inPreview && !wrongTab && draft != null && draft.willCreateFolder && draft.folderNameToSave.isNotBlank()) {
                        val currentStack = ctx.navigation.folderStackFlow.value
                        val firstPreviewIdx = currentStack.indexOfFirst { ImportPreviewLocation.of(it.id) != null }
                        if (firstPreviewIdx >= 0) {
                            val firstLoc = requireNotNull(ImportPreviewLocation.of(currentStack[firstPreviewIdx].id))
                            val oldTop = firstLoc.path.trim('/').substringBefore('/')
                            val newTop = draft.folderNameToSave.trim('/')
                            if (oldTop.isNotEmpty() && newTop.isNotEmpty() && oldTop != newTop &&
                                draft.projectedSaveEntries.any { (_, resolved) ->
                                    val clean = resolved.path.trim('/')
                                    clean == newTop || clean.startsWith("$newTop/")
                                }
                            ) {
                                val rewritten = currentStack.mapIndexed { idx, crumb ->
                                    val loc = ImportPreviewLocation.of(crumb.id) ?: return@mapIndexed crumb
                                    val cleanPath = loc.path.trim('/')
                                    val updatedPath = when {
                                        cleanPath == oldTop -> newTop
                                        cleanPath.startsWith("$oldTop/") -> "$newTop/${cleanPath.removePrefix("$oldTop/")}"
                                        else -> cleanPath
                                    }
                                    val newLoc = ImportPreviewLocation(loc.targetFolderId, updatedPath)
                                    val newName = if (idx == firstPreviewIdx && cleanPath == oldTop) newTop else crumb.name
                                    PikoPathBreadcrumb(newLoc.id, newName)
                                }
                                if (rewritten != currentStack) {
                                    ctx.navigation.restoreFolderStack(rewritten)
                                    return@collectLatest
                                }
                            }
                        }
                    }
                    val invalidPreview = inPreview && (wrongTab || draft == null || previewLocations.any { location ->
                        val subPath = location.path.trim('/')
                        location.targetFolderId != draft.target?.id || draft.projectedSaveEntries.none { (_, resolved) ->
                            val clean = resolved.path.trim('/')
                            clean == subPath || clean.startsWith("$subPath/")
                        }
                    })
                    if (draft == null || wrongTab) previewArchiveIds.clear()
                    if (invalidPreview) {
                        val currentStack = ctx.navigation.folderStackFlow.value
                        val validIdx = if (!wrongTab && draft != null) {
                            currentStack.indexOfLast { crumb ->
                                val loc = ImportPreviewLocation.of(crumb.id)
                                if (loc == null) {
                                    ArchiveLocation.of(crumb.id) == null
                                } else {
                                    val subPath = loc.path.trim('/')
                                    loc.targetFolderId == draft.target?.id && draft.projectedSaveEntries.any { (_, resolved) ->
                                        val clean = resolved.path.trim('/')
                                        clean == subPath || clean.startsWith("$subPath/")
                                    }
                                }
                            }
                        } else {
                            currentStack.outsideArchives().size - 1
                        }
                        if (validIdx in 0 until currentStack.lastIndex) {
                            ctx.navigation.popToBreadcrumb(validIdx)
                        }
                        return@collectLatest
                    }
                    val previewLoc = ImportPreviewLocation.of(folderId)
                    if (previewLoc != null) {
                        val virtualList = importPreviewFilesFor(folderId)
                        val structure = DriveAnalysisCache.analyze(virtualList)
                        Snapshot.withMutableSnapshot {
                            if (ctx.activeFolderId() == folderId) {
                                baseFiles = virtualList
                                loadedFolderId = folderId
                                analysis = FolderAnalysis(folderId, virtualList, structure)
                                isLoading = false
                                isRefreshing = false
                            }
                        }
                    } else if (ctx.libraryView() == null && ctx.archiveView() == null && loadedFolderId == folderId) {
                        val merged = withImportPreviews(folderId, remoteFiles)
                        if (merged != files) {
                            val structure = DriveAnalysisCache.analyze(merged)
                            Snapshot.withMutableSnapshot {
                                if (ctx.activeFolderId() == folderId && loadedFolderId == folderId) {
                                    baseFiles = merged
                                    analysis = FolderAnalysis(folderId, merged, structure)
                                }
                            }
                        }
                    }
                }
            }
        }
        ctx.scope.launch {
            val reloads = Channel<Unit>(Channel.CONFLATED)
            launch {
                ctx.driveRepo.folderChanges.collect { change ->
                    val targetIdSet: Set<String>? = when (change) {
                        is DriveChange.Moved -> change.ids.toHashSet()
                        is DriveChange.Trashed -> change.ids.toHashSet()
                        is DriveChange.Restored -> change.ids.toHashSet()
                        is DriveChange.Deleted -> change.ids.toHashSet()
                        is DriveChange.Starred -> change.ids.toHashSet()
                        else -> null
                    }
                    val libraryView = ctx.libraryView()
                    val activeFolderId = ctx.activeFolderId()
                    if (change !is DriveChange.ContentsChanged &&
                        !ctx.isLocalPlace() &&
                        ctx.archiveView() == null &&
                        ctx.importPreviewView() == null &&
                        libraryView?.isSession != true
                    ) {
                        fun matchesChange(id: String): Boolean = when (change) {
                            is DriveChange.Renamed -> id == change.id
                            else -> targetIdSet != null && id in targetIdSet
                        }
                        fun patch(item: FileStat): FileStat? = when (change) {
                            is DriveChange.Renamed -> if (item.id == change.id) item.copy(name = change.name) else item
                            is DriveChange.Moved -> if (targetIdSet != null && item.id in targetIdSet) item.copy(parentId = change.to) else item
                            is DriveChange.Trashed -> if (targetIdSet != null && item.id in targetIdSet) item.copy(trashed = true) else item
                            is DriveChange.Restored -> if (targetIdSet != null && item.id in targetIdSet) item.copy(trashed = false) else item
                            is DriveChange.Deleted -> item.takeUnless { targetIdSet != null && it.id in targetIdSet }
                            is DriveChange.Starred -> if (targetIdSet != null && item.id in targetIdSet) {
                                item.copy(
                                    starred = change.starred,
                                    tags = item.tags.filterNot { it.name.equals("STAR", true) } +
                                        if (change.starred) listOf(FileTag(id = "STAR", name = "STAR")) else emptyList(),
                                )
                            } else {
                                item
                            }
                            else -> item
                        }
                        fun inCurrentView(item: FileStat): Boolean =
                            item.trashed == (libraryView == DriveLibrary.TRASH) &&
                                (libraryView != null || item.parentId == activeFolderId) &&
                                (libraryView != DriveLibrary.STARRED || item.isStarred)
                        val patchBase = baseFiles.any { matchesChange(it.id) || !inCurrentView(it) }
                        val patchRemote = remoteFiles.any { matchesChange(it.id) || !inCurrentView(it) }
                        val hasHitMatch = ctx.search.globalSearchHits.any { matchesChange(it.file.id) || it.file.trashed }
                        if (patchBase || patchRemote || hasHitMatch) {
                            Snapshot.withMutableSnapshot {
                                if (patchBase) baseFiles = baseFiles.mapNotNull(::patch).filter(::inCurrentView)
                                if (patchRemote) remoteFiles = remoteFiles.mapNotNull(::patch).filter(::inCurrentView)
                                if (hasHitMatch) ctx.search.patchGlobalHits(::matchesChange, ::patch)
                            }
                        }
                    }
                    if (change is DriveChange.Starred ||
                        change is DriveChange.Renamed ||
                        change is DriveChange.Trashed ||
                        change is DriveChange.Deleted ||
                        change is DriveChange.Restored ||
                        change is DriveChange.ContentsChanged
                    ) {
                        libraryListings.remove(DriveLibrary.STARRED)
                    }
                    if (change is DriveChange.Renamed &&
                        change.parentId == activeFolderId &&
                        libraryView == null &&
                        ctx.archiveView() == null &&
                        !ctx.search.isSearching
                    ) {
                        val folderId = activeFolderId
                        val previous = files
                        val currentRemote = remoteFiles
                        val currentOrder = sortOrder
                        val currentFoldersFirst = foldersFirst
                        val (nextRemote, nextFiles, structure) = withContext(Dispatchers.Default) {
                            val sortedRemote = ctx.driveRepo.sortFiles(
                                currentRemote.map { if (it.id == change.id) it.copy(name = change.name) else it },
                                currentOrder,
                                folderId,
                                foldersFirst = currentFoldersFirst,
                            )
                            val merged = withImportPreviews(folderId, sortedRemote)
                            Triple(sortedRemote, merged, DriveAnalysisCache.analyze(merged))
                        }
                        Snapshot.withMutableSnapshot {
                            if (files === previous && ctx.activeFolderId() == folderId) {
                                remoteFiles = nextRemote
                                baseFiles = nextFiles
                                analysis = FolderAnalysis(folderId, nextFiles, structure)
                            } else {
                                reloads.trySend(Unit)
                            }
                        }
                    } else if (change is DriveChange.Starred && libraryView == null && ctx.archiveView() == null) {
                        val targetIds = targetIdSet ?: change.ids.toHashSet()
                        if (remoteFiles.any { it.id in targetIds } || files.any { it.id in targetIds }) {
                            fun patchStar(item: FileStat): FileStat {
                                if (item.id !in targetIds) return item
                                val withoutStar = item.tags.filterNot { it.name.equals("STAR", ignoreCase = true) }
                                val nextTags = if (change.starred) withoutStar + FileTag(id = "STAR", name = "STAR") else withoutStar
                                return item.copy(tags = nextTags, starred = change.starred)
                            }
                            Snapshot.withMutableSnapshot {
                                remoteFiles = remoteFiles.map(::patchStar)
                                baseFiles = baseFiles.map(::patchStar)
                            }
                        }
                        ctx.search.patchGlobalHitsStarred(targetIds, change.starred)
                    } else if (isAffectedBy(change)) {
                        reloads.trySend(Unit)
                    }
                }
            }
            for (reload in reloads) {
                load()
                loadJob?.join()
            }
        }
    }

    private companion object {
        const val PREFETCH_DWELL_MILLIS = 400L
    }
}
