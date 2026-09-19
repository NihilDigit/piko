package dev.piko.data.repository

private val VIDEO_EXTENSIONS = setOf("avi", "flv", "m4v", "mkv", "mov", "mp4", "ts", "webm", "wmv")
private val IMAGE_EXTENSIONS = setOf("avif", "bmp", "gif", "heic", "jpeg", "jpg", "png", "webp")

fun String.isPlayableVideo(): Boolean = substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
fun String.isPreviewableImage(): Boolean = substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
