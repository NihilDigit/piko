package dev.piko.ui.format

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

/** Runs on Kotlin/Native as well as JVM; BigDecimal parity stays in desktopTest. */
class NativeDisplayFormatTest {
    private var previousLanguage = AppLanguage.ZH_CN

    @BeforeTest
    fun selectChinese() {
        previousLanguage = PikoLocale.preference
        PikoLocale.apply(AppLanguage.ZH_CN)
    }

    @AfterTest
    fun restoreLanguage() { PikoLocale.apply(previousLanguage) }

    @Test
    fun localizedDatesAndQuotaLabelsCoverAllFiveLanguages() {
        val date = "2027-01-01T00:30:00+14:00"
        val now = Instant.parse("2026-12-31T15:00:00Z")
        val cases = listOf(
            listOf(AppLanguage.ZH_CN, "2027 年 1 月 1 日", "1 天后重置", "1 小时后重置", "今天 15:00"),
            listOf(AppLanguage.ZH_HANT, "2027 年 1 月 1 日", "1 天後重設", "1 小時後重設", "今天 15:00"),
            listOf(AppLanguage.EN, "Jan 1, 2027", "Resets in 1 day", "Resets in 1 hour", "Today 15:00"),
            listOf(AppLanguage.JA, "2027年1月1日", "1 日後にリセット", "1 時間後にリセット", "今日 15:00"),
            listOf(AppLanguage.KO, "2027년 1월 1일", "1일 후 초기화", "1시간 후 초기화", "오늘 15:00"),
        )
        for (case in cases) {
            PikoLocale.apply(case[0] as AppLanguage)
            assertEquals(case[1] as String, formatOffsetDate(date, includeYear = true))
            assertEquals(case[2] as String, monthlyQuotaResetLabel(now))
            assertEquals(case[3] as String, dailyQuotaResetLabel(now))
            assertEquals(case[4] as String, formatLibraryEventTime(now.toString(), now, TimeZone.UTC))
        }
        PikoLocale.apply(AppLanguage.EN)
        assertEquals("Resets in 31 days", monthlyQuotaResetLabel(Instant.parse("2026-12-31T16:00:00Z")))
        assertEquals("Resets in 24 hours", dailyQuotaResetLabel(Instant.parse("2026-12-31T16:00:00Z")))
    }

    @Test
    fun percentagesKeepOneDecimalAndRoundHalfUp() {
        assertEquals("0.0%", formatPercentage(0.0))
        assertEquals("12.3%", formatPercentage(12.25))
        assertEquals("100.0%", formatPercentage(100.0))
    }

    @Test
    fun clocksHaveStableAsciiDigits() {
        assertEquals("00:00", formatDurationMillis(Long.MIN_VALUE))
        assertEquals("01:01", formatDurationMillis(61_999))
        assertEquals("1:00:00", formatDurationMillis(3_600_000))
        assertEquals("01:00:00", formatDurationMillis(3_600_000, padHours = true))
        assertEquals("2562047788015:12:55", formatDurationMillis(Long.MAX_VALUE))
    }

    @Test
    fun playbackSpeedRoundingDoesNotNeedJavaMath() {
        assertEquals("1", formatPlaybackSpeed(1f))
        assertEquals("1.5", formatPlaybackSpeed(1.5f))
        assertEquals("1.13", formatPlaybackSpeed(1.125f))
        assertEquals("-1.13", formatPlaybackSpeed(-1.125f))
        assertEquals("0", formatPlaybackSpeed(-0f))
        assertFailsWith<IllegalArgumentException> { formatPlaybackSpeed(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { formatPlaybackSpeed(Float.POSITIVE_INFINITY) }
    }

    @Test
    fun offsetDateRetainsServerCalendar() {
        assertEquals("1 月 1 日", formatOffsetDate("2027-01-01T00:30:00+14:00"))
        assertEquals("2026 年 12 月 31 日", formatOffsetDate("2026-12-31T23:30:00-12:00", true))
        assertEquals("2 月 29 日", formatOffsetDate("2024-02-29T12:34:56.123456789Z"))
        assertNull(formatOffsetDate("2026-02-29T00:00:00Z"))
    }

    @Test
    fun historyUsesViewerTimeZone() {
        val now = Instant.parse("2027-01-01T01:00:00Z")
        val zone = TimeZone.of("UTC+08:00")
        assertEquals("今天 08:30", formatLibraryEventTime("2027-01-01T00:30:00Z", now, zone))
        assertEquals("昨天 23:59", formatLibraryEventTime("2026-12-31T15:59:00Z", now, zone))
        assertEquals("2026 年 12 月 30 日", formatLibraryEventTime("2026-12-30T00:00:00Z", now, zone))
        assertNull(formatLibraryEventTime("bad-date", now, zone))
    }

    @Test
    fun quotasRollOverAtSingaporeMidnight() {
        assertEquals("29 天后重置", monthlyQuotaResetLabel(Instant.parse("2024-01-31T16:00:00Z")))
        assertEquals("1 天后重置", monthlyQuotaResetLabel(Instant.parse("2024-02-29T15:59:59Z")))
        assertEquals("31 天后重置", monthlyQuotaResetLabel(Instant.parse("2026-12-31T16:00:00Z")))
        assertEquals("24 小时后重置", dailyQuotaResetLabel(Instant.parse("2026-01-01T16:00:00Z")))
        assertEquals("1 小时后重置", dailyQuotaResetLabel(Instant.parse("2026-01-01T15:00:00Z")))
        assertEquals("1 小时内重置", dailyQuotaResetLabel(Instant.parse("2026-01-01T15:00:00.001Z")))
    }
}
