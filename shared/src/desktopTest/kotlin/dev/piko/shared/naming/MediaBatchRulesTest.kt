package dev.piko.shared.naming

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `unrecognized files keep a null label so the ui shows the full name`() {
        val result = batch("output/c1.mkv" to 1_000_000, "output/0000-0049.mov" to 1_000_000)
        val work = result.works.single()
        assertEquals(WorkKind.UNKNOWN, work.kind)
        assertEquals(listOf(null, null), work.sections.flatMap { it.entries }.map { it.label })
    }
}
