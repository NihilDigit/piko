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
    /** 番号文件夹的番号本身；[title] 是行标题，带着片名，FC2 只有片名。 */
    val code: String? = null,
) {
    val recognized: Boolean get() = title != null
}

/**
 * 描述一个文件夹。文件夹名多半就是种子标题，作品名常写成中文（「[DBD-Raws][命运石之门][01-24TV全集+SP+剧场版]…」），
 * 而里面的文件名是罗马音（「[DBD-Raws][Steins;Gate][01]…」），所以这类文件夹的作品名优先取自 [content]。
 * 内容可能只列出一部分（网盘生成封面时只给前几项），集数范围与包含的分区因此优先采信文件夹名。
 * 文件夹名只有中文、又没有可用内容时，如实报告未识别。
 */
fun describeFolder(folderName: String, content: List<MediaFileInput> = emptyList()): FolderDescription {
    matchAv(folderName.trim(), allowLanguageSuffix = false)?.let { match ->
        return FolderDescription(match.info.displayTitle(), WorkKind.AV, match.tags, episodeRange = null, extras = emptyList(), code = match.info.code)
    }
    val washed = stripSiteNoise(folderName.trim())
    val fromName = parseSeriesStem(washed)
    val nameTags = fromName.tags + scanFolderTags(washed)

    val analyzed = content.takeIf { it.isNotEmpty() }?.let(::analyzeMediaBatch)
    // 内容真成一部作品时才用它的名字：一堆互不相干的独立视频里随便挑一个的名字当文件夹名，
    // 「Pack From Shared」就被改成了其中一个视频的名字。须有集号或多个条目，且占内容的大多数。
    // 内容只有一个文件时不算：记住的内容不含子文件夹，「My Pack」里三个子文件夹加一个番号视频，
    // 看上去就只有那个视频
    val contentCount = analyzed?.works?.sumOf { work -> work.sections.sumOf { it.entries.size } } ?: 0
    val works = analyzed?.works.orEmpty().filter { it.kind != WorkKind.UNKNOWN }
    val bySize = compareBy<MediaWork> { work -> work.sections.firstOrNull { it.section == Section.MAIN }?.entries?.size ?: 0 }
        .thenBy { work -> work.sections.sumOf { it.entries.size } }
    // 与文件夹同名的那部优先，不必占多数：「红眼罩女仆」目录里还混着另一套「红眼罩紫幕」，集数更多，
    // 按多少挑会把紫幕的集数挂在女仆名下
    val candidate = works.filter { work -> isWork(work) && work.title?.let { sameWork(it, washed) } == true }.maxWithOrNull(bySize)
        ?: works.filter { representsFolder(it, contentCount) }.maxWithOrNull(bySize)
    // 内容里的作品名顶替文件夹名要有理由：文件夹名看得出是一个发布（发布组、集数范围或技术标签），只是作品名
    // 写成了别的语言；或者两者本就是同一部；或者这部作品占了文件夹的绝大部分（同一套合集里另起标题的剧场版之类
    // 不妨碍）。不然文件夹名是用户起的归类名（「Dramas」里一集 Saijo、一集别的，各占一半），哪部都代表不了它，
    // 那部作品的标签也不该挂上来。分区目录（SPs、Scans）是结构，不是归类，名字一律不顶替
    val nameRange = folderRange(folderName)
    val nameIsRelease = fromName.group != null || nameTags.isNotEmpty() || nameRange != null
    val isSectionDir = directoryMeaning(folderName.trim()).let { it.section != null || it.secondary != null }
    val mainWork = candidate?.takeIf { work ->
        val title = work.title ?: return@takeIf true
        val entries = work.sections.sumOf { it.entries.size }
        val dominates = entries >= MIN_DOMINANT_ENTRIES && entries * 3 >= contentCount * 2
        !isSectionDir && (nameIsRelease || sameWork(title, washed) || dominates)
    }

    if (mainWork?.kind == WorkKind.AV) {
        val av = mainWork.sections.firstOrNull()?.entries?.firstOrNull()?.primary?.name?.av
        val title = av?.copy(part = null)?.displayTitle() ?: mainWork.title
        return FolderDescription(title, WorkKind.AV, mergeTags(mainWork.commonTags, nameTags), null, emptyList(), code = mainWork.title)
    }

    // 解析器把季号从作品名里拆进了集号，文件夹只剩「Yuru Camp」就和第一季、剧场版同名了，这里拼回去
    val season = mainWork?.let(::uniformSeason) ?: folderSeason(folderName)
    val range = nameRange ?: mainWork?.let(::mainRange)
    val extras = (sectionsMentioned(folderName) + mainWork?.sections.orEmpty().map { it.section })
        .filter { it != Section.MAIN }
        .distinct()
        .sortedBy { it.ordinal }
    val tags = mergeTags(mainWork?.commonTags.orEmpty(), nameTags)
    // 文件夹名本就含着作品名时用文件夹自己的写法，内容只补集数与标签：「MomoYIH」不该变成内容里小写的「momoyih」
    val bareTitle = mainWork?.title?.takeIf { hasLatin(it) && !sameWork(it, washed) }
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

private fun representsFolder(work: MediaWork, contentCount: Int): Boolean =
    isWork(work) && contentCount >= 2 && work.sections.sumOf { it.entries.size } * 2 >= contentCount

private fun isWork(work: MediaWork): Boolean {
    val entries = work.sections.flatMap { it.entries }
    // 只有一个条目时集号须可信：「dmf123」粘在字母后的数字是弱集号，凭它把目录改名成「dmf」不可靠
    return work.kind == WorkKind.AV || entries.size >= 2 ||
        entries.any { it.episode != null && it.primary.name.confidence == Confidence.HIGH }
}

// 一两个文件说明不了什么：归类文件夹里恰好只放了一部作品的两集，它仍是归类
private const val MIN_DOMINANT_ENTRIES = 3

/**
 * 文件夹名里含着这个作品名：「Show」之于「Show 2019 BD」。只认这个方向：作品名反过来含着文件夹名时
 * （文件夹「MomoYIH」，作品名是带着它的一长串描述），作品名只是更啰嗦，不该顶替
 */
private fun sameWork(title: String, folderName: String): Boolean {
    val work = workKeyOf(title)
    return work.isNotEmpty() && work in workKeyOf(folderName)
}

private fun hasLatin(text: String): Boolean = text.count { it in 'A'..'Z' || it in 'a'..'z' } >= 2

/**
 * 标题里用斜线或竖线并列的多个写法，取含拉丁字母最多的一个：
 * 「幻想万华镜/Gensou Mangekyou」「Heike Monogatari / 平家物語」。以中日文为主时返回 null：
 * 「【标签】某某的JK制服合集」里的 JK 不是罗马音写法，改写只是把开头的标签挪走，不如原名。
 */
private fun latinAlternative(title: String): String? {
    val best = title.split('/', '|', '／').map { it.trim().trim('~', '～', ' ') }.filter { it.isNotEmpty() }
        .maxByOrNull(::latinCount) ?: return null
    return best.takeIf { hasLatin(it) && latinCount(it) >= it.count(::isCjk) }
}

private fun latinCount(text: String): Int = text.count { it in 'A'..'Z' || it in 'a'..'z' }

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

// 文件夹名「Yuru Camp Season 2」里的 2 会被当成集号，作品名剩下一个悬空的「Season」。
// 季号写在单独的方括号里时（「[Sousou no Frieren Season 2]」）不会被当成集号，季号连同数字留在作品名里
private val DANGLING_SEASON = Regex("""(?i)\s+(?:season|s)(?:\s*\d{1,2})?$""")

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
