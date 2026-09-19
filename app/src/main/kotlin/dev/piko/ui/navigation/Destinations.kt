package dev.piko.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation destinations modeling application screens.
 *
 * Implements [NavKey] to support Navigation 3 back stack persistence
 * across configuration changes and process death.
 *
 * Documentation References:
 * - Android Navigation 3: android-docs-mirror/pages/guide/navigation/navigation-3/basics.md
 * - Android Navigation 3 State: android-docs-mirror/pages/guide/navigation/navigation-3/save-state.md
 *   "Every key in the back stack must implement the NavKey interface and be marked @Serializable."
 */
sealed interface Screen : NavKey {
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
    data object Trash : Screen

    @Serializable
    data class VideoPlayer(
        val fileId: String,
        val fileName: String,
        val localPath: String? = null,
    ) : Screen
}

enum class MainTab(val title: String) {
    FILES("文件"),
    TRANSFERS("传输"),
    SETTINGS("我的"),
}
