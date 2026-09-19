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
