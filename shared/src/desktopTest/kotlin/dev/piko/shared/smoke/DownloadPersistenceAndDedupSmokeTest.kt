package dev.piko.shared.smoke

import dev.piko.download.DownloadStatus
import dev.piko.download.DownloadTask
import dev.piko.shared.data.PikoClientProvider
import dev.piko.shared.data.PikoDriveRepository
import dev.piko.shared.download.DownloadFolderSource
import dev.piko.shared.download.DriveDownloadFolderSource
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.download.PikoDownloadStorage
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.media.cache.PikoFileCachePool
import dev.piko.shared.media.testing.FakePikPakCloud
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.StreamRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class DirectoryStorage(private val directory: File) : PikoDownloadStorage {
    override fun forDirectory(directory: String?): PikoDownloadStorage =
        if (directory.isNullOrBlank()) this else DirectoryStorage(File(directory))
    override fun pathFor(fileName: String): String = directory.resolve(fileName).absolutePath
    override suspend fun downloadTarget(fileName: String): String = directory.resolve(fileName).also { it.parentFile?.mkdirs() }.absolutePath
    override suspend fun commit(fileName: String, downloadedPath: String): String = downloadedPath
    override suspend fun existingLength(fileName: String): Long = directory.resolve(fileName).length()
    override suspend fun exists(fileName: String): Boolean = directory.resolve(fileName).exists()
    override suspend fun delete(path: String): Boolean = File(path).delete()
    override suspend fun locate(fileName: String): String? = directory.resolve(fileName).takeIf { it.exists() }?.absolutePath
    override suspend fun pruneEmptyFolders(folder: String) = Unit
}

class DownloadPersistenceAndDedupSmokeTest {

