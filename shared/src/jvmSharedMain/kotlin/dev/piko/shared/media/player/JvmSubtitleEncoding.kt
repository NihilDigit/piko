package dev.piko.shared.media.player

import dev.piko.data.auth.AppLanguage
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * JVM 平台（Desktop / Android）完整字幕编码探测器与 UTF-8 规范化器：
 * 1. UTF-8 BOM / UTF-16LE / UTF-16BE（含 BOM 与无 BOM 启发式）直接无损转 UTF-8（去除 BOM）；
 * 2. 严格 RFC 3629 UTF-8（且不含孤立 NUL 字节）原样保留；
 * 3. 非 UTF-8 传统编码按当前界面/系统语言优先级尝试严格解码（`CodingErrorAction.REPORT`）并结合
 *    中日韩字形分布（汉字 / 假名 / 谚文 / 全角标点）与异常控制/私用区惩罚评分择优；
 * 4. 若文件含极少量坏字节导致全部严格解码失败，退回 `REPLACE` 评分择优，彻底避免退化为 Latin-1 乱码。
 */
object JvmSubtitleTextTranscoder : SubtitleTextTranscoder {
    override fun decodeToString(
        bytes: ByteArray,
        effectiveLanguage: AppLanguage,
        systemLanguage: AppLanguage,
    ): String {
        if (bytes.isEmpty()) return ""
        BasicSubtitleTextTranscoder.decodeUnicodeIfRecognized(bytes)?.let { return it }
        if (!containsNulByte(bytes) && BasicSubtitleTextTranscoder.isValidUtf8(bytes)) {
            return bytes.toString(Charsets.UTF_8)
        }
        return decodeLegacyCharset(bytes, effectiveLanguage, systemLanguage)
    }

    override fun normalizeToUtf8(
        bytes: ByteArray,
        effectiveLanguage: AppLanguage,
        systemLanguage: AppLanguage,
    ): ByteArray {
        if (bytes.isEmpty()) return bytes
        if (BasicSubtitleTextTranscoder.hasUtf8Bom(bytes)) {
            val stripped = bytes.copyOfRange(3, bytes.size)
            if (!containsNulByte(stripped) && BasicSubtitleTextTranscoder.isValidUtf8(stripped)) {
                return stripped
            }
        }
        BasicSubtitleTextTranscoder.decodeUnicodeIfRecognized(bytes)?.let { decoded ->
            return decoded.toByteArray(Charsets.UTF_8)
        }
        if (!containsNulByte(bytes) && BasicSubtitleTextTranscoder.isValidUtf8(bytes)) {
            return bytes
        }
        return decodeLegacyCharset(bytes, effectiveLanguage, systemLanguage).toByteArray(Charsets.UTF_8)
    }

