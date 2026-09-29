package dev.piko.shared.rename

/**
 * 查找作用于名称的哪一段（在原名里的下标），与 [FindReplaceRule] 的范围与跳过规则一致；这一项不参与时为 null。
 */
internal fun searchPart(item: RenameSource, options: FindReplaceOptions): IntRange? {
    val name = item.name
    if (item.isFolder && (!options.includeFolders || options.scope == RenameScope.EXTENSION)) return null
    if (!item.isFolder && !options.includeFiles) return null
    val (stem, extension) = splitExtension(name, item.isFolder)
    return when {
        item.isFolder || options.scope == RenameScope.FULL -> 0 until name.length
        options.scope == RenameScope.NAME -> 0 until stem.length
        extension.isEmpty() -> null
        else -> stem.length + 1 until name.length
    }
}

/** 原名里被查找匹配到的一段。[block] 是积木在积木条上的位置，文本模式下为 -1（整个匹配一种颜色）。 */
data class MatchHighlight(val range: IntRange, val block: Int)

/**
 * 给预览上色：标出原名里每块积木匹配到的部分。
 *
 * 不从 [FindReplaceRule] 的结果反推：替换后的名称里分不出哪段来自哪块积木。这里另编一条正则，
 * 把每块积木都包成一个捕获组，组号与积木一一对应；匹配逻辑（范围、跳过的条目、全部还是第一处、
 * 紧接上一个匹配的空匹配不算）与 [FindReplaceRule] 保持一致，改那边时这里要跟着改。
 */
class MatchHighlighter(private val options: FindReplaceOptions, blocks: List<FindBlock>?) {
    // 第 i 个捕获组对应的积木位置；开头、结尾不占组
    private val groupBlocks: List<Int>? = blocks?.indices?.filter { blocks[it] != FindBlock.Start && blocks[it] != FindBlock.End }

    private val regex: Regex? = if (options.search.isEmpty()) {
        null
    } else {
        val pattern = if (blocks != null) findBlocksToRegex(blocks.map { it.withCapture() }) else options.search
        Regex(UNICODE_CLASSES_FLAG + pattern, if (options.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    }

    fun highlights(item: RenameSource): List<MatchHighlight> {
        val regex = regex ?: return emptyList()
        val part = searchPart(item, options) ?: return emptyList()
        val source = item.name.substring(part.first, part.last + 1)
        val result = mutableListOf<MatchHighlight>()
        var previousEnd = -1
        var match = regex.find(source)
        while (match != null) {
            val start = match.range.first
            val end = match.range.last + 1
            if (!(start == end && start == previousEnd)) {
                previousEnd = end
                if (groupBlocks == null) {
                    if (end > start) result += MatchHighlight(part.first + start until part.first + end, -1)
                } else {
                    val groups = match.groups
                    groupBlocks.forEachIndexed { group, block ->
                        val range = groups[group + 1]?.range
                        if (range != null && !range.isEmpty()) result += MatchHighlight(part.first + range.first..part.first + range.last, block)
                    }
                }
                if (!options.matchAll) break
            }
            match = match.next()
        }
        return result
    }
}
