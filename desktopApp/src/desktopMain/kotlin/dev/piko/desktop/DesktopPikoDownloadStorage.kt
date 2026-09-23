package dev.piko.desktop

import dev.piko.shared.download.PikoDownloadStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DesktopPikoDownloadStorage(
    private val directory: File = File(System.getProperty("user.home"), "Downloads/Piko"),
) : PikoDownloadStorage {
    override fun pathFor(fileName: String): String = directory.resolve(fileName).absolutePath

    override suspend fun downloadTarget(fileName: String): String = withContext(Dispatchers.IO) {
        directory.mkdirs()
        directory.resolve(fileName).absolutePath
    }

    override suspend fun commit(fileName: String, downloadedPath: String): String = downloadedPath

    override suspend fun existingLength(fileName: String): Long = withContext(Dispatchers.IO) {
        directory.resolve(fileName).takeIf { it.exists() }?.length() ?: 0L
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        directory.resolve(fileName).exists()
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).takeIf { it.isAbsolute }?.delete() == true || directory.resolve(path).delete()
    }
}
