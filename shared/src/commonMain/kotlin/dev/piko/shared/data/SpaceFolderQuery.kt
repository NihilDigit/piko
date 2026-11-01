package dev.piko.shared.data

import dev.piko.shared.space.SpaceFolderSnapshot
import dev.piko.shared.space.SpaceNodeItem
import dev.piko.shared.space.SpacePreviewBlock
import io.github.nihildigit.pikpak.FileStat

/**
 * 空间分布查询：文件夹骨架与物化 `SubtreeStats` 直接取自内存 `State.sortProjection`（零 JSON 解码），
 * SQLite 模式下直接文件与子目录 Top-16 预览文件仅扫描原始数值/文本索引列，彻底避免反序列化数千份 `StoredTreeEntry`。
 */
internal fun spaceFolderSnapshot(
    index: DriveTreeIndex,
    folderId: String,
    includeVaulted: Boolean,
    isBuilding: Boolean,
): SpaceFolderSnapshot = index.withReadState { state ->
    val subtreeStats = (state.sortProjection ?: index.sortContext()).subtreeStats
    val chain = ArrayDeque<PikoPathBreadcrumb>()
    val visited = HashSet<String>()
    var ancestorId = folderId
    var excluded = false
    while (ancestorId.isNotEmpty() && visited.add(ancestorId)) {
        val folder = (state.entries[ancestorId] ?: index.file(ancestorId))?.takeIf { it.isFolder } ?: break
        chain.addFirst(PikoPathBreadcrumb(folder.id, folder.name))
        excluded = excluded || folder.trashed || isPikoInternalFolder(folder)
        ancestorId = folder.parentId
    }
    chain.addFirst(PikoDriveRepository.ROOT_BREADCRUMB)

    val aggregate = subtreeStats[folderId].takeUnless { excluded }
    val itemOrder = compareByDescending<SpaceNodeItem> { it.bytes(includeVaulted) }
        .thenBy { it.name }.thenBy { it.id }
    val previewOrder = compareByDescending<SpacePreviewBlock> { it.bytes }
        .thenBy { it.name }.thenBy { it.id }

    fun folderNodeOf(folder: FileStat): SpaceNodeItem? {
        if (folder.trashed || isPikoInternalFolder(folder)) return null
        val stats = subtreeStats[folder.id]
        val physical = stats?.physical ?: ContentStats(listingComplete = false)
        val archived = stats?.vaulted ?: ContentStats(listingComplete = false)
        val selected = if (includeVaulted) physical + archived else physical
        return SpaceNodeItem(
            id = folder.id,
            parentId = folder.parentId,
            name = folder.name,
            isFolder = true,
            isVaulted = false,
            physicalBytes = physical.bytes,
            vaultedBytes = archived.bytes,
            fileCount = selected.fileCount,
            durationMillis = selected.durationMillis,
            isComplete = stats?.structuralValid == true && selected.listingComplete,
        )
    }

    val rawDirectFiles = if (excluded) null else index.directSpaceFiles(folderId, includeVaulted)
    val items: List<SpaceNodeItem> = if (excluded) {
        emptyList()
    } else if (rawDirectFiles != null) {
        // SQLite 快速通道：文件夹读内存骨架，直接文件只读原始索引列
        val combined = ArrayList<SpaceNodeItem>(state.childrenByParent[folderId].orEmpty().size + rawDirectFiles.size)
        for (childId in state.childrenByParent[folderId].orEmpty()) {
            val childFolder = state.entries[childId]?.takeIf { it.isFolder } ?: continue
            folderNodeOf(childFolder)?.let(combined::add)
        }
        for (raw in rawDirectFiles) {
            combined += SpaceNodeItem(
                id = raw.id,
                parentId = folderId,
                name = raw.name,
                isFolder = false,
                isVaulted = raw.isVaulted,
                physicalBytes = if (raw.isVaulted) 0L else raw.bytes,
                vaultedBytes = if (raw.isVaulted) raw.bytes else 0L,
                fileCount = 1L,
                durationMillis = raw.durationMillis,
                isComplete = true,
            )
        }
        combined.sortedWith(itemOrder)
    } else {
        // 内存回退通道（无 SQLite 测试环境）
        val context = state.sortProjection ?: index.sortContext()
        fun visible(file: FileStat): Boolean = !file.trashed && !isPikoInternalFolder(file) &&
            !VaultStore.looksLikeManifest(file) && !VaultStore.isRecoveryFile(file) &&
            (includeVaulted || !VaultEntry.isVaulted(file.id))
        fun node(file: FileStat): SpaceNodeItem {
            if (file.isFolder) return folderNodeOf(file) ?: error("Filtered")
            val vaulted = VaultEntry.isVaulted(file.id)
            val bytes = file.sizeBytes.coerceAtLeast(0L)
            val duration = (ContentKey.of(file)?.let { context.contentMetadata[it]?.durationMillis }
                ?: ContentMetadata.of(file).durationMillis)?.coerceAtLeast(0L) ?: 0L
            return SpaceNodeItem(
                id = file.id,
                parentId = file.parentId,
                name = file.name,
                isFolder = false,
                isVaulted = vaulted,
                physicalBytes = if (vaulted) 0L else bytes,
                vaultedBytes = if (vaulted) bytes else 0L,
                fileCount = 1L,
                durationMillis = duration,
                isComplete = true,
            )
        }
        index.children(folderId).files.asSequence()
            .filter(::visible)
            .map(::node)
            .sortedWith(itemOrder)
            .toList()
    }

    val previewIds = items.asSequence().filter { it.isFolder }.take(24).map { it.id }.toSet()
    val withPreviews = items.map { item ->
        if (item.id !in previewIds) {
            item
        } else {
            val fastTopFiles = index.topSpacePreviewFiles(item.id, includeVaulted, 16)
            val previewBlocks = if (fastTopFiles != null) {
                val candidates = ArrayList<SpacePreviewBlock>(state.childrenByParent[item.id].orEmpty().size + fastTopFiles.size)
                for (subId in state.childrenByParent[item.id].orEmpty()) {
                    val subFolder = state.entries[subId]?.takeIf { it.isFolder } ?: continue
                    val subNode = folderNodeOf(subFolder) ?: continue
                    val b = subNode.bytes(includeVaulted)
                    if (b > 0L) {
                        candidates += SpacePreviewBlock(
                            id = subNode.id,
                            name = subNode.name,
                            isFolder = true,
                            isVaulted = false,
                            bytes = b,
                            physicalBytes = subNode.physicalBytes,
                            vaultedBytes = subNode.vaultedBytes,
                            fileCount = subNode.fileCount,
                        )
                    }
                }
                candidates += fastTopFiles
                candidates.sortedWith(previewOrder).take(16)
            } else {
                val previewFiles = index.children(item.id).files
                previewFiles.asSequence()
                    .filter { f ->
                        !f.trashed && !isPikoInternalFolder(f) &&
                            !VaultStore.looksLikeManifest(f) && !VaultStore.isRecoveryFile(f) &&
                            (includeVaulted || !VaultEntry.isVaulted(f.id))
                    }
                    .mapNotNull { f ->
                        if (f.isFolder) {
                            val subNode = folderNodeOf(f) ?: return@mapNotNull null
                            val b = subNode.bytes(includeVaulted)
                            if (b <= 0L) null else SpacePreviewBlock(
                                id = subNode.id,
                                name = subNode.name,
                                isFolder = true,
                                isVaulted = false,
                                bytes = b,
                                physicalBytes = subNode.physicalBytes,
                                vaultedBytes = subNode.vaultedBytes,
                                fileCount = subNode.fileCount,
                            )
                        } else {
                            val vaulted = VaultEntry.isVaulted(f.id)
                            val b = f.sizeBytes.coerceAtLeast(0L)
                            if (b <= 0L) null else SpacePreviewBlock(
                                id = f.id,
                                name = f.name,
                                isFolder = false,
                                isVaulted = vaulted,
                                bytes = b,
                                physicalBytes = if (vaulted) 0L else b,
                                vaultedBytes = if (vaulted) b else 0L,
                                fileCount = 1L,
                            )
                        }
                    }
                    .sortedWith(previewOrder)
                    .take(16)
                    .toList()
            }
            item.copy(previewChildren = previewBlocks)
        }
    }

    val selected = aggregate?.let { if (includeVaulted) it.physical + it.vaulted else it.physical }
    SpaceFolderSnapshot(
        folderId = folderId,
        breadcrumbs = chain.toList(),
        physicalBytes = aggregate?.physical?.bytes ?: 0L,
        vaultedBytes = aggregate?.vaulted?.bytes ?: 0L,
        fileCount = selected?.fileCount ?: 0L,
        isTreeComplete = aggregate?.structuralValid == true && selected?.listingComplete == true,
        isTreeBuilding = isBuilding,
        items = withPreviews,
    )
}

