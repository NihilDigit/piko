package dev.piko.shared.naming

/**
 * 解析单个文件名（不含目录）。目录能提供的信息（分区、作品名的兜底）由 [analyzeMediaBatch] 补上。
 */
fun parseMediaName(fileName: String): ParsedName {
    val kind = fileKindOf(fileName)
    val hasExtension = kind != FileKind.DOCUMENT || fileName.contains('.') && extensionOf(fileName).length in 1..5
    val stemWithLanguage = if (hasExtension) fileName.substringBeforeLast('.') else fileName
    val (stem, languageCode) = splitLanguageSuffix(stemWithLanguage, kind)
    val language = languageCode?.let { LANGUAGE_SUFFIXES[it.lowercase()] }

    if (kind == FileKind.VIDEO || kind == FileKind.SUBTITLE || kind == FileKind.IMAGE || kind == FileKind.DISC_IMAGE) {
        matchAv(stem, allowLanguageSuffix = kind == FileKind.SUBTITLE)?.let { match ->
            return ParsedName(
                fileName = fileName, fileKind = kind, kind = NameKind.AV, confidence = Confidence.HIGH,
                title = match.info.code, group = null, episode = null, episodeTitle = null, section = null,
                marker = null, label = listOfNotNull(match.info.code, match.info.part).joinToString(" "),
                av = match.info, tags = match.tags, language = language, languageCode = languageCode,
            )
        }
    }
    // 应用与相机自动起的名字不进剧集解析：LINE_MOVIE 的「MOVIE」会被当成剧场版，长串数字会被当成集号
    generatedName(stem)?.let { generated ->
        val title = when (generated) {
            is GeneratedName.Timed -> generated.label
            is GeneratedName.Posted -> generated.account
            GeneratedName.Opaque -> stem
        }
        val label = (generated as? GeneratedName.Posted)?.label ?: title
        return ParsedName(
            fileName = fileName, fileKind = kind, kind = NameKind.STANDALONE, confidence = Confidence.LOW,
            title = title, group = null, episode = null, episodeTitle = null, section = null, marker = null, label = label,
            av = null, tags = emptyList(), language = language, languageCode = languageCode, opaque = generated == GeneratedName.Opaque,
            timed = generated is GeneratedName.Timed,
        )
    }
    val series = parseSeriesStem(stem)
    return ParsedName(
        fileName = fileName, fileKind = kind, kind = series.kind, confidence = series.confidence,
        title = series.title, group = series.group, episode = series.episode, episodeTitle = series.episodeTitle,
        section = series.section, marker = series.marker, label = series.label, av = null,
        tags = series.tags, language = language, languageCode = languageCode,
    )
}

/**
 * 字幕与外挂音轨的语言后缀：「….scjp」「… 04.en」，番号字幕的「ABC-123-zh」。视频偶尔也带
 * （「….chs.mp4」），同样剥掉，否则「chs」会混进作品名。
 */
private fun splitLanguageSuffix(stem: String, kind: FileKind): Pair<String, String?> {
    val dot = stem.lastIndexOf('.')
    if (dot > 0) {
        val candidate = stem.substring(dot + 1)
        if (candidate.lowercase() in LANGUAGE_SUFFIXES) return stem.substring(0, dot) to candidate
    }
    if (kind == FileKind.SUBTITLE) {
        val dash = stem.lastIndexOfAny(charArrayOf('-', '_'))
        if (dash > 0) {
            val candidate = stem.substring(dash + 1)
            val lower = candidate.lowercase()
            // 两个字母的「sc」「it」这类太容易是名字的一部分，横线后缀只认明确的语言代码
            if (lower in LANGUAGE_SUFFIXES && lower !in setOf("sc", "tc", "it", "de", "es", "in", "ja", "ko", "ar", "fr", "ru")) {
                return stem.substring(0, dash) to candidate
            }
        }
    }
    return stem to null
}

// region 词法

internal sealed interface NameToken {
    data class Bracket(val open: Char, val content: String) : NameToken {
        /** 同一个方括号在解析中要判断多次是不是标签段，结果缓存在记号上。 */
        val tagScan: TagScan by lazy(LazyThreadSafetyMode.NONE) { scanTags(content) }
    }
    data class Word(val text: String) : NameToken
    data object Dash : NameToken
}

private val H26_DOTTED = Regex("""(?i)\bh\.26([45])\b""")
private val UNDERSCORED_NUMBER = Regex("""(?<=\w)_(\d{1,4})(?=[_\s\[(]|$)""")
private val MARKER_SPACES = Regex("""[\s_]+""")
private val MENTION_SEPARATORS = Regex("""[\s_&+,.-]+""")
private val AMBIGUOUS_MARKERS = setOf("op", "ed", "sp", "cm", "cf")
private val SHORT_NUMBER = Regex("""^\d{1,2}$""")
private val ABSOLUTE_NUMBER = Regex("""^\d{2,4}$""")
private val WHITESPACE = Regex("""\s+""")
// 完整日期与拍摄时间戳合成一个记号，否则点与下划线换成空格后月份、日子、毫秒各自成了集号候选：
// 「archlinux-2026.04.01」的 04、「VID_20260913_090829_383」的 383
private val DOTTED_DATE = Regex("""(?<!\d)((?:19|20)\d{2})[._](0[1-9]|1[0-2])[._](0[1-9]|[12]\d|3[01])(?!\d)""")
private val CAMERA_TIMESTAMP = Regex("""(?<!\d)((?:19|20)\d{6})_(\d{6})(?:_(\d{1,3}))?(?!\d)""")
// scene release 的两位年份日期：「blacked.25.08.26.name」「Hegre 21 02 23 name」。补成四位年份，交给词表当日期噪声
private val SCENE_DATE = Regex("""(?<=[A-Za-z][._ ])(\d{2})[. ](0[1-9]|1[0-2])[. ](0[1-9]|[12]\d|3[01])(?=[._ ]|$)""")
// 整个主干只是「名字.编号」：czechstreets.121
private val NAME_DOT_NUMBER = Regex("""^([A-Za-z]{2,})\.(\d{2,4})$""")
private val PARENTHESIZED = Regex("""[(（][^)）]*[)）]""")

