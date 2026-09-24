package dev.piko.shared.state

import dev.piko.shared.naming.directoryMeaning
import io.github.nihildigit.pikpak.FileStat

private val SECONDARY_FOLDER_NAMES = setOf(
    "sample", "samples", "proof", "proofs", "screens", "screen", "screenshot", "screenshots",
    "subs", "sub", "subtitle", "subtitles", "extra", "extras", "nfo", "trailer", "trailers",
    "bonus", "featurette", "featurettes", "cover", "covers", "metadata",
)
private val SECONDARY_FOLDER_PREFIXES = listOf("sample", "screen", "proof", "sub")

/**
 * 从磁力解析出的文件里挑出主体内容，返回选中项的下标。
 *
 * 路径里带次要目录名的（sample、subs、screens 之类）直接排除，剩下的按最大文件的
 * 十分之一做门槛。网盘列表的折叠已改用解析器的结论（见 filterDriveFiles），这里的旧判据
 * 暂由秒传面板沿用。
 *
 * 全部被排除时回退为全选——宁可多选，也不要让用户面对一个一项没勾的列表。
 */
fun mainContentIndices(paths: List<String>, sizes: List<Long>): Set<Int> {
    if (paths.isEmpty()) return emptySet()

    val candidates = paths.indices.filterNot { index ->
        paths[index].split('/', '\\')
            .dropLast(1)
            .any { segment -> isLikelyNoiseFolderName(segment) }
    }
    if (candidates.isEmpty()) return paths.indices.toSet()

    val maximumSize = candidates.maxOf { sizes[it] }
    if (maximumSize < MINIMUM_LARGE_FILE_BYTES) return candidates.toSet()
    val threshold = maximumSize / 10L
    val kept = candidates.filter { sizes[it] >= threshold }
    return if (kept.isEmpty()) candidates.toSet() else kept.toSet()
}

private const val MINIMUM_LARGE_FILE_BYTES = 5 * 1024 * 1024L

private fun isLikelyNoiseFolderName(name: String): Boolean {
    val clean = name.trim().lowercase()
    return clean in SECONDARY_FOLDER_NAMES ||
        SECONDARY_FOLDER_PREFIXES.any { prefix ->
            clean.startsWith("$prefix-") ||
                clean.startsWith("${prefix}_") ||
                clean.startsWith("$prefix ") ||
                clean.removePrefix(prefix).toIntOrNull() != null
        }
}

/**
 * 本层是否允许折叠：没有子目录，或子目录都是同一发布的组成部分（PV、SPs、Scans、Subs、Season 1 这类）。
 * 有一个带作品名或随意命名的子目录，说明这是用户自己的上层目录，折叠会把要找的东西藏起来。
 */
internal fun isFoldingScope(files: List<FileStat>): Boolean = files.filter(FileStat::isFolder).all { folder ->
    val meaning = directoryMeaning(folder.name)
    meaning.section != null || meaning.secondary != null || meaning.neutral
}

/** 扫图、截图、样片、字体、日志这类子目录，与次要文件一起折叠。 */
internal fun isSecondaryFolderName(name: String): Boolean = directoryMeaning(name).secondary != null

/**
 * 按解析器的结论折叠：[foldedIds] 来自 [analyzeDriveFolder]，是它判为次要的文件与次要子目录。
 * 不再按「小于最大文件十分之一」：那样会把短的 PV、NCOP 当成广告，也会把图集目录里的图片全藏掉。
 */
internal fun filterDriveFiles(
    files: List<FileStat>,
    foldedIds: Set<String>,
    enabled: Boolean,
    revealAll: Boolean,
): List<FileStat> {
    if (!enabled || revealAll || !isFoldingScope(files)) return files
    return files.filterNot { it.id in foldedIds }
}
