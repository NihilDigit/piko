package dev.piko.ui.screens.instant

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.ResolvedFile
import io.github.nihildigit.pikpak.imageThumbnailUrlOf
import io.github.nihildigit.pikpak.videoThumbnailUrlOf
import dev.piko.data.repository.isPlayableVideo
import dev.piko.data.repository.fileCategory
import dev.piko.data.repository.FileCategory

internal data class ImportProjectionSource(val key: String, val files: List<Pair<Int, ResolvedFile>>)
internal data class ImportResultEntry(val file: FileStat, val sourceKey: String, val sourceIndex: Int? = null)

/** Keep link ownership even when two inputs have identical output paths. No provider IDs escape this adapter. */
internal fun importResultEntries(sources: List<ImportProjectionSource>): List<ImportResultEntry> = sources.flatMap { source ->
    val prefix = "${source.key.length}:${source.key}:"
    fun directoryId(path: String) = if (path.isEmpty()) "" else "import-dir:$prefix$path"
    val directories = source.files.flatMap { (_, file) ->
        val parts = file.path.split('/')
        parts.dropLast(1).indices.map { parts.take(it + 1).joinToString("/") }
    }.distinct().map { path ->
        ImportResultEntry(FileStat(id = directoryId(path), name = path.substringAfterLast('/'),
            parentId = directoryId(path.substringBeforeLast('/', "")), kind = FileKind.FOLDER), source.key)
    }
    directories + source.files.map { (index, file) ->
        ImportResultEntry(FileStat(id = "import-file:$prefix$index", name = file.name,
            parentId = directoryId(file.path.substringBeforeLast('/', "")), size = file.size.toString(),
            hash = file.gcid.orEmpty(),
            thumbnailLink = file.thumbnailLink?.takeIf { it.isNotBlank() } ?: file.gcid?.takeIf { it.isNotBlank() }?.let { gcid ->
                when {
                    file.name.isPlayableVideo() -> videoThumbnailUrlOf(gcid)
                    file.name.fileCategory() == FileCategory.IMAGE -> imageThumbnailUrlOf(gcid)
                    else -> ""
                }
            }.orEmpty(),
            fileExtension = file.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }), source.key, index)
    }
}
