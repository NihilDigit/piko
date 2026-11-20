package dev.piko.shared.state

import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr

import dev.piko.shared.data.readableSize
import dev.piko.shared.data.treeEpochMillis
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

enum class DuplicateKeepRule {
    RECOMMENDED, NEWEST, OLDEST, LARGEST, SMALLEST, SHORTEST_PATH, SHORTEST_NAME;

    val label: String get() = when (this) {
        RECOMMENDED -> tr("智能推荐", "Recommended", "おすすめ", "추천", "智慧推薦")
        NEWEST -> tr("保留最新", "Keep newest", "最新を保持", "최신 항목 유지", "保留最新")
        OLDEST -> tr("保留最旧", "Keep oldest", "最も古いものを保持", "가장 오래된 항목 유지", "保留最舊")
        LARGEST -> tr("保留最大", "Keep largest", "最大を保持", "가장 큰 항목 유지", "保留最大")
        SMALLEST -> tr("保留最小", "Keep smallest", "最小を保持", "가장 작은 항목 유지", "保留最小")
        SHORTEST_PATH -> tr("保留最浅路径", "Keep shortest path", "最も浅いパスを保持", "가장 짧은 경로 유지", "保留最淺路徑")
        SHORTEST_NAME -> tr("保留最短文件名", "Keep shortest name", "最も短い名前を保持", "가장 짧은 이름 유지", "保留最短檔名")
    }
}

/** 列表与批量勾选共用同一个顺序；目录置顶优先于保留规则。 */
fun DuplicateGroup.orderedRows(rule: DuplicateKeepRule, focusFolder: String? = null): List<DuplicateRow> {
    // 默认顺序由分组器决定：相同文件按保留建议，版本按大小降序。
    if (rule == DuplicateKeepRule.RECOMMENDED) {
        if (focusFolder == null) return rows
        var hasFocused = false
        for (i in 0 until rows.size) {
            if (rows[i].file.folderPath == focusFolder) {
                hasFocused = true
                break
            }
        }
        return if (!hasFocused) rows else rows.sortedByDescending { it.file.folderPath == focusFolder }
    }
    fun timeMillisOf(file: DuplicateFile): Long? = treeEpochMillis(file.modifiedTime) ?: file.effectiveCreatedAtMillis
    fun compareTime(a: DuplicateFile, b: DuplicateFile, descending: Boolean): Int {
        val ta = timeMillisOf(a)
        val tb = timeMillisOf(b)
        return when {
            ta != null && tb != null -> if (descending) tb.compareTo(ta) else ta.compareTo(tb)
            ta != null -> -1
            tb != null -> 1
            else -> {
                val sa = a.modifiedTime.ifEmpty { a.createdTime }
                val sb = b.modifiedTime.ifEmpty { b.createdTime }
                if (descending) sb.compareTo(sa) else sa.compareTo(sb)
            }
        }
    }
    val order = when (rule) {
        DuplicateKeepRule.RECOMMENDED -> KEEP_ORDER
        DuplicateKeepRule.NEWEST -> Comparator<DuplicateFile> { a, b -> compareTime(a, b, descending = true) }
        DuplicateKeepRule.OLDEST -> Comparator<DuplicateFile> { a, b -> compareTime(a, b, descending = false) }
        DuplicateKeepRule.LARGEST -> compareByDescending<DuplicateFile> { it.size }
        DuplicateKeepRule.SMALLEST -> compareBy<DuplicateFile> { it.size }
        DuplicateKeepRule.SHORTEST_PATH -> compareBy<DuplicateFile> { it.folderPath.count { char -> char == '/' } }
            .thenBy { it.path.length }
        DuplicateKeepRule.SHORTEST_NAME -> compareBy<DuplicateFile> { it.name.length }
    }.then(KEEP_ORDER)
    return if (focusFolder == null) {
        rows.sortedWith { a, b -> order.compare(a.file, b.file) }
    } else {
        rows.sortedWith(compareByDescending<DuplicateRow> { it.file.folderPath == focusFolder }
            .thenComparator { a, b -> order.compare(a.file, b.file) })
    }
}

