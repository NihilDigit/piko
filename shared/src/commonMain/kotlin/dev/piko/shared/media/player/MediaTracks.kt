package dev.piko.shared.media.player

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr

/** 中文轨道的简繁变体分类：区分简体、繁体与未标明变体的通用中文。 */
enum class ChineseSubtitleVariant {
    Simplified,
    Traditional,
    Generic,
}

/** 根据当前界面语言（或系统回退语言）确定自动优先选中的中文简繁变体。 */
fun preferredChineseSubtitleVariant(
    effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
    systemLanguage: AppLanguage = PikoLocale.systemLanguage,
): ChineseSubtitleVariant = when {
    effectiveLanguage == AppLanguage.ZH_HANT -> ChineseSubtitleVariant.Traditional
    effectiveLanguage == AppLanguage.ZH_CN -> ChineseSubtitleVariant.Simplified
    systemLanguage == AppLanguage.ZH_HANT -> ChineseSubtitleVariant.Traditional
    else -> ChineseSubtitleVariant.Simplified
}

val MediaTrack.chineseVariant: ChineseSubtitleVariant?
    get() = detectChineseSubtitleVariant(language = language, title = title, externalFilename = externalFilename)

/**
 * 综合轨道语言码、轨道标题与外挂文件名识别中文简繁变体。
 *
 * 优先级：显式简繁语言码 > 标题中的简繁标识 > 外挂文件名中的简繁标识 > 通用中文标识（chi/zho/zh 等）。
 * 兼容 Android ICU 正则：仅使用 `[0-9]` 与 `\p{script=Han}`。
 */
fun detectChineseSubtitleVariant(
    language: String?,
    title: String?,
    externalFilename: String? = null,
): ChineseSubtitleVariant? {
    val langVariant = language?.let(::classifyChineseSource)
    if (langVariant == ChineseSubtitleVariant.Simplified || langVariant == ChineseSubtitleVariant.Traditional) {
        return langVariant
    }
    val titleVariant = title?.let(::classifyChineseSource)
    if (titleVariant == ChineseSubtitleVariant.Simplified || titleVariant == ChineseSubtitleVariant.Traditional) {
        return titleVariant
    }
    val fileVariant = externalFilename?.let(::extractFilenameStem)?.let(::classifyChineseSource)
    if (fileVariant == ChineseSubtitleVariant.Simplified || fileVariant == ChineseSubtitleVariant.Traditional) {
        return fileVariant
    }
    if (langVariant == ChineseSubtitleVariant.Generic ||
        titleVariant == ChineseSubtitleVariant.Generic ||
        fileVariant == ChineseSubtitleVariant.Generic
    ) {
        return ChineseSubtitleVariant.Generic
    }
    return null
}

/**
 * 为外挂字幕（自动匹配或手动加载）推断传给 mpv `sub-add` 的语言代码（chs / cht / chi / eng / jpn 等）。
 */
fun inferSubtitleLanguageCode(label: String?, fileName: String? = null): String? {
    when (detectChineseSubtitleVariant(language = null, title = label, externalFilename = fileName)) {
        ChineseSubtitleVariant.Simplified -> return "chs"
        ChineseSubtitleVariant.Traditional -> return "cht"
        ChineseSubtitleVariant.Generic -> return "chi"
        null -> Unit
    }
    val combined = listOfNotNull(label, fileName?.let(::extractFilenameStem)).joinToString(" ")
    if (combined.isBlank()) return null
    val trimmedLabel = label?.trim().orEmpty()
    when {
        trimmedLabel.startsWith("英") -> return "eng"
        trimmedLabel.startsWith("日") -> return "jpn"
        trimmedLabel.startsWith("韩") || trimmedLabel.startsWith("韓") -> return "kor"
    }
    val tokens = tokenizeAsciiWords(combined)
    return when {
        tokens.any { it in ENGLISH_TOKENS } -> "eng"
        tokens.any { it in JAPANESE_TOKENS } -> "jpn"
        tokens.any { it in KOREAN_TOKENS } -> "kor"
        else -> null
    }
}

