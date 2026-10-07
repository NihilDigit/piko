package dev.piko.shared.naming.av

import dev.piko.shared.naming.MediaTag
import dev.piko.shared.naming.TagKind
import dev.piko.shared.naming.isCjk
import dev.piko.shared.naming.isDomainName
import dev.piko.shared.naming.isTitleStoppingTag
import dev.piko.shared.naming.leadingDomain
import dev.piko.shared.naming.lookupTagWord
import dev.piko.shared.naming.scanTags

// 番号识别的流程。规则表在 AvRules.kt；通用的文件名解析只经 [matchAv] 一个入口进来。

// 中文的简体、繁体字幕标签。对番号片只有「有没有中字」一个旗标，简繁不另分
private val CHINESE_SUBTITLE_TAGS = setOf(MediaTag.CHINESE_SUBTITLES, "简", "繁", "简繁")

private fun MediaTag.isChineseSubtitles() = kind == TagKind.SUBTITLES && text in CHINESE_SUBTITLE_TAGS

private fun MediaTag.isUncensored() = kind == TagKind.CENSORSHIP && text == MediaTag.UNCENSORED

private fun cleanAvTitle(description: String): String? {
    var title = description.replace(TRAILING_NOTE, "")
    while (LEADING_MARK.containsMatchIn(title)) title = title.replaceFirst(LEADING_MARK, "")
    while (TRAILING_MARK.containsMatchIn(title)) title = title.replaceFirst(TRAILING_MARK, "")
    // 片名后面的压制标签（「片名 1080p HEVC」）不是片名，规范名里也不该带着
    val words = title.trim().split(' ').toMutableList()
    while (words.size > 1 && isTitleStoppingTag(words.last())) words.removeAt(words.lastIndex)
    return words.joinToString(" ").trim(' ', '　', '-', '_', '.').takeIf { it.count(Char::isLetter) >= 2 }
}

internal class AvMatch(val info: AvInfo, val tags: List<MediaTag>)

/** 认出的番号：[end] 是番号在文本里的结束位置。[glue] 是前缀与编号之间的符号，东京热的 N0421 没有。 */
private class CodeMatch(val prefix: String, val number: String, val kind: AvKind, val end: Int, val glue: String = "-") {
    val code: String get() = if (prefix.isEmpty()) number else prefix + glue + number

    fun shifted(by: Int) = CodeMatch(prefix, number, kind, end + by, glue)
}

/** 常规番号的编号：去掉前导零，补足三位。「00123」「0123」「123」都是 123，「012」仍是 012。 */
private fun standardNumber(digits: String): String = digits.trimStart('0').padStart(3, '0')

/**
 * 把任意写法的番号归一，认不出时返回 null。与 [dev.piko.shared.naming.parseMediaName] 不同，这里假定调用方已经知道
 * 手上是番号，所以连写的「abc123」也接受。
 */
fun normalizeAvCode(text: String): String? {
    val cleaned = stripSitePrefix(text.trim()).second
    return matchCodeAllowingQualityPrefix(cleaned, strict = false)?.code
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
    // 「kcf9.com-ABC-123」：不带 @ 的网址前缀
    leadingDomain(rest)?.let { (domain, length) ->
        site = domain
        rest = rest.substring(length)
    }
    // 开头的方括号：站点名（[site.net]、[3xplanet]）或纯标签（[中文字幕]、[HD]、[JAV]）才剥
    while (true) {
        val match = LEADING_BRACKET.find(rest) ?: break
        val content = match.groupValues[1].trim()
        val isSite = isDomainName(content) || content.lowercase() in SITE_WORDS
        val strippable = isSite || content.lowercase() in RELEASE_MARKS || scanTags(content).isTagText
        if (!strippable || matchCode(content, strict = true) != null) break
        if (isSite) site = content
        rest = rest.substring(match.range.last + 1)
    }
    return site to rest.trimStart(' ', '-', '_', '.')
}

/** 「HD-ABC-123」：开头的画质前缀只在剥掉之后认得出番号时才剥，「HD-1080」不动。 */
private fun matchCodeAllowingQualityPrefix(text: String, strict: Boolean): CodeMatch? {
    matchCode(text, strict)?.let { return it }
    val prefix = QUALITY_PREFIX.find(text) ?: return null
    val skipped = prefix.range.last + 1
    return matchCode(text.substring(skipped), strict)?.shifted(skipped)
}

