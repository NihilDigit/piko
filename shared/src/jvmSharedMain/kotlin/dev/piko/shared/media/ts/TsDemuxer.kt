package dev.piko.shared.media.ts

/** 按偏移读：从 [position] 读至多 [length] 字节进 [buffer] 的 [offset] 处，返回读到的字节数，文件尾返回 -1。 */
typealias TsRead = suspend (position: Long, buffer: ByteArray, offset: Int, length: Int) -> Int

/** HEVC 视频轨。[config] 是 Annex B 的 VPS、SPS、PPS（各带起始码），即 MediaMuxer 要的 csd-0。 */
class TsVideoTrack(val pid: Int, val config: ByteArray, val width: Int, val height: Int)

/** ADTS 的 AAC 音轨。[config] 是由 ADTS 头换算出的 AudioSpecificConfig。 */
class TsAudioTrack(val pid: Int, val config: ByteArray, val sampleRate: Int, val channels: Int)

class TsInfo(
    val video: TsVideoTrack,
    val audio: TsAudioTrack?,
    /** 片头的时间，微秒：两条轨第一个时间戳里早的那个，与播放器、FFmpeg 的 start_time 同一个起点。 */
    val startUs: Long,
)

/**
 * 一个样本：一帧视频（一个 PES，Annex B），或一帧 AAC（去掉 ADTS 头）。时间为微秒，未减片头，33 位回绕已展开。
 * 样本按文件里的顺序交出，即解码顺序；有 B 帧时 [ptsUs] 前后交错，[dtsUs] 递增。
 */
class TsSample(val video: Boolean, val ptsUs: Long, val dtsUs: Long, val keyframe: Boolean, val data: ByteArray)

/** 片段的起点关键帧：时间，微秒，与它起头的 TS 包在文件里的偏移。 */
class TsKeyframe(val ptsUs: Long, val byteOffset: Long)

/**
 * 只认 PikPak 转码档那种 MPEG-TS 的解复用器：一路 HEVC（stream_type 0x24）、至多一路 ADTS 的 AAC（0x0F），一个 PES 是一帧视频。
 *
 * 为 Android 而写：系统的 MediaExtractor 读 TS 时认不出 HEVC 视频轨，只交出音频（PKG110、Android 16 实测：
 * 对 testdata 的 transcode-hevc-aac.ts，getTrackFormat 只有 audio/mp4a-latm），转码档下载与从转码档截片段因此只剩声音。
 * 读出的样本交给 MediaMuxer 写 MP4。否决过引入 media3-extractor：为这一种格式固定的容器多一个依赖，
 * 还要接它整套 Extractor 与 TrackOutput 回调，自己解析反而更小，也能在 JVM 上与 FFmpeg 逐样本对拍。
 *
 * 读取经 [read] 按偏移进行，片段只读起点附近与那一段。continuity counter 断了的那个 PES 整个丢掉，
 * 不交出缺了字节的帧；PTS 的 33 位回绕按片头展开一次（26.5 小时以内的片子足够）。
 */
class TsDemuxer(private val size: Long, private val read: TsRead) {

    // 片头的 90 kHz 时间戳，展开回绕的参照；probe 之后才有
    private var originTicks: Long? = null

    /** 读节目表与两条轨的参数。不是 TS、或节目表里没有 HEVC 视频时返回 null，调用方交给系统解析器。 */
    suspend fun probe(): TsInfo? {
        if (!looksLikeTs()) return null
        var pmtPid = -1
        var videoPid = -1
        var audioPid = -1
        var noHevc = false
        var video: TsVideoTrack? = null
        var audio: TsAudioTrack? = null
        var firstVideo: Long? = null
        var firstAudio: Long? = null
        val assembler = PesAssembler()
        scan(0L, PROBE_BYTES) { offset, packet ->
            when (val pid = packet.pid) {
                0 -> if (packet.start) pmtPid = parsePat(packet)
                pmtPid -> if (packet.start && videoPid < 0) parsePmt(packet)?.let { (v, a) ->
                    if (v < 0) noHevc = true
                    videoPid = v
                    audioPid = a
                }
                videoPid, audioPid -> assembler.feed(packet, offset)?.let { pes ->
                    if (pid == videoPid) {
                        if (firstVideo == null) firstVideo = pes.ptsTicks
                        if (video == null) video = videoTrack(pid, pes.payload)
                    } else {
                        if (firstAudio == null) firstAudio = pes.ptsTicks
                        if (audio == null) audio = audioTrack(pid, pes.payload)
                    }
                }
            }
            !noHevc && (video == null || (audioPid >= 0 && audio == null))
        }
        val track = video ?: return null
        // 两条轨的起点隔着回绕点时，早的那个是数值大的
        val origin = listOfNotNull(firstVideo, firstAudio).minWithOrNull { a, b -> wrapDistance(b, a).compareTo(0) } ?: 0L
        originTicks = origin
        return TsInfo(track, audio, ticksToUs(origin))
    }

