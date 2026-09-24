package dev.piko.cli

import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.analyzeDriveFolder
import dev.piko.shared.state.buildDriveItems
import dev.piko.shared.state.describeDriveFolder

/**
 * 离线复现网盘页对一个目录的呈现：文件夹行走 describeDriveFolder，文件走 analyzeDriveFolder 与
 * buildDriveItems，与 DriveScreenState 调的是同一组函数。每一行先写原名，箭头后是界面上的样子。
 *
 * 分区一律展开，收起的分区标上「默认收起」，免得看不到里面解析成了什么。折叠开关按默认的「隐藏」算，
 * 被折叠的条目单列在目录末尾。
 */
fun renderDryRun(snapshot: Snapshot, pathFilter: String?, visited: Boolean, out: Appendable) {
    val byId = snapshot.folders.associateBy { it.id }
    snapshot.folders
        .filter { pathFilter == null || it.path.startsWith(pathFilter) }
        .forEach { folder -> renderFolder(folder, byId, visited, out) }
}

private fun renderFolder(folder: SnapshotFolder, byId: Map<String, SnapshotFolder>, visited: Boolean, out: Appendable) {
    val files = folder.files.map { it.toFileStat(folder.id) }
    out.appendLine("══ ${folder.path.ifEmpty { "/" }}（${files.size} 项）")
    if (files.isEmpty()) return

    val structure = analyzeDriveFolder(files)
    val defaultExpanded = structure.blocks.associate { it.id to it.defaultExpanded }
    val items = buildDriveItems(files, structure, hideFolded = true, isExpanded = { true })
    val shown = items.filterIsInstance<DriveListItem.File>().map { it.file.id }.toSet()

    items.forEach { item ->
        when (item) {
            is DriveListItem.WorkHeader -> out.appendLine("  ▌作品 ${item.title ?: "（无名）"}${tagText(item.tags)}")
            is DriveListItem.SectionHeader -> {
                val collapsed = if (defaultExpanded[item.blockId] == false) "，默认收起" else ""
                val kind = if (item.isWork) "作品" else "分区"
                out.appendLine("  ▸$kind ${item.label}${tagText(item.tags)}$collapsed")
            }
            is DriveListItem.File -> if (item.file.isFolder) {
                renderFolderRow(item.file.name, byId[item.file.id], visited, out)
            } else {
                val view = item.view
                val parsed = if (view == null) "（原样）" else "${chipText(view.code)}${view.title}${tagText(view.tags)}"
                out.appendLine("    ${item.file.name}")
                out.appendLine("      → $parsed")
            }
        }
    }

    val hidden = files.filter { it.id !in shown }
    if (hidden.isNotEmpty()) {
        out.appendLine("  ▸折叠或并入他行（${hidden.size}）")
        hidden.forEach { file ->
            val reason = when {
                structure.attachedTo[file.id] != null -> "附件 → ${files.first { it.id == structure.attachedTo[file.id] }.name}"
                file.id in structure.secondaryIds -> "次要"
                else -> "折叠目录"
            }
            out.appendLine("    ${file.name}  [$reason]")
        }
    }
    out.appendLine()
}

/**
 * 文件夹行。app 在文件夹名看得出是发布、作品名却认不出时，补取一页文件名再解析；快照里有这个子目录就照做，
 * 没有（超出抓取范围）时如实标出。[visited] 模拟每个目录都点进去过：app 记住了里面至多 200 个文件名，
 * 之后描述文件夹行时一律用上。
 */
private fun renderFolderRow(name: String, listed: SnapshotFolder?, visited: Boolean, out: Appendable) {
    val byName = describeDriveFolder(name, null)
    val view = if (visited && listed != null) {
        describeDriveFolder(name, listed.files.filterNot { it.kind == FOLDER_KIND }.take(REMEMBERED_CHILD_NAMES).map { it.name })
    } else if (byName.wantsContent && listed != null) {
        describeDriveFolder(name, listed.files.take(CHILD_NAME_PAGE).filterNot { it.kind == FOLDER_KIND }.map { it.name })
    } else {
        byName
    }
    val note = if (byName.wantsContent && listed == null) "，要补取内容但不在快照里" else ""
    out.appendLine("    [文件夹] $name")
    out.appendLine("      → ${chipText(view.code)}${view.title ?: "（原样）"}${tagText(view.tags)}$note")
}

private fun chipText(code: String?): String = code?.let { "⟨$it⟩ " }.orEmpty()

private fun tagText(tags: List<String>): String = if (tags.isEmpty()) "" else "  〔${tags.joinToString("｜")}〕"

// 与 PikoDriveRepository 补取文件名时的一页大小一致：一页 20 项，文件夹也占名额
private const val CHILD_NAME_PAGE = 20

// 与 PikoDriveRepository 记住的文件名个数一致
private const val REMEMBERED_CHILD_NAMES = 200
