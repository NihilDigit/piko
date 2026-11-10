package dev.piko.shared.data

import dev.piko.data.repository.isPlayableVideo

@kotlinx.serialization.Serializable
data class ContentStats(
    val bytes: Long = 0,
    val fileCount: Long = 0,
    val durationMillis: Long = 0,
    val knownDurationFiles: Long = 0,
    val unknownDurationFiles: Long = 0,
    val listingComplete: Boolean = true,
    val metadataComplete: Boolean = true,
) {
    val totalBytes: Long get() = bytes
    val knownDurationVideoCount: Long get() = knownDurationFiles
    val unknownDurationVideoCount: Long get() = unknownDurationFiles

    operator fun plus(other: ContentStats) = ContentStats(
        saturatedAdd(bytes, other.bytes), saturatedAdd(fileCount, other.fileCount),
        saturatedAdd(durationMillis, other.durationMillis),
        saturatedAdd(knownDurationFiles, other.knownDurationFiles), saturatedAdd(unknownDurationFiles, other.unknownDurationFiles),
        listingComplete && other.listingComplete, metadataComplete && other.metadataComplete,
    )
}

private fun saturatedAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

@kotlinx.serialization.Serializable
data class SubtreeStats(
    val physical: ContentStats = ContentStats(),
    val vaulted: ContentStats = ContentStats(),
    val structuralValid: Boolean = true,
) {
    val archived: ContentStats get() = vaulted
    val bytes: Long get() = saturatedAdd(physical.bytes, vaulted.bytes)
    val totalBytes: Long get() = bytes
    val fileCount: Long get() = saturatedAdd(physical.fileCount, vaulted.fileCount)
    val durationMillis: Long get() = saturatedAdd(physical.durationMillis, vaulted.durationMillis)
    val knownDurationFiles: Long get() = saturatedAdd(physical.knownDurationFiles, vaulted.knownDurationFiles)
    val unknownDurationFiles: Long get() = saturatedAdd(physical.unknownDurationFiles, vaulted.unknownDurationFiles)
    val listingComplete: Boolean get() = physical.listingComplete && vaulted.listingComplete
    val metadataComplete: Boolean get() = physical.metadataComplete && vaulted.metadataComplete

    operator fun plus(other: SubtreeStats) = SubtreeStats(
        physical + other.physical, vaulted + other.vaulted, structuralValid && other.structuralValid,
    )
}

/** Every map belongs to a single captured revision. Comparators never consult a live tree. */
internal data class SortContext(
    val revision: Long,
    val subtreeStats: Map<String, SubtreeStats>,
    val createdAtMillis: Map<String, Long>,
    val modifiedAtMillis: Map<String, Long>,
    val contentMetadata: Map<ContentKey, ContentMetadata>,
) {
    companion object {
        fun of(state: DriveTreeIndex.State): SortContext {
            val excluded = HashSet<String>()
            val queue = ArrayDeque<String>()
            for (file in state.entries.values) if (isPikoInternalFolder(file, file.parentId)) queue.add(file.id)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (excluded.add(id)) queue.addAll(state.childrenByParent[id].orEmpty())
            }
            val stats = HashMap<String, SubtreeStats>()
            val pendingChildren = HashMap<String, Int>()
            val created = HashMap<String, Long>()
            val modified = HashMap<String, Long>()
            fun addFolder(id: String) {
                val complete = (state.complete || id in state.listedFolders) && id !in state.dirtyFolders
                val empty = ContentStats(listingComplete = complete)
                stats[id] = SubtreeStats(empty, empty)
                pendingChildren[id] = 0
            }
            addFolder("")
            for (file in state.entries.values) {
                (state.entryMetadata[file.id]?.originalCreatedAtMillis ?: treeEpochMillis(file.createdTime))?.let { created[file.id] = it }
                treeEpochMillis(file.modifiedTime)?.let { modified[file.id] = it }
                if (file.isFolder && file.id !in excluded && !file.trashed) addFolder(file.id)
            }
            for (file in state.entries.values) {
                if (file.id in excluded || file.trashed || VaultStore.looksLikeManifest(file) || VaultStore.isRecoveryFile(file)) continue
                if (file.isFolder) {
                    if (file.parentId in stats) pendingChildren[file.parentId] = pendingChildren.getValue(file.parentId) + 1
                    else stats[file.id] = stats.getValue(file.id).copy(structuralValid = false)
                    continue
                }
                val parent = stats[file.parentId] ?: continue // Orphans are never silently attached to root.
                val duration = (ContentKey.of(file)?.let { state.contentMetadata[it]?.durationMillis }
                    ?: ContentMetadata.of(file).durationMillis)?.takeIf { it > 0 }
                val video = file.isPlayableVideo()
                val own = ContentStats(
                    bytes = file.sizeBytes.coerceAtLeast(0), fileCount = 1,
                    durationMillis = duration?.coerceAtLeast(0) ?: 0,
                    knownDurationFiles = if (video && duration != null) 1 else 0,
                    unknownDurationFiles = if (video && duration == null) 1 else 0,
                    metadataComplete = !video || duration != null,
                )
                stats[file.parentId] = parent + if (VaultEntry.isVaulted(file.id)) SubtreeStats(vaulted = own) else SubtreeStats(physical = own)
            }
            queue.clear()
            for ((id, count) in pendingChildren) if (count == 0) queue.add(id)
            val visited = HashSet<String>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!visited.add(id) || id.isEmpty()) continue
                val parentId = state.entries[id]?.parentId ?: continue
                val parent = stats[parentId] ?: continue
                stats[parentId] = parent + stats.getValue(id)
                val remaining = pendingChildren.getValue(parentId) - 1
                pendingChildren[parentId] = remaining
                if (remaining == 0) queue.add(parentId)
            }
            // Kahn elimination leaves cycles and their dependent aggregates unprocessed.
            for (id in stats.keys.toList()) if (id !in visited) {
                val value = stats.getValue(id)
                stats[id] = value.copy(
                    structuralValid = false,
                    physical = value.physical.copy(listingComplete = false),
                    vaulted = value.vaulted.copy(listingComplete = false),
                )
            }
            return SortContext(state.revision, stats, created, modified, state.contentMetadata)
        }
    }
}
