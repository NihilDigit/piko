package dev.piko.shared.state

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.data.PikoPathBreadcrumb
import io.github.nihildigit.pikpak.FileStat

data class ShareSource(val id: String, val name: String, val folder: Boolean, val bytes: Long, val hash: String) {
    companion object { fun of(file: FileStat) = ShareSource(file.id, file.name, file.isFolder, file.sizeBytes, file.hash) }
}

/** 提交快照不保存提取码、passCodeToken 或签名直链。 */
data class ShareSaveCommand(val shareId: String, val sources: List<ShareSource>, val target: PikoPathBreadcrumb,
    val ancestors: List<String>, val canonicalNames: Boolean = false)
enum class ShareSavePhase { PREPARING, SUBMITTING, TRACKING, IDENTIFYING, NAMING, FINISHED }
enum class ShareCopyState { UNKNOWN, COMPLETE, ERROR, REFUSED }
enum class ShareSaveCompletion { SUCCEEDED, PARTIAL, FAILED, UNCONFIRMED }
data class ShareSaveResult(val completion: ShareSaveCompletion, val message: String, val confirmedSources: Set<String>,
    val unconfirmedSources: Set<String>, val failedSources: Set<String>, val notAttemptedSources: Set<String>, val finishedAtMs: Long)
data class ShareSaveWork(val id: Long, val account: String, val command: ShareSaveCommand, val createdAtMs: Long,
    val journalGeneration: Long = 0L,
    val phase: ShareSavePhase = ShareSavePhase.PREPARING, val remoteTaskId: String? = null,
    val remoteProgress: Int? = null, val copyState: ShareCopyState = ShareCopyState.UNKNOWN,
    val beforeIds: Set<String>? = null, val baselineAvailable: Boolean = false, val associatedOutputs: Map<String, String> = emptyMap(),
    val confirmedSources: Set<String> = emptySet(), val renamedCount: Int = 0, val namingSkipped: Int = 0,
    val pendingRenames: List<DriveChangeJournal.Renamed> = emptyList(), val undoChangeIds: List<Long> = emptyList(),
    val renameGoals: Map<String, String> = emptyMap(),
    val renameSources: Map<String, String> = emptyMap(),
    val refusalMessage: String? = null, val needsPassCode: Boolean = false,
    val recordedRenameIds: Set<String> = emptySet(), val undoUnavailableNames: Set<String> = emptySet(), val undoLimited: Boolean = false,
    val result: ShareSaveResult? = null) {
    val isRunning: Boolean get() = result == null
    val sourceIds: Set<String> get() = command.sources.mapTo(HashSet()) { it.id }
    val blockedSources: Set<String> get() = if (isRunning) sourceIds else result?.unconfirmedSources.orEmpty() + pendingRenames.mapNotNull { renameSources[it.id] }
    val title: String get() = tr("转存 ${command.sources.size} 项到「${command.target.name}」", "Save ${enCount(command.sources.size, "item", "items")} to “${command.target.name}”", "${command.sources.size} 項目を「${command.target.name}」に保存", "${command.sources.size}개 항목을 “${command.target.name}”에 저장", "轉存 ${command.sources.size} 項至「${command.target.name}」")
}
data class ShareSaveOutcome(val account: String, val id: Long, val title: String, val result: ShareSaveResult)
