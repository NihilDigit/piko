package dev.piko.data.repository

import io.github.nihildigit.pikpak.FileStat

private val VIDEO_EXTENSIONS = setOf(
    "3g2", "3gp", "asf", "avi", "divx", "dv", "f4v", "flv", "m2ts", "m2v", "m4v",
    "mkv", "mov", "mp4", "mpe", "mpeg", "mpg", "mts", "mxf", "ogv", "rm", "rmvb",
    "ts", "vob", "webm", "wmv", "wtv",
)
private val IMAGE_EXTENSIONS = setOf("avif", "bmp", "gif", "heic", "jpeg", "jpg", "png", "webp")
private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape", "mka")
private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "xz", "bz2")
// 种子里常夹带字幕，单列一类，不与 nfo、txt 一起算作文档
private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "sup", "idx")

/** 只按扩展名的文件大类。网盘列表的图标、秒传面板的按类勾选共用这一套。 */
enum class FileCategory { VIDEO, AUDIO, IMAGE, ARCHIVE, SUBTITLE, DOCUMENT }

fun String.fileCategory(): FileCategory {
    val ext = substringAfterLast('.', "").lowercase()
    return when (ext) {
        in VIDEO_EXTENSIONS -> FileCategory.VIDEO
        in AUDIO_EXTENSIONS -> FileCategory.AUDIO
        in IMAGE_EXTENSIONS -> FileCategory.IMAGE
        in ARCHIVE_EXTENSIONS -> FileCategory.ARCHIVE
        in SUBTITLE_EXTENSIONS -> FileCategory.SUBTITLE
        else -> FileCategory.DOCUMENT
    }
}

fun String.isPlayableVideo(): Boolean = substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

fun String.isPreviewableImage(): Boolean = substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/**
 * 网盘条目是否可播。扩展名之外还认服务端元数据：扩展名非标或干脆没有时
 * PikPak 回的是 application/octet-stream，但只要它抽过元数据，params 里
 * 就同时有 duration 和 width——音频只有时长，图片只有尺寸。
 */
fun FileStat.isPlayableVideo(): Boolean = !isFolder && (
    name.isPlayableVideo() ||
        mimeType.startsWith("video/") ||
        (params.containsKey("duration") && params.containsKey("width"))
    )

fun FileStat.isPreviewableImage(): Boolean = !isFolder && (
    name.isPreviewableImage() || mimeType.startsWith("image/")
    )
