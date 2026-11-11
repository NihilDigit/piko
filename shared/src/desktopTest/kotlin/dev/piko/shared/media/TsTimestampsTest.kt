package dev.piko.shared.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TsTimestampsTest {
    private fun tsPacket(pid: Int, payloadStart: Boolean, randomAccess: Boolean = false, pesPtsMs: Long? = null): ByteArray {
        val packet = ByteArray(188) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (((if (payloadStart) 0x40 else 0) or ((pid ushr 8) and 0x1F))).toByte()
        packet[2] = (pid and 0xFF).toByte()
        var pos = 4
        if (randomAccess) {
            packet[3] = 0x30
            packet[4] = 1
            packet[5] = 0x40
            pos = 6
        } else {
            packet[3] = 0x10
        }
        if (pesPtsMs != null) {
            val ticks = pesPtsMs * 90L
            packet[pos] = 0x00
            packet[pos + 1] = 0x00
            packet[pos + 2] = 0x01
            packet[pos + 3] = 0xE0.toByte()
            packet[pos + 4] = 0x00
            packet[pos + 5] = 0x00
            packet[pos + 6] = 0x80.toByte()
            packet[pos + 7] = 0x80.toByte()
            packet[pos + 8] = 0x05
            packet[pos + 9] = (0x20 or (((ticks ushr 30) and 0x07).toInt() shl 1) or 1).toByte()
            packet[pos + 10] = ((ticks ushr 22) and 0xFF).toByte()
            packet[pos + 11] = ((((ticks ushr 15) and 0x7F).toInt() shl 1) or 1).toByte()
            packet[pos + 12] = ((ticks ushr 7) and 0xFF).toByte()
            packet[pos + 13] = (((ticks and 0x7F).toInt() shl 1) or 1).toByte()
        }
        return packet
    }

    private fun scanSingle(bytes: ByteArray): TsTimestamps.VerifiedSlice? {
        val scanner = TsTimestamps.VerifiedSliceScanner()
        return scanner.feed(bytes, 0L) ?: scanner.finish()
    }

    private fun sampleHevcTsBytes(): ByteArray =
        generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .map { it.resolve("testdata/media/transcode-hevc-aac.ts") }
            .first { it.isFile }
            .readBytes()

    @Test
    fun elapsedMsMeasuresDurationAcross33BitPtsWrap() {
        val wrapMs = (1L shl 33) / 90L
        assertEquals(139_000L, TsTimestamps.elapsedMs(wrapMs - 40_000L, 99_000L))
        assertEquals(30_000L, TsTimestamps.elapsedMs(10_000L, 40_000L))
    }

    @Test
    fun verifiedSliceFindsRealHevcKeyframeWithPatPmtAndParameterSetsAcrossArbitraryChunkSplits() {
        val realTs = sampleHevcTsBytes()
        val prefixGarbage = tsPacket(pid = 0x100, payloadStart = false) + tsPacket(pid = 0x100, payloadStart = false)
        val full = prefixGarbage + realTs

        val singlePass = assertNotNull(scanSingle(full))
        val expectedFirstPat = (0 until full.size step 188).first { pos ->
            val payloadStart = full[pos + 1].toInt() and 0x40 != 0
            val pid = ((full[pos + 1].toInt() and 0x1F) shl 8) or (full[pos + 2].toInt() and 0xFF)
            payloadStart && pid == 0
        }
        assertEquals(expectedFirstPat.toLong(), singlePass.sliceStartOffset)
        assertEquals(0x47.toByte(), full[singlePass.sliceStartOffset.toInt()])
        val patPid = ((full[singlePass.sliceStartOffset.toInt() + 1].toInt() and 0x1F) shl 8) or
            (full[singlePass.sliceStartOffset.toInt() + 2].toInt() and 0xFF)
        assertEquals(0, patPid)

        for (chunkSize in listOf(188, 376, 503, 188 * 9 + 77)) {
            val scanner = TsTimestamps.VerifiedSliceScanner()
            var offset = 0
            var found: TsTimestamps.VerifiedSlice? = null
            while (offset < full.size && found == null) {
                val end = minOf(full.size, offset + chunkSize)
                found = scanner.feed(full.copyOfRange(offset, end), offset.toLong())
                offset = end
            }
            if (found == null) found = scanner.finish()
            val verified = assertNotNull(found, "chunkSize=$chunkSize failed to locate verified slice")
            assertEquals(singlePass.sliceStartOffset, verified.sliceStartOffset, "chunkSize=$chunkSize")
            assertEquals(singlePass.ptsMs, verified.ptsMs, "chunkSize=$chunkSize")
        }
    }

    @Test
    fun verifiedSliceRejectsKeyframeMissingParameterSetsOrMissingPsi() {
        val pat = ByteArray(188) { 0xFF.toByte() }.also { pkt ->
            pkt[0] = 0x47
            pkt[1] = 0x40
            pkt[2] = 0x00
            pkt[3] = 0x10
            pkt[4] = 0x00
            pkt[5] = 0x00
            pkt[6] = 0xB0.toByte()
            pkt[7] = 0x0D
            pkt[13] = 0x00
            pkt[14] = 0x01
            pkt[15] = 0xF0.toByte()
            pkt[16] = 0x20
        }
        val pmt = ByteArray(188) { 0xFF.toByte() }.also { pkt ->
            pkt[0] = 0x47
            pkt[1] = 0x50
            pkt[2] = 0x20
            pkt[3] = 0x10
            pkt[4] = 0x00
            pkt[5] = 0x02
            pkt[6] = 0xB0.toByte()
            pkt[7] = 0x12
            pkt[15] = 0xF0.toByte()
            pkt[16] = 0x00
            pkt[17] = 0x24
            pkt[18] = 0xE1.toByte()
            pkt[19] = 0x00
            pkt[20] = 0xF0.toByte()
            pkt[21] = 0x00
        }
        val trailWithoutParams = tsPacket(pid = 0x0100, payloadStart = true, randomAccess = true, pesPtsMs = 5_000L).also { pkt ->
            val nalPos = 22
            pkt[nalPos] = 0x00
            pkt[nalPos + 1] = 0x00
            pkt[nalPos + 2] = 0x01
            pkt[nalPos + 3] = 0x02
            pkt[nalPos + 4] = 0x01
            pkt[nalPos + 5] = 0x80.toByte()
        }
        assertNull(scanSingle(pat + pmt + trailWithoutParams))

        val realWithoutPat = sampleHevcTsBytes().let { raw ->
            val out = ArrayList<Byte>(raw.size)
            var pos = 0
            while (pos + 188 <= raw.size) {
                val pid = ((raw[pos + 1].toInt() and 0x1F) shl 8) or (raw[pos + 2].toInt() and 0xFF)
                if (pid != 0) {
                    for (i in pos until pos + 188) out.add(raw[i])
                }
                pos += 188
            }
            out.toByteArray()
        }
        assertTrue(realWithoutPat.isNotEmpty())
        assertNull(scanSingle(realWithoutPat))
    }

    @Test
    fun slicedByteSourceMasksOrphanVideoPacketsBeforeVerifiedKeyframePes() = kotlinx.coroutines.runBlocking {
        val realTs = sampleHevcTsBytes()
        val firstPat = (0 until realTs.size step 188).first { pos ->
            val payloadStart = realTs[pos + 1].toInt() and 0x40 != 0
            val pid = ((realTs[pos + 1].toInt() and 0x1F) shl 8) or (realTs[pos + 2].toInt() and 0xFF)
            payloadStart && pid == 0
        }
        val verifiedBase = assertNotNull(scanSingle(realTs))
        assertTrue(verifiedBase.videoPid > 0)
        // Insert an orphan previous-GOP P-frame TS packet between PMT and the real keyframe PES
        val pmtEnd = firstPat + 188 * 2
        val orphanVideoPacket = tsPacket(pid = verifiedBase.videoPid, payloadStart = false)
        val combined = realTs.copyOfRange(0, pmtEnd) + orphanVideoPacket + realTs.copyOfRange(pmtEnd, realTs.size)

        val verified = assertNotNull(scanSingle(combined))
        assertEquals(firstPat.toLong(), verified.sliceStartOffset)
        assertTrue(verified.keyframeOffset > verified.sliceStartOffset + 188 * 2)

        val inner = object : dev.piko.shared.media.proxy.ProxyByteSource {
            override val size: Long = combined.size.toLong()
            override suspend fun openReader(): dev.piko.shared.media.proxy.ProxyReader =
                object : dev.piko.shared.media.proxy.ProxyReader {
                    override var position: Long = 0L
                    override suspend fun seekTo(position: Long) { this.position = position }
                    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (position >= combined.size) return -1
                        val n = minOf(length.toLong(), combined.size - position).toInt()
                        combined.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                        position += n
                        return n
                    }
                    override fun close() = Unit
                }
            override fun close() = Unit
        }
        val sliceLen = combined.size - verified.sliceStartOffset
        val sliced = dev.piko.shared.media.proxy.SlicedByteSource(
            inner = inner,
            offset = verified.sliceStartOffset,
            size = sliceLen,
            nullVideoBeforeOffset = verified.keyframeOffset - verified.sliceStartOffset,
            nullVideoPid = verified.videoPid,
        )
        val out = ByteArray(sliceLen.toInt())
        sliced.openReader().use { reader ->
            var total = 0
            while (total < out.size) {
                val n = reader.read(out, total, out.size - total)
                if (n <= 0) break
                total += n
            }
            assertEquals(out.size, total)
        }
        val keyframeRel = (verified.keyframeOffset - verified.sliceStartOffset).toInt()
        for (pos in 0 until keyframeRel step 188) {
            val pid = ((out[pos + 1].toInt() and 0x1F) shl 8) or (out[pos + 2].toInt() and 0xFF)
            assertTrue(pid != verified.videoPid, "orphan video packet at $pos should be masked to null PID 0x1FFF")
        }
        val keyPid = ((out[keyframeRel + 1].toInt() and 0x1F) shl 8) or (out[keyframeRel + 2].toInt() and 0xFF)
        assertEquals(verified.videoPid, keyPid)
    }
}
