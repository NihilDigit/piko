package dev.piko.ui.screens.drive

import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.MenuQuickLimit
import dev.piko.ui.components.QuickLimit
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.layoutActions
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileActionsRowTest {
    private val file = FileStat(kind = FileKind.FILE, id = "f", name = "a.txt", fileExtension = ".txt")

    private fun handlers(desktop: Boolean, unvault: (() -> Unit)? = null) = FileActionHandlers(
        toggleStar = {}, download = {}, downloadAtQuality = {}, share = {}, rename = {}, move = {}, copy = {}, trash = {}, extract = {},
        findDuplicates = {}, canonicalNameFolder = null, downloadSegment = {}, prepareQualities = {}, copySource = {}, openSource = {},
        openInExternalPlayer = null, openInNewTab = null, togglePin = null, isPinned = false, vault = {}, unvault = unvault,
        previewHidden = null, togglePreview = {},
        putOnClipboard = if (desktop) ({ _ -> }) else null,
    )

    private fun actions(desktop: Boolean) =
        fileActions(file, itemCommands(CommandPlace.FOLDER, listOf(file)), handlers(desktop)) + DriveActions.properties {}

    @Test
    fun `only explicit quality actions prepare video variants`() {
        val video = file.copy(name = "video.mp4", mimeType = "video/mp4")
        val actions = fileActions(video, itemCommands(CommandPlace.FOLDER, listOf(video)), handlers(desktop = true))
        assertEquals(null, actions.single { it.label == "下载到本地" }.onPrepare)
        assertTrue(actions.single { it.label == "下载指定画质…" }.onPrepare != null)
        assertTrue(fileActions(file, itemCommands(CommandPlace.FOLDER, listOf(file)), handlers(true)).none { it.label == "下载指定画质…" })
    }

    @Test
    fun `fully vaulted folders retain only unvault while partial folders retain both`() {
        val folder = FileStat(kind = FileKind.FOLDER, id = "folder", name = "folder")
        for (desktop in listOf(false, true)) {
            val on = handlers(desktop, unvault = {})
            val partial = fileActions(folder, itemCommands(CommandPlace.FOLDER, listOf(folder)), on).map { it.label }
            assertTrue("归档" in partial)
            assertTrue("取消归档" in partial)
            val full = fileActions(folder, itemCommands(CommandPlace.FOLDER, listOf(folder), setOf(folder.id)), on).map { it.label }
            assertTrue("归档" !in full)
            assertTrue("取消归档" in full)
        }
    }

    @Test
    fun `the desktop menu row highlights core actions and keeps clipboard in manage`() {
        val layout = layoutActions(actions(desktop = true), quickLimit = MenuQuickLimit)
        assertEquals(listOf("下载到本地", "重命名", "分享", "添加星标"), layout.quick.map { it.label })
        val manage = layout.sections.single { section -> section.all { it.group == ActionGroup.Manage } }
        assertTrue(manage.map { it.label }.containsAll(listOf("剪切", "复制")))
    }

    @Test
    fun `the mobile panel row keeps download rename share and star`() {
        val layout = layoutActions(actions(desktop = false))
        assertEquals(listOf("下载到本地", "重命名", "分享", "添加星标"), layout.quick.map { it.label })
        assertTrue(layout.sections.flatten().none { it.label == "剪切" || it.label == "复制" })
    }

    @Test
    fun `selection quick rows preserve clipboard actions`() {
        val cut = DriveActions.cut {}
        val copy = DriveActions.copy {}
        val download = DriveActions.download {}
        val share = DriveActions.share {}
        val rename = DriveActions.rename {}
        val row = DriveActions.quickRow(cut, copy, download, share, rename, null)
        val layout = layoutActions(row + DriveActions.properties {})
        assertTrue((layout.quick + layout.sections.flatten()).containsAll(listOf(cut, copy)))
    }

    @Test
    fun `scrollable panels preserve all actions including folder subtree tools tabs pins and clipboard`() {
        val video = FileStat(
            kind = FileKind.FILE, id = "v", name = "ABC-123 Movie.mp4",
            mimeType = "video/mp4", thumbnailLink = "https://thumb",
            params = mapOf("url" to "magnet:?xt=urn:btih:0"),
        )
        val folder = FileStat(kind = FileKind.FOLDER, id = "d", name = "ABC-123", params = mapOf("url" to "https://mypikpak.com/s/x"))
        fun fullHandlers(inLibrary: Boolean, vaultMarked: Boolean) = FileActionHandlers(
            toggleStar = {}, download = {}, downloadAtQuality = {}, share = {}, rename = {}, move = {}, copy = {},
            trash = {}, extract = {}, findDuplicates = {}, canonicalNameFolder = if (inLibrary) null else ({}),
            viewSpaceMap = {}, downloadSegment = {}, prepareQualities = {}, copySource = {}, openSource = {},
            openInExternalPlayer = {}, openInNewTab = {}, togglePin = {}, isPinned = false,
            vault = {}, unvault = if (!inLibrary && vaultMarked) ({}) else null,
            previewHidden = false, togglePreview = {}, putOnClipboard = { _ -> },
        )
        fun panel(target: FileStat, place: CommandPlace, vaultMarked: Boolean = false): List<SheetAction> {
            val commands = itemCommands(place, listOf(target))
            val inLibrary = place in setOf(CommandPlace.LIBRARY, CommandPlace.RECENT, CommandPlace.HISTORY)
            val reveal = listOfNotNull(DriveActions.revealInDrive {}.takeIf { inLibrary })
            val removeRecord = listOfNotNull(DriveActions.removeRecord {}.takeIf { commands.removeRecord })
            return reveal + removeRecord + fileActions(target, commands, fullHandlers(inLibrary, vaultMarked)) + DriveActions.properties {}
        }
        fun assertFits(actions: List<SheetAction>, case: String) {
            val layout = layoutActions(actions)
            val rows = layout.sections.flatten() + layout.properties + layout.danger
            assertTrue(layout.quick.size <= QuickLimit, case)
            assertEquals(actions.toSet(), (layout.quick + rows).toSet(), case)
        }

        for (place in listOf(CommandPlace.FOLDER, CommandPlace.SEARCH, CommandPlace.RECENT, CommandPlace.HISTORY, CommandPlace.LIBRARY)) {
            assertFits(panel(video, place), "$place")
        }
        val folderInDrive = panel(folder, CommandPlace.FOLDER, vaultMarked = true)
        assertFits(folderInDrive, "folder-drive")
        val labels = folderInDrive.map { it.label }
        for (label in listOf("查重", "查看空间分布", "规范命名", "在新标签页打开", "固定到快速访问", "剪切", "复制")) {
            assertTrue(label in labels, "Missing $label in $labels")
        }
        val folderInLibrary = panel(folder, CommandPlace.LIBRARY)
        assertFits(folderInLibrary, "folder-library")
        assertTrue(folderInLibrary.any { it.label == "复制到…" })
    }
}
