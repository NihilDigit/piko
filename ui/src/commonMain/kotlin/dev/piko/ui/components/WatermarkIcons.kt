package dev.piko.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 海报墙里没有缩略图的文件画在封面区的类型图标，取自 Material Symbols Rounded（FILL 1，24px）。
 *
 * material-icons-extended 只有旧版 Material Icons：Folder 的页签是直角斜切，
 * 放大到 88dp 后棱角生硬；Outlined 线条按比例放大后只剩几道细线，压到一成透明度近乎不见。
 * Symbols Rounded 的圆角与页签过渡同 M3 Expressive 的形状语言一致，实心填充放大后仍成块面。
 * 路径原样摘自 google/material-design-icons 的 symbols/web，viewBox 为 0 -960 960 960。
 */
internal object WatermarkIcons {
    val Folder = symbol(
        "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h207q16 0 30.5 6t25.5 17l57 57h320q33 0 56.5 23.5T880-640v400q0 33-23.5 56.5T800-160H160Z",
    )
    val Movie = symbol(
        "m160-800 65 130q7 14 20 22t28 8q30 0 46-25.5t2-52.5l-41-82h80l65 130q7 14 20 22t28 8q30 0 46-25.5t2-52.5l-41-82h80l65 130q7 14 20 22t28 8q30 0 46-25.5t2-52.5l-41-82h120q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800Z",
    )
    val AudioFile = symbol(
        "M240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h287q16 0 30.5 6t25.5 17l194 194q11 11 17 25.5t6 30.5v447q0 33-23.5 56.5T720-80H240Zm280-560q0 17 11.5 28.5T560-600h160L520-800v160Zm-90 440q38 0 64-26t26-64v-150h80q17 0 28.5-11.5T640-480q0-17-11.5-28.5T600-520h-80q-17 0-28.5 11.5T480-480v115q-11-8-23.5-11.5T430-380q-38 0-64 26t-26 64q0 38 26 64t64 26Z",
    )
    val Image = symbol(
        "M200-120q-33 0-56.5-23.5T120-200v-560q0-33 23.5-56.5T200-840h560q33 0 56.5 23.5T840-760v560q0 33-23.5 56.5T760-120H200Zm80-160h400q12 0 18-11t-2-21L586-459q-6-8-16-8t-16 8L450-320l-74-99q-6-8-16-8t-16 8l-80 107q-8 10-2 21t18 11Z",
    )
    val FolderZip = symbol(
        "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h207q16 0 30.5 6t25.5 17l57 57h320q33 0 56.5 23.5T880-640v400q0 33-23.5 56.5T800-160H160Zm400-80h80v-80h80v-80h-80v-80h80v-80h-80v-80h-80v80h80v80h-80v80h80v80h-80v80Z",
    )
    val Subtitles = symbol(
        "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Zm120-160h240q17 0 28.5-11.5T560-360q0-17-11.5-28.5T520-400H280q-17 0-28.5 11.5T240-360q0 17 11.5 28.5T280-320Zm160-160h240q17 0 28.5-11.5T720-520q0-17-11.5-28.5T680-560H440q-17 0-28.5 11.5T400-520q0 17 11.5 28.5T440-480Zm-160 0q17 0 28.5-11.5T320-520q0-17-11.5-28.5T280-560q-17 0-28.5 11.5T240-520q0 17 11.5 28.5T280-480Zm400 160q17 0 28.5-11.5T720-360q0-17-11.5-28.5T680-400q-17 0-28.5 11.5T640-360q0 17 11.5 28.5T680-320Z",
    )
    val Description = symbol(
        "M360-240h240q17 0 28.5-11.5T640-280q0-17-11.5-28.5T600-320H360q-17 0-28.5 11.5T320-280q0 17 11.5 28.5T360-240Zm0-160h240q17 0 28.5-11.5T640-440q0-17-11.5-28.5T600-480H360q-17 0-28.5 11.5T320-440q0 17 11.5 28.5T360-400ZM240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h287q16 0 30.5 6t25.5 17l194 194q11 11 17 25.5t6 30.5v447q0 33-23.5 56.5T720-80H240Zm280-560q0 17 11.5 28.5T560-600h160L520-800v160Z",
    )
}

private fun symbol(pathData: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    )
        // Symbols 的 viewBox 从 y=-960 起，平移回视口内
        .addGroup(translationY = 960f)
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .clearGroup()
        .build()