private fun matchCode(text: String, strict: Boolean): CodeMatch? {
    FC_GLUED.find(text)?.let { return CodeMatch("FC2-PPV", it.groupValues[1], AvKind.FC2, it.range.last + 1) }
    FC2.find(text)?.let { return CodeMatch("FC2-PPV", it.groupValues[1], AvKind.FC2, it.range.last + 1) }
    for (label in DATED_LABELS) {
        val match = label.leading.find(text) ?: label.trailing.find(text) ?: continue
        val number = match.groupValues[1] + label.joiner + match.groupValues[2]
        return CodeMatch(label.label, number, AvKind.UNCENSORED_LABEL, match.range.last + 1)
    }
    HEYDOUGA.find(text)?.let {
        return CodeMatch("HEYDOUGA", "${it.groupValues[1]}-${it.groupValues[2]}", AvKind.UNCENSORED_LABEL, it.range.last + 1)
    }
    TOKYO_HOT.find(text)?.let {
        return CodeMatch(it.groupValues[1].uppercase(), it.groupValues[2], AvKind.UNCENSORED_LABEL, it.range.last + 1, glue = "")
    }
    NUMBERED_LABEL.find(text)?.let { match ->
        val label = match.groupValues[1]
        if (label.drop(3).let { it == it.uppercase() || it == it.lowercase() }) {
            return CodeMatch(label.uppercase(), standardNumber(match.groupValues[2]), AvKind.AMATEUR, match.range.last + 1)
        }
    }
    DIGIT_LEADING_LABEL.find(text)?.let {
        return CodeMatch(it.groupValues[1].uppercase(), it.groupValues[2], AvKind.STANDARD, it.range.last + 1)
    }
    BARE_DATED.find(text)?.let {
        return CodeMatch("", "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}", AvKind.UNCENSORED_LABEL, it.range.last + 1)
    }
    DIGIT_IN_PREFIX.find(text)?.let { return CodeMatch(it.groupValues[1], it.groupValues[2], AvKind.STANDARD, it.range.last + 1) }
    LETTER_IN_NUMBER.find(text)?.let { match ->
        if (acceptablePrefix(match.groupValues[1])) return CodeMatch(match.groupValues[1], match.groupValues[2], AvKind.STANDARD, match.range.last + 1)
    }
    HEYZO.find(text)?.let { return CodeMatch("HEYZO", it.groupValues[1], AvKind.UNCENSORED_LABEL, it.range.last + 1) }
    SEPARATED.find(text)?.let { match ->
        val letters = match.groupValues[1]
        val date = DATE_CONTINUATION.matchesAt(text, match.range.last + 1)
        if (acceptablePrefix(letters) && !date) {
            return CodeMatch(letters.uppercase(), standardNumber(match.groupValues[2]), AvKind.STANDARD, match.range.last + 1)
        }
    }
    for (glued in listOf(GLUED_DMM, GLUED_PADDED)) {
        val match = glued.find(text) ?: continue
        val letters = match.groupValues[1]
        if (acceptablePrefix(letters)) return CodeMatch(letters.uppercase(), standardNumber(match.groupValues[2]), AvKind.STANDARD, match.range.last + 1)
    }
    GLUED_SUFFIXED.find(text)?.let { match ->
        val letters = match.groupValues[1]
        if (acceptablePrefix(letters)) {
            return CodeMatch(letters.uppercase(), standardNumber(match.groupValues[2]), AvKind.STANDARD, match.groups[2]!!.range.last + 1)
        }
    }
    if (!strict) {
        LOOSE.find(text)?.let { match ->
            val letters = match.groupValues[1]
            if (letters.uppercase() !in NOT_A_PREFIX) {
                return CodeMatch(letters.uppercase(), standardNumber(match.groupValues[2]), AvKind.STANDARD, match.range.last + 1)
            }
        }
    }
    return null
}

private fun acceptablePrefix(letters: String): Boolean =
    (letters == letters.uppercase() || letters == letters.lowercase()) && letters.uppercase() !in NOT_A_PREFIX

private fun partOfLetter(letter: Char): String = "CD${letter.uppercaseChar() - 'A' + 1}"

