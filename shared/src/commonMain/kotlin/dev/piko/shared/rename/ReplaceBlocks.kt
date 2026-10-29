package dev.piko.shared.rename

import dev.piko.shared.i18n.tr

/**
 * 替换的积木，拼成 [ReplaceTemplate] 认的替换串（正则模式）。与 [FindBlock] 一样只是另一种写法。
 */
sealed interface ReplaceBlock {
    /** 查找里取出的第 [number] 段（①②…），0 为整个匹配。 */
    data class Piece(val number: Int) : ReplaceBlock

    /** 原样的文字。 */
    data class Text(val text: String) : ReplaceBlock

    /** 序号，参数同 PowerRename 的 `${start=,increment=,padding=}`。 */
    data class Counter(val start: Int = 0, val increment: Int = 1, val padding: Int = 0) : ReplaceBlock

    /** 条目时间的一项，[token] 取 [DATE_TOKENS] 之一，如 YYYY、MM。 */
    data class Date(val token: String) : ReplaceBlock

    /** [length] 位随机字符。 */
    data class RandomText(val kind: RandomKind, val length: Int) : ReplaceBlock

    data object Uuid : ReplaceBlock
}

enum class RandomKind(val key: String, private val labelText: () -> String) {
    ALNUM("rstringalnum", { tr("随机字母与数字", "Random letters and digits", "ランダムな英数字", "무작위 영문자와 숫자") }),
    ALPHA("rstringalpha", { tr("随机字母", "Random letters", "ランダムな英字", "무작위 영문자") }),
    DIGIT("rstringdigit", { tr("随机数字", "Random digits", "ランダムな数字", "무작위 숫자") }),
;

    val label: String get() = labelText()
}

/** 合并相邻的文字积木，去掉空文字。 */
fun normalizeReplaceBlocks(blocks: List<ReplaceBlock>): List<ReplaceBlock> {
    val result = mutableListOf<ReplaceBlock>()
    for (block in blocks) {
        if (block is ReplaceBlock.Text && block.text.isEmpty()) continue
        val last = result.lastOrNull()
        if (block is ReplaceBlock.Text && last is ReplaceBlock.Text) {
            result[result.lastIndex] = ReplaceBlock.Text(last.text + block.text)
        } else {
            result += block
        }
    }
    return result
}

/**
 * 积木拼出的替换串。文字里的 `$` 写成 `$$`，正则模式下展开回一个 `$`，也不会被当成日期或分组引用。
 * 文字里含「${…}」时无法转义（PowerRename 找 `${` 不看前面 `$` 的奇偶，见 [ReplaceTemplate]），
 * 这种文字只能在文本模式里写，积木不收。
 */
fun replaceBlocksToTemplate(blocks: List<ReplaceBlock>): String = buildString {
    for (block in blocks) {
        when (block) {
            is ReplaceBlock.Piece -> append(if (block.number == 0) "$&" else "$" + block.number)
            is ReplaceBlock.Text -> append(block.text.replace("$", "$$"))
            is ReplaceBlock.Counter -> {
                val parameters = listOfNotNull(
                    "start=${block.start}".takeIf { block.start != 0 },
                    "increment=${block.increment}".takeIf { block.increment != 1 },
                    "padding=${block.padding}".takeIf { block.padding != 0 },
                )
                append("\${" + parameters.joinToString(",") + "}")
            }
            is ReplaceBlock.Date -> append("$" + block.token)
            is ReplaceBlock.RandomText -> append("\${${block.kind.key}=${block.length}}")
            ReplaceBlock.Uuid -> append("\${ruuidv4}")
        }
    }
}

/** 文字积木能否原样写进替换串：含 `${` 的不行，见 [replaceBlocksToTemplate]。 */
fun isExpressibleText(text: String): Boolean = "\${" !in text

