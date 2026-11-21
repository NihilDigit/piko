package dev.piko.shared.media.reader

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.media.player.BasicSubtitleTextTranscoder
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * JVM 平台（Android / Desktop）完整文档编码探测与解码器：
 * 支持 UTF-8（含/无 BOM）、UTF-16LE/BE（含/无 BOM）及常见 CJK 传统编码
 * （GB18030、Big5、Shift_JIS、EUC-JP、CP949 / EUC-KR、Windows-1252），
 * 并报告识别出的编码名称与支持手动字符集切换。
 */
object JvmDocumentCharsetDecoder : DocumentCharsetDecoder {
    override val supportsLegacyCharsets: Boolean = true

    override fun decode(
        bytes: ByteArray,
        forcedCharset: String?,
        effectiveLanguage: AppLanguage,
        systemLanguage: AppLanguage,
    ): DecodedDocumentText {
        if (bytes.isEmpty()) return DecodedDocumentText("", "UTF-8")

        if (!forcedCharset.isNullOrBlank()) {
            BasicDocumentCharsetDecoder.decodeForcedUnicode(bytes, forcedCharset)?.let { return it }
            val cs = runCatching { Charset.forName(forcedCharset) }.getOrNull()
            if (cs != null) {
                val decoded = decodeLenient(bytes, cs) ?: bytes.toString(Charsets.UTF_8)
                return DecodedDocumentText(
                    text = BasicDocumentCharsetDecoder.normalizeLineEndings(decoded),
                    encodingName = displayNameOf(cs.name()),
                )
            }
        }

        BasicDocumentCharsetDecoder.decodeUnicodeIfRecognized(bytes)?.let { return it }

        val trimmed = BasicDocumentCharsetDecoder.trimTrailingIncompleteUtf8(bytes)
        val hasNul = BasicDocumentCharsetDecoder.containsNulByte(trimmed)
        if (!hasNul && BasicSubtitleTextTranscoder.isValidUtf8(trimmed)) {
            return DecodedDocumentText(
                text = BasicDocumentCharsetDecoder.normalizeLineEndings(trimmed.toString(Charsets.UTF_8)),
                encodingName = "UTF-8",
            )
        }
        if (hasNul && BasicDocumentCharsetDecoder.isLikelyBinary(bytes)) {
            return DecodedDocumentText(
                text = BasicDocumentCharsetDecoder.normalizeLineEndings(trimmed.toString(Charsets.UTF_8)),
                encodingName = "UTF-8",
                looksBinary = true,
            )
        }

        return decodeLegacyCharset(bytes, effectiveLanguage, systemLanguage)
    }

    override fun readLocalBounded(path: String, maxBytes: Int): Pair<ByteArray, Long>? {
        if (path.isBlank() || maxBytes <= 0 || path.startsWith("content:", ignoreCase = true)) return null
        return runCatching {
            val file = File(path)
            if (!file.isFile || !file.canRead()) return@runCatching null
            val total = file.length()
            val limit = minOf(total, maxBytes.toLong()).toInt()
            if (limit <= 0) return@runCatching ByteArray(0) to total
            file.inputStream().use { input ->
                val out = ByteArray(limit)
                var filled = 0
                while (filled < limit) {
                    val n = input.read(out, filled, limit - filled)
                    if (n <= 0) break
                    filled += n
                }
                (if (filled == limit) out else out.copyOf(filled)) to total
            }
        }.getOrNull()
    }

    private fun decodeLegacyCharset(
        bytes: ByteArray,
        effectiveLanguage: AppLanguage,
        systemLanguage: AppLanguage,
    ): DecodedDocumentText {
        val candidates = candidateCharsetsFor(effectiveLanguage, systemLanguage)
        var bestStrictText: String? = null
        var bestStrictCharset: String? = null
        var bestStrictScore = Double.NEGATIVE_INFINITY
        for ((index, charset) in candidates.withIndex()) {
            val decoded = decodeStrict(bytes, charset) ?: continue
            val score = scoreDecodedText(decoded, charset.name(), priorityIndex = index)
            if (score > bestStrictScore) {
                bestStrictScore = score
                bestStrictText = decoded
                bestStrictCharset = charset.name()
            }
        }
        if (bestStrictText != null && bestStrictCharset != null) {
            return DecodedDocumentText(
                text = BasicDocumentCharsetDecoder.normalizeLineEndings(bestStrictText),
                encodingName = displayNameOf(bestStrictCharset),
            )
        }

        var bestLenientText: String? = null
        var bestLenientCharset: String? = null
        var bestLenientScore = Double.NEGATIVE_INFINITY
        for ((index, charset) in candidates.withIndex()) {
            val decoded = decodeLenient(bytes, charset) ?: continue
            val score = scoreDecodedText(decoded, charset.name(), priorityIndex = index)
            if (score > bestLenientScore) {
                bestLenientScore = score
                bestLenientText = decoded
                bestLenientCharset = charset.name()
            }
        }
        return DecodedDocumentText(
            text = BasicDocumentCharsetDecoder.normalizeLineEndings(bestLenientText ?: bytes.toString(Charsets.UTF_8)),
            encodingName = displayNameOf(bestLenientCharset ?: "UTF-8"),
        )
    }

