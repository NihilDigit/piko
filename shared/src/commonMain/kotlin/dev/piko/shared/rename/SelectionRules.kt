package dev.piko.shared.rename

/** 在预览里选中原名的一段后要做的事。 */
enum class SelectionEdit {
    DELETE,
    REPLACE,

    /** 选中的是数字时，换成按列表顺序的编号。 */
    NUMBER,
}

/**
 * 由预览里的选区生成的查找与替换积木，交给积木条，用户看得见、改得了。[matched] 是这条规则在 [total] 项里
 * 匹配到的项数；[generalized] 为 true 表示选中的文字各项不同，规则按位置而不是按文字匹配。
 */
class SelectionProposal(
    val find: List<FindBlock>,
    val replace: List<ReplaceBlock>,
    val matched: Int,
    val total: Int,
    val generalized: Boolean,
)

/**
 * 把「在这一项的原名里选中 [selection] 这一段」变成一条对整批都成立的规则。
 *
 * 选中的文字在每一项里都恰好出现一次时，直接按文字查找。否则按位置：选区换成一块取出的积木（数字、字母或任意字符），
 * 两边用紧挨着的文字定位，如「 - 」与「 [」；在这一项里验证匹配正好落在选区上，落不上就把两边的文字再往外扩一个词。
 * 各项里集数不同、分隔符相同，这样就能一条规则改到所有项的同一位置。
 *
 * 没用 naming 里的 SiblingAlignment：它只对齐数字槽位、给出簇的头尾文字，选区可以是任意一段文字，
 * 需要的是「这一段两边是什么」；而且这里直接拿生成的正则去匹配每一项来数匹配数，不必推断。
 *
 * 选区不在查找范围内（如只改主名时选了扩展名）、或定位的文字含「${」写不进替换串时返回 null。
 */
fun proposeSelectionRule(
    items: List<RenameSource>,
    options: FindReplaceOptions,
    target: RenameSource,
    selection: IntRange,
    edit: SelectionEdit,
    replacement: String = "",
): SelectionProposal? {
    val part = searchPart(target, options) ?: return null
    if (selection.isEmpty() || selection.first < part.first || selection.last > part.last) return null
    val text = target.name.substring(part.first, part.last + 1)
    val start = selection.first - part.first
    val end = selection.last + 1 - part.first
    val selected = text.substring(start, end)
    val parts = items.mapNotNull { item -> searchPart(item, options)?.let { item.name.substring(it.first, it.last + 1) } }

    fun count(find: List<FindBlock>): Int {
        val regex = compile(find, options) ?: return 0
        return parts.count { regex.containsMatchIn(it) }
    }

    if (parts.all { occurrences(it, selected) == 1 }) {
        val find = listOf(FindBlock.Text(selected))
        val replace = middleReplacement(edit, selected, replacement)
        return SelectionProposal(find, replace, count(find), parts.size, generalized = false)
    }

    var left = contextBefore(text, start)
    var right = contextAfter(text, end)
    repeat(MAX_WIDENING) {
        val find = positionalFind(text, left, start, end, right, selected)
        val regex = compile(find, options)
        val match = regex?.find(text)
        if (match != null && match.groups[1]?.range == start until end) {
            val leftText = text.substring(left, start)
            val rightText = text.substring(end, right)
            if (!isExpressibleText(leftText) || !isExpressibleText(rightText)) return null
            val replace = normalizeReplaceBlocks(
                listOf(ReplaceBlock.Text(leftText)) + middleReplacement(edit, selected, replacement) + ReplaceBlock.Text(rightText),
            )
            return SelectionProposal(find, replace, count(find), parts.size, generalized = true)
        }
        // 落在了更早的地方：两边各再带上一个词，定位得更准
        if (left > 0) left = tokenStart(text, left - 1) else if (right < text.length) right = tokenEnd(text, right)
    }
    return null
}

