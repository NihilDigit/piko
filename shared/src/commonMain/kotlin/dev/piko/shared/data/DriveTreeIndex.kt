package dev.piko.shared.data

import dev.piko.data.repository.NaturalOrder
import dev.piko.shared.search.cjkFuzzyScore
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import io.github.nihildigit.pikpak.SearchHit
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 当前账号的本地文件树索引（文件夹、真实文件与已读出的归档虚拟条目）。
 *
 * 来源：
 * 1. 日常浏览列目录（[putFolderListing]）与读取归档清单（[putVaultEntries]）实时写入；
 * 2. 全盘搜索首次流式拉取平铺目录与文件（[putFolders]、[putFiles]）后拼齐整棵树并标为 [isComplete]；
 * 3. 网盘内的新建、改名、移动、移入回收站、删除经 [applyChange] 原地更新，无需整树作废。
 *
 * 有了完整本地树后，再次全盘搜索或边输边改搜索词可直接在内存中毫秒级完成 CJK 模糊匹配。
 */
internal class DriveTreeIndex {
    data class State(
        val entries: Map<String, FileStat> = emptyMap(),
        val childrenByParent: Map<String, Set<String>> = emptyMap(),
        val manifestsByFolder: Map<String, FileStat> = emptyMap(),
        val vaultEntriesByFolder: Map<String, List<VaultEntry>> = emptyMap(),
        val listedFolders: Set<String> = emptySet(),
        val dirtyFolders: Set<String> = emptySet(),
        val lastUsageBytes: Long? = null,
        val complete: Boolean = false,
        val revision: Long = 0L,
    )

    private val lock = Any()
    private val state = MutableStateFlow(State())
    val flow: StateFlow<State> = state.asStateFlow()

    private var mutationSeq: Long = 0L
    private var treeInvalidatedAtSeq: Long = 0L
    private var usagePatchedAtSeq: Long = 0L
    private val dirtyAtSeq = HashMap<String, Long>()
    private val listedAtSeq = HashMap<String, Long>()
    private val removedAtSeq = HashMap<String, Long>()
    private val renamedAtSeq = HashMap<String, Pair<Long, String>>()
    private val movedAtSeq = HashMap<String, Pair<Long, String>>()
    private val patchedAtSeq = HashMap<String, Pair<Long, FileStat>>()

    var lastFullSyncAtMillis: Long = 0L
        private set

    val currentMutationSeq: Long get() = synchronized(lock) { mutationSeq }

    enum class FolderCoverage { UNKNOWN, PARTIAL, COMPLETE, DIRTY }

    data class FolderChildren(val files: List<FileStat>, val coverage: FolderCoverage)

    private inline fun update(transform: (State) -> State) {
        synchronized(lock) {
            val current = state.value
            val next = transform(current).copy(revision = current.revision)
            if (next != current) {
                state.value = next.copy(revision = current.revision + 1)
            }
        }
    }

    val isComplete: Boolean get() = flow.value.complete

    val dirtyFolders: Set<String> get() = flow.value.dirtyFolders

    val lastUsageBytes: Long? get() = flow.value.lastUsageBytes

    fun setLastUsageBytes(bytes: Long?) {
        update { it.copy(lastUsageBytes = bytes) }
    }

    fun clear() {
        synchronized(lock) {
            mutationSeq = 0L
            treeInvalidatedAtSeq = 0L
            usagePatchedAtSeq = 0L
            dirtyAtSeq.clear()
            listedAtSeq.clear()
            removedAtSeq.clear()
            renamedAtSeq.clear()
            movedAtSeq.clear()
            patchedAtSeq.clear()
            lastFullSyncAtMillis = 0L
            val current = state.value
            val next = State(revision = current.revision)
            if (next != current) {
                state.value = next.copy(revision = current.revision + 1)
            }
        }
    }

    fun file(id: String): FileStat? = flow.value.entries[id]

    fun children(folderId: String): FolderChildren = children(folderId, realOnly = false)

    fun realChildren(folderId: String): FolderChildren = children(folderId, realOnly = true)

    private fun children(folderId: String, realOnly: Boolean): FolderChildren {
        val current = flow.value
        val ids = current.childrenByParent[folderId].orEmpty()
        val coverage = when {
            folderId in current.dirtyFolders -> FolderCoverage.DIRTY
            folderId in current.listedFolders -> FolderCoverage.COMPLETE
            ids.isNotEmpty() || folderId in current.entries -> FolderCoverage.PARTIAL
            else -> FolderCoverage.UNKNOWN
        }
        return FolderChildren(
            ids.mapNotNull { id ->
                current.entries[id]?.takeUnless { realOnly && VaultEntry.isVaulted(id) }
            },
            coverage,
        )
    }

