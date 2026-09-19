package dev.piko.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import dev.piko.util.runSuspendCatching
import kotlinx.coroutines.Dispatchers
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val extractor = MediaExtractor()
            try {
                if (sourceUrlOrPath.startsWith("http://") || sourceUrlOrPath.startsWith("https://")) {
                    extractor.setDataSource(context, Uri.parse(sourceUrlOrPath), mapOf("User-Agent" to "Piko/1.0"))
                } else {
                    extractor.setDataSource(sourceUrlOrPath)
                }

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
                    for (i in 0 until trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            extractor.selectTrack(i)
                            val muxerTrack = muxer.addTrack(format)
                            trackIndexMap[i] = muxerTrack
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

                    muxer.start()
                    muxerStarted = true

                    val startUs = (startMs * 1000L).coerceAtLeast(0L)
                    val endUs = (endMs * 1000L).coerceAtLeast(startUs + 1000L)
                    val durationUs = (endUs - startUs).coerceAtLeast(1L)

                    // 无损流抽取限制：必须从前序同步关键帧 (I 帧) 开始提取，确保视频首帧画面干净且音画同步
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val basePts = extractor.sampleTime.coerceAtLeast(0L)

                    val buffer = ByteBuffer.allocateDirect(maxTrackBufSize)
                    val bufferInfo = MediaCodec.BufferInfo()
                    val lastPtsMap = mutableMapOf<Int, Long>()

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

                            val rawAdjustedPts = (sampleTime - basePts).coerceAtLeast(0L)

                            // 保证单轨道内时间戳单调非递减，规避系统 Muxer 异常
                            val lastPts = lastPtsMap.getOrDefault(trackIndex, -1L)
                            val safePts = if (rawAdjustedPts > lastPts) rawAdjustedPts else lastPts + 1000L
                            lastPtsMap[trackIndex] = safePts

                            val flags = extractor.sampleFlags
                            bufferInfo.set(0, sampleSize, safePts, flags)
                            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)

                            val progress = ((sampleTime - startUs).toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }

                        extractor.advance()
                    }
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
