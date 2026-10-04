package dev.piko.shared.rename

/**
 * 查找的积木：给不会写正则的人拼查找条件。积木只是正则的另一种写法，最后都生成正则交给 [FindReplaceRule]，
 * 预览、冲突检查与执行不知道查找串来自积木还是手写。
 *
 * [capture] 为 true 的积木生成编号捕获组，按出现先后编为 ①②…，替换积木里的 [ReplaceBlock.Piece] 引用它们。
 *
 * 「数字」与「字母」只认 ASCII（[0-9]、[A-Za-z]），与手写 \d 不同，是有意的：手写正则在两端都按 Unicode 算
 * （见 FindReplaceRule 的 UNICODE_CLASSES_FLAG），积木里的「数字」就是阿拉伯数字，全角的２、数学字体的𝟐不算。
 * 积木给的是「集数」「年份」这类意图，按 Unicode 算反而会把「第２集」这类写法和正常的一起改掉。
 */
sealed interface FindBlock {
    val capture: Boolean

    /** 原样的文字。 */
    data class Text(val text: String, override val capture: Boolean = false) : FindBlock

    /** 一串数字。[max] 为 null 表示不限位数上限。 */
    data class Digits(val min: Int = 1, val max: Int? = null, override val capture: Boolean = false) : FindBlock

    /** 一串英文字母。 */
    data class Letters(override val capture: Boolean = false) : FindBlock

    /** 任意字符，尽量少地匹配；[until] 不为 null 时一直匹配到这个字符之前。 */
    data class AnyText(val until: Char? = null, override val capture: Boolean = false) : FindBlock

    /** 一对括号及其中的内容。[kind] 为 null 表示常见的几种括号之一；[optional] 时没有也算匹配。 */
    data class Bracketed(val kind: BracketKind? = null, val optional: Boolean = false, override val capture: Boolean = false) : FindBlock

    /** 若干段文字之一。 */
    data class OneOf(val options: List<String>, override val capture: Boolean = false) : FindBlock

    /** 名称的开头。 */
    data object Start : FindBlock {
        override val capture: Boolean get() = false
    }

    /** 名称的结尾。 */
    data object End : FindBlock {
        override val capture: Boolean get() = false
    }
}

enum class BracketKind(val open: Char, val close: Char, val label: String) {
    SQUARE('[', ']', "[ ]"),
    LENTICULAR('【', '】', "【 】"),
    ROUND('(', ')', "( )"),
    FULLWIDTH_ROUND('（', '）', "（ ）"),
}

/** 积木拼出的正则。相邻的文字积木应先经 [normalizeFindBlocks] 合并，否则反向解析时它们会并成一块。 */
fun findBlocksToRegex(blocks: List<FindBlock>): String = buildString {
    for (block in blocks) append(block.regex())
}

/** 合并相邻的、都不取出的文字积木，去掉空文字。积木条上连着的两段文字与一段文字是同一个意思。 */
fun normalizeFindBlocks(blocks: List<FindBlock>): List<FindBlock> {
    val result = mutableListOf<FindBlock>()
    for (block in blocks) {
        if (block is FindBlock.Text && block.text.isEmpty()) continue
        val last = result.lastOrNull()
        if (block is FindBlock.Text && !block.capture && last is FindBlock.Text && !last.capture) {
            result[result.lastIndex] = FindBlock.Text(last.text + block.text)
        } else {
            result += block
        }
    }
    return result
}

/**
 * 把正则解析回积木。只认积木自己生成的写法：手写的正则即便意思相同，写法不同也认不出，返回 null，
 * 界面据此停在文本模式并注明无法图形化。转义过的标点与不带特殊含义的字符都算文字。
 */
fun regexToFindBlocks(regex: String): List<FindBlock>? {
    val blocks = mutableListOf<FindBlock>()
    var index = 0
    while (index < regex.length) {
        if (index == 0 && regex[0] == '^') {
            blocks += FindBlock.Start
            index = 1
            continue
        }
        if (index == regex.lastIndex && regex[index] == '$') {
            blocks += FindBlock.End
            index++
            continue
        }
        // 取出的积木：一对不带 ?: 的圆括号，里面恰好是一块积木
        if (regex[index] == '(' && !regex.startsWith("(?", index)) {
            val close = closingParen(regex, index) ?: return null
            val inner = parseSingle(regex.substring(index + 1, close)) ?: return null
            blocks += inner.withCapture()
            index = close + 1
            continue
        }
        val (block, length) = parseAt(regex, index) ?: return null
        blocks += block
        index += length
    }
    return normalizeFindBlocks(blocks)
}

/** 取出的积木在替换里的编号，从 1 起，按积木先后。不取出的积木为 null。 */
fun captureNumbers(blocks: List<FindBlock>): List<Int?> {
    var next = 1
    return blocks.map { if (it.capture) next++ else null }
}

