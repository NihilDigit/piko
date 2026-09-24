package dev.piko.shared.naming

// 番号只在文件名开头找：站点前缀（xxx.com@、[site.net]、123456_site_）与标签方括号
// （[中文字幕]、[HD]）剥掉之后，番号必须是第一个记号。在全文里找会把动画文件名里的
// 「Naruto_198」「HEVC-10」当成番号。

private val FC2 = Regex("""^FC2[\s_-]*(?:PPV[\s_-]*)?(\d{5,8})(?![0-9])""", RegexOption.IGNORE_CASE)

// 「fc2765224」「FC980638」：FC 后面紧跟的数字整体是编号，其中打头的 2 不是 FC2 的 2。
// 网盘里人手整理过的目录名（FC2-PPV-2765224）给出了这个答案；FC2 的 2 只在后面有分隔或 PPV 时才算前缀
private val FC_GLUED = Regex("""^FC(\d{6,8})(?![0-9])""", RegexOption.IGNORE_CASE)
// 日期加序号的站点：一本道 092415_001、加勒比 021014-540，站点名在前或在后都有
private val ONE_PONDO = Regex("""^(?:1pon(?:do)?|一本道)[\s_-]*(\d{6})[-_](\d{3})(?![0-9])""", RegexOption.IGNORE_CASE)
private val CARIB = Regex("""^(?:carib(?:bean(?:com)?|com)?|加勒比)[\s_-]*(\d{6})[-_](\d{3})(?![0-9])""", RegexOption.IGNORE_CASE)
private val CARIB_TRAILING = Regex("""^(\d{6})[-_](\d{3})[-_]carib""", RegexOption.IGNORE_CASE)

// Heydouga 的写法最乱：hey4017_244、heydouga 4017-175、HeyDouga-4017-228；「しろハメ」是 4017 这个频道的名字
private val HEYDOUGA = Regex("""^(?:しろハメ[\s_-]*(?:hey(?:douga)?)?|hey(?:douga)?)[\s_-]*(\d{4})[\s_-]+(\d{2,5})(?![0-9])""", RegexOption.IGNORE_CASE)

// 没写站点名的日期番号：月日年六位加三位序号，如 123014_949、072815-931。月日须合法，
// 否则「202401-001」这类编号也会被当成番号
private val BARE_DATED = Regex("""^((?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{2})([-_])(\d{3})(?![0-9])""")

// 字母段里夹一位数字（MCB3DBD-47），或数字段前带一个字母（MKBD-S94）。只收全大写，
// 与「Show-S01」这类季号写法区分
private val DIGIT_IN_PREFIX = Regex("""^([A-Z]{2,5}\d[A-Z]{2,5})-(\d{2,4})(?![0-9])""")
private val LETTER_IN_NUMBER = Regex("""^([A-Z]{2,6})-([A-Z]\d{2,4})(?![0-9]|E\d)""")

// 四位补零的连写：xss0057
private val GLUED_PADDED = Regex("""^([A-Za-z]{2,6})(0\d{3})(?![0-9])""")

// Tokyo-Hot：单字母 n 或 k 加四位数，如 n0421。单字母前缀太宽，四位数后面紧跟字母的不算，
// 否则「k1080p」会被认成 K1080
private val TOKYO_HOT = Regex("""^([nk])(\d{4})(?=$|[\s_.\-\[(])""", RegexOption.IGNORE_CASE)

private val HEYZO = Regex("""^HEYZO[\s_-]*(?:HD[\s_-]*)?(\d{3,5})(?![0-9])""", RegexOption.IGNORE_CASE)

// 分隔写法：字母段与数字段之间有 - 或 _。字母段须全大写或全小写，「Naruto_198」这种首字母大写的
// 普通单词不是番号
private val SEPARATED = Regex("""^([A-Za-z]{2,6})[-_](\d{2,5})(?![0-9])""")

// 连写：abcd00123pl、ABCD123C。没有分隔符时与普通单词更难区分，只接受 DMM 式的五位补零，
// 或数字后紧跟已知后缀（C 中字、pl/ps 封面）的写法
private val GLUED_DMM = Regex("""^([A-Za-z]{2,6})(0\d{4})(?![0-9])""")
private val GLUED_SUFFIXED = Regex("""^([A-Za-z]{2,6})(\d{3})(C|pl|ps)$""")

