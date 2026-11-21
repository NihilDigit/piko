@file:JvmMultifileClass
@file:JvmName("DriveScreenStateKt")

package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.data.ArchiveLocation
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.isImportPreview
import dev.piko.shared.presentation.DriveNavigation
import dev.piko.shared.presentation.HistoryCommands
import dev.piko.shared.upload.isUploading
import io.github.nihildigit.pikpak.FileStat
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class DriveScreenContext(
    val driveRepo: PikoDriveRepository,
    val preferences: PikoUserPreferences,
    val scope: CoroutineScope,
    val sessionListing: (DriveLibrary) -> SessionListing?,
    val archives: ArchiveBrowser?,
    val navigation: DriveNavigation,
    val historyActions: HistoryCommands,
    val historyEditSession: HistoryMutations,
    val instantSession: InstantSession?,
    val addLinkOwnerTabId: () -> Long?,
    val supplementalFiles: () -> List<FileStat>,
    val canAcceptAction: () -> Boolean,
    val activeFolder: () -> PikoPathBreadcrumb,
    val activeFolderId: () -> String,
    val activeFolderStack: () -> List<PikoPathBreadcrumb>,
    val libraryView: () -> DriveLibrary?,
    val usesFolderStructure: () -> Boolean,
    val archiveView: () -> ArchiveLocation?,
    val importPreviewView: () -> ImportPreviewLocation?,
    val isLocalPlace: () -> Boolean,
    val isVirtualPlace: () -> Boolean,
    val navigateUp: () -> Boolean,
    val openFolder: (String, String) -> Unit,
    val notify: (String) -> Unit,
    val notifySuspend: suspend (String) -> Unit,
) {
    lateinit var listing: DriveListingProjection
    lateinit var search: DriveSearchController
    lateinit var selection: DriveSelectionController
}

/**
 * 单选、Shift 连选、矩形框选、全选与查重同名异行 `rowKey`/`fileId` 映射控制器，
 * 同时管理外挂字幕/音轨随附项展开、防窥揭示状态与定位高亮追赶。
 */
