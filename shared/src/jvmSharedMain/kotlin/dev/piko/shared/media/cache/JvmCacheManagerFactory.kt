package dev.piko.shared.media.cache

import dev.piko.shared.media.FileClipCache
import dev.piko.shared.media.storyboard.StoryboardCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JVM 平台（Desktop / Android）统一构建 [PikoCacheManager] 的装配工厂：
 * - `IMAGES`: Coil 图片磁盘缓存目录（支持清理与 LRU+TTL 裁剪）；
 * - `PLAYBACK`: [PikoFileCachePool] 持久化播放分块缓存（严格保护在途下载租约与播放中流）；
 * - `STORYBOARD`: [StoryboardCache] 预览雪碧图缓存；
 * - `CLIPS`: [FileClipCache] 信息流切片缓存；
 * - `TREE_INDEX`: SQLite 目录树索引库只读体积统计（严禁直接删除打开中的 WAL）。
 */
fun createJvmCacheManager(
    scope: CoroutineScope,
    imageCacheDir: File,
    fileCachePool: PikoFileCachePool,
    storyboardCache: StoryboardCache,
    clipCache: FileClipCache?,
    treeIndexDir: File,
    initialPolicy: CachePolicy = CachePolicy(),
    onClearImages: (suspend () -> Unit)? = null,
): PikoCacheManager {
    val backends = LinkedHashMap<CacheCategory, CacheCategoryBackend>(5)
    backends[CacheCategory.IMAGES] = DirectoryCacheBackend(
        category = CacheCategory.IMAGES,
        directory = imageCacheDir,
        clearable = true,
        onClearHook = onClearImages,
    )
    backends[CacheCategory.PLAYBACK] = fileCachePool.asCacheBackend()
    backends[CacheCategory.STORYBOARD] = storyboardCache.asCacheBackend()
    if (clipCache != null) {
        backends[CacheCategory.CLIPS] = clipCache.asCacheBackend()
    }
    backends[CacheCategory.TREE_INDEX] = ReadOnlyDirectoryStatsBackend(
        category = CacheCategory.TREE_INDEX,
        directory = treeIndexDir,
    )
    val cacheRootDir = imageCacheDir.parentFile ?: imageCacheDir
    return PikoCacheManager(
        backends = backends,
        scope = scope,
        initialPolicy = initialPolicy,
        deviceFreeBytesProvider = { queryUsableSpace(cacheRootDir) },
    )
}

private fun queryUsableSpace(directory: File): Long {
    val existing = generateSequence(directory.absoluteFile) { it.parentFile }
        .firstOrNull { it.exists() }
        ?: return 0L
    return runCatching { existing.usableSpace }.getOrDefault(0L).coerceAtLeast(0L)
}

internal class DirectoryCacheBackend(
    override val category: CacheCategory,
    private val directory: File,
    override val clearable: Boolean = true,
    private val onClearHook: (suspend () -> Unit)? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CacheCategoryBackend {
    override suspend fun scan(): CacheCategoryStats = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) {
            return@withContext CacheCategoryStats(category, 0L, 0, clearable)
        }
        var bytes = 0L
        var count = 0
        for (file in directory.walkTopDown()) {
            if (file.isFile) {
                bytes += file.length()
                count++
            }
        }
        CacheCategoryStats(category, bytes, count, clearable)
    }

    override suspend fun clear(): Long = withContext(Dispatchers.IO) {
        if (!clearable) return@withContext 0L
        val before = scan().bytes
        if (onClearHook != null) {
            runCatching { onClearHook.invoke() }
        }
        var deletedBytes = 0L
        if (directory.isDirectory) {
            for (file in directory.walkBottomUp()) {
                if (file == directory) continue
                // 保留 Coil DiskCache 的 journal 结构（由 onClearHook 清理）；若无 hook 则清空文件
                if (onClearHook != null && file.name.equals("journal", ignoreCase = true)) continue
                val size = if (file.isFile) file.length() else 0L
                if (file.delete() && size > 0L) {
                    deletedBytes += size
                }
            }
        }
        val after = scan().bytes
        maxOf(deletedBytes, (before - after).coerceAtLeast(0L))
    }

    override suspend fun prune(maxAgeMillis: Long, targetMaxBytes: Long): Long = withContext(Dispatchers.IO) {
        if (!clearable || !directory.isDirectory) return@withContext 0L
        val now = nowMillis()
        val files = directory.walkTopDown()
            .filter { it.isFile && !it.name.equals("journal", ignoreCase = true) && !it.name.endsWith(".tmp") }
            .map { Triple(it, it.length(), it.lastModified()) }
            .toList()
        var freed = 0L
        val remaining = ArrayList<Triple<File, Long, Long>>(files.size)
        for (entry in files) {
            val (file, size, mtime) = entry
            if (maxAgeMillis > 0L && (now - mtime) > maxAgeMillis) {
                if (file.delete()) freed += size else remaining += entry
            } else {
                remaining += entry
            }
        }
        if (targetMaxBytes >= 0L) {
            var current = remaining.sumOf { it.second }
            if (current > targetMaxBytes) {
                for ((file, size, _) in remaining.sortedBy { it.third }) {
                    if (current <= targetMaxBytes) break
                    if (file.delete()) {
                        current -= size
                        freed += size
                    }
                }
            }
        }
        freed
    }
}

internal class ReadOnlyDirectoryStatsBackend(
    override val category: CacheCategory,
    private val directory: File,
) : CacheCategoryBackend {
    override val clearable: Boolean = false

    override suspend fun scan(): CacheCategoryStats = withContext(Dispatchers.IO) {
        if (!directory.isDirectory) {
            return@withContext CacheCategoryStats(category, 0L, 0, clearable = false)
        }
        var bytes = 0L
        var count = 0
        for (file in directory.walkTopDown()) {
            if (file.isFile) {
                bytes += file.length()
                count++
            }
        }
        CacheCategoryStats(category, bytes, count, clearable = false)
    }

    override suspend fun clear(): Long = 0L
    override suspend fun prune(maxAgeMillis: Long, targetMaxBytes: Long): Long = 0L
}
