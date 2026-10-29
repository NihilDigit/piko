package dev.piko.shared.state

import dev.piko.shared.data.ChildFile
import dev.piko.shared.i18n.enCount
import dev.piko.shared.i18n.tr
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
    /** 番号芯片：带片名的常规番号文件夹才有，见 AvInfo.chip。 */
    val code: String? = null,
    val resolution: String?,
    val fields: List<DriveParsedField>,
)

/** 正片之外值得在标签里点明的分区。PV、NCOP、菜单几乎每个 BD 合集都有，写出来只是噪声。 */
private fun notableExtraLabel(section: Section): String? = when (section) {
    Section.SPECIAL -> "SP"
    Section.OVA -> "OVA"
    Section.MOVIE -> tr("剧场版", "Movie", "劇場版", "극장판")
    Section.BONUS -> tr("特典", "Extras", "特典", "특전")
    else -> null
}

/** [content] 为 null 表示还不知道文件夹里有什么，只按文件夹名描述。 */
fun describeDriveFolder(name: String, content: List<ChildFile>?): DriveFolderView {
    val inputs = content.orEmpty().map { it.toMediaFileInput() }
    val description = describeFolder(name, inputs)
    val resolution = description.tags.firstOrNull { it.kind == TagKind.RESOLUTION }?.text
    val tags = if (description.kind == WorkKind.AV) avTags(description, inputs) else seriesTags(description)
    return DriveFolderView(
        title = description.title,
        tags = tags,
        code = description.code?.takeIf { description.kind == WorkKind.AV && it != description.title },
        resolution = resolution,
        fields = folderFields(description),
    )
}

private fun seriesTags(description: FolderDescription): List<String> = buildList {
    description.episodeRange?.let(::add)
    description.extras.mapNotNull(::notableExtraLabel).forEach(::add)
    description.tags.filter { it.kind == TagKind.GROUP }.forEach { add(it.text) }
    description.tags.filter { it.kind == TagKind.RESOLUTION }.forEach { add(it.text) }
    description.tags.filter { it.kind != TagKind.GROUP && it.kind != TagKind.RESOLUTION }.forEach { add(it.text) }
}.distinct()

/**
 * 番号文件夹：中字、无码在前。分段与版本数只能从内容里数出来，拿不到内容时就不写。
 */
private fun avTags(description: FolderDescription, content: List<MediaFileInput>): List<String> {
    val pinned = description.tags.filter { it.pinned }.map { it.text }
    val rest = description.tags.filterNot { it.pinned }.map { it.text }
    val counts = buildList {
        if (content.isEmpty()) return@buildList
        val batch = analyzeMediaBatch(content)
        val entries = batch.works.firstOrNull { it.kind == WorkKind.AV && it.title == description.code }
            ?.sections?.flatMap { it.entries }.orEmpty()
        if (entries.size > 1) add(tr("${entries.size} 段", "${enCount(entries.size, "part", "parts")}", "${entries.size} パート", "${entries.size}개 파트"))
        val versions = entries.maxOfOrNull { it.files.size } ?: 0
        if (versions > 1) add(tr("$versions 版本", "${enCount(versions, "version", "versions")}", "$versions バージョン", "${versions}개 버전"))
    }
    return (pinned + counts + rest).distinct()
}

private fun folderFields(description: FolderDescription): List<DriveParsedField> = listOfNotNull(
    (description.code ?: description.title)?.let {
        DriveParsedField(
            if (description.kind == WorkKind.AV) tr("番号", "Code", "作品番号", "품번") else tr("作品", "Work", "作品", "작품"),
            it,
        )
    },
    description.episodeRange?.let { DriveParsedField(tr("集数", "Episodes", "話数", "화수"), it) },
    description.extras.takeIf { it.isNotEmpty() }?.let { extras -> DriveParsedField(tr("包含", "Includes", "収録", "포함"), extras.joinToString(" ") { it.label }) },
    description.tags.takeIf { it.isNotEmpty() }?.let { tags -> DriveParsedField(tr("标签", "Tags", "タグ", "태그"), tags.joinToString(" ") { it.text }) },
)
