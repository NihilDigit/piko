package dev.piko.download

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 片段截取与转码档转封装在真机解析器上的结果。样片与桌面端的转封装测试共用（testdata/media，见 generate.sh），
 * 关键帧每 2 秒一个。MediaExtractor 与 MediaMuxer 的行为随系统版本与厂商不同，JVM 上测不到。
 */
@RunWith(AndroidJUnit4::class)
class VideoSegmentExtractorSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    // PikPak 转码档的样子：TS 里带 B 帧的 HEVC 与 ADTS 的 AAC
    @Test
    fun remuxTranscodeTsToMp4() = runBlocking {
        val output = output("remux.mp4")
        val progress = mutableListOf<Float>()
        // 系统的 MediaExtractor 读这个源只认出音频（PKG110、Android 16 实测），视频轨要靠 TsDemuxer
        val source = asset("transcode-hevc-aac.ts")

        val result = VideoSegmentExtractor.remux(context, source.path, output, durationHintMs = 10_000) { progress += it }

        assertTrue("转封装失败：${result.exceptionOrNull()}", result.isSuccess)
        val tracks = tracksOf(output)
        assertEquals(listOf("video/hevc", "audio/mp4a-latm"), tracks.map { it.getString(MediaFormat.KEY_MIME) })
        // QuickTime 与系统播放器只认 hvc1 的 HEVC
        assertTrue("HEVC 没有写成 hvc1", output.readBytes().asList().windowed(4).any { it == "hvc1".toByteArray().asList() })
        assertEquals(10.0, seconds(tracks.first()), 0.2)
        assertEquals(1f, progress.last())
    }

    // 关键帧在 0、2、4 秒……要 5 到 8.5 秒，起点应退到 4 秒，画面长约 4.5 秒；退错一个 GOP 就差出 2 秒
    @Test
    fun extractMkvStartsAtPreviousKeyframe() = runBlocking {
        val output = output("clip-mkv.mp4")

        val result = VideoSegmentExtractor.extractSegment(context, asset("keyframes-h264-aac.mkv").path, output, 5000, 8500, {})

        assertTrue("截取失败：${result.exceptionOrNull()}", result.isSuccess)
        val video = tracksOf(output).first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val length = seconds(video)
        assertTrue("起点没有落在之前的关键帧上：画面长 $length 秒", length in 4.3..4.9)
    }

    @Test
    fun extractTsStartsAtPreviousKeyframe() = runBlocking {
        val output = output("clip-ts.mp4")

        val result = VideoSegmentExtractor.extractSegment(context, asset("transcode-hevc-aac.ts").path, output, 5000, 8500, {})

        assertTrue("截取失败：${result.exceptionOrNull()}", result.isSuccess)
        val video = tracksOf(output).first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        val length = seconds(video)
        assertTrue("起点没有落在之前的关键帧上：画面长 $length 秒", length in 4.3..4.9)
    }

    private fun asset(name: String): File = File(context.cacheDir, "segment-smoke/$name").also { file ->
        file.parentFile?.mkdirs()
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun output(name: String): File = File(context.cacheDir, "segment-smoke/$name").also {
        it.parentFile?.mkdirs()
        it.delete()
    }

    private fun tracksOf(file: File): List<MediaFormat> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return List(extractor.trackCount) { extractor.getTrackFormat(it) }
        } finally {
            extractor.release()
        }
    }

    private fun seconds(format: MediaFormat): Double = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
}
