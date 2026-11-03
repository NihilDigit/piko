package dev.piko.ui.screens.drive

import dev.piko.shared.data.LocalLocation
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.*

class LocalDriveCommandsTest {
    @Test fun mountedRootsNestedFilesAndMixedSelectionsNeverExposeCloudWrites() {
        val root = FileStat(id = LocalLocation("mount-1").id, kind = FileKind.FOLDER, name = "Local")
        val video = FileStat(id = LocalLocation("mount-1", listOf("movie.mkv"), false).id, name = "movie.mkv")
        val cloud = FileStat(id = "cloud", name = "cloud.mkv")
        for (place in listOf(CommandPlace.ROOT, CommandPlace.FOLDER, CommandPlace.LOCAL, CommandPlace.SEARCH)) {
            for (targets in listOf(listOf(root), listOf(video), listOf(root, cloud), listOf(video, cloud))) {
                val commands = itemCommands(place, targets)
                assertFalse(commands.cutCopy)
                assertFalse(commands.rename)
                assertFalse(commands.share)
                assertFalse(commands.moveToTrash)
                assertFalse(commands.restoreOrDelete)
                assertFalse(commands.moveCopyTo)
                assertFalse(commands.download)
                assertFalse(commands.downloadAtQuality)
                assertFalse(commands.extract)
                assertFalse(commands.removeRecord)
                assertFalse(commands.vault)
                assertFalse(commands.unvault)
            }
        }
    }
    @Test fun localLocationRetainsReadOnlyExplorationAndSorting() {
        val commands = driveCommands(CommandInputs(CommandPlace.LOCAL, false, false, true, 5, false, 2, false, true, true))
        assertFalse(commands.create)
        assertFalse(commands.paste)
        assertFalse(commands.addLink)
        assertFalse(commands.canonicalNaming)
        assertFalse(commands.findDuplicates)
        assertTrue(commands.sort)
        assertTrue(commands.filter)
        assertTrue(commands.gallery)
        assertTrue(commands.feed)
    }
}
