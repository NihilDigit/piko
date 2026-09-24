package dev.piko.shared.naming

import dev.piko.data.repository.NaturalOrder

/**
 * 网盘文件夹行的显示信息。
 *
 * [title] 是作品名的罗马音或英文写法；认不出时为 null，UI 显示原文件夹名。[episodeRange] 形如
 * 「01–24」，保留原文的补零；[extras] 是正片之外包含的分区（SP、剧场版、特典……），按分区顺序。
 */
data class FolderDescription(
    val title: String?,
    val kind: WorkKind,
    val tags: List<MediaTag>,
    val episodeRange: String?,
    val extras: List<Section>,
) {
    val recognized: Boolean get() = title != null
}

/**
 * 描述一个文件夹。文件夹名多半就是种子标题，作品名常写成中文（「[DBD-Raws][命运石之门][01-24TV全集+SP+剧场版]…」），
 * 而里面的文件名是罗马音（「[DBD-Raws][Steins;Gate][01]…」），所以作品名优先取自 [contentNames]。
 * 内容可能只列出一部分（网盘生成封面时只给前几项），集数范围与包含的分区因此优先采信文件夹名。
 * 文件夹名只有中文、又没有可用内容时，如实报告未识别。
 */
fun describeFolder(folderName: String, contentNames: List<String> = emptyList()): FolderDescription {
    matchAv(folderName.trim(), allowLanguageSuffix = false)?.let { match ->
        return FolderDescription(match.info.code, WorkKind.AV, match.tags, episodeRange = null, extras = emptyList())
    }
    val washed = stripSiteNoise(folderName.trim())
    val fromName = parseSeriesStem(washed)
    val nameTags = fromName.tags + scanFolderTags(washed)

    val content = contentNames.takeIf { it.isNotEmpty() }?.let { names -> analyzeMediaBatch(names.map { MediaFileInput(it, 0) }) }
    // 内容真成一部作品时才用它的名字：一堆互不相干的独立视频里随便挑一个的名字当文件夹名，
    // 「Pack From Shared」就被改成了其中一个视频的名字。须有集号或多个条目，且占内容的大多数。
    // 内容只有一个文件时不算：记住的内容不含子文件夹，「My Pack」里三个子文件夹加一个番号视频，
    // 看上去就只有那个视频
    val contentCount = content?.works?.sumOf { work -> work.sections.sumOf { it.entries.size } } ?: 0
    val mainWork = content?.works?.filter { it.kind != WorkKind.UNKNOWN && representsFolder(it, contentCount) }?.maxWithOrNull(
        compareBy<MediaWork> { work -> work.sections.firstOrNull { it.section == Section.MAIN }?.entries?.size ?: 0 }
            .thenBy { work -> work.sections.sumOf { it.entries.size } },
    )

    if (mainWork?.kind == WorkKind.AV) {
        return FolderDescription(mainWork.title, WorkKind.AV, mergeTags(mainWork.commonTags, nameTags), null, emptyList())
    }

    // 解析器把季号从作品名里拆进了集号，文件夹只剩「Yuru Camp」就和第一季、剧场版同名了，这里拼回去
    val season = mainWork?.let(::uniformSeason) ?: folderSeason(folderName)
    val nameRange = folderRange(folderName)
    val range = nameRange ?: mainWork?.let(::mainRange)
    val extras = (sectionsMentioned(folderName) + mainWork?.sections.orEmpty().map { it.section })
        .filter { it != Section.MAIN }
        .distinct()
        .sortedBy { it.ordinal }
    val tags = mergeTags(mainWork?.commonTags.orEmpty(), nameTags)
    val bareTitle = mainWork?.title?.takeIf(::hasLatin)
        ?: fromName.title?.takeIf { worthRewriting(folderName, fromName, nameTags, nameRange, season, extras) }?.let(::latinAlternative)?.let(::stripSeasonWord)
    val title = bareTitle?.let { if (season != null && season > 0) "$it Season $season" else it }
        ?: washed.takeIf { it != folderName.trim() }
    return FolderDescription(
        title = title,
        kind = if (title != null || mainWork != null) WorkKind.SERIES else WorkKind.UNKNOWN,
        tags = tags,
        episodeRange = range,
        extras = extras,
    )
}

