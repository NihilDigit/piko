package dev.piko.ui.screens.drive

import dev.piko.ui.components.QuickLimit
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.layoutActions
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 移动端的操作面板没有「更多」，全部平铺；增加显式画质入口后，最长列表允许滚动，列表行（含属性与危险项）至多 9 行，
 * 图标行至多 4 个。这里取各类条目里最挤的情形，按 DriveScreen.operationsOf 在移动端的拼法（库里多在网盘中显示与移除记录，
 * 处理器里移动端为 null 的几项照样为 null）拼出整份操作。往 fileActions 里给某类条目加一项而没想到手机，这里就超。
 */
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
        copy = if (inLibrary) null else ({}),
        trash = {}, extract = {},
        findDuplicates = null,
        canonicalNameFolder = null,
        downloadSegment = {}, prepareQualities = {},
        copySource = null, openSource = null,
        openInExternalPlayer = {},
        openInNewTab = null, togglePin = null, isPinned = false,
        vault = {},
        unvault = if (!inLibrary && vaultMarked) ({}) else null,
        previewHidden = false, togglePreview = {},
        putOnClipboard = null,
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
        assertTrue(rows.size <= MaxRows, "$case: ${rows.size} 行 ${rows.map { it.label }}")
    }

    @Test
    fun `the most crowded panels keep actions within the scrollable row budget`() {
        // 文件的归档在这里已占满 8 行；库里不给归档（itemCommands.vault），最近添加里才放得下在网盘中显示与移除记录
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

    private companion object {
        // 显式画质入口增加一行；保留滚动，不通过删除其他业务动作腾位置。
        const val MaxRows = 9
    }
}
