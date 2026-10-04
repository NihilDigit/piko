package dev.piko.shared.data

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** 字节数写成给人看的大小，按 1024 进位，保留一位小数（四舍五入）。 */
fun readableSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(digitGroups)
    // 整数除以 10 的 Double 转成字符串是最短表示，不会冒出 0.30000000000000004
    val rounded = floor(value * 10 + 0.5) / 10
    return "$rounded ${units[digitGroups]}"
}
