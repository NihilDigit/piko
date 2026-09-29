package dev.piko.shared.smoke

import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.download.DownloadFolderSource
import dev.piko.shared.download.DriveDownloadFolderSource
import dev.piko.shared.download.PikoDownloadCoordinator
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FolderDownloadSmokeTest {

    /**
     * 固定文件夹展开成一批任务的规则：路径以文件夹名打头、保持子文件夹；归档条目照样下载；上传中的不下；
     * 根目录下的 Piko-Temp 不下；清理后撞名的加序号，否则两个任务往同一个文件里追加，文件写坏。
     * 再下完一个，确认子文件夹里的文件真的落在对应的位置上。
     */
    @Test
    fun `a folder expands into one batch of tasks that keeps its relative paths`() = smoke { scope ->
        val server = FakePikPakServer()
        val show = server.addFolder("Show")
        server.addFile("ep1.mkv", show.id, content = ByteArray(2_048) { 1 }, hash = "GCIDEP1")
        server.addFile("a:b.txt", show.id, content = ByteArray(10), hash = "GCIDAB1")
        server.addFile("a b.txt", show.id, content = ByteArray(10), hash = "GCIDAB2")
        val extras = server.addFolder("Extras", show.id)
        server.addFile("making.mp4", extras.id, content = ByteArray(10), hash = "GCIDMAKING")
        val temp = server.addFolder("Piko-Temp")
        server.addFile("preview.mkv", temp.id, content = ByteArray(10), hash = "GCIDPREVIEW")
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val driveRepo = PikoDriveRepository(provider, prefs)
        val vaulted = VaultEntry.create("vaulted.mkv", size = 100, gcid = "GCIDVAULT", source = null, addedAt = 0).toFileStat(show.id)
        val uploading = FileStat(id = "UP1", name = "uploading.mkv", parentId = show.id, size = "10", phase = TaskPhase.PENDING)
        // 归档清单要经上传与读回，假服务端不接；清单的读取另有 VaultStoreTest，这里直接把条目并进列表
        val source = object : DownloadFolderSource {
            val drive = DriveDownloadFolderSource(driveRepo)
            override suspend fun list(folderId: String): List<FileStat> =
                drive.list(folderId) + if (folderId == show.id) listOf(vaulted, uploading) else emptyList()
            override suspend fun remainingDailyDownload(): Long? = null
        }
        val directory = Files.createTempDirectory("piko-smoke").toFile()
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), scope)
        val root = driveRepo.listAllFiles().getOrThrow()

        val started = coordinator.enqueueFolders(root.filter { it.id == show.id || it.id == temp.id }, source)

        assertEquals(1, started, "Piko-Temp 不该开一批")
        awaitUntil("列完并排进任务表") { coordinator.listings.value.isEmpty() && coordinator.tasks.value.isNotEmpty() }
        val tasks = coordinator.tasks.value.values
        assertEquals(
            setOf("Show/ep1.mkv", "Show/a b.txt", "Show/a b (2).txt", "Show/Extras/making.mp4", "Show/vaulted.mkv"),
            tasks.map { it.fileName }.toSet(),
        )
        assertEquals(1, tasks.map { it.batch }.distinct().size)
        assertEquals("Show", tasks.first().batch?.folderName)

        val episode = tasks.single { it.fileName == "Show/ep1.mkv" }.taskId
        awaitUntil("子文件夹外的一集下完") { coordinator.tasks.value[episode]?.status == DownloadStatus.COMPLETED }
        assertEquals(2_048L, directory.resolve("Show/ep1.mkv").length())
        val making = tasks.single { it.fileName.endsWith("making.mp4") }.taskId
        awaitUntil("子文件夹里的一个下完") { coordinator.tasks.value[making]?.status == DownloadStatus.COMPLETED }
        assertTrue(directory.resolve("Show/Extras/making.mp4").isFile)
        directory.deleteRecursively()
    }

    /** 超出今日下载额度时停在待确认，不自己开始；确认后照常排进任务表。 */
    @Test
    fun `a folder larger than the daily allowance waits for confirmation`() = smoke { scope ->
        val server = FakePikPakServer()
        val show = server.addFolder("Show")
        server.addFile("ep1.mkv", show.id, content = ByteArray(1_000), hash = "GCIDEP1")
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val driveRepo = PikoDriveRepository(provider, prefs)
        val source = object : DownloadFolderSource {
            val drive = DriveDownloadFolderSource(driveRepo)
            override suspend fun list(folderId: String) = drive.list(folderId)
            override suspend fun remainingDailyDownload(): Long = 999
        }
        val directory = Files.createTempDirectory("piko-smoke").toFile()
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), scope)
        val folder = driveRepo.listAllFiles().getOrThrow().single { it.id == show.id }

        coordinator.enqueueFolders(listOf(folder), source)

        awaitUntil("列完停在待确认") { coordinator.listings.value.values.singleOrNull()?.quotaExcess != null }
        assertTrue(coordinator.tasks.value.isEmpty())
        coordinator.confirmListing(coordinator.listings.value.keys.single())
        awaitUntil("确认后排进任务表") { coordinator.tasks.value.size == 1 && coordinator.listings.value.isEmpty() }
        directory.deleteRecursively()
    }
}
