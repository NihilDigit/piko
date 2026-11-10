package dev.piko.shared.state

/** 记号结尾的字符。空格、- 与 _ 只在括号外才算：「[Game Name]」「[DBD-Raws]」是一个记号。 */
private const val TOKEN_END = "])}-_ "
private const val BRACKET_CLOSE = "])}"
private const val BRACKET_OPEN = "[({"

internal fun tokenize(name: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    for (c in name) {
        if (c == '.' && current.isNotEmpty() && depth == 0) {
            tokens += current.toString()
            current.clear()
        }
        current.append(c)
        when {
            c in BRACKET_OPEN -> depth++
            c in BRACKET_CLOSE -> depth = maxOf(0, depth - 1)
        }
        val ends = if (c in BRACKET_CLOSE) depth == 0 else depth == 0 && c in TOKEN_END
        if (ends) {
            tokens += current.toString()
            current.clear()
        }
    }
    if (current.isNotEmpty()) tokens += current.toString()
    return tokens
}

