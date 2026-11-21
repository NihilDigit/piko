package dev.piko.shared.media.player

import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import kotlin.concurrent.Volatile

/**
 * 可进行字符集检测与 UTF-8 规范化的纯文本字幕扩展名（排除 `.sup` 等二进制图像字幕）。
 */
val TEXT_SUBTITLE_EXTENSIONS = setOf("ass", "ssa", "srt", "vtt")

fun isTextSubtitleName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in TEXT_SUBTITLE_EXTENSIONS

/**
 * 字幕文本编码探测与 UTF-8 规范化器接口：
 * 将非 UTF-8 字幕（GB18030/GBK、Big5、Shift_JIS、EUC-JP、CP949/EUC-KR、Windows-1252、UTF-16LE/BE）
 * 无损转为干净的 UTF-8 文本或字节数组，避免 mpv 在无 uchardet 时按 Latin-1 误解码导致中文/日韩乱码。
 *
 * 遵循无 `expect`/`actual` 约定：commonMain 提供基础 UTF-8/UTF-16 实现，JVM 平台（Desktop / Android）
 * 在启动时注入基于 `java.nio.charset.Charset` 的完整实现 [SubtitleEncoding.install]。
 */
interface SubtitleTextTranscoder {
    fun decodeToString(
        bytes: ByteArray,
        effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
        systemLanguage: AppLanguage = PikoLocale.systemLanguage,
    ): String

    fun normalizeToUtf8(
        bytes: ByteArray,
        effectiveLanguage: AppLanguage = PikoLocale.effectiveLanguage,
        systemLanguage: AppLanguage = PikoLocale.systemLanguage,
    ): ByteArray

    /** 读取本地字幕文件前 [maxBytes] 字节供语言探测使用；不支持或文件不可读时返回 null。 */
    fun readLocalSample(path: String, maxBytes: Int = 32 * 1024): ByteArray? = null
}

object SubtitleEncoding {
    @Volatile
    var current: SubtitleTextTranscoder = BasicSubtitleTextTranscoder
        private set

    fun install(transcoder: SubtitleTextTranscoder) {
        current = transcoder
    }
}

/**
 * 纯 Kotlin 基础实现（iOS 及未注入 JVM 解码器时的安全兜底）：
 * 支持 UTF-8 BOM、严格 UTF-8、UTF-16LE/BE（含 BOM 与无 BOM 零字节启发式）。
 */
object BasicSubtitleTextTranscoder : SubtitleTextTranscoder {
    override fun decodeToString(
        bytes: ByteArray,
        effectiveLanguage: AppLanguage,
        systemLanguage: AppLanguage,
    ): String {
        if (bytes.isEmpty()) return ""
        decodeUnicodeIfRecognized(bytes)?.let { return it }
        return bytes.decodeToString()
    }

    override fun normalizeToUtf8(
        bytes: ByteArray,
        effectiveLanguage: AppLanguage,
        systemLanguage: AppLanguage,
    ): ByteArray {
        if (bytes.isEmpty()) return bytes
        if (hasUtf8Bom(bytes)) {
            val stripped = bytes.copyOfRange(3, bytes.size)
            if (isValidUtf8(stripped)) return stripped
        }
        decodeUnicodeIfRecognized(bytes)?.let { return it.encodeToByteArray() }
        if (isValidUtf8(bytes)) return bytes
        return decodeToString(bytes, effectiveLanguage, systemLanguage).encodeToByteArray()
    }

    internal fun hasUtf8Bom(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()

    internal fun hasUtf16LeBom(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xFE.toByte() &&
            !(bytes.size >= 4 && bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte())

    internal fun hasUtf16BeBom(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() &&
            bytes[1] == 0xFF.toByte()

    /**
     * 优先识别带 BOM 的 UTF-8 / UTF-16LE / UTF-16BE，以及无 BOM 的 UTF-16LE / UTF-16BE（典型 ASCII 隔字节 0x00）。
     * 未识别时返回 null，由调用方继续走严格 UTF-8 或传统多字节字符集探测。
     */
    internal fun decodeUnicodeIfRecognized(bytes: ByteArray): String? {
        if (hasUtf8Bom(bytes)) {
            val payload = bytes.copyOfRange(3, bytes.size)
            if (isValidUtf8(payload)) return payload.decodeToString()
        }
        if (hasUtf16LeBom(bytes)) {
            return decodeUtf16(bytes, startOffset = 2, littleEndian = true)
        }
        if (hasUtf16BeBom(bytes)) {
            return decodeUtf16(bytes, startOffset = 2, littleEndian = false)
        }
        detectBomlessUtf16Endianness(bytes)?.let { littleEndian ->
            return decodeUtf16(bytes, startOffset = 0, littleEndian = littleEndian)
        }
        return null
    }

    internal fun detectBomlessUtf16Endianness(bytes: ByteArray): Boolean? {
        if (bytes.size < 4 || (bytes.size and 1) != 0) return null
        val samplePairs = minOf(bytes.size, 4096) / 2
        if (samplePairs < 2) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until samplePairs) {
            if (bytes[i * 2] == 0.toByte()) evenZeros++
            if (bytes[i * 2 + 1] == 0.toByte()) oddZeros++
        }
        val minRequired = maxOf(2, samplePairs / 4)
        val maxOpposite = samplePairs / 20
        return when {
            oddZeros >= minRequired && evenZeros <= maxOpposite -> true
            evenZeros >= minRequired && oddZeros <= maxOpposite -> false
            else -> null
        }
    }

    internal fun decodeUtf16(bytes: ByteArray, startOffset: Int, littleEndian: Boolean): String {
        val count = ((bytes.size - startOffset) / 2).coerceAtLeast(0)
        val chars = CharArray(count)
        var out = 0
        var pos = startOffset
        while (pos + 1 < bytes.size) {
            val b0 = bytes[pos].toInt() and 0xFF
            val b1 = bytes[pos + 1].toInt() and 0xFF
            val codeUnit = if (littleEndian) (b0 or (b1 shl 8)) else ((b0 shl 8) or b1)
            chars[out++] = codeUnit.toChar()
            pos += 2
        }
        return chars.concatToString(0, out)
    }

    /** 严格 RFC 3629 UTF-8 字节流校验（拒绝过长编码、代理区与越界码点）。 */
    internal fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b0 = bytes[i].toInt() and 0xFF
            when {
                b0 <= 0x7F -> i++
                b0 in 0xC2..0xDF -> {
                    if (i + 1 >= n) return false
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80) return false
                    i += 2
                }
                b0 in 0xE0..0xEF -> {
                    if (i + 2 >= n) return false
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80 || (b2 and 0xC0) != 0x80) return false
                    if (b0 == 0xE0 && b1 < 0xA0) return false // overlong
                    if (b0 == 0xED && b1 >= 0xA0) return false // UTF-16 surrogates U+D800..U+DFFF
                    i += 3
                }
                b0 in 0xF0..0xF4 -> {
                    if (i + 3 >= n) return false
                    val b1 = bytes[i + 1].toInt() and 0xFF
                    val b2 = bytes[i + 2].toInt() and 0xFF
                    val b3 = bytes[i + 3].toInt() and 0xFF
                    if ((b1 and 0xC0) != 0x80 || (b2 and 0xC0) != 0x80 || (b3 and 0xC0) != 0x80) return false
                    if (b0 == 0xF0 && b1 < 0x90) return false // overlong
                    if (b0 == 0xF4 && b1 > 0x8F) return false // > U+10FFFF
                    i += 4
                }
                else -> return false
            }
        }
        return true
    }
}
