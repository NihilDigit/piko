package dev.piko.desktop

import dev.piko.desktop.media.Ffmpeg
import dev.piko.shared.download.PikoRemuxRequest
import dev.piko.shared.download.PikoSegmentRequest
import dev.piko.shared.media.RandomAccessMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.JarURLConnection
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 转封装与片段截取，经 FFM 调 mpv 运行库里的 FFmpeg。样片在 testdata/media（生成方式见 generate.sh），
 * 关键帧每 2 秒一个。FFmpeg 从测试类路径上的 mpv 运行库 jar 里解出；Windows 上必须载得起来，
 * 别的系统载不起来时跳过（CI 只在 Windows 上跑这个模块的测试）。
 */
class DesktopPikoSegmentDownloaderTest {
    private val downloader by lazy { DesktopPikoSegmentDownloader(::testFfmpeg) }

    // PikPak 转码档的样子：TS 里带 B 帧的 HEVC 与 ADTS 的 AAC，时间戳从 1.46 秒起
    @Test
    fun remux_transcodeTs_producesFaststartHvc1Mp4() = runBlocking {
        val destination = output("remux.mp4")
        val progress = mutableListOf<Float>()

        val result = downloader.remux(PikoRemuxRequest(sample("transcode-hevc-aac.ts").absolutePath, destination.absolutePath)) { progress += it }

        assertTrue(result.isSuccess, "转封装失败：${result.exceptionOrNull()}")
        val mp4 = Mp4(destination)
        assertEquals(listOf("ftyp", "moov"), mp4.topLevel.take(2), "moov 不在 mdat 之前：${mp4.topLevel}")
        // hev1 的 HEVC QuickTime 与系统播放器不认；mp4a 里没有 esds 说明 ADTS 头没换成 AudioSpecificConfig
        assertEquals(listOf("hvc1", "mp4a"), mp4.sampleEntries.map { it.type }, "样本描述不对")
        assertContains(mp4.sampleEntries.first { it.type == "mp4a" }.children, "esds")
        assertEquals(10.0, mp4.durationSeconds, 0.15, "时长与样片不一致")
        assertEquals(10.0, mp4.videoSeconds, 0.15, "画面时长与样片不一致")
        assertEquals(1f, progress.last(), "进度没有报到头：$progress")
        assertFalse(File(destination.path + ".part").exists())
    }

    // 关键帧在 0、2、4、6 秒……要 5 到 8.5 秒，起点应退到 4 秒，长约 4.5 秒；退到 2 秒或进到 6 秒都差出一个 GOP
    @Test
    fun extract_mkv_startsAtPreviousKeyframe() = runBlocking {
        val source = CountingSource(sample("keyframes-h264-aac.mkv"))
        val destination = output("clip-mkv.mp4")
        val progress = mutableListOf<Float>()

        val result = downloader.extract(
            PikoSegmentRequest("", destination.absolutePath, destination.name, 5000L, 8500L, openRandomAccess = { source }),
        ) { progress += it }

        assertTrue(result.isSuccess, "截取失败：${result.exceptionOrNull()}")
        assertTrue(source.reads > 0, "没有经按偏移读的来源读取")
        val mp4 = Mp4(destination)
        assertEquals(listOf("avc1", "mp4a"), mp4.sampleEntries.map { it.type })
        assertTrue(mp4.videoSeconds in CLIP_SECONDS, "起点没有落在之前的关键帧上：画面长 ${mp4.videoSeconds} 秒")
        assertEquals(1f, progress.last())
    }

    // TS 没有索引，FFmpeg 定位时不保证落在关键帧上。片头 1.46 秒、关键帧在其后 0.02 秒起每 2 秒，起点应在 4.02 秒
    @Test
    fun extract_ts_startsAtPreviousKeyframe() = runBlocking {
        val destination = output("clip-ts.mp4")

        val result = downloader.extract(
            PikoSegmentRequest(sample("transcode-hevc-aac.ts").absolutePath, destination.absolutePath, destination.name, 5000L, 8500L),
        ) {}

        assertTrue(result.isSuccess, "截取失败：${result.exceptionOrNull()}")
        val seconds = Mp4(destination).videoSeconds
        assertTrue(seconds in CLIP_SECONDS, "起点没有落在之前的关键帧上：画面长 $seconds 秒")
    }

