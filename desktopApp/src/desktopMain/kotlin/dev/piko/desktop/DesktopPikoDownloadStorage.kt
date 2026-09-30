package dev.piko.desktop

import dev.piko.shared.download.PikoDownloadStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 下载目录每次现取：设置页改了位置之后，新任务直接落到新目录，不必重建下载调度器。 */
class DesktopPikoDownloadStorage(
    private val directoryProvider: () -> File,
) : PikoDownloadStorage {
    override suspend fun cacheTarget(name: String): String = withContext(Dispatchers.IO) {
        // 放在下载目录以外，目录大小与用户看到的文件不包含暂存数据。
        val root = File(System.getProperty("user.home"), ".piko/download-cache")
        root.mkdirs()
        root.resolve("$name.data").absolutePath
    }
    private val directory: File get() = directoryProvider()

    // File.resolve 在 Windows 上也认 / 分隔的相对路径
    override fun pathFor(fileName: String): String = directory.resolve(fileName).absolutePath

    override suspend fun downloadTarget(fileName: String): String = withContext(Dispatchers.IO) {
        val target = directory.resolve(fileName)
        target.parentFile?.mkdirs()
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
