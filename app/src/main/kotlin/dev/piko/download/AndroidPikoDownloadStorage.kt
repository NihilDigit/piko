package dev.piko.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.download.PikoDownloadStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class AndroidPikoDownloadStorage(
    private val context: Context,
    preferences: PikoUserPreferences,
    scope: CoroutineScope,
) : PikoDownloadStorage {
    @Volatile
    private var configuredDirectory: String = ""

    private val folderLock = Mutex()

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

    // 暂存区与私有目录里照搬相对路径建子文件夹，与最终位置一一对应，续传时凭同一个 fileName 找回半截文件
    override suspend fun downloadTarget(fileName: String): String = withContext(Dispatchers.IO) {
        val target = if (treeUri != null) File(stagingDirectory, fileName) else File(resolveDirectory(), fileName)
        target.parentFile?.mkdirs()
        target.absolutePath
    }

    override suspend fun commit(fileName: String, downloadedPath: String): String = withContext(Dispatchers.IO) {
        val tree = treeUri ?: return@withContext downloadedPath
        val staged = File(downloadedPath)
        val root = DocumentFile.fromTreeUri(context, tree) ?: error("无法访问下载目录")
        val segments = fileName.split('/')
        val name = segments.last()
        // 同一文件夹下载的几个文件并行交付，同时发现子文件夹不存在时会各建一个，
        // 系统给后建的改名成「文件夹 (1)」，所以找与建放在一把锁里
        val parent = folderLock.withLock { findDocument(root, segments.dropLast(1), createFolders = true) }
            ?: error("无法创建下载文件夹")
        val document = parent.findFile(name) ?: parent.createFile("application/octet-stream", name)
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
            File(stagingDirectory, fileName).takeIf { it.isFile }?.length()
                ?: findInTree(tree, fileName)?.takeIf { it.isFile }?.length()
                ?: 0L
        } else {
            File(resolveDirectory(), fileName).takeIf { it.isFile }?.length() ?: 0L
        }
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val tree = treeUri
        if (tree != null) {
            findInTree(tree, fileName)?.exists() == true
        } else {
            File(resolveDirectory(), fileName).exists()
        }
    }

    override suspend fun locate(fileName: String): String? = withContext(Dispatchers.IO) {
        val tree = treeUri
        if (tree != null) {
            findInTree(tree, fileName)?.uri?.toString()
        } else {
            File(resolveDirectory(), fileName).takeIf { it.exists() }?.absolutePath
        }
    }

    override suspend fun pruneEmptyFolders(folder: String) = withContext(Dispatchers.IO) {
        val tree = treeUri
        if (tree != null) {
            findInTree(tree, folder)?.takeIf { it.isDirectory }?.let(::pruneDocument)
        } else {
            val root = File(resolveDirectory(), folder)
            if (root.isDirectory) root.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
        }
        // 暂存区里取消掉的半截文件已由 delete 删掉，剩下同样结构的空文件夹
        val staged = File(stagingDirectory, folder)
        if (staged.isDirectory) staged.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
    }

    /**
     * DocumentFile.delete 对文件夹是连同内容整个删掉，与 File.delete 不同，所以先看空不空。
     * 返回这个文件夹是否已删掉。
     */
    private fun pruneDocument(folder: DocumentFile): Boolean {
        val children = folder.listFiles()
        val remaining = children.count { !(it.isDirectory && pruneDocument(it)) }
        return remaining == 0 && folder.delete()
    }

    private fun findInTree(tree: Uri, fileName: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, tree) ?: return null
        return findDocument(root, fileName.split('/'), createFolders = false)
    }

    /**
     * 按路径逐级往下找。SAF 没有按路径直取的接口，每一级都要列一次目录（findFile 内部就是 listFiles），
     * 所以文件夹下载在 SAF 目录下比普通目录慢。[createFolders] 时缺的文件夹就地建出，此时 [segments] 应只含文件夹。
     */
    private fun findDocument(root: DocumentFile, segments: List<String>, createFolders: Boolean): DocumentFile? {
        var current = root
        for (segment in segments) {
            val next = current.findFile(segment)
            current = when {
                next != null -> next
                createFolders -> current.createDirectory(segment) ?: return null
                else -> return null
            }
        }
        return current
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
