package dev.piko.shared.state

import dev.piko.shared.data.readableSize
import dev.piko.shared.data.DriveLibrary
import io.github.nihildigit.pikpak.FileStat

/**
 * 网盘页里内容来自进程级会话、不经网络的位置（DriveLibrary.isSession）列出的东西：每组一个分区标题，下面是行。
 * 选中、框选、键盘与右键菜单都是网盘页的，这里只给行。
 */
interface SessionListing {
    val usesFolderStructure: Boolean get() = false
    fun decorate(item: DriveListItem.File): DriveListItem.File = item
    fun filesInFolder(folderId: String): List<FileStat>? = null
    /** 列出的文件，去重，供按 ID 找回、全选与空态判断。 */
    val files: List<FileStat>

    /** 列表项。[isExpanded] 按分区 ID 问展开没有，收起的分区只列标题。 */
    fun items(isExpanded: (blockId: String) -> Boolean): List<DriveListItem>
}

class DuplicateDirectoryListing(
    private val finder: DuplicateFinderState,
    private val folderId: String,
    private val kind: () -> DuplicateKind = { DuplicateKind.IDENTICAL },
) : SessionListing {
    override val usesFolderStructure: Boolean get() = true
    override val files: List<FileStat> get() = finder.directoryFiles(folderId, kind())
    override fun filesInFolder(folderId: String): List<FileStat> =
        finder.directoryFiles(if (folderId == DriveLibrary.DUPLICATES.id) finder.root.id else folderId, kind())
    override fun items(isExpanded: (blockId: String) -> Boolean): List<DriveListItem> =
        files.map { DriveListItem.File(it, null) }

    override fun decorate(item: DriveListItem.File): DriveListItem.File {
        if (item.file.isFolder) return item
        val view = item.view
        val kept = item.file.id in finder.keptIds
        val tags = listOfNotNull("建议保留".takeIf { kept }) + view?.tags.orEmpty()
        return DriveListItem.File(item.file, DriveFileView(
            title = view?.title ?: item.file.name,
            tags = tags.distinct(),
            code = view?.code,
            resolution = view?.resolution,
            heading = view?.heading ?: item.file.name,
            fields = view?.fields.orEmpty(),
        ), item.key)
    }
}

enum class DuplicateKeepRule(val label: String) {
    RECOMMENDED("智能推荐"),
    NEWEST("保留最新"),
    OLDEST("保留最旧"),
    LARGEST("保留最大"),
    SMALLEST("保留最小"),
    SHORTEST_PATH("保留最浅路径"),
    SHORTEST_NAME("保留最短文件名"),
}

/** 列表与批量勾选共用同一个顺序；目录置顶优先于保留规则。 */
fun DuplicateGroup.orderedRows(rule: DuplicateKeepRule, focusFolder: String? = null): List<DuplicateRow> {
    // 默认顺序由分组器决定：相同文件按保留建议，版本按大小降序。
    if (rule == DuplicateKeepRule.RECOMMENDED) {
        return rows.sortedByDescending { focusFolder != null && it.file.folderPath == focusFolder }
    }
    val order = when (rule) {
        DuplicateKeepRule.RECOMMENDED -> KEEP_ORDER
        DuplicateKeepRule.NEWEST -> compareByDescending<DuplicateFile> { it.modifiedTime.ifEmpty { it.createdTime } }
        DuplicateKeepRule.OLDEST -> compareBy<DuplicateFile> { it.modifiedTime.ifEmpty { it.createdTime } }
        DuplicateKeepRule.LARGEST -> compareByDescending<DuplicateFile> { it.size }
        DuplicateKeepRule.SMALLEST -> compareBy<DuplicateFile> { it.size }
        DuplicateKeepRule.SHORTEST_PATH -> compareBy<DuplicateFile> { it.folderPath.count { char -> char == '/' } }
            .thenBy { it.path.length }
        DuplicateKeepRule.SHORTEST_NAME -> compareBy<DuplicateFile> { it.name.length }
    }.then(KEEP_ORDER)
    return rows.sortedWith(compareByDescending<DuplicateRow> { focusFolder != null && it.file.folderPath == focusFolder }
        .thenComparator { a, b -> order.compare(a.file, b.file) })
}

/** 同一文件可能同时出现在相同文件与版本组，行 key 带组身份，选中仍按文件 ID。 */
class DuplicateListing(
    private val finder: DuplicateFinderState,
    private val kind: DuplicateKind? = null,
    private val keepRule: () -> DuplicateKeepRule = { DuplicateKeepRule.RECOMMENDED },
    private val focusFolder: () -> String? = { null },
    private val kindProvider: () -> DuplicateKind? = { kind },
) : SessionListing {
    private val groups: List<DuplicateGroup>
        get() = when (kindProvider()) {
            DuplicateKind.IDENTICAL -> finder.report.identical
            DuplicateKind.VERSIONS -> finder.report.versions
            null -> finder.report.identical + finder.report.versions
        }

    override val files: List<FileStat>
        get() = groups
            .flatMap { group -> group.rows.mapNotNull { finder.fileStat(it.file.id) } }
            .distinctBy { it.id }

    override fun items(isExpanded: (blockId: String) -> Boolean): List<DriveListItem> = buildList {
        for (group in groups) {
            val blockId = "dup:${group.kind}:${group.key}"
            val expanded = isExpanded(blockId)
            add(DriveListItem.SectionHeader(blockId, blockId, label(group), group.title, expanded))
            if (!expanded) continue
            val rule = keepRule()
            val orderedRows = group.orderedRows(rule, focusFolder())
            val showKeepSuggestion = group.kind == DuplicateKind.IDENTICAL || rule != DuplicateKeepRule.RECOMMENDED
            for (row in orderedRows) {
                val file = finder.fileStat(row.file.id) ?: continue
                add(DriveListItem.File(file, view(file, row, showKeepSuggestion && row == orderedRows.firstOrNull()), key = "$blockId/${file.id}"))
            }
        }
    }

    private fun label(group: DuplicateGroup): String = when (group.kind) {
        DuplicateKind.IDENTICAL -> "${group.title}：${group.rows.size} 份相同，可腾出 ${readableSize(group.reclaimableBytes)}"
        DuplicateKind.VERSIONS -> "${group.title}：${group.rows.size} 个版本，共 ${readableSize(group.totalBytes)}"
    }

    // 标签写这一份与同组其余几份的区别：字幕组、分辨率这些，以及默认留哪一份
    private fun view(file: FileStat, row: DuplicateRow, isKept: Boolean): DriveFileView {
        val tags = buildList {
            if (isKept) add("建议保留")
            addAll(row.details)
            if (row.sameCopies > 0) add("另有 ${row.sameCopies} 份相同")
        }
        return DriveFileView(title = file.name, tags = tags, heading = file.name, fields = emptyList())
    }
}
