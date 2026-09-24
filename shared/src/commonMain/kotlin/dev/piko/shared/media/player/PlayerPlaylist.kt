package dev.piko.shared.media.player

import dev.piko.data.repository.NaturalOrder
import dev.piko.shared.naming.EntryFile
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.Section
import dev.piko.shared.naming.analyzeMediaBatch
import dev.piko.shared.naming.distinctFiles
import dev.piko.shared.naming.versionsOfPrimary
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
    /**
     * 同一内容的几个版本共用一个键；没有其他版本时就是 [fileId]。上一集、下一集按组走，每组算一集。
     */
    val groupKey: String = fileId,
    /** 能区分同组各版本的那段标签：「HDR10」「720p」。没有其他版本时为空串。 */
    val versionLabel: String = "",
    /** 组里体积最大的那个。点集这一行、没有版本偏好时播它。 */
    val primary: Boolean = true,
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
            // 同一内容的几个版本成一组，组里体积最大的排在最前；编号相同而内容不同的文件各自成组
            for (entry in workSection.entries) {
                val distinct = entry.distinctFiles()
                for (file in distinct) {
                    val base = entry.label ?: stemOf(files[file.index].name)
                    val variant = if (distinct.size > 1) file.tags.joinToString(" ") { it.text } else ""
                    val label = listOf(base, variant).filter { it.isNotBlank() }.joinToString(" ")
                    val group = if (file == entry.primary) listOf(file) + entry.versionsOfPrimary() else listOf(file)
                    val versionLabels = versionLabels(group, files)
                    group.forEachIndexed { index, member ->
                        ordered += files[member.index].copy(
                            label = label,
                            sectionKey = sectionKey,
                            sectionLabel = sectionLabel,
                            groupKey = files[file.index].fileId,
                            versionLabel = versionLabels[index],
                            primary = index == 0,
                        )
                    }
                }
                entry.files.forEach { placed += it.index }
            }
        }
    }
    val rest = files.indices.filter { it !in placed }.map { files[it] }
    ordered += buildRawPlaylist(rest).map { it.copy(sectionKey = OTHER_SECTION_KEY, sectionLabel = Section.OTHER.label) }
    return ordered
}

/**
 * 一组里该放哪个版本：沿用正在放的版本（第 3 集切到 720p，第 4 集也放 720p），这一组没有时放体积最大的。
 * 自动连播与选集里点「集」那一行都按这个挑
 */
fun preferredVersion(group: List<PlaylistEntry>, currentVersionLabel: String?): PlaylistEntry =
    group.firstOrNull { !currentVersionLabel.isNullOrEmpty() && it.versionLabel == currentVersionLabel }
        ?: group.first { it.primary }

/**
 * 同组各版本的名字：各自标签里别人没有的那几个（「SDR」「HDR10」「DoVi」，「1080p」「720p」）。
 * 标签分不开时退回体积，再分不开就按先后编号。只有一个成员时为空串
 */
private fun versionLabels(group: List<EntryFile>, files: List<PlaylistEntry>): List<String> {
    if (group.size < 2) return listOf("")
    val tagSets = group.map { file -> file.tags.map { it.text }.toSet() }
    val common = tagSets.reduce { acc, tags -> acc intersect tags }
    val byTags = tagSets.map { (it - common).joinToString(" ") }
    if (byTags.all { it.isNotEmpty() } && byTags.distinct().size == group.size) return byTags
    val bySize = group.map { formatSize(files[it.index].size) }
    if (bySize.distinct().size == group.size) return bySize
    return group.indices.map { "版本 ${it + 1}" }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "${(bytes * 10 / (1L shl 30)) / 10.0} GB"
    else -> "${bytes / (1L shl 20)} MB"
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
