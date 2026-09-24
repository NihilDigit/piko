package dev.piko.shared.state

import dev.piko.shared.naming.AttachmentKind
import dev.piko.shared.naming.EntryFile
import dev.piko.shared.naming.FileRole
import dev.piko.shared.naming.MediaBatch
import dev.piko.shared.naming.MediaEntry
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.MediaTag
import dev.piko.shared.naming.MediaWork
import dev.piko.shared.naming.Section
import dev.piko.shared.naming.TagKind
import dev.piko.shared.naming.WorkKind
import dev.piko.shared.naming.analyzeMediaBatch
import io.github.nihildigit.pikpak.FileStat

/** 解析结果面板里的一行，如「分区」「SP」。 */
data class DriveParsedField(val label: String, val value: String)

/**
 * 一个文件在结构化列表里的显示信息。[title] 是行标题（「01」「25(SP)」「SSIS-123」），
 * [tags] 按显示优先级排好，放不下的由界面从尾部丢弃。[fields] 与 [heading] 供详情面板。
 */
class DriveFileView(
    val title: String,
    val tags: List<String>,
    /** 详情面板的标题：作品名加行标题，如「Steins;Gate 01」。 */
    val heading: String,
    val fields: List<DriveParsedField>,
)

/** 列表里的一项。结构化时在文件之间插入作品头与分区标题，二者都占满整行。 */
sealed interface DriveListItem {
    val key: String

    class WorkHeader(override val key: String, val title: String?, val tags: List<String>) : DriveListItem

    class SectionHeader(
        override val key: String,
        val blockId: String,
        val label: String,
        /** 顶栏副标题与分区菜单用的名字；多部作品并列时带上作品名。 */
        val menuLabel: String,
        val expanded: Boolean,
        /**
         * 作品只有一个分区时，分区标题兼作作品头：写作品名与作品的公共标签。
         * 否则一排「正片」标题各自顶着一个只有一行的作品头。
         */
        val isWork: Boolean = false,
        val tags: List<String> = emptyList(),
    ) : DriveListItem

    /** [view] 为 null 时照原样显示文件名。 */
    class File(val file: FileStat, val view: DriveFileView?) : DriveListItem {
        override val key: String get() = file.id
    }
}

/**
 * 一个分区块：某部作品的某个分区，或把番号、未识别、次要文件各归成的一块。
 * [workKey] 相同的相邻块共用一个作品头。
 */
data class DriveBlock(
    val id: String,
    val label: String,
    val menuLabel: String,
    val defaultExpanded: Boolean,
    val workKey: String?,
    val workTitle: String?,
    val workTags: List<String>,
    val fileIds: List<String>,
)

/**
 * 一个目录的分析结果，与折叠开关、展开状态、原始文件名开关无关，所以可以按目录内容缓存。
 *
 * [blocks] 为空表示没有认出任何作品，界面按原样平铺。[foldedIds] 是可折叠的条目：解析器判为
 * 次要的文件，以及扫图、截图、样片、字体一类的子目录。附件（外挂字幕、音轨、封面）既不在
 * 块里也不在 [foldedIds] 里，它们已化作宿主行上的标签。
 */
class DriveStructure(
    val blocks: List<DriveBlock>,
    val secondaryIds: List<String>,
    val foldedIds: Set<String>,
    val views: Map<String, DriveFileView>,
    /** 挂在别的文件下的附件，id 到宿主 id。 */
    val attachedTo: Map<String, String>,
)

internal const val SECONDARY_BLOCK_ID = "secondary"

/**
 * 分析一个目录。文件只给名字不给目录：网盘里一层就是一批，上级目录名在这里帮倒忙，
 * 「My Pack」会被当成「01.mp4」的作品名。
 */
