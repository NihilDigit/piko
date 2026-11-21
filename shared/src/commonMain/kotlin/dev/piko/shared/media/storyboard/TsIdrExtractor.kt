package dev.piko.shared.media.storyboard

/**
 * PikPak MPEG-TS 转码流支持的视频编码类型（`0x24` HEVC 与 `0x1B` H.264/AVC）。
 */
enum class TsVideoCodec {
    HEVC,
    AVC,
}

/**
 * 从 PikPak `<= 480P` MPEG-TS 转码流首块提取出的流参数头（PAT/PMT -> 视频 PID + 编码 + 参数集 + 片头 90kHz 基准时间戳）。
 */
data class TsStoryboardHeader(
    val videoPid: Int,
    /** Annex B 4 字节起始码拼接的参数集（HEVC 为 VPS+SPS+PPS，AVC 为 SPS+PPS）。 */
    val csdAnnexB: ByteArray,
    val originTicks: Long,
    val width: Int,
    val height: Int,
    val codec: TsVideoCodec = TsVideoCodec.HEVC,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TsStoryboardHeader) return false
        return videoPid == other.videoPid &&
            originTicks == other.originTicks &&
            width == other.width &&
            height == other.height &&
            codec == other.codec &&
            csdAnnexB.contentEquals(other.csdAnnexB)
    }

    override fun hashCode(): Int {
        var result = videoPid
        result = 31 * result + csdAnnexB.contentHashCode()
        result = 31 * result + originTicks.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + codec.hashCode()
        return result
    }
}

/**
 * 从单个 MPEG-TS 分块中直接抽出的单帧 IDR/IRAP 访问单元与真实片内 `PTS`（毫秒）。
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
 * [TsIdrExtractor.extractFirstIdrDetailed] 与流式扫描器收尾的结构化结果，用于指导引擎区分
 * 「已抽到完整 IDR」「IDR 在分块末尾跨界截断」与「分块内尚未遇到 IDR」。
 */
sealed interface TsIdrExtractionResult {
    data class Found(val idr: ExtractedTsIdr) : TsIdrExtractionResult

    /**
     * 分块末尾已开始组装关键帧或参数集 PES，但尚未等到下一个 PES 起始包或未收齐声明长度。
     * [declaredRemainingBytes] 非空时表示 PES 头显式声明了固定长度且已知剩余字节数。
     */
    data class TruncatedAtTail(val declaredRemainingBytes: Int? = null) : TsIdrExtractionResult

    data object NotFound : TsIdrExtractionResult
}

/**
 * 纯 `commonMain` 轻量级 MPEG-TS 单帧 IDR 提取器（支持 HEVC `0x24` 与 H.264/AVC `0x1B`），
 * 复用 `TsDemuxer` 的 0x47 同步、PAT/PMT、33 位 90 kHz PTS 回绕展开、参数集提取与 Annex B 访问单元规范化逻辑：
 * - 不走 `avformat_open_input` / `av_seek_frame`，单次 Range 流式读取即可在微秒级抽出带真实 `PTS` 的 IDR；
 * - 提供增量流式扫描器（[IncrementalHeaderAndIdrScanner] / [IncrementalIdrScanner]），支持边收网络分块边解析，
 *   一旦拼出首个完整 IDR 立即通知上层中止 HTTP 流，零多余尾部流量；
 * - 遇到未带完整下个 PES 起始包的尾部关键帧时，精确识别跨界截断以便上层仅补拉紧凑尾块，EOF 时可用 `allowTailDrain = true` 收尾。
 */
