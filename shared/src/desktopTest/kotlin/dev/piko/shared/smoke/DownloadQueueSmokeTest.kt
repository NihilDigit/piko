package dev.piko.shared.smoke

import dev.piko.download.DownloadStatus
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.download.DriveDownloadFolderSource
import dev.piko.shared.download.PikoDownloadCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadQueueSmokeTest {
    @Test
    fun `multiple folders and a file batch share one cap and pause all keeps the queue stopped`() = smoke { scope ->
        val server = FakePikPakServer().apply { cdnDelayMs = 100 }
        val first = server.addFolder("a:b")
        val second = server.addFolder("a b")
        repeat(4) { number ->
            server.addFile("$number.mkv", first.id, hash = "F$number", content = ByteArray(3 * 1024 * 1024))
            server.addFile("$number.mkv", second.id, hash = "S$number", content = ByteArray(3 * 1024 * 1024))
        }
        repeat(4) { number -> server.addFile("single-$number.txt", hash = "L$number", content = ByteArray(3 * 1024 * 1024)) }
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val root = Files.createTempDirectory("piko-queue").toFile()
        val drive = PikoDriveRepository(provider, prefs)
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(root), scope)
        val maximum = AtomicInteger()
        val observer = scope.launch {
            coordinator.tasks.collect { tasks ->
                val running = tasks.values.count { it.status == DownloadStatus.DOWNLOADING }
                maximum.updateAndGet { maxOf(it, running) }
            }
        }
        val files = drive.listAllFiles().getOrThrow()
        coordinator.enqueueFiles(files.filter { !it.isFolder })
        coordinator.enqueueFolders(files.filter { it.isFolder }, DriveDownloadFolderSource(drive))
        awaitUntil("所有文件进入同一队列") { coordinator.tasks.value.size == 12 && coordinator.listings.value.isEmpty() }
        coordinator.pauseAll()
        awaitUntil("全部暂停") { coordinator.tasks.value.values.all { it.status == DownloadStatus.PAUSED } }
        delay(500)
        assertTrue(coordinator.tasks.value.values.none { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING })
        assertTrue(maximum.get() in 1..3)
        assertEquals(setOf("a b", "a b (2)"), coordinator.tasks.value.values.mapNotNull { it.batch?.takeIf { batch -> batch.isFolder }?.folderName }.toSet())
        server.cdnDelayMs = 0
        coordinator.tasks.value.values.mapNotNull { it.batch?.id }.distinct().forEach(coordinator::resumeBatch)
        awaitUntil("各组均完成", 20_000) { coordinator.tasks.value.values.all { it.status == DownloadStatus.COMPLETED } }
        assertTrue(maximum.get() <= 3)
        assertTrue(root.resolve("a b/0.mkv").exists())
        assertTrue(root.resolve("a b (2)/0.mkv").exists())
        observer.cancel()
    }

    @Test
    fun `colliding selected file names stay independent and existing user files remain unchanged`() = smoke { scope ->
        val server = FakePikPakServer()
        server.addFile("a:b.txt", hash = "ONE", content = byteArrayOf(1, 2))
        server.addFile("a b.txt", hash = "TWO", content = byteArrayOf(3, 4, 5))
        server.addFile("empty.txt", hash = "EMPTY", content = byteArrayOf())
        val provider = server.provider()
        val prefs = MemoryPreferences()
        val drive = PikoDriveRepository(provider, prefs)
        val root = Files.createTempDirectory("piko-collision").toFile()
        root.resolve("a b.txt").writeBytes(byteArrayOf(9))
        val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(root), scope)
        coordinator.enqueueFiles(drive.listAllFiles().getOrThrow())
        awaitUntil("批量文件完成") { coordinator.tasks.value.size == 3 && coordinator.tasks.value.values.all { it.status == DownloadStatus.COMPLETED } }
        assertContentEquals(byteArrayOf(9), root.resolve("a b.txt").readBytes())
        assertEquals(setOf("a b (2).txt", "a b (3).txt", "empty.txt"), coordinator.tasks.value.values.map { it.fileName }.toSet())
        assertTrue(root.resolve("empty.txt").isFile)
        assertEquals(0, root.resolve("empty.txt").length())
    }
}
