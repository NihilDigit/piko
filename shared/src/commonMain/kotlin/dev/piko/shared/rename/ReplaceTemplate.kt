/*
 * 替换串的占位符与 $ 引用照搬 PowerToys PowerRename 的行为（MIT 许可，Copyright (c) Microsoft Corporation），
 * 对照的是 src/modules/powerrename/lib 下的 Enumerating、Randomizer、Helpers（GetDatedFileName）与
 * PowerRenameRegEx，以及 unittests 里的期望值。代码是按行为重写的，不是逐行翻译。
 */
package dev.piko.shared.rename

import kotlin.random.Random
import kotlinx.datetime.LocalDateTime

/**
 * 解析好的替换串。按 PowerRename 认三类占位符：
 * - `${...}`：计数器，花括号里可写 `start=`、`increment=`、`padding=`，其余内容一概忽略，`${}` 即从 0 数起；
 *   写了 `rstringalnum=N`、`rstringalpha=N`、`rstringdigit=N` 或 `ruuidv4` 的是随机串，不是计数器。
 * - `$YYYY`、`$MM`、`$hh` 一类：条目的时间，见 [DATE_TOKENS]。`$$` 是转义，`$$YYYY` 不展开。
 * - 正则模式下剩余文字里的 `$1`…`$9`、`$&`、`$$`，替换时再按匹配结果展开，见 [render]。
 *
 * 与 PowerRename 的一处差别：它先把计数器与随机串的值插回替换串，再交给正则替换，插进去的值紧跟在 `$` 后面时
 * 会被当成捕获组引用（`$${}` 的 0 与前面的 `$` 拼成 `$0`）。这里占位符的值不再参与 `$` 解析。
 * 它按固定偏移插回计数器，与日期占位符同用时偏移会错位，这里一次扫描展开，没有这个问题。
 */
class ReplaceTemplate private constructor(private val parts: List<Part>) {

    /** 用到了条目时间，取不到时间的条目据此提示。 */
    val usesTime: Boolean = parts.any { it is Part.Date }

    /**
     * 为第 [counter] 个匹配到的条目展开占位符。[time] 为 null 时日期占位符原样保留，不猜一个时间。
     * 随机串取自 [random]，调用方给定种子，预览与执行之间、改别的选项时同一项的随机串不变。
     */
    fun evaluate(counter: Int, random: Random, time: LocalDateTime?): List<Piece> = parts.map { part ->
        when (part) {
            is Part.Text -> Piece(part.text, raw = true)
            is Part.Counter -> Piece(part.format(counter), raw = false)
            is Part.RandomString -> Piece(part.generate(random), raw = false)
            Part.Uuid -> Piece(randomUuid(random), raw = false)
            is Part.Date -> Piece(if (time == null) "$" + part.token else formatDate(part.token, time), raw = false)
        }
    }

    /** 展开后的一段。[raw] 为 true 的是用户写的文字，正则模式下其中的 `$` 引用还要解析。 */
    class Piece(val text: String, val raw: Boolean)

    private sealed interface Part {
        class Text(val text: String) : Part

        class Counter(private val start: Int, private val increment: Int, private val padding: Int) : Part {
            // 与 PowerRename 一样按 32 位整数算，溢出即回绕；printf 的 %0Nd 把负号算进宽度
            fun format(index: Int): String {
                val value = start + index * increment
                val digits = if (value < 0) (-value.toLong()).toString() else value.toString()
                val sign = if (value < 0) "-" else ""
                return sign + digits.padStart(padding - sign.length, '0')
            }
        }

        class RandomString(private val alphabet: String, private val count: Int) : Part {
            fun generate(random: Random): String = buildString { repeat(count) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }

        data object Uuid : Part

        class Date(val token: String) : Part
    }

