package dev.piko.shared.media.player

import dev.piko.shared.i18n.tr

/**
 * 轨道在列表里的名字：容器里写了标题就用标题，再补上语言；都没有时按序号。
 * 同一文件里常有几条同语言的字幕（「简体」「简日双语」），只写语言分不开，所以标题优先。
 */
fun trackDisplayName(track: MediaTrack, index: Int): String {
    val language = track.language?.let(::languageName)
    val title = track.title?.trim()?.takeIf { it.isNotEmpty() }
    val base = when {
        title == null -> language ?: tr("轨道 ${index + 1}", "Track ${index + 1}", "トラック ${index + 1}", "트랙 ${index + 1}")
        language == null || title.contains(language) -> title
        else -> tr("$title（$language）", "$title ($language)", "$title（$language）", "$title ($language)")
    }
    return if (track.isExternal) tr("$base 外挂", "$base external", "$base 外部", "$base 외부") else base
}

/** 常见语言代码的中文名；认不出的原样返回。mpv 报的是 ISO 639-2 三字母码，也有文件写两字母码或区域码。 */
fun languageName(code: String): String {
    val key = code.trim().lowercase()
    return LANGUAGE_NAMES[key] ?: LANGUAGE_NAMES[key.substringBefore('-')] ?: code
}

private val LANGUAGE_NAMES: Map<String, String> get() = buildMap {
    listOf("chi", "zho", "zh", "chn").forEach { put(it, tr("中文", "Chinese", "中国語", "중국어")) }
    listOf("chs", "zh-hans", "zh-cn", "sc", "gb").forEach { put(it, tr("简体中文", "Simplified Chinese", "簡体字中国語", "중국어 간체")) }
    listOf("cht", "zh-hant", "zh-tw", "zh-hk", "tc", "big5").forEach { put(it, tr("繁体中文", "Traditional Chinese", "繁体字中国語", "중국어 번체")) }
    listOf("jpn", "ja", "jp").forEach { put(it, tr("日语", "Japanese", "日本語", "일본어")) }
    listOf("eng", "en").forEach { put(it, tr("英语", "English", "英語", "영어")) }
    listOf("kor", "ko").forEach { put(it, tr("韩语", "Korean", "韓国語", "한국어")) }
    listOf("fre", "fra", "fr").forEach { put(it, tr("法语", "French", "フランス語", "프랑스어")) }
    listOf("ger", "deu", "de").forEach { put(it, tr("德语", "German", "ドイツ語", "독일어")) }
    listOf("spa", "es").forEach { put(it, tr("西班牙语", "Spanish", "スペイン語", "스페인어")) }
    listOf("rus", "ru").forEach { put(it, tr("俄语", "Russian", "ロシア語", "러시아어")) }
    listOf("ita", "it").forEach { put(it, tr("意大利语", "Italian", "イタリア語", "이탈리아어")) }
    listOf("por", "pt").forEach { put(it, tr("葡萄牙语", "Portuguese", "ポルトガル語", "포르투갈어")) }
    listOf("tha", "th").forEach { put(it, tr("泰语", "Thai", "タイ語", "태국어")) }
    listOf("vie", "vi").forEach { put(it, tr("越南语", "Vietnamese", "ベトナム語", "베트남어")) }
    listOf("und").forEach { put(it, tr("未知语言", "Unknown language", "不明な言語", "알 수 없는 언어")) }
}

/**
 * 换集后按上一集的选择找对应轨道。编号在文件之间不稳定，只能比标题与语言：
 * 先找标题与语言都相同的，再找语言相同的。找不到返回 null，让后端的默认选择生效。
 */
fun matchTrack(tracks: List<MediaTrack>, preferred: MediaTrack): MediaTrack? =
    tracks.firstOrNull { it.title == preferred.title && it.language == preferred.language && it.isExternal == preferred.isExternal }
        ?: tracks.firstOrNull { it.title == preferred.title && it.language == preferred.language }
        ?: preferred.language?.let { language -> tracks.firstOrNull { it.language == language } }
