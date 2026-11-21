package dev.piko.shared.media.player

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale

/**
 * 基于字幕文本内容本身的轻量语言探测器：
 * 当外挂字幕文件名与元数据均未携带语言标签（如 `The.Fifth.Republic.E01.MBC.SDTVRip.XviD.srt`）时，
 * 跳过 SRT/VTT 时间轴与 ASS 样式头/花括号特效标签，统计实际对白行的字形分布（谚文、假名、简繁汉字、西里尔、泰文与英文高频词），
 * 返回供 mpv `sub-add` 与界面显示使用的语言码（`chs` / `cht` / `chi` / `jpn` / `kor` / `eng` / `rus` / `tha`）。
 */
fun detectSubtitleLanguageFromBytes(
    bytes: ByteArray,
    fileName: String? = null,
    effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
    systemLanguage: AppLanguage = PikoLocale.systemLanguage,
): String? {
    if (bytes.isEmpty()) return null
    val sample = if (bytes.size <= MAX_BYTE_SAMPLE) {
        bytes
    } else {
        trimTrailingIncompleteUtf8(bytes, MAX_BYTE_SAMPLE)
    }
    val text = SubtitleEncoding.current.decodeToString(sample, effectiveLanguage, systemLanguage)
    return detectSubtitleLanguageFromText(text, fileName)
}

/** 对已经规范化为 UTF-8 的内存字幕字节进行零复制开销采样探测。 */
fun detectSubtitleLanguageFromUtf8Bytes(
    utf8Bytes: ByteArray,
    fileName: String? = null,
): String? {
    if (utf8Bytes.isEmpty()) return null
    val end = if (utf8Bytes.size <= MAX_BYTE_SAMPLE) {
        utf8Bytes.size
    } else {
        safeUtf8SliceLength(utf8Bytes, MAX_BYTE_SAMPLE)
    }
    if (end <= 0) return null
    return detectSubtitleLanguageFromText(utf8Bytes.decodeToString(0, end), fileName)
}

/** 读取本地文本字幕文件头部采样并探测语言（仅对纯文本字幕扩展名生效）。 */
fun sniffLocalSubtitleLanguage(
    path: String,
    fileName: String? = null,
    effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
    systemLanguage: AppLanguage = PikoLocale.systemLanguage,
): String? {
    val targetName = fileName ?: path
    if (!isTextSubtitleName(targetName)) return null
    val sample = SubtitleEncoding.current.readLocalSample(path, MAX_BYTE_SAMPLE) ?: return null
    return detectSubtitleLanguageFromBytes(sample, targetName, effectiveLanguage, systemLanguage)
}

fun detectSubtitleLanguageFromText(
    text: String,
    fileName: String? = null,
): String? {
    if (text.isBlank()) return null
    val dialogue = extractDialogueSample(text, fileName)
    if (dialogue.isEmpty()) return null

    var hangul = 0
    var kana = 0
    var han = 0
    var simplifiedHits = 0
    var traditionalHits = 0
    var latin = 0
    var cyrillic = 0
    var thai = 0

    for (ch in dialogue) {
        val code = ch.code
        when {
            code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF || code in 0x3130..0x318F -> hangul++
            code in 0x3040..0x30FF -> kana++
            code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF -> {
                han++
                if (ch in SIMPLIFIED_ONLY_HAN) simplifiedHits++
                else if (ch in TRADITIONAL_ONLY_HAN) traditionalHits++
            }
            code in 'A'.code..'Z'.code || code in 'a'.code..'z'.code || code in 0x00C0..0x024F -> latin++
            code in 0x0400..0x04FF -> cyrillic++
            code in 0x0E00..0x0E7F -> thai++
        }
    }

    val cjkTotal = han + kana + hangul
    val letterTotal = cjkTotal + latin + cyrillic + thai
    if (letterTotal < MIN_TOTAL_LETTERS) return null

    if (hangul >= MIN_CJK_HITS && hangul.toDouble() / cjkTotal.coerceAtLeast(1) >= 0.35 && hangul >= kana) {
        return "kor"
    }
    if (kana >= MIN_CJK_HITS && kana.toDouble() / (kana + han).coerceAtLeast(1) >= 0.18 && kana >= hangul) {
        return "jpn"
    }
    if (han >= MIN_CJK_HITS && han.toDouble() / letterTotal >= 0.20) {
        return when {
            simplifiedHits >= 2 && simplifiedHits >= traditionalHits * 2 -> "chs"
            traditionalHits >= 2 && traditionalHits >= simplifiedHits * 2 -> "cht"
            simplifiedHits >= 1 && traditionalHits == 0 -> "chs"
            traditionalHits >= 1 && simplifiedHits == 0 -> "cht"
            else -> "chi"
        }
    }
    if (cyrillic >= MIN_TOTAL_LETTERS && cyrillic.toDouble() / letterTotal >= 0.40) {
        return "rus"
    }
    if (thai >= MIN_TOTAL_LETTERS && thai.toDouble() / letterTotal >= 0.40) {
        return "tha"
    }
    if (latin >= MIN_LATIN_LETTERS && latin.toDouble() / letterTotal >= 0.70) {
        if (countEnglishStopWords(dialogue) >= 2) {
            return "eng"
        }
    }
    return null
}