    companion object {
        fun parse(template: String): ReplaceTemplate {
            val parts = mutableListOf<Part>()
            val text = StringBuilder()
            fun flushText() {
                if (text.isNotEmpty()) parts += Part.Text(text.toString())
                text.clear()
            }
            var index = 0
            while (index < template.length) {
                if (template[index] != '$') {
                    text.append(template[index++])
                    continue
                }
                var runEnd = index
                while (runEnd < template.length && template[runEnd] == '$') runEnd++
                val dollars = runEnd - index
                val close = if (runEnd < template.length && template[runEnd] == '{') template.indexOf('}', runEnd) else -1
                // PowerRename 找 `${` 不看前面 `$` 的奇偶：`$${}` 也是一个计数器，前面剩一个 `$`
                if (close >= 0) {
                    repeat(dollars - 1) { text.append('$') }
                    flushText()
                    parts += placeholder(template.substring(runEnd + 1, close))
                    index = close + 1
                    continue
                }
                val token = if (dollars % 2 == 1) DATE_TOKENS.firstOrNull { template.startsWith(it, runEnd) } else null
                if (token != null) {
                    repeat(dollars - 1) { text.append('$') }
                    flushText()
                    parts += Part.Date(token)
                    index = runEnd + token.length
                } else {
                    repeat(dollars) { text.append('$') }
                    index = runEnd
                }
            }
            flushText()
            return ReplaceTemplate(parts)
        }

        // 数字写 [0-9] 不写 \d：Android 的正则是 ICU，\d 认全角与其他文字的数字，toInt 会失败
        private val START = Regex("start=(-?[0-9]+)")
        private val INCREMENT = Regex("increment=(-?[0-9]+)")
        private val PADDING = Regex("padding=([0-9]+)")
        private val RANDOM_ALNUM = Regex("rstringalnum=([0-9]+)")
        private val RANDOM_ALPHA = Regex("rstringalpha=(-?[0-9]+)")
        private val RANDOM_DIGIT = Regex("rstringdigit=([0-9]+)")

        /**
         * 花括号里的内容。几种随机串写在一起时字符集相加、长度取最后认出的那一种，`ruuidv4` 优先，
         * 都与 PowerRename 相同；只有 rstringalpha 允许负数，负数得到空串，也照它。
         */
        private fun placeholder(content: String): Part {
            if ("ruuidv4" in content) return Part.Uuid
            var alphabet = ""
            var length: Int? = null
            RANDOM_ALNUM.find(content)?.let { alphabet += LOWER + UPPER + DIGITS; length = it.number() }
            RANDOM_ALPHA.find(content)?.let { alphabet += LOWER + UPPER; length = it.number() }
            RANDOM_DIGIT.find(content)?.let { alphabet += DIGITS; length = it.number() }
            if (alphabet.isNotEmpty()) {
                return Part.RandomString(alphabet, (length ?: DEFAULT_RANDOM_LENGTH).coerceIn(0, MAX_NAME_LENGTH))
            }
            // 数值溢出时 PowerRename 的 from_chars 失败，留下的是 0
            return Part.Counter(
                start = START.find(content)?.number() ?: 0,
                increment = INCREMENT.find(content)?.number() ?: 1,
                padding = (PADDING.find(content)?.number() ?: 0) % MAX_NAME_LENGTH,
            )
        }

        private fun MatchResult.number(): Int = groupValues[1].toIntOrNull() ?: 0

        private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
        private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val DIGITS = "0123456789"
        private const val DEFAULT_RANDOM_LENGTH = 10

        // PowerRename 以 MAX_PATH 为上限。补零与随机串再长也放不进一个名称，只防手滑写出几百万位把界面卡死
        private const val MAX_NAME_LENGTH = 260
    }
}

/**
 * 日期占位符，同一字母长的在前：在同一位置按先后取第一个对上的，`$YYY` 因此是 `$YY` 加一个 Y，与 PowerRename 相同。
 * 大写 H 是 12 小时制、小写 h 是 24 小时制，TT 与 tt 是 AM/PM 的大小写。
 */
internal val DATE_TOKENS = listOf(
    "YYYY", "YY", "Y",
    "MMMM", "MMM", "MM", "M",
    "DDDD", "DDD", "DD", "D",
    "HH", "H", "TT", "tt",
    "hh", "h", "mm", "m", "ss", "s",
    "fff", "ff", "f",
)

/**
 * 月份与星期的名称，PowerRename 按系统区域格式化。Piko 的界面只有中文，这里写死 Windows 中文区域的写法：
 * MMMM 为「七月」、MMM 为「7月」、DDDD 为「星期三」、DDD 为「周三」。AM/PM 在 PowerRename 里本来就不随区域。
 */
private fun formatDate(token: String, time: LocalDateTime): String {
    val hour12 = (time.hour % 12).let { if (it == 0) 12 else it }
    val millis = time.nanosecond / 1_000_000
    val weekday = time.dayOfWeek.ordinal // 周一为 0
    return when (token) {
        "YYYY" -> time.year.toString().padStart(4, '0')
        "YY" -> (time.year % 100).toString().padStart(2, '0')
        "Y" -> (time.year % 10).toString()
        "MMMM" -> MONTH_NAMES[time.month.ordinal]
        "MMM" -> "${time.month.ordinal + 1}月"
        "MM" -> (time.month.ordinal + 1).toString().padStart(2, '0')
        "M" -> (time.month.ordinal + 1).toString()
        "DDDD" -> "星期" + WEEKDAY_NAMES[weekday]
        "DDD" -> "周" + WEEKDAY_NAMES[weekday]
        "DD" -> time.day.toString().padStart(2, '0')
        "D" -> time.day.toString()
        "HH" -> hour12.toString().padStart(2, '0')
        "H" -> hour12.toString()
        "TT" -> if (time.hour < 12) "AM" else "PM"
        "tt" -> if (time.hour < 12) "am" else "pm"
        "hh" -> time.hour.toString().padStart(2, '0')
        "h" -> time.hour.toString()
        "mm" -> time.minute.toString().padStart(2, '0')
        "m" -> time.minute.toString()
        "ss" -> time.second.toString().padStart(2, '0')
        "s" -> time.second.toString()
        "fff" -> millis.toString().padStart(3, '0')
        "ff" -> (millis / 10).toString().padStart(2, '0')
        "f" -> (millis / 100).toString()
        else -> error("未知的日期占位符 $token")
    }
}

private val MONTH_NAMES = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
private val WEEKDAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

/** 版本 4 的 UUID，大写、不带花括号：PowerRename 取 StringFromCLSID 的结果去掉两端花括号。 */
private fun randomUuid(random: Random): String {
    val bytes = random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.uppercase()
    return listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16), hex.substring(16, 20), hex.substring(20))
        .joinToString("-")
}

