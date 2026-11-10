package dev.piko.shared.media

/**
 * 从 MPEG-TS 字节里读视频的显示时间戳（PTS），毫秒。
 *
 * 随机片段的切片按平均码率算起点字节，码率不均时切出来的那一截实际从哪一刻开始，只有流里的时间戳说得准。
 * 视频按 PES 头的 stream_id（0xE0 到 0xEF）认，不解析 PAT/PMT：PikPak 的转码只有一路视频，
 * 为认它先读节目表，切片开头未必带着节目表。
 */
internal object TsTimestamps {
    class VerifiedSlice(val sliceStartOffset: Long, val ptsMs: Long)

    /**
     * 跨分块保持解析状态的 TS 安全切点扫描器，消除固定重叠包数漏掉前导 PAT/PMT 或跨块截断 PES 的盲区。
     */
    internal class VerifiedSliceScanner {
        private enum class Codec { HEVC, H264 }

        private class Candidate(
            val sliceStartOffset: Long,
            val ptsMs: Long,
            val codec: Codec,
        ) {
            var buffer = ByteArray(4096)
            var size = 0
            var overflowed = false

            fun append(src: ByteArray, offset: Int, length: Int) {
                if (length <= 0 || overflowed) return
                if (size + length > MAX_CANDIDATE_PES_BYTES) {
                    overflowed = true
                    return
                }
                if (size + length > buffer.size) {
                    var nextCap = buffer.size * 2
                    while (nextCap < size + length) nextCap *= 2
                    buffer = buffer.copyOf(minOf(nextCap, MAX_CANDIDATE_PES_BYTES))
                }
                src.copyInto(buffer, destinationOffset = size, startIndex = offset, endIndex = offset + length)
                size += length
            }
        }

        private var carry = ByteArray(0)
        private var carryOffset = 0L
        private var synced = false

        private var lastPatOffset = -1L
        private var pmtPid = -1
        private var sawPmtSinceLastPat = false
        private var videoPid = -1
        private var videoCodec: Codec? = null
        private var lastVideoContinuity = -1
        private var candidate: Candidate? = null

        fun feed(chunk: ByteArray, chunkStreamOffset: Long): VerifiedSlice? {
            if (chunk.isEmpty()) return null
            val combined: ByteArray
            val baseOffset: Long
            if (carry.isEmpty()) {
                combined = chunk
                baseOffset = chunkStreamOffset
            } else {
                combined = ByteArray(carry.size + chunk.size)
                carry.copyInto(combined, 0)
                chunk.copyInto(combined, carry.size)
                baseOffset = carryOffset
                carry = ByteArray(0)
            }

            var at = if (synced) {
                if (combined[0] == SYNC) 0 else syncOffset(combined) ?: run {
                    saveTail(combined, maxOf(0, combined.size - PACKET + 1), baseOffset)
                    synced = false
                    return null
                }
            } else {
                syncOffset(combined) ?: run {
                    saveTail(combined, maxOf(0, combined.size - PACKET + 1), baseOffset)
                    return null
                }
            }
            synced = true

            while (at + PACKET <= combined.size) {
                if (combined[at] != SYNC) {
                    val resync = (at until minOf(at + PACKET, combined.size)).firstOrNull { start ->
                        combined[start] == SYNC && (start + PACKET >= combined.size || combined[start + PACKET] == SYNC)
                    }
                    if (resync == null) {
                        candidate = null
                        lastVideoContinuity = -1
                        synced = false
                        saveTail(combined, at + 1, baseOffset)
                        return null
                    }
                    candidate = null
                    lastVideoContinuity = -1
                    at = resync
                    if (at + PACKET > combined.size) break
                }
                onPacket(combined, at, baseOffset + at)?.let { return it }
                at += PACKET
            }

            if (at < combined.size) {
                saveTail(combined, at, baseOffset)
            }
            return null
        }

        fun finish(): VerifiedSlice? {
            val pending = candidate ?: return null
            candidate = null
            return if (isSelfContainedKeyframe(pending.buffer, pending.size, pending.codec, completePes = true)) {
                VerifiedSlice(pending.sliceStartOffset, pending.ptsMs)
            } else {
                null
            }
        }

