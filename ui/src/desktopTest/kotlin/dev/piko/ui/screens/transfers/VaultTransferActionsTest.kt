package dev.piko.ui.screens.transfers

import dev.piko.shared.data.VaultEntry
import dev.piko.shared.state.FolderVaultSession
import dev.piko.shared.state.MissingVaultEntry
import dev.piko.shared.state.TransferItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultTransferActionsTest {
    private val work = FolderVaultSession.Work(1, "folder", restoring = true, createdAtMs = 0)

    @Test
    fun `missing sources open import with newline separated links`() {
        val sources = listOf("magnet:?xt=urn:btih:first", "https://mypikpak.com/s/second")
        val record = record(sources)
        var imported: String? = null
        val action = actions(TransferItem.Vault(work, record)) { imported = it }.single { it.label == "解析来源链接" }
        action.onClick()
        assertEquals(sources.joinToString("\n"), imported)
    }

    @Test
    fun `active work and results without source links do not offer import`() {
        assertTrue(actions(TransferItem.Vault(work)).none { it.label == "解析来源链接" })
        assertTrue(actions(TransferItem.Vault(work, record(emptyList()))).none { it.label == "解析来源链接" })
        assertTrue(actions(TransferItem.Vault(work, record(listOf("")))).none { it.label == "解析来源链接" })
    }

    private fun actions(item: TransferItem.Vault, onImport: (String) -> Unit = {}) = vaultTransferActions(
        item = item,
        stopping = false,
        onStop = {},
        onRemoveRecord = {},
        canUndo = false,
        undo = null,
        onUndo = {},
        onCreateImport = onImport,
    )

    private fun record(sources: List<String>) = FolderVaultSession.Record(
        work = work,
        completion = FolderVaultSession.Completion.PARTIAL,
        title = "未能全部恢复",
        message = "部分内容已失效",
        finishedAtMs = 1,
        missingEntries = sources.mapIndexed { index, source ->
            MissingVaultEntry("folder", VaultEntry("entry-$index", "file-$index", 1, "hash-$index", source = source, addedAt = 0))
        },
    )
}
