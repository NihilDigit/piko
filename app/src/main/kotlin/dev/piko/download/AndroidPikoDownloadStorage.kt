package dev.piko.download

import dev.piko.shared.i18n.tr

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import dev.piko.data.auth.PikoUserPreferences
import dev.piko.shared.download.PikoDownloadStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class AndroidPikoDownloadStorage(
    private val context: Context,
    preferences: PikoUserPreferences,
    scope: CoroutineScope,
) : PikoDownloadStorage {
    @Volatile
    private var configuredDirectory: String = ""

    private val folderLock = Mutex()
    private val directoryLoaded = CompletableDeferred<Unit>()
    private val dirCacheLock = Any()
    private val dirCache = object : LinkedHashMap<Pair<String, String>, CachedSafDirectory>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, CachedSafDirectory>): Boolean =
            size > MAX_CACHED_DIRECTORIES
    }

    private val appDirectory: File
        get() = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("Piko")

    // SAF 目录下载时的暂存区。放 files 而不放 cache：大文件下到一半时系统清理缓存，
    // 断点就没了
    private val stagingDirectory: File
        get() = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("Piko/.partial")

    init {
        scope.launch {
            preferences.downloadDirPathFlow.collect {
                configuredDirectory = it
                clearDirCache()
                directoryLoaded.complete(Unit)
            }
        }
    }

    private val treeUri: Uri?
        get() = configuredDirectory.takeIf { it.startsWith("content:") }?.let(Uri::parse)

    override fun pathFor(fileName: String): String = configuredDirectory.takeIf { it.startsWith("content:") }
        ?: File(resolveDirectory(), fileName).absolutePath

    override suspend fun cacheTarget(name: String): String = withContext(Dispatchers.IO) {
        val directory = File(stagingDirectory, "blocks")
        directory.mkdirs()
        File(directory, "$name.data").absolutePath
    }

    override suspend fun existingLengths(fileNames: List<String>): Map<String, Long> = withContext(Dispatchers.IO) {
        directoryLoaded.await()
        val tree = treeUri ?: return@withContext fileNames.associateWith { File(resolveDirectory(), it).length() }
        val rootId = treeRootDocumentId(tree) ?: return@withContext emptyMap()
        buildMap {
            for ((parent, names) in fileNames.groupBy { it.substringBeforeLast('/', "") }) {
                val segments = parent.split('/').filter { it.isNotEmpty() }
                val folderId = folderLock.withLock { resolveFolderId(tree, rootId, segments, createFolders = false) }
                val children = if (folderId != null) listDirectoryByName(tree, folderId, allowCache = true) else emptyMap()
                for (name in names) {
                    val staged = File(stagingDirectory, name)
                    val leaf = name.substringAfterLast('/')
                    val existing = children[leaf]?.takeIf { !it.isDirectory }?.size ?: 0L
                    put(name, if (staged.isFile) staged.length() else existing)
                }
            }
        }
    }

    // 暂存区与私有目录里照搬相对路径建子文件夹，与最终位置一一对应，续传时凭同一个 fileName 找回半截文件
    override suspend fun downloadTarget(fileName: String): String = withContext(Dispatchers.IO) {
        directoryLoaded.await()
        val target = if (treeUri != null) File(stagingDirectory, fileName) else File(resolveDirectory(), fileName)
        target.parentFile?.mkdirs()
        target.absolutePath
    }

    override suspend fun commit(fileName: String, downloadedPath: String): String = withContext(Dispatchers.IO) {
        directoryLoaded.await()
        val tree = treeUri ?: return@withContext downloadedPath
        val staged = File(downloadedPath)
        val rootId = treeRootDocumentId(tree)
            ?: error(tr("无法访问下载目录", "Cannot access download folder", "ダウンロード先にアクセスできません", "다운로드 폴더에 접근할 수 없습니다", "無法存取下載資料夾"))
        val segments = fileName.split('/').filter { it.isNotEmpty() }
        val name = segments.last()
        val folderSegments = segments.dropLast(1)
        // 同一文件夹下载的几个文件并行交付，同时发现子文件夹不存在时会各建一个，
        // 系统给后建的改名成「文件夹 (1)」，所以找与建放在一把锁里
        val targetUri = folderLock.withLock {
            var parentId = resolveFolderId(tree, rootId, folderSegments, createFolders = true)
            if (parentId == null) {
                clearDirCache()
                parentId = resolveFolderId(tree, rootId, folderSegments, createFolders = true)
            }
            val resolvedParentId = parentId
                ?: error(tr("无法创建下载文件夹", "Cannot create download folder", "ダウンロード先を作成できません", "다운로드 폴더를 만들 수 없습니다", "無法建立下載資料夾"))
            findOrCreateChildFileUri(context.contentResolver, tree, resolvedParentId, name, "application/octet-stream")
                ?.also { invalidateDirCache(tree, resolvedParentId) }
                ?: error(tr("无法创建下载文件", "Cannot create download file", "ダウンロードファイルを作成できません", "다운로드 파일을 만들 수 없습니다", "無法建立下載檔案"))
        }
        val written = exportFileToSafUri(context.contentResolver, staged, targetUri)
        if (!written) {
            error(tr("无法写入下载文件", "Cannot write download file", "ダウンロードファイルに書き込めません", "다운로드 파일에 쓸 수 없습니다", "無法寫入下載檔案"))
        }
        invalidateParentCacheForPath(tree, rootId, folderSegments)
        staged.delete()
        targetUri.toString()
    }

    override suspend fun existingLength(fileName: String): Long = withContext(Dispatchers.IO) {
        directoryLoaded.await()
        val tree = treeUri
        if (tree != null) {
            // 暂存区里有半截文件说明还没交付，以它为准；否则看目录里有没有已交付的完整文件
            File(stagingDirectory, fileName).takeIf { it.isFile }?.length()
                ?: findEntryInTree(tree, fileName)?.takeIf { !it.isDirectory }?.size
                ?: 0L
        } else {
            File(resolveDirectory(), fileName).takeIf { it.isFile }?.length() ?: 0L
        }
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        directoryLoaded.await()
        val tree = treeUri
        if (tree != null) {
            findEntryInTree(tree, fileName) != null
        } else {
            File(resolveDirectory(), fileName).exists()
        }
    }

    override suspend fun locate(fileName: String): String? = withContext(Dispatchers.IO) {
        directoryLoaded.await()
        val tree = treeUri
        if (tree != null) {
            findEntryInTree(tree, fileName)?.let { entry ->
                DocumentsContract.buildDocumentUriUsingTree(tree, entry.documentId).toString()
            }
        } else {
            File(resolveDirectory(), fileName).takeIf { it.exists() }?.absolutePath
        }
    }

    override suspend fun pruneEmptyFolders(folder: String) = withContext(Dispatchers.IO) {
        directoryLoaded.await()
        val tree = treeUri
        if (tree != null) {
            findEntryInTree(tree, folder, allowCache = false)?.takeIf { it.isDirectory }?.let { entry ->
                pruneSafDirectory(tree, entry.documentId)
                clearDirCache()
            }
        } else {
            val root = File(resolveDirectory(), folder)
            if (root.isDirectory) root.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
        }
        // 暂存区里取消掉的半截文件已由 delete 删掉，剩下同样结构的空文件夹
        val staged = File(stagingDirectory, folder)
        if (staged.isDirectory) staged.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
    }

    /**
     * DocumentsContract.deleteDocument 对文件夹是连同内容整个删掉，与 File.delete 不同，所以先看空不空。
     * 返回这个文件夹是否已删掉。
     */
    private fun pruneSafDirectory(tree: Uri, folderDocumentId: String): Boolean {
        val children = querySafChildren(context.contentResolver, tree, folderDocumentId) ?: return false
        val remaining = children.count { !(it.isDirectory && pruneSafDirectory(tree, it.documentId)) }
        if (remaining != 0) return false
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(tree, folderDocumentId)
        return deleteSafDocument(context.contentResolver, folderUri)
    }

    private fun findEntryInTree(tree: Uri, relativePath: String, allowCache: Boolean = true): SafChildEntry? {
        val rootId = treeRootDocumentId(tree) ?: return null
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val parentId = resolveFolderId(tree, rootId, segments.dropLast(1), createFolders = false, allowCache = allowCache)
            ?: return null
        return listDirectoryByName(tree, parentId, allowCache = allowCache)[segments.last()]
    }

    /**
     * 按路径逐级往下找。通过单次投影查询整目录替代 DocumentFile.findFile（后者每个子项都会触发额外跨进程 IPC），
     * 配合短时目录缓存合并连续的 exists / existingLength 查询。[createFolders] 时缺的文件夹就地建出，此时 [segments] 应只含文件夹。
     */
    private fun resolveFolderId(
        tree: Uri,
        rootId: String,
        segments: List<String>,
        createFolders: Boolean,
        allowCache: Boolean = true,
    ): String? {
        var currentId = rootId
        for (segment in segments) {
            val existing = listDirectoryByName(tree, currentId, allowCache = allowCache)[segment]
            if (existing != null) {
                if (!existing.isDirectory) return null
                currentId = existing.documentId
                continue
            }
            if (!createFolders) return null
            // 缓存里没见到时再直接查一次底层目录，防止外部刚建出同名文件夹而重复创建出「文件夹 (1)」
            val fresh = if (allowCache) listDirectoryByName(tree, currentId, allowCache = false)[segment] else null
            if (fresh != null) {
                if (!fresh.isDirectory) return null
                currentId = fresh.documentId
                continue
            }
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, currentId)
            val createdUri = runCatching {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    segment,
                )
            }.getOrNull() ?: return null
            invalidateDirCache(tree, currentId)
            currentId = runCatching { DocumentsContract.getDocumentId(createdUri) }.getOrNull() ?: return null
        }
        return currentId
    }

    private fun invalidateParentCacheForPath(tree: Uri, rootId: String, folderSegments: List<String>) {
        if (folderSegments.isEmpty()) {
            invalidateDirCache(tree, rootId)
            return
        }
        val parentId = resolveFolderId(tree, rootId, folderSegments, createFolders = false, allowCache = true)
        if (parentId != null) invalidateDirCache(tree, parentId) else clearDirCache()
    }

    private fun listDirectoryByName(tree: Uri, parentDocumentId: String, allowCache: Boolean): Map<String, SafChildEntry> {
        val key = tree.toString() to parentDocumentId
        val now = SystemClock.elapsedRealtime()
        if (allowCache) {
            synchronized(dirCacheLock) {
                val cached = dirCache[key]
                if (cached != null && now - cached.fetchedAtMs <= DIR_CACHE_TTL_MS) {
                    return cached.byName
                }
            }
        }
        val entries = querySafChildren(context.contentResolver, tree, parentDocumentId) ?: return emptyMap()
        val byName = LinkedHashMap<String, SafChildEntry>(entries.size)
        for (entry in entries) {
            if (entry.name.isNotEmpty() && !byName.containsKey(entry.name)) {
                byName[entry.name] = entry
            }
        }
        val fetchedAt = SystemClock.elapsedRealtime()
        synchronized(dirCacheLock) {
            dirCache[key] = CachedSafDirectory(fetchedAt, byName)
        }
        return byName
    }

    private fun invalidateDirCache(tree: Uri, parentDocumentId: String) {
        synchronized(dirCacheLock) {
            dirCache.remove(tree.toString() to parentDocumentId)
        }
    }

    private fun clearDirCache() {
        synchronized(dirCacheLock) {
            dirCache.clear()
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        directoryLoaded.await()
        if (path.startsWith("content:")) {
            // 下载目录本身也是 content: URI，误传进来就会删掉用户选的整个目录
            if (path == configuredDirectory) return@withContext false
            val deleted = deleteSafDocument(context.contentResolver, Uri.parse(path))
            if (deleted) clearDirCache()
            return@withContext deleted
        }
        File(path).takeIf { it.isAbsolute }?.delete() == true || File(resolveDirectory(), path).delete()
    }

    private fun resolveDirectory(): File {
        val path = configuredDirectory
        val result = if (path.isNotBlank() && !path.startsWith("content:")) File(path) else appDirectory
        result.mkdirs()
        return result
    }

    private data class CachedSafDirectory(
        val fetchedAtMs: Long,
        val byName: Map<String, SafChildEntry>,
    )

    private companion object {
        const val DIR_CACHE_TTL_MS = 1_500L
        const val MAX_CACHED_DIRECTORIES = 128
    }
}