    /** 完整列举替换真实子项；分页列举只合并已观察到的条目。返回因跨目录移动而受影响的原父目录集合。 */
    fun putFolderListing(parentId: String, files: List<FileStat>, complete: Boolean = true): Set<String> {
        val physical = files.filterNot { it.trashed || VaultEntry.isVaulted(it.id) }
        var reparentedFrom = emptySet<String>()
        update { current ->
            val seq = ++mutationSeq
            for (file in physical) {
                removedAtSeq.remove(file.id)
            }
            val movedParents = physical.mapNotNullTo(HashSet()) { file ->
                current.entries[file.id]?.parentId?.takeIf { it != parentId }
            }
            reparentedFrom = movedParents
            if (!complete) {
                val upserted = upsert(current, physical)
                return@update refreshManifestsForFolders(upserted, setOf(parentId) + movedParents)
            }
            val observed = physical.mapTo(HashSet()) { it.id }
            val winningManifest = VaultStore.winnerOf(physical)
            val prevManifest = current.manifestsByFolder[parentId]
            val clearStaleVault = prevManifest != null && winningManifest?.id != prevManifest.id
            val missing = current.childrenByParent[parentId].orEmpty().filterTo(HashSet()) {
                if (VaultEntry.isVaulted(it)) clearStaleVault else it !in observed
            }
            // 一次列举只能证明子目录已离开此父目录，不能证明其后代已删除；但其整棵子树失去已列举完备性。
            val directDetachedFolders = missing.filterTo(HashSet()) { current.entries[it]?.isFolder == true }
            val detachedSubtreeFolders = collectDescendantFolders(current, directDetachedFolders)
            listedAtSeq[parentId] = seq
            dirtyAtSeq.remove(parentId)
            for (detachedId in detachedSubtreeFolders) {
                dirtyAtSeq[detachedId] = seq
            }
            for (movedParent in movedParents) {
                listedAtSeq[movedParent] = seq
            }
            val upserted = upsert(current, physical, missing)
            val updatedManifests = if (winningManifest != null) {
                upserted.manifestsByFolder + (parentId to winningManifest)
            } else {
                upserted.manifestsByFolder - parentId
            }
            val updatedVaultEntries = if (clearStaleVault) {
                upserted.vaultEntriesByFolder - parentId
            } else {
                upserted.vaultEntriesByFolder
            }
            refreshManifestsForFolders(
                upserted.copy(
                    manifestsByFolder = updatedManifests,
                    vaultEntriesByFolder = updatedVaultEntries,
                    listedFolders = (upserted.listedFolders - detachedSubtreeFolders) + parentId,
                    dirtyFolders = (upserted.dirtyFolders + detachedSubtreeFolders) - parentId,
                ),
                movedParents,
            )
        }
        return reparentedFrom
    }

    /**
     * 某个文件夹下的归档条目（保留完整条目表以供已取回文件回填元数据，并将仍在归档中的条目投影为虚拟行）。
     * 若 [requireMatch] 为 true，仅当目录当前胜出清单仍为 [expectedManifestId] 时才写入，防止旧清单的异步读取覆盖新清单状态。
     */
    fun putVaultEntries(
        folderId: String,
        entries: List<VaultEntry>,
        expectedManifestId: String? = null,
        requireMatch: Boolean = false,
    ): Boolean {
        val virtualStats = entries.filter { it.isArchived }.map { it.toFileStat(folderId) }
        val storedEntries = entries.toList()
        var accepted = true
        update { current ->
            if (requireMatch) {
                val hasAuthoritativeManifestState =
                    folderId in current.manifestsByFolder || folderId in current.listedFolders || current.complete
                if (hasAuthoritativeManifestState && current.manifestsByFolder[folderId]?.id != expectedManifestId) {
                    accepted = false
                    return@update current
                }
            }
            val replaced = current.childrenByParent[folderId].orEmpty().filterTo(HashSet()) {
                VaultEntry.isVaulted(it)
            }
            val nextVaultMap = if (storedEntries.isEmpty()) {
                current.vaultEntriesByFolder - folderId
            } else {
                current.vaultEntriesByFolder + (folderId to storedEntries)
            }
            upsert(current, virtualStats, replaced).copy(vaultEntriesByFolder = nextVaultMap)
        }
        return accepted
    }

    /** 全盘平铺拉回的文件夹列表。 */
    fun putFolders(folders: List<FileStat>) {
        putFiles(folders)
    }

    /** 全盘平铺分页拉回的真实文件列表。 */
    fun putFiles(files: List<FileStat>) {
        val physical = files.filterNot { it.trashed || VaultEntry.isVaulted(it.id) }
        if (physical.isEmpty()) return
        update { current ->
            for (file in physical) {
                removedAtSeq.remove(file.id)
            }
            val touchedManifestFolders = HashSet<String>()
            for (file in physical) {
                if (VaultStore.looksLikeManifest(file)) {
                    touchedManifestFolders += file.parentId
                    current.entries[file.id]?.parentId?.let(touchedManifestFolders::add)
                }
            }
            refreshManifestsForFolders(upsert(current, physical), touchedManifestFolders)
        }
    }

    /** 哨兵探针增量补丁：写入新增/修改的节点并记入变更日志，防止并发进行的后台整树重建回滚补丁。 */
    fun applyCanaryPatch(files: List<FileStat>, usageBytes: Long? = null) {
        val physical = files.filterNot { it.trashed || VaultEntry.isVaulted(it.id) }
        if (physical.isEmpty() && usageBytes == null) return
        update { current ->
            val seq = ++mutationSeq
            val touchedManifestFolders = HashSet<String>()
            for (file in physical) {
                removedAtSeq.remove(file.id)
                patchedAtSeq[file.id] = seq to file
                if (VaultStore.looksLikeManifest(file)) {
                    touchedManifestFolders += file.parentId
                    current.entries[file.id]?.parentId?.let(touchedManifestFolders::add)
                }
            }
            val next = if (physical.isNotEmpty()) {
                refreshManifestsForFolders(upsert(current, physical), touchedManifestFolders)
            } else {
                current
            }
            if (usageBytes != null) {
                usagePatchedAtSeq = seq
                next.copy(lastUsageBytes = usageBytes)
            } else {
                next
            }
        }
    }

    fun markComplete() {
        update { it.copy(complete = true) }
    }

