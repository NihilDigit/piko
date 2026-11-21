package dev.piko.shared.media.player

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleVariantTest {

    @Test
    fun `detects embedded CHS and CHT tracks when container language is generic chi`() {
        val chsCases = listOf(
            MediaTrack("1", title = "CHS", language = "chi"),
            MediaTrack("2", title = "SC", language = "zho"),
            MediaTrack("3", title = "GB", language = "chi"),
            MediaTrack("4", title = "简体中文", language = "chi"),
            MediaTrack("5", title = "簡體", language = "chi"),
            MediaTrack("6", title = "简日双语", language = "chi"),
            MediaTrack("7", title = "Simplified Chinese", language = "chi"),
            MediaTrack("8", title = null, language = "zh-Hans"),
            MediaTrack("9", title = null, language = "zh-CN"),
            MediaTrack("10", title = null, language = "zh-SG"),
        )
        chsCases.forEach { track ->
            assertEquals(ChineseSubtitleVariant.Simplified, track.chineseVariant, "Expected Simplified for $track")
        }

        val chtCases = listOf(
            MediaTrack("11", title = "CHT", language = "chi"),
            MediaTrack("12", title = "TC", language = "zho"),
            MediaTrack("13", title = "Big5", language = "chi"),
            MediaTrack("14", title = "繁体中文", language = "chi"),
            MediaTrack("15", title = "繁體", language = "chi"),
            MediaTrack("16", title = "正體中文", language = "chi"),
            MediaTrack("17", title = "正体", language = "chi"),
            MediaTrack("18", title = "繁日双语", language = "chi"),
            MediaTrack("19", title = "Traditional Chinese", language = "chi"),
            MediaTrack("20", title = null, language = "zh-Hant"),
            MediaTrack("21", title = null, language = "zh-TW"),
            MediaTrack("22", title = null, language = "zh-HK"),
        )
        chtCases.forEach { track ->
            assertEquals(ChineseSubtitleVariant.Traditional, track.chineseVariant, "Expected Traditional for $track")
        }

        val genericCases = listOf(
            MediaTrack("30", title = null, language = "chi"),
            MediaTrack("31", title = null, language = "zho"),
            MediaTrack("32", title = "中文字幕", language = "zh"),
            MediaTrack("33", title = "简繁双语", language = "chi"),
            MediaTrack("34", title = "CHS&CHT", language = "chi"),
        )
        genericCases.forEach { track ->
            assertEquals(ChineseSubtitleVariant.Generic, track.chineseVariant, "Expected Generic for $track")
        }
    }

    @Test
    fun `detects external subtitle variant from externalFilename without false positives`() {
        val extChs = MediaTrack("1", title = "外挂字幕", language = null, isExternal = true, externalFilename = "/tmp/Show.01.zh-Hans.ass")
        val extCht = MediaTrack("2", title = "外挂字幕", language = null, isExternal = true, externalFilename = "C:\\subs\\Show.01.TC.srt")
        val extBig5 = MediaTrack("3", title = null, language = null, isExternal = true, externalFilename = "Movie.Big5.ass")
        val falsePositiveSize = MediaTrack("4", title = "English 2GB Rip", language = "eng", isExternal = true, externalFilename = "Screen.Watch.2GB.en.srt")

        assertEquals(ChineseSubtitleVariant.Simplified, extChs.chineseVariant)
        assertEquals(ChineseSubtitleVariant.Traditional, extCht.chineseVariant)
        assertEquals(ChineseSubtitleVariant.Traditional, extBig5.chineseVariant)
        assertNull(falsePositiveSize.chineseVariant)
    }

    @Test
    fun `trackDisplayName distinguishes CHS and CHT clearly without duplicating text`() {
        try {
            PikoLocale.apply(AppLanguage.ZH_CN)

            val rawChs = MediaTrack("1", title = "CHS", language = "chi")
            val rawCht = MediaTrack("2", title = "CHT", language = "chi")
            val bilingualChs = MediaTrack("3", title = "简日双语", language = "chi")
            val bilingualCht = MediaTrack("4", title = "繁日双语", language = "chi")
            val extraTitleCht = MediaTrack("5", title = "特效字幕", language = "zh-Hant")
            val extCht = MediaTrack("6", title = "外挂字幕", language = null, isExternal = true, externalFilename = "Ep01.cht.ass")

            assertEquals("简体中文", trackDisplayName(rawChs, 0))
            assertEquals("繁体中文", trackDisplayName(rawCht, 1))
            assertEquals("简日双语", trackDisplayName(bilingualChs, 2))
            assertEquals("繁日双语", trackDisplayName(bilingualCht, 3))
            assertEquals("特效字幕（繁体中文）", trackDisplayName(extraTitleCht, 4))
            assertEquals("繁体中文", trackDisplayName(extCht, 5))

            PikoLocale.apply(AppLanguage.EN)
            assertEquals("Simplified Chinese", trackDisplayName(rawChs, 0))
            assertEquals("Traditional Chinese", trackDisplayName(rawCht, 1))
            assertEquals("Traditional Chinese", trackDisplayName(extCht, 5))
            assertTrue(trackDisplayName(bilingualCht, 3).contains("Traditional Chinese"))
        } finally {
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
    }

    @Test
    fun `external subtitle naming avoids duplicating external label and puts language first`() {
        try {
            PikoLocale.apply(AppLanguage.ZH_CN)

            // Regression: bare "外挂字幕" + language=null must NEVER produce "外挂字幕 外挂"
            val barePlaceholder = MediaTrack("1", title = "外挂字幕", language = null, isExternal = true, externalFilename = null)
            assertEquals("外挂字幕", trackDisplayName(barePlaceholder, 0))

            // Proxy URL filename fallback when language is truly unknown
            val proxyUntagged = MediaTrack(
                id = "2",
                title = "外挂字幕",
                language = null,
                isExternal = true,
                externalFilename = "http://127.0.0.1:54321/tok/The.Fifth.Republic.E01.MBC.SDTVRip.XviD.srt",
            )
            assertEquals("The.Fifth.Republic.E01.MBC.SDTVRip.XviD.srt", trackDisplayName(proxyUntagged, 1))

            // When language is sniffed (e.g. chs), language is shown cleanly without "外挂" clutter
            val fifthRepublicSniffed = MediaTrack(
                id = "3",
                title = null,
                language = "chs",
                isExternal = true,
                externalFilename = "http://127.0.0.1:54321/tok/The.Fifth.Republic.E01.MBC.SDTVRip.XviD.srt",
            )
            assertEquals("简体中文", trackDisplayName(fifthRepublicSniffed, 2))

            // Manual subtitle with raw filename title + sniffed language puts language FIRST
            val manualSniffed = MediaTrack(
                id = "4",
                title = "The.Fifth.Republic.E01.MBC.SDTVRip.XviD.srt",
                language = "chs",
                isExternal = true,
            )
            assertEquals("简体中文（The.Fifth.Republic.E01.MBC.SDTVRip.XviD.srt）", trackDisplayName(manualSniffed, 3))

            // externalSubtitleTitleFor produces empty title when language is known and stem matches video
            val ref = SubtitleRef(fileId = "s1", name = "The.Fifth.Republic.E01.MBC.SDTVRip.XviD.srt", language = null)
            assertEquals("", externalSubtitleTitleFor(ref, "The.Fifth.Republic.E01.MBC.SDTVRip.XviD.avi", detectedLang = "chs"))
            assertEquals("SRT", externalSubtitleTitleFor(ref, "The.Fifth.Republic.E01.MBC.SDTVRip.XviD.avi", detectedLang = null))

            // trackDisplayNames only appends discriminator when multiple tracks collide
            val single = listOf(fifthRepublicSniffed)
            assertEquals(listOf("简体中文"), trackDisplayNames(single))

            val embeddedAndExternalSameLang = listOf(
                MediaTrack("1", title = null, language = "chs", isExternal = false, codec = "ass"),
                MediaTrack("2", title = null, language = "chs", isExternal = true, externalFilename = "ep01.srt"),
            )
            assertEquals(listOf("简体中文", "简体中文（外挂）"), trackDisplayNames(embeddedAndExternalSameLang))

            val twoExternalFormats = listOf(
                MediaTrack("1", title = null, language = "chs", isExternal = true, externalFilename = "ep01.ass"),
                MediaTrack("2", title = null, language = "chs", isExternal = true, externalFilename = "ep01.srt"),
            )
            assertEquals(listOf("简体中文（ASS）", "简体中文（SRT）"), trackDisplayNames(twoExternalFormats))
        } finally {
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
    }

    @Test
    fun `readMpvTracks detects PGS and VobSub image subtitles accurately`() {
        val props = mapOf(
            "track-list/count" to "4",
            "track-list/0/id" to "1",
            "track-list/0/type" to "audio",
            "track-list/0/codec" to "flac",
            "track-list/0/lang" to "jpn",
            "track-list/0/selected" to "yes",
            "track-list/1/id" to "1",
            "track-list/1/type" to "sub",
            "track-list/1/codec" to "hdmv_pgs_subtitle",
            "track-list/1/lang" to "jpn",
            "track-list/1/image" to "no",
            "track-list/1/selected" to "yes",
            "track-list/2/id" to "2",
            "track-list/2/type" to "sub",
            "track-list/2/codec" to "dvd_subtitle",
            "track-list/2/lang" to "chi",
            "track-list/2/image" to "yes",
            "track-list/2/selected" to "no",
            "track-list/3/id" to "3",
            "track-list/3/type" to "sub",
            "track-list/3/codec" to "subrip",
            "track-list/3/lang" to "eng",
            "track-list/3/selected" to "no",
        )
        val snapshot = readMpvTracks { props[it] }
        assertEquals(1, snapshot.audio.size)
        assertFalse(snapshot.audio[0].isImage)
        assertEquals("flac", snapshot.audio[0].codec)

        assertEquals(3, snapshot.subtitles.size)
        assertTrue(snapshot.subtitles[0].isImage)
        assertEquals("hdmv_pgs_subtitle", snapshot.subtitles[0].codec)
        assertEquals("PGS", imageSubtitleFormatLabel(snapshot.subtitles[0].codec))

        assertTrue(snapshot.subtitles[1].isImage)
        assertEquals("VobSub", imageSubtitleFormatLabel(snapshot.subtitles[1].codec))

        assertFalse(snapshot.subtitles[2].isImage)
        assertNull(imageSubtitleFormatLabel(snapshot.subtitles[2].codec))
    }

    @Test
    fun `content-based language detector identifies CJK, Korean, English across UTF-8, GB18030, and UTF-16LE`() {
        SubtitleEncoding.install(JvmSubtitleTextTranscoder)

        val chsSrt = """
            1
            00:00:01,000 --> 00:00:04,000
            朴正熙总统在这个时候还没有做出决定。

            2
            00:00:05,000 --> 00:00:08,000
            他们说现在必须立刻召开会议，请大家注意听。
        """.trimIndent()
        assertEquals("chs", detectSubtitleLanguageFromText(chsSrt, "The.Fifth.Republic.E01.MBC.SDTVRip.XviD.srt"))
        assertEquals(
            "chs",
            detectSubtitleLanguageFromBytes(chsSrt.toByteArray(Charset.forName("GB18030")), "ep01.srt"),
        )
        val utf16LeBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + chsSrt.toByteArray(Charsets.UTF_16LE)
        assertEquals("chs", detectSubtitleLanguageFromBytes(utf16LeBytes, "ep01.srt"))

        val chtSrt = """
            1
            00:00:01,000 --> 00:00:04,000
            他們說現在必須立刻召開會議，請大家注意聽這件事情。

            2
            00:00:05,000 --> 00:00:08,000
            經過長時間的討論，總統終於做出決定。
        """.trimIndent()
        assertEquals("cht", detectSubtitleLanguageFromText(chtSrt, "ep01.srt"))
        assertEquals("cht", detectSubtitleLanguageFromBytes(chtSrt.toByteArray(Charset.forName("Big5")), "ep01.srt"))

        val jpnAss = """
            [Script Info]
            Title: Legal High Episode 01
            ScriptType: v4.00+

            [V4+ Styles]
            Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,{\fnMeiryo\fs24}これは裁判ではありません。
            Dialogue: 0,0:00:05.00,0:00:08.00,Default,,0,0,0,,本当に正義の味方なんですか？
        """.trimIndent()
        assertEquals("jpn", detectSubtitleLanguageFromText(jpnAss, "ep01.ass"))

        val korSrt = """
            1
            00:00:01,000 --> 00:00:04,000
            제5공화국 제1화 방송을 시작하겠습니다.

            2
            00:00:05,000 --> 00:00:08,000
            지금 바로 회의를 진행해야 합니다.
        """.trimIndent()
        assertEquals("kor", detectSubtitleLanguageFromText(korSrt, "ep01.srt"))
        assertEquals("kor", detectSubtitleLanguageFromBytes(korSrt.toByteArray(Charset.forName("x-windows-949")), "ep01.srt"))

        val engSrt = """
            1
            00:00:01,000 --> 00:00:04,000
            <i>What are you going to do about this?</i>

            2
            00:00:05,000 --> 00:00:08,000
            I think we should wait for the meeting to start.
        """.trimIndent()
        assertEquals("eng", detectSubtitleLanguageFromText(engSrt, "ep01.srt"))

        val emptySrt = "1\n00:00:01,000 --> 00:00:02,000\n...\n"
        assertNull(detectSubtitleLanguageFromText(emptySrt, "ep01.srt"))
    }

    @Test
    fun `auto preference switches between CHS and CHT according to PikoLocale`() {
        val tracks = listOf(
            MediaTrack("1", title = "CHT", language = "chi"),
            MediaTrack("2", title = "CHS", language = "chi"),
            MediaTrack("3", title = "English", language = "eng"),
        )
        val extSubs = listOf(
            ExternalSubtitle("http://127.0.0.1/1", title = "繁", language = "cht"),
            ExternalSubtitle("http://127.0.0.1/2", title = "简", language = "chs"),
        )
        try {
            PikoLocale.apply(AppLanguage.ZH_CN)
            assertEquals(ChineseSubtitleVariant.Simplified, preferredChineseSubtitleVariant())
            assertTrue(mpvSubtitleLanguages().startsWith("zh-CN,zh-SG,zh-Hans"))
            assertEquals("2", selectPreferredSubtitleTrack(tracks)?.id)
            val cmdsChs = mpvSubtitleAddCommands(extSubs, hasSelectedSubtitle = false)
            assertEquals("auto", cmdsChs[0][2])
            assertEquals("select", cmdsChs[1][2])

            PikoLocale.apply(AppLanguage.ZH_HANT)
            assertEquals(ChineseSubtitleVariant.Traditional, preferredChineseSubtitleVariant())
            assertTrue(mpvSubtitleLanguages().startsWith("zh-TW,zh-HK,zh-MO,zh-Hant"))
            assertEquals("1", selectPreferredSubtitleTrack(tracks)?.id)
            val cmdsCht = mpvSubtitleAddCommands(extSubs, hasSelectedSubtitle = false)
            assertEquals("select", cmdsCht[0][2])
            assertEquals("auto", cmdsCht[1][2])
        } finally {
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
    }

    @Test
    fun `readMpvTracks captures external-filename and matchTrack preserves CHT vs CHS across episodes`() {
        val props = mapOf(
            "track-list/count" to "2",
            "track-list/0/id" to "1",
            "track-list/0/type" to "sub",
            "track-list/0/lang" to "chi",
            "track-list/0/title" to "简体",
            "track-list/0/external" to "no",
            "track-list/0/selected" to "no",
            "track-list/1/id" to "2",
            "track-list/1/type" to "sub",
            "track-list/1/lang" to "chi",
            "track-list/1/title" to "",
            "track-list/1/external" to "yes",
            "track-list/1/external-filename" to "/cache/Show.02.zh-Hant.ass",
            "track-list/1/selected" to "yes",
        )
        val snapshot = readMpvTracks { props[it] }
        assertEquals(2, snapshot.subtitles.size)
        assertFalse(snapshot.subtitles[0].isExternal)
        assertNull(snapshot.subtitles[0].externalFilename)
        assertTrue(snapshot.subtitles[1].isExternal)
        assertEquals("/cache/Show.02.zh-Hant.ass", snapshot.subtitles[1].externalFilename)
        assertEquals(ChineseSubtitleVariant.Traditional, snapshot.subtitles[1].chineseVariant)

        // 上一集选了「繁日双语」(lang=chi)，下一集有「简体」与「繁體中文」(都为 lang=chi)，须匹配到繁体而非第一条 chi
        val prevPreferred = MediaTrack("9", title = "繁日双语", language = "chi")
        val nextTracks = listOf(
            MediaTrack("1", title = "简体中文", language = "chi"),
            MediaTrack("2", title = "繁體中文", language = "chi"),
        )
        assertEquals("2", matchTrack(nextTracks, prevPreferred)?.id)
    }

    @Test
    fun `inferSubtitleLanguageCode detects tags from filename when parser label is absent`() {
        assertEquals("chs", inferSubtitleLanguageCode(null, "Movie.2026.zh-Hans.srt"))
        assertEquals("cht", inferSubtitleLanguageCode(null, "Movie.2026.zh-Hant.ass"))
        assertEquals("cht", inferSubtitleLanguageCode(null, "Movie.2026.繁體.ass"))
        assertEquals("chs", inferSubtitleLanguageCode("简日", "Show.01.scjp.ass"))
        assertEquals("cht", inferSubtitleLanguageCode("繁日", "Show.01.tcjp.ass"))
        assertEquals("eng", inferSubtitleLanguageCode(null, "Movie.en.srt"))
    }
}
