package dev.piko.shared.naming

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 成批分析的期望结果，夹具是真实种子的文件树（nyaa，公开发布）。
 */
class MediaBatchFixtureTest {

    private fun analyze(name: String) = analyzeMediaBatch(NamingFixtures.load(name))

    // region 命运石之门：主参考样本

    private val steinsGateOutline = """
        Steins;Gate
          正片: 01, 02, 03, 04, 05, 06, 07, 08, 09, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 23 Beta, 24
          SP: 25(SP)
          PV/CM: PV 01, PV 02
          NCOP/NCED: Game OP1, Game OP2, Game OP3, Game OP4, Game OP5, Game OP6, Game OP7, Game OP8, NCED, NCOP
          特典: Tokuten
          菜单: menu 01, menu 02, menu 03, menu 04, menu 05
        Steins;Gate Fuka Ryouiki no Deja vu
          剧场版: Steins;Gate Fuka Ryouiki no Deja vu
          PV/CM: PV 01, PV 02
          特典: Tokuten 01, Tokuten 02, Tokuten 03
          菜单: menu
        Steins;Gate Soumei Eichi no Cognitive Computing
          正片: 01, 02, 03, 04
    """.trimIndent()

    @Test
    fun `steins gate groups into works sections and episodes`() {
        assertEquals(steinsGateOutline, analyze("steins-gate-dbd").outline())
    }

    @Test
    fun `steins gate without directories falls back to file name markers and ends up the same`() {
        // 网盘里拍平的旧数据没有 PV/、menu/、特典映像/ 这些目录，分区只能靠文件名里的 [PV]、[menu]、[Tokuten]
        val flat = NamingFixtures.flatten(NamingFixtures.load("steins-gate-dbd"))
        assertEquals(steinsGateOutline, analyzeMediaBatch(flat).outline())
    }

    @Test
    fun `steins gate subtitles hang under their videos with language`() {
        val batch = analyze("steins-gate-dbd")
        fun languages(entry: MediaEntry) = entry.primary.attachments.map { it.language }.toSet()
        val main = batch.work("Steins;Gate")
        main.section(Section.MAIN).entries.forEach { assertEquals(setOf("简日", "繁日"), languages(it), it.label) }
        assertEquals(setOf("简", "繁"), languages(main.entry("25(SP)")))
        assertEquals(setOf("简", "繁"), languages(batch.work("Steins;Gate Soumei Eichi no Cognitive Computing").entry("03")))
        assertEquals(setOf("简日", "繁日"), languages(batch.work("Steins;Gate Fuka Ryouiki no Deja vu").section(Section.MOVIE).entries.single()))
        // 字幕是附件，不单独成行
        assertTrue(batch.works.flatMap { it.sections }.flatMap { it.entries }.none { it.primary.name.fileKind == FileKind.SUBTITLE })
    }

    @Test
    fun `steins gate common tags appear once at work level`() {
        val main = analyze("steins-gate-dbd").work("Steins;Gate")
        assertEquals(setOf("DBD-Raws", "1080p", "BDRip", "HEVC", "10bit", "FLAC"), main.commonTags.map { it.text }.toSet())
        assertTrue(main.sections.flatMap { it.entries }.flatMap { it.files }.all { it.tags.isEmpty() })
    }

    @Test
    fun `steins gate fonts are secondary and main episodes are selected by default`() {
        val files = NamingFixtures.load("steins-gate-dbd")
        val batch = analyzeMediaBatch(files)
        assertEquals(listOf("Fonts.rar" to SecondaryReason.FONTS), batch.secondary.map { files[it.index].path.substringAfterLast('/') to it.reason })

        val selected = batch.defaultSelection().map { files[it].path.substringAfterLast('/') }
        assertTrue(selected.any { "[Steins;Gate][01]" in it && it.endsWith(".scjp.ass") }, "正片的字幕随正片勾选")
        assertTrue(selected.any { "[Soumei Eichi no Cognitive Computing][04]" in it })
        assertTrue(selected.none { "[PV]" in it || "[menu]" in it || "[NCOP]" in it || "[Tokuten]" in it })
    }

    @Test
    fun `steins gate playlist continues within the section`() {
        val files = NamingFixtures.load("steins-gate-dbd")
        val batch = analyzeMediaBatch(files)
        val episode23 = files.indexOfFirst { it.path.endsWith("[Steins;Gate][23][1080P][BDRip][HEVC-10bit][FLAC].mkv") }
        val location = batch.locate(episode23)!!
        val labels = location.section.entries.map { it.label }
        assertEquals(listOf("23", "23 Beta", "24"), labels.subList(labels.indexOf("23"), labels.indexOf("23") + 3))
        assertEquals(Section.MAIN, location.section.section)
    }