    /**
     * [targetUs] 之前（含）最近的视频关键帧。先按时间戳二分找到早于目标的位置，往后读视频 PES 头；
     * 没碰到就再往前多退一些，片头之前都没有时取第一个关键帧。关键帧按 TS 包的 random_access_indicator
     * 或 PES 开头那一包里的 IRAP 片认。
     */
    suspend fun keyframeAtOrBefore(info: TsInfo, targetUs: Long): TsKeyframe {
        for (lookbackUs in KEYFRAME_LOOKBACK_US) {
            val from = offsetBefore(info, targetUs - lookbackUs)
            var best: TsKeyframe? = null
            var firstAfter: TsKeyframe? = null
            scan(from, Long.MAX_VALUE) { offset, packet ->
                if (packet.pid != info.video.pid || !packet.start) return@scan true
                val pts = packet.pesPtsTicks()?.let(::unwrapUs) ?: return@scan true
                if (packet.randomAccess || packet.startsIrap()) {
                    if (pts <= targetUs) best = TsKeyframe(pts, offset)
                    else if (firstAfter == null) firstAfter = TsKeyframe(pts, offset)
                }
                // B 帧让显示时间在解码顺序上前后交错，越过目标一些再停
                pts <= targetUs + REORDER_MARGIN_US
            }
            best?.let { return it }
            if (from == 0L) return firstAfter ?: TsKeyframe(info.startUs, 0L)
        }
        return TsKeyframe(info.startUs, 0L)
    }

    /**
     * 从 [from] 起逐个交出样本，[onSample] 返回 false 即停。[from] 不在包边界上时取它之前的包边界，
     * 落在 PES 中间的那一帧不完整，丢掉。[onPosition] 报已读到的偏移，整段转封装按它算进度。
     */
    suspend fun samples(
        info: TsInfo,
        from: Long,
        onPosition: (Long) -> Unit = {},
        onSample: suspend (TsSample) -> Boolean,
    ) {
        val assembler = PesAssembler()
        var going = true
        suspend fun emit(pes: Pes?): Boolean {
            pes ?: return true
            val pts = unwrapUs(pes.ptsTicks)
            val dts = unwrapUs(pes.dtsTicks)
            if (pes.pid == info.video.pid) {
                return onSample(TsSample(true, pts, dts, isKeyframe(pes.payload), accessUnit(pes.payload)))
            }
            val audio = info.audio ?: return true
            // 一个 PES 常装着几帧 ADTS，每帧 1024 个采样，时间依次往后推
            return adtsFrames(pes.payload).withIndex().all { (index, frame) ->
                val time = pts + index * 1024L * 1_000_000L / audio.sampleRate
                onSample(TsSample(false, time, time, true, frame))
            }
        }
        scan(from / PACKET * PACKET, Long.MAX_VALUE) { offset, packet ->
            onPosition(offset + PACKET)
            if (packet.pid == info.video.pid || packet.pid == info.audio?.pid) going = emit(assembler.feed(packet, offset))
            going
        }
        if (going) for (pes in assembler.drain()) if (!emit(pes)) break
    }

