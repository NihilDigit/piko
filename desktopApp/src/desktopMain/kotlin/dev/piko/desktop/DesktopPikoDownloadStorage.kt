package dev.piko.desktop

import dev.piko.shared.download.PikoDownloadStorage
import io.github.nihildigit.pikpak.PikPakStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DesktopPikoDownloadStorage(
    private val directory: File = File(System.getProperty("user.home"), "Downloads/Piko"),
) : PikoDownloadStorage {
    override fun pathFor(fileName: String): String = directory.resolve(fileName).absolutePath

    override suspend fun download(
        fileName: String,
        reader: PikPakStreamReader,
        totalBytes: Long,
        onProgress: suspend (Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val output = directory.resolve(fileName)
        var written = 0L
        FileOutputStream(output, true).use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (written < totalBytes) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count <= 0) break
                stream.write(buffer, 0, count)
                written += count
                onProgress(written)
            }
        }
        output.absolutePath
    }

    override suspend fun existingLength(fileName: String): Long =
        directory.resolve(fileName).takeIf { it.exists() }?.length() ?: 0L

    override suspend fun exists(fileName: String): Boolean = directory.resolve(fileName).exists()

    override suspend fun delete(path: String): Boolean = File(path).takeIf { it.isAbsolute }?.delete() == true || directory.resolve(path).delete()
}