    fun applyChange(change: DriveChange) {
        update { current ->
            val seq = ++mutationSeq
            when (change) {
                is DriveChange.Renamed -> {
                    renamedAtSeq[change.id] = seq to change.name
                    val target = current.entries[change.id] ?: return@update current
                    current.copy(entries = current.entries + (change.id to target.copy(name = change.name)))
                }
                is DriveChange.Moved -> {
                    val known = change.ids.mapNotNull { current.entries[it] }
                    val affected = change.folders.orEmpty() + known.map { it.parentId } + change.to
                    for (id in change.ids) {
                        movedAtSeq[id] = seq to change.to
                    }
                    for (folderId in affected) {
                        dirtyAtSeq[folderId] = seq
                    }
                    upsert(current, known.map { it.copy(parentId = change.to) }).copy(
                        dirtyFolders = current.dirtyFolders + affected,
                    )
                }
                is DriveChange.Trashed -> {
                    for (id in change.ids) {
                        removedAtSeq[id] = seq
                        renamedAtSeq.remove(id)
                        movedAtSeq.remove(id)
                        patchedAtSeq.remove(id)
                    }
                    for (folderId in change.folders.orEmpty()) {
                        dirtyAtSeq[folderId] = seq
                    }
                    removeEntriesAndDescendants(current, change.ids.toSet())
                }
                is DriveChange.Deleted -> {
                    for (id in change.ids) {
                        removedAtSeq[id] = seq
                        renamedAtSeq.remove(id)
                        movedAtSeq.remove(id)
                        patchedAtSeq.remove(id)
                    }
                    for (folderId in change.folders) {
                        dirtyAtSeq[folderId] = seq
                    }
                    removeEntriesAndDescendants(current, change.ids.toSet())
                }
                is DriveChange.Created -> {
                    for (folderId in change.folders) {
                        dirtyAtSeq[folderId] = seq
                    }
                    current.copy(dirtyFolders = current.dirtyFolders + change.folders)
                }
                is DriveChange.Copied -> {
                    for (folderId in change.folders.orEmpty()) {
                        dirtyAtSeq[folderId] = seq
                    }
                    current.copy(dirtyFolders = current.dirtyFolders + change.folders.orEmpty())
                }
                is DriveChange.Restored, is DriveChange.ContentsChanged -> {
                    val folders = change.folders
                    if (folders == null) {
                        treeInvalidatedAtSeq = seq
                        current.copy(complete = false, listedFolders = emptySet(), dirtyFolders = emptySet())
                    } else {
                        for (folderId in folders) {
                            dirtyAtSeq[folderId] = seq
                        }
                        current.copy(dirtyFolders = current.dirtyFolders + folders)
                    }
                }
            }
        }
    }

    private fun upsert(current: State, added: List<FileStat>, removed: Set<String> = emptySet()): State {
        if (added.isEmpty() && removed.isEmpty()) return current
        val entries = current.entries.toMutableMap()
        val children = current.childrenByParent.toMutableMap()
        // 每个受影响的桶只复制一次，避免批量写入时反复复制同一组兄弟节点。
        val changedBuckets = HashMap<String, MutableSet<String>>()
        fun bucket(parentId: String): MutableSet<String> = changedBuckets.getOrPut(parentId) {
            children[parentId].orEmpty().toMutableSet()
        }
        fun detach(entry: FileStat) {
            bucket(entry.parentId).remove(entry.id)
        }
        for (id in removed) entries.remove(id)?.let(::detach)
        for (entry in added) {
            if (entry.trashed) continue
            val old = entries.put(entry.id, entry)
            if (old != null && old.parentId != entry.parentId) detach(old)
            bucket(entry.parentId).add(entry.id)
        }
        for ((parentId, ids) in changedBuckets) {
            if (ids.isEmpty()) children.remove(parentId) else children[parentId] = ids.toSet()
        }
        return current.copy(entries = entries.toMap(), childrenByParent = children.toMap())
    }