/** 同一文件可能同时出现在相同文件与版本组，行 key 带组身份，选中仍按文件 ID。 */
class DuplicateListing(
    private val finder: DuplicateFinderState,
    private val kind: DuplicateKind? = null,
    private val keepRule: () -> DuplicateKeepRule = { DuplicateKeepRule.RECOMMENDED },
    private val focusFolder: () -> String? = { null },
    private val kindProvider: () -> DuplicateKind? = { kind },
) : SessionListing {
    private class MaterializedGroup(
        val blockId: String,
        val expandedHeader: DriveListItem.SectionHeader,
        val collapsedHeader: DriveListItem.SectionHeader,
        val rows: List<DriveListItem.File>,
    )

    private var cachedFilesReport: DuplicateReport? = null
    private var cachedFilesKind: DuplicateKind? = null
    private var cachedFiles: List<FileStat> = emptyList()

    private var cachedGroupsReport: DuplicateReport? = null
    private var cachedGroupsKind: DuplicateKind? = null
    private var cachedGroupsRule: DuplicateKeepRule? = null
    private var cachedGroupsFocus: String? = null
    private var cachedGroupsLanguage: Any? = null
    private var cachedMaterialized: List<MaterializedGroup> = emptyList()
    private var cachedTotalItemCount: Int = 0

    private fun groupsOf(report: DuplicateReport, currentKind: DuplicateKind?): List<DuplicateGroup> = when (currentKind) {
        DuplicateKind.IDENTICAL -> report.identical
        DuplicateKind.VERSIONS -> report.versions
        null -> report.identical + report.versions
    }

    override val files: List<FileStat>
        get() {
            val report = finder.report
            val currentKind = kindProvider()
            if (cachedFilesReport === report && cachedFilesKind == currentKind) return cachedFiles
            val groups = groupsOf(report, currentKind)
            val seen = HashSet<String>(groups.size * 3)
            val out = ArrayList<FileStat>(groups.size * 2)
            for (i in 0 until groups.size) {
                val rows = groups[i].rows
                for (j in 0 until rows.size) {
                    val id = rows[j].file.id
                    if (seen.add(id)) {
                        finder.fileStat(id)?.let(out::add)
                    }
                }
            }
            cachedFilesReport = report
            cachedFilesKind = currentKind
            cachedFiles = out
            return out
        }

    private fun materializedGroups(): List<MaterializedGroup> {
        val report = finder.report
        val currentKind = kindProvider()
        val rule = keepRule()
        val focus = focusFolder()
        val language = dev.piko.shared.i18n.PikoLocale.effectiveLanguage
        if (cachedGroupsReport === report &&
            cachedGroupsKind == currentKind &&
            cachedGroupsRule == rule &&
            cachedGroupsFocus == focus &&
            cachedGroupsLanguage == language
        ) {
            return cachedMaterialized
        }
        val groups = groupsOf(report, currentKind)
        val built = ArrayList<MaterializedGroup>(groups.size)
        var totalCount = 0
        for (i in 0 until groups.size) {
            val group = groups[i]
            val blockId = "dup:${group.kind}:${group.key}"
            val text = label(group)
            val expandedHeader = DriveListItem.SectionHeader(blockId, blockId, text, group.title, expanded = true)
            val collapsedHeader = DriveListItem.SectionHeader(blockId, blockId, text, group.title, expanded = false)
            val orderedRows = group.orderedRows(rule, focus)
            val showKeepSuggestion = group.kind == DuplicateKind.IDENTICAL || rule != DuplicateKeepRule.RECOMMENDED
            val rowItems = ArrayList<DriveListItem.File>(orderedRows.size)
            for (j in 0 until orderedRows.size) {
                val row = orderedRows[j]
                val file = finder.fileStat(row.file.id) ?: continue
                rowItems.add(DriveListItem.File(file, view(file, row, showKeepSuggestion && j == 0), key = "$blockId/${file.id}"))
            }
            totalCount += 1 + rowItems.size
            built.add(MaterializedGroup(blockId, expandedHeader, collapsedHeader, rowItems))
        }
        cachedGroupsReport = report
        cachedGroupsKind = currentKind
        cachedGroupsRule = rule
        cachedGroupsFocus = focus
        cachedGroupsLanguage = language
        cachedMaterialized = built
        cachedTotalItemCount = totalCount
        return built
    }

    override fun items(isExpanded: (blockId: String) -> Boolean): List<DriveListItem> {
        val groups = materializedGroups()
        if (groups.isEmpty()) return emptyList()
        val out = ArrayList<DriveListItem>(cachedTotalItemCount)
        for (i in 0 until groups.size) {
            val g = groups[i]
            val expanded = isExpanded(g.blockId)
            out.add(if (expanded) g.expandedHeader else g.collapsedHeader)
            if (expanded) out.addAll(g.rows)
        }
        return out
    }

    private fun label(group: DuplicateGroup): String = when (group.kind) {
        DuplicateKind.IDENTICAL -> tr(
            "${group.title}：${group.rows.size} 份相同文件，可释放 ${readableSize(group.reclaimableBytes)}",
            "${group.title}: ${enCount(group.rows.size, "identical copy", "identical copies")}, ${readableSize(group.reclaimableBytes)} reclaimable",
            "${group.title}：同一ファイル ${group.rows.size} 件、${readableSize(group.reclaimableBytes)} 解放可能",
            "${group.title}: 동일한 파일 ${group.rows.size}개, ${readableSize(group.reclaimableBytes)} 확보 가능",
            "${group.title}：${group.rows.size} 份相同檔案，可釋出 ${readableSize(group.reclaimableBytes)}",
        )
        DuplicateKind.VERSIONS -> tr(
            "${group.title}：${group.rows.size} 个版本，共 ${readableSize(group.totalBytes)}",
            "${group.title}: ${enCount(group.rows.size, "version", "versions")}, ${readableSize(group.totalBytes)} total",
            "${group.title}：${group.rows.size} バージョン、合計 ${readableSize(group.totalBytes)}",
            "${group.title}: ${group.rows.size}개 버전, 총 ${readableSize(group.totalBytes)}",
            "${group.title}：${group.rows.size} 個版本，共 ${readableSize(group.totalBytes)}",
        )
    }

    // 标签写这一份与同组其余几份的区别：字幕组、分辨率这些，以及默认留哪一份
    private fun view(file: FileStat, row: DuplicateRow, isKept: Boolean): DriveFileView {
        val tags = buildList {
            if (isKept) add(tr("建议保留", "Keep", "保持推奨", "유지 권장", "建議保留"))
            addAll(row.details)
            if (row.sameCopies > 0) add(tr("另有 ${row.sameCopies} 份相同", "${enCount(row.sameCopies, "more identical copy", "more identical copies")}", "ほか同一ファイル ${row.sameCopies} 件", "동일한 사본 ${row.sameCopies}개 더 있음", "另有 ${row.sameCopies} 份相同"))
        }
        return DriveFileView(title = file.name, tags = tags, heading = file.name, fields = emptyList())
    }
}