private fun extractDialogueSample(raw: String, fileName: String?): String {
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()
    val looksLikeAss = ext == "ass" || ext == "ssa" ||
        raw.contains("[Events]", ignoreCase = true) ||
        raw.contains("Dialogue:", ignoreCase = true)

    val out = StringBuilder(minOf(raw.length, MAX_DIALOGUE_CHARS))
    var inEvents = !looksLikeAss
    var inVttIgnoredBlock = false
    var pos = 0
    val len = raw.length
    var scannedChars = 0

    while (pos < len && out.length < MAX_DIALOGUE_CHARS && scannedChars < MAX_SCAN_CHARS) {
        var lineEnd = raw.indexOf('\n', pos)
        if (lineEnd < 0) lineEnd = len
        val line = raw.substring(pos, lineEnd).trim('\r', ' ', '\t', '\uFEFF')
        scannedChars += (lineEnd - pos) + 1
        pos = lineEnd + 1

        if (line.isEmpty()) {
            inVttIgnoredBlock = false
            continue
        }

        if (looksLikeAss) {
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents && !line.startsWith("Dialogue:", ignoreCase = true)) continue
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val payload = extractAssDialogueText(line) ?: continue
            appendCleanedLine(out, payload)
            continue
        }

        if (inVttIgnoredBlock) continue
        if (line.startsWith("WEBVTT", ignoreCase = true) ||
            line.startsWith("STYLE", ignoreCase = true) ||
            line.startsWith("NOTE", ignoreCase = true) ||
            line.startsWith("REGION", ignoreCase = true)
        ) {
            inVttIgnoredBlock = true
            continue
        }
        if (isCueIndexLine(line) || line.contains("-->")) continue
        appendCleanedLine(out, line)
    }
    return out.toString()
}

private fun extractAssDialogueText(line: String): String? {
    // ASS Dialogue format: Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
    var commas = 0
    for (i in line.indices) {
        if (line[i] == ',') {
            commas++
            if (commas == 9) {
                return line.substring(i + 1)
            }
        }
    }
    return null
}

private fun isCueIndexLine(line: String): Boolean {
    if (line.length > 8) return false
    for (ch in line) {
        if (ch !in '0'..'9') return false
    }
    return true
}

private fun appendCleanedLine(out: StringBuilder, line: String) {
    var i = 0
    val n = line.length
    var braceDepth = 0
    var angleDepth = 0
    while (i < n && out.length < MAX_DIALOGUE_CHARS) {
        val ch = line[i]
        when {
            ch == '{' -> {
                braceDepth++
                i++
            }
            ch == '}' && braceDepth > 0 -> {
                braceDepth--
                i++
            }
            ch == '<' -> {
                angleDepth++
                i++
            }
            ch == '>' && angleDepth > 0 -> {
                angleDepth--
                i++
            }
            braceDepth > 0 || angleDepth > 0 -> i++
            ch == '\\' && i + 1 < n && (line[i + 1] == 'N' || line[i + 1] == 'n' || line[i + 1] == 'h') -> {
                out.append(' ')
                i += 2
            }
            else -> {
                out.append(ch)
                i++
            }
        }
    }
    out.append('\n')
}

