package dev.piko.shared.state

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 网盘一个目录的呈现：哪些文件起作品头，哪些平铺，行标题取清理后的名字还是原名。 */
class DriveListingTest {

    private fun file(name: String, size: Long = 100_000_000) = FileStat(kind = FileKind.FILE, id = "f:$name", name = name, size = size.toString())

    private fun items(names: List<String>): List<DriveListItem> {
        val files = names.map { file(it) }
        return buildDriveItems(files, analyzeDriveFolder(files), hideFolded = true) { true }
    }

    @Test
    fun `unrelated videos are listed flat without a header each`() {
        val listed = items(listOf("Some Title Here.avi", "Another Clip Name.avi", "Third Thing.mp4"))
        assertTrue(listed.none { it is DriveListItem.WorkHeader || it is DriveListItem.SectionHeader }, listed.toString())
        assertEquals(3, listed.size)
    }

    @Test
    fun `a series keeps its header next to standalone files`() {
        val listed = items(listOf("[G] Show - 01 [1080p].mkv", "[G] Show - 02 [1080p].mkv", "Lonely Clip.mp4"))
        assertTrue(listed.any { it is DriveListItem.SectionHeader && it.isWork && it.label == "Show" })
        val lonely = listed.filterIsInstance<DriveListItem.File>().single { it.file.name == "Lonely Clip.mp4" }
        assertEquals("Lonely Clip", lonely.view?.title)
    }

    @Test
    fun `copy markers are not episode numbers`() {
        // 浏览器给重名文件加的 (1)，本体也在：三个都不是剧集
        val copies = items(listOf("image-2026.04.01-x86_64.iso", "image-2026.04.01-x86_64(1).iso", "image-2026.04.01-x86_64(2).iso"))
        assertTrue(copies.none { it is DriveListItem.SectionHeader || it is DriveListItem.WorkHeader })
        // 清理后三个都叫「image」，撞名时退回原名
        copies.filterIsInstance<DriveListItem.File>().forEach { assertNull(it.view, it.file.name) }

        // 各自独一份的「…(1)」同样不是第 1 集
        val singles = items(listOf("3081 - abc_source(1).mp4", "3087 - def_source(1).mp4"))
        assertTrue(singles.none { it is DriveListItem.SectionHeader }, singles.toString())
    }
}