/**
 * 把替换串解析回积木。花括号里写法不规范但意思清楚的（参数顺序不同、多余的空格之外的内容）按意思认，
 * 切回积木后改写成规范写法，替换结果不变。几种随机串写在一起、`` $` `` 与 `$'` 这类积木表达不了的，返回 null。
 */
fun templateToReplaceBlocks(template: String): List<ReplaceBlock>? {
    val blocks = mutableListOf<ReplaceBlock>()
    val text = StringBuilder()
    fun flush() {
        if (text.isNotEmpty()) blocks += ReplaceBlock.Text(text.toString())
        text.clear()
    }
    var index = 0
    while (index < template.length) {
        val char = template[index]
        if (char != '$') {
            text.append(char)
            index++
            continue
        }
        val next = template.getOrNull(index + 1)
        when {
            next == '$' -> {
                // 一对 $$ 后面紧跟 { 时，ReplaceTemplate 会把后一个 $ 与花括号认成占位符，这种写法积木表达不了
                if (template.getOrNull(index + 2) == '{') return null
                text.append('$')
                index += 2
            }
            next == '&' -> {
                flush()
                blocks += ReplaceBlock.Piece(0)
                index += 2
            }
            next != null && next in '1'..'9' -> {
                flush()
                blocks += ReplaceBlock.Piece(next - '0')
                index += 2
            }
            next == '{' -> {
                val close = template.indexOf('}', index + 2)
                if (close < 0) return null
                flush()
                blocks += placeholderBlock(template.substring(index + 2, close)) ?: return null
                index = close + 1
            }
            next == '`' || next == '\'' -> return null
            else -> {
                val token = DATE_TOKENS.firstOrNull { template.startsWith(it, index + 1) }
                if (token != null) {
                    flush()
                    blocks += ReplaceBlock.Date(token)
                    index += 1 + token.length
                } else {
                    // 其余的 $ 是字面的，与 render 一致
                    text.append('$')
                    index++
                }
            }
        }
    }
    flush()
    return normalizeReplaceBlocks(blocks)
}

private val START = Regex("start=(-?[0-9]+)")
private val INCREMENT = Regex("increment=(-?[0-9]+)")
private val PADDING = Regex("padding=([0-9]+)")

private fun placeholderBlock(content: String): ReplaceBlock? {
    if ("ruuidv4" in content) return if (RandomKind.entries.none { it.key in content }) ReplaceBlock.Uuid else null
    val randoms = RandomKind.entries.mapNotNull { kind ->
        Regex("${kind.key}=([0-9]+)").find(content)?.let { kind to (it.groupValues[1].toIntOrNull() ?: return null) }
    }
    if (randoms.size > 1) return null
    randoms.singleOrNull()?.let { (kind, length) -> return ReplaceBlock.RandomText(kind, length) }
    // 负的 rstringalpha 在 ReplaceTemplate 里是空串，积木没有这种写法
    if ("rstring" in content) return null
    fun number(pattern: Regex) = pattern.find(content)?.groupValues?.get(1)?.toIntOrNull()
    return ReplaceBlock.Counter(start = number(START) ?: 0, increment = number(INCREMENT) ?: 1, padding = number(PADDING) ?: 0)
}

/** 一块替换积木的中文说明。 */
fun ReplaceBlock.describe(): String = when (this) {
    is ReplaceBlock.Piece -> if (number == 0) tr("匹配内容", "Matched text", "一致した文字列", "일치한 텍스트") else tr("片段", "Capture", "キャプチャ", "캡처") + circled(number)
    is ReplaceBlock.Text -> "「$text」"
    // 直接写出前两个号：「序号 01、02…」比「从 1 开始，步长 1，补足 2 位」一眼看得懂
    is ReplaceBlock.Counter -> tr("序号 ${counterValue(start, padding)}、${counterValue(start + increment, padding)}…", "Counter ${counterValue(start, padding)}, ${counterValue(start + increment, padding)}…", "連番 ${counterValue(start, padding)}、${counterValue(start + increment, padding)}…", "번호 ${counterValue(start, padding)}, ${counterValue(start + increment, padding)}…")
    is ReplaceBlock.Date -> DATE_TOKEN_LABELS.getValue(token)
    is ReplaceBlock.RandomText -> tr("$length 位${kind.label}", "$length characters: ${kind.label}", "$length 文字：${kind.label}", "${length}자리 ${kind.label}")
    ReplaceBlock.Uuid -> tr("随机 UUID", "Random UUID", "ランダムUUID", "무작위 UUID")
}

