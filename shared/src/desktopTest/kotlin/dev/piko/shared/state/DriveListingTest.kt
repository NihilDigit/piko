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
        val listed = items(listOf("[G] Show - 01 [1080p].mkv", "[G] Show - 02 [1080p].mkv", "[G] Show - 03 [1080p].mkv", "Lonely Clip.mp4"))
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

    @Test
    fun `only the same content collapses into versions`() {
        val listed = items(listOf("[G] Show - 01 [1080p].mkv", "[G] Show - 01 [720p].mkv", "[G] Show - 02 [1080p].mkv"))
        val rows = listed.filterIsInstance<DriveListItem.File>()
        assertEquals(2, rows.size, "01 的 720p 挂在 1080p 下面")
        assertTrue("2 版本" in rows.first { it.file.name.contains("01") }.view!!.tags)

        // 编号相同而内容不同的，照旧各占一行
        val different = items(listOf("3 (14).mp4", "4 (14).mp4", "5 (14).mp4"))
        assertEquals(3, different.filterIsInstance<DriveListItem.File>().size)
        // 形似 CRC 的 1166282A 与 1166282B 是两段
        val parts = items(listOf("ABC-PPV-1166282A.mp4", "ABC-PPV-1166282B.mp4"))
        assertEquals(2, parts.filterIsInstance<DriveListItem.File>().size)
    }

    @Test
    fun `small groups and the rest lie flat without headers`() {
        // 两集的剧集、没有作品名的系列与其余文件都平铺，不起标题
        val listed = items(listOf("A Clip Name.mp4", "site.vip@01.mp4", "site.vip@02.mp4", "site.vip@03.mp4", "[G] Show - 01.mkv", "[G] Show - 02.mkv"))
        assertTrue(listed.none { it is DriveListItem.SectionHeader || it is DriveListItem.WorkHeader }, listed.toString())
        // 拆散的剧集行标题带上作品名，光一个「01」认不出是哪部
        val show = listed.filterIsInstance<DriveListItem.File>().first { it.file.name.startsWith("[G] Show - 01") }
        assertEquals("Show 01", show.view?.title)
    }

    @Test
    fun `photos next to a series stay visible`() {
        // 以照片为主的目录里夹着几段视频：视频聚成系列后，照片不能被收进默认收起的「其他文件」
        val files = listOf("clip_1.mov", "clip_2.mov", "clip_3.mov", "beach.jpg", "hill.jpg", "lake.jpg", "sea.jpg").map { file(it) }
        val structure = analyzeDriveFolder(files)
        val others = structure.blocks.single { it.id == "unknown" }
        assertTrue(others.defaultExpanded, "照片是内容，不像字体说明那样默认收起")
    }
}
