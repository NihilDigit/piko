package dev.piko.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.download.PikoDownloadStorage
import io.github.nihildigit.pikpak.PikPakStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class AndroidPikoDownloadStorage(
    private val context: Context,
    preferences: PikoUserPreferences,
    scope: CoroutineScope,
) : PikoDownloadStorage {
    @Volatile
    private var configuredDirectory: String = ""

    private val directory: File get() = context.getExternalFilesDir(null)?.resolve("Piko") ?: context.filesDir.resolve("Piko")

    init {
        scope.launch {
            preferences.downloadDirPathFlow.collect { configuredDirectory = it }
        }
    }

    override fun pathFor(fileName: String): String = configuredDirectory.takeIf { it.startsWith("content:") }
        ?: File(resolveDirectory(), fileName).absolutePath

    override suspend fun download(
        fileName: String,
        reader: PikPakStreamReader,
        totalBytes: Long,
        onProgress: suspend (Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val tree = configuredDirectory.takeIf { it.startsWith("content:") }?.let(Uri::parse)
        val document = tree?.let { treeUri ->
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("无法访问下载目录")
            root.findFile(fileName) ?: root.createFile("application/octet-stream", fileName)
                ?: error("无法创建下载文件")
        }
        val output = if (document == null) File(resolveDirectory(), fileName) else null
        var written = 0L
        val stream = document?.let { context.contentResolver.openOutputStream(it.uri, "wt") }
            ?: FileOutputStream(requireNotNull(output), false)
        stream.use { outputStream ->
            val buffer = ByteArray(256 * 1024)
            while (written < totalBytes) {
                val count = reader.read(buffer, 0, buffer.size)
                if (count <= 0) break
                outputStream.write(buffer, 0, count)
                written += count
                onProgress(written)
            }
        }
        document?.uri?.toString() ?: requireNotNull(output).absolutePath
    }

    override suspend fun existingLength(fileName: String): Long {
        val tree = configuredDirectory.takeIf { it.startsWith("content:") }?.let(Uri::parse)
        if (tree != null) {
            return DocumentFile.fromTreeUri(context, tree)?.findFile(fileName)?.length() ?: 0L
        } else {
            return File(resolveDirectory(), fileName).takeIf { it.exists() }?.length() ?: 0L
        }
    }

    override suspend fun exists(fileName: String): Boolean {
        val tree = configuredDirectory.takeIf { it.startsWith("content:") }?.let(Uri::parse)
        return if (tree != null) {
            DocumentFile.fromTreeUri(context, tree)?.findFile(fileName)?.exists() == true
        } else {
            File(resolveDirectory(), fileName).exists()
        }
    }

    override suspend fun delete(path: String): Boolean {
        if (path.startsWith("content:")) return DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete() == true
        return File(path).takeIf { it.isAbsolute }?.delete() == true || File(resolveDirectory(), path).delete()
    }

    private fun resolveDirectory(): File {
        val path = configuredDirectory
        val result = if (path.isNotBlank() && !path.startsWith("content:")) File(path) else directory
        result.mkdirs()
        return result
    }
}
