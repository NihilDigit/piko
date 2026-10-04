package dev.piko.shared.state

import dev.piko.data.repository.fileCategory
import dev.piko.shared.data.ChildFile
import dev.piko.shared.naming.AttachmentKind
import dev.piko.shared.naming.EntryFile
import dev.piko.shared.naming.FileKind as NamingFileKind
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
import dev.piko.shared.naming.distinctFiles
import dev.piko.shared.naming.toFileKind
import dev.piko.shared.naming.versionsOfPrimary
import dev.piko.shared.naming.workKeyOf
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
    /** 番号芯片，排在标签前面；标题就是番号或没有番号时为 null。 */
    val code: String? = null,
    /** 清晰度，也在 [tags] 里。封面卡片把它单独放在右下角。 */
    val resolution: String? = null,
    /** 详情面板的标题：作品名加行标题，如「Steins;Gate 01」。 */
    val heading: String,
    val fields: List<DriveParsedField>,
)

internal fun DriveFileView.withTitle(title: String) = DriveFileView(title, tags, code, resolution, heading, fields)

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
    /** [key] 平常就是文件 ID；查找重复里同一个文件可能同时在两组，要带上组名区分。 */
    class File(val file: FileStat, val view: DriveFileView?, override val key: String = file.id) : DriveListItem
}

/**
 * 从列表里拿掉 [hide] 为真的文件，文件全被拿掉的分区与作品连标题一起去掉，免得留下空标题。
 *
 * 收起的分区在列表里本来就只有标题、没有文件，它要留着：只看「标题后面有没有文件」会把它误删。
 * 所以按原列表判断，只有原本有文件、而且全被拿掉的分区才去掉标题。作品头之后的去留在分区处理完后再看：
 * 到下一个作品头之前什么都不剩的，去掉。
 */