/**
 * 把展开后的替换串拼起来。[match] 为 null 是普通文本替换，全部原样拼接（`$$` 也原样保留，与 PowerRename 相同）。
 *
 * 正则模式下用户文字里的 `$` 按 PowerRename 默认的 std::regex 解析，不交给平台的 Matcher：
 * - `$1`…`$9` 只取一位，`$12` 是第 1 组加字符 2；组不存在或未参与匹配时为空。
 * - `$0` 原样保留：PowerRename 特意把它转义成字面量。
 * - `$$` 是一个 `$`，`$&` 是整个匹配，`` $` `` 与 `$'` 是匹配之前与之后的部分；其余的 `$` 原样保留。
 * JVM 的 Matcher 把 `$12` 读成第 12 组（组数不够时退一位），`$x` 抛异常；Android 的 Matcher 按应用的 targetSdk
 * 在两种实现之间切换，旧的那种只取一位、把不认识的 `$` 吞掉。两端都不照搬，才能在各平台得到同一个结果。
 */
internal fun render(pieces: List<ReplaceTemplate.Piece>, match: MatchResult?, input: CharSequence): String = buildString {
    for (piece in pieces) {
        if (match == null || !piece.raw) {
            append(piece.text)
            continue
        }
        val text = piece.text
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val next = text.getOrNull(index + 1)
            if (char != '$' || next == null) {
                append(char)
                index++
                continue
            }
            when (next) {
                '$' -> append('$')
                '&' -> append(match.value)
                '`' -> append(input, 0, match.range.first)
                '\'' -> append(input, match.range.last + 1, input.length)
                in '1'..'9' -> {
                    val group = next - '0'
                    if (group < match.groups.size) append(match.groups[group]?.value.orEmpty())
                }
                else -> {
                    append('$')
                    index++
                    continue
                }
            }
            index += 2
        }
    }
}
