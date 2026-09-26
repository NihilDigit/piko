package dev.piko.shared.naming

import dev.piko.data.repository.FileCategory
import dev.piko.data.repository.fileCategory

private val LINK_EXTENSIONS = setOf("url", "lnk", "html", "htm", "mht", "mhtml", "chm", "webloc", "desktop")
private val PROGRAM_EXTENSIONS = setOf("apk", "exe", "msi", "bat", "cmd", "scr", "com", "ipa", "sh", "php")
private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc", "woff", "woff2", "fon")
private val DISC_IMAGE_EXTENSIONS = setOf("iso", "mds", "mdf", "img", "nrg")
private val DISC_METADATA_EXTENSIONS = setOf("clpi", "mpls", "bdmv", "bdjo", "jar", "cer", "ifo", "bup", "inf", "crt", "tbl", "cci")
// 基础表里没有的图片与音频扩展名
private val EXTRA_IMAGE_EXTENSIONS = setOf("tif", "tiff", "jxl")
private val EXTRA_AUDIO_EXTENSIONS = setOf("dsf", "dff", "wv", "tta", "alac", "aiff", "ac3", "dts", "eac3", "thd")

internal fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

internal fun fileKindOf(name: String): FileKind {
    val ext = extensionOf(name)
    return when {
        ext in LINK_EXTENSIONS -> FileKind.LINK
        ext in PROGRAM_EXTENSIONS -> FileKind.PROGRAM
        ext in FONT_EXTENSIONS -> FileKind.FONT
        ext in DISC_IMAGE_EXTENSIONS -> FileKind.DISC_IMAGE
        ext in DISC_METADATA_EXTENSIONS -> FileKind.DISC_METADATA
        ext in EXTRA_IMAGE_EXTENSIONS -> FileKind.IMAGE
        ext in EXTRA_AUDIO_EXTENSIONS -> FileKind.AUDIO
        else -> name.fileCategory().toFileKind()
    }
}

fun FileCategory.toFileKind(): FileKind = when (this) {
    FileCategory.VIDEO -> FileKind.VIDEO
    FileCategory.AUDIO -> FileKind.AUDIO
    FileCategory.IMAGE -> FileKind.IMAGE
    FileCategory.ARCHIVE -> FileKind.ARCHIVE
    FileCategory.SUBTITLE -> FileKind.SUBTITLE
    FileCategory.DOCUMENT -> FileKind.DOCUMENT
}

internal fun isCjk(c: Char): Boolean {
    val code = c.code
    return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0x3040..0x30FF ||
        code in 0xF900..0xFAFF || code in 0xAC00..0xD7AF
}

internal fun resolutionTag(height: Int): MediaTag {
    val text = when {
        height >= 2000 -> "4K"
        height in 1000..1199 -> "1080p"
        height in 700..799 -> "720p"
        height in 560..599 -> "576p"
        height in 450..499 -> "480p"
        else -> "${height}p"
    }
    return MediaTag(TagKind.RESOLUTION, text)
}

private fun tag(kind: TagKind, text: String) = listOf(MediaTag(kind, text))

/**
 * 技术标签的词表。键是小写的单个记号；值为空列表表示「认得、但不值得显示」，
 * 如 CRC、容器名、「END」，这类记号同样能证明所在的方括号是标签段而不是作品名。
 */
