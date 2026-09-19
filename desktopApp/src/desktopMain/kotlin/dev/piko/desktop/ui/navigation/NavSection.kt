package dev.piko.desktop.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowDownload
import io.github.composefluent.icons.regular.Cloud
import io.github.composefluent.icons.regular.Flash
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.icons.regular.Settings

enum class NavSection(val title: String, val icon: ImageVector) {
    DRIVE("文件网盘", Icons.Default.Folder),
    DOWNLOADS("传输任务", Icons.Default.ArrowDownload),
    TASKS("离线秒传", Icons.Default.Flash),
    SETTINGS("系统设置", Icons.Default.Settings),
}