/**
 * 轨道在列表里的名字：区分简体中文 / 繁体中文 / 通用中文，保留额外描述（如「简日双语」「繁日双语」），
 * 同时避免重复拼接相同含义的文字（例如不会出现「简体中文（简体中文）」或「CHS（简体中文）」）。
 */
fun trackDisplayName(track: MediaTrack, index: Int): String {
    val variant = track.chineseVariant
    val language = when (variant) {
        ChineseSubtitleVariant.Simplified -> tr("简体中文", "Simplified Chinese", "簡体字中国語", "중국어 간체", "簡體中文")
        ChineseSubtitleVariant.Traditional -> tr("繁体中文", "Traditional Chinese", "繁体字中国語", "중국어 번체", "繁體中文")
        ChineseSubtitleVariant.Generic -> tr("中文", "Chinese", "中国語", "중국어", "中文")
        null -> track.language?.let(::languageName)
    }
    val title = track.title?.trim()?.takeIf { it.isNotEmpty() }
    val base = when {
        title == null -> language ?: tr("轨道 ${index + 1}", "Track ${index + 1}", "トラック ${index + 1}", "트랙 ${index + 1}", "軌道 ${index + 1}")
        language == null -> title
        isPureLanguagePlaceholder(title, variant, language) -> language
        titleAlreadyExpressesLanguage(title, variant, language) -> title
        else -> tr("$title（$language）", "$title ($language)", "$title（$language）", "$title ($language)", "$title（$language）")
    }
    return if (track.isExternal) tr("$base 外挂", "$base external", "$base 外部", "$base 외부", "$base 外掛") else base
}

/** 常见语言代码的本地化名称；认不出的原样返回。mpv 报的是 ISO 639-2 三字母码，也有文件写两字母码或区域码。 */
fun languageName(code: String): String {
    val key = code.trim().lowercase().replace('_', '-')
    return LANGUAGE_NAMES[key] ?: LANGUAGE_NAMES[key.substringBefore('-')] ?: code
}

private val LANGUAGE_NAMES: Map<String, String> get() = buildMap {
    listOf("chi", "zho", "zh", "chn", "cmn").forEach { put(it, tr("中文", "Chinese", "中国語", "중국어", "中文")) }
    listOf("chs", "zhs", "zh-hans", "zh-cn", "zh-sg", "sc", "gb", "gbk", "gb2312", "gb18030", "simplified")
        .forEach { put(it, tr("简体中文", "Simplified Chinese", "簡体字中国語", "중국어 간체", "簡體中文")) }
    listOf("cht", "zht", "zh-hant", "zh-tw", "zh-hk", "zh-mo", "tc", "big5", "traditional")
        .forEach { put(it, tr("繁体中文", "Traditional Chinese", "繁体字中国語", "중국어 번체", "繁體中文")) }
    listOf("jpn", "ja", "jp").forEach { put(it, tr("日语", "Japanese", "日本語", "일본어", "日語")) }
    listOf("eng", "en").forEach { put(it, tr("英语", "English", "英語", "영어", "英語")) }
    listOf("kor", "ko").forEach { put(it, tr("韩语", "Korean", "韓国語", "한국어", "韓語")) }
    listOf("fre", "fra", "fr").forEach { put(it, tr("法语", "French", "フランス語", "프랑스어", "法語")) }
    listOf("ger", "deu", "de").forEach { put(it, tr("德语", "German", "ドイツ語", "독일어", "德語")) }
    listOf("spa", "es").forEach { put(it, tr("西班牙语", "Spanish", "スペイン語", "스페인어", "西班牙語")) }
    listOf("rus", "ru").forEach { put(it, tr("俄语", "Russian", "ロシア語", "러시아어", "俄語")) }
    listOf("ita", "it").forEach { put(it, tr("意大利语", "Italian", "イタリア語", "이탈리아어", "義大利語")) }
    listOf("por", "pt").forEach { put(it, tr("葡萄牙语", "Portuguese", "ポルトガル語", "포르투갈어", "葡萄牙語")) }
    listOf("tha", "th").forEach { put(it, tr("泰语", "Thai", "タイ語", "태국어", "泰語")) }
    listOf("vie", "vi").forEach { put(it, tr("越南语", "Vietnamese", "ベトナム語", "베트남어", "越南語")) }
    listOf("und").forEach { put(it, tr("未知语言", "Unknown language", "不明な言語", "알 수 없는 언어", "未知語言")) }
}

