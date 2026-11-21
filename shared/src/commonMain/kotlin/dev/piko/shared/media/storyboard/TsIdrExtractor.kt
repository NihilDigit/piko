package dev.piko.shared.media.storyboard

/**
 * 从 PikPak `<= 480P` MPEG-TS 转码流首块提取出的流参数头（PAT/PMT -> 视频 PID + HEVC VPS/SPS/PPS + 片头 90kHz 基准时间戳）。
 */
data class TsStoryboardHeader(
    val videoPid: Int,
    /** Annex B 4 字节起始码拼接的 VPS + SPS + PPS，即 HEVC 解码器所需的 csd-0。 */
    val csdAnnexB: ByteArray,
    val originTicks: Long,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TsStoryboardHeader) return false
        return videoPid == other.videoPid &&
            originTicks == other.originTicks &&
            width == other.width &&
            height == other.height &&
            csdAnnexB.contentEquals(other.csdAnnexB)
    }

    override fun hashCode(): Int {
        var result = videoPid
        result = 31 * result + csdAnnexB.contentHashCode()
        result = 31 * result + originTicks.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}

/**
 * 从单个 `48–128 KiB` MPEG-TS 分块中直接抽出的单帧 HEVC IDR 访问单元与真实片内 `PTS`（毫秒）。
 */
data class ExtractedTsIdr(
    val ptsMs: Long,
    val accessUnitAnnexB: ByteArray,
    val hasInlineParameterSets: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedTsIdr) return false
        return ptsMs == other.ptsMs &&
            hasInlineParameterSets == other.hasInlineParameterSets &&
            accessUnitAnnexB.contentEquals(other.accessUnitAnnexB)
    }

    override fun hashCode(): Int {
        var result = ptsMs.hashCode()
        result = 31 * result + accessUnitAnnexB.contentHashCode()
        result = 31 * result + hasInlineParameterSets.hashCode()
        return result
    }
}

/**
 * 纯 `commonMain` 轻量级 MPEG-TS HEVC 单帧 IDR 提取器，复用 `TsDemuxer` 的 0x47 同步、PAT/PMT、
 * 33 位 90 kHz PTS 回绕展开、HEVC VPS/SPS/PPS 与 Annex B 访问单元规范化逻辑：
 * - 不走 `avformat_open_input` / `av_seek_frame`，单次 48–64 KiB Range 块即可在微秒级抽出带真实 `PTS` 的 IDR；
 * - 遇到未带完整下个 PES 起始包的尾部关键帧时，也支持用 `drain()` 安全收尾（只要 continuity counter 未断）。
 */
object TsIdrExtractor {
    private const val PACKET = 188
    private const val SYNC = 0x47.toByte()
    private const val STREAM_HEVC = 0x24
    private const val HEVC_VPS = 32
    private const val HEVC_SPS = 33
    private const val HEVC_PPS = 34
    private val HEVC_IRAP = 16..21
    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
    private const val WRAP = 1L shl 33

    /**
     * 解析文件头部（建议 64–128 KiB）取得 PAT/PMT、HEVC 视频 PID、VPS/SPS/PPS、首帧 90 kHz 基准时间与宽高。
     */
    fun parseHeader(bytes: ByteArray, fallbackWidth: Int = 0, fallbackHeight: Int = 0): TsStoryboardHeader? {
        val syncStart = findSyncOffset(bytes) ?: return null
        var pmtPid = -1
        var videoPid = -1
        var firstVideoTicks: Long? = null
        var csd: ByteArray? = null
        var width = fallbackWidth
        var height = fallbackHeight
        val assembler = SinglePidPesAssembler()

        var at = syncStart
        while (at + PACKET <= bytes.size) {
            if (bytes[at] != SYNC) {
                at = findSyncOffset(bytes, at + 1) ?: break
                continue
            }
            val pkt = PacketView(bytes, at)
            val pid = pkt.pid
            if (pid == 0 && pkt.start) {
                val parsed = parsePat(pkt)
                if (parsed >= 0) pmtPid = parsed
            } else if (pid == pmtPid && pkt.start && videoPid < 0) {
                val parsedVideo = parsePmtVideoPid(pkt)
                if (parsedVideo >= 0) videoPid = parsedVideo
            } else if (videoPid >= 0 && pid == videoPid) {
                if (firstVideoTicks == null && pkt.start) {
                    pkt.pesPtsTicks()?.let { firstVideoTicks = it }
                }
                val pes = assembler.feed(pkt)
                if (pes != null) {
                    if (firstVideoTicks == null) firstVideoTicks = pes.ptsTicks
                    val extracted = extractParameterSets(pes.payload)
                    if (extracted != null) {
                        csd = extracted
                        break
                    }
                }
            }
            at += PACKET
        }

        if (csd == null && videoPid >= 0) {
            assembler.drain()?.let { pes ->
                if (firstVideoTicks == null) firstVideoTicks = pes.ptsTicks
                csd = extractParameterSets(pes.payload)
            }
        }

        val finalCsd = csd ?: return null
        if (videoPid < 0) return null
        return TsStoryboardHeader(
            videoPid = videoPid,
            csdAnnexB = finalCsd,
            originTicks = firstVideoTicks ?: 0L,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
        )
    }