    @Test
    fun `batch reuses complete disk files and avoids colliding with existing user files`() = smoke { scope ->
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
            val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), scope)
            coordinator.enqueueFiles(files)
            awaitUntil("all completed") { coordinator.tasks.value.size == 3 && coordinator.tasks.value.values.all { it.status == DownloadStatus.COMPLETED } }
            nodes.forEachIndexed { index, node ->
                assertEquals(node.name, coordinator.tasks.value.getValue(node.id).fileName)
                assertContentEquals(content[index], directory.resolve(node.name).readBytes())
                assertFalse(directory.resolve("part${index + 1} (2).bin").exists())
            }
            assertEquals(0, server.count("GET /${nodes[0].id}"))
            assertEquals(0, server.count("GET /${nodes[1].id}"))
            assertTrue(server.count("GET /${nodes[2].id}") > 0)
        } finally { directory.deleteRecursively() }

        val collisionRoot = Files.createTempDirectory("piko-collision").toFile()
        try {
            val server = FakePikPakServer()
            server.addFile("a:b.txt", hash = "ONE", content = byteArrayOf(1, 2))
            server.addFile("a b.txt", hash = "TWO", content = byteArrayOf(3, 4, 5))
            server.addFile("empty.txt", hash = "EMPTY", content = byteArrayOf())
            collisionRoot.resolve("a b.txt").writeBytes(byteArrayOf(9))
            val provider = server.provider()
            val prefs = MemoryPreferences()
            val coordinator = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(collisionRoot), scope)
            coordinator.enqueueFiles(PikoDriveRepository(provider, prefs).listAllFiles().getOrThrow())
            awaitUntil("collision batch done") { coordinator.tasks.value.size == 3 && coordinator.tasks.value.values.all { it.status == DownloadStatus.COMPLETED } }
            assertContentEquals(byteArrayOf(9), collisionRoot.resolve("a b.txt").readBytes())
            assertEquals(setOf("a b (2).txt", "a b (3).txt", "empty.txt"), coordinator.tasks.value.values.map { it.fileName }.toSet())
            assertEquals(0L, collisionRoot.resolve("empty.txt").length())
        } finally { collisionRoot.deleteRecursively() }
    }

    @Test
    fun `restore discovers retained sparse blocks and drops missing completed files`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-sparse-restore").toFile()
        try {
            val content = Random(44).nextBytes(2 * 1024 * 1024 + 17)
            val server = FakePikPakServer()
            val remote = server.addFile("partial.bin", content = content, hash = "PARTIAL44")
            val provider = server.provider()
            val client = provider.currentClient.value!!
            val storage = DirectoryStorage(directory)
            val pool = PikoFileCachePool(scope) { storage.cacheTarget(it) }
            val lease = pool.acquire(client, remote.id, remote.hash, content.size.toLong(), remote.name, retained = true)
            lease.entry.cache.download(listOf(0L..262143L), StreamRole.BACKGROUND).await()
            lease.entry.store.flush()
            val held = lease.entry.store.heldBytes.value
            assertTrue(held > 0 && held < content.size)
            lease.release()
            directory.resolve("kept.mkv").writeBytes(ByteArray(300))
            val savedTasks = listOf(
                DownloadTask(remote.id, remote.id, remote.name, remote.hash, content.size.toLong(), status = DownloadStatus.PAUSED, account = client.account, sparseCache = false),
                DownloadTask("kept", "kept", "kept.mkv", "GCIDkept", 300, downloadedBytes = 300, status = DownloadStatus.COMPLETED, destinationPath = "kept.mkv"),
                DownloadTask("gone", "gone", "gone.mkv", "GCIDgone", 300, downloadedBytes = 300, status = DownloadStatus.COMPLETED, destinationPath = "gone.mkv"),
            )
            val prefs = MemoryPreferences().apply { downloadTasks = Json.encodeToString(ListSerializer(DownloadTask.serializer()), savedTasks) }
            val restored = PikoDownloadCoordinator(provider, prefs, storage, scope)
            restored.flushPersistence()
            restored.fileCachePool.prune()
            assertEquals(setOf(remote.id, "kept"), restored.tasks.value.keys)
            val paused = restored.tasks.value.getValue(remote.id)
            assertTrue(paused.sparseCache)
            assertEquals(held, paused.downloadedBytes)
            restored.startDownload(remote.id)
            awaitUntil("sparse resume completed") { restored.tasks.value[remote.id]?.status == DownloadStatus.COMPLETED }
            assertContentEquals(content, directory.resolve(remote.name).readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `pausing mid-download and resuming yields exact original bytes`() = smoke { scope ->
        val content = Random(42).nextBytes(5 * 512 * 1024 + 123)
        val remaining = CompletableDeferred<Unit>()
        val cloud = FakePikPakCloud(content, beforeCdnChunk = { offset ->
            if (offset >= PikPakStreamReader.DEFAULT_BLOCK_SIZE) remaining.await()
        })
        val directory = Files.createTempDirectory("piko-resume").toFile()
        val coordinator = PikoDownloadCoordinator(cloud.provider, MemoryPreferences(), DirectoryStorage(directory), scope)
        val file = FileStat(id = "f1", name = "movie.mkv", size = content.size.toString(), hash = "GCIDFAKE0001")
        try {
            coordinator.enqueue(file)
            awaitUntil("partial bytes written") { (coordinator.tasks.value[file.id]?.downloadedBytes ?: 0) > 0 }
            coordinator.pauseDownload(file.id)
            awaitUntil("task paused") { coordinator.tasks.value[file.id]?.let { it.status == DownloadStatus.PAUSED && it.downloadedBytes > 0 } == true }
            assertTrue(coordinator.tasks.value.getValue(file.id).downloadedBytes < content.size)
            remaining.complete(Unit)
            coordinator.startDownload(file.id)
            awaitUntil("download completed", timeoutMs = 20_000) { coordinator.tasks.value[file.id]?.status == DownloadStatus.COMPLETED }
            assertContentEquals(content, directory.resolve("movie.mkv").readBytes())
        } finally {
            remaining.complete(Unit)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `interrupted active downloads resume when account returns while manually paused tasks stay paused`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-active-restore").toFile()
        val coordinatorJob = SupervisorJob(scope.coroutineContext[Job])
        try {
            val server = FakePikPakServer()
            val contents = (1..3).map { Random(it + 80).nextBytes(32 * 1024) }
            val nodes = contents.mapIndexed { index, bytes -> server.addFile("restore$index.bin", content = bytes, hash = "RESTORE$index") }
            val client = server.client()
            val provider = object : PikoClientProvider { override val currentClient = MutableStateFlow<PikPakClient?>(null) }
            val statuses = listOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.PAUSED)
            val saved = nodes.mapIndexed { index, node ->
                DownloadTask(node.id, node.id, node.name, node.hash, contents[index].size.toLong(), status = statuses[index], account = client.account)
            }
            val prefs = MemoryPreferences().apply { downloadTasks = Json.encodeToString(ListSerializer(DownloadTask.serializer()), saved) }
            val restored = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), CoroutineScope(scope.coroutineContext + coordinatorJob))
            restored.flushPersistence()
            val persisted = Json.decodeFromString(ListSerializer(DownloadTask.serializer()), prefs.downloadTasks)
            assertEquals(listOf(DownloadStatus.PENDING, DownloadStatus.PENDING, DownloadStatus.PAUSED), persisted.map { it.status })
            provider.currentClient.value = client
            awaitUntil("interrupted tasks finished") { nodes.take(2).all { restored.tasks.value[it.id]?.status == DownloadStatus.COMPLETED } }
            assertEquals(DownloadStatus.PAUSED, restored.tasks.value.getValue(nodes[2].id).status)
            assertEquals(0, server.count("GET /${nodes[2].id}"))
        } finally {
            coordinatorJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `pending folder listing survives restart and malformed persisted table is never overwritten`() = smoke { scope ->
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
            val first = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), CoroutineScope(scope.coroutineContext + firstJob), cacheStore = cache)
            first.enqueueFolders(listOf(folderFile), blocked)
            entered.await()
            first.flushPersistence()
            firstJob.cancelAndJoin()
            val restored = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(directory), scope, cacheStore = cache)
            restored.flushPersistence()
            assertEquals(1, restored.listings.value.size)
            restored.bindDefaultFolderSource(DriveDownloadFolderSource(repository))
            awaitUntil("folder download finished") { restored.tasks.value[remote.id]?.status == DownloadStatus.COMPLETED && restored.listings.value.isEmpty() }
            assertContentEquals(content, directory.resolve("Collection/episode.bin").readBytes())

            val invalidPrefs = MemoryPreferences().apply { downloadTasks = "invalid-json" }
            val invalidCoordinator = PikoDownloadCoordinator(provider, invalidPrefs, DirectoryStorage(directory), scope)
            invalidCoordinator.flushPersistence()
            assertEquals("invalid-json", invalidPrefs.downloadTasks)
        } finally {
            firstJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `multiple folders and file batches share concurrency cap and pauseAll keeps queue stopped`() = smoke { scope ->
        val root = Files.createTempDirectory("piko-queue").toFile()
        try {
            val server = FakePikPakServer().apply { cdnDelayMs = 80 }
            val first = server.addFolder("a:b")
            val second = server.addFolder("a b")
            repeat(3) { number ->
                server.addFile("$number.mkv", first.id, hash = "F$number", content = ByteArray(2 * 1024 * 1024))
                server.addFile("$number.mkv", second.id, hash = "S$number", content = ByteArray(2 * 1024 * 1024))
                server.addFile("single-$number.txt", hash = "L$number", content = ByteArray(2 * 1024 * 1024))
            }
            val provider = server.provider()
            val prefs = MemoryPreferences()
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
            awaitUntil("all queued") { coordinator.tasks.value.size == 9 && coordinator.listings.value.isEmpty() && maximum.get() >= 1 }
            coordinator.pauseAll()
            awaitUntil("all paused") { coordinator.tasks.value.values.all { it.status == DownloadStatus.PAUSED } }
            delay(150)
            assertTrue(coordinator.tasks.value.values.none { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING })
            assertTrue(maximum.get() in 1..3)
            server.cdnDelayMs = 0
            coordinator.tasks.value.values.mapNotNull { it.batch?.id }.distinct().forEach(coordinator::resumeBatch)
            awaitUntil("all completed", 20_000) { coordinator.tasks.value.values.all { it.status == DownloadStatus.COMPLETED } }
            assertTrue(maximum.get() <= 3)
            assertTrue(root.resolve("a b/0.mkv").exists())
            assertTrue(root.resolve("a b (2)/0.mkv").exists())
            observer.cancel()
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `leased content downloads work without owned file id and survive pause across restart`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-content-download").toFile()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bytes = ByteArray(2 * 1024 * 1024 + 17) { (it % 251).toByte() }
            val server = FakePikPakServer()
            val target = server.addFolder("Dramas")
            val original = server.addFile("owner-video.mkv", "owner", bytes, "CONTENT")
            val coordinator = PikoDownloadCoordinator(server.provider(), MemoryPreferences(), DirectoryStorage(directory), scope)
            val resolved = ResolvedFile("episode.mkv", bytes.size.toLong(), "CONTENT")
            coordinator.enqueueResolved(listOf(resolved, resolved.copy(gcid = null)), target.id)
            awaitUntil("leased download completed", 20_000) { coordinator.tasks.value.values.singleOrNull()?.status == DownloadStatus.COMPLETED }
            assertContentEquals(bytes, directory.resolve("episode.mkv").readBytes())
            assertTrue(coordinator.tasks.value.values.single().leasedSource)
            assertNotNull(server.node(original.id))
            assertTrue(server.children(target.id).isEmpty())
            coordinator.enqueueResolved(listOf(resolved.copy(path = "other-name.mkv")), target.id)
            awaitUntil("deduplicated") { coordinator.tasks.value.size == 1 }
            assertEquals(1, server.instantCreates.get())

            val resumeBytes = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
            val remainingRange = CompletableDeferred<Unit>()
            val resumeServer = FakePikPakServer().apply {
                beforeCdnRange = { from ->
                    if (from >= 4 * 1024 * 1024) remainingRange.await()
                }
            }
            val resumeTarget = resumeServer.addFolder("Dramas")
            resumeServer.addFile("owner.mkv", "owner", resumeBytes, "RESUME")
            val resumeDir = Files.createTempDirectory("piko-content-resume").toFile()
            val prefs = MemoryPreferences()
            val provider = resumeServer.provider()
            try {
                val first = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(resumeDir), firstScope)
                first.enqueueResolved(listOf(ResolvedFile("episode.mkv", resumeBytes.size.toLong(), "RESUME")), resumeTarget.id)
                awaitUntil("sparse bytes written") { first.tasks.value.values.singleOrNull()?.downloadedBytes?.let { it > 0 && it < resumeBytes.size } == true }
                val id = first.tasks.value.keys.single()
                first.pauseDownload(id)
                val serializer = ListSerializer(DownloadTask.serializer())
                awaitUntil("paused leased source saved") {
                    Json.decodeFromString(serializer, prefs.downloadTasks).singleOrNull()?.let { it.leasedSource && it.status == DownloadStatus.PAUSED } == true
                }
                firstScope.cancel()
                remainingRange.complete(Unit)
                val media = PikoMediaRepository(provider)
                val restarted = PikoDownloadCoordinator(provider, prefs, DirectoryStorage(resumeDir), scope, mediaRepository = media)
                awaitUntil("task restored") { restarted.tasks.value[id]?.leasedSource == true }
                media.openRandomAccess(id).getOrThrow().use { source ->
                    val tail = ByteArray(128)
                    source.readAt(resumeBytes.size - tail.size.toLong(), tail, 0, tail.size)
                    assertContentEquals(resumeBytes.copyOfRange(resumeBytes.size - tail.size, resumeBytes.size), tail)
                }
                restarted.startDownload(id)
                awaitUntil("resumed leased download completed", 20_000) { restarted.tasks.value[id]?.status == DownloadStatus.COMPLETED }
                assertContentEquals(resumeBytes, resumeDir.resolve("episode.mkv").readBytes())
                assertTrue(resumeServer.children(resumeTarget.id).isEmpty())
            } finally {
                remainingRange.complete(Unit)
                resumeDir.deleteRecursively()
            }
        } finally {
            firstScope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancelling active download clears sparse cache without creating target folder or deleting download target`() = smoke { scope ->
        val directory = Files.createTempDirectory("piko-cancel-safe").toFile()
        val blockedRange = CompletableDeferred<Unit>()
        try {
            val largeBytes = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
            val smallBytes = ByteArray(64 * 1024) { (it % 199).toByte() }
            val server = FakePikPakServer().apply {
                beforeCdnRange = { from ->
                    if (from >= 2 * 1024 * 1024) blockedRange.await()
                }
            }
            val showFolder = server.addFolder("Show")
            server.addFile("episode.mkv", showFolder.id, content = largeBytes, hash = "SHOWEP1")
            val flatNode = server.addFile("after-enqueue.bin", content = largeBytes, hash = "FLAT1")
            val completedNode = server.addFile("completed.bin", content = smallBytes, hash = "SMALL1")
            val provider = server.provider()
            val prefs = MemoryPreferences()
            val drive = PikoDriveRepository(provider, prefs)
            val storage = DirectoryStorage(directory)
            val coordinator = PikoDownloadCoordinator(provider, prefs, storage, scope)
            val cacheDir = directory.resolve(".piko-cache")

            // 子文件夹任务在 DOWNLOADING 阶段取消：必须删掉 .piko-cache，且不得新建 Show/ 子目录
            val rootFiles = drive.listAllFiles().getOrThrow()
            coordinator.enqueueFolders(rootFiles.filter { it.id == showFolder.id }, DriveDownloadFolderSource(drive))
            awaitUntil("subfolder task downloading") {
                val task = coordinator.tasks.value.values.singleOrNull()
                task?.status == DownloadStatus.DOWNLOADING &&
                    task.downloadedBytes in 1L until largeBytes.size &&
                    cacheDir.listFiles()?.isNotEmpty() == true
            }
            assertFalse(directory.resolve("Show").exists())
            val subfolderTaskId = coordinator.tasks.value.keys.single()
            coordinator.cancelDownload(subfolderTaskId)?.join()
            awaitUntil("subfolder cache cleaned") {
                coordinator.tasks.value.isEmpty() && (cacheDir.listFiles()?.isEmpty() ?: true)
            }
            assertFalse(directory.resolve("Show").exists())

            // 单文件任务在 DOWNLOADING 阶段取消：不得删除入队后写入下载目录的同名无关文件
            coordinator.enqueueFiles(rootFiles.filter { it.id == flatNode.id })
            awaitUntil("flat task downloading") {
                val task = coordinator.tasks.value.values.singleOrNull()
                task?.status == DownloadStatus.DOWNLOADING &&
                    task.downloadedBytes in 1L until largeBytes.size &&
                    cacheDir.listFiles()?.isNotEmpty() == true
            }
            val userFile = directory.resolve("after-enqueue.bin").apply { writeText("keep-user-file") }
            val flatTaskId = coordinator.tasks.value.keys.single()
            coordinator.cancelDownload(flatTaskId)?.join()
            awaitUntil("flat cache cleaned") {
                coordinator.tasks.value.isEmpty() && (cacheDir.listFiles()?.isEmpty() ?: true)
            }
            assertTrue(userFile.exists())
            assertEquals("keep-user-file", userFile.readText())

            // 已完成任务取消时仍正常删除已交付的 destinationPath
            coordinator.enqueueFiles(rootFiles.filter { it.id == completedNode.id })
            awaitUntil("small task completed") {
                coordinator.tasks.value.values.singleOrNull()?.status == DownloadStatus.COMPLETED
            }
            val delivered = directory.resolve("completed.bin")
            assertTrue(delivered.exists())
            assertContentEquals(smallBytes, delivered.readBytes())
            val completedTaskId = coordinator.tasks.value.keys.single()
            coordinator.cancelDownload(completedTaskId)?.join()
            assertFalse(delivered.exists())
            awaitUntil("completed cache cleaned") {
                coordinator.tasks.value.isEmpty() && (cacheDir.listFiles()?.isEmpty() ?: true)
            }
        } finally {
            blockedRange.complete(Unit)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `per-request targetDirectory overrides default folder for files, folders, and restart restoration`() = smoke { scope ->
        val defaultDir = Files.createTempDirectory("piko-default-dir").toFile()
        val customDir1 = Files.createTempDirectory("piko-custom-dir-1").toFile()
        val customDir2 = Files.createTempDirectory("piko-custom-dir-2").toFile()
        val firstJob = SupervisorJob(scope.coroutineContext[Job])
        val secondJob = SupervisorJob(scope.coroutineContext[Job])
        try {
            val server = FakePikPakServer()
            val singleContent = Random(91).nextBytes(24 * 1024)
            val folderContent = Random(92).nextBytes(16 * 1024)
            val singleNode = server.addFile("movie.mkv", content = singleContent, hash = "CUSTOMSINGLE1")
            val albumFolder = server.addFolder("Album")
            server.addFile("track.flac", parentId = albumFolder.id, content = folderContent, hash = "CUSTOMFOLDER1")
            val provider = server.provider()
            val prefs = MemoryPreferences()
            val drive = PikoDriveRepository(provider, prefs)
            val rootItems = drive.listAllFiles().getOrThrow()
            val singleStat = rootItems.first { it.id == singleNode.id }
            val folderStat = rootItems.first { it.id == albumFolder.id }

            val firstCoordinator = PikoDownloadCoordinator(
                provider,
                prefs,
                DirectoryStorage(defaultDir),
                CoroutineScope(scope.coroutineContext + firstJob),
            )

            // 单文件下载到自定义目录 1，不应落在默认目录
            firstCoordinator.enqueue(singleStat, targetDirectory = customDir1.absolutePath)
            awaitUntil("single custom dir completed") {
                firstCoordinator.tasks.value.values.any {
                    it.fileId == singleStat.id && it.targetDirectory == customDir1.absolutePath && it.status == DownloadStatus.COMPLETED
                }
            }
            assertFalse(defaultDir.resolve("movie.mkv").exists())
            assertContentEquals(singleContent, customDir1.resolve("movie.mkv").readBytes())
            assertEquals(
                customDir1.resolve("movie.mkv").canonicalPath,
                firstCoordinator.findCompletedLocalPath(singleStat)?.let { File(it).canonicalPath },
            )

            // 同一文件再次下载到自定义目录 2，生成独立任务并落盘到目录 2，不覆盖目录 1 任务
            firstCoordinator.enqueue(singleStat, targetDirectory = customDir2.absolutePath)
            awaitUntil("second custom dir completed") {
                firstCoordinator.tasks.value.values.count { it.fileId == singleStat.id && it.status == DownloadStatus.COMPLETED } == 2
            }
            assertContentEquals(singleContent, customDir1.resolve("movie.mkv").readBytes())
            assertContentEquals(singleContent, customDir2.resolve("movie.mkv").readBytes())

            // 文件夹下载到自定义目录 1
            val batchCount = firstCoordinator.enqueueFolders(
                listOf(folderStat),
                DriveDownloadFolderSource(drive),
                targetDirectory = customDir1.absolutePath,
            )
            assertEquals(1, batchCount)
            awaitUntil("custom dir folder batch completed") {
                firstCoordinator.tasks.value.values.any {
                    it.batch?.sourceFolderId == folderStat.id && it.status == DownloadStatus.COMPLETED
                }
            }
            assertFalse(defaultDir.resolve("Album").exists())
            assertContentEquals(folderContent, customDir1.resolve("Album/track.flac").readBytes())
            val batch = firstCoordinator.tasks.value.values.first { it.batch?.sourceFolderId == folderStat.id }.batch!!
            assertEquals(customDir1.absolutePath, batch.targetDirectory)
            assertEquals(
                customDir1.resolve("Album").canonicalPath,
                firstCoordinator.batchFolderPath(batch)?.let { File(it).canonicalPath },
            )

            // 重启恢复：目录 1 仍存在的任务保留，从目录 2 删除的文件自动剔除
            firstCoordinator.flushPersistence()
            firstJob.cancelAndJoin()
            assertTrue(customDir2.resolve("movie.mkv").delete())

            val restored = PikoDownloadCoordinator(
                provider,
                prefs,
                DirectoryStorage(defaultDir),
                CoroutineScope(scope.coroutineContext + secondJob),
            )
            restored.flushPersistence()
            assertEquals(2, restored.tasks.value.size)
            assertTrue(restored.tasks.value.values.all { it.targetDirectory == customDir1.absolutePath && it.status == DownloadStatus.COMPLETED })
            assertEquals(
                customDir1.resolve("movie.mkv").canonicalPath,
                restored.findCompletedLocalPath(singleStat)?.let { File(it).canonicalPath },
            )

            // 旧版不含 targetDirectory 字段的序列化任务无损反序列化为 null
            val legacyJson = """{"taskId":"legacy","fileId":"f0","fileName":"old.bin","gcid":"G0","totalBytes":10}"""
            val decodedLegacy = Json { ignoreUnknownKeys = true }.decodeFromString(DownloadTask.serializer(), legacyJson)
            assertNull(decodedLegacy.targetDirectory)

            // 先按默认目录（targetDirectory = null）下载，再在选择器中显式确认同一个默认目录，必须复用已有任务不产生重复项
            val defaultNode = server.addFile("default-dedup.bin", content = singleContent, hash = "DEFAULTDEDUP1")
            val defaultStat = drive.listAllFiles().getOrThrow().first { it.id == defaultNode.id }
            restored.enqueue(defaultStat)
            awaitUntil("default dir initial download completed") {
                restored.tasks.value[defaultStat.id]?.status == DownloadStatus.COMPLETED
            }
            val fetchCountBefore = server.count("GET /${defaultNode.id}")
            restored.enqueue(defaultStat, targetDirectory = defaultDir.absolutePath)
            delay(150L)
            assertEquals(1, restored.tasks.value.values.count { it.fileId == defaultStat.id })
            assertEquals(fetchCountBefore, server.count("GET /${defaultNode.id}"))
            assertFalse(defaultDir.resolve("default-dedup (2).bin").exists())
        } finally {
            firstJob.cancelAndJoin()
            secondJob.cancelAndJoin()
            defaultDir.deleteRecursively()
            customDir1.deleteRecursively()
            customDir2.deleteRecursively()
        }
    }
}