object TsIdrExtractor {
    private const val PACKET = 188
    private const val SYNC = 0x47.toByte()
    private const val STREAM_AVC = 0x1B
    private const val STREAM_HEVC = 0x24
    private const val HEVC_VPS = 32
    private const val HEVC_SPS = 33
    private const val HEVC_PPS = 34
    private val HEVC_IRAP = 16..21
    private const val AVC_IDR = 5
    private const val AVC_SPS = 7
    private const val AVC_PPS = 8
    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)
    private const val WRAP = 1L shl 33

    /**
     * 增量流式流头 + 首帧（槽位 0）合并扫描器：
     * 喂入任意长度（不必 188 字节对齐）的网络分块，在单次偏移 0 HTTP 流中同时提取
     * [header]（PAT/PMT + 参数集 + 片头基准时间戳）与 [firstIdr]（槽位 0 首个完整 IDR）；
     * 一旦两者就绪（或无需首帧时流头就绪），[feed] 立即返回 `true` 通知调用方中止网络流。
     */
    class IncrementalHeaderAndIdrScanner(
        private val fallbackWidth: Int = 0,
        private val fallbackHeight: Int = 0,
        private val durationMillis: Long = Long.MAX_VALUE,
        private val captureFirstIdr: Boolean = true,
    ) {
        private var pmtPid = -1
        private var videoPid = -1
        private var videoCodec: TsVideoCodec = TsVideoCodec.HEVC
        private var firstVideoTicks: Long? = null
        private val assembler = SinglePidPesAssembler()
        private val framer = TsPacketStreamFramer()

        var header: TsStoryboardHeader? = null
            private set
        var firstIdr: ExtractedTsIdr? = null
            private set

        val isDone: Boolean
            get() = header != null && (!captureFirstIdr || firstIdr != null)

        /** 喂入 [chunk] 的 `[0, length)` 字节；返回 `true` 表示已收齐全部目标，可立即中止流。 */
        fun feed(chunk: ByteArray, length: Int = chunk.size): Boolean {
            if (isDone || length <= 0) return isDone
            framer.feed(chunk, length) { bytes, at ->
                processPacket(PacketView(bytes, at))
                isDone
            }
            return isDone
        }

        /** 流结束（或到达预算上限）时调用：在 EOF 或定长 PES 已收齐时收尾。 */
        fun finish(allowTailDrain: Boolean) {
            if (isDone || videoPid < 0) return
            if (header == null) {
                assembler.peekComplete(requireDeclaredLength = !allowTailDrain)?.let { pes ->
                    handleCompletedPes(pes)
                }
                if (header == null && allowTailDrain) {
                    assembler.drain(requireDeclaredLength = false)?.let { pes ->
                        handleCompletedPes(pes)
                    }
                }
            } else if (captureFirstIdr && firstIdr == null) {
                assembler.peekComplete(requireDeclaredLength = !allowTailDrain)?.let { pes ->
                    handleCompletedPes(pes)
                }
            }
        }

        private fun processPacket(pkt: PacketView) {
            val pid = pkt.pid
            if (pid == 0 && pkt.start) {
                val parsed = parsePat(pkt)
                if (parsed >= 0) pmtPid = parsed
            } else if (pid == pmtPid && pkt.start && videoPid < 0) {
                parsePmtVideo(pkt)?.let { stream ->
                    videoPid = stream.pid
                    videoCodec = stream.codec
                }
            } else if (videoPid >= 0 && pid == videoPid) {
                if (firstVideoTicks == null && pkt.start) {
                    pkt.pesPtsTicks()?.let { firstVideoTicks = it }
                }
                val (prevPes, completedCurrent) = assembler.feedWithImmediateComplete(pkt)
                if (prevPes != null) {
                    handleCompletedPes(prevPes)
                }
                if (!isDone && completedCurrent != null) {
                    handleCompletedPes(completedCurrent)
                }
            }
        }

        private fun handleCompletedPes(pes: PesPacket) {
            if (firstVideoTicks == null) firstVideoTicks = pes.ptsTicks
            var resolvedHeader = header
            if (resolvedHeader == null) {
                val csd = extractParameterSets(pes.payload, videoCodec)
                if (csd != null) {
                    resolvedHeader = TsStoryboardHeader(
                        videoPid = videoPid,
                        csdAnnexB = csd,
                        originTicks = firstVideoTicks ?: 0L,
                        width = fallbackWidth.coerceAtLeast(1),
                        height = fallbackHeight.coerceAtLeast(1),
                        codec = videoCodec,
                    )
                    header = resolvedHeader
                }
            }
            if (captureFirstIdr && firstIdr == null && resolvedHeader != null) {
                maybeBuildIdr(pes, resolvedHeader.originTicks, durationMillis, resolvedHeader.codec)?.let { idr ->
                    firstIdr = idr
                }
            }
        }
    }

    /**
     * 增量流式单槽位 IDR 扫描器：
     * 边收网络分块边组装目标视频 PID 的 PES；首个完整 IDR 组装完毕立即返回 [ExtractedTsIdr]，
     * 上层随即中止 HTTP Range 流；同一实例可无缝跨续块继续喂入而无需拷贝拼接字节数组。
     */
    class IncrementalIdrScanner(
        private val header: TsStoryboardHeader,
        private val durationMillis: Long = Long.MAX_VALUE,
    ) {
        private val targetPid = header.videoPid
        private val assembler = SinglePidPesAssembler()
        private val framer = TsPacketStreamFramer()

        var foundIdr: ExtractedTsIdr? = null
            private set

        /** 喂入 [chunk] 的 `[0, length)` 字节；若已提取出首个完整 IDR 则立即返回该帧。 */
        fun feed(chunk: ByteArray, length: Int = chunk.size): ExtractedTsIdr? {
            foundIdr?.let { return it }
            if (length <= 0) return null
            framer.feed(chunk, length) { bytes, at ->
                val pkt = PacketView(bytes, at)
                if (pkt.pid == targetPid) {
                    val (prevPes, completedCurrent) = assembler.feedWithImmediateComplete(pkt)
                    if (prevPes != null) {
                        maybeBuildIdr(prevPes, header.originTicks, durationMillis, header.codec)?.let { idr ->
                            foundIdr = idr
                            return@feed true
                        }
                    }
                    if (completedCurrent != null) {
                        maybeBuildIdr(completedCurrent, header.originTicks, durationMillis, header.codec)?.let { idr ->
                            foundIdr = idr
                            return@feed true
                        }
                    }
                }
                false
            }
            return foundIdr
        }

        /**
         * 当前流段结束时调用：若到达 EOF（`allowTailDrain = true`）或定长 PES 已收齐则收尾产出；
         * 否则报告尾部是否已出现跨界截断的 IDR PES（[TsIdrExtractionResult.TruncatedAtTail]）或 [TsIdrExtractionResult.NotFound]。
         * 本方法不破坏内部状态，若上层继续拉取紧邻续块，可直接继续调用 [feed]。
         */
        fun finish(allowTailDrain: Boolean): TsIdrExtractionResult {
            foundIdr?.let { return TsIdrExtractionResult.Found(it) }
            assembler.peekComplete(requireDeclaredLength = !allowTailDrain)?.let { pes ->
                maybeBuildIdr(pes, header.originTicks, durationMillis, header.codec)?.let { idr ->
                    foundIdr = idr
                    return TsIdrExtractionResult.Found(idr)
                }
            }
            if (!allowTailDrain) {
                assembler.inspectTruncatedIdrTail(header.codec)?.let { tail ->
                    return tail
                }
            }
            return TsIdrExtractionResult.NotFound
        }
    }

    /**
     * 解析文件头部（建议 64–128 KiB）取得 PAT/PMT、视频 PID 与编码（HEVC / AVC）、参数集、首帧 90 kHz 基准时间与宽高。
     */
    fun parseHeader(bytes: ByteArray, fallbackWidth: Int = 0, fallbackHeight: Int = 0): TsStoryboardHeader? {
        val scanner = IncrementalHeaderAndIdrScanner(
            fallbackWidth = fallbackWidth,
            fallbackHeight = fallbackHeight,
            captureFirstIdr = false,
        )
        scanner.feed(bytes, bytes.size)
        if (scanner.header == null) {
            scanner.finish(allowTailDrain = true)
        }
        return scanner.header
    }

    /**
     * 从任意中段 TS 字节块中提取第一个完整 HEVC IDR/CRA 或 AVC IDR 帧及其真实片内 `PTS`（毫秒）。
     *
     * 默认 [allowTailDrain] 为 `false`：若关键帧 PES 在分块末尾尚未见到下一个视频 PES 起始包（且未显式声明固定长度），
     * 绝不把跨边界被截断的半帧（尤其是 HEVC NALU 21 `CRA_NUT`）送进解码器，
     * 而是返回 `null` 交由上层补拉续块后再完整解码；仅当分块已触达流末尾（EOF）时传入 `allowTailDrain = true`。
     */
    fun extractFirstIdr(
        bytes: ByteArray,
        header: TsStoryboardHeader,
        durationMillis: Long = Long.MAX_VALUE,
        allowTailDrain: Boolean = false,
    ): ExtractedTsIdr? =
        (extractFirstIdrDetailed(bytes, header, durationMillis, allowTailDrain) as? TsIdrExtractionResult.Found)?.idr

    /**
     * 与 [extractFirstIdr] 相同的提取流程，但额外区分「块尾已见到跨界截断的 IDR PES」与「整块未遇到 IDR」，
     * 使上层续块拉取可针对跨界尾部仅补拉精确/紧凑尾块，长 GOP 才拉满至扩展上限。
     */
    fun extractFirstIdrDetailed(
        bytes: ByteArray,
        header: TsStoryboardHeader,
        durationMillis: Long = Long.MAX_VALUE,
        allowTailDrain: Boolean = false,
    ): TsIdrExtractionResult {
        val scanner = IncrementalIdrScanner(header = header, durationMillis = durationMillis)
        scanner.feed(bytes, bytes.size)?.let { return TsIdrExtractionResult.Found(it) }
        return scanner.finish(allowTailDrain = allowTailDrain)
    }

    /**
     * 合并参数集（HEVC VPS/SPS/PPS 或 AVC SPS/PPS）与 IDR 访问单元：若 IDR 已内联全部参数集则直接返回，否则在前面拼上 [csdAnnexB]。
     */
    fun combineCsdAndIdr(csdAnnexB: ByteArray, idr: ExtractedTsIdr): ByteArray {
        if (idr.hasInlineParameterSets || csdAnnexB.isEmpty()) return idr.accessUnitAnnexB
        val out = ByteArray(csdAnnexB.size + idr.accessUnitAnnexB.size)
        csdAnnexB.copyInto(out, 0)
        idr.accessUnitAnnexB.copyInto(out, csdAnnexB.size)
        return out
    }

    private fun maybeBuildIdr(
        pes: PesPacket,
        originTicks: Long,
        durationMillis: Long,
        codec: TsVideoCodec,
    ): ExtractedTsIdr? {
        val normalized = normalizeAccessUnit(pes.payload)
        val nals = nalUnits(normalized, codec)
        val isIdr = when (codec) {
            TsVideoCodec.HEVC -> nals.any { it.type in HEVC_IRAP && (it.end - it.start) > 2 }
            TsVideoCodec.AVC -> nals.any { it.type == AVC_IDR && (it.end - it.start) > 1 }
        }
        if (!isIdr) return null
        val hasInline = when (codec) {
            TsVideoCodec.HEVC -> nals.any { it.type == HEVC_VPS } && nals.any { it.type == HEVC_SPS } && nals.any { it.type == HEVC_PPS }
            TsVideoCodec.AVC -> nals.any { it.type == AVC_SPS } && nals.any { it.type == AVC_PPS }
        }
        val deltaTicks = wrapDistance(originTicks, pes.ptsTicks)
        val ptsUs = ticksToUs(deltaTicks)
        val ptsMs = (ptsUs / 1_000L).coerceIn(0L, durationMillis.coerceAtLeast(0L))
        return ExtractedTsIdr(
            ptsMs = ptsMs,
            accessUnitAnnexB = normalized,
            hasInlineParameterSets = hasInline,
        )
    }

    private fun extractParameterSets(accessUnit: ByteArray, codec: TsVideoCodec): ByteArray? {
        val units = nalUnits(accessUnit, codec)
        val sets = when (codec) {
            TsVideoCodec.HEVC -> {
                val filtered = units.filter { it.type in HEVC_VPS..HEVC_PPS }.distinctBy { it.type }.sortedBy { it.type }
                if (filtered.size < 3) return null
                filtered
            }
            TsVideoCodec.AVC -> {
                val filtered = units.filter { it.type == AVC_SPS || it.type == AVC_PPS }.distinctBy { it.type }.sortedBy { it.type }
                if (filtered.size < 2) return null
                filtered
            }
        }
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

    internal fun findSyncOffset(bytes: ByteArray, from: Int = 0, limitEnd: Int = bytes.size): Int? {
        if (limitEnd - from < PACKET * 2) return null
        val limit = minOf(limitEnd - PACKET * 2, from + PACKET * 4)
        for (i in from..limit) {
            if (bytes[i] == SYNC && bytes[i + PACKET] == SYNC) {
                if (i + PACKET * 2 >= limitEnd || bytes[i + PACKET * 2] == SYNC) {
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

    private data class VideoStreamInfo(val pid: Int, val codec: TsVideoCodec)

    private fun parsePmtVideo(packet: PacketView): VideoStreamInfo? {
        if (!packet.hasPayload) return null
        val section = packet.payloadStart + 1 + packet.byte(packet.payloadStart)
        if (section + 12 > PACKET || packet.byte(section) != 0x02) return null
        val sectionLen = ((packet.byte(section + 1) and 0x0F) shl 8) or packet.byte(section + 2)
        val end = minOf(PACKET, section + 3 + sectionLen - 4)
        val programInfo = ((packet.byte(section + 10) and 0x0F) shl 8) or packet.byte(section + 11)
        var at = section + 12 + programInfo
        var avcFallback: VideoStreamInfo? = null
        while (at + 5 <= end) {
            val type = packet.byte(at)
            val pid = ((packet.byte(at + 1) and 0x1F) shl 8) or packet.byte(at + 2)
            when (type) {
                STREAM_HEVC -> return VideoStreamInfo(pid, TsVideoCodec.HEVC)
                STREAM_AVC -> if (avcFallback == null) avcFallback = VideoStreamInfo(pid, TsVideoCodec.AVC)
            }
            val esInfoLen = ((packet.byte(at + 3) and 0x0F) shl 8) or packet.byte(at + 4)
            at += 5 + esInfoLen
        }
        return avcFallback
    }

    /**
     * 跨任意大小网络分块维护 188 字节 MPEG-TS 同步与包边界对齐，零整块拷贝。
     */
    private class TsPacketStreamFramer {
        private var synced = false
        private val carry = ByteArray(PACKET)
        private var carryLen = 0
        private var syncBuf = ByteArray(0)
        private var syncLen = 0

        inline fun feed(chunk: ByteArray, length: Int, onPacket: (bytes: ByteArray, at: Int) -> Boolean) {
            if (length <= 0) return
            var pos = 0

            if (!synced) {
                if (syncLen == 0) {
                    val sync = findSyncOffset(chunk, 0, length)
                    if (sync != null) {
                        synced = true
                        pos = sync
                    } else {
                        // 仅保留末尾足以跨下一块寻找 0x47 同步的前缀，防止损坏流无限涨内存
                        val keep = minOf(length, PACKET * 3)
                        if (syncBuf.size < keep) syncBuf = ByteArray(PACKET * 4)
                        chunk.copyInto(syncBuf, 0, length - keep, length)
                        syncLen = keep
                        return
                    }
                } else {
                    val combinedLen = syncLen + length
                    val needed = minOf(combinedLen, PACKET * 8)
                    val combined = ByteArray(syncLen + minOf(length, needed - syncLen))
                    syncBuf.copyInto(combined, 0, 0, syncLen)
                    val prefixTake = combined.size - syncLen
                    chunk.copyInto(combined, syncLen, 0, prefixTake)
                    val sync = findSyncOffset(combined, 0, combined.size)
                    if (sync == null) {
                        val keep = minOf(combined.size, PACKET * 3)
                        if (syncBuf.size < keep) syncBuf = ByteArray(PACKET * 4)
                        combined.copyInto(syncBuf, 0, combined.size - keep, combined.size)
                        syncLen = keep
                        if (prefixTake < length) {
                            val tailSync = findSyncOffset(chunk, prefixTake, length)
                            if (tailSync != null) {
                                synced = true
                                syncLen = 0
                                pos = tailSync
                            } else {
                                val tailKeep = minOf(length, PACKET * 3)
                                chunk.copyInto(syncBuf, 0, length - tailKeep, length)
                                syncLen = tailKeep
                                return
                            }
                        } else {
                            return
                        }
                    } else {
                        synced = true
                        val savedSyncLen = syncLen
                        syncLen = 0
                        // 先吐出落在 combined 前半段（跨 syncBuf 与 chunk）的完整 TS 包
                        var at: Int = sync
                        while (at + PACKET <= combined.size) {
                            if (combined[at] != SYNC) {
                                val next = findSyncOffset(combined, at + 1, combined.size)
                                if (next == null) {
                                    synced = false
                                    break
                                }
                                at = next
                                continue
                            }
                            if (onPacket(combined, at)) return
                            at += PACKET
                        }
                        if (!synced) {
                            val rem = combined.size - at
                            if (rem > 0) {
                                val keep = minOf(rem, PACKET * 3)
                                if (syncBuf.size < keep) syncBuf = ByteArray(PACKET * 4)
                                combined.copyInto(syncBuf, 0, combined.size - keep, combined.size)
                                syncLen = keep
                            }
                            return
                        }
                        val tailInCombined = combined.size - at
                        if (at >= savedSyncLen) {
                            pos = at - savedSyncLen
                        } else {
                            if (tailInCombined > 0) {
                                combined.copyInto(carry, 0, at, combined.size)
                                carryLen = tailInCombined
                            }
                            pos = prefixTake
                        }
                    }
                }
            }

            // 若上一块留下了不足 188 字节的尾包，先与当前块头部拼成完整包
            if (carryLen > 0) {
                val need = PACKET - carryLen
                if (length - pos < need) {
                    chunk.copyInto(carry, carryLen, pos, length)
                    carryLen += (length - pos)
                    return
                }
                chunk.copyInto(carry, carryLen, pos, pos + need)
                carryLen = 0
                pos += need
                if (carry[0] == SYNC) {
                    if (onPacket(carry, 0)) return
                } else {
                    synced = false
                    val next = findSyncOffset(chunk, pos, length) ?: return
                    synced = true
                    pos = next
                }
            }

            // 零拷贝遍历当前块中的所有完整 188 字节 TS 包
            while (pos + PACKET <= length) {
                if (chunk[pos] != SYNC) {
                    val next = findSyncOffset(chunk, pos + 1, length)
                    if (next == null) {
                        synced = false
                        val keep = minOf(length - pos, PACKET * 3)
                        if (syncBuf.size < keep) syncBuf = ByteArray(PACKET * 4)
                        chunk.copyInto(syncBuf, 0, length - keep, length)
                        syncLen = keep
                        return
                    }
                    pos = next
                    continue
                }
                if (onPacket(chunk, pos)) return
                pos += PACKET
            }

            val rem = length - pos
            if (rem > 0) {
                chunk.copyInto(carry, 0, pos, length)
                carryLen = rem
            }
        }
    }

    private class PacketView(val bytes: ByteArray, val at: Int) {
        fun byte(offset: Int): Int = bytes[at + offset].toInt() and 0xFF
        val pid: Int get() = ((byte(1) and 0x1F) shl 8) or byte(2)
        val start: Boolean get() = byte(1) and 0x40 != 0
        val continuity: Int get() = byte(3) and 0x0F
        private val adaptation: Int get() = (byte(3) ushr 4) and 0x3
        val randomAccess: Boolean get() = adaptation and 0x2 != 0 && byte(4) >= 1 && (byte(5) and 0x40) != 0
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
        private var currentBytes = 0
        private var currentDeclaredLen = 0
        private var broken = false
        private var currentRandomAccess = false
        private var lastContinuity = -1

        /**
         * 喂入一帧 TS 包：返回 `(由本包 start 终结的上一条 PES, 若当前定长 PES 已收齐则立即产出的本条 PES)`。
         */
        fun feedWithImmediateComplete(packet: PacketView): Pair<PesPacket?, PesPacket?> {
            if (!packet.hasPayload) return null to null
            val cc = packet.continuity
            if (lastContinuity == cc) return null to null
            val gap = lastContinuity >= 0 && cc != ((lastContinuity + 1) and 0x0F)
            lastContinuity = cc
            if (gap) broken = true
            val finishedPrev = if (packet.start) buildCurrent(requireDeclaredLength = false) else null
            val payload = packet.payload()
            if (packet.start) {
                parts = mutableListOf(payload)
                currentBytes = payload.size
                currentDeclaredLen = if (payload.size >= 6 && payload[0] == 0.toByte() && payload[1] == 0.toByte() && payload[2] == 1.toByte()) {
                    ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
                } else {
                    0
                }
                broken = false
                currentRandomAccess = packet.randomAccess
            } else {
                val list = parts
                if (list != null) {
                    list.add(payload)
                    currentBytes += payload.size
                }
            }
            val completedNow = if (!broken && currentDeclaredLen > 0 && currentBytes >= 6 + currentDeclaredLen) {
                drain(requireDeclaredLength = true)
            } else {
                null
            }
            return finishedPrev to completedNow
        }

        fun drain(requireDeclaredLength: Boolean = false): PesPacket? =
            buildCurrent(requireDeclaredLength = requireDeclaredLength).also {
                parts = null
                currentBytes = 0
                currentDeclaredLen = 0
                broken = false
                currentRandomAccess = false
            }

        fun peekComplete(requireDeclaredLength: Boolean = false): PesPacket? =
            buildCurrent(requireDeclaredLength = requireDeclaredLength)

        fun inspectTruncatedIdrTail(codec: TsVideoCodec): TsIdrExtractionResult.TruncatedAtTail? {
            val list = parts ?: return null
            if (broken) return null
            val total = currentBytes
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
            if (declared != 0 && 6 + declared <= headerEnd) return null
            val declaredRemaining = if (declared != 0) {
                val rem = (6 + declared) - bytes.size
                if (rem <= 0) return null
                rem
            } else {
                null
            }
            val payload = bytes.copyOfRange(headerEnd, bytes.size)
            val nals = nalUnits(normalizeAccessUnit(payload), codec)
            val looksLikeIdr = currentRandomAccess || when (codec) {
                TsVideoCodec.HEVC -> nals.any { it.type in HEVC_IRAP || it.type in HEVC_VPS..HEVC_PPS }
                TsVideoCodec.AVC -> nals.any { it.type == AVC_IDR || it.type == AVC_SPS || it.type == AVC_PPS }
            }
            return if (looksLikeIdr) TsIdrExtractionResult.TruncatedAtTail(declaredRemainingBytes = declaredRemaining) else null
        }

        private fun buildCurrent(requireDeclaredLength: Boolean = false): PesPacket? {
            val list = parts ?: return null
            if (broken) return null
            val total = currentBytes
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

    private fun nalUnits(bytes: ByteArray, codec: TsVideoCodec): List<Nal> {
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
        val minNalBytes = if (codec == TsVideoCodec.HEVC) 2 else 1
        return starts.mapIndexedNotNull { index, start ->
            var end = if (index + 1 < starts.size) starts[index + 1] - 3 else bytes.size
            while (end > start && bytes[end - 1] == 0.toByte()) end--
            if (end - start < minNalBytes) {
                null
            } else {
                val type = when (codec) {
                    TsVideoCodec.HEVC -> (bytes[start].toInt() ushr 1) and 0x3F
                    TsVideoCodec.AVC -> bytes[start].toInt() and 0x1F
                }
                Nal(type, start, end)
            }
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
