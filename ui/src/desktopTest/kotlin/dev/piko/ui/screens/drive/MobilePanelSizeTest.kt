package dev.piko.ui.screens.drive

import dev.piko.ui.components.QuickLimit
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.layoutActions
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertTrue

/** 窄窗口面板通过滚动承载完整操作，图标行容量不应裁掉能力。 */
class MobilePanelSizeTest {
    private fun video(name: String) = FileStat(
        kind = FileKind.FILE,
        id = "v",
        name = name,
        mimeType = "video/mp4",
        thumbnailLink = "https://thumb",
        params = mapOf("url" to "magnet:?xt=urn:btih:0"),
    )

    private val folder = FileStat(kind = FileKind.FOLDER, id = "d", name = "ABC-123", params = mapOf("url" to "https://mypikpak.com/s/x"))

    private fun mobileHandlers(inLibrary: Boolean, vaultMarked: Boolean) = FileActionHandlers(
        toggleStar = {}, download = {}, downloadAtQuality = {}, share = {}, rename = {}, move = {},
        copy = {},
        trash = {}, extract = {},
        findDuplicates = {},
        canonicalNameFolder = if (inLibrary) null else ({}),
        downloadSegment = {}, prepareQualities = {},
        copySource = {}, openSource = {},
        openInExternalPlayer = {},
        openInNewTab = {}, togglePin = {}, isPinned = false,
        vault = {},
        unvault = if (!inLibrary && vaultMarked) ({}) else null,
        previewHidden = false, togglePreview = {},
        putOnClipboard = { _ -> },
    )

    private fun panel(file: FileStat, place: CommandPlace, vaultMarked: Boolean = false): List<SheetAction> {
        val commands = itemCommands(place, listOf(file))
        val inLibrary = place in setOf(CommandPlace.LIBRARY, CommandPlace.RECENT, CommandPlace.HISTORY)
        val reveal = listOfNotNull(DriveActions.revealInDrive {}.takeIf { inLibrary })
        val removeRecord = listOfNotNull(DriveActions.removeRecord {}.takeIf { commands.removeRecord })
        return reveal + removeRecord + fileActions(file, commands, mobileHandlers(inLibrary, vaultMarked)) + DriveActions.properties {}
    }

    private fun assertFits(actions: List<SheetAction>, case: String) {
        val layout = layoutActions(actions)
        val rows = layout.sections.flatten() + layout.properties + layout.danger
        assertTrue(layout.quick.size <= QuickLimit, "$case: ${layout.quick.map { it.label }}")
        assertTrue((layout.quick + rows).toSet() == actions.toSet(), "$case: 排列丢失了操作")
    }

    @Test
    fun `scrollable panels preserve every available action`() {
        val inFolder = panel(video("ABC-123 某片名.mp4"), CommandPlace.FOLDER)
        assertTrue(inFolder.any { it.label == "归档" })
        assertFits(inFolder, "番号视频，防窥，带来源")
        assertFits(panel(video("ABC-123 某片名.mp4"), CommandPlace.SEARCH), "搜索结果里的番号视频")
        assertFits(panel(video("ABC-123 某片名.mp4"), CommandPlace.RECENT), "最近添加里的番号视频")
        assertFits(panel(video("ABC-123 某片名.mp4"), CommandPlace.HISTORY), "播放历史里的番号视频")
        // 查重与规范命名的结果页、星标都算 LIBRARY
        assertFits(panel(video("ABC-123 某片名.mp4"), CommandPlace.LIBRARY), "查重结果里的番号视频")
        assertFits(panel(folder, CommandPlace.FOLDER, vaultMarked = true), "有归档标记的文件夹")
        assertFits(panel(folder, CommandPlace.LIBRARY), "星标里的文件夹")
    }

    @Test
    fun `folder panels retain subtree actions tabs pins and clipboard`() {
        val actions = panel(folder, CommandPlace.FOLDER, vaultMarked = true)
        val labels = actions.map { it.label }
        for (label in listOf("查找重复", "按番号规范命名", "在新标签页打开", "固定到快速访问", "剪切", "复制")) {
            assertTrue(label in labels, "缺少 $label：$labels")
        }
        assertTrue(panel(folder, CommandPlace.LIBRARY).any { it.label == "复制到…" })
    }
}