private val BRACKET_PAIRS = mapOf('[' to ']', '(' to ')', '【' to '】', '{' to '}', '（' to '）')

internal fun normalizeStem(raw: String): String {
    var s = raw.replace('–', '-').replace('—', '-').replace('‒', '-').replace('－', '-').replace('　', ' ')
    s = H26_DOTTED.replace(s, "H26$1")
    s = DOTTED_DATE.replace(s, "$1-$2-$3")
    s = SCENE_DATE.replace(s, "20$1-$2-$3")
    s = NAME_DOT_NUMBER.replace(s, "$1 $2")
    // 下划线稍后会换成空格，x86_64 得先连成一个词，交给词表认作架构名
    s = s.replace("x86_64", "x86-64", ignoreCase = true)
    s = CAMERA_TIMESTAMP.replace(s) { m -> m.groupValues.drop(1).filter(String::isNotEmpty).joinToString("-") }
    // 没有空格（或空格远少于点）的名字才把 _ 与 . 当作空格：「[AonE]_Naruto_198」「Dragon.Ball.Z.S01E19」
    // 「Space.Brothers.S01EP13.3-D Ant.1080p」。空格多时它们多半是名字的一部分，如「Tai-Ari Deshita.」「Beta.Ver」。
    // 两个判断都看原名：先换了下划线，点号那一步就会以为名字里本来有空格
    val spaces = s.count { it == ' ' }
    val dots = s.count { it == '.' }
    val replaceUnderscores = spaces == 0 && '_' in s
    val replaceDots = dots >= 2 && spaces * 2 < dots
    if (replaceDots) s = dotsToSpaces(s)
    if (replaceUnderscores) s = s.replace('_', ' ')
    // 「Nine O'Clock Woman_01_[3FB0AE7E]」：有空格的名字里，下划线夹着的集号
    s = UNDERSCORED_NUMBER.replace(s, " $1")
    // 全用连字符连接的网页式短名：「15-bishoujo-hyouryuuki-1-720p」
    if (' ' !in s && '[' !in s && s.count { it == '-' } >= 3) s = s.replace('-', ' ')
    s = s.replace("_-_", " - ").replace("_[", " [").replace("]_", "] ").replace("_(", " (").replace(")_", ") ")
    return s
}

private fun Char?.isDigitAscii() = this != null && this in '0'..'9'

/**
 * 括号外的点换成空格。只有「2.0」「5.1」这种一位数对一位数的声道写法保留：「S01E19.1080p」的点
 * 两侧也是数字，但它是分隔符。
 */
private fun dotsToSpaces(s: String): String {
    val out = StringBuilder(s.length)
    var depth = 0
    s.forEachIndexed { i, c ->
        when {
            c in BRACKET_PAIRS.keys -> depth++
            c in BRACKET_PAIRS.values -> depth = maxOf(0, depth - 1)
        }
        val channels = s.getOrNull(i - 1).isDigitAscii() && !s.getOrNull(i - 2).isDigitAscii() &&
            s.getOrNull(i + 1).isDigitAscii() && !s.getOrNull(i + 2).isDigitAscii()
        if (c == '.' && depth == 0 && !channels) out.append(' ') else out.append(c)
    }
    return out.toString()
}

internal fun lexName(stem: String): List<NameToken> {
    val out = mutableListOf<NameToken>()
    val word = StringBuilder()
    fun flush() {
        if (word.isEmpty()) return
        val text = word.toString()
        out += if (text.all { it == '-' || it == '~' || it == '～' }) NameToken.Dash else NameToken.Word(text)
        word.clear()
    }
    var i = 0
    while (i < stem.length) {
        val c = stem[i]
        val close = BRACKET_PAIRS[c]
        if (close != null) {
            val end = findClose(stem, i, c, close)
            if (end > i) {
                flush()
                out += NameToken.Bracket(c, stem.substring(i + 1, end).trim())
                i = end + 1
                continue
            }
        }
        if (c.isWhitespace()) flush() else word.append(c)
        i++
    }
    flush()
    return out
}

private fun findClose(s: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    for (j in start until s.length) {
        if (s[j] == open) depth++
        if (s[j] == close) {
            depth--
            if (depth == 0) return j
        }
    }
    return -1
}

// endregion

// region 集号与分区标记

internal class SeriesParse(
    val kind: NameKind,
    val confidence: Confidence,
    val title: String?,
    val group: String?,
    val episode: EpisodeNumber?,
    val episodeTitle: String?,
    val section: Section?,
    val marker: String?,
    val label: String?,
    val tags: List<MediaTag>,
)

/**
 * 一处集号或分区标记在记号序列里的位置 [start, end)。[strength] 越大越可信，同强度取靠前的。
 * [titleHint] 是与集号粘在同一个词里的作品名，如「DetectiveConan-0141」的前半段。
 */
private class Hit(
    val start: Int,
    val end: Int,
    val episode: EpisodeNumber?,
    val section: Section?,
    val marker: String?,
    val label: String,
    val strength: Int,
    val titleHint: String? = null,
    val fromWords: Boolean = false,
)

private const val STRENGTH_SE = 60
private const val STRENGTH_BRACKET = 50
private const val STRENGTH_DASH = 45
private const val STRENGTH_PREFIXED = 40
private const val STRENGTH_MARKER = 35
private const val STRENGTH_DESCRIBED = 30
private const val STRENGTH_WEAK = 10

