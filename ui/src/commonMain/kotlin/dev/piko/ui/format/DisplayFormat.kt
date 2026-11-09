package dev.piko.ui.format

import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Clock
import kotlin.time.Instant

/** Stable ASCII clock digits on every platform; segment handles also pad the hours. */
internal fun formatDurationMillis(millis: Long, padHours: Boolean = false): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val minutes = (totalSeconds / 60 % 60).toString().padStart(2, '0')
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        val hourText = hours.toString().let { if (padHours) it.padStart(2, '0') else it }
        "$hourText:$minutes:$seconds"
    } else {
        "$minutes:$seconds"
    }
}

/** Playback speeds are small finite values; round HALF_UP, then omit trailing zeroes. */
internal fun formatPlaybackSpeed(speed: Float): String {
    require(speed.isFinite())
    val hundredths = floor(abs(speed.toDouble()) * 100 + 0.5).toLong()
    val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    val sign = if (speed < 0 && hundredths != 0L) "-" else ""
    return sign + (hundredths / 100).toString() + if (fraction.isEmpty()) "" else ".$fraction"
}

private fun LocalDate.localizedLabel(includeYear: Boolean): String {
    val m = month.number
    val y = year.toString().padStart(4, '0')
    val englishMonth = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[m - 1]
    return if (includeYear) {
        tr("$y 年 $m 月 $day 日", "$englishMonth $day, $y", "${y}年${m}月${day}日", "${y}년 ${m}월 ${day}일", "$y 年 $m 月 $day 日")
    } else {
        tr("$m 月 $day 日", "$englishMonth $day", "${m}月${day}日", "${m}월 ${day}일", "$m 月 $day 日")
    }
}

/** Fixed one-decimal percentages without JVM String.format. */
internal fun formatPercentage(value: Double): String {
    val tenths = floor(value.coerceIn(0.0, 100.0) * 10 + 0.5).toInt()
    return "${tenths / 10}.${tenths % 10}%"
}

/** Share and membership dates retain the calendar date in the server's explicit offset. */
internal fun formatOffsetDate(value: String, includeYear: Boolean = false): String? = runCatching {
    DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(value).toLocalDateTime().date.localizedLabel(includeYear)
}.getOrNull()

/** History dates, unlike share dates, are displayed in the viewer's local time zone. */
internal fun formatLibraryEventTime(
    value: String,
    now: Instant = Clock.System.now(),
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String? = runCatching {
    val time = Instant.parse(value).toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val date = time.date
    val clock = "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
    when {
        date == today -> tr("今天 $clock", "Today $clock", "今日 $clock", "오늘 $clock", "今天 $clock")
        date == today + DatePeriod(days = -1) -> tr("昨天 $clock", "Yesterday $clock", "昨日 $clock", "어제 $clock", "昨天 $clock")
        else -> date.localizedLabel(includeYear = date.year != today.year)
    }
}.getOrNull()

private val quotaZone = TimeZone.of("UTC+08:00")

internal fun monthlyQuotaResetLabel(now: Instant = Clock.System.now()): String {
    val today = now.toLocalDateTime(quotaZone).date
    val month = today.month.number
    val reset = LocalDate(today.year + month / 12, month % 12 + 1, 1)
    val days = today.daysUntil(reset)
    return tr("$days 天后重置", "Resets in ${enCount(days, "day")}", "$days 日後にリセット", "${days}일 후 초기화", "$days 天後重設")
}

internal fun dailyQuotaResetLabel(now: Instant = Clock.System.now()): String {
    val tomorrow = now.toLocalDateTime(quotaZone).date + DatePeriod(days = 1)
    val hours = (tomorrow.atStartOfDayIn(quotaZone) - now).inWholeHours
    return if (hours < 1) tr("1 小时内重置", "Resets within 1 hour", "1時間以内にリセット", "1시간 이내 초기화", "1 小時內重設")
    else tr("$hours 小时后重置", "Resets in ${enCount(hours, "hour")}", "$hours 時間後にリセット", "${hours}시간 후 초기화", "$hours 小時後重設")
}