internal class DriveSelectionController(
    private val ctx: DriveScreenContext,
) {
    private val selection = DriveSelectionState(
        rows = { ctx.listing.fileRows },
        visible = { ctx.listing.displayedFiles },
        canSelect = ::canSelect,
    )

    val selectedFileIds: List<String> get() = selection.ids
    val selectedIdSet: Set<String> get() = selection.idSet
    val isSelectionMode: Boolean get() = selection.active

    private val _revealedFileIds = mutableStateListOf<String>()
    val revealedFileIds: List<String> get() = _revealedFileIds

    var highlightedFileIds by mutableStateOf(ctx.navigation.takePendingHighlight())
        private set
    var highlightRevision by mutableStateOf(0)
        private set

    private var highlightSatisfiedFolderId: String? = null

    val highlightedRowIds: Set<String> by derivedStateOf {
        val ids = highlightedFileIds
        if (ids.isEmpty()) return@derivedStateOf emptySet()
        val rows = ctx.listing.displayItems.mapNotNullTo(HashSet()) { (it as? DriveListItem.File)?.file?.id }
        val hosts = ctx.listing.currentAnalysis?.attachedTo.orEmpty()
        ids.mapNotNullTo(HashSet()) { id -> id.takeIf { it in rows } ?: hosts[id]?.takeIf { it in rows } }
    }

    fun canSelect(id: String): Boolean =
        !ctx.usesFolderStructure() || ctx.listing.filesById[id]?.isFolder != true

    fun retainSelected(allowedIds: Set<String>) {
        selection.ids.retainAll(allowedIds)
        if (allowedIds.isEmpty()) exitSelection()
    }

    fun selectOnly(ids: Set<String>) {
        if (ids.isEmpty()) return
        selection.replaceSelected(ids)
    }

    fun enterSelection(fileId: String? = null) = selection.enterSelection(fileId)
    fun exitSelection() = selection.exitSelection()
    fun setSelected(fileId: String, selected: Boolean) = selection.setSelected(fileId, selected)
    fun toggleSelected(rowKey: String) = selection.toggleSelected(rowKey)
    fun selectRange(rowKey: String) = selection.selectRange(rowKey)
    fun selectFiles(fileIds: Iterable<String>) = selection.selectFiles(fileIds)
    fun selectBoxed(base: Set<String>, boxedRows: Collection<String>) = selection.selectBoxed(base, boxedRows)
    fun toggleSelectAll() = selection.toggleSelectAll()

    fun withAttachedFiles(selected: List<FileStat>): List<FileStat> {
        val attachedTo = ctx.listing.currentAnalysis?.attachedTo.orEmpty()
        val attachments = ctx.listing.files.filter { attachedTo[it.id] != null }.groupBy { attachedTo.getValue(it.id) }
        return selected.flatMap { listOf(it) + attachments[it.id].orEmpty() }
            .distinctBy { it.id }
            .filterNot { it.isUploading || it.isImportPreview }
    }

    fun toggleSpoiler(fileId: String) {
        if (!_revealedFileIds.remove(fileId)) _revealedFileIds.add(fileId)
    }

    fun clearRevealed() {
        _revealedFileIds.clear()
    }

    fun highlight(ids: Set<String>) {
        Snapshot.withMutableSnapshot {
            highlightSatisfiedFolderId = null
            ctx.listing.currentAnalysis?.let { revealHighlightedItems(it, ids) }
            highlightedFileIds = ids
            highlightRevision++
        }
    }

    fun revealHighlightedItems(structure: DriveStructure, ids: Set<String>) {
        if (ids.isEmpty()) return
        if (ids.any { it in structure.foldedIds }) ctx.listing.setShowAllFiles(true)
        structure.blocks.filter { block -> block.fileIds.any { it in ids } }.forEach { ctx.listing.expandSection(it.id) }
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
            ctx.listing.awaitLoad()
            if (ctx.activeFolderId() != folderId) return
            val present = ctx.listing.files.mapTo(HashSet()) { it.id }
            if (ids.any { it in present }) {
                highlightSatisfiedFolderId = folderId
            }
            if (ids.all { it in present }) return
            val snapshotBefore = ctx.listing.remoteFilesSnapshot()
            delay(wait)
            ctx.listing.load(useCache = false, showRefreshing = false)
            ctx.listing.awaitLoad()
            if (ctx.activeFolderId() != folderId) return
            val afterPresent = ctx.listing.files.mapTo(HashSet()) { it.id }
            if (ids.any { it in afterPresent }) {
                highlightSatisfiedFolderId = folderId
            }
            if (ids.all { it in afterPresent }) return
            val snapshotAfter = ctx.listing.remoteFilesSnapshot()
            if (snapshotAfter == snapshotBefore) {
                unchangedUncachedCount++
                if (unchangedUncachedCount >= 2) return
            } else {
                unchangedUncachedCount = 0
            }
        }
    }

    fun start() {
        ctx.scope.launch {
            ctx.navigation.pendingHighlights.collect { ids ->
                if (ids.isNotEmpty()) highlight(ctx.navigation.takePendingHighlight())
            }
        }
        ctx.scope.launch {
            snapshotFlow { highlightedFileIds to ctx.activeFolderId() }.collectLatest { (ids, folderId) ->
                if (ids.isNotEmpty()) catchUpWithHighlight(ids, folderId)
            }
        }
        ctx.scope.launch {
            snapshotFlow { highlightedFileIds to ctx.listing.currentAnalysis }.collect { (ids, structure) ->
                if (ids.isEmpty() || structure == null) return@collect
                revealHighlightedItems(structure, ids)
            }
        }
    }

    private companion object {
        val HIGHLIGHT_RETRY_DELAYS = listOf(300L, 600L, 800L, 1000L)
    }
}