private val SE = Regex("""^S(\d{1,2})[\s.]?EP?(\d{1,4})(?:(?:-|~)E?P?(\d{1,4}))?(v\d+)?$""", RegexOption.IGNORE_CASE)
private val NXNN = Regex("""^(\d{1,2})x(\d{2,3})$""")
private val ABS_NXNN = Regex("""^(\d{2,4})-(\d{1,2})x(\d{2,3})$""")
private val E_PREFIXED = Regex("""^(?:E|EP|Ep\.)(\d{1,4})(?:-E?P?(\d{1,4}))?(v\d+)?$""", RegexOption.IGNORE_CASE)
private val EPISODE_PHRASE = Regex("""^(?:Episode|Ep\.?)\s*(\d{1,4})$""", RegexOption.IGNORE_CASE)
private val HASH = Regex("""^[#＃](\d{1,4})$""")
private val CJK_EPISODE = Regex("""^第?(\d{1,4})[话話集回]$""")
private val CJK_EPISODE_IN_WORD = Regex("""第(\d{1,4})[话話集回]""")
private val PLAIN_NUMBER = Regex("""^(\d{1,4})(?:\.(\d))?(?:([vV]\d{1,2})|([a-e]))?$""")
private val LEADING_NUMBER = Regex("""^(\d{1,4})(?:-.*)?$""")
private val RANGE = Regex("""^(\d{1,4})\s?[-~]\s?(\d{1,4})$""")
private val NUMBER_WITH_SECTION = Regex("""^(\d{1,4})\s*\(\s*(SP|OVA|OAD|OAV|Special)\s*\)$""", RegexOption.IGNORE_CASE)
private val SEASON_TAIL = Regex("""(?i)(?:\s+|^)(?:S(\d{1,2})|Season\s*(\d{1,2})|(\d{1,2})(?:st|nd|rd|th)\s+Season|第([一二三四五六七八九十\d]{1,3})季)$""")
private val VERSION = Regex("""^(?:[vV](\d{1,2})|(Beta|Alpha)(?:[.\s]?Ver\.?)?|Ver\.?\s?(\d{1,2}))$""", RegexOption.IGNORE_CASE)
private val DESCRIBED_WITH_NUMBER = Regex("""^(.*\p{L}.*?)[\s_]+(\d{1,3})$""")
private val GROUP_LIKE = Regex("""(?i)raws?\b|subs?\b|fansub|studio|字幕|&|组|組|社|team|\.com|\.net""")
private val PART_WORD = Regex("""^(?:Part|Pt)[.\s]?(\d{1,2})$""", RegexOption.IGNORE_CASE)
private val GLUED_LETTERS_NUMBER = Regex("""^([A-Za-z]{2,})(\d{1,4})$""")
private val GLUED_TITLE_NUMBER = Regex("""^(\D.*?)-(\d{2,4})$""")

/** 分区标记词。键为小写，多词标记以单空格连接。 */
private val MARKERS: Map<String, Section> = buildMap {
    listOf("sp", "sps", "special", "specials", "tv special", "soushuuhen", "总集篇", "總集篇", "recap").forEach { put(it, Section.SPECIAL) }
    listOf("ova", "ovas", "oad", "oav").forEach { put(it, Section.OVA) }
    listOf("movie", "the movie", "movies", "gekijouban", "gekijou-ban", "劇場版", "剧场版", "映画", "film").forEach { put(it, Section.MOVIE) }
    listOf("pv", "cm", "cf", "trailer", "teaser", "preview", "promo", "dvd promo", "spot", "commercial", "予告", "预告", "tv spot").forEach {
        put(it, Section.PREVIEW)
    }
    listOf(
        "ncop", "nced", "nc op", "nc ed", "creditless", "creditless op", "creditless ed", "opening", "ending",
        "game op", "game ed", "clean op", "clean ed", "op", "ed", "creditless op-ed", "op-ed",
    ).forEach { put(it, Section.CREDITLESS) }
    listOf("tokuten", "特典", "特典映像", "映像特典", "bonus", "extra", "extras", "interview", "making", "images", "图集", "featurette").forEach {
        put(it, Section.BONUS)
    }
    listOf("menu", "menus", "bd menu", "dvd menu", "メニュー", "菜单").forEach { put(it, Section.MENU) }
}

/** 这些词只在后面紧跟编号时才算标记：「ED」「Images」「Extra」单独出现时多半是普通用词。 */
private val MARKERS_NEEDING_NUMBER = setOf("op", "ed", "cm", "cf", "film", "spot", "extra", "making", "images")

private val MARKER_WITH_NUMBER = Regex(
    """^(game\s?op|game\s?ed|ncop|nced|nc\s?op|nc\s?ed|op|ed|pv|cm|cf|sp|ova|oad|oav|menu|tokuten|special|movie|preview|trailer|teaser|opening|ending|images|extra|interview|m)[\s._-]?(\d{1,3})([a-e])?(?:[_-](\d{1,2}))?(?:v\d{1,2})?$""",
    RegexOption.IGNORE_CASE,
)

private fun markerSection(text: String): Section? = MARKERS[text.lowercase().replace(MARKER_SPACES, " ").trim()]

/** 一段文字里出现的分区标记词（取第一个），供「Cast & Staff Interview 01」这类描述性条目归类。 */
private fun sectionMentioned(text: String): Section? =
    text.lowercase().split(MENTION_SEPARATORS).firstNotNullOfOrNull { word -> markerSection(word)?.takeIf { word !in AMBIGUOUS_MARKERS } }

private fun isYearNumber(digits: String): Boolean = digits.length == 4 && digits.all { it in '0'..'9' } && digits.toInt() in 1950..2035

private fun episodeOf(digits: String, decimal: String? = null, last: String? = null, season: Int? = null, suffix: String = "", version: String? = null) =
    EpisodeNumber(number = digits.toInt(), text = digits, decimal = decimal, lastText = last, season = season, suffix = suffix, version = version?.lowercase())

private fun plainEpisode(m: MatchResult) =
    episodeOf(m.groupValues[1], m.groupValues[2].ifEmpty { null }, suffix = m.groupValues[4], version = m.groupValues[3].ifEmpty { null })

private fun plainLabel(m: MatchResult) = m.groupValues[1] + m.groupValues[2].let { if (it.isEmpty()) "" else ".$it" } + m.groupValues[4]