    /**
     * 从任意中段 48–128 KiB TS 字节块中提取第一个完整 HEVC IDR/CRA 帧及其真实片内 `PTS`（毫秒）。
     *
     * 默认 [allowTailDrain] 为 `false`：若关键帧 PES 在分块末尾尚未见到下一个视频 PES 起始包（且未显式声明固定长度），
     * 绝不把跨 64 KiB 边界被截断的半帧（尤其是 NALU 21 `CRA_NUT`）送进 FFmpeg 触发 `Skipping invalid undecodable NALU`，
     * 而是返回 `null` 交由上层补拉续块拼接后再完整解码；仅当分块已触达流末尾（EOF）时传入 `allowTailDrain = true`。
     */
    fun extractFirstIdr(
        bytes: ByteArray,
        header: TsStoryboardHeader,
        durationMillis: Long = Long.MAX_VALUE,
        allowTailDrain: Boolean = false,
    ): ExtractedTsIdr? {
        val syncStart = findSyncOffset(bytes) ?: return null
        val targetPid = header.videoPid
        val assembler = SinglePidPesAssembler()

        var at = syncStart
        while (at + PACKET <= bytes.size) {
            if (bytes[at] != SYNC) {
                at = findSyncOffset(bytes, at + 1) ?: break
                continue
            }
            val pkt = PacketView(bytes, at)
            if (pkt.pid == targetPid) {
                val finished = assembler.feed(pkt)
                if (finished != null) {
                    val idr = maybeBuildIdr(finished, header.originTicks, durationMillis)
                    if (idr != null) return idr
                }
            }
            at += PACKET
        }

        // 仅在块尾已触达 EOF（allowTailDrain=true）或 PES 显式声明了完整固定长度时放行末尾收尾：
        // 既不丢弃 EOF 或定长 PES 中的紧凑/单包关键帧（< 1 KiB），也严禁将跨 64 KiB 边界截断的无界半帧送入解码器。
        return assembler.drain(requireDeclaredLength = !allowTailDrain)?.let { pes ->
            maybeBuildIdr(pes, header.originTicks, durationMillis)
        }
    }

    /**
     * 合并参数集（VPS/SPS/PPS）与 IDR 访问单元：若 IDR 已内联全部 3 个参数集则直接返回，否则在前面拼上 [csdAnnexB]。
     */
    fun combineCsdAndIdr(csdAnnexB: ByteArray, idr: ExtractedTsIdr): ByteArray {
        if (idr.hasInlineParameterSets || csdAnnexB.isEmpty()) return idr.accessUnitAnnexB
        val out = ByteArray(csdAnnexB.size + idr.accessUnitAnnexB.size)
        csdAnnexB.copyInto(out, 0)
        idr.accessUnitAnnexB.copyInto(out, csdAnnexB.size)
        return out
    }