    // 读源的回调里抛出的异常若漏回原生代码，整个 JVM 当场崩掉；应当变成这次截取的失败原因
    @Test
    fun extract_sourceFailing_failsWithItsError() = runBlocking {
        val file = sample("keyframes-h264-aac.mkv")
        val failing = object : RandomAccessMediaSource by CountingSource(file) {
            override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("网络中断")
        }
        val destination = output("failing.mp4")

        val result = downloader.extract(
            PikoSegmentRequest("", destination.absolutePath, destination.name, 5000L, 8500L, openRandomAccess = { failing }),
        ) {}

        assertIs<IOException>(result.exceptionOrNull(), "失败原因不对：${result.exceptionOrNull()}")
        assertFalse(File(destination.path + ".part").exists(), "失败后留下了 .part")
    }

    // 暂停即取消协程：卡在读源上的那一次也要停下，不留 .part
    @Test
    fun extract_cancelledWhileReading_stopsAndCleansUp() = runBlocking {
        val file = sample("keyframes-h264-aac.mkv")
        val reading = CompletableDeferred<Unit>()
        val stalled = object : RandomAccessMediaSource by CountingSource(file) {
            override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                reading.complete(Unit)
                awaitCancellation()
            }
        }
        val destination = output("cancelled.mp4")

        val task = async(Dispatchers.Default) {
            downloader.extract(PikoSegmentRequest("", destination.absolutePath, destination.name, 5000L, 8500L, openRandomAccess = { stalled })) {}
        }
        withTimeout(10_000) { reading.await() }
        task.cancel()
        val outcome = runCatching { withTimeout(10_000) { task.await() } }

        assertIs<CancellationException>(outcome.exceptionOrNull(), "取消没有生效：$outcome")
        assertFalse(File(destination.path + ".part").exists(), "取消后留下了 .part")
    }

    // 换不进目标时不留 .part，目标处原有的东西原样留着
    @Test
    fun clip_unreplaceableDestination_leavesNoPartAndKeepsOriginal() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "piko-segment-test").also { it.mkdirs() }
        val source = sample("keyframes-h264-aac.mkv")
        val destination = File(dir, "occupied.mp4").also { it.deleteRecursively(); it.mkdirs() }
        val original = File(destination, "keep.txt").apply { writeText("keep") }

        val result = downloader.extract(
            PikoSegmentRequest(source.absolutePath, destination.absolutePath, destination.name, 2000L, 5000L),
        ) {}

        assertTrue(result.isFailure, "目标是非空目录时必须失败")
        assertFalse(File(destination.path + ".part").exists(), "失败后留下了 .part")
        assertTrue(original.isFile, "原有内容被删了")
        destination.deleteRecursively()
        Unit
    }

    @Test
    fun clip_existingDestination_isReplaced() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "piko-segment-test").also { it.mkdirs() }
        val source = sample("keyframes-h264-aac.mkv")
        val destination = File(dir, "replace.mp4").apply { writeText("old") }

        val result = downloader.extract(
            PikoSegmentRequest(source.absolutePath, destination.absolutePath, destination.name, 2000L, 5000L),
        ) {}

        assertTrue(result.isSuccess, "切片失败：${result.exceptionOrNull()?.message}")
        assertTrue(destination.length() > 3, "目标没有被替换")
        assertFalse(File(destination.path + ".part").exists())
        destination.delete()
        Unit
    }

    @Test
    fun clip_invalidRange_failsLoudly() = runBlocking {
        val result = DesktopPikoSegmentDownloader().extract(
            PikoSegmentRequest("/nonexistent.mp4", "/tmp/nope.mp4", "nope.mp4", 5000L, 2000L),
        ) {}
        assertTrue(result.isFailure, "非法区间必须失败")
    }

    private companion object {
        /**
         * 要 5 到 8.5 秒、起点退到 4 秒左右的关键帧时画面的长度。终点照 ffmpeg 的流复制按解码时间截，带 B 帧时
         * 显示时间会越过终点几帧（样片最多 3 帧，0.12 秒）；起点多退或少退一个 GOP 都差出 2 秒。
         */
        val CLIP_SECONDS = 4.4..4.9
    }

    private fun sample(name: String): File = File(System.getProperty("piko.testdata"), name).also {
        assertTrue(it.isFile, "缺少样片 $it")
    }

    private fun output(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "piko-segment-test").also { it.mkdirs() }.resolve(name).also { it.delete() }

    private class CountingSource(file: File) : RandomAccessMediaSource {
        private val input = RandomAccessFile(file, "r")
        var reads = 0
            private set
        override val size: Long = input.length()

        override suspend fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            reads++
            input.seek(position)
            return input.read(buffer, offset, length)
        }

        override fun close() = input.close()
    }

    /** 只解析断言要用的几个盒子：顶层顺序、样本描述、mvhd 里的时长。 */
    private class Mp4(file: File) {
        class Box(val type: String, val payload: Int, val end: Int, val children: List<String> = emptyList())

        private val bytes = file.readBytes()

        val topLevel: List<String> = children(0, bytes.size).map { it.type }

        val sampleEntries: List<Box> = find(listOf("moov", "trak", "mdia", "minf", "stbl", "stsd")).flatMap { stsd ->
            // stsd 的负载先是 4 字节版本与标志、4 字节条目数；音频条目自身的字段占 28 字节，之后才是 esds 等子盒子
            children(stsd.payload + 8, stsd.end).map { entry ->
                Box(entry.type, entry.payload, entry.end, children(entry.payload + 28, entry.end).map { it.type })
            }
        }

        val durationSeconds: Double = duration(find(listOf("moov", "mvhd")).single())

        /**
         * 视频轨自身的时长（mdhd，不含编辑表）。整个文件的时长被最长的音轨撑着，视频起点退错了一个 GOP 也看不出来，
         * 要看画面本身有多长。
         */
        val videoSeconds: Double = find(listOf("moov", "trak")).single { trak ->
            find(listOf("mdia", "hdlr"), trak).single().let { String(bytes, it.payload + 8, 4, Charsets.ISO_8859_1) } == "vide"
        }.let { trak -> duration(find(listOf("mdia", "mdhd"), trak).single()) }

        // mvhd 与 mdhd 开头相同：版本 0 是两个 4 字节时间、4 字节时间刻度、4 字节时长，版本 1 的时间与时长是 8 字节
        private fun duration(box: Box): Double {
            val version = bytes[box.payload].toInt()
            val timescale = u32(box.payload + if (version == 1) 20 else 12)
            val duration = if (version == 1) u64(box.payload + 24) else u32(box.payload + 16)
            return duration.toDouble() / timescale
        }

        private fun find(path: List<String>, root: Box = Box("", 0, bytes.size)): List<Box> =
            path.fold(listOf(root)) { boxes, type ->
                boxes.flatMap { children(it.payload, it.end) }.filter { it.type == type }
            }

        private fun children(from: Int, to: Int): List<Box> = buildList {
            var position = from
            while (position + 8 <= to) {
                var size = u32(position)
                var header = 8
                if (size == 1L) {
                    size = u64(position + 8)
                    header = 16
                } else if (size == 0L) {
                    size = (to - position).toLong()
                }
                if (size < header) break
                add(Box(String(bytes, position + 4, 4, Charsets.ISO_8859_1), position + header, (position + size).toInt()))
                position += size.toInt()
            }
        }

        private fun u32(at: Int): Long = (0 until 4).fold(0L) { value, i -> value shl 8 or (bytes[at + i].toLong() and 0xff) }

        private fun u64(at: Int): Long = (0 until 8).fold(0L) { value, i -> value shl 8 or (bytes[at + i].toLong() and 0xff) }
    }
}