/** 方括号内容整体是集号或标记时给出命中。 */
private fun bracketHit(content: String, index: Int): Hit? {
    val text = content.trim()
    PLAIN_NUMBER.matchEntire(text)?.let { m ->
        if (isYearNumber(m.groupValues[1])) return null
        return Hit(index, index + 1, plainEpisode(m), null, null, plainLabel(m), STRENGTH_BRACKET)
    }
    NUMBER_WITH_SECTION.matchEntire(text)?.let { m ->
        val section = markerSection(m.groupValues[2]) ?: Section.SPECIAL
        return Hit(index, index + 1, episodeOf(m.groupValues[1]), section, m.groupValues[2], text.replace(" ", ""), STRENGTH_BRACKET)
    }
    CJK_EPISODE.matchEntire(text)?.let { m -> return Hit(index, index + 1, episodeOf(m.groupValues[1]), null, null, m.groupValues[1], STRENGTH_BRACKET) }
    EPISODE_PHRASE.matchEntire(text)?.let { m -> return Hit(index, index + 1, episodeOf(m.groupValues[1]), null, null, m.groupValues[1], STRENGTH_BRACKET) }
    SE.matchEntire(text)?.let { m -> return seHit(m, index, index + 1) }
    E_PREFIXED.matchEntire(text)?.let { m ->
        return Hit(index, index + 1, episodeOf(m.groupValues[1], last = m.groupValues[2].ifEmpty { null }, version = m.groupValues[3].ifEmpty { null }),
            null, null, m.groupValues[1] + m.groupValues[2].let { if (it.isEmpty()) "" else "-$it" }, STRENGTH_BRACKET)
    }
    RANGE.matchEntire(text)?.let { m ->
        // 「2017 - 2023」是年份区间，不是集号
        if (isYearNumber(m.groupValues[1])) return null
        return Hit(index, index + 1, episodeOf(m.groupValues[1], last = m.groupValues[2]), null, null, "${m.groupValues[1]}-${m.groupValues[2]}", STRENGTH_BRACKET)
    }
    MARKER_WITH_NUMBER.matchEntire(text)?.let { m ->
        val word = m.groupValues[1]
        if (word.equals("m", ignoreCase = true)) return null
        val section = markerSection(word) ?: return null
        return Hit(index, index + 1, episodeOf(m.groupValues[2], suffix = m.groupValues[3]), section, word, text, STRENGTH_BRACKET)
    }
    markerSection(text)?.let { section ->
        if (text.lowercase() in MARKERS_NEEDING_NUMBER) return null
        return Hit(index, index + 1, null, section, text, text, STRENGTH_BRACKET)
    }
    return null
}

private fun seHit(m: MatchResult, start: Int, end: Int): Hit {
    val season = m.groupValues[1].toInt()
    val section = if (season == 0) Section.SPECIAL else null
    val episode = episodeOf(m.groupValues[2], last = m.groupValues[3].ifEmpty { null }, season = season, version = m.groupValues[4].ifEmpty { null })
    return Hit(start, end, episode, section, if (season == 0) "S00" else null, episode.shortText, STRENGTH_SE)
}

private fun findHits(tokens: List<NameToken>, bodyStart: Int): List<Hit> {
    val hits = mutableListOf<Hit>()
    tokens.forEachIndexed { i, token ->
        if (i < bodyStart) return@forEachIndexed
        when (token) {
            is NameToken.Bracket -> bracketHits(tokens, i, bodyStart)?.let { hits += it }
            is NameToken.Word -> wordHits(tokens, i, bodyStart, hits)
            NameToken.Dash -> Unit
        }
    }
    return hits
}

private fun bracketHits(tokens: List<NameToken>, i: Int, bodyStart: Int): Hit? {
    val content = (tokens[i] as NameToken.Bracket).content.trim()
    // 「[PV][01]」「[menu][03]」「[Images][55]」：标记方括号后面跟纯数字方括号，合成一个条目
    val next = tokens.getOrNull(i + 1)
    val section = markerSection(content)
    if (section != null && next is NameToken.Bracket) {
        PLAIN_NUMBER.matchEntire(next.content.trim())?.let { number ->
            return Hit(i, i + 2, plainEpisode(number), section, content, "$content ${next.content.trim()}", STRENGTH_BRACKET)
        }
    }
    bracketHit(content, i)?.let { return it }
    // 作品名之后的描述性方括号：「[Juju Sanpo 17]」「[Cast & Staff Interview 01]」「[TV Special Program …]」。
    // 带编号或提到分区标记的才算条目；什么都不带的是副标题（「[Soumei Eichi no Cognitive Computing]」）
    if (i == bodyStart || (tokens[i] as NameToken.Bracket).tagScan.isTagText || !content.any { it.isLetter() }) return null
    val mentioned = sectionMentioned(content)
    DESCRIBED_WITH_NUMBER.matchEntire(content)?.let { m ->
        if (!isYearNumber(m.groupValues[2])) {
            return Hit(i, i + 1, episodeOf(m.groupValues[2]), mentioned ?: Section.OTHER, m.groupValues[1].trim(), content, STRENGTH_DESCRIBED)
        }
    }
    if (mentioned != null) return Hit(i, i + 1, null, mentioned, content, content, STRENGTH_DESCRIBED)
    return null
}

