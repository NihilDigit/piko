package dev.piko.shared.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 番号的识别、归一与成批整理。仓库是公开的，这里全部用手写的合成名字，覆盖语料 README 总结的写法：
 * 分隔符与大小写、站点前缀、-U/-UC/-C 与中文标记、压制与帧率标记、分段、伴随的广告文件。
 */
class AvNamingTest {

    @Test
    fun `codes are uppercase and hyphen separated whatever the original spelling`() {
        listOf("fc2ppv1234567", "FC2_PPV_1234567", "fc2-ppv-1234567", "FC2PPV-1234567", "FC2-PPV_1234567")
            .forEach { assertEquals("FC2-PPV-1234567", normalizeAvCode(it), it) }
        listOf("abc123", "ABC_123", "abc-123", "ABC-123").forEach { assertEquals("ABC-123", normalizeAvCode(it), it) }
    }

    @Test
    fun `site specific codes are normalized`() {
        fun code(name: String) = parseMediaName(name).av?.code
        fun part(name: String) = parseMediaName(name).av?.part
        // fc 后面紧跟的数字整体是编号，打头的 2 不是 FC2 的 2
        assertEquals("FC2-PPV-2765224", code("fc2765224_1.mp4"))
        assertEquals("FC2-PPV-980638", code("FC980638_01.mp4"))
        assertEquals("1", part("FC980638_01.mp4"))
        listOf("hey4017_244-fhd1.wmv", "heydouga 4017-244_1.wmv", "HeyDouga-4017-244.mp4", "しろハメ Heydouga 4017-244.wmv")
            .forEach { assertEquals("HEYDOUGA-4017-244", code(it), it) }
        assertEquals("1", part("hey4017_244-fhd1.wmv"))
        listOf("021014-540-carib-high_1.mp4", "Caribbean-021014-540.mp4", "加勒比 021014-540 片名.mp4")
            .forEach { assertEquals("CARIB-021014-540", code(it), it) }
        assertEquals("1", part("021014-540-carib-high_1.mp4"))
        assertEquals("1PON-092415_001", code("1pon-092415_001-fhd1_(new).mp4"))
        assertEquals("N0421", code("n0421_name_surname_ta1.mp4"))
        assertEquals("N0397", code("[NoDRM]-n0397_name_ei1_n.wmv"))
        assertEquals("N0781", code("[Tokyo Hot] n0781 Some Title.wmv"))
        assertNull(code("k1080p.mp4"), "单字母前缀后紧跟字母的不是 Tokyo-Hot")
        assertEquals("123014_949", code("123014_949 片名.mp4"))
        assertEquals("072815-931", code("072815-931 片名.mp4"))
        assertNull(code("202401-001.mp4"), "月日不合法的六位数不是日期番号")
        assertEquals("ABC3DEF-47", code("ABC3DEF-47 片名.mp4"))
        assertEquals("ABCD-S94", code("ABCD-S94 片名.mkv"))
        assertNull(code("Show-S01.mkv"), "首字母大写的是季号写法")
        assertEquals("XYZ-057", code("xyz0057_02.wmv"))
        assertEquals("B", part("FC2-PPV-1166282B.mp4"))
        assertEquals("104DANDAN-015", code("104dandan-015-C 片名.mp4"))
        assertEquals("300MIUM-123", code("300MIUM-123.mp4"))
        assertTrue(parseMediaName("SSIS-123C.mp4").av!!.chineseSubtitles, "粘着的 C 仍是中字")
    }

    @Test
    fun `digits keep their original width except for dmm padding`() {
        assertEquals("HEYZO-0123", normalizeAvCode("HEYZO_0123"))
        assertEquals("HEYZO-0123", normalizeAvCode("heyzo_hd_0123_full"))
        assertEquals("ABC-012", normalizeAvCode("ABC-012"))
        assertEquals("ABCD-12345", normalizeAvCode("abcd-12345"))
        // DMM 的五位补零写法还原
        assertEquals("SSIS-123", normalizeAvCode("ssis00123"))
    }

