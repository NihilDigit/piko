package dev.piko.shared.smoke

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.SavedAccount
import dev.piko.shared.data.SavedAccounts
import dev.piko.shared.data.PikoPathBreadcrumb
import dev.piko.shared.presentation.DriveWorkspace
import io.github.nihildigit.pikpak.Session
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import dev.piko.shared.data.PikoCacheStore
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
    fun `immediate local account restore publishes before background coroutine advances`() = smoke { scope ->
        val account = "restore@piko.dev"
        val store = MemorySessionStore().apply {
            accounts = SavedAccounts(current = account, accounts = listOf(SavedAccount(account)))
            sessions[account] = Session(accessToken = "LOCAL", refreshToken = "REFRESH", sub = "local",
                expiresAt = kotlin.time.Clock.System.now().epochSeconds + 3600)
        }
        val server = FakePikPakServer()
        val job = SupervisorJob()
        try {
            val manager = PikoClientManager(store, CoroutineScope(scope.coroutineContext + job),
                httpClient = server.httpClient(), restoreLocallyImmediately = true)
            assertFalse(manager.isInitializing.value)
            assertEquals(account, manager.currentClient.value?.account)
            assertEquals(0, server.count("POST /v1/auth/"), "本地恢复不应等待网络鉴权")
            manager.currentClient.value?.close()
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `workspace flush writes pending tab snapshot without debounce`() = smoke { scope ->
        val server = FakePikPakServer()
        val provider = server.provider()
        val repository = PikoDriveRepository(provider, MemoryPreferences())
        val cache = MemoryCacheStore()
        val workspace = DriveWorkspace(repository, cache, scope)
        try {
            workspace.enterAccount(provider.currentClient.value!!.account)
            workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb("folder", "Folder")))
            workspace.flushPersistence()
            val saved = cache.read("drive-tabs-smoke@piko.dev.json")
            assertTrue(saved?.contains("folder") == true)
        } finally { workspace.close() }
    }

    @Test
    fun `workspace flush caller can time out while accepted write finishes`() = smoke { scope ->
        val provider = FakePikPakServer().provider()
        val repository = PikoDriveRepository(provider, MemoryPreferences())
        val memory = MemoryCacheStore()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val written = CompletableDeferred<Unit>()
        val cache = object : PikoCacheStore by memory {
            override suspend fun write(key: String, value: String) {
                if (key.startsWith("drive-tabs-")) {
                    entered.complete(Unit)
                    release.await()
                    memory.write(key, value)
                    written.complete(Unit)
                } else memory.write(key, value)
            }
        }
        val workspace = DriveWorkspace(repository, cache, scope)
        try {
            workspace.enterAccount(provider.currentClient.value!!.account)
            workspace.openTab(listOf(PikoDriveRepository.ROOT_BREADCRUMB, PikoPathBreadcrumb("slow-folder", "Folder")))
            val result = withTimeoutOrNull(100) { workspace.flushPersistence(); true }
            assertEquals(null, result, "调用方超时不应等待不可取消的写入")
            withTimeout(5000) { entered.await() }
            assertFalse(written.isCompleted)
            release.complete(Unit)
            withTimeout(5000) { written.await() }
            assertTrue(memory.read("drive-tabs-smoke@piko.dev.json")?.contains("slow-folder") == true)
        } finally {
            release.complete(Unit)
            workspace.close()
        }
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
                status = DownloadStatus.PAUSED, account = client.account, sparseCache = false)
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
    fun `interrupted active downloads resume when account returns but manually paused tasks stay paused`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-active-restore").toFile()
        val coordinatorJob = SupervisorJob(scope.coroutineContext[Job])
        try {
            val server = FakePikPakServer()
            val contents = (1..3).map { Random(it + 80).nextBytes(32 * 1024) }
            val nodes = contents.mapIndexed { index, bytes ->
                server.addFile("restore$index.bin", content = bytes, hash = "RESTORE$index")
            }
            val client = server.client()
            val provider = object : PikoClientProvider {
                override val currentClient = MutableStateFlow<PikPakClient?>(null)
            }
            val statuses = listOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.PAUSED)
            val saved = nodes.mapIndexed { index, node ->
                DownloadTask(node.id, node.id, node.name, node.hash, contents[index].size.toLong(),
                    status = statuses[index], account = client.account)
            }
            val prefs = MemoryPreferences().apply {
                downloadTasks = Json.encodeToString(ListSerializer(DownloadTask.serializer()), saved)
            }
            val restored = PikoDownloadCoordinator(provider, prefs, Storage(directory), CoroutineScope(scope.coroutineContext + coordinatorJob))
            restored.flushPersistence()
            assertTrue(restored.tasks.value.values.all { it.status == DownloadStatus.PAUSED })
            // 等待账号期间再次落盘，也不能把中断的工作误记成手动暂停。
            val persisted = Json.decodeFromString(ListSerializer(DownloadTask.serializer()), prefs.downloadTasks)
            assertEquals(listOf(DownloadStatus.PENDING, DownloadStatus.PENDING, DownloadStatus.PAUSED), persisted.map { it.status })
            assertEquals(0, server.count("GET /${nodes[0].id}"))
            provider.currentClient.value = client
            awaitUntil("中断任务自动恢复完成") {
                nodes.take(2).all { restored.tasks.value[it.id]?.status == DownloadStatus.COMPLETED }
            }
            nodes.take(2).forEachIndexed { index, node ->
                assertContentEquals(contents[index], directory.resolve(node.name).readBytes())
                assertTrue(server.count("GET /${node.id}") > 0)
            }
            assertEquals(DownloadStatus.PAUSED, restored.tasks.value.getValue(nodes[2].id).status)
            assertEquals(0, server.count("GET /${nodes[2].id}"), "手动暂停任务不应请求 CDN")
        } finally {
            coordinatorJob.cancelAndJoin()
            directory.deleteRecursively()
        }
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
