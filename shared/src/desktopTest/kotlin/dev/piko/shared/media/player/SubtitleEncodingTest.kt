package dev.piko.shared.media.player

import dev.piko.data.auth.AppLanguage
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleEncodingTest {

    @Test
    fun gbkAndGb18030BytesTranscodeLosslesslyToUtf8() {
        val text = "1\r\n00:00:01,000 --> 00:00:04,500\r\n测试字幕：你好，世界！\r\n"
        val gbkBytes = text.toByteArray(Charset.forName("GBK"))
        val gb18030Bytes = "${text}扩展汉字：𠮷野家\r\n".toByteArray(Charset.forName("GB18030"))

        val decodedGbk = JvmSubtitleTextTranscoder.normalizeToUtf8(gbkBytes, effectiveLanguage = AppLanguage.ZH_CN)
        assertEquals(text, decodedGbk.decodeToString())

        val decodedGb18030 = JvmSubtitleTextTranscoder.normalizeToUtf8(gb18030Bytes, effectiveLanguage = AppLanguage.ZH_CN)
        assertEquals("${text}扩展汉字：𠮷野家\r\n", decodedGb18030.decodeToString())

        // 即使界面语言为英文，简体系统或默认回退也能正确解码 GBK 字幕，不再按 Latin-1 乱码
        val decodedUnderEn = JvmSubtitleTextTranscoder.normalizeToUtf8(
            gbkBytes,
            effectiveLanguage = AppLanguage.EN,
            systemLanguage = AppLanguage.EN,
        )
        assertEquals(text, decodedUnderEn.decodeToString())
    }

    @Test
    fun big5ShiftJisAndCp949TranscodeLosslesslyToUtf8() {
        val big5Text = "1\n00:00:02,000 --> 00:00:05,000\n繁體中文字幕測試，請勿出現亂碼。\n"
        val big5Bytes = big5Text.toByteArray(Charset.forName("Big5"))
        assertEquals(
            big5Text,
            JvmSubtitleTextTranscoder.normalizeToUtf8(big5Bytes, effectiveLanguage = AppLanguage.ZH_HANT).decodeToString(),
        )
        // Big5 高频汉字（中/文/日/天/世/生等 0xA4A1..0xA5F6）在 GB18030 下会误映射为日文假名；
        // 在简体中文或英文界面下也必须准确识别为 Big5，不得被 GB18030 误抢
        val big5ShortText = "中文日文幕測試"
        val big5ShortBytes = big5ShortText.toByteArray(Charset.forName("Big5"))
        assertEquals(
            big5ShortText,
            JvmSubtitleTextTranscoder.normalizeToUtf8(big5ShortBytes, effectiveLanguage = AppLanguage.ZH_CN).decodeToString(),
        )
        assertEquals(
            big5ShortText,
            JvmSubtitleTextTranscoder.normalizeToUtf8(
                big5ShortBytes,
                effectiveLanguage = AppLanguage.EN,
                systemLanguage = AppLanguage.EN,
            ).decodeToString(),
        )
        assertEquals(
            big5Text,
            JvmSubtitleTextTranscoder.normalizeToUtf8(big5Bytes, effectiveLanguage = AppLanguage.ZH_CN).decodeToString(),
        )

        val sjisText = "1\n00:00:03,000 --> 00:00:06,000\n日本語の字幕テストです。ひらがなとカタカナ。\n"
        val sjisBytes = sjisText.toByteArray(Charset.forName("Shift_JIS"))
        // 即使当前界面为简体中文，假名特征也应压倒 GB18030 准确识别 Shift_JIS
        assertEquals(
            sjisText,
            JvmSubtitleTextTranscoder.normalizeToUtf8(sjisBytes, effectiveLanguage = AppLanguage.ZH_CN).decodeToString(),
        )
        assertEquals(
            sjisText,
            JvmSubtitleTextTranscoder.normalizeToUtf8(sjisBytes, effectiveLanguage = AppLanguage.JA).decodeToString(),
        )

        val koText = "1\n00:00:04,000 --> 00:00:07,000\n한국어 자막 테스트입니다. 한글이 깨지지 않습니다.\n"
        val koBytes = koText.toByteArray(Charset.forName("x-windows-949"))
        assertEquals(
            koText,
            JvmSubtitleTextTranscoder.normalizeToUtf8(koBytes, effectiveLanguage = AppLanguage.ZH_CN).decodeToString(),
        )
        assertEquals(
            koText,
            JvmSubtitleTextTranscoder.normalizeToUtf8(koBytes, effectiveLanguage = AppLanguage.KO).decodeToString(),
        )
    }

    @Test
    fun utf16LeBeAndUtf8WithOrWithoutBomTranscodeCleanly() {
        val text = "1\n00:00:01,000 --> 00:00:03,000\n测试字幕 UTF-16 & UTF-8\n"

        // UTF-8 without BOM
        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        assertContentEquals(utf8Bytes, JvmSubtitleTextTranscoder.normalizeToUtf8(utf8Bytes))

        // UTF-8 with BOM -> strips BOM cleanly
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8Bytes
        assertContentEquals(utf8Bytes, JvmSubtitleTextTranscoder.normalizeToUtf8(utf8Bom))

        // UTF-16LE with BOM
        val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, JvmSubtitleTextTranscoder.normalizeToUtf8(utf16LeBom).decodeToString())

        // UTF-16BE with BOM
        val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, JvmSubtitleTextTranscoder.normalizeToUtf8(utf16BeBom).decodeToString())

        // BOM-less UTF-16LE (common in Windows Notepad saves)
        val utf16LeNoBom = text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, JvmSubtitleTextTranscoder.normalizeToUtf8(utf16LeNoBom).decodeToString())

        // BOM-less UTF-16BE
        val utf16BeNoBom = text.toByteArray(Charsets.UTF_16BE)
        assertEquals(text, JvmSubtitleTextTranscoder.normalizeToUtf8(utf16BeNoBom).decodeToString())
    }

    @Test
    fun windows1252TextSubtitlesAndBinarySupFilterWorkCorrectly() {
        val latinText = "1\n00:00:01,000 --> 00:00:03,000\nCafé, naïve résumé — voilà!\n"
        val cp1252Bytes = latinText.toByteArray(Charset.forName("windows-1252"))
        assertEquals(
            latinText,
            JvmSubtitleTextTranscoder.normalizeToUtf8(cp1252Bytes, effectiveLanguage = AppLanguage.EN, systemLanguage = AppLanguage.EN).decodeToString(),
        )

        assertTrue(isTextSubtitleName("episode.srt"))
        assertTrue(isTextSubtitleName("episode.ASS"))
        assertTrue(isTextSubtitleName("episode.ssa"))
        assertTrue(isTextSubtitleName("episode.vtt"))
        assertFalse(isTextSubtitleName("bluray.sup"))
    }

    @Test
    fun mpvSubtitleCodepageFollowsLocaleWithoutForcingPlusPrefix() {
        assertEquals("gb18030", mpvSubtitleCodepage(AppLanguage.ZH_CN, AppLanguage.EN))
        assertEquals("big5", mpvSubtitleCodepage(AppLanguage.ZH_HANT, AppLanguage.ZH_CN))
        assertEquals("cp932", mpvSubtitleCodepage(AppLanguage.JA, AppLanguage.ZH_CN))
        assertEquals("cp949", mpvSubtitleCodepage(AppLanguage.KO, AppLanguage.ZH_CN))
        assertEquals("big5", mpvSubtitleCodepage(AppLanguage.EN, AppLanguage.ZH_HANT))
        assertEquals("gb18030", mpvSubtitleCodepage(AppLanguage.EN, AppLanguage.EN))
        // Must NEVER use leading '+' (which forces recoding even on valid UTF-8 subtitles in mpv)
        for (lang in AppLanguage.entries) {
            assertFalse(mpvSubtitleCodepage(lang, lang).startsWith("+"))
        }
    }
}
