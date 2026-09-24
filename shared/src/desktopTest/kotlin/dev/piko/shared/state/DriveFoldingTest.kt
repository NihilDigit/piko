package dev.piko.shared.state

import dev.piko.shared.naming.NamingFixtures
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 网盘列表的折叠改用解析器的结论之后，旧的「小于最大文件十分之一」会误藏的几类文件。
 * 网盘里一个目录就是一批，所以夹具按目录拆开，每层只给文件名与大小。
 */
class DriveFoldingTest {

    private val steinsGate = NamingFixtures.load("steins-gate-dbd")

    private fun folderOf(dir: String): List<FileStat> =
        steinsGate.filter { it.path.substringBeforeLast('/', "") == dir }
            .map { file(it.path.substringAfterLast('/'), it.size) }

    private fun file(name: String, size: Long) = FileStat(kind = FileKind.FILE, id = "f:$name", name = name, size = size.toString())

    private fun folder(name: String) = FileStat(kind = FileKind.FOLDER, id = "d:$name", name = name)

    private fun visible(files: List<FileStat>): List<String> =
        filterDriveFiles(files, analyzeDriveFolder(files).foldedIds, enabled = true, revealAll = false).map { it.name }

    @Test
    fun `short pv and creditless videos stay visible`() {
        val pv = folderOf("PV")
        // 两部作品的 PV 相差十倍以上，旧规则会把 11 MB 的那两个藏掉
        assertEquals(pv.map { it.name }, visible(pv))

        val creditless = folderOf("NCOP&NCED")
        assertTrue(creditless.any { "[NCED]" in it.name && it.sizeBytes * 10 < creditless.maxOf(FileStat::sizeBytes) })
        assertEquals(creditless.map { it.name }, visible(creditless))
    }

    @Test
    fun `release root folds the fonts folder but keeps section folders and subtitles`() {
        val root = listOf("PV", "NCOP&NCED", "menu", "特典映像", "Fonts").map(::folder) + folderOf("")
        val shown = visible(root)
        assertFalse("Fonts" in shown)
        assertTrue(listOf("PV", "NCOP&NCED", "menu", "特典映像").all { it in shown })
        assertTrue(shown.any { it.endsWith("[01][1080P][BDRip][HEVC-10bit][FLAC].scjp.ass") }, "字幕是附件，不算次要")
    }

    @Test
    fun `subtitles become tags on their video instead of rows`() {
        val files = folderOf("")
        val items = buildDriveItems(files, analyzeDriveFolder(files), hideFolded = true) { true }
        val rows = items.filterIsInstance<DriveListItem.File>()
        assertTrue(rows.none { it.file.name.endsWith(".ass") })
        val first = rows.single { it.file.name.startsWith("[DBD-Raws][Steins;Gate][01]") }
        assertEquals("01", first.view?.title)
        assertEquals(2, first.view!!.tags.size, "简繁两份字幕各成一个标签：${first.view!!.tags}")
    }

    @Test
    fun `bundled ads in a code folder are folded`() {
        val files = listOf(
            file("SSIS-123.mp4", 5_000_000_000),
            file("SSIS-123-C.mp4", 4_000_000_000),
            file("直播平台推荐.mp4", 70_000_000),
            file("Some Actress Name Special Clip.mp4", 30_000_000),
            file("永久地址.url", 120),
            file("app.apk", 1_700_000),
        )
        assertEquals(listOf("SSIS-123.mp4", "SSIS-123-C.mp4"), visible(files))
    }

    @Test
    fun `images outnumbering the single video are content`() {
        val images = (1..60).map { file("IMG_%04d.jpg".format(it), 3_000_000) }
        val files = images + file("clip.mp4", 1_500_000_000)
        // 旧规则以视频的十分之一为门槛，60 张图片会全部被藏
        assertEquals(files.map { it.name }, visible(files))
    }

    @Test
    fun `a single block gets no header and cannot be collapsed`() {
        // 用户进了 PV 目录：整块都是默认收起的 PV，收起就什么都看不到了
        val pv = folderOf("PV").filter { "[Steins;Gate]" in it.name }
        val items = buildDriveItems(pv, analyzeDriveFolder(pv), hideFolded = true) { false }
        assertEquals(0, items.count { it is DriveListItem.SectionHeader })
        assertEquals(2, items.count { it is DriveListItem.File })
    }
}