        private fun saveTail(bytes: ByteArray, from: Int, baseOffset: Long) {
            if (from >= bytes.size) {
                carry = ByteArray(0)
            } else {
                carry = bytes.copyOfRange(from, bytes.size)
                carryOffset = baseOffset + from
            }
        }

        private fun onPacket(bytes: ByteArray, at: Int, packetOffset: Long): VerifiedSlice? {
            if (bytes[at + 1].toInt() and 0x80 != 0) {
                // transport_error_indicator
                if (pidOf(bytes, at) == videoPid) {
                    candidate = null
                    lastVideoContinuity = -1
                }
                return null
            }
            val start = bytes[at + 1].toInt() and 0x40 != 0
            val pid = pidOf(bytes, at)
            val adaptation = (bytes[at + 3].toInt() ushr 4) and 0x3
            val continuity = bytes[at + 3].toInt() and 0x0F
            var payload = at + 4
            if (adaptation and 0x2 != 0) {
                val length = bytes[payload].toInt() and 0xFF
                payload += 1 + length
            }
            val hasPayload = adaptation and 0x1 != 0 && payload < at + PACKET

            if (pid == 0 && start && hasPayload) {
                val parsedPmt = parsePatPmtPid(bytes, payload, at + PACKET)
                if (parsedPmt > 0) {
                    if (parsedPmt != pmtPid) {
                        pmtPid = parsedPmt
                        videoPid = -1
                        videoCodec = null
                        candidate = null
                        lastVideoContinuity = -1
                    }
                    lastPatOffset = packetOffset
                    sawPmtSinceLastPat = false
                }
                return null
            }

            if (pid == pmtPid && pmtPid > 0 && start && hasPayload) {
                parsePmtVideo(bytes, payload, at + PACKET)?.let { (vPid, codec) ->
                    if (vPid != videoPid || codec != videoCodec) {
                        videoPid = vPid
                        videoCodec = codec
                        candidate = null
                        lastVideoContinuity = -1
                    }
                    if (lastPatOffset >= 0 && packetOffset - lastPatOffset <= MAX_VERIFIED_PAT_LEAD_BYTES) {
                        sawPmtSinceLastPat = true
                    }
                }
                return null
            }

            val codec = videoCodec
            if (pid != videoPid || videoPid < 0 || codec == null) return null

            if (hasPayload) {
                if (lastVideoContinuity == continuity && !start) {
                    // MPEG-TS 规范允许最多一次完全相同的重复包，直接跳过
                    return null
                }
                val gap = lastVideoContinuity >= 0 && continuity != ((lastVideoContinuity + 1) and 0x0F)
                lastVideoContinuity = continuity
                if (gap) {
                    candidate = null
                }
            } else {
                return null
            }

            if (start) {
                candidate?.let { prev ->
                    candidate = null
                    if (isSelfContainedKeyframe(prev.buffer, prev.size, prev.codec, completePes = true)) {
                        return VerifiedSlice(prev.sliceStartOffset, prev.ptsMs)
                    }
                }
                val hasRecentPsi = lastPatOffset >= 0 && sawPmtSinceLastPat &&
                    (packetOffset - lastPatOffset) <= MAX_VERIFIED_PAT_LEAD_BYTES
                if (!hasRecentPsi) return null

                val pes = parseVideoPesHeader(bytes, payload, at + PACKET) ?: return null
                val next = Candidate(lastPatOffset, pes.first, codec)
                next.append(bytes, pes.second, (at + PACKET) - pes.second)
                if (isSelfContainedKeyframe(next.buffer, next.size, codec, completePes = false)) {
                    return VerifiedSlice(next.sliceStartOffset, next.ptsMs)
                }
                candidate = next
            } else {
                val active = candidate ?: return null
                active.append(bytes, payload, (at + PACKET) - payload)
                if (active.overflowed) {
                    candidate = null
                    return null
                }
                if (isSelfContainedKeyframe(active.buffer, active.size, active.codec, completePes = false)) {
                    candidate = null
                    return VerifiedSlice(active.sliceStartOffset, active.ptsMs)
                }
            }
            return null
        }

