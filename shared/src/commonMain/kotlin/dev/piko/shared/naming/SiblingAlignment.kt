package dev.piko.shared.naming

/**
 * 同一目录里文件名的对齐。单看一个名字分不清的事，看兄弟文件就清楚了：「hey4017_244-fhd1」到「fhd7」
 * 里逐个在变的那一段是分段号；「5_6190741636838855047」的长串是 ID；「47、44、52」是一个长系列里下载的几集。
 *
 * 做法：数字换成占位符得到骨架，骨架相同的归一簇；再逐个看数字槽位在簇内怎么变。
 * 日期与时刻整体算一个时间记号，拆成年、月、日，月和日单看都像又小又密的编号。
 */
internal enum class SlotKind { CONSTANT, SEQUENCE, TIME, ID }

internal class AlignedCluster(
    /** 簇内成员在输入里的下标。 */
    val members: List<Int>,
    /** 第一个编号槽位在各成员里的取值，与 [members] 一一对应；没有编号槽位时为 null。 */
    val sequence: List<String>?,
    /** 编号槽位之前、各成员都相同的文字，已去掉变化的槽位。 */
    val head: String,
    /** 编号槽位之后的不变文字。编号打头时（「0499-Misa」），作品名在这里。 */
    val tail: String,
    /**
     * 编号之前有逐个在变的时间：「橙橙_20220907-015242-325」的 325 是毫秒，区分这些文件的是时间，不是编号。
     */
    val timeVariesBeforeSequence: Boolean,
    val slotKinds: List<SlotKind>,
)

// 技术数字整体当文字：「ABC-123 1080p」与「ABC-123 720p」是两个版本，1080 与 720 不是分段号
private val ALIGN_TOKEN = Regex(
    """((?:19|20)\d{2}[-._]?(?:0[1-9]|1[0-2])[-._]?(?:0[1-9]|[12]\d|3[01])(?!\d))|(\d{2}[-:.]\d{2}[-:.]\d{2}(?!\d))|""" +
        """((?i:\d{3,4}[pi]|\d[k]|[xh]\.?26[45]|\d{1,2}bit|\d{2,3}fps)(?![a-z0-9]))|(\d+)|([A-Za-z]+)|(\s+)|(.)""",
)

private sealed interface AlignToken {
    val text: String
    data class Number(override val text: String) : AlignToken
    data class Time(override val text: String) : AlignToken
    data class Literal(override val text: String) : AlignToken
}

private fun alignTokens(stem: String): List<AlignToken> = ALIGN_TOKEN.findAll(stem).map { m ->
    when {
        m.groups[1] != null || m.groups[2] != null -> AlignToken.Time(m.value)
        m.groups[4] != null -> AlignToken.Number(m.value)
        else -> AlignToken.Literal(m.value)
    }
}.toList()

private fun skeletonOf(tokens: List<AlignToken>): String = tokens.joinToString("") {
    when (it) {
        is AlignToken.Number -> "#"
        is AlignToken.Time -> "@"
        is AlignToken.Literal -> it.text.lowercase()
    }
}

/**
 * 编号看位数不看疏密：只下载了一个长系列里的几集很常见。四位以内且互不重复的算编号，更长的是 ID。
 * 十位、十三位的时间戳与 Twitter 的 snowflake ID 交给 GeneratedNames，这里只当 ID。
 */
private fun classifySlot(values: List<String>): SlotKind {
    if (values.distinct().size == 1) return SlotKind.CONSTANT
    if (values.maxOf { it.length } <= 4 && values.map { it.toInt() }.distinct().size == values.size) return SlotKind.SEQUENCE
    return SlotKind.ID
}

/**
 * 把 [stems]（不含扩展名）按骨架分簇，至少 [minSize] 个成员才算一簇。孤立的名字不下结论：
 * 单个文件没有兄弟可比，解析得不完美也只占一行。
 */
