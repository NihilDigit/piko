package dev.piko.shared.state

import dev.piko.shared.util.matchRange
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 从粘贴文本里找到的一条链接。
 *
 * [infoHash] 是 40 位小写十六进制的 btih，非磁力链接为 null。去重按它比而不按原文：
 * 同一个种子常以完整磁力、裸 hash、Base32 三种写法同时出现在一段转发里。
 */
data class PastedLink(val uri: String, val infoHash: String?) {
    val isMagnet: Boolean get() = infoHash != null

    /** 去重与列表行的键。 */
    val key: String get() = infoHash ?: uri
}

/**
 * 找出文本里的全部链接：任意位置的磁力链、裸的 40 位十六进制或 32 位 Base32 infohash，
 * 以及 http(s)、ed2k、thunder 链接。按出现顺序返回，磁力按 infohash 去重，其余按原文去重。
 *
 * 裸 hash 必须前后都不挨着字母数字才算：掐头去尾凑出 40 位的做法会从别的长串里造出不存在的种子。
 * 链接内部的长串（网址路径里的 hash、ed2k 的文件 hash）不另算。
 */
fun extractLinks(text: String): List<PastedLink> {
    val linkMatches = (MAGNET.findAll(text) + ED2K.findAll(text) + WEB_LINK.findAll(text))
        .sortedBy { it.range.first }
        .toList()
    // 网址参数里可能嵌着磁力链，反之亦然，重叠时取先开始的那条
    val found = mutableListOf<Pair<Int, PastedLink>>()
    var coveredUntil = -1
    for (match in linkMatches) {
        if (match.range.first <= coveredUntil) continue
        coveredUntil = match.range.last
        val raw = if (match.value.startsWith("ed2k", ignoreCase = true)) {
            normalizeEd2kUri(match.value)
        } else {
            match.value.trimEnd(*TRAILING_PUNCTUATION)
        }
        var link = linkFromUri(raw)
        if (InstantSheetState.findShareLink(link.uri) != null && InstantSheetState.findSharePassCode(link.uri) == null) {
            val next = linkMatches.firstOrNull { it.range.first > match.range.last }?.range?.first ?: text.length
            InstantSheetState.findSharePassCode(text.substring(match.range.first, next))?.let { code ->
                link = link.copy(uri = "${link.uri}?pwd=$code")
            }
        }
        found += match.range.first to link
    }
    val covered = linkMatches.map { it.range }
    for (match in BARE_HASH.findAll(text)) {
        if (covered.any { match.range.first in it }) continue
        val hash = infoHashOf(match.value) ?: continue
        found += match.range.first to PastedLink("$MAGNET_PREFIX$hash", hash)
    }
    return found.sortedBy { it.first }.map { it.second }.distinctBy { it.key }
}

private fun linkFromUri(uri: String): PastedLink {
    if (uri.startsWith("thunder://", ignoreCase = true)) {
        unwrapThunderUri(uri)?.let { inner ->
            val normalizedInner = if (inner.startsWith("ed2k://", ignoreCase = true)) normalizeEd2kUri(inner) else inner
            return linkFromUri(normalizedInner)
        }
    }
    if (!uri.startsWith("magnet:", ignoreCase = true)) {
        val share = InstantSheetState.findShareLink(uri)
        val code = InstantSheetState.findSharePassCode(uri)
        return PastedLink(if (share != null) share + (code?.let { "?pwd=$it" } ?: "") else uri, null)
    }
    val btih = BTIH.find(uri) ?: return PastedLink(uri, null)
    val raw = btih.groupValues[1]
    val hash = infoHashOf(raw) ?: return PastedLink(uri, null)
    // Base32 写法换成十六进制再交出去，dn、tr 等参数原样保留
    val normalized = if (raw.length == BASE32_HASH_LENGTH) uri.replaceRange(btih.groups[1]!!.matchRange, hash) else uri
    return PastedLink(normalized, hash)
}

