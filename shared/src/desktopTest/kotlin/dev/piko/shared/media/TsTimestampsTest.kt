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
            packet[3] = 0x30 // adaptation + payload
            packet[4] = 1    // adaptation_field_length = 1
            packet[5] = 0x40 // random_access_indicator = 1
            pos = 6
        } else {
            packet[3] = 0x10 // payload only
        }
        if (pesPtsMs != null) {
            val ticks = pesPtsMs * 90L
            packet[pos] = 0x00
            packet[pos + 1] = 0x00
            packet[pos + 2] = 0x01
            packet[pos + 3] = 0xE0.toByte() // video stream_id
            packet[pos + 4] = 0x00
            packet[pos + 5] = 0x00
            packet[pos + 6] = 0x80.toByte()
            packet[pos + 7] = 0x80.toByte() // PTS_DTS_flags = 10
            packet[pos + 8] = 0x05
            packet[pos + 9] = (0x20 or (((ticks ushr 30) and 0x07).toInt() shl 1) or 1).toByte()
            packet[pos + 10] = ((ticks ushr 22) and 0xFF).toByte()
            packet[pos + 11] = ((((ticks ushr 15) and 0x7F).toInt() shl 1) or 1).toByte()
            packet[pos + 12] = ((ticks ushr 7) and 0xFF).toByte()
            packet[pos + 13] = (((ticks and 0x7F).toInt() shl 1) or 1).toByte()
        }
        return packet
    }

    @Test
    fun keyframeSliceRewindsToPrecedingPatPacketSoDemuxerRetainsProgramTablesAndParameterSets() {
        val filler = tsPacket(pid = 0x100, payloadStart = false)
        val pat = tsPacket(pid = 0x0000, payloadStart = true)
        val pmt = tsPacket(pid = 0x1000, payloadStart = true)
        val idr = tsPacket(pid = 0x100, payloadStart = true, randomAccess = true, pesPtsMs = 12_000L)
        val stream = filler + filler + pat + pmt + idr

        val found = assertNotNull(TsTimestamps.firstVideoKeyframe(stream))
        // PAT 位于第 2 个包（2 * 188 = 376），PTS 仍取关键帧的 12_000 ms
        assertEquals(2 * 188, found.byteOffset)
        assertEquals(12_000L, found.ptsMs)
    }

    @Test
    fun keyframeWithoutNearbyPatFallsBackToKeyframePacket() {
        val filler = tsPacket(pid = 0x100, payloadStart = false)
        val idr = tsPacket(pid = 0x100, payloadStart = true, randomAccess = true, pesPtsMs = 8_500L)
        val stream = filler + idr

        val found = assertNotNull(TsTimestamps.firstVideoKeyframe(stream))
        assertEquals(188, found.byteOffset)
        assertEquals(8_500L, found.ptsMs)
    }

    @Test
    fun lastVideoPtsAndElapsedMsMeasureActualSliceDurationAcross33BitPtsWrap() {
        val wrapMs = (1L shl 33) / 90L
        val headPtsMs = wrapMs - 40_000L
        val tailPtsMs = 99_000L // 跨越 33 位 PTS 回绕后实际覆盖 139_000 ms（不足 180s 的高码率切片）
        val head = tsPacket(pid = 0x100, payloadStart = true, randomAccess = true, pesPtsMs = headPtsMs)
        val mid = tsPacket(pid = 0x100, payloadStart = true, pesPtsMs = wrapMs - 10_000L)
        val tail = tsPacket(pid = 0x100, payloadStart = true, pesPtsMs = tailPtsMs) + tsPacket(pid = 0x100, payloadStart = false)

        val startPts = assertNotNull(TsTimestamps.firstVideoKeyframe(head)).ptsMs
        val endPts = assertNotNull(TsTimestamps.lastVideoPtsMs(mid + tail))
        assertEquals(139_000L, TsTimestamps.elapsedMs(startPts, endPts))
    }

    private fun sampleHevcTsBytes(): ByteArray =
        generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .map { it.resolve("testdata/media/transcode-hevc-aac.ts") }
            .first { it.isFile }
            .readBytes()

    @Test
    fun verifiedSliceFindsRealHevcKeyframeWithPatPmtAndParameterSetsAcrossArbitraryChunkSplits() {
        val realTs = sampleHevcTsBytes()
        val prefixGarbage = tsPacket(pid = 0x100, payloadStart = false) + tsPacket(pid = 0x100, payloadStart = false)
        val full = prefixGarbage + realTs

        val singlePass = assertNotNull(TsTimestamps.firstVerifiedVideoSlice(full))
        // FFmpeg mpegts 样片第 0 包为 SDT（PID 0x0011）、第 1 包为 PAT（PID 0x0000），加上前缀 2 包后切点精确落在第 3 包（564）
        val expectedFirstPat = (0 until full.size step 188).first { pos ->
            val payloadStart = full[pos + 1].toInt() and 0x40 != 0
            val pid = ((full[pos + 1].toInt() and 0x1F) shl 8) or (full[pos + 2].toInt() and 0xFF)
            payloadStart && pid == 0
        }
        assertEquals(expectedFirstPat, singlePass.byteOffset)
        assertEquals(0x47.toByte(), full[singlePass.byteOffset])
        val patPid = ((full[singlePass.byteOffset + 1].toInt() and 0x1F) shl 8) or (full[singlePass.byteOffset + 2].toInt() and 0xFF)
        assertEquals(0, patPid)

        // 按非 188 对齐的碎块（例如每块 503 字节）流式喂入，跨块状态机不得漏掉前导 PAT/PMT 或截断 PES
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
            assertEquals(singlePass.byteOffset.toLong(), verified.sliceStartOffset, "chunkSize=$chunkSize")
            assertEquals(singlePass.ptsMs, verified.ptsMs, "chunkSize=$chunkSize")
        }
    }

    @Test
    fun verifiedSliceRejectsKeyframeMissingParameterSetsOrMissingPsi() {
        // 仅有 random_access_indicator 和视频 PES 头、但载荷全部是 0xFF（无 VPS/SPS/PPS 与 IRAP NAL）的伪关键帧必须被拒绝
        val pat = ByteArray(188) { 0xFF.toByte() }.also { pkt ->
            pkt[0] = 0x47
            pkt[1] = 0x40
            pkt[2] = 0x00
            pkt[3] = 0x10
            pkt[4] = 0x00 // pointer_field
            pkt[5] = 0x00 // table_id = PAT
            pkt[6] = 0xB0.toByte()
            pkt[7] = 0x0D // section_length = 13
            pkt[13] = 0x00
            pkt[14] = 0x01 // program 1
            pkt[15] = 0xF0.toByte()
            pkt[16] = 0x20 // PMT PID = 0x1020 -> wait: 0x10 << 8 | 0x20: (0xF0 & 0x1F)=0x10 -> 0x1020
        }
        val pmt = ByteArray(188) { 0xFF.toByte() }.also { pkt ->
            pkt[0] = 0x47
            pkt[1] = 0x50 // payloadStart + PID high 0x10
            pkt[2] = 0x20 // PID = 0x1020
            pkt[3] = 0x10
            pkt[4] = 0x00 // pointer_field
            pkt[5] = 0x02 // table_id = PMT
            pkt[6] = 0xB0.toByte()
            pkt[7] = 0x12 // section_length = 18
            pkt[15] = 0xF0.toByte()
            pkt[16] = 0x00 // program_info_length = 0
            pkt[17] = 0x24 // stream_type = HEVC
            pkt[18] = 0xE1.toByte()
            pkt[19] = 0x00 // video PID = 0x0100
            pkt[20] = 0xF0.toByte()
            pkt[21] = 0x00
        }
        // 模拟日志中 "Skipping invalid undecodable NALU: 1"（TRAIL_R 非关键帧且无 VPS/SPS/PPS）
        val trailWithoutParams = tsPacket(pid = 0x0100, payloadStart = true, randomAccess = true, pesPtsMs = 5_000L).also { pkt ->
            // 在 PES 头后写入单个 HEVC TRAIL_R (nal_type = 1 -> header byte0 = 0x02, byte1 = 0x01)
            val nalPos = 22
            pkt[nalPos] = 0x00
            pkt[nalPos + 1] = 0x00
            pkt[nalPos + 2] = 0x01
            pkt[nalPos + 3] = 0x02 // nal_type = 1 (TRAIL_R)
            pkt[nalPos + 4] = 0x01
            pkt[nalPos + 5] = 0x80.toByte()
        }
        assertNull(TsTimestamps.firstVerifiedVideoSlice(pat + pmt + trailWithoutParams))

        // 真实 HEVC 流若剥掉所有 PAT（PID 0）包，无节目表上下文也不可作为独立切片起点
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
        assertNull(TsTimestamps.firstVerifiedVideoSlice(realWithoutPat))
    }
}