/** 一块积木的中文说明，如「2 位数字」「方括号内容」。取出的积木不在这里标编号，由调用方加。 */
fun FindBlock.describe(): String = when (this) {
    is FindBlock.Text -> "「$text」"
    is FindBlock.Digits -> when {
        max == null && min <= 1 -> "数字"
        max == null -> "至少 $min 位数字"
        min == max -> "$min 位数字"
        else -> "$min 至 $max 位数字"
    }
    is FindBlock.Letters -> "英文字母"
    is FindBlock.AnyText -> if (until == null) "任意文字" else "「$until」前的任意文字"
    // 写成括号本身（[…]）比「方括号内容」一眼看得懂，块上也短
    is FindBlock.Bracketed -> (kind?.let { "${it.open}…${it.close}" } ?: "括号及内容") + (if (optional) "（可选）" else "")
    is FindBlock.OneOf -> options.joinToString("或") { "「$it」" }
    FindBlock.Start -> "开头"
    FindBlock.End -> "结尾"
}

/**
 * 整条查找拼成一句话，如「开头的方括号内容、任意字符、2 位数字①」。开头与结尾不单独成项，
 * 并进紧挨着的那一块（「开头的…」「结尾的…」）。并列项用顿号，不用中点。
 */
fun describeFind(blocks: List<FindBlock>): String {
    val numbers = captureNumbers(blocks)
    val parts = mutableListOf<String>()
    var atStart = false
    // 「2 位数字」前面接「的」时补一个空格，与界面上数字两侧留空的写法一致
    fun prefixed(prefix: String, label: String) = if (label.first().isDigit()) "$prefix $label" else prefix + label
    blocks.forEachIndexed { index, block ->
        when (block) {
            FindBlock.Start -> atStart = true
            FindBlock.End -> if (parts.isEmpty()) parts += "结尾" else parts[parts.lastIndex] = prefixed("结尾的", parts.last())
            else -> {
                val label = block.describe() + (numbers[index]?.let(::circled) ?: "")
                parts += if (atStart) prefixed("开头的", label) else label
                atStart = false
            }
        }
    }
    if (atStart) parts += "开头"
    return parts.joinToString("、")
}

/** ①…⑳，更多的写成 (21)。 */
fun circled(number: Int): String = if (number in 1..20) ('①' + (number - 1)).toString() else "($number)"

internal fun FindBlock.withCapture(): FindBlock = when (this) {
    is FindBlock.Text -> copy(capture = true)
    is FindBlock.Digits -> copy(capture = true)
    is FindBlock.Letters -> copy(capture = true)
    is FindBlock.AnyText -> copy(capture = true)
    is FindBlock.Bracketed -> copy(capture = true)
    is FindBlock.OneOf -> copy(capture = true)
    FindBlock.Start, FindBlock.End -> this
}

// region 积木 → 正则

private fun FindBlock.regex(): String {
    val body = when (this) {
        is FindBlock.Text -> escapeLiteral(text)
        is FindBlock.Digits -> "[0-9]" + quantifier(min, max)
        is FindBlock.Letters -> LETTERS
        is FindBlock.AnyText -> if (until == null) LAZY_ANY else "[^${escapeInClass(until)}]*"
        is FindBlock.Bracketed -> when {
            optional -> "(?:${bracketCore(kind)})?"
            kind == null -> "(?:${bracketCore(null)})"
            else -> bracketCore(kind)
        }
        is FindBlock.OneOf -> "(?:" + options.joinToString("|") { escapeLiteral(it) } + ")"
        FindBlock.Start -> "^"
        FindBlock.End -> "$"
    }
    return if (capture) "($body)" else body
}

private fun quantifier(min: Int, max: Int?): String = when {
    max == null && min <= 1 -> "+"
    max == null -> "{$min,}"
    min == max -> "{$min}"
    else -> "{$min,$max}"
}

private fun bracketCore(kind: BracketKind?): String =
    kind?.let { escapeLiteral(it.open.toString()) + "[^${escapeInClass(it.close)}]*" + escapeLiteral(it.close.toString()) }
        ?: BracketKind.entries.joinToString("|") { bracketCore(it) }

private const val LETTERS = "[A-Za-z]+"
private const val LAZY_ANY = ".*?"
private const val REGEX_META = "\\^$.|?*+()[]{}"

private fun escapeLiteral(text: String): String = buildString {
    for (char in text) {
        if (char in REGEX_META) append('\\')
        append(char)
    }
}

private fun escapeInClass(char: Char): String = if (char in "\\]^-[") "\\$char" else char.toString()

// endregion

// region 正则 → 积木

