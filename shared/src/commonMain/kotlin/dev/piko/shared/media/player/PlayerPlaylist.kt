package dev.piko.shared.media.player

import dev.piko.data.repository.NaturalOrder
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.Section
import dev.piko.shared.naming.analyzeMediaBatch
import dev.piko.shared.state.distinctSpans
import dev.piko.shared.state.tokenize

/**
 * 播放列表里的一项。[label] 是能区分它与同列表其他项的那一段，通常就是集数。
 * [thumbnailUrl] 是网盘生成的缩略图，没有时为空串。
 * [sectionKey] 相同的项属于同一分区（同一作品的正片、SP、剧场版……），上一集、下一集与
 * 自动连播都不跨分区；[sectionLabel] 是分区在界面上的名字。
 */
data class PlaylistEntry(
    val fileId: String,
    val name: String,
    val label: String,
    val thumbnailUrl: String = "",
    val size: Long = 0,
    val sectionKey: String = "",
    val sectionLabel: String = "",
)

/**
 * 按文件名解析出的「作品 → 分区 → 条目」排好，并给出短标签与分区。传入的顺序不重要。
 *
 * 分区名：第一部作品（通常是正片那部）直接用分区名；其余作品（外传、剧场版这类另起标题的）
 * 的正片用作品名，其余分区在不重名时也直接用分区名，重名才加作品名。
 * 同一集有多个压制版本时都保留，标签后面附上能区分它们的标签。
 * 解析器没归入任何作品的文件放在最后的「其他」分区，标签退回按公共前后缀剥离。
 */
fun buildPlaylist(files: List<PlaylistEntry>): List<PlaylistEntry> {
    if (files.isEmpty()) return emptyList()
    val batch = analyzeMediaBatch(files.map { MediaFileInput(path = it.name, size = it.size) })
    val placed = mutableSetOf<Int>()
    val ordered = mutableListOf<PlaylistEntry>()
    val usedLabels = mutableSetOf<String>()
    batch.works.forEachIndexed { workIndex, work ->
        for (workSection in work.sections) {
            val bare = workSection.section.label
            val sectionLabel = when {
                workIndex == 0 -> bare
                workSection.section == Section.MAIN -> work.title ?: bare
                // 剧场版这类通常只此一处，直接叫「剧场版」；和前面重名时才加作品名，否则切换条放不下
                bare !in usedLabels -> bare
                else -> listOfNotNull(work.title, bare).joinToString(" ")
            }
            usedLabels += sectionLabel
            val sectionKey = "${work.key}/${workSection.section.name}"
            for (entry in workSection.entries) {
                for (file in entry.files) {
                    val source = files[file.index]
                    val base = entry.label ?: stemOf(source.name)
                    val variant = if (entry.files.size > 1) file.tags.joinToString(" ") { it.text } else ""
                    ordered += source.copy(
                        label = listOf(base, variant).filter { it.isNotBlank() }.joinToString(" "),
                        sectionKey = sectionKey,
                        sectionLabel = sectionLabel,
                    )
                    placed += file.index
                }
            }
        }
    }
    val rest = files.indices.filter { it !in placed }.map { files[it] }
    ordered += buildRawPlaylist(rest).map { it.copy(sectionKey = OTHER_SECTION_KEY, sectionLabel = Section.OTHER.label) }
    return ordered
}

/**
 * 文件名解析关闭时用，也是解析器认不出的文件的退路：按文件名自然排序，不分区，
 * 标签是剥掉公共前后缀后剩下的那一段。
 */
fun buildRawPlaylist(files: List<PlaylistEntry>): List<PlaylistEntry> {
    val sorted = files.sortedWith(compareBy(NaturalOrder) { it.name })
    val labels = distinctLabels(sorted.map { it.name })
    return sorted.mapIndexed { i, entry -> entry.copy(label = labels[i]) }
}

private const val OTHER_SECTION_KEY = "other"

private fun stemOf(name: String): String = name.substringBeforeLast('.').ifEmpty { name }

/** 解析器认不出的文件用它：剥掉公共前后缀，剩下能区分彼此的那一段。 */
internal fun distinctLabels(names: List<String>): List<String> =
    names.zip(distinctSpans(names)) { name, span ->
        val stem = stemOf(name)
        val middle = span?.let { name.substring(it) } ?: stem
        val trimmed = middle.trim(*SEPARATORS)
        // 只剩一个记号时去掉外层括号，「[01]」显示为「01」。只去成对的最外一层：逐字符 trim 会把
        // 「[25(SP)]」削成「25(SP」；多个记号时去括号则会拼出「01][1080P」
        val label = if (tokenize(trimmed).size == 1) trimmed.unwrapBrackets() else trimmed
        label.ifEmpty { stem }
    }

private fun String.unwrapBrackets(): String {
    if (length < 2) return this
    val close = BRACKET_PAIRS[first()] ?: return this
    return if (last() == close) substring(1, length - 1).trim(*SEPARATORS) else this
}

private val SEPARATORS = charArrayOf(' ', '-', '_', '.')
private val BRACKET_PAIRS = mapOf('[' to ']', '(' to ')', '{' to '}', '【' to '】')
