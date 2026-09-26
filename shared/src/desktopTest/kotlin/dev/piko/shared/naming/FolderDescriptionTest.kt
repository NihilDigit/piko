package dev.piko.shared.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderDescriptionTest {

    private val steinsGateFolder = "[DBD-Raws][命运石之门][01-24TV全集+SP+剧场版+特典映像][1080P][BDRip][HEVC-10bit][简繁日双语外挂][FLAC][MKV]"
    private val steinsGateContent = NamingFixtures.load("steins-gate-dbd").map { it.path.substringAfterLast('/') }

    // 这里的用例只关心文件名，不带服务端给的类型
    private fun describeFolder(folderName: String, contentNames: List<String>) =
        describeFolder(folderName, contentNames.map { MediaFileInput(it, 0) })

    @Test
    fun `chinese folder name takes the romaji title from its content`() {
        val folder = describeFolder(steinsGateFolder, steinsGateContent)
        assertEquals("Steins;Gate", folder.title)
        assertEquals("01–24", folder.episodeRange)
        assertEquals(listOf(Section.SPECIAL, Section.MOVIE, Section.PREVIEW, Section.CREDITLESS, Section.BONUS, Section.MENU), folder.extras)
        val tags = folder.tags.map { it.kind to it.text }.toSet()
        assertTrue((TagKind.GROUP to "DBD-Raws") in tags)
        assertTrue((TagKind.RESOLUTION to "1080p") in tags)
        assertTrue((TagKind.SOURCE to "BDRip") in tags)
        assertTrue((TagKind.SUBTITLES to "简繁") in tags)
    }

    @Test
    fun `camera counter names do not name the folder`() {
        val folder = describeFolder("家庭旅行", listOf("IMG_0216.mp4", "IMG_0219.mp4", "IMG_0222.mp4", "DSC_0012.MOV", "DSC_0013.MOV"))
        assertNull(folder.title)
        assertEquals("0216–0222", folder.episodeRange)
    }

    @Test
    fun `chinese folder name without content is reported as unrecognized`() {
        val folder = describeFolder(steinsGateFolder)
        assertNull(folder.title)
        // 集数范围、分区与标签不依赖作品名，照样给出
        assertEquals("01–24", folder.episodeRange)
        assertTrue(Section.MOVIE in folder.extras)
        assertTrue(folder.tags.any { it.kind == TagKind.GROUP && it.text == "DBD-Raws" })
    }

    @Test
    fun `partial content does not shrink the range written in the folder name`() {
        // 网盘生成封面时只列出前几项
        val folder = describeFolder(steinsGateFolder, steinsGateContent.filter { "[01]" in it || "[02]" in it })
        assertEquals("Steins;Gate", folder.title)
        assertEquals("01–24", folder.episodeRange)
    }

    @Test
    fun `a category folder is not renamed after one of its works`() {
        // 两部不相干的作品各一集，其中一集没有扩展名、类型来自服务端
        val mixed = listOf(
            MediaFileInput("[Grp] Show A - S01E08 - [WebRip 1080P].mkv", 0),
            MediaFileInput("[Grp2] Show B - 12 [WebRip 1080p HEVC-10bit AAC][END]", 0, FileKind.VIDEO),
        )
        assertNull(describeFolder("Dramas", mixed).title)
        // 分区目录是结构，里面全是同一部作品的特典也不改名
        val specials = (1..4).map { "[Grp] Show A [Special 0$it][1080p][x265_flac].mkv" }
        assertNull(describeFolder("SPs", specials).title)
    }

    @Test
    fun `range comes from content when the folder name has none`() {
        val folder = describeFolder("Some Folder", steinsGateContent)
        assertEquals("Steins;Gate", folder.title)
        assertEquals("01–24", folder.episodeRange)
    }

    @Test
    fun `latin alternative is picked from bilingual folder names`() {
        assertEquals("Heike Monogatari", describeFolder("[Nekomoe kissaten&VCB-Studio] Heike Monogatari / 平家物語 10-bit 1080p HEVC BDRip [Fin]").title)
        assertEquals(
            "Gensou Mangekyou ~The Memories of Phantasm",
            describeFolder("[DBD-Raws][幻想万华镜/Gensou Mangekyou ~The Memories of Phantasm~][20][1080P][BDRip][HEVC-10bit][简繁外挂+日文内封][FLAC][MKV]").title,
        )
    }

    @Test
    fun `season and part ranges are not episode ranges`() {
        assertEquals("01–500", describeFolder("[HorribleSubs] Naruto Shippuuden 01 - 500(Batch) [480p][720p] [1080p]").episodeRange)
        assertNull(describeFolder("[hchcsen] Yu Yu Hakusho Season 1-4 (BD Remux 1080p x264 8-bit FLAC) [Dual Audio]").episodeRange)
        assertNull(describeFolder("My Hero Academia S01-04P1+Movies (Dual audio BDremux)").episodeRange)
        assertNull(describeFolder("[shincaps] Otome Game Sekai wa Mob ni Kibishii Sekai desu 2 - 12 (AT-X 1440x1080 MPEG2 AAC)").episodeRange)
        assertNull(describeFolder("Boruto - Naruto Next Generations [2017 - 2023] COMPLETE 1080p").episodeRange)
    }

    @Test
    fun `code folders show the normalized code`() {
        val folder = describeFolder("SSIS-123_60FPS_FHD_CH")
        assertEquals("SSIS-123", folder.title)
        assertEquals(WorkKind.AV, folder.kind)
        assertTrue(folder.tags.any { it.text == MediaTag.CHINESE_SUBTITLES })
    }

    @Test
    fun `a season folder keeps its season so it does not share a title with season one`() {
        val group = "[Airota&Nekomoe kissaten&VCB-Studio]"
        val folder = "$group Yuru Camp Season 2 [Ma10p_1080p]"
        val content = (1..3).map { "$group Yuru Camp Season 2 [0$it][Ma10p_1080p][x265_flac].mkv" }
        assertEquals("Yuru Camp Season 2", describeFolder(folder).title)
        assertEquals("Yuru Camp Season 2", describeFolder(folder, content).title)
        assertEquals("Yuru Camp", describeFolder("$group Yuru Camp [Ma10p_1080p]").title)
        // 季的范围不是某一季
        assertTrue("Season" !in describeFolder("[G] Show S01-04 [1080p]").title.orEmpty())
    }

    @Test
    fun `a folder keeps the number that tells it apart from its siblings`() {
        // 拆掉编号，同级的两个文件夹就同名了
        listOf("somebody9", "somebody11", "Studio758", "Scene Title Pt1 January-3rd-2021", "FC2(制作商)")
            .forEach { assertNull(describeFolder(it).title, it) }
    }

    @Test
    fun `names with nothing extracted are not rewritten`() {
        // 只换分隔符不算提取出东西
        listOf("some_user", "some.user.name").forEach { assertNull(describeFolder(it).title, it) }
        // 论坛前缀洗掉就是提取
        assertEquals("P", describeFolder("www.example.la@P").title)
    }

    @Test
    fun `sizes counts and dates are not tags`() {
        fun tags(name: String) = describeFolder(name).tags.map { it.text }
        assertFalse("200p" in tags("合集【80V+200P 8.5G】"), "200P 是图片张数")
        assertFalse("简" in tags("@someone(69.2 GB)"), "数字后面的 GB 是容量")
        assertNull(describeFolder("【某组】7月28-29号 活动").episodeRange, "月日范围是日期")
        assertFalse(MediaTag.UNCENSORED in tags("【未流出】某片"))
    }

    @Test
    fun `unrelated content does not rename a folder`() {
        // 一堆互不相干的独立视频，不能随便挑一个当文件夹名
        assertNull(describeFolder("Pack From Friends", listOf("some clip.mp4", "another video.mp4", "third thing.mp4")).title)
        // 记住的内容不含子文件夹，只剩一个文件时撑不起文件夹名
        assertNull(describeFolder("My Stuff", listOf("site.com@ABC-123.mp4")).title)
    }
}
