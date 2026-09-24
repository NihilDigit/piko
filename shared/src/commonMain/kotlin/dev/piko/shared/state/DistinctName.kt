package dev.piko.shared.state

/**
 * 一组文件名里，每个名字区别于同组其他名字的那一段在全名里的位置。
 *
 * 剥掉多数名字共有的开头与结尾，只在记号边界上切，与秒传面板的折叠同一套切法。返回位置而不是
 * 剥好的字符串：网盘列表要保留全名，只把这一段加重、省略时优先保住它；播放器的选集标签
 * 才真的把其余部分去掉。
 *
 * 要求多数而不是全体共有：一季正片旁边常放着剧场版、PV、特典，名字开头不同，要求全体一致时
 * 一个例外就让整列都剥不掉。不具备共有部分的名字照原样返回 null，正片仍只剩集数。
 * 结尾的比较不含扩展名，.mkv 与 .mp4 混放不影响剥掉前面共有的「[1080P][FLAC]」。
 */
fun distinctSpans(names: List<String>): List<IntRange?> {
    if (names.size < 2) return names.map { null }
    val needed = maxOf(2, ceilMajority(names.size))
    val stems = names.map { it.substringBeforeLast('.').ifEmpty { it } }
    val tokens = stems.map(::tokenize)

    val prefix = majorityRun(tokens, needed, fromEnd = false)
    // 剥完开头至少留一个记号：名字恰好等于公共前缀时不剥
    val prefixCounts = tokens.map { list -> if (list.size > prefix.size && list.take(prefix.size) == prefix) prefix.size else 0 }
    val rests = tokens.mapIndexed { index, list -> list.drop(prefixCounts[index]) }
    val suffix = majorityRun(rests, needed, fromEnd = true)
    val suffixCounts = rests.map { list -> if (list.size > suffix.size && list.takeLast(suffix.size) == suffix) suffix.size else 0 }

    return tokens.mapIndexed { index, list ->
        if (prefixCounts[index] == 0 && suffixCounts[index] == 0) return@mapIndexed null
        val start = list.take(prefixCounts[index]).sumOf { it.length }
        val end = stems[index].length - list.takeLast(suffixCounts[index]).sumOf { it.length }
        start until end
    }
}

/**
 * 从一端逐个记号延伸：每一步取在已选路径上出现最多的记号，够 [needed] 个名字就接上。
 * 结果按名字里的正常顺序排列。
 */
private fun majorityRun(tokens: List<List<String>>, needed: Int, fromEnd: Boolean): List<String> {
    fun List<String>.at(depth: Int): String? = getOrNull(if (fromEnd) size - 1 - depth else depth)
    val run = mutableListOf<String>()
    var candidates = tokens
    while (true) {
        val depth = run.size
        val top = candidates.mapNotNull { it.at(depth) }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (top == null || top.value < needed) break
        run += top.key
        candidates = candidates.filter { it.at(depth) == top.key }
    }
    return if (fromEnd) run.reversed() else run
}

// 六成，向上取整。用整数算：0.6 * 5 在浮点里是 3.0000000000000004，ceil 会得出 4
private fun ceilMajority(count: Int): Int = (count * 3 + 4) / 5
