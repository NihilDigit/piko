package dev.piko.shared.state

import dev.piko.data.repository.FileNameSanitizer
import dev.piko.shared.naming.AttachmentKind
import dev.piko.shared.naming.EntryFile
import dev.piko.shared.naming.FileKind
import dev.piko.shared.naming.MediaBatch
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.MediaWork
import dev.piko.shared.naming.Section
import dev.piko.shared.naming.SecondaryReason
import dev.piko.shared.naming.WorkKind
import dev.piko.shared.naming.WorkSection
import dev.piko.shared.naming.analyzeMediaBatch

sealed interface InstantNode {
    val key: String
}

/**
 * 可勾选的一行：一个视频（或其他正文文件）连同挂在它下面的字幕、音轨、封面、原盘文件。
 * [indices] 是勾这一行时一并勾上的全部文件，首项是 [index] 本身。
 * [label] 是解析器给的短标签；解析器没认出来的与次要文件用原始文件名。
 */
data class InstantRow(
    val index: Int,
    val label: String,
    val tags: List<String>,
    val indices: List<Int>,
    val subtitleCount: Int,
    val audioTrackCount: Int,
    val bytes: Long,
) : InstantNode {
    override val key: String get() = "f:$index"
}

/** 作品、分区、「其他」或「其他文件」。[tags] 是整组共有的标签，只在组行上显示一次。 */
data class InstantGroup(
    override val key: String,
    val title: String,
    val tags: List<String>,
    val children: List<InstantNode>,
    val defaultExpanded: Boolean,
) : InstantNode {
    val rows: List<InstantRow> = children.flatMap { child ->
        when (child) {
            is InstantRow -> listOf(child)
            is InstantGroup -> child.rows
        }
    }
    val indices: List<Int> = rows.flatMap { it.indices }
}

data class InstantTreeRow(val key: String, val depth: Int, val node: InstantNode)

/**
 * 磁链面板的文件树，由 [buildInstantTree] 一次算好，之后只随展开状态展平。
 *
 * [folderName] 是多项保存时新建文件夹的默认名：主作品的标题，认不出时退回种子名。
 */