/**
 * 解析单个剧集文件的规则用在文件夹名上，只有提取出了东西才值得改写：发布组、标签、集数范围、季、分区。
 * 什么也没提取出来时，改写只剩把分隔符换成空格（「www.98T.la@P」成了「www 98T la@P」），不如原名。
 *
 * 单个集号只拆方括号、「- 03」这类明确的写法。粘在词尾或夹在句中的数字（置信度低）是文件夹自己的编号：
 * 「jujuswing9」与「jujuswing11」拆掉编号就撞名了。「Pt1」「Part 2」同理，给文件夹分篇的编号不拆
 */
private fun worthRewriting(
    folderName: String,
    fromName: SeriesParse,
    nameTags: List<MediaTag>,
    range: String?,
    season: Int?,
    extras: List<Section>,
): Boolean {
    val singleEpisode = fromName.episode != null && range == null && season == null
    if (singleEpisode && (fromName.confidence == Confidence.LOW || FOLDER_PART.containsMatchIn(folderName))) return false
    return fromName.group != null || nameTags.isNotEmpty() || range != null || season != null || extras.isNotEmpty()
}

private val FOLDER_PART = Regex("""(?i)(?<![a-z])(?:pt|part)[\s.-]?\d""")

private fun representsFolder(work: MediaWork, contentCount: Int): Boolean {
    val entries = work.sections.flatMap { it.entries }
    val isWork = work.kind == WorkKind.AV || entries.size >= 2 || entries.any { it.episode != null }
    return isWork && contentCount >= 2 && entries.size * 2 >= contentCount
}

private fun hasLatin(text: String): Boolean = text.count { it in 'A'..'Z' || it in 'a'..'z' } >= 2

/**
 * 标题里用斜线或竖线并列的多个写法，取含拉丁字母最多的一个：
 * 「幻想万华镜/Gensou Mangekyou」「Heike Monogatari / 平家物語」。全是中日文时返回 null。
 */
private fun latinAlternative(title: String): String? {
    val best = title.split('/', '|', '／').map { it.trim().trim('~', '～', ' ') }.filter { it.isNotEmpty() }
        .maxByOrNull { part -> part.count { it in 'A'..'Z' || it in 'a'..'z' } } ?: return null
    return best.takeIf(::hasLatin)
}

/** 标签方括号里的「+」连接写法，parseSeriesStem 只收整体是标签的方括号，这里补上零散的。 */
private fun scanFolderTags(name: String): List<MediaTag> =
    Regex("""[\[【(]([^\]】)]*)[\]】)]""").findAll(name).flatMap { scanTags(it.groupValues[1]).tags }.toList()

private fun mergeTags(primary: List<MediaTag>, secondary: List<MediaTag>): List<MediaTag> {
    val hasGroup = primary.any { it.kind == TagKind.GROUP }
    val merged = (primary + secondary.filter { !(hasGroup && it.kind == TagKind.GROUP) }).distinct()
    // 同一类里保留第一个：文件夹名写「1080P」而内容是「1080p」时二者已归一，写法不同的分辨率则以内容为准
    return merged.filter { tag -> tag.kind != TagKind.RESOLUTION || merged.first { it.kind == TagKind.RESOLUTION } == tag }
        .sortedBy { it.kind.ordinal }
}

// 后面跟着「号」「日」的是日期：「7月28-29号」
private val FOLDER_RANGE = Regex("""(?<![\d.月])(?:第|E|EP)?(\d{1,4})\s*(?:-|~|～|到)\s*(?:E|EP)?(\d{1,4})(?![\d]|-?bit|p\b|\s*[号號日])""", RegexOption.IGNORE_CASE)

private val SEASON_PREFIX = Regex("""(?i)(?:\b(?:season|part|vol\.?|set|disc)\s*|(?:^|[^a-z])s)$""")

