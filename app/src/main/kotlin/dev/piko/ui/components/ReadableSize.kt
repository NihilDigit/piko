package dev.piko.ui.components

fun Long.toReadableSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var digitGroups = (Math.log10(this.toDouble()) / Math.log10(1024.0)).toInt()
    digitGroups = digitGroups.coerceIn(0, units.lastIndex)
    return String.format("%.1f %s", this / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}
