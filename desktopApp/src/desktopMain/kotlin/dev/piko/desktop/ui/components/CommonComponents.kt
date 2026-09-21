package dev.piko.desktop.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Document
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.icons.regular.Image
import io.github.composefluent.icons.regular.Play
import java.text.DecimalFormat

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${DecimalFormat("#,##0.#").format(value)} ${units[digitGroups]}"
}

fun isVideoFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf(
        "3g2", "3gp", "asf", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4",
        "mpeg", "mpg", "mts", "mxf", "ogm", "rm", "rmvb", "ts", "vob", "webm", "wmv",
    )

fun isImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf(
        "avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "webp",
    )

fun getFileIcon(name: String, isFolder: Boolean): ImageVector {
    return when {
        isFolder -> Icons.Default.Folder
        isVideoFile(name) -> Icons.Default.Play
        isImageFile(name) -> Icons.Default.Image
        else -> Icons.Default.Document
    }
}

/**
 * 解析片段时间输入：mm:ss、hh:mm:ss 或纯秒数，转毫秒。非法返回 null。
 * 注意 mp4 切片只认毫秒整数，秒后小数直接丢弃。
 */
fun parseSegmentTime(input: String): Long? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val parts = text.split(":")
    return runCatching {
        when (parts.size) {
            1 -> parts[0].toDouble().toLong() * 1000L
            2 -> parts[0].toLong() * 60_000L + parts[1].toDouble().toLong() * 1000L
            3 -> parts[0].toLong() * 3_600_000L + parts[1].toLong() * 60_000L + parts[2].toDouble().toLong() * 1000L
            else -> null
        }?.takeIf { it >= 0 }
    }.getOrNull()
}

/** 毫秒转 mm:ss / hh:mm:ss（片段任务命名与时长提示共用）。 */
fun formatSegmentTime(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
