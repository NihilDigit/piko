package dev.piko.shared.state

import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoFileSortOrder
import io.github.nihildigit.pikpak.FileStat

internal class DriveListProjection(private val repository: PikoDriveRepository) {
    fun files(base: List<FileStat>, folder: String, library: DriveLibrary?): List<FileStat> {
        val source = if (library == DriveLibrary.STARRED || library == DriveLibrary.TRASH)
            (base + repository.optimisticCandidates()).distinctBy { it.id } else base
        val projected = repository.projectOptimisticFiles(source, folder.takeIf { library == null }, library == DriveLibrary.TRASH)
        return if (library == DriveLibrary.STARRED) projected.filter { it.isStarred } else projected
    }

    fun items(
        files: List<FileStat>, structure: DriveStructure?, hideFolded: Boolean,
        parseNames: Boolean, foldersFirst: Boolean, order: PikoFileSortOrder,
        isExpanded: (DriveBlock) -> Boolean,
    ): List<DriveListItem> {
        if (structure == null) return files.map { DriveListItem.File(it, null) }
        if (!parseNames || !foldersFirst || structure.blocks.isEmpty() ||
            order == PikoFileSortOrder.DURATION_DESC || order == PikoFileSortOrder.DURATION_ASC
        ) return filterDriveFiles(files, structure.foldedIds, enabled = hideFolded, revealAll = false).map { file ->
            val view = if (parseNames) structure.views[file.id]?.let {
                if (it.heading.isNotBlank()) it.withTitle(it.heading) else it
            } else null
            DriveListItem.File(file, view)
        }
        return buildDriveItems(files, structure, hideFolded, isExpanded)
    }
}
