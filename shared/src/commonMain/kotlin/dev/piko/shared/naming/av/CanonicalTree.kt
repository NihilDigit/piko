package dev.piko.shared.naming.av

import dev.piko.shared.naming.parseMediaName

/** 一棵目录树里的一项。树根本身可以在里面，它的 [parentId] 指向树外。 */
data class AvTreeItem(val id: String, val parentId: String, val name: String, val isFolder: Boolean)

/** [canonicalAvTree] 给每一项的结果：新名字，与它归入的番号（作品）。认不出番号的 [code] 为 null。 */
data class AvTreeName(val name: String, val code: String?)

/**
 * 资源文件夹的规范名：其中的视频只有一个番号时是「番号旗标 片名」，不带分段与压制标记。
 * 旗标取这些视频与文件夹名的并集；片名依次取 [titles]、文件夹名里同一番号的片名、视频名里的片名。
 * 视频不止一个番号或一个也没有时为 null，文件夹名留给调用方决定。
 */
fun canonicalResourceName(folderName: String, videos: List<AvInfo>, titles: Map<String, String> = emptyMap()): String? {
    val code = videos.map { it.code }.distinct().singleOrNull() ?: return null
    val own = matchAvFolder(folderName)?.takeIf { it.code == code }
    val info = videos.first().copy(
        chineseSubtitles = videos.any { it.chineseSubtitles } || own?.chineseSubtitles == true,
        uncensored = videos.any { it.uncensored } || own?.uncensored == true,
    )
    val title = titles[code] ?: own?.title ?: videos.firstNotNullOfOrNull { it.title }
    return canonicalAvName(folderName, info, title, isFolder = true)
}

/**
 * 一棵目录树的规范名，与 [items] 一一对应，不改的项原样返回。
 *
 * - 名字里有番号、其下的视频也只有这一个番号的文件夹是一份资源，改成 [canonicalResourceName]。里面（含子文件夹）
 *   这个番号的文件只写番号、旗标与分段（ABC-123-C-CD1.mp4），片名已在文件夹名上，不重复；字幕照常跟随。
 * - 名字里认不出番号的文件夹不改，哪怕其下只有一部：它多半是用户自己的归类（「收藏」「待看」），只放着一部是碰巧。
 * - 视频有几个番号的文件夹是合集，保留原名：取其中一个番号命名会让人以为只有那一部。里面的文件各自带片名。
 * - 资源里又有同一番号的子文件夹（ABC-123 片名/ABC-123/…）时子文件夹不改，免得同一个名字套两层。
 * - 没有视频、名字里有番号的文件夹（只放着字幕或封面）照名字里的番号改，与批量重命名一致。
 * - 其余文件夹与认不出番号的文件不动。
 *
 * [resources] 里的文件夹名字里没有番号也当作资源：刚从分享转存来的文件夹是人挑出来要存的那一份，不是自己的归类。
 */
fun canonicalAvTree(
    items: List<AvTreeItem>,
    titles: Map<String, String> = emptyMap(),
    resources: Set<String> = emptySet(),
): List<AvTreeName> {
    val position = items.withIndex().associate { (index, item) -> item.id to index }
    val children = items.groupBy { it.parentId }
    val videoInfos = items.mapNotNull { item ->
        if (item.isFolder) return@mapNotNull null
        val parsed = parseMediaName(item.name)
        parsed.av?.takeIf { parsed.fileKind.isAvContent }?.let { item.id to it }
    }.toMap()
    val subtreeVideos = HashMap<String, List<AvInfo>>()
    fun videosUnder(folderId: String): List<AvInfo> = subtreeVideos.getOrPut(folderId) {
        children[folderId].orEmpty().flatMap { child -> if (child.isFolder) videosUnder(child.id) else listOfNotNull(videoInfos[child.id]) }
    }

    val result = items.map { AvTreeName(it.name, null) }.toMutableList()

    // carried 是外层某个文件夹已经以之命名的番号，这一层里该番号的文件不再写片名
    fun visitChildren(parentId: String, carried: String?) {
        val kids = children[parentId].orEmpty()
        val files = kids.filterNot { it.isFolder }
        val names = canonicalAvNames(files.map { AvNamingItem(it.name, group = parentId) }, titles) { _, code -> code != carried }
        files.forEachIndexed { at, file ->
            val code = parseMediaName(names[at]).av?.code ?: parseMediaName(file.name).av?.code
            result[position.getValue(file.id)] = AvTreeName(names[at], code)
        }
        for (folder in kids.filter { it.isFolder }) {
            val videos = videosUnder(folder.id)
            val resourceCode = videos.map { it.code }.distinct().singleOrNull()
            val own = matchAvFolder(folder.name)
            val isResource = resourceCode != null && resourceCode != carried && (own?.code == resourceCode || folder.id in resources)
            val (name, code) = when {
                isResource -> (canonicalResourceName(folder.name, videos, titles) ?: folder.name) to resourceCode
                videos.isEmpty() && own != null && own.code != carried ->
                    canonicalAvName(folder.name, own, titles[own.code] ?: own.title, isFolder = true) to own.code
                else -> folder.name to null
            }
            result[position.getValue(folder.id)] = AvTreeName(name, code)
            visitChildren(folder.id, if (isResource) resourceCode else carried)
        }
    }

    items.map { it.parentId }.filter { it !in position }.distinct().forEach { visitChildren(it, carried = null) }
    return result
}
