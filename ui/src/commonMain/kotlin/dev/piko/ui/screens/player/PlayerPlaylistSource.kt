package dev.piko.ui.screens.player

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.DriveRepository
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.buildPlaylist
import dev.piko.shared.media.player.buildRawPlaylist
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 与 [fileId] 同目录的可播视频，含它自己。取不到父目录或列表时为空。
 *
 * 只在打开播放器时取一次：播放期间目录内容变了，也不该让播放列表在脚下重排。
 */
suspend fun DriveRepository.siblingVideos(fileId: String): List<FileStat> {
    val parentId = getFileDetail(fileId).getOrNull()?.parentId ?: return emptyList()
    return listAllFiles(parentId).getOrNull().orEmpty().filter { it.isPlayableVideo() }
}

/**
 * 按文件名解析出作品、分区与集数排好；解析总开关关闭时只按文件名排序。
 * 大合集有上千个文件，解析要几秒，不能占着主线程。
 */
suspend fun playlistOf(videos: List<FileStat>, preferences: PikoUserPreferences): List<PlaylistEntry> {
    val parse = preferences.nameParsingFlow.first()
    val entries = videos.map {
        PlaylistEntry(fileId = it.id, name = it.name, label = "", thumbnailUrl = it.thumbnailLink, size = it.sizeBytes)
    }
    return withContext(Dispatchers.Default) { if (parse) buildPlaylist(entries) else buildRawPlaylist(entries) }
}
