package dev.piko.shared.media.ts

import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用 testdata/media 的转码档样片（generate.sh 生成：176x144、25 帧、关键帧每 2 秒、HEVC 带 B 帧，单声道 44.1 kHz 的 AAC，
 * 时间戳从约 1.457 秒起）核对解复用的结果。期望值与 ffprobe 读同一个文件的结果一致。
 */
class TsDemuxerTest {
    private val sample = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { it.resolve("testdata/media/transcode-hevc-aac.ts") }.first { it.isFile }

    @Test
    fun probeReadsTracksFromTheParameterSets() = withDemuxer { demuxer ->
        val info = assertNotNull(demuxer.probe(), "认不出转码档")
        assertEquals(176 to 144, info.video.width to info.video.height, "SPS 里的画面尺寸")
        assertEquals(44_100, info.audio?.sampleRate)
        assertEquals(1, info.audio?.channels)
        // AAC LC、44.1 kHz、单声道的 AudioSpecificConfig
        assertEquals(listOf(0x12, 0x08), info.audio?.config?.map { it.toInt() and 0xFF })
        assertEquals(1_456_778.0, info.startUs.toDouble(), 2_000.0)
    }

    @Test
    fun samplesCoverEveryFrameWithKeyframesEveryTwoSeconds() = withDemuxer { demuxer ->
        val info = demuxer.probe()!!
        val video = mutableListOf<TsSample>()
        var audio = 0
        demuxer.samples(info, 0L) { sample ->
            if (sample.video) video += sample else audio++
            true
        }
        assertEquals(250, video.size, "视频帧数")
        val keyframes = video.filter { it.keyframe }.map { (it.ptsUs - info.startUs) / 1000 }
        assertEquals(listOf(23L, 2023L, 4023L, 6023L, 8023L), keyframes, "关键帧位置（毫秒，相对片头）")
        assertTrue(audio in 430..435, "音频帧数 $audio")
    }

    @Test
    fun keyframeSearchReturnsThePreviousKeyframe() = withDemuxer { demuxer ->
        val info = demuxer.probe()!!
        val keyframe = demuxer.keyframeAtOrBefore(info, info.startUs + 5_000_000)
        assertEquals(4_023L, (keyframe.ptsUs - info.startUs) / 1000)
        // 从它的偏移读起，第一帧视频就是这个关键帧
        var first: TsSample? = null
        demuxer.samples(info, keyframe.byteOffset) { sample ->
            if (sample.video) first = sample
            first == null
        }
        assertEquals(keyframe.ptsUs, first?.ptsUs)
        assertTrue(first?.keyframe == true)
    }

    // 样片带 B 帧：解码顺序上显示时间前后交错，解码时间要单调递增，MediaMuxer 才写得出正确的 ctts
    @Test
    fun bFramesCarryDecodeTimestamps() = withDemuxer { demuxer ->
        val info = demuxer.probe()!!
        val video = collect(demuxer, info).filter { it.video }
        assertTrue(video.any { it.ptsUs != it.dtsUs }, "样片里应有 B 帧")
        assertTrue(video.zipWithNext().all { (a, b) -> b.dtsUs > a.dtsUs }, "解码时间不单调")
    }

    // 丢一个包：缺了字节的那一帧整个不交出，其余各帧与原样一字不差
    @Test
    fun continuityGapDropsOnlyTheDamagedFrame() = runBlocking {
        val original = sample.readBytes()
        val reference = collect(original).filter { it.video }
        val victim = packetOffsets(original).filter { pidOf(original, it) == videoPid(original) && !startsPes(original, it) }[300]
        val damaged = original.copyOfRange(0, victim) + original.copyOfRange(victim + 188, original.size)

        val video = collect(damaged).filter { it.video }

        assertEquals(reference.size - 1, video.size, "应只丢一帧")
        val intact = reference.map { it.dtsUs to it.data.toList() }.toSet()
        assertTrue(video.all { (it.dtsUs to it.data.toList()) in intact }, "交出了缺字节的帧")
    }

