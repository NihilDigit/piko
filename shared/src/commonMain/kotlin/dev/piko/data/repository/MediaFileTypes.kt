package dev.piko.data.repository

import io.github.nihildigit.pikpak.FileStat

private val VIDEO_EXTENSIONS = setOf(
    "3g2", "3gp", "asf", "avi", "divx", "dv", "f4v", "flv", "m2ts", "m2v", "m4v",
    "mkv", "mov", "mp4", "mpe", "mpeg", "mpg", "mts", "mxf", "ogv", "rm", "rmvb",
    "ts", "vob", "webm", "wmv", "wtv",
)
private val IMAGE_EXTENSIONS = setOf("avif", "bmp", "gif", "heic", "jpeg", "jpg", "png", "webp")

/**
 * ExoPlayer 没有 extractor 的容器，原文件一定报 3003 PARSING_CONTAINER_UNSUPPORTED。
 *
 * 只列确定解不开的：扩展名不认识的文件仍然先试原画，失败了再换转码流，
 * 免得一个改了后缀的 mp4 白白降到转码画质。
 */
private val UNDEMUXABLE_EXTENSIONS = setOf("asf", "dv", "mxf", "rm", "rmvb", "wmv", "wtv")

fun String.isPlayableVideo(): Boolean = substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

fun String.isPreviewableImage(): Boolean = substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/** 本地解码器啃不动这个容器，开播就该直接要转码流。 */
fun String.needsTranscodedPlayback(): Boolean =
    substringAfterLast('.', "").lowercase() in UNDEMUXABLE_EXTENSIONS

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
