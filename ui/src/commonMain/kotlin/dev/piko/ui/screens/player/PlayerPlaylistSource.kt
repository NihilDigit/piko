package dev.piko.ui.screens.player

import dev.piko.data.auth.PikoUserPreferences
import dev.piko.data.repository.DriveRepository
import dev.piko.data.repository.isPlayableAudio
import dev.piko.data.repository.isPlayableVideo
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.shared.media.player.SubtitleRef
import dev.piko.shared.media.player.buildPlaylist
import dev.piko.shared.media.player.buildRawPlaylist
import dev.piko.shared.media.player.isPlayerSubtitleName
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 与某个视频或音频同目录的同类可播文件（含它自己）与播放器能读的字幕文件。 */
class SiblingMedia(val videos: List<FileStat>, val subtitles: List<FileStat>)

/**
 * 与 [fileId] 同目录的同类可播媒体与字幕，列一次目录。取不到父目录或列表时两份都为空。
 * 音频文件匹配同目录音频文件，视频文件匹配同目录视频文件，互不串入彼此队列。
 *
 * 只在打开播放器时取一次：播放期间目录内容变了，也不该让播放列表在脚下重排。
 */
suspend fun DriveRepository.siblingMedia(fileId: String): SiblingMedia {
    val known = knownFile(fileId)
    val detail = if (known == null) getFileDetail(fileId).getOrNull() else null
    val parentId = known?.parentId
        ?: locateFolder(fileId).getOrNull()?.lastOrNull()?.id
        ?: detail?.parentId
        ?: return SiblingMedia(emptyList(), emptyList())
    val files = cachedBrowsable(parentId, dev.piko.shared.data.PikoFileSortOrder.NAME_ASC)
        ?: listBrowsable(parentId, dev.piko.shared.data.PikoFileSortOrder.NAME_ASC).getOrNull().orEmpty()
    val targetStat = known ?: files.firstOrNull { it.id == fileId }
    val audioScope = if (targetStat != null) {
        targetStat.isPlayableAudio() && !targetStat.isPlayableVideo()
    } else {
        detail?.name?.let { it.isPlayableAudio() && !it.isPlayableVideo() } == true
    }
    return SiblingMedia(
        videos = if (audioScope) files.filter { it.isPlayableAudio() } else files.filter { it.isPlayableVideo() },
        subtitles = if (audioScope) emptyList() else files.filter { !it.isFolder && isPlayerSubtitleName(it.name) },
    )
}

/**
 * 按文件名解析出作品、分区与集数排好；解析总开关关闭时或解析结果退化为单文件时按当前文件夹自然排序。
 * 大合集有上千个文件，解析要几秒，不能占着主线程。
 *
 * [subtitles] 交给解析器挂到对应的集上，播放时一并加载；解析关闭时没人能判断哪条字幕属于哪集，不挂。
 */
suspend fun playlistOf(
    videos: List<FileStat>,
    preferences: PikoUserPreferences,
    subtitles: List<FileStat> = emptyList(),
    currentFileId: String? = null,
): List<PlaylistEntry> {
    val parse = preferences.nameParsingFlow.first()
    val entries = videos.map {
        PlaylistEntry(fileId = it.id, name = it.name, label = "", thumbnailUrl = it.thumbnailLink, size = it.sizeBytes)
    }
    val subtitleRefs = subtitles.map { SubtitleRef(fileId = it.id, name = it.name, language = null) }
    val isAudioList = videos.isNotEmpty() && videos.all { it.isPlayableAudio() && !it.isPlayableVideo() }
    return withContext(Dispatchers.Default) {
        if (parse && !isAudioList) {
            buildPlaylist(entries, subtitleRefs, currentFileId = currentFileId)
        } else {
            buildRawPlaylist(entries)
        }
    }
}