private fun wordHits(tokens: List<NameToken>, i: Int, bodyStart: Int, hits: MutableList<Hit>) {
    val text = (tokens[i] as NameToken.Word).text
    SE.matchEntire(text)?.let { hits += seHit(it, i, i + 1) }
    NXNN.matchEntire(text)?.let { m ->
        hits += Hit(i, i + 1, episodeOf(m.groupValues[2], season = m.groupValues[1].toInt()), null, null, m.groupValues[2], STRENGTH_SE)
    }
    ABS_NXNN.matchEntire(text)?.let { m -> hits += Hit(i, i + 1, episodeOf(m.groupValues[1]), null, null, m.groupValues[1], STRENGTH_DASH) }
    E_PREFIXED.matchEntire(text)?.let { m ->
        hits += Hit(i, i + 1, episodeOf(m.groupValues[1], last = m.groupValues[2].ifEmpty { null }, version = m.groupValues[3].ifEmpty { null }),
            null, null, m.groupValues[1] + m.groupValues[2].let { if (it.isEmpty()) "" else "-$it" }, STRENGTH_PREFIXED)
    }
    HASH.matchEntire(text)?.let { m -> hits += Hit(i, i + 1, episodeOf(m.groupValues[1]), null, null, m.groupValues[1], STRENGTH_PREFIXED) }
    // 「第1话「相亲 臭脾气」」：中文集号后面常紧跟不在括号表里的「」
    CJK_EPISODE_IN_WORD.find(text)?.let { m ->
        if (m.range.first == 0) hits += Hit(i, i + 1, episodeOf(m.groupValues[1]), null, null, m.groupValues[1], STRENGTH_PREFIXED)
    }
    // 「Episode 74」「EP 140-Goku Gains Speed」
    if (text.equals("Episode", true) || text.equals("Ep", true) || text.equals("Ep.", true)) {
        val next = (tokens.getOrNull(i + 1) as? NameToken.Word)?.text.orEmpty()
        LEADING_NUMBER.matchEntire(next)?.let { m ->
            hits += Hit(i, i + 2, episodeOf(m.groupValues[1]), null, null, m.groupValues[1], STRENGTH_PREFIXED)
        }
    }
    // 「Part.1」「Part 2」：分上下篇的 OVA
    PART_WORD.matchEntire(text)?.let { m -> hits += Hit(i, i + 1, episodeOf(m.groupValues[1]), null, null, "Part ${m.groupValues[1]}", STRENGTH_PREFIXED) }
    if (text.equals("Part", true)) {
        val next = (tokens.getOrNull(i + 1) as? NameToken.Word)?.text.orEmpty()
        if (SHORT_NUMBER.matches(next)) hits += Hit(i, i + 2, episodeOf(next), null, null, "Part $next", STRENGTH_PREFIXED)
    }
    // 名字以集号开头：「158 - Dende's Dragon」「01. The Evil Spirit」「38 [A] - Pino」
    if (i == bodyStart && tokens.size > i + 1) {
        val next = tokens[i + 1]
        val numbered = text.endsWith('.') && next is NameToken.Word
        if (next == NameToken.Dash || next is NameToken.Bracket || numbered) {
            PLAIN_NUMBER.matchEntire(text.removeSuffix("."))?.let { m ->
                val end = if (next == NameToken.Dash) i + 2 else i + 1
                if (!isYearNumber(m.groupValues[1])) hits += Hit(i, end, plainEpisode(m), null, null, plainLabel(m), STRENGTH_PREFIXED)
            }
        }
    }
    wordMarkerHit(tokens, i)?.let { hits += it }
    // 「- 12」「- 115v2」「- 12.5」「- 117- Follow Dr Gero」
    if (tokens.getOrNull(i - 1) == NameToken.Dash && i - 1 > bodyStart) {
        PLAIN_NUMBER.matchEntire(text.trimEnd('-'))?.let { m ->
            if (!isYearNumber(m.groupValues[1])) hits += Hit(i - 1, i + 1, plainEpisode(m), null, null, plainLabel(m), STRENGTH_DASH)
        }
        RANGE.matchEntire(text)?.let { m ->
            if (!isYearNumber(m.groupValues[1])) hits += Hit(i - 1, i + 1, episodeOf(m.groupValues[1], last = m.groupValues[2]), null, null, text, STRENGTH_DASH)
        }
    }
}

/** 词形式的标记：「Opening 06」「ED 002」「PV 01」「NCOP 19b」「Movie 05」「OVA」「BD Menu」「NCOP1」。 */
private fun wordMarkerHit(tokens: List<NameToken>, i: Int): Hit? {
    val word = (tokens[i] as NameToken.Word).text
    MARKER_WITH_NUMBER.matchEntire(word)?.let { m ->
        val w = m.groupValues[1]
        // 「Z M05」「DBZ M02」：SoM 剧场版合集的 M 编号，只在前面紧跟作品名时才认
        val isMovieCode = w.equals("m", true)
        val section = if (isMovieCode) Section.MOVIE else markerSection(w)
        if (section != null && (!isMovieCode || tokens.getOrNull(i - 1) is NameToken.Word)) {
            return Hit(i, i + 1, episodeOf(m.groupValues[2], suffix = m.groupValues[3]), section, w, word, STRENGTH_MARKER, fromWords = true)
        }
    }
    for (span in 2 downTo 1) {
        val words = (i until i + span).mapNotNull { (tokens.getOrNull(it) as? NameToken.Word)?.text }
        if (words.size != span) continue
        val phrase = words.joinToString(" ")
        val section = markerSection(phrase) ?: continue
        val next = (tokens.getOrNull(i + span) as? NameToken.Word)?.text
        val number = next?.let { PLAIN_NUMBER.matchEntire(it) }
        if (number == null && phrase.lowercase() in MARKERS_NEEDING_NUMBER) continue
        val end = i + span + (if (number != null) 1 else 0)
        val label = listOfNotNull(phrase, next?.takeIf { number != null }).joinToString(" ")
        return Hit(i, end, number?.let(::plainEpisode), section, phrase, label, STRENGTH_MARKER, fromWords = true)
    }
    return null
}

// endregion

/** 解析过程中逐步修正的结果。各步骤只改自己负责的字段。 */
private class Draft(chosen: Hit?) {
    var title: String? = null
    var section: Section? = chosen?.section
    var marker: String? = chosen?.marker
    var episode: EpisodeNumber? = chosen?.episode
    var label: String? = chosen?.label
    var episodeTitle: String? = null
    val tags = mutableListOf<MediaTag>()
}

/** 作品名在记号序列里的范围 [start, end)，以及集号之后从哪里接着读。 */
private class TitleSpan(val start: Int, val end: Int, val after: Int)