    // 二分找一个包边界，从它往后第一个视频 PES 的时间不晚于 [targetUs]，且尽量靠后
    private suspend fun offsetBefore(info: TsInfo, targetUs: Long): Long {
        if (targetUs <= info.startUs) return 0L
        var low = 0L
        var high = size / PACKET
        while (high - low > 1) {
            val middle = (low + high) / 2
            val pts = firstVideoPtsFrom(info, middle * PACKET)
            if (pts != null && pts <= targetUs) low = middle else high = middle
        }
        return low * PACKET
    }

    private suspend fun firstVideoPtsFrom(info: TsInfo, from: Long): Long? {
        var found: Long? = null
        scan(from, BISECT_SCAN_BYTES) { _, packet ->
            if (packet.pid == info.video.pid && packet.start) found = packet.pesPtsTicks()?.let(::unwrapUs)
            found == null
        }
        return found
    }

    private fun unwrapUs(ticks: Long): Long {
        val origin = originTicks ?: return ticksToUs(ticks)
        return ticksToUs(origin + wrapDistance(origin, ticks))
    }

    private suspend fun looksLikeTs(): Boolean {
        if (size < PACKET * 2) return false
        val head = ByteArray(PACKET + 1)
        return readFully(0, head) == head.size && head[0] == SYNC && head[PACKET] == SYNC
    }

    /** 从 [from] 起最多读 [limit] 字节，逐包交给 [onPacket]（偏移与包），它返回 false 即停。 */
    private suspend fun scan(from: Long, limit: Long, onPacket: suspend (Long, Packet) -> Boolean) {
        val buffer = ByteArray(CHUNK)
        var position = from
        val end = if (limit == Long.MAX_VALUE) size else minOf(size, from + limit)
        while (position + PACKET <= end) {
            val wanted = minOf(CHUNK.toLong(), (end - position) / PACKET * PACKET).toInt()
            val got = readFully(position, buffer, wanted)
            if (got < PACKET) return
            var at = 0
            while (at + PACKET <= got) {
                if (buffer[at] == SYNC && !onPacket(position + at, Packet(buffer, at))) return
                at += PACKET
            }
            position += at
        }
    }

    private suspend fun readFully(position: Long, buffer: ByteArray, length: Int = buffer.size): Int {
        var filled = 0
        while (filled < length) {
            val n = read(position + filled, buffer, filled, length - filled)
            if (n <= 0) break
            filled += n
        }
        return filled
    }

    private fun parsePat(packet: Packet): Int {
        val section = packet.payloadStart + 1 + packet.byte(packet.payloadStart)
        // 节目循环里第一个节目号不为 0 的那个；0 号是网络信息表
        var at = section + 8
        val end = section + 3 + (((packet.byte(section + 1) and 0x0F) shl 8) or packet.byte(section + 2)) - 4
        while (at + 4 <= end && at + 4 <= PACKET) {
            val program = (packet.byte(at) shl 8) or packet.byte(at + 1)
            if (program != 0) return ((packet.byte(at + 2) and 0x1F) shl 8) or packet.byte(at + 3)
            at += 4
        }
        return -1
    }

    /** HEVC 视频与 ADTS 音频的 PID，没有的为 -1。 */
    private fun parsePmt(packet: Packet): Pair<Int, Int>? {
        val section = packet.payloadStart + 1 + packet.byte(packet.payloadStart)
        if (packet.byte(section) != 0x02) return null
        val end = section + 3 + (((packet.byte(section + 1) and 0x0F) shl 8) or packet.byte(section + 2)) - 4
        val programInfo = ((packet.byte(section + 10) and 0x0F) shl 8) or packet.byte(section + 11)
        var at = section + 12 + programInfo
        var video = -1
        var audio = -1
        while (at + 5 <= end && at + 5 <= PACKET) {
            val type = packet.byte(at)
            val pid = ((packet.byte(at + 1) and 0x1F) shl 8) or packet.byte(at + 2)
            if (type == STREAM_HEVC && video < 0) video = pid
            if (type == STREAM_AAC_ADTS && audio < 0) audio = pid
            at += 5 + ((((packet.byte(at + 3) and 0x0F) shl 8) or packet.byte(at + 4)))
        }
        return video to audio
    }

