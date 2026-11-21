package dev.piko.shared.media.player

import dev.piko.shared.media.proxy.ProxyByteSource
import dev.piko.shared.media.proxy.ProxyReader
import kotlin.math.min

/**
 * 原画容器内封字幕轨的轻量元数据（来自 MKV Tracks 头或 MP4 moov 原子，单次小范围 Range 即可探测）。
 */
data class OriginalSubtitleTrackInfo(
    val trackNumber: Int,
    val codecId: String,
    val title: String?,
    val language: String?,
    val extension: String,
    val isImage: Boolean = false,
)

/**
 * 从原画容器提取出的内存字幕载荷：经本机代理作为外挂字幕挂到转码流上，两端通用、零磁盘残留。
 */
data class ExtractedOriginalSubtitle(
    val info: OriginalSubtitleTrackInfo,
    val bytes: ByteArray,
    val fileName: String,
)

/**
 * 原画容器内封字幕探测与提取结果。
 * [extracted] 可直接挂载到转码播放流；[unextracted] 为大体积未缓存 MKV 等暂未全量抽出的内封字幕提示。
 */
data class OriginalEmbeddedSubtitlesResult(
    val extracted: List<ExtractedOriginalSubtitle> = emptyList(),
    val unextracted: List<OriginalSubtitleTrackInfo> = emptyList(),
)

/**
 * 轻量纯 Kotlin 字节数组代理源：把提取到内存的 `.srt` / `.ass` 直接交给 `PikoMediaProxy` 供 mpv `sub-add` 读取。
 */
internal class InMemoryProxyByteSource(
    private val data: ByteArray,
) : ProxyByteSource {
    override val size: Long get() = data.size.toLong()

    override suspend fun openReader(): ProxyReader = object : ProxyReader {
        private var pos = 0L

        override val position: Long get() = pos

        override suspend fun seekTo(position: Long) {
            pos = position.coerceIn(0L, data.size.toLong())
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size || length <= 0) return -1
            val start = pos.toInt()
            val count = min(length, data.size - start)
            data.copyInto(buffer, destinationOffset = offset, startIndex = start, endIndex = start + count)
            pos += count
            return count
        }

        override fun close() = Unit
    }

    override fun close() = Unit
}

/**
 * 原画内封字幕探测与提取器（零平台依赖，Android 与 Desktop 共用）：
 * - **MP4 / MOV**：仅按 box 链定位 `moov`（片头或片尾），解析 `sbtl`/`text` 轨的 `stco`/`co64` 偏移表后，
 *   只拉取字幕采样本身（通常几十 KiB，零视频/音频下载），生成标准 `.srt`；
 * - **MKV / WebM**：单次小范围读头部 `Tracks` 识别全部内封字幕轨（`S_TEXT/UTF8` / `S_TEXT/ASS` / `S_TEXT/SSA`），
 *   当原画已在本地缓存或体积处于安全阈值内时扫描 `Cluster` 抽出完整 `.srt` / `.ass`，否则保留轨道元数据供界面提示。
 */
internal object OriginalSubtitleExtractor {
    private const val HEAD_PROBE_BYTES = 256 * 1024
    private const val MAX_MOOV_BYTES = 8 * 1024 * 1024
    private const val MAX_SUBTITLE_TOTAL_BYTES = 4 * 1024 * 1024
    private const val MAX_MP4_SUB_CHUNKS = 240
    private const val MAX_MP4_SUB_SAMPLES = 50_000
    private const val MAX_MP4_TABLE_ENTRIES = 50_000
    private const val MAX_MP4_SUB_SAMPLE_BYTES = 64 * 1024
    private const val MAX_MKV_DIRECT_EXTRACT_BYTES = 96L * 1024 * 1024
    private const val MAX_MKV_SUB_BLOCK_BYTES = 64L * 1024L
    private const val MAX_MKV_BLOCK_GROUP_BYTES = 256L * 1024L
    private const val MAX_SAFE_TIMESTAMP_MS = Long.MAX_VALUE / 4L

    suspend fun extract(
        fileSize: Long,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?,
        canScanFullMkv: Boolean = false,
    ): OriginalEmbeddedSubtitlesResult {
        if (fileSize <= 32L) return OriginalEmbeddedSubtitlesResult()
        val headLen = min(fileSize, HEAD_PROBE_BYTES.toLong()).toInt()
        val head = readRange(0L, headLen) ?: return OriginalEmbeddedSubtitlesResult()
        if (head.size < 12) return OriginalEmbeddedSubtitlesResult()
        return when {
            isEbml(head) -> extractMkv(head, fileSize, readRange, allowFullScan = canScanFullMkv || fileSize <= MAX_MKV_DIRECT_EXTRACT_BYTES)
            isIsoBmff(head) -> extractMp4(head, fileSize, readRange)
            else -> OriginalEmbeddedSubtitlesResult()
        }
    }

