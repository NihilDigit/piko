package dev.piko.desktop

import dev.piko.desktop.media.Ffmpeg
import dev.piko.desktop.media.probeLocalVideo
import dev.piko.shared.data.LocalLocation
import dev.piko.shared.data.LocalMount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.*

class LocalVideoProbeTest {
    private fun temporary(name: String): File {
        val root = File(assertNotNull(System.getProperty("piko.testdata"))).parentFile.parentFile.resolve("desktopApp/build/local-mount-tests").apply { mkdirs() }
        return Files.createTempDirectory(root.toPath(), name).toFile()
    }
    @Test fun bundledFfmpegProbesAndDecodesAllCommittedSampleFormats() {
        val samples = File(assertNotNull(System.getProperty("piko.testdata")))
        val libraries = samples.parentFile.parentFile.resolve("desktopApp/build/appResources/common/mpv")
        org.junit.Assume.assumeTrue("此验证需要本机随包原生库", libraries.isDirectory)
        val ff = Ffmpeg.load(libraries)
        val output = temporary("probe")
        for (name in listOf("control-mpeg4-aac.mp4", "keyframes-h264-aac.mkv", "transcode-hevc-aac.ts", "mpeg4asp-mp3.avi", "msmpeg4v3-mp2.avi", "wmv2-wmav2.wmv", "rv20-ra144.rm")) {
            val cover = output.resolve("$name.jpg")
            val metadata = probeLocalVideo(samples.resolve(name), cover, ff)
            assertEquals(176, metadata.width, name)
            assertEquals(144, metadata.height, name)
            assertTrue(metadata.durationMs >= 3_000, "$name duration ${metadata.durationMs}")
            if (name == "rv20-ra144.rm") {
                // 随包 avcodec 只含 rv30/rv40，没有 rv20；仍应保留尺寸和时长。
                assertEquals("", metadata.thumbnail)
                continue
            }
            assertTrue(cover.isFile, "$name thumbnail")
            val image = assertNotNull(ImageIO.read(cover), name)
            assertTrue(image.width in 1..480, name)
            assertEquals(cover.absolutePath, metadata.thumbnail)
        }
        val rotated = samples.resolve("control-mpeg4-aac.mp4").readBytes()
        val tkhd = rotated.indices.first { at -> at + 4 <= rotated.size && rotated.copyOfRange(at, at + 4).contentEquals("tkhd".toByteArray()) }
        val matrix = intArrayOf(0, 65536, 0, -65536, 0, 0, 0, 0, 1 shl 30)
        val buffer = java.nio.ByteBuffer.wrap(rotated).order(java.nio.ByteOrder.BIG_ENDIAN)
        matrix.forEachIndexed { index, value -> buffer.putInt(tkhd + 44 + index * 4, value) }
        val source = output.resolve("rotated.mp4").apply { writeBytes(rotated) }
        val cover = output.resolve("rotated.jpg")
        val metadata = probeLocalVideo(source, cover, ff)
        assertEquals(144, metadata.width)
        assertEquals(176, metadata.height)
        val image = assertNotNull(ImageIO.read(cover))
        assertEquals(144, image.width)
        assertEquals(176, image.height)
    }

    private fun nativeLibraries(): Ffmpeg {
        val samples = File(assertNotNull(System.getProperty("piko.testdata")))
        val libraries = samples.parentFile.parentFile.resolve("desktopApp/build/appResources/common/mpv")
        org.junit.Assume.assumeTrue("此验证需要本机随包原生库", libraries.isDirectory)
        return Ffmpeg.load(libraries)
    }

    @org.junit.Test(timeout = 10_000L)
    fun disguisedConcatCannotReadAnExternalMediaFile() {
        val ff = nativeLibraries()
        val root = temporary("sandbox")
        val outside = temporary("external").resolve("external.mp4")
        val sample = File(assertNotNull(System.getProperty("piko.testdata"))).resolve("control-mpeg4-aac.mp4")
        sample.copyTo(outside)
        val source = root.resolve("disguised.mp4").apply {
            writeText("ffconcat version 1.0\nfile '${outside.absolutePath.replace('\\', '/')}'\n")
        }
        val cover = root.resolve("cover.jpg")
        val result = runCatching { probeLocalVideo(source, cover, ff) }
        assertTrue(result.isFailure || (result.getOrThrow().width == 0 && result.getOrThrow().thumbnail.isEmpty()))
        assertFalse(cover.exists())
        result.exceptionOrNull()?.let { error ->
            assertFalse(error.message.orEmpty().contains(outside.absolutePath))
        }
    }

    @org.junit.Test(timeout = 10_000L)
    fun cancelledProbeStopsWithoutPublishingAThumbnail() {
        val ff = nativeLibraries()
        val source = File(assertNotNull(System.getProperty("piko.testdata"))).resolve("control-mpeg4-aac.mp4")
        val cover = temporary("cancelled").resolve("cover.jpg")
        val start = System.nanoTime()
        assertFails { probeLocalVideo(source, cover, ff, cancelled = { true }) }
        assertTrue(System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(2))
        assertFalse(cover.exists())
    }

    @org.junit.Test(timeout = 10_000L)
    fun expiredDeadlineInterruptsNativeRead() {
        val ff = nativeLibraries()
        val source = File(assertNotNull(System.getProperty("piko.testdata"))).resolve("control-mpeg4-aac.mp4")
        val cover = temporary("timeout").resolve("cover.jpg")
        var checks = 0
        assertFails {
            probeLocalVideo(source, cover, ff, timeoutMs = 10L, cancelled = {
                // 第二次检查来自 AVIO 读回调；不请求取消，仅使截止时间确定过期。
                if (++checks == 2) Thread.sleep(30)
                false
            })
        }
        assertFalse(cover.exists())
    }

    @Test fun nioListsNestedFilesAndRefusesSymlinkEscapesWithoutChangingTheSource() = runBlocking {
        val root = temporary("root").toPath()
        val nested = Files.createDirectories(root.resolve("season"))
        val movie = Files.write(nested.resolve("movie.mkv"), byteArrayOf(1, 2, 3))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val access = DesktopLocalFileAccess(scope, {}, temporary("cache"))
            val mount = LocalMount("mount-1", "account", "host", root.toString(), "Local", 0)
            val folder = access.list(mount, mount.location).single()
            assertTrue(folder.isFolder)
            val file = access.list(mount, assertNotNull(LocalLocation.of(folder.id))).single()
            assertEquals("movie.mkv", file.name)
            assertEquals(3L, file.sizeBytes)
            assertEquals(movie.toRealPath().toString(), access.playbackPath(mount, assertNotNull(LocalLocation.of(file.id))))
            assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(movie).toList())
            val outside = temporary("outside").toPath()
            val link = root.resolve("outside-link")
            if (runCatching { Files.createSymbolicLink(link, outside) }.isSuccess) {
                assertFalse(access.list(mount, mount.location).any { it.name == "outside-link" })
                assertFailsWith<IllegalStateException> { access.playbackPath(mount, mount.location.child("outside-link", true)) }
            }
        } finally { scope.cancel() }
    }
}