internal fun spaceFolderPreviewChildren(
    index: DriveTreeIndex,
    folderId: String,
    includeVaulted: Boolean,
    limit: Int = 128,
): List<SpacePreviewBlock> = index.withReadState { state ->
    if (limit <= 0) return@withReadState emptyList()
    val subtreeStats = (state.sortProjection ?: index.sortContext()).subtreeStats
    val previewOrder = compareByDescending<SpacePreviewBlock> { it.bytes }
        .thenBy { it.name }.thenBy { it.id }

    fun folderPreviewOf(folder: FileStat): SpacePreviewBlock? {
        if (folder.trashed || isPikoInternalFolder(folder)) return null
        val stats = subtreeStats[folder.id]
        val physical = stats?.physical ?: ContentStats(listingComplete = false)
        val archived = stats?.vaulted ?: ContentStats(listingComplete = false)
        val selected = if (includeVaulted) physical + archived else physical
        if (selected.bytes <= 0L) return null
        return SpacePreviewBlock(
            id = folder.id,
            name = folder.name,
            isFolder = true,
            isVaulted = false,
            bytes = selected.bytes,
            physicalBytes = physical.bytes,
            vaultedBytes = archived.bytes,
            fileCount = selected.fileCount,
        )
    }

    val fastTopFiles = index.topSpacePreviewFiles(folderId, includeVaulted, limit)
    if (fastTopFiles != null) {
        val candidates = ArrayList<SpacePreviewBlock>(state.childrenByParent[folderId].orEmpty().size + fastTopFiles.size)
        for (subId in state.childrenByParent[folderId].orEmpty()) {
            val subFolder = state.entries[subId]?.takeIf { it.isFolder } ?: continue
            folderPreviewOf(subFolder)?.let(candidates::add)
        }
        candidates += fastTopFiles
        candidates.sortedWith(previewOrder).take(limit)
    } else {
        index.children(folderId).files.asSequence()
            .filter { f ->
                !f.trashed && !isPikoInternalFolder(f) &&
                    !VaultStore.looksLikeManifest(f) && !VaultStore.isRecoveryFile(f) &&
                    (includeVaulted || !VaultEntry.isVaulted(f.id))
            }
            .mapNotNull { f ->
                if (f.isFolder) {
                    folderPreviewOf(f)
                } else {
                    val vaulted = VaultEntry.isVaulted(f.id)
                    val b = f.sizeBytes.coerceAtLeast(0L)
                    if (b <= 0L) null else SpacePreviewBlock(
                        id = f.id,
                        name = f.name,
                        isFolder = false,
                        isVaulted = vaulted,
                        bytes = b,
                        physicalBytes = if (vaulted) 0L else b,
                        vaultedBytes = if (vaulted) b else 0L,
                        fileCount = 1L,
                    )
                }
            }
            .sortedWith(previewOrder)
            .take(limit)
            .toList()
    }
}
