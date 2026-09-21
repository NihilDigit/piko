package dev.piko.desktop

import com.coremedia.iso.IsoFile
import com.coremedia.iso.boxes.TrackBox
import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.Mp4TrackImpl
import com.googlecode.mp4parser.authoring.Track
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.tracks.CroppedTrack
import dev.piko.shared.download.PikoSegmentDownloader
import dev.piko.shared.download.PikoSegmentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.FileOutputStream
import java.net.URI

/**
 * 桌面端 MP4 无损切片：mp4parser 纯 JVM 流复制，无需捆绑 ffmpeg。
 * 与 Android（MediaExtractor + MediaMuxer）同语义：不转码、起止对齐到视频关键帧、
 * 输出标准 MP4（ftyp + moov 即时可播）。http(s) 源先下载到临时文件再切。
 */
class DesktopPikoSegmentDownloader : PikoSegmentDownloader {
    override suspend fun extract(
        request: PikoSegmentRequest,
        onProgress: suspend (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(request.endMillis > request.startMillis) { "结束时间必须晚于开始时间" }
            var sourceFile = File(request.sourceUrl)
            var tempSource: File? = null
            try {
                if (request.sourceUrl.startsWith("http://") || request.sourceUrl.startsWith("https://")) {
                    tempSource = File.createTempFile("piko-segment-src", ".mp4")
                    downloadTo(request.sourceUrl, tempSource) { onProgress(it * DOWNLOAD_WEIGHT) }
                    sourceFile = tempSource
                } else {
                    require(sourceFile.isFile) { "找不到源文件：${request.sourceUrl}" }
                    onProgress(0f)
                }
                ensureActive()
                onProgress(DOWNLOAD_WEIGHT)
                clipMp4(sourceFile, request)
                onProgress(1f)
                Result.success(request.destinationPath)
            } finally {
                tempSource?.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun downloadTo(url: String, target: File, onProgress: suspend (Float) -> Unit) {
        val connection = URI(url).toURL().openConnection()
        connection.setRequestProperty("User-Agent", "Piko/1.0")
        connection.connect()
        val total = connection.contentLengthLong.takeIf { it > 0 }
        connection.getInputStream().use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    total?.let { onProgress(copied.toFloat() / it) }
                }
            }
        }
    }

    private fun clipMp4(sourceFile: File, request: PikoSegmentRequest) {
        val partFile = File(request.destinationPath + PART_SUFFIX)
        partFile.parentFile?.mkdirs()
        if (partFile.exists()) partFile.delete()

        IsoFile(FileDataSourceImpl(sourceFile)).use { iso ->
            val movie = Movie()
            for (trackBox in iso.movieBox.getBoxes(TrackBox::class.java)) {
                // 只切音视频轨，字幕/元数据轨跳过（与 Android 只 select 音视频一致）。
                val handler = trackBox.mediaBox.handlerBox.handlerType
                if (handler != VIDEO_HANDLER && handler != SOUND_HANDLER) continue
                val track = Mp4TrackImpl(handler, trackBox)
                val timescale = track.trackMetaData.timescale
                if (timescale <= 0) continue
                val from = startSample(track, request.startMillis, timescale)
                val to = endSample(track, request.endMillis, timescale)
                if (to > from) movie.addTrack(CroppedTrack(track, from, to))
            }
            check(movie.tracks.isNotEmpty()) { "源文件中没有可切片的音视频轨道" }
            val out = DefaultMp4Builder().build(movie)
            FileOutputStream(partFile).channel.use { channel -> out.writeContainer(channel) }
        }
        check(partFile.length() > 0) { "切片输出为空，源文件可能已损坏" }
        val destination = File(request.destinationPath)
        if (destination.exists()) destination.delete()
        check(partFile.renameTo(destination)) { "无法写入目标文件：${request.destinationPath}" }
    }

    /** 起始采样点：视频轨回退到上一个关键帧（stss 表是 1-based，转 0-based 再比较）。 */
    private fun startSample(track: Track, startMs: Long, timescale: Long): Long {
        val index = sampleIndexAtOrAfter(track, startMs, timescale)
        if (track.handler != VIDEO_HANDLER) return index
        val syncs = track.syncSamples ?: return index
        var snapped = index
        for (sampleNumber in syncs) {
            val zeroBased = sampleNumber - 1
            if (zeroBased <= index) snapped = zeroBased else break
        }
        return snapped
    }

    /** 结束采样点：第一个起始时间 >= endMs 的采样（开区间右端）。 */
    private fun endSample(track: Track, endMs: Long, timescale: Long): Long =
        sampleIndexAtOrAfter(track, endMs, timescale)
            .coerceAtMost(track.sampleDurations.size.toLong())

    private fun sampleIndexAtOrAfter(track: Track, timeMs: Long, timescale: Long): Long {
        val target = timeMs * timescale / 1000L
        var elapsed = 0L
        track.sampleDurations.forEachIndexed { i, duration ->
            if (elapsed >= target) return i.toLong()
            elapsed += duration
        }
        return track.sampleDurations.size.toLong()
    }

    private companion object {
        const val DOWNLOAD_WEIGHT = 0.5f
        const val PART_SUFFIX = ".part"
        const val VIDEO_HANDLER = "vide"
        const val SOUND_HANDLER = "soun"
    }
}
