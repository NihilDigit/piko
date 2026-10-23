package dev.piko.ui.screens.drive

import dev.piko.ui.components.ActionGroup
import dev.piko.ui.components.MenuQuickLimit
import dev.piko.ui.components.layoutActions
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 图标行按平台排：桌面右键菜单照 Win11 是剪切、复制、重命名、分享、下载，星标进整理组的列表；
 * 移动端面板没有剪贴板，仍是下载、分享、星标、重命名。两套共用一份 fileActions 与 layoutActions，
 * 改分档、改上限或改先后都可能让一端串到另一端的样子。
 */
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
    fun `narrow selection rows preserve clipboard actions`() {
        val cut = DriveActions.cut {}
        val copy = DriveActions.copy {}
        val download = DriveActions.download {}
        val share = DriveActions.share {}
        val rename = DriveActions.rename {}
        fun row(wide: Boolean) = DriveActions.quickRow(wide, cut, copy, download, share, rename, null)
        assertEquals(row(true).toSet(), row(false).toSet())
        val layout = layoutActions(row(false) + DriveActions.properties {})
        assertTrue((layout.quick + layout.sections.flatten()).containsAll(listOf(cut, copy)))
    }

}
