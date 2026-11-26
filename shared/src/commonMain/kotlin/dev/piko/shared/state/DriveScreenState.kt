@file:JvmMultifileClass
@file:JvmName("DriveScreenStateKt")

package dev.piko.shared.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.FileCategory
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.DriveClipboard
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.DriveNames
import dev.piko.shared.data.HistoryKind
import dev.piko.shared.data.ImportPreviewEntryId
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.data.isDriveFolderId
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.data.isVaulted
import dev.piko.shared.data.library
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.log.logFolder
import dev.piko.shared.presentation.ActionAvailability
import dev.piko.shared.presentation.DriveClipboardState
import dev.piko.shared.presentation.DriveNavigation
import dev.piko.shared.presentation.FileCommandResult
import dev.piko.shared.presentation.FileCommands
import dev.piko.shared.presentation.HistoryCommands
import dev.piko.shared.presentation.LibraryNavigation
import dev.piko.shared.presentation.ReceivingActivity
import dev.piko.shared.presentation.VaultEntryCommands
import dev.piko.shared.presentation.localizedName
import io.github.nihildigit.pikpak.DriveEvent
import io.github.nihildigit.pikpak.FileStat
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val DRIVE_SCREEN_TAG = "Drive"

private val VAULTED_NEEDS_RESTORE: String
    get() = tr("已归档项目需先恢复至网盘", "Restore archived items to the drive first", "アーカイブ済みの項目は先にドライブへ復元してください", "보관된 항목은 먼저 드라이브로 복원해 주세요", "已歸檔項目需先還原至雲端硬碟")

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

data class TrashProgress(val completed: Int, val total: Int)

data class PasteReturnNotice(val message: String, val originStack: List<PikoPathBreadcrumb>)

data class TrashFailure(val completed: Int, val remainingIds: List<String>)

