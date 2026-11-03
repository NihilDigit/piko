package dev.piko.upload

import dev.piko.shared.i18n.tr

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
            val name = cursor.string(OpenableColumns.DISPLAY_NAME) ?: parsed.lastPathSegment ?: tr("未命名", "Untitled", "無題", "제목 없음", "未命名")
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
                if (read < 0) throw EOFException(tr("文件在 ${offset + buffer.position()} 处提前结束", "File ended early at ${offset + buffer.position()}", "ファイルが ${offset + buffer.position()} で予期せず終了", "파일이 ${offset + buffer.position()}에서 일찍 끝났습니다", "檔案在 ${offset + buffer.position()} 處提前結束"))
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

    override fun permissionKey(uri: String): String {
        val parsed = Uri.parse(uri)
        val authority = parsed.authority
        return if (authority != null && DocumentsContract.isTreeUri(parsed)) DocumentsContract.buildTreeDocumentUri(
            authority, DocumentsContract.getTreeDocumentId(parsed),
        ).toString() else uri
    }

    override fun retain(uri: String) {
        runCatching { resolver.takePersistableUriPermission(Uri.parse(permissionKey(uri)), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    // 调度器按 permissionKey 统一计算所有准备和任务的引用，最后一个使用者结束后才交还。
    override fun release(uri: String) {
        val parsed = Uri.parse(uri)
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == parsed } ?: return
        // 上传只持有读授权；可写目录还由下载保存位置等功能长期使用。
        if (permission.isWritePermission) return
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
        } ?: throw IllegalStateException(tr("无法列出 $childrenUri", "Cannot list $childrenUri", "$childrenUri の一覧を取得できません", "$childrenUri 목록을 불러올 수 없습니다", "無法列出 $childrenUri"))
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
            ?: throw IllegalStateException(tr("无法打开 $uri", "Cannot open $uri", "$uri を開けません", "${uri}을(를) 열 수 없습니다", "無法開啟 $uri"))
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
