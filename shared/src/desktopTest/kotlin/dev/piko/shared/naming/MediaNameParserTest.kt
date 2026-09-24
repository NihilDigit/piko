package dev.piko.shared.naming

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 单个文件名的解析。样本取自 piko-name-corpus 的 nyaa 部分（公开的动画发布），
 * 每条针对一种写法或一个曾经解析错的边界。
 */
class MediaNameParserTest {

    /** 作品名 | 行标题 | 分区 [| 版本]；作品名缺失写「-」，未识别写「未识别」。 */
    private fun summary(name: String): String {
        val parsed = parseMediaName(name)
        if (parsed.kind == NameKind.UNKNOWN) return "未识别"
        val parts = listOf(parsed.title ?: "-", parsed.label ?: "-", parsed.section?.name ?: "-")
        return (parts + listOfNotNull(parsed.episode?.version)).joinToString(" | ")
    }

    private fun assertParses(vararg cases: Pair<String, String>) {
        val failures = cases.mapNotNull { (name, expected) ->
            val actual = summary(name)
            if (actual == expected) null else "$name\n  期望 $expected\n  实际 $actual"
        }
        assertEquals("", failures.joinToString("\n"))
    }

    @Test
    fun `bracket style releases keep version and embedded section in the label`() = assertParses(
        "[DBD-Raws][Steins;Gate][01][1080P][BDRip][HEVC-10bit][FLAC].mkv" to "Steins;Gate | 01 | -",
        "[DBD-Raws][Steins;Gate][23][Beta.Ver][1080P][BDRip][HEVC-10bit][FLAC].mkv" to "Steins;Gate | 23 | - | Beta",
        "[DBD-Raws][Steins;Gate][25(SP)][1080P][BDRip][HEVC-10bit][FLAC].mkv" to "Steins;Gate | 25(SP) | SPECIAL",
        "[DBD-Raws][Steins;Gate][Game OP1][1080P][BDRip][HEVC-10bit][FLAC].mkv" to "Steins;Gate | Game OP1 | CREDITLESS",
        "[DBD-Raws][Steins;Gate][NCED][1080P][BDRip][HEVC-10bit][FLAC].mkv" to "Steins;Gate | NCED | CREDITLESS",
        "[DBD-Raws][Steins;Gate Fuka Ryouiki no Deja vu][PV][01][1080P][BDRip][HEVC-10bit][FLAC].mkv" to
            "Steins;Gate Fuka Ryouiki no Deja vu | PV 01 | PREVIEW",
        "[DBD-Raws][Steins;Gate][menu][03][1080P][BDRip][HEVC-10bit][FLAC].mkv" to "Steins;Gate | menu 03 | MENU",
        // 作品名之后的第二个方括号是副标题，自成一部作品
        "[DBD-Raws][Steins;Gate][Soumei Eichi no Cognitive Computing][02][1080P][BDRip][HEVC-10bit][FLAC].mkv" to
            "Steins;Gate Soumei Eichi no Cognitive Computing | 02 | -",
        // [1080] 是集号，[1080P] 是分辨率
        "[DBD-Raws][One Piece][1080][1080P][BDRip][HEVC-10bit][FLAC].mkv" to "One Piece | 1080 | -",
        "[DBD-Raws][One Piece Wano Arc][Soushuuhen][02][1080P][BDRip][HEVC-10bit][FLAC].mkv" to
            "One Piece Wano Arc | Soushuuhen 02 | SPECIAL",
        "[KTXP][Baka_Test_Shoukanjuu_1][06][GB_CN][HEVC_opus][1080p][Remaster].mkv" to "Baka Test Shoukanjuu 1 | 06 | -",
        "[Nekomoe kissaten][Kimi ga Shinu made Koi wo Shitai][12][1080p][JPTC].mp4" to "Kimi ga Shinu made Koi wo Shitai | 12 | -",
    )

