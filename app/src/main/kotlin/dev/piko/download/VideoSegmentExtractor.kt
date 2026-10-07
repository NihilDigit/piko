package dev.piko.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import dev.piko.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Lossless video segment extractor based on native MediaExtractor and MediaMuxer.
 *
 * Extracts and remuxes audio and video tracks without re-encoding, preserving
 * synchronization from keyframes and generating compliant MP4 container boxes.
 *
 * Documentation References:
 * - Android Media Extraction: android-docs-mirror/pages/media/media3/inspector/extract-samples.md
 * - Kotlin Structured Concurrency: kotlin-docs-mirror/pages/docs/coroutines-cancellation.md
 *   "Rethrow CancellationException to ensure coroutine cancellation propagates correctly."
 */
object VideoSegmentExtractor {

    /**
     * 利用 Android 原生 MediaExtractor 与 MediaMuxer 执行无损流复制切片。
     * 无需重新编解码，将指定起止毫秒时间段内的音视频封包提取并组装为标准的 MP4 容器文件，
     * 具备完整合法的 ftyp 与 moov box，确保在任意播放器中即时可播。
     */
    suspend fun extractSegment(
        context: Context,
        sourceUrlOrPath: String,
        destinationFile: File,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit,
        /** 给了就从它读，不再按 [sourceUrlOrPath] 打开。 */
        dataSource: MediaDataSource? = null,
    ): Result<Unit> = TsSegmentMuxer.muxIfHevcTs(context, sourceUrlOrPath, dataSource, destinationFile, startMs..endMs, onProgress)
        ?: copy(context, destinationFile, startMs..endMs, durationHintMs = 0L, onProgress) { extractor ->
        if (dataSource != null) {
            extractor.setDataSource(dataSource)
        } else if (sourceUrlOrPath.startsWith("http://") || sourceUrlOrPath.startsWith("https://")) {
            extractor.setDataSource(context, Uri.parse(sourceUrlOrPath), mapOf("User-Agent" to "Piko/1.0"))
        } else {
            extractor.setDataSource(sourceUrlOrPath)
        }
    }