internal fun hideFiles(items: List<DriveListItem>, hide: (FileStat) -> Boolean): List<DriveListItem> {
    val kept = ArrayList<DriveListItem>(items.size)
    var index = 0
    while (index < items.size) {
        val item = items[index]
        if (item is DriveListItem.SectionHeader) {
            var end = index + 1
            while (end < items.size && items[end] is DriveListItem.File) end++
            val files = items.subList(index + 1, end)
            val visible = files.filterNot { hide((it as DriveListItem.File).file) }
            if (files.isEmpty() || visible.isNotEmpty()) {
                kept += item
                kept += visible
            }
            index = end
        } else {
            if (item !is DriveListItem.File || !hide(item.file)) kept += item
            index++
        }
    }
    return kept.filterIndexed { i, item ->
        item !is DriveListItem.WorkHeader || kept.getOrNull(i + 1).let { it != null && it !is DriveListItem.WorkHeader }
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
 * 次要的文件，以及扫图、截图、样片、字体一类的子目录。附件（外挂字幕、音轨、封面）不在 [foldedIds] 里，
 * 在宿主行上化作标签；字幕与音轨另在末尾各成一个默认收起的块，见 attachmentBlocks。
 */
class DriveStructure(
    val blocks: List<DriveBlock>,
    val secondaryIds: List<String>,
    val foldedIds: Set<String>,
    val views: Map<String, DriveFileView>,
    /** 挂在别的文件下的附件，id 到宿主 id。 */
    val attachedTo: Map<String, String>,
)

/**
 * 网盘文件交给解析器时带上服务端按 mime 判断的类型：名字没有扩展名的视频（「… - 12 […][END]」），
 * 光看名字会被当成说明文件归进次要文件。
 */
internal fun FileStat.toMediaFileInput(path: String = name): MediaFileInput = MediaFileInput(path, sizeBytes, fileCategory().toFileKind())

/** 记下的文件夹内容交给解析器，同样带上服务端给的类型。 */
internal fun ChildFile.toMediaFileInput(): MediaFileInput = MediaFileInput(name, 0, category?.toFileKind())

internal const val SECONDARY_BLOCK_ID = "secondary"
private const val OTHERS_BLOCK_ID = "unknown"
private const val ATTACHMENT_BLOCK_PREFIX = "attached:"
private const val FLAT_BLOCK_MAX = 2

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
    val batch = analyzeMediaBatch(regular.map { it.toMediaFileInput() })
    val secondaryIds = regular.indices.filter { batch.roles[it] == FileRole.SECONDARY }.map { regular[it].id }
    val attachedTo = buildMap {
        batch.works.forEach { work ->
            work.sections.forEach { section ->
                section.entries.forEach { entry ->
                    entry.files.forEach { file -> file.attachments.forEach { put(regular[it.index].id, regular[file.index].id) } }
                    // 同一内容的其他版本挂在体积最大的那个下面，列表里只占一行，点开播放最大的
                    entry.versionsOfPrimary().forEach { put(regular[it.index].id, regular[entry.primary.index].id) }
                }
            }
        }
    }
    val workBlocks = if (batch.works.any { it.kind != WorkKind.UNKNOWN }) buildBlocks(batch, regular) else emptyList()
    // 没认出作品时整个目录平铺，附件本来就各占一行，不必另起一栏
    val blocks = if (workBlocks.isEmpty()) workBlocks else workBlocks + attachmentBlocks(batch, regular)
    val views = if (blocks.isEmpty()) emptyMap() else withoutCollidingStandalone(buildViews(batch, regular), batch, regular)
    return DriveStructure(
        blocks = blocks,
        secondaryIds = secondaryIds,
        foldedIds = (secondaryIds + secondaryFolders).toSet(),
        views = views,
        attachedTo = attachedTo,
    )
}

/**
 * 外挂字幕与音轨各成一栏，默认收起。它们在宿主行上化作「简日」一类的标签，原来就只剩这个标签：
 * 不在任何作品块里，也不算次要文件，「显示全部文件」放不出来，单独下载、改名、删除都无从下手。
 * 不并进宿主的操作面板：那样一个字幕要先找到它挂在哪一集下面，而这一栏点开就是普通的文件行。
 * 封面与原盘的成员文件不列，前者是图片、后者属于原盘目录，不是要单独拿出来处理的东西。
 */
private fun attachmentBlocks(batch: MediaBatch, files: List<FileStat>): List<DriveBlock> {
    val byKind = batch.works.asSequence()
        .flatMap { it.sections }.flatMap { it.entries }.flatMap { it.files }.flatMap { it.attachments }
        .groupBy({ it.kind }, { it.index })
    return listOfNotNull(
        byKind[AttachmentKind.SUBTITLE]?.let { attachmentBlock("${ATTACHMENT_BLOCK_PREFIX}subtitle", "外挂字幕", it, files) },
        byKind[AttachmentKind.AUDIO_TRACK]?.let { attachmentBlock("${ATTACHMENT_BLOCK_PREFIX}audio", "外挂音轨", it, files) },
    )
}

private fun attachmentBlock(id: String, label: String, indices: List<Int>, files: List<FileStat>) =
    DriveBlock(id, label, label, false, null, null, emptyList(), indices.distinct().sorted().map { files[it].id })

private val CONTENT_KINDS =setOf(NamingFileKind.VIDEO, NamingFileKind.IMAGE, NamingFileKind.AUDIO, NamingFileKind.DISC_IMAGE)

/** 正片、SP、OVA、剧场版默认展开；PV、NCOP、特典、菜单、其他默认收起。 */
private val EXPANDED_BY_DEFAULT = setOf(Section.MAIN, Section.SPECIAL, Section.OVA, Section.MOVIE)

private fun buildBlocks(batch: MediaBatch, files: List<FileStat>): List<DriveBlock> {
    // 独立文件（一部电影、一段没有编号的视频）在解析器里也是一部 SERIES 作品，照剧集那样逐部起作品头，
    // 七个互不相干的视频就是七个与行标题一模一样的标题。它们与未识别文件并成一块，平铺显示
    val (standalone, series) = batch.works.filter { it.kind == WorkKind.SERIES }.partition(::isStandalone)
    val manyWorks = series.size > 1
    val blocks = mutableListOf<DriveBlock>()
    // 没有作品名的系列排在最前：它不起标题（见 buildDriveItems），排在别的作品后面，它的行就像接在那部作品下面
    series.sortedBy { it.title != null }.forEach { work ->
        val workTags = work.commonTags.map(MediaTag::text)
        val workTitle = displayTitle(work)
        work.sections.forEach { section ->
            val label = section.section.label
            blocks += DriveBlock(
                id = "w:${work.key}/${section.section.name}",
                label = label,
                menuLabel = if (manyWorks && workTitle != null) "$workTitle $label" else label,
                defaultExpanded = section.section in EXPANDED_BY_DEFAULT,
                workKey = work.key,
                workTitle = workTitle,
                workTags = workTags,
                fileIds = section.entries.flatMap { entry -> entry.distinctFiles().map { files[it.index].id } },
            )
        }
    }
    // 番号一部一作品，逐部起作品头会让一屏全是标题；合成一块，行标题本身就是番号
    val av = batch.works.filter { it.kind == WorkKind.AV }
    if (av.isNotEmpty()) {
        blocks += DriveBlock(
            id = "av", label = "番号", menuLabel = "番号", defaultExpanded = true,
            workKey = null, workTitle = null, workTags = emptyList(),
            fileIds = av.flatMap { work -> work.sections.flatMap { it.entries } }.flatMap { entry -> entry.distinctFiles().map { files[it.index].id } },
        )
    }
    // 不按解析器的路径顺序，而按用户选的排序：输入顺序就是它
    val others = (batch.works.filter { it.kind == WorkKind.UNKNOWN } + standalone)
        .flatMap { work -> work.sections.flatMap { it.entries } }
        .flatMap { entry -> entry.distinctFiles().map { it.index } }
        .sorted()
    if (others.isNotEmpty()) {
        // 只剩文档、压缩包一类时默认收起：动画发布里认不出的零散文件多是说明与字体。
        // 有图片、视频或音频就展开，个人目录里的照片本身就是内容
        val hasMedia = others.any { batch.parsed[it].fileKind in CONTENT_KINDS }
        blocks += DriveBlock(
            id = OTHERS_BLOCK_ID, label = "其他文件", menuLabel = "其他文件", defaultExpanded = standalone.isNotEmpty() || hasMedia,
            workKey = null, workTitle = null, workTags = emptyList(),
            fileIds = others.map { files[it].id },
        )
    }
    // 整个目录都是默认收起的分区（用户进了 PV/ 或 menu/ 目录），收起就只剩几个标题
    return if (blocks.none { it.defaultExpanded }) blocks.map { it.copy(defaultExpanded = true) } else blocks
}

/**
 * 没有集号的行，标题只是把原名清理一遍：去掉日期、清晰度、架构名、UUID。同目录里清理后撞名的
 * （archlinux-2026.04.01.iso 与它的 (1)、(2) 都成了「archlinux」），清理就是在丢信息，改回显示原名。
 * 不只看独立作品：「旅行 IMG_4100」一簇定为作品「旅行」后，「旅行 <UUID>」这样同名前缀加 UUID 的几个文件
 * 也并了进去，行标题都是作品名
 */
private fun withoutCollidingStandalone(views: Map<String, DriveFileView>, batch: MediaBatch, files: List<FileStat>): Map<String, DriveFileView> {
    val standaloneIds = batch.works.filter { it.kind == WorkKind.SERIES }
        .flatMap { work -> work.sections.flatMap { it.entries } }
        .filter { entry -> entry.episode == null && entry.av == null }
        .flatMap { entry -> entry.files.map { files[it.index].id } }
    val colliding = standaloneIds.groupBy { views[it]?.title }.filterKeys { it != null }.values.filter { it.size > 1 }.flatten().toSet()
    return if (colliding.isEmpty()) views else views - colliding
}

/** 一行文件的标签。番号一部一作品，作品级的公共标签就是这个文件自己的，逐行显示；「中字」「无码」排在最前。 */
internal fun rowTags(work: MediaWork, file: EntryFile): List<MediaTag> =
    if (work.kind == WorkKind.AV) (file.tags + work.commonTags).distinct().sortedBy { if (it.pinned) 0 else 1 } else file.tags

/**
 * 作品头上的作品名。解析器把季号拆进了集号，第二季的目录里作品头只剩「Yuru Camp」，与第一季同名；
 * 正片全是同一季（第二季起）时拼回去
 */
internal fun displayTitle(work: MediaWork): String? {
    val title = work.title ?: return null
    val season = work.sections.firstOrNull { it.section == Section.MAIN }?.entries?.map { it.episode?.season }?.distinct()?.singleOrNull()
    return if (season != null && season >= 2) "$title Season $season" else title
}

private fun isStandalone(work: MediaWork): Boolean {
    val entry = work.sections.singleOrNull()?.entries?.singleOrNull() ?: return false
    return entry.section == Section.MAIN && entry.episode == null && entry.av == null
}

private fun buildViews(batch: MediaBatch, files: List<FileStat>): Map<String, DriveFileView> = buildMap {
    batch.works.filter { it.kind != WorkKind.UNKNOWN }.forEach { work ->
        val repeated = repeatedEpisodeTitles(work)
        work.sections.forEach { section ->
            section.entries.forEach { entry ->
                entry.files.forEach { file -> put(files[file.index].id, fileView(work, section.section, entry, file, repeated)) }
            }
        }
    }
}

private val HASHTAG = Regex("""\s*#\S+""")

/** 同一作品里两集以上共有的集标题：「01.某作品.mp4」「02.某作品.mp4」编号后面的是作品名，不是描述。 */
private fun repeatedEpisodeTitles(work: MediaWork): Set<String> =
    work.sections.flatMap { it.entries }.mapNotNull { entry -> entry.primary.name.episodeTitle?.let(::workKeyOf) }
        .groupingBy { it }.eachCount().filterValues { it > 1 }.keys

/**
 * 集号后面的描述接在行标题里：「16.【…】描述」只显示「16」就认不出是哪段。动画的文件名多半只有作品名与集号，
 * 没有这一段；有的话只收真正的描述，作品名或各集共有的文字不算
 */
private fun episodeDescription(work: MediaWork, entry: MediaEntry, file: EntryFile, repeated: Set<String>): String? {
    if (entry.episode == null) return null
    // 话题标签是给平台检索用的，不是描述。去掉后只剩数字的也不算：「IMG_1234_5678」集号后的 5678 是相机的编号
    val text = file.name.episodeTitle?.replace(HASHTAG, "")?.trim()?.takeIf { title -> title.any(Char::isLetter) } ?: return null
    val key = workKeyOf(text)
    if (key.isEmpty() || key in repeated || work.title?.let(::workKeyOf) == key) return null
    return text
}

private fun fileView(work: MediaWork, section: Section, entry: MediaEntry, file: EntryFile, repeated: Set<String>): DriveFileView {
    val av = file.name.av?.takeIf { work.kind == WorkKind.AV }
    val avTitle = av?.displayTitle()
    val numbered = entry.label?.let(::stripBrackets)?.let { label -> listOfNotNull(label, episodeDescription(work, entry, file, repeated)).joinToString(" ") }
    val title = avTitle ?: numbered ?: file.name.fileName.substringBeforeLast('.')
    val ownTags = rowTags(work, file)
    val languages = attachmentTags(file)
    val versionCount = if (file == entry.primary) entry.versionsOfPrimary().size + 1 else 1
    val versions = listOfNotNull("$versionCount 版本".takeIf { versionCount > 1 })
    val tags = (ownTags.map(MediaTag::text) + languages + versions).distinct()
    val heading = if (work.kind == WorkKind.SERIES && work.title != null && entry.label != null && entry.label != work.title) {
        "${work.title} $title"
    } else {
        title
    }
    val resolution = ownTags.firstOrNull { it.kind == TagKind.RESOLUTION }?.text
    return DriveFileView(
        title = title, tags = tags, code = av?.chip, resolution = resolution, heading = heading,
        fields = parsedFields(work, section, entry, file, languages),
    )
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
        // 行里的片名会被截断，详情里给全文
        name.av?.title?.let { DriveParsedField("片名", it) },
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
    // 「其他文件」与至多两个文件的块不起标题，平铺在最前：为一两个文件起一个标题只是多一行，
    // 放在最前也就不会被看成上一部作品的内容。拆散的剧集行标题改用「作品名 集号」，没了作品头，
    // 光一个「01」认不出是哪部
    // 没有作品名、只有正片的系列本来就不起标题（写出来只是「正片」），一并平铺
    val worksWithOneBlock = structure.blocks.groupingBy { it.workKey }.eachCount()
    val (flat, grouped) = structure.blocks.partition { block ->
        val untitled = block.workKey != null && block.workTitle == null && block.label == Section.MAIN.label && worksWithOneBlock[block.workKey] == 1
        // 字幕与音轨那一栏只有一两个文件也留着标题：平铺到最前，就成了与正片并列的条目
        val attachments = block.id.startsWith(ATTACHMENT_BLOCK_PREFIX)
        !attachments && (block.id == OTHERS_BLOCK_ID || block.fileIds.size <= FLAT_BLOCK_MAX || untitled)
    }
    val flatIds = flat.flatMap { it.fileIds }.sortedBy { id -> files.indexOfFirst { it.id == id } }
    val flatWorks = flat.filter { it.id != OTHERS_BLOCK_ID }.flatMap { it.fileIds }.toSet()
    val blocks = grouped + listOfNotNull(secondaryBlock)
    val withHeaders = blocks.size > 1 || (blocks.isNotEmpty() && flatIds.isNotEmpty())
    val blocksPerWork = blocks.groupingBy { it.workKey }.eachCount()
    return buildList {
        folders.forEach { add(DriveListItem.File(it, null)) }
        flatIds.forEach { id ->
            val view = structure.views[id]?.let { if (id in flatWorks) it.withTitle(it.heading) else it }
            byId[id]?.let { add(DriveListItem.File(it, view)) }
        }
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
