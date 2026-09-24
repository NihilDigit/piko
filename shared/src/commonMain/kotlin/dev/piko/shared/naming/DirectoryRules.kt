package dev.piko.shared.naming

/**
 * 目录名的含义。分区以目录为主、文件名为辅：PV/、menu/ 这类专用目录说了算；SPs/、Extras/ 这类
 * 杂项目录只给默认分区，文件名里写明 NCOP、PV、Menu 的仍按文件名细分（VCB 的 SPs/ 里就混着这几类）。
 */
internal class DirectoryMeaning(
    val section: Section? = null,
    /** false 表示杂项目录：文件名里的 PV、NCOP、Menu、OVA、剧场版标记可以覆盖它。 */
    val authoritative: Boolean = true,
    val secondary: SecondaryReason? = null,
    /** 不携带作品名的目录（Season 1、720p、Subs），找作品名时跳过。 */
    val neutral: Boolean = false,
)

private val AUTHORITATIVE: Map<String, Section> = buildMap {
    listOf(
        "pv", "pvs", "cm", "cms", "trailer", "trailers", "teaser", "teasers", "preview", "previews", "promo", "promos",
        "dvd promos", "tv spots", "english tv spots", "jpn previews", "commercials", "予告", "预告", "spots",
    ).forEach { put(it, Section.PREVIEW) }
    listOf(
        "ncop", "nced", "ncop&nced", "ncop & nced", "ncop_nced", "ncop nced", "nc", "ncop&ed", "ncoped", "nced&ncop",
        "creditless", "openings", "endings", "op&ed", "op & ed", "ops & eds", "op_ed", "op ed", "oped", "op", "ed",
        "opening", "ending", "ops", "eds",
    ).forEach { put(it, Section.CREDITLESS) }
    listOf("menu", "menus", "メニュー", "菜单", "bd menu", "bd menus", "dvd menus").forEach { put(it, Section.MENU) }
    listOf(
        "特典", "特典映像", "映像特典", "tokuten", "bonus", "bd features", "features", "interviews", "interview", "featurettes",
        "making", "图集", "images", "bonus features", "特典视频",
    ).forEach { put(it, Section.BONUS) }
    listOf("movie", "movies", "剧场版", "劇場版", "films", "film", "gekijouban", "剧场版动画").forEach { put(it, Section.MOVIE) }
    listOf("ova", "ovas", "oad", "oads", "ova&oad", "ova & oad", "oav").forEach { put(it, Section.OVA) }
    listOf("cds", "cd", "ost", "osts", "soundtrack", "soundtracks", "soundtrack ost", "music", "音乐", "原声", "原声带").forEach {
        put(it, Section.OTHER)
    }
}

private val GENERIC: Map<String, Section> = buildMap {
    listOf("specials", "special", "sp", "season 00", "s00", "tv specials", "season 0").forEach { put(it, Section.SPECIAL) }
    listOf("sps", "extras", "extra", "others", "other", "misc", "bonus content").forEach { put(it, Section.BONUS) }
}

private val SECONDARY_DIRS: Map<String, SecondaryReason> = buildMap {
    listOf(
        "scans", "scan", "booklet", "booklets", "art book", "artbook", "画集", "covers", "cover", "jacket", "jackets",
        "linear notes", "扫图", "掃圖", "bk", "bks", "scan&bk",
    ).forEach { put(it, SecondaryReason.SCANS) }
    listOf("screens", "screen", "screenshots", "screenshot", "proof", "proofs", "截图", "截圖").forEach { put(it, SecondaryReason.SCREENSHOTS) }
    listOf("sample", "samples").forEach { put(it, SecondaryReason.SAMPLE) }
    listOf("fonts", "font", "字体", "字體", "attachments").forEach { put(it, SecondaryReason.FONTS) }
    listOf("logs", "log", "nfo", "nfo files", "md5", "info").forEach { put(it, SecondaryReason.INFO) }
}

private val NEUTRAL_DIR = Regex(
    """(?i)^(season|s|series|part|vol\.?|volume|disc|disk|box|set|batch|cd)\s*[\d一二三四五六七八九十]+.*$|^(subs?|subtitles?|字幕|english subtitles|srt subtitles|sub(title)?s? \w+|completed|content|tv|output|\d{3,4}p.*|.*内嵌.*|.*内封.*|chs|cht|gb|big5|720p avc|1080p avc|hevc|avc)$""",
)

internal fun directoryMeaning(rawName: String): DirectoryMeaning {
    val name = rawName.trim().lowercase()
        .replace(Regex("""^[0-9]{1,2}(?:[.\s_-]+)"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    AUTHORITATIVE[name]?.let { return DirectoryMeaning(section = it) }
    GENERIC[name]?.let { return DirectoryMeaning(section = it, authoritative = false) }
    SECONDARY_DIRS[name]?.let { return DirectoryMeaning(secondary = it, neutral = true) }
    // 组合写法：「Creditless OPs & EDs」「Part I ED_OP's」「Soundtrack OST」
    if ("creditless" in name || Regex("""\b(ed_op|op_ed|op&ed|ops & eds|nc ?op|nc ?ed)""").containsMatchIn(name)) {
        return DirectoryMeaning(section = Section.CREDITLESS)
    }
    if (Regex("""\b(soundtrack|ost)\b""").containsMatchIn(name)) return DirectoryMeaning(section = Section.OTHER)
    if (Regex("""\b(promos?|trailers?|previews?|tv spots?)\b""").containsMatchIn(name)) return DirectoryMeaning(section = Section.PREVIEW)
    if (Regex("""^(scans?|screens?|screenshots?)\b""").containsMatchIn(name)) {
        return DirectoryMeaning(secondary = if (name.startsWith("scan")) SecondaryReason.SCANS else SecondaryReason.SCREENSHOTS, neutral = true)
    }
    if (NEUTRAL_DIR.matches(name)) return DirectoryMeaning(neutral = true)
    return DirectoryMeaning(authoritative = false)
}

/** 原盘结构目录：路径里出现这些段时，其上一层就是一张盘。 */
internal val DISC_STRUCTURE_DIRS = setOf("BDMV", "VIDEO_TS", "CERTIFICATE", "AACS", "AUDIO_TS", "BDSVM")

/** 「DISC_01」「Disc 1」「D10」「Vol.1」「BLEACH SET 1 DISC 1」里的盘号。 */
private val DISC_NUMBER = Regex("""(?i)(?:^|[\s_\-.\[(])(?:disc|disk|dvd|bd|vol(?:ume)?\.?|d)[\s_.\-]?(\d{1,2})(?=$|[\s_\-.\])])""")

internal class DiscName(val title: String?, val number: Int?)

internal fun parseDiscName(name: String): DiscName {
    val match = DISC_NUMBER.findAll(name).lastOrNull()
    val number = match?.groupValues?.get(1)?.toIntOrNull()
    val rest = if (match != null) name.substring(0, match.range.first) else name
    val title = parseSeriesStem(rest).title
    return DiscName(title, number)
}