    @Test
    fun `a pack of subtitles alone becomes the content`() {
        val batch = analyze("steins-gate-subtitles-only")
        val main = batch.work("Steins;Gate")
        assertEquals(25, main.section(Section.MAIN).entries.size)
        assertEquals(listOf("25(SP)"), main.section(Section.SPECIAL).entries.map { it.label })
        assertTrue(batch.roles.all { it == FileRole.CONTENT })
        assertEquals(2, main.entry("01").files.size, "简日与繁日两份字幕是同一集的两个文件")
    }

    // endregion

    @Test
    fun `vcb collection sorts sps into sections and leaves scans fonts and logs aside`() {
        val files = NamingFixtures.load("heike-vcb")
        val batch = analyzeMediaBatch(files)
        val heike = batch.work("Heike Monogatari")
        assertEquals(
            """
            Heike Monogatari
              正片: 01, 02, 03, 04, 05, 06, 07, 08, 09, 10, 11
              PV/CM: PV01, PV02
              NCOP/NCED: NCED, NCOP
              特典: Cast & Staff Interview 01, Cast & Staff Interview 02, Cast & Staff Interview 03, Cast & Staff Interview 04, Cast & Staff Interview 05, Cast & Staff Interview 06, Cast & Staff Interview 07, TV Special Program Before Broadcasting
              菜单: Menu01_1, Menu01_2, Menu02_1, Menu02_2, Menu03
            """.trimIndent(),
            batch.outline().lines().filterNot { it.trimStart().startsWith("其他") }.joinToString("\n"),
        )
        // CDs/ 下的音轨是「其他」，按专辑依次排列，同为「01.」的曲目不会并成一条
        val tracks = heike.section(Section.OTHER).entries
        assertEquals(files.count { "/CDs/" in it.path && it.path.endsWith(".flac") }, tracks.size)
        val albums = tracks.map { files[it.primary.index].path.substringBeforeLast('/') }
        assertEquals(albums.distinct().size, albums.zipWithNext().count { (a, b) -> a != b } + 1, "同一张专辑的曲目连续排列")

        val reasons = batch.secondary.groupBy({ it.reason }, { files[it.index].path })
        assertTrue(reasons.getValue(SecondaryReason.SCANS).all { "/Scans/" in it })
        assertEquals(files.count { "/Scans/" in it.path }, reasons.getValue(SecondaryReason.SCANS).size)
        assertEquals(1, reasons.getValue(SecondaryReason.FONTS).size)
        assertTrue(reasons.getValue(SecondaryReason.INFO).any { it.endsWith(".log") })
        // 正片共有的标签在作品一级，只有 01、02、10、11 多出的 AAC 音轨逐行显示
        assertEquals(listOf("AAC"), heike.entry("01").primary.tags.map { it.text })
        assertEquals(emptyList(), heike.entry("03").primary.tags.map { it.text })
    }

    @Test
    fun `images next to videos are extras while a scans folder stays scans`() {
        val files = NamingFixtures.load("sanda-vcb")
        val batch = analyzeMediaBatch(files)
        val reasons = batch.secondary.associate { files[it.index].path.substringAfterLast('/') to it.reason }
        assertEquals(SecondaryReason.EXTRA, reasons["[VCB-Studio] SANDA [Menu01].png"])
        assertEquals(SecondaryReason.SCANS, reasons["Booklet_01.webp"])
        assertEquals(listOf("PV01", "PV02"), batch.work("SANDA").section(Section.PREVIEW).entries.map { it.label })
    }

    @Test
    fun `bdmv discs become one entry each with the largest stream as primary`() {
        val files = NamingFixtures.load("fma-bdmv")
        val batch = analyzeMediaBatch(files)
        val work = batch.works.single()
        assertEquals("FMA BROTHERHOOD", work.title)
        val discs = work.section(Section.MAIN).entries
        assertEquals(listOf("Disc 1", "Disc 10"), discs.map { it.label })
        discs.forEach { disc ->
            val root = files[disc.primary.index].path.substringBefore("/BDMV/")
            val members = files.indices.filter { files[it].path.startsWith("$root/") }
            val largestStream = members.filter { "/STREAM/" in files[it].path }.maxBy { files[it].size }
            assertEquals(largestStream, disc.primary.index)
            assertEquals((members - largestStream).toSet(), disc.primary.attachments.map { it.index }.toSet())
        }
        assertTrue(batch.secondary.isEmpty(), "原盘结构文件属于盘，不是次要文件")
    }

    @Test
    fun `extras without a title in the name join the series from the directory`() {
        val batch = analyze("naruto-shippuden-jysze")
        assertEquals(
            """
            Naruto Shippuden
              正片: 001, 002, 003, 004, 005, 006
              NCOP/NCED: Ending 01, Ending 02, Ending 03, Opening 01, Opening 02, Opening 03
            """.trimIndent(),
            batch.outline(),
        )
        // 全部是 v2 时版本不进行标题
    }