// 「image-2026.04.01」：数字后面接着月和日，是日期，不是番号
private val DATE_CONTINUATION = Regex("""[._-](0[1-9]|1[0-2])[._-]\d{2}(?!\d)""")

private val NOT_A_PREFIX = setOf(
    "HEVC", "AVC", "AAC", "FLAC", "DTS", "AC", "EAC", "MP", "FHD", "UHD", "HD", "SD", "BD", "DVD", "WEB", "CD",
    "DISC", "DISK", "VOL", "PART", "PT", "EP", "SP", "OP", "ED", "NCOP", "NCED", "PV", "CM", "OVA", "OAD", "MA",
    "HI", "MAIN", "BIT", "FPS", "BS", "AT", "NHK", "TVK", "BSP", "CR", "NF", "TV", "TS", "OST", "VER", "VTS",
    "VIDEO", "AUDIO", "SEASON", "EPISODE", "MPEG", "MENU", "SCAN", "IMG", "DSC", "BOX", "SET", "CH", "MOVIE",
    "ENDING", "OPENING", "TRACK", "CAM", "DB", "DBZ", "DBS", "DBGT", "AT-X",
)

private val LOOSE = Regex("""^([A-Za-z]{2,6})\s?(\d{2,5})(?![0-9])""")
private val SUFFIX_PIECE = Regex("""([-_.\s]*)([^-_.\s]+)""")
private val LEADING_TAG_BRACKET = Regex("""[\[【]([^\]】]*)[\]】]""")
private val SITE_AT = Regex("""^(.*)@(?=[A-Za-z0-9])""")
private val SITE_NUMBERED = Regex("""^\d{3,8}_([A-Za-z0-9.]+)_""")
private val DOMAIN = Regex("""(?i)^(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|me|tv|cc|xyz|top|vip|club|fun|app|live|info|io|co|in|site|online|pw|ru|jp|tw|la|cn)$""")
private val LEADING_BRACKET = Regex("""^\s*[\[【(]([^\]】)]*)[\]】)]\s*""")

private val UNCENSORED_WORDS = setOf("u", "uncensored", "ucensored", "uncen", "leak", "leaked", "无码", "無碼", "無修正", "无修正", "破解", "流出")
private val CHINESE_WORDS = setOf("c", "ch", "chs", "cht", "中文字幕", "中字", "sub", "zh")
private val PART = Regex("""^(?:cd|part|pt|disc|disk)[\s.]?(\d{1,2})$""", RegexOption.IGNORE_CASE)
private val GLUED_PART_LETTERS = setOf("A", "B", "D", "E", "F")
private val PART_LETTER = Regex("""^[A-Fa-f]$""")
private val PART_DIGIT = Regex("""^0?[1-9]$""")
// Heydouga 的分段写成 fhd1、hd2
private val PART_QUALITY = Regex("""^f?hd(\d{1,2})$""", RegexOption.IGNORE_CASE)
private val IGNORED_SUFFIXES = setOf("full", "hd", "pl", "ps", "jp", "mosaic", "high", "low")

internal class AvMatch(val info: AvInfo, val tags: List<MediaTag>)

/**
 * 把任意写法的番号归一，认不出时返回 null。与 [parseMediaName] 不同，这里假定调用方已经知道
 * 手上是番号，所以连写的「abc123」也接受。
 */
fun normalizeAvCode(text: String): String? {
    val cleaned = stripSitePrefix(text.trim()).second
    matchCode(cleaned, strict = false)?.let { return it.first }
    return null
}

/** 返回（站点前缀，剩余文本）。 */
private fun stripSitePrefix(stem: String): Pair<String?, String> {
    var rest = stem
    var site: String? = null
    SITE_AT.find(rest)?.let { match ->
        val before = match.groupValues[1]
        site = before.split('@').lastOrNull { it.isNotBlank() }?.trim()
        rest = rest.substring(match.range.last + 1)
    }
    SITE_NUMBERED.find(rest)?.let { match ->
        site = match.groupValues[1]
        rest = rest.substring(match.range.last + 1)
    }
    // 开头的方括号：站点名（[site.net]、[3xplanet]）或纯标签（[中文字幕]、[HD]、[JAV]）才剥
    while (true) {
        val match = LEADING_BRACKET.find(rest) ?: break
        val content = match.groupValues[1].trim()
        val strippable = DOMAIN.matches(content) || content.lowercase() in SITE_WORDS || content.lowercase() in RELEASE_MARKS ||
            scanTags(content).isTagText
        if (!strippable || matchCode(content, strict = true) != null) break
        if (DOMAIN.matches(content) || content.lowercase() in SITE_WORDS) site = content
        rest = rest.substring(match.range.last + 1)
    }
    return site to rest.trimStart(' ', '-', '_', '.')
}

