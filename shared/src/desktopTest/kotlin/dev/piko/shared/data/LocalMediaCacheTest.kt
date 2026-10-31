package dev.piko.shared.data

import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class LocalMediaCacheTest {
    private fun temporary() = Files.createTempDirectory(File("build/local-media-tests").apply { mkdirs() }.toPath(), "cache-").toFile()
    private fun video(modified: String = "2026-01-01T00:00:00Z") = FileStat(
        id = LocalLocation("mount-1", listOf("private movie.mkv"), false).id,
        name = "private movie.mkv", parentId = LocalLocation("mount-1").id, size = "100", modifiedTime = modified,
    )
    @Test fun cachedMetadataPersistsAndFingerprintChangesReprobe() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val directory = temporary()
            val calls = AtomicInteger()
            val cache = LocalMediaCache(directory, scope) {}
            val first = cache.probe(video()) { calls.incrementAndGet(); LocalMediaMetadata(1920, 1080, 120_500, "file:///cache/cover.jpg") }
            assertEquals("120.5", first.params["duration"])
            assertEquals("1920", first.params["width"])
            val reloaded = LocalMediaCache(directory, scope) {}
            assertEquals(first, reloaded.probe(video()) { error("缓存命中时不能重复探测") })
            reloaded.probe(video("2026-01-02T00:00:00Z")) { calls.incrementAndGet(); LocalMediaMetadata(1280, 720, 100_000) }
            assertEquals(2, calls.get())
            assertTrue(directory.listFiles().orEmpty().all { !it.name.contains("private") && !it.name.contains("piko-local") })
        } finally { scope.cancel() }
    }
    @Test fun failedProbesAreNegativelyCachedAndDoNotCauseRefreshLoops() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = LocalMediaCache(temporary(), scope) {}
            assertEquals(video(), cache.probe(video()) { error("不可读") })
            assertEquals(video(), cache.probe(video()) { error("不能立即重试失败条目") })
        } finally { scope.cancel() }
    }
    @Test fun uncachedDirectoryRowsReturnImmediatelyAndProbeConcurrencyIsBounded() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = AtomicInteger()
            val maximum = AtomicInteger()
            val finished = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val cache = LocalMediaCache(temporary(), scope) { finished.incrementAndGet() }
            for (index in 0 until 8) {
                val file = video().copy(id = LocalLocation("mount-1", listOf("$index.mkv"), false).id)
                assertEquals(file, cache.enrich(file) {
                    val active = entered.incrementAndGet()
                    maximum.accumulateAndGet(active, ::maxOf)
                    release.await()
                    entered.decrementAndGet()
                    LocalMediaMetadata(640, 360, 90_000)
                })
            }
            withTimeout(5_000) { while (entered.get() < 2) delay(5) }
            assertEquals(2, maximum.get())
            release.complete(Unit)
            withTimeout(5_000) { while (finished.get() < 8) delay(5) }
            assertEquals(2, maximum.get())
        } finally { scope.cancel() }
    }
    @Test fun exifQuarterTurnSwapsImageDimensionsWithoutPixelDecoding() {
        fun jpeg(orientation: Int): ByteArray {
            val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
            tiff.putShort(0x4949).putShort(42).putInt(8).putShort(1)
            tiff.putShort(0x112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(0)
            val exif = byteArrayOf(69, 120, 105, 102, 0, 0) + tiff.array()
            return byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0, (exif.size + 2).toByte()) + exif
        }
        assertTrue(localImageSwapsAxes(jpeg(6).inputStream()))
        assertTrue(localImageSwapsAxes(jpeg(8).inputStream()))
        assertFalse(localImageSwapsAxes(jpeg(1).inputStream()))
        assertFalse(localImageSwapsAxes(byteArrayOf(1, 2, 3).inputStream()))
    }
}