internal data class SafChildEntry(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
) {
    val isDirectory: Boolean
        get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

private val SAF_CHILD_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
)

private const val COPY_BUFFER_BYTES = 256 * 1024
private const val TRANSFER_CHUNK_BYTES = 8L * 1024L * 1024L

internal fun treeRootDocumentId(treeUri: Uri): String? =
    runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()

internal fun querySafChildren(
    resolver: ContentResolver,
    treeUri: Uri,
    parentDocumentId: String,
): List<SafChildEntry>? = runCatching {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
    resolver.query(childrenUri, SAF_CHILD_PROJECTION, null, null, null)?.use { cursor ->
        buildList(cursor.count.coerceAtLeast(0)) {
            while (cursor.moveToNext()) {
                val id = if (cursor.isNull(0)) null else cursor.getString(0)
                val name = if (cursor.isNull(1)) null else cursor.getString(1)
                if (id.isNullOrEmpty() || name.isNullOrEmpty()) continue
                val mime = if (cursor.isNull(2)) "" else cursor.getString(2).orEmpty()
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3).coerceAtLeast(0L)
                add(SafChildEntry(id, name, mime, size))
            }
        }
    }
}.getOrNull()

internal fun findOrCreateChildFileUri(
    resolver: ContentResolver,
    treeUri: Uri,
    parentDocumentId: String,
    displayName: String,
    mimeType: String,
): Uri? {
    val existing = querySafChildren(resolver, treeUri, parentDocumentId)
        ?.firstOrNull { it.name == displayName && !it.isDirectory }
    if (existing != null) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, existing.documentId)
    }
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
    return runCatching { DocumentsContract.createDocument(resolver, parentUri, mimeType, displayName) }.getOrNull()
}

