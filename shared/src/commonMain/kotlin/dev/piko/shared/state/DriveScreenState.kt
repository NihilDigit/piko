package dev.piko.shared.state

import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import dev.piko.shared.i18n.enCount
import dev.piko.shared.presentation.localizedName

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.isPikoInternalFolder
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.ImportPreviewEntryId
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isExtractableArchive
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.outsideArchives
import dev.piko.data.repository.isPlayableVideo
import io.github.nihildigit.pikpak.thumbnailUrlOf
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import io.github.nihildigit.pikpak.ArchivePasswordException
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.PikPakException
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.VaultStore
import dev.piko.shared.data.DriveChange
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.isVaulted
import dev.piko.shared.upload.isUploading
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
import dev.piko.shared.log.logFolder
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.shared.search.cjkFuzzyScore
import dev.piko.shared.search.cjkMatches
import dev.piko.shared.search.normalizeCjk
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import io.github.nihildigit.pikpak.SearchHit
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import dev.piko.shared.presentation.DriveClipboardState
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

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

private const val PREFETCH_DWELL_MILLIS = 400L

private val HIGHLIGHT_RETRY_DELAYS = listOf(300L, 600L, 800L, 1000L)

private const val TAG = "Drive"

private val VAULTED_NEEDS_RESTORE: String get() = tr("已归档项目需先恢复至网盘", "Restore archived items to the drive first", "アーカイブ済みの項目は先にドライブへ復元してください", "보관된 항목은 먼저 드라이브로 복원해 주세요", "已歸檔項目需先還原至雲端硬碟")

data class TrashProgress(val completed: Int, val total: Int)

data class PasteReturnNotice(val message: String, val originStack: List<PikoPathBreadcrumb>)

data class TrashFailure(val completed: Int, val remainingIds: List<String>)

