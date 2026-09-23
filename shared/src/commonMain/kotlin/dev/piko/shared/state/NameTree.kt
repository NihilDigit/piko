package dev.piko.shared.state

import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.NaturalOrder
import dev.piko.data.repository.fileCategory

/**
 * 按文件名的公共前缀折叠出的层级，供秒传面板展示磁力解析结果。
 *
 * 字幕组的种子里，文件名多是「[组名][剧名][02][1080P][BDRip][HEVC-10bit][FLAC].sc.ass」这种
 * 长串，能区分两个文件的只有中间的集数与末尾的后缀，平铺显示时正好都被截掉。把公共前缀
 * 提成一层标题，行里只剩不同的那段。
 *
 * 前缀只在记号边界上切：] ) } - _ 与空格之后，或 . 之前。按字符切会切出「[0」「2]」这种
 * 半截标题。不按种子里的目录结构分层：多数字幕组的种子只有一层目录，长名字都在同一层。
 */
sealed interface NameNode {
    /** 相对上一层的名字片段；顶层节点是完整前缀。 */
    val label: String

    /** 子树里所有文件在原列表中的下标。 */
    val indices: List<Int>
}

data class NameLeaf(override val label: String, val index: Int) : NameNode {
    override val indices: List<Int> get() = listOf(index)
}

/**
 * [suffix] 是子项共有的结尾，已从子项的 label 里剥掉。一季剧集除了集数，后面的
 * 「[1080P][BDRip][HEVC-10bit][FLAC].mkv」每行都一样，剥掉之后一集只剩「[01]」。
 */
data class NameGroup(
    override val label: String,
    val children: List<NameNode>,
    val suffix: String = "",
) : NameNode {
    override val indices: List<Int> = children.flatMap { it.indices }
}

/** 传入带下标的名字，可以只对其中一部分建树，如剔掉已随视频打包的字幕，下标仍是原列表的。 */
fun buildNameTree(entries: List<IndexedValue<String>>): List<NameNode> {
    val root = TrieNode()
    entries.sortedWith(compareBy(NaturalOrder) { it.value }).forEach { (index, name) ->
        var node = root
        tokenize(name).forEach { token -> node = node.children.getOrPut(token) { TrieNode() } }
        node.files += index
    }
    return when (val top = root.toNode(label = "")) {
        is NameGroup -> if (top.label.length < MIN_GROUP_LABEL) top.children.map { it.prefixed(top.label) } else listOf(top)
        is NameLeaf -> listOf(top)
    }
}

/**
 * 片段短于这个长度的组不单列，把它的子项并回上一层：「.sc.ass」与「.sc.srt」会先在「.sc」
 * 分叉，为三个字符开一层标题只是多点一下。
 */
private const val MIN_GROUP_LABEL = 6

private class TrieNode {
    val children = LinkedHashMap<String, TrieNode>()

    /** 名字恰好到此结束的文件。同名文件（不同目录下）可以有多个。 */
    val files = mutableListOf<Int>()

    fun fileCount(): Int = files.size + children.values.sumOf { it.fileCount() }
}

private fun TrieNode.toNode(label: String): NameNode {
    // 只有一条出路的链合成一段，否则每个记号都是一层
    var node = this
    var merged = label
    while (node.files.isEmpty() && node.children.size == 1) {
        val (token, child) = node.children.entries.single()
        merged += token
        node = child
    }
    if (node.fileCount() == 1) {
        return node.singleLeaf(merged)
    }
    val children = buildList {
        node.files.forEach { add(NameLeaf(label = "", index = it)) }
        node.children.forEach { (token, child) ->
            when (val sub = child.toNode(token)) {
                is NameGroup -> if (sub.label.length < MIN_GROUP_LABEL) {
                    // 并回上一层时把剥掉的结尾还给子项，否则「[PV][01].mp4」只剩「[PV][01]」
                    sub.children.forEach { add(it.prefixed(sub.label).withTail(sub.suffix)) }
                } else {
                    add(sub)
                }
                is NameLeaf -> add(sub)
            }
        }
    }
    return foldCommonSuffix(NameGroup(merged, children))
}

/** 短于这个长度的公共结尾不剥：只省下「.mkv」四个字符，却让每行读不出扩展名。 */
private const val MIN_SUFFIX = 8

/**
 * 把多数直属文件共有的结尾提到组上，没有这段结尾的文件照常显示全名。
 *
 * 不要求全体一致：正片二十几集的结尾相同，但同组里常夹着 PV（.mp4）、测试版这类例外，
 * 一个例外就让整组剥不了。子组不参与也不改，它们的 label 只是中段。
 * 候选取每个文件在记号边界上的各段结尾，取够多数的里面最长的一段。
 */