private val SITE_WORDS = setOf("3xplanet", "jav", "javhd", "thz", "sis001", "hjd2048", "fc2", "tokyo hot", "tokyo-hot")

// 开头方括号里的发布标记，剥掉但不算站点
private val RELEASE_MARKS = setOf("nodrm")

/** 返回（归一后的番号，番号在文本里的结束位置）。 */
private fun matchCode(text: String, strict: Boolean): Pair<String, Int>? {
    FC_GLUED.find(text)?.let { return "FC2-PPV-${it.groupValues[1]}" to it.range.last + 1 }
    FC2.find(text)?.let { return "FC2-PPV-${it.groupValues[1]}" to it.range.last + 1 }
    ONE_PONDO.find(text)?.let { return "1PON-${it.groupValues[1]}_${it.groupValues[2]}" to it.range.last + 1 }
    CARIB.find(text)?.let { return "CARIB-${it.groupValues[1]}-${it.groupValues[2]}" to it.range.last + 1 }
    CARIB_TRAILING.find(text)?.let { return "CARIB-${it.groupValues[1]}-${it.groupValues[2]}" to it.range.last + 1 }
    HEYDOUGA.find(text)?.let { return "HEYDOUGA-${it.groupValues[1]}-${it.groupValues[2]}" to it.range.last + 1 }
    TOKYO_HOT.find(text)?.let { return "${it.groupValues[1].uppercase()}${it.groupValues[2]}" to it.range.last + 1 }
    BARE_DATED.find(text)?.let { return "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}" to it.range.last + 1 }
    DIGIT_IN_PREFIX.find(text)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}" to it.range.last + 1 }
    LETTER_IN_NUMBER.find(text)?.let { match ->
        if (acceptablePrefix(match.groupValues[1])) return "${match.groupValues[1]}-${match.groupValues[2]}" to match.range.last + 1
    }
    HEYZO.find(text)?.let { return "HEYZO-${it.groupValues[1]}" to it.range.last + 1 }
    SEPARATED.find(text)?.let { match ->
        val letters = match.groupValues[1]
        val date = DATE_CONTINUATION.matchesAt(text, match.range.last + 1)
        if (acceptablePrefix(letters) && !date) return "${letters.uppercase()}-${match.groupValues[2]}" to match.range.last + 1
    }
    GLUED_DMM.find(text)?.let { match ->
        val letters = match.groupValues[1]
        if (acceptablePrefix(letters)) {
            val digits = match.groupValues[2].trimStart('0').padStart(3, '0')
            return "${letters.uppercase()}-$digits" to match.range.last + 1
        }
    }
    GLUED_PADDED.find(text)?.let { match ->
        val letters = match.groupValues[1]
        if (acceptablePrefix(letters)) return "${letters.uppercase()}-${match.groupValues[2].trimStart('0').padStart(3, '0')}" to match.range.last + 1
    }
    GLUED_SUFFIXED.find(text)?.let { match ->
        val letters = match.groupValues[1]
        if (acceptablePrefix(letters)) return "${letters.uppercase()}-${match.groupValues[2]}" to match.groups[2]!!.range.last + 1
    }
    if (!strict) {
        LOOSE.find(text)?.let { match ->
            val letters = match.groupValues[1]
            if (letters.uppercase() !in NOT_A_PREFIX) return "${letters.uppercase()}-${match.groupValues[2]}" to match.range.last + 1
        }
    }
    return null
}

private fun acceptablePrefix(letters: String): Boolean =
    (letters == letters.uppercase() || letters == letters.lowercase()) && letters.uppercase() !in NOT_A_PREFIX

