package dev.piko.shared.naming.av

import dev.piko.shared.naming.FileKind
import dev.piko.shared.naming.parseMediaName

/**
 * 番号的规范名：`{番号}{旗标} {片名}{分段}.{扩展名}`，如「ABC-123-C 某片名-CD1.mp4」，没有片名时「ABC-123-C-CD1.mp4」。
 *
 * - 番号打头：MDC 与 MetaTube 都从开头找番号。
 * - 旗标紧跟番号：中字 -C、无码 -U、两者 -UC。
 * - 分段放最后、一律写作 -CD1：Jellyfin 的多段合并认「名称-cd1」，不认没有关键字的 -1、-A。
 * - 压制标记（hhb、fhd、4K、H265、60fps）不进名字，同目录里撞名时由调用方加版本标签区分。
 *
 * 规范名再解析一遍，番号、旗标、分段与片名不变；对规范名再求规范名得到它自己。
 */

/** 规范名的可读上限，按 UTF-8 字节。服务端收到 1024，这里先截短，超出时只截片名。 */
const val CANONICAL_NAME_MAX_BYTES = 200

// PikPak 不收的字符换成全角的同形字，制表与换行换成空格。只删不换的话「上集/下集」会粘成「上集下集」
private val FULLWIDTH = mapOf(
    '/' to '／', '\\' to '＼', ':' to '：', '*' to '＊', '?' to '？', '"' to '＂', '<' to '＜', '>' to '＞', '|' to '｜',
)
private val SPACES = Regex("""[\t\r\n ]+""")
private const val ELLIPSIS = "…"

/** 番号与旗标：「ABC-123-UC」。 */
fun AvInfo.flaggedCode(): String = code + when {
    chineseSubtitles && uncensored -> "-UC"
    uncensored -> "-U"
    chineseSubtitles -> "-C"
    else -> ""
}

/** 片名里 PikPak 不收的字符换成全角，首尾空白与结尾句点去掉。什么都不剩时为 null。 */
fun sanitizeAvTitle(title: String?): String? {
    if (title == null) return null
    val replaced = title.map { FULLWIDTH[it] ?: it }.joinToString("").replace(SPACES, " ")
    return replaced.trim().trimEnd('.', ' ').ifEmpty { null }
}

private fun compose(info: AvInfo, title: String?, versionTag: String?): String = buildString {
    append(info.flaggedCode())
    title?.let { append(' ').append(it) }
    versionTag?.let { append(" [").append(it).append(']') }
    info.part?.let { append('-').append(it) }
}

/**
 * 规范的主干（不含扩展名）。[title] 为 null 时不写片名。[versionTag] 是撞名时用来区分版本的标签，如「4K」。
 * [suffix] 接在主干后面一起计长度，通常是「.zh.srt」这样的语言后缀加扩展名。
 *
 * 片名照解析的结果收敛：片名开头恰是标签词（「4K 片名」）时，规范名再解析会把它当后缀吃掉，
 * 所以先按解析的结果改片名，直到再解析不变。这样规范名才对自己不动。
 */
fun canonicalAvStem(info: AvInfo, title: String?, versionTag: String? = null, suffix: String = ""): String {
    var current = sanitizeAvTitle(title)?.let(::stripTitlePart)
    for (round in 0 until MAX_SETTLE_ROUNDS) {
        val reparsed = matchAv(compose(info, current, null), allowLanguageSuffix = false)?.info?.title
        if (reparsed == current) break
        current = sanitizeAvTitle(reparsed)
    }
    return fitLength(info, current, versionTag, suffix)
}

/**
 * 文件的规范名。[fileName] 只取扩展名，[languageCode] 是字幕的语言后缀（「zh」），写成「.zh」接在主干后面，
 * 字幕才跟视频同主干。文件夹（[isFolder]）没有扩展名，也不写分段。
 */
fun canonicalAvName(
    fileName: String,
    info: AvInfo,
    title: String? = info.title,
    isFolder: Boolean = false,
    languageCode: String? = null,
    versionTag: String? = null,
): String {
    if (isFolder) return canonicalAvStem(info.copy(part = null), title, versionTag)
    val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() && '.' in fileName }?.let { ".$it" }.orEmpty()
    val suffix = languageCode?.let { ".$it" }.orEmpty() + extension
    return canonicalAvStem(info, title, versionTag, suffix) + suffix
}

/** 片名本身以「-CD2」收尾时，再解析会把它当分段，先去掉。 */
private fun stripTitlePart(title: String): String? =
    TITLE_PART.find(title)?.let { title.substring(0, it.range.first).trim().ifEmpty { null } } ?: title

/** 超出 [CANONICAL_NAME_MAX_BYTES] 时逐字截片名，末尾加省略号；片名截完仍超出就不写片名。 */
private fun fitLength(info: AvInfo, title: String?, versionTag: String?, suffix: String): String {
    fun bytes(stem: String) = (stem + suffix).encodeToByteArray().size
    val full = compose(info, title, versionTag)
    if (title == null || bytes(full) <= CANONICAL_NAME_MAX_BYTES) return full
    var cut: String = title
    while (cut.isNotEmpty()) {
        // 按码点截，补充平面的字符不劈成两半
        cut = cut.dropLast(if (cut.length >= 2 && cut[cut.length - 1].isLowSurrogate()) 2 else 1).trimEnd()
        val candidate = compose(info, cut + ELLIPSIS, versionTag)
        if (cut.isNotEmpty() && bytes(candidate) <= CANONICAL_NAME_MAX_BYTES) return candidate
    }
    return compose(info, null, versionTag)
}

private const val MAX_SETTLE_ROUNDS = 3

/** 文件夹名里的番号，认不出时为 null。文件夹名没有扩展名，不能交给 parseMediaName。 */
fun matchAvFolder(name: String): AvInfo? = matchAv(name.trim(), allowLanguageSuffix = false)?.info

/** 文件名能否给出番号。界面据此决定要不要给「按番号规范命名」的入口。 */
fun hasAvCode(fileName: String): Boolean = parseMediaName(fileName).av != null

/** 视频与原盘镜像是条目的主文件，规范名以它们为准；字幕跟着它们走。 */
internal val FileKind.isAvContent: Boolean get() = this == FileKind.VIDEO || this == FileKind.DISC_IMAGE
