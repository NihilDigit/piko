package dev.piko.shared.media.reader

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.media.player.BasicSubtitleTextTranscoder
import kotlin.concurrent.Volatile

/**
 * 文本解码结果：规范化换行符（`\n`）后的正文与识别出的字符集名称。
 */
data class DecodedDocumentText(
    val text: String,
    val encodingName: String,
    val looksBinary: Boolean = false,
)

/**
 * 可供用户手动覆盖的常用文本编码选项。`null` 表示自动探测。
 */
enum class DocumentEncodingOption(val charsetId: String?, val displayName: String) {
    AUTO(null, "Auto"),
    UTF_8("UTF-8", "UTF-8"),
    GB18030("GB18030", "GB18030"),
    BIG5("Big5", "Big5"),
    SHIFT_JIS("Shift_JIS", "Shift-JIS"),
    CP949("x-windows-949", "CP949"),
    UTF_16LE("UTF-16LE", "UTF-16LE"),
    UTF_16BE("UTF-16BE", "UTF-16BE"),
}

/**
 * 文档文本编码探测器与本地小文件读取器接口（遵循无 `expect`/`actual` 约定）：
 * `commonMain` 提供纯 Kotlin UTF-8 / UTF-16 实现（iOS 默认使用），
 * JVM 平台（Android / Desktop）启动时通过 [DocumentTextDecoder.install] 注入基于 `Charset` 的完整 CJK 实现。
 */
interface DocumentCharsetDecoder {
    fun decode(
        bytes: ByteArray,
        forcedCharset: String? = null,
        effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
        systemLanguage: AppLanguage = PikoLocale.systemLanguage,
    ): DecodedDocumentText

    /** 读取本机文件路径的前 [maxBytes] 字节，返回 `(bytes, totalFileSize)`；不支持或不可读时返回 null。 */
    fun readLocalBounded(path: String, maxBytes: Int): Pair<ByteArray, Long>? = null

    /** 当前平台是否支持除 Unicode 外的传统 CJK 字符集（JVM 支持，iOS 为基础 Unicode）。 */
    val supportsLegacyCharsets: Boolean get() = false
}

object DocumentTextDecoder {
    @Volatile
    var current: DocumentCharsetDecoder = BasicDocumentCharsetDecoder
        private set

    fun install(decoder: DocumentCharsetDecoder) {
        current = decoder
    }

    fun decode(
        bytes: ByteArray,
        forcedCharset: String? = null,
        effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
        systemLanguage: AppLanguage = PikoLocale.systemLanguage,
    ): DecodedDocumentText = current.decode(bytes, forcedCharset, effectiveLanguage, systemLanguage)

    fun readLocalBounded(path: String, maxBytes: Int): Pair<ByteArray, Long>? =
        current.readLocalBounded(path, maxBytes)
}

/**
 * 纯 Kotlin Unicode 文本解码器：支持 UTF-8（含/不含 BOM）、UTF-16LE/BE（含/不含 BOM），
 * 并对截断尾部的不完整 UTF-8 序列做边界裁剪，防止超大文件截断预览时末尾出现乱码。
 */
object BasicDocumentCharsetDecoder : DocumentCharsetDecoder {
    override fun decode(
        bytes: ByteArray,
        forcedCharset: String?,
        effectiveLanguage: AppLanguage,
        systemLanguage: AppLanguage,
    ): DecodedDocumentText {
        if (bytes.isEmpty()) return DecodedDocumentText("", "UTF-8")
        if (!forcedCharset.isNullOrBlank()) {
            decodeForcedUnicode(bytes, forcedCharset)?.let { return it }
        }
        decodeUnicodeIfRecognized(bytes)?.let { return it }
        val trimmed = trimTrailingIncompleteUtf8(bytes)
        val hasNul = containsNulByte(trimmed)
        if (!hasNul && BasicSubtitleTextTranscoder.isValidUtf8(trimmed)) {
            return DecodedDocumentText(
                text = normalizeLineEndings(trimmed.decodeToString()),
                encodingName = "UTF-8",
            )
        }
        val fallbackText = normalizeLineEndings(trimmed.decodeToString())
        return DecodedDocumentText(
            text = fallbackText,
            encodingName = "UTF-8",
            looksBinary = hasNul && isLikelyBinary(bytes),
        )
    }

