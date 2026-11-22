package dev.piko.shared.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.shared.data.InstantFileItem
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultMediaMeta
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFolder
import dev.piko.shared.memory.MemoryPolicy
import dev.piko.shared.memory.PikoMemoryGovernor
import dev.piko.shared.notice.PikoErrorMapper
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.ShareFolderPage
import io.github.nihildigit.pikpak.ShareInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * O(1) memory & toggle index selection for large file lists (10,000+ items).
 *
 * Stores only [exceptions] relative to [defaultSelected] and automatically flips polarity
 * if `exceptions.size > total / 2`, so selecting 20,000 items or toggling individual rows
 * never copies a 20,000-element [Set]. Implements [Set] for full API compatibility.
 */
class CompactIndexSelection private constructor(
    val total: Int,
    val defaultSelected: Boolean,
    val exceptions: Set<Int>,
) : AbstractSet<Int>() {

    override val size: Int
        get() = if (defaultSelected) (total - exceptions.size).coerceAtLeast(0) else exceptions.size

    val isAllSelected: Boolean
        get() = total > 0 && defaultSelected && exceptions.isEmpty()

    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: Int): Boolean {
        if (element !in 0 until total) return false
        return if (defaultSelected) element !in exceptions else element in exceptions
    }

    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        private var nextIndex = findNext(0)

        private fun findNext(from: Int): Int {
            var i = from
            while (i < total) {
                if (contains(i)) return i
                i++
            }
            return -1
        }

        override fun hasNext(): Boolean = nextIndex >= 0

        override fun next(): Int {
            val current = nextIndex
            if (current < 0) throw NoSuchElementException()
            nextIndex = findNext(current + 1)
            return current
        }
    }

    /** Counts how many indices in [indices] are selected in O(min(|exceptions|, |indices|)). */
    fun countIn(indices: Collection<Int>): Int {
        if (total <= 0 || indices.isEmpty()) return 0
        if (exceptions.isEmpty()) return if (defaultSelected) indices.size else 0
        if (indices is Set<Int> && exceptions.size < indices.size) {
            var diff = 0
            for (ex in exceptions) {
                if (ex in indices) diff++
            }
            return if (defaultSelected) indices.size - diff else diff
        }
        var count = 0
        for (idx in indices) {
            if (contains(idx)) count++
        }
        return count
    }

    fun withSelected(indices: Collection<Int>, selected: Boolean): CompactIndexSelection {
        if (total <= 0 || indices.isEmpty()) return this
        val next = LinkedHashSet(exceptions)
        var changed = false
        for (idx in indices) {
            if (idx !in 0 until total) continue
            val isException = selected != defaultSelected
            if (isException) {
                if (next.add(idx)) changed = true
            } else {
                if (next.remove(idx)) changed = true
            }
        }
        if (!changed) return this
        return normalize(total, defaultSelected, next)
    }

    fun withSingle(index: Int, selected: Boolean): CompactIndexSelection {
        if (index !in 0 until total) return this
        val already = contains(index)
        if (already == selected) return this
        val next = LinkedHashSet(exceptions)
        if (selected != defaultSelected) next.add(index) else next.remove(index)
        return normalize(total, defaultSelected, next)
    }

    fun selectedIndicesSorted(): List<Int> {
        val count = size
        if (count == 0) return emptyList()
        val result = ArrayList<Int>(count)
        if (!defaultSelected) {
            result.addAll(exceptions)
            result.sort()
        } else {
            for (i in 0 until total) {
                if (i !in exceptions) result.add(i)
            }
        }
        return result
    }

    companion object {
        val EMPTY = CompactIndexSelection(0, defaultSelected = false, exceptions = emptySet())

        fun all(total: Int): CompactIndexSelection =
            if (total <= 0) EMPTY else CompactIndexSelection(total, defaultSelected = true, exceptions = emptySet())

        fun none(total: Int): CompactIndexSelection =
            if (total <= 0) EMPTY else CompactIndexSelection(total, defaultSelected = false, exceptions = emptySet())

        fun fromSet(total: Int, selected: Set<Int>): CompactIndexSelection {
            if (total <= 0 || selected.isEmpty()) return none(total)
            if (selected is CompactIndexSelection && selected.total == total) return selected
            val validCount = if (selected.size <= total) {
                selected.count { it in 0 until total }
            } else {
                (0 until total).count { it in selected }
            }
            if (validCount == 0) return none(total)
            if (validCount == total) return all(total)
            return if (validCount > total / 2) {
                val excluded = HashSet<Int>(total - validCount)
                for (i in 0 until total) {
                    if (i !in selected) excluded.add(i)
                }
                CompactIndexSelection(total, defaultSelected = true, exceptions = excluded)
            } else {
                val included = HashSet<Int>(validCount)
                for (i in selected) {
                    if (i in 0 until total) included.add(i)
                }
                CompactIndexSelection(total, defaultSelected = false, exceptions = included)
            }
        }

        private fun normalize(total: Int, defaultSelected: Boolean, exceptions: Set<Int>): CompactIndexSelection {
            if (exceptions.isEmpty()) {
                return CompactIndexSelection(total, defaultSelected, emptySet())
            }
            if (exceptions.size >= total) {
                return CompactIndexSelection(total, !defaultSelected, emptySet())
            }
            if (exceptions.size > total / 2) {
                val flipped = HashSet<Int>(total - exceptions.size)
                for (i in 0 until total) {
                    if (i !in exceptions) flipped.add(i)
                }
                return CompactIndexSelection(total, !defaultSelected, flipped)
            }
            return CompactIndexSelection(total, defaultSelected, exceptions)
        }
    }
}