internal fun parseSeriesStem(rawStem: String): SeriesParse {
    val tokens = lexName(normalizeStem(rawStem))
    if (tokens.isEmpty()) return unknownParse()

    val first = tokens.first() as? NameToken.Bracket
    var group = first?.takeIf { tokens.size > 1 && !isTagBracket(it) && bracketHit(it.content, 0) == null }?.content
    // 组名之后先跳过标签方括号：「[桜都字幕组][720P][メリー・ジェーン]巨乳…」的作品名在标签之后
    var bodyStart = if (group != null) 1 else 0
    while (bodyStart < tokens.size - 1 && (tokens[bodyStart] as? NameToken.Bracket)?.let(::isStrongTagBracket) == true) bodyStart++

    val chosen = chooseHit(tokens, findHits(tokens, bodyStart), bodyStart, contentStart(tokens, bodyStart))
    val draft = Draft(chosen)
    val span = titleSpan(tokens, chosen, bodyStart, draft)
    val builtTitle = buildTitle(tokens, span.start, span.end)
    draft.title = chosen?.titleHint ?: builtTitle.first
    applyTitleTail(draft)
    readAfterEpisode(tokens, span.after, chosen, draft)
    // 作品名之外、集号之前的标签段也收（「[DBD-Raws][Show][1080P][01]」少见但存在）
    for (index in bodyStart until span.after) {
        val token = tokens[index]
        if (index !in span.start until span.end && token is NameToken.Bracket && token !in builtTitle.second) draft.tags += token.tagScan.tags
    }
    group?.let { draft.tags.add(0, MediaTag(TagKind.GROUP, it)) }

    var title = draft.title?.let(::cleanTitle)?.takeIf(::isMeaningfulTitle)
    // 「[Cleopatra][DVDRIP][480P]」：只有一个非标签方括号时，它是作品名而不是组名
    if (title == null && group != null && !GROUP_LIKE.containsMatchIn(group) && isMeaningfulTitle(group)) {
        title = cleanTitle(group)
        draft.tags.removeAll { it.kind == TagKind.GROUP }
        group = null
    }
    val kind = when {
        draft.episode != null || (draft.section != null && chosen != null) -> NameKind.EPISODE
        title != null -> NameKind.STANDALONE
        else -> NameKind.UNKNOWN
    }
    return SeriesParse(
        kind = kind,
        confidence = if (chosen != null && chosen.strength <= STRENGTH_WEAK) Confidence.LOW else Confidence.HIGH,
        title = title,
        group = group,
        episode = draft.episode,
        episodeTitle = draft.episodeTitle,
        section = draft.section,
        marker = draft.marker,
        label = if (kind == NameKind.STANDALONE) title else draft.label,
        tags = draft.tags.distinct(),
    )
}

/**
 * 作品名一般在集号之前；以标记开头的名字（「[Ending 19] Boruto - …」「Ending 27」）作品名在标记之后。
 * 「Fate Strange Fake - TV Series Making PV」这种，横线与标记之间的词属于条目名，并进行标题。
 */
private fun titleSpan(tokens: List<NameToken>, chosen: Hit?, bodyStart: Int, draft: Draft): TitleSpan {
    if (chosen == null) {
        val end = firstTagIndex(tokens, contentStart(tokens, bodyStart))
        return TitleSpan(bodyStart, end, end)
    }
    if (chosen.start == bodyStart && chosen.section != null) {
        val end = firstTagIndex(tokens, chosen.end)
        return TitleSpan(chosen.end, end, end)
    }
    if (chosen.fromWords && chosen.start > bodyStart) {
        val dash = (bodyStart + 1 until chosen.start).lastOrNull { tokens[it] == NameToken.Dash }
        val between = dash?.let { (it + 1 until chosen.start).map { index -> tokens[index] } }.orEmpty()
        if (dash != null && between.isNotEmpty() && between.all { it is NameToken.Word }) {
            draft.label = (between.map { (it as NameToken.Word).text } + draft.label!!).joinToString(" ")
            return TitleSpan(bodyStart, dash, chosen.end)
        }
    }
    return TitleSpan(bodyStart, chosen.start, chosen.end)
}

/** 作品名末尾的分区标记与季号：「Puttsun Make Love OVA - 01」「City Hunter the Movie 05」「Youjo Senki S2 - 12」。 */
private fun applyTitleTail(draft: Draft) {
    draft.title?.let { title ->
        val (stripped, tailSection, tailMarker) = stripTitleMarker(title)
        if (tailSection != null && stripped.isNotBlank()) {
            if (draft.section == null) {
                draft.section = tailSection
                draft.marker = tailMarker
                if (draft.label != null && draft.episode != null) draft.label = "$tailMarker ${draft.label}"
            }
            draft.title = stripped
        }
    }
    val episode = draft.episode ?: return
    if (episode.season != null) return
    val title = draft.title ?: return
    val match = SEASON_TAIL.find(title) ?: return
    val season = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.let(::seasonNumber) ?: return
    val stripped = title.substring(0, match.range.first).trim()
    if (stripped.isNotBlank()) {
        draft.episode = episode.copy(season = season)
        draft.title = stripped
    }
}

/** 集号之后：版本限定、绝对集号、更具体的分区、单集标题与标签。 */
private fun readAfterEpisode(tokens: List<NameToken>, after: Int, chosen: Hit?, draft: Draft) {
    val trailingWords = mutableListOf<String>()
    var inTags = false
    var version = draft.episode?.version
    var absolute: EpisodeNumber? = null
    for (index in after until tokens.size) {
        when (val token = tokens[index]) {
            is NameToken.Bracket -> {
                val content = token.content.trim()
                val v = VERSION.matchEntire(content)
                val followsSeason = index == after && draft.episode?.season != null
                val sectionHit = if (followsSeason) bracketHit(content, index)?.takeIf { it.section != null && it.episode != null } else null
                when {
                    v != null && draft.episode != null -> version = versionText(v)
                    // 「S05E27 (137)」：括号里的绝对集号
                    followsSeason && PLAIN_NUMBER.matches(content) -> absolute = episodeOf(PLAIN_NUMBER.matchEntire(content)!!.groupValues[1])
                    // 「S00E01 (Movie 01)」：括号里的分区标记比季号 0 更具体
                    sectionHit != null -> {
                        draft.section = sectionHit.section
                        draft.marker = sectionHit.marker
                        draft.episode = sectionHit.episode
                        draft.label = sectionHit.label
                    }
                    // 「S00E05 (OVA - Kuroi Tessaiga)」：季号 0 只说明是特别篇，括号里写了更具体的分区
                    followsSeason && draft.episode?.season == 0 && sectionMentioned(content) != null -> {
                        draft.section = sectionMentioned(content)
                        draft.marker = content
                    }
                    else -> {
                        draft.tags += token.tagScan.tags
                        token.tagScan.version?.let { if (draft.episode != null && version == null) version = it }
                    }
                }
                inTags = true
            }
            is NameToken.Word -> {
                val text = token.text
                val v = VERSION.matchEntire(text)
                when {
                    v != null && draft.episode != null && version == null -> version = versionText(v)
                    // 「Fairy.Tail.S02E27.075」：SxxEyy 后紧跟的数字是绝对集号
                    index == after && draft.episode?.season != null && ABSOLUTE_NUMBER.matches(text) && !isYearNumber(text) -> absolute = episodeOf(text)
                    else -> {
                        val scan = scanTags(text)
                        if (scan.known > 0 && scan.unknown == 0) {
                            inTags = true
                            draft.tags += scan.tags
                        } else if (!inTags) {
                            trailingWords += text
                        }
                    }
                }
            }
            NameToken.Dash -> Unit
        }
    }
    val trailing = trailingWords.joinToString(" ").trim(' ', '-').ifEmpty { null }
    // 没有编号的标记条目，后面的词是它的名字（「PV For Film Festival」）；有编号的是单集标题
    if (chosen != null && draft.episode == null && draft.section != null) {
        if (trailing != null) draft.label = "${draft.label} $trailing"
    } else if (chosen != null) {
        draft.episodeTitle = trailing
    }
    absolute?.let { number ->
        draft.episode?.let { episode ->
            draft.episode = number.copy(version = episode.version)
            draft.label = number.shortText
        }
    }
    if (version != null) draft.episode = draft.episode?.copy(version = version)
}

