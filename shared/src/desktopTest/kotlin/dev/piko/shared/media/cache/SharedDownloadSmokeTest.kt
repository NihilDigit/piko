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
    fun startupAndResumeRequestsDoNotTriggerHeaderOrTailSpeculation() = smoke { _ ->
        val cloud = FakePikPakCloud(ByteArray(24 * 1024 * 1024) { it.toByte() })
        val repository = PikoMediaRepository(cloud.provider)
        val playback = repository.preparePlayback("f1").getOrThrow()
        try {
            val url = requireNotNull(playback.proxyUrl)
            assertEquals(206, dev.piko.shared.media.testing.request(url, range = "bytes=0-1023").status)
            kotlinx.coroutines.delay(100)
            assertEquals(1, cloud.cdnRequests.get())
            assertTrue(cloud.deliveredCdnBytes.get() <= 256 * 1024L)
            assertEquals(206, dev.piko.shared.media.testing.request(url, range = "bytes=16777216-16778239").status)
            kotlinx.coroutines.delay(100)
            assertEquals(2, cloud.cdnRequests.get())
            assertTrue(cloud.deliveredCdnBytes.get() <= 512 * 1024L)
        } finally { playback.close(); cloud.provider.currentClient.value?.close() }
    }

    @Test
    fun ownedPreparationPreemptsDownloadAndSteadyPlaybackRestoresIt() = smoke { scope ->
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val payload = ByteArray(1024 * 1024) { it.toByte() }
        val cloud = FakePikPakCloud(payload, beforeCdnResponse = { release.await() })
        val root = Files.createTempDirectory("piko-priority").toFile()
        val repository = PikoMediaRepository(cloud.provider)
        val coordinator = PikoDownloadCoordinator(cloud.provider, MemoryPreferences(), DirectoryStorage(root), scope,
            mediaRepository = repository)
        coordinator.enqueue(FileStat(id = "f1", name = "movie.mkv", size = payload.size.toString(), hash = "GCIDFAKE0001"))
        awaitUntil("后台请求已开始") { cloud.activeCdnRequests > 0 }
        val playback = repository.preparePlayback("f1").getOrThrow()
        try {
            awaitUntil("准备租约撤回后台请求") { cloud.activeCdnRequests == 0 }
            val requests = cloud.cdnRequests.get()
            playback.updatePhase(urgent = false, steady = true)
            awaitUntil("稳定播放立即归还容量") { cloud.activeCdnRequests > 0 && cloud.cdnRequests.get() > requests }
            playback.withdrawTransport()
            release.complete(Unit)
            awaitUntil("下载完成", 20_000) { coordinator.tasks.value["f1"]?.status == DownloadStatus.COMPLETED }
            assertContentEquals(payload, root.resolve("movie.mkv").readBytes())
        } finally { release.complete(Unit); playback.close() }
    }

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
            assertEquals(payload.size.toLong(), cloud.deliveredCdnBytes.get(), "播放与下载不能重复请求同一个块：${cloud.cdnRanges}")
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
        val remainingBlocks = kotlinx.coroutines.CompletableDeferred<Unit>()
        val paused = java.util.concurrent.atomic.AtomicBoolean()
        val cloud = FakePikPakCloud(payload, beforeCdnRange = { start ->
            if (start > 0 || paused.get()) remainingBlocks.await()
        }, beforeCdnChunk = { offset ->
            if (offset >= io.github.nihildigit.pikpak.PikPakStreamReader.DEFAULT_BLOCK_SIZE) remainingBlocks.await()
        })
        val root = Files.createTempDirectory("piko-partial").toFile()
        val repository = PikoMediaRepository(cloud.provider)
        val coordinator = PikoDownloadCoordinator(cloud.provider, MemoryPreferences(), DirectoryStorage(root), scope,
            mediaRepository = repository)
        coordinator.enqueue(FileStat(id = "f1", name = "movie.mkv", size = payload.size.toString(), hash = "GCIDFAKE0001"))
        awaitUntil("已下载部分块") {
            coordinator.tasks.value["f1"]?.let { it.downloadedBytes >= io.github.nihildigit.pikpak.PikPakStreamReader.DEFAULT_BLOCK_SIZE && it.downloadedBytes < payload.size } == true
        }
        paused.set(true)
        coordinator.pauseDownload("f1")
        awaitUntil("下载已转为暂停态") { coordinator.tasks.value["f1"]?.status == DownloadStatus.PAUSED }
        val task = coordinator.tasks.value.getValue("f1")
        val path = root.resolve(".piko-cache/${fileCacheName(PikoFileCachePool.identity(task.account, task.gcid, task.totalBytes))}.data").path
        awaitUntil("暂停数据已持久化") { java.io.File("$path.blocks").exists() }
        val metadataCalls = cloud.detailCalls.get()
        repository.preparePlayback("f1").getOrThrow().use { playback ->
            assertTrue(playback.proxyUrl != null)
        }
        assertEquals(metadataCalls, cloud.detailCalls.get(), "已有下载元数据的播放无需重取文件详情")
        // New CDN responses remain blocked: a successful head read proves reuse of the paused cache.
        repository.openRandomAccess("f1").getOrThrow().use { source ->
            val head = ByteArray(4096)
            kotlinx.coroutines.withTimeout(5_000) { source.readAt(0, head, 0, head.size) }
            assertContentEquals(payload.copyOfRange(0, head.size), head)
        }
        remainingBlocks.complete(Unit)
        repository.openRandomAccess("f1").getOrThrow().use { source ->
            val bytes = ByteArray(4096)
            source.readAt(5L * 1024 * 1024, bytes, 0, bytes.size)
            assertContentEquals(payload.copyOfRange(5 * 1024 * 1024, 5 * 1024 * 1024 + bytes.size), bytes)
        }
        coordinator.startDownload("f1")
        awaitUntil("播放后仍可继续下载", 20_000) { coordinator.tasks.value["f1"]?.status == DownloadStatus.COMPLETED }
        assertContentEquals(payload, root.resolve("movie.mkv").readBytes())
    }
}
