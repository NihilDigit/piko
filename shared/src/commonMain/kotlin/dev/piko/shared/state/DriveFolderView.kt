package dev.piko.shared.state

import dev.piko.shared.naming.FolderDescription
import dev.piko.shared.naming.MediaFileInput
import dev.piko.shared.naming.Section
import dev.piko.shared.naming.TagKind
import dev.piko.shared.naming.WorkKind
import dev.piko.shared.naming.analyzeMediaBatch
import dev.piko.shared.naming.describeFolder

/**
 * 文件夹行与卡片的显示信息。
 *
 * [tags] 按优先级排好：动画是集数范围、「SP」一类、发布组、清晰度，其后是其余技术标签；
 * 番号是中字、无码，其后是分段与版本数。[resolution] 单独给出，封面卡片把它放在右下角。
 */
class DriveFolderView(
    /** 认不出时为 null，界面显示原文件夹名。 */
    val title: String?,
    val tags: List<String>,
    val resolution: String?,
    val fields: List<DriveParsedField>,
    /**
     * 文件夹名看得出是一个发布（有发布组或集数范围），但作品名写成了中文、解析不出。
     * 只有这种文件夹值得补取一页文件名来找作品名。
     */
    val wantsContent: Boolean,
)

/** 正片之外值得在标签里点明的分区。PV、NCOP、菜单几乎每个 BD 合集都有，写出来只是噪声。 */
private val NOTABLE_EXTRAS = mapOf(
    Section.SPECIAL to "SP",
    Section.OVA to "OVA",
    Section.MOVIE to "剧场版",
    Section.BONUS to "特典",
)

fun describeDriveFolder(name: String, contentNames: List<String>?): DriveFolderView {
    val description = describeFolder(name, contentNames.orEmpty())
    val resolution = description.tags.firstOrNull { it.kind == TagKind.RESOLUTION }?.text
    val tags = if (description.kind == WorkKind.AV) avTags(description, contentNames.orEmpty()) else seriesTags(description)
    val hasReleaseHints = description.episodeRange != null || description.tags.any { it.kind == TagKind.GROUP }
    return DriveFolderView(
        title = description.title,
        tags = tags,
        resolution = resolution,
        fields = folderFields(description),
        wantsContent = description.title == null && hasReleaseHints && contentNames == null,
    )
}

private fun seriesTags(description: FolderDescription): List<String> = buildList {
    description.episodeRange?.let(::add)
    description.extras.mapNotNull(NOTABLE_EXTRAS::get).forEach(::add)
    description.tags.filter { it.kind == TagKind.GROUP }.forEach { add(it.text) }
    description.tags.filter { it.kind == TagKind.RESOLUTION }.forEach { add(it.text) }
    description.tags.filter { it.kind != TagKind.GROUP && it.kind != TagKind.RESOLUTION }.forEach { add(it.text) }
}.distinct()

/**
 * 番号文件夹：中字、无码在前。分段与版本数只能从内容里数出来，拿不到内容时就不写。
 */
private fun avTags(description: FolderDescription, contentNames: List<String>): List<String> {
    val pinned = description.tags.filter { it.pinned }.map { it.text }
    val rest = description.tags.filterNot { it.pinned }.map { it.text }
    val counts = buildList {
        if (contentNames.isEmpty()) return@buildList
        val batch = analyzeMediaBatch(contentNames.map { MediaFileInput(it, 0) })
        val entries = batch.works.firstOrNull { it.kind == WorkKind.AV && it.title == description.title }
            ?.sections?.flatMap { it.entries }.orEmpty()
        if (entries.size > 1) add("${entries.size} 段")
        val versions = entries.maxOfOrNull { it.files.size } ?: 0
        if (versions > 1) add("$versions 版本")
    }
    return (pinned + counts + rest).distinct()
}

private fun folderFields(description: FolderDescription): List<DriveParsedField> = listOfNotNull(
    description.title?.let { DriveParsedField(if (description.kind == WorkKind.AV) "番号" else "作品", it) },
    description.episodeRange?.let { DriveParsedField("集数", it) },
    description.extras.takeIf { it.isNotEmpty() }?.let { extras -> DriveParsedField("包含", extras.joinToString(" ") { it.label }) },
    description.tags.takeIf { it.isNotEmpty() }?.let { tags -> DriveParsedField("标签", tags.joinToString(" ") { it.text }) },
)
