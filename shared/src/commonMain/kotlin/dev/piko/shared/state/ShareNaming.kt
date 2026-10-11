package dev.piko.shared.state

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.DriveWriteAccess
import dev.piko.shared.data.DuplicateScanEvent
import dev.piko.shared.data.DuplicateScanner
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.runSuspendCatching
import dev.piko.shared.naming.av.AvTreeItem
import dev.piko.shared.rename.RenameRun
import dev.piko.shared.rename.planCanonicalTree
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first

internal data class ShareNamingResult(val renamed: List<DriveChangeJournal.Renamed>,
    val unconfirmed: List<DriveChangeJournal.Renamed>, val succeeded: Int, val skipped: Int,
    val goals: Map<String, String>, val roots: Map<String, String>)

internal suspend fun nameShareOutputs(repository: PikoDriveRepository, access: DriveWriteAccess, targetId: String,
    outputs: List<FileStat>, listing: List<FileStat>): ShareNamingResult {
    val items = mutableListOf<AvTreeItem>()
    val resources = mutableSetOf<String>()
    val roots = mutableMapOf<String, String>()
    var skipped = 0
    for (file in outputs) {
        if (!file.isFolder) {
            items += AvTreeItem(file.id, targetId, file.name, false)
            roots[file.id] = file.id
            continue
        }
        val scanner = DuplicateScanner(listFolder = { access.listFolder(it).getOrThrow() })
        val scan = runSuspendCatching { scanner.scan(file.id).filterIsInstance<DuplicateScanEvent.Finished>().first() }.getOrNull()
        if (scan == null || scan.failedFolders > 0 || scan.stop != null) { skipped++; continue }
        items += AvTreeItem(file.id, targetId, file.name, true)
        roots[file.id] = file.id
        resources += file.id
        (scan.subfolders + scan.files).forEach {
            items += AvTreeItem(it.file.id, it.file.parentId, it.file.name, it.file.isFolder)
            roots[it.file.id] = file.id
        }
    }
    val ids = outputs.mapTo(HashSet()) { it.id }
    val siblings = mapOf(targetId to listing.filter { it.id !in ids }.mapTo(HashSet()) { it.name })
    val plan = planCanonicalTree(items, siblings, resources = resources)
    val run = RenameRun(repository, "ShareSave", access)
    val execution = runSuspendCatching { run.execute(plan) }
    val unknown = run.unconfirmed.mapTo(HashSet()) { it.id }
    return ShareNamingResult(run.renamed.toList(), run.unconfirmed.toList(), run.succeeded,
        skipped + plan.problemCount + run.failures.count { it.source.id !in unknown } + if (execution.isFailure) 1 else 0,
        plan.rows.filter { it.isChanged && it.problem == null }.associate { it.source.id to it.newName }, roots)
}