        private companion object {
            const val MAX_VERIFIED_PAT_LEAD_BYTES = 128L * PACKET
            const val MAX_CANDIDATE_PES_BYTES = 256 * 1024
            const val STREAM_H264 = 0x1B
            const val STREAM_HEVC = 0x24
            const val MIN_PARTIAL_SLICE_NAL_BYTES = 16

            fun pidOf(bytes: ByteArray, at: Int): Int =
                ((bytes[at + 1].toInt() and 0x1F) shl 8) or (bytes[at + 2].toInt() and 0xFF)

            fun parsePatPmtPid(bytes: ByteArray, payload: Int, packetEnd: Int): Int {
                val pointer = bytes[payload].toInt() and 0xFF
                val section = payload + 1 + pointer
                if (section + 12 > packetEnd) return -1
                if ((bytes[section].toInt() and 0xFF) != 0x00) return -1
                val sectionLength = ((bytes[section + 1].toInt() and 0x0F) shl 8) or (bytes[section + 2].toInt() and 0xFF)
                val end = minOf(packetEnd, section + 3 + sectionLength - 4)
                var at = section + 8
                while (at + 4 <= end) {
                    val program = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
                    if (program != 0) {
                        return ((bytes[at + 2].toInt() and 0x1F) shl 8) or (bytes[at + 3].toInt() and 0xFF)
                    }
                    at += 4
                }
                return -1
            }

            fun parsePmtVideo(bytes: ByteArray, payload: Int, packetEnd: Int): Pair<Int, Codec>? {
                val pointer = bytes[payload].toInt() and 0xFF
                val section = payload + 1 + pointer
                if (section + 16 > packetEnd) return null
                if ((bytes[section].toInt() and 0xFF) != 0x02) return null
                val sectionLength = ((bytes[section + 1].toInt() and 0x0F) shl 8) or (bytes[section + 2].toInt() and 0xFF)
                val end = minOf(packetEnd, section + 3 + sectionLength - 4)
                val programInfoLen = ((bytes[section + 10].toInt() and 0x0F) shl 8) or (bytes[section + 11].toInt() and 0xFF)
                var at = section + 12 + programInfoLen
                while (at + 5 <= end) {
                    val streamType = bytes[at].toInt() and 0xFF
                    val pid = ((bytes[at + 1].toInt() and 0x1F) shl 8) or (bytes[at + 2].toInt() and 0xFF)
                    val esInfoLen = ((bytes[at + 3].toInt() and 0x0F) shl 8) or (bytes[at + 4].toInt() and 0xFF)
                    when (streamType) {
                        STREAM_HEVC -> return pid to Codec.HEVC
                        STREAM_H264 -> return pid to Codec.H264
                    }
                    at += 5 + esInfoLen
                }
                return null
            }

            /** 返回 (ptsMs, elementaryPayloadStart)。 */
            fun parseVideoPesHeader(bytes: ByteArray, payload: Int, packetEnd: Int): Pair<Long, Int>? {
                if (payload + 14 > packetEnd) return null
                val isPes = bytes[payload] == 0.toByte() && bytes[payload + 1] == 0.toByte() && bytes[payload + 2] == 1.toByte()
                val streamId = bytes[payload + 3].toInt() and 0xFF
                if (!isPes || streamId !in 0xE0..0xEF) return null
                val hasPts = bytes[payload + 7].toInt() and 0x80 != 0
                if (!hasPts) return null
                val headerDataLen = bytes[payload + 8].toInt() and 0xFF
                val elementaryStart = payload + 9 + headerDataLen
                if (elementaryStart > packetEnd) return null
                val p = payload + 9
                val ticks = ((bytes[p].toLong() and 0x0E) shl 29) or
                    ((bytes[p + 1].toLong() and 0xFF) shl 22) or
                    ((bytes[p + 2].toLong() and 0xFE) shl 14) or
                    ((bytes[p + 3].toLong() and 0xFF) shl 7) or
                    ((bytes[p + 4].toLong() and 0xFE) ushr 1)
                return (ticks / 90L) to elementaryStart
            }

            fun isSelfContainedKeyframe(buffer: ByteArray, length: Int, codec: Codec, completePes: Boolean): Boolean {
                val starts = ArrayList<Int>(8)
                var i = 0
                while (i + 3 <= length) {
                    if (buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() && buffer[i + 2] == 1.toByte()) {
                        starts += i + 3
                        i += 3
                    } else {
                        i++
                    }
                }
                if (starts.isEmpty()) return false
                return when (codec) {
                    Codec.HEVC -> verifyHevcNals(buffer, length, starts, completePes)
                    Codec.H264 -> verifyH264Nals(buffer, length, starts, completePes)
                }
            }

            fun verifyHevcNals(buffer: ByteArray, length: Int, starts: List<Int>, completePes: Boolean): Boolean {
                var hasVps = false
                val validSpsIds = HashSet<Int>(2)
                val validPpsIds = HashSet<Int>(2)
                for (idx in starts.indices) {
                    val start = starts[idx]
                    val isLast = idx == starts.lastIndex
                    var end = if (!isLast) starts[idx + 1] - 3 else length
                    while (end > start && buffer[end - 1] == 0.toByte()) end--
                    if (start + 2 > end) continue
                    val b0 = buffer[start].toInt() and 0xFF
                    val b1 = buffer[start + 1].toInt() and 0xFF
                    if ((b0 and 0x80) != 0 || (b1 and 0x07) == 0) continue
                    val nalType = (b0 ushr 1) and 0x3F
                    when (nalType) {
                        32 -> { // VPS: 必须已有后续起始码或 PES 已完结，确认整条 VPS 未被截断
                            if ((!isLast || completePes) && end - start >= 6) hasVps = true
                        }
                        33 -> { // SPS: 完整 NAL 且解析出有效正宽高
                            if (!isLast || completePes) {
                                parseHevcSpsIdAndDimensions(buffer, start, end)?.let { validSpsIds += it }
                            }
                        }
                        34 -> { // PPS: 完整 NAL 且所引用的 sps_id 已在前面出现并校验通过
                            if (!isLast || completePes) {
                                parseHevcPpsIds(buffer, start, end)?.let { (ppsId, spsId) ->
                                    if (spsId in validSpsIds) validPpsIds += ppsId
                                }
                            }
                        }
                        in 16..21 -> { // IRAP (BLA / IDR / CRA)
                            if (!hasVps || validSpsIds.isEmpty() || validPpsIds.isEmpty()) continue
                            if (isLast && !completePes && (end - start) < MIN_PARTIAL_SLICE_NAL_BYTES) continue
                            val slicePpsId = parseHevcIrapSlicePpsId(buffer, start, minOf(end, start + 32), nalType)
                            if (slicePpsId != null && slicePpsId in validPpsIds) return true
                        }
                    }
                }
                return false
            }

            fun verifyH264Nals(buffer: ByteArray, length: Int, starts: List<Int>, completePes: Boolean): Boolean {
                val validSpsIds = HashSet<Int>(2)
                val validPpsIds = HashSet<Int>(2)
                for (idx in starts.indices) {
                    val start = starts[idx]
                    val isLast = idx == starts.lastIndex
                    var end = if (!isLast) starts[idx + 1] - 3 else length
                    while (end > start && buffer[end - 1] == 0.toByte()) end--
                    if (start >= end) continue
                    val b0 = buffer[start].toInt() and 0xFF
                    if ((b0 and 0x80) != 0) continue
                    when (b0 and 0x1F) {
                        7 -> { // SPS
                            if (!isLast || completePes) {
                                parseH264SpsIdAndValidDimensions(buffer, start, end)?.let { validSpsIds += it }
                            }
                        }
                        8 -> { // PPS
                            if (!isLast || completePes) {
                                parseH264PpsIds(buffer, start, end)?.let { (ppsId, spsId) ->
                                    if (spsId in validSpsIds) validPpsIds += ppsId
                                }
                            }
                        }
                        5 -> { // IDR
                            if (validSpsIds.isEmpty() || validPpsIds.isEmpty()) continue
                            if (isLast && !completePes && (end - start) < MIN_PARTIAL_SLICE_NAL_BYTES) continue
                            val slicePpsId = parseH264IdrSlicePpsId(buffer, start, minOf(end, start + 32))
                            if (slicePpsId != null && slicePpsId in validPpsIds) return true
                        }
                    }
                }
                return false
            }

            fun parseHevcSpsIdAndDimensions(buffer: ByteArray, start: Int, end: Int): Int? = runCatching {
                val rbsp = unescapeRbsp(buffer, start + 2, end)
                val bits = RbspBitReader(rbsp)
                bits.skip(4) // sps_video_parameter_set_id
                val maxSubLayers = bits.read(3)
                if (maxSubLayers > 6) return null
                bits.skip(1) // sps_temporal_id_nesting_flag
                bits.skip(88 + 8) // general profile_tier_level
                val profilePresent = BooleanArray(maxSubLayers)
                val levelPresent = BooleanArray(maxSubLayers)
                for (i in 0 until maxSubLayers) {
                    profilePresent[i] = bits.read(1) == 1
                    levelPresent[i] = bits.read(1) == 1
                }
                if (maxSubLayers > 0) {
                    for (i in maxSubLayers until 8) bits.skip(2)
                }
                for (i in 0 until maxSubLayers) {
                    if (profilePresent[i]) bits.skip(88)
                    if (levelPresent[i]) bits.skip(8)
                }
                val spsId = bits.ue()
                if (spsId !in 0..15) return null
                val chroma = bits.ue()
                if (chroma !in 0..3) return null
                if (chroma == 3) bits.skip(1)
                var width = bits.ue()
                var height = bits.ue()
                if (width <= 0 || height <= 0) return null
                if (bits.read(1) == 1) {
                    val subWidth = if (chroma == 1 || chroma == 2) 2 else 1
                    val subHeight = if (chroma == 1) 2 else 1
                    width -= subWidth * (bits.ue() + bits.ue())
                    height -= subHeight * (bits.ue() + bits.ue())
                }
                if (width > 0 && height > 0) spsId else null
            }.getOrNull()

            fun parseHevcPpsIds(buffer: ByteArray, start: Int, end: Int): Pair<Int, Int>? = runCatching {
                val rbsp = unescapeRbsp(buffer, start + 2, end)
                val bits = RbspBitReader(rbsp)
                val ppsId = bits.ue()
                val spsId = bits.ue()
                if (ppsId in 0..63 && spsId in 0..15) ppsId to spsId else null
            }.getOrNull()

            fun parseHevcIrapSlicePpsId(buffer: ByteArray, start: Int, end: Int, nalType: Int): Int? = runCatching {
                val rbsp = unescapeRbsp(buffer, start + 2, end)
                val bits = RbspBitReader(rbsp)
                val firstSliceInPic = bits.read(1) == 1
                if (!firstSliceInPic) return null
                if (nalType in 16..23) bits.skip(1) // no_output_of_prior_pics_flag
                val ppsId = bits.ue()
                ppsId.takeIf { it in 0..63 }
            }.getOrNull()

            fun parseH264SpsIdAndValidDimensions(buffer: ByteArray, start: Int, end: Int): Int? = runCatching {
                val rbsp = unescapeRbsp(buffer, start + 1, end)
                val bits = RbspBitReader(rbsp)
                val profileIdc = bits.read(8)
                bits.skip(8) // constraint flags
                bits.skip(8) // level_idc
                val spsId = bits.ue()
                if (spsId !in 0..31) return null
                if (profileIdc in H264_HIGH_PROFILES) {
                    val chroma = bits.ue()
                    if (chroma == 3) bits.skip(1)
                    bits.ue() // bit_depth_luma_minus8
                    bits.ue() // bit_depth_chroma_minus8
                    bits.skip(1) // qpprime_y_zero_transform_bypass_flag
                    if (bits.read(1) == 1) { // seq_scaling_matrix_present_flag
                        val listCount = if (chroma != 3) 8 else 12
                        for (idx in 0 until listCount) {
                            if (bits.read(1) == 1) skipH264ScalingList(bits, if (idx < 6) 16 else 64)
                        }
                    }
                }
                bits.ue() // log2_max_frame_num_minus4
                when (val pocType = bits.ue()) {
                    0 -> bits.ue()
                    1 -> {
                        bits.skip(1)
                        bits.se()
                        bits.se()
                        val cycle = bits.ue()
                        repeat(cycle) { bits.se() }
                    }
                    2 -> Unit
                    else -> return null
                }
                bits.ue() // max_num_ref_frames
                bits.skip(1) // gaps_in_frame_num_value_allowed_flag
                val widthMbs = bits.ue() + 1
                val heightMapUnits = bits.ue() + 1
                if (widthMbs > 0 && heightMapUnits > 0) spsId else null
            }.getOrNull()

            fun skipH264ScalingList(bits: RbspBitReader, size: Int) {
                var lastScale = 8
                var nextScale = 8
                for (j in 0 until size) {
                    if (nextScale != 0) {
                        val delta = bits.se()
                        nextScale = (lastScale + delta + 256) % 256
                    }
                    lastScale = if (nextScale == 0) lastScale else nextScale
                }
            }

            fun parseH264PpsIds(buffer: ByteArray, start: Int, end: Int): Pair<Int, Int>? = runCatching {
                val rbsp = unescapeRbsp(buffer, start + 1, end)
                val bits = RbspBitReader(rbsp)
                val ppsId = bits.ue()
                val spsId = bits.ue()
                if (ppsId in 0..255 && spsId in 0..31) ppsId to spsId else null
            }.getOrNull()

            fun parseH264IdrSlicePpsId(buffer: ByteArray, start: Int, end: Int): Int? = runCatching {
                val rbsp = unescapeRbsp(buffer, start + 1, end)
                val bits = RbspBitReader(rbsp)
                val firstMb = bits.ue()
                if (firstMb != 0) return null
                val sliceType = bits.ue()
                if (sliceType !in 0..9) return null
                val ppsId = bits.ue()
                ppsId.takeIf { it in 0..255 }
            }.getOrNull()

            fun unescapeRbsp(buffer: ByteArray, from: Int, to: Int): ByteArray {
                if (from >= to) return ByteArray(0)
                val out = ByteArray(to - from)
                var outLen = 0
                var zeros = 0
                for (idx in from until to) {
                    val b = buffer[idx]
                    if (zeros >= 2 && b == 3.toByte()) {
                        zeros = 0
                        continue
                    }
                    out[outLen++] = b
                    zeros = if (b == 0.toByte()) zeros + 1 else 0
                }
                return if (outLen == out.size) out else out.copyOf(outLen)
            }

            val H264_HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
        }