private val WORDS: Map<String, List<MediaTag>> = buildMap {
    fun put(kind: TagKind, text: String, vararg keys: String) = keys.forEach { put(it, tag(kind, text)) }
    fun noise(vararg keys: String) = keys.forEach { put(it, emptyList()) }

    // 系统镜像的架构名，其中的数字不是集号
    noise("x86_64", "x86-64", "x64", "amd64", "arm64", "aarch64", "i386", "i686")
    // scene release 的惯用词
    noise("xxx", "siterip", "rarbg")
    put(TagKind.VIDEO_CODEC, "HEVC", "hevc", "h265", "x265", "h.265", "hevc10")
    put(TagKind.VIDEO_CODEC, "AVC", "avc", "h264", "x264", "h.264")
    put(TagKind.VIDEO_CODEC, "AV1", "av1")
    put(TagKind.VIDEO_CODEC, "VP9", "vp9")
    put(TagKind.VIDEO_CODEC, "MPEG-2", "mpeg2", "mpeg-2")
    put(TagKind.VIDEO_CODEC, "XviD", "xvid", "divx")
    put(TagKind.VIDEO_CODEC, "VC-1", "vc1", "vc-1")
    put(TagKind.BIT_DEPTH, "10bit", "10bit", "10-bit", "hi10p", "hi10", "ma10p", "main10", "main10p", "yuv420p10")
    put(TagKind.BIT_DEPTH, "8bit", "8bit", "8-bit")
    put(TagKind.AUDIO_CODEC, "AAC", "aac", "aacx2")
    put(TagKind.AUDIO_CODEC, "FLAC", "flac", "flacx2", "flacx3")
    put(TagKind.AUDIO_CODEC, "AC3", "ac3", "ac-3")
    put(TagKind.AUDIO_CODEC, "E-AC3", "eac3", "e-ac3", "e-ac-3", "ddp", "dd+")
    put(TagKind.AUDIO_CODEC, "DTS", "dts")
    put(TagKind.AUDIO_CODEC, "DTS-HD", "dts-hd", "dtshd", "dts-hdma", "dts-hd-ma")
    put(TagKind.AUDIO_CODEC, "TrueHD", "truehd", "true-hd")
    put(TagKind.AUDIO_CODEC, "Opus", "opus")
    put(TagKind.AUDIO_CODEC, "LPCM", "lpcm", "pcm")
    put(TagKind.AUDIO_CODEC, "MP3", "mp3")
    put(TagKind.AUDIO_CODEC, "DD", "dd")
    put(TagKind.SOURCE, "BD", "bd", "bluray", "blu-ray", "usbd", "jpbd", "ukbd", "hkgbd")
    put(TagKind.SOURCE, "BDRip", "bdrip", "bd-rip", "brrip", "bdrips")
    put(TagKind.SOURCE, "Remux", "remux", "bdremux")
    put(TagKind.SOURCE, "BDMV", "bdmv")
    put(TagKind.SOURCE, "BDISO", "bdiso")
    put(TagKind.SOURCE, "WEB-DL", "web", "web-dl", "webdl")
    put(TagKind.SOURCE, "WebRip", "webrip", "web-rip")
    put(TagKind.SOURCE, "DVD", "dvd", "dvd5", "dvd9", "dvdiso", "r2j", "r1")
    put(TagKind.SOURCE, "DVDRip", "dvdrip", "dvd-rip")
    put(TagKind.SOURCE, "HDTV", "hdtv", "hdtvrip", "sdtv")
    put(TagKind.SOURCE, "TV", "tv", "tvrip", "tv-rip")
    put(TagKind.SOURCE, "VHSRip", "vhsrip")
    put(TagKind.PLATFORM, "Baha", "baha")
    put(TagKind.PLATFORM, "CR", "cr")
    put(TagKind.PLATFORM, "NF", "nf")
    put(TagKind.PLATFORM, "AMZN", "amzn")
    put(TagKind.PLATFORM, "DSNP", "dsnp")
    put(TagKind.PLATFORM, "Hulu", "hulu")
    put(TagKind.PLATFORM, "ABEMA", "abema")
    put(TagKind.PLATFORM, "B-Global", "b-global", "bilibili")
    put(TagKind.PLATFORM, "AT-X", "at-x", "atx")
    put(TagKind.PLATFORM, "BS11", "bs11")
    put(TagKind.PLATFORM, "BS-NTV", "bs-ntv")
    put(TagKind.PLATFORM, "BS-Fuji", "bs-fuji")
    put(TagKind.PLATFORM, "NHK", "nhk")
    put(TagKind.PLATFORM, "NBN", "nbn")
    put(TagKind.PLATFORM, "TVer", "tver")
    put(TagKind.PLATFORM, "YouTube", "ytb")
    put(TagKind.RESOLUTION, "4K", "4k", "uhd", "bsp4k", "2160p")
    put(TagKind.RESOLUTION, "1080p", "fhd")
    put(TagKind.RESOLUTION, "HD", "hd")
    put(TagKind.SUBTITLES, "简", "chs", "gb", "gb-cn", "sc")
    put(TagKind.SUBTITLES, "繁", "cht", "big5", "tc")
    put(TagKind.SUBTITLES, "简繁", "chs-cht", "gb-big5")
    put(TagKind.SUBTITLES, "繁日", "jptc", "cht-jp", "tcjp")
    put(TagKind.SUBTITLES, "简日", "jpsc", "chs-jp", "scjp")
    put(TagKind.SUBTITLES, "英", "eng", "eng-sub", "eng-subs", "english-sub", "english-subs")
    put(TagKind.SUBTITLES, "多语字幕", "multi-sub", "multi-subs", "multisub", "multisubs", "multiple-subtitle",
        "multiple-subtitles", "multi-audio-subs", "trisub", "multi-subtitle", "multi-subtitles")
    put(TagKind.SUBTITLES, "内嵌", "hardsub", "hardsubs")
    put(TagKind.AUDIO_LANGUAGE, "双音轨", "dual-audio", "dual", "dualaudio")
    put(TagKind.AUDIO_LANGUAGE, "多音轨", "multi-audio", "multiaudio")
    put(TagKind.AUDIO_LANGUAGE, "配音", "dubbed", "english-dub", "eng-dub", "dublado", "dub")
    put(TagKind.CENSORSHIP, MediaTag.UNCENSORED, "uncensored", "ucensored", "uncen", "uncensor")
    put(TagKind.CENSORSHIP, "减码", "reducing-mosaic")
    put(TagKind.EDITION, "重制", "remaster", "remastered")
    put(TagKind.EDITION, "HDR", "hdr", "hdr10")
    // 同一部片子常同时发 SDR、HDR10、杜比视界几个版本，它们得是标签，版本合并才认得出来
    put(TagKind.EDITION, "DoVi", "dovi", "dv")
    put(TagKind.EDITION, "SDR", "sdr")
    put(TagKind.EDITION, "Uncut", "uncut")
    noise(
        "end", "fin", "batch", "complete", "completo", "unofficial", "mkv", "mp4", "avi", "chap", "sub", "subs",
        "pseudo", "rev", "reseed", "hq", "hbr", "hi-fi", "mono", "stereo", "x2", "cc", "raw", "raws",
        "vfr", "cfr", "nvenc", "ma", "hdma", "hd-ma", "softsubs", "softsub", "ass", "srt", "assx2", "srtx2",
        "ass×2", "upscale", "upscaled", "jpn", "japanese", "english", "chinese", "mp4/720p", "uncropped", "cropped",
        "tri-audio", "hevc_aac", "regrade", "retail",
    )
}