    @Test
    fun `site prefixes markers and parts go to their own fields`() {
        fun av(name: String) = parseMediaName(name).av!!
        with(av("site.com@SSIS-123.mp4")) {
            assertEquals("SSIS-123", code)
            assertEquals("site.com", site)
        }
        assertEquals("site.net", av("[site.net]SSIS-123 some description.mp4").site)
        assertEquals("3xplanet", av("123456_3xplanet_HEYZO_1234.mp4").site)
        assertEquals("SSIS-123", av("某论坛新片@SITE01@SSIS-123-U.mp4").code)

        assertTrue(av("SSIS-123-U.mp4").uncensored)
        with(av("SSIS-123-UC.mp4")) { assertTrue(uncensored && chineseSubtitles) }
        assertTrue(av("SSIS-123-C.mp4").chineseSubtitles)
        assertTrue(av("SSIS-123C.mp4").chineseSubtitles)
        assertTrue(av("SSIS-123_60FPS_FHD_CH.mp4").chineseSubtitles)
        assertTrue(av("[中文字幕]SSIS-123.mp4").chineseSubtitles)
        assertTrue(av("SSIS-123 无码破解.mp4").uncensored)
        assertTrue(av("[Ucensored] SSIS-123.mp4").uncensored, "Ucensored 是语料里真实出现的拼写错误")

        assertEquals("CD1", av("SSIS-123-cd1.mp4").part)
        assertEquals("CD2", av("SSIS-123 CD2.mp4").part)
        assertEquals("A", av("SSIS-123-A.mp4").part)
        assertEquals("CD1", av("SSIS-123.part1.mp4").part)
        assertEquals(listOf("AI"), av("SSIS-123-AI.mp4").marks)
        // 空格隔开的是片名描述，不是后缀
        assertEquals(emptyList(), av("SSIS-123 S Model 24.mp4").marks)
    }

    @Test
    fun `tags from suffixes and leading brackets`() {
        fun tags(name: String) = parseMediaName(name).tags.map { it.text }.toSet()
        assertEquals(setOf("60fps", "1080p", MediaTag.CHINESE_SUBTITLES), tags("SSIS-123_60FPS_FHD_CH.mp4"))
        assertEquals(setOf("4K"), tags("SSIS-123-4K.mp4"))
        assertEquals(setOf("HEVC"), tags("FC2-PPV-1234567.H265.mp4"))
        assertEquals(setOf("720p"), tags("[MP4/720p] FC2PPV-1234567.mp4"))
    }

    @Test
    fun `subtitle language suffix is not the chinese subtitle flag of a video`() {
        val sub = parseMediaName("SSIS-123-zh.srt")
        assertEquals("SSIS-123", sub.av?.code)
        assertEquals("简", sub.language)
        assertEquals(false, sub.av?.chineseSubtitles)
        assertEquals("SSIS-123", parseMediaName("ssis00123pl.jpg").av?.code)
    }

    @Test
    fun `anime names are never taken for codes`() {
        listOf(
            "[AonE]_Naruto_198_[ACBAAC0B].avi",
            "Naruto_198.avi",
            "[HorribleSubs] One Piece - 107 [1080p].mkv",
            "DB 067 Short NEP (Reconstruction) [Hikari].mkv",
            "[Erai-raws] Shoukoku no Altair - 03 [720p AVC-YUV444P10][E-AC3].mkv",
            "VTS_01_1.VOB",
            "DBZ153.mkv",
            "HEVC-10.mkv",
            "[Nekomoe kissaten&VCB-Studio] Heike Monogatari [PV01][Ma10p_1080p][x265_flac].mkv",
        ).forEach { assertNull(parseMediaName(it).av, it) }
    }

    @Test
    fun `a code folder groups encodes into one entry and drops the bundled ads`() {
        val batch = analyzeMediaBatch(
            listOf(
                MediaFileInput("SSIS-123/site.com@SSIS-123.mp4", 5_000_000_000),
                MediaFileInput("SSIS-123/SSIS-123-C.mp4", 4_000_000_000),
                MediaFileInput("SSIS-123/SSIS-123-zh.srt", 90_000),
                MediaFileInput("SSIS-123/ssis00123pl.jpg", 150_000),
                MediaFileInput("SSIS-123/精 彩 视 频 免 费 看.mp4", 20_000_000),
                MediaFileInput("SSIS-123/example.fun.mp4", 11_000_000),
                MediaFileInput("SSIS-123/直播平台推荐.mp4", 70_000_000),
                // 文件名看不出是广告，但没有番号、又远小于同目录的正片
                MediaFileInput("SSIS-123/Some Actress Name Special Clip.mp4", 30_000_000),
                MediaFileInput("SSIS-123/永久地址.url", 120),
                MediaFileInput("SSIS-123/app.apk", 1_700_000),
                MediaFileInput("SSIS-123/readme.txt", 200),
            ),
        )
        val work = batch.works.single()
        assertEquals(WorkKind.AV, work.kind)
        assertEquals("SSIS-123", work.title)
        val entry = work.sections.single().entries.single()
        assertEquals("SSIS-123", entry.label)
        assertEquals(listOf(0, 1), entry.files.map { it.index }, "两个压制版本归成一组，大的在前")
        assertEquals(setOf(2, 3), entry.files.flatMap { it.attachments }.map { it.index }.toSet())
        assertEquals(listOf(MediaTag.CHINESE_SUBTITLES), entry.files[1].tags.map { it.text })

        val secondary = batch.secondary.associate { it.index to it.reason }
        assertEquals(mapOf(4 to SecondaryReason.AD, 5 to SecondaryReason.AD, 6 to SecondaryReason.AD, 7 to SecondaryReason.AD,
            8 to SecondaryReason.AD, 9 to SecondaryReason.AD, 10 to SecondaryReason.INFO), secondary)
    }

