package dev.piko.cli

import dev.piko.shared.state.DriveListItem
import dev.piko.shared.state.analyzeDriveFolder
import dev.piko.shared.state.buildDriveItems
import dev.piko.shared.state.describeDriveFolder

/**
 * 用整理过的文件夹名当标注：文件夹名认得出番号时，里面的视频应当归到同一个番号下。
 * 文件夹名是人手整理的、格式规整，文件名多是下载时的原样，所以前者可以给后者打分。
 *
 * 文件名里本来就没有番号的（「本編.mp4」、章节名、哈希名）单独计数，不算解析失败：
 * 这些只能靠文件夹提供上下文。
 */
fun renderScore(snapshot: Snapshot, out: Appendable) {
    val byPath = snapshot.folders.associateBy { it.path }
    var hit = 0
    val misses = mutableListOf<String>()
    var codeless = 0
    snapshot.folders.forEach { parent ->
        parent.files.filter { it.kind == FOLDER_KIND }.forEach { folder ->
            val code = describeDriveFolder(folder.name, null).fields.firstOrNull { it.label == "番号" }?.value ?: return@forEach
            val child = byPath["${parent.path}/${folder.name}"] ?: return@forEach
            val files = child.files.map { it.toFileStat(child.id) }
            val structure = analyzeDriveFolder(files)
            val titles = buildDriveItems(files, structure, hideFolded = false, isExpanded = { true })
                .filterIsInstance<DriveListItem.File>()
                .associate { it.file.id to recognizedCode(it) }
            child.files.filter { VIDEO.containsMatchIn(it.name) }.forEach { file ->
                val title = titles[file.id] ?: file.name
                when {
                    title.startsWith(code) -> hit++
                    !mentionsCode(file.name, code) -> codeless++
                    else -> misses += "$code\t${file.name}\t→ $title"
                }
            }
        }
    }
    val total = hit + misses.size
    out.appendLine("文件名含番号的视频 $total 个，认对 $hit 个（${if (total == 0) 0 else hit * 100 / total}%）；另有 $codeless 个文件名里没有番号")
    misses.forEach(out::appendLine)
}

/**
 * 这一行认出的番号，取详情里的「番号」与「分段」字段：行标题带着片名，FC2 的行标题只有片名。
 * 没认成番号的行退回行标题或原名，好在失败样本里看到它被认成了什么
 */
private fun recognizedCode(item: DriveListItem.File): String {
    val fields = item.view?.fields.orEmpty()
    val code = fields.firstOrNull { it.label == "番号" }?.value ?: return item.view?.title ?: item.file.name
    return listOfNotNull(code, fields.firstOrNull { it.label == "分段" }?.value).joinToString(" ")
}

/** 文件名里出现了番号的数字部分，才算有番号可认。只比数字：前缀的写法五花八门，正是要考的东西。 */
private fun mentionsCode(fileName: String, code: String): Boolean {
    val digits = Regex("""\d{3,}""").findAll(code).map { it.value.trimStart('0') }.toList()
    return digits.isNotEmpty() && digits.all { it in fileName }
}

private val VIDEO = Regex("\\.(mp4|mkv|wmv|avi|ts|mov|m4v|flv|rmvb|webm)$", RegexOption.IGNORE_CASE)