/**
 * 这些词虽在词表里，却不能作为标签段的起点：它们也是普通英文单词，或常作为作品名的注释出现，
 * 如「Black Clover (TV) - 001」里的 (TV)。
 */
private val WEAK_TITLE_WORDS = setOf(
    "end", "fin", "complete", "sub", "subs", "dual", "raw", "hd", "batch", "uncut", "remaster", "cc", "chap", "tv",
    "rev", "mono", "stereo", "english", "japanese", "chinese", "the", "ed", "sp", "ncop", "dub", "dv", "sc", "tc",
    "gb", "cr", "nf", "web", "ma", "hq", "dd", "remastered", "dubbed", "pcm",
)

private val RESOLUTION_P = Regex("""^(\d{3,4})[pPiI]$""")

// 「NNNp」只认常见的画面高度：合集名里的「200P」「435P」是图片张数
internal val STANDARD_HEIGHTS = setOf(240, 288, 360, 480, 540, 576, 720, 900, 1080, 1440, 2160, 4320)

// 合集的体积与数量：「45.7G」「69.2 GB」「154V」「42P+17V」「338V81P51G」。认得但不显示，
// 这样整段方括号才会被当作标签段，从标题里去掉；数字后面的 GB 也就不会被当成简体字幕的 GB
private val SIZE_OR_COUNT = Regex("""^(?:\d+(?:\.\d+)?[VPGMT]B?)+$""", RegexOption.IGNORE_CASE)
private val SIZE_UNITS = setOf("gb", "mb", "tb", "g", "m", "t")
private val RESOLUTION_WXH = Regex("""^(\d{3,4})[xX×*](\d{3,4})$""")
private val CRC = Regex("""^[0-9A-Fa-f]{8}$""")
private val AUDIO_WITH_CHANNELS = Regex("""^(aac|flac|ac3|eac3|e-ac3|dd|ddp|dd\+|dts|opus|lpcm|pcm|truehd)[\d.x]+$""", RegexOption.IGNORE_CASE)
private val CHANNELS = Regex("""^\d\.\d(ch)?$|^\d+ch$""", RegexOption.IGNORE_CASE)
private val FRAME_RATE = Regex("""^(\d{2,3})fps$""", RegexOption.IGNORE_CASE)
private val YEAR = Regex("""^(19[5-9]\d|20[0-4]\d)$""")
private val DATE_CODE = Regex("""^\d{6}$""")
private val DATE = Regex("""^(19|20)\d{2}[-./](0?[1-9]|1[0-2]|00)[-./]\d{1,2}$""")
private val VERSION_WORD = Regex("""^[vV]\d{1,2}$""")