    private fun maybeBuildIdr(pes: PesPacket, originTicks: Long, durationMillis: Long): ExtractedTsIdr? {
        val normalized = normalizeAccessUnit(pes.payload)
        val nals = nalUnits(normalized)
        if (nals.none { it.type in HEVC_IRAP && (it.end - it.start) > 2 }) return null
        val hasVps = nals.any { it.type == HEVC_VPS }
        val hasSps = nals.any { it.type == HEVC_SPS }
        val hasPps = nals.any { it.type == HEVC_PPS }
        val deltaTicks = wrapDistance(originTicks, pes.ptsTicks)
        val ptsUs = ticksToUs(deltaTicks)
        val ptsMs = (ptsUs / 1_000L).coerceIn(0L, durationMillis.coerceAtLeast(0L))
        return ExtractedTsIdr(
            ptsMs = ptsMs,
            accessUnitAnnexB = normalized,
            hasInlineParameterSets = hasVps && hasSps && hasPps,
        )
    }

    private fun extractParameterSets(accessUnit: ByteArray): ByteArray? {
        val units = nalUnits(accessUnit)
        val sets = units.filter { it.type in HEVC_VPS..HEVC_PPS }.distinctBy { it.type }.sortedBy { it.type }
        if (sets.size < 3) return null
        val totalLen = sets.sumOf { START_CODE_4.size + (it.end - it.start) }
        val out = ByteArray(totalLen)
        var pos = 0
        for (nal in sets) {
            START_CODE_4.copyInto(out, pos)
            pos += START_CODE_4.size
            val len = nal.end - nal.start
            accessUnit.copyInto(out, pos, nal.start, nal.end)
            pos += len
        }
        return out
    }

    private fun normalizeAccessUnit(payload: ByteArray): ByteArray {
        var end = payload.size
        while (end > 0 && payload[end - 1] == 0.toByte()) end--
        val shortStartCode = end >= 3 && payload[0] == 0.toByte() && payload[1] == 0.toByte() && payload[2] == 1.toByte()
        return when {
            shortStartCode -> ByteArray(end + 1).also { payload.copyInto(it, destinationOffset = 1, endIndex = end) }
            end == payload.size -> payload
            else -> payload.copyOf(end)
        }
    }

    internal fun findSyncOffset(bytes: ByteArray, from: Int = 0): Int? {
        if (bytes.size - from < PACKET * 2) return null
        val limit = minOf(bytes.size - PACKET * 2, from + PACKET * 4)
        for (i in from..limit) {
            if (bytes[i] == SYNC && bytes[i + PACKET] == SYNC) {
                if (i + PACKET * 2 >= bytes.size || bytes[i + PACKET * 2] == SYNC) {
                    return i
                }
            }
        }
        return null
    }

    private fun parsePat(packet: PacketView): Int {
        if (!packet.hasPayload) return -1
        val pointerField = packet.byte(packet.payloadStart)
        val section = packet.payloadStart + 1 + pointerField
        if (section + 8 > PACKET) return -1
        val sectionLen = ((packet.byte(section + 1) and 0x0F) shl 8) or packet.byte(section + 2)
        val end = minOf(PACKET, section + 3 + sectionLen - 4)
        var at = section + 8
        while (at + 4 <= end) {
            val program = (packet.byte(at) shl 8) or packet.byte(at + 1)
            if (program != 0) return ((packet.byte(at + 2) and 0x1F) shl 8) or packet.byte(at + 3)
            at += 4
        }
        return -1
    }

    private fun parsePmtVideoPid(packet: PacketView): Int {
        if (!packet.hasPayload) return -1
        val section = packet.payloadStart + 1 + packet.byte(packet.payloadStart)
        if (section + 12 > PACKET || packet.byte(section) != 0x02) return -1
        val sectionLen = ((packet.byte(section + 1) and 0x0F) shl 8) or packet.byte(section + 2)
        val end = minOf(PACKET, section + 3 + sectionLen - 4)
        val programInfo = ((packet.byte(section + 10) and 0x0F) shl 8) or packet.byte(section + 11)
        var at = section + 12 + programInfo
        while (at + 5 <= end) {
            val type = packet.byte(at)
            val pid = ((packet.byte(at + 1) and 0x1F) shl 8) or packet.byte(at + 2)
            if (type == STREAM_HEVC) return pid
            val esInfoLen = ((packet.byte(at + 3) and 0x0F) shl 8) or packet.byte(at + 4)
            at += 5 + esInfoLen
        }
        return -1
    }

