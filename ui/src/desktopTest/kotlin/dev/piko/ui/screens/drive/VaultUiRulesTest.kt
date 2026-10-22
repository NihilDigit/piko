package dev.piko.ui.screens.drive

import dev.piko.shared.state.FolderVaultSession
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultUiRulesTest {
    @Test
    fun `size choice only appears when it changes a nonempty subset`() {
        assertFalse(offersVaultSizeChoice(null))
        assertFalse(offersVaultSizeChoice(survey(3)))
        assertFalse(offersVaultSizeChoice(survey(3, 0)))
        assertFalse(offersVaultSizeChoice(survey(3, 3)))
        assertFalse(offersVaultSizeChoice(survey(0, 0)))
        assertTrue(offersVaultSizeChoice(survey(3, 1)))
        assertTrue(offersVaultSizeChoice(survey(3, 2)))
    }

    @Test
    fun `fully vaulted selection hides archive only when every target is a full folder`() {
        val full = FileStat(kind = FileKind.FOLDER, id = "full", name = "full")
        val partial = FileStat(kind = FileKind.FOLDER, id = "partial", name = "partial")
        val file = FileStat(kind = FileKind.FILE, id = "file", name = "file")
        val fullIds = setOf(full.id)
        val allowed = setOf(CommandPlace.ROOT, CommandPlace.FOLDER, CommandPlace.SEARCH)
        for (place in CommandPlace.entries) {
            assertFalse(itemCommands(place, listOf(full), fullIds).vault, "$place")
            assertEquals(place in allowed, itemCommands(place, listOf(full), fullIds).unvault, "$place")
            assertEquals(place in allowed, itemCommands(place, listOf(full, partial), fullIds).vault, "$place")
            assertEquals(place in allowed, itemCommands(place, listOf(full, file), fullIds).vault, "$place")
        }
    }

    private fun survey(files: Int, largeFiles: Int? = null): FolderVaultSession.Survey = FolderVaultSession.Survey(
        files = files,
        bytes = files.toLong(),
        unsourcedFiles = 0,
        unsourcedBytes = 0,
        largeFiles = largeFiles?.let { survey(it) },
    )
}