    private fun candidateCharsetsFor(
        effectiveLanguage: AppLanguage,
        systemLanguage: AppLanguage,
    ): List<Charset> {
        val primary = if (effectiveLanguage == AppLanguage.EN && systemLanguage != AppLanguage.EN) {
            systemLanguage
        } else {
            effectiveLanguage
        }
        val names = when (primary) {
            AppLanguage.ZH_CN, AppLanguage.SYSTEM -> listOf("GB18030", "Big5", "Shift_JIS", "x-windows-949", "EUC-KR", "EUC-JP", "windows-1252")
            AppLanguage.ZH_HANT -> listOf("Big5", "GB18030", "Shift_JIS", "x-windows-949", "EUC-KR", "EUC-JP", "windows-1252")
            AppLanguage.JA -> listOf("Shift_JIS", "EUC-JP", "GB18030", "Big5", "x-windows-949", "EUC-KR", "windows-1252")
            AppLanguage.KO -> listOf("x-windows-949", "EUC-KR", "GB18030", "Big5", "Shift_JIS", "EUC-JP", "windows-1252")
            AppLanguage.EN -> listOf("GB18030", "Big5", "Shift_JIS", "x-windows-949", "EUC-KR", "windows-1252")
        }
        val resolved = ArrayList<Charset>(names.size)
        val seen = HashSet<String>(names.size)
        for (name in names) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            if (seen.add(cs.name())) resolved += cs
        }
        return resolved
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun decodeLenient(bytes: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun scoreDecodedText(text: String, charsetName: String, priorityIndex: Int): Double {
        val isShiftJis = charsetName.contains("Shift_JIS", ignoreCase = true)
        val isEucJp = charsetName.contains("EUC-JP", ignoreCase = true)
        val isJapaneseCharset = isShiftJis || isEucJp
        val isKoreanCharset = charsetName.contains("949", ignoreCase = true) ||
            charsetName.contains("EUC-KR", ignoreCase = true)
        var score = 0.0
        var nonAsciiCount = 0
        var cjkCount = 0
        var kanaCount = 0
        var hangulCount = 0
        var badCount = 0
        val limit = minOf(text.length, 32_768)
        for (i in 0 until limit) {
            val ch = text[i]
            val code = ch.code
            if (code < 0x80) {
                if (code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t') {
                    score -= 15.0
                    badCount++
                }
                continue
            }
            nonAsciiCount++
            when (code) {
                0xFFFD -> { score -= 25.0; badCount++ }
                in 0x0080..0x009F -> { score -= 15.0; badCount++ }
                in 0xE000..0xF8FF -> { score -= 12.0; badCount++ }
                in 0x3040..0x30FF -> {
                    when {
                        isShiftJis -> { score += 4.2; kanaCount++ }
                        isEucJp -> { score += 3.5; kanaCount++ }
                        else -> score -= 2.5
                    }
                }
                in 0xAC00..0xD7A3 -> {
                    if (isKoreanCharset) {
                        score += 3.0
                        hangulCount++
                    } else {
                        score -= 2.5
                    }
                }
                in 0x4E00..0x9FFF -> { score += 3.0; cjkCount++ }
                in 0x3000..0x303F, in 0xFF01..0xFF5E -> score += 3.0
                in 0x00C0..0x024F, in 0x2010..0x2027 -> score += 1.2
                in 0xFF61..0xFF9F -> score += if (isJapaneseCharset) -1.5 else -4.0
                else -> score -= 1.5
            }
        }
        val isSingleByteLatin = charsetName.equals("windows-1252", ignoreCase = true) ||
            charsetName.equals("ISO-8859-1", ignoreCase = true)
        if (isSingleByteLatin && nonAsciiCount > 0) {
            score -= nonAsciiCount * 0.5
        }
        val priorityBonus = (10 - priorityIndex).coerceAtLeast(0) * 0.35 * maxOf(1, minOf(nonAsciiCount, 8))
        val scriptCoherence = when {
            isShiftJis && kanaCount >= 2 -> kanaCount * 1.2
            isEucJp && kanaCount >= 2 -> kanaCount * (if (priorityIndex <= 1) 1.2 else 0.3)
            isKoreanCharset && hangulCount > 0 -> {
                val hangulRatio = hangulCount.toDouble() / (hangulCount + cjkCount).coerceAtLeast(1)
                when {
                    hangulCount >= 4 && hangulRatio >= 0.85 -> hangulCount * 2.0
                    cjkCount > 0 && hangulRatio < 0.85 -> -hangulCount * 2.5
                    else -> 0.0
                }
            }
            cjkCount >= 2 && badCount == 0 && !isKoreanCharset -> cjkCount * 0.2
            else -> 0.0
        }
        return score + priorityBonus + scriptCoherence
    }

    private fun displayNameOf(charsetName: String): String = when {
        charsetName.equals("x-windows-949", ignoreCase = true) -> "CP949"
        charsetName.equals("Shift_JIS", ignoreCase = true) -> "Shift-JIS"
        charsetName.equals("windows-1252", ignoreCase = true) -> "Windows-1252"
        else -> charsetName
    }
}
