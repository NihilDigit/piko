package dev.piko.shared.data

import dev.piko.data.repository.NaturalOrder
import dev.piko.shared.search.cjkFuzzyScore
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.SearchHit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
        val complete: Boolean = false,
        val dirtyFolders: Set<String> = emptySet(),
    )

    private val state = MutableStateFlow(State())
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    val isComplete: Boolean get() = state.value.complete

    val dirtyFolders: Set<String> get() = state.value.dirtyFolders

    fun clear() {
        state.value = State()
        _revision.update { it + 1 }
    }

    fun file(id: String): FileStat? = state.value.entries[id]

    /** 某个文件夹完整列出的真实条目（不含已解析的虚拟归档条目）。 */
    fun putFolderListing(parentId: String, files: List<FileStat>) {
        state.update { current ->
            val kept = current.entries.filterValues { entry ->
                entry.parentId != parentId || VaultEntry.isVaulted(entry.id)
            }
            val added = files.filterNot { it.trashed }.associateBy { it.id }
            current.copy(
                entries = kept + added,
                dirtyFolders = current.dirtyFolders - parentId,
            )
        }
        _revision.update { it + 1 }
    }

    /** 某个文件夹下的归档条目（只收仍在归档中的虚拟条目）。 */
    fun putVaultEntries(folderId: String, entries: List<VaultEntry>) {
        val virtualStats = entries.filter { it.isArchived }.map { it.toFileStat(folderId) }
        state.update { current ->
            val kept = current.entries.filterValues { entry ->
                !(entry.parentId == folderId && VaultEntry.isVaulted(entry.id))
            }
            current.copy(entries = kept + virtualStats.associateBy { it.id })
        }
        _revision.update { it + 1 }
    }

    /** 全盘平铺拉回的文件夹列表。 */
    fun putFolders(folders: List<FileStat>) {
        val valid = folders.filterNot { it.trashed }.associateBy { it.id }
        if (valid.isEmpty()) return
        state.update { current -> current.copy(entries = current.entries + valid) }
        _revision.update { it + 1 }
    }

    /** 全盘平铺分页拉回的真实文件列表。 */
    fun putFiles(files: List<FileStat>) {
        val valid = files.filterNot { it.trashed }.associateBy { it.id }
        if (valid.isEmpty()) return
        state.update { current -> current.copy(entries = current.entries + valid) }
        _revision.update { it + 1 }
    }

    fun markComplete() {
        state.update { it.copy(complete = true) }
        _revision.update { it + 1 }
    }

    fun applyChange(change: DriveChange) {
        state.update { current ->
            when (change) {
                is DriveChange.Renamed -> {
                    val target = current.entries[change.id] ?: return@update current
                    current.copy(entries = current.entries + (change.id to target.copy(name = change.name)))
                }
                is DriveChange.Moved -> {
                    val moved = change.ids.mapNotNull { id ->
                        current.entries[id]?.let { id to it.copy(parentId = change.to) }
                    }
                    if (moved.isEmpty()) current else current.copy(entries = current.entries + moved)
                }
                is DriveChange.Trashed -> removeEntriesAndDescendants(current, change.ids.toSet())
                is DriveChange.Deleted -> removeEntriesAndDescendants(current, change.ids.toSet())
                is DriveChange.Created -> current.copy(dirtyFolders = current.dirtyFolders + change.folders)
                is DriveChange.Restored, is DriveChange.Copied, is DriveChange.ContentsChanged -> {
                    val folders = change.folders
                    if (folders == null) current.copy(complete = false, dirtyFolders = emptySet())
                    else current.copy(dirtyFolders = current.dirtyFolders + folders)
                }
            }
        }
    }

    private fun removeEntriesAndDescendants(current: State, removedIds: Set<String>): State {
        if (removedIds.isEmpty()) return current
        val allRemoved = HashSet(removedIds)
        var added = true
        while (added) {
            added = false
            for (entry in current.entries.values) {
                if (entry.id !in allRemoved && entry.parentId in allRemoved) {
                    allRemoved.add(entry.id)
                    added = true
                }
            }
        }
        return current.copy(
            entries = current.entries - allRemoved,
            dirtyFolders = current.dirtyFolders - allRemoved,
        )
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
        val currentEntries = state.value.entries
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

    companion object {
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
