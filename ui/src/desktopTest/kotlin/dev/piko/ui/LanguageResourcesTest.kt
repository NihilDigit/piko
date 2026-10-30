package dev.piko.ui

import androidx.compose.ui.text.font.FontFamily
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import dev.piko.ui.resources.*
import dev.piko.ui.theme.pikoLocaleList
import dev.piko.ui.theme.pikoTypography
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageResourcesTest {
    @Test
    fun `official resources load all five languages including traditional chinese without falling back to simplified`() = runBlocking {
        val initial = Locale.getDefault()
        val previousSystemTag = PikoLocale.systemLanguageTag
        try {
            for ((language, expectedRename) in listOf(
                AppLanguage.ZH_CN to "重命名",
                AppLanguage.ZH_HANT to "重新命名",
                AppLanguage.KO to "이름 변경",
                AppLanguage.JA to "名前を変更",
                AppLanguage.EN to "Rename",
            )) {
                Locale.setDefault(Locale.forLanguageTag(language.tag))
                assertEquals(expectedRename, getString(Res.string.common_rename), language.name)
            }
            for (systemTag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-HK")) {
                PikoLocale.bindSystemLocale(systemTag)
                val resolved = PikoLocale.resolve(AppLanguage.SYSTEM)
                assertEquals(AppLanguage.ZH_HANT, resolved, systemTag)
                Locale.setDefault(Locale.forLanguageTag(resolved.tag))
                assertEquals("重新命名", getString(Res.string.common_rename), systemTag)
            }
            for (systemTag in listOf("zh-CN", "zh-SG", "zh-Hans", "zh-Hans-CN")) {
                PikoLocale.bindSystemLocale(systemTag)
                val resolved = PikoLocale.resolve(AppLanguage.SYSTEM)
                assertEquals(AppLanguage.ZH_CN, resolved, systemTag)
                Locale.setDefault(Locale.forLanguageTag(resolved.tag))
                assertEquals("重命名", getString(Res.string.common_rename), systemTag)
            }
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("1 item", getPluralString(Res.plurals.item_count, 1, 1))
            assertEquals("2 items", getPluralString(Res.plurals.item_count, 2, 2))
        } finally {
            PikoLocale.bindSystemLocale(previousSystemTag)
            Locale.setDefault(initial)
        }
    }

    @Test
    fun `typography binds explicit localeList per effective language and system CJK preference`() {
        assertEquals(listOf("zh-CN"), pikoLocaleList(AppLanguage.ZH_CN).map { it.toLanguageTag() })
        assertEquals(listOf("zh-TW"), pikoLocaleList(AppLanguage.ZH_HANT).map { it.toLanguageTag() })
        assertEquals(listOf("ja-JP", "zh-CN"), pikoLocaleList(AppLanguage.JA).map { it.toLanguageTag() })
        assertEquals(listOf("ko-KR", "zh-CN"), pikoLocaleList(AppLanguage.KO).map { it.toLanguageTag() })
        assertEquals(
            listOf("en-US", "zh-TW"),
            pikoLocaleList(AppLanguage.EN, systemLanguage = AppLanguage.ZH_HANT).map { it.toLanguageTag() },
        )
        assertEquals(
            listOf("en-US", "zh-CN"),
            pikoLocaleList(AppLanguage.EN, systemLanguage = AppLanguage.EN).map { it.toLanguageTag() },
        )
        val localeList = pikoLocaleList(AppLanguage.JA)
        val typography = pikoTypography(FontFamily.Default, localeList)
        assertEquals(localeList, typography.bodyMedium.localeList)
        assertEquals(localeList, typography.titleMediumEmphasized.localeList)
    }
}