    @Test
    fun `small videos are not ads when the folder has no code`() {
        // 同样的体积比例放在动画目录里：PV 与 NCOP 都很短，却是正文
        val batch = analyzeMediaBatch(
            listOf(
                MediaFileInput("Show/[Group] Show - 01 [1080p].mkv", 1_500_000_000),
                MediaFileInput("Show/[Group] Show - PV 01 [1080p].mkv", 20_000_000),
                MediaFileInput("Show/[Group] Show - NCOP [1080p].mkv", 30_000_000),
            ),
        )
        assertTrue(batch.secondary.isEmpty())
        assertEquals(listOf(Section.MAIN, Section.PREVIEW, Section.CREDITLESS), batch.works.single().sections.map { it.section })
    }

    @Test
    fun `parts are separate entries in order and spellings of one code meet in one work`() {
        val batch = analyzeMediaBatch(
            listOf(
                MediaFileInput("SSIS-124-CD2.mp4", 2_000_000_000),
                MediaFileInput("SSIS-124-CD1.mp4", 2_000_000_000),
                MediaFileInput("FC2-PPV-1234567.mp4", 700_000_000),
                MediaFileInput("FC2PPV-1234567.mp4", 600_000_000),
            ),
        )
        val parts = batch.works.single { it.title == "SSIS-124" }.sections.single().entries
        assertEquals(listOf("SSIS-124 CD1", "SSIS-124 CD2"), parts.map { it.label })
        val fc2 = batch.works.single { it.title == "FC2-PPV-1234567" }.sections.single().entries.single()
        assertEquals(2, fc2.files.size)
    }

    @Test
    fun `chinese subtitle and uncensored tags stay on every row`() {
        val batch = analyzeMediaBatch(
            listOf(
                MediaFileInput("SSIS-125-UC.mp4", 3_000_000_000),
                MediaFileInput("SSIS-125_CH.mp4", 2_000_000_000),
            ),
        )
        val work = batch.works.single()
        assertTrue(work.commonTags.none { it.pinned }, "人人都有的中字也不收进作品级标签")
        val rows = work.sections.single().entries.single().files.map { file -> file.tags.map { it.text }.toSet() }
        assertEquals(listOf(setOf(MediaTag.UNCENSORED, MediaTag.CHINESE_SUBTITLES), setOf(MediaTag.CHINESE_SUBTITLES)), rows)
        assertNotEquals(rows[0], rows[1])
    }

    @Test
    fun `rows show the title and the code goes to a chip`() {
        fun av(name: String) = parseMediaName(name).av!!
        with(av("ABC-123 某部片名 演员.mp4")) {
            assertEquals("某部片名 演员", displayTitle())
            assertEquals("ABC-123", chip)
        }
        // 开头的短标记与结尾的附注去掉
        assertEquals("片名正文", av("FC2-PPV-1234567 【無】片名正文　※特典高画質.mp4").displayTitle())
        // 开头的长方括号是片名的一部分
        assertEquals("【 某个很受欢迎的系列 】 的片名", av("FC2-PPV-1234567 ~ vol.48 ~【 某个很受欢迎的系列 】 的片名.mp4").displayTitle())
        // 一长段日文里夹着「流出」，是片名，不是后缀标签
        with(av("FC2-PPV-1234567 『無』很长很长的一段日文片名里有流出两个字.mp4")) {
            assertEquals("很长很长的一段日文片名里有流出两个字", title)
            assertTrue(uncensored)
        }
        // 没写片名时番号就是标题，不再重复一个芯片
        with(av("site.com@FC2-PPV-1234567_1.mp4")) {
            assertEquals("FC2-PPV-1234567 1", displayTitle())
            assertNull(chip)
        }
    }
}
