package dev.piko.shared.media.cache

import dev.piko.download.DownloadStatus
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.testing.FakePikPakCloud
import dev.piko.shared.smoke.DirectoryStorage
import dev.piko.shared.smoke.MemoryPreferences
import dev.piko.shared.smoke.awaitUntil
import dev.piko.shared.smoke.smoke
import io.github.nihildigit.pikpak.FileStat
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedDownloadSmokeTest {
    @Test
    fun `playback and download share every network block and playback survives completion`() = smoke { scope ->
        val payload = ByteArray(8 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val cloud = FakePikPakCloud(payload, blockDelayMillis = 10)
        val root = Files.createTempDirectory("piko-shared").toFile()
        val repository = PikoMediaRepository(cloud.provider)
        val coordinator = PikoDownloadCoordinator(cloud.provider, MemoryPreferences(), DirectoryStorage(root), scope,
            mediaRepository = repository)
        val source = repository.openRandomAccess("f1").getOrThrow()
        try {
            val bytes = ByteArray(4096)
            source.readAt(0, bytes, 0, bytes.size)
            assertContentEquals(payload.copyOfRange(0, bytes.size), bytes)
            coordinator.enqueue(FileStat(id = "f1", name = "movie.mkv", size = payload.size.toString(), hash = "GCIDFAKE0001"))
            awaitUntil("共享缓存下载完成", 20_000) { coordinator.tasks.value["f1"]?.status == DownloadStatus.COMPLETED }
            assertContentEquals(payload, root.resolve("movie.mkv").readBytes())
            assertEquals(payload.size.toLong(), cloud.deliveredCdnBytes.get(), "播放与下载不能重复请求同一个块")
            var filled = 0
            while (filled < bytes.size) {
                filled += source.readAt(payload.size - bytes.size.toLong() + filled, bytes, filled, bytes.size - filled)
            }
            assertContentEquals(payload.copyOfRange(payload.size - bytes.size, payload.size), bytes)
            assertEquals(payload.size.toLong(), cloud.deliveredCdnBytes.get())
        } finally {
            source.close()
        }
    }

    @Test
    fun `a paused sparse download supplies playback and remains resumable after playback closes`() = smoke { scope ->
        val payload = ByteArray(12 * 1024 * 1024) { (it % 251).toByte() }
        val cloud = FakePikPakCloud(payload, blockDelayMillis = 100)
        val root = Files.createTempDirectory("piko-partial").toFile()
        val repository = PikoMediaRepository(cloud.provider)
        val coordinator = PikoDownloadCoordinator(cloud.provider, MemoryPreferences(), DirectoryStorage(root), scope,
            mediaRepository = repository)
        coordinator.enqueue(FileStat(id = "f1", name = "movie.mkv", size = payload.size.toString(), hash = "GCIDFAKE0001"))
        awaitUntil("已下载部分块") {
            coordinator.tasks.value["f1"]?.let { it.downloadedBytes > 0 && it.downloadedBytes < payload.size } == true
        }
        coordinator.pauseDownload("f1")
        val path = coordinator.tasks.value.getValue("f1").cachePath!!
        awaitUntil("暂停数据已持久化") { java.io.File("$path.blocks").exists() }
        val metadataCalls = cloud.detailCalls.get()
        repository.preparePlayback("f1").getOrThrow().use { playback ->
            assertTrue(playback.proxyUrl != null)
        }
        assertEquals(metadataCalls, cloud.detailCalls.get(), "已有下载元数据的播放无需重取文件详情")
        repository.openRandomAccess("f1").getOrThrow().use { source ->
            val bytes = ByteArray(4096)
            source.readAt(5L * 1024 * 1024, bytes, 0, bytes.size)
            assertContentEquals(payload.copyOfRange(5 * 1024 * 1024, 5 * 1024 * 1024 + bytes.size), bytes)
        }
        coordinator.startDownload("f1")
        awaitUntil("播放后仍可继续下载", 20_000) { coordinator.tasks.value["f1"]?.status == DownloadStatus.COMPLETED }
        assertContentEquals(payload, root.resolve("movie.mkv").readBytes())
        assertTrue(cloud.deliveredCdnBytes.get() <= payload.size + 4L * 1024 * 1024)
    }
}
