package dev.piko.ui.screens.player

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.DriveRepository
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.SubtitleRef
import dev.piko.shared.media.player.buildPlaylist
import dev.piko.shared.media.player.buildRawPlaylist
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 与某个视频同目录的可播视频（含它自己）与播放器能读的字幕文件。 */
class SiblingMedia(val videos: List<FileStat>, val subtitles: List<FileStat>)

/**
 * 与 [fileId] 同目录的视频与字幕，列一次目录。取不到父目录或列表时两份都为空。
 *
 * 只在打开播放器时取一次：播放期间目录内容变了，也不该让播放列表在脚下重排。
 */
suspend fun DriveRepository.siblingMedia(fileId: String): SiblingMedia {
    val parentId = getFileDetail(fileId).getOrNull()?.parentId ?: return SiblingMedia(emptyList(), emptyList())
    val files = listAllFiles(parentId).getOrNull().orEmpty()
    return SiblingMedia(
        videos = files.filter { it.isPlayableVideo() },
        subtitles = files.filter { !it.isFolder && it.name.substringAfterLast('.', "").lowercase() in PLAYER_SUBTITLE_EXTENSIONS },
    )
}

// mpv 能直接当外挂字幕加载的格式。idx/sub 要成对，smi 不认，都不列
private val PLAYER_SUBTITLE_EXTENSIONS = setOf("ass", "ssa", "srt", "vtt", "sup")

/**
 * 按文件名解析出作品、分区与集数排好；解析总开关关闭时只按文件名排序。
 * 大合集有上千个文件，解析要几秒，不能占着主线程。
 *
 * [subtitles] 交给解析器挂到对应的集上，播放时一并加载；解析关闭时没人能判断哪条字幕属于哪集，不挂。
 */
suspend fun playlistOf(
    videos: List<FileStat>,
    preferences: PikoUserPreferences,
    subtitles: List<FileStat> = emptyList(),
): List<PlaylistEntry> {
    val parse = preferences.nameParsingFlow.first()
    val entries = videos.map {
        PlaylistEntry(fileId = it.id, name = it.name, label = "", thumbnailUrl = it.thumbnailLink, size = it.sizeBytes)
    }
    val subtitleRefs = subtitles.map { SubtitleRef(fileId = it.id, name = it.name, language = null) }
    return withContext(Dispatchers.Default) { if (parse) buildPlaylist(entries, subtitleRefs) else buildRawPlaylist(entries) }
}
