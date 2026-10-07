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

    private fun handlers(desktop: Boolean) = FileActionHandlers(
        toggleStar = {}, download = {}, share = {}, rename = {}, move = {}, copy = {}, trash = {}, extract = {},
        findDuplicates = {}, downloadSegment = {}, prepareQualities = {}, copySource = {}, openSource = {},
        openInExternalPlayer = null, openInNewTab = null, togglePin = null, isPinned = false, vault = null, unvault = null,
        previewHidden = null, togglePreview = {},
        putOnClipboard = if (desktop) ({ _ -> }) else null,
    )

    private fun actions(desktop: Boolean) =
        fileActions(file, itemCommands(CommandPlace.FOLDER, listOf(file)), handlers(desktop)) + DriveActions.properties {}

    @Test
    fun `the desktop menu row follows the windows 11 context menu and lists the star`() {
        val layout = layoutActions(actions(desktop = true), foldMore = false, quickLimit = MenuQuickLimit)
        assertEquals(listOf("剪切", "复制", "重命名", "分享", "下载到本地"), layout.quick.map { it.label })
        val organize = layout.sections.single { section -> section.all { it.group == ActionGroup.Organize } }
        assertEquals("添加星标", organize.first().label)
    }

    @Test
    fun `the mobile panel row keeps download share star and rename`() {
        val layout = layoutActions(actions(desktop = false))
        assertEquals(listOf("下载到本地", "分享", "添加星标", "重命名"), layout.quick.map { it.label })
        assertTrue(layout.sections.flatten().none { it.label == "剪切" || it.label == "复制" })
    }
}