private class Phrase(val hint: String, val regex: Regex, val replacement: String)

/**
 * 多词短语先并成一个记号，免得按空白切开后「Dual」「Audio」各自落空。每条带一个小写提示词，
 * 文本里没有它就不跑正则：这一步每个方括号都要过，两万个文件的种子里是主要开销。
 */
private val PHRASES = listOf(
    Phrase("audio", Regex("""(?i)\b(dual|multi(?:ple)?|tri)[\s_-]+(audio)\b"""), "$1-$2"),
    Phrase("sub", Regex("""(?i)\b(dual|multi(?:ple)?|tri)[\s_-]+(subs?|subtitles?)\b"""), "$1-$2"),
    Phrase("eng", Regex("""(?i)\b(eng(?:lish)?)[\s_-]+(subs?|dub(?:bed)?)\b"""), "$1-$2"),
    Phrase("mosaic", Regex("""(?i)\breducing[\s_-]+mosaic\b"""), "reducing-mosaic"),
    Phrase("ch", Regex("""(?i)\b(chs|gb)[&_+\s]+(cht|big5)\b"""), "chs-cht"),
    Phrase("jp", Regex("""(?i)\b(cht|chs)[_\s-]+(jp|jpn)\b"""), "$1-jp"),
    Phrase("gb", Regex("""(?i)\bgb[_-]cn\b"""), "gb-cn"),
    Phrase("dts", Regex("""(?i)\bdts[\s_-]+hd(?:[\s_-]*ma)?\b"""), "dts-hd"),
    Phrase("26", Regex("""(?i)\bh[\s.]?26([45])\b"""), "h26$1"),
    Phrase("web", Regex("""(?i)\bweb[\s_]+dl\b"""), "web-dl"),
    Phrase("hd", Regex("""(?i)\btrue[\s_-]?hd\b"""), "truehd"),
    Phrase("blu", Regex("""(?i)\bblu[\s_]ray\b"""), "bluray"),
)

private val TAG_SEPARATORS = Regex("""[\s_+,/|&;]+""")

internal fun normalizeTagPhrases(text: String): String {
    val lower = text.lowercase()
    return PHRASES.fold(text) { acc, phrase -> if (phrase.hint in lower) phrase.regex.replace(acc, phrase.replacement) else acc }
}

/** 一个记号对应的标签；null 表示不认得。 */
internal fun lookupTagWord(word: String): List<MediaTag>? {
    val token = word.trim().trim('.', '-', '~', '!', '＋')
    if (token.isEmpty()) return emptyList()
    val lower = token.lowercase()
    WORDS[lower]?.let { return it }
    RESOLUTION_P.matchEntire(token)?.let { match ->
        val height = match.groupValues[1].toInt()
        if (height in STANDARD_HEIGHTS) return listOf(resolutionTag(height))
    }
    if (SIZE_OR_COUNT.matches(token)) return emptyList()
    RESOLUTION_WXH.matchEntire(token)?.let { return listOf(resolutionTag(it.groupValues[2].toInt())) }
    FRAME_RATE.matchEntire(token)?.let { return tag(TagKind.FRAME_RATE, "${it.groupValues[1]}fps") }
    AUDIO_WITH_CHANNELS.matchEntire(token)?.let { match ->
        return lookupTagWord(match.groupValues[1]) ?: emptyList()
    }
    if (CHANNELS.matches(token) || YEAR.matches(token) || VERSION_WORD.matches(token) || DATE.matches(token) || DATE_CODE.matches(token)) return emptyList()
    if (CRC.matches(token) && token.any { it.isLetter() } && token.any { it in '0'..'9' }) return emptyList()
    if (token.any(::isCjk)) return cjkTag(token)
    return null
}