    private fun collectDescendantFolders(current: State, rootFolders: Set<String>): Set<String> {
        if (rootFolders.isEmpty()) return emptySet()
        val visited = HashSet<String>()
        val pending = ArrayDeque<String>()
        pending.addAll(rootFolders)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            for (childId in current.childrenByParent[id].orEmpty()) {
                if (current.entries[childId]?.isFolder == true || childId in current.childrenByParent) {
                    pending.add(childId)
                }
            }
        }
        return visited
    }

    private fun refreshManifestsForFolders(current: State, folderIds: Set<String>): State {
        if (folderIds.isEmpty()) return current
        val manifests = current.manifestsByFolder.toMutableMap()
        val vaultByFolder = current.vaultEntriesByFolder.toMutableMap()
        val staleVirtualIds = HashSet<String>()
        var changed = false
        for (folderId in folderIds) {
            val childStats = current.childrenByParent[folderId].orEmpty().mapNotNull { id ->
                current.entries[id]?.takeUnless { VaultEntry.isVaulted(id) }
            }
            val winner = VaultStore.winnerOf(childStats)
            val prev = manifests[folderId]
            if (winner != prev) {
                changed = true
                if (winner != null) manifests[folderId] = winner else manifests.remove(folderId)
                if (prev != null && winner?.id != prev.id) {
                    vaultByFolder.remove(folderId)
                    current.childrenByParent[folderId].orEmpty().filterTo(staleVirtualIds) {
                        VaultEntry.isVaulted(it)
                    }
                }
            }
        }
        if (!changed) return current
        val cleaned = if (staleVirtualIds.isNotEmpty()) upsert(current, emptyList(), staleVirtualIds) else current
        return cleaned.copy(
            manifestsByFolder = manifests.toMap(),
            vaultEntriesByFolder = vaultByFolder.toMap(),
        )
    }

    private fun removeEntriesAndDescendants(current: State, removedIds: Set<String>): State {
        val allRemoved = HashSet<String>()
        val pending = ArrayDeque<String>()
        pending.addAll(removedIds)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (allRemoved.add(id)) pending.addAll(current.childrenByParent[id].orEmpty())
        }
        val affectedManifestParents = allRemoved.mapNotNullTo(HashSet()) { id ->
            current.entries[id]?.takeIf(VaultStore::looksLikeManifest)?.parentId
        } - allRemoved
        val upserted = upsert(current, emptyList(), allRemoved)
        val refreshed = refreshManifestsForFolders(upserted, affectedManifestParents)
        return refreshed.copy(
            manifestsByFolder = refreshed.manifestsByFolder - allRemoved,
            vaultEntriesByFolder = refreshed.vaultEntriesByFolder - allRemoved,
            listedFolders = refreshed.listedFolders - allRemoved,
            dirtyFolders = refreshed.dirtyFolders - allRemoved,
        )
    }

    /**
     * 后台双缓冲整树替换（Shadow Tree Swap）：
     * 将后台拉齐的完整树（[folders]、[files]、[vaultByFolder]）与重建期间发生本地改动的目录（[keepDirtyFolders]）合并后一次性原子发布，
     * 返回实际子项发生变化的目录集合，供仓库定向发出 [DriveChange.ContentsChanged]。
     */
    fun replaceTree(
        folders: List<FileStat>,
        files: List<FileStat>,
        vaultByFolder: Map<String, List<VaultEntry>>,
        usageBytes: Long?,
        sinceMutationSeq: Long? = null,
        keepDirtyFolders: Set<String> = emptySet(),
        freshlyListedFolders: Set<String> = emptySet(),
        removedIds: Set<String> = emptySet(),
        renamedNames: Map<String, String> = emptyMap(),
        movedParents: Map<String, String> = emptyMap(),
        syncedAtMillis: Long = 0L,
    ): Set<String> {
        var changedFolders = emptySet<String>()
        update { current ->
            if (sinceMutationSeq != null && treeInvalidatedAtSeq > sinceMutationSeq) {
                return@update current
            }
            val journalDirty = if (sinceMutationSeq == null) {
                emptySet()
            } else {
                dirtyAtSeq.filter { (folderId, dirtySeq) ->
                    dirtySeq > sinceMutationSeq && dirtySeq >= (listedAtSeq[folderId] ?: 0L)
                }.keys
            }
            val journalListed = if (sinceMutationSeq == null) {
                emptySet()
            } else {
                listedAtSeq.filter { (folderId, listSeq) ->
                    listSeq > sinceMutationSeq && listSeq > (dirtyAtSeq[folderId] ?: 0L)
                }.keys
            }
            val effListed = (freshlyListedFolders - keepDirtyFolders) + journalListed
            val effDirty = (keepDirtyFolders - journalListed) + journalDirty
            val effRemoved = if (sinceMutationSeq == null) {
                removedIds
            } else {
                removedIds + removedAtSeq.filterValues { it > sinceMutationSeq }.keys
            }
            val effRenamed = if (sinceMutationSeq == null) {
                renamedNames
            } else {
                buildMap {
                    putAll(renamedNames)
                    for ((id, pair) in renamedAtSeq) {
                        if (pair.first > sinceMutationSeq) put(id, pair.second)
                    }
                }
            }
            val effMoved = if (sinceMutationSeq == null) {
                movedParents
            } else {
                buildMap {
                    putAll(movedParents)
                    for ((id, pair) in movedAtSeq) {
                        if (pair.first > sinceMutationSeq) put(id, pair.second)
                    }
                }
            }
            val effPatched = if (sinceMutationSeq == null) {
                emptyMap()
            } else {
                buildMap {
                    for ((id, pair) in patchedAtSeq) {
                        if (pair.first > sinceMutationSeq && id !in effRemoved) put(id, pair)
                    }
                }
            }
            val cleanListedFolders = effListed - current.dirtyFolders - effDirty
            val stillDirty = effDirty intersect current.dirtyFolders
            val preservedFolders = cleanListedFolders + stillDirty
            val rawManifestsByFolder = HashMap<String, MutableList<FileStat>>()
            for (rawEntry in files) {
                if (!rawEntry.trashed && VaultStore.looksLikeManifest(rawEntry)) {
                    rawManifestsByFolder.getOrPut(rawEntry.parentId) { ArrayList() }.add(rawEntry)
                }
            }
            val rawWinnerIds = HashMap<String, String>(rawManifestsByFolder.size)
            for ((folderId, list) in rawManifestsByFolder) {
                VaultStore.winnerOf(list)?.id?.let { rawWinnerIds[folderId] = it }
            }
            val candidateIds = HashSet<String>(folders.size + files.size)
            val candidateEntries = buildList(folders.size + files.size + effPatched.size) {
                for (rawEntry in folders + files) {
                    if (!rawEntry.trashed) candidateIds += rawEntry.id
                    val patchedPair = effPatched[rawEntry.id]
                    val entry = patchedPair?.second ?: rawEntry
                    val patchSeq = patchedPair?.first ?: 0L
                    val newName = effRenamed[entry.id]?.takeIf {
                        sinceMutationSeq == null || (renamedAtSeq[entry.id]?.first ?: Long.MAX_VALUE) >= patchSeq
                    }
                    val newParent = effMoved[entry.id]?.takeIf {
                        sinceMutationSeq == null || (movedAtSeq[entry.id]?.first ?: Long.MAX_VALUE) >= patchSeq
                    }
                    val patched = when {
                        newName != null && newParent != null -> entry.copy(name = newName, parentId = newParent)
                        newName != null -> entry.copy(name = newName)
                        newParent != null -> entry.copy(parentId = newParent)
                        else -> entry
                    }
                    add(patched)
                }
                for ((patchedId, pair) in effPatched) {
                    if (patchedId in candidateIds) continue
                    val (patchSeq, entry) = pair
                    val newName = effRenamed[patchedId]?.takeIf {
                        (renamedAtSeq[patchedId]?.first ?: Long.MAX_VALUE) >= patchSeq
                    }
                    val newParent = effMoved[patchedId]?.takeIf {
                        (movedAtSeq[patchedId]?.first ?: Long.MAX_VALUE) >= patchSeq
                    }
                    val patched = when {
                        newName != null && newParent != null -> entry.copy(name = newName, parentId = newParent)
                        newName != null -> entry.copy(name = newName)
                        newParent != null -> entry.copy(parentId = newParent)
                        else -> entry
                    }
                    add(patched)
                }
                for ((movedId, newParent) in effMoved) {
                    if (movedId !in candidateIds && movedId !in effPatched) {
                        current.entries[movedId]?.takeUnless { VaultEntry.isVaulted(it.id) }?.let { live ->
                            val newName = effRenamed[movedId] ?: live.name
                            add(live.copy(name = newName, parentId = newParent))
                        }
                    }
                }
            }
            val rawCandidateBase = upsert(State(), candidateEntries)
            val candidateBase = if (effRemoved.isNotEmpty()) {
                removeEntriesAndDescendants(rawCandidateBase, effRemoved)
            } else {
                rawCandidateBase
            }
            val candidateManifests = HashMap<String, FileStat>()
            for ((folderId, ids) in candidateBase.childrenByParent) {
                val childStats = ids.mapNotNull { candidateBase.entries[it] }
                VaultStore.winnerOf(childStats)?.let { candidateManifests[folderId] = it }
            }
            val virtualStats = ArrayList<FileStat>()
            val mergedVaultByFolder = HashMap<String, List<VaultEntry>>()
            for ((folderId, entries) in vaultByFolder) {
                if (folderId in preservedFolders) continue
                val winner = candidateManifests[folderId] ?: continue
                if (winner.id != rawWinnerIds[folderId]) continue
                if (entries.isNotEmpty()) {
                    mergedVaultByFolder[folderId] = entries.toList()
                    entries.filter { it.isArchived }.forEach { virtualStats += it.toFileStat(folderId) }
                }
            }
            for ((folderId, winner) in candidateManifests) {
                if (folderId in preservedFolders || folderId in mergedVaultByFolder) continue
                if (current.manifestsByFolder[folderId]?.id == winner.id) {
                    current.vaultEntriesByFolder[folderId]?.let { entries ->
                        mergedVaultByFolder[folderId] = entries
                        entries.filter { it.isArchived }.forEach { virtualStats += it.toFileStat(folderId) }
                    }
                }
            }
            for (preservedId in preservedFolders) {
                val liveWinner = current.manifestsByFolder[preservedId]
                if (liveWinner != null) candidateManifests[preservedId] = liveWinner else candidateManifests.remove(preservedId)
                current.vaultEntriesByFolder[preservedId]?.let { entries ->
                    mergedVaultByFolder[preservedId] = entries
                    entries.filter { it.isArchived }.forEach { virtualStats += it.toFileStat(preservedId) }
                }
            }
            var merged = upsert(candidateBase, virtualStats)
            val goneFromPreserved = HashSet<String>()
            if (preservedFolders.isNotEmpty()) {
                for (preservedId in preservedFolders) {
                    val livePhysical = current.childrenByParent[preservedId].orEmpty().mapNotNull { id ->
                        current.entries[id]?.takeUnless { VaultEntry.isVaulted(id) }?.let { live ->
                            val newName = effRenamed[live.id]
                            if (newName != null && newName != live.name) live.copy(name = newName) else live
                        }
                    }
                    val candidatePhysicalIds = merged.childrenByParent[preservedId].orEmpty().filterTo(HashSet()) {
                        !VaultEntry.isVaulted(it)
                    }
                    val missingInLive = candidatePhysicalIds - livePhysical.mapTo(HashSet()) { it.id }
                    missingInLive.filterTo(goneFromPreserved) { it !in current.entries }
                    merged = upsert(merged, livePhysical, missingInLive)
                }
            }
            val allRemovedIds = effRemoved + goneFromPreserved
            if (allRemovedIds.isNotEmpty()) {
                merged = removeEntriesAndDescendants(merged, allRemovedIds)
                candidateManifests.keys.removeAll(allRemovedIds)
                mergedVaultByFolder.keys.removeAll(allRemovedIds)
            }
            val unlistedOverlayFolders = merged.entries.values.asSequence()
                .filter { it.isFolder && it.id !in candidateIds && it.id !in cleanListedFolders }
                .mapTo(HashSet()) { it.id }
            val survivingDirty = (stillDirty + unlistedOverlayFolders) - allRemovedIds
            val allListedFolders = buildSet {
                add("")
                for (entry in merged.entries.values) {
                    if (entry.isFolder) add(entry.id)
                }
                removeAll(survivingDirty)
            }
            if (syncedAtMillis > 0L) {
                lastFullSyncAtMillis = syncedAtMillis
            }
            val effectiveUsageBytes = if (sinceMutationSeq != null && usagePatchedAtSeq > sinceMutationSeq) {
                current.lastUsageBytes ?: usageBytes
            } else {
                usageBytes ?: current.lastUsageBytes
            }
            val nextState = merged.copy(
                manifestsByFolder = candidateManifests.toMap(),
                vaultEntriesByFolder = mergedVaultByFolder.toMap(),
                listedFolders = allListedFolders,
                dirtyFolders = survivingDirty,
                lastUsageBytes = effectiveUsageBytes,
                complete = true,
            )
            val allParents = current.childrenByParent.keys + nextState.childrenByParent.keys
            val diffs = HashSet<String>()
            for (parentId in allParents) {
                val oldIds = current.childrenByParent[parentId].orEmpty()
                val newIds = nextState.childrenByParent[parentId].orEmpty()
                if (oldIds != newIds) {
                    diffs += parentId
                    continue
                }
                val modified = oldIds.any { id ->
                    val a = current.entries[id]
                    val b = nextState.entries[id]
                    a?.name != b?.name || a?.size != b?.size || a?.modifiedTime != b?.modifiedTime || a?.hash != b?.hash
                }
                if (modified || current.vaultEntriesByFolder[parentId] != nextState.vaultEntriesByFolder[parentId]) {
                    diffs += parentId
                }
            }
            changedFolders = diffs
            nextState
        }
        return changedFolders
    }

    /**
     * 若 [rootId] 子树在本地树中已完整且无脏目录，直接内存 $O(K)$ 汇总真实文件数与总字节数；否则返回 null 交由网络遍历兜底。
     */
    fun folderUsage(rootId: String): FolderUsage? {
        val current = flow.value
        if (!current.complete && rootId !in current.listedFolders) return null
        if (rootId in current.dirtyFolders) return null
        var files = 0
        var bytes = 0L
        val visited = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootId)
        while (queue.isNotEmpty()) {
            val folderId = queue.removeFirst()
            if (!visited.add(folderId)) continue
            if (folderId in current.dirtyFolders) return null
            if (!current.complete && folderId !in current.listedFolders) return null
            for (childId in current.childrenByParent[folderId].orEmpty()) {
                val entry = current.entries[childId] ?: continue
                if (VaultEntry.isVaulted(entry.id) || entry.trashed) continue
                if (entry.isFolder) {
                    queue.add(entry.id)
                } else {
                    files++
                    bytes += entry.sizeBytes
                }
            }
        }
        return FolderUsage(files, bytes, FolderUsage.Progress.COMPLETE)
    }

    /** 从磁盘快照与已缓存的归档清单恢复完整内存树。若恢复前已有前台列举或本地改动，保留最新状态。 */
    fun restoreSnapshot(
        snapshot: PersistedTreeSnapshot,
        cachedManifests: Map<String, List<VaultEntry>>,
        sinceMutationSeq: Long = 0L,
    ): Boolean {
        val rawPhysical = snapshot.entries.map(StoredTreeEntry::toFileStat).filterNot { it.trashed }
        var applied = false
        update { current ->
            if (current.complete || treeInvalidatedAtSeq > sinceMutationSeq) return@update current
            applied = true
            val effRemoved = removedAtSeq.filterValues { it > sinceMutationSeq }.keys
            val effRenamed = renamedAtSeq.mapNotNull { (id, pair) ->
                if (pair.first > sinceMutationSeq) id to pair.second else null
            }.toMap()
            val effMoved = movedAtSeq.mapNotNull { (id, pair) ->
                if (pair.first > sinceMutationSeq) id to pair.second else null
            }.toMap()
            val journalDirty = dirtyAtSeq.filter { (folderId, dirtySeq) ->
                dirtySeq > sinceMutationSeq && dirtySeq >= (listedAtSeq[folderId] ?: 0L)
            }.keys
            val journalListed = listedAtSeq.filter { (folderId, listSeq) ->
                listSeq > sinceMutationSeq && listSeq > (dirtyAtSeq[folderId] ?: 0L)
            }.keys
            val effDirty = (journalDirty + current.dirtyFolders) - (journalListed - current.dirtyFolders)
            val cleanLiveListed = ((journalListed + current.listedFolders) - current.dirtyFolders) - journalDirty

            val snapshotIds = HashSet<String>(rawPhysical.size)
            val movedOldParents = HashSet<String>()
            val patchedEntries = buildList(rawPhysical.size + current.entries.size) {
                for (entry in rawPhysical) {
                    snapshotIds += entry.id
                    val newName = effRenamed[entry.id]
                    val newParent = effMoved[entry.id]
                    if (newParent != null && newParent != entry.parentId) {
                        movedOldParents += entry.parentId
                    }
                    val patched = when {
                        newName != null && newParent != null -> entry.copy(name = newName, parentId = newParent)
                        newName != null -> entry.copy(name = newName)
                        newParent != null -> entry.copy(parentId = newParent)
                        else -> entry
                    }
                    add(patched)
                }
                for (live in current.entries.values) {
                    if (VaultEntry.isVaulted(live.id) || live.id in snapshotIds || live.id in effRemoved) continue
                    val newName = effRenamed[live.id] ?: live.name
                    val newParent = effMoved[live.id] ?: live.parentId
                    add(live.copy(name = newName, parentId = newParent))
                }
            }
            val base = upsert(State(), patchedEntries)
            val manifestsByFolder = HashMap<String, FileStat>()
            val vaultByFolder = HashMap<String, List<VaultEntry>>()
            for ((folderId, ids) in base.childrenByParent) {
                val childStats = ids.mapNotNull { base.entries[it] }
                val winner = VaultStore.winnerOf(childStats) ?: continue
                manifestsByFolder[folderId] = winner
                val cached = cachedManifests[winner.id]
                if (cached != null) {
                    vaultByFolder[folderId] = cached
                }
            }
            val virtualStats = vaultByFolder.flatMap { (folderId, entries) ->
                entries.filter { it.isArchived }.map { it.toFileStat(folderId) }
            }
            var merged = upsert(base, virtualStats)
            val goneFromLiveListed = HashSet<String>()
            for (folderId in cleanLiveListed) {
                val livePhysical = current.childrenByParent[folderId].orEmpty().mapNotNull { id ->
                    current.entries[id]?.takeUnless { VaultEntry.isVaulted(id) }?.let { live ->
                        val newName = effRenamed[live.id]
                        if (newName != null && newName != live.name) live.copy(name = newName) else live
                    }
                }
                val snapshotPhysicalIds = merged.childrenByParent[folderId].orEmpty().filterTo(HashSet()) {
                    !VaultEntry.isVaulted(it)
                }
                val missingInLive = snapshotPhysicalIds - livePhysical.mapTo(HashSet()) { it.id }
                missingInLive.filterTo(goneFromLiveListed) { it !in current.entries }
                val liveVirtualIds = merged.childrenByParent[folderId].orEmpty().filterTo(HashSet()) {
                    VaultEntry.isVaulted(it)
                }
                merged = upsert(merged, livePhysical, missingInLive + liveVirtualIds)
                val liveWinner = current.manifestsByFolder[folderId]
                if (liveWinner != null) manifestsByFolder[folderId] = liveWinner else manifestsByFolder.remove(folderId)
                val liveVault = current.vaultEntriesByFolder[folderId] ?: liveWinner?.let { cachedManifests[it.id] }
                if (liveVault != null) {
                    vaultByFolder[folderId] = liveVault
                    merged = upsert(merged, liveVault.filter { it.isArchived }.map { it.toFileStat(folderId) })
                } else {
                    vaultByFolder.remove(folderId)
                }
            }
            for (folderId in effDirty) {
                val livePhysical = current.childrenByParent[folderId].orEmpty().mapNotNull { id ->
                    current.entries[id]?.takeUnless { VaultEntry.isVaulted(id) }?.let { live ->
                        val newName = effRenamed[live.id]
                        if (newName != null && newName != live.name) live.copy(name = newName) else live
                    }
                }
                if (livePhysical.isNotEmpty()) {
                    merged = upsert(merged, livePhysical)
                }
                if (folderId in current.vaultEntriesByFolder) {
                    val liveVault = current.vaultEntriesByFolder.getValue(folderId)
                    vaultByFolder[folderId] = liveVault
                    val oldVirtual = merged.childrenByParent[folderId].orEmpty().filterTo(HashSet()) { VaultEntry.isVaulted(it) }
                    merged = upsert(merged, liveVault.filter { it.isArchived }.map { it.toFileStat(folderId) }, oldVirtual)
                }
            }
            val allRemovedIds = effRemoved + goneFromLiveListed
            if (allRemovedIds.isNotEmpty()) {
                merged = removeEntriesAndDescendants(merged, allRemovedIds)
                manifestsByFolder.keys.removeAll(allRemovedIds)
                vaultByFolder.keys.removeAll(allRemovedIds)
            }
            val unlistedLiveFolders = merged.entries.values.asSequence()
                .filter { it.isFolder && it.id !in snapshotIds && it.id !in cleanLiveListed }
                .mapTo(HashSet()) { it.id }
            val effectiveDirty = ((snapshot.dirtyFolders - cleanLiveListed) + effDirty + movedOldParents + unlistedLiveFolders) - allRemovedIds
            val listedFolders = if (snapshot.complete) {
                buildSet {
                    add("")
                    for (entry in merged.entries.values) {
                        if (entry.isFolder) add(entry.id)
                    }
                    removeAll(effectiveDirty)
                }
            } else {
                cleanLiveListed - effectiveDirty
            }
            if (snapshot.lastFullSyncAtMillis > 0L) {
                lastFullSyncAtMillis = snapshot.lastFullSyncAtMillis
            }
            merged.copy(
                manifestsByFolder = manifestsByFolder.toMap(),
                vaultEntriesByFolder = vaultByFolder.toMap(),
                listedFolders = listedFolders,
                dirtyFolders = effectiveDirty,
                lastUsageBytes = current.lastUsageBytes ?: snapshot.lastUsageBytes,
                complete = snapshot.complete,
            )
        }
        return applied
    }

    /**
     * 在当前已知的本地文件树中搜索 [parentId] 子树下匹配 [query] 的条目，按匹配度排序返回。
     */
    fun searchLocal(
        query: String,
        parentId: String,
        folderIndex: FolderIndex,
        extraFolderTitle: (FileStat) -> String? = { null },
        transformFile: (FileStat) -> FileStat = { it },
    ): List<SearchHit> {
        val currentEntries = flow.value.entries
        if (currentEntries.isEmpty()) return emptyList()
        val indexNodes = folderIndex.flow.value.nodes

        fun breadcrumbOf(entry: FileStat): List<String>? =
            resolveBreadcrumb(entry, parentId, currentEntries, indexNodes)

        val scoredHits = ArrayList<Pair<Int, SearchHit>>()
        for (entry in currentEntries.values) {
            if (entry.trashed) continue
            if (isPikoInternalFolder(entry, entry.parentId)) continue
            if (VaultStore.looksLikeManifest(entry) || VaultStore.isRecoveryFile(entry)) continue
            val nameScore = cjkFuzzyScore(query, entry.name)
            val titleScore = if (nameScore == null && entry.isFolder) {
                extraFolderTitle(entry)?.let { cjkFuzzyScore(query, it) }
            } else null
            val score = nameScore ?: titleScore ?: continue
            val crumb = breadcrumbOf(entry) ?: continue
            scoredHits.add(score to SearchHit(transformFile(entry), crumb))
        }
        scoredHits.sortWith { a, b ->
            val byScore = b.first.compareTo(a.first)
            if (byScore != 0) byScore
            else {
                val fa = a.second.file
                val fb = b.second.file
                if (fa.isFolder != fb.isFolder) {
                    if (fa.isFolder) -1 else 1
                } else {
                    NaturalOrder.compare(fa.name, fb.name)
                }
            }
        }
        return scoredHits.map { it.second }
    }

    @Serializable
    internal data class PersistedTreeSnapshot(
        val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
        val savedAtMillis: Long = 0L,
        val lastFullSyncAtMillis: Long = 0L,
        val lastUsageBytes: Long? = null,
        val complete: Boolean = true,
        val dirtyFolders: Set<String> = emptySet(),
        val entries: List<StoredTreeEntry> = emptyList(),
    )

    @Serializable
    internal data class StoredTreeEntry(
        val id: String,
        @SerialName("p") val parentId: String = "",
        @SerialName("n") val name: String,
        @SerialName("f") val isFolder: Boolean = false,
        @SerialName("s") val size: String = "0",
        @SerialName("h") val hash: String = "",
        @SerialName("c") val createdTime: String = "",
        @SerialName("m") val modifiedTime: String = "",
        @SerialName("t") val mimeType: String = "",
        @SerialName("e") val fileExtension: String = "",
        @SerialName("th") val thumbnailLink: String = "",
        @SerialName("ph") val phase: String = TaskPhase.COMPLETE,
        @SerialName("pa") val params: Map<String, String> = emptyMap(),
        @SerialName("tg") val tags: List<FileTag> = emptyList(),
    ) {
        fun toFileStat(): FileStat = FileStat(
            kind = if (isFolder) FileKind.FOLDER else FileKind.FILE,
            id = id,
            parentId = parentId,
            name = name,
            size = size,
            hash = hash,
            createdTime = createdTime,
            modifiedTime = modifiedTime,
            mimeType = mimeType,
            fileExtension = fileExtension,
            thumbnailLink = thumbnailLink,
            params = params,
            tags = tags,
            phase = phase,
        )

        companion object {
            fun of(stat: FileStat): StoredTreeEntry = StoredTreeEntry(
                id = stat.id,
                parentId = stat.parentId,
                name = stat.name,
                isFolder = stat.isFolder,
                size = stat.size,
                hash = stat.hash,
                createdTime = stat.createdTime,
                modifiedTime = stat.modifiedTime,
                mimeType = stat.mimeType,
                fileExtension = stat.fileExtension,
                thumbnailLink = stat.thumbnailLink,
                phase = stat.phase,
                params = stat.params,
                tags = stat.tags,
            )
        }
    }

    companion object {
        private const val CURRENT_SCHEMA_VERSION = 1
        private val UNSAFE_KEY_CHARS = Regex("""[^A-Za-z0-9._@-]""")
        private val SNAPSHOT_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        fun snapshotKeyOf(account: String): String =
            "drive-tree-" + account.replace(UNSAFE_KEY_CHARS, "_") + ".json"

        fun encodeSnapshot(
            state: State,
            savedAtMillis: Long = 0L,
            lastFullSyncAtMillis: Long = 0L,
        ): String {
            val physical = state.entries.values
                .filterNot { it.trashed || VaultEntry.isVaulted(it.id) }
                .map(StoredTreeEntry::of)
            val snapshot = PersistedTreeSnapshot(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                savedAtMillis = savedAtMillis,
                lastFullSyncAtMillis = lastFullSyncAtMillis,
                lastUsageBytes = state.lastUsageBytes,
                complete = state.complete,
                dirtyFolders = state.dirtyFolders,
                entries = physical,
            )
            return SNAPSHOT_JSON.encodeToString(PersistedTreeSnapshot.serializer(), snapshot)
        }

        fun decodeSnapshot(text: String): PersistedTreeSnapshot? =
            runCatching {
                SNAPSHOT_JSON.decodeFromString(PersistedTreeSnapshot.serializer(), text)
                    .takeIf { it.schemaVersion == CURRENT_SCHEMA_VERSION }
            }.getOrNull()

        internal fun resolveBreadcrumb(
            entry: FileStat,
            parentId: String,
            entries: Map<String, FileStat>,
            indexNodes: Map<String, FolderIndex.Node>,
        ): List<String>? {
            if (entry.id == parentId) return null
            val names = ArrayList<String>(4)
            val seen = HashSet<String>(8)
            seen.add(entry.id)
            var ancestor = entry.parentId
            while (ancestor != parentId) {
                if (ancestor.isEmpty() || !seen.add(ancestor)) return null
                val folderStat = entries[ancestor]
                val indexNode = indexNodes[ancestor]?.takeUnless { it.provisional }
                val folderName = folderStat?.name ?: indexNode?.name ?: return null
                val folderParent = folderStat?.parentId ?: indexNode?.parentId ?: return null
                if (folderParent.isEmpty() && isPikoInternalFolderName(folderName) && parentId.isEmpty()) return null
                names.add(folderName)
                ancestor = folderParent
            }
            if (parentId.isEmpty() && names.lastOrNull()?.let(::isPikoInternalFolderName) == true) return null
            names.reverse()
            return names
        }
    }
}
