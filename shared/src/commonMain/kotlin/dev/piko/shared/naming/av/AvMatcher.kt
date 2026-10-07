package dev.piko.shared.naming.av

import dev.piko.shared.naming.MediaTag
import dev.piko.shared.naming.TagKind
import dev.piko.shared.naming.isCjk
import dev.piko.shared.naming.lookupTagWord
import dev.piko.shared.naming.scanTags

// 番号识别的流程。规则表在 AvRules.kt；通用的文件名解析只经 [matchAv] 一个入口进来。

private fun cleanAvTitle(description: String): String? {
    var title = description.replace(TRAILING_NOTE, "")
    while (LEADING_MARK.containsMatchIn(title)) title = title.replaceFirst(LEADING_MARK, "")
    while (TRAILING_MARK.containsMatchIn(title)) title = title.replaceFirst(TRAILING_MARK, "")
    return title.trim(' ', '　', '-', '_', '.').takeIf { it.count(Char::isLetter) >= 2 }
}

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

/** 返回（归一后的番号，番号在文本里的结束位置）。 */
private fun matchCode(text: String, strict: Boolean): Pair<String, Int>? {
    FC_GLUED.find(text)?.let { return "FC2-PPV-${it.groupValues[1]}" to it.range.last + 1 }
    FC2.find(text)?.let { return "FC2-PPV-${it.groupValues[1]}" to it.range.last + 1 }
    ONE_PONDO.find(text)?.let { return "1PON-${it.groupValues[1]}_${it.groupValues[2]}" to it.range.last + 1 }
    CARIB.find(text)?.let { return "CARIB-${it.groupValues[1]}-${it.groupValues[2]}" to it.range.last + 1 }
    CARIB_TRAILING.find(text)?.let { return "CARIB-${it.groupValues[1]}-${it.groupValues[2]}" to it.range.last + 1 }
    HEYDOUGA.find(text)?.let { return "HEYDOUGA-${it.groupValues[1]}-${it.groupValues[2]}" to it.range.last + 1 }
    TOKYO_HOT.find(text)?.let { return "${it.groupValues[1].uppercase()}${it.groupValues[2]}" to it.range.last + 1 }
    NUMBERED_LABEL.find(text)?.let { match ->
        val label = match.groupValues[1]
        if (label.drop(3).let { it == it.uppercase() || it == it.lowercase() }) return "${label.uppercase()}-${match.groupValues[2]}" to match.range.last + 1
    }
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
    val pieceMatches = SUFFIX_PIECE.findAll(suffixText).toList()
    val pieces = pieceMatches.map { it.groupValues[1] to it.groupValues[2] }
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
    // 通用词表不收「流出」，番号片的流出才是无码，出现在名字任何位置都算。「未流出」是没流出过
    uncensored = uncensored || ("流出" in stem && "未流出" !in stem)
    chinese = chinese || tags.any { it.kind == TagKind.SUBTITLES && it.text == MediaTag.CHINESE_SUBTITLES }
    if (uncensored) tags += MediaTag(TagKind.CENSORSHIP, MediaTag.UNCENSORED)
    if (chinese) tags += MediaTag(TagKind.SUBTITLES, MediaTag.CHINESE_SUBTITLES)
    val info = AvInfo(
        code = code, uncensored = uncensored, chineseSubtitles = chinese, part = part, site = site, marks = marks,
        // 片名取原文：按分隔符切开再拼回来，「vol.48」的点与片名里的连字符都丢了
        title = cleanAvTitle(pieceMatches.getOrNull(consumed)?.let { suffixText.substring(it.groups[2]!!.range.first) }.orEmpty()),
    )
    return AvMatch(info, tags.distinct())
}
