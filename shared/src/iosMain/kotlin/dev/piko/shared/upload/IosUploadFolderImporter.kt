@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.piko.shared.upload

import dev.piko.shared.storage.IosAppDirectories
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSFileTypeSymbolicLink
import platform.Foundation.NSURL

/**
 * 把一个由文稿选择器选中的文件夹整棵导入进应用私有上传根，返回 `piko-app-upload:` 标识。
 *
 * 只在 [+ url.startAccessingSecurityScopedResource] 拿到的授权与 [NSFileCoordinator] 的读协调里工作，
 * 逐条读、逐条复制，不保存外部 URL、书签或授权；结束就还回去。复制本身交给共享层的
 * [AppDirectoryUploadFolderImporter]：整棵先进暂存目录，成功才整个搬进上传根。
 *
 * 已知边界（宿主侧条件，不在本函数里假装解决）：iCloud 或第三方 provider 的未下载文件在被读到时
 * 才按需下载，读不到就会抛错并让整次导入失败；导入期间应用被挂起也可能让 provider 失效。
 * 因此这是前台一次性的 I/O，不是后台传输，也不提供断点续传。
 */
suspend fun importIosUploadFolder(
    directories: IosAppDirectories,
    url: NSURL,
    maxBytesPerFile: Long,
    maxTotalBytes: Long,
): String = withContext(Dispatchers.IO) {
    require(url.scheme?.lowercase() == "file") { "A local folder URL is required" }
    require(maxBytesPerFile >= 0 && maxTotalBytes >= 0) { "Import byte limits must not be negative" }
    val name = url.lastPathComponent ?: throw IOException("Folder has no name")
    val context = currentCoroutineContext()
    val importer = AppDirectoryUploadFolderImporter(
        directories.downloads,
        Path(directories.caches, "upload-imports").toString(),
    )
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        memScoped {
            val error = alloc<ObjCObjectVar<platform.Foundation.NSError?>>()
            error.value = null
            var outcome: Result<String>? = null
            NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
                url = url,
                options = 0uL,
                error = error.ptr,
                byAccessor = { coordinated ->
                    // 异常必须在 Kotlin 侧接住，不能穿过 Obj-C 栈帧
                    outcome = runCatching {
                        context.ensureActive()
                        val readable = coordinated ?: throw IOException("Folder coordinator returned no URL")
                        require(readable.scheme?.lowercase() == "file") { "Coordinated folder is not a file URL" }
                        val path = readable.path ?: throw IOException("Folder path is unavailable")
                        if (NSFileManager.defaultManager.fileExistsAtPath(path).not()) {
                            throw IOException("Selected folder is missing")
                        }
                        importer.importFolder(
                            folderName = name,
                            maxBytesPerFile = maxBytesPerFile,
                            maxTotalBytes = maxTotalBytes,
                            checkCancelled = { context.ensureActive() },
                            snapshot = NSFileManagerFolderSnapshot(path),
                        )
                    }
                },
            )
            // Coordinator failures may prevent the accessor from running at all; retain the
            // NSError before reporting a missing callback, without exposing the selected path.
            error.value?.let { err ->
                throw IOException("Folder coordination failed: ${err.domain} (${err.code})")
            }
            val result = outcome?.getOrThrow() ?: throw IOException("Folder coordination did not provide a readable folder")
            result
        }
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

/**
 * 用 NSFileManager 在给定根目录下列目录、看类型、开文件。相对路径一律斜杠分隔，根是空串。
 * 符号链接报成 [UploadFolderEntryKind.OTHER]，让共享层跳过，不跟随。
 */
private class NSFileManagerFolderSnapshot(private val root: String) : UploadFolderSnapshot {
    private val manager = NSFileManager.defaultManager

    override fun kindOf(relativePath: String): UploadFolderEntryKind? {
        val attributes = manager.attributesOfItemAtPath(absolute(relativePath), error = null) ?: return null
        return when (attributes["NSFileType"] as? String) {
            NSFileTypeDirectory -> UploadFolderEntryKind.DIRECTORY
            NSFileTypeRegular -> UploadFolderEntryKind.FILE
            NSFileTypeSymbolicLink -> UploadFolderEntryKind.OTHER
            else -> UploadFolderEntryKind.OTHER
        }
    }

    override fun listDirectory(relativePath: String): List<String> {
        val entries = manager.contentsOfDirectoryAtPath(absolute(relativePath), error = null)
            ?: throw IOException("Listing the selected folder failed")
        return entries.filterIsInstance<String>()
    }

    override fun openFile(relativePath: String): RawSource {
        val path = absolute(relativePath)
        if (manager.attributesOfItemAtPath(path, error = null) == null) throw IOException("Selected file is missing")
        return SystemFileSystem.source(Path(path))
    }

    private fun absolute(relativePath: String): String =
        if (relativePath.isEmpty()) root else "$root/$relativePath".replace("//", "/")
}