    internal fun decodeForcedUnicode(bytes: ByteArray, charset: String): DecodedDocumentText? = when (charset.uppercase()) {
        "UTF-8", "UTF8" -> {
            val payload = if (BasicSubtitleTextTranscoder.hasUtf8Bom(bytes)) bytes.copyOfRange(3, bytes.size) else bytes
            val trimmed = trimTrailingIncompleteUtf8(payload)
            DecodedDocumentText(normalizeLineEndings(trimmed.decodeToString()), "UTF-8")
        }
        "UTF-16LE", "UTF16LE" -> {
            val offset = if (BasicSubtitleTextTranscoder.hasUtf16LeBom(bytes)) 2 else 0
            val raw = BasicSubtitleTextTranscoder.decodeUtf16(bytes, offset, littleEndian = true)
            DecodedDocumentText(normalizeLineEndings(raw), "UTF-16LE")
        }
        "UTF-16BE", "UTF16BE" -> {
            val offset = if (BasicSubtitleTextTranscoder.hasUtf16BeBom(bytes)) 2 else 0
            val raw = BasicSubtitleTextTranscoder.decodeUtf16(bytes, offset, littleEndian = false)
            DecodedDocumentText(normalizeLineEndings(raw), "UTF-16BE")
        }
        else -> null
    }

    internal fun decodeUnicodeIfRecognized(bytes: ByteArray): DecodedDocumentText? {
        if (BasicSubtitleTextTranscoder.hasUtf8Bom(bytes)) {
            val payload = trimTrailingIncompleteUtf8(bytes.copyOfRange(3, bytes.size))
            if (BasicSubtitleTextTranscoder.isValidUtf8(payload)) {
                return DecodedDocumentText(normalizeLineEndings(payload.decodeToString()), "UTF-8 BOM")
            }
        }
        if (BasicSubtitleTextTranscoder.hasUtf16LeBom(bytes)) {
            val raw = BasicSubtitleTextTranscoder.decodeUtf16(bytes, startOffset = 2, littleEndian = true)
            return DecodedDocumentText(normalizeLineEndings(raw), "UTF-16LE")
        }
        if (BasicSubtitleTextTranscoder.hasUtf16BeBom(bytes)) {
            val raw = BasicSubtitleTextTranscoder.decodeUtf16(bytes, startOffset = 2, littleEndian = false)
            return DecodedDocumentText(normalizeLineEndings(raw), "UTF-16BE")
        }
        BasicSubtitleTextTranscoder.detectBomlessUtf16Endianness(bytes)?.let { littleEndian ->
            val raw = BasicSubtitleTextTranscoder.decodeUtf16(bytes, startOffset = 0, littleEndian = littleEndian)
            return DecodedDocumentText(
                text = normalizeLineEndings(raw),
                encodingName = if (littleEndian) "UTF-16LE" else "UTF-16BE",
            )
        }
        return null
    }

    /** 若字节流末尾因定长截断切在 UTF-8 多字节字符中间，裁去末尾 1..3 个残缺字节。 */
    internal fun trimTrailingIncompleteUtf8(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val maxBack = minOf(3, bytes.size)
        var start = bytes.size - 1
        var continuations = 0
        while (start >= bytes.size - maxBack && (bytes[start].toInt() and 0xC0) == 0x80) {
            continuations++
            start--
        }
        if (start < 0) return bytes
        val lead = bytes[start].toInt() and 0xFF
        val expectedLen = when {
            lead <= 0x7F -> 1
            lead in 0xC2..0xDF -> 2
            lead in 0xE0..0xEF -> 3
            lead in 0xF0..0xF4 -> 4
            else -> return bytes
        }
        val actualLen = continuations + 1
        return if (actualLen < expectedLen) bytes.copyOf(start) else bytes
    }

    internal fun normalizeLineEndings(raw: String): String {
        if ('\r' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            if (c == '\r') {
                sb.append('\n')
                if (i + 1 < n && raw[i + 1] == '\n') i++
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    internal fun containsNulByte(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 8192)
        for (i in 0 until sample) {
            if (bytes[i] == 0.toByte()) return true
        }
        return false
    }

    internal fun isLikelyBinary(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, 8192)
        if (sample == 0) return false
        var suspicious = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0 || (b < 0x09) || (b in 0x0E..0x1F && b != 0x1B)) {
                suspicious++
            }
        }
        return suspicious.toDouble() / sample > 0.10
    }
}
