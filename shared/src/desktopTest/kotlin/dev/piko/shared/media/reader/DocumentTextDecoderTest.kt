package dev.piko.shared.media.reader

import dev.piko.data.auth.AppLanguage
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentTextDecoderTest {
    @Test
    fun decodesUtf8WithAndWithoutBomAndNormalizesLineEndings() {
        val raw = "第一行\r\n第二行\r第三行\n".toByteArray(Charsets.UTF_8)
        val plain = JvmDocumentCharsetDecoder.decode(raw)
        assertEquals("UTF-8", plain.encodingName)
        assertEquals("第一行\n第二行\n第三行\n", plain.text)
        assertFalse(plain.looksBinary)

        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + raw
        val bomDecoded = JvmDocumentCharsetDecoder.decode(withBom)
        assertEquals("UTF-8 BOM", bomDecoded.encodingName)
        assertEquals("第一行\n第二行\n第三行\n", bomDecoded.text)
    }

    @Test
    fun decodesUtf16LeAndBeWithBom() {
        val sample = "Hello 世界\r\nLine 2"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + sample.toByteArray(Charsets.UTF_16BE)

        val decodedLe = BasicDocumentCharsetDecoder.decode(le)
        assertEquals("UTF-16LE", decodedLe.encodingName)
        assertEquals("Hello 世界\nLine 2", decodedLe.text)

        val decodedBe = BasicDocumentCharsetDecoder.decode(be)
        assertEquals("UTF-16BE", decodedBe.encodingName)
        assertEquals("Hello 世界\nLine 2", decodedBe.text)
    }

    @Test
    fun trimsTrailingIncompleteUtf8SequenceOnTruncatedPreview() {
        val full = "前缀中文测试".toByteArray(Charsets.UTF_8)
        // Cut off the final byte of the last 3-byte Hanzi character
        val truncated = full.copyOf(full.size - 1)
        val decoded = BasicDocumentCharsetDecoder.decode(truncated)
        assertEquals("UTF-8", decoded.encodingName)
        assertEquals("前缀中文测", decoded.text)
        assertFalse('\uFFFD' in decoded.text)
    }

    @Test
    fun detectsLegacyCjkEncodingsOnJvm() {
        val gbSample = "这是一段使用简体中文 GB18030 编码保存的小说章节内容，包含标点符号与常见汉字。"
        val gbBytes = gbSample.toByteArray(Charset.forName("GB18030"))
        val gbDecoded = JvmDocumentCharsetDecoder.decode(
            gbBytes,
            effectiveLanguage = AppLanguage.ZH_CN,
            systemLanguage = AppLanguage.ZH_CN,
        )
        assertEquals(gbSample, gbDecoded.text)
        assertEquals("GB18030", gbDecoded.encodingName)

        val sjisSample = "これは日本語のシフトJISで保存されたテキストファイルのテスト文章です。"
        val sjisBytes = sjisSample.toByteArray(Charset.forName("Shift_JIS"))
        val sjisDecoded = JvmDocumentCharsetDecoder.decode(
            sjisBytes,
            effectiveLanguage = AppLanguage.JA,
            systemLanguage = AppLanguage.JA,
        )
        assertEquals(sjisSample, sjisDecoded.text)
        assertEquals("Shift-JIS", sjisDecoded.encodingName)

        val krSample = "이것은 한국어 인코딩으로 저장된 문서 테스트 내용입니다."
        val krBytes = krSample.toByteArray(Charset.forName("x-windows-949"))
        val krDecoded = JvmDocumentCharsetDecoder.decode(
            krBytes,
            effectiveLanguage = AppLanguage.KO,
            systemLanguage = AppLanguage.KO,
        )
        assertEquals(krSample, krDecoded.text)
        assertEquals("CP949", krDecoded.encodingName)
    }

    @Test
    fun supportsForcedCharsetOverride() {
        val sample = "繁體中文測試內容，用於確認手動編碼切換。"
        val big5Bytes = sample.toByteArray(Charset.forName("Big5"))
        val decoded = JvmDocumentCharsetDecoder.decode(big5Bytes, forcedCharset = "Big5")
        assertEquals(sample, decoded.text)
        assertEquals("Big5", decoded.encodingName)
    }

    @Test
    fun flagsLikelyBinaryPayload() {
        val binary = ByteArray(256) { i -> (i % 7).toByte() }
        val decoded = JvmDocumentCharsetDecoder.decode(binary)
        assertTrue(decoded.looksBinary)
    }
}
