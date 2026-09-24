package dev.piko.shared.media.player

/**
 * 轨道在列表里的名字：容器里写了标题就用标题，再补上语言；都没有时按序号。
 * 同一文件里常有几条同语言的字幕（「简体」「简日双语」），只写语言分不开，所以标题优先。
 */
fun trackDisplayName(track: MediaTrack, index: Int): String {
    val language = track.language?.let(::languageName)
    val title = track.title?.trim()?.takeIf { it.isNotEmpty() }
    val base = when {
        title == null -> language ?: "轨道 ${index + 1}"
        language == null || title.contains(language) -> title
        else -> "$title（$language）"
    }
    return if (track.isExternal) "$base 外挂" else base
}

/** 常见语言代码的中文名；认不出的原样返回。mpv 报的是 ISO 639-2 三字母码，也有文件写两字母码或区域码。 */
fun languageName(code: String): String {
    val key = code.trim().lowercase()
    return LANGUAGE_NAMES[key] ?: LANGUAGE_NAMES[key.substringBefore('-')] ?: code
}

private val LANGUAGE_NAMES = buildMap {
    listOf("chi", "zho", "zh", "chn").forEach { put(it, "中文") }
    listOf("chs", "zh-hans", "zh-cn", "sc", "gb").forEach { put(it, "简体中文") }
    listOf("cht", "zh-hant", "zh-tw", "zh-hk", "tc", "big5").forEach { put(it, "繁体中文") }
    listOf("jpn", "ja", "jp").forEach { put(it, "日语") }
    listOf("eng", "en").forEach { put(it, "英语") }
    listOf("kor", "ko").forEach { put(it, "韩语") }
    listOf("fre", "fra", "fr").forEach { put(it, "法语") }
    listOf("ger", "deu", "de").forEach { put(it, "德语") }
    listOf("spa", "es").forEach { put(it, "西班牙语") }
    listOf("rus", "ru").forEach { put(it, "俄语") }
    listOf("ita", "it").forEach { put(it, "意大利语") }
    listOf("por", "pt").forEach { put(it, "葡萄牙语") }
    listOf("tha", "th").forEach { put(it, "泰语") }
    listOf("vie", "vi").forEach { put(it, "越南语") }
    listOf("und").forEach { put(it, "未知语言") }
}

/**
 * 换集后按上一集的选择找对应轨道。编号在文件之间不稳定，只能比标题与语言：
 * 先找标题与语言都相同的，再找语言相同的。找不到返回 null，让后端的默认选择生效。
 */
fun matchTrack(tracks: List<MediaTrack>, preferred: MediaTrack): MediaTrack? =
    tracks.firstOrNull { it.title == preferred.title && it.language == preferred.language && it.isExternal == preferred.isExternal }
        ?: tracks.firstOrNull { it.title == preferred.title && it.language == preferred.language }
        ?: preferred.language?.let { language -> tracks.firstOrNull { it.language == language } }
