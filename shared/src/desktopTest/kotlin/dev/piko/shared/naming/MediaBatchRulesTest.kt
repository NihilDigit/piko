package dev.piko.shared.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 成批分析里靠兄弟文件或目录才能定下来的规则，用合成的小例子逐条验证。 */
class MediaBatchRulesTest {

    private fun batch(vararg files: Pair<String, Long>) = analyzeMediaBatch(files.map { (path, size) -> MediaFileInput(path, size) })

    @Test
    fun `a trailing number shared by every file is part of the title`() {
        // 「Mob Psycho 100」单看像第 100 集；同作品两个文件都是 100，说明它是作品名
        val same = batch("Mob Psycho 100 [BD 1080p].mkv" to 8_000_000_000, "Mob Psycho 100 [BD 720p].mkv" to 4_000_000_000)
        assertEquals("Mob Psycho 100", same.works.single().title)
        assertEquals(listOf("Mob Psycho 100"), same.works.single().sections.single().entries.map { it.label })

        val distinct = batch("Some Show 01 [720p].mkv" to 300_000_000, "Some Show 02 [720p].mkv" to 300_000_000)
        assertEquals(listOf("01", "02"), distinct.works.single().sections.single().entries.map { it.label })
    }

    @Test
    fun `a dedicated directory beats the file name while a generic one is only a default`() {
        val result = batch(
            "Show/[G] Show - 01 [1080p].mkv" to 1_000_000_000,
            "Show/PV/[G] Show [NCOP][1080p].mkv" to 50_000_000,
            "Show/SPs/[G] Show [NCOP][1080p].mkv" to 50_000_000,
            "Show/SPs/[G] Show [TV Special Program][1080p].mkv" to 300_000_000,
        )
        val sections = result.works.single().sections.associate { section -> section.section to section.entries.map { it.primary.index } }
        assertEquals(listOf(1), sections[Section.PREVIEW], "PV/ 目录说了算")
        assertEquals(listOf(2), sections[Section.CREDITLESS], "SPs/ 只给默认分区，文件名写明 NCOP 时按文件名")
        assertEquals(listOf(3), sections[Section.BONUS], "SPs/ 里的 Special 是特典，不是 SP 分集")
    }

    @Test
    fun `a large untitled file is a movie only next to episodes`() {
        val alone = batch("[G][Kimi no Na wa][1080P].mkv" to 8_000_000_000)
        assertEquals(Section.MAIN, alone.works.single().sections.single().section)

        val withEpisodes = batch(
            "[G][Show][01][1080P].mkv" to 500_000_000,
            "[G][Show][02][1080P].mkv" to 500_000_000,
            "[G][Show][03][1080P].mkv" to 500_000_000,
            "[G][Show Gekijou Subtitle][1080P].mkv" to 4_000_000_000,
            // 与分集一样大的无集号文件不是剧场版
            "[G][Show Recap][1080P].mkv" to 600_000_000,
        )
        assertEquals(Section.MOVIE, withEpisodes.work("Show Gekijou Subtitle").sections.single().section)
        assertEquals(Section.MAIN, withEpisodes.work("Show Recap").sections.single().section)
    }

    @Test
    fun `seasons show in labels only when a section mixes them`() {
        val one = batch("Show.S02E01.1080p.mkv" to 1, "Show.S02E02.1080p.mkv" to 1)
        assertEquals(listOf("01", "02"), one.works.single().sections.single().entries.map { it.label })
        val two = batch("Show.S01E12.1080p.mkv" to 1, "Show.S02E01.1080p.mkv" to 1)
        assertEquals(listOf("S01E12", "S02E01"), two.works.single().sections.single().entries.map { it.label })
    }

    @Test
    fun `versions show in labels only when they tell entries apart`() {
        val mixed = batch("[G] Show - 10 [720p].mkv" to 1, "[G] Show - 10v2 [720p].mkv" to 1, "[G] Show - 11 [720p].mkv" to 1)
        assertEquals(listOf("10", "10 v2", "11"), mixed.works.single().sections.single().entries.map { it.label })
        val all = batch("[G] Show - 10 [v2].mkv" to 1, "[G] Show - 11 [v2].mkv" to 1)
        assertEquals(listOf("10", "11"), all.works.single().sections.single().entries.map { it.label })
    }

    @Test
    fun `korean smi subtitles attach to their episode instead of folding as documents`() {
        val result = batch("Show.E01.avi" to 700_000_000, "Show.E01.smi" to 80_000, "Show.E02.avi" to 700_000_000)
        assertEquals(listOf(FileRole.CONTENT, FileRole.ATTACHMENT, FileRole.CONTENT), result.roles)
    }

    @Test
    fun `unrecognized files keep a null label so the ui shows the full name`() {
        val result = batch("output/c1.mkv" to 1_000_000, "output/0000-0049.mov" to 1_000_000)
        val work = result.works.single()
        assertEquals(WorkKind.UNKNOWN, work.kind)
        assertEquals(listOf(null, null), work.sections.flatMap { it.entries }.map { it.label })
    }

