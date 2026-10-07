package dev.piko.shared.smoke

import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay

class DownloadRelocationSmokeTest {

    // 便携目录整个挪走（或换了盘符）后续传：从新位置的暂存接着下，不在旧位置把目录重新建出来
    @Test
    fun `a paused download resumes from the moved data directory without recreating the old one`() = smoke { scope ->
        val content = Random(7).nextBytes(5 * 512 * 1024 + 99)
        val server = FakePikPakServer().apply { cdnDelayMs = 150 }
        val remote = server.addFile("movie.mkv", content = content, hash = "GCIDMOVE")
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val base = Files.createTempDirectory("piko-relocate").toFile()
        val before = base.resolve("before")
        val after = base.resolve("after")
        val file = PikoDriveRepository(provider, prefs).listAllFiles().getOrThrow().single { it.id == remote.id }

        val firstRun = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(before), firstRun)
        first.enqueue(file)
        awaitUntil("写入了一部分") { (first.tasks.value[remote.id]?.downloadedBytes ?: 0) > 0 }
        first.pauseDownload(remote.id)
        awaitUntil("任务进入暂停") { first.tasks.value[remote.id]?.status == DownloadStatus.PAUSED }
        // 任务表的写盘与暂存的落盘都在协程里，退出前等它们完成
        while ("\"PAUSED\"" !in prefs.loadDownloadTasks()) delay(20)
        firstRun.coroutineContext[Job]!!.cancelAndJoin()

        Files.move(before.toPath(), after.toPath())
        server.cdnDelayMs = 0
        val second = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(after), scope)
        awaitUntil("恢复了任务表") { second.tasks.value[remote.id] != null }
        assertTrue(second.tasks.value.getValue(remote.id).downloadedBytes > 0, "新位置的有效块记录没被读到")
        second.startDownload(remote.id)
        awaitUntil("下载完成", timeoutMs = 20_000) { second.tasks.value[remote.id]?.status == DownloadStatus.COMPLETED }

        assertContentEquals(content, after.resolve("movie.mkv").readBytes())
        assertFalse(before.exists(), "旧位置被重新建了出来")
        base.deleteRecursively()
    }
}
