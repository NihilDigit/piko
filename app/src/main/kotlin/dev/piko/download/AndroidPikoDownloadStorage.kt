package dev.piko.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.download.PikoDownloadStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPikoDownloadStorage(
    private val context: Context,
    preferences: PikoUserPreferences,
    scope: CoroutineScope,
) : PikoDownloadStorage {
    @Volatile
    private var configuredDirectory: String = ""

    private val appDirectory: File
        get() = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("Piko")

    // SAF 目录下载时的暂存区。放 files 而不放 cache：大文件下到一半时系统清理缓存，
    // 断点就没了
    private val stagingDirectory: File
        get() = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("Piko/.partial")

    init {
        scope.launch {
            preferences.downloadDirPathFlow.collect { configuredDirectory = it }
        }
    }

    private val treeUri: Uri?
        get() = configuredDirectory.takeIf { it.startsWith("content:") }?.let(Uri::parse)

    override fun pathFor(fileName: String): String = configuredDirectory.takeIf { it.startsWith("content:") }
        ?: File(resolveDirectory(), fileName).absolutePath

    override suspend fun downloadTarget(fileName: String): String = withContext(Dispatchers.IO) {
        if (treeUri != null) {
            stagingDirectory.mkdirs()
            File(stagingDirectory, fileName).absolutePath
        } else {
            File(resolveDirectory(), fileName).absolutePath
        }
    }

    override suspend fun commit(fileName: String, downloadedPath: String): String = withContext(Dispatchers.IO) {
        val tree = treeUri ?: return@withContext downloadedPath
        val staged = File(downloadedPath)
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("无法访问下载目录")
        val document = root.findFile(fileName) ?: root.createFile("application/octet-stream", fileName)
            ?: error("无法创建下载文件")
        val output = context.contentResolver.openOutputStream(document.uri, "wt") ?: error("无法写入下载文件")
        output.use { sink -> staged.inputStream().use { it.copyTo(sink, COPY_BUFFER_BYTES) } }
        staged.delete()
        document.uri.toString()
    }

    override suspend fun existingLength(fileName: String): Long = withContext(Dispatchers.IO) {
        val tree = treeUri
        if (tree != null) {
            // 暂存区里有半截文件说明还没交付，以它为准；否则看目录里有没有已交付的完整文件
            File(stagingDirectory, fileName).takeIf { it.exists() }?.length()
                ?: DocumentFile.fromTreeUri(context, tree)?.findFile(fileName)?.length()
                ?: 0L
        } else {
            File(resolveDirectory(), fileName).takeIf { it.exists() }?.length() ?: 0L
        }
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val tree = treeUri
        if (tree != null) {
            DocumentFile.fromTreeUri(context, tree)?.findFile(fileName)?.exists() == true
        } else {
            File(resolveDirectory(), fileName).exists()
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        if (path.startsWith("content:")) {
            // 下载目录本身也是 content: URI，误传进来就会删掉用户选的整个目录
            if (path == configuredDirectory) return@withContext false
            return@withContext DocumentFile.fromSingleUri(context, Uri.parse(path))?.delete() == true
        }
        File(path).takeIf { it.isAbsolute }?.delete() == true || File(resolveDirectory(), path).delete()
    }

    private fun resolveDirectory(): File {
        val path = configuredDirectory
        val result = if (path.isNotBlank() && !path.startsWith("content:")) File(path) else appDirectory
        result.mkdirs()
        return result
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 256 * 1024
    }
}