/** Network seam for [ShareTreeBrowser], enabling pure unit tests without a full HTTP stack. */
interface ShareTreeDataSource {
    suspend fun shareInfo(shareId: String, passCode: String): Result<ShareInfo>

    suspend fun shareFolderPage(
        shareId: String,
        passCodeToken: String,
        parentId: String,
        pageToken: String,
    ): Result<ShareFolderPage>

    suspend fun restoreFromShare(
        shareId: String,
        passCodeToken: String,
        fileIds: List<String>,
        toParentId: String,
        ancestorIds: List<String>,
    ): Result<Unit>

    /** Materializes partially-selected (`INDETERMINATE`) folders under destination during restore. */
    suspend fun createFolder(parentId: String, name: String): Result<String>
}

fun PikoDriveRepository.asShareTreeDataSource(): ShareTreeDataSource = object : ShareTreeDataSource {
    override suspend fun shareInfo(shareId: String, passCode: String): Result<ShareInfo> =
        this@asShareTreeDataSource.shareInfo(shareId, passCode)

    override suspend fun shareFolderPage(
        shareId: String,
        passCodeToken: String,
        parentId: String,
        pageToken: String,
    ): Result<ShareFolderPage> =
        this@asShareTreeDataSource.shareFolderPage(shareId, passCodeToken, parentId, pageToken)

    override suspend fun restoreFromShare(
        shareId: String,
        passCodeToken: String,
        fileIds: List<String>,
        toParentId: String,
        ancestorIds: List<String>,
    ): Result<Unit> =
        this@asShareTreeDataSource.restoreFromShare(shareId, passCodeToken, fileIds, toParentId, ancestorIds)

    override suspend fun createFolder(parentId: String, name: String): Result<String> =
        this@asShareTreeDataSource.createFolder(parentId, name)
}

enum class ShareNodeCheckState {
    OFF,
    ON,
    INDETERMINATE,
}

data class ShareTreeNode(
    val stat: FileStat,
    val parentId: String,
    val ancestorIds: List<String>,
    val relativePath: String,
) {
    val id: String get() = stat.id
    val name: String get() = stat.name
    val isFolder: Boolean get() = stat.isFolder
    val sizeBytes: Long get() = stat.sizeBytes
    val hash: String get() = stat.hash
    val depth: Int get() = ancestorIds.size
}

data class ShareFolderState(
    val loaded: Boolean = false,
    val children: List<ShareTreeNode> = emptyList(),
    val nextPageToken: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val hasMore: Boolean get() = nextPageToken.isNotEmpty()
}

sealed interface ShareTreeRow {
    val key: String
    val depth: Int

    data class NodeRow(
        val node: ShareTreeNode,
        override val depth: Int,
        val expanded: Boolean,
        val isLoading: Boolean,
        val checkState: ShareNodeCheckState,
    ) : ShareTreeRow {
        override val key: String get() = "snode:${node.id}"
    }

    data class LoadMoreRow(
        val parentId: String,
        override val depth: Int,
        val isLoading: Boolean,
        val errorMessage: String?,
    ) : ShareTreeRow {
        override val key: String get() = "smore:$parentId"
    }

    data class ErrorRow(
        val parentId: String,
        override val depth: Int,
        val message: String,
    ) : ShareTreeRow {
        override val key: String get() = "serr:$parentId"
    }

    data class EmptyRow(
        val parentId: String,
        override val depth: Int,
    ) : ShareTreeRow {
        override val key: String get() = "sempty:$parentId"
    }
}

data class ShareRestoreBatch(
    val ancestorIds: List<String>,
    val fileIds: List<String>,
    /**
     * Subfolder chain of partially-selected (`INDETERMINATE`) folders to materialize under the
     * user's destination folder so partial restores preserve nested directory structure instead
     * of flattening into the destination root.
     */
    val relativeDestinationPath: List<String> = emptyList(),
)

data class ShareRestoreProgress(
    val completedBatches: Int,
    val totalBatches: Int,
    val submittedItems: Int,
)

data class ShareVaultProgress(
    val foldersScanned: Int,
    val filesCollected: Int,
    val filesSaved: Int,
    val currentFolderName: String = "",
)

/**
 * Lazy on-demand paginated share tree browser & hierarchical selection engine.
 *
 * Avoids eagerly BFS-crawling 64+ subfolders or materializing tens of thousands of nodes:
 * - Loads only root page 1 up front (and optionally page 1 of a single root folder).
 * - Loads child pages on demand when a folder is expanded or "Load more" is requested, serialized
 *   per folder via a suspending [Mutex] so concurrent save-while-loading calls await cleanly.
 * - Uses O(1) inherited override selection so toggling or selecting 20,000+ nodes never copies giant sets.
 * - Submits whole unexpanded folders directly to server-side [ShareTreeDataSource.restoreFromShare],
 *   and materializes `INDETERMINATE` subfolders so partial restores preserve directory hierarchy.
 * - Streams Vault ("Save as reference") saves in bounded, memory-governed, cancellable batches.
 */