class DriveScreenState(
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
    private val sessionListing: (DriveLibrary) -> SessionListing? = { null },
    private val archives: ArchiveBrowser? = null,
    initialSortOrder: PikoFileSortOrder = PikoFileSortOrder.TIME_DESC,
    val navigation: dev.piko.shared.presentation.DriveNavigation = dev.piko.shared.presentation.LibraryNavigation(
        driveRepo, null, scope, listOf(PikoDriveRepository.ROOT_BREADCRUMB),
    ),
    private val clipboard: DriveClipboardState = DriveClipboardState(),
    operationScope: CoroutineScope = scope,
    private val fileWriteSession: FileWriteSession = FileWriteSession(driveRepo, operationScope),
    historyEditSession: HistoryMutations = HistoryMutations(driveRepo, operationScope),
    vaultEditSession: VaultEntryMutations = VaultEntryMutations(driveRepo, operationScope),
    folderVault: FolderVaultSession? = null,
    receiving: dev.piko.shared.presentation.ReceivingActivity? = null,
    private val instantSession: InstantSession? = null,
    private val addLinkOwnerTabId: () -> Long? = { null },
    private val supplementalFiles: () -> List<FileStat> = { emptyList() },
) {
    private val browserAccount = driveRepo.currentAccount
    private val browserGeneration = driveRepo.accountGeneration
    private val canAcceptAction: Boolean get() = scope.isActive && driveRepo.currentAccount == browserAccount && driveRepo.accountGeneration == browserGeneration
    val acceptsActions: Boolean get() = canAcceptAction
    val fileActions = dev.piko.shared.presentation.FileCommands(driveRepo, fileWriteSession, browserAccount, folderVault, receiving) { canAcceptAction }
    val vaultActions = dev.piko.shared.presentation.VaultEntryCommands(driveRepo, vaultEditSession, fileActions, browserAccount) { canAcceptAction }
    val historyActions = dev.piko.shared.presentation.HistoryCommands(driveRepo, historyEditSession, browserAccount) { canAcceptAction }
    init {
        val initialRevision = driveRepo.clientRevision.value
        scope.launch {
            var previous = initialRevision
            driveRepo.clientRevision.collect { revision ->
                if (revision != previous && canAcceptAction) {
                    libraryListings.clear()
                    if (archiveView != null) load()
                }
                previous = revision
            }
        }
    }

    private val actions = DriveActionController(fileActions, historyActions, vaultActions, fileWriteSession, scope,
        { canAcceptAction }, ::exitSelection, { _messages.tryEmit(it) })
    private fun submitFileAction(command: FileWriteCommand, clearSelection: Boolean = true, onSuccess: (() -> Unit)? = null) =
        actions.file(command, clearSelection, onSuccess)

    val clipboardFlow: StateFlow<DriveClipboard?> get() = clipboard.contents
    private val projection = DriveListProjection(driveRepo)
    private var baseFiles by mutableStateOf<List<FileStat>>(emptyList())
    private var optimisticRevision by mutableStateOf(0L)
    private var historyRevision by mutableStateOf(0L)
    val files: List<FileStat> by derivedStateOf {
        historyRevision
        optimisticRevision
        if (isLocalPlace || archiveView != null || importPreviewView != null || libraryView?.isSession == true) baseFiles
        else {
            val library = libraryView
            val projected = projection.files(baseFiles, activeFolderId, library)
            if (library == DriveLibrary.STARRED) projected
            else if (library == DriveLibrary.HISTORY || library == DriveLibrary.RECENT) {
                val kind = if (library == DriveLibrary.HISTORY) dev.piko.shared.data.HistoryKind.PLAY else dev.piko.shared.data.HistoryKind.RECENT
                projected.filterNot { file -> libraryEvents[file.id]?.let { historyActions.session.hidden(kind, it.id) } == true }
            } else projected
        }
    }
    private var remoteFiles by mutableStateOf<List<FileStat>>(emptyList())
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

    var searchQuery by mutableStateOf("")
        private set
    var isSearchOpen by mutableStateOf(false)
        private set

    fun openSearch() { isSearchOpen = true }
    fun closeSearch() { isSearchOpen = false; updateSearchQuery("") }
    private val search = DriveSearchSession(driveRepo, scope) { _messages.tryEmit(it) }
    val isGlobalSearching get() = search.isGlobalSearching
    val isGlobalSearchActive get() = search.isGlobalSearchActive
    private val globalSearchHits get() = search.globalSearchHits

    private val selection = DriveSelectionState({ fileRows }, { displayedFiles }, ::canSelect)
    private val _selectedFileIds get() = selection.ids
    val selectedFileIds: List<String> get() = selection.ids
    val isSelectionMode: Boolean get() = selection.active

    private val _revealedFileIds = mutableStateListOf<String>()
    val revealedFileIds: List<String> get() = _revealedFileIds

    var highlightedFileIds by mutableStateOf(navigation.takePendingHighlight())
        private set
    var highlightRevision by mutableStateOf(0)
        private set

    private val showAllDirectories = mutableStateMapOf<String, Boolean>()
    private val expandedSections = mutableStateMapOf<String, Boolean>()
    val showAllFilesTemporarily: Boolean by derivedStateOf { showAllDirectories[activeFolderId] == true }

    var isHeuristicFilterEnabled by mutableStateOf(true)
        private set

    var isNameParsing by mutableStateOf(true)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _pasteNotices = MutableSharedFlow<PasteReturnNotice>(extraBufferCapacity = 8)
    val pasteNotices: SharedFlow<PasteReturnNotice> = _pasteNotices.asSharedFlow()

    val folderStack get() = navigation.folderStackFlow
    val activeFolder: PikoPathBreadcrumb
        get() = navigation.folderStackFlow.value.lastOrNull() ?: PikoDriveRepository.ROOT_BREADCRUMB

    private var activeFolderId by mutableStateOf(navigation.folderStackFlow.value.lastOrNull()?.id.orEmpty())
    private var activeFolderStack by mutableStateOf(navigation.folderStackFlow.value)

    val libraryView: DriveLibrary? by derivedStateOf {
        if (ArchiveLocation.of(activeFolderId) != null) null
        else activeFolderStack.library?.takeIf { it.isSession } ?: DriveLibrary.of(activeFolderId)
    }

    private val usesFolderStructure: Boolean by derivedStateOf {
        libraryView?.let(sessionListing)?.usesFolderStructure == true
    }

    val archiveView: ArchiveLocation? by derivedStateOf { ArchiveLocation.of(activeFolderId) }

    val importPreviewView: ImportPreviewLocation? by derivedStateOf { ImportPreviewLocation.of(activeFolderId) }

    val isLocalPlace: Boolean by derivedStateOf { dev.piko.shared.data.LocalLocation.isLocal(activeFolderId) }
    val isVirtualPlace: Boolean by derivedStateOf { isLocalPlace || libraryView != null || archiveView != null || importPreviewView != null }

    var libraryEvents by mutableStateOf<Map<String, DriveEvent>>(emptyMap())
        private set

    private class FolderAnalysis(val folderId: String?, val source: List<FileStat>, val structure: DriveStructure)
    private var analysis by mutableStateOf<FolderAnalysis?>(null)

    private val currentAnalysis: DriveStructure? by derivedStateOf {
        analysis?.takeIf {
            it.source === files || (it.folderId != null && it.folderId == loadedFolderId && it.folderId == activeFolderId)
        }?.structure
    }
    val isDisplayStructureReady: Boolean by derivedStateOf { isSearching || (isVirtualPlace && !isLocalPlace && !usesFolderStructure) || currentAnalysis != null }

    val folderEmptiness get() = driveRepo.folderEmptiness

    val vaultedFolders get() = driveRepo.vaultedFolders
    val fullyVaultedFolders get() = driveRepo.fullyVaultedFolders

    private val _folderViews = mutableStateMapOf<String, DriveFolderView>()
    val folderViews: Map<String, DriveFolderView> get() = _folderViews
    private val isFoldingActive: Boolean by derivedStateOf {
        val folded = currentAnalysis?.foldedIds ?: return@derivedStateOf false
        isHeuristicFilterEnabled && isNameParsing && (!isVirtualPlace || isLocalPlace) && folded.size < files.size && isFoldingScope(files)
    }

    val potentialHiddenCount: Int by derivedStateOf {
        if (isFoldingActive) currentAnalysis?.foldedIds?.size ?: 0 else 0
    }

    var typeFilter by mutableStateOf<FileCategory?>(null)
        private set

    private val isSearching: Boolean by derivedStateOf { isGlobalSearchActive || searchQuery.isNotBlank() || typeFilter != null }

    private val searchedFiles: List<FileStat> by derivedStateOf {
        when {
            isGlobalSearchActive -> {
                optimisticRevision
                driveRepo.projectOptimisticFiles(globalSearchHits.map { it.file })
            }
            searchQuery.isNotBlank() -> searchQuery.trim().let { query ->
                files.filter { listedName(it).matches(query) }
            }
            else -> files
        }
    }

    val availableTypes: List<Pair<FileCategory, Int>> by derivedStateOf {
        val counts = searchedFiles.filterNot { it.isFolder }.groupingBy { it.fileCategory() }.eachCount()
        FileCategory.entries.mapNotNull { category -> counts[category]?.let { category to it } }
    }

    var thumbnailsOnly by mutableStateOf(false)
        private set

    fun updateThumbnailsOnly(value: Boolean) {
        if (value == thumbnailsOnly) return
        thumbnailsOnly = value
        if (isSelectionMode) exitSelection()
    }

    private fun isHiddenByThumbnails(file: FileStat) = thumbnailsOnly && !file.isFolder && file.thumbnailLink.isEmpty()

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
            usesFolderStructure -> folderSessionItems { block -> isBlockExpanded(block) }
            libraryView?.isSession == true -> sessionItems { blockId -> isSessionGroupExpanded(blockId) }
            structure == null || (isVirtualPlace && !isLocalPlace && importPreviewView == null) -> files.map { DriveListItem.File(it, null) }
            else -> projection.items(files, structure, hideFolded, isNameParsing, foldersFirst, sortOrder, ::isBlockExpanded)
        }
    }

    private val isStructured: Boolean by derivedStateOf {
        val structure = currentAnalysis
        !isSearching && (!isVirtualPlace || isLocalPlace || importPreviewView != null) && isNameParsing && foldersFirst &&
            sortOrder != PikoFileSortOrder.DURATION_DESC && sortOrder != PikoFileSortOrder.DURATION_ASC &&
            structure != null && structure.blocks.isNotEmpty()
    }

    private val fileRows: List<DriveListItem.File> by derivedStateOf {
        val structure = currentAnalysis
        val rows = when {
            isStructured && structure != null -> buildDriveItems(files, structure, isFoldingActive && !showAllFilesTemporarily) { true }
            !isSearching && usesFolderStructure -> folderSessionItems { true }
            !isSearching && libraryView?.isSession == true -> sessionItems { true }
            else -> displayItems
        }
        rows.filterIsInstance<DriveListItem.File>().filterNot { isHiddenByThumbnails(it.file) }
    }

    private val fileByRowKey: Map<String, FileStat> by derivedStateOf { fileRows.associate { it.key to it.file } }

    val displayedFiles: List<FileStat> by derivedStateOf {
        val shown = fileRows.map { it.file }
        val hosts = if (isStructured) currentAnalysis?.attachedTo.orEmpty() else emptyMap()
        val shownIds = shown.mapTo(HashSet()) { it.id }
        val attachments = files.filter { file -> hosts[file.id]?.let { it in shownIds } == true && !isHiddenByThumbnails(file) }
        (shown + attachments).distinctBy { it.id }
    }

    fun withAttachedFiles(selected: List<FileStat>): List<FileStat> {
        val attachedTo = currentAnalysis?.attachedTo.orEmpty()
        val attachments = files.filter { attachedTo[it.id] != null }.groupBy { attachedTo.getValue(it.id) }
        return selected.flatMap { listOf(it) + attachments[it.id].orEmpty() }
            .distinctBy { it.id }
            .filterNot { it.isUploading || it.isVaulted || it.isImportPreview }
    }

    val highlightedRowIds: Set<String> by derivedStateOf {
        val ids = highlightedFileIds
        if (ids.isEmpty()) return@derivedStateOf emptySet()
        val rows = displayItems.mapNotNullTo(HashSet()) { (it as? DriveListItem.File)?.file?.id }
        val hosts = currentAnalysis?.attachedTo.orEmpty()
        ids.mapNotNullTo(HashSet()) { id -> id.takeIf { it in rows } ?: hosts[id]?.takeIf { it in rows } }
    }

    val sectionHeaders: List<IndexedValue<DriveListItem.SectionHeader>> by derivedStateOf {
        displayItems.withIndex().mapNotNull { (index, item) -> (item as? DriveListItem.SectionHeader)?.let { IndexedValue(index, it) } }
    }

    private fun isBlockExpanded(block: DriveBlock): Boolean =
        expandedSections[expandKey(block.id)] ?: block.defaultExpanded

    private fun expandKey(blockId: String) = "$activeFolderId|$blockId"

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

    private val rowViews: Map<String, DriveFileView> by derivedStateOf {
        fileRows.mapNotNull { row -> row.view?.let { row.file.id to it } }.toMap()
    }

    fun folderView(file: FileStat): DriveFolderView? = if (isNameParsing && file.isFolder) _folderViews[file.id] else null

    fun itemName(file: FileStat): DriveItemName = driveItemName(file, rowViews[file.id], folderView(file))

    private fun listedName(file: FileStat): DriveItemName =
        driveItemName(file, if (isNameParsing) currentAnalysis?.views?.get(file.id) else null, folderView(file))

    fun fileView(fileId: String): DriveFileView? = if (!isNameParsing) null else currentAnalysis?.views?.get(fileId)

    val hitLocations: Map<String, String> by derivedStateOf {
        if (isGlobalSearchActive) {
            globalSearchHits.associate { it.file.id to it.parentPath.ifEmpty { PikoDriveRepository.ROOT_BREADCRUMB.localizedName } }
        } else {
            emptyMap()
        }
    }

    var loadedFolderId by mutableStateOf<String?>(null)
        private set

    private val previewArchiveIds = mutableSetOf<String>()

    init {
        scope.launch {
            driveRepo.optimisticRevision.collect { optimisticRevision = it }
        }
        scope.launch {
            preferences.heuristicFilterFlow.collect { isHeuristicFilterEnabled = it }
        }
        scope.launch {
            preferences.nameParsingFlow.collect { isNameParsing = it }
        }
        scope.launch {
            preferences.foldersFirstFlow.collect { enabled -> applyFoldersFirst(enabled) }
        }
        scope.launch {
            preferences.perFolderSortFlow.collect { enabled ->
                if (perFolderSort == enabled) return@collect
                perFolderSort = enabled
                val desired = effectiveSortOrderFor(activeFolderId)
                if (sortOrder != desired) {
                    sortOrder = desired
                    if (loadedFolderId != activeFolderId) load()
                }
            }
        }
        scope.launch {
            preferences.folderSortOrdersFlow.collect { serialized ->
                val parsed = decodeFolderSortOrders(serialized)
                withPikoLock(folderSortLock) {
                    folderSortOrders.clear()
                    folderSortOrders.putAll(parsed)
                }
                if (usesPerFolderSort()) {
                    val desired = effectiveSortOrderFor(activeFolderId)
                    if (sortOrder != desired) {
                        sortOrder = desired
                        if (loadedFolderId != activeFolderId) load()
                    }
                }
            }
        }
        scope.launch {
            navigation.folderStackFlow.collect { stack ->
                val id = stack.lastOrNull()?.id.orEmpty()
                val previousStack = activeFolderStack
                if (id == activeFolderId && previousStack.library == stack.library) return@collect
                Snapshot.withMutableSnapshot {
                    activeFolderStack = stack
                    activeFolderId = id
                }
                onFolderChanged()
            }
        }
        scope.launch {
            snapshotFlow { libraryView?.takeIf { it.isSession }?.let { it to sessionListing(it)?.files } }.collect { shown ->
                if (shown != null) showSession(shown.first)
            }
        }
        scope.launch {
            navigation.pendingHighlights.collect { ids -> if (ids.isNotEmpty()) highlight(navigation.takePendingHighlight()) }
        }
        scope.launch {
            snapshotFlow { highlightedFileIds to activeFolderId }.collectLatest { (ids, folderId) ->
                if (ids.isNotEmpty()) catchUpWithHighlight(ids, folderId)
            }
        }
        scope.launch {
            snapshotFlow { highlightedFileIds to currentAnalysis }.collect { (ids, structure) ->
                if (ids.isEmpty() || structure == null) return@collect
                revealHighlightedItems(structure, ids)
            }
        }
        scope.launch {
            combine(driveRepo.observeSortRevision(), snapshotFlow {
                LocalSortInput(activeFolderId, loadedFolderId, baseFiles, remoteFiles, sortOrder, foldersFirst, globalSearchHits.toList())
            }) { _, input -> input }.collectLatest { input ->
                if (!canAcceptAction || archiveView != null || libraryView?.isSession == true) return@collectLatest
                val generation = driveRepo.accountGeneration
                val (context, sorted) = withContext(Dispatchers.Default) {
                    val context = driveRepo.sortContextFor(input.files + input.remote + input.hits.map { it.file }, input.order)
                    context to Triple(
                        driveRepo.sortFiles(input.files, input.order, input.folderId, context, foldersFirst = input.foldersFirst),
                        driveRepo.sortFiles(input.remote, input.order, input.folderId, context, foldersFirst = input.foldersFirst),
                        driveRepo.sortFiles(input.hits.map { it.file }, input.order, "", context, foldersFirst = input.foldersFirst),
                    )
                }
                Snapshot.withMutableSnapshot {
                    if (canAcceptAction && driveRepo.accountGeneration == generation && driveRepo.sortContextRevision == context.revision &&
                        activeFolderId == input.folderId && loadedFolderId == input.loadedFolderId &&
                        baseFiles === input.files && remoteFiles === input.remote && sortOrder == input.order && foldersFirst == input.foldersFirst && globalSearchHits.toList() == input.hits) {
                        if (baseFiles != sorted.first) baseFiles = sorted.first
                        if (remoteFiles != sorted.second) remoteFiles = sorted.second
                        val hitsById = input.hits.associateBy { it.file.id }
                        val hits = sorted.third.mapNotNull { hitsById[it.id] }
                        if (globalSearchHits.toList() != hits) {
                            globalSearchHits.clear()
                            globalSearchHits.addAll(hits)
                        }
                    }
                }
            }
        }
        scope.launch {
            snapshotFlow { Triple(loadedFolderId, files, dev.piko.shared.i18n.PikoLocale.effectiveLanguage) }.collectLatest { (folderId, list, language) ->
                val structure = DriveAnalysisCache.analyze(list)
                Snapshot.withMutableSnapshot {
                    if (canAcceptAction && files === list && loadedFolderId == folderId && dev.piko.shared.i18n.PikoLocale.effectiveLanguage == language) {
                        revealHighlightedItems(structure, highlightedFileIds)
                        analysis = FolderAnalysis(folderId, list, structure)
                    }
                }
            }
        }
        scope.launch {
            combine(snapshotFlow { files to dev.piko.shared.i18n.PikoLocale.effectiveLanguage }, driveRepo.childContentsFlow) { (list, language), content ->
                language to list.filter(FileStat::isFolder).map { folder -> folder to content[folder.id] }
            }.distinctUntilChanged().collectLatest { (_, folders) -> describeFolders(folders) }
        }
        scope.launch {
            driveRepo.vaultCoverChanges.collect {
                Snapshot.withMutableSnapshot {
                    remoteFiles = driveRepo.withVaultCovers(remoteFiles)
                    val patched = driveRepo.withVaultCovers(baseFiles)
                    if (patched !== baseFiles) baseFiles = patched
                }
            }
        }
        if (instantSession != null) {
            scope.launch {
                snapshotFlow {
                    val draft = instantSession.state
                    val targetId = draft?.target?.id
                    val entries = draft?.projectedSaveEntries.orEmpty()
                    val ownerTab = addLinkOwnerTabId()
                    listOf(activeFolderStack, draft, targetId, entries, ownerTab)
                }.collectLatest {
                    val folderId = activeFolderId
                    val draft = instantSession.state
                    val tabNav = navigation as? dev.piko.shared.presentation.TabNavigation
                    val ownerTab = addLinkOwnerTabId()
                    val wrongTab = tabNav != null && ownerTab != null && tabNav.tabId != ownerTab
                    val previewLocations = activeFolderStack.mapNotNull { ImportPreviewLocation.of(it.id) }
                    val inPreview = previewLocations.isNotEmpty() || archiveView?.archiveId in previewArchiveIds
                    if (inPreview && !wrongTab && draft != null && draft.willCreateFolder && draft.folderNameToSave.isNotBlank()) {
                        val currentStack = navigation.folderStackFlow.value
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
                                    navigation.restoreFolderStack(rewritten)
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
                        val currentStack = navigation.folderStackFlow.value
                        val validIdx = if (!wrongTab && draft != null) {
                            currentStack.indexOfLast { crumb ->
                                val loc = ImportPreviewLocation.of(crumb.id)
                                if (loc == null) ArchiveLocation.of(crumb.id) == null
                                else {
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
                            navigation.popToBreadcrumb(validIdx)
                        }
                        return@collectLatest
                    }
                    val previewLoc = ImportPreviewLocation.of(folderId)
                    if (previewLoc != null) {
                        val virtualList = importPreviewFilesFor(folderId)
                        val structure = DriveAnalysisCache.analyze(virtualList)
                        Snapshot.withMutableSnapshot {
                            if (activeFolderId == folderId) {
                                baseFiles = virtualList
                                loadedFolderId = folderId
                                analysis = FolderAnalysis(folderId, virtualList, structure)
                                isLoading = false
                                isRefreshing = false
                            }
                        }
                    } else if (libraryView == null && archiveView == null && loadedFolderId == folderId) {
                        val merged = withImportPreviews(folderId, remoteFiles)
                        if (merged != files) {
                            val structure = DriveAnalysisCache.analyze(merged)
                            Snapshot.withMutableSnapshot {
                                if (activeFolderId == folderId && loadedFolderId == folderId) {
                                    baseFiles = merged
                                    analysis = FolderAnalysis(folderId, merged, structure)
                                }
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            val reloads = Channel<Unit>(Channel.CONFLATED)
            launch { driveRepo.folderChanges.collect { change ->
                if (change !is DriveChange.ContentsChanged && !isLocalPlace && archiveView == null && importPreviewView == null && libraryView?.isSession != true) {
                    fun patch(item: FileStat): FileStat? = when (change) {
                        is DriveChange.Renamed -> if (item.id == change.id) item.copy(name = change.name) else item
                        is DriveChange.Moved -> if (item.id in change.ids) item.copy(parentId = change.to) else item
                        is DriveChange.Trashed -> if (item.id in change.ids) item.copy(trashed = true) else item
                        is DriveChange.Restored -> if (item.id in change.ids) item.copy(trashed = false) else item
                        is DriveChange.Deleted -> item.takeUnless { it.id in change.ids }
                        is DriveChange.Starred -> if (item.id in change.ids) item.copy(
                            starred = change.starred,
                            tags = item.tags.filterNot { it.name.equals("STAR", true) } +
                                if (change.starred) listOf(FileTag(id = "STAR", name = "STAR")) else emptyList(),
                        ) else item
                        else -> item
                    }
                    fun inCurrentView(item: FileStat): Boolean = item.trashed == (libraryView == DriveLibrary.TRASH) &&
                        (libraryView != null || item.parentId == activeFolderId) &&
                        (libraryView != DriveLibrary.STARRED || item.isStarred)
                    Snapshot.withMutableSnapshot {
                        baseFiles = baseFiles.mapNotNull(::patch).filter(::inCurrentView)
                        remoteFiles = remoteFiles.mapNotNull(::patch).filter(::inCurrentView)
                        val nextHits = globalSearchHits.mapNotNull { hit ->
                            patch(hit.file)?.takeUnless { it.trashed }?.let { hit.copy(file = it) }
                        }
                        if (nextHits != globalSearchHits.toList()) {
                            globalSearchHits.clear()
                            globalSearchHits.addAll(nextHits)
                        }
                    }
                }
                if (change is DriveChange.Starred) {
                    libraryListings.remove(DriveLibrary.STARRED)
                }
                if (change is DriveChange.Renamed && change.parentId == activeFolderId && libraryView == null && archiveView == null && !isSearching) {
                    val folderId = activeFolderId
                    val previous = files
                    val currentRemote = remoteFiles
                    val currentOrder = sortOrder
                    val currentFoldersFirst = foldersFirst
                    val (nextRemote, nextFiles, structure) = withContext(Dispatchers.Default) {
                        val sortedRemote = driveRepo.sortFiles(
                            currentRemote.map { if (it.id == change.id) it.copy(name = change.name) else it },
                            currentOrder,
                            folderId,
                            foldersFirst = currentFoldersFirst,
                        )
                        val merged = withImportPreviews(folderId, sortedRemote)
                        Triple(sortedRemote, merged, DriveAnalysisCache.analyze(merged))
                    }
                    Snapshot.withMutableSnapshot {
                        if (files === previous && activeFolderId == folderId) {
                            remoteFiles = nextRemote
                            baseFiles = nextFiles
                            analysis = FolderAnalysis(folderId, nextFiles, structure)
                        } else reloads.trySend(Unit)
                    }
                } else if (change is DriveChange.Starred && libraryView == null && archiveView == null) {
                    val targetIds = change.ids.toSet()
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
                    if (isGlobalSearchActive && globalSearchHits.any { it.file.id in targetIds }) {
                        for (i in globalSearchHits.indices) {
                            val hit = globalSearchHits[i]
                            if (hit.file.id in targetIds) {
                                val withoutStar = hit.file.tags.filterNot { it.name.equals("STAR", ignoreCase = true) }
                                val nextTags = if (change.starred) withoutStar + FileTag(id = "STAR", name = "STAR") else withoutStar
                                globalSearchHits[i] = hit.copy(file = hit.file.copy(tags = nextTags, starred = change.starred))
                            }
                        }
                    }
                } else if (isAffectedBy(change)) reloads.trySend(Unit)
            } }
            for (reload in reloads) {
                load()
                loadJob?.join()
            }
        }
    }

    private fun isAffectedBy(change: DriveChange): Boolean = when {
        libraryView?.isSession == true || archiveView != null || importPreviewView != null -> false
        libraryView != null -> true
        else -> change.affects(activeFolderId)
    }

    suspend fun restoreLocation(): Boolean {
        if (!canAcceptAction || highlightedFileIds.isNotEmpty()) return false
        val unchangedBefore = activeFolderId
        if (!navigation.restoreLocation()) return false
        if (navigation.folderStackFlow.value.lastOrNull()?.id.orEmpty() == unchangedBefore) onFolderChanged()
        return true
    }

    fun restoreFolderStack(stack: List<PikoPathBreadcrumb>) {
        if (stack.isEmpty()) return
        val unchanged = stack.last().id == activeFolderId
        navigation.restoreFolderStack(stack)
        if (unchanged) onFolderChanged()
    }

    private data class LocalSortInput(
        val folderId: String, val loadedFolderId: String?, val files: List<FileStat>, val remote: List<FileStat>,
        val order: PikoFileSortOrder, val foldersFirst: Boolean, val hits: List<SearchHit>,
    )

    private val listingSource = DriveListingSource(driveRepo)
    private var loadJob: Job? = null

    fun load(refresh: Boolean = false) = load(useCache = !refresh, showRefreshing = refresh)
    private fun withoutInternalFolders(folderId: String, listing: List<FileStat>): List<FileStat> =
        listing.filterNot { isPikoInternalFolder(it, folderId) }

    private fun importPreviewFilesFor(folderId: String): List<FileStat> {
        val tabNav = navigation as? dev.piko.shared.presentation.TabNavigation
        val ownerTab = addLinkOwnerTabId()
        if (tabNav != null && ownerTab != null && tabNav.tabId != ownerTab) return emptyList()
        val draft = instantSession?.state ?: return emptyList()
        val targetId = draft.target?.id ?: return emptyList()
        val entries = draft.projectedSaveEntries
        if (entries.isEmpty()) return emptyList()
        val previewLoc = ImportPreviewLocation.of(folderId)
        val prefix = when {
            previewLoc != null -> if (previewLoc.targetFolderId == targetId) "${previewLoc.path.trim('/')}/" else return emptyList()
            folderId == targetId -> ""
            else -> return emptyList()
        }
        val childFolders = LinkedHashMap<String, MutableList<Pair<Int, io.github.nihildigit.pikpak.ResolvedFile>>>()
        val directFiles = mutableListOf<FileStat>()
        for ((sourceIndex, resolved) in entries) {
            val clean = resolved.path.trim('/')
            if (clean.isEmpty()) continue
            val rest = if (prefix.isEmpty()) clean else {
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
        return driveRepo.sortFilesFast(folderStats + directFiles, sortOrder, folderId, foldersFirst = foldersFirst)
    }

    private fun withImportPreviews(folderId: String, realFiles: List<FileStat>): List<FileStat> {
        val virtuals = importPreviewFilesFor(folderId)
        if (virtuals.isEmpty()) return realFiles
        val (virtualFolders, virtualFiles) = virtuals.partition(FileStat::isFolder)
        val (realFolders, realFilesOnly) = realFiles.partition(FileStat::isFolder)
        val combined = virtualFolders + realFolders + virtualFiles + realFilesOnly
        return if (foldersFirst) combined else driveRepo.sortFilesFast(combined, sortOrder, folderId, foldersFirst = false)
    }

    private fun load(useCache: Boolean, showRefreshing: Boolean) {
        if (!canAcceptAction) return
        val folderId = activeFolder.id
        libraryView?.let { library ->
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
            loadJob = scope.launch {
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
            driveRepo.onRootPullRefresh()
        }
        if (showRefreshing) {
            isRefreshing = true
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            listingSource.observe(folderId, sortOrder, useCache).collect { result ->
                if (activeFolderId != folderId) return@collect
                when (result) {
                    DriveListingResult.Loading -> if (useCache && !showRefreshing) isLoading = true
                    DriveListingResult.Gone -> {
                        isLoading = false
                        isRefreshing = false
                        if (navigateUp()) _messages.tryEmit(tr("文件夹已不存在，已返回上一级", "Folder no longer exists; returned to parent", "フォルダーが存在しないため、上の階層に戻りました", "폴더가 없어 상위 폴더로 돌아갔습니다", "資料夾已不存在，已返回上一層"))
                    }
                    is DriveListingResult.Failure -> {
                        PikoLog.w(TAG, "Failed to load drive folder ${logFolder(folderId)} (useCache=$useCache, refresh=$showRefreshing)", result.error)
                        loadError = result.error.message ?: tr("读取网盘失败", "Failed to load drive", "ドライブを読み込めませんでした", "드라이브를 불러오지 못했습니다", "讀取雲端硬碟失敗")
                        isLoading = false
                        isRefreshing = false
                        _messages.tryEmit(tr("加载失败", "Failed to load", "読み込みに失敗しました", "불러오지 못했습니다", "載入失敗"))
                    }
                    is DriveListingResult.Content -> {
                        val order = sortOrder
                        val first = foldersFirst
                        val (raw, merged, structure) = withContext(Dispatchers.Default) {
                            val sorted = driveRepo.sortFiles(withoutInternalFolders(folderId, result.files), order, folderId, foldersFirst = first)
                            val visible = withImportPreviews(folderId, sorted)
                            Triple(sorted, visible, DriveAnalysisCache.analyze(visible))
                        }
                        if (activeFolderId != folderId) return@collect
                        Snapshot.withMutableSnapshot {
                            remoteFiles = raw
                            baseFiles = merged
                            loadedFolderId = folderId
                            revealHighlightedItems(structure, highlightedFileIds)
                            analysis = FolderAnalysis(folderId, merged, structure)
                            isLoading = false
                            if (!result.cached) { loadError = null; isRefreshing = false }
                        }
                    }
                }
            }
        }
    }


    private class LibraryListing(val files: List<FileStat>, val events: Map<String, DriveEvent>)

    private val libraryListings = mutableMapOf<DriveLibrary, LibraryListing>()
    init {
        var previous = historyEditSession.revisions.value
        scope.launch {
            historyEditSession.revisions.collect { revisions ->
                historyRevision++
                val changed = revisions.keys.filter { revisions[it] != previous[it] }.map {
                    if (it == dev.piko.shared.data.HistoryKind.PLAY) DriveLibrary.HISTORY else DriveLibrary.RECENT
                }
                previous = revisions
                changed.forEach(libraryListings::remove)
                libraryView?.takeIf { it in changed }?.let { loadLibrary(it, useCache = false, showRefreshing = false) }
            }
        }
    }

    private fun loadLibrary(library: DriveLibrary, useCache: Boolean, showRefreshing: Boolean) {
        if (!canAcceptAction) return
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
                .logFailure(TAG, "Failed to load drive library ${library.name}")
                .onFailure {
                    loadError = it.message ?: tr("读取${library.title}失败", "Failed to load ${library.title}", "${library.title}を読み込めませんでした", "${library.title} 불러오기 실패", "讀取${library.title}失敗")
                    _messages.tryEmit(tr("加载失败", "Failed to load", "読み込みに失敗しました", "불러오지 못했습니다", "載入失敗"))
                }
            isLoading = false
            isRefreshing = false
        }
    }

    class ArchivePasswordRequest(val archiveName: String, val incorrect: Boolean)

    var archivePasswordRequest by mutableStateOf<ArchivePasswordRequest?>(null)
        private set

    var preparingEntryId by mutableStateOf<String?>(null)
        private set

    val isArchiveInImportPreview: Boolean by derivedStateOf {
        val loc = archiveView
        loc != null && (loc.archiveId in previewArchiveIds || activeFolderStack.any { ImportPreviewLocation.of(it.id) != null })
    }

    fun openArchive(file: FileStat) {
        if (archives == null) return
        if (file.hash.isEmpty()) {
            _messages.tryEmit(if (file.isImportPreview) tr("未收录的压缩包需先通过离线下载保存至网盘", "Save this unindexed archive via cloud download before opening", "未収録の圧縮ファイルは、先にクラウドダウンロードでドライブに保存してください", "미등록 압축 파일은 먼저 클라우드 다운로드로 드라이브에 저장해 주세요", "未收錄的壓縮檔需先透過離線下載儲存至雲端硬碟") else tr("文件正在校验，请稍后重试", "File verification in progress. Try again later", "ファイルを検証中です。しばらくしてから再試行してください", "파일을 확인 중입니다. 잠시 후 다시 시도해 주세요", "檔案正在驗證，請稍後重試"))
            return
        }
        if (ImportPreviewEntryId.isEntry(file.id)) {
            val resolved = ImportPreviewEntryId.resolvedFileOf(file.id)
            val draft = instantSession?.state
            if (resolved == null || draft == null) {
                _messages.tryEmit(tr("未收录的压缩包需先通过离线下载保存至网盘", "Save this unindexed archive via cloud download before opening", "未収録の圧縮ファイルは、先にクラウドダウンロードでドライブに保存してください", "미등록 압축 파일은 먼저 클라우드 다운로드로 드라이브에 저장해 주세요", "未收錄的壓縮檔需先透過離線下載儲存至雲端硬碟"))
                return
            }
            if (preparingEntryId != null) return
            preparingEntryId = file.id
            val expectedFolderId = activeFolderId
            scope.launch {
                try {
                    draft.leaseForArchiveBrowse(resolved)
                        .logFailure(TAG, "Failed to lease import-preview archive for browsing (gcid=${file.hash.take(8)}, bytes=${resolved.size})")
                        .onSuccess { leasedFileId ->
                            previewArchiveIds += leasedFileId
                            if (activeFolderId == expectedFolderId && canAcceptAction) {
                                openFolder(ArchiveLocation(leasedFileId, file.hash, "").id, file.name)
                            }
                        }
                        .onFailure { _messages.tryEmit(dev.piko.shared.notice.PikoErrorMapper.failure(tr("打开压缩包", "Open archive", "圧縮ファイルを開く", "압축 파일 열기", "開啟壓縮檔"), it)) }
                } finally {
                    preparingEntryId = null
                }
            }
            return
        }
        openFolder(ArchiveLocation(file.id, file.hash, "").id, file.name)
    }

    private fun loadArchive(location: ArchiveLocation, useCache: Boolean, showRefreshing: Boolean, password: String? = null) {
        if (!canAcceptAction) return
        val browser = archives ?: return
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
        loadJob = scope.launch {
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
                        archivePasswordRequest = ArchivePasswordRequest(archiveName(), incorrect = password != null)
                    } else {
                        PikoLog.w(
                            TAG,
                            "Failed to list archive ${location.archiveId} (${if (location.path.isEmpty()) "root" else "subpath"}, hasPassword=${password != null})",
                            error,
                        )
                        if (cached == null) {
                            baseFiles = emptyList()
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

    fun dismissArchivePassword() {
        archivePasswordRequest = null
        val stack = navigation.folderStackFlow.value
        val firstArchive = stack.indexOfFirst { ArchiveLocation.of(it.id) != null }
        if (firstArchive > 0) navigation.popToBreadcrumb(firstArchive - 1)
    }

    suspend fun prepareArchiveEntry(file: FileStat): FileStat? {
        if (!ArchiveEntryId.isEntry(file.id)) return file
        val browser = archives ?: return null
        if (preparingEntryId != null) return null
        preparingEntryId = file.id
        try {
            val location = archiveView
            val archiveId = ArchiveEntryId.pathOf(file.id)?.first ?: location?.archiveId ?: "unknown"
            return browser.prepare(file, files)
                .onSuccess {
                    if (location != null && archiveView == location) browser.cached(location)?.let { baseFiles = it }
                }
                .logFailure(TAG, "Failed to prepare entry in archive $archiveId (bytes=${file.sizeBytes})")
                .onFailure { _messages.tryEmit(tr("无法打开：${archiveFailure(it)}", "Unable to open: ${archiveFailure(it)}", "開けません：${archiveFailure(it)}", "열 수 없습니다: ${archiveFailure(it)}", "無法開啟：${archiveFailure(it)}")) }
                .getOrNull()
        } finally {
            preparingEntryId = null
        }
    }

    fun currentArchive(): Pair<FileStat, String>? {
        val location = archiveView ?: return null
        val file = FileStat(kind = FileKind.FILE, id = location.archiveId, name = archiveName(), hash = location.gcid)
        return file to archives?.password(location.archiveId).orEmpty()
    }

    fun archivePaths(items: List<FileStat>): List<String> = items.mapNotNull { item ->
        ArchiveLocation.of(item.id)?.path ?: ArchiveEntryId.pathOf(item.id)?.second
    }

    private fun archiveName(): String {
        val stack = navigation.folderStackFlow.value
        val firstArchive = stack.indexOfFirst { ArchiveLocation.of(it.id) != null }
        return if (firstArchive >= 0) stack[firstArchive].name else tr("压缩包", "Archive", "圧縮ファイル", "압축 파일", "壓縮檔")
    }

    private fun archiveFailure(error: Throwable): String = dev.piko.shared.notice.PikoErrorMapper.archive(error)

    private fun showLibrary(library: DriveLibrary, listing: LibraryListing) = Snapshot.withMutableSnapshot {
        baseFiles = listing.files
        if (!isGlobalSearchActive) {
            _selectedFileIds.retainAll(listing.files.mapTo(HashSet()) { it.id })
            if (listing.files.isEmpty()) exitSelection()
        }
        libraryEvents = listing.events
        loadedFolderId = library.id
    }
    private suspend fun fetchLibrary(library: DriveLibrary): Result<LibraryListing> = when (library) {
        DriveLibrary.STARRED -> driveRepo.starredFiles().map { LibraryListing(it, emptyMap()) }
        DriveLibrary.TRASH -> driveRepo.trashFiles().map { files ->
            if (driveRepo.reconcileTrashListing(files)) {
                _messages.tryEmit(tr("项目较多，服务端正在后台清理，存储空间将稍后释放", "The server is cleaning up in the background. Storage space will be freed shortly", "項目数が多いため、サーバーがバックグラウンドで削除しています。空き容量は順次反映されます", "항목이 많아 서버에서 백그라운드로 정리 중입니다. 저장 공간이 곧 확보됩니다", "項目較多，伺服器正在背景清理，儲存空間將稍後釋放"))
            }
            LibraryListing(files, emptyMap())
        }
        DriveLibrary.RECENT -> driveRepo.recentlyAdded().map(::eventListing)
        DriveLibrary.HISTORY -> driveRepo.playHistory().map(::eventListing)
        DriveLibrary.DUPLICATES -> error("${library.title}的内容来自会话，不经网络")
    }

    private fun showSession(library: DriveLibrary) {
        baseFiles = sessionListing(library)?.files.orEmpty()
        libraryEvents = emptyMap()
        loadedFolderId = activeFolderId
        loadError = null
        isLoading = false
    }

    fun selectOnly(ids: Set<String>) {
        if (ids.isEmpty()) return
        exitSelection()
        _selectedFileIds.addAll(ids)
    }

    private fun isSessionGroupExpanded(blockId: String): Boolean = expandedSections[expandKey(blockId)] ?: true

    private fun sessionItems(isExpanded: (blockId: String) -> Boolean): List<DriveListItem> =
        libraryView?.let(sessionListing)?.items(isExpanded).orEmpty().map { item ->
            if (!isNameParsing && item is DriveListItem.File) DriveListItem.File(item.file, null, item.key) else item
        }

    private fun folderSessionItems(isExpanded: (DriveBlock) -> Boolean): List<DriveListItem> {
        val listing = libraryView?.let(sessionListing) ?: return emptyList()
        val structure = currentAnalysis
        if (!isNameParsing) return files.map { DriveListItem.File(it, null) }
        if (!foldersFirst || structure == null || structure.blocks.isEmpty()) return files.map { file ->
            val view = structure?.views?.get(file.id)?.let { if (it.heading.isNotBlank()) it.withTitle(it.heading) else it }
            listing.decorate(DriveListItem.File(file, view))
        }
        val items = buildDriveItems(files, structure, hideFolded = false, isExpanded)
        val rows = items.filterIsInstance<DriveListItem.File>().mapTo(HashSet()) { it.file.id }
        val related = files.filter { it.id !in rows && it.id in structure.attachedTo }.groupBy { structure.attachedTo[it.id] }
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

    fun revealInDrive(file: FileStat) {
        scope.launch {
            driveRepo.locateFolder(file.id)
                .logFailure(TAG, "Failed to locate containing folder for ${logFile(file.id, file.name)}")
                .onSuccess { parents ->
                    navigation.navigateThen(parents) {
                        navigation.updateFolderStack(parents)
                        highlight(setOf(file.id))
                    }
                }
                .onFailure { _messages.tryEmit(tr("未找到所在文件夹", "Containing folder not found", "保存先フォルダーが見つかりません", "상위 폴더를 찾을 수 없습니다", "找不到所在資料夾")) }
        }
    }

    fun removeFromLibrary(ids: Collection<String>) {
        val library = libraryView?.takeIf { it.isEventLog } ?: return
        val eventIds = ids.mapNotNull { libraryEvents[it]?.id }
        if (eventIds.isEmpty()) return
        val kind = if (library == DriveLibrary.HISTORY) dev.piko.shared.data.HistoryKind.PLAY else dev.piko.shared.data.HistoryKind.RECENT
        submitHistoryAction(HistoryEditCommand.Remove(kind, eventIds))
    }

    fun clearPlayHistory() {
        submitHistoryAction(HistoryEditCommand.ClearPlay)
    }

    private fun submitHistoryAction(command: HistoryEditCommand) = actions.history(command)

    fun restoreFromTrash(ids: List<String>) { submitFileAction(FileWriteCommand.Restore(ids)) }

    fun deletePermanently(ids: List<String>) { submitFileAction(FileWriteCommand.Delete(ids)) }

    private fun usesPerFolderSort(folderId: String = activeFolderId): Boolean =
        perFolderSort && isDriveFolderId(folderId) && libraryView == null && archiveView == null && !isGlobalSearchActive

    private fun effectiveSortOrderFor(folderId: String): PikoFileSortOrder =
        if (perFolderSort && isDriveFolderId(folderId) && libraryView == null && archiveView == null) {
            withPikoLock(folderSortLock) { folderSortOrders[folderId] } ?: globalSortOrder
        } else {
            globalSortOrder
        }

    fun changeSortOrder(order: PikoFileSortOrder) {
        if (usesPerFolderSort()) {
            val folderId = activeFolderId
            val serialized = withPikoLock(folderSortLock) {
                folderSortOrders.remove(folderId)
                folderSortOrders[folderId] = order
                while (folderSortOrders.size > MAX_FOLDER_SORT_ENTRIES) {
                    val eldest = folderSortOrders.keys.firstOrNull() ?: break
                    folderSortOrders.remove(eldest)
                }
                encodeFolderSortOrders(folderSortOrders)
            }
            scope.launch { preferences.saveFolderSortOrders(serialized) }
        } else {
            globalSortOrder = order
        }
        if (order == sortOrder && !isGlobalSearchActive) return
        sortOrder = order
        if (isGlobalSearchActive) {
            val sortedFiles = driveRepo.sortFilesFast(globalSearchHits.map { it.file }, order, "", foldersFirst = foldersFirst)
            val byId = globalSearchHits.associateBy { it.file.id }
            val reordered = sortedFiles.mapNotNull { byId[it.id] }
            globalSearchHits.clear()
            globalSearchHits.addAll(reordered)
        } else if (loadedFolderId != activeFolderId || archiveView != null || libraryView?.isSession == true) {
            load()
        }
    }

    fun updateFoldersFirst(enabled: Boolean) {
        applyFoldersFirst(enabled)
        scope.launch { preferences.setFoldersFirst(enabled) }
    }

    private fun applyFoldersFirst(enabled: Boolean) {
        if (foldersFirst == enabled) return
        foldersFirst = enabled
        if (archiveView != null || libraryView?.isSession == true) return
        baseFiles = driveRepo.sortFilesFast(baseFiles, sortOrder, activeFolderId, foldersFirst = enabled)
        remoteFiles = driveRepo.sortFilesFast(remoteFiles, sortOrder, activeFolderId, foldersFirst = enabled)
        val byId = globalSearchHits.associateBy { it.file.id }
        val sortedHits = driveRepo.sortFilesFast(globalSearchHits.map { it.file }, sortOrder, "", foldersFirst = enabled)
        globalSearchHits.clear()
        globalSearchHits.addAll(sortedHits.mapNotNull { byId[it.id] })
    }

    fun setPerFolderSortEnabled(enabled: Boolean) {
        if (perFolderSort == enabled) return
        perFolderSort = enabled
        if (enabled && isDriveFolderId(activeFolderId) && libraryView == null && archiveView == null) {
            val folderId = activeFolderId
            val serialized = withPikoLock(folderSortLock) {
                if (folderId !in folderSortOrders) {
                    folderSortOrders[folderId] = sortOrder
                    encodeFolderSortOrders(folderSortOrders)
                } else null
            }
            if (serialized != null) {
                scope.launch { preferences.saveFolderSortOrders(serialized) }
            }
            val desired = effectiveSortOrderFor(folderId)
            if (sortOrder != desired) {
                sortOrder = desired
                load()
            }
        } else if (!enabled) {
            globalSortOrder = sortOrder
        }
        scope.launch { preferences.setPerFolderSort(enabled) }
    }

    fun openFolder(id: String, name: String) {
        navigation.pushFolder(id, name)
    }

    fun navigateUp(): Boolean = navigation.popFolder() != null

    val history get() = navigation.historyFlow

    fun goBack(): Boolean = navigation.goBack()

    fun goForward(): Boolean = navigation.goForward()

    fun navigateToBreadcrumb(index: Int) {
        navigation.popToBreadcrumb(index)
    }

    suspend fun goToPath(text: String): Boolean {
        val names = addressPathNames(text, addressBase())
        return driveRepo.resolveFolderPath(names, navigation.folderStackFlow.value).fold(
            onSuccess = { stack ->
                navigation.updateFolderStack(stack)
                true
            },
            onFailure = { error ->
                if (error !is PikoDriveRepository.FolderNotFoundException) {
                    PikoLog.w(TAG, "Failed to resolve address-bar path (segments=${names.size}, base=${logFolder(addressBase().lastOrNull()?.id)})", error)
                }
                _messages.emit(
                    if (error is PikoDriveRepository.FolderNotFoundException) error.message!! else tr("无法打开此路径，请检查网络连接后重试", "Unable to open this path. Check your network connection and retry", "このパスを開けません。ネットワーク接続を確認して再試行してください", "이 경로를 열 수 없습니다. 네트워크 연결을 확인한 후 다시 시도해 주세요", "無法開啟此路徑，請檢查網路連線後重試"),
                )
                false
            },
        )
    }

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
    private fun addressBase(): List<PikoPathBreadcrumb> =
        navigation.folderStackFlow.value.outsideArchives().takeIf { it.library == null } ?: listOf(PikoDriveRepository.ROOT_BREADCRUMB)

    suspend fun subfoldersOf(folderId: String): Result<List<PikoPathBreadcrumb>> {
        if (usesFolderStructure) {
            val files = libraryView?.let(sessionListing)?.filesInFolder(folderId).orEmpty()
            return Result.success(files.filter { it.isFolder }.map { PikoPathBreadcrumb(it.id, it.name) })
        }
        val previewFolders = importPreviewFilesFor(folderId).filter { it.isFolder }.map { PikoPathBreadcrumb(it.id, it.name) }
        if (ImportPreviewLocation.of(folderId) != null) return Result.success(previewFolders)
        if (ArchiveLocation.of(folderId) == null) return driveRepo.subfolders(folderId).map { previewFolders + it }
        return when (val level = folderMapLevel(folderId)) {
            is FolderMapLevel.Loaded -> Result.success(level.nodes.filter { it.expandable }.map { it.crumb })
            FolderMapLevel.NeedsPassword -> Result.failure(IllegalStateException(tr("需要密码", "Password required", "パスワードが必要です", "비밀번호가 필요합니다", "需要密碼")))
            is FolderMapLevel.Failed -> Result.failure(IllegalStateException(level.message))
        }
    }

    suspend fun folderMapLevel(id: String): FolderMapLevel {
        if (usesFolderStructure) {
            val files = libraryView?.let(sessionListing)?.filesInFolder(id).orEmpty()
            return FolderMapLevel.Loaded(files.map { file ->
                FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false, file = file.takeUnless { it.isFolder })
            })
        }
        val previewNodes = importPreviewFilesFor(id).map { file ->
            FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false, file = file.takeUnless { it.isFolder })
        }
        if (ImportPreviewLocation.of(id) != null) return FolderMapLevel.Loaded(previewNodes)
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
                FolderMapLevel.Loaded(previewNodes + files.map { file ->
                    when {
                        file.isFolder -> FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false)
                        archives != null && file.isExtractableArchive && file.hash.isNotEmpty() ->
                            FolderMapNode(PikoPathBreadcrumb(ArchiveLocation(file.id, file.hash, "").id, file.name), isArchive = true)
                        else -> FolderMapNode(PikoPathBreadcrumb(file.id, file.name), isArchive = false, file = file)
                    }
                })
            },
            onFailure = { error ->
                PikoLog.w(TAG, "Failed to load folder map level for ${logFolder(id)}", error)
                FolderMapLevel.Failed(tr("无法加载此文件夹，请检查网络连接", "Unable to load this folder. Check your network connection", "このフォルダーを読み込めません。ネットワーク接続を確認して再試行してください", "이 폴더를 불러올 수 없습니다. 네트워크 연결을 확인해 주세요", "無法載入此資料夾，請檢查網路連線"))
            },
        )
    }

    fun openPinned(folder: PikoPathBreadcrumb) {
        scope.launch {
            driveRepo.locateFolder(folder.id)
                .logFailure(TAG, "Failed to locate quick-access folder ${logFolder(folder.id)}")
                .onSuccess { parents -> navigation.updateFolderStack(parents + folder) }
                .onFailure { _messages.emit(tr("未找到此文件夹，可能已被删除", "Folder not found. It may have been deleted", "フォルダーが見つかりません。削除された可能性があります", "폴더를 찾을 수 없습니다. 삭제되었을 수 있습니다", "找不到此資料夾，可能已被刪除")) }
        }
    }

    fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) {
        navigation.navigateToFolder(breadcrumb)
    }

    fun updateTypeFilter(value: FileCategory?) {
        typeFilter = value
        if (isSelectionMode) exitSelection()
    }

    private fun onFolderChanged() {
        isSearchOpen = false
        typeFilter = null
        searchQuery = ""
        stopGlobalSearch()
        exitSelection()
        _revealedFileIds.clear()
        sortOrder = effectiveSortOrderFor(activeFolderId)
        load()
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
        if (isGlobalSearchActive) {
            if (value.isBlank()) {
                stopGlobalSearch()
            } else {
                startGlobalSearch()
            }
        } else {
            stopGlobalSearch()
        }
    }

    fun startGlobalSearch() = search.startGlobalSearch(searchQuery)
    fun cancelGlobalSearch() = search.cancelGlobalSearch()
    fun stopGlobalSearch() = search.stopGlobalSearch()

    fun setShowAllFiles(value: Boolean) {
        showAllDirectories[activeFolderId] = value
    }

    private suspend fun describeFolders(folders: List<Pair<FileStat, List<ChildFile>?>>) {
        folders.forEach { (folder, content) -> _folderViews[folder.id] = folderView(folder, content) }
    }

    private suspend fun folderView(folder: FileStat, content: List<ChildFile>?): DriveFolderView {
        while (true) {
            val revision = dev.piko.shared.i18n.PikoLocale.revision
            val cacheGeneration = DriveAnalysisCache.generation
            val language = dev.piko.shared.i18n.PikoLocale.effectiveLanguage
            val key = DriveAnalysisCache.folderKey(folder, content, language)
            DriveAnalysisCache.folderView(key)?.let { return it }
            val computed = withContext(Dispatchers.Default) { describeDriveFolder(folder.name, content) }
            if (dev.piko.shared.i18n.PikoLocale.revision == revision && dev.piko.shared.i18n.PikoLocale.effectiveLanguage == language) {
                DriveAnalysisCache.putFolderView(key, computed, cacheGeneration)
                return computed
            }
        }
    }

    suspend fun onFolderVisible(folder: FileStat) {
        if (isSearching || usesFolderStructure || folder.trashed || libraryView == DriveLibrary.TRASH || !isDriveFolderId(folder.id)) return
        val known = driveRepo.knownChildContents(folder.id)
        val wantsCover = folder.thumbnailLink.isEmpty()
        val wantsEmptiness = isNameParsing && known?.isEmpty() == true
        val wantsContent = known == null && (isNameParsing || wantsCover)
        if (!wantsContent && !wantsEmptiness && !wantsCover) return
        delay(PREFETCH_DWELL_MILLIS)
        if (wantsEmptiness) driveRepo.probeFolderEmptiness(folder.id)
        if (wantsContent) {
            val content = driveRepo.fetchChildContents(folder.id)
            if (content != null && isNameParsing) _folderViews[folder.id] = folderView(folder, content)
        }
        if (wantsCover) driveRepo.fetchVaultCover(folder.id)
    }

    fun toggleSpoiler(fileId: String) {
        if (!_revealedFileIds.remove(fileId)) _revealedFileIds.add(fileId)
    }

    fun enterSelection(fileId: String? = null) = selection.enterSelection(fileId)
    fun exitSelection() = selection.exitSelection()
    fun setSelected(fileId: String, selected: Boolean) = selection.setSelected(fileId, selected)
    fun toggleSelected(rowKey: String) = selection.toggleSelected(rowKey)
    fun selectRange(rowKey: String) = selection.selectRange(rowKey)
    fun selectBoxed(base: Set<String>, boxedRows: Collection<String>) = selection.selectBoxed(base, boxedRows)
    fun toggleSelectAll() = selection.toggleSelectAll()

    private fun canSelect(id: String): Boolean = !usesFolderStructure || files.none { it.id == id && it.isFolder }

    private var highlightSatisfiedFolderId: String? = null

    fun highlight(ids: Set<String>) {
        Snapshot.withMutableSnapshot {
            highlightSatisfiedFolderId = null
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
        highlightSatisfiedFolderId = null
        highlightedFileIds = emptySet()
    }

    private suspend fun catchUpWithHighlight(ids: Set<String>, folderId: String) {
        val satisfiedIn = highlightSatisfiedFolderId
        if (satisfiedIn != null && satisfiedIn != folderId) {
            clearHighlight()
            return
        }
        var unchangedUncachedCount = 0
        for (wait in HIGHLIGHT_RETRY_DELAYS) {
            loadJob?.join()
            if (activeFolderId != folderId) return
            val present = files.mapTo(HashSet()) { it.id }
            if (ids.any { it in present }) {
                highlightSatisfiedFolderId = folderId
            }
            if (ids.all { it in present }) return
            val snapshotBefore = remoteFiles.map { it.id to it.modifiedTime }
            delay(wait)
            load(useCache = false, showRefreshing = false)
            loadJob?.join()
            if (activeFolderId != folderId) return
            val afterPresent = files.mapTo(HashSet()) { it.id }
            if (ids.any { it in afterPresent }) {
                highlightSatisfiedFolderId = folderId
            }
            if (ids.all { it in afterPresent }) return
            val snapshotAfter = remoteFiles.map { it.id to it.modifiedTime }
            if (snapshotAfter == snapshotBefore) {
                unchangedUncachedCount++
                if (unchangedUncachedCount >= 2) return
            } else {
                unchangedUncachedCount = 0
            }
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank() || isVirtualPlace) return
        submitFileAction(FileWriteCommand.CreateFolder(activeFolder, name), clearSelection = false)
    }

    private fun refusedInArchive(ids: Collection<String>): Boolean {
        if (ids.none { ArchiveEntryId.isEntry(it) || ArchiveLocation.of(it) != null }) return false
        _messages.tryEmit(tr("压缩包内的文件仅支持打开或解压", "Files inside an archive can only be opened or extracted", "圧縮ファイル内の項目は、開くか解凍のみ可能です", "압축 파일 내 항목은 열거나 압축 해제만 할 수 있습니다", "壓縮檔內的檔案僅支援開啟或解壓縮"))
        return true
    }

    fun rename(fileId: String, newName: String) {
        if (newName.isBlank() || refusedInArchive(listOf(fileId))) return
        if (ImportPreviewEntryId.isEntry(fileId) || ImportPreviewLocation.of(fileId) != null) {
            _messages.tryEmit(tr("请在右侧面板中重命名待保存项目", "Rename unsaved items in the side panel", "保存前の項目はサイドパネルで名前を変更してください", "저장 전 항목은 측면 패널에서 이름을 변경해 주세요", "請在右側面板中重新命名待儲存項目"))
            return
        }
        val trimmed = newName.trimStart().trimEnd { it.isWhitespace() || it == '.' }
        if (trimmed.isEmpty() || dev.piko.shared.data.DriveNames.clean(trimmed) != trimmed) {
            _messages.tryEmit(tr("文件名含有无效字符或为空", "File name is empty or contains invalid characters", "ファイル名が空か、使用できない文字が含まれています", "파일 이름이 비어 있거나 잘못된 문자가 포함되어 있습니다", "檔案名稱含有無效字元或為空"))
            return
        }
        if (VaultEntry.isVaulted(fileId)) {
            renameInVault(fileId, trimmed)
            return
        }
        submitFileAction(FileWriteCommand.Rename(fileId, trimmed, knownFile(fileId)?.let { FileBefore(it.name, it.parentId) }), clearSelection = false)
    }

    fun setStarred(file: FileStat, starred: Boolean) = setStarred(listOf(file), starred)

    fun setStarred(targets: List<FileStat>, starred: Boolean) {
        if (!canAcceptAction || targets.isEmpty()) return
        val ids = targets.map { it.id }
        if (refusedInArchive(ids)) return
        val nonPreview = targets.filterNot { it.isImportPreview }
        if (nonPreview.size < targets.size) {
            _messages.tryEmit(tr("请先将链接项目保存至网盘", "Save link items to the drive first", "リンク項目を先にドライブへ保存してください", "링크 항목을 먼저 드라이브에 저장해 주세요", "請先將連結項目儲存至雲端硬碟"))
            if (nonPreview.isEmpty()) return
        }
        val (vaulted, real) = nonPreview.partition { it.isVaulted }
        if (vaulted.isNotEmpty()) {
            val withResolvedParent = vaulted.map { item ->
                if (item.parentId.isNotEmpty()) item
                else {
                    val knownParent = knownFile(item.id)?.parentId?.takeIf(String::isNotEmpty)
                        ?: activeFolderId.takeIf { isDriveFolderId(it) }.orEmpty()
                    if (knownParent.isNotEmpty()) item.copy(parentId = knownParent) else item
                }
            }
            driveRepo.setVaultStarred(withResolvedParent, starred)
            if (real.isEmpty()) {
                _messages.tryEmit(if (starred) tr("已添加星标", "Starred", "スターを付けました", "별표를 추가했습니다", "已新增星號") else tr("已取消星标", "Unstarred", "スターを外しました", "별표를 해제했습니다", "已取消星號"))
            }
        }
        if (real.isNotEmpty()) {
            submitFileAction(FileWriteCommand.Star(real.map { it.id }, starred), clearSelection = false)
        }
    }


    fun removeFromImportPreview(ids: Collection<String>) {
        val draft = instantSession?.state ?: return
        val attached = currentAnalysis?.attachedTo.orEmpty()
        val expandedIds = buildSet {
            addAll(ids)
            ids.forEach { hostId ->
                files.filter { attached[it.id] == hostId }.forEach { add(it.id) }
            }
        }
        val indices = mutableSetOf<Int>()
        for (id in expandedIds) {
            ImportPreviewEntryId.sourceIndexOf(id)?.let { indices += it }
            ImportPreviewLocation.of(id)?.let { loc ->
                val prefix = "${loc.path.trim('/')}/"
                draft.projectedSaveEntries.forEach { (idx, resolved) ->
                    val clean = resolved.path.trim('/')
                    if (clean == loc.path.trim('/') || clean.startsWith(prefix)) indices += idx
                }
            }
        }
        if (indices.isNotEmpty()) {
            draft.setSourceSelected(indices, false)
            exitSelection()
        }
    }

    fun moveToTrash(ids: List<String>) {
        if (!canAcceptAction) return
        if (refusedInArchive(ids)) return
        val distinct = ids.distinct().filter(::canSelect)
        val (importPreviewIds, nonPreviewIds) = distinct.partition {
            ImportPreviewEntryId.isEntry(it) || ImportPreviewLocation.of(it) != null
        }
        val (vaulted, real) = nonPreviewIds.partition(VaultEntry::isVaulted)
        val availability = fileActions.filesAvailability(real)
        if (availability is dev.piko.shared.presentation.ActionAvailability.Blocked) { _messages.tryEmit(availability.reason); return }
        val removal = vaultRemoval(vaulted)
        if (removal.entries.isNotEmpty()) {
            val vaultAvailability = vaultActions.availability(removal)
            if (vaultAvailability is dev.piko.shared.presentation.ActionAvailability.Blocked) { _messages.tryEmit(vaultAvailability.reason); return }
            if (!submitVaultEdit(removal, clearSelection = false)) return
        }
        if (importPreviewIds.isNotEmpty()) removeFromImportPreview(importPreviewIds)
        if (real.isNotEmpty()) submitFileAction(FileWriteCommand.Trash(real), clearSelection = false)
        exitSelection()
    }

    fun move(ids: List<String>, targetId: String, targetName: String, sources: Map<String, String> = emptyMap(), onSuccess: (() -> Unit)? = null): Boolean {
        fun sourceOf(id: String) = knownFile(id)?.parentId ?: sources[id]
        val moving = withoutVaulted(ids).filter { it != targetId && sourceOf(it) != targetId }
        if (moving.isEmpty()) return false
        val before = moving.mapNotNull { id ->
            (knownFile(id) ?: driveRepo.knownFile(id))?.takeUnless { it.trashed }
                ?.let { id to FileBefore(it.name, it.parentId) }
        }.toMap()
        return submitFileAction(FileWriteCommand.Move(moving, PikoPathBreadcrumb(targetId, targetName), before), onSuccess = onSuccess)
    }

    fun undoLast(): Boolean {
        if (!canAcceptAction) return false
        val change = driveRepo.changes.state.value.available.lastOrNull() ?: return false
        return when (val result = fileActions.undo(change.id)) {
            is dev.piko.shared.presentation.FileCommandResult.Accepted -> true
            is dev.piko.shared.presentation.FileCommandResult.Rejected -> { _messages.tryEmit(result.reason); true }
        }
    }
    private fun knownFile(id: String): FileStat? = displayedFiles.firstOrNull { it.id == id }
        ?: files.firstOrNull { it.id == id }
        ?: supplementalFiles().firstOrNull { it.id == id }
        ?: driveRepo.knownFile(id)

    fun putOnClipboard(requested: List<String>, cut: Boolean) {
        if (!canAcceptAction) return
        val ids = withoutVaulted(requested)
        if (ids.isEmpty()) return
        val origin = activeFolderStack.takeIf { isDriveFolderId(activeFolderId) }.orEmpty()
        clipboard.put(DriveClipboard(ids.associateWith { knownFile(it)?.parentId ?: activeFolderId }, cut, origin.toList()))
        exitSelection()
        val verb = if (cut) tr("剪切", "Cut", "切り取り", "잘라내기", "剪下") else tr("复制", "Copy", "コピー", "복사", "複製")
        _messages.tryEmit(if (ids.size == 1) tr("已$verb，请在目标文件夹中粘贴", "$verb. Paste in the destination folder", "$verb しました。移動先フォルダーで貼り付けてください", "$verb 완료. 대상 폴더에 붙여넣으세요", "已$verb，請至目標資料夾貼上") else tr("已$verb ${ids.size} 项，请在目标文件夹中粘贴", "$verb ${enCount(ids.size, "item", "items")}. Paste in the destination folder", "${ids.size} 項目を$verb しました。移動先フォルダーで貼り付けてください", "${ids.size}개 항목 $verb 완료. 대상 폴더에 붙여넣으세요", "已$verb ${ids.size} 項，請至目標資料夾貼上"))
    }

    fun paste() {
        if (!canAcceptAction) return
        val clip = clipboard.contents.value ?: return
        val target = navigation.folderStackFlow.value.lastOrNull()?.takeIf { isDriveFolderId(it.id) } ?: return
        val ids = clip.sources.keys.toList()
        val movingCount = if (clip.cut) withoutVaulted(ids).count {
            it != target.id && (knownFile(it)?.parentId ?: clip.sources[it]) != target.id
        } else ids.size
        val onSuccess = {
            if (clip.originStack.isNotEmpty() && clip.originStack.last().id != target.id) {
                val verb = if (clip.cut) tr("移动", "Move", "移動", "이동", "移動") else tr("复制", "Copy", "コピー", "복사", "複製")
                val targetLabel = target.localizedName
                _pasteNotices.tryEmit(PasteReturnNotice(tr("已将 $movingCount 项${verb}到「$targetLabel」", "$verb ${enCount(movingCount, "item", "items")} to “$targetLabel”", "$movingCount 項目を「$targetLabel」に${verb}しました", "${movingCount}개 항목을 “$targetLabel”(으)로 ${verb}했습니다", "已將 $movingCount 項${verb}至「$targetLabel」"), clip.originStack))
            }
            Unit
        }
        if (clip.cut) {
            if (move(ids, target.id, target.name, clip.sources, onSuccess)) clipboard.clear()
        } else {
            copy(ids, target.id, target.name, onSuccess)
        }
    }

    fun clearClipboard() = clipboard.clear()

    fun copy(requested: List<String>, targetId: String, targetName: String, onSuccess: (() -> Unit)? = null): Boolean {
        val ids = withoutVaulted(requested)
        if (ids.isEmpty()) return false
        return submitFileAction(FileWriteCommand.Copy(ids, PikoPathBreadcrumb(targetId, targetName)), onSuccess = onSuccess)
    }

    private fun withoutVaulted(ids: List<String>): List<String> {
        if (ids.any(dev.piko.shared.data.LocalLocation::isLocal)) {
            _messages.tryEmit(tr("本地文件夹为只读", "Local folders are read-only", "ローカルフォルダーは読み取り専用です", "로컬 폴더는 읽기 전용입니다", "本機資料夾為唯讀"))
            return emptyList()
        }
        if (refusedInArchive(ids)) return emptyList()
        val nonPreview = ids.filterNot { ImportPreviewEntryId.isEntry(it) || ImportPreviewLocation.of(it) != null }
        if (nonPreview.size < ids.size) _messages.tryEmit(tr("请先将链接项目保存至网盘", "Save link items to the drive first", "リンク項目を先にドライブへ保存してください", "링크 항목을 먼저 드라이브에 저장해 주세요", "請先將連結項目儲存至雲端硬碟"))
        val real = nonPreview.filterNot(VaultEntry::isVaulted)
        if (real.size < nonPreview.size) _messages.tryEmit(VAULTED_NEEDS_RESTORE)
        return real
    }

    private fun vaultedByFolder(ids: Collection<String>): Map<String, Set<String>> =
        ids.filter(VaultEntry::isVaulted)
            .groupBy { knownFile(it)?.parentId ?: activeFolderId }
            .mapValues { it.value.toSet() }

    private fun entryIds(virtualIds: Collection<String>): Set<String> = virtualIds.mapNotNullTo(HashSet(), VaultEntry::entryIdOf)

    private fun vaultRemoval(ids: Collection<String>) = VaultEntryEditCommand.Remove(
        vaultedByFolder(ids).mapValues { entryIds(it.value) },
    )

    private fun submitVaultEdit(command: VaultEntryEditCommand, clearSelection: Boolean = true) = actions.vault(command, clearSelection)

    fun removeFromVault(ids: Collection<String>) {
        val command = vaultRemoval(ids)
        if (command.entries.isNotEmpty()) submitVaultEdit(command)
    }

    private fun renameInVault(fileId: String, newName: String) {
        val folderId = knownFile(fileId)?.parentId ?: activeFolderId
        val entryId = VaultEntry.entryIdOf(fileId) ?: return
        submitVaultEdit(VaultEntryEditCommand.Rename(folderId, entryId, newName), clearSelection = false)
    }
}
