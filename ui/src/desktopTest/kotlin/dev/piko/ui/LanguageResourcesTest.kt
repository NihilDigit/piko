package dev.piko.ui

import dev.piko.data.auth.AppLanguage
import dev.piko.ui.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class LanguageResourcesTest {
    @Test
    fun `official resources load all four languages and english plural forms`() = runBlocking {
        val initial = Locale.getDefault()
        try {
            for ((language, cancel) in listOf(
                AppLanguage.ZH_CN to "取消", AppLanguage.EN to "Cancel",
                AppLanguage.JA to "キャンセル", AppLanguage.KO to "취소",
            )) {
                Locale.setDefault(Locale.forLanguageTag(language.tag))
                assertEquals(cancel, getString(Res.string.common_cancel))
            }
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("1 item", getPluralString(Res.plurals.item_count, 1, 1))
            assertEquals("2 items", getPluralString(Res.plurals.item_count, 2, 2))
        } finally {
            Locale.setDefault(initial)
        }
    }
}
