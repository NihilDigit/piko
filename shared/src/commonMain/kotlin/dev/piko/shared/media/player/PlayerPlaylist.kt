package dev.piko.shared.media.player

import dev.piko.data.repository.NaturalOrder
import dev.piko.shared.state.distinctSpans
import dev.piko.shared.state.tokenize

/**
 * 播放列表里的一项。[label] 是能区分它与同列表其他项的那一段，通常就是集数。
 * [thumbnailUrl] 是网盘生成的缩略图，没有时为空串。
 */
data class PlaylistEntry(
    val fileId: String,
    val name: String,
    val label: String,
    val thumbnailUrl: String = "",
)

/** 按自然顺序排好并算出短标签。传入的顺序不重要：网盘列表的默认排序不一定是按名字。 */
fun buildPlaylist(files: List<PlaylistEntry>): List<PlaylistEntry> {
    val sorted = files.sortedWith(compareBy(NaturalOrder) { it.name })
    val labels = distinctLabels(sorted.map { it.name })
    return sorted.mapIndexed { index, entry -> entry.copy(label = labels[index]) }
}

/** 顶栏始终显示完整标题，所以标签可以只留区分段。 */
internal fun distinctLabels(names: List<String>): List<String> =
    names.zip(distinctSpans(names)) { name, span ->
        val stem = name.substringBeforeLast('.').ifEmpty { name }
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