private fun counterValue(value: Int, padding: Int): String =
    if (value < 0) "-" + (-value).toString().padStart(padding, '0') else value.toString().padStart(padding, '0')

/** 整条替换拼成一句话，如「片段①、「 - 」、序号 01、02…」。 */
fun describeReplace(blocks: List<ReplaceBlock>): String =
    if (blocks.isEmpty()) tr("留空将删除匹配内容", "Leave empty to delete matched text", "空欄にすると一致した文字列を削除します", "비워 두면 일치한 텍스트를 삭제합니다") else blocks.joinToString("、") { it.describe() }

/** 日期积木可选的各项及其说明，按 [DATE_TOKENS] 的顺序。 */
val DATE_TOKEN_CHOICES: List<Pair<String, String>> get() = DATE_TOKENS.map { it to DATE_TOKEN_LABELS.getValue(it) }

private val DATE_TOKEN_LABELS: Map<String, String> get() = mapOf(
    "YYYY" to tr("四位年份", "Four-digit year", "年（4桁）", "4자리 연도"),
    "YY" to tr("两位年份", "Two-digit year", "年（2桁）", "2자리 연도"),
    "Y" to tr("年份末位", "Last digit of year", "年の末尾1桁", "연도의 마지막 자리"),
    "MMMM" to tr("月份全称", "Full month name", "月名", "월 이름"),
    "MMM" to tr("月份简称", "Short month name", "月名（略称）", "월 이름 약어"),
    "MM" to tr("两位月份", "Two-digit month", "月（2桁）", "2자리 월"),
    "M" to tr("月份", "Month", "月", "월"),
    "DDDD" to tr("星期全称", "Full weekday name", "曜日名", "요일 이름"),
    "DDD" to tr("星期简称", "Short weekday name", "曜日名（略称）", "요일 약어"),
    "DD" to tr("两位日期", "Two-digit day", "日（2桁）", "2자리 일"),
    "D" to tr("日期", "Day", "日", "일"),
    "HH" to tr("两位小时（12 小时制）", "Two-digit hour (12-hour)", "時（12時間制、2桁）", "2자리 시 (12시간제)"),
    "H" to tr("小时（12 小时制）", "Hour (12-hour)", "時（12時間制）", "시 (12시간제)"),
    "TT" to tr("AM 或 PM", "AM or PM", "AM または PM", "AM 또는 PM"),
    "tt" to tr("am 或 pm", "am or pm", "am または pm", "am 또는 pm"),
    "hh" to tr("两位小时", "Two-digit hour", "時（2桁）", "2자리 시"),
    "h" to tr("小时", "Hour", "時", "시"),
    "mm" to tr("两位分钟", "Two-digit minute", "分（2桁）", "2자리 분"),
    "m" to tr("分钟", "Minute", "分", "분"),
    "ss" to tr("两位秒", "Two-digit second", "秒（2桁）", "2자리 초"),
    "s" to tr("秒", "Second", "秒", "초"),
    "fff" to tr("毫秒", "Milliseconds", "ミリ秒", "밀리초"),
    "ff" to tr("百分之一秒", "Hundredths of a second", "100分の1秒", "100분의 1초"),
    "f" to tr("十分之一秒", "Tenths of a second", "10分の1秒", "10분의 1초"),
)
