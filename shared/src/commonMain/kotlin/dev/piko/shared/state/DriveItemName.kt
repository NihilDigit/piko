package dev.piko.shared.state

import io.github.nihildigit.pikpak.FileStat

/**
 * 一项给人看时叫什么。认条目的地方（卡片、列表行、操作面板、属性、拖放、提到某一项的提示）都从这里取，
 * 标路径的地方（地址栏、标签、目录选择器、重命名框）一律用真实名称，不经这里。
 *
 * [title] 与 [heading] 为 null 时照原样写名字。真实名称另起一行写在显示名下面，只在两者不同时。
 */
class DriveItemName(
    val file: FileStat,
    /** 行与卡片上的名字：解析出的作品名、集号，或结果页给的规范名。集号的作品名在作品头里，这里不重复。 */
    val title: String?,
    /** 离开列表时的名字（面板、属性、拖放、提示）：没有作品头可依，集号前带上作品名，如「Steins;Gate 01」。 */
    val heading: String?,
) {
    /** 显示名或真实名称里含有 [query]，不区分大小写。 */
    fun matches(query: String): Boolean = listOfNotNull(file.name, title, heading).any { it.contains(query, ignoreCase = true) }
}

/**
 * [row] 是这一行在列表里带的显示信息（结构化列表的解析结果、结果页的规范名），[folder] 是文件夹的解析结果，
 * 解析关闭时调用方传 null。两者都没有或认不出时照原样。
 */
fun driveItemName(file: FileStat, row: DriveFileView?, folder: DriveFolderView?): DriveItemName = when {
    row != null -> DriveItemName(file, row.title, row.heading)
    folder?.title != null -> DriveItemName(file, folder.title, folder.title)
    else -> DriveItemName(file, null, null)
}