    private fun videoTrack(pid: Int, accessUnit: ByteArray): TsVideoTrack? {
        val units = nalUnits(accessUnit)
        val parameterSets = units.filter { it.type in HEVC_VPS..HEVC_PPS }.distinctBy { it.type }
        if (parameterSets.size < 3) return null
        val sps = parameterSets.first { it.type == HEVC_SPS }
        val (width, height) = HevcSps.dimensions(accessUnit.copyOfRange(sps.start, sps.end)) ?: return null
        val config = parameterSets.flatMap { START_CODE + accessUnit.copyOfRange(it.start, it.end).toList() }
        return TsVideoTrack(pid, config.toByteArray(), width, height)
    }

    private fun audioTrack(pid: Int, payload: ByteArray): TsAudioTrack? {
        if (payload.size < 7 || payload[0] != 0xFF.toByte() || payload[1].toInt() and 0xF0 != 0xF0) return null
        val objectType = ((payload[2].toInt() ushr 6) and 0x3) + 1
        val frequencyIndex = (payload[2].toInt() ushr 2) and 0xF
        val channels = ((payload[2].toInt() and 0x1) shl 2) or ((payload[3].toInt() ushr 6) and 0x3)
        val rate = ADTS_RATES.getOrNull(frequencyIndex) ?: return null
        val config = byteArrayOf(
            ((objectType shl 3) or (frequencyIndex ushr 1)).toByte(),
            (((frequencyIndex and 1) shl 7) or (channels shl 3)).toByte(),
        )
        return TsAudioTrack(pid, config, rate, channels)
    }

    private fun isKeyframe(accessUnit: ByteArray): Boolean = nalUnits(accessUnit).any { it.type in HEVC_IRAP }

    /**
     * PikPak 的转码把下一帧起始码前的 zero_byte 写在了上一个 PES 末尾，除第一帧外每帧都以三字节起始码开头、以一个 0 结尾。
     * 照 H.265 附录 B，zero_byte 属于它后面那个访问单元：去掉末尾的 0，开头补成四字节起始码。
     * FFmpeg 的 HEVC 解析器也是这样分帧的，真实转码档逐帧字节一致；只去尾不补头，第二帧起每帧差一个字节。
     */
    private fun accessUnit(payload: ByteArray): ByteArray {
        var end = payload.size
        while (end > 0 && payload[end - 1] == 0.toByte()) end--
        val shortStartCode = end >= 3 && payload[0] == 0.toByte() && payload[1] == 0.toByte() && payload[2] == 1.toByte()
        return when {
            shortStartCode -> ByteArray(end + 1).also { payload.copyInto(it, destinationOffset = 1, endIndex = end) }
            end == payload.size -> payload
            else -> payload.copyOf(end)
        }
    }

    private fun adtsFrames(payload: ByteArray): List<ByteArray> = buildList {
        var at = 0
        while (at + 7 <= payload.size && payload[at] == 0xFF.toByte() && payload[at + 1].toInt() and 0xF0 == 0xF0) {
            val header = if (payload[at + 1].toInt() and 0x1 == 0) 9 else 7
            val length = ((payload[at + 3].toInt() and 0x3) shl 11) or ((payload[at + 4].toInt() and 0xFF) shl 3) or
                ((payload[at + 5].toInt() and 0xFF) ushr 5)
            if (length < header || at + length > payload.size) break
            add(payload.copyOfRange(at + header, at + length))
            at += length
        }
    }

    /** 一个 TS 包的视图，不复制。 */
    private class Packet(val bytes: ByteArray, val at: Int) {
        fun byte(offset: Int): Int = bytes[at + offset].toInt() and 0xFF
        val pid: Int get() = ((byte(1) and 0x1F) shl 8) or byte(2)
        val start: Boolean get() = byte(1) and 0x40 != 0
        val continuity: Int get() = byte(3) and 0x0F
        private val adaptation: Int get() = (byte(3) ushr 4) and 0x3
        val randomAccess: Boolean get() = adaptation and 0x2 != 0 && byte(4) > 0 && byte(5) and 0x40 != 0
        val payloadStart: Int get() = if (adaptation and 0x2 != 0) 5 + byte(4) else 4
        val hasPayload: Boolean get() = adaptation and 0x1 != 0 && payloadStart < PACKET

        fun payload(): ByteArray = bytes.copyOfRange(at + payloadStart, at + PACKET)

