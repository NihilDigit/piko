@file:JvmMultifileClass
@file:JvmName("DriveScreenStateKt")

package dev.piko.shared.state

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.data.library
import dev.piko.shared.data.outsideArchives
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFolder
import dev.piko.shared.presentation.localizedName
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import io.github.nihildigit.pikpak.SearchHit
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 目录内实时过滤（大目录 >500 项自动切换防抖后台过滤）、按文件类型筛选与全盘递归搜索控制器，
 * 维护命中项所属父路径映射（[hitLocations]）与搜索结果集增量修补。
 */
internal class DriveSearchController(
    private val ctx: DriveScreenContext,
) {
    private class AsyncLocalSearch(
        val folderId: String?,
        val query: String,
        val source: List<FileStat>,
        val results: List<FileStat>,
    )

    private data class AsyncLocalSearchInput(
        val folderId: String?,
        val query: String,
        val list: List<FileStat>,
        val parse: Boolean,
        val views: Map<String, DriveFileView>?,
        val folders: Map<String, DriveFolderView>,
    )

    var searchQuery by mutableStateOf("")
        private set
    var isSearchOpen by mutableStateOf(false)
        private set
    var typeFilter by mutableStateOf<FileCategory?>(null)
        private set

    private val session = DriveSearchSession(ctx.driveRepo, ctx.scope, ctx.notify)
    val isGlobalSearching: Boolean get() = session.isGlobalSearching
    val isGlobalSearchActive: Boolean get() = session.isGlobalSearchActive
    val globalSearchHits get() = session.globalSearchHits

    val isSearching: Boolean by derivedStateOf {
        isGlobalSearchActive || searchQuery.isNotBlank() || typeFilter != null
    }

    private var asyncLocalSearch by mutableStateOf<AsyncLocalSearch?>(null)

    val searchedFiles: List<FileStat> by derivedStateOf {
        val currentFiles = ctx.listing.files
        when {
            isGlobalSearchActive -> {
                ctx.listing.optimisticRevision
                ctx.driveRepo.projectOptimisticFiles(globalSearchHits.map { it.file })
            }
            searchQuery.isNotBlank() -> searchQuery.trim().let { query ->
                if (currentFiles.size <= ASYNC_LOCAL_SEARCH_THRESHOLD) {
                    currentFiles.filter { ctx.listing.listedName(it).matches(query) }
                } else {
                    val cached = asyncLocalSearch
                    val loadedFolderId = ctx.listing.loadedFolderId
                    val activeFolderId = ctx.activeFolderId()
                    when {
                        cached != null && cached.source === currentFiles -> cached.results
                        cached != null && cached.folderId != null && cached.folderId == loadedFolderId && cached.folderId == activeFolderId -> {
                            val byId = currentFiles.associateBy(FileStat::id)
                            cached.results.mapNotNull { byId[it.id] }
                        }
                        else -> currentFiles
                    }
                }
            }
            else -> currentFiles
        }
    }

    val availableTypes: List<Pair<FileCategory, Int>> by derivedStateOf {
        val counts = searchedFiles.filterNot { it.isFolder }.groupingBy { it.fileCategory() }.eachCount()
        FileCategory.entries.mapNotNull { category -> counts[category]?.let { category to it } }
    }

    val hitLocations: Map<String, String> by derivedStateOf {
        if (isGlobalSearchActive) {
            globalSearchHits.associate {
                it.file.id to it.parentPath.ifEmpty { PikoDriveRepository.ROOT_BREADCRUMB.localizedName }
            }
        } else {
            emptyMap()
        }
    }

    fun openSearch() {
        isSearchOpen = true
    }

    fun closeSearch() {
        isSearchOpen = false
        updateSearchQuery("")
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

    fun updateTypeFilter(value: FileCategory?) {
        typeFilter = value
        if (ctx.selection.isSelectionMode) ctx.selection.exitSelection()
    }

    fun resetOnFolderChanged() {
        isSearchOpen = false
        typeFilter = null
        searchQuery = ""
        stopGlobalSearch()
    }

    fun startGlobalSearch() = session.startGlobalSearch(searchQuery)
    fun cancelGlobalSearch() = session.cancelGlobalSearch()
    fun stopGlobalSearch() = session.stopGlobalSearch()

    suspend fun goToPath(text: String): Boolean {
        val base = addressBase()
        val names = addressPathNames(text, base)
        return ctx.driveRepo.resolveFolderPath(names, ctx.navigation.folderStackFlow.value).fold(
            onSuccess = { stack ->
                ctx.navigation.updateFolderStack(stack)
                true
            },
            onFailure = { error ->
                if (error !is PikoDriveRepository.FolderNotFoundException) {
                    PikoLog.w(DRIVE_SCREEN_TAG, "Failed to resolve address-bar path (segments=${names.size}, base=${logFolder(base.lastOrNull()?.id)})", error)
                }
                ctx.notifySuspend(
                    if (error is PikoDriveRepository.FolderNotFoundException) error.message!!
                    else tr("无法打开此路径，请检查网络连接后重试", "Unable to open this path. Check your network connection and retry", "このパスを開けません。ネットワーク接続を確認して再試行してください", "이 경로를 열 수 없습니다. 네트워크 연결을 확인한 후 다시 시도해 주세요", "無法開啟此路徑，請檢查網路連線後重試"),
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
        val parent = ctx.driveRepo.resolveFolderPath(addressPathNames(parentText, current)).getOrNull()
            ?: return AddressCompletion(partial, parentFound = false, matches = emptyList())
        val bases = if (cut < 0 && current.size > 1) listOf(current, parent) else listOf(parent)
        val matches = bases.flatMap { base ->
            val folders = ctx.driveRepo.subfolders(base.last().id).getOrNull().orEmpty()
            rankByName(folders, partial).map { base + it }
        }.distinctBy { it.last().id }.take(ADDRESS_COMPLETION_LIMIT)
        return AddressCompletion(partial, parentFound = true, matches = matches)
    }

    private fun addressBase(): List<PikoPathBreadcrumb> =
        ctx.navigation.folderStackFlow.value.outsideArchives().takeIf { it.library == null } ?: listOf(PikoDriveRepository.ROOT_BREADCRUMB)

    fun reorderGlobalHits(order: PikoFileSortOrder, foldersFirst: Boolean) {
        if (globalSearchHits.isEmpty()) return
        val byId = globalSearchHits.associateBy { it.file.id }
        val sortedFiles = ctx.driveRepo.sortFilesFast(globalSearchHits.map { it.file }, order, "", foldersFirst = foldersFirst)
        val reordered = sortedFiles.mapNotNull { byId[it.id] }
        globalSearchHits.clear()
        globalSearchHits.addAll(reordered)
    }

    fun replaceGlobalHitsIfChanged(hitsById: Map<String, SearchHit>, sortedFiles: List<FileStat>) {
        val hits = sortedFiles.mapNotNull { hitsById[it.id] }
        if (globalSearchHits.toList() != hits) {
            globalSearchHits.clear()
            globalSearchHits.addAll(hits)
        }
    }

    fun patchGlobalHits(matchesChange: (String) -> Boolean, patch: (FileStat) -> FileStat?): Boolean =
        globalSearchHits.any { matchesChange(it.file.id) || it.file.trashed }.also { shouldPatch ->
            if (shouldPatch) {
                val nextHits = globalSearchHits.mapNotNull { hit ->
                    patch(hit.file)?.takeUnless { it.trashed }?.let { hit.copy(file = it) }
                }
                if (nextHits != globalSearchHits.toList()) {
                    globalSearchHits.clear()
                    globalSearchHits.addAll(nextHits)
                }
            }
        }

    fun patchGlobalHitsStarred(targetIds: Set<String>, starred: Boolean) {
        if (!isGlobalSearchActive || globalSearchHits.none { it.file.id in targetIds }) return
        for (i in globalSearchHits.indices) {
            val hit = globalSearchHits[i]
            if (hit.file.id in targetIds) {
                val withoutStar = hit.file.tags.filterNot { it.name.equals("STAR", ignoreCase = true) }
                val nextTags = if (starred) withoutStar + FileTag(id = "STAR", name = "STAR") else withoutStar
                globalSearchHits[i] = hit.copy(file = hit.file.copy(tags = nextTags, starred = starred))
            }
        }
    }

    fun start() {
        ctx.scope.launch {
            var debouncedQuery = ""
            snapshotFlow {
                val query = searchQuery.trim()
                val list = ctx.listing.files
                if (isGlobalSearchActive || query.isEmpty() || list.size <= ASYNC_LOCAL_SEARCH_THRESHOLD) {
                    null
                } else {
                    val parse = ctx.listing.isNameParsing
                    val views = if (parse) ctx.listing.currentAnalysis?.views else null
                    val folders = if (parse) ctx.listing.folderViews.toMap() else emptyMap()
                    AsyncLocalSearchInput(ctx.listing.loadedFolderId, query, list, parse, views, folders)
                }
            }.collectLatest { input ->
                if (input == null) {
                    debouncedQuery = ""
                    if (asyncLocalSearch != null) asyncLocalSearch = null
                    return@collectLatest
                }
                if (input.query != debouncedQuery) {
                    delay(ASYNC_LOCAL_SEARCH_DEBOUNCE_MS)
                    debouncedQuery = input.query
                }
                val filtered = withContext(Dispatchers.Default) {
                    input.list.filter { file ->
                        val fileView = if (input.parse) input.views?.get(file.id) else null
                        val folderView = if (input.parse && file.isFolder) input.folders[file.id] else null
                        driveItemName(file, fileView, folderView).matches(input.query)
                    }
                }
                Snapshot.withMutableSnapshot {
                    if (ctx.canAcceptAction() &&
                        ctx.listing.files === input.list &&
                        ctx.listing.loadedFolderId == input.folderId &&
                        searchQuery.trim() == input.query &&
                        !isGlobalSearchActive
                    ) {
                        asyncLocalSearch = AsyncLocalSearch(input.folderId, input.query, input.list, filtered)
                    }
                }
            }
        }
    }

    private companion object {
        const val ASYNC_LOCAL_SEARCH_THRESHOLD = 500
        const val ASYNC_LOCAL_SEARCH_DEBOUNCE_MS = 80L
    }
}
