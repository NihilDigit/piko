package dev.piko.desktop

import com.coremedia.iso.IsoFile
import com.coremedia.iso.boxes.TrackBox
import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Mp4TrackImpl
import dev.piko.shared.download.PikoSegmentRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * 真机集成测试：下 W3C Sintel 样片，切 2s~5s，验证输出是合法 MP4。
 * 需要网络；CI Windows runner 可直连。失败即大声失败，不静默跳过。
 */
class DesktopPikoSegmentDownloaderTest {

    @Test
    fun clip_realMp4_producesPlayableSegment() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "piko-segment-test").also { it.mkdirs() }
        val source = File(dir, "sintel-trailer.mp4")
        if (!source.isFile) {
            URI(SAMPLE_URL).toURL().openStream().use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
        }
        assertTrue(source.length() > 0, "样片下载失败")

        val destination = File(dir, "sintel-clip.mp4").also { if (it.exists()) it.delete() }
        val progress = mutableListOf<Float>()
        val result = DesktopPikoSegmentDownloader().extract(
            PikoSegmentRequest(
                sourceUrl = source.absolutePath,
                destinationPath = destination.absolutePath,
                fileName = destination.name,
                startMillis = 2000L,
                endMillis = 5000L,
            ),
        ) { progress.add(it) }

        assertTrue(result.isSuccess, "切片失败：${result.exceptionOrNull()?.message}")
        assertTrue(destination.isFile && destination.length() > 0, "输出文件为空")
        assertTrue(progress.isNotEmpty() && progress.last() == 1f, "进度回调异常：$progress")

        // 输出必须能被重新解析，且含视频轨，时长约 3s（关键帧对齐允许前后浮动）。
        IsoFile(FileDataSourceImpl(destination)).use { iso ->
            val trackBoxes = iso.movieBox.getBoxes(TrackBox::class.java)
            val handlers = trackBoxes.map { it.mediaBox.handlerBox.handlerType }
            assertContains(handlers, "vide", "输出丢了视频轨：$handlers")
            val videoBox = trackBoxes.first { it.mediaBox.handlerBox.handlerType == "vide" }
            val video = Mp4TrackImpl("vide", videoBox)
            val timescale = video.trackMetaData.timescale
            val durationMs = video.sampleDurations.sum() * 1000L / timescale
            assertTrue(durationMs in 1500L..6000L, "输出时长异常：${durationMs}ms")
        }
    }

    @Test
    fun clip_invalidRange_failsLoudly() = runBlocking {
        val result = DesktopPikoSegmentDownloader().extract(
            PikoSegmentRequest("/nonexistent.mp4", "/tmp/nope.mp4", "nope.mp4", 5000L, 2000L),
        ) {}
        assertTrue(result.isFailure, "非法区间必须失败")
    }

    private companion object {
        const val SAMPLE_URL = "https://media.w3.org/2010/05/sintel/trailer.mp4"
    }
}
