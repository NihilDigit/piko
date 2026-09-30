package dev.piko.shared.state

import io.github.nihildigit.pikpak.FileStat

internal fun FileStat.clipContentKey(): String =
    if (hash.isNotBlank()) "hash:${hash.lowercase()}:$size" else "id:$id"

/** 队列中的内容不重复；同一内容的多个文件只保留一份，优先选择出现次数较少的内容。 */
internal fun selectFeedCandidates(
    ranked: List<FileStat>,
    queued: Set<String>,
    recent: List<String>,
    counts: Map<String, Int>,
    collecting: Boolean,
): List<FileStat> {
    val unique = ranked.distinctBy { it.clipContentKey() }
    val avoid = recent.takeLast(minOf(50, unique.size / 2)).toSet()
    return unique.filter {
        val key = it.clipContentKey()
        key !in queued && key !in avoid && (!collecting || counts.getOrElse(key) { 0 } == 0)
    }.sortedBy { counts.getOrElse(it.clipContentKey()) { 0 } }
}