internal fun alignSiblings(stems: List<String>, minSize: Int = 3): List<AlignedCluster> {
    val tokenized = stems.map(::alignTokens)
    return tokenized.indices.groupBy { skeletonOf(tokenized[it]) }.values
        .filter { it.size >= minSize }
        .map { members ->
            val first = tokenized[members.first()]
            val numberSlots = first.indices.filter { first[it] is AlignToken.Number }
            val kinds = first.indices.associateWith { index ->
                when (first[index]) {
                    is AlignToken.Number -> classifySlot(members.map { tokenized[it][index].text })
                    is AlignToken.Time -> if (members.map { tokenized[it][index].text }.distinct().size == 1) SlotKind.CONSTANT else SlotKind.TIME
                    is AlignToken.Literal -> SlotKind.CONSTANT
                }
            }
            val sequenceSlot = numberSlots.firstOrNull { kinds[it] == SlotKind.SEQUENCE }
            fun constantText(range: IntRange) = range.filter { kinds[it] == SlotKind.CONSTANT }.joinToString("") { first[it].text }
            val cut = sequenceSlot ?: 0
            AlignedCluster(
                members = members,
                sequence = sequenceSlot?.let { slot -> members.map { tokenized[it][slot].text } },
                head = constantText(0 until cut),
                tail = if (sequenceSlot == null) "" else constantText(sequenceSlot + 1 until first.size),
                timeVariesBeforeSequence = (0 until cut).any { kinds[it] == SlotKind.TIME },
                slotKinds = numberSlots.map { kinds.getValue(it) },
            )
        }
}

private val ALIGN_SEPARATORS = Regex("""[\s_.\-]+""")
private val EDGE_PUNCTUATION = charArrayOf(' ', '(', ')', '（', '）', '[', ']', '【', '】', '#', '@', '-', '_', '.', ',', '，')

/**
 * 簇里不变的文字整理成作品名：去掉站点前缀（www.98T.la@）、技术标签（HEVC）与两端的标点。
 * 剩下不到两个字母时返回 null，这簇就不起作品名。
 */
internal fun alignedTitle(text: String): String? {
    val words = stripSiteNoise(text).split(ALIGN_SEPARATORS)
        .filter { word -> word.isNotBlank() && !scanTags(word).isTagText }
    val title = words.joinToString(" ").trim(*EDGE_PUNCTUATION)
    return title.takeIf { candidate -> candidate.count { it.isLetter() } >= 2 }
}

private val CONTAINERS = setOf("mp4", "mkv", "avi", "ts", "mov", "wmv", "m4v", "flv", "webm", "rmvb")
private val COPY_SUFFIX = Regex("""\s*\(\d{1,2}\)""")
private val VERSION_SEPARATORS = Regex("""[\s_.\-\[\]()【】（）+,]+""")

/**
 * 去掉技术标签、容器扩展名、重复序号与网址之后的名字。同一条目下的文件这个键相同才是同一内容的几个版本：
 * 「…① [230721].ts」与「….mp4」、「IMG_5630」与「IMG_5630 (1)」。条目键相同而这个键不同的，
 * 是恰好编号相同的不同视频（几个来源各自的「(14)」），不能合并
 */
internal fun versionKey(fileName: String): String =
    stripSiteNoise(fileName.substringBeforeLast('.'))
        .replace(COPY_SUFFIX, " ")
        .split(VERSION_SEPARATORS)
        // 只丢能打出标签的技术词与容器名。词表里「认得但不显示」的还有 CRC 与日期，它们可能恰好是区分内容的部分：
        // 「FC2-PPV-1166282A」的 1166282A 形似 CRC，丢掉的话 A、B 两段就成了同一内容
        .filter { word -> word.isNotBlank() && lookupTagWord(word).isNullOrEmpty() && word.lowercase() !in CONTAINERS }
        .joinToString(" ") { it.lowercase() }

/** 条目里与主文件是同一内容的其他版本。 */
internal fun MediaEntry.versionsOfPrimary(): List<EntryFile> {
    val key = versionKey(primary.name.fileName)
    return files.drop(1).filter { versionKey(it.name.fileName) == key }
}

/** 条目里各自该占一行的文件：主文件，加上恰好编号相同、内容不同的其他文件。 */
internal fun MediaEntry.distinctFiles(): List<EntryFile> {
    val versions = versionsOfPrimary().map { it.index }.toSet()
    return files.filter { it.index !in versions }
}
