package dev.piko.ui.screens.drive

import androidx.compose.ui.unit.dp
import dev.piko.shared.data.ArchiveEntryId
import dev.piko.shared.data.ImportPreviewLocation
import dev.piko.shared.data.LocalLocation
import dev.piko.ui.adaptive.WidthClass
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriveCommandsTest {
    @Test
    fun `list sizes select columns for each width class`() {
        val expected = mapOf(
            WidthClass.Compact to listOf(1, 1, 1),
            WidthClass.Medium to listOf(2, 2, 1),
            WidthClass.Expanded to listOf(3, 2, 1),
        )
        for ((width, columns) in expected) {
            assertEquals(columns, TileSize.entries.map { tileColumns(DriveViewMode.LIST, it, width) }, "$width")
        }
        assertEquals(listOf(420.dp, 640.dp, 960.dp), TileSize.entries.map { tileMaxWidth(DriveViewMode.LIST, it) })
    }

    @Test
    fun `all view modes retain three checked size choices in overflow`() {
        assertEquals(listOf("小", "中", "大"), TileSize.entries.map { it.label })
        for (mode in DriveViewMode.entries) {
            for (selected in TileSize.entries) {
                val actions = tileSizeActions(mode, selected) {}
                assertEquals(listOf("小尺寸", "中尺寸", "大尺寸"), actions.map { it.label }, "$mode")
                assertEquals(TileSize.entries.map { it == selected }, actions.map { it.checked }, "$mode $selected")
            }
        }
    }

    private fun inputs(place: CommandPlace, selecting: Boolean, nameParsing: Boolean = true, selectedVideoCount: Int = 0, feedSupported: Boolean = true) = CommandInputs(
        place = place,
        atRoot = place == CommandPlace.ROOT,
        selecting = selecting,
        clipboardFull = true,
        itemCount = 5,
        allSelected = false,
        typeCount = 3,
        filtering = false,
        feedSupported = feedSupported,
        selectedVideoCount = selectedVideoCount,
        nameParsing = nameParsing,
    )

    @Test
    fun `place commands only in a real folder and naming follows the parser switch`() {
        val folders = setOf(CommandPlace.ROOT, CommandPlace.FOLDER)
        for (place in CommandPlace.entries) {
            val on = driveCommands(inputs(place, selecting = false))
            assertEquals(place in folders, on.findDuplicates, "$place")
            assertEquals(place in folders, on.canonicalNaming, "$place")
            assertEquals(false, driveCommands(inputs(place, selecting = false, nameParsing = false)).canonicalNaming, "$place")
        }
    }

    @Test
    fun `selection swaps create for select all and needs videos for feed`() {
        for (place in CommandPlace.entries) {
            val idle = driveCommands(inputs(place, selecting = false))
            val selecting = driveCommands(inputs(place, selecting = true))
            assertEquals(false, idle.selectAll, "$place")
            assertEquals(idle.copy(create = false, selectAll = selecting.selectAll, gallery = false, feed = false), selecting, "$place")
        }
    }

    @Test
    fun `selected videos open a fixed feed from browsable places`() {
        val nonFeedPlaces = setOf(CommandPlace.TRASH, CommandPlace.ARCHIVE, CommandPlace.IMPORT_PREVIEW)
        for (place in CommandPlace.entries) {
            val selected = inputs(place, selecting = true, selectedVideoCount = 2)
            assertEquals(place !in nonFeedPlaces, driveCommands(selected).gallery, "$place")
            assertEquals(place !in nonFeedPlaces, driveCommands(selected).feed, "$place")
            assertEquals(false, driveCommands(inputs(place, selecting = true, selectedVideoCount = 2, feedSupported = false)).feed)
            assertEquals(false, driveCommands(inputs(place, selecting = true)).gallery)
            assertEquals(false, driveCommands(inputs(place, selecting = true)).feed)
        }
    }

    @Test
    fun `empty folders retain paste when the clipboard has files`() {
        for (place in CommandPlace.entries) {
            for (clipboardFull in listOf(false, true)) {
                val commands = driveCommands(CommandInputs(
                    place = place,
                    atRoot = place == CommandPlace.ROOT,
                    selecting = false,
                    clipboardFull = clipboardFull,
                    itemCount = 0,
                    allSelected = false,
                    typeCount = 0,
                    filtering = false,
                    feedSupported = false,
                    nameParsing = true,
                ))
                assertEquals(clipboardFull && place in setOf(CommandPlace.ROOT, CommandPlace.FOLDER), commands.paste, "$place")
                assertEquals(false, commands.findDuplicates, "$place")
                assertEquals(false, commands.canonicalNaming, "$place")
                assertEquals(false, commands.emptyPlace, "$place")
            }
        }
    }

    @Test
    fun `viewSpaceMap is enabled only for a single real cloud folder`() {
        val cloudFolder = FileStat(kind = FileKind.FOLDER, id = "cloud-folder-1", name = "Movies")
        val cloudFile = FileStat(kind = FileKind.FILE, id = "cloud-file-1", name = "movie.mkv")
        val localFolder = FileStat(kind = FileKind.FOLDER, id = LocalLocation("mount-1").id, name = "Local")
        val archiveFolder = FileStat(kind = FileKind.FOLDER, id = ArchiveEntryId.of("arc", "", 0, "sub"), name = "Sub")
        val importFolder = FileStat(kind = FileKind.FOLDER, id = ImportPreviewLocation("root", "sub").id, name = "Preview")

        assertTrue(itemCommands(CommandPlace.ROOT, listOf(cloudFolder)).viewSpaceMap)
        assertTrue(itemCommands(CommandPlace.FOLDER, listOf(cloudFolder)).viewSpaceMap)
        assertTrue(itemCommands(CommandPlace.SEARCH, listOf(cloudFolder)).viewSpaceMap)
        assertTrue(itemCommands(CommandPlace.LIBRARY, listOf(cloudFolder)).viewSpaceMap)

        assertFalse(itemCommands(CommandPlace.FOLDER, listOf(cloudFile)).viewSpaceMap)
        assertFalse(itemCommands(CommandPlace.FOLDER, listOf(cloudFolder, cloudFile)).viewSpaceMap)
        assertFalse(itemCommands(CommandPlace.TRASH, listOf(cloudFolder)).viewSpaceMap)
        assertFalse(itemCommands(CommandPlace.ARCHIVE, listOf(cloudFolder)).viewSpaceMap)
        assertFalse(itemCommands(CommandPlace.ARCHIVE, listOf(archiveFolder)).viewSpaceMap)
        assertFalse(itemCommands(CommandPlace.LOCAL, listOf(localFolder)).viewSpaceMap)
        assertFalse(itemCommands(CommandPlace.FOLDER, listOf(localFolder)).viewSpaceMap)
        assertFalse(itemCommands(CommandPlace.IMPORT_PREVIEW, listOf(importFolder)).viewSpaceMap)
        assertFalse(itemCommands(CommandPlace.FOLDER, listOf(importFolder)).viewSpaceMap)
    }

    @Test
    fun `mounted roots nested files and mixed selections never expose cloud writes while keeping read-only tools`() {
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
        val local = driveCommands(CommandInputs(CommandPlace.LOCAL, false, false, true, 5, false, 2, false, true, true))
        assertFalse(local.create)
        assertFalse(local.paste)
        assertFalse(local.addLink)
        assertFalse(local.canonicalNaming)
        assertFalse(local.findDuplicates)
        assertTrue(local.sort)
        assertTrue(local.filter)
        assertTrue(local.gallery)
        assertTrue(local.feed)
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
}