/**
 * 按当前语言偏好从字幕轨列表中挑选最佳中文轨道（优先匹配简/繁变体，其次通用中文）。
 * 当 MKV 多条内封中文字幕都标着 `lang=chi` 时，仅靠 mpv `slang` 无法区分简繁，需由此结合标题与文件名校正。
 */
fun selectPreferredSubtitleTrack(
    tracks: List<MediaTrack>,
    preferredVariant: ChineseSubtitleVariant = preferredChineseSubtitleVariant(),
): MediaTrack? {
    if (tracks.isEmpty()) return null
    val exact = tracks.firstOrNull { it.chineseVariant == preferredVariant }
    if (exact != null) return exact
    val generic = tracks.firstOrNull { it.chineseVariant == ChineseSubtitleVariant.Generic }
    if (generic != null) return generic
    val opposite = when (preferredVariant) {
        ChineseSubtitleVariant.Simplified -> ChineseSubtitleVariant.Traditional
        ChineseSubtitleVariant.Traditional -> ChineseSubtitleVariant.Simplified
        ChineseSubtitleVariant.Generic -> null
    }
    return opposite?.let { alt -> tracks.firstOrNull { it.chineseVariant == alt } }
}

/**
 * 换集后按上一集的选择找对应轨道。编号在文件之间不稳定，只能比标题、简繁变体与语言：
 * 先找标题与语言完全相同的，再按同一中文简繁变体（区分简/繁）匹配，最后回退到语言码相同。
 * 找不到返回 null，让后端的默认选择生效。
 */
fun matchTrack(tracks: List<MediaTrack>, preferred: MediaTrack): MediaTrack? {
    tracks.firstOrNull {
        it.title == preferred.title && it.language == preferred.language && it.isExternal == preferred.isExternal
    }?.let { return it }
    tracks.firstOrNull {
        it.title == preferred.title && it.language == preferred.language
    }?.let { return it }
    val preferredVariant = preferred.chineseVariant
    if (preferredVariant == ChineseSubtitleVariant.Simplified || preferredVariant == ChineseSubtitleVariant.Traditional) {
        tracks.firstOrNull { it.chineseVariant == preferredVariant && it.isExternal == preferred.isExternal }?.let { return it }
        tracks.firstOrNull { it.chineseVariant == preferredVariant }?.let { return it }
    }
    return preferred.language?.let { language -> tracks.firstOrNull { it.language == language } }
}

private fun extractFilenameStem(path: String): String {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    return fileName.substringBeforeLast('.', fileName)
}

private fun classifyChineseSource(raw: String): ChineseSubtitleVariant? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    val normalized = lower.replace('_', '-')

    // 先检查双简繁共现（简繁 / chs&cht / sc_tc / gb-big5）→ 归类为通用中文，避免误判为单一变体
    if (DUAL_SCRIPT_HINTS.any { it in lower } || DUAL_SCRIPT_REGEX.containsMatchIn(normalized)) {
        return ChineseSubtitleVariant.Generic
    }

    val hasChsCjk = CHS_CJK_HINTS.any { it in text } || STANDALONE_CHS_HAN_REGEX.containsMatchIn(text)
    val hasChtCjk = CHT_CJK_HINTS.any { it in text } || STANDALONE_CHT_HAN_REGEX.containsMatchIn(text)
    if (hasChsCjk && !hasChtCjk) return ChineseSubtitleVariant.Simplified
    if (hasChtCjk && !hasChsCjk) return ChineseSubtitleVariant.Traditional
    if (hasChsCjk && hasChtCjk) return ChineseSubtitleVariant.Generic

    val hasChsAscii = CHS_COMPOUND_REGEX.containsMatchIn(normalized) ||
        hasAsciiVariantToken(normalized, CHS_EXACT_TOKENS, rejectDigitPrefixedGb = true)
    val hasChtAscii = CHT_COMPOUND_REGEX.containsMatchIn(normalized) ||
        hasAsciiVariantToken(normalized, CHT_EXACT_TOKENS, rejectDigitPrefixedGb = false)
    if (hasChsAscii && !hasChtAscii) return ChineseSubtitleVariant.Simplified
    if (hasChtAscii && !hasChsAscii) return ChineseSubtitleVariant.Traditional
    if (hasChsAscii && hasChtAscii) return ChineseSubtitleVariant.Generic

    if (GENERIC_CJK_HINTS.any { it in text } ||
        hasAsciiVariantToken(normalized, GENERIC_CHINESE_TOKENS, rejectDigitPrefixedGb = false)
    ) {
        return ChineseSubtitleVariant.Generic
    }
    return null
}

