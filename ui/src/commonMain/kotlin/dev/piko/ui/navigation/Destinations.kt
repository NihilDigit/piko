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
    /** 返回栈的栈底：导航栏与三个根页面。其余页面压在它上面，连同导航栏一起盖住。 */
    @Serializable
    data object Home : Screen

    /** 宽窗口里与详情页并排的「我的」，即两栏的列表栏。窄窗口不压它，「我的」就是 Home 里的那一页。 */
    @Serializable
    data object Profile : Screen

    @Serializable
    data object Login : Screen

    @Serializable
    data object Files : Screen

    @Serializable
    data object Transfers : Screen

    @Serializable
    data object Settings : Screen

    @Serializable
    data object Trash : Screen

    @Serializable
    data object Starred : Screen

    @Serializable
    data object PlayHistory : Screen

    @Serializable
    data object MyShares : Screen

    @Serializable
    data class VideoPlayer(
        val fileId: String,
        val fileName: String,
        val localPath: String? = null,
        /** 从这里开播，不查续播记录。 */
        val startMillis: Long? = null,
    ) : Screen
}

enum class MainTab(val title: String) {
    FILES("文件"),
    TRANSFERS("传输"),
    SETTINGS("我的"),
}