/**
 * 测试进程没有应用资源目录，FFmpeg 从类路径上的 mpv 运行库 jar 里解到临时目录，整个 JVM 只解一次。
 * Windows 上载不起来就是失败；别的系统上的 mpv 运行库没在这台机器上验过，载不起来时跳过。
 */
internal fun testFfmpeg(): Ffmpeg {
    val result = testFfmpegResult
    if (!System.getProperty("os.name").startsWith("Windows")) {
        assumeTrue("本机载不起 mpv 运行库里的 FFmpeg：${result.exceptionOrNull()}", result.isSuccess)
    }
    return result.getOrThrow()
}

private val testFfmpegResult: Result<Ffmpeg> by lazy {
    runCatching {
        val os = System.getProperty("os.name")
        val platform = when {
            os.startsWith("Windows") -> "windows"
            os.startsWith("Mac") -> "macos"
            else -> "linux"
        } + if (System.getProperty("os.arch") == "aarch64") "-arm64" else "-x64"
        val marker = ClassLoader.getSystemResource("mpv-natives-$platform.txt") ?: error("类路径上没有 $platform 的 mpv 运行库")
        // 另开一份 JarFile：连接里缓存的那份是共享的，关掉它会连累别处
        val jar = JarFile(File((marker.openConnection() as JarURLConnection).jarFileURL.toURI()))
        val directory = File(System.getProperty("java.io.tmpdir"), "piko-ffmpeg-test/${File(jar.name).nameWithoutExtension}").also { it.mkdirs() }
        jar.use {
            for (entry in jar.entries()) {
                if (entry.isDirectory || entry.name.contains('/')) continue
                val target = directory.resolve(entry.name)
                if (target.length() == entry.size) continue
                jar.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        Ffmpeg.load(directory)
    }
}