    @Test
    fun `large files without the episodes group are not movies`() {
        // 几段小分段拉低中位数，其余整段视频没有发布组，不是剧场版
        val collection = batch(
            "Clip Name (1).mp4" to 50_000_000,
            "Clip Name (2).mp4" to 50_000_000,
            "Clip Name (3).mp4" to 50_000_000,
            "Some Long Video.mp4" to 2_000_000_000,
        )
        assertEquals(Section.MAIN, collection.work("Some Long Video").sections.single().section)
    }

    @Test
    fun `opaque names are numbered by name and same times get a suffix`() {
        val result = batch(
            "5_6190741636838855061_(new).avi" to 1,
            "5_6190741636838855047_(new).avi" to 1,
            "VID_20260913_090829_470.mp4" to 1,
            "VID_20260913_090829_383.mp4" to 1,
        )
        val labels = result.parsed.map { it.label }
        assertEquals(listOf("视频 2", "视频 1"), labels.take(2), "按文件名的自然顺序编号，与输入顺序无关")
        assertEquals(listOf("相机 2026-09-13 09:08 (2)", "相机 2026-09-13 09:08 (1)"), labels.drop(2))
        assertEquals(4, result.works.size, "各自成一部，才能与其他独立文件一起平铺")
    }

    @Test
    fun `tweet media downloads group under the account by post time`() {
        val result = batch(
            "someone_20220625__1540494487322931201_1_15404944110159339520.mp4" to 1,
            "someone_20220625__1540651516796604416_1_15406514479764643840.mp4" to 1,
        )
        val work = result.works.single()
        assertEquals("someone", work.title)
        // 同一天的两条推靠推文 ID 里的时间分开，不会并成一个条目的两个版本
        assertEquals(2, work.sections.single().entries.size)
    }

    @Test
    fun `uploader numbering of unrelated clips is not a special section`() {
        val result = batch(
            "SP01 第一个短片.mp4" to 1,
            "SP02 另一个标题.mp4" to 1,
            "SP03 完全不同的片子.mp4" to 1,
        )
        assertTrue(result.works.all { work -> work.sections.single().section == Section.MAIN })
        assertEquals("SP02 另一个标题", result.parsed[1].title)
    }

    @Test
    fun `a leading uploader number leaves the trailing number in the title`() {
        val result = batch(
            "22 Cyberthing 2077.mp4" to 1,
            "23 另一个片子.mp4" to 1,
            "26 第三个标题.mp4" to 1,
        )
        assertEquals("22 Cyberthing 2077", result.parsed[0].title)
        assertNull(result.parsed[0].episode, "2077 是标题的一部分，不是集号")
    }

    @Test
    fun `a copy marker in the middle of a name is not an episode`() {
        val result = batch("某人  IMG_5845 (1) 6669.mp4" to 1, "某人  IMG_5850 (1) 6669.mp4" to 1)
        assertTrue(result.parsed.all { it.episode?.number != 1 }, result.parsed.toString())
    }

    @Test
    fun `sibling alignment finds parts written after a description`() {
        val parts = batch(
            "Heydouga 4017-226 (某人)_4k5.wmv" to 1,
            "Heydouga 4017-226 (某人)_4k6.wmv" to 1,
            "Heydouga 4017-226 (某人)_4k7.wmv" to 1,
        )
        // 分段写在描述后面，后缀扫描认不出；没有对齐的话三段是同一条目的三个版本
        assertEquals(3, parts.works.single().sections.single().entries.size)
    }

    @Test
    fun `quality variants stay versions of one entry`() {
        val versions = batch("ABC-123 1080p.mp4" to 2, "ABC-123 720p.mp4" to 1)
        assertEquals(1, versions.works.single().sections.single().entries.size, "1080 与 720 不是分段号")
    }

    @Test
    fun `aligned works take their title from the constant text`() {
        // 编号打头时作品名在后面
        val leading = batch("0499-Someone_HEVC.mp4" to 1, "0501-Someone_HEVC.mp4" to 1, "0510-Someone_HEVC.mp4" to 1)
        assertEquals("Someone", leading.works.single().title)
        // 逐文件一致解析成站点前缀的残渣时，用不变文字
        val site = batch("www.site.la@分类甲1.mp4" to 1, "www.site.la@分类甲2.mp4" to 1, "www.site.la@分类甲3.mp4" to 1)
        assertEquals("分类甲", site.works.single().title)
        // 时间之后的毫秒不是编号
        val recorded = batch("主播_20220907-015242-325.mp4" to 1, "主播_20220908-011502-812.mp4" to 1, "主播_20220909-020012-104.mp4" to 1)
        assertTrue(recorded.parsed.none { it.episode != null }, recorded.parsed.map { it.label }.toString())
    }
}
