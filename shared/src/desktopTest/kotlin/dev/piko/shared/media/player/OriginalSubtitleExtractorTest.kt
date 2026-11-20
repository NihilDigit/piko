package dev.piko.shared.media.player

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OriginalSubtitleExtractorTest {

    @Test
    fun `detects embedded MKV subtitles when Segment size exceeds 2 GiB without Int overflow`() = runBlocking {
        // 3.5 GiB Segment size: (dataStart + size).toInt() would wrap negative if cast before Long clamping!
        val hugeSegmentSize = 3_500_000_000L
        val tracksPayload = buildTrackEntry(
            trackNumber = 3,
            trackType = 17,
            codecId = "S_TEXT/UTF8",
            language = "chi",
            name = "简体中文",
        ) + buildTrackEntry(
            trackNumber = 4,
            trackType = 17,
            codecId = "S_TEXT/ASS",
            language = "jpn",
            name = "日本語",
        )
        val header = buildEbmlElement(0x1A45DFA3L, byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01)) +
            encodeEbmlId(0x18538067L) + encodeEbmlSize(hugeSegmentSize) +
            buildEbmlElement(0x1654AE6BL, tracksPayload)

        val result = OriginalSubtitleExtractor.extract(
            fileSize = hugeSegmentSize + 64L,
            readRange = { offset, length ->
                if (offset != 0L) null else header.copyOf(minOf(length, header.size))
            },
        )

        assertTrue(result.extracted.isEmpty(), "Large >96 MiB MKV should not be scanned over WAN")
        assertEquals(2, result.unextracted.size, "Both embedded subtitle tracks must be detected")
        assertEquals(3, result.unextracted[0].trackNumber)
        assertEquals("S_TEXT/UTF8", result.unextracted[0].codecId)
        assertEquals("chi", result.unextracted[0].language)
        assertEquals("简体中文", result.unextracted[0].title)
        assertEquals(4, result.unextracted[1].trackNumber)
        assertEquals("S_TEXT/ASS", result.unextracted[1].codecId)
        assertEquals("jpn", result.unextracted[1].language)
    }

    @Test
    fun `extracts SRT cues from small MKV with Cluster and BlockGroup`() = runBlocking {
        val tracksPayload = buildTrackEntry(
            trackNumber = 2,
            trackType = 17,
            codecId = "S_TEXT/UTF8",
            language = "eng",
            name = "English",
        )
        // Simple BlockGroup: Block (track=2 -> 0x82, relTc=500ms, flags=0x00, text) + BlockDuration (2000ms)
        val blockBytes = byteArrayOf(0x82.toByte(), 0x01, 0xF4.toByte(), 0x00) + "Hello Piko".encodeToByteArray()
        val blockGroup = buildEbmlElement(0xA1L, blockBytes) + buildEbmlElement(0x9BL, byteArrayOf(0x07, 0xD0.toByte()))
        val clusterPayload = buildEbmlElement(0xE7L, byteArrayOf(0x03, 0xE8.toByte())) + // Cluster TC = 1000ms
            buildEbmlElement(0xA0L, blockGroup)
        val segmentBody = buildEbmlElement(0x1654AE6BL, tracksPayload) + buildEbmlElement(0x1F43B675L, clusterPayload)
        val fileBytes = buildEbmlElement(0x1A45DFA3L, byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01)) +
            buildEbmlElement(0x18538067L, segmentBody)

        val result = OriginalSubtitleExtractor.extract(
            fileSize = fileBytes.size.toLong(),
            readRange = { offset, length ->
                val start = offset.toInt()
                if (start !in fileBytes.indices) null
                else fileBytes.copyOfRange(start, minOf(fileBytes.size, start + length))
            },
        )

        assertEquals(1, result.extracted.size)
        val srt = result.extracted.single().bytes.decodeToString()
        assertTrue(srt.contains("00:00:01,500 --> 00:00:03,500"), "Expected calibrated cue timestamp in SRT: $srt")
        assertTrue(srt.contains("Hello Piko"), "Expected cue text in SRT: $srt")
    }

    private fun buildTrackEntry(
        trackNumber: Int,
        trackType: Int,
        codecId: String,
        language: String,
        name: String,
    ): ByteArray {
        val body = buildEbmlElement(0xD7L, byteArrayOf(trackNumber.toByte())) +
            buildEbmlElement(0x83L, byteArrayOf(trackType.toByte())) +
            buildEbmlElement(0x86L, codecId.encodeToByteArray()) +
            buildEbmlElement(0x22B59CL, language.encodeToByteArray()) +
            buildEbmlElement(0x536EL, name.encodeToByteArray())
        return buildEbmlElement(0xAEL, body)
    }

    private fun buildEbmlElement(id: Long, payload: ByteArray): ByteArray =
        encodeEbmlId(id) + encodeEbmlSize(payload.size.toLong()) + payload

    private fun encodeEbmlId(id: Long): ByteArray {
        val out = ByteArrayOutputStream()
        when {
            id <= 0xFFL -> out.write(id.toInt())
            id <= 0xFFFFL -> {
                out.write(((id ushr 8) and 0xFF).toInt())
                out.write((id and 0xFF).toInt())
            }
            id <= 0xFFFFFFL -> {
                out.write(((id ushr 16) and 0xFF).toInt())
                out.write(((id ushr 8) and 0xFF).toInt())
                out.write((id and 0xFF).toInt())
            }
            else -> {
                out.write(((id ushr 24) and 0xFF).toInt())
                out.write(((id ushr 16) and 0xFF).toInt())
                out.write(((id ushr 8) and 0xFF).toInt())
                out.write((id and 0xFF).toInt())
            }
        }
        return out.toByteArray()
    }

    private fun encodeEbmlSize(size: Long): ByteArray = when {
        size < 0x7FL -> byteArrayOf((0x80L or size).toByte())
        size < 0x3FFFL -> byteArrayOf((0x40L or (size ushr 8)).toByte(), (size and 0xFF).toByte())
        size < 0x1FFFFFL -> byteArrayOf(
            (0x20L or (size ushr 16)).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        )
        size < 0x0FFFFFFFL -> byteArrayOf(
            (0x10L or (size ushr 24)).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        )
        else -> byteArrayOf(
            (0x01L).toByte(),
            ((size ushr 48) and 0xFF).toByte(),
            ((size ushr 40) and 0xFF).toByte(),
            ((size ushr 32) and 0xFF).toByte(),
            ((size ushr 24) and 0xFF).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        )
    }
}
