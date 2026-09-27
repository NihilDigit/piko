package dev.piko.shared.state

import dev.piko.shared.data.DriveNames

/** 参与批量重命名的一项。只取名称与所在目录，逻辑不依赖 FileStat，便于离线测试。 */
data class RenameSource(
    val id: String,
    val parentId: String,
    val name: String,
    val isFolder: Boolean,
)

/**
 * 拆成主名与扩展名（含句点）。文件夹没有扩展名。
 * 只认最后一段短的 ASCII 字母数字为扩展名：「Movie.2020 高清版」的最后一段不是扩展名，拆开的话共同后缀会停在句点上。
 * 以句点开头且只有这一个句点的（如 .nfo）整个算主名。
 */
fun splitExtension(name: String, isFolder: Boolean): Pair<String, String> {
    if (isFolder) return name to ""
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return name to ""
    val extension = name.substring(dot + 1)
    val looksLikeExtension = extension.length in 1..MAX_EXTENSION_LENGTH &&
        extension.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    return if (looksLikeExtension) name.substring(0, dot) to name.substring(dot) else name to ""
}

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

/** 空字符串表示不剥或不替换。 */
data class RenameRules(
    val prefix: String = "",
    val suffix: String = "",
    val find: String = "",
    val replacement: String = "",
)

/**
 * 按 [rules] 得出的新名称，已经过 [DriveNames.clean]。主名什么都不剩时返回空字符串，
 * 不拼上扩展名：「.mkv」对服务端是合法名称，但显然不是用户要的。
 *
 * 查找替换只作用于主名，扩展名原样保留，免得把「.」换成空格时连扩展名一起改掉。
 * 剥掉前缀或后缀后去掉切口处的空白，「[站名] 电影」剥完不留一个开头的空格。
 */
fun renamedName(item: RenameSource, rules: RenameRules): String {
    val (originalStem, extension) = splitExtension(item.name, item.isFolder)
    var stem = originalStem
    if (rules.prefix.isNotEmpty() && stem.startsWith(rules.prefix)) stem = stem.removePrefix(rules.prefix).trimStart()
    if (rules.suffix.isNotEmpty() && stem.endsWith(rules.suffix)) stem = stem.removeSuffix(rules.suffix).trimEnd()
    if (rules.find.isNotEmpty()) stem = stem.replace(rules.find, rules.replacement)
    if (stem.isBlank()) return ""
    return DriveNames.clean(stem + extension)
}

enum class RenameProblem {
    /** 规则应用后什么都不剩。 */
    EMPTY,

    /** 与同目录里保留原名的项重名：未选中的项、不改名的所选项，或因其他问题不改名的所选项。 */
    TAKEN,

    /** 与所选另一项的新名称相同。 */
    DUPLICATE,

    /** 与所选其他项的名称循环互换（A 改成 B、B 改成 A），逐个改名总有一步撞上还没改的那个。 */
    CYCLE,
}

data class RenameRow(
    val source: RenameSource,
    val newName: String,
    val problem: RenameProblem?,
) {
    val isChanged: Boolean get() = newName != source.name
}

/**
 * [rows] 与所选顺序一致，供预览。[order] 是可执行的改名项及其执行顺序：
 * A 改成 B、B 改成 C 时先改 B，逐个改名的每一步都不与当时的名称冲突。
 */
data class RenamePlan(val rows: List<RenameRow>, val order: List<RenameRow>) {
    val problemCount: Int get() = rows.count { it.problem != null }
}

/**
 * [siblingNames] 是每个目录里未选中的项的名称，按 parentId 分组。所选项可能来自不同目录（全盘搜索的结果），
 * 冲突只在同一目录内判断。名称区分大小写，与网盘列表里的比较方式一致。
 */
fun planRenames(
    items: List<RenameSource>,
    rules: RenameRules,
    siblingNames: Map<String, Set<String>>,
): RenamePlan {
    val targets = items.map { renamedName(it, rules) }
    val problems = arrayOfNulls<RenameProblem>(items.size)
    fun isChanged(index: Int) = targets[index] != items[index].name
    fun isPending(index: Int) = isChanged(index) && problems[index] == null
    fun targetKey(index: Int) = items[index].parentId to targets[index]
    fun originalKey(index: Int) = items[index].parentId to items[index].name

    for (index in items.indices) {
        if (isChanged(index) && targets[index].isEmpty()) problems[index] = RenameProblem.EMPTY
    }
    items.indices.filter(::isPending)
        .groupBy(::targetKey)
        .values
        .filter { it.size > 1 }
        .flatten()
        .forEach { problems[it] = RenameProblem.DUPLICATE }

    // 不改名的项占着原名。每标出一个问题，那一项也不改名了，它的原名随之变成占用，所以要反复查到不再变化
    while (true) {
        val kept = items.indices.filterNot(::isPending).map(::originalKey).toSet()
        val taken = items.indices.filter { index ->
            isPending(index) &&
                (targets[index] in siblingNames[items[index].parentId].orEmpty() || targetKey(index) in kept)
        }
        if (taken.isEmpty()) break
        taken.forEach { problems[it] = RenameProblem.TAKEN }
    }

    // 目标名称仍是某个待改项的当前名称时，等那一项先改。一轮挑不出任何一项，剩下的就是循环
    val pending = items.indices.filter(::isPending).toMutableList()
    val order = mutableListOf<Int>()
    while (pending.isNotEmpty()) {
        val occupied = pending.map(::originalKey).toSet()
        val ready = pending.filter { targetKey(it) !in occupied }
        if (ready.isEmpty()) {
            pending.forEach { problems[it] = RenameProblem.CYCLE }
            break
        }
        order += ready
        pending -= ready.toSet()
    }

    val rows = items.mapIndexed { index, item -> RenameRow(item, targets[index], problems[index]) }
    return RenamePlan(rows = rows, order = order.map { rows[it] })
}

private const val MAX_EXTENSION_LENGTH = 10

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