    private fun isEbml(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()

    private fun isIsoBmff(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()

    // =========================================================================
    // MKV / WebM (EBML)
    // =========================================================================

    private data class MkvSubTrack(
        val info: OriginalSubtitleTrackInfo,
        val codecPrivate: String?,
        val defaultDurationNs: Long,
    )

    private data class MkvCue(
        val startMs: Long,
        val endMs: Long,
        val payload: String,
    )

    private suspend fun extractMkv(
        head: ByteArray,
        fileSize: Long,
        readRange: suspend (Long, Int) -> ByteArray?,
        allowFullScan: Boolean,
    ): OriginalEmbeddedSubtitlesResult {
        val header = parseMkvHeader(head) ?: return OriginalEmbeddedSubtitlesResult()
        if (header.tracks.isEmpty()) return OriginalEmbeddedSubtitlesResult()
        val extractableTracks = header.tracks.filterNot { it.info.isImage }
        if (!allowFullScan || extractableTracks.isEmpty()) {
            return OriginalEmbeddedSubtitlesResult(
                extracted = emptyList(),
                unextracted = header.tracks.map { it.info },
            )
        }
        val tracksByNum = extractableTracks.associateBy { it.info.trackNumber }
        val cuesByTrack = extractableTracks.associate { it.info.trackNumber to ArrayList<MkvCue>() }
        val timecodeScaleNs = header.timecodeScaleNs.takeIf { it > 0L } ?: 1_000_000L

        // 流式按 EBML 元素边界扫描 Cluster（跨分块保持剩余载荷字节数，绝不在压缩视频字节内做盲扫）
        var filePos = header.firstClusterOffset.coerceAtLeast(0L)
        val chunkBytes = 1024 * 1024
        var carry = ByteArray(0)
        var skipBytesRemaining = 0L
        var currentClusterTimecode = 0L
        var totalCueBytes = 0

        while (filePos < fileSize && totalCueBytes < MAX_SUBTITLE_TOTAL_BYTES) {
            if (skipBytesRemaining > 0L && carry.isEmpty()) {
                val advance = min(skipBytesRemaining, fileSize - filePos)
                filePos += advance
                skipBytesRemaining -= advance
                if (filePos >= fileSize) break
            }
            val len = min(chunkBytes.toLong(), fileSize - filePos).toInt()
            val chunk = if (filePos == 0L && len <= head.size) {
                head.copyOfRange(0, len)
            } else {
                readRange(filePos, len)
            } ?: break
            if (chunk.isEmpty()) break
            filePos += chunk.size
            val isEof = filePos >= fileSize

            val buf = if (carry.isEmpty()) chunk else carry + chunk
            val scanResult = scanMkvBuffer(
                buf = buf,
                fileSize = fileSize,
                isEof = isEof,
                initialSkipBytes = skipBytesRemaining,
                tracksByNum = tracksByNum,
                timecodeScaleNs = timecodeScaleNs,
                currentClusterTimecode = currentClusterTimecode,
                onClusterTimecode = { currentClusterTimecode = it },
                onCue = { trackNum, cue ->
                    if (totalCueBytes < MAX_SUBTITLE_TOTAL_BYTES) {
                        cuesByTrack[trackNum]?.add(cue)
                        totalCueBytes += cue.payload.length
                    }
                },
            )
            skipBytesRemaining = scanResult.skipBytesRemaining
            val consumed = scanResult.consumed
            carry = if (consumed < buf.size) {
                val keepStart = maxOf(consumed, buf.size - 512 * 1024)
                buf.copyOfRange(keepStart, buf.size)
            } else {
                ByteArray(0)
            }
        }

        val extracted = ArrayList<ExtractedOriginalSubtitle>()
        val unextracted = ArrayList<OriginalSubtitleTrackInfo>()
        for (track in header.tracks) {
            if (track.info.isImage) {
                unextracted += track.info
                continue
            }
            val cues = cuesByTrack[track.info.trackNumber].orEmpty()
            if (cues.isEmpty()) {
                unextracted += track.info
                continue
            }
            val text = formatMkvSubtitle(track, cues)
            if (text.isNotBlank()) {
                val ext = track.info.extension
                extracted += ExtractedOriginalSubtitle(
                    info = track.info,
                    bytes = text.encodeToByteArray(),
                    fileName = "original-sub-${track.info.trackNumber}.$ext",
                )
            } else {
                unextracted += track.info
            }
        }
        return OriginalEmbeddedSubtitlesResult(extracted = extracted, unextracted = unextracted)
    }

    private data class MkvHeaderInfo(
        val timecodeScaleNs: Long,
        val tracks: List<MkvSubTrack>,
        val firstClusterOffset: Long,
    )

    private fun clampedEnd(dataStart: Int, elementSize: Long, limit: Int): Int {
        val startLong = dataStart.toLong()
        val endLong = if (elementSize <= 0L) startLong else startLong + elementSize
        val safeEnd = if (endLong < startLong) limit.toLong() else min(limit.toLong(), endLong)
        return safeEnd.coerceIn(startLong, limit.toLong()).toInt()
    }

    private fun parseMkvHeader(buf: ByteArray): MkvHeaderInfo? {
        var pos = 0
        var timecodeScaleNs = 1_000_000L
        val tracks = ArrayList<MkvSubTrack>()
        var firstClusterOffset = -1L

        // 跳过 EBML Header，进入 Segment (0x18538067)
        while (pos < buf.size - 4) {
            val idRes = readEbmlId(buf, pos) ?: break
            val sizeRes = readEbmlSize(buf, idRes.nextPos) ?: break
            val dataStart = sizeRes.nextPos
            if (idRes.value == 0x18538067L) { // Segment
                var segPos = dataStart
                val segEnd = if (sizeRes.unknown) buf.size else clampedEnd(dataStart, sizeRes.value, buf.size)
                while (segPos < segEnd - 4) {
                    val elemId = readEbmlId(buf, segPos) ?: break
                    val elemSize = readEbmlSize(buf, elemId.nextPos) ?: break
                    val elemData = elemSize.nextPos
                    val elemEnd = if (elemSize.unknown) segEnd else clampedEnd(elemData, elemSize.value, segEnd)
                    when (elemId.value) {
                        0x1549A966L -> { // Info
                            timecodeScaleNs = parseMkvInfoTimecodeScale(buf, elemData, elemEnd) ?: timecodeScaleNs
                        }
                        0x1654AE6BL -> { // Tracks
                            parseMkvTracks(buf, elemData, elemEnd, tracks)
                        }
                        0x1F43B675L -> { // Cluster
                            if (firstClusterOffset < 0L) firstClusterOffset = segPos.toLong()
                            break
                        }
                    }
                    if (elemSize.unknown || elemEnd <= segPos) break
                    segPos = elemEnd
                }
                break
            }
            val nextPosLong = dataStart.toLong() + sizeRes.value
            if (sizeRes.unknown || nextPosLong <= pos.toLong() || nextPosLong >= buf.size.toLong()) break
            pos = nextPosLong.toInt()
        }
        return MkvHeaderInfo(
            timecodeScaleNs = timecodeScaleNs,
            tracks = tracks,
            firstClusterOffset = if (firstClusterOffset >= 0L) firstClusterOffset else 0L,
        )
    }

    private fun parseMkvInfoTimecodeScale(buf: ByteArray, start: Int, end: Int): Long? {
        var pos = start
        while (pos < end - 2) {
            val id = readEbmlId(buf, pos) ?: break
            val sz = readEbmlSize(buf, id.nextPos) ?: break
            val dataStart = sz.nextPos
            val dataEnd = if (sz.unknown) end else clampedEnd(dataStart, sz.value, end)
            if (id.value == 0x2AD7B1L && !sz.unknown && dataEnd <= end) {
                return readUIntBe(buf, dataStart, dataEnd - dataStart)
            }
            if (sz.unknown || dataEnd <= pos) break
            pos = dataEnd
        }
        return null
    }

    private fun parseMkvTracks(buf: ByteArray, start: Int, end: Int, out: MutableList<MkvSubTrack>) {
        var pos = start
        while (pos < end - 2) {
            val id = readEbmlId(buf, pos) ?: break
            val sz = readEbmlSize(buf, id.nextPos) ?: break
            val dataStart = sz.nextPos
            val dataEnd = if (sz.unknown) end else clampedEnd(dataStart, sz.value, end)
            if (id.value == 0xAEL && !sz.unknown) { // TrackEntry
                parseMkvTrackEntry(buf, dataStart, dataEnd)?.let(out::add)
            }
            if (sz.unknown || dataEnd <= pos) break
            pos = dataEnd
        }
    }

    private fun parseMkvTrackEntry(buf: ByteArray, start: Int, end: Int): MkvSubTrack? {
        var trackNumber = -1
        var trackType = -1
        var codecId: String? = null
        var codecPrivate: String? = null
        var name: String? = null
        var language: String? = null
        var languageBcp47: String? = null
        var defaultDurationNs = 0L

        var pos = start
        while (pos < end - 2) {
            val id = readEbmlId(buf, pos) ?: break
            val sz = readEbmlSize(buf, id.nextPos) ?: break
            val dataStart = sz.nextPos
            val dataEnd = if (sz.unknown) end else clampedEnd(dataStart, sz.value, end)
            if (!sz.unknown && dataEnd <= end) {
                val len = dataEnd - dataStart
                when (id.value) {
                    0xD7L -> trackNumber = readUIntBe(buf, dataStart, len).toInt()
                    0x83L -> trackType = readUIntBe(buf, dataStart, len).toInt()
                    0x23E383L -> defaultDurationNs = readUIntBe(buf, dataStart, len)
                    0x86L -> codecId = buf.decodeToString(dataStart, dataEnd).trimEnd('\u0000')
                    0x63A2L -> if (len <= 64 * 1024) codecPrivate = decodeSubtitleSlice(buf, dataStart, dataEnd)
                    0x536EL -> name = decodeSubtitleSlice(buf, dataStart, dataEnd).trimEnd('\u0000').takeIf { it.isNotBlank() }
                    0x22B59CL -> language = buf.decodeToString(dataStart, dataEnd).trimEnd('\u0000').takeIf { it.isNotBlank() }
                    0x22B59DL -> languageBcp47 = buf.decodeToString(dataStart, dataEnd).trimEnd('\u0000').takeIf { it.isNotBlank() }
                }
            }
            if (sz.unknown || dataEnd <= pos) break
            pos = dataEnd
        }

        if (trackType != 17 || trackNumber <= 0) return null
        val codec = codecId ?: return null
        val (ext, isImage) = when (codec) {
            "S_TEXT/UTF8" -> "srt" to false
            "S_TEXT/ASS", "S_TEXT/SSA" -> "ass" to false
            "S_TEXT/WEBVTT" -> "vtt" to false
            "S_HDMV/PGS" -> "sup" to true
            "S_VOBSUB", "S_DVBSUB" -> "sub" to true
            else -> return null
        }
        return MkvSubTrack(
            info = OriginalSubtitleTrackInfo(
                trackNumber = trackNumber,
                codecId = codec,
                title = name,
                language = languageBcp47 ?: language,
                extension = ext,
                isImage = isImage,
            ),
            codecPrivate = if (isImage) null else codecPrivate,
            defaultDurationNs = defaultDurationNs,
        )
    }

    private data class MkvScanResult(
        val consumed: Int,
        val skipBytesRemaining: Long,
    )

    /**
     * 按验证过的 EBML 元素边界流式扫描 Cluster Timecode (0xE7)、SimpleBlock (0xA3) 与 BlockGroup (0xA0)。
     * 非目标大块（如视频关键帧或内封字体）跨分块时保留 [MkvScanResult.skipBytesRemaining]，绝不在视频字节内盲扫。
     */
    private fun scanMkvBuffer(
        buf: ByteArray,
        fileSize: Long,
        isEof: Boolean,
        initialSkipBytes: Long,
        tracksByNum: Map<Int, MkvSubTrack>,
        timecodeScaleNs: Long,
        currentClusterTimecode: Long,
        onClusterTimecode: (Long) -> Unit,
        onCue: (trackNumber: Int, cue: MkvCue) -> Unit,
    ): MkvScanResult {
        var clusterTc = currentClusterTimecode
        var pos = 0
        if (initialSkipBytes > 0L) {
            if (initialSkipBytes >= buf.size.toLong()) {
                return MkvScanResult(consumed = buf.size, skipBytesRemaining = initialSkipBytes - buf.size.toLong())
            }
            pos = initialSkipBytes.toInt()
        }

        while (pos < buf.size) {
            if (!isEof && buf.size - pos < 16) {
                return MkvScanResult(consumed = pos, skipBytesRemaining = 0L)
            }
            val id = readEbmlId(buf, pos)
            val sz = if (id != null) readEbmlSize(buf, id.nextPos) else null
            if (id == null || sz == null) {
                if (!isEof && buf.size - pos < 16) {
                    return MkvScanResult(consumed = pos, skipBytesRemaining = 0L)
                }
                pos = resyncToNextCluster(buf, pos + 1, isEof)
                continue
            }
            val dataStart = sz.nextPos
            if (id.value == 0x18538067L || id.value == 0x1F43B675L) { // Segment or Cluster: step into children
                pos = dataStart
                continue
            }
            if (sz.unknown || sz.value < 0L || sz.value > fileSize) {
                pos = resyncToNextCluster(buf, pos + 1, isEof)
                continue
            }
            val availInBuf = (buf.size - dataStart).toLong()
            when (id.value) {
                0xE7L -> { // Cluster Timestamp
                    if (sz.value > 8L) {
                        pos = resyncToNextCluster(buf, pos + 1, isEof)
                        continue
                    }
                    if (sz.value > availInBuf) {
                        return if (!isEof) {
                            MkvScanResult(consumed = pos, skipBytesRemaining = 0L)
                        } else {
                            MkvScanResult(consumed = buf.size, skipBytesRemaining = 0L)
                        }
                    }
                    val end = dataStart + sz.value.toInt()
                    if (sz.value > 0L) {
                        val parsedTc = readUIntBe(buf, dataStart, sz.value.toInt())
                        if (parsedTc >= 0L) {
                            clusterTc = parsedTc
                            onClusterTimecode(clusterTc)
                        }
                    }
                    pos = end
                }
                0xA3L -> { // SimpleBlock
                    if (sz.value < 4L) {
                        if (sz.value <= availInBuf) {
                            pos = dataStart + sz.value.toInt()
                            continue
                        }
                        return MkvScanResult(consumed = buf.size, skipBytesRemaining = sz.value - availInBuf)
                    }
                    if (availInBuf < min(4L, sz.value)) {
                        return if (!isEof) {
                            MkvScanResult(consumed = pos, skipBytesRemaining = 0L)
                        } else {
                            MkvScanResult(consumed = buf.size, skipBytesRemaining = 0L)
                        }
                    }
                    val trackVint = readEbmlSize(buf, dataStart)
                    val isSubTrack = trackVint != null &&
                        !trackVint.unknown &&
                        trackVint.value in 1L..Int.MAX_VALUE.toLong() &&
                        tracksByNum.containsKey(trackVint.value.toInt())
                    if (!isSubTrack || sz.value > MAX_MKV_SUB_BLOCK_BYTES) {
                        if (sz.value <= availInBuf) {
                            pos = dataStart + sz.value.toInt()
                            continue
                        }
                        return MkvScanResult(consumed = buf.size, skipBytesRemaining = sz.value - availInBuf)
                    }
                    if (sz.value > availInBuf) {
                        return if (!isEof) {
                            MkvScanResult(consumed = pos, skipBytesRemaining = 0L)
                        } else {
                            MkvScanResult(consumed = buf.size, skipBytesRemaining = 0L)
                        }
                    }
                    val end = dataStart + sz.value.toInt()
                    parseMkvBlock(buf, dataStart, end, tracksByNum, timecodeScaleNs, clusterTc, blockDuration = null, onCue = onCue)
                    pos = end
                }
                0xA0L -> { // BlockGroup
                    if (sz.value > MAX_MKV_BLOCK_GROUP_BYTES || sz.value < 6L) {
                        if (sz.value <= availInBuf) {
                            pos = dataStart + sz.value.toInt()
                            continue
                        }
                        return MkvScanResult(consumed = buf.size, skipBytesRemaining = sz.value - availInBuf)
                    }
                    if (sz.value > availInBuf) {
                        return if (!isEof) {
                            MkvScanResult(consumed = pos, skipBytesRemaining = 0L)
                        } else {
                            parseMkvBlockGroup(buf, dataStart, buf.size, tracksByNum, timecodeScaleNs, clusterTc, onCue)
                            MkvScanResult(consumed = buf.size, skipBytesRemaining = 0L)
                        }
                    }
                    val end = dataStart + sz.value.toInt()
                    parseMkvBlockGroup(buf, dataStart, end, tracksByNum, timecodeScaleNs, clusterTc, onCue)
                    pos = end
                }
                else -> {
                    if (sz.value <= availInBuf) {
                        pos = dataStart + sz.value.toInt()
                    } else {
                        return MkvScanResult(consumed = buf.size, skipBytesRemaining = sz.value - availInBuf)
                    }
                }
            }
        }
        return MkvScanResult(consumed = pos, skipBytesRemaining = 0L)
    }

    private fun resyncToNextCluster(buf: ByteArray, fromPos: Int, isEof: Boolean): Int {
        var i = fromPos.coerceAtLeast(0)
        while (i + 4 <= buf.size) {
            if (buf[i] == 0x1F.toByte() &&
                buf[i + 1] == 0x43.toByte() &&
                buf[i + 2] == 0xB6.toByte() &&
                buf[i + 3] == 0x75.toByte()
            ) {
                if (!isEof && buf.size - i < 12) return i
                if (readEbmlSize(buf, i + 4) != null) return i
            }
            i++
        }
        return if (isEof) buf.size else maxOf(fromPos, buf.size - 3).coerceAtMost(buf.size)
    }

    private fun parseMkvBlockGroup(
        buf: ByteArray,
        start: Int,
        end: Int,
        tracksByNum: Map<Int, MkvSubTrack>,
        timecodeScaleNs: Long,
        clusterTc: Long,
        onCue: (Int, MkvCue) -> Unit,
    ) {
        var blockStart = -1
        var blockEnd = -1
        var duration: Long? = null
        var pos = start
        while (pos < end - 2) {
            val id = readEbmlId(buf, pos) ?: break
            val sz = readEbmlSize(buf, id.nextPos) ?: break
            val dataStart = sz.nextPos
            val dataEnd = if (sz.unknown) end else clampedEnd(dataStart, sz.value, end)
            if (!sz.unknown && dataEnd <= end) {
                when (id.value) {
                    0xA1L -> {
                        blockStart = dataStart
                        blockEnd = dataEnd
                    }
                    0x9BL -> duration = readUIntBe(buf, dataStart, dataEnd - dataStart)
                }
            }
            if (sz.unknown || dataEnd <= pos) break
            pos = dataEnd
        }
        if (blockStart >= 0 && blockEnd > blockStart) {
            parseMkvBlock(buf, blockStart, blockEnd, tracksByNum, timecodeScaleNs, clusterTc, duration, onCue)
        }
    }

    private fun parseMkvBlock(
        buf: ByteArray,
        start: Int,
        end: Int,
        tracksByNum: Map<Int, MkvSubTrack>,
        timecodeScaleNs: Long,
        clusterTc: Long,
        blockDuration: Long?,
        onCue: (Int, MkvCue) -> Unit,
    ) {
        if (clusterTc < 0L || timecodeScaleNs <= 0L) return
        val trackVint = readEbmlSize(buf, start) ?: return
        if (trackVint.unknown || trackVint.value !in 1L..Int.MAX_VALUE.toLong()) return
        val trackNum = trackVint.value.toInt()
        val track = tracksByNum[trackNum] ?: return
        val headerPos = trackVint.nextPos
        if (headerPos + 3 >= end) return
        val relTc = ((buf[headerPos].toInt() shl 8) or (buf[headerPos + 1].toInt() and 0xFF)).toShort().toLong()
        val payloadStart = headerPos + 3
        val payloadLen = end - payloadStart
        if (payloadLen <= 0 || payloadLen > 32 * 1024) return
        val text = decodeSubtitleSlice(buf, payloadStart, end).trimEnd('\u0000')
        if (text.isBlank()) return
        val rawTicks = if (relTc >= 0L) satAdd(clusterTc, relTc) else (clusterTc + relTc).coerceAtLeast(0L)
        val startMs = scaleTicksToMillisSafe(rawTicks, timecodeScaleNs)
        val durMs = when {
            blockDuration != null && blockDuration > 0L -> scaleTicksToMillisSafe(blockDuration, timecodeScaleNs)
            track.defaultDurationNs > 0L -> track.defaultDurationNs / 1_000_000L
            else -> 3_000L
        }.coerceIn(200L, 30_000L)
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = satAdd(safeStart, durMs).coerceAtLeast(satAdd(safeStart, 200L))
        onCue(trackNum, MkvCue(startMs = safeStart, endMs = safeEnd, payload = text))
    }

    private fun satAdd(a: Long, b: Long): Long {
        if (a <= 0L) return b.coerceAtMost(MAX_SAFE_TIMESTAMP_MS)
        if (b <= 0L) return a.coerceAtMost(MAX_SAFE_TIMESTAMP_MS)
        return if (MAX_SAFE_TIMESTAMP_MS - a < b) MAX_SAFE_TIMESTAMP_MS else a + b
    }

    private fun satMul(a: Long, b: Long): Long {
        if (a <= 0L || b <= 0L) return 0L
        return if (a > MAX_SAFE_TIMESTAMP_MS / b) MAX_SAFE_TIMESTAMP_MS else a * b
    }

    private fun scaleTicksToMillisSafe(ticks: Long, timecodeScaleNs: Long): Long {
        if (ticks <= 0L || timecodeScaleNs <= 0L) return 0L
        if (timecodeScaleNs == 1_000_000L) return ticks.coerceAtMost(MAX_SAFE_TIMESTAMP_MS)
        val m = 1_000_000L
        val qT = ticks / m
        val rT = ticks % m
        val qS = timecodeScaleNs / m
        val rS = timecodeScaleNs % m
        val term1 = satMul(satMul(qT, qS), m)
        val term2 = satMul(qT, rS)
        val term3 = satMul(rT, qS)
        val term4 = (rT * rS) / m
        return satAdd(satAdd(term1, term2), satAdd(term3, term4))
    }

    private fun formatMkvSubtitle(track: MkvSubTrack, cues: List<MkvCue>): String {
        val sorted = cues.sortedBy { it.startMs }
        return when (track.info.extension) {
            "ass" -> buildString {
                val header = track.codecPrivate?.takeIf { it.isNotBlank() } ?: DEFAULT_ASS_HEADER
                append(header.trimEnd())
                append("\n")
                if (!header.contains("[Events]", ignoreCase = true)) {
                    append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
                }
                for (cue in sorted) {
                    // Matroska S_TEXT/ASS block 格式: ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text
                    val parts = cue.payload.split(',', limit = 9)
                    if (parts.size >= 9) {
                        val layer = parts[1].trim()
                        val style = parts[2]
                        val name = parts[3]
                        val ml = parts[4]
                        val mr = parts[5]
                        val mv = parts[6]
                        val effect = parts[7]
                        val text = parts[8].replace("\r\n", "\\N").replace("\n", "\\N")
                        append("Dialogue: $layer,${formatAssTime(cue.startMs)},${formatAssTime(cue.endMs)},$style,$name,$ml,$mr,$mv,$effect,$text\n")
                    } else {
                        val text = cue.payload.replace("\r\n", "\\N").replace("\n", "\\N")
                        append("Dialogue: 0,${formatAssTime(cue.startMs)},${formatAssTime(cue.endMs)},Default,,0,0,0,,$text\n")
                    }
                }
            }
            "vtt" -> buildString {
                append("WEBVTT\n\n")
                for (cue in sorted) {
                    append("${formatVttTime(cue.startMs)} --> ${formatVttTime(cue.endMs)}\n")
                    append(cue.payload.trim())
                    append("\n\n")
                }
            }
            else -> formatSrtCues(sorted)
        }
    }

    private data class EbmlVint(val value: Long, val nextPos: Int, val unknown: Boolean)

    private fun readEbmlId(buf: ByteArray, pos: Int): EbmlVint? {
        if (pos >= buf.size) return null
        val first = buf[pos].toInt() and 0xFF
        if (first == 0) return null
        var mask = 0x80
        var len = 1
        while (len <= 4 && (first and mask) == 0) {
            mask = mask ushr 1
            len++
        }
        if (len > 4 || pos + len > buf.size) return null
        var value = first.toLong()
        for (i in 1 until len) {
            value = (value shl 8) or (buf[pos + i].toInt() and 0xFF).toLong()
        }
        return EbmlVint(value, pos + len, unknown = false)
    }

    private fun readEbmlSize(buf: ByteArray, pos: Int): EbmlVint? {
        if (pos >= buf.size) return null
        val first = buf[pos].toInt() and 0xFF
        if (first == 0) return null
        var mask = 0x80
        var len = 1
        while (len <= 8 && (first and mask) == 0) {
            mask = mask ushr 1
            len++
        }
        if (len > 8 || pos + len > buf.size) return null
        var value = (first and (mask - 1)).toLong()
        var allOnes = (first and (mask - 1)) == (mask - 1)
        for (i in 1 until len) {
            val b = buf[pos + i].toInt() and 0xFF
            value = (value shl 8) or b.toLong()
            if (b != 0xFF) allOnes = false
        }
        return EbmlVint(value, pos + len, unknown = allOnes)
    }

    private fun readUIntBe(buf: ByteArray, offset: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until min(len, 8)) {
            v = (v shl 8) or (buf[offset + i].toInt() and 0xFF).toLong()
        }
        return v
    }

    // =========================================================================
    // MP4 / MOV (ISO-BMFF) — 零视频字节下载的稀疏字幕提取
    // =========================================================================

    private data class Mp4Stsz(
        val defaultSize: Int,
        val sampleCount: Int,
        val sizes: IntArray,
    )

    private data class Mp4SubTrack(
        val trackId: Int,
        val language: String?,
        val title: String?,
        val codec: String,
        val timescale: Long,
        val stts: List<Pair<Long, Long>>, // count -> delta
        val stsc: List<Triple<Long, Long, Long>>, // firstChunk -> samplesPerChunk -> descIdx
        val stszDefault: Int,
        val stszSampleCount: Int,
        val stszSizes: IntArray,
        val chunkOffsets: LongArray,
    )

    private suspend fun extractMp4(
        head: ByteArray,
        fileSize: Long,
        readRange: suspend (Long, Int) -> ByteArray?,
    ): OriginalEmbeddedSubtitlesResult {
        val moov = readMp4Moov(head, fileSize, readRange) ?: return OriginalEmbeddedSubtitlesResult()
        val tracks = parseMp4SubTracks(moov)
        if (tracks.isEmpty()) return OriginalEmbeddedSubtitlesResult()

        val extracted = ArrayList<ExtractedOriginalSubtitle>()
        val unextracted = ArrayList<OriginalSubtitleTrackInfo>()

        for ((index, track) in tracks.withIndex()) {
            val info = OriginalSubtitleTrackInfo(
                trackNumber = track.trackId.takeIf { it > 0 } ?: (index + 1),
                codecId = track.codec,
                title = track.title,
                language = track.language,
                extension = "srt",
            )
            val cues = extractMp4TrackCues(track, fileSize, readRange)
            if (cues.isEmpty()) {
                unextracted += info
                continue
            }
            val srt = formatSrtCues(cues)
            if (srt.isNotBlank()) {
                extracted += ExtractedOriginalSubtitle(
                    info = info,
                    bytes = srt.encodeToByteArray(),
                    fileName = "original-sub-${info.trackNumber}.srt",
                )
            } else {
                unextracted += info
            }
        }
        return OriginalEmbeddedSubtitlesResult(extracted = extracted, unextracted = unextracted)
    }

    private suspend fun readMp4Moov(
        head: ByteArray,
        fileSize: Long,
        readRange: suspend (Long, Int) -> ByteArray?,
    ): ByteArray? {
        if (fileSize < 8L) return null
        var offset = 0L
        var hops = 0
        while (offset <= fileSize - 8L && hops < 24) {
            hops++
            val remaining = fileSize - offset
            val hdr = if (offset <= head.size.toLong() - 16L) {
                head.copyOfRange(offset.toInt(), (offset + 16L).toInt())
            } else {
                readRange(offset, min(16L, remaining).toInt())
            } ?: return null
            if (hdr.size < 8) return null
            val rawSize = readUInt32Be(hdr, 0)
            val type = fourCc(hdr, 4)
            val headerLen: Long
            val boxSize: Long
            when (rawSize) {
                0L -> {
                    headerLen = 8L
                    boxSize = remaining
                }
                1L -> {
                    if (hdr.size < 16) return null
                    headerLen = 16L
                    boxSize = readUInt64Be(hdr, 8)
                }
                else -> {
                    headerLen = 8L
                    boxSize = rawSize
                }
            }
            if (boxSize < headerLen || boxSize <= 0L || boxSize > remaining) return null
            if (type == "moov") {
                if (boxSize > MAX_MOOV_BYTES.toLong()) return null
                val payloadOffset = offset + headerLen
                val payloadLen = (boxSize - headerLen).toInt()
                return if (payloadOffset <= head.size.toLong() - payloadLen.toLong()) {
                    head.copyOfRange(payloadOffset.toInt(), (payloadOffset + payloadLen).toInt())
                } else {
                    readRange(payloadOffset, payloadLen)
                }
            }
            offset += boxSize
        }
        return null
    }

    private fun parseMp4SubTracks(moov: ByteArray): List<Mp4SubTrack> {
        val result = ArrayList<Mp4SubTrack>()
        forEachMp4Box(moov, 0, moov.size) { type, start, end ->
            if (type == "trak") {
                parseMp4Track(moov, start, end)?.let(result::add)
            }
        }
        return result
    }

    private fun parseMp4Track(buf: ByteArray, start: Int, end: Int): Mp4SubTrack? {
        var trackId = 0
        var handlerType: String? = null
        var handlerName: String? = null
        var timescale = 1_000L
        var language: String? = null
        var codec: String? = null
        var stts: List<Pair<Long, Long>> = emptyList()
        var stsc: List<Triple<Long, Long, Long>> = emptyList()
        var stszDefault = 0
        var stszSampleCount = 0
        var stszSizes = IntArray(0)
        var chunkOffsets = LongArray(0)

        forEachMp4Box(buf, start, end) { type, s, e ->
            when (type) {
                "tkhd" -> if (e - s >= 24) {
                    val version = buf[s].toInt() and 0xFF
                    trackId = if (version == 1 && e - s >= 28) readUInt32Be(buf, s + 20).toInt() else readUInt32Be(buf, s + 12).toInt()
                }
                "mdia" -> {
                    forEachMp4Box(buf, s, e) { mType, ms, me ->
                        when (mType) {
                            "mdhd" -> if (me - ms >= 24) {
                                val version = buf[ms].toInt() and 0xFF
                                val tsOff = if (version == 1) ms + 20 else ms + 12
                                val langOff = if (version == 1) ms + 32 else ms + 20
                                if (langOff + 2 <= me) {
                                    timescale = readUInt32Be(buf, tsOff).coerceAtLeast(1L)
                                    language = decodeIso6392(readUInt16Be(buf, langOff))
                                }
                            }
                            "hdlr" -> if (me - ms >= 12) {
                                handlerType = fourCc(buf, ms + 8)
                                if (me - ms > 24) {
                                    val rawName = decodeSubtitleSlice(buf, ms + 24, me).trimEnd('\u0000').trim()
                                    if (rawName.isNotBlank() && !rawName.equals("SubtitleHandler", ignoreCase = true)) {
                                        handlerName = rawName
                                    }
                                }
                            }
                            "minf" -> {
                                forEachMp4Box(buf, ms, me) { nType, ns, ne ->
                                    if (nType == "stbl") {
                                        forEachMp4Box(buf, ns, ne) { bType, bs, be ->
                                            when (bType) {
                                                "stsd" -> if (be - bs >= 16) {
                                                    codec = fourCc(buf, bs + 12)
                                                }
                                                "stts" -> stts = parseStts(buf, bs, be)
                                                "stsc" -> stsc = parseStsc(buf, bs, be)
                                                "stsz" -> {
                                                    val parsed = parseStsz(buf, bs, be)
                                                    stszDefault = parsed.defaultSize
                                                    stszSampleCount = parsed.sampleCount
                                                    stszSizes = parsed.sizes
                                                }
                                                "stco" -> chunkOffsets = parseStco(buf, bs, be)
                                                "co64" -> chunkOffsets = parseCo64(buf, bs, be)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (handlerType !in setOf("sbtl", "text", "subt")) return null
        val subCodec = codec?.takeIf { it in setOf("tx3g", "text", "wvtt") } ?: return null
        if (chunkOffsets.isEmpty() || stts.isEmpty()) return null
        return Mp4SubTrack(
            trackId = trackId,
            language = language,
            title = handlerName,
            codec = subCodec,
            timescale = timescale,
            stts = stts,
            stsc = stsc,
            stszDefault = stszDefault,
            stszSampleCount = stszSampleCount,
            stszSizes = stszSizes,
            chunkOffsets = chunkOffsets,
        )
    }

    private suspend fun extractMp4TrackCues(
        track: Mp4SubTrack,
        fileSize: Long,
        readRange: suspend (Long, Int) -> ByteArray?,
    ): List<MkvCue> {
        if (track.chunkOffsets.isEmpty() || track.chunkOffsets.size > MAX_MP4_SUB_CHUNKS) return emptyList()
        var sttsTotalSamples = 0L
        for ((count, delta) in track.stts) {
            if (count < 0L || delta < 0L) return emptyList()
            sttsTotalSamples = (sttsTotalSamples + count).coerceAtMost(MAX_MP4_SUB_SAMPLES.toLong() + 1L)
        }
        val declaredSampleCount = if (track.stszDefault > 0) track.stszSampleCount else track.stszSizes.size
        if (declaredSampleCount <= 0 || sttsTotalSamples <= 0L) return emptyList()
        val sampleCount = minOf(
            declaredSampleCount.toLong(),
            sttsTotalSamples,
            MAX_MP4_SUB_SAMPLES.toLong(),
        ).toInt()
        if (sampleCount <= 0) return emptyList()

        // 展开每个样本的起止时间戳
        val timescale = track.timescale.coerceAtLeast(1L)
        val startTimesMs = LongArray(sampleCount)
        val endTimesMs = LongArray(sampleCount)
        var cursor = 0
        var pts = 0L
        for ((count, delta) in track.stts) {
            if (cursor >= sampleCount) break
            val runLen = minOf(count, (sampleCount - cursor).toLong()).toInt()
            for (i in 0 until runLen) {
                startTimesMs[cursor] = mp4PtsToMillisSafe(pts, timescale)
                pts = if (Long.MAX_VALUE - pts < delta) Long.MAX_VALUE else pts + delta
                endTimesMs[cursor] = mp4PtsToMillisSafe(pts, timescale)
                cursor++
            }
        }

        // 将连续块按样本映射为待读取区间，并合并邻近区间以减少 HTTP Range 次数
        data class SampleSpan(val sampleIdx: Int, val offset: Long, val size: Int)
        val spans = ArrayList<SampleSpan>(min(sampleCount, 4096))
        var sampleIdx = 0
        for (chunkIdx in track.chunkOffsets.indices) {
            if (sampleIdx >= sampleCount) break
            val chunkNum1Based = chunkIdx + 1L
            val samplesInChunk = samplesPerChunkAt(track.stsc, chunkNum1Based)
                .coerceIn(0L, (sampleCount - sampleIdx).toLong())
                .toInt()
            var off = track.chunkOffsets[chunkIdx]
            if (off < 0L || off > fileSize) {
                sampleIdx += samplesInChunk
                continue
            }
            for (s in 0 until samplesInChunk) {
                if (sampleIdx >= sampleCount) break
                val idx = sampleIdx++
                val sz = if (track.stszDefault > 0) track.stszDefault else track.stszSizes[idx]
                if (sz < 0 || off < 0L || off > fileSize || sz.toLong() > fileSize - off) {
                    off = -1L
                    continue
                }
                if (sz in 1..MAX_MP4_SUB_SAMPLE_BYTES) {
                    spans += SampleSpan(idx, off, sz)
                }
                off += sz.toLong()
            }
        }
        if (spans.isEmpty()) return emptyList()

        // 仅拉取小体积字幕区段（合并间隔 <= 4 KiB 的相邻块，总拉取量不超过 2 MiB）
        val cues = ArrayList<MkvCue>()
        var fetchedTotal = 0
        var totalCueBytes = 0
        var i = 0
        while (i < spans.size && fetchedTotal < 2 * 1024 * 1024 && totalCueBytes < MAX_SUBTITLE_TOTAL_BYTES) {
            val groupStart = spans[i].offset
            var groupEnd = groupStart + spans[i].size.toLong()
            var j = i + 1
            while (j < spans.size) {
                val nextEnd = spans[j].offset + spans[j].size.toLong()
                if (spans[j].offset < groupStart || spans[j].offset - groupEnd > 4096L || (nextEnd - groupStart) > 64 * 1024L) {
                    break
                }
                groupEnd = maxOf(groupEnd, nextEnd)
                j++
            }
            val fetchLen = (groupEnd - groupStart).toInt()
            if (fetchLen <= 0) {
                i = j
                continue
            }
            val bytes = readRange(groupStart, fetchLen) ?: break
            fetchedTotal += bytes.size
            for (k in i until j) {
                if (totalCueBytes >= MAX_SUBTITLE_TOTAL_BYTES) break
                val sp = spans[k]
                val relLong = sp.offset - groupStart
                if (relLong < 0L || relLong + sp.size.toLong() > bytes.size.toLong()) continue
                val text = decodeMp4SubtitleSample(track.codec, bytes, relLong.toInt(), sp.size)
                if (!text.isNullOrBlank()) {
                    val st = startTimesMs[sp.sampleIdx]
                    val en = endTimesMs[sp.sampleIdx].coerceAtLeast(satAdd(st, 200L))
                    cues += MkvCue(startMs = st, endMs = en, payload = text)
                    totalCueBytes += text.length
                }
            }
            i = j
        }
        return cues
    }

    private fun mp4PtsToMillisSafe(pts: Long, timescale: Long): Long {
        if (pts <= 0L || timescale <= 0L) return 0L
        val seconds = pts / timescale
        val rem = pts % timescale
        val remMs = if (rem <= Long.MAX_VALUE / 1_000L) {
            (rem * 1_000L) / timescale
        } else {
            ((rem.toDouble() * 1_000.0) / timescale.toDouble()).toLong()
        }
        return satAdd(satMul(seconds, 1_000L), remMs)
    }

    private fun decodeMp4SubtitleSample(codec: String, buf: ByteArray, offset: Int, len: Int): String? {
        if (len < 2) return null
        return when (codec) {
            "tx3g", "text" -> {
                val textLen = readUInt16Be(buf, offset)
                if (textLen <= 0 || 2 + textLen > len) return null
                decodeSubtitleSlice(buf, offset + 2, offset + 2 + textLen).trim()
            }
            "wvtt" -> {
                var result: String? = null
                forEachMp4Box(buf, offset, offset + len) { type, s, e ->
                    if (type == "vttc") {
                        forEachMp4Box(buf, s, e) { cType, cs, ce ->
                            if (cType == "payl" && ce > cs) {
                                result = decodeSubtitleSlice(buf, cs, ce).trim()
                            }
                        }
                    }
                }
                result
            }
            else -> null
        }
    }

    private fun samplesPerChunkAt(stsc: List<Triple<Long, Long, Long>>, chunkNum1Based: Long): Long {
        var spc = 1L
        for ((first, count, _) in stsc) {
            if (first <= chunkNum1Based) spc = count else break
        }
        return spc.coerceIn(0L, MAX_MP4_SUB_SAMPLES.toLong())
    }

    private fun forEachMp4Box(buf: ByteArray, start: Int, end: Int, onBox: (type: String, payloadStart: Int, payloadEnd: Int) -> Unit) {
        var pos = start
        val limit = end.coerceAtMost(buf.size)
        while (pos >= 0 && pos <= limit - 8) {
            val rawSize = readUInt32Be(buf, pos)
            val type = fourCc(buf, pos + 4)
            val headerLen: Int
            val boxSize: Long
            when (rawSize) {
                0L -> {
                    headerLen = 8
                    boxSize = (limit - pos).toLong()
                }
                1L -> {
                    if (pos > limit - 16) break
                    headerLen = 16
                    boxSize = readUInt64Be(buf, pos + 8)
                }
                else -> {
                    headerLen = 8
                    boxSize = rawSize
                }
            }
            if (boxSize < headerLen.toLong() || boxSize > (limit - pos).toLong()) break
            val next = pos + boxSize.toInt()
            onBox(type, pos + headerLen, next)
            if (next <= pos) break
            pos = next
        }
    }

    private fun parseStts(buf: ByteArray, start: Int, end: Int): List<Pair<Long, Long>> {
        if (end - start < 8) return emptyList()
        val rawCount = readUInt32Be(buf, start + 4)
        val maxByBytes = (end - start - 8) / 8
        val count = minOf(rawCount, maxByBytes.toLong(), MAX_MP4_TABLE_ENTRIES.toLong()).coerceAtLeast(0L).toInt()
        val out = ArrayList<Pair<Long, Long>>(count)
        var pos = start + 8
        repeat(count) {
            if (pos > end - 8) return out
            out += readUInt32Be(buf, pos) to readUInt32Be(buf, pos + 4)
            pos += 8
        }
        return out
    }

    private fun parseStsc(buf: ByteArray, start: Int, end: Int): List<Triple<Long, Long, Long>> {
        if (end - start < 8) return emptyList()
        val rawCount = readUInt32Be(buf, start + 4)
        val maxByBytes = (end - start - 8) / 12
        val count = minOf(rawCount, maxByBytes.toLong(), MAX_MP4_TABLE_ENTRIES.toLong()).coerceAtLeast(0L).toInt()
        val out = ArrayList<Triple<Long, Long, Long>>(count)
        var pos = start + 8
        repeat(count) {
            if (pos > end - 12) return out
            out += Triple(
                readUInt32Be(buf, pos),
                readUInt32Be(buf, pos + 4).coerceAtMost(MAX_MP4_SUB_SAMPLES.toLong()),
                readUInt32Be(buf, pos + 8),
            )
            pos += 12
        }
        return out
    }

    private fun parseStsz(buf: ByteArray, start: Int, end: Int): Mp4Stsz {
        if (end - start < 12) return Mp4Stsz(0, 0, IntArray(0))
        val rawDefaultSize = readUInt32Be(buf, start + 4)
        val rawCount = readUInt32Be(buf, start + 8)
        if (rawDefaultSize > MAX_MP4_SUB_SAMPLE_BYTES.toLong() || rawCount <= 0L) {
            return Mp4Stsz(0, 0, IntArray(0))
        }
        if (rawDefaultSize > 0L) {
            val cappedCount = min(rawCount, MAX_MP4_SUB_SAMPLES.toLong()).toInt()
            return Mp4Stsz(rawDefaultSize.toInt(), cappedCount, IntArray(0))
        }
        val maxByBytes = (end - start - 12) / 4
        val count = minOf(rawCount, maxByBytes.toLong(), MAX_MP4_SUB_SAMPLES.toLong()).coerceAtLeast(0L).toInt()
        val sizes = IntArray(count)
        var pos = start + 12
        for (i in 0 until count) {
            if (pos > end - 4) break
            val sz = readUInt32Be(buf, pos)
            sizes[i] = if (sz in 0L..MAX_MP4_SUB_SAMPLE_BYTES.toLong()) sz.toInt() else -1
            pos += 4
        }
        return Mp4Stsz(0, count, sizes)
    }

    private fun parseStco(buf: ByteArray, start: Int, end: Int): LongArray {
        if (end - start < 8) return LongArray(0)
        val rawCount = readUInt32Be(buf, start + 4)
        val maxByBytes = (end - start - 8) / 4
        val count = minOf(rawCount, maxByBytes.toLong(), (MAX_MP4_SUB_CHUNKS + 1).toLong()).coerceAtLeast(0L).toInt()
        val out = LongArray(count)
        var pos = start + 8
        for (i in 0 until count) {
            if (pos > end - 4) break
            out[i] = readUInt32Be(buf, pos)
            pos += 4
        }
        return out
    }

    private fun parseCo64(buf: ByteArray, start: Int, end: Int): LongArray {
        if (end - start < 8) return LongArray(0)
        val rawCount = readUInt32Be(buf, start + 4)
        val maxByBytes = (end - start - 8) / 8
        val count = minOf(rawCount, maxByBytes.toLong(), (MAX_MP4_SUB_CHUNKS + 1).toLong()).coerceAtLeast(0L).toInt()
        val out = LongArray(count)
        var pos = start + 8
        for (i in 0 until count) {
            if (pos > end - 8) break
            out[i] = readUInt64Be(buf, pos)
            pos += 8
        }
        return out
    }

    private fun fourCc(buf: ByteArray, offset: Int): String =
        charArrayOf(
            (buf[offset].toInt() and 0xFF).toChar(),
            (buf[offset + 1].toInt() and 0xFF).toChar(),
            (buf[offset + 2].toInt() and 0xFF).toChar(),
            (buf[offset + 3].toInt() and 0xFF).toChar(),
        ).concatToString()

    private fun readUInt16Be(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun readUInt32Be(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toInt() and 0xFF).toLong() shl 24) or
            ((buf[offset + 1].toInt() and 0xFF).toLong() shl 16) or
            ((buf[offset + 2].toInt() and 0xFF).toLong() shl 8) or
            (buf[offset + 3].toInt() and 0xFF).toLong()

    private fun readUInt64Be(buf: ByteArray, offset: Int): Long =
        (readUInt32Be(buf, offset) shl 32) or readUInt32Be(buf, offset + 4)

    private fun decodeIso6392(packed: Int): String? {
        val c1 = ((packed ushr 10) and 0x1F) + 0x60
        val c2 = ((packed ushr 5) and 0x1F) + 0x60
        val c3 = (packed and 0x1F) + 0x60
        if (c1 !in 'a'.code..'z'.code || c2 !in 'a'.code..'z'.code || c3 !in 'a'.code..'z'.code) return null
        val code = charArrayOf(c1.toChar(), c2.toChar(), c3.toChar()).concatToString()
        return code.takeUnless { it == "und" }
    }

    // =========================================================================
    // Formatting helpers
    // =========================================================================

    private fun formatSrtCues(cues: List<MkvCue>): String = buildString {
        cues.forEachIndexed { idx, cue ->
            append(idx + 1).append('\n')
            append(formatSrtTime(cue.startMs)).append(" --> ").append(formatSrtTime(cue.endMs)).append('\n')
            append(cue.payload.trim()).append("\n\n")
        }
    }

    private fun formatSrtTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)},${pad3(millis)}"
    }

    private fun formatVttTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "${pad2(hours)}:${pad2(minutes)}:${pad2(seconds)}.${pad3(millis)}"
    }

    private fun formatAssTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val centis = (safe % 1_000L) / 10L
        return "$hours:${pad2(minutes)}:${pad2(seconds)}.${pad2(centis)}"
    }

    private fun decodeSubtitleSlice(buf: ByteArray, start: Int, end: Int): String {
        if (end <= start) return ""
        return SubtitleEncoding.current.decodeToString(buf.copyOfRange(start, end))
    }

    private fun pad2(v: Long): String = v.toString().padStart(2, '0')
    private fun pad3(v: Long): String = v.toString().padStart(3, '0')

    private const val DEFAULT_ASS_HEADER =
        "[Script Info]\nScriptType: v4.00+\nPlayResX: 1280\nPlayResY: 720\n\n" +
            "[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n" +
            "Style: Default,Sans,38,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,2,0,2,20,20,24,1\n"
}
