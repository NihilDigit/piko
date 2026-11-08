package dev.piko.shared.state

import dev.piko.shared.presentation.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Accepted writes outlive the browser; callbacks only touch a still-live browser. */
internal class DriveActionController(
    val files: FileCommands,
    val history: HistoryCommands,
    val vault: VaultEntryCommands,
    private val writes: FileWriteSession,
    private val scope: CoroutineScope,
    private val acceptsActions: () -> Boolean,
    private val clearSelection: () -> Unit,
    private val notify: (String) -> Unit,
) {
    fun file(command: FileWriteCommand, clear: Boolean, onSuccess: (() -> Unit)?): Boolean =
        when (val result = files.submit(command)) {
            is FileCommandResult.Rejected -> false.also { notify(result.reason) }
            is FileCommandResult.Accepted -> {
                if (clear) clearSelection()
                if (onSuccess != null) scope.launch {
                    val completed = writes.awaitResult(result.workId)
                    if (acceptsActions() && completed?.completion == FileWriteCompletion.SUCCEEDED) onSuccess()
                }
                true
            }
        }

    fun history(command: HistoryEditCommand) {
        when (val result = history.submit(command)) {
            is HistoryCommandResult.Accepted -> clearSelection()
            is HistoryCommandResult.Rejected -> notify(result.reason)
        }
    }

    fun vault(command: VaultEntryEditCommand, clear: Boolean): Boolean = when (val result = vault.submit(command)) {
        is VaultEntryCommandResult.Rejected -> false.also { notify(result.reason) }
        is VaultEntryCommandResult.Accepted -> true.also { if (clear) clearSelection() }
    }
}