private fun unknownParse() = SeriesParse(NameKind.UNKNOWN, Confidence.LOW, null, null, null, null, null, null, null, emptyList())

private fun versionText(match: MatchResult): String = when {
    match.groupValues[1].isNotEmpty() -> "v" + match.groupValues[1]
    match.groupValues[2].isNotEmpty() -> match.groupValues[2].replaceFirstChar { it.uppercase() }
    else -> "v" + match.groupValues[3]
}

private fun seasonNumber(text: String): Int? = text.toIntOrNull() ?: when (text) {
    "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
    else -> null
}

private fun isTagBracket(token: NameToken.Bracket): Boolean = token.tagScan.isTagText

/** 整体是标签、且不只是一个弱标签词（「(TV)」）的方括号，才能作为标签段的起点。 */
private fun isStrongTagBracket(token: NameToken.Bracket): Boolean = isTagBracket(token) && !isWeakTagWord(token.content.trim())

/**
 * 作品名的起点。组名与标签之后若还有品牌、站点一类的方括号，紧接着才是词形式的作品名
 * （「[BT-btt.com][桜都字幕组][480P][BOOTLEG]コワレモノ璃沙」），标签段要从第一个词往后找，
 * 否则 [480P] 会把作品名截成空。名字里没有词时（「[DBD-Raws][Steins;Gate][01]」）从组名之后算起。
 */
private fun contentStart(tokens: List<NameToken>, bodyStart: Int): Int {
    for (i in bodyStart until tokens.size) {
        when (val token = tokens[i]) {
            is NameToken.Word -> return i
            is NameToken.Bracket -> if (bracketHit(token.content, i) != null) return bodyStart
            NameToken.Dash -> return bodyStart
        }
    }
    return bodyStart
}

/**
 * 从命中里挑一个。同一名字常有多处像集号的地方，「[hchcsen] Yu Yu Hakusho - 098 - S04E04」里
 * 「098」是绝对集号、S04E04 是季内集号：两者并存时取绝对集号，季号丢掉，排序才能跨季连续。
 */
private fun chooseHit(tokens: List<NameToken>, hits: List<Hit>, bodyStart: Int, contentStart: Int): Hit? {
    val tagStart = firstTagIndex(tokens, contentStart)
    // 标签段之后的「数字」多半是声道、年份一类，只接受 SxxEyy 与标记
    val usable = hits.filter { it.start < tagStart || it.strength >= STRENGTH_SE }
    val best = usable.maxWithOrNull(compareBy<Hit> { it.strength }.thenByDescending { it.start })
        ?: return weakHit(tokens, bodyStart, tagStart)
    if (best.strength == STRENGTH_SE) {
        val dash = usable.firstOrNull { it.strength == STRENGTH_DASH && it.start < best.start && it.episode != null }
        if (dash != null) {
            return Hit(dash.start, best.end, dash.episode!!.copy(version = best.episode?.version), best.section, best.marker, dash.label, STRENGTH_DASH)
        }
    }
    return best
}

/**
 * 作品名后的裸数字：「Deji Meets Girl  04」「[AonE] Naruto 198 [ACBAAC0B]」「DB 067 Short NEP」。
 * 取标签段之前、前面至少有一个词的第一个纯数字词。整个名字只有一个数字（「181.mp4」）时集号确定、没有作品名。
 */
private fun weakHit(tokens: List<NameToken>, bodyStart: Int, tagStart: Int): Hit? {
    val words = (bodyStart until tagStart).filter { tokens[it] is NameToken.Word }
    if (words.isEmpty()) return null
    if (words.size == 1) {
        val only = words.single()
        val text = (tokens[only] as NameToken.Word).text
        PLAIN_NUMBER.matchEntire(text)?.let { m ->
            val titleBrackets = (bodyStart until only).any { tokens[it] is NameToken.Bracket && !isTagBracket(tokens[it] as NameToken.Bracket) }
            if (!isYearNumber(m.groupValues[1])) {
                return Hit(only, only + 1, plainEpisode(m), null, null, plainLabel(m), if (titleBrackets) STRENGTH_WEAK else STRENGTH_PREFIXED)
            }
        }
        GLUED_TITLE_NUMBER.matchEntire(text)?.let { m ->
            if (m.groupValues[1].any { it.isLetter() }) {
                return Hit(only, only + 1, episodeOf(m.groupValues[2]), null, null, m.groupValues[2], STRENGTH_WEAK, titleHint = m.groupValues[1])
            }
        }
        // 「DBZ153」「DBGT1」：缩写与集号连写。单看一个文件与「Area88」无从区分，交给批量分析按同作品的其他文件核对
        GLUED_LETTERS_NUMBER.matchEntire(text)?.let { m ->
            if (lookupTagWord(text) == null) {
                return Hit(only, only + 1, episodeOf(m.groupValues[2]), null, null, m.groupValues[2], STRENGTH_WEAK, titleHint = m.groupValues[1])
            }
        }
        return null
    }
    for (position in words.drop(1)) {
        val text = (tokens[position] as NameToken.Word).text
        PLAIN_NUMBER.matchEntire(text)?.let { m ->
            if (!isYearNumber(m.groupValues[1])) return Hit(position, position + 1, plainEpisode(m), null, null, plainLabel(m), STRENGTH_WEAK)
        }
        // 「Naruto 26-27」：一个文件两集
        RANGE.matchEntire(text)?.let { m ->
            if (!isYearNumber(m.groupValues[1]) && m.groupValues[1].toInt() < m.groupValues[2].toInt()) {
                return Hit(position, position + 1, episodeOf(m.groupValues[1], last = m.groupValues[2]), null, null, text, STRENGTH_WEAK)
            }
        }
    }
    return null
}