class InstantTree(
    val roots: List<InstantNode>,
    val defaultSelection: Set<Int>,
    val folderName: String,
) {
    val rows: List<InstantRow> = roots.flatMap { node ->
        when (node) {
            is InstantRow -> listOf(node)
            is InstantGroup -> node.rows
        }
    }

    private val rowByIndex: Map<Int, InstantRow> = rows.associateBy { it.index }

    /** 以 [index] 为主文件的行；字幕等附件不是行，返回 null。 */
    fun rowOf(index: Int): InstantRow? = rowByIndex[index]

    fun flatten(isExpanded: (InstantGroup) -> Boolean): List<InstantTreeRow> = buildList {
        fun visit(node: InstantNode, depth: Int) {
            add(InstantTreeRow(node.key, depth, node))
            if (node is InstantGroup && isExpanded(node)) node.children.forEach { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }
    }
}

/**
 * 按文件名解析器的「作品 → 分区 → 条目」组织面板。大合集有上千个文件，解析要几秒，调用方应放在后台线程。
 *
 * 形状：
 * - 每部作品一组，组行带作品共有的标签；只有一个分区时不再套分区一层，只有一个条目时连作品一层也不要，
 *   直接是一行（单文件种子、番号合集里的每个番号）；
 * - 解析器认不出的放在最后的「其他」组，行上是原始文件名；整批都认不出时不套这一层；
 * - 次要文件（广告、字体、sample、扫图……）放进收起的「其他文件」，不止一种原因时按原因再分组。
 */
fun buildInstantTree(files: List<MediaFileInput>, resourceName: String): InstantTree {
    val batch = analyzeMediaBatch(files)
    return InstantTreeBuilder(files, batch).build(resourceName)
}

private class InstantTreeBuilder(private val files: List<MediaFileInput>, private val batch: MediaBatch) {
    private val placed = HashSet<Int>()

    fun build(resourceName: String): InstantTree {
        val known = batch.works.filter { it.kind != WorkKind.UNKNOWN }
        // 作品一多，全部展开时第一屏只看得到第一部的头几集，这时一律收起，列表就是一张目录
        val expandWorks = known.count { !it.isSingleEntry } <= MAX_EXPANDED_WORKS
        val roots = mutableListOf<InstantNode>()
        known.forEach { work -> roots += workNode(work, resourceName, expandWorks) }

        val unknownRows = batch.works.filter { it.kind == WorkKind.UNKNOWN }
            .flatMap { work -> work.sections.flatMap { it.entries } }
            .flatMap { entry -> entry.files.map { contentRow(it, label = null, extraTags = emptyList()) } }
        val secondaryIndices = batch.secondary.map { it.index }.toSet()
        // 解析器理应把每个文件放进作品、附件或次要文件之一；万一漏了，也要让它出现在面板上，否则勾不到也存不了
        val strays = files.indices.filter { it !in placed && it !in secondaryIndices }.map { rawRow(it) }
        val otherRows = unknownRows + strays
        if (roots.isEmpty()) {
            roots += otherRows
        } else if (otherRows.isNotEmpty()) {
            roots += InstantGroup(OTHER_KEY, Section.OTHER.label, emptyList(), otherRows, defaultExpanded = false)
        }

        secondaryNode()?.let { roots += it }

        val selection = batch.defaultSelection().ifEmpty { files.indices.toSet() }
        return InstantTree(roots, selection, folderNameOf(known, resourceName))
    }

    private val MediaWork.isSingleEntry: Boolean
        get() = sections.size == 1 && sections.single().entries.size == 1

    private fun workNode(work: MediaWork, resourceName: String, expand: Boolean): List<InstantNode> {
        val commonTags = work.commonTags.map { it.text }
        if (work.isSingleEntry) {
            // 单独成行时没有组行来放作品名与公共标签，并回行里。番号与剧场版的行标题就是作品名，不重复
            val entry = work.sections.single().entries.single()
            val label = entry.label?.let { label ->
                val title = work.title
                if (title == null || label.contains(title, ignoreCase = true)) label else "$title $label"
            }
            return entry.files.map { contentRow(it, label, extraTags = commonTags) }
        }
        val title = work.title ?: resourceName
        val workKey = "w:" + work.key
        val children: List<InstantNode> = if (work.sections.size == 1) {
            sectionRows(work.sections.single())
        } else {
            work.sections.map { section ->
                InstantGroup(
                    key = workKey + "|" + section.section.name,
                    title = section.section.label,
                    tags = emptyList(),
                    children = sectionRows(section),
                    defaultExpanded = section.section in EXPANDED_SECTIONS,
                )
            }
        }
        val onlySection = work.sections.singleOrNull()?.section
        return listOf(
            InstantGroup(
                key = workKey,
                title = title,
                tags = commonTags,
                children = children,
                defaultExpanded = expand && (onlySection == null || onlySection in EXPANDED_SECTIONS),
            ),
        )
    }

    private fun sectionRows(section: WorkSection): List<InstantRow> =
        section.entries.flatMap { entry -> entry.files.map { contentRow(it, entry.label, extraTags = emptyList()) } }

    private fun contentRow(file: EntryFile, label: String?, extraTags: List<String>): InstantRow {
        val indices = listOf(file.index) + file.attachments.map { it.index }
        placed += indices
        val shortLabel = label?.let(::withoutBrackets)
        return InstantRow(
            index = file.index,
            label = shortLabel ?: fileNameOf(file.index),
            // 标签行放不下时从后往前丢，有区分度的排前面，作品公共标签垫底
            tags = file.tags.map { it.text } + listOfNotNull(file.languageTag) + extraTags,
            indices = indices,
            subtitleCount = file.attachments.count { it.kind == AttachmentKind.SUBTITLE },
            audioTrackCount = file.attachments.count { it.kind == AttachmentKind.AUDIO_TRACK },
            bytes = indices.sumOf { files[it].size },
        )
    }

    /** 字幕包里同一集的简日、繁日两份是同一条目下的两个文件，标签完全相同，只有语言能区分。 */
    private val EntryFile.languageTag: String?
        get() = name.language?.takeIf { name.fileKind == FileKind.SUBTITLE || name.fileKind == FileKind.AUDIO }

    private fun rawRow(index: Int): InstantRow {
        placed += index
        return InstantRow(
            index = index,
            label = fileNameOf(index),
            tags = emptyList(),
            indices = listOf(index),
            subtitleCount = 0,
            audioTrackCount = 0,
            bytes = files[index].size,
        )
    }

    private fun secondaryNode(): InstantGroup? {
        if (batch.secondary.isEmpty()) return null
        val byReason = batch.secondary.groupBy { it.reason }.toList().sortedBy { (reason, _) -> reason.ordinal }
        val single = byReason.singleOrNull()
        if (single != null) {
            // 只有一种原因时写在组行的标签上，不再套一层
            return InstantGroup(
                key = SECONDARY_KEY,
                title = SECONDARY_TITLE,
                tags = listOf(single.first.label),
                children = single.second.map { rawRow(it.index) },
                defaultExpanded = false,
            )
        }
        return InstantGroup(
            key = SECONDARY_KEY,
            title = SECONDARY_TITLE,
            tags = emptyList(),
            children = byReason.map { (reason, members) ->
                InstantGroup(
                    key = SECONDARY_KEY + "|" + reason.name,
                    title = reason.label,
                    tags = emptyList(),
                    children = members.map { rawRow(it.index) },
                    defaultExpanded = false,
                )
            },
            defaultExpanded = false,
        )
    }

    /**
     * 主作品取正片体积最大的一部。它若只有一个条目而同批还有别的作品，说明这是番号合集或几部作品拼成的包，
     * 拿其中一部的名字命名整包文件夹是错的，退回种子名。
     */
    private fun folderNameOf(known: List<MediaWork>, resourceName: String): String {
        val fallback = FileNameSanitizer.sanitize(resourceName)
        val main = known.filter { it.title != null }.maxByOrNull { work ->
            val sections = work.sections.filter { it.section == Section.MAIN }.ifEmpty { work.sections }
            sections.flatMap { it.entries }.flatMap { it.files }.sumOf { files[it.index].size }
        }
        if (main == null || main.isSingleEntry && known.size > 1) return fallback
        return main.title?.let(FileNameSanitizer::sanitize)?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun fileNameOf(index: Int): String = files[index].path.substringAfterLast('/')

    private companion object {
        const val MAX_EXPANDED_WORKS = 3
        const val OTHER_KEY = "other"
        const val SECONDARY_KEY = "secondary"
        const val SECONDARY_TITLE = "其他文件"

        /** 默认展开的分区，其余（PV、NCOP/NCED、特典、菜单、其他）收起。 */
        val EXPANDED_SECTIONS = setOf(Section.MAIN, Section.SPECIAL, Section.OVA, Section.MOVIE)

        /** 只去掉包住整个标签的一对方括号：「[01]」显示为「01」，「[A][B]」原样保留。 */
        fun withoutBrackets(label: String): String =
            if (label.length > 2 && label.first() == '[' && label.last() == ']' && label.indexOf(']') == label.lastIndex) {
                label.substring(1, label.lastIndex).trim()
            } else {
                label
            }
    }
}

private val SecondaryReason.label: String
    get() = when (this) {
        SecondaryReason.AD -> "广告"
        SecondaryReason.SAMPLE -> "样片"
        SecondaryReason.SCANS -> "扫图"
        SecondaryReason.SCREENSHOTS -> "截图"
        SecondaryReason.FONTS -> "字体"
        SecondaryReason.INFO -> "说明"
        SecondaryReason.EXTRA -> "附带文件"
    }