    @Test
    fun `dash and season styles`() = assertParses(
        "[SubsPlease] Black Clover - 19 (1080p) [ABCC138F].mkv" to "Black Clover | 19 | -",
        // (TV) 是作品名的注释，不能让它截断作品名、把「- 001」挤到标签段后面
        "[Erai-raws] Black Clover (TV) - 001 [1080p][Multiple Subtitle][284B3626].mkv" to "Black Clover (TV) | 001 | -",
        "[Kiriya] Aikatsu! - 115v2 (BD 1920x1080 x265 FLAC) [559792E7].mkv" to "Aikatsu! | 115 | - | v2",
        "[Mezashite] Aikatsu! ‒ 173 [07F163EB].mkv" to "Aikatsu! | 173 | -",
        "[Almighty] Boruto - Naruto Next Generations - 192 [BD 1920x1080 x264 10bit FLAC].mkv" to "Boruto - Naruto Next Generations | 192 | -",
        // 绝对集号与季内集号并存时取绝对集号，跨季才能连续排序
        "[hchcsen] Yu Yu Hakusho - 098 - S04E04 [BD Remux 1080p AVC 8-bit FLAC TrueHD].mkv" to "Yu Yu Hakusho | 098 | -",
        "[A&C] Inuyasha - S05E27 (137) [BDrip] [Multi-Audio-Subs] [EB8CD0DB].mkv" to "Inuyasha | 137 | -",
        "[A&C] Inuyasha - S00E01 (Movie 01) [BDrip] [Multi-Audio-Subs] [3A1056A2].mkv" to "Inuyasha | Movie 01 | MOVIE",
        "Fairy.Tail.S02E27.075.HDTV-720p.x264.AAC.mkv" to "Fairy Tail | 075 | -",
        "[AsukaRaws] Katainaka no Ossan, Kensei ni Naru S2 - 12 (AMZN 1280x720 x264 AAC).mp4" to "Katainaka no Ossan, Kensei ni Naru | 12 | -",
        "[shincaps] Hanazakari no Kimitachi e 2nd Season - 13 (BS11 1920x1080 MPEG2 AAC).ts" to "Hanazakari no Kimitachi e | 13 | -",
        "[ANi] 女性向遊戲世界對路人角色很不友好 第二季 - 12 [1080P][Baha][WEB-DL][AAC AVC][CHT].mp4" to "女性向遊戲世界對路人角色很不友好 | 12 | -",
        "[AI-Raws] イクシオン サーガ DT #09 (BD HEVC 1920x1080 FLAC)[B835DAA3].mkv" to "イクシオン サーガ DT | 09 | -",
        "[SomeSub] Kimi no Na 第03话「前篇」[1080p].mp4" to "Kimi no Na | 03 | -",
    )

    @Test
    fun `scene style names split on dots but keep audio channels`() = assertParses(
        "Dragon.Ball.Z.S01E19.1080p.Bluray.x264-ZER0.mkv" to "Dragon Ball Z | 19 | -",
        "One.Piece.E952-E953.1080p.mkv" to "One Piece | 952-953 | -",
        // 名字里有一个空格也仍是点号分隔的写法
        "Space.Brothers.S01EP13.3-D Ant.1080p.Remux.AVC.FLAC2.0.mkv" to "Space Brothers | 13 | -",
        // 先换下划线会让点号那一步以为名字里本来有空格
        "Great.Pretender.S01E01.Case.1_1.Los.Angeles.Connection.1080p.WEB-DL.AAC2.0.H.264-aKraa.mkv" to "Great Pretender | 01 | -",
        "Tomb.Raider.King.S01E12.1080p.LIV.WEB-DL.JPN.AAC2.0.H.264-ToonsHub.mkv" to "Tomb Raider King | 12 | -",
    )

    @Test
    fun `section markers written as words`() = assertParses(
        "[Shiniori-Raws] Puttsun Make Love OVA - 01 (AnimeFesta WEB-DL 1440x1080 x265 10bit EAC3).mkv" to "Puttsun Make Love | OVA 01 | OVA",
        "[BlueLobster] City Hunter the Movie 05 - Goodbye My Sweetheart [480p].mkv" to "City Hunter | the Movie 05 | MOVIE",
        "[Airota&Nekomoe kissaten&LoliHouse] Yuru Camp Movie [BDRip 1080p HEVC-10bit FLAC ASSx2].mkv" to "Yuru Camp | Movie | MOVIE",
        "One Piece - NCOP 19b.mkv" to "One Piece | NCOP 19b | CREDITLESS",
        "Pokemon - Opening 06 - I Want to Be a Hero - David Rolfe [ColdFusion][F04A6AA3].mp4" to "Pokemon | Opening 06 | CREDITLESS",
        "[Anime Time] Fairy Tail ED 002 Pierce.mkv" to "Fairy Tail | ED 002 | CREDITLESS",
        // BD 在这里是标记的一部分，不是片源标签
        "[Shiniori-Raws] Deji Meets Girl - BD Menu (BD 1920x1080 x265 10bit FLAC).mkv" to "Deji Meets Girl | BD Menu | MENU",
        "[Shiniori-Raws] Deji Meets Girl - PV For Film Festival (BD 1920x1080 x265 10bit FLAC).mkv" to
            "Deji Meets Girl | PV For Film Festival | PREVIEW",
        "[Shiniori-Raws] Fate Strange Fake - TV Series Making PV (BD 1280x720 x265 10bit AAC).mp4" to
            "Fate Strange Fake | TV Series Making PV | PREVIEW",
        "[Nekomoe kissaten&VCB-Studio] Heike Monogatari [Cast & Staff Interview 01][Ma10p_1080p][x265_aac].mkv" to
            "Heike Monogatari | Cast & Staff Interview 01 | BONUS",
        "[Nekomoe kissaten&VCB-Studio] Heike Monogatari [Menu01_1][Ma10p_1080p][x265_flac].mkv" to "Heike Monogatari | Menu01_1 | MENU",
        // 不认识的描述性条目不冒充正片的第 17 集
        "[BeanSub&FZSD&VCB-Studio] Jujutsu Kaisen [Juju Sanpo 17][Ma10p_1080p][x265_flac].mkv" to "Jujutsu Kaisen | Juju Sanpo 17 | OTHER",
    )

