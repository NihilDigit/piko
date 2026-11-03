package dev.piko.shared.i18n

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.data.DriveLibrary
import dev.piko.shared.data.PikoSortField
import dev.piko.shared.notice.PikoErrorMapper
import dev.piko.shared.presentation.localizedName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals

class PikoLocaleTest {
    @Test
    fun `language order and storage compatibility match specification`() {
        assertEquals(
            listOf(
                AppLanguage.SYSTEM,
                AppLanguage.ZH_CN,
                AppLanguage.ZH_HANT,
                AppLanguage.KO,
                AppLanguage.JA,
                AppLanguage.EN,
            ),
            AppLanguage.entries,
        )
        AppLanguage.entries.forEach {
            assertEquals(it, AppLanguage.fromStorage(it.tag.lowercase()))
            assertEquals(it, AppLanguage.fromStorage(it.name.lowercase()))
        }
        for (raw in listOf("ZH_CN", "zh-CN", "zh_CN", "zh-Hans", "zh_Hans", "zh-Hans-CN", "zh", "zh-SG")) {
            assertEquals(AppLanguage.ZH_CN, AppLanguage.fromStorage(raw), raw)
        }
        for (raw in listOf("ZH_HANT", "ZH_TW", "zh-Hant", "zh_Hant", "zh-TW", "zh_TW", "zh-HK", "zh_HK", "zh-MO", "zh-Hant-HK", "zh-Hant-TW")) {
            assertEquals(AppLanguage.ZH_HANT, AppLanguage.fromStorage(raw), raw)
        }
        for (raw in listOf("KO", "ko", "ko-KR", "ko_KR")) {
            assertEquals(AppLanguage.KO, AppLanguage.fromStorage(raw), raw)
        }
        for (raw in listOf("JA", "ja", "ja-JP", "ja_JP")) {
            assertEquals(AppLanguage.JA, AppLanguage.fromStorage(raw), raw)
        }
        for (raw in listOf("EN", "en", "en-US", "en_GB")) {
            assertEquals(AppLanguage.EN, AppLanguage.fromStorage(raw), raw)
        }
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorage(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorage(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorage("unknown"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorage("fr-FR"))
    }

    @Test
    fun `bindSystemLocale distinguishes simplified and traditional chinese tags`() {
        val previousSystemTag = PikoLocale.systemLanguageTag
        try {
            PikoLocale.apply(AppLanguage.SYSTEM)

            for (hantTag in listOf("zh-TW", "zh_TW", "zh-HK", "zh_HK", "zh-MO", "zh_MO", "zh-Hant", "zh-Hant-TW", "zh-Hant-HK", "zh-Hant-MO", "zh_Hant_HK")) {
                PikoLocale.bindSystemLocale(hantTag)
                assertEquals(AppLanguage.ZH_HANT, PikoLocale.effectiveLanguage, hantTag)
                assertEquals(AppLanguage.ZH_HANT, PikoLocale.resolve(AppLanguage.SYSTEM), hantTag)
            }

            for (hansTag in listOf("zh", "zh-CN", "zh_CN", "zh-SG", "zh_SG", "zh-Hans", "zh-Hans-CN", "zh-Hans-SG", "zh-Hans-HK")) {
                PikoLocale.bindSystemLocale(hansTag)
                assertEquals(AppLanguage.ZH_CN, PikoLocale.effectiveLanguage, hansTag)
                assertEquals(AppLanguage.ZH_CN, PikoLocale.resolve(AppLanguage.SYSTEM), hansTag)
            }

            PikoLocale.bindSystemLocale("ja-JP")
            assertEquals(AppLanguage.JA, PikoLocale.effectiveLanguage)

            PikoLocale.bindSystemLocale("ko-KR")
            assertEquals(AppLanguage.KO, PikoLocale.effectiveLanguage)
            assertEquals(AppLanguage.KO, PikoLocale.resolve(AppLanguage.SYSTEM))

            PikoLocale.bindSystemLocale("de-DE")
            assertEquals(AppLanguage.EN, PikoLocale.effectiveLanguage)
        } finally {
            PikoLocale.bindSystemLocale(previousSystemTag)
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
    }

    @Test
    fun `synchronous labels and errors follow runtime language changes`() {
        try {
            PikoLocale.apply(AppLanguage.ZH_CN)
            assertEquals("星标", DriveLibrary.STARRED.title)
            assertEquals("名称", PikoSortField.NAME.label)
            assertEquals("英语", dev.piko.shared.media.player.languageName("eng"))
            for (language in listOf(AppLanguage.ZH_HANT, AppLanguage.EN, AppLanguage.JA, AppLanguage.KO)) {
                PikoLocale.apply(language)
                assertEquals(language, PikoLocale.effectiveLanguageState.value)
                assertNotEquals("星标", DriveLibrary.STARRED.title)
                assertNotEquals("名称", PikoSortField.NAME.label)
                assertNotEquals("英语", dev.piko.shared.media.player.languageName("eng"))
                for (code in listOf("NEED_MORE_QUOTA", "INVALID_FILE_FORMAT", "file_duplicated_name", "file_restore_own", "file_in_recycle_bin", "file_name_too_long", "space_not_enough")) {
                    assertNotNull(PikoErrorMapper.reason(code))
                }
            }
        } finally {
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
    }

    @Test
    fun `mapped failures preserve chinese messages and hide raw server codes`() {
        val duplicate = io.github.nihildigit.pikpak.PikPakException(1, "file_duplicated_name")
        try {
            PikoLocale.apply(AppLanguage.ZH_CN)
            assertEquals("移动失败：目标位置已有同名文件", PikoErrorMapper.failure("移动", duplicate))
            assertEquals("移动失败：网络连接中断，请重试", PikoErrorMapper.failure("移动", kotlinx.io.IOException("private endpoint")))

            PikoLocale.apply(AppLanguage.ZH_HANT)
            assertEquals("我的雲端硬碟", dev.piko.shared.data.PikoPathBreadcrumb("", "我的网盘").localizedName)
            assertEquals("移動失敗：目標位置已有同名檔案", PikoErrorMapper.failure("移動", duplicate))
            assertEquals("移動失敗：網路連線中斷，請重試", PikoErrorMapper.failure("移動", kotlinx.io.IOException("private endpoint")))

            PikoLocale.apply(AppLanguage.EN)
            assertEquals("My drive", dev.piko.shared.data.PikoPathBreadcrumb("", "我的网盘").localizedName)
            assertEquals("My drive", dev.piko.shared.data.PikoPathBreadcrumb("", "我的雲端硬碟").localizedName)
            assertEquals("Starred", dev.piko.shared.data.PikoPathBreadcrumb(DriveLibrary.STARRED.id, "星标").localizedName)
            assertEquals("我的文件夹", dev.piko.shared.data.PikoPathBreadcrumb("folder", "我的文件夹").localizedName)
            assertEquals("Confirmed saved: 1 file; 2 files not saved", dev.piko.shared.data.InstantSaveReceipt(
                "account", "target", confirmed = mapOf("one" to "id"), failed = setOf("two", "three"),
            ).message)
            assertEquals("Move failed: A file with the same name already exists at the destination", PikoErrorMapper.failure("Move", duplicate))
            assertEquals("Unrecognized archive format; the file may be corrupted or a multi-volume archive", PikoErrorMapper.reason("INVALID_FILE_FORMAT"))
        } finally {
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
    }
}
