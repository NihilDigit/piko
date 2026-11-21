package dev.piko.shared.media.cache

import dev.piko.shared.media.FileClipCache
import dev.piko.shared.media.storyboard.FileStoryboardCache
import dev.piko.shared.media.storyboard.StoryboardBundle
import dev.piko.shared.media.storyboard.StoryboardFrame
import dev.piko.shared.media.testing.FakePikPakCloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PikoCacheManagerTest {
    private val blockSize = 256 * 1024

    private fun withTempDir(block: suspend (File, CoroutineScope) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("piko-cache-mgr-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(root, scope)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun persistentPlaybackCacheRetainsChunksReportsCoverageAndNeverEvictsDownloadLeases() = withTempDir { root, scope ->
        var fakeClock = 1_000_000L
        val cloud = FakePikPakCloud(ByteArray(blockSize * 2) { (it % 251).toByte() })
        val client = cloud.provider.currentClient.value!!
        val poolDir = File(root, "pool").apply { mkdirs() }
        val pool = PikoFileCachePool(scope, nowMillis = { fakeClock }) { poolDir.resolve("$it.data").path }

        // 1. 持久化播放缓存（仅写第 0 块，共 2 块 = 50% 覆盖）
        val playLease = pool.acquire(
            client = client,
            fileId = "f-play",
            gcid = "GCID_PLAY",
            size = (blockSize * 2).toLong(),
            name = "movie.mp4",
            retainForPlayback = true,
        )
        val block0Data = ByteArray(blockSize) { ((it * 7) % 251).toByte() }
        playLease.entry.store.write("k", 0L, block0Data)
        playLease.release()

        // 关闭播放后，磁盘应保留缓存，且 cachedCoverage / windowCached / readCachedBytes 均准确命中
        val covStart = pool.cachedCoverage(
            account = client.account,
            gcid = "GCID_PLAY",
            totalBytes = (blockSize * 2).toLong(),
            mediaId = null,
            windowStartByte = 0L,
            windowLengthBytes = 64L * 1024L,
        )
        assertEquals(blockSize.toLong(), covStart.cachedBytes)
        assertEquals(0.5f, covStart.coverageRatio, 0.01f)
        assertTrue(covStart.windowCached)

        val covUncachedEnd = pool.cachedCoverage(
            account = client.account,
            gcid = "GCID_PLAY",
            totalBytes = (blockSize * 2).toLong(),
            mediaId = null,
            windowStartByte = blockSize.toLong(),
            windowLengthBytes = 64L * 1024L,
        )
        assertFalse(covUncachedEnd.windowCached)

        val localSlice = pool.readCachedBytes(
            account = client.account,
            gcid = "GCID_PLAY",
            totalBytes = (blockSize * 2).toLong(),
            mediaId = null,
            offset = 1024L,
            length = 4096,
        )
        assertNotNull(localSlice)
        assertContentEquals(block0Data.copyOfRange(1024, 1024 + 4096), localSlice)
        assertNull(
            pool.readCachedBytes(
                account = client.account,
                gcid = "GCID_PLAY",
                totalBytes = (blockSize * 2).toLong(),
                mediaId = null,
                offset = blockSize.toLong(),
                length = 4096,
            ),
        )

        // 2. 启动孤儿清理 prune() 必须保留持久化播放缓存，同时照常删除无主孤儿文件
        val orphan = File(poolDir, "${"b".repeat(64)}.data").apply { writeBytes(ByteArray(128)) }
        pool.prune()
        assertFalse(orphan.exists(), "Orphan sparse file without .pplay marker should be pruned")
        assertTrue(
            pool.cachedCoverage(client.account, "GCID_PLAY", (blockSize * 2).toLong()).cachedBytes == blockSize.toLong(),
            "Persistent playback cache must survive startup orphan prune()",
        )

        // 3. 活跃下载租约（retained = true）绝不能被 clearPlaybackCache 或 PikoCacheManager.clearAll() 误删
        val dlLease = pool.acquire(
            client = client,
            fileId = "f-dl",
            gcid = "GCID_DL",
            size = blockSize.toLong(),
            name = "download.mkv",
            retained = true,
            owner = "dl-task-1",
        )
        dlLease.entry.store.write("dl", 0L, ByteArray(blockSize) { 9 })
        dlLease.release()

        // 同时打开一个正在播放中的流（references > 0），也不得在清理时被删除
        val activePlay = pool.acquire(
            client = client,
            fileId = "f-live",
            gcid = "GCID_LIVE",
            size = blockSize.toLong(),
            name = "live.mp4",
            retainForPlayback = true,
        )
        activePlay.entry.store.write("live", 0L, ByteArray(blockSize) { 3 })

        val cleared = pool.clearPlaybackCache()
        assertTrue(cleared >= blockSize.toLong(), "Expected closed playback cache to be cleared, freed=$cleared")
        assertEquals(0L, pool.cachedCoverage(client.account, "GCID_PLAY", (blockSize * 2).toLong()).cachedBytes)
        // 下载租约完好无损！
        assertEquals(blockSize.toLong(), pool.progress(client.account, "GCID_DL", blockSize.toLong()))
        // 正在播放的流仍可正常读出！
        assertNotNull(activePlay.entry.store.read("live", 0L, blockSize))
        activePlay.release()
    }

    @Test
    fun unifiedCacheManagerScansAll5CategoriesProtectsTreeIndexAndEnforcesLruAndTtl() = withTempDir { root, scope ->
        var fakeClock = 10_000_000_000L
        val imagesDir = File(root, "images").apply { mkdirs() }
        val poolDir = File(root, "pool").apply { mkdirs() }
        val sbDir = File(root, "storyboards").apply { mkdirs() }
        val clipsDir = File(root, "clips").apply { mkdirs() }
        val treeDir = File(root, "trees").apply { mkdirs() }

        // 造 5 类真实磁盘文件
        File(imagesDir, "img1.bin").writeBytes(ByteArray(12_000))
        val treeDb = File(treeDir, "tree-account.db").apply { writeBytes(ByteArray(40_000)) }
        val treeWal = File(treeDir, "tree-account.db-wal").apply { writeBytes(ByteArray(8_000)) }

        val clipCache = FileClipCache(clipsDir)
        clipCache.blocks.write("clipA", 0L, ByteArray(20_000) { 1 })

        val sbCache = FileStoryboardCache(sbDir.absolutePath, nowMillis = { fakeClock })
        sbCache.write(
            StoryboardBundle(
                gcid = "SB_OLD",
                durationMillis = 60_000L,
                width = 640,
                height = 360,
                completed = true,
                lastAccessEpochMillis = fakeClock - 20L * 86_400_000L, // 20 天前
                frames = listOf(StoryboardFrame(0, 0L, ByteArray(4096) { 2 })),
            ),
        )
        sbCache.write(
            StoryboardBundle(
                gcid = "SB_NEW",
                durationMillis = 60_000L,
                width = 640,
                height = 360,
                completed = true,
                lastAccessEpochMillis = fakeClock,
                frames = listOf(StoryboardFrame(0, 0L, ByteArray(4096) { 3 })),
            ),
        )

        val cloud = FakePikPakCloud(ByteArray(blockSize))
        val client = cloud.provider.currentClient.value!!
        val pool = PikoFileCachePool(scope, nowMillis = { fakeClock }) { poolDir.resolve("$it.data").path }
        // 写入两条播放缓存：OLD 在 T-10d，NEW 在 T
        fakeClock -= 10L * 86_400_000L
        val oldPlay = pool.acquire(client, "f1", "PLAY_OLD", blockSize.toLong(), "old.mp4", retainForPlayback = true)
        oldPlay.entry.store.write("k", 0L, ByteArray(blockSize) { 1 })
        oldPlay.release()

        fakeClock += 10L * 86_400_000L
        val newPlay = pool.acquire(client, "f2", "PLAY_NEW", blockSize.toLong(), "new.mp4", retainForPlayback = true)
        newPlay.entry.store.write("k", 0L, ByteArray(blockSize) { 2 })
        newPlay.release()

        val manager = createJvmCacheManager(
            scope = scope,
            imageCacheDir = imagesDir,
            fileCachePool = pool,
            storyboardCache = sbCache,
            clipCache = clipCache,
            treeIndexDir = treeDir,
        )

        val initial = manager.refresh()
        assertEquals(12_000L, initial.statsOf(CacheCategory.IMAGES).bytes)
        assertTrue(initial.statsOf(CacheCategory.PLAYBACK).bytes >= blockSize * 2L)
        assertTrue(initial.statsOf(CacheCategory.STORYBOARD).bytes > 8_000L)
        assertEquals(20_000L, initial.statsOf(CacheCategory.CLIPS).bytes)
        assertEquals(48_000L, initial.statsOf(CacheCategory.TREE_INDEX).bytes)
        assertFalse(initial.statsOf(CacheCategory.TREE_INDEX).clearable)
        assertEquals(initial.totalBytes - 48_000L, initial.clearableBytes)
        assertTrue(initial.deviceFreeBytes > 0L, "Expected positive deviceFreeBytes on JVM cache volume")
        assertEquals(
            (CachePolicy.DEFAULT_MAX_TOTAL_BYTES - initial.clearableBytes).coerceAtLeast(0L),
            initial.remainingQuotaBytes,
        )
        assertNull(initial.copy(policy = CachePolicy(maxTotalBytes = 0L)).remainingQuotaBytes)
        assertEquals(
            0L,
            initial.copy(policy = CachePolicy(maxTotalBytes = 1024L)).remainingQuotaBytes,
        )

        // 1. 尝试清理 TREE_INDEX 必须是空操作，绝不能删除 .db 或 .db-wal
        assertEquals(0L, manager.clearCategory(CacheCategory.TREE_INDEX))
        assertTrue(treeDb.exists() && treeWal.exists())

        // 2. 应用 14 天 TTL + 350 KiB 容量上限：
        //    - SB_OLD（20 天前）被 TTL 清理，SB_NEW 保留；
        //    - PLAY_OLD（较老 LRU）被容量上限淘汰，PLAY_NEW 保留！
        val freedByPolicy = manager.applyPolicy(maxTotalBytes = 350L * 1024L, maxAgeDays = 14)
        assertTrue(freedByPolicy >= blockSize.toLong(), "Expected policy to evict old playback & expired storyboard")
        assertNull(sbCache.read("SB_OLD"))
        assertNotNull(sbCache.read("SB_NEW"))
        assertEquals(0L, pool.cachedCoverage(client.account, "PLAY_OLD", blockSize.toLong()).cachedBytes)
        assertEquals(blockSize.toLong(), pool.cachedCoverage(client.account, "PLAY_NEW", blockSize.toLong()).cachedBytes)

        // 3. clearAll() 清空所有可清理分类，同时仍完好保留 TREE_INDEX
        val freedAll = manager.clearAll()
        assertTrue(freedAll > 0L)
        val afterClear = manager.summary.value
        assertEquals(0L, afterClear.clearableBytes)
        assertEquals(48_000L, afterClear.statsOf(CacheCategory.TREE_INDEX).bytes)
        assertTrue(treeDb.exists() && treeWal.exists())
    }

    @Test
    fun readCachedBytesConcurrencyWithAcquireReleaseAndClearOnPackAndSparseNeverThrows() = withTempDir { root, scope ->
        for (ext in listOf("pack", "data")) {
            val poolDir = File(root, "conc-$ext").apply { mkdirs() }
            val cloud = FakePikPakCloud(ByteArray(blockSize) { (it % 251).toByte() })
            val client = cloud.provider.currentClient.value!!
            val pool = PikoFileCachePool(scope) { poolDir.resolve("$it.$ext").path }
            val expected = ByteArray(blockSize) { ((it * 13 + 5) % 251).toByte() }

            val seed = pool.acquire(
                client = client,
                fileId = "f-conc",
                gcid = "GCID_CONC_$ext",
                size = blockSize.toLong(),
                name = "conc.ts",
                retainForPlayback = true,
            )
            seed.entry.store.write("k", 0L, expected)
            seed.release()

            // 16 路并发 readCachedBytes + acquire/release + clearPlaybackCache 交错执行：
            // 严禁出现 use-after-close、duplicate store 或 Windows PackFileStore 删除共享冲突异常。
            coroutineScope {
                val jobs = (0 until 12).map { i ->
                    async(Dispatchers.IO) {
                        repeat(15) { iter ->
                            val slice = pool.readCachedBytes(
                                account = client.account,
                                gcid = "GCID_CONC_$ext",
                                totalBytes = blockSize.toLong(),
                                offset = ((i * 1024 + iter * 128) % (blockSize - 4096)).toLong(),
                                length = 4096,
                            )
                            if (slice != null) {
                                val off = ((i * 1024 + iter * 128) % (blockSize - 4096))
                                assertContentEquals(expected.copyOfRange(off, off + 4096), slice)
                            }
                        }
                    }
                } + (0 until 2).map {
                    async(Dispatchers.IO) {
                        repeat(10) {
                            val l = pool.acquire(
                                client = client,
                                fileId = "f-conc",
                                gcid = "GCID_CONC_$ext",
                                size = blockSize.toLong(),
                                name = "conc.ts",
                                retainForPlayback = true,
                            )
                            pool.cachedCoverage(client.account, "GCID_CONC_$ext", blockSize.toLong(), windowStartByte = 0L, windowLengthBytes = 4096L)
                            l.release()
                        }
                    }
                } + listOf(
                    async(Dispatchers.IO) {
                        repeat(5) {
                            pool.prunePlaybackCache(maxAgeMillis = 0L, targetMaxBytes = Long.MAX_VALUE)
                        }
                    },
                )
                jobs.awaitAll()
            }

            // 全部并发读者退出后，clearPlaybackCache 必须能够干净删掉 .pack/.data 与 .pplay
            val freed = pool.clearPlaybackCache()
            assertTrue(freed > 0L, "Expected clearPlaybackCache to cleanly delete $ext cache after readers finished")
            assertEquals(0L, pool.cachedCoverage(client.account, "GCID_CONC_$ext", blockSize.toLong()).cachedBytes)
        }
    }

    @Test
    fun completeDownloadClearsPreviousPlaybackCacheMarkerAndRemovesDuplicateStaging() = withTempDir { root, scope ->
        for (ext in listOf("pack", "data")) {
            val poolDir = File(root, "complete-$ext").apply { mkdirs() }
            val cloud = FakePikPakCloud(ByteArray(blockSize) { 7 })
            val client = cloud.provider.currentClient.value!!
            val pool = PikoFileCachePool(scope) { poolDir.resolve("$it.$ext").path }

            // 1. 用户先在线播放了一段（写入持久化播放缓存与 .pplay 标记）
            val playLease = pool.acquire(
                client = client,
                fileId = "f-dup",
                gcid = "GCID_DUP_$ext",
                size = blockSize.toLong(),
                name = "movie.mkv",
                retainForPlayback = true,
            )
            playLease.entry.store.write("k", 0L, ByteArray(blockSize) { 7 })
            val stagingPath = playLease.entry.store.path
            playLease.release()
            assertTrue(File(stagingPath).exists())
            assertTrue(File("$stagingPath.pplay").exists())

            // 2. 随后用户把同一视频完整下载到了本地目录并调用 complete() + release()
            val dlLease = pool.acquire(
                client = client,
                fileId = "f-dup",
                gcid = "GCID_DUP_$ext",
                size = blockSize.toLong(),
                name = "movie.mkv",
                retained = true,
                owner = "dl-task-complete",
            )
            pool.complete(dlLease, "dl-task-complete")
            dlLease.release()

            // 3. 暂存文件与 .pplay 标记必须全部清除，不得在 cache/files 残存多 GB 重复副本
            assertFalse(File(stagingPath).exists(), "Completed download must remove duplicate $ext staging file")
            assertFalse(File("$stagingPath.pplay").exists(), "Completed download must remove .pplay sidecar marker")
            assertEquals(0L, pool.cachedCoverage(client.account, "GCID_DUP_$ext", blockSize.toLong()).cachedBytes)
        }
    }

    @Test
    fun discardByOwnerReleasesTranscodeAndSegmentPinsWhilePreservingPlaybackCache() = withTempDir { root, scope ->
        val poolDir = File(root, "discard-owner").apply { mkdirs() }
        val cloud = FakePikPakCloud(ByteArray(blockSize) { 5 })
        val client = cloud.provider.currentClient.value!!
        val pool = PikoFileCachePool(scope) { poolDir.resolve("$it.pack").path }

        // Simulate post-probe transcode download lease pinned under taskId.
        val transcodeLease = pool.acquire(
            client = client,
            fileId = "f-trans",
            gcid = "GCID_TRANS",
            size = (blockSize * 4).toLong(),
            streamSize = blockSize.toLong(),
            mediaId = "media-720p",
            name = "video.mkv",
            retained = true,
            owner = "dl-cancel-task",
        )
        transcodeLease.entry.store.write("t", 0L, ByteArray(blockSize) { 5 })
        val transcodePath = transcodeLease.entry.store.path
        transcodeLease.release()
        assertTrue(File(transcodePath).exists())

        // Also simulate a shared entry that has both a download owner pin AND persistent playback cache.
        val sharedPlay = pool.acquire(
            client = client,
            fileId = "f-shared",
            gcid = "GCID_SHARED",
            size = blockSize.toLong(),
            name = "shared.mp4",
            retained = true,
            owner = "dl-cancel-task",
            retainForPlayback = true,
        )
        sharedPlay.entry.store.write("s", 0L, ByteArray(blockSize) { 6 })
        val sharedPath = sharedPlay.entry.store.path
        sharedPlay.release()
        assertTrue(File("$sharedPath.pplay").exists())

        pool.discardByOwner("dl-cancel-task")

        // Non-playback transcode staging file is deleted; playback-cached file survives and is now clearable.
        assertFalse(File(transcodePath).exists(), "discardByOwner must delete unpinned non-playback staging file")
        assertTrue(File(sharedPath).exists(), "discardByOwner must preserve .pplay-marked playback cache")
        assertTrue(pool.clearPlaybackCache() >= blockSize.toLong(), "Unpinned playback cache must become clearable")
    }

    @Test
    fun poolShutdownFlushesDurablePlaybackStoreBeforeWritingMarker() = withTempDir { root, outerScope ->
        val poolDir = File(root, "shutdown-flush").apply { mkdirs() }
        val cloud = FakePikPakCloud(ByteArray(blockSize * 2) { (it % 251).toByte() })
        val client = cloud.provider.currentClient.value!!
        val poolJob = SupervisorJob()
        val poolScope = CoroutineScope(Dispatchers.Default + poolJob)
        val pool1 = PikoFileCachePool(poolScope, nowMillis = { 5_000_000L }) { poolDir.resolve("$it.pack").path }
        val data = ByteArray(blockSize) { ((it * 11 + 3) % 251).toByte() }

        val lease = pool1.acquire(
            client = client,
            fileId = "f-shut",
            gcid = "GCID_SHUTDOWN",
            size = (blockSize * 2).toLong(),
            name = "shutdown.mp4",
            retainForPlayback = true,
        )
        lease.entry.store.write(lease.entry.key, 0L, data)
        val packPath = lease.entry.store.path

        // Trigger pool scope cancellation while lease is still active.
        poolScope.cancel()
        val deadline = System.currentTimeMillis() + 5_000L
        while (!File("$packPath.pplay").exists() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
        }
        assertTrue(File("$packPath.pplay").exists(), "Shutdown hook must write .pplay after flushing store")

        val pool2 = PikoFileCachePool(outerScope, nowMillis = { 5_000_000L }) { poolDir.resolve("$it.pack").path }
        val cov = pool2.cachedCoverage(client.account, "GCID_SHUTDOWN", (blockSize * 2).toLong(), windowStartByte = 0L, windowLengthBytes = 4096L)
        assertEquals(blockSize.toLong(), cov.cachedBytes)
        assertTrue(cov.windowCached)
        val restored = pool2.readCachedBytes(client.account, "GCID_SHUTDOWN", (blockSize * 2).toLong(), offset = 0L, length = 4096)
        assertNotNull(restored)
        assertContentEquals(data.copyOfRange(0, 4096), restored)
    }
}
