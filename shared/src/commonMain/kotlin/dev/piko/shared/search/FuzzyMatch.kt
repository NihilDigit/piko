package dev.piko.shared.search

/**
 * 命令面板的模糊匹配：[query] 在 [text] 里能按顺序找齐（不必相连）就算匹配，返回分数，越大越靠前；找不齐返回 null。
 *
 * 分数的来由：整段相连地出现最高，出现在开头或词首再加；只能拆开找齐时，相连的字、落在词首的字各加分。
 * 长的文本略减分，同样匹配时短的名字更像要找的那个。不分大小写，查询里的空格忽略。
 */
fun fuzzyScore(query: String, text: String): Int? {
    val q = query.lowercase().filterNot(Char::isWhitespace)
    if (q.isEmpty()) return 0
    val t = text.lowercase()
    val at = t.indexOf(q)
    if (at >= 0) {
        val wordStart = at == 0 || !t[at - 1].isLetterOrDigit()
        return CONTIGUOUS + (if (at == 0) PREFIX else 0) + (if (wordStart) WORD_START * 2 else 0) - at - t.length / 4
    }
    var from = 0
    var previous = -2
    var score = 0
    for (c in q) {
        val found = t.indexOf(c, from)
        if (found < 0) return null
        score += if (found == previous + 1) ADJACENT else SCATTERED
        if (found == 0 || !t[found - 1].isLetterOrDigit()) score += WORD_START
        previous = found
        from = found + 1
    }
    return score - t.length / 4
}

private const val CONTIGUOUS = 1_000
private const val PREFIX = 200
private const val WORD_START = 10
private const val ADJACENT = 15
private const val SCATTERED = 5
