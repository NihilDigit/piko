package dev.piko.shared.media.player

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
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
            assertEquals("繁体中文 外挂", trackDisplayName(extCht, 5))

            PikoLocale.apply(AppLanguage.EN)
            assertEquals("Simplified Chinese", trackDisplayName(rawChs, 0))
            assertEquals("Traditional Chinese", trackDisplayName(rawCht, 1))
            assertTrue(trackDisplayName(bilingualCht, 3).contains("Traditional Chinese"))
        } finally {
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
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