private fun hasAsciiVariantToken(
    normalized: String,
    targets: Set<String>,
    rejectDigitPrefixedGb: Boolean,
): Boolean {
    val len = normalized.length
    var i = 0
    while (i < len) {
        while (i < len && !isAsciiAlnum(normalized[i])) i++
        val start = i
        while (i < len && isAsciiAlnum(normalized[i])) i++
        if (start >= i) continue
        val token = normalized.substring(start, i)
        if (token in targets) {
            if (rejectDigitPrefixedGb && token == "gb" && start > 0 && normalized[start - 1] in '0'..'9') {
                continue
            }
            return true
        }
    }
    return false
}

private fun tokenizeAsciiWords(raw: String): List<String> {
    val lower = raw.lowercase()
    val result = ArrayList<String>(4)
    val len = lower.length
    var i = 0
    while (i < len) {
        while (i < len && !isAsciiAlnum(lower[i])) i++
        val start = i
        while (i < len && isAsciiAlnum(lower[i])) i++
        if (start < i) result.add(lower.substring(start, i))
    }
    return result
}

private fun isAsciiAlnum(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9'

private fun isPureLanguagePlaceholder(
    title: String,
    variant: ChineseSubtitleVariant?,
    languageLabel: String,
): Boolean {
    if (title.equals(languageLabel, ignoreCase = true)) return true
    val compact = title.trim().lowercase().replace('_', '-').replace(WHITESPACE_RUN_REGEX, " ")
    if (compact in GENERIC_PLACEHOLDER_TITLES) return true
    return when (variant) {
        ChineseSubtitleVariant.Simplified -> compact in PURE_CHS_TITLES
        ChineseSubtitleVariant.Traditional -> compact in PURE_CHT_TITLES
        ChineseSubtitleVariant.Generic -> compact in PURE_GENERIC_ZH_TITLES
        null -> false
    }
}

private fun titleAlreadyExpressesLanguage(
    title: String,
    variant: ChineseSubtitleVariant?,
    languageLabel: String,
): Boolean {
    if (title.contains(languageLabel, ignoreCase = true)) return true
    if (variant == null) return false
    val isChineseUi = PikoLocale.effectiveLanguage == AppLanguage.ZH_CN ||
        PikoLocale.effectiveLanguage == AppLanguage.ZH_HANT
    if (!isChineseUi) return false
    return when (variant) {
        ChineseSubtitleVariant.Simplified -> CHS_DISPLAY_ALREADY_CLEAR.any { it in title }
        ChineseSubtitleVariant.Traditional -> CHT_DISPLAY_ALREADY_CLEAR.any { it in title }
        ChineseSubtitleVariant.Generic -> GENERIC_CJK_HINTS.any { it in title }
    }
}

private val WHITESPACE_RUN_REGEX = Regex("""[ \t]+""")

private val DUAL_SCRIPT_HINTS = listOf("简繁", "簡繁")
private val DUAL_SCRIPT_REGEX = Regex("""(?<![a-z0-9])(chs[-&+ ]+cht|cht[-&+ ]+chs|sc[-&+ ]+tc|tc[-&+ ]+sc|gb[-&+ ]+big5|big5[-&+ ]+gb)(?![a-z0-9])""")

private val CHS_CJK_HINTS = listOf("简体", "簡體", "简中", "簡中", "简日", "簡日", "简英", "簡英", "简双", "簡雙", "简字", "簡字")
private val CHT_CJK_HINTS = listOf("繁体", "繁體", "正体", "正體", "繁中", "繁日", "繁英", "繁双", "繁雙", "繁字")
private val GENERIC_CJK_HINTS = listOf("中文", "中字", "国语", "國語", "汉语", "漢語")

// 仅当「简」「簡」「繁」前后不是其他汉字时才算独立简繁标（避免命中人名/片名中的「简单」「繁荣」等词）
private val STANDALONE_CHS_HAN_REGEX = Regex("""(?<!\p{script=Han})[简簡](?!\p{script=Han})""")
private val STANDALONE_CHT_HAN_REGEX = Regex("""(?<!\p{script=Han})繁(?!\p{script=Han})""")

private val CHS_COMPOUND_REGEX = Regex("""(?<![a-z0-9])(zh-hans|zh-cn|zh-sg|gb-cn|chs-jp|chs-jpn|gb-jp|sc-jp|jp-sc|chs&jp|chs&jpn)(?![a-z0-9])""")
private val CHT_COMPOUND_REGEX = Regex("""(?<![a-z0-9])(zh-hant|zh-tw|zh-hk|zh-mo|cht-jp|cht-jpn|big5-jp|tc-jp|jp-tc|cht&jp|cht&jpn)(?![a-z0-9])""")

private val CHS_EXACT_TOKENS = setOf("chs", "sc", "gb", "gbk", "gb2312", "gb18030", "hans", "zhs", "simplified", "scjp", "jpsc", "chsjp", "jpchs")
private val CHT_EXACT_TOKENS = setOf("cht", "tc", "big5", "hant", "zht", "traditional", "tcjp", "jptc", "chtjp", "jpcht")
private val GENERIC_CHINESE_TOKENS = setOf("chi", "zho", "zh", "chn", "cmn", "chinese")

private val ENGLISH_TOKENS = setOf("en", "eng", "english")
private val JAPANESE_TOKENS = setOf("ja", "jp", "jpn", "japanese")
private val KOREAN_TOKENS = setOf("ko", "kor", "korean")

private val GENERIC_PLACEHOLDER_TITLES = setOf("外挂字幕", "外掛字幕", "external subtitles", "外部字幕", "외부 자막")

private val PURE_CHS_TITLES = setOf(
    "chs", "sc", "gb", "gbk", "gb2312", "gb18030", "hans", "zhs",
    "zh-hans", "zh-cn", "zh-sg", "simplified", "simplified chinese", "chinese (simplified)",
    "简", "簡", "简体", "簡體", "简中", "簡中", "简体中文", "簡體中文", "简体字幕", "簡體字幕",
)

private val PURE_CHT_TITLES = setOf(
    "cht", "tc", "big5", "hant", "zht",
    "zh-hant", "zh-tw", "zh-hk", "zh-mo", "traditional", "traditional chinese", "chinese (traditional)",
    "繁", "繁体", "繁體", "正体", "正體", "繁中", "繁体中文", "繁體中文", "正體中文", "繁体字幕", "繁體字幕", "正體字幕",
)

private val PURE_GENERIC_ZH_TITLES = setOf(
    "chi", "zho", "zh", "chn", "cmn", "chinese", "中文", "中文字幕", "中字",
)

private val CHS_DISPLAY_ALREADY_CLEAR = listOf("简体", "簡體", "简中", "簡中", "简日", "簡日", "简英", "簡英", "简双", "簡雙")
private val CHT_DISPLAY_ALREADY_CLEAR = listOf("繁体", "繁體", "正体", "正體", "繁中", "繁日", "繁英", "繁双", "繁雙")