    // 时间戳在片中回绕（33 位计数到头归零）：展开后照样连续，关键帧相对片头的位置不变
    @Test
    fun timestampWrapIsUnrolled() = runBlocking {
        val original = sample.readBytes()
        val start = collect(original).minOf { it.dtsUs } * 9 / 100
        val wrapped = shiftTimestamps(original, (1L shl 33) - start - 5 * 90_000)

        val info = TsDemuxer(wrapped.size.toLong(), memoryReader(wrapped)).probe()!!
        val video = collect(wrapped).filter { it.video }

        assertTrue(video.zipWithNext().all { (a, b) -> b.dtsUs > a.dtsUs }, "回绕处时间倒退了")
        assertEquals(listOf(23L, 2023L, 4023L, 6023L, 8023L), video.filter { it.keyframe }.map { (it.ptsUs - info.startUs) / 1000 })
    }

    @Test
    fun otherContainersAreLeftToTheSystemParser() = runBlocking {
        val mkv = sample.resolveSibling("keyframes-h264-aac.mkv")
        RandomAccessFile(mkv, "r").use { file -> assertNull(TsDemuxer(file.length(), reader(file)).probe()) }
    }

    private fun withDemuxer(block: suspend (TsDemuxer) -> Unit) = runBlocking {
        RandomAccessFile(sample, "r").use { file -> block(TsDemuxer(file.length(), reader(file))) }
    }

    private suspend fun collect(demuxer: TsDemuxer, info: TsInfo): List<TsSample> = buildList {
        demuxer.samples(info, 0L) { add(it); true }
    }

    private suspend fun collect(bytes: ByteArray): List<TsSample> {
        val demuxer = TsDemuxer(bytes.size.toLong(), memoryReader(bytes))
        return collect(demuxer, demuxer.probe()!!)
    }

    private fun memoryReader(bytes: ByteArray): TsRead = { position, buffer, offset, length ->
        if (position >= bytes.size) -1 else {
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            n
        }
    }

    private fun packetOffsets(bytes: ByteArray): List<Int> = (0 until bytes.size / 188).map { it * 188 }

    private fun pidOf(bytes: ByteArray, at: Int): Int = ((bytes[at + 1].toInt() and 0x1F) shl 8) or (bytes[at + 2].toInt() and 0xFF)

    private fun startsPes(bytes: ByteArray, at: Int): Boolean = bytes[at + 1].toInt() and 0x40 != 0

    // 样片由 ffmpeg 写出，视频的 PID 是 0x100
    private fun videoPid(@Suppress("UNUSED_PARAMETER") bytes: ByteArray): Int = 0x100

    /** 把每个 PES 头里的 PTS、DTS 加上 [shift]（按 33 位取模），模拟时间戳在片中回绕。 */
    private fun shiftTimestamps(original: ByteArray, shift: Long): ByteArray {
        val bytes = original.copyOf()
        for (at in packetOffsets(bytes)) {
            if (!startsPes(bytes, at) || pidOf(bytes, at) < 0x100) continue
            val adaptation = (bytes[at + 3].toInt() ushr 4) and 0x3
            val p = at + if (adaptation and 0x2 != 0) 5 + (bytes[at + 4].toInt() and 0xFF) else 4
            if (bytes[p] != 0.toByte() || bytes[p + 1] != 0.toByte() || bytes[p + 2] != 1.toByte()) continue
            val flags = (bytes[p + 7].toInt() ushr 6) and 0x3
            if (flags and 0x2 != 0) rewrite(bytes, p + 9, shift)
            if (flags == 0x3) rewrite(bytes, p + 14, shift)
        }
        return bytes
    }

    private fun rewrite(bytes: ByteArray, at: Int, shift: Long) {
        val old = ((bytes[at].toLong() and 0x0E) shl 29) or ((bytes[at + 1].toLong() and 0xFF) shl 22) or
            ((bytes[at + 2].toLong() and 0xFE) shl 14) or ((bytes[at + 3].toLong() and 0xFF) shl 7) or
            ((bytes[at + 4].toLong() and 0xFE) ushr 1)
        val value = (old + shift).mod(1L shl 33)
        bytes[at] = ((bytes[at].toInt() and 0xF0) or (((value ushr 30) and 0x7).toInt() shl 1) or 1).toByte()
        bytes[at + 1] = (value ushr 22).toByte()
        bytes[at + 2] = ((((value ushr 15) and 0x7F).toInt() shl 1) or 1).toByte()
        bytes[at + 3] = (value ushr 7).toByte()
        bytes[at + 4] = (((value and 0x7F).toInt() shl 1) or 1).toByte()
    }

    private fun reader(file: RandomAccessFile): TsRead = { position, buffer, offset, length ->
        file.seek(position)
        file.read(buffer, offset, length)
    }
}
