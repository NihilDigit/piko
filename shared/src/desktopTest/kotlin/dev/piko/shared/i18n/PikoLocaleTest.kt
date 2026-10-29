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
    fun `preferences accept names and tags and default unknown values to system`() {
        AppLanguage.entries.forEach {
            assertEquals(it, AppLanguage.fromStorage(it.tag.lowercase()))
            assertEquals(it, AppLanguage.fromStorage(it.name.lowercase()))
        }
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorage(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorage("unknown"))
    }

    @Test
    fun `bindSystemLocale refreshes effective language when preference follows system`() {
        val previousSystemTag = PikoLocale.systemLanguageTag
        try {
            PikoLocale.bindSystemLocale("ja-JP")
            PikoLocale.apply(AppLanguage.SYSTEM)
            assertEquals(AppLanguage.JA, PikoLocale.effectiveLanguage)

            PikoLocale.bindSystemLocale("ko-KR")
            assertEquals(AppLanguage.KO, PikoLocale.effectiveLanguage)
            assertEquals(AppLanguage.KO, PikoLocale.resolve(AppLanguage.SYSTEM))
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
            for (language in listOf(AppLanguage.EN, AppLanguage.JA, AppLanguage.KO)) {
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
            PikoLocale.apply(AppLanguage.EN)
            assertEquals("My drive", dev.piko.shared.data.PikoPathBreadcrumb("", "我的网盘").localizedName)
            assertEquals("Starred", dev.piko.shared.data.PikoPathBreadcrumb(DriveLibrary.STARRED.id, "星标").localizedName)
            assertEquals("我的文件夹", dev.piko.shared.data.PikoPathBreadcrumb("folder", "我的文件夹").localizedName)
            assertEquals("Confirmed saved: 1 file; 2 files not saved", dev.piko.shared.data.InstantSaveReceipt(
                "account", "target", confirmed = mapOf("one" to "id"), failed = setOf("two", "three"),
            ).message)
            assertEquals("Move failed: A file with the same name already exists at the destination", PikoErrorMapper.failure("Move", duplicate))
            assertEquals("Unsupported archive format; the archive may be corrupted or split into volumes", PikoErrorMapper.reason("INVALID_FILE_FORMAT"))
        } finally {
            PikoLocale.apply(AppLanguage.ZH_CN)
        }
    }
}
