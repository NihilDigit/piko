package dev.piko.shared.rename

data class CommonAffixes(val prefix: String, val suffix: String)

/**
 * 所选名称共有的开头与结尾，比较的是主名，扩展名不参与。
 *
 * 逐字符比出的公共部分常停在一个词中间（「Show S01E01」与「Show S01E02」停在「Show S01E0」），
 * 所以从最长处往回退，直到每个名称在这里都是切口：两侧至少一侧是分隔符，且不在括号里面。
 * 括号计深度而不是只看分隔符，否则「[abc]Movie[1]」与「[abc]Movie[2]」会切在「[」之后，剩下半个括号。
 * 中文词之间没有分隔符，「电影A」与「电影B」因此不剥任何东西，宁可少剥。
 *
 * 剥完每个名称至少要留下一个非分隔符的字符：名称本身就是公共前缀（「Movie」与「Movie 2」）时不剥，
 * 否则第一项会变成空名。后缀在剥掉前缀之后的剩余部分上找，两者不会重叠。
 */
fun commonAffixes(items: List<RenameSource>): CommonAffixes {
    if (items.size < 2) return CommonAffixes("", "")
    val stems = items.map { splitExtension(it.name, it.isFolder).first }

    val prefixLength = (commonPrefixLength(stems) downTo 1).firstOrNull { length ->
        stems.all { isCut(it, length) && hasSubstance(it, length, it.length) }
    } ?: 0
    val rests = stems.map { it.substring(prefixLength) }
    val suffixLength = (commonSuffixLength(rests) downTo 1).firstOrNull { length ->
        rests.all { isCut(it, it.length - length) && hasSubstance(it, 0, it.length - length) }
    } ?: 0

    return CommonAffixes(
        prefix = stems.first().take(prefixLength),
        suffix = rests.first().takeLast(suffixLength),
    )
}

/**
 * 去掉主名开头的 [prefix] 与结尾的 [suffix]，空字符串表示不去。剥掉后去掉切口处的空白，
 * 「[站名] 电影」剥完不留一个开头的空格。扩展名原样保留。
 */
class AffixStripRule(private val prefix: String, private val suffix: String) : RenameRule {
    override fun apply(items: List<RenameSource>, names: List<String>): List<String> =
        items.mapIndexed { index, item ->
            val (originalStem, extension) = splitExtension(names[index], item.isFolder)
            var stem = originalStem
            if (prefix.isNotEmpty() && stem.startsWith(prefix)) stem = stem.removePrefix(prefix).trimStart()
            if (suffix.isNotEmpty() && stem.endsWith(suffix)) stem = stem.removeSuffix(suffix).trimEnd()
            // 主名什么都不剩时给空名，交给冲突检查标出来：「.mkv」对服务端是合法名称，但显然不是用户要的
            if (stem.isBlank()) "" else stem + extension
        }
}

class BatchRenamePipelineResult(val names: List<String>, val affixes: CommonAffixes)

/**
 * 批量重命名的管线：先查找替换，再去掉此时仍共有的开头与结尾。
 *
 * 顺序这样定，是因为用户写查找时看的是原名：查找「Frieren - 」替换成「第」，指的就是原名里的那一段。
 * 先去共同开头的话，「[SweetSub] Frieren - 」整段已经没了，查找落空，写的替换也跟着消失（出过这个问题）。
 * 共同部分在替换之后重新识别，开关旁显示的正是此刻会去掉的文字；替换改动了开头时，识别出的共同部分跟着变，
 * 不会去掉一段各名里已不再共有的文字。
 *
 * [findReplace] 为 null 表示正则写错了，此时名称原样进入第二步。
 *
 * [base] 是整套改写名称的规则（按番号规范命名），排在最前：查找替换接着在它给出的名称上微调。
 */
fun runBatchRenamePipeline(
    items: List<RenameSource>,
    findReplace: RenameRule?,
    stripPrefix: Boolean,
    stripSuffix: Boolean,
    base: RenameRule? = null,
): BatchRenamePipelineResult {
    val start = base?.apply(items, items.map { it.name }) ?: items.map { it.name }
    val replaced = findReplace?.apply(items, start) ?: start
    val affixes = commonAffixes(items.zip(replaced) { item, name -> item.copy(name = name) })
    val strip = AffixStripRule(if (stripPrefix) affixes.prefix else "", if (stripSuffix) affixes.suffix else "")
    return BatchRenamePipelineResult(strip.apply(items, replaced), affixes)
}

private const val OPEN_BRACKETS = "[【(（{「『《<〈"
private const val CLOSE_BRACKETS = "]】)）}」』》>〉"
private const val OTHER_SEPARATORS = "-_.@#~+,，、;；:：!！|&=·—"

private fun isSeparator(char: Char): Boolean =
    char.isWhitespace() || char in OPEN_BRACKETS || char in CLOSE_BRACKETS || char in OTHER_SEPARATORS

/** 切在 [position] 之前：不在括号里面，且两侧至少一侧是分隔符（或名称的两端）。 */
private fun isCut(name: String, position: Int): Boolean {
    if (position < 0 || position > name.length) return false
    if (bracketDepth(name, position) != 0) return false
    return position == 0 || position == name.length ||
        isSeparator(name[position - 1]) || isSeparator(name[position])
}

/** 多出的右括号不让深度变负，否则一个落单的「]」会让后面所有位置都算不在括号外。 */
private fun bracketDepth(name: String, end: Int): Int {
    var depth = 0
    for (index in 0 until end) {
        when (name[index]) {
            in OPEN_BRACKETS -> depth++
            in CLOSE_BRACKETS -> depth = maxOf(0, depth - 1)
        }
    }
    return depth
}

private fun hasSubstance(name: String, from: Int, to: Int): Boolean =
    (from until to).any { !isSeparator(name[it]) }

private fun commonPrefixLength(names: List<String>): Int {
    val first = names.first()
    var length = names.minOf { it.length }
    for (name in names) {
        var index = 0
        while (index < length && name[index] == first[index]) index++
        length = index
    }
    return length
}

private fun commonSuffixLength(names: List<String>): Int {
    val first = names.first()
    var length = names.minOf { it.length }
    for (name in names) {
        var count = 0
        while (count < length && name[name.length - 1 - count] == first[first.length - 1 - count]) count++
        length = count
    }
    return length
}