    @Test
    fun `extras named after the series are adopted and subtitles in another folder pair by episode`() {
        val files = NamingFixtures.load("deji-meets-girl-shiniori")
        val batch = analyzeMediaBatch(files)
        assertEquals(
            """
            Deji Meets Girl
              正片: 01, 02, 03, 04, 05, 06, 07, 08, 09, 10, 11, 12
              PV/CM: PV 01, PV 02, PV 03, PV 04, PV For Film Festival
              特典: Creator Challenge Winners Music Part, Cast Talk, Yasuno Kiyono & Kobayashi Tatsuyuki Okinawa Video
              菜单: BD Menu
            """.trimIndent(),
            batch.outline().lines().filterNot { it.trimStart().startsWith("其他") }.joinToString("\n"),
        )
        // 「English Subtitles/Deji Meets Girl  04.en.ass」与视频不同名，只能按集号配
        batch.work("Deji Meets Girl").section(Section.MAIN).entries.forEach { entry ->
            val subtitle = entry.primary.attachments.single()
            assertEquals("英", subtitle.language)
            assertTrue(files[subtitle.index].path.endsWith(" ${entry.label}.en.ass"), entry.label)
        }
    }

    @Test
    fun `dbd arc extras and recaps join the main work`() {
        val batch = analyze("one-piece-wano-dbd")
        val work = batch.works.single()
        assertEquals("One Piece", work.title)
        assertEquals(
            listOf(Section.MAIN, Section.SPECIAL, Section.PREVIEW, Section.BONUS, Section.MENU),
            work.sections.map { it.section },
        )
        assertEquals("Soushuuhen 02", work.section(Section.SPECIAL).entries.first().label)
        assertEquals("Images 29", work.section(Section.BONUS).entries.first().label)
    }

    @Test
    fun `absolute numbers win over season numbers and season zero becomes specials`() {
        assertEquals(
            """
            Inuyasha
              正片: 001, 002, 003, 111, 112, 113
              OVA/OAD: 05
              剧场版: Movie 01, Movie 02, Movie 03, Movie 04
              特典: 06
            """.trimIndent(),
            analyze("inuyasha-ac").outline(),
        )
    }

    @Test
    fun `a generic other folder is refined by the ncop marker`() {
        assertEquals(
            """
            One Piece
              正片: 795, 796, 797, 798, 799, 800
              NCOP/NCED: NCOP 19b, NCOP 20a, NCOP 20b, NCOP 20c, NCOP 20d, NCOP 20e
            """.trimIndent(),
            analyze("one-piece-galactic").outline(),
        )
    }

    @Test
    fun `scene releases keep nfo aside and pair srt from a sibling folder`() {
        val files = NamingFixtures.load("pokemon-srs")
        val batch = analyzeMediaBatch(files)
        val entries = batch.work("Pokemon").section(Section.MAIN).entries
        assertEquals(listOf("01", "02", "03", "04"), entries.map { it.label })
        entries.forEach { assertTrue(files[it.primary.attachments.single().index].path.contains("/SRT Subtitles/")) }
        assertTrue(batch.secondary.all { it.reason == SecondaryReason.INFO && files[it.index].path.endsWith(".nfo") })
    }

    @Test
    fun `flattened drive folder of single episodes from several groups`() {
        val files = NamingFixtures.load("flat-drive-mixed-groups")
        val batch = analyzeMediaBatch(files)
        assertEquals(
            listOf(
                "Baka Test Shoukanjuu 1", "CANDY CARIES 蛀在糖糖裡", "Kimi ga Shinu made Koi wo Shitai",
                "Mushoku Tensei III： Isekai Ittara Honki Dasu", "Otome Kaijuu Carameliser", "Seihantai na Kimi to Boku", "Youjo Senki II",
            ),
            batch.works.map { it.title },
        )
        // 两个组的同一集是一个条目的两个文件，逐行标签区分组名与画质
        val episode = batch.work("Kimi ga Shinu made Koi wo Shitai").entry("12")
        assertEquals(2, episode.files.size)
        val groups = episode.files.map { file -> file.tags.single { it.kind == TagKind.GROUP }.text }.toSet()
        assertEquals(setOf("KitaujiSub", "Nekomoe kissaten"), groups)
        assertEquals(listOf("05", "06"), batch.work("Baka Test Shoukanjuu 1").section(Section.MAIN).entries.map { it.label })
    }

    @Test
    fun `labels of an english batch carry no brackets and tags shared by all stay at work level`() {
        val work = analyze("black-clover-subsplease").works.single()
        assertEquals((1..12).map { it.toString().padStart(2, '0') }, work.section(Section.MAIN).entries.map { it.label })
        assertEquals(setOf("SubsPlease", "1080p"), work.commonTags.map { it.text }.toSet())
    }
}