    override fun readLocalSample(path: String, maxBytes: Int): ByteArray? {
        if (path.isBlank() || maxBytes <= 0 || path.startsWith("content:", ignoreCase = true)) return null
        return runCatching {
            val file = File(path)
            if (!file.isFile || !file.canRead()) return@runCatching null
            val limit = minOf(file.length(), maxBytes.toLong()).toInt()
            if (limit <= 0) return@runCatching null
            file.inputStream().use { input ->
                val out = ByteArray(limit)
                var filled = 0
                while (filled < limit) {
                    val n = input.read(out, filled, limit - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled <= 0) null else if (filled == limit) out else out.copyOf(filled)
            }
        }.getOrNull()
    }

    private fun decodeLegacyCharset(
        bytes: ByteArray,
        effectiveLanguage: AppLanguage,
        systemLanguage: AppLanguage,
    ): String {
        val candidates = candidateCharsetsFor(effectiveLanguage, systemLanguage)
        // 第一轮：严格解码（REPORT），并在多个合法候选中按语言特征与优先级评分择优
        var bestStrictText: String? = null
        var bestStrictScore = Double.NEGATIVE_INFINITY
        for ((index, charset) in candidates.withIndex()) {
            val decoded = decodeStrict(bytes, charset) ?: continue
            val score = scoreDecodedText(decoded, charset.name(), priorityIndex = index)
            if (score > bestStrictScore) {
                bestStrictScore = score
                bestStrictText = decoded
            }
        }
        if (bestStrictText != null) return bestStrictText

        // 第二轮：容错解码（REPLACE），处理极少数尾部截断或夹杂个别坏字节的传统字幕文件
        var bestLenientText: String? = null
        var bestLenientScore = Double.NEGATIVE_INFINITY
        for ((index, charset) in candidates.withIndex()) {
            val decoded = decodeLenient(bytes, charset) ?: continue
            val score = scoreDecodedText(decoded, charset.name(), priorityIndex = index)
            if (score > bestLenientScore) {
                bestLenientScore = score
                bestLenientText = decoded
            }
        }
        return bestLenientText ?: bytes.toString(Charsets.UTF_8)
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
            if (seen.add(cs.name())) {
                resolved += cs
            }
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
                0xFFFD -> {
                    score -= 25.0
                    badCount++
                }
                in 0x0080..0x009F -> {
                    score -= 15.0
                    badCount++
                }
                in 0xE000..0xF8FF -> {
                    // Private Use Area characters almost always indicate wrong CJK codepage
                    score -= 12.0
                    badCount++
                }
                in 0x3040..0x30FF -> {
                    // Hiragana & Katakana: positive ONLY for Japanese charsets;
                    // in GB18030, common Big5 Hanzi (0xA4A1..0xA5F6: 中/文/天/日/世/生) mis-decode as Kana!
                    when {
                        isShiftJis -> {
                            score += 4.2
                            kanaCount++
                        }
                        isEucJp -> {
                            score += 3.5
                            kanaCount++
                        }
                        else -> score -= 2.5
                    }
                }
                in 0xAC00..0xD7A3 -> {
                    // Hangul Syllables: same base weight as Hanzi (+3.0) for Korean charsets;
                    // purity boost below ensures genuine Korean wins without letting CP949 hijack GBK
                    // (GB2312 Level-1 Hanzi 0xB0..0xC8 decodes as Hangul mixed with 0xCA..0xFD Hanja in CP949).
                    if (isKoreanCharset) {
                        score += 3.0
                        hangulCount++
                    } else {
                        score -= 2.5
                    }
                }
                in 0x4E00..0x9FFF -> {
                    // CJK Unified Ideographs
                    score += 3.0
                    cjkCount++
                }
                in 0x3000..0x303F, in 0xFF01..0xFF5E -> {
                    // CJK punctuation & fullwidth ASCII
                    score += 3.0
                }
                in 0x00C0..0x024F, in 0x2010..0x2027 -> {
                    // Western European accented letters & typographic punctuation
                    score += 1.2
                }
                in 0xFF61..0xFF9F -> {
                    // Halfwidth Katakana: modern subtitles rarely use halfwidth; GBK/Big5 bytes in Shift_JIS spam it
                    score += if (isJapaneseCharset) -1.5 else -4.0
                }
                else -> {
                    // Obscure symbols/box drawing/rare blocks often come from cross-decoding CJK bytes
                    score -= 1.5
                }
            }
        }
        // 仅单字节西欧编码（windows-1252）在无高置信西欧字母上下文时不能压过有效 CJK 解码
        val isSingleByteLatin = charsetName.equals("windows-1252", ignoreCase = true) ||
            charsetName.equals("ISO-8859-1", ignoreCase = true)
        if (isSingleByteLatin && nonAsciiCount > 0) {
            score -= nonAsciiCount * 0.5
        }
        // 在 CJK 字符数相近时，语言优先级高的编码获得温和加权（不压倒明确假名/高纯度谚文信号）
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

    private fun containsNulByte(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 4096)
        for (i in 0 until sample) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }
}
