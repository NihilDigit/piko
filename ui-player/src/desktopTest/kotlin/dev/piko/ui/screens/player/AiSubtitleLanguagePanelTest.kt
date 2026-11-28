package dev.piko.ui.screens.player

import io.github.nihildigit.pikpak.AiSubtitleLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiSubtitleLanguagePanelTest {

    @Test
    fun keepsOnlyServerSupportedQuickLanguagesWhenTraditionalChineseAbsent() {
        val serverLanguages = listOf(
            AiSubtitleLanguage(code = "zh", name = "Chinese"),
            AiSubtitleLanguage(code = "yue", name = "Cantonese"),
            AiSubtitleLanguage(code = "en", name = "English"),
            AiSubtitleLanguage(code = "ja", name = "Japanese"),
            AiSubtitleLanguage(code = "ko", name = "Korean"),
            AiSubtitleLanguage(code = "fr", name = "French"),
        )

        val quick = selectQuickAiLanguages(serverLanguages)

        assertEquals(listOf("zh", "en", "ja", "ko"), quick.map { it.code })
        assertEquals(
            listOf("简体中文", "English", "日本語", "한국어"),
            quick.map(::aiLanguageNativeEndonym),
        )
    }

    @Test
    fun preservesServerTraditionalChineseAcrossUnderscoreAndHantVariants() {
        val serverLanguages = listOf(
            AiSubtitleLanguage(code = "zh_Hant", name = "Traditional Chinese"),
            AiSubtitleLanguage(code = "zh_CN", name = "Simplified Chinese"),
            AiSubtitleLanguage(code = "en", name = "English"),
            AiSubtitleLanguage(code = "ja", name = "Japanese"),
            AiSubtitleLanguage(code = "ko", name = "Korean"),
        )

        val quick = selectQuickAiLanguages(serverLanguages)

        assertEquals(listOf("zh_CN", "zh_Hant", "en", "ja", "ko"), quick.map { it.code })
        assertEquals(
            listOf("简体中文", "繁體中文", "English", "日本語", "한국어"),
            quick.map(::aiLanguageNativeEndonym),
        )
    }

    @Test
    fun displaysLanguagesUsingNativeEndonymsAndKeepsMultilingualSearch() {
        val french = AiSubtitleLanguage(code = "fr", name = "French")
        val german = AiSubtitleLanguage(code = "de", name = "German")
        val spanish = AiSubtitleLanguage(code = "es", name = "Spanish")
        val cantonese = AiSubtitleLanguage(code = "yue", name = "Cantonese")

        assertEquals("Français", aiLanguageNativeEndonym(french))
        assertEquals("Deutsch", aiLanguageNativeEndonym(german))
        assertEquals("Español", aiLanguageNativeEndonym(spanish))
        assertEquals("粵語", aiLanguageNativeEndonym(cantonese))

        assertTrue(matchesLanguageQuery(french, "Français"))
        assertTrue(matchesLanguageQuery(french, "French"))
        assertTrue(matchesLanguageQuery(french, "法语"))
        assertTrue(matchesLanguageQuery(french, "fr"))
        assertTrue(matchesLanguageQuery(cantonese, "粤语"))
        assertTrue(matchesLanguageQuery(cantonese, "粵語"))
        assertTrue(matchesLanguageQuery(cantonese, "yue"))
    }
}
