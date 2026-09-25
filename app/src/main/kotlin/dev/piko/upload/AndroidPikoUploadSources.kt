package dev.piko.upload

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.piko.shared.upload.PikoUploadSources
import dev.piko.shared.upload.UploadFolder
import dev.piko.shared.upload.UploadSourceInfo
import dev.piko.shared.upload.UploadTreeEntry
import java.io.EOFException
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlinx.io.RawSource
import kotlinx.io.asSource

/** uri 是 content: URI：选择器给的文档、系统分享来的文件，或文件夹树里的文档。 */
class AndroidPikoUploadSources(context: Context) : PikoUploadSources {
    private val resolver = context.contentResolver

    override fun describe(uri: String): UploadSourceInfo? = try {
        val parsed = Uri.parse(uri)
        // 投影传 null：分享来的 URI 可能出自 MediaStore 或 FileProvider，点名它们没有的列会直接抛异常
        resolver.query(parsed, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.string(OpenableColumns.DISPLAY_NAME) ?: parsed.lastPathSegment ?: "未命名"
            val size = cursor.long(OpenableColumns.SIZE) ?: statSize(parsed) ?: return@use null
            UploadSourceInfo(name, size, lastModifiedMs(cursor))
        }
    } catch (e: Exception) {
        // 授权已失效是 SecurityException，文档已删除时各家提供方抛的异常不一
        null
    }

    override fun readAt(uri: String, offset: Long, length: Int): ByteArray {
        openStream(uri).channel.use { channel ->
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, offset + buffer.position())
                if (read < 0) throw EOFException("文件在 ${offset + buffer.position()} 处提前结束")
            }
            return buffer.array()
        }
    }

    override fun open(uri: String, offset: Long): RawSource {
        val stream = openStream(uri)
        try {
            stream.channel.position(offset)
        } catch (e: Exception) {
            stream.close()
            throw e
        }
        return stream.asSource()
    }

    override fun listFolder(uri: String): UploadFolder? = try {
        val treeUri = Uri.parse(uri)
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val name = resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        if (name == null) {
            null
        } else {
            val files = mutableListOf<UploadTreeEntry>()
            collectFiles(treeUri, rootId, "", files)
            UploadFolder(name, files)
        }
    } catch (e: Exception) {
        null
    }

    // 只交还这个 uri 本身持有的授权。文件夹里的文档靠树授权读取，没有自己的授权；
    // 树授权由整个文件夹的文件共用，这里不知道其他文件是否传完，留着不还
    override fun release(uri: String) {
        val parsed = Uri.parse(uri)
        if (resolver.persistedUriPermissions.none { it.uri == parsed }) return
        runCatching { resolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun collectFiles(treeUri: Uri, documentId: String, relativeDir: String, into: MutableList<UploadTreeEntry>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val children = resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildList<ChildDocument> {
                while (cursor.moveToNext()) add(ChildDocument(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
            }
        } ?: throw IllegalStateException("无法列出 $childrenUri")
        for (child in children) {
            if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                val childDir = if (relativeDir.isEmpty()) child.name else "$relativeDir/${child.name}"
                collectFiles(treeUri, child.id, childDir, into)
            } else {
                // 用树 URI 拼出文档 URI，持久化的树授权才覆盖得到它
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id)
                into += UploadTreeEntry(fileUri.toString(), relativeDir)
            }
        }
    }

    /** AutoCloseInputStream 关闭时连同 ParcelFileDescriptor 一起关，不会留下句柄。 */
    private fun openStream(uri: String): FileInputStream {
        val descriptor = resolver.openFileDescriptor(Uri.parse(uri), "r")
            ?: throw IllegalStateException("无法打开 $uri")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    /** 管道等不可定位的来源 statSize 为 -1，这种也无法分片续传，按不可读处理。 */
    private fun statSize(uri: Uri): Long? =
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 }

    /**
     * 文档提供方给毫秒的 last_modified，MediaStore 给秒的 date_modified，都没有时为 0。
     * 取不到时恒为 0 而不是当前时间：调度器拿它判断文件是否改过，两次读到的必须一致。
     */
    private fun lastModifiedMs(cursor: Cursor): Long =
        cursor.long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ?: cursor.long(MediaStore.MediaColumns.DATE_MODIFIED)?.let { it * 1000 }
            ?: 0L
}

private class ChildDocument(val id: String, val name: String, val mimeType: String?)

private fun Cursor.string(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.long(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