        fun pesPtsTicks(): Long? {
            val p = payloadStart
            if (!hasPayload || p + 14 > PACKET || byte(p) != 0 || byte(p + 1) != 0 || byte(p + 2) != 1) return null
            if (byte(p + 7) and 0x80 == 0) return null
            return timestamp(bytes, at + p + 9)
        }

        /** PES 起头的这一包里有没有 IRAP 片。参数集较长时片头可能落在下一包，此时靠 [randomAccess]。 */
        fun startsIrap(): Boolean {
            val p = payloadStart
            if (!hasPayload || p + 9 > PACKET || p + 9 + byte(p + 8) >= PACKET) return false
            val body = bytes.copyOfRange(at + p + 9 + byte(p + 8), at + PACKET)
            return nalUnits(body).any { it.type in HEVC_IRAP }
        }
    }

    /** 时间戳是 90 kHz 的原始值，未展开回绕。 */
    private class Pes(val pid: Int, val ptsTicks: Long, val dtsTicks: Long, val payload: ByteArray)

    /**
     * 按 PID 把包拼成 PES：下一个 PES 起头时，上一个才算完整。同一 PID 的 continuity counter 跳号说明中间丢了包，
     * 正在拼的那个 PES 缺了字节，整个丢掉；计数不变的是重复包，跳过。
     */
    private class PesAssembler {
        private class Pending(val parts: MutableList<ByteArray>, var broken: Boolean = false)

        private val open = HashMap<Int, Pending>()
        private val counters = HashMap<Int, Int>()

        fun feed(packet: Packet, @Suppress("UNUSED_PARAMETER") offset: Long): Pes? {
            if (!packet.hasPayload) return null
            val last = counters.put(packet.pid, packet.continuity)
            if (last == packet.continuity) return null
            val gap = last != null && packet.continuity != (last + 1) and 0x0F
            if (gap) open[packet.pid]?.broken = true
            val finished = if (packet.start) open.remove(packet.pid)?.let { build(packet.pid, it) } else null
            if (packet.start) open[packet.pid] = Pending(mutableListOf(packet.payload()))
            else open[packet.pid]?.parts?.add(packet.payload())
            return finished
        }

        fun drain(): List<Pes> = open.entries.mapNotNull { (pid, pending) -> build(pid, pending) }.also { open.clear() }

        private fun build(pid: Int, pending: Pending): Pes? {
            if (pending.broken) return null
            val bytes = ByteArray(pending.parts.sumOf { it.size })
            var at = 0
            for (part in pending.parts) {
                part.copyInto(bytes, at)
                at += part.size
            }
            if (bytes.size < 14 || bytes[0] != 0.toByte() || bytes[1] != 0.toByte() || bytes[2] != 1.toByte()) return null
            val flags = (bytes[7].toInt() ushr 6) and 0x3
            if (flags and 0x2 == 0) return null
            val headerEnd = 9 + (bytes[8].toInt() and 0xFF)
            if (headerEnd > bytes.size) return null
            val declared = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            // 长度为 0 是视频 PES 不写长度；写了的按它截，末尾可能有填充
            val end = if (declared == 0) bytes.size else minOf(bytes.size, 6 + declared)
            // 写了长度却没拼够，说明末尾的包丢了
            if (declared != 0 && bytes.size < 6 + declared) return null
            val pts = timestamp(bytes, 9)
            val dts = if (flags == 0x3 && bytes.size >= 19) timestamp(bytes, 14) else pts
            return Pes(pid, pts, dts, bytes.copyOfRange(headerEnd, end))
        }
    }

