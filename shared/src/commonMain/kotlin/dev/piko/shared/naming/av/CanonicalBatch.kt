package dev.piko.shared.naming.av

import dev.piko.shared.naming.FileKind
import dev.piko.shared.naming.TagKind
import dev.piko.shared.naming.parseMediaName

/** 一批要规范命名的条目之一。[group] 是所在目录，撞名、跟随与成套的 A、B、C 都只在同一目录里看。 */
data class AvNamingItem(val name: String, val group: String, val isFolder: Boolean = false)

/**
 * 一批文件的规范名，与 [items] 一一对应，不改的项原样返回。
 *
 * - 视频与原盘镜像按自己的番号取规范名，片名优先取 [titles]（番号到片名，如 MetaTube 查到的），没有就用原名里的。
 * - 字幕、音轨与图片的名字以某个视频的原主干加「.」开头时，跟着那个视频换主干，后面的部分（「.zh.srt」）不动。
 * - 跟不上任何视频、自己带番号的字幕（「ABC-123-zh.srt」）单独取规范名，片名与同番号同分段的视频一致。
 * - 文件夹名只含一个番号的，改成「番号 片名」。
 * - 同目录里两个视频得到同一个名字时，各加分辨率标签区分；分辨率也相同就留着撞名，交给冲突检查标出来。
 */
fun canonicalAvNames(items: List<AvNamingItem>, titles: Map<String, String> = emptyMap()): List<String> {
    val parsed = items.map { if (it.isFolder) null else parseMediaName(it.name) }
    val infos = arrayOfNulls<AvInfo>(items.size)
    items.indices.groupBy { items[it].group }.values.forEach { indices ->
        resolveLetteredParts(indices.map { parsed[it]?.av }).forEachIndexed { at, info -> infos[indices[at]] = info }
    }
    val result = items.map { it.name }.toMutableList()
    fun titleOf(info: AvInfo) = titles[info.code] ?: info.title

    val videos = items.indices.filter { parsed[it]?.fileKind?.isAvContent == true && infos[it] != null }
    val proposed = videos.associateWith { canonicalAvName(items[it].name, infos[it]!!, titleOf(infos[it]!!)) }.toMutableMap()
    // 撞名的加分辨率标签
    videos.groupBy { items[it].group to proposed.getValue(it) }.values.filter { it.size > 1 }.forEach { clashing ->
        val tags = clashing.associateWith { index -> parsed[index]!!.tags.firstOrNull { it.kind == TagKind.RESOLUTION }?.text }
        if (tags.values.distinct().size < clashing.size) return@forEach
        clashing.forEach { index ->
            proposed[index] = canonicalAvName(items[index].name, infos[index]!!, titleOf(infos[index]!!), versionTag = tags.getValue(index))
        }
    }
    proposed.forEach { (index, name) -> result[index] = name }

    // 原主干到新主干，供字幕、封面跟随。长的先试：「ABC-123.zh.srt」该跟「ABC-123」，不是更短的同名前缀
    val stems = videos.map { index -> Triple(items[index].group, stemOf(items[index].name), stemOf(proposed.getValue(index))) }
        .sortedByDescending { it.second.length }
    // 同目录同番号同分段的视频，字幕照它的旗标与片名取名，两者才同主干
    val videoInfos = videos.reversed().associate { index -> (items[index].group to infos[index]!!.code + "|" + infos[index]!!.part) to infos[index]!! }

    items.indices.forEach { index ->
        val item = items[index]
        val name = parsed[index]
        when {
            item.isFolder -> canonicalFolderName(item.name, titles)?.let { result[index] = it }
            index in proposed -> Unit
            name == null -> Unit
            name.fileKind == FileKind.SUBTITLE || name.fileKind == FileKind.IMAGE || name.fileKind == FileKind.AUDIO -> {
                val follow = stems.firstOrNull { (group, old, _) -> group == item.group && item.name.startsWith("$old.") }
                val info = infos[index]
                when {
                    follow != null -> result[index] = follow.third + item.name.substring(follow.second.length)
                    // 封面图常是「abc00123pl.jpg」，没有通行的规范写法，跟不上视频的就不动
                    info != null && name.fileKind == FileKind.SUBTITLE -> {
                        val video = videoInfos[item.group to info.code + "|" + info.part] ?: info
                        result[index] = canonicalAvName(item.name, video, titleOf(video), languageCode = name.languageCode)
                    }
                }
            }
        }
    }
    return result
}

/** 单独一个文件的规范名，认不出番号时为 null。[title] 不为 null 时取代原名里的片名。 */
fun canonicalAvNameOf(fileName: String, title: String? = null): String? {
    val info = parseMediaName(fileName).av ?: return null
    val titles = title?.let { mapOf(info.code to it) }.orEmpty()
    return canonicalAvNames(listOf(AvNamingItem(fileName, group = "")), titles).single()
}

/** 只含一个番号的文件夹名：「番号 片名」，不带分段。认不出番号时为 null。 */
private fun canonicalFolderName(name: String, titles: Map<String, String>): String? {
    val info = matchAvFolder(name) ?: return null
    return canonicalAvName(name, info, titles[info.code] ?: info.title, isFolder = true)
}

private fun stemOf(name: String): String = name.substringBeforeLast('.', name)