    private class PacketView(val bytes: ByteArray, val at: Int) {
        fun byte(offset: Int): Int = bytes[at + offset].toInt() and 0xFF
        val pid: Int get() = ((byte(1) and 0x1F) shl 8) or byte(2)
        val start: Boolean get() = byte(1) and 0x40 != 0
        val continuity: Int get() = byte(3) and 0x0F
        private val adaptation: Int get() = (byte(3) ushr 4) and 0x3
        val payloadStart: Int get() = if (adaptation and 0x2 != 0) 5 + byte(4) else 4
        val hasPayload: Boolean get() = adaptation and 0x1 != 0 && payloadStart < PACKET

        fun payload(): ByteArray = bytes.copyOfRange(at + payloadStart, at + PACKET)

        fun pesPtsTicks(): Long? {
            val p = payloadStart
            if (!hasPayload || p + 14 > PACKET || byte(p) != 0 || byte(p + 1) != 0 || byte(p + 2) != 1) return null
            if (byte(p + 7) and 0x80 == 0) return null
            return timestamp(bytes, at + p + 9)
        }
    }

    private class PesPacket(val ptsTicks: Long, val payload: ByteArray)

    private class SinglePidPesAssembler {
        private var parts: MutableList<ByteArray>? = null
        private var broken = false
        private var lastContinuity = -1

        fun feed(packet: PacketView): PesPacket? {
            if (!packet.hasPayload) return null
            val cc = packet.continuity
            if (lastContinuity == cc) return null
            val gap = lastContinuity >= 0 && cc != ((lastContinuity + 1) and 0x0F)
            lastContinuity = cc
            if (gap) broken = true
            val finished = if (packet.start) buildCurrent() else null
            if (packet.start) {
                parts = mutableListOf(packet.payload())
                broken = false
            } else {
                parts?.add(packet.payload())
            }
            return finished
        }

        fun drain(requireDeclaredLength: Boolean = false): PesPacket? =
            buildCurrent(requireDeclaredLength = requireDeclaredLength).also {
                parts = null
                broken = false
            }

        private fun buildCurrent(requireDeclaredLength: Boolean = false): PesPacket? {
            val list = parts ?: return null
            if (broken) return null
            val total = list.sumOf { it.size }
            if (total < 14) return null
            val bytes = ByteArray(total)
            var pos = 0
            for (part in list) {
                part.copyInto(bytes, pos)
                pos += part.size
            }
            if (bytes[0] != 0.toByte() || bytes[1] != 0.toByte() || bytes[2] != 1.toByte()) return null
            val flags = (bytes[7].toInt() ushr 6) and 0x3
            if (flags and 0x2 == 0) return null
            val headerEnd = 9 + (bytes[8].toInt() and 0xFF)
            if (headerEnd < 14 || headerEnd >= bytes.size) return null
            val declared = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            if (requireDeclaredLength && declared == 0) return null
            if (declared != 0 && bytes.size < 6 + declared) return null
            val end = if (declared == 0) bytes.size else minOf(bytes.size, 6 + declared)
            if (end <= headerEnd) return null
            val pts = timestamp(bytes, 9)
            return PesPacket(pts, bytes.copyOfRange(headerEnd, end))
        }
    }

    private class Nal(val type: Int, val start: Int, val end: Int)

    private fun nalUnits(bytes: ByteArray): List<Nal> {
        val starts = ArrayList<Int>(8)
        var i = 0
        while (i + 3 <= bytes.size) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexedNotNull { index, start ->
            var end = if (index + 1 < starts.size) starts[index + 1] - 3 else bytes.size
            while (end > start && bytes[end - 1] == 0.toByte()) end--
            if (end - start < 2) null else Nal((bytes[start].toInt() ushr 1) and 0x3F, start, end)
        }
    }

    private fun timestamp(bytes: ByteArray, at: Int): Long =
        ((bytes[at].toLong() and 0x0E) shl 29) or
            ((bytes[at + 1].toLong() and 0xFF) shl 22) or
            ((bytes[at + 2].toLong() and 0xFE) shl 14) or
            ((bytes[at + 3].toLong() and 0xFF) shl 7) or
            ((bytes[at + 4].toLong() and 0xFE) ushr 1)

    private fun ticksToUs(ticks: Long): Long = ticks * 100L / 9L

    private fun wrapDistance(from: Long, to: Long): Long {
        val diff = (to - from).mod(WRAP)
        return if (diff >= WRAP / 2) diff - WRAP else diff
    }
}