/** 40 位十六进制或 32 位 Base32 的 infohash 转成小写十六进制，都不是就返回 null。 */
private fun infoHashOf(raw: String): String? = when {
    raw.length == HEX_HASH_LENGTH && raw.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } -> raw.lowercase()
    raw.length == BASE32_HASH_LENGTH && raw.all { it.uppercaseChar() in BASE32_ALPHABET } -> base32ToHex(raw)
    else -> null
}

// 32 个字符 160 位，恰好 40 位十六进制，没有填充
private fun base32ToHex(raw: String): String {
    val out = StringBuilder(HEX_HASH_LENGTH)
    var buffer = 0
    var bits = 0
    for (c in raw) {
        buffer = (buffer shl 5) or BASE32_ALPHABET.indexOf(c.uppercaseChar())
        bits += 5
        while (bits >= 4) {
            bits -= 4
            out.append(HEX_DIGITS[(buffer shr bits) and 0xF])
        }
        buffer = buffer and ((1 shl bits) - 1)
    }
    return out.toString()
}

@OptIn(ExperimentalEncodingApi::class)
internal fun unwrapThunderUri(uri: String): String? {
    if (!uri.startsWith("thunder://", ignoreCase = true)) return null
    val payload = uri.substring("thunder://".length).trim().trimEnd('/')
    if (payload.isEmpty()) return null
    val decoded = runCatching {
        val padded = when (payload.length % 4) {
            2 -> "$payload=="
            3 -> "$payload="
            else -> payload
        }
        Base64.decode(padded).decodeToString().trim()
    }.getOrNull() ?: return null
    if (decoded.length > 4 && decoded.startsWith("AA") && decoded.endsWith("ZZ")) {
        return decoded.substring(2, decoded.length - 2).trim().takeIf { it.isNotEmpty() }
    }
    return null
}

/** 将浏览器复制时被编码为 %7C 的 ed2k 分隔符统一还原为标准竖线 `|`，确保服务端离线任务可识别。 */
internal fun normalizeEd2kUri(raw: String): String {
    val normalized = raw.trim().replace(ED2K_ENCODED_PIPE, "|")
    return if (normalized.endsWith("|") && !normalized.endsWith("|/")) "$normalized/" else normalized
}

private const val MAGNET_PREFIX = "magnet:?xt=urn:btih:"
private const val HEX_HASH_LENGTH = 40
private const val BASE32_HASH_LENGTH = 32
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val HEX_DIGITS = "0123456789abcdef"

// 链接在空白、引号、尖括号与中文标点处结束。磁力的 dn 常带未转义的中文，所以不能只收 ASCII
private const val LINK_BODY = """[^\s"'<>，。；！？、（）【】「」『』《》]+"""
private val MAGNET = Regex("""magnet:\?$LINK_BODY""", RegexOption.IGNORE_CASE)
private val WEB_LINK = Regex("""(?:https?|thunder|pikpakapp)://$LINK_BODY""", RegexOption.IGNORE_CASE)
private val ED2K_ENCODED_PIPE = Regex("""%7[Cc]""")
private const val ED2K_SEP = """(?:\||%7[Cc])"""
// ed2k 的文件名段可以带空格，分隔符既可能是竖线 `|` 也可能是浏览器复制转义的 `%7C`，匹配到末尾的 `|/` 或 `|` 为止
private val ED2K = Regex(
    """ed2k://$ED2K_SEP[Ff][Ii][Ll][Ee]$ED2K_SEP(?:(?!$ED2K_SEP)[^\r\n])+$ED2K_SEP[0-9]+$ED2K_SEP[0-9A-Fa-f]{32}$ED2K_SEP(?:[^\s"'<>|]*$ED2K_SEP)*/?""",
    RegexOption.IGNORE_CASE,
)
private val BTIH = Regex("""xt=urn:btih:([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
private val BARE_HASH = Regex("""(?<![A-Za-z0-9])(?:[0-9A-Fa-f]{40}|[A-Za-z2-7]{32})(?![A-Za-z0-9])""")

// 句末的英文标点多半属于句子而不是链接
private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']')
