package dev.piko.ui.navigation

import kotlinx.serialization.Serializable

sealed interface Screen {
    @Serializable
    data object Login : Screen

    @Serializable
    data object Files : Screen

    @Serializable
    data class SubDrive(val folderId: String, val folderName: String) : Screen

    @Serializable
    data object Transfers : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data class VideoPlayer(val fileId: String, val fileName: String) : Screen
}

enum class MainTab(val title: String) {
    FILES("文件"),
    TRANSFERS("传输"),
    SETTINGS("我的"),
}