/** [text] 整个恰好是一块积木（可以是连成一段的文字）。 */
private fun parseSingle(text: String): FindBlock? {
    if (text.isEmpty()) return null
    var index = 0
    val parsed = mutableListOf<FindBlock>()
    while (index < text.length) {
        val (block, length) = parseAt(text, index) ?: return null
        parsed += block
        index += length
    }
    return normalizeFindBlocks(parsed).singleOrNull()
}

// 位数量词：+、{m}、{m,}、{m,n}
private val DIGITS_PATTERN = Regex("""^\[0-9\](\+|\{([0-9]+)(,([0-9]*))?\})""")
private val UNTIL_PATTERN = Regex("""^\[\^(\\.|[^\\\]])\]\*""")

/** 从 [index] 起的一块积木及其在正则里占的长度。 */
private fun parseAt(regex: String, index: Int): Pair<FindBlock, Int>? {
    val rest = regex.substring(index)
    DIGITS_PATTERN.find(rest)?.let { match ->
        val quantifier = match.groupValues[1]
        val block = if (quantifier == "+") {
            FindBlock.Digits()
        } else {
            val min = match.groupValues[2].toIntOrNull() ?: return null
            val max = when {
                match.groupValues[3].isEmpty() -> min
                match.groupValues[4].isEmpty() -> null
                else -> match.groupValues[4].toIntOrNull() ?: return null
            }
            // {1,} 与 + 同义，积木只有一种写法，认成 + 往返才不变
            if (max == null && min <= 1) return null
            FindBlock.Digits(min, max)
        }
        return block to match.value.length
    }
    if (rest.startsWith(LETTERS)) return FindBlock.Letters() to LETTERS.length
    if (rest.startsWith(LAZY_ANY)) return FindBlock.AnyText() to LAZY_ANY.length
    UNTIL_PATTERN.find(rest)?.let { match ->
        val until = match.groupValues[1].let { if (it.length == 2) it[1] else it[0] }
        return FindBlock.AnyText(until) to match.value.length
    }
    for (kind in BracketKind.entries) {
        val core = bracketCore(kind)
        if (rest.startsWith(core)) return FindBlock.Bracketed(kind) to core.length
    }
    if (rest.startsWith("(?:")) {
        val close = closingParen(regex, index) ?: return null
        val inner = regex.substring(index + 3, close)
        val optional = regex.getOrNull(close + 1) == '?'
        val length = close + 1 - index + (if (optional) 1 else 0)
        val kind = BracketKind.entries.firstOrNull { bracketCore(it) == inner }
        return when {
            inner == bracketCore(null) -> FindBlock.Bracketed(null, optional) to length
            kind != null && optional -> FindBlock.Bracketed(kind, optional = true) to length
            optional -> null
            else -> {
                val options = splitAlternatives(inner).map { parseLiteral(it) ?: return null }
                if (options.size < 2) null else FindBlock.OneOf(options) to length
            }
        }
    }
    val (char, length) = literalAt(regex, index) ?: return null
    return FindBlock.Text(char.toString()) to length
}

/** 一个字面字符：转义过的标点，或不带特殊含义的字符。反斜杠后跟字母或数字的是字符类或反向引用，不是字面字符。 */
private fun literalAt(regex: String, index: Int): Pair<Char, Int>? {
    val char = regex[index]
    if (char == '\\') {
        val next = regex.getOrNull(index + 1) ?: return null
        return if (next.isLetterOrDigit()) null else next to 2
    }
    return if (char in REGEX_META) null else char to 1
}

private fun parseLiteral(text: String): String? {
    if (text.isEmpty()) return null
    val result = StringBuilder()
    var index = 0
    while (index < text.length) {
        val (char, length) = literalAt(text, index) ?: return null
        result.append(char)
        index += length
    }
    return result.toString()
}

/** 与 [open] 处的左括号配对的右括号位置，跳过转义与方括号里的字符。 */
private fun closingParen(regex: String, open: Int): Int? {
    var depth = 0
    var index = open
    var inClass = false
    while (index < regex.length) {
        val char = regex[index]
        when {
            char == '\\' -> index++
            inClass -> if (char == ']') inClass = false
            char == '[' -> inClass = true
            char == '(' -> depth++
            char == ')' -> if (--depth == 0) return index
        }
        index++
    }
    return null
}

/** 按顶层的 | 切开。 */
private fun splitAlternatives(text: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    var index = 0
    var depth = 0
    var inClass = false
    while (index < text.length) {
        val char = text[index]
        when {
            char == '\\' -> index++
            inClass -> if (char == ']') inClass = false
            char == '[' -> inClass = true
            char == '(' -> depth++
            char == ')' -> depth--
            char == '|' && depth == 0 -> {
                parts += text.substring(start, index)
                start = index + 1
            }
        }
        index++
    }
    parts += text.substring(start)
    return parts
}

// endregion
