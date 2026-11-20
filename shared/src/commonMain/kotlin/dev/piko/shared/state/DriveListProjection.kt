package dev.piko.shared.state

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import dev.piko.shared.data.isImportPreview
import io.github.nihildigit.pikpak.FileStat

internal class DriveListProjection(private val repository: PikoDriveRepository) {
    fun files(base: List<FileStat>, folder: String, library: DriveLibrary?): List<FileStat> {
        val source = if (library == DriveLibrary.STARRED || library == DriveLibrary.TRASH)
            (base + repository.optimisticCandidates()).distinctBy { it.id } else base
        val projected = repository.projectOptimisticFiles(source, folder.takeIf { library == null }, library == DriveLibrary.TRASH)
        return if (library == DriveLibrary.STARRED) projected.filter { it.isStarred } else projected
    }

    /**
     * 仅在按名称正序（`NAME_ASC`）且满足以下条件时启用作品/剧集结构分组：
     * - `foldersFirst = true`，或当前目录不含可见文件夹（不存在文件夹与文件混排的冲突）。
     *
     * 其他所有排序方式（创建时间、大小、时长、名称倒序）以及带文件夹的混排模式均走严格排序投影
     * [buildOrderedDriveItems]，绝不被作品/番号分组打乱顺序，同时依然完整剥离外挂字幕与外挂音轨分区。
     */
    fun usesWorkGrouping(
        files: List<FileStat>,
        structure: DriveStructure?,
        hideFolded: Boolean,
        parseNames: Boolean,
        foldersFirst: Boolean,
        order: PikoFileSortOrder,
    ): Boolean {
        if (!parseNames || structure == null || structure.blocks.isEmpty()) return false
        if (order != PikoFileSortOrder.NAME_ASC) return false
        if (foldersFirst) return true
        val foldingApplied = hideFolded && isFoldingScope(files)
        return files.none { it.isFolder && (it.isImportPreview || !(foldingApplied && it.id in structure.foldedIds)) }
    }

    fun items(
        files: List<FileStat>, structure: DriveStructure?, hideFolded: Boolean,
        parseNames: Boolean, foldersFirst: Boolean, order: PikoFileSortOrder,
        isExpanded: (DriveBlock) -> Boolean,
    ): List<DriveListItem> {
        if (!parseNames || structure == null) return files.map { DriveListItem.File(it, null) }
        if (usesWorkGrouping(files, structure, hideFolded, parseNames, foldersFirst, order)) {
            return buildDriveItems(files, structure, hideFolded, isExpanded)
        }
        return buildOrderedDriveItems(files, structure, hideFolded, isExpanded)
    }
}