/** [index] 处的词在 [name] 里的范围，供触屏长按与鼠标双击选词。词是连续的数字、英文字母、空白或其他文字；标点单独成词。 */
fun tokenRangeAt(name: String, index: Int): IntRange {
    if (name.isEmpty()) return IntRange.EMPTY
    val at = index.coerceIn(0, name.lastIndex)
    return tokenStart(name, at) until tokenEnd(name, at)
}

private const val MAX_WIDENING = 4

private fun middleReplacement(edit: SelectionEdit, selected: String, replacement: String): List<ReplaceBlock> = when (edit) {
    SelectionEdit.DELETE -> emptyList()
    SelectionEdit.REPLACE -> listOf(ReplaceBlock.Text(replacement))
    SelectionEdit.NUMBER -> listOf(ReplaceBlock.Counter(start = 1, padding = selected.length))
}

/** 选区换成一块取出的积木，两边是定位用的文字。紧挨开头、结尾时用开头、结尾积木，不必再有文字。 */
private fun positionalFind(text: String, left: Int, start: Int, end: Int, right: Int, selected: String): List<FindBlock> {
    val rightText = text.substring(end, right)
    val middle = when {
        selected.all { it in '0'..'9' } -> FindBlock.Digits(capture = true)
        selected.all { it in 'a'..'z' || it in 'A'..'Z' } -> FindBlock.Letters(capture = true)
        rightText.isNotEmpty() -> FindBlock.AnyText(until = rightText.first(), capture = true)
        else -> FindBlock.AnyText(capture = true)
    }
    return normalizeFindBlocks(
        listOfNotNull(
            FindBlock.Start.takeIf { left == 0 },
            FindBlock.Text(text.substring(left, start)),
            middle,
            FindBlock.Text(rightText),
            FindBlock.End.takeIf { right == text.length },
        ),
    )
}

private fun compile(find: List<FindBlock>, options: FindReplaceOptions): Regex? = runCatching {
    Regex(UNICODE_CLASSES_FLAG + findBlocksToRegex(find), if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
}.getOrNull()

private fun occurrences(text: String, part: String): Int {
    var count = 0
    var from = text.indexOf(part)
    while (from >= 0) {
        count++
        from = text.indexOf(part, from + 1)
    }
    return count
}

/** 选区左边定位用的文字的起点：紧挨着的一串分隔符（「 - 」）；没有分隔符时是前一个词（「E09」的「E」）。 */
private fun contextBefore(text: String, start: Int): Int {
    if (start == 0) return 0
    var index = start
    while (index > 0 && !isWordChar(text[index - 1])) index--
    return if (index < start) index else tokenStart(text, start - 1)
}

private fun contextAfter(text: String, end: Int): Int {
    if (end == text.length) return end
    var index = end
    while (index < text.length && !isWordChar(text[index])) index++
    return if (index > end) index else tokenEnd(text, end)
}

private fun isWordChar(char: Char): Boolean = char.isLetterOrDigit()

private enum class CharClass { DIGIT, ASCII_LETTER, SPACE, OTHER_LETTER, SINGLE }

// 数字与英文字母只认 ASCII，与积木的「数字」「字母」一致；全角数字算其他文字
private fun classOf(char: Char): CharClass = when {
    char in '0'..'9' -> CharClass.DIGIT
    // 补充平面的字符（𝟐、表情）由两个代理项组成，归到文字里，选词时不会从中间切开
    char.isSurrogate() -> CharClass.OTHER_LETTER
    char in 'a'..'z' || char in 'A'..'Z' -> CharClass.ASCII_LETTER
    char.isWhitespace() -> CharClass.SPACE
    char.isLetterOrDigit() -> CharClass.OTHER_LETTER
    else -> CharClass.SINGLE
}

private fun tokenStart(text: String, index: Int): Int {
    val kind = classOf(text[index])
    if (kind == CharClass.SINGLE) return index
    var start = index
    while (start > 0 && classOf(text[start - 1]) == kind) start--
    return start
}

private fun tokenEnd(text: String, index: Int): Int {
    val kind = classOf(text[index])
    if (kind == CharClass.SINGLE) return index + 1
    var end = index + 1
    while (end < text.length && classOf(text[end]) == kind) end++
    return end
}