@Stable
class DriveScreenState(
    private val driveRepo: PikoDriveRepository,
    private val preferences: PikoUserPreferences,
    private val scope: CoroutineScope,
    private val sessionListing: (DriveLibrary) -> SessionListing? = { null },
    private val archives: ArchiveBrowser? = null,
    initialSortOrder: PikoFileSortOrder? = null,
    val navigation: DriveNavigation = LibraryNavigation(driveRepo, null, scope, listOf(PikoDriveRepository.ROOT_BREADCRUMB)),
    private val clipboard: DriveClipboardState = DriveClipboardState(),
    operationScope: CoroutineScope = scope,
    private val fileWriteSession: FileWriteSession = FileWriteSession(driveRepo, operationScope),
    historyEditSession: HistoryMutations = HistoryMutations(driveRepo, operationScope),
    vaultEditSession: VaultEntryMutations = VaultEntryMutations(driveRepo, operationScope),
    folderVault: FolderVaultSession? = null,
    receiving: ReceivingActivity? = null,
    private val instantSession: InstantSession? = null,
    private val addLinkOwnerTabId: () -> Long? = { null },
    private val supplementalFiles: () -> List<FileStat> = { emptyList() },
) {
    class ArchivePasswordRequest(val archiveName: String, val incorrect: Boolean)

    private val browserAccount = driveRepo.currentAccount
    private val browserGeneration = driveRepo.accountGeneration
    private val canAcceptAction: Boolean
        get() = scope.isActive && driveRepo.currentAccount == browserAccount && driveRepo.accountGeneration == browserGeneration
    val acceptsActions: Boolean get() = canAcceptAction

    val fileActions = FileCommands(driveRepo, fileWriteSession, browserAccount, folderVault, receiving) { canAcceptAction }
    val vaultActions = VaultEntryCommands(driveRepo, vaultEditSession, fileActions, browserAccount) { canAcceptAction }
    val historyActions = HistoryCommands(driveRepo, historyEditSession, browserAccount) { canAcceptAction }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    private val _pasteNotices = MutableSharedFlow<PasteReturnNotice>(extraBufferCapacity = 8)
    val pasteNotices: SharedFlow<PasteReturnNotice> = _pasteNotices.asSharedFlow()
    val clipboardFlow: StateFlow<DriveClipboard?> get() = clipboard.contents

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
    val isLocalPlace: Boolean by derivedStateOf { LocalLocation.isLocal(activeFolderId) }
    val isVirtualPlace: Boolean by derivedStateOf { isLocalPlace || libraryView != null || archiveView != null || importPreviewView != null }

    private val ctx = DriveScreenContext(
        driveRepo = driveRepo,
        preferences = preferences,
        scope = scope,
        sessionListing = sessionListing,
        archives = archives,
        navigation = navigation,
        historyActions = historyActions,
        historyEditSession = historyEditSession,
        instantSession = instantSession,
        addLinkOwnerTabId = addLinkOwnerTabId,
        supplementalFiles = supplementalFiles,
        canAcceptAction = { canAcceptAction },
        activeFolder = { activeFolder },
        activeFolderId = { activeFolderId },
        activeFolderStack = { activeFolderStack },
        libraryView = { libraryView },
        usesFolderStructure = { usesFolderStructure },
        archiveView = { archiveView },
        importPreviewView = { importPreviewView },
        isLocalPlace = { isLocalPlace },
        isVirtualPlace = { isVirtualPlace },
        navigateUp = ::navigateUp,
        openFolder = ::openFolder,
        notify = { _messages.tryEmit(it) },
        notifySuspend = { _messages.emit(it) },
    )

    private val listingProjection = DriveListingProjection(ctx, initialSortOrder).also { ctx.listing = it }
    private val searchController = DriveSearchController(ctx).also { ctx.search = it }
    private val selectionController = DriveSelectionController(ctx).also { ctx.selection = it }

    private val actions = DriveActionController(
        fileActions, historyActions, vaultActions, fileWriteSession, scope,
        { canAcceptAction }, ::exitSelection, { _messages.tryEmit(it) },
    )

    init {
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
        listingProjection.start()
        searchController.start()
        selectionController.start()
    }

    val files: List<FileStat> get() = listingProjection.files
    val isLoading: Boolean get() = listingProjection.isLoading
    val isRefreshing: Boolean get() = listingProjection.isRefreshing
    val loadError: String? get() = listingProjection.loadError
    val sortOrder: PikoFileSortOrder get() = listingProjection.sortOrder
    val perFolderSort: Boolean get() = listingProjection.perFolderSort
    val foldersFirst: Boolean get() = listingProjection.foldersFirst

    val searchQuery: String get() = searchController.searchQuery
    val isSearchOpen: Boolean get() = searchController.isSearchOpen
    val isGlobalSearching: Boolean get() = searchController.isGlobalSearching
    val isGlobalSearchActive: Boolean get() = searchController.isGlobalSearchActive
    val typeFilter: FileCategory? get() = searchController.typeFilter
    val availableTypes: List<Pair<FileCategory, Int>> get() = searchController.availableTypes
    val hitLocations: Map<String, String> get() = searchController.hitLocations

    val selectedFileIds: List<String> get() = selectionController.selectedFileIds
    val selectedIdSet: Set<String> get() = selectionController.selectedIdSet
    val isSelectionMode: Boolean get() = selectionController.isSelectionMode
    val revealedFileIds: List<String> get() = selectionController.revealedFileIds
    val highlightedFileIds: Set<String> get() = selectionController.highlightedFileIds
    val highlightRevision: Int get() = selectionController.highlightRevision
    val highlightedRowIds: Set<String> get() = selectionController.highlightedRowIds

    val showAllFilesTemporarily: Boolean get() = listingProjection.showAllFilesTemporarily
    val isHeuristicFilterEnabled: Boolean get() = listingProjection.isHeuristicFilterEnabled
    val isNameParsing: Boolean get() = listingProjection.isNameParsing
    val libraryEvents: Map<String, DriveEvent> get() = listingProjection.libraryEvents
    val isDisplayStructureReady: Boolean get() = listingProjection.isDisplayStructureReady
    val folderEmptiness get() = driveRepo.folderEmptiness
    val vaultedFolders get() = driveRepo.vaultedFolders
    val fullyVaultedFolders get() = driveRepo.fullyVaultedFolders
    val folderViews: Map<String, DriveFolderView> get() = listingProjection.folderViews
    val potentialHiddenCount: Int get() = listingProjection.potentialHiddenCount
    val thumbnailsOnly: Boolean get() = listingProjection.thumbnailsOnly
    val displayItems: List<DriveListItem> get() = listingProjection.displayItems
    val displayedFiles: List<FileStat> get() = listingProjection.displayedFiles
    val sectionHeaders: List<IndexedValue<DriveListItem.SectionHeader>> get() = listingProjection.sectionHeaders
    val loadedFolderId: String? get() = listingProjection.loadedFolderId
    val archivePasswordRequest: ArchivePasswordRequest? get() = listingProjection.archivePasswordRequest
    val preparingEntryId: String? get() = listingProjection.preparingEntryId
    val isArchiveInImportPreview: Boolean get() = listingProjection.isArchiveInImportPreview

    fun openSearch() = searchController.openSearch()
    fun closeSearch() = searchController.closeSearch()
    fun updateSearchQuery(value: String) = searchController.updateSearchQuery(value)
    fun updateTypeFilter(value: FileCategory?) = searchController.updateTypeFilter(value)
    fun startGlobalSearch() = searchController.startGlobalSearch()
    fun cancelGlobalSearch() = searchController.cancelGlobalSearch()
    fun stopGlobalSearch() = searchController.stopGlobalSearch()

    fun updateThumbnailsOnly(value: Boolean) = listingProjection.updateThumbnailsOnly(value)
    fun withAttachedFiles(selected: List<FileStat>): List<FileStat> = selectionController.withAttachedFiles(selected)
    fun toggleSection(blockId: String) = listingProjection.toggleSection(blockId)
    fun expandSection(blockId: String) = listingProjection.expandSection(blockId)
    fun setShowAllFiles(value: Boolean) = listingProjection.setShowAllFiles(value)
    fun folderView(file: FileStat): DriveFolderView? = listingProjection.folderView(file)
    fun itemName(file: FileStat): DriveItemName = listingProjection.itemName(file)
    fun fileView(fileId: String): DriveFileView? = listingProjection.fileView(fileId)
    suspend fun onFolderVisible(folder: FileStat) = listingProjection.onFolderVisible(folder)

    fun load(refresh: Boolean = false) = listingProjection.load(refresh)
    fun openArchive(file: FileStat) = listingProjection.openArchive(file)
    fun submitArchivePassword(password: String) = listingProjection.submitArchivePassword(password)
    fun dismissArchivePassword() = listingProjection.dismissArchivePassword()
    suspend fun prepareArchiveEntry(file: FileStat): FileStat? = listingProjection.prepareArchiveEntry(file)
    fun currentArchive(): Pair<FileStat, String>? = listingProjection.currentArchive()
    fun archivePaths(items: List<FileStat>): List<String> = listingProjection.archivePaths(items)

    fun changeSortOrder(order: PikoFileSortOrder) = listingProjection.changeSortOrder(order)
    fun updateFoldersFirst(enabled: Boolean) = listingProjection.updateFoldersFirst(enabled)
    fun setPerFolderSortEnabled(enabled: Boolean) = listingProjection.setPerFolderSortEnabled(enabled)

    fun toggleSpoiler(fileId: String) = selectionController.toggleSpoiler(fileId)
    fun selectOnly(ids: Set<String>) = selectionController.selectOnly(ids)
    fun enterSelection(fileId: String? = null) = selectionController.enterSelection(fileId)
    fun exitSelection() = selectionController.exitSelection()
    fun setSelected(fileId: String, selected: Boolean) = selectionController.setSelected(fileId, selected)
    fun toggleSelected(rowKey: String) = selectionController.toggleSelected(rowKey)
    fun selectRange(rowKey: String) = selectionController.selectRange(rowKey)
    fun selectFiles(fileIds: Iterable<String>) = selectionController.selectFiles(fileIds)
    fun selectBoxed(base: Set<String>, boxedRows: Collection<String>) = selectionController.selectBoxed(base, boxedRows)
    fun toggleSelectAll() = selectionController.toggleSelectAll()
    fun highlight(ids: Set<String>) = selectionController.highlight(ids)
    fun clearHighlight() = selectionController.clearHighlight()

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

    fun openFolder(id: String, name: String) = navigation.pushFolder(id, name)
    fun navigateUp(): Boolean = navigation.popFolder() != null
    val history get() = navigation.historyFlow
    fun goBack(): Boolean = navigation.goBack()
    fun goForward(): Boolean = navigation.goForward()
    fun navigateToBreadcrumb(index: Int) = navigation.popToBreadcrumb(index)
    suspend fun goToPath(text: String): Boolean = searchController.goToPath(text)
    suspend fun addressCompletions(text: String): AddressCompletion = searchController.addressCompletions(text)
    suspend fun subfoldersOf(folderId: String): Result<List<PikoPathBreadcrumb>> = listingProjection.subfoldersOf(folderId)
    suspend fun folderMapLevel(id: String): FolderMapLevel = listingProjection.folderMapLevel(id)

    fun openPinned(folder: PikoPathBreadcrumb) {
        scope.launch {
            driveRepo.locateFolder(folder.id)
                .logFailure(DRIVE_SCREEN_TAG, "Failed to locate quick-access folder ${logFolder(folder.id)}")
                .onSuccess { parents -> navigation.updateFolderStack(parents + folder) }
                .onFailure { _messages.emit(tr("未找到此文件夹，可能已被删除", "Folder not found. It may have been deleted", "フォルダーが見つかりません。削除された可能性があります", "폴더를 찾을 수 없습니다. 삭제되었을 수 있습니다", "找不到此資料夾，可能已被刪除")) }
        }
    }

    fun navigateToFolder(breadcrumb: PikoPathBreadcrumb) = navigation.navigateToFolder(breadcrumb)

    fun revealInDrive(file: FileStat) {
        scope.launch {
            driveRepo.locateFolder(file.id)
                .logFailure(DRIVE_SCREEN_TAG, "Failed to locate containing folder for ${logFile(file.id, file.name)}")
                .onSuccess { parents ->
                    navigation.navigateThen(parents) {
                        navigation.updateFolderStack(parents)
                        highlight(setOf(file.id))
                    }
                }
                .onFailure { _messages.tryEmit(tr("未找到所在文件夹", "Containing folder not found", "保存先フォルダーが見つかりません", "상위 폴더를 찾을 수 없습니다", "找不到所在資料夾")) }
        }
    }

    private fun onFolderChanged() {
        searchController.resetOnFolderChanged()
        exitSelection()
        selectionController.clearRevealed()
        listingProjection.applyEffectiveSortOnFolderChange()
        load()
    }

    private fun submitFileAction(command: FileWriteCommand, clearSelection: Boolean = true, onSuccess: (() -> Unit)? = null) =
        actions.file(command, clearSelection, onSuccess)
    private fun submitHistoryAction(command: HistoryEditCommand) = actions.history(command)
    private fun submitVaultEdit(command: VaultEntryEditCommand, clearSelection: Boolean = true) = actions.vault(command, clearSelection)
    private fun knownFile(id: String): FileStat? = listingProjection.knownFile(id)

    fun removeFromLibrary(ids: Collection<String>) {
        val library = libraryView?.takeIf { it.isEventLog } ?: return
        val eventIds = ids.mapNotNull { libraryEvents[it]?.id }
        if (eventIds.isEmpty()) return
        val kind = if (library == DriveLibrary.HISTORY) HistoryKind.PLAY else HistoryKind.RECENT
        submitHistoryAction(HistoryEditCommand.Remove(kind, eventIds))
    }

    fun clearPlayHistory() { submitHistoryAction(HistoryEditCommand.ClearPlay) }
    fun restoreFromTrash(ids: List<String>) { submitFileAction(FileWriteCommand.Restore(ids)) }
    fun deletePermanently(ids: List<String>) { submitFileAction(FileWriteCommand.Delete(ids)) }

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
        if (trimmed.isEmpty() || DriveNames.clean(trimmed) != trimmed) {
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
                if (item.parentId.isNotEmpty()) item else {
                    val knownParent = knownFile(item.id)?.parentId?.takeIf(String::isNotEmpty) ?: activeFolderId.takeIf { isDriveFolderId(it) }.orEmpty()
                    if (knownParent.isNotEmpty()) item.copy(parentId = knownParent) else item
                }
            }
            driveRepo.setVaultStarred(withResolvedParent, starred)
            if (real.isEmpty()) {
                _messages.tryEmit(if (starred) tr("已添加星标", "Starred", "スターを付けました", "별표를 추가했습니다", "已新增星號") else tr("已取消星标", "Unstarred", "スターを外しました", "별표를 해제했습니다", "已取消星號"))
            }
        }
        if (real.isNotEmpty()) submitFileAction(FileWriteCommand.Star(real.map { it.id }, starred), clearSelection = false)
    }

    fun removeFromImportPreview(ids: Collection<String>) {
        val draft = instantSession?.state ?: return
        val attached = listingProjection.currentAnalysis?.attachedTo.orEmpty()
        val expandedIds = buildSet {
            addAll(ids)
            ids.forEach { hostId -> files.filter { attached[it.id] == hostId }.forEach { add(it.id) } }
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
        if (!canAcceptAction || refusedInArchive(ids)) return
        val distinct = ids.distinct().filter(selectionController::canSelect)
        val (importPreviewIds, nonPreviewIds) = distinct.partition { ImportPreviewEntryId.isEntry(it) || ImportPreviewLocation.of(it) != null }
        val (vaulted, real) = nonPreviewIds.partition(VaultEntry::isVaulted)
        val availability = fileActions.filesAvailability(real)
        if (availability is ActionAvailability.Blocked) { _messages.tryEmit(availability.reason); return }
        val removal = vaultRemoval(vaulted)
        if (removal.entries.isNotEmpty()) {
            val vaultAvailability = vaultActions.availability(removal)
            if (vaultAvailability is ActionAvailability.Blocked) { _messages.tryEmit(vaultAvailability.reason); return }
            if (!submitVaultEdit(removal, clearSelection = false)) return
        }
        if (importPreviewIds.isNotEmpty()) removeFromImportPreview(importPreviewIds)
        if (real.isNotEmpty()) submitFileAction(FileWriteCommand.Trash(real), clearSelection = false)
        exitSelection()
    }

    fun move(ids: List<String>, targetId: String, targetName: String, sources: Map<String, String> = emptyMap(), onSuccess: (() -> Unit)? = null): Boolean {
        if (!canAcceptAction) return false
        val eligible = editableCloudIds(ids)
        if (eligible.isEmpty()) return false
        fun sourceOf(id: String) = knownFile(id)?.parentId ?: sources[id]
        val (vaultedAll, realAll) = eligible.partition(VaultEntry::isVaulted)
        val realMoving = realAll.filter { it != targetId && sourceOf(it) != targetId }
        val vaultedBySource = vaultedAll.groupBy { sourceOf(it) ?: activeFolderId }
            .filterKeys { it != targetId }.mapValues { entryIds(it.value) }.filterValues { it.isNotEmpty() }
        if (realMoving.isEmpty() && vaultedBySource.isEmpty()) return false
        if (realMoving.isNotEmpty()) {
            val availability = fileActions.filesAvailability(realMoving)
            if (availability is ActionAvailability.Blocked) { _messages.tryEmit(availability.reason); return false }
        }
        val vaultMove = vaultedBySource.takeIf { it.isNotEmpty() }?.let { VaultEntryEditCommand.Move(it, targetId, targetName) }
        if (vaultMove != null) {
            val vaultAvailability = vaultActions.availability(vaultMove)
            if (vaultAvailability is ActionAvailability.Blocked) { _messages.tryEmit(vaultAvailability.reason); return false }
            if (!submitVaultEdit(vaultMove, clearSelection = realMoving.isEmpty())) return false
        }
        if (realMoving.isEmpty()) { onSuccess?.invoke(); return true }
        val before = realMoving.mapNotNull { id -> knownFile(id)?.takeUnless { it.trashed }?.let { id to FileBefore(it.name, it.parentId) } }.toMap()
        return submitFileAction(FileWriteCommand.Move(realMoving, PikoPathBreadcrumb(targetId, targetName), before), onSuccess = onSuccess)
    }

    fun undoLast(): Boolean {
        if (!canAcceptAction) return false
        val change = driveRepo.changes.state.value.available.lastOrNull() ?: return false
        return when (val result = fileActions.undo(change.id)) {
            is FileCommandResult.Accepted -> true
            is FileCommandResult.Rejected -> { _messages.tryEmit(result.reason); true }
        }
    }

    fun putOnClipboard(requested: List<String>, cut: Boolean) {
        if (!canAcceptAction) return
        val ids = if (cut) editableCloudIds(requested) else withoutVaulted(requested)
        if (ids.isEmpty()) return
        val origin = activeFolderStack.takeIf { isDriveFolderId(activeFolderId) }.orEmpty()
        clipboard.put(DriveClipboard(ids.associateWith { knownFile(it)?.parentId ?: activeFolderId }, cut, origin.toList()))
        exitSelection()
        val verb = if (cut) tr("剪切", "Cut", "切り取り", "잘라내기", "剪下") else tr("复制", "Copy", "コピー", "복사", "複製")
        _messages.tryEmit(
            if (ids.size == 1) tr("已$verb，请在目标文件夹中粘贴", "$verb. Paste in the destination folder", "$verb しました。移動先フォルダーで貼り付けてください", "$verb 완료. 대상 폴더에 붙여넣으세요", "已$verb，請至目標資料夾貼上")
            else tr("已$verb ${ids.size} 项，请在目标文件夹中粘贴", "$verb ${enCount(ids.size, "item", "items")}. Paste in the destination folder", "${ids.size} 項目を$verb しました。移動先フォルダーで貼り付けてください", "${ids.size}개 항목 $verb 완료. 대상 폴더에 붙여넣으세요", "已$verb ${ids.size} 項，請至目標資料夾貼上"),
        )
    }

    fun paste() {
        if (!canAcceptAction) return
        val clip = clipboard.contents.value ?: return
        val target = navigation.folderStackFlow.value.lastOrNull()?.takeIf { isDriveFolderId(it.id) } ?: return
        val ids = clip.sources.keys.toList()
        val movingCount = if (clip.cut) editableCloudIds(ids).count { it != target.id && (knownFile(it)?.parentId ?: clip.sources[it]) != target.id } else ids.size
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

    private fun editableCloudIds(ids: List<String>): List<String> {
        if (ids.any(LocalLocation::isLocal)) {
            _messages.tryEmit(tr("本地文件夹为只读", "Local folders are read-only", "ローカルフォルダーは読み取り専用です", "로컬 폴더는 읽기 전용입니다", "本機資料夾為唯讀"))
            return emptyList()
        }
        if (refusedInArchive(ids)) return emptyList()
        val nonPreview = ids.filterNot { ImportPreviewEntryId.isEntry(it) || ImportPreviewLocation.of(it) != null }
        if (nonPreview.size < ids.size) _messages.tryEmit(tr("请先将链接项目保存至网盘", "Save link items to the drive first", "リンク項目を先にドライブへ保存してください", "링크 항목을 먼저 드라이브에 저장해 주세요", "請先將連結項目儲存至雲端硬碟"))
        return nonPreview
    }

    private fun withoutVaulted(ids: List<String>): List<String> {
        val nonPreview = editableCloudIds(ids)
        val real = nonPreview.filterNot(VaultEntry::isVaulted)
        if (real.size < nonPreview.size) _messages.tryEmit(VAULTED_NEEDS_RESTORE)
        return real
    }

    private fun vaultedByFolder(ids: Collection<String>): Map<String, Set<String>> =
        ids.filter(VaultEntry::isVaulted).groupBy { knownFile(it)?.parentId ?: activeFolderId }.mapValues { it.value.toSet() }

    private fun entryIds(virtualIds: Collection<String>): Set<String> = virtualIds.mapNotNullTo(HashSet(), VaultEntry::entryIdOf)

    private fun vaultRemoval(ids: Collection<String>) = VaultEntryEditCommand.Remove(vaultedByFolder(ids).mapValues { entryIds(it.value) })

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