fun analyzeDriveFolder(files: List<FileStat>): DriveStructure {
    val regular = files.filterNot(FileStat::isFolder)
    val secondaryFolders = files.filter { it.isFolder && isSecondaryFolderName(it.name) }.map { it.id }
    if (regular.isEmpty()) {
        return DriveStructure(emptyList(), emptyList(), secondaryFolders.toSet(), emptyMap(), emptyMap())
    }
    val batch = analyzeMediaBatch(regular.map { MediaFileInput(it.name, it.sizeBytes) })
    val secondaryIds = regular.indices.filter { batch.roles[it] == FileRole.SECONDARY }.map { regular[it].id }
    val attachedTo = buildMap {
        batch.works.forEach { work ->
            work.sections.forEach { section ->
                section.entries.forEach { entry ->
                    entry.files.forEach { file -> file.attachments.forEach { put(regular[it.index].id, regular[file.index].id) } }
                }
            }
        }
    }
    val blocks = if (batch.works.any { it.kind != WorkKind.UNKNOWN }) buildBlocks(batch, regular) else emptyList()
    val views = if (blocks.isEmpty()) emptyMap() else withoutCollidingStandalone(buildViews(batch, regular), batch, regular)
    return DriveStructure(
        blocks = blocks,
        secondaryIds = secondaryIds,
        foldedIds = (secondaryIds + secondaryFolders).toSet(),
        views = views,
        attachedTo = attachedTo,
    )
}

/** 正片、SP、OVA、剧场版默认展开；PV、NCOP、特典、菜单、其他默认收起。 */
private val EXPANDED_BY_DEFAULT = setOf(Section.MAIN, Section.SPECIAL, Section.OVA, Section.MOVIE)

private fun buildBlocks(batch: MediaBatch, files: List<FileStat>): List<DriveBlock> {
    // 独立文件（一部电影、一段没有编号的视频）在解析器里也是一部 SERIES 作品，照剧集那样逐部起作品头，
    // 七个互不相干的视频就是七个与行标题一模一样的标题。它们与未识别文件并成一块，平铺显示
    val (standalone, series) = batch.works.filter { it.kind == WorkKind.SERIES }.partition(::isStandalone)
    val manyWorks = series.size > 1
    val blocks = mutableListOf<DriveBlock>()
    series.forEach { work ->
        val workTags = work.commonTags.map(MediaTag::text)
        work.sections.forEach { section ->
            val label = section.section.label
            blocks += DriveBlock(
                id = "w:${work.key}/${section.section.name}",
                label = label,
                menuLabel = if (manyWorks && work.title != null) "${work.title} $label" else label,
                defaultExpanded = section.section in EXPANDED_BY_DEFAULT,
                workKey = work.key,
                workTitle = work.title,
                workTags = workTags,
                fileIds = section.entries.flatMap { entry -> entry.files.map { files[it.index].id } },
            )
        }
    }
    // 番号一部一作品，逐部起作品头会让一屏全是标题；合成一块，行标题本身就是番号
    val av = batch.works.filter { it.kind == WorkKind.AV }
    if (av.isNotEmpty()) {
        blocks += DriveBlock(
            id = "av", label = "番号", menuLabel = "番号", defaultExpanded = true,
            workKey = null, workTitle = null, workTags = emptyList(),
            fileIds = av.flatMap { work -> work.sections.flatMap { it.entries } }.flatMap { entry -> entry.files.map { files[it.index].id } },
        )
    }
    // 不按解析器的路径顺序，而按用户选的排序：输入顺序就是它
    val others = (batch.works.filter { it.kind == WorkKind.UNKNOWN } + standalone)
        .flatMap { work -> work.sections.flatMap { it.entries } }
        .flatMap { entry -> entry.files.map { it.index } }
        .sorted()
    if (others.isNotEmpty()) {
        blocks += DriveBlock(
            // 只有未识别文件时默认收起：剧集目录里的扫图、字体说明之类，平时不必看
            id = "unknown", label = "其他文件", menuLabel = "其他文件", defaultExpanded = standalone.isNotEmpty(),
            workKey = null, workTitle = null, workTags = emptyList(),
            fileIds = others.map { files[it].id },
        )
    }
    // 整个目录都是默认收起的分区（用户进了 PV/ 或 menu/ 目录），收起就只剩几个标题
    return if (blocks.none { it.defaultExpanded }) blocks.map { it.copy(defaultExpanded = true) } else blocks
}

