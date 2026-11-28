package dev.piko.shared.data

import dev.piko.shared.space.SpaceFolderSnapshot
import dev.piko.shared.space.SpaceNodeItem
import dev.piko.shared.space.SpacePreviewBlock
import io.github.nihildigit.pikpak.FileStat

/**
 * 空间分布查询：文件夹骨架与物化 `SubtreeStats` 直接取自内存 `State.sortProjection`（零 JSON 解码），
 * SQLite 快速路径下直接文件与子目录 Top-24 预览块仅扫描原始数值/文本索引列（无乐观写叠加时），
 * 有乐观写或内存树回退路径则解码子节点取 Top-16，彻底避免常规浏览反序列化数千份 `StoredTreeEntry`。
 */
internal fun spaceFolderSnapshot(
    index: DriveTreeIndex,
    folderId: String,
    includeVaulted: Boolean,
    isBuilding: Boolean,
    projectOptimistic: ((List<FileStat>, String?) -> List<FileStat>)? = null,
    vaultLedger: AccountVaultLedger? = null,
): SpaceFolderSnapshot = index.withReadState { state ->
    if (VirtualVaultLocation.isVirtualFolder(folderId)) {
        val ledger = vaultLedger ?: return@withReadState SpaceFolderSnapshot(
            folderId = folderId,
            breadcrumbs = listOf(PikoDriveRepository.ROOT_BREADCRUMB),
            physicalBytes = 0L,
            vaultedBytes = 0L,
            fileCount = 0L,
            isTreeComplete = true,
            isTreeBuilding = false,
            items = emptyList(),
        )
        val (anchorId, vChain) = ledger.breadcrumbChainForVirtualFolder(folderId)
            ?: ("" to emptyList())
        val anchorBreadcrumbs = ArrayDeque<PikoPathBreadcrumb>()
        var anc = anchorId
        val seenAnc = HashSet<String>()
        while (anc.isNotEmpty() && seenAnc.add(anc)) {
            val f = state.entries[anc]?.takeIf { it.isFolder } ?: break
            anchorBreadcrumbs.addFirst(PikoPathBreadcrumb(f.id, f.name))
            anc = f.parentId
        }
        anchorBreadcrumbs.addFirst(PikoDriveRepository.ROOT_BREADCRUMB)
        val breadcrumbs = anchorBreadcrumbs.toList() + vChain
        if (!includeVaulted) {
            return@withReadState SpaceFolderSnapshot(
                folderId = folderId,
                breadcrumbs = breadcrumbs,
                physicalBytes = 0L,
                vaultedBytes = ledger.subtreeUsageOf(folderId)?.second ?: 0L,
                fileCount = 0L,
                isTreeComplete = true,
                isTreeBuilding = false,
                items = emptyList(),
            )
        }
        val itemOrder = compareByDescending<SpaceNodeItem> { it.bytes(true) }
            .thenBy { it.name }.thenBy { it.id }
        val vChildren = buildLedgerSpaceItems(ledger, folderId, true).sortedWith(itemOrder)
        val (totalCount, totalBytes) = ledger.subtreeUsageOf(folderId) ?: (0 to 0L)
        return@withReadState SpaceFolderSnapshot(
            folderId = folderId,
            breadcrumbs = breadcrumbs,
            physicalBytes = 0L,
            vaultedBytes = totalBytes,
            fileCount = totalCount.toLong(),
            isTreeComplete = true,
            isTreeBuilding = false,
            items = vChildren,
        )
    }
    val entries = if (projectOptimistic == null) {
        state.entries
    } else {
        projectOptimistic(state.entries.values.toList(), null).filter { it.isFolder }.associateBy { it.id }
    }
    val childrenByParent = if (projectOptimistic == null) {
        state.childrenByParent
    } else {
        entries.values.groupBy { it.parentId }.mapValues { (_, fs) -> fs.mapTo(HashSet()) { it.id } as Set<String> }
    }
    val subtreeStats = (state.sortProjection ?: index.sortContext()).subtreeStats
    val chain = ArrayDeque<PikoPathBreadcrumb>()
    val visited = HashSet<String>()
    var ancestorId = folderId
    var excluded = false
    while (ancestorId.isNotEmpty() && visited.add(ancestorId)) {
        val rawFolder = entries[ancestorId] ?: index.file(ancestorId)?.let { f ->
            if (projectOptimistic != null) projectOptimistic(listOf(f), null).firstOrNull() else f
        }
        val folder = rawFolder?.takeIf { it.isFolder } ?: break
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

    val ancestorLedgerByRealFolder: Map<String, Pair<Long, Long>> = if (!excluded && vaultLedger != null) {
        computeLedgerUsageByPhysicalAncestor(vaultLedger, state)
    } else {
        emptyMap()
    }

    fun folderNodeOf(folder: FileStat): SpaceNodeItem? {
        if (folder.trashed || isPikoInternalFolder(folder)) return null
        val stats = subtreeStats[folder.id]
        val physical = stats?.physical ?: ContentStats(listingComplete = false)
        val archived = stats?.vaulted ?: ContentStats(listingComplete = false)
        val extraLedger = ancestorLedgerByRealFolder[folder.id] ?: (0L to 0L)
        val selected = if (includeVaulted) physical + archived else physical
        return SpaceNodeItem(
            id = folder.id,
            parentId = folder.parentId,
            name = folder.name,
            isFolder = true,
            isVaulted = false,
            physicalBytes = physical.bytes,
            vaultedBytes = archived.bytes + extraLedger.second,
            fileCount = selected.fileCount + (if (includeVaulted) extraLedger.first else 0L),
            durationMillis = selected.durationMillis,
            isComplete = stats?.structuralValid == true && selected.listingComplete,
        )
    }

    val rawDirectFiles = if (excluded || projectOptimistic != null) null else index.directSpaceFiles(folderId, includeVaulted)
    val items: List<SpaceNodeItem> = if (excluded) {
        emptyList()
    } else if (rawDirectFiles != null) {
        // SQLite 快速通道：文件夹读内存骨架，直接文件只读原始索引列
        val combined = ArrayList<SpaceNodeItem>(childrenByParent[folderId].orEmpty().size + rawDirectFiles.size)
        for (childId in childrenByParent[folderId].orEmpty()) {
            val childFolder = entries[childId]?.takeIf { it.isFolder } ?: continue
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
        // 乐观写投影或内存回退通道（无 SQLite 测试环境）
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
        val baseChildren = index.children(folderId).files
        val effectiveChildren = if (projectOptimistic != null) projectOptimistic(baseChildren, folderId) else baseChildren
        effectiveChildren.asSequence()
            .filter(::visible)
            .mapNotNull { file -> if (file.isFolder) folderNodeOf(file) else node(file) }
            .sortedWith(itemOrder)
            .toList()
    }

    val previewIds = items.asSequence().filter { it.isFolder }.take(24).map { it.id }.toSet()
    val batchedTopFiles = if (previewIds.isEmpty() || projectOptimistic != null) emptyMap() else index.topSpacePreviewFilesByParent(previewIds, includeVaulted, 24)
    val withPreviews = items.map { item ->
        if (item.id !in previewIds) {
            item
        } else {
            val fastTopFiles = if (projectOptimistic != null) {
                null
            } else if (batchedTopFiles != null) {
                batchedTopFiles[item.id].orEmpty()
            } else {
                index.topSpacePreviewFiles(item.id, includeVaulted, 24)
            }
            val previewBlocks = if (fastTopFiles != null) {
                val candidates = ArrayList<SpacePreviewBlock>(childrenByParent[item.id].orEmpty().size + fastTopFiles.size)
                for (subId in childrenByParent[item.id].orEmpty()) {
                    val subFolder = entries[subId]?.takeIf { it.isFolder } ?: continue
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
                candidates.sortedWith(previewOrder).take(24)
            } else {
                val rawPreviewFiles = index.children(item.id).files
                val previewFiles = if (projectOptimistic != null) projectOptimistic(rawPreviewFiles, item.id) else rawPreviewFiles
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

    val ledgerDirectItems = if (!excluded && includeVaulted && vaultLedger != null) {
        buildLedgerSpaceItems(vaultLedger, folderId, true)
    } else {
        emptyList()
    }
    val extraLedgerUsage = ancestorLedgerByRealFolder[folderId] ?: (0L to 0L)
    val mergedItems = if (ledgerDirectItems.isEmpty()) withPreviews else (withPreviews + ledgerDirectItems).sortedWith(itemOrder)

    val selected = aggregate?.let { if (includeVaulted) it.physical + it.vaulted else it.physical }
    SpaceFolderSnapshot(
        folderId = folderId,
        breadcrumbs = chain.toList(),
        physicalBytes = aggregate?.physical?.bytes ?: 0L,
        vaultedBytes = (aggregate?.vaulted?.bytes ?: 0L) + extraLedgerUsage.second,
        fileCount = (selected?.fileCount ?: 0L) + (if (includeVaulted) extraLedgerUsage.first else 0L),
        isTreeComplete = aggregate?.structuralValid == true && selected?.listingComplete == true,
        isTreeBuilding = isBuilding,
        items = mergedItems,
    )
}

private fun computeLedgerUsageByPhysicalAncestor(
    ledger: AccountVaultLedger,
    state: DriveTreeIndex.State,
): Map<String, Pair<Long, Long>> {
    val proj = ledger.projection.value
    if (proj.realFoldersWithVault.isEmpty()) return emptyMap()
    val totals = HashMap<String, Pair<Long, Long>>()
    for (anchor in proj.realFoldersWithVault) {
        val direct = ledger.subtreeUsageOf(anchor) ?: continue
        if (direct.first <= 0 && direct.second <= 0L) continue
        if (anchor.isEmpty()) {
            val prev = totals[""] ?: (0L to 0L)
            totals[""] = (prev.first + direct.first.toLong()) to (prev.second + direct.second)
            continue
        }
        // Validate that the physical anchor chain is visible and non-trashed
        val chain = ArrayList<String>(8)
        var cur = anchor
        var visible = true
        val seen = HashSet<String>()
        while (cur.isNotEmpty() && seen.add(cur)) {
            val folder = state.entries[cur]
            if (folder == null) {
                if (state.complete) visible = false
                break
            }
            if (!folder.isFolder || folder.trashed || isPikoInternalFolder(folder)) {
                visible = false
                break
            }
            chain += cur
            cur = folder.parentId
        }
        if (!visible) continue
        if (chain.isEmpty() && !state.complete) chain += anchor
        for (folderId in chain) {
            val prev = totals[folderId] ?: (0L to 0L)
            totals[folderId] = (prev.first + direct.first.toLong()) to (prev.second + direct.second)
        }
        val prevRoot = totals[""] ?: (0L to 0L)
        totals[""] = (prevRoot.first + direct.first.toLong()) to (prevRoot.second + direct.second)
    }
    return totals
}

private fun buildLedgerSpaceItems(
    ledger: AccountVaultLedger,
    parentFolderId: String,
    includePreviews: Boolean,
): List<SpaceNodeItem> {
    val proj = ledger.projection.value
    val children = proj.childrenByParent[parentFolderId].orEmpty()
    if (children.isEmpty()) return emptyList()
    val previewOrder = compareByDescending<SpacePreviewBlock> { it.bytes }.thenBy { it.name }.thenBy { it.id }
    val out = ArrayList<SpaceNodeItem>(children.size)
    for (node in children) {
        if (node.isFolder) {
            val (fCount, vBytes) = proj.subtreeBytesByFolderId[node.virtualFolderId] ?: (0 to 0L)
            val previews = if (includePreviews) {
                buildLedgerPreviewBlocks(ledger, node.virtualFolderId, 24, previewOrder)
            } else {
                emptyList()
            }
            out += SpaceNodeItem(
                id = node.virtualFolderId,
                parentId = parentFolderId,
                name = node.name,
                isFolder = true,
                isVaulted = true,
                physicalBytes = 0L,
                vaultedBytes = vBytes,
                fileCount = fCount.toLong(),
                durationMillis = 0L,
                isComplete = true,
                previewChildren = previews,
            )
        } else if (node.restoredFileId == null) {
            val entry = node.toVaultEntry()
            val durationMs = ((node.meta?.duration?.toDoubleOrNull() ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
            out += SpaceNodeItem(
                id = entry.virtualId,
                parentId = parentFolderId,
                name = node.name,
                isFolder = false,
                isVaulted = true,
                physicalBytes = 0L,
                vaultedBytes = node.size,
                fileCount = 1L,
                durationMillis = durationMs,
                isComplete = true,
            )
        }
    }
    return out
}

private fun buildLedgerPreviewBlocks(
    ledger: AccountVaultLedger,
    folderId: String,
    limit: Int,
    order: Comparator<SpacePreviewBlock>,
): List<SpacePreviewBlock> {
    val proj = ledger.projection.value
    val kids = proj.childrenByParent[folderId].orEmpty()
    if (kids.isEmpty() || limit <= 0) return emptyList()
    val blocks = ArrayList<SpacePreviewBlock>(kids.size)
    for (child in kids) {
        if (child.isFolder) {
            val (cnt, b) = proj.subtreeBytesByFolderId[child.virtualFolderId] ?: (0 to 0L)
            if (b > 0L) {
                blocks += SpacePreviewBlock(
                    id = child.virtualFolderId,
                    name = child.name,
                    isFolder = true,
                    isVaulted = true,
                    bytes = b,
                    physicalBytes = 0L,
                    vaultedBytes = b,
                    fileCount = cnt.toLong(),
                )
            }
        } else if (child.restoredFileId == null && child.size > 0L) {
            blocks += SpacePreviewBlock(
                id = child.toVaultEntry().virtualId,
                name = child.name,
                isFolder = false,
                isVaulted = true,
                bytes = child.size,
                physicalBytes = 0L,
                vaultedBytes = child.size,
                fileCount = 1L,
            )
        }
    }
    return blocks.sortedWith(order).take(limit)
}

internal fun spaceFolderPreviewChildren(
    index: DriveTreeIndex,
    folderId: String,
    includeVaulted: Boolean,
    limit: Int = 128,
    vaultLedger: AccountVaultLedger? = null,
): List<SpacePreviewBlock> = index.withReadState { state ->
    if (limit <= 0) return@withReadState emptyList()
    val previewOrder = compareByDescending<SpacePreviewBlock> { it.bytes }
        .thenBy { it.name }.thenBy { it.id }
    if (VirtualVaultLocation.isVirtualFolder(folderId)) {
        if (!includeVaulted || vaultLedger == null) return@withReadState emptyList()
        return@withReadState buildLedgerPreviewBlocks(vaultLedger, folderId, limit, previewOrder)
    }
    val subtreeStats = (state.sortProjection ?: index.sortContext()).subtreeStats

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