/** 在文件名主干里识别番号；[stem] 已去掉扩展名与语言后缀。 */
internal fun matchAv(stem: String, allowLanguageSuffix: Boolean): AvMatch? {
    val (site, rest) = stripSitePrefix(stem)
    val (code, end) = matchCode(rest, strict = true) ?: return null
    var uncensored = false
    var chinese = false
    var part: String? = null
    val marks = mutableListOf<String>()
    val tags = mutableListOf<MediaTag>()

    // 番号之后的后缀：-U、-UC、_60FPS_FHD_CH、.H265、-CD1、-A。遇到第一个既不是后缀也不是标签的记号就停，
    // 其后是片名描述，只从中捡标签
    val tail = rest.substring(end)
    val glued = tail.takeWhile { it.isLetter() && it.code < 128 }
    var suffixText = tail
    if (glued.isNotEmpty() && (glued.equals("C", true) || glued.equals("UC", true) || glued.equals("U", true))) {
        if (glued.contains('U', ignoreCase = true)) uncensored = true
        if (glued.contains('C', ignoreCase = true)) chinese = true
        suffixText = tail.substring(glued.length)
    }
    // 粘着的单个字母是分段：「FC2-PPV-1166282A」「…B」。C 已表示中字，不在其列
    if (glued.length == 1 && glued.uppercase() in GLUED_PART_LETTERS) {
        part = glued.uppercase()
        suffixText = tail.substring(1)
    }
    val pieces = SUFFIX_PIECE.findAll(suffixText).map { it.groupValues[1] to it.groupValues[2] }.toList()
    var consumed = 0
    for ((separator, piece) in pieces) {
        val lower = piece.lowercase()
        val handled = when {
            lower == "uc" -> { uncensored = true; chinese = true; true }
            lower in UNCENSORED_WORDS -> { uncensored = true; true }
            lower in CHINESE_WORDS && !(allowLanguageSuffix && lower == "zh") -> { chinese = true; true }
            PART.matches(piece) -> { part = "CD" + PART.find(piece)!!.groupValues[1]; true }
            PART_LETTER.matches(piece) && separator.isNotBlank() -> { part = piece.uppercase(); true }
            PART_DIGIT.matches(piece) && separator.isNotBlank() -> { part = piece.trimStart('0'); true }
            PART_QUALITY.matches(piece) -> { part = PART_QUALITY.find(piece)!!.groupValues[1]; true }
            lower in IGNORED_SUFFIXES -> true
            else -> {
                val found = lookupTagWord(piece)
                // 「-AI」「-YP」这类紧跟番号、以连字符相连的短后缀含义不明，原样保留；空格隔开的是片名描述
                val gluedMark = consumed == 0 && ('-' in separator || '_' in separator) && piece.length <= 3 && piece.all { it.isLetter() && it.code < 128 }
                when {
                    found != null -> { tags += found; true }
                    gluedMark -> { marks += piece.uppercase(); true }
                    else -> false
                }
            }
        }
        if (!handled) break
        consumed++
    }
    val description = pieces.drop(consumed).joinToString(" ") { it.second }
    // 描述里偶尔写着「Uncensored」「中文字幕」，照样收下
    scanTags(description).tags.forEach { found ->
        if (found.kind == TagKind.CENSORSHIP && found.text == MediaTag.UNCENSORED) uncensored = true
        else if (found.kind == TagKind.SUBTITLES && found.text == MediaTag.CHINESE_SUBTITLES) chinese = true
        else tags += found
    }
    // 开头剥掉的标签方括号里的标记同样算数
    LEADING_TAG_BRACKET.findAll(stem.substring(0, maxOf(0, stem.length - rest.length))).forEach { bracket ->
        scanTags(bracket.groupValues[1]).tags.forEach { found ->
            when {
                found.kind == TagKind.CENSORSHIP && found.text == MediaTag.UNCENSORED -> uncensored = true
                found.kind == TagKind.SUBTITLES && found.text == MediaTag.CHINESE_SUBTITLES -> chinese = true
                else -> tags += found
            }
        }
    }
    // 后缀里「无码破解」这类整词经标签词表认出，旗标要与标签一致
    uncensored = uncensored || tags.any { it.kind == TagKind.CENSORSHIP && it.text == MediaTag.UNCENSORED }
    chinese = chinese || tags.any { it.kind == TagKind.SUBTITLES && it.text == MediaTag.CHINESE_SUBTITLES }
    if (uncensored) tags += MediaTag(TagKind.CENSORSHIP, MediaTag.UNCENSORED)
    if (chinese) tags += MediaTag(TagKind.SUBTITLES, MediaTag.CHINESE_SUBTITLES)
    val info = AvInfo(code = code, uncensored = uncensored, chineseSubtitles = chinese, part = part, site = site, marks = marks)
    return AvMatch(info, tags.distinct())
}