/**
 * 独立文件的行标题只是把原名清理一遍：去掉日期、清晰度、架构名。同目录里清理后撞名的
 * （archlinux-2026.04.01.iso 与它的 (1)、(2) 都成了「archlinux」），清理就是在丢信息，改回显示原名。
 */
private fun withoutCollidingStandalone(views: Map<String, DriveFileView>, batch: MediaBatch, files: List<FileStat>): Map<String, DriveFileView> {
    val standaloneIds = batch.works.filter { it.kind == WorkKind.SERIES && isStandalone(it) }
        .flatMap { work -> work.sections.flatMap { it.entries } }
        .flatMap { entry -> entry.files.map { files[it.index].id } }
    val colliding = standaloneIds.groupBy { views[it]?.title }.filterKeys { it != null }.values.filter { it.size > 1 }.flatten().toSet()
    return if (colliding.isEmpty()) views else views - colliding
}

private fun isStandalone(work: MediaWork): Boolean {
    val entry = work.sections.singleOrNull()?.entries?.singleOrNull() ?: return false
    return entry.section == Section.MAIN && entry.episode == null && entry.av == null
}

private fun buildViews(batch: MediaBatch, files: List<FileStat>): Map<String, DriveFileView> = buildMap {
    batch.works.filter { it.kind != WorkKind.UNKNOWN }.forEach { work ->
        work.sections.forEach { section ->
            section.entries.forEach { entry ->
                entry.files.forEach { file -> put(files[file.index].id, fileView(work, section.section, entry, file)) }
            }
        }
    }
}

private fun fileView(work: MediaWork, section: Section, entry: MediaEntry, file: EntryFile): DriveFileView {
    val title = entry.label?.let(::stripBrackets) ?: file.name.fileName.substringBeforeLast('.')
    // 番号一部一作品，作品级的公共标签就是这个文件自己的，逐行显示；「中字」「无码」排在最前
    val ownTags = if (work.kind == WorkKind.AV) (file.tags + work.commonTags).distinct().sortedBy { if (it.pinned) 0 else 1 } else file.tags
    val languages = attachmentTags(file)
    val tags = (ownTags.map(MediaTag::text) + languages).distinct()
    val heading = if (work.kind == WorkKind.SERIES && work.title != null && entry.label != null && entry.label != work.title) {
        "${work.title} $title"
    } else {
        title
    }
    return DriveFileView(title = title, tags = tags, heading = heading, fields = parsedFields(work, section, entry, file, languages))
}

/** 外挂字幕与音轨化作宿主行上的标签：「简日」「繁日」，没有语言后缀的记作「字幕」。 */
private fun attachmentTags(file: EntryFile): List<String> = file.attachments.mapNotNull { attachment ->
    when (attachment.kind) {
        AttachmentKind.SUBTITLE -> attachment.language ?: "字幕"
        AttachmentKind.AUDIO_TRACK -> attachment.language?.let { "${it}音轨" } ?: "音轨"
        AttachmentKind.COVER, AttachmentKind.DISC_FILE -> null
    }
}.distinct()

private val TECH_KINDS = setOf(
    TagKind.GROUP, TagKind.RESOLUTION, TagKind.VIDEO_CODEC, TagKind.BIT_DEPTH, TagKind.AUDIO_CODEC,
    TagKind.SOURCE, TagKind.PLATFORM, TagKind.FRAME_RATE, TagKind.AUDIO_LANGUAGE, TagKind.EDITION, TagKind.CENSORSHIP,
)