/** 第一个标签段的位置：方括号整体是标签，或词在标签词表里。没有时为记号总数。 */
private fun firstTagIndex(tokens: List<NameToken>, from: Int): Int {
    for (i in from until tokens.size) {
        when (val token = tokens[i]) {
            is NameToken.Bracket -> if (isStrongTagBracket(token) && !isYearOnly(token.content)) return i
            // 「BD Menu」「DVD Menu」里的 BD 是标记的一部分，不是片源标签
            is NameToken.Word -> if (i > from && isTitleStoppingTag(token.text) && !startsMarkerPhrase(tokens, i) && !isYearInProse(tokens, i)) return i
            NameToken.Dash -> Unit
        }
    }
    return tokens.size
}

private fun isYearOnly(text: String) = isYearNumber(text.trim())

/**
 * 年份后面全是标签时才是标签段的起点（「Show 2013 1080p」）；后面还有正文，年份就是句子里的日期，
 * 在这里截断会把正文整段丢掉：「HGB - Jan 2, 2013 - 一串演员名」只剩「HGB - Jan 2」
 */
private fun isYearInProse(tokens: List<NameToken>, i: Int): Boolean {
    val word = (tokens[i] as NameToken.Word).text.trim(',', '.', ';')
    if (!isYearNumber(word)) return false
    return tokens.drop(i + 1).any { it is NameToken.Word && !isTitleStoppingTag(it.text) && isMeaningfulTitle(it.text) }
}

private fun startsMarkerPhrase(tokens: List<NameToken>, i: Int): Boolean {
    val word = (tokens[i] as? NameToken.Word)?.text ?: return false
    val next = (tokens.getOrNull(i + 1) as? NameToken.Word)?.text ?: return false
    return markerSection("$word $next") != null
}

/**
 * 作品名。范围内有词时取词，夹在词之间的非标签括号一并保留，如「Black Clover (TV)」；
 * 没有词时取第一个非标签方括号，其后的非标签方括号是副标题，如
 * 「[Steins;Gate][Soumei Eichi no Cognitive Computing][01]」。
 * 有词时，词之前的方括号是合作组名（「[IceBlue][JySzE] Naruto - 028」），不进作品名。
 */
private fun buildTitle(tokens: List<NameToken>, from: Int, to: Int): Pair<String?, Set<NameToken.Bracket>> {
    val range = (from until minOf(to, tokens.size)).map { tokens[it] }
    val used = mutableSetOf<NameToken.Bracket>()
    val firstWord = range.indexOfFirst { it is NameToken.Word }
    if (firstWord >= 0) {
        val parts = mutableListOf<String>()
        // 到第一个标签段为止：「Naruto Shippuden [Dual Audio] [Complete]」里 [Complete] 不属于作品名
        for (token in range.drop(firstWord)) {
            when (token) {
                is NameToken.Word -> parts += token.text
                is NameToken.Bracket -> when {
                    isYearOnly(token.content) -> used += token
                    isStrongTagBracket(token) -> break
                    bracketHit(token.content, 1)?.section == null -> {
                        parts += "${token.open}${token.content}${BRACKET_PAIRS[token.open]}"
                        used += token
                    }
                }
                NameToken.Dash -> parts += "-"
            }
        }
        return parts.joinToString(" ").trim(' ', '-') to used
    }
    val brackets = range.filterIsInstance<NameToken.Bracket>().filter { !isTagBracket(it) && bracketHit(it.content, 1) == null }
    if (brackets.isEmpty()) return null to used
    used += brackets
    return brackets.joinToString(" ") { it.content.trim() } to used
}

private val TITLE_HEAD_MARKERS = listOf("gekijouban", "gekijou-ban", "劇場版", "剧场版", "映画")
private val TITLE_TAIL_MARKERS = listOf("the movie", "movie", "ova", "oad", "oav", "specials", "special", "sp", "gekijouban")

private fun stripTitleMarker(title: String): Triple<String, Section?, String?> {
    val words = title.split(' ').filter { it.isNotEmpty() }
    if (words.size >= 2) {
        val head = words.first()
        if (head.lowercase() in TITLE_HEAD_MARKERS) return Triple(words.drop(1).joinToString(" "), Section.MOVIE, head)
    }
    for (tail in TITLE_TAIL_MARKERS) {
        val tailWords = tail.split(' ')
        if (words.size > tailWords.size && words.takeLast(tailWords.size).map { it.lowercase().trim('-') } == tailWords) {
            val rest = words.dropLast(tailWords.size).joinToString(" ").trim(' ', '-')
            val original = words.takeLast(tailWords.size).joinToString(" ")
            return Triple(rest, markerSection(tail), original)
        }
    }
    return Triple(title, null, null)
}

private fun cleanTitle(title: String): String = title
    .replace(WHITESPACE, " ")
    .trim(' ', '-', '_', '~', '～', '|', ':', '：', ',')

// 容器名也不是作品名：「mp4_ (3).avi」这类导出名只剩编号，不该顶着一个叫「mp4」的作品头
private val NOT_A_TITLE = setOf("episode", "ep", "ep.", "e", "part", "vol", "disc", "track", "mp4", "mkv", "avi", "mov", "wmv", "video", "视频", "img")

/** 纯数字、单个字符、「Episode」这类词或只剩标点的「标题」不算数。 */
private fun isMeaningfulTitle(title: String): Boolean {
    if (title.lowercase() in NOT_A_TITLE) return false
    val letters = title.count { it.isLetter() || isCjk(it) }
    return letters >= 2 || (letters >= 1 && title.any(::isCjk))
}

/** 作品的比较键：忽略大小写、标点与括号里的注释（「(TV)」「(2020)」）。 */
fun workKeyOf(title: String): String {
    val withoutParens = title.replace(PARENTHESIZED, " ")
    val base = withoutParens.ifBlank { title }
    return buildString {
        base.lowercase().forEach { c -> if (c.isLetterOrDigit() || isCjk(c)) append(c) }
    }
}