    @Test
    fun `names without a title keep the episode and leave the title to the directory`() = assertParses(
        "Ending 27.mkv" to "- | Ending 27 | CREDITLESS",
        "[Episode 31] The Aftermath of Hero Killer：Stain.mkv" to "- | 31 | -",
        "158 - Dende's Dragon.avi" to "- | 158 | -",
        "181.mp4" to "- | 181 | -",
        "episode 274.mkv" to "- | 274 | -",
    )

    @Test
    fun `numbers that are not episodes`() = assertParses(
        // 括号里的年份
        "4-Lupan III - From Russia with Love (1992).mkv" to "4-Lupan III - From Russia with Love | 4-Lupan III - From Russia with Love | -",
        "[Some-Stuffs] Pocket Monsters (2023) 077 (1080p HEVC 10-bit) v2 [E61C46C0].mkv" to "Pocket Monsters | 077 | - | v2",
        // 年份区间
        "[Ending 19] Boruto - Naruto Next Generations [2017 - 2023].mkv" to "Boruto - Naruto Next Generations | Ending 19 | CREDITLESS",
        // 只有一个非标签方括号时它是作品名
        "[Akira][BDRIP][1440x1080][H264_FLAC].mkv" to "Akira | Akira | -",
        // 原盘里的流文件名没有任何信息，如实报告未识别
        "00001.m2ts" to "未识别",
    )

    @Test
    fun `bare trailing numbers are recognized but marked low confidence`() {
        val cases = listOf(
            "Deji Meets Girl  04.en.ass" to "04",
            "[AonE]_Naruto_198_[ACBAAC0B].avi" to "198",
            "[Nakama-Fansubs]_One_Piece_377_HD_[76FF96F0].mp4" to "377",
            "DetectiveConan-0141.mkv" to "0141",
        )
        cases.forEach { (name, label) ->
            val parsed = parseMediaName(name)
            assertEquals(label, parsed.label, name)
            assertEquals(Confidence.LOW, parsed.confidence, name)
        }
        assertEquals(Confidence.HIGH, parseMediaName("[SubsPlease] Black Clover - 19 (1080p).mkv").confidence)
    }

    @Test
    fun `technical tags are normalized across spellings`() {
        fun tags(name: String) = parseMediaName(name).tags.filter { it.kind != TagKind.GROUP }.map { it.text }.toSet()
        assertEquals(setOf("10bit", "1080p", "HEVC", "FLAC"), tags("[Nekomoe kissaten&VCB-Studio] Heike Monogatari [07][Ma10p_1080p][x265_flac].mkv"))
        assertEquals(setOf("HEVC", "10bit", "1080p", "AAC", "简繁"), tags("[Sakurato] Mushoku Tensei III： Isekai Ittara Honki Dasu [13][HEVC-10bit 1080P AAC][CHS&CHT].mkv"))
        assertEquals(setOf("HEVC", "Opus", "1080p", "简", "重制"), tags("[KTXP][Baka_Test_Shoukanjuu_1][06][GB_CN][HEVC_opus][1080p][Remaster].mkv"))
        assertEquals(setOf("BS11", "1080p", "MPEG-2", "AAC"), tags("[shincaps] Youjo Senki II - 12 (BS11 1920x1080 MPEG2 AAC).ts"))
        assertEquals(setOf("WebRip", "HEVC", "AAC", "繁日"), tags("[KitaujiSub] Kimi ga Shinu made Koi wo Shitai [12][WebRip][HEVC_AAC][CHT_JP].mp4"))
    }