private fun parsedFields(work: MediaWork, section: Section, entry: MediaEntry, file: EntryFile, attachedLanguages: List<String>): List<DriveParsedField> {
    val name = file.name
    val episode = entry.episode
    val all = (name.tags + work.commonTags).distinct().sortedBy { it.kind.ordinal }
    val subtitles = (all.filter { it.kind == TagKind.SUBTITLES }.map(MediaTag::text) + attachedLanguages).distinct()
    return listOfNotNull(
        work.title?.let { DriveParsedField(if (work.kind == WorkKind.AV) "番号" else "作品", it) },
        DriveParsedField("分区", section.label).takeIf { work.kind == WorkKind.SERIES },
        episode?.let { DriveParsedField("集号", it.shortText) },
        entry.av?.part?.let { DriveParsedField("分段", it) },
        episode?.version?.let { DriveParsedField("版本", it) },
        subtitles.takeIf { it.isNotEmpty() }?.let { DriveParsedField("字幕语言", it.joinToString(" ")) },
        all.filter { it.kind in TECH_KINDS }.takeIf { it.isNotEmpty() }?.let { tags -> DriveParsedField("技术标签", tags.joinToString(" ") { it.text }) },
    )
}

/** 只剩一个括号包着的记号时去掉括号：「[ABC-123]」显示为「ABC-123」。 */
private fun stripBrackets(label: String): String {
    val pairs = mapOf('[' to ']', '【' to '】', '(' to ')')
    val close = pairs[label.firstOrNull()] ?: return label
    if (label.length < 3 || label.last() != close) return label
    val inner = label.substring(1, label.length - 1)
    return if (pairs.keys.any { it in inner }) label else inner.trim()
}

/**
 * 把分析结果、展开状态与折叠开关拼成列表项。
 *
 * 只有一个块时不插分区标题，也不收起：「CDs」目录整块是「其他」，收起就什么都看不到了。
 * 次要文件只在不折叠时出现，排在最后自成一块，默认展开：用户点了「显示全部」就是要看它们。
 */
fun buildDriveItems(
    files: List<FileStat>,
    structure: DriveStructure,
    hideFolded: Boolean,
    isExpanded: (DriveBlock) -> Boolean,
): List<DriveListItem> {
    val byId = files.associateBy { it.id }
    val folders = files.filter { it.isFolder && !(hideFolded && it.id in structure.foldedIds) }
    val secondaryBlock = if (!hideFolded && structure.secondaryIds.isNotEmpty()) {
        DriveBlock(SECONDARY_BLOCK_ID, "次要文件", "次要文件", true, null, null, emptyList(), structure.secondaryIds)
    } else {
        null
    }
    val blocks = structure.blocks + listOfNotNull(secondaryBlock)
    val withHeaders = blocks.size > 1
    val blocksPerWork = blocks.groupingBy { it.workKey }.eachCount()
    return buildList {
        folders.forEach { add(DriveListItem.File(it, null)) }
        var previousWork: String? = null
        blocks.forEach { block ->
            val soleSectionOfWork = withHeaders && block.workKey != null && blocksPerWork[block.workKey] == 1
            val hasWorkInfo = block.workTitle != null || block.workTags.isNotEmpty()
            if (block.workKey != null && block.workKey != previousWork && hasWorkInfo && !soleSectionOfWork) {
                add(DriveListItem.WorkHeader("work:${block.workKey}", block.workTitle, block.workTags))
            }
            previousWork = block.workKey
            val expanded = !withHeaders || isExpanded(block)
            if (soleSectionOfWork) {
                // 正片不必点明；只有 PV 或剧场版的作品要写出来，否则收起时看不出里面是什么
                val workLabel = listOfNotNull(block.workTitle, block.label.takeIf { it != Section.MAIN.label }).joinToString(" ")
                add(
                    DriveListItem.SectionHeader(
                        key = "section:${block.id}", blockId = block.id,
                        label = workLabel.ifEmpty { block.label },
                        menuLabel = workLabel.ifEmpty { block.menuLabel },
                        expanded = expanded, isWork = true, tags = block.workTags,
                    ),
                )
            } else if (withHeaders) {
                add(DriveListItem.SectionHeader("section:${block.id}", block.id, block.label, block.menuLabel, expanded))
            }
            if (expanded) {
                block.fileIds.forEach { id -> byId[id]?.let { add(DriveListItem.File(it, structure.views[id])) } }
            }
        }
    }
}
