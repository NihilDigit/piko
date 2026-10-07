package dev.piko.shared.state

import dev.piko.shared.data.readableSize
import io.github.nihildigit.pikpak.FileStat

/**
 * 网盘页里内容来自进程级会话、不经网络的位置（DriveLibrary.isSession）列出的东西：每组一个分区标题，下面是行。
 * 选中、框选、键盘与右键菜单都是网盘页的，这里只给行。
 */
interface SessionListing {
    /** 列出的文件，去重，供按 ID 找回、全选与空态判断。 */
    val files: List<FileStat>

    /** 列表项。[isExpanded] 按分区 ID 问展开没有，收起的分区只列标题。 */
    fun items(isExpanded: (blockId: String) -> Boolean): List<DriveListItem>
}

/**
 * 查找重复的结果。同一个文件可能既在完全相同的组里、又代表版本组里的一行，列表项的 key 因此带上组；
 * 选中仍按文件，两处是同一个勾，见 DriveScreenState.fileRows。
 */
class DuplicateListing(private val finder: DuplicateFinderState) : SessionListing {
    override val files: List<FileStat>
        get() = (finder.report.identical + finder.report.versions)
            .flatMap { group -> group.rows.mapNotNull { finder.fileStat(it.file.id) } }
            .distinctBy { it.id }

    override fun items(isExpanded: (blockId: String) -> Boolean): List<DriveListItem> = buildList {
        val report = finder.report
        for (group in report.identical + report.versions) {
            val blockId = "dup:${group.kind}:${group.key}"
            val expanded = isExpanded(blockId)
            add(DriveListItem.SectionHeader(blockId, blockId, label(group), group.title, expanded))
            if (!expanded) continue
            for (row in group.rows) {
                val file = finder.fileStat(row.file.id) ?: continue
                add(DriveListItem.File(file, view(file, row, group), key = "$blockId/${file.id}"))
            }
        }
    }

    private fun label(group: DuplicateGroup): String = when (group.kind) {
        DuplicateKind.IDENTICAL -> "${group.title}：${group.rows.size} 份相同，可腾出 ${readableSize(group.reclaimableBytes)}"
        DuplicateKind.VERSIONS -> "${group.title}：${group.rows.size} 个版本，共 ${readableSize(group.totalBytes)}"
    }

    // 标签写这一份与同组其余几份的区别：字幕组、分辨率这些，以及默认留哪一份
    private fun view(file: FileStat, row: DuplicateRow, group: DuplicateGroup): DriveFileView {
        val tags = buildList {
            if (file.id == group.keptId) add("建议保留")
            addAll(row.details)
            if (row.sameCopies > 0) add("另有 ${row.sameCopies} 份相同")
        }
        return DriveFileView(title = file.name, tags = tags, heading = file.name, fields = emptyList())
    }
}