    @Test
    fun `group names are not mistaken for tags`() {
        assertEquals("DBD-Raws", parseMediaName("[DBD-Raws][Steins;Gate][01][1080P].mkv").group)
        // 组名里的「字幕」不能让整个方括号被当成字幕语言标签
        assertEquals("某某字幕组", parseMediaName("[某某字幕组][720P][Studio]Some Title[x264_aac].mp4").group)
        assertEquals("Some Title", parseMediaName("[某某字幕组][720P][Studio]Some Title[x264_aac].mp4").title)
    }

    @Test
    fun `subtitle language suffixes`() {
        assertEquals("简日", parseMediaName("[DBD-Raws][Steins;Gate][01][1080P][BDRip][HEVC-10bit][FLAC].scjp.ass").language)
        assertEquals("繁", parseMediaName("[DBD-Raws][Steins;Gate][25(SP)][1080P][BDRip][HEVC-10bit][FLAC].tc.ass").language)
        assertEquals("英", parseMediaName("Deji Meets Girl  04.en.ass").language)
        // 语言后缀剥掉后不能留在作品名里
        assertEquals("Steins;Gate", parseMediaName("[DBD-Raws][Steins;Gate][01][1080P].sc.ass").title)
        assertNull(parseMediaName("[SubsPlease] Black Clover - 19 (1080p).mkv").language)
    }

    @Test
    fun `work keys ignore case punctuation and parenthesized notes`() {
        assertEquals(workKeyOf("Black Clover"), workKeyOf("Black Clover (TV)"))
        assertEquals(workKeyOf("Baka_Test Shoukanjuu"), workKeyOf("baka test shoukanjuu"))
        assertEquals(false, workKeyOf("Steins;Gate") == workKeyOf("Steins;Gate 0"))
    }

    @Test
    fun `dates and prose are not episodes or title ends`() = assertParses(
        // scene release 的两位年份日期
        "site.25.08.26.some.performer.scene.xxx.mp4" to "site | site | -",
        "Site 21 02 23 performer and friend.mp4" to "Site | Site | -",
        // 整个主干只是「名字.编号」
        "sitestreets.121.mp4" to "sitestreets | 121 | -",
        // 年份后面还有正文时，年份是句子里的日期
        "ABC - Jan 2, 2013 - Name One, Name Two.wmv" to "ABC - Jan 2, 2013 - Name One, Name Two | ABC - Jan 2, 2013 - Name One, Name Two | -",
        "Show 2013 1080p.mkv" to "Show | Show | -",
    )

    @Test
    fun `generated names become a source and a time`() {
        val utc = TimeZone.UTC
        // 2020-07-28T12:00:00Z，东西十一区内都是同一天
        assertEquals("LINE 视频 2020-07-28 12:00", (generatedName("LINE_MOVIE_1595937600000", utc) as GeneratedName.Timed).label)
        // 相机名里的时间是当地时间，不随时区换算
        assertEquals("相机 2026-09-13 09:08", (generatedName("VID_20260913_090829_383", utc) as GeneratedName.Timed).label)
        assertEquals(GeneratedName.Opaque, generatedName("5_6190741636838855047_(new)", utc))
        assertEquals("2023-12-06 18:03", (generatedName("2023-12-06 18-03-01", utc) as GeneratedName.Timed).label)
        assertEquals(GeneratedName.Opaque, generatedName("cd03bb5bbf8d6d0f", utc))
        // 纯数字不当哈希，也不在合理年份内时不当时间戳
        assertNull(generatedName("20240101", utc))
        assertNull(generatedName("99999999999999", utc))
    }

    @Test
    fun `forum and ad addresses are washed but studio brackets stay`() {
        assertEquals("国风10", stripSiteNoise("www.98T.la@国风10"))
        assertEquals("国风10", stripSiteNoise("www.98T.la@www.98T.la@国风10"))
        assertEquals("2023-12-06 18-03-01", stripSiteNoise("kcf9.com-2023-12-06 18-03-01"))
        assertEquals("heydouga4017-240-34", stripSiteNoise("[thz.la]heydouga4017-240-34"))
        assertEquals("某人 [标签] 标题", stripSiteNoise("某人 [标签] www.98T.la@标题"))
        assertEquals("某个标题", stripSiteNoise("某个标题 - Pornhub.com"))
        assertEquals("(33)", stripSiteNoise("美库meiku.vip内购首发@ (33)"))
        // 出品方是内容信息，作品名以常见词结尾的不是网址，点连接的 scene 名也不是
        assertEquals("[Studio.com] Name - Title", stripSiteNoise("[Studio.com] Name - Title"))
        assertEquals("Sword.Art.Online - 01", stripSiteNoise("Sword.Art.Online - 01"))
        assertEquals("Site.19.05.17.Name.XXX", stripSiteNoise("Site.19.05.17.Name.XXX"))
    }
}
