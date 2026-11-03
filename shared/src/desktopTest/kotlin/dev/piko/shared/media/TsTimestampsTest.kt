package dev.piko.shared.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