/** 在文件名主干里识别番号；[stem] 已去掉扩展名与语言后缀。 */
internal fun matchAv(stem: String, allowLanguageSuffix: Boolean): AvMatch? {
    val (site, rest) = stripSitePrefix(stem)
    val code = matchCodeAllowingQualityPrefix(rest, strict = true) ?: return null
    var uncensored = false
    var chinese = false
    // 中字只由一个单独的 C 表示时记下，成套的 A、B、C 里它其实是分段，见 resolveLetteredParts
    var letterC = false
    var part: String? = null
    val marks = mutableListOf<String>()
    val tags = mutableListOf<MediaTag>()

    // 番号之后的后缀：-U、-UC、_60FPS_FHD_CH、.H265、-CD1、-A。遇到第一个既不是后缀也不是标签的记号就停，
    // 其后是片名描述，只从中捡标签
    val tail = rest.substring(code.end)
    val glued = tail.takeWhile { it.isLetter() && it.code < 128 }
    var suffixText = tail
    if (glued.equals("C", true) || glued.equals("UC", true) || glued.equals("U", true)) {
        if (glued.contains('U', ignoreCase = true)) uncensored = true
        if (glued.contains('C', ignoreCase = true)) chinese = true
        letterC = glued.equals("C", true)
        suffixText = tail.substring(glued.length)
    }
    // 粘着的单个字母是分段：「FC2-PPV-1166282A」「…B」。C 先当中字，成套时再改
    if (glued.length == 1 && glued.uppercase() in GLUED_PART_LETTERS) {
        part = partOfLetter(glued[0])
        suffixText = tail.substring(1)
    }
    val pieceMatches = SUFFIX_PIECE.findAll(suffixText).toList()
    val pieces = pieceMatches.map { it.groupValues[1] to it.groupValues[2] }
    var consumed = 0
    for ((separator, piece) in pieces) {
        val lower = piece.lowercase()
        val spaced = separator.isNotEmpty() && separator.isBlank()
        val handled = when {
            lower in SHORT_FLAGS && spaced -> false
            lower == "uc" -> { uncensored = true; chinese = true; true }
            lower in UNCENSORED_SUFFIXES -> { uncensored = true; true }
            lower in CHINESE_SUFFIXES && !(allowLanguageSuffix && lower == "zh") -> {
                if (lower == "c" && !chinese) letterC = true else letterC = false
                chinese = true
                true
            }
            PART.matches(piece) -> { part = "CD" + PART.find(piece)!!.groupValues[1].trimStart('0'); true }
            PART_LETTER.matches(piece) && separator.isNotBlank() -> { part = partOfLetter(piece[0]); true }
            PART_DIGIT.matches(piece) && separator.isNotBlank() -> { part = "CD" + piece.trimStart('0'); true }
            PART_QUALITY.matches(piece) -> { part = "CD" + PART_QUALITY.find(piece)!!.groupValues[1].trimStart('0'); true }
            lower in IGNORED_SUFFIXES -> true
            else -> {
                val found = lookupTagWord(piece)
                // 「-AI」「-YP」这类紧跟番号、以连字符相连的短后缀含义不明，原样保留；空格隔开的是片名描述
                val gluedMark = consumed == 0 && ('-' in separator || '_' in separator) && piece.length <= 3 && piece.all { it.isLetter() && it.code < 128 }
                // 一长段中日韩文是片名：「『無』『完全顔出し』スレンダー…流出110分物語」里有「流出」，
                // 按标签词吃掉的话片名就没了。标签照样会从片名里扫出来
                val longCjk = piece.count(::isCjk) > 8
                when {
                    found != null && !longCjk -> { tags += found; true }
                    gluedMark -> { marks += piece.uppercase(); true }
                    else -> false
                }
            }
        }
        if (!handled) break
        consumed++
    }
    var titleSource = pieceMatches.getOrNull(consumed)?.let { suffixText.substring(it.groups[2]!!.range.first) }.orEmpty()
    // 规范名把分段写在片名之后：「ABC-123-C 片名-CD1」
    TITLE_PART.find(titleSource)?.let { match ->
        if (part == null) part = "CD" + match.groupValues[1].trimStart('0')
        titleSource = titleSource.substring(0, match.range.first)
    }
    val description = pieces.drop(consumed).joinToString(" ") { it.second }
    // 描述里偶尔写着「Uncensored」「中文字幕」，照样收下
    scanTags(description).tags.forEach { found -> tags += found }
    // 开头剥掉的标签方括号里的标记同样算数
    LEADING_TAG_BRACKET.findAll(stem.substring(0, maxOf(0, stem.length - rest.length))).forEach { bracket ->
        tags += scanTags(bracket.groupValues[1]).tags
    }
    // 后缀里「无码破解」这类整词经标签词表认出，旗标要与标签一致
    uncensored = uncensored || tags.any { it.isUncensored() }
    // 通用词表不收「流出」，番号片的流出才是无码，出现在名字任何位置都算。「未流出」是没流出过
    uncensored = uncensored || ("流出" in stem && "未流出" !in stem)
    if (tags.any { it.isChineseSubtitles() }) {
        chinese = true
        letterC = false
    }
    tags.removeAll { it.isUncensored() || it.isChineseSubtitles() }
    if (uncensored) tags += MediaTag(TagKind.CENSORSHIP, MediaTag.UNCENSORED)
    if (chinese) tags += MediaTag(TagKind.SUBTITLES, MediaTag.CHINESE_SUBTITLES)
    val info = AvInfo(
        code = code.code, prefix = code.prefix, number = code.number, kind = code.kind,
        uncensored = uncensored, chineseSubtitles = chinese, part = part, site = site, marks = marks,
        // 片名取原文：按分隔符切开再拼回来，「vol.48」的点与片名里的连字符都丢了
        title = cleanAvTitle(titleSource),
        letterC = letterC && chinese,
    )
    return AvMatch(info, tags.distinct())
}
