package dev.piko.shared.naming

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 应用、相机与网盘自动起的文件名。里面没有作品信息，原样显示又长又乱：能解出时间的换成
 * 「来源 时间」，解不出的交给批量分析按目录里的顺序编号（见 BatchAnalyzer.nameOpaqueFiles）。
 */
internal sealed interface GeneratedName {
    /** [source] 为 null 时名字里只有一个时间戳，看不出来源。 */
    data class Timed(val source: String?, val time: LocalDateTime) : GeneratedName {
        val label: String get() = listOfNotNull(source, formatTime(time)).joinToString(" ")
    }

    /**
     * Twitter 媒体下载器的名字：账号_日期__推文ID_序号_媒体ID。作品是账号，行标题是发推时间；
     * 同一条推文有多段时 [index] 从 1 起，只有一段时为 null。
     */
    data class Posted(val account: String, val time: LocalDateTime, val index: Int?) : GeneratedName {
        val label: String get() = listOfNotNull(formatTime(time), index?.let { "($it)" }).joinToString(" ")
    }

    /** Telegram 的「5_6190741636838855047」、十六进制哈希、UUID：没有任何可读信息。 */
    data object Opaque : GeneratedName
}

private class EpochPattern(val regex: Regex, val source: String?)

// 毫秒时间戳：LINE_MOVIE_1595952922014、微信的 wx_camera_1595952922014 与 mmexport1595952922014
private val EPOCH_MILLIS = listOf(
    EpochPattern(Regex("""(?i)^LINE_MOVIE_(\d{13})"""), "LINE 视频"),
    EpochPattern(Regex("""(?i)^(?:wx_camera_|mmexport)(\d{13})"""), "微信"),
    EpochPattern(Regex("""^(\d{13})$"""), null),
)
private val EPOCH_SECONDS = Regex("""^(\d{10})$""")

// 名字里直接写着当地时间：VID_20260913_090829、PXL_20240101_123456789、Screenrecorder-2024-01-01-12-30-45
private val CAMERA = Regex("""(?i)^(?:VID|IMG|PXL|MVIMG)_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})""")
// 整个名字就是日期加时刻：「2023-12-06 18-03-01」（洗掉「kcf9.com-」之后）
private val DATE_TIME = Regex("""^(\d{4})-(\d{2})-(\d{2})[ _T](\d{2})[-:.](\d{2})[-:.](\d{2})$""")
private val SCREEN_RECORDER = Regex("""(?i)^Screen_?recorder[-_](\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})""")

private val TWEET_MEDIA = Regex("""^(.+?)_(\d{8})__(\d{15,20})_(\d{1,2})_\d{15,25}$""")

// Twitter 的 snowflake ID 右移 22 位是自 Twitter 纪元起的毫秒数，比名字里只到日的日期精确，同一天的两条推不会撞名
private const val TWITTER_EPOCH_MILLIS = 1288834974657L

private val TELEGRAM = Regex("""^\d_\d{15,20}(?:_\(new\))?$""")
private val HEX_HASH = Regex("""(?i)^[0-9a-f]{12,64}$""")
private val UUID = Regex("""(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""")

// 超出这个范围的十几位数字多半是 ID，不是时间
private val PLAUSIBLE_YEARS = 2005..2040

/**
 * 认出自动生成的名字。[stem] 不含扩展名。毫秒与秒级时间戳按 [timeZone] 换成当地时间；
 * 相机名里的时间本来就是拍摄地的当地时间，原样使用。
 */
internal fun generatedName(stem: String, timeZone: TimeZone = TimeZone.currentSystemDefault()): GeneratedName? {
    TWEET_MEDIA.find(stem)?.let { match ->
        val millis = (match.groupValues[3].toLongOrNull() ?: return@let) shr 22
        val index = match.groupValues[4].toInt()
        epochTime(Instant.fromEpochMilliseconds(millis + TWITTER_EPOCH_MILLIS), timeZone)?.let {
            return GeneratedName.Posted(match.groupValues[1], it, index.takeIf { n -> n > 1 })
        }
    }
    EPOCH_MILLIS.forEach { pattern ->
        pattern.regex.find(stem)?.let { match ->
            epochTime(Instant.fromEpochMilliseconds(match.groupValues[1].toLong()), timeZone)?.let { return GeneratedName.Timed(pattern.source, it) }
        }
    }
    EPOCH_SECONDS.find(stem)?.let { match ->
        epochTime(Instant.fromEpochSeconds(match.groupValues[1].toLong()), timeZone)?.let { return GeneratedName.Timed(null, it) }
    }
    CAMERA.find(stem)?.let { match -> localTime(match)?.let { return GeneratedName.Timed("相机", it) } }
    SCREEN_RECORDER.find(stem)?.let { match -> localTime(match)?.let { return GeneratedName.Timed("录屏", it) } }
    DATE_TIME.find(stem)?.let { match -> localTime(match)?.let { return GeneratedName.Timed(null, it) } }
    if (TELEGRAM.matches(stem) || UUID.matches(stem)) return GeneratedName.Opaque
    // 全是数字的串交给上面的时间戳判断，这里只收真正混有字母的哈希，免得「20240101」这类被吞掉
    if (HEX_HASH.matches(stem) && stem.any { it.isLetter() }) return GeneratedName.Opaque
    return null
}

private fun epochTime(instant: Instant, timeZone: TimeZone): LocalDateTime? =
    instant.toLocalDateTime(timeZone).takeIf { it.year in PLAUSIBLE_YEARS }

private fun localTime(match: MatchResult): LocalDateTime? = runCatching {
    val (year, month, day, hour, minute, second) = match.destructured
    LocalDateTime(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
}.getOrNull()?.takeIf { it.year in PLAUSIBLE_YEARS }

private fun formatTime(time: LocalDateTime): String {
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "${time.year}-${two(time.month.ordinal + 1)}-${two(time.day)} ${two(time.hour)}:${two(time.minute)}"
}
