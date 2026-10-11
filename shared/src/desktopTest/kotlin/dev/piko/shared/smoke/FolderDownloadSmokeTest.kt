package dev.piko.shared.smoke

import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.VaultEntry
import dev.piko.shared.download.DownloadFolderSource
import dev.piko.shared.download.DriveDownloadFolderSource
import dev.piko.shared.download.PikoDownloadCoordinator
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FolderDownloadSmokeTest {

    @Test
    fun `a game folder is visible while preparing and repeated clicks do not scan it again`() = smoke { scope ->
        val server = FakePikPakServer()
        val game = server.addFolder("Game")
        server.addFile("game.exe", game.id, ByteArray(128) { 2 }, hash = "GAMEEXE")
        val data = server.addFolder("Data", game.id)
        server.addFile("data.pak", data.id, ByteArray(256) { 4 }, hash = "GAMEPAK")
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(provider, prefs)
        val root = repo.listAllFiles().getOrThrow().single { it.id == game.id }
        val entered = Channel<Unit>(1)
        val release = Channel<Unit>(1)
        val calls = AtomicInteger()
        val real = DriveDownloadFolderSource(repo)
        val source = object : DownloadFolderSource {
            override suspend fun list(folderId: String): List<FileStat> {
                calls.incrementAndGet()
                if (folderId == game.id) { entered.send(Unit); release.receive() }
                return real.list(folderId)
            }
            override suspend fun remainingDailyDownload(): Long? = null
        }
        val directory = Files.createTempDirectory("piko-game-download").toFile()
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), scope)
        coordinator.enqueueFolders(listOf(root), source, maxHeight = 0)
        assertEquals(1, coordinator.listings.value.size, "目录尚未列出也已经有可见的准备任务")
        withTimeout(5_000) { entered.receive() }
        repeat(4) { coordinator.enqueueFolders(listOf(root), source, maxHeight = 0) }
        assertEquals(1, calls.get(), "重复点击不能并行扫描同一个游戏目录")
        release.send(Unit)
        awaitUntil("游戏文件夹直接展开并下载") {
            coordinator.listings.value.isEmpty() && coordinator.tasks.value.size == 2 &&
                coordinator.tasks.value.values.all { it.status == DownloadStatus.COMPLETED }
        }
        assertEquals(2, calls.get(), "顶层与子目录各列一次")
        assertEquals(setOf("Game/game.exe", "Game/Data/data.pak"), coordinator.tasks.value.values.map { it.fileName }.toSet())
        assertTrue(coordinator.tasks.value.values.all { it.qualityCap == 0 })
        directory.deleteRecursively()
    }

    @Test
    fun `cloud pending files do not block downloading completed siblings from a folder`() = smoke { scope ->
        val server = FakePikPakServer()
        val folder = server.addFolder("Mixed")
        val ready = server.addFile("ready.txt", folder.id, ByteArray(256) { 7 }, hash = "READY")
        val nested = server.addFolder("Nested", folder.id)
        val nestedReady = server.addFile("nested.txt", nested.id, ByteArray(128) { 3 }, hash = "NESTED")
        val pending = server.addFile("cloud-pending.txt", folder.id, ByteArray(512), hash = "NOTHELD")
        server.addFile("nested-pending.txt", nested.id, ByteArray(512), hash = "NOTHELD")
        server.unheldHashes += "NOTHELD"
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val repo = PikoDriveRepository(provider, prefs)
        val directory = Files.createTempDirectory("piko-mixed-download").toFile()
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), scope)
        val root = repo.listAllFiles().getOrThrow().single { it.id == folder.id }

        coordinator.enqueueFolders(listOf(root), DriveDownloadFolderSource(repo), maxHeight = 0)

        awaitUntil("就绪文件入队并完成，云端未完成文件不阻塞整批") {
            coordinator.listings.value.isEmpty() && coordinator.tasks.value.size == 2 &&
                coordinator.tasks.value.values.all { it.status == DownloadStatus.COMPLETED }
        }
        assertEquals(setOf(ready.id, nestedReady.id), coordinator.tasks.value.values.map { it.fileId }.toSet())
        assertTrue(directory.resolve("Mixed/ready.txt").readBytes().contentEquals(ready.content))
        assertTrue(directory.resolve("Mixed/Nested/nested.txt").readBytes().contentEquals(nestedReady.content))
        assertTrue(!directory.resolve("Mixed/cloud-pending.txt").exists())
        assertEquals(0, server.count("GET /drive/v1/files/${pending.id}"), "未就绪条目不会尝试获取下载详情")
        directory.deleteRecursively()
    }

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
