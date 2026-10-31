package dev.piko.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.isPreviewableImage
import dev.piko.shared.data.*
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

class AndroidLocalFileAccess(private val context: Context, scope: CoroutineScope, changed: (String) -> Unit) : LocalFileAccess {
    private val resolver get() = context.contentResolver
    private val cache = LocalMediaCache(File(context.cacheDir, "piko/local-media"), scope, changed)
    private val subtitleLocks = Array(16) { Mutex() }
    private val subtitleCleanup = Any()
    override fun cancelPending() = cache.cancelPending()
    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private data class Entry(val id: String, val name: String, val mime: String, val size: Long, val modified: Long)
    private fun children(tree: Uri, documentId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2).orEmpty(), cursor.getLong(3), cursor.getLong(4))) }
        } ?: error("本机文件夹不可访问")
    }
    private fun document(mount: LocalMount, location: LocalLocation): Uri {
        require(location.mountId == mount.mountId)
        val tree = Uri.parse(mount.localRootUriOrPath)
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (segment in location.segments) id = children(tree, id).firstOrNull { it.name == segment }?.id ?: error("本机文件夹不可访问")
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }
    private fun stat(mount: LocalMount, location: LocalLocation, entry: Entry, uri: Uri): FileStat {
        val folder = entry.mime == DocumentsContract.Document.MIME_TYPE_DIR
        val base = FileStat(id = location.copy(directory = folder).id, parentId = location.parent?.id ?: mount.parentFolderId,
            name = entry.name, kind = if (folder) FileKind.FOLDER else FileKind.FILE,
            size = entry.size.toString(), mimeType = entry.mime, phase = TaskPhase.COMPLETE,
            fileExtension = entry.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" },
            modifiedTime = kotlin.time.Instant.fromEpochMilliseconds(entry.modified).toString(),
            params = mapOf("local_path" to uri.toString()))
        return if (base.isPreviewableImage()) base.copy(thumbnailLink = uri.toString()) else base
    }
    override suspend fun list(mount: LocalMount, location: LocalLocation): List<FileStat> = withContext(Dispatchers.IO) {
        try {
            val tree = Uri.parse(mount.localRootUriOrPath)
            val parent = document(mount, location)
            children(tree, DocumentsContract.getDocumentId(parent)).mapNotNull { entry ->
                val child = runCatching { location.child(entry.name, entry.mime == DocumentsContract.Document.MIME_TYPE_DIR) }.getOrNull() ?: return@mapNotNull null
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.id)
                val file = stat(mount, child, entry, uri)
                cache.enrich(file) { output -> metadata(file, uri, output) }
            }
        } catch (_: Exception) { throw IllegalStateException("本机文件夹不可访问") }
    }
    override suspend fun playbackPath(mount: LocalMount, location: LocalLocation): String = withContext(Dispatchers.IO) {
        try { document(mount, location).toString() } catch (_: Exception) { throw IllegalStateException("本机文件夹不可访问") }
    }
    override suspend fun subtitlePath(mount: LocalMount, location: LocalLocation): String = withContext(Dispatchers.IO) {
        try {
            val uri = document(mount, location)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val entry = resolver.query(uri, columns, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2).orEmpty(), cursor.getLong(3), cursor.getLong(4)) else null
            } ?: error("字幕不可访问")
            val directory = File(context.cacheDir, "piko/local-subtitles").apply { mkdirs() }
            val key = MessageDigest.getInstance("SHA-256").digest("${location.id}|${entry.size}|${entry.modified}".toByteArray()).joinToString("") { "%02x".format(it) }
            subtitleLocks[key.hashCode() and 15].withLock {
                val extension = entry.name.substringAfterLast('.', "srt").lowercase().takeIf { it in setOf("srt", "ass", "ssa", "vtt", "sub", "txt") } ?: "srt"
                val output = File(directory, "$key.$extension")
                if (output.isFile && output.length() > 0) return@withLock output.absolutePath
                val tmp = File.createTempFile("subtitle-", ".tmp", directory)
                try {
                    resolver.openInputStream(uri)?.use { source -> tmp.outputStream().use { source.copyTo(it) } } ?: error("字幕不可访问")
                    check(tmp.length() > 0) { "字幕不可访问" }
                    atomicReplace(tmp, output)
                    synchronized(subtitleCleanup) {
                        val files = directory.listFiles { file -> file.isFile && file.extension != "tmp" }.orEmpty()
                        var excess = files.size - 64
                        for (old in files.sortedBy { it.lastModified() }) {
                            if (excess <= 0) break
                            if (old != output && old.delete()) excess--
                        }
                    }
                    output.absolutePath
                } finally { tmp.delete() }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { throw IllegalStateException("字幕不可访问") }
    }
    override suspend fun probe(mount: LocalMount, location: LocalLocation): FileStat? = withContext(Dispatchers.IO) {
        try {
            val parent = location.parent ?: return@withContext mount.toFileStat()
            val file = list(mount, parent).firstOrNull { it.id == location.id } ?: return@withContext null
            cache.probe(file) { output -> metadata(file, document(mount, location), output) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { throw IllegalStateException("本机文件夹不可访问") }
    }
    private fun metadata(file: FileStat, uri: Uri, output: File): LocalMediaMetadata? {
        if (file.isPreviewableImage()) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            val swaps = runCatching { resolver.openInputStream(uri)?.use { stream ->
                val orientation = android.media.ExifInterface(stream).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
                orientation in 5..8
            } ?: false }.getOrDefault(false)
            return if (swaps) LocalMediaMetadata(options.outHeight, options.outWidth) else LocalMediaMetadata(options.outWidth, options.outHeight)
        }
        if (!file.isPlayableVideo()) return null
        val retriever = MediaMetadataRetriever()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) } ?: return null
            fun value(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0
            val duration = value(MediaMetadataRetriever.METADATA_KEY_DURATION)
            var width = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            var height = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            val rotation = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val scale = minOf(1.0, 480.0 / maxOf(width, height).coerceAtLeast(1))
            val frame = if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(duration * 100, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            else retriever.getFrameAtTime(duration * 100, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val thumbnail = if (frame != null) {
                try {
                    val tmp = File.createTempFile("local-cover-", ".tmp", output.parentFile)
                    try {
                        val compressed = tmp.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        if (compressed && tmp.length() > 0) {
                            atomicReplace(tmp, output)
                            output.absolutePath
                        } else ""
                    } finally { tmp.delete() }
                } catch (_: Exception) { "" }
                finally { frame.recycle() }
            } else ""
            if (rotation == 90L || rotation == 270L) { val original = width; width = height; height = original }
            return LocalMediaMetadata(width, height, duration, thumbnail)
        } finally { retriever.release() }
    }
}