internal fun deleteSafDocument(resolver: ContentResolver, documentUri: Uri): Boolean =
    runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }.getOrDefault(false)

/**
 * 优先走可定位文件描述符的内核态零拷贝（FileChannel.transferTo / sendfile），分块检查协程取消信号；
 * 若底层 DocumentsProvider 返回的是管道或不支持通道传输，则自动回退到 256 KiB 大缓冲流复制。
 */
internal suspend fun exportFileToSafUri(
    resolver: ContentResolver,
    sourceFile: File,
    targetUri: Uri,
): Boolean {
    var exportedViaChannel = false
    try {
        resolver.openFileDescriptor(targetUri, "wt")?.use { pfd ->
            FileInputStream(sourceFile).channel.use { sourceChannel ->
                FileOutputStream(pfd.fileDescriptor).channel.use { destChannel ->
                    val total = sourceChannel.size()
                    var position = 0L
                    while (position < total) {
                        currentCoroutineContext().ensureActive()
                        val chunk = minOf(TRANSFER_CHUNK_BYTES, total - position)
                        val transferred = sourceChannel.transferTo(position, chunk, destChannel)
                        if (transferred <= 0L) {
                            if (position == 0L) return@use
                            break
                        }
                        position += transferred
                    }
                    if (position == total) {
                        exportedViaChannel = true
                    }
                }
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        // Provider rejected "wt" file descriptor or sendfile/splice; fall back to buffered stream below.
    }
    if (exportedViaChannel) return true
    currentCoroutineContext().ensureActive()
    val output = runCatching { resolver.openOutputStream(targetUri, "wt") }.getOrNull()
        ?: runCatching { resolver.openOutputStream(targetUri) }.getOrNull()
        ?: return false
    output.use { sink ->
        sourceFile.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
            }
        }
    }
    return true
}
