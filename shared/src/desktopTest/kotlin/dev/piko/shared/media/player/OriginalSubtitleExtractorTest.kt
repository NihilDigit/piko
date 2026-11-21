package dev.piko.shared.media.player

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `detects Blu-ray PGS and VobSub tracks as unextracted without scanning clusters`() = runBlocking {
        val tracksPayload = buildTrackEntry(
            trackNumber = 3,
            trackType = 17,
            codecId = "S_HDMV/PGS",
            language = "jpn",
            name = "",
        ) + buildTrackEntry(
            trackNumber = 4,
            trackType = 17,
            codecId = "S_HDMV/PGS",
            language = "chi",
            name = "简体",
        ) + buildTrackEntry(
            trackNumber = 5,
            trackType = 17,
            codecId = "S_VOBSUB",
            language = "eng",
            name = "English",
        )
        // Small MKV with binary PGS payload in Cluster — even with canScanFullMkv = true, must NOT scan or extract!
        val pgsBlock = byteArrayOf(0x83.toByte(), 0x00, 0x10, 0x00, 0x16, 0x00, 0x0B)
        val clusterPayload = buildEbmlElement(0xE7L, byteArrayOf(0x03, 0xE8.toByte())) +
            buildEbmlElement(0xA3L, pgsBlock)
        val segmentBody = buildEbmlElement(0x1654AE6BL, tracksPayload) + buildEbmlElement(0x1F43B675L, clusterPayload)
        val fileBytes = buildEbmlElement(0x1A45DFA3L, byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01)) +
            buildEbmlElement(0x18538067L, segmentBody)

        var rangeReads = 0
        val result = OriginalSubtitleExtractor.extract(
            fileSize = fileBytes.size.toLong(),
            readRange = { offset, length ->
                rangeReads++
                val start = offset.toInt()
                if (start !in fileBytes.indices) null
                else fileBytes.copyOfRange(start, minOf(fileBytes.size, start + length))
            },
            canScanFullMkv = true,
        )

        assertTrue(result.extracted.isEmpty(), "Bitmap subtitle tracks must never be text-extracted")
        assertEquals(1, rangeReads, "Header probe alone must suffice when only bitmap subtitle tracks exist")
        assertEquals(3, result.unextracted.size)
        assertEquals("S_HDMV/PGS", result.unextracted[0].codecId)
        assertEquals("sup", result.unextracted[0].extension)
        assertEquals("jpn", result.unextracted[0].language)
        assertTrue(result.unextracted[0].isImage)
        assertEquals("S_HDMV/PGS", result.unextracted[1].codecId)
        assertEquals("chi", result.unextracted[1].language)
        assertTrue(result.unextracted[1].isImage)
        assertEquals("S_VOBSUB", result.unextracted[2].codecId)
        assertEquals("sub", result.unextracted[2].extension)
        assertTrue(result.unextracted[2].isImage)
    }

    @Test
    fun `mixed MKV extracts text SRT while keeping PGS in unextracted`() = runBlocking {
        val tracksPayload = buildTrackEntry(
            trackNumber = 2,
            trackType = 17,
            codecId = "S_TEXT/UTF8",
            language = "chi",
            name = "简体",
        ) + buildTrackEntry(
            trackNumber = 3,
            trackType = 17,
            codecId = "S_HDMV/PGS",
            language = "jpn",
            name = "日本語",
        )
        val srtBlock = byteArrayOf(0x82.toByte(), 0x00, 0x00, 0x00) + "你好世界".encodeToByteArray()
        val pgsBlock = byteArrayOf(0x83.toByte(), 0x00, 0x00, 0x00, 0x16, 0x00, 0x0A, 0xFF.toByte())
        val clusterPayload = buildEbmlElement(0xE7L, byteArrayOf(0x03, 0xE8.toByte())) +
            buildEbmlElement(0xA3L, srtBlock) +
            buildEbmlElement(0xA3L, pgsBlock)
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
            canScanFullMkv = true,
        )

        assertEquals(1, result.extracted.size)
        assertEquals(2, result.extracted.single().info.trackNumber)
        assertTrue(result.extracted.single().bytes.decodeToString().contains("你好世界"))
        assertEquals(1, result.unextracted.size)
        assertEquals(3, result.unextracted.single().trackNumber)
        assertEquals("S_HDMV/PGS", result.unextracted.single().codecId)
        assertTrue(result.unextracted.single().isImage)
    }

    @Test
    fun `mkv large video SimpleBlock crossing 1 MiB chunk boundary preserves EBML framing and skips fake subtitle bytes`() = runBlocking {
        val tracksPayload = buildTrackEntry(
            trackNumber = 2,
            trackType = 17,
            codecId = "S_TEXT/UTF8",
            language = "eng",
            name = "English",
        )
        // Craft a 1.35 MiB video SimpleBlock (track=1 -> 0x81) whose compressed payload straddles the 1 MiB chunk boundary
        // and embeds decoy 0xE7 (corrupt huge timestamp) + decoy 0xA3 subtitle SimpleBlock bytes right after 1 MiB!
        val videoPayload = ByteArray(1_350_000)
        videoPayload[0] = 0x81.toByte() // track 1 (video, non-subtitle)
        videoPayload[1] = 0x00
        videoPayload[2] = 0x00
        videoPayload[3] = 0x80.toByte()
        val decoyTimecode = buildEbmlElement(0xE7L, ByteArray(8) { 0x7F })
        val decoySubBlock = buildEbmlElement(0xA3L, byteArrayOf(0x82.toByte(), 0x00, 0x10, 0x00) + "FAKE_IN_VIDEO".encodeToByteArray())
        val injectAt = 1_060_000
        decoyTimecode.copyInto(videoPayload, injectAt)
        decoySubBlock.copyInto(videoPayload, injectAt + decoyTimecode.size)

        val realSubBlock = buildEbmlElement(0xA3L, byteArrayOf(0x82.toByte(), 0x01, 0xF4.toByte(), 0x00) + "Real Subtitle Cue".encodeToByteArray())
        val clusterPayload = buildEbmlElement(0xE7L, byteArrayOf(0x07, 0xD0.toByte())) + // Cluster TC = 2000ms
            buildEbmlElement(0xA3L, videoPayload) +
            realSubBlock
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
            canScanFullMkv = true,
        )

        assertEquals(1, result.extracted.size)
        val srt = result.extracted.single().bytes.decodeToString()
        assertFalse(srt.contains("FAKE_IN_VIDEO"), "Must never byte-scan inside compressed video SimpleBlock: $srt")
        assertTrue(srt.contains("Real Subtitle Cue"), "Must extract real subtitle cue after cross-chunk video block: $srt")
        assertTrue(srt.contains("00:00:02,500 --> 00:00:05,500"), "Cluster timecode must remain uncorrupted: $srt")
    }

    @Test
    fun `mp4 extracts constant-size tx3g cues and resists stts OOM bomb and co64 Long overflow`() = runBlocking {
        // 1. Valid constant-size stszDefault > 0 with malicious huge stts sample_count: cross-checked against stsz.sampleCount!
        val sampleText = "Hello MP4"
        val sampleBytes = ByteArray(2 + sampleText.length).also {
            it[0] = 0
            it[1] = sampleText.length.toByte()
            sampleText.encodeToByteArray().copyInto(it, 2)
        }
        val ftyp = mp4Box("ftyp", "isom\u0000\u0000\u0000\u0000".encodeToByteArray()) // 16 bytes
        val mdat = mp4Box("mdat", sampleBytes + sampleBytes) // starts at 16, payload at 24
        val validMoov = buildMp4Moov(
            sttsEntries = listOf(0x7FFF_FFFFL to 2_000L), // Malicious 2.14B count in stts, while stsz says 2 samples!
            stscSamplesPerChunk = 0x7FFF_FFFFL, // Malicious 2.14B samplesPerChunk!
            stszDefaultSize = sampleBytes.size.toLong(),
            stszSampleCount = 2L,
            co64Offsets = longArrayOf(24L),
        )
        val mp4Bytes = ftyp + mdat + validMoov
        val validResult = OriginalSubtitleExtractor.extract(
            fileSize = mp4Bytes.size.toLong(),
            readRange = { offset, length ->
                val start = offset.toInt()
                if (start !in mp4Bytes.indices) null
                else mp4Bytes.copyOfRange(start, minOf(mp4Bytes.size, start + length))
            },
        )
        assertEquals(1, validResult.extracted.size)
        val srt = validResult.extracted.single().bytes.decodeToString()
        assertTrue(srt.contains("00:00:00,000 --> 00:00:02,000"), "Expected first cue in SRT: $srt")
        assertTrue(srt.contains("00:00:02,000 --> 00:00:04,000"), "Expected second cue in SRT: $srt")
        assertTrue(srt.contains("Hello MP4"))

        // 2. Malicious co64 offset near Long.MAX_VALUE: must not overflow (off + sz) into negative <= fileSize
        val overflowMoov = buildMp4Moov(
            sttsEntries = listOf(1L to 1_000L),
            stscSamplesPerChunk = 1L,
            stszDefaultSize = 16L,
            stszSampleCount = 1L,
            co64Offsets = longArrayOf(Long.MAX_VALUE - 4L),
        )
        val overflowMp4 = ftyp + mdat + overflowMoov
        val overflowResult = OriginalSubtitleExtractor.extract(
            fileSize = overflowMp4.size.toLong(),
            readRange = { offset, length ->
                if (offset < 0L || offset >= overflowMp4.size.toLong()) {
                    throw AssertionError("Out-of-bounds range read due to Long overflow: offset=$offset")
                }
                val start = offset.toInt()
                overflowMp4.copyOfRange(start, minOf(overflowMp4.size, start + length))
            },
        )
        assertTrue(overflowResult.extracted.isEmpty())
        assertEquals(1, overflowResult.unextracted.size)
    }

    private fun mp4Box(type: String, payload: ByteArray): ByteArray {
        val total = 8 + payload.size
        val out = ByteArray(total)
        writeU32(out, 0, total.toLong())
        type.encodeToByteArray().copyInto(out, 4, 0, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun writeU32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeU64(buf: ByteArray, offset: Int, value: Long) {
        writeU32(buf, offset, value ushr 32)
        writeU32(buf, offset + 4, value)
    }

    private fun buildMp4Moov(
        sttsEntries: List<Pair<Long, Long>>,
        stscSamplesPerChunk: Long,
        stszDefaultSize: Long,
        stszSampleCount: Long,
        co64Offsets: LongArray,
    ): ByteArray {
        val tkhd = ByteArray(24).also { writeU32(it, 12, 3L) }
        val mdhd = ByteArray(24).also {
            writeU32(it, 12, 1_000L) // timescale = 1000
            it[20] = 0x15
            it[21] = 0xC7.toByte() // "eng"
        }
        val hdlr = ByteArray(12).also { "sbtl".encodeToByteArray().copyInto(it, 8) }
        val stsd = ByteArray(16).also {
            writeU32(it, 4, 1L)
            writeU32(it, 8, 8L)
            "tx3g".encodeToByteArray().copyInto(it, 12)
        }
        val stts = ByteArray(8 + sttsEntries.size * 8).also {
            writeU32(it, 4, sttsEntries.size.toLong())
            sttsEntries.forEachIndexed { idx, (cnt, delta) ->
                writeU32(it, 8 + idx * 8, cnt)
                writeU32(it, 12 + idx * 8, delta)
            }
        }
        val stsc = ByteArray(8 + 12).also {
            writeU32(it, 4, 1L)
            writeU32(it, 8, 1L)
            writeU32(it, 12, stscSamplesPerChunk)
            writeU32(it, 16, 1L)
        }
        val stsz = ByteArray(12).also {
            writeU32(it, 4, stszDefaultSize)
            writeU32(it, 8, stszSampleCount)
        }
        val co64 = ByteArray(8 + co64Offsets.size * 8).also {
            writeU32(it, 4, co64Offsets.size.toLong())
            co64Offsets.forEachIndexed { idx, off ->
                writeU64(it, 8 + idx * 8, off)
            }
        }
        val stbl = mp4Box("stbl", mp4Box("stsd", stsd) + mp4Box("stts", stts) + mp4Box("stsc", stsc) + mp4Box("stsz", stsz) + mp4Box("co64", co64))
        val minf = mp4Box("minf", stbl)
        val mdia = mp4Box("mdia", mp4Box("mdhd", mdhd) + mp4Box("hdlr", hdlr) + minf)
        val trak = mp4Box("trak", mp4Box("tkhd", tkhd) + mdia)
        return mp4Box("moov", trak)
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
