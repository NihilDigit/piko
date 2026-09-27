package dev.piko.shared.media

/**
 * 从 MPEG-TS 字节里读视频的显示时间戳（PTS），毫秒。
 *
 * 随机片段的切片按平均码率算起点字节，码率不均时切出来的那一截实际从哪一刻开始，只有流里的时间戳说得准。
 * 视频按 PES 头的 stream_id（0xE0 到 0xEF）认，不解析 PAT/PMT：PikPak 的转码只有一路视频，
 * 为认它先读节目表，切片开头未必带着节目表。
 */
internal object TsTimestamps {
    /** [bytes] 里第一个带 PTS 的视频 PES 的时间戳。 */
    fun firstVideoPtsMs(bytes: ByteArray): Long? = videoPes(bytes, keyframeOnly = false)?.ptsMs

    /**
     * [bytes] 里第一个视频关键帧：它起头的那个 TS 包在 [bytes] 里的偏移与它的时间戳。
     * 关键帧按 TS 包自适应字段的 random_access_indicator 认。从这个包截起的一截 TS，解码器头一帧就能出画面，
     * 不必先丢掉一堆缺参数集、解不了的包。
     */
    fun firstVideoKeyframe(bytes: ByteArray): VideoPes? = videoPes(bytes, keyframeOnly = true)

    class VideoPes(val byteOffset: Int, val ptsMs: Long)

    /** [later] 比 [earlier] 晚多少毫秒。PTS 是 33 位计数，一条流里可能回绕一次。 */
    fun elapsedMs(earlier: Long, later: Long): Long {
        val diff = later - earlier
        return if (diff < 0) diff + WRAP_MS else diff
    }

    private fun videoPes(bytes: ByteArray, keyframeOnly: Boolean): VideoPes? {
        var at = syncOffset(bytes) ?: return null
        while (at + PACKET <= bytes.size) {
            ptsOfPacket(bytes, at, keyframeOnly)?.let { return VideoPes(at, it) }
            at += PACKET
        }
        return null
    }

    // 切片起点按 188 对齐，但不假定：连着两个包头都是 0x47 才算找到包边界
    private fun syncOffset(bytes: ByteArray): Int? =
        (0 until minOf(PACKET, bytes.size)).firstOrNull { start ->
            bytes[start] == SYNC && (start + PACKET >= bytes.size || bytes[start + PACKET] == SYNC)
        }

    private fun ptsOfPacket(bytes: ByteArray, at: Int, keyframeOnly: Boolean): Long? {
        val payloadStart = bytes[at + 1].toInt() and 0x40 != 0
        if (!payloadStart) return null
        val adaptation = (bytes[at + 3].toInt() ushr 4) and 0x3
        var payload = at + 4
        var randomAccess = false
        if (adaptation and 0x2 != 0) {
            val length = bytes[payload].toInt() and 0xff
            if (length > 0) randomAccess = bytes[payload + 1].toInt() and 0x40 != 0
            payload += 1 + length
        }
        if (adaptation and 0x1 == 0 || payload + 14 > at + PACKET) return null
        if (keyframeOnly && !randomAccess) return null
        val isPes = bytes[payload] == 0.toByte() && bytes[payload + 1] == 0.toByte() && bytes[payload + 2] == 1.toByte()
        val streamId = bytes[payload + 3].toInt() and 0xff
        if (!isPes || streamId !in 0xE0..0xEF) return null
        val hasPts = bytes[payload + 7].toInt() and 0x80 != 0
        if (!hasPts) return null
        val p = payload + 9
        val ticks = ((bytes[p].toLong() and 0x0E) shl 29) or
            ((bytes[p + 1].toLong() and 0xff) shl 22) or
            ((bytes[p + 2].toLong() and 0xFE) shl 14) or
            ((bytes[p + 3].toLong() and 0xff) shl 7) or
            ((bytes[p + 4].toLong() and 0xFE) ushr 1)
        return ticks / 90
    }

    private const val PACKET = 188
    private const val SYNC = 0x47.toByte()
    private const val WRAP_MS = (1L shl 33) / 90
}