private fun cjkTag(token: String): List<MediaTag>? {
    // 「脸肿字幕组」「极影字幕社」「漫之学园资源部」是组名，不能因为含「字幕」就当成标签
    if (listOf("组", "組", "社", "部", "屋", "学园", "學園").any { it in token }) return null
    val found = buildList {
        when {
            "简繁" in token || "簡繁" in token -> add(MediaTag(TagKind.SUBTITLES, "简繁"))
            "简日" in token || "簡日" in token -> add(MediaTag(TagKind.SUBTITLES, "简日"))
            "繁日" in token -> add(MediaTag(TagKind.SUBTITLES, "繁日"))
            "简体" in token || "簡體" in token || "简中" in token || token == "简" -> add(MediaTag(TagKind.SUBTITLES, "简"))
            "繁体" in token || "繁體" in token || "繁中" in token || token == "繁" -> add(MediaTag(TagKind.SUBTITLES, "繁"))
        }
        if ("中文字幕" in token || "中字" in token) add(MediaTag(TagKind.SUBTITLES, MediaTag.CHINESE_SUBTITLES))
        // 「流出」只在番号上才是无码（见 matchAv）：没有番号的视频说流出只是外传，与有无马赛克无关
        if (listOf("无码", "無碼", "无修正", "無修正", "破解").any { it in token }) {
            add(MediaTag(TagKind.CENSORSHIP, MediaTag.UNCENSORED))
        }
    }
    if (found.isNotEmpty()) return found
    val noise = listOf(
        "内封", "內封", "内嵌", "內嵌", "外挂", "外掛", "双语", "雙語", "字幕", "合集", "全集", "高清", "自抓",
        "日文", "日语", "完结", "完結", "修正", "翻新", "双字", "國語", "国语", "粤语", "中文",
    )
    return if (noise.any { it in token }) emptyList() else null
}

/** 解析一段标签文本（通常是一对方括号里的内容）。 */
internal class TagScan(val tags: List<MediaTag>, val known: Int, val unknown: Int, val version: String?) {
    val isTagText: Boolean get() = known > 0 && (unknown == 0 || known >= 2 && known > unknown)
}

internal fun scanTags(text: String): TagScan {
    val tags = mutableListOf<MediaTag>()
    var known = 0
    var unknown = 0
    var version: String? = null
    var previous: String? = null
    normalizeTagPhrases(text).split(TAG_SEPARATORS).filter { it.isNotBlank() }.forEach { raw ->
        val afterNumber = previous?.toDoubleOrNull() != null
        previous = raw
        if (afterNumber && raw.lowercase() in SIZE_UNITS) {
            known++
            return@forEach
        }
        if (VERSION_WORD.matches(raw)) {
            version = raw.lowercase()
            known++
            return@forEach
        }
        val direct = lookupTagWord(raw)
        if (direct != null) {
            tags += direct
            known++
            return@forEach
        }
        // HEVC-10bit、AVC-8bit、x264-SRS：按连字符再拆一次。第一段须认得，否则「DBD-Raws」会因为
        // 「Raws」在词表里而被当成标签
        val pieces = raw.split('-').filter { it.isNotBlank() }.map { lookupTagWord(it) }
        if (pieces.size > 1 && pieces.first() != null) {
            pieces.filterNotNull().forEach { tags += it }
            known++
        } else {
            unknown++
        }
    }
    return TagScan(tags.distinct(), known, unknown, version)
}

internal fun isWeakTagWord(word: String): Boolean = word.lowercase().trim('.', '-') in WEAK_TITLE_WORDS

/** 标题里的一个词能否作为标签段的起点。 */
internal fun isTitleStoppingTag(word: String): Boolean {
    val lower = word.lowercase().trim('.', '-')
    if (lower in WEAK_TITLE_WORDS) return false
    if (DATE_CODE.matches(lower)) return false
    val scan = scanTags(word)
    return scan.known > 0 && scan.unknown == 0
}

/** 字幕与外挂音轨的语言后缀：「.scjp.ass」「.en.ass」「-zh.srt」。键为小写原文。 */
internal val LANGUAGE_SUFFIXES: Map<String, String> = mapOf(
    "sc" to "简", "chs" to "简", "gb" to "简", "zh" to "简", "zh-cn" to "简", "zh-hans" to "简", "chi" to "简",
    "tc" to "繁", "cht" to "繁", "big5" to "繁", "zh-tw" to "繁", "zh-hk" to "繁", "zh-hant" to "繁",
    "scjp" to "简日", "jpsc" to "简日", "chs&jpn" to "简日", "chs_jp" to "简日", "gb_jp" to "简日",
    "tcjp" to "繁日", "jptc" to "繁日", "cht&jpn" to "繁日", "cht_jp" to "繁日", "big5_jp" to "繁日",
    "sc_tc" to "简繁", "chs&cht" to "简繁",
    "en" to "英", "eng" to "英", "english" to "英", "en-us" to "英",
    "ja" to "日", "jp" to "日", "jpn" to "日", "japanese" to "日",
    "ko" to "韩", "kor" to "韩",
    "pt-br" to "葡", "por" to "葡", "es" to "西", "spa" to "西", "fr" to "法", "fre" to "法", "de" to "德", "ger" to "德",
    "it" to "意", "ita" to "意", "ru" to "俄", "rus" to "俄", "ar" to "阿", "ara" to "阿",
)
