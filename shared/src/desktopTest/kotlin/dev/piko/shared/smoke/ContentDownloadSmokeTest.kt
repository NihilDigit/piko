package dev.piko.shared.smoke

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import io.github.nihildigit.pikpak.ResolvedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContentDownloadSmokeTest {
    @Test fun contentDownloadsWithoutAnOwnedFileIdAndReusesRepeatedRequests() = smoke { scope ->
        val bytes = ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val server = FakePikPakServer()
        val target = server.addFolder("Dramas")
        val original = server.addFile("owner-video.mkv", "owner", bytes, "CONTENT")
        val directory = Files.createTempDirectory("piko-content-download").toFile()
        val coordinator = PikoDownloadCoordinator(server.provider(), MemoryPreferences(), DirectoryStorage(directory), scope)
        val resolved = ResolvedFile("episode.mkv", bytes.size.toLong(), "CONTENT")
        coordinator.enqueueResolved(listOf(resolved, resolved.copy(gcid = null)), target.id)
        awaitUntil("内容下载完成", 20_000) { coordinator.tasks.value.values.singleOrNull()?.status == DownloadStatus.COMPLETED }
        assertContentEquals(bytes, directory.resolve("episode.mkv").readBytes())
        assertTrue(coordinator.tasks.value.values.single().leasedSource)
        assertNotNull(server.node(original.id))
        assertTrue(server.children(target.id).isEmpty(), "临时对象只用于取下载链接，不留在网盘里")
        coordinator.enqueueResolved(listOf(resolved.copy(path = "other-name.mkv")), target.id)
        awaitUntil("重复请求仍是同一个任务") { coordinator.tasks.value.size == 1 }
        assertEquals(1, server.instantCreates.get())
    }

    @Test fun pausedContentDownloadRetainsItsSourceAcrossRestart() = smoke { scope ->
        val bytes = ByteArray(12 * 1024 * 1024) { (it % 251).toByte() }
        val server = FakePikPakServer().apply { cdnDelayMs = 300 }
        val target = server.addFolder("Dramas")
        server.addFile("owner.mkv", "owner", bytes, "RESUME")
        val directory = Files.createTempDirectory("piko-content-resume").toFile()
        val prefs = MemoryPreferences()
        val provider = server.provider()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), firstScope)
            first.enqueueResolved(listOf(ResolvedFile("episode.mkv", bytes.size.toLong(), "RESUME")), target.id)
            awaitUntil("稀疏缓存已有内容") { first.tasks.value.values.singleOrNull()?.downloadedBytes?.let { it > 0 && it < bytes.size } == true }
            val id = first.tasks.value.keys.single()
            first.pauseDownload(id)
            val serializer = ListSerializer(DownloadTask.serializer())
            awaitUntil("暂停的内容来源已保存") {
                Json.decodeFromString(serializer, prefs.downloadTasks).singleOrNull()?.let { it.leasedSource && it.status == DownloadStatus.PAUSED } == true
            }
            firstScope.cancel()
            server.cdnDelayMs = 0
            val media = PikoMediaRepository(provider)
            val restarted = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), scope, mediaRepository = media)
            awaitUntil("任务已恢复") { restarted.tasks.value[id]?.leasedSource == true }
            assertEquals(target.id, restarted.tasks.value.getValue(id).parentId)
            val source = media.openRandomAccess(id).getOrThrow()
            try {
                val tail = ByteArray(128)
                source.readAt(bytes.size - tail.size.toLong(), tail, 0, tail.size)
                assertContentEquals(bytes.copyOfRange(bytes.size - tail.size, bytes.size), tail)
            } finally { source.close() }
            restarted.startDownload(id)
            awaitUntil("重启后内容下载完成", 20_000) { restarted.tasks.value[id]?.status == DownloadStatus.COMPLETED }
            assertContentEquals(bytes, directory.resolve("episode.mkv").readBytes())
            assertTrue(server.children(target.id).isEmpty())
        } finally { firstScope.cancel() }
    }
}
