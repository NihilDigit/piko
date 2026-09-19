package dev.piko.shared.state

import dev.piko.data.repository.HeuristicFileFilter
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
 * 与网盘列表的折叠用的是同一套判据：路径里带次要目录名的（sample、subs、
 * screens 之类）直接排除，剩下的按最大文件的十分之一做门槛。判据相同但入口
 * 不同，是因为这里拿到的是种子内的路径与大小，还没有 FileStat。
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

internal fun isLikelyNoiseFolderName(name: String): Boolean {
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
 * 启发式折叠只允许发生在叶目录或倒数第二层。倒数第二层时，次要子目录算噪声，
 * 只要有一个普通子目录就整层不折叠。
 */
internal fun filterDriveFiles(
    files: List<FileStat>,
    enabled: Boolean,
    revealAll: Boolean,
): List<FileStat> {
    if (!enabled || revealAll) return files

    val childFolders = files.filter(FileStat::isFolder)
    if (childFolders.isEmpty()) {
        return HeuristicFileFilter.filter(files, enabled = true, revealAll = false)
    }
    if (!childFolders.all { isLikelyNoiseFolderName(it.name) }) return files

    val leafFiles = files.filterNot(FileStat::isFolder)
    val visibleLeafFiles = HeuristicFileFilter.filter(leafFiles, enabled = true, revealAll = false)
    val visibleFileIds = visibleLeafFiles.mapTo(hashSetOf()) { it.id }
    return files.filter { file ->
        if (file.isFolder) !isLikelyNoiseFolderName(file.name) else file.id in visibleFileIds
    }
}
