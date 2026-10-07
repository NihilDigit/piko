package dev.piko.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.piko.shared.media.ts.TsDemuxer
import dev.piko.shared.media.ts.TsInfo
import dev.piko.shared.media.ts.TsRead
import dev.piko.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * HEVC 的 MPEG-TS（PikPak 的转码档）转成 MP4：系统的 MediaExtractor 认不出 TS 里的 HEVC 视频轨（见 TsDemuxer），
 * 这里改由 TsDemuxer 取样本，照样交给 MediaMuxer。别的容器与编码仍走 [VideoSegmentExtractor] 的 MediaExtractor。
 */
internal object TsSegmentMuxer {

    /**
     * 源是 HEVC 的 TS 时转封装并返回结果，否则返回 null。[range] 为 null 时整段，否则是毫秒区间，起点退到之前的关键帧。
     * 源从 [dataSource] 读，没有时读本机路径 [path]。
     */
    suspend fun muxIfHevcTs(
        context: Context,
        path: String,
        dataSource: MediaDataSource?,
        destinationFile: File,
        range: LongRange?,
        onProgress: (Float) -> Unit,
    ): Result<Unit>? = withContext(Dispatchers.IO) {
        val file = if (dataSource == null) File(path).takeIf { it.isFile }?.let { RandomAccessFile(it, "r") } else null
        try {
            val size = dataSource?.size ?: file?.length() ?: return@withContext null
            val read: TsRead = if (dataSource != null) {
                { position, buffer, offset, length -> dataSource.readAt(position, buffer, offset, length) }
            } else {
                { position, buffer, offset, length ->
                    file!!.seek(position)
                    file.read(buffer, offset, length)
                }
            }
            val demuxer = TsDemuxer(size, read)
            val info = runSuspendCatching { demuxer.probe() }.getOrElse { return@withContext Result.failure(it) } ?: return@withContext null
            mux(context, demuxer, info, size, destinationFile, range, onProgress)
        } finally {
            file?.close()
        }
    }

    private suspend fun mux(
        context: Context,
        demuxer: TsDemuxer,
        info: TsInfo,
        size: Long,
        destinationFile: File,
        range: LongRange?,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = runSuspendCatching {
        val tempFile = File(destinationFile.parentFile ?: context.cacheDir, "${destinationFile.name}.part")
        tempFile.delete()
        tempFile.parentFile?.mkdirs()
        try {
            val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                val video = muxer.addTrack(
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, info.video.width, info.video.height).apply {
                        setByteBuffer("csd-0", ByteBuffer.wrap(info.video.config))
                    },
                )
                val audio = info.audio?.let { track ->
                    muxer.addTrack(
                        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, track.sampleRate, track.channels).apply {
                            setByteBuffer("csd-0", ByteBuffer.wrap(track.config))
                        },
                    )
                }
                muxer.start()
                started = true
                copySamples(demuxer, info, size, muxer, video, audio, range, onProgress)
            } finally {
                if (started) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
            check(tempFile.length() > 1024L) { "视频片段提取未生成有效数据" }
            destinationFile.delete()
            if (!tempFile.renameTo(destinationFile)) {
                tempFile.copyTo(destinationFile, overwrite = true)
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 与 [VideoSegmentExtractor] 的 MediaExtractor 路径同样的截法：视频从起点关键帧到显示时间越过终点为止，
     * 关键帧之后显示时间早于它的前导帧丢掉，音频按显示时间截在同一区间，时间戳整体减去起点。
     */
    private suspend fun copySamples(
        demuxer: TsDemuxer,
        info: TsInfo,
        size: Long,
        muxer: MediaMuxer,
        videoTrack: Int,
        audioTrack: Int?,
        range: LongRange?,
        onProgress: (Float) -> Unit,
    ) {
        val keyframe = range?.let { demuxer.keyframeAtOrBefore(info, info.startUs + it.first * 1000) }
        val startUs = keyframe?.ptsUs ?: info.startUs
        val endUs = range?.let { info.startUs + it.last * 1000 } ?: Long.MAX_VALUE
        // 音频在字节上常比同一时刻的视频靠前，从关键帧之前一截读起，免得片段开头没声音
        val from = keyframe?.let { (it.byteOffset - AUDIO_LEAD_BYTES).coerceAtLeast(0) } ?: 0L
        var videoStarted = range == null
        var videoDone = false
        var audioDone = audioTrack == null
        var reported = -1
        val bufferInfo = MediaCodec.BufferInfo()
        fun report(fraction: Double) {
            val percent = (fraction * 100).toInt().coerceIn(0, 100)
            if (percent > reported) {
                reported = percent
                onProgress(percent / 100f)
            }
        }
        demuxer.samples(info, from, onPosition = { if (range == null) report(it.toDouble() / size) }) { sample ->
            coroutineContext.ensureActive()
            if (sample.video) {
                if (!videoStarted) {
                    if (!sample.keyframe || sample.ptsUs < startUs) return@samples true
                    videoStarted = true
                }
                if (sample.ptsUs < startUs) return@samples true
                if (sample.ptsUs > endUs) {
                    videoDone = true
                    return@samples !audioDone && sample.ptsUs <= endUs + END_OVERRUN_US
                }
                bufferInfo.set(0, sample.data.size, sample.ptsUs - startUs, if (sample.keyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(videoTrack, ByteBuffer.wrap(sample.data), bufferInfo)
                if (range != null) report((sample.ptsUs - startUs).toDouble() / (endUs - startUs).coerceAtLeast(1))
            } else if (audioTrack != null) {
                if (sample.ptsUs < startUs) return@samples true
                if (sample.ptsUs > endUs) {
                    audioDone = true
                    return@samples !videoDone
                }
                bufferInfo.set(0, sample.data.size, sample.ptsUs - startUs, 0)
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(sample.data), bufferInfo)
            }
            true
        }
        coroutineContext.ensureActive()
        onProgress(1f)
    }

    private const val AUDIO_LEAD_BYTES = 512L * 1024
    private const val END_OVERRUN_US = 2_000_000L
}