class ShareTreeBrowser(
    val shareId: String,
    val info: ShareInfo,
    private val dataSource: ShareTreeDataSource,
    private val scope: CoroutineScope,
    private val memoryGovernor: PikoMemoryGovernor = PikoMemoryGovernor.Shared,
    autoExpandSingleRoot: Boolean = true,
) {
    val passCodeToken: String = info.passCodeToken

    val title: String = info.files.singleOrNull()?.takeIf { it.isFolder && info.nextPageToken.isEmpty() }?.name
        ?: info.title.ifBlank { info.files.firstOrNull()?.name.orEmpty() }

    /** True whenever the share contains subfolders or paginated root listings. */
    val isLazyMode: Boolean = info.nextPageToken.isNotEmpty() || info.files.any { it.isFolder }

    private val stateLock = PikoLock()
    private val folders = LinkedHashMap<String, ShareFolderState>()
    private val nodesById = LinkedHashMap<String, ShareTreeNode>()
    private val expandedFolders = LinkedHashSet<String>()
    private val folderMutexes = HashMap<String, Mutex>()

    /**
     * Explicit selection overrides keyed by node ID (`""` is the share root).
     * Any node without an entry inherits from its nearest ancestor present in this map.
     * Fully-loaded folders whose children all agree collapse upwards so [overrides] stays tiny.
     */
    private val overrides = LinkedHashMap<String, Boolean>().apply { put(ROOT_ID, true) }

    /** Monotonic Compose state version bumped whenever tree content, expansion, or selection changes. */
    var revision by mutableIntStateOf(0)
        private set

    var selectionEdited by mutableStateOf(false)
        private set

    var restoreProgress by mutableStateOf<ShareRestoreProgress?>(null)
        private set

    var vaultProgress by mutableStateOf<ShareVaultProgress?>(null)
        private set

    init {
        val rootNodes = buildChildNodes(ROOT_ID, emptyList(), "", info.files)
        val singleRoot = rootNodes.singleOrNull()?.takeIf { it.isFolder && info.nextPageToken.isEmpty() }
        withPikoLock(stateLock) {
            rootNodes.forEach { nodesById[it.id] = it }
            folders[ROOT_ID] = ShareFolderState(
                loaded = true,
                children = rootNodes,
                nextPageToken = info.nextPageToken,
                isLoading = false,
                errorMessage = null,
            )
            if (autoExpandSingleRoot && singleRoot != null) {
                expandedFolders.add(singleRoot.id)
            }
        }
        // For single-folder shares, eagerly expand just page 1 of that single root folder (non-recursive).
        if (autoExpandSingleRoot && singleRoot != null) {
            scope.launch { loadNextPage(singleRoot.id) }
        }
    }

    val rootHasMore: Boolean
        get() {
            revision
            return withPikoLock(stateLock) { folders[ROOT_ID]?.hasMore == true }
        }

    val loadedNodeCount: Int
        get() {
            revision
            return withPikoLock(stateLock) { nodesById.size }
        }

    val loadedFileCount: Int
        get() {
            revision
            return withPikoLock(stateLock) { nodesById.values.count { !it.isFolder } }
        }

    val isAllSelected: Boolean
        get() {
            revision
            return withPikoLock(stateLock) { overrides[ROOT_ID] == true && overrides.size == 1 }
        }

    val hasSelection: Boolean
        get() {
            revision
            return withPikoLock(stateLock) {
                if (folders[ROOT_ID]?.children.isNullOrEmpty() && folders[ROOT_ID]?.hasMore != true) false
                else overrides[ROOT_ID] == true || overrides.values.any { it }
            }
        }

    /** Count of explicitly selected top-level / loaded items for concise UI summary. */
    val selectedSummaryText: String
        get() {
            revision
            val (anySelected, allSelected, hasMoreRoot, effectiveCount) = withPikoLock(stateLock) {
                val rootState = folders[ROOT_ID]
                val hasMore = rootState?.hasMore == true
                val any = !(rootState?.children.isNullOrEmpty() && !hasMore) &&
                    (overrides[ROOT_ID] == true || overrides.values.any { it })
                val all = overrides[ROOT_ID] == true && overrides.size == 1
                val count = if (any && !all) countSelectedEffectiveRootsLocked() else 0
                Quad(any, all, hasMore, count)
            }
            if (!anySelected) return tr("未选择项目", "No items selected", "項目が選択されていません", "선택한 항목 없음", "未選取項目")
            if (allSelected) {
                val totalHint = info.fileNum.toIntOrNull()?.takeIf { it > 0 }
                return if (totalHint != null && hasMoreRoot) {
                    tr("已全选（共约 $totalHint 项）", "All selected (~$totalHint items)", "すべて選択済み（約 $totalHint 項目）", "모두 선택됨 (약 ${totalHint}개)", "已全選（共約 $totalHint 項）")
                } else {
                    tr("已全选", "All selected", "すべて選択済み", "모두 선택됨", "已全選")
                }
            }
            return tr("已选 $effectiveCount 项", "$effectiveCount selected", "$effectiveCount 項目を選択済み", "${effectiveCount}개 선택됨", "已選 $effectiveCount 項")
        }

    private data class Quad(val a: Boolean, val b: Boolean, val c: Boolean, val d: Int)

    fun folderState(folderId: String): ShareFolderState {
        revision
        return withPikoLock(stateLock) { folders[folderId] ?: ShareFolderState() }
    }

    fun nodeById(nodeId: String): ShareTreeNode? = withPikoLock(stateLock) { nodesById[nodeId] }

    fun isExpanded(folderId: String): Boolean {
        revision
        return withPikoLock(stateLock) { folderId in expandedFolders }
    }

    fun effectiveSelection(nodeId: String, ancestorIds: List<String>): Boolean =
        withPikoLock(stateLock) { effectiveSelectionLocked(nodeId, ancestorIds) }

    fun checkStateOf(node: ShareTreeNode): ShareNodeCheckState =
        withPikoLock(stateLock) { checkStateOfLocked(node) }

    fun setAllSelected(selected: Boolean) {
        withPikoLock(stateLock) {
            overrides.clear()
            overrides[ROOT_ID] = selected
        }
        selectionEdited = true
        revision++
    }

    fun toggleSelectAll() {
        setAllSelected(!isAllSelected)
    }

    fun toggleNodeSelected(node: ShareTreeNode) {
        val next = checkStateOf(node) != ShareNodeCheckState.ON
        setNodeSelected(node, next)
    }

    fun setNodeSelected(node: ShareTreeNode, selected: Boolean) {
        withPikoLock(stateLock) {
            // 1. Remove any overrides inside [node]'s subtree when toggling a folder.
            if (node.isFolder && overrides.size > 1) {
                val iterator = overrides.entries.iterator()
                while (iterator.hasNext()) {
                    val (id, _) = iterator.next()
                    if (id != ROOT_ID && id != node.id) {
                        val target = nodesById[id]
                        if (target != null && node.id in target.ancestorIds) {
                            iterator.remove()
                        }
                    }
                }
            }
            // 2. Apply or prune override for [node] against its parent's inherited state.
            val parentBase = parentInheritedSelectionLocked(node)
            if (selected == parentBase) {
                overrides.remove(node.id)
            } else {
                overrides[node.id] = selected
            }
            // 3. Collapse fully-loaded ancestor folders whose children now all agree.
            collapseUpwardLocked(node.parentId)
        }
        selectionEdited = true
        revision++
    }

    fun toggleExpand(folderId: String) {
        val shouldLoad = withPikoLock(stateLock) {
            if (folderId in expandedFolders) {
                expandedFolders.remove(folderId)
                false
            } else {
                expandedFolders.add(folderId)
                val state = folders[folderId]
                state == null || (!state.loaded && !state.isLoading)
            }
        }
        revision++
        if (shouldLoad) {
            scope.launch { loadNextPage(folderId) }
        }
    }

    fun loadMore(parentId: String) {
        val canLoad = withPikoLock(stateLock) {
            val state = folders[parentId]
            state != null && !state.isLoading && state.hasMore
        }
        if (!canLoad) return
        scope.launch { loadNextPage(parentId) }
    }

    fun retryFolder(parentId: String) {
        val isLoading = withPikoLock(stateLock) { folders[parentId]?.isLoading == true }
        if (isLoading) return
        scope.launch { loadNextPage(parentId) }
    }

    /**
     * Loads page 1 (if unloaded) or the next page (if [ShareFolderState.hasMore]) of [parentId].
     *
     * Serialized per [parentId] via a suspending [Mutex]: concurrent callers (e.g. Save triggered
     * while `init` or `loadMore` is fetching the same folder) suspend until the in-flight page
     * finishes instead of returning synchronously without suspending.
     */
    suspend fun loadNextPage(parentId: String): Result<Unit> {
        val mutex = mutexFor(parentId)
        return mutex.withLock {
            val existing = withPikoLock(stateLock) {
                val cur = folders[parentId] ?: ShareFolderState()
                if (cur.loaded && !cur.hasMore) return@withLock Result.success(Unit)
                folders[parentId] = cur.copy(isLoading = true, errorMessage = null)
                cur
            }
            revision++

            val pageToken = if (existing.loaded) existing.nextPageToken else ""
            val result = try {
                dataSource.shareFolderPage(shareId, passCodeToken, parentId, pageToken)
            } catch (cancel: CancellationException) {
                withPikoLock(stateLock) {
                    val cur = folders[parentId]
                    if (cur != null && cur.isLoading) {
                        folders[parentId] = cur.copy(isLoading = false)
                    }
                }
                revision++
                throw cancel
            }

            result.fold(
                onSuccess = { page ->
                    withPikoLock(stateLock) {
                        val latest = folders[parentId] ?: existing
                        val parentNode = if (parentId == ROOT_ID) null else nodesById[parentId]
                        val ancestors = if (parentNode == null) emptyList() else parentNode.ancestorIds + parentNode.id
                        val parentPath = parentNode?.relativePath.orEmpty()
                        val existingIds = latest.children.mapTo(HashSet(latest.children.size)) { it.id }
                        val added = buildChildNodes(parentId, ancestors, parentPath, page.files)
                            .filter { it.id !in existingIds }
                        added.forEach { nodesById[it.id] = it }
                        val merged = if (latest.children.isEmpty()) added else latest.children + added
                        folders[parentId] = ShareFolderState(
                            loaded = true,
                            children = merged,
                            nextPageToken = page.nextPageToken,
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                    revision++
                    Result.success(Unit)
                },
                onFailure = { err ->
                    if (err is CancellationException) {
                        withPikoLock(stateLock) {
                            val cur = folders[parentId]
                            if (cur != null && cur.isLoading) folders[parentId] = cur.copy(isLoading = false)
                        }
                        revision++
                        throw err
                    }
                    PikoLog.w(TAG, "Failed to load share folder page (share=${maskShareId(shareId)}, parent=${logFolder(parentId)})", err)
                    withPikoLock(stateLock) {
                        val latest = folders[parentId] ?: existing
                        folders[parentId] = latest.copy(
                            isLoading = false,
                            errorMessage = PikoErrorMapper.failure(tr("加载文件夹", "Load folder", "フォルダー読み込み", "폴더 불러오기", "載入資料夾"), err),
                        )
                    }
                    revision++
                    Result.failure(err)
                },
            )
        }
    }

    /** Returns the flattened list of currently visible rows (filtered by [query] if non-blank). */
    fun visibleRows(query: String = ""): List<ShareTreeRow> {
        revision
        val trimmed = query.trim()
        return withPikoLock(stateLock) {
            if (trimmed.isNotEmpty()) {
                buildFilteredRowsLocked(trimmed)
            } else {
                val rows = ArrayList<ShareTreeRow>(64)
                appendFolderRowsLocked(ROOT_ID, depth = 0, out = rows)
                rows
            }
        }
    }

    /**
     * Computes server-side restore batches grouped by `(relativeDestinationPath, ancestorIds)`.
     *
     * Key guarantees:
     * - Whole selected folders (including unexpanded ones!) are emitted directly by folder ID
     *   without crawling their contents.
     * - Partially-selected (`INDETERMINATE`) folders record their folder name into
     *   [ShareRestoreBatch.relativeDestinationPath] so [restoreSelected] materializes those
     *   subfolders and preserves nested directory structure (no flattening or same-name collisions!).
     */
    suspend fun computeRestoreBatches(): Result<List<ShareRestoreBatch>> = runSuspendCatching {
        val grouped = LinkedHashMap<Pair<List<String>, List<String>>, MutableList<String>>()
        collectRestoreIds(ROOT_ID, emptyList(), emptyList(), grouped)
        val batches = ArrayList<ShareRestoreBatch>()
        for ((key, ids) in grouped) {
            val (destPath, ancestors) = key
            for (chunk in ids.distinct().chunked(RESTORE_BATCH_LIMIT)) {
                batches += ShareRestoreBatch(
                    ancestorIds = ancestors,
                    fileIds = chunk,
                    relativeDestinationPath = destPath,
                )
            }
        }
        batches
    }

    /**
     * Restores selected items directly via PikPak's server-side `share/restore` endpoint,
     * materializing any partially-selected (`INDETERMINATE`) subfolders under [toParentId].
     */
    suspend fun restoreSelected(toParentId: String): Result<Int> = runSuspendCatching {
        val batches = computeRestoreBatches().getOrThrow()
        if (batches.isEmpty()) return@runSuspendCatching 0
        val totalItems = batches.sumOf { it.fileIds.size }
        val materializedFolders = LinkedHashMap<List<String>, String>().apply {
            put(emptyList(), toParentId)
        }

        suspend fun ensureDestinationFolder(relativePath: List<String>): String {
            materializedFolders[relativePath]?.let { return it }
            var currentId = toParentId
            val prefix = ArrayList<String>(relativePath.size)
            for (rawSegment in relativePath) {
                currentCoroutineContext().ensureActive()
                prefix += rawSegment
                val key = prefix.toList()
                val existingId = materializedFolders[key]
                if (existingId != null) {
                    currentId = existingId
                } else {
                    val createdId = dataSource.createFolder(currentId, driveFolderName(rawSegment)).getOrThrow()
                    materializedFolders[key] = createdId
                    currentId = createdId
                }
            }
            return currentId
        }

        var submitted = 0
        restoreProgress = ShareRestoreProgress(0, batches.size, 0)
        try {
            batches.forEachIndexed { index, batch ->
                currentCoroutineContext().ensureActive()
                val destParentId = ensureDestinationFolder(batch.relativeDestinationPath)
                dataSource.restoreFromShare(
                    shareId = shareId,
                    passCodeToken = passCodeToken,
                    fileIds = batch.fileIds,
                    toParentId = destParentId,
                    ancestorIds = batch.ancestorIds,
                ).getOrThrow()
                submitted += batch.fileIds.size
                restoreProgress = ShareRestoreProgress(index + 1, batches.size, submitted)
            }
            totalItems
        } finally {
            restoreProgress = null
        }
    }

    /**
     * Bounded, cancellable, memory-governed streaming traversal for "Save as reference" (Vault)
     * on arbitrarily large shares (no 64-folder limit, no 20-page limit).
     *
     * Walks only selected subtrees, flushes collected GCID files in chunks to [onBatch], and
     * releases each chunk immediately so RAM stays bounded. Reads [PikoMemoryGovernor.policy]
     * dynamically for batch/concurrency sizing without holding the non-reentrant governor permit
     * across [onBatch] (whose vault manifest cache flush acquires [MemoryWorkKind.Persistence]).
     */
    suspend fun streamSelectedForVault(
        onProgress: (ShareVaultProgress) -> Unit = {},
        onBatch: suspend (List<InstantFileItem>) -> Result<Int>,
    ): Result<Int> = withContext(Dispatchers.Default) {
        val visitedFolders = HashSet<String>()
        var foldersScanned = 0
        var filesCollected = 0
        var filesSaved = 0
        val buffer = ArrayList<InstantFileItem>(VAULT_BATCH_NORMAL)

        fun publishProgress(folderName: String) {
            val next = ShareVaultProgress(foldersScanned, filesCollected, filesSaved, folderName)
            vaultProgress = next
            onProgress(next)
        }

        // Snapshot selection state once at save start so background traversal is consistent & lock-free.
        val selectionSnapshot = withPikoLock(stateLock) {
            SelectionSnapshot(
                overrides = LinkedHashMap(overrides),
                ancestorsByNodeId = nodesById.mapValues { it.value.ancestorIds },
                allSelected = overrides[ROOT_ID] == true && overrides.size == 1,
            )
        }

        suspend fun flushBuffer() {
            if (buffer.isEmpty()) return
            currentCoroutineContext().ensureActive()
            val batch = buffer.toList()
            buffer.clear()
            val savedNow = onBatch(batch).getOrThrow()
            filesSaved += savedNow
            publishProgress(vaultProgress?.currentFolderName.orEmpty())
        }

        fun currentBatchLimit(): Int = when (memoryGovernor.policy.value) {
            MemoryPolicy.Normal -> VAULT_BATCH_NORMAL
            MemoryPolicy.Constrained -> VAULT_BATCH_CONSTRAINED
            MemoryPolicy.Critical, MemoryPolicy.Background -> VAULT_BATCH_CRITICAL
        }

        fun singleFolderSafetyLimit(): Int = when (memoryGovernor.policy.value) {
            MemoryPolicy.Normal -> VAULT_SINGLE_FOLDER_MAX_NORMAL
            MemoryPolicy.Constrained -> VAULT_SINGLE_FOLDER_MAX_CONSTRAINED
            MemoryPolicy.Critical, MemoryPolicy.Background -> VAULT_SINGLE_FOLDER_MAX_CRITICAL
        }

        fun currentConcurrency(): Int = when (memoryGovernor.policy.value) {
            MemoryPolicy.Normal -> VAULT_CONCURRENCY_NORMAL
            MemoryPolicy.Constrained -> VAULT_CONCURRENCY_CONSTRAINED
            MemoryPolicy.Critical, MemoryPolicy.Background -> 1
        }

        data class FolderTask(
            val folderId: String,
            val ancestors: List<String>,
            val pathPrefix: String,
            val name: String,
            val wholeSelected: Boolean,
        )

        publishProgress(title)
        try {
            runSuspendCatching {
                val queue = ArrayDeque<FolderTask>()
                queue.addLast(
                    FolderTask(
                        folderId = ROOT_ID,
                        ancestors = emptyList(),
                        pathPrefix = "",
                        name = title,
                        wholeSelected = selectionSnapshot.allSelected,
                    ),
                )
                visitedFolders.add(ROOT_ID)

                while (queue.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val concurrency = currentConcurrency()
                    val wave = ArrayList<FolderTask>(concurrency)
                    while (wave.size < concurrency && queue.isNotEmpty()) {
                        wave += queue.removeFirst()
                    }

                    // Stream each folder in this wave page by page, keeping a single folder's files together
                    // so newly created subfolders write one complete v1 manifest via the fast path instead of
                    // rewriting v1..vN every 200 items.
                    for (task in wave) {
                        currentCoroutineContext().ensureActive()
                        foldersScanned++
                        publishProgress(task.name)

                        val cachedSnapshot = withPikoLock(stateLock) {
                            folders[task.folderId]?.takeIf { it.loaded }?.let { state ->
                                state.children.map { it.stat } to state.nextPageToken
                            }
                        }
                        var nextToken = ""
                        var firstLoop = true
                        val folderItems = ArrayList<InstantFileItem>()
                        val folderSafetyCap = singleFolderSafetyLimit()

                        while (firstLoop || nextToken.isNotEmpty()) {
                            currentCoroutineContext().ensureActive()
                            val entries: List<FileStat>
                            if (firstLoop && cachedSnapshot != null) {
                                entries = cachedSnapshot.first
                                nextToken = cachedSnapshot.second
                            } else {
                                val page = dataSource.shareFolderPage(shareId, passCodeToken, task.folderId, nextToken).getOrThrow()
                                entries = page.files
                                nextToken = page.nextPageToken
                            }
                            firstLoop = false

                            for (stat in entries) {
                                val relPath = if (task.pathPrefix.isEmpty()) stat.name else "${task.pathPrefix}/${stat.name}"
                                val nodeAncestors = if (task.folderId == ROOT_ID) emptyList() else task.ancestors
                                if (stat.isFolder) {
                                    if (!visitedFolders.add(stat.id)) continue
                                    val folderCheck = if (task.wholeSelected) {
                                        ShareNodeCheckState.ON
                                    } else {
                                        selectionSnapshot.checkFolderState(stat.id, nodeAncestors)
                                    }
                                    if (folderCheck != ShareNodeCheckState.OFF) {
                                        queue.addLast(
                                            FolderTask(
                                                folderId = stat.id,
                                                ancestors = nodeAncestors + stat.id,
                                                pathPrefix = relPath,
                                                name = stat.name,
                                                wholeSelected = folderCheck == ShareNodeCheckState.ON,
                                            ),
                                        )
                                    }
                                } else {
                                    val selected = task.wholeSelected || selectionSnapshot.isSelected(stat.id, nodeAncestors)
                                    if (!selected) continue
                                    val gcid = stat.hash.ifBlank { null } ?: continue
                                    val resolved = ResolvedFile(relPath, stat.sizeBytes, gcid)
                                    val meta = VaultMediaMeta.fromFileStat(stat).takeUnless { it.isEmpty }
                                    folderItems += InstantFileItem(resolved, isInstantReady = true, meta = meta)
                                    filesCollected++
                                    if (folderItems.size >= folderSafetyCap) {
                                        if (buffer.isNotEmpty()) flushBuffer()
                                        buffer.addAll(folderItems)
                                        folderItems.clear()
                                        flushBuffer()
                                    }
                                }
                            }
                            publishProgress(task.name)
                        }

                        if (folderItems.isNotEmpty()) {
                            val batchLimit = currentBatchLimit()
                            if (buffer.isNotEmpty() && buffer.size + folderItems.size > batchLimit) {
                                flushBuffer()
                            }
                            buffer.addAll(folderItems)
                            if (buffer.size >= batchLimit) {
                                flushBuffer()
                            }
                        }
                    }
                }
                flushBuffer()
                filesSaved
            }
        } finally {
            vaultProgress = null
        }
    }

    private class SelectionSnapshot(
        private val overrides: Map<String, Boolean>,
        private val ancestorsByNodeId: Map<String, List<String>>,
        val allSelected: Boolean,
    ) {
        fun isSelected(nodeId: String, ancestorIds: List<String>): Boolean {
            overrides[nodeId]?.let { return it }
            for (i in ancestorIds.lastIndex downTo 0) {
                overrides[ancestorIds[i]]?.let { return it }
            }
            return overrides[ROOT_ID] ?: true
        }

        fun checkFolderState(folderId: String, ancestorIds: List<String>): ShareNodeCheckState {
            val base = isSelected(folderId, ancestorIds)
            if (overrides.size <= 1) {
                return if (base) ShareNodeCheckState.ON else ShareNodeCheckState.OFF
            }
            for ((overrideId, value) in overrides) {
                if (overrideId == ROOT_ID || overrideId == folderId || value == base) continue
                val targetAncestors = ancestorsByNodeId[overrideId] ?: continue
                if (folderId in targetAncestors) return ShareNodeCheckState.INDETERMINATE
            }
            return if (base) ShareNodeCheckState.ON else ShareNodeCheckState.OFF
        }
    }

    private suspend fun collectRestoreIds(
        parentId: String,
        ancestors: List<String>,
        relativeDestPath: List<String>,
        out: MutableMap<Pair<List<String>, List<String>>, MutableList<String>>,
    ) {
        currentCoroutineContext().ensureActive()
        // If this parent still has unloaded pages and its base inherited selection is true,
        // paginate ONLY this parent so we don't drop items on later pages.
        val needsFullPages = withPikoLock(stateLock) {
            val parentBase = if (parentId == ROOT_ID) {
                overrides[ROOT_ID] ?: true
            } else {
                val node = nodesById[parentId]
                if (node != null) effectiveSelectionLocked(node.id, node.ancestorIds) else true
            }
            val state = folders[parentId]
            parentBase && (state == null || !state.loaded || state.hasMore)
        }
        if (needsFullPages) {
            loadAllPagesOfSingleFolder(parentId).getOrThrow()
        }

        // Snapshot children and their check states under lock before recursing.
        val snapshot = withPikoLock(stateLock) {
            folders[parentId]?.children.orEmpty().map { child ->
                child to checkStateOfLocked(child)
            }
        }
        val batchKey = relativeDestPath to ancestors
        for ((child, checkState) in snapshot) {
            if (!child.isFolder) {
                if (checkState == ShareNodeCheckState.ON) {
                    out.getOrPut(batchKey) { mutableListOf() }.add(child.id)
                }
            } else {
                when (checkState) {
                    ShareNodeCheckState.OFF -> Unit
                    ShareNodeCheckState.ON -> {
                        // Whole folder selected: restore server-side directly without crawling children!
                        out.getOrPut(batchKey) { mutableListOf() }.add(child.id)
                    }
                    ShareNodeCheckState.INDETERMINATE -> {
                        collectRestoreIds(
                            parentId = child.id,
                            ancestors = ancestors + child.id,
                            relativeDestPath = relativeDestPath + child.name,
                            out = out,
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadAllPagesOfSingleFolder(parentId: String): Result<Unit> = runSuspendCatching {
        while (true) {
            currentCoroutineContext().ensureActive()
            val done = withPikoLock(stateLock) {
                val state = folders[parentId]
                state != null && state.loaded && !state.hasMore
            }
            if (done) break
            // Suspends on per-folder Mutex if another load is in flight; propagates errors cleanly.
            loadNextPage(parentId).getOrThrow()
        }
    }

    private fun mutexFor(parentId: String): Mutex = withPikoLock(stateLock) {
        folderMutexes.getOrPut(parentId) { Mutex() }
    }

    private fun effectiveSelectionLocked(nodeId: String, ancestorIds: List<String>): Boolean {
        overrides[nodeId]?.let { return it }
        for (i in ancestorIds.lastIndex downTo 0) {
            overrides[ancestorIds[i]]?.let { return it }
        }
        return overrides[ROOT_ID] ?: true
    }

    private fun checkStateOfLocked(node: ShareTreeNode): ShareNodeCheckState {
        val base = effectiveSelectionLocked(node.id, node.ancestorIds)
        if (!node.isFolder) {
            return if (base) ShareNodeCheckState.ON else ShareNodeCheckState.OFF
        }
        if (overrides.size <= 1) {
            return if (base) ShareNodeCheckState.ON else ShareNodeCheckState.OFF
        }
        for ((overrideId, value) in overrides) {
            if (overrideId == ROOT_ID || overrideId == node.id || value == base) continue
            val target = nodesById[overrideId] ?: continue
            if (node.id in target.ancestorIds) {
                return ShareNodeCheckState.INDETERMINATE
            }
        }
        return if (base) ShareNodeCheckState.ON else ShareNodeCheckState.OFF
    }

    private fun appendFolderRowsLocked(parentId: String, depth: Int, out: MutableList<ShareTreeRow>) {
        val state = folders[parentId] ?: return
        if (state.loaded && state.children.isEmpty() && !state.hasMore && state.errorMessage == null && parentId != ROOT_ID) {
            out += ShareTreeRow.EmptyRow(parentId, depth)
            return
        }
        for (node in state.children) {
            val expanded = node.isFolder && node.id in expandedFolders
            val childFolder = if (node.isFolder) folders[node.id] else null
            out += ShareTreeRow.NodeRow(
                node = node,
                depth = depth,
                expanded = expanded,
                isLoading = node.isFolder && childFolder?.isLoading == true && !childFolder.loaded,
                checkState = checkStateOfLocked(node),
            )
            if (expanded) {
                if (childFolder != null) {
                    if (childFolder.errorMessage != null && !childFolder.loaded) {
                        out += ShareTreeRow.ErrorRow(node.id, depth + 1, childFolder.errorMessage)
                    } else if (childFolder.loaded) {
                        appendFolderRowsLocked(node.id, depth + 1, out)
                    }
                }
            }
        }
        if (state.hasMore) {
            out += ShareTreeRow.LoadMoreRow(
                parentId = parentId,
                depth = depth,
                isLoading = state.isLoading,
                errorMessage = state.errorMessage,
            )
        } else if (state.errorMessage != null && parentId == ROOT_ID) {
            out += ShareTreeRow.ErrorRow(parentId, depth, state.errorMessage)
        }
    }

    private fun buildFilteredRowsLocked(query: String): List<ShareTreeRow> {
        val matchingIds = HashSet<String>()
        for (node in nodesById.values) {
            if (node.name.contains(query, ignoreCase = true)) {
                matchingIds.add(node.id)
                matchingIds.addAll(node.ancestorIds)
            }
        }
        if (matchingIds.isEmpty()) return emptyList()
        val out = ArrayList<ShareTreeRow>(matchingIds.size)
        fun visit(parentId: String, depth: Int) {
            val children = folders[parentId]?.children ?: return
            for (node in children) {
                if (node.id !in matchingIds) continue
                out += ShareTreeRow.NodeRow(
                    node = node,
                    depth = depth,
                    expanded = node.isFolder,
                    isLoading = false,
                    checkState = checkStateOfLocked(node),
                )
                if (node.isFolder) visit(node.id, depth + 1)
            }
        }
        visit(ROOT_ID, 0)
        return out
    }

    private fun parentInheritedSelectionLocked(node: ShareTreeNode): Boolean {
        for (i in node.ancestorIds.lastIndex downTo 0) {
            overrides[node.ancestorIds[i]]?.let { return it }
        }
        return overrides[ROOT_ID] ?: true
    }

    private fun collapseUpwardLocked(startParentId: String) {
        var currentId = startParentId
        while (true) {
            val state = folders[currentId] ?: break
            if (!state.loaded || state.hasMore || state.children.isEmpty()) break
            var allOn = true
            var allOff = true
            for (child in state.children) {
                when (checkStateOfLocked(child)) {
                    ShareNodeCheckState.ON -> allOff = false
                    ShareNodeCheckState.OFF -> allOn = false
                    ShareNodeCheckState.INDETERMINATE -> {
                        allOn = false
                        allOff = false
                    }
                }
                if (!allOn && !allOff) break
            }
            if (!allOn && !allOff) break
            val unified = allOn
            for (child in state.children) {
                overrides.remove(child.id)
            }
            if (currentId == ROOT_ID) {
                overrides.clear()
                overrides[ROOT_ID] = unified
                break
            } else {
                val parentNode = nodesById[currentId] ?: break
                val grandParentBase = parentInheritedSelectionLocked(parentNode)
                if (unified == grandParentBase) {
                    overrides.remove(currentId)
                } else {
                    overrides[currentId] = unified
                }
                currentId = parentNode.parentId
            }
        }
    }

    private fun countSelectedEffectiveRootsLocked(): Int {
        var count = 0
        fun walk(parentId: String) {
            val children = folders[parentId]?.children ?: return
            for (child in children) {
                when (checkStateOfLocked(child)) {
                    ShareNodeCheckState.OFF -> Unit
                    ShareNodeCheckState.ON -> count++
                    ShareNodeCheckState.INDETERMINATE -> walk(child.id)
                }
            }
        }
        walk(ROOT_ID)
        return count
    }

    private fun buildChildNodes(
        parentId: String,
        ancestors: List<String>,
        parentPath: String,
        stats: List<FileStat>,
    ): List<ShareTreeNode> = stats.map { stat ->
        val rel = if (parentPath.isEmpty()) stat.name else "$parentPath/${stat.name}"
        ShareTreeNode(
            stat = stat,
            parentId = parentId,
            ancestorIds = ancestors,
            relativePath = rel,
        )
    }

    companion object {
        private const val TAG = "ShareTree"
        const val ROOT_ID = ""
        const val RESTORE_BATCH_LIMIT = 100
        private const val VAULT_BATCH_NORMAL = 200
        private const val VAULT_BATCH_CONSTRAINED = 100
        private const val VAULT_BATCH_CRITICAL = 50
        private const val VAULT_SINGLE_FOLDER_MAX_NORMAL = 25_000
        private const val VAULT_SINGLE_FOLDER_MAX_CONSTRAINED = 5_000
        private const val VAULT_SINGLE_FOLDER_MAX_CRITICAL = 1_000
        private const val VAULT_CONCURRENCY_NORMAL = 4
        private const val VAULT_CONCURRENCY_CONSTRAINED = 2

        internal fun maskShareId(id: String): String = if (id.length <= 6) "***" else "${id.take(4)}…"
    }
}
