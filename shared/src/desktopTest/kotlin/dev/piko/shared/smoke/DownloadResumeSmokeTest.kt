package dev.piko.shared.smoke

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.download.PikoDownloadStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 与 DesktopPikoDownloadStorage 同样的落盘方式：普通目录，写入即最终位置。 */
private class DirectoryStorage(private val directory: File) : PikoDownloadStorage {
    override fun pathFor(fileName: String): String = directory.resolve(fileName).absolutePath
    override suspend fun downloadTarget(fileName: String): String = pathFor(fileName)
    override suspend fun commit(fileName: String, downloadedPath: String): String = downloadedPath
    override suspend fun existingLength(fileName: String): Long = directory.resolve(fileName).length()
    override suspend fun exists(fileName: String): Boolean = directory.resolve(fileName).exists()
    override suspend fun delete(path: String): Boolean = File(path).delete()
}

class DownloadResumeSmokeTest {

    /**
     * 防的是暂停再继续把文件写坏。旧实现续传时从零读起：Desktop 追加写入，整份文件被再接到
     * 尾部，长度超过原文件后又被当成「已完成」；Android 截断重下。这里走一次真实的暂停与继续，
     * 比对落盘字节。
     */
    @Test
    fun `pausing mid-download and resuming yields the exact original bytes`() = smoke { scope ->
        val content = Random(42).nextBytes(5 * 512 * 1024 + 123)
        val server = FakePikPakServer().apply { cdnDelayMs = 150 }
        val remote = server.addFile("movie.mkv", content = content, hash = "GCIDMOVIE")
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val directory = Files.createTempDirectory("piko-smoke").toFile()
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), scope)
        val file = PikoDriveRepository(provider, prefs).listAllFiles().getOrThrow().single { it.id == remote.id }
        val local = directory.resolve("movie.mkv")

        coordinator.enqueue(file)
        awaitUntil("写入了一部分") { local.length() > 0 }
        coordinator.pauseDownload(remote.id)
        awaitUntil("任务进入暂停") { coordinator.tasks.value[remote.id]?.status == DownloadStatus.PAUSED }
        val pausedAt = local.length()
        assertTrue(pausedAt < content.size, "暂停时应停在半途，否则这个用例没有测到续传")

        server.cdnDelayMs = 0
        coordinator.startDownload(remote.id)
        awaitUntil("下载完成", timeoutMs = 20_000) {
            coordinator.tasks.value[remote.id]?.status == DownloadStatus.COMPLETED
        }

        assertEquals(content.size.toLong(), local.length())
        assertContentEquals(content, local.readBytes())
        directory.deleteRecursively()
    }

    /**
     * 防的是重启后任务表失真：上次在下载的任务协程已不在，却仍显示「下载中」且无法暂停；
     * 已完成但文件被删的任务留在列表里，点开是坏链接。同时核对清理结果写回了偏好。
     */
    @Test
    fun `restoring the saved table pauses interrupted tasks and drops missing files`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-smoke").toFile()
        directory.resolve("partial.mkv").writeBytes(ByteArray(100))
        directory.resolve("kept.mkv").writeBytes(ByteArray(300))
        val serializer = ListSerializer(DownloadTask.serializer())
        val prefs = MemoryPreferences()
        prefs.downloadTasks = Json.encodeToString(
            serializer,
            listOf(
                savedTask("partial", totalBytes = 1_000, status = DownloadStatus.DOWNLOADING, downloadedBytes = 40),
                savedTask("kept", totalBytes = 300, status = DownloadStatus.COMPLETED, downloadedBytes = 300),
                savedTask("gone", totalBytes = 300, status = DownloadStatus.COMPLETED, downloadedBytes = 300),
            ),
        )

        val coordinator = PikoDownloadCoordinator(
            FakePikPakServer().provider(),
            prefs,
            DirectoryStorage(directory),
            scope,
        )
        awaitUntil("任务表恢复完成") { coordinator.tasks.value.isNotEmpty() }

        val restored = coordinator.tasks.value
        assertEquals(setOf("partial", "kept"), restored.keys)
        assertEquals(DownloadStatus.PAUSED, restored.getValue("partial").status)
        assertEquals(100L, restored.getValue("partial").downloadedBytes, "续传点应以磁盘上的文件长度为准")
        awaitUntil("清理结果写回偏好") {
            Json.decodeFromString(serializer, prefs.downloadTasks).map { it.taskId }.toSet() == restored.keys
        }
        assertFalse(prefs.downloadTasks.contains("gone"))
        directory.deleteRecursively()
    }

    private fun savedTask(name: String, totalBytes: Long, status: DownloadStatus, downloadedBytes: Long) =
        DownloadTask(
            taskId = name,
            fileId = name,
            fileName = "$name.mkv",
            gcid = "GCID$name",
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            speedBytesPerSec = 1_024,
            status = status,
            destinationPath = "$name.mkv",
        )
}
