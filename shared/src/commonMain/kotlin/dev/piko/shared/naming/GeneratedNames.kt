package dev.piko.shared.naming

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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

// 毫秒时间戳：LINE_MOVIE_1595952922014、微信的 wx_camera_1595952922014 与 mmexport1595952922014。
// 其他应用的「studio_video_1744635768893」看不出是哪个应用，只取时间
private val EPOCH_MILLIS = listOf(
    EpochPattern(Regex("""(?i)^LINE_MOVIE_(\d{13})"""), "LINE 视频"),
    EpochPattern(Regex("""(?i)^(?:wx_camera_|mmexport)(\d{13})"""), "微信"),
    EpochPattern(Regex("""^(\d{13})$"""), null),
    EpochPattern(Regex("""(?i)^[a-z]+(?:_[a-z]+)*_(\d{13})$"""), null),
)
private val EPOCH_SECONDS = Regex("""^(\d{10})$""")

// 名字里直接写着当地时间：VID_20260913_090829、VID_20250918235340、PXL_20240101_123456789、Screenrecorder-2024-01-01-12-30-45
private val CAMERA = Regex("""(?i)^(?:VID|IMG|PXL|MVIMG)_(\d{4})(\d{2})(\d{2})_?(\d{2})(\d{2})(\d{2})""")
private val WECHAT = Regex("""(?i)^WeChat_(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:\s*\(\d{1,3}\))*$""")
// Telegram 桌面版保存的媒体：video_2025-08-14_20-10-40，同一秒的几个加「 (2)」「 (3)」
private val TELEGRAM_DESKTOP = Regex("""(?i)^(?:video|photo)_(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})(?:\s*\(\d{1,3}\))*$""")
// 转存机器人的名字：From-<来源>-20241013T191849590Z，时间是 UTC
private val FORWARDED = Regex("""^From-(.+?)-(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})\d{0,3}Z$""")
// 名字加录制时间：「某某_20220907-015242-325」「某某20260728_195020」「881627187_某某_20230306_230427」。
// 名字是账号，时间区分各段；开头的一串数字是平台的用户 ID
private val NAMED_TIME = Regex("""^(?:\d{5,}_)?(.*?\p{L}.*?)[_\- ]?(\d{4})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})(?:[-_]\d{1,3})?$""")
// 前缀或后缀夹着哈希：VID_<哈希>、copy_<UUID>、<哈希>_raw
private val AFFIXED_ID = Regex("""^(?:[A-Za-z]{2,8}_)?(.+?)(?:_[A-Za-z]{2,8})?$""")
// 整个名字就是日期加时刻：「2023-12-06 18-03-01」（洗掉「kcf9.com-」之后）
private val DATE_TIME = Regex("""^(\d{4})-(\d{2})-(\d{2})[ _T](\d{2})[-:.](\d{2})[-:.](\d{2})$""")
private val SCREEN_RECORDER = Regex("""(?i)^Screen_?recorder[-_](\d{4})-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})""")

// 只写计数器的相机名：IMG_0216、DSC_0012、DSCF1234、MVI_5678。计数器照常当编号，前缀只是机器的约定
private val CAMERA_PREFIXES = setOf("img", "vid", "dsc", "dscf", "dscn", "mvi", "mov", "pxl", "mvimg", "gopr", "dji")

/** 相机文件名的前缀不是作品名，否则一目录的 IMG_0216、IMG_0219 成了作品「IMG」，上级目录也跟着叫 IMG。 */
internal fun isCameraPrefix(word: String): Boolean = word.lowercase() in CAMERA_PREFIXES

private val TWEET_MEDIA =Regex("""^(.+?)_(\d{8})__(\d{15,20})_(\d{1,2})_\d{15,25}$""")

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
    WECHAT.find(stem)?.let { match -> localTime(match)?.let { return GeneratedName.Timed("微信", it) } }
    TELEGRAM_DESKTOP.find(stem)?.let { match -> localTime(match)?.let { return GeneratedName.Timed("Telegram", it) } }
    FORWARDED.find(stem)?.let { match ->
        val source = match.groupValues[1].trim()
        val utc = localTime(match, offset = 1) ?: return@let
        val time = epochTime(utc.toInstant(TimeZone.UTC), timeZone) ?: return@let
        return if (source.equals("Telegram", ignoreCase = true)) GeneratedName.Timed("Telegram", time) else GeneratedName.Posted(source, time, null)
    }
    SCREEN_RECORDER.find(stem)?.let { match -> localTime(match)?.let { return GeneratedName.Timed("录屏", it) } }
    DATE_TIME.find(stem)?.let { match -> localTime(match)?.let { return GeneratedName.Timed(null, it) } }
    if (isOpaqueId(stem)) return GeneratedName.Opaque
    AFFIXED_ID.matchEntire(stem)?.let { match -> if (match.groupValues[1] != stem && isOpaqueId(match.groupValues[1])) return GeneratedName.Opaque }
    NAMED_TIME.matchEntire(stem)?.let { match ->
        val account = match.groupValues[1].trim(' ', '_', '-')
        localTime(match, offset = 1)?.let { if (account.isNotEmpty()) return GeneratedName.Posted(account, it, null) }
    }
    return null
}

// 全是数字的串交给上面的时间戳判断，这里只收真正混有字母的哈希，免得「20240101」这类被吞掉
private fun isOpaqueId(text: String): Boolean =
    TELEGRAM.matches(text) || UUID.matches(text) || HEX_HASH.matches(text) && text.any { it.isLetter() }

private fun epochTime(instant: Instant, timeZone: TimeZone): LocalDateTime? =
    instant.toLocalDateTime(timeZone).takeIf { it.year in PLAUSIBLE_YEARS }

/** 从第 [offset] + 1 个分组起依次读年月日时分秒。 */
private fun localTime(match: MatchResult, offset: Int = 0): LocalDateTime? = runCatching {
    val parts = match.groupValues.drop(1 + offset).take(6).map(String::toInt)
    LocalDateTime(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
}.getOrNull()?.takeIf { it.year in PLAUSIBLE_YEARS }

private fun formatTime(time: LocalDateTime): String {
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "${time.year}-${two(time.month.ordinal + 1)}-${two(time.day)} ${two(time.hour)}:${two(time.minute)}"
}
