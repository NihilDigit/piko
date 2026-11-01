package dev.piko.shared.smoke

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.download.DownloadFolderSource
import dev.piko.shared.download.DriveDownloadFolderSource
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.download.PikoDownloadStorage
import dev.piko.shared.media.cache.PikoFileCachePool
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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

class DownloadPersistenceAndDedupSmokeTest {
    private class Storage(private val directory: File) : PikoDownloadStorage by DirectoryStorage(directory) {
        override suspend fun downloadTarget(fileName: String): String = directory.resolve(fileName).also { it.parentFile.mkdirs() }.absolutePath
    }

    @Test
    fun `batch reuses two complete disk files and downloads only the missing third`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-dedup").toFile()
        try {
            val server = FakePikPakServer()
            val content = (1..3).map { Random(it).nextBytes(32 * 1024) }
            val nodes = content.mapIndexed { index, bytes -> server.addFile("part${index + 1}.bin", content = bytes, hash = "PART${index + 1}") }
            directory.resolve(nodes[0].name).writeBytes(content[0])
            directory.resolve(nodes[1].name).writeBytes(content[1])
            val provider = server.provider()
            val prefs = MemoryPreferences()
            val files = PikoDriveRepository(provider, prefs).listAllFiles().getOrThrow()
            val coordinator = PikoDownloadCoordinator(provider, prefs, Storage(directory), scope)
            coordinator.enqueueFiles(files)
            awaitUntil("全部文件完成") { coordinator.tasks.value.size == 3 && coordinator.tasks.value.values.all { it.status == DownloadStatus.COMPLETED } }
            nodes.forEachIndexed { index, node ->
                assertEquals(node.name, coordinator.tasks.value.getValue(node.id).fileName)
                assertContentEquals(content[index], directory.resolve(node.name).readBytes())
                assertFalse(directory.resolve("part${index + 1} (2).bin").exists())
            }
            assertEquals(0, server.count("GET /${nodes[0].id}"), "完整文件不应请求 CDN")
            assertEquals(0, server.count("GET /${nodes[1].id}"), "完整文件不应请求 CDN")
            assertTrue(server.count("GET /${nodes[2].id}") > 0)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `restore discovers retained blocks even when sparse flag was not persisted`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-sparse-restore").toFile()
        try {
            val content = Random(44).nextBytes(2 * 1024 * 1024 + 17)
            val server = FakePikPakServer()
            val remote = server.addFile("partial.bin", content = content, hash = "PARTIAL44")
            val provider = server.provider()
            val client = provider.currentClient.value!!
            val storage = Storage(directory)
            val pool = PikoFileCachePool(scope) { storage.cacheTarget(it) }
            val lease = pool.acquire(client, remote.id, remote.hash, content.size.toLong(), remote.name, retained = true)
            lease.entry.cache.download(listOf(0L..262143L), StreamRole.BACKGROUND).await()
            lease.entry.store.flush()
            val held = lease.entry.store.heldBytes.value
            assertTrue(held > 0 && held < content.size)
            lease.release()
            val task = DownloadTask(remote.id, remote.id, remote.name, remote.hash, content.size.toLong(),
                status = DownloadStatus.DOWNLOADING, account = client.account, sparseCache = false)
            val prefs = MemoryPreferences().apply { downloadTasks = Json.encodeToString(ListSerializer(DownloadTask.serializer()), listOf(task)) }
            val restored = PikoDownloadCoordinator(provider, prefs, storage, scope)
            restored.flushPersistence()
            restored.fileCachePool.prune()
            val paused = restored.tasks.value.getValue(remote.id)
            assertEquals(DownloadStatus.PAUSED, paused.status)
            assertTrue(paused.sparseCache)
            assertEquals(held, paused.downloadedBytes)
            assertEquals(held, restored.fileCachePool.progress(client.account, remote.hash, content.size.toLong()))
            restored.startDownload(remote.id)
            awaitUntil("恢复分块后下载完成") { restored.tasks.value[remote.id]?.status == DownloadStatus.COMPLETED }
            assertContentEquals(content, directory.resolve(remote.name).readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `pending folder listing survives restart and resumes after default source binding`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-listing-restore").toFile()
        val firstJob = SupervisorJob(scope.coroutineContext[Job])
        try {
            val server = FakePikPakServer()
            val folder = server.addFolder("Collection")
            val content = Random(45).nextBytes(16 * 1024)
            val remote = server.addFile("episode.bin", parentId = folder.id, content = content, hash = "EPISODE45")
            val provider = server.provider()
            val prefs = MemoryPreferences()
            val repository = PikoDriveRepository(provider, prefs)
            val folderFile = repository.listAllFiles().getOrThrow().single { it.id == folder.id }
            val cache = MemoryCacheStore()
            val entered = CompletableDeferred<Unit>()
            val blocked = object : DownloadFolderSource {
                override suspend fun list(folderId: String): List<FileStat> {
                    entered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    return emptyList()
                }
                override suspend fun remainingDailyDownload(): Long? = null
            }
            val first = PikoDownloadCoordinator(provider, prefs, Storage(directory), CoroutineScope(scope.coroutineContext + firstJob), cacheStore = cache)
            first.enqueueFolders(listOf(folderFile), blocked)
            entered.await()
            first.flushPersistence()
            assertTrue(cache.read("pending-folder-downloads.json")!!.contains(folder.id))
            firstJob.cancelAndJoin()
            val restored = PikoDownloadCoordinator(provider, prefs, Storage(directory), scope, cacheStore = cache)
            restored.flushPersistence()
            assertEquals(1, restored.listings.value.size)
            restored.bindDefaultFolderSource(DriveDownloadFolderSource(repository))
            awaitUntil("文件夹准备恢复并下载完成") { restored.tasks.value[remote.id]?.status == DownloadStatus.COMPLETED && restored.listings.value.isEmpty() }
            assertContentEquals(content, directory.resolve("Collection/episode.bin").readBytes())
            restored.flushPersistence()
            assertEquals("[]", cache.read("pending-folder-downloads.json"))
        } finally {
            firstJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `malformed persisted tasks are not overwritten by initial empty state or flush`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-invalid-table").toFile()
        try {
            val prefs = MemoryPreferences().apply { downloadTasks = "invalid-json" }
            val coordinator = PikoDownloadCoordinator(FakePikPakServer().provider(), prefs, Storage(directory), scope)
            coordinator.flushPersistence()
            assertEquals("invalid-json", prefs.downloadTasks)
        } finally { directory.deleteRecursively() }
    }
}
