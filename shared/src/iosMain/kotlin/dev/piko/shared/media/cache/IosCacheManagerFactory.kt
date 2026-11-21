@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.media.cache

import dev.piko.shared.media.IosClipCache
import dev.piko.shared.media.storyboard.StoryboardCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970

/**
 * iOS 平台 [PikoCacheManager] 装配工厂：
 * - `IMAGES`: 基于 `NSFileManager` 实现体积统计、清理与 `NSFileModificationDate` TTL+LRU 淘汰；
 * - `PLAYBACK`: [PikoFileCachePool] 持久化播放分块缓存；
 * - `STORYBOARD`: [StoryboardCache] 预览雪碧图缓存；
 * - `CLIPS`: 直接托管给 [IosClipCache.asCacheBackend]（与内部 `lock` / `storedBytes` 严格同步）；
 * - `TREE_INDEX`: 只读体积统计，严禁直接删除打开中的 WAL。
 */
fun createIosCacheManager(
    scope: CoroutineScope,
    imageCacheDir: String,
    fileCachePool: PikoFileCachePool,
    storyboardCache: StoryboardCache,
    clipCache: IosClipCache?,
    treeIndexDir: String,
    initialPolicy: CachePolicy = CachePolicy(),
    clearImageCache: (suspend () -> Boolean)? = null,
): PikoCacheManager {
    val backends = LinkedHashMap<CacheCategory, CacheCategoryBackend>(5)
    backends[CacheCategory.IMAGES] = IosDirectoryCacheBackend(
        category = CacheCategory.IMAGES,
        directoryPath = imageCacheDir,
        clearable = true,
        customClear = clearImageCache,
    )
    backends[CacheCategory.PLAYBACK] = fileCachePool.asCacheBackend()
    backends[CacheCategory.STORYBOARD] = storyboardCache.asCacheBackend()
    if (clipCache != null) {
        backends[CacheCategory.CLIPS] = clipCache.asCacheBackend()
    }
    backends[CacheCategory.TREE_INDEX] = IosDirectoryCacheBackend(CacheCategory.TREE_INDEX, treeIndexDir, clearable = false)
    return PikoCacheManager(
        backends = backends,
        scope = scope,
        initialPolicy = initialPolicy,
        deviceFreeBytesProvider = { queryIosFreeSpace(imageCacheDir) },
    )
}

private fun queryIosFreeSpace(preferredPath: String): Long {
    val fm = NSFileManager.defaultManager
    val targetPath = when {
        fm.fileExistsAtPath(preferredPath) -> preferredPath
        else -> {
            val parent = preferredPath.substringBeforeLast('/', missingDelimiterValue = "")
            if (parent.isNotEmpty() && fm.fileExistsAtPath(parent)) parent else NSHomeDirectory()
        }
    }
    val attrs = fm.attributesOfFileSystemForPath(targetPath, error = null) ?: return 0L
    return (attrs[NSFileSystemFreeSize] as? NSNumber)?.longLongValue?.coerceAtLeast(0L) ?: 0L
}

private class IosDirectoryCacheBackend(
    override val category: CacheCategory,
    private val directoryPath: String,
    override val clearable: Boolean,
    private val customClear: (suspend () -> Boolean)? = null,
) : CacheCategoryBackend {
    private val files = NSFileManager.defaultManager

    override suspend fun scan(): CacheCategoryStats = withContext(Dispatchers.IO) {
        if (!files.fileExistsAtPath(directoryPath)) {
            return@withContext CacheCategoryStats(category, 0L, 0, clearable)
        }
        var bytes = 0L
        var count = 0
        fun walk(dir: String) {
            val children = files.contentsOfDirectoryAtPath(dir, error = null)?.filterIsInstance<String>() ?: return
            for (name in children) {
                val child = "$dir/$name"
                if (isDirectory(child)) {
                    walk(child)
                } else {
                    bytes += sizeOfFile(child)
                    count++
                }
            }
        }
        walk(directoryPath)
        CacheCategoryStats(category, bytes, count, clearable)
    }

    override suspend fun clear(): Long = withContext(Dispatchers.IO) {
        if (!clearable) return@withContext 0L
        if (customClear != null) {
            val before = if (files.fileExistsAtPath(directoryPath)) scan().bytes else 0L
            if (customClear.invoke()) {
                val after = if (files.fileExistsAtPath(directoryPath)) scan().bytes else 0L
                return@withContext (before - after).coerceAtLeast(0L)
            }
        }
        if (!files.fileExistsAtPath(directoryPath)) return@withContext 0L
        var freed = 0L
        fun clearDir(dir: String) {
            val children = files.contentsOfDirectoryAtPath(dir, error = null)?.filterIsInstance<String>() ?: return
            for (name in children) {
                val child = "$dir/$name"
                if (isDirectory(child)) {
                    clearDir(child)
                    files.removeItemAtPath(child, error = null)
                } else {
                    val size = sizeOfFile(child)
                    if (files.removeItemAtPath(child, error = null)) freed += size
                }
            }
        }
        clearDir(directoryPath)
        freed
    }

    override suspend fun prune(maxAgeMillis: Long, targetMaxBytes: Long): Long = withContext(Dispatchers.IO) {
        if (!clearable || !files.fileExistsAtPath(directoryPath)) return@withContext 0L
        val nowSec = NSDate().timeIntervalSince1970
        val maxAgeSec = maxAgeMillis / 1_000.0
        val entries = ArrayList<Triple<String, Long, Double>>()

        fun collect(dir: String) {
            val children = files.contentsOfDirectoryAtPath(dir, error = null)?.filterIsInstance<String>() ?: return
            for (name in children) {
                val child = "$dir/$name"
                if (isDirectory(child)) {
                    collect(child)
                } else if (!name.equals("journal", ignoreCase = true) && !name.endsWith(".tmp")) {
                    entries += Triple(child, sizeOfFile(child), modifiedAt(child))
                }
            }
        }
        collect(directoryPath)

        var freed = 0L
        val remaining = ArrayList<Triple<String, Long, Double>>(entries.size)
        for (entry in entries) {
            val (path, size, mtimeSec) = entry
            if (maxAgeMillis > 0L && (nowSec - mtimeSec) > maxAgeSec) {
                if (files.removeItemAtPath(path, error = null)) freed += size else remaining += entry
            } else {
                remaining += entry
            }
        }
        if (targetMaxBytes >= 0L) {
            var current = remaining.sumOf { it.second }
            if (current > targetMaxBytes) {
                for ((path, size, _) in remaining.sortedBy { it.third }) {
                    if (current <= targetMaxBytes) break
                    if (files.removeItemAtPath(path, error = null)) {
                        current -= size
                        freed += size
                    }
                }
            }
        }
        freed
    }

    private fun sizeOfFile(path: String): Long =
        (files.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    private fun isDirectory(path: String): Boolean =
        (files.attributesOfItemAtPath(path, error = null)?.get(NSFileType) as? String) == NSFileTypeDirectory

    private fun modifiedAt(path: String): Double =
        (files.attributesOfItemAtPath(path, error = null)?.get(NSFileModificationDate) as? NSDate)
            ?.timeIntervalSince1970 ?: 0.0
}