        private class RbspBitReader(private val bytes: ByteArray) {
            private var position = 0

            fun read(count: Int): Int {
                var value = 0
                repeat(count) {
                    if (position >= bytes.size * 8) error("RBSP EOF")
                    val bit = (bytes[position / 8].toInt() ushr (7 - (position % 8))) and 1
                    value = (value shl 1) or bit
                    position++
                }
                return value
            }

            fun skip(count: Int) {
                if (position + count > bytes.size * 8) error("RBSP EOF")
                position += count
            }

            fun ue(): Int {
                var zeros = 0
                while (read(1) == 0) {
                    zeros++
                    if (zeros > 30) error("Invalid exp-golomb")
                }
                return if (zeros == 0) 0 else ((1 shl zeros) - 1 + read(zeros))
            }

            fun se(): Int {
                val code = ue()
                val magnitude = (code + 1) ushr 1
                return if ((code and 1) == 1) magnitude else -magnitude
            }
        }
    }

    /** [later] 比 [earlier] 晚多少毫秒。PTS 是 33 位计数，一条流里可能回绕一次。 */
    fun elapsedMs(earlier: Long, later: Long): Long {
        val diff = later - earlier
        return if (diff < 0) diff + WRAP_MS else diff
    }

    // 切片起点按 188 对齐，但不假定：连着两个包头都是 0x47 才算找到包边界
    private fun syncOffset(bytes: ByteArray): Int? =
        (0 until minOf(PACKET, bytes.size)).firstOrNull { start ->
            bytes[start] == SYNC && (start + PACKET >= bytes.size || bytes[start + PACKET] == SYNC)
        }

    private const val PACKET = 188
    private const val SYNC = 0x47.toByte()
    private const val WRAP_MS = (1L shl 33) / 90
}
