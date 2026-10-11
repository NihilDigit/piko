package dev.piko.shared.presentation

import dev.piko.shared.data.DriveChangeJournal
import dev.piko.shared.state.FolderVaultSession

fun FolderVaultSession.Record.needsAttention(undo: DriveChangeJournal.UndoResult?): Boolean = when (undo?.status) {
    DriveChangeJournal.UndoStatus.RUNNING, DriveChangeJournal.UndoStatus.UNDONE -> false
    DriveChangeJournal.UndoStatus.FAILED -> true
    null -> completion != FolderVaultSession.Completion.SUCCEEDED
}
