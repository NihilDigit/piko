package dev.piko.data.repository

import io.github.nihildigit.pikpak.FileStat

private val VIDEO_EXTENSIONS = setOf(
    "3g2", "3gp", "asf", "avi", "divx", "dv", "f4v", "flv", "m2ts", "m2v", "m4v",
    "mkv", "mov", "mp4", "mpe", "mpeg", "mpg", "mts", "mxf", "ogv", "rm", "rmvb",
    "ts", "vob", "webm", "wmv", "wtv",
)
private val IMAGE_EXTENSIONS = setOf("avif", "bmp", "gif", "heic", "jpeg", "jpg", "png", "webp")

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