private fun countEnglishStopWords(text: String): Int {
    val found = HashSet<String>(8)
    var i = 0
    val n = text.length
    while (i < n) {
        while (i < n && !isAsciiLetter(text[i])) i++
        val start = i
        while (i < n && isAsciiLetter(text[i])) i++
        val wordLen = i - start
        if (wordLen in 2..6) {
            val word = text.substring(start, i).lowercase()
            if (word in ENGLISH_STOP_WORDS) {
                found += word
                if (found.size >= 3) return found.size
            }
        }
    }
    return found.size
}

private fun isAsciiLetter(ch: Char): Boolean = ch in 'A'..'Z' || ch in 'a'..'z'

private fun trimTrailingIncompleteUtf8(bytes: ByteArray, limit: Int): ByteArray =
    bytes.copyOf(safeUtf8SliceLength(bytes, limit))

private fun safeUtf8SliceLength(bytes: ByteArray, limit: Int): Int {
    var end = minOf(bytes.size, limit)
    if (end >= bytes.size) return end
    // Back up past any UTF-8 continuation bytes (10xxxxxx) and their lead byte if truncated mid-sequence
    var back = 0
    while (end > 0 && back < 4 && (bytes[end - 1].toInt() and 0xC0) == 0x80) {
        end--
        back++
    }
    if (end > 0 && (bytes[end - 1].toInt() and 0x80) != 0) {
        val lead = bytes[end - 1].toInt() and 0xFF
        val seqLen = when {
            lead in 0xC2..0xDF -> 2
            lead in 0xE0..0xEF -> 3
            lead in 0xF0..0xF4 -> 4
            else -> 1
        }
        if (end - 1 + seqLen > limit) {
            end--
        }
    }
    return end.coerceAtLeast(1)
}

private const val MAX_BYTE_SAMPLE = 32 * 1024
private const val MAX_SCAN_CHARS = 64 * 1024
private const val MAX_DIALOGUE_CHARS = 4096
private const val MIN_TOTAL_LETTERS = 6
private const val MIN_CJK_HITS = 4
private const val MIN_LATIN_LETTERS = 16

private val ENGLISH_STOP_WORDS = setOf(
    "the", "and", "you", "that", "have", "for", "not", "with", "this", "but",
    "are", "was", "what", "they", "from", "can", "your", "just", "know", "will",
    "here", "there", "about", "would", "think", "going", "right", "don", "did", "his", "her",
)

// 严格互斥的高频简/繁特征字表（仅在确认属于中文而非日/韩语后用于区分 chs / cht）
private val SIMPLIFIED_ONLY_HAN: Set<Char> = (
    "这们来说时会为么对过还点经发从现给让样进问听见关头开长电话车门东两个国学实应该觉体机动当无书写认识欢乐帮办变错难离虽谁请谢钱买卖爱亲师战总统军队权议员党导务处场报闻视号证检调审判杀枪护医药伤坏恶紧张担惊脸脑边远选运连续断绝约结终细继维纪红绿蓝语词试译乱码简吗与专业没"
).toSet()

private val TRADITIONAL_ONLY_HAN: Set<Char> = (
    "這們來說時會為爲麼對過還點經發從現給讓樣進問聽見關頭開長電話車門東兩個國學實應該覺體機動當無書寫認識歡樂幫辦變錯難離雖誰請謝錢買賣愛親師戰總統軍隊權議員黨導務處場報聞視號證檢調審判殺槍護醫藥傷壞惡緊張擔驚臉腦邊遠選運連續斷絕約結終細繼維紀紅綠藍語詞試譯亂碼簡嗎與專業沒裡裏後"
).toSet()
