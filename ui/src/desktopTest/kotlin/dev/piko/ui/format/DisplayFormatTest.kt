package dev.piko.ui.format

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class DisplayFormatTest {
    private var previousLanguage = AppLanguage.ZH_CN

    @BeforeTest
    fun selectChinese() {
        previousLanguage = PikoLocale.preference
        PikoLocale.apply(AppLanguage.ZH_CN)
    }

    @AfterTest
    fun restoreLanguage() { PikoLocale.apply(previousLanguage) }

    @Test
    fun durationClampsNegativesTruncatesMillisAndPadsClockFields() {
        assertEquals("00:00", formatDurationMillis(Long.MIN_VALUE))
        assertEquals("00:00", formatDurationMillis(999))
        assertEquals("01:01", formatDurationMillis(61_999))
        assertEquals("59:59", formatDurationMillis(3_599_999))
        assertEquals("1:00:00", formatDurationMillis(3_600_000))
        assertEquals("01:00:00", formatDurationMillis(3_600_000, padHours = true))
        assertEquals("100:00:00", formatDurationMillis(360_000_000, padHours = true))
        assertEquals("2562047788015:12:55", formatDurationMillis(Long.MAX_VALUE))
    }

    @Test
    fun playbackSpeedsRetainHalfUpRoundingOfTheOriginalFloatValue() {
        for (step in 500..3500) {
            val speed = step / 1000f
            val original = BigDecimal(speed.toDouble()).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString()
            assertEquals(original, formatPlaybackSpeed(speed), "speed=$speed")
        }
        assertEquals("1", formatPlaybackSpeed(1f))
        assertEquals("1.5", formatPlaybackSpeed(1.5f))
        assertEquals("1.13", formatPlaybackSpeed(1.125f))
        assertEquals("-1.13", formatPlaybackSpeed(-1.125f))
        assertEquals("0", formatPlaybackSpeed(-0f))
    }

    @Test
    fun offsetDatesRetainServerCalendarDateRatherThanConvertingToUtc() {
        assertEquals("1 月 1 日", formatOffsetDate("2027-01-01T00:30:00+14:00"))
        assertEquals("2026 年 12 月 31 日", formatOffsetDate("2026-12-31T23:30:00-12:00", true))
        assertEquals("2 月 29 日", formatOffsetDate("2024-02-29T12:34:56.123456789Z"))
    }

    @Test
    fun malformedDatesAreOmitted() {
        for (value in listOf("", "-1", "not-a-date", "2026-02-29T00:00:00Z", "2026-01-01")) {
            assertNull(formatOffsetDate(value), value)
            assertNull(formatLibraryEventTime(value, Instant.parse("2026-01-01T00:00:00Z"), TimeZone.UTC), value)
        }
    }

    @Test
    fun libraryDatesUseLocalTodayYesterdayAndCrossYearLabels() {
        val now = Instant.parse("2027-01-01T01:00:00Z")
        val zone = TimeZone.of("UTC+08:00")
        assertEquals("今天 08:30", formatLibraryEventTime("2027-01-01T00:30:00Z", now, zone))
        assertEquals("昨天 23:59", formatLibraryEventTime("2026-12-31T15:59:00Z", now, zone))
        assertEquals("2026 年 12 月 30 日", formatLibraryEventTime("2026-12-30T00:00:00Z", now, zone))
        assertEquals("1 月 2 日", formatLibraryEventTime("2027-01-02T00:00:00Z", now, zone))
    }

    @Test
    fun monthlyResetUsesSingaporeCalendarAcrossLeapMonthAndYear() {
        assertEquals("29 天后重置", monthlyQuotaResetLabel(Instant.parse("2024-01-31T16:00:00Z")))
        assertEquals("1 天后重置", monthlyQuotaResetLabel(Instant.parse("2024-02-29T15:59:59Z")))
        assertEquals("1 天后重置", monthlyQuotaResetLabel(Instant.parse("2026-12-31T15:59:59Z")))
        assertEquals("31 天后重置", monthlyQuotaResetLabel(Instant.parse("2026-12-31T16:00:00Z")))
    }

    @Test
    fun dailyResetTruncatesWholeHoursAndRollsOverAtSingaporeMidnight() {
        assertEquals("24 小时后重置", dailyQuotaResetLabel(Instant.parse("2026-01-01T16:00:00Z")))
        assertEquals("1 小时后重置", dailyQuotaResetLabel(Instant.parse("2026-01-01T15:00:00Z")))
        assertEquals("1 小时内重置", dailyQuotaResetLabel(Instant.parse("2026-01-01T15:00:00.001Z")))
        assertEquals("1 小时内重置", dailyQuotaResetLabel(Instant.parse("2026-01-01T15:59:59Z")))
    }
}