    companion object {
        private const val PACKET = 188
        private const val SYNC = 0x47.toByte()
        private const val CHUNK = PACKET * 1394
        private const val PROBE_BYTES = 8L * 1024 * 1024
        private const val BISECT_SCAN_BYTES = 4L * 1024 * 1024
        private const val STREAM_HEVC = 0x24
        private const val STREAM_AAC_ADTS = 0x0F
        private const val HEVC_VPS = 32
        private const val HEVC_SPS = 33
        private const val HEVC_PPS = 34
        private val HEVC_IRAP = 16..21
        private val START_CODE = listOf<Byte>(0, 0, 0, 1)
        private const val REORDER_MARGIN_US = 1_000_000L
        private val KEYFRAME_LOOKBACK_US = longArrayOf(0L, 10_000_000L, 30_000_000L, 120_000_000L)
        private val ADTS_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        private const val WRAP = 1L shl 33

        private fun timestamp(bytes: ByteArray, at: Int): Long =
            ((bytes[at].toLong() and 0x0E) shl 29) or
                ((bytes[at + 1].toLong() and 0xFF) shl 22) or
                ((bytes[at + 2].toLong() and 0xFE) shl 14) or
                ((bytes[at + 3].toLong() and 0xFF) shl 7) or
                ((bytes[at + 4].toLong() and 0xFE) ushr 1)

        private fun ticksToUs(ticks: Long): Long = ticks * 100 / 9

        /** 从 [from] 到 [to] 隔了多少个 90 kHz 刻度，按 33 位回绕取最近的那个方向。 */
        internal fun wrapDistance(from: Long, to: Long): Long {
            val diff = (to - from).mod(WRAP)
            return if (diff >= WRAP / 2) diff - WRAP else diff
        }

        private class Nal(val type: Int, val start: Int, val end: Int)

        /** Annex B 里的各个 NAL（不含起始码），type 是 HEVC 的 nal_unit_type。 */
        private fun nalUnits(bytes: ByteArray): List<Nal> {
            val starts = mutableListOf<Int>()
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
                // 四字节起始码多出来的那个 0 属于前一个 NAL 的末尾，去掉
                while (end > start && bytes[end - 1] == 0.toByte()) end--
                if (start >= end) null else Nal((bytes[start].toInt() ushr 1) and 0x3F, start, end)
            }
        }
    }
}

/** 从 HEVC 的 SPS 读画面尺寸（按裁切窗口裁过）。MediaMuxer 写 MP4 必须有宽高，TS 里只有 SPS 带着。 */
internal object HevcSps {
    fun dimensions(nal: ByteArray): Pair<Int, Int>? = runCatching {
        val bits = BitReader(unescape(nal), startByte = 2)
        bits.skip(4)
        val maxSubLayers = bits.read(3)
        bits.skip(1)
        bits.skip(88 + 8)
        val profilePresent = BooleanArray(maxSubLayers)
        val levelPresent = BooleanArray(maxSubLayers)
        for (i in 0 until maxSubLayers) {
            profilePresent[i] = bits.read(1) == 1
            levelPresent[i] = bits.read(1) == 1
        }
        if (maxSubLayers > 0) for (i in maxSubLayers until 8) bits.skip(2)
        for (i in 0 until maxSubLayers) {
            if (profilePresent[i]) bits.skip(88)
            if (levelPresent[i]) bits.skip(8)
        }
        bits.ue()
        val chroma = bits.ue()
        if (chroma == 3) bits.skip(1)
        var width = bits.ue()
        var height = bits.ue()
        if (bits.read(1) == 1) {
            val subWidth = if (chroma == 1 || chroma == 2) 2 else 1
            val subHeight = if (chroma == 1) 2 else 1
            width -= subWidth * (bits.ue() + bits.ue())
            height -= subHeight * (bits.ue() + bits.ue())
        }
        width to height
    }.getOrNull()?.takeIf { (w, h) -> w > 0 && h > 0 }

    // 去掉防竞争字节：00 00 03 里的 03
    private fun unescape(nal: ByteArray): ByteArray {
        val out = ArrayList<Byte>(nal.size)
        var zeros = 0
        for (byte in nal) {
            if (zeros >= 2 && byte == 3.toByte()) {
                zeros = 0
                continue
            }
            out += byte
            zeros = if (byte == 0.toByte()) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val bytes: ByteArray, startByte: Int) {
        private var position = startByte * 8

        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                val bit = (bytes[position / 8].toInt() ushr (7 - position % 8)) and 1
                value = (value shl 1) or bit
                position++
            }
            return value
        }

        fun skip(count: Int) {
            position += count
        }

        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) zeros++
            return (1 shl zeros) - 1 + read(zeros)
        }
    }
}