private fun foldCommonSuffix(group: NameGroup): NameGroup {
    val labels = group.children.filterIsInstance<NameLeaf>().map { it.label }
    if (labels.size < 2) return group
    val counts = HashMap<String, Int>()
    labels.forEach { label ->
        boundarySuffixes(label).forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    val needed = maxOf(2, (labels.size * SUFFIX_MAJORITY).toInt())
    val suffix = counts.filter { it.value >= needed }.keys.maxByOrNull { it.length } ?: return group
    return group.copy(
        children = group.children.map { child ->
            if (child is NameLeaf && child.label.endsWith(suffix)) child.copy(label = child.label.dropLast(suffix.length)) else child
        },
        suffix = suffix,
    )
}

private const val SUFFIX_MAJORITY = 0.6

/**
 * label 在记号边界上的各段结尾，至少 [MIN_SUFFIX] 长，且剥掉后前面不为空。边界与建树时
 * 同一套切法，所以「HEVC-10bit」里的 - 不会成为切口。
 */
private fun boundarySuffixes(label: String): List<String> {
    val tokens = tokenize(label)
    return (1 until tokens.size)
        .map { tokens.subList(it, tokens.size).joinToString("") }
        .filter { it.length >= MIN_SUFFIX }
}



private fun TrieNode.singleLeaf(label: String): NameLeaf {
    files.singleOrNull()?.let { return NameLeaf(label, it) }
    val (token, child) = children.entries.single { it.value.fileCount() == 1 }
    return child.singleLeaf(label + token)
}

private fun NameNode.withTail(tail: String): NameNode =
    if (this is NameLeaf && tail.isNotEmpty()) copy(label = label + tail) else this

private fun NameNode.prefixed(prefix: String): NameNode = when (this) {
    is NameLeaf -> copy(label = prefix + label)
    is NameGroup -> copy(label = prefix + label)
}

/** 记号结尾的字符。空格、- 与 _ 只在括号外才算：「[Game Name]」「[DBD-Raws]」是一个记号。 */
private const val TOKEN_END = "])}-_ "
private const val BRACKET_CLOSE = "])}"
private const val BRACKET_OPEN = "[({"

private fun tokenize(name: String): List<String> {
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

/**
 * 视频与同名字幕的配对：视频下标 → 它的字幕下标。
 *
 * 字幕几 KB 到几百 KB，勾视频时几乎总要带上，单独列出来只是把行数翻两三倍。配对看文件名，
 * 字幕名须以「视频名去掉扩展名 + .」开头：Ep1.mkv 认 Ep1.sc.ass，不认 Ep10.ass。不看目录，
 * 有的字幕组把字幕放在 Subs/ 下，名字仍与视频相同。多个视频都能配上时取名字最长的那个。
 */
fun subtitleBundles(names: List<String>): Map<Int, List<Int>> {
    val videoStems = names.withIndex()
        .filter { it.value.fileCategory() == FileCategory.VIDEO }
        .map { it.index to it.value.substringBeforeLast('.') + "." }
        .sortedByDescending { (_, stem) -> stem.length }
    return names.withIndex()
        .filter { it.value.fileCategory() == FileCategory.SUBTITLE }
        .mapNotNull { sub ->
            videoStems.firstOrNull { (_, stem) -> sub.value.startsWith(stem) }?.let { (video, _) -> video to sub.index }
        }
        .groupBy({ it.first }, { it.second })
}

/**
 * 顶层的组名若就是资源名的开头（常见的是字幕组名），去掉这一层：它已写在文件夹名里，
 * 单列一行只多一级缩进。组上有剥下来的公共结尾时保留，那是子项里不再出现的信息。
 */
fun List<NameNode>.withoutRedundantGroups(resourceName: String): List<NameNode> = flatMap { node ->
    if (node is NameGroup && node.suffix.isEmpty() && resourceName.startsWith(node.label)) node.children else listOf(node)
}

/** 层级摊平后的一行。[key] 取自从顶层到这一层的标签路径，展开状态按它记。 */
data class NameTreeRow(val node: NameNode, val depth: Int, val key: String)

fun flattenNameTree(
    nodes: List<NameNode>,
    isExpanded: (key: String, depth: Int) -> Boolean,
    depth: Int = 0,
    parentKey: String = "",
): List<NameTreeRow> = nodes.flatMap { node ->
    val key = when (node) {
        is NameLeaf -> "f${node.index}"
        is NameGroup -> "$parentKey/${node.label}"
    }
    val row = NameTreeRow(node, depth, key)
    if (node is NameGroup && isExpanded(key, depth)) {
        listOf(row) + flattenNameTree(node.children, isExpanded, depth + 1, key)
    } else {
        listOf(row)
    }
}
