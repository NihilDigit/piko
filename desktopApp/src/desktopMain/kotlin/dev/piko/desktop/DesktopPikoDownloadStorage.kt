package dev.piko.desktop

import dev.piko.shared.PikoHome
import dev.piko.shared.download.PikoDownloadStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 下载目录每次现取：设置页改了位置之后，新任务直接落到新目录，不必重建下载调度器。 */
class DesktopPikoDownloadStorage(
    private val cacheDirectory: File = PikoHome.downloadCacheRoot.toFile(),
    private val legacyCacheDirectory: File? = PikoHome.root.resolve("download-cache").toFile()
        .takeIf { it.toPath().toAbsolutePath().normalize() != cacheDirectory.toPath().toAbsolutePath().normalize() },
    private val usePackCache: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
    private val directoryProvider: () -> File,
) : PikoDownloadStorage {
    constructor(
        cacheDirectory: File,
        directoryProvider: () -> File,
    ) : this(
        cacheDirectory = cacheDirectory,
        legacyCacheDirectory = null,
        usePackCache = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
        directoryProvider = directoryProvider,
    )

    override suspend fun cacheTarget(name: String): String = withContext(Dispatchers.IO) {
        // Windows 统一使用顺序追加块包（.pack），且绝不回指旧目录里的稀疏 .data 文件，避免取消或清理时删除旧稀疏文件触发误报；
        // macOS 与 Linux 继续使用稀疏文件（.data）。
        if (usePackCache) {
            val primary = cacheDirectory.resolve("$name.pack")
            if (!primary.exists()) {
                val legacyPack = legacyCacheDirectory?.resolve("$name.pack")
                if (legacyPack != null && legacyPack.isFile) {
                    return@withContext legacyPack.absolutePath
                }
            }
            if (!cacheDirectory.isDirectory) cacheDirectory.mkdirs()
            return@withContext primary.absolutePath
        }
        val primary = cacheDirectory.resolve("$name.data")
        if (!primary.exists() && !File("${primary.path}.blocks").exists()) {
            val legacy = legacyCacheDirectory?.resolve("$name.data")
            if (legacy != null && (legacy.isFile || File("${legacy.path}.blocks").isFile)) {
                return@withContext legacy.absolutePath
            }
        }
        if (!cacheDirectory.isDirectory) cacheDirectory.mkdirs()
        primary.absolutePath
    }
    private val directory: File get() = directoryProvider()

    override fun forDirectory(directory: String?): PikoDownloadStorage {
        val normalized = directory?.trim()?.takeIf { it.isNotEmpty() } ?: return this
        val target = File(normalized)
        return DesktopPikoDownloadStorage(
            cacheDirectory = cacheDirectory,
            legacyCacheDirectory = legacyCacheDirectory,
            usePackCache = usePackCache,
            directoryProvider = { target },
        )
    }

    // File.resolve 在 Windows 上也认 / 分隔的相对路径
    override fun pathFor(fileName: String): String = directory.resolve(fileName).absolutePath

    override suspend fun downloadTarget(fileName: String): String = withContext(Dispatchers.IO) {
        val target = directory.resolve(fileName)
        target.parentFile?.let { parent -> if (!parent.isDirectory) parent.mkdirs() }
        target.absolutePath
    }

    override suspend fun commit(fileName: String, downloadedPath: String): String = downloadedPath

    override suspend fun existingLength(fileName: String): Long = withContext(Dispatchers.IO) {
        directory.resolve(fileName).takeIf { it.isFile }?.length() ?: 0L
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        directory.resolve(fileName).exists()
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).takeIf { it.isAbsolute }?.delete() == true || directory.resolve(path).delete()
    }

    override suspend fun locate(fileName: String): String? = withContext(Dispatchers.IO) {
        directory.resolve(fileName).takeIf { it.exists() }?.absolutePath
    }

    override suspend fun pruneEmptyFolders(folder: String) = withContext(Dispatchers.IO) {
        val root = directory.resolve(folder)
        // 自底向上：子文件夹先删空，上级才删得掉
        if (root.isDirectory) root.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
    }
}