    /**
     * 把本机的一个完整视频文件原样转封装成 MP4。转码档（HEVC 的 MPEG-TS）系统解析器读不出视频，走 [TsSegmentMuxer]；
     * HEVC 由 MediaMuxer 写成 hvc1（API 24 起支持，minSdk 是 26）。别的格式经 MediaExtractor，轨道格式里没有时长时
     * 进度按 [durationHintMs] 算。
     */
    suspend fun remux(
        context: Context,
        sourcePath: String,
        destinationFile: File,
        durationHintMs: Long,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = TsSegmentMuxer.muxIfHevcTs(context, sourcePath, null, destinationFile, null, onProgress)
        ?: copy(context, destinationFile, range = null, durationHintMs, onProgress) { it.setDataSource(sourcePath) }

    /** [range] 为 null 时整段复制，否则是毫秒区间，起点退到之前的关键帧。 */
    private suspend fun copy(
        context: Context,
        destinationFile: File,
        range: LongRange?,
        durationHintMs: Long,
        onProgress: (Float) -> Unit,
        open: (MediaExtractor) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val extractor = MediaExtractor()
            try {
                open(extractor)

                val tempFile = File(destinationFile.parentFile ?: context.cacheDir, "${destinationFile.name}.part")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                tempFile.parentFile?.mkdirs()

                val muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var muxerStarted = false
                try {
                    val trackCount = extractor.trackCount
                    val trackIndexMap = mutableMapOf<Int, Int>()

                    var maxTrackBufSize = 2 * 1024 * 1024

                    var videoTrackIdx = -1
                    var durationUs = durationHintMs * 1000L
                    for (i in 0 until trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            extractor.selectTrack(i)
                            val muxerTrack = muxer.addTrack(format)
                            trackIndexMap[i] = muxerTrack
                            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                                durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                            }
                            if (mime.startsWith("video/")) {
                                videoTrackIdx = i
                                val rotation = runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
                                if (rotation != 0) {
                                    runCatching { muxer.setOrientationHint(rotation) }
                                }
                            }

                            val bufSize = runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(0)
                            if (bufSize > maxTrackBufSize) {
                                maxTrackBufSize = bufSize
                            }
                        }
                    }

                    if (trackIndexMap.isEmpty()) {
                        error("源媒体中未找到受支持的视频或音频轨道")
                    }
                    // 系统解析器认不出视频编码时只交出音频（TS 里的 HEVC 即如此，见 TsDemuxer），
                    // 照样写下去就是一个只有声音的「视频」，明确失败
                    if (videoTrackIdx < 0) error("无法读取这个视频的画面，不支持它的封装或编码")

                    muxer.start()
                    muxerStarted = true

                    val startUs = range?.let { (it.first * 1000L).coerceAtLeast(0L) } ?: 0L
                    val endUs = range?.let { (it.last * 1000L).coerceAtLeast(startUs + 1000L) } ?: Long.MAX_VALUE
                    // 片段按区间长度报进度；整段按全片时长，不知道时长就只在结束时报一次
                    val progressSpanUs = if (range != null) endUs - startUs else durationUs

                    // 无损流抽取限制：必须从前序同步关键帧 (I 帧) 开始提取，确保视频首帧画面干净且音画同步。
                    // 整段复制不跳转，时间戳原样保留（TS 的时间已由 MediaExtractor 减去第一个 PTS）
                    val basePts = if (range != null) {
                        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        extractor.sampleTime.coerceAtLeast(0L)
                    } else {
                        0L
                    }

                    val buffer = ByteBuffer.allocateDirect(maxTrackBufSize)
                    val bufferInfo = MediaCodec.BufferInfo()
                    var reported = -1

                    while (coroutineContext.isActive) {
                        val trackIndex = extractor.sampleTrackIndex
                        if (trackIndex < 0) break // 读到流结尾

                        val sampleTime = extractor.sampleTime
                        if (sampleTime > endUs) {
                            // 主视频轨到达截止时间，或任一流超出 endUs 2 秒以上，停止提取
                            if (trackIndex == videoTrackIdx || sampleTime > endUs + 2_000_000L) {
                                break
                            }
                            extractor.advance()
                            continue
                        }

                        val muxerTrack = trackIndexMap[trackIndex]
                        if (muxerTrack != null) {
                            buffer.clear()
                            val sampleSize = extractor.readSampleData(buffer, 0)
                            if (sampleSize < 0) break

                            // 原样写入显示时间戳，不做单调化。HEVC 与 H.264 的 B 帧按解码顺序读出，
                            // 显示时间本来就前后交错，由 muxer 写成 ctts；强行递增会把帧的显示顺序
                            // 打乱，播放时一帧紧挨一帧只差 1ms（2026-09-23 实测）。早于起始关键帧的
                            // 前导帧引用上一组画面，本就解不出来，直接丢掉
                            val pts = sampleTime - basePts
                            if (pts < 0) {
                                extractor.advance()
                                continue
                            }

                            val flags = extractor.sampleFlags
                            bufferInfo.set(0, sampleSize, pts, flags)
                            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)

                            // 每多 1% 报一次：逐个样本报，任务表的状态流一秒要更新上千次
                            if (progressSpanUs > 0) {
                                val percent = ((sampleTime - startUs).toFloat() / progressSpanUs * 100).toInt().coerceIn(0, 100)
                                if (percent > reported) {
                                    reported = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }

                        extractor.advance()
                    }
                    // 循环因取消（暂停）退出时不能当作写完：收尾成文件再改名，暂停的片段就成了一个截短的「已完成」
                    coroutineContext.ensureActive()
                    onProgress(1f)
                } finally {
                    if (muxerStarted) {
                        runCatching { muxer.stop() }
                    }
                    runCatching { muxer.release() }
                }

                // 写入完整后再原子替换至最终目标文件
                if (tempFile.exists() && tempFile.length() > 1024L) {
                    if (destinationFile.exists()) {
                        destinationFile.delete()
                    }
                    if (!tempFile.renameTo(destinationFile)) {
                        tempFile.copyTo(destinationFile, overwrite = true)
                        tempFile.delete()
                    }
                } else {
                    tempFile.delete()
                    error("视频片段提取未生成有效数据")
                }
            } catch (e: Throwable) {
                val tempFile = File(destinationFile.parentFile ?: context.cacheDir, "${destinationFile.name}.part")
                runCatching { tempFile.delete() }
                throw e
            } finally {
                extractor.release()
            }
        }
    }

    /**
     * 校验媒体切片文件是否已完整写入并具备合法的容器及解码时长信息
     */
    fun isCompleteMediaFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            duration != null && (duration.toLongOrNull() ?: 0L) > 0L
        } catch (e: Exception) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }
}