/**
 * 文件夹名里的集数范围。「S01-04」「Season 1-4」「Part 01-06」是季或篇的范围；「…desu 2 - 12」是作品名里的
 * 数字加上单集集号，两侧带空格而前一段只有一位数，这种不算。
 */
private fun folderRange(name: String): String? {
    FOLDER_RANGE.findAll(name).forEach { m ->
        val first = m.groupValues[1]
        val last = m.groupValues[2]
        val ofSeasons = SEASON_PREFIX.containsMatchIn(name.substring(0, m.range.first))
        val spacedSingle = first.length == 1 && m.value.contains(' ')
        val years = first.length == 4 && first.toInt() in 1950..2035
        if (!years && !ofSeasons && !spacedSingle && first.toInt() < last.toInt()) return "$first–$last"
    }
    return null
}

/** 正片各集的季号一致时返回它。 */
private fun uniformSeason(work: MediaWork): Int? =
    work.sections.firstOrNull { it.section == Section.MAIN }?.entries?.map { it.episode?.season }?.distinct()?.singleOrNull()

// 「S01-04」「Season 1-4」是季的范围，不是某一季，交给 folderRange
private val FOLDER_SEASON = Regex(
    """(?i)(?<![a-z\d])(?:season\s*(\d{1,2})|s(\d{1,2})|(\d{1,2})(?:st|nd|rd|th)\s+season)(?!\d|\s*[-~～]\s*\d|e\d)""",
)

private fun folderSeason(name: String): Int? =
    FOLDER_SEASON.find(name)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toInt()

// 文件夹名「Yuru Camp Season 2」里的 2 会被当成集号，作品名剩下一个悬空的「Season」
private val DANGLING_SEASON = Regex("""(?i)\s+(?:season|s)$""")

private fun stripSeasonWord(title: String): String = title.replace(DANGLING_SEASON, "").ifBlank { title }

private fun mainRange(work: MediaWork): String? {
    val episodes = work.sections.firstOrNull { it.section == Section.MAIN }?.entries?.mapNotNull { it.episode }.orEmpty()
    if (episodes.isEmpty()) return null
    val first = episodes.minWith(compareBy<EpisodeNumber> { it.number }.thenComparator { a, b -> NaturalOrder.compare(a.text, b.text) })
    val last = episodes.maxBy { it.last ?: it.number }
    val lastText = last.lastText ?: last.text
    return if (first.number == (last.last ?: last.number)) first.text else "${first.text}–$lastText"
}

private val FOLDER_MARKERS = mapOf(
    "sp" to Section.SPECIAL, "sps" to Section.SPECIAL, "special" to Section.SPECIAL, "specials" to Section.SPECIAL,
    "ova" to Section.OVA, "ovas" to Section.OVA, "oad" to Section.OVA,
    "movie" to Section.MOVIE, "movies" to Section.MOVIE, "gekijouban" to Section.MOVIE,
    "pv" to Section.PREVIEW, "cm" to Section.PREVIEW,
    "ncop" to Section.CREDITLESS, "nced" to Section.CREDITLESS,
    "extras" to Section.BONUS, "tokuten" to Section.BONUS, "menu" to Section.MENU,
)
private val FOLDER_CJK_MARKERS = mapOf(
    "剧场版" to Section.MOVIE, "劇場版" to Section.MOVIE, "特典" to Section.BONUS, "总集篇" to Section.SPECIAL,
    "總集篇" to Section.SPECIAL, "番外" to Section.SPECIAL, "菜单" to Section.MENU,
)

private fun sectionsMentioned(name: String): List<Section> {
    val words = name.lowercase().split(Regex("""[\s\[\]()【】+&,/_|.]+""")).map { it.trimStart('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-') }
    val latin = words.mapNotNull { FOLDER_MARKERS[it] }
    val cjk = FOLDER_CJK_MARKERS.filterKeys { it in name }.values
    return (latin + cjk).distinct()
}
