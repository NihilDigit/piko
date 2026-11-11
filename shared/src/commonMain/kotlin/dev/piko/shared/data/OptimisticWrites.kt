package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FileTag
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Projections overlay confirmed data; rollback removes only the rejected write. */
internal class OptimisticWrites {
    class Write(
        val account: String,
        val change: DriveChange,
        val before: Map<String, FileStat>,
        val predecessors: List<CompletableDeferred<Unit>>,
        val client: PikPakClient? = null,
    ) {
        val done = CompletableDeferred<Unit>()
        val ids: Set<String> = change.fileIds()
        suspend fun awaitTurn() { predecessors.forEach { it.await() } }
    }
    private val pending = MutableStateFlow<List<Write>>(emptyList())

    private fun hasPendingIn(all: List<Write>, account: String?): Boolean {
        for (i in 0 until all.size) {
            if (all[i].account == account) return true
        }
        return false
    }

    fun candidates(account: String?): List<FileStat> {
        val all = pending.value
        if (!hasPendingIn(all, account)) return emptyList()
        return all.filter { it.account == account }.flatMap { it.before.values }.distinctBy { it.id }
    }

    fun hasPending(account: String?) = hasPendingIn(pending.value, account)

    fun projectFolders(account: String?, state: FolderIndex.State): FolderIndex.State {
        val all = pending.value
        if (!hasPendingIn(all, account)) return state
        var nodes = state.nodes
        var gone = state.gone
        val writes = all.filter { it.account == account }
        for (write in writes) {
            when (val change = write.change) {
                is DriveChange.Renamed -> nodes[change.id]?.let { nodes = nodes + (change.id to it.copy(name = change.name)) }
                is DriveChange.Moved -> nodes = nodes + change.ids.mapNotNull { id -> nodes[id]?.let { id to it.copy(parentId = change.to) } }
                is DriveChange.Restored -> gone = gone - write.ids
                else -> Unit
            }
        }
        val removals = removalRoots(writes)
        val hidden = nodes.keys.filterTo(HashSet()) { id ->
            removedAncestor(id, removals) { nodes[it]?.parentId } != null
        }
        return state.copy(nodes = nodes - hidden, gone = gone + hidden + removals.keys)
    }

    private fun removalRoots(writes: List<Write>): Map<String, Boolean> = buildMap {
        for (write in writes) when (write.change) {
            is DriveChange.Trashed -> write.ids.forEach { put(it, false) }
            is DriveChange.Deleted -> write.ids.forEach { put(it, true) }
            is DriveChange.Restored -> write.ids.forEach(::remove)
            else -> Unit
        }
    }

    /** Return whether a removed ancestor is permanently deleted; false still means hidden. */
    private fun removedAncestor(id: String, removals: Map<String, Boolean>, parentOf: (String) -> String?): Boolean? {
        val visited = HashSet<String>()
        var current = id
        var trashed = false
        while (current.isNotEmpty() && visited.add(current)) {
            when (removals[current]) {
                true -> return true
                false -> trashed = true
                null -> Unit
            }
            current = parentOf(current) ?: break
        }
        return false.takeIf { trashed }
    }

    fun begin(
        account: String,
        change: DriveChange,
        before: Map<String, FileStat>,
        client: PikPakClient? = null,
    ): Write {
        lateinit var write: Write
        pending.update { all ->
            val ids = change.fileIds()
            write = Write(
                account = account,
                change = change,
                before = before,
                predecessors = all.filter { it.account == account && it.ids.any(ids::contains) }.map { it.done },
                client = client,
            )
            all + write
        }
        return write
    }
    fun finish(write: Write) {
        pending.update { it - write }
        write.done.complete(Unit)
    }
    fun project(
        account: String?, folder: String?, files: List<FileStat>, trash: Boolean = false,
        parentOf: (String) -> String? = { null },
    ): List<FileStat> {
        val all = pending.value
        if (!hasPendingIn(all, account)) {
            if (files.isEmpty()) return files
            for (i in 0 until files.size) {
                val file = files[i]
                if ((folder != null && file.parentId != folder) || file.trashed != trash) {
                    return files.filter { (folder == null || it.parentId == folder) && it.trashed == trash }
                }
            }
            return files
        }
        val result = files.associateByTo(LinkedHashMap(files.size)) { it.id }
        val writes = all.filter { it.account == account }
        for (write in writes) for ((id, before) in write.before) {
            if ((folder != null || id in result) && id !in result) result[id] = before
        }
        for (write in writes) {
            for (id in write.ids) {
                val base = result[id] ?: continue
                val next = when (val change = write.change) {
                    is DriveChange.Renamed -> base.copy(name = change.name)
                    is DriveChange.Moved -> base.copy(parentId = change.to)
                    is DriveChange.Trashed -> base.copy(trashed = true)
                    is DriveChange.Deleted -> null
                    is DriveChange.Restored -> base.copy(trashed = false)
                    is DriveChange.Starred -> base.copy(starred = change.starred, tags = base.tags.filterNot { it.name.equals("STAR", true) } +
                        if (change.starred) listOf(FileTag(id = "STAR", name = "STAR")) else emptyList())
                    else -> base
                }
                if (next == null) result.remove(id)
                else result[id] = next
            }
        }
        val removals = removalRoots(writes)
        if (removals.isEmpty()) return result.values.filter { (folder == null || it.parentId == folder) && it.trashed == trash }
        val parents = HashMap<String, String>()
        for (write in writes) for ((id, before) in write.before) parents.getOrPut(id) { before.parentId }
        files.forEach { parents[it.id] = it.parentId }
        // Use the final projected graph: a child moved out of a removed folder remains visible.
        for (write in writes) (write.change as? DriveChange.Moved)?.let { move ->
            move.ids.forEach { parents[it] = move.to }
        }
        return result.values.filter { file ->
            val removed = removedAncestor(file.id, removals) { parents[it] ?: parentOf(it) }
            (folder == null || file.parentId == folder) && file.trashed == trash &&
                (removed == null || (trash && removed == false))
        }
    }
}

internal fun DriveChange.fileIds(): Set<String> = when (this) {
    is DriveChange.Renamed -> setOf(id)
    is DriveChange.Created -> setOf(id)
    is DriveChange.Moved -> ids.toSet()
    is DriveChange.Trashed -> ids.toSet()
    is DriveChange.Restored -> ids.toSet()
    is DriveChange.Deleted -> ids.toSet()
    is DriveChange.Starred -> ids.toSet()
    is DriveChange.Copied -> ids.toSet()
    is DriveChange.ContentsChanged -> emptySet()
}
