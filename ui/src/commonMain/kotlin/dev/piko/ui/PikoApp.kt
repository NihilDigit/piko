package dev.piko.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.archive.ArchiveExtractHost
import dev.piko.ui.screens.auth.LoginScreen
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.PikoMotion
import dev.piko.ui.theme.PikoTheme
import dev.piko.update.StartupUpdatePrompt

/**
 * 两端共用的根界面：主题、初始化、登录与主界面之间的切换。
 *
 * [appearance] 由入口读好再传进来，而不是在这里收集偏好：两端都要在第一帧之前同步读出
 * 外观，否则首帧按默认配色画出来，随即跳成用户选的主题。
 */
@Composable
fun PikoApp(
    services: PikoServices,
    platform: PikoPlatform,
    appearance: Appearance,
    videoPlayer: VideoPlayerHost,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalPikoServices provides services,
        LocalPikoPlatform provides platform,
    ) {
        PikoTheme(appearance = appearance) {
            val clientManager = services.clientManager
            val currentClient by clientManager.currentClient.collectAsStateWithLifecycle()
            val isInitializing by clientManager.isInitializing.collectAsStateWithLifecycle()

            Crossfade(
                targetState = when {
                    isInitializing -> AppState.INITIALIZING
                    currentClient != null -> AppState.MAIN
                    else -> AppState.LOGIN
                },
                animationSpec = PikoMotion.StateCrossfadeSpec,
                label = "app_root_state",
                modifier = modifier.fillMaxSize(),
            ) { state ->
                when (state) {
                    AppState.INITIALIZING -> FullScreenLoading()
                    // 登录成功后 currentClient 变为非空，根状态随之切到 MAIN，不需要回调
                    AppState.LOGIN -> {
                        // 未完成的添加链接与解压队列都属于上一个账号，保存目标也是那边的目录
                        LaunchedEffect(Unit) {
                            services.instantSession.end()
                            services.archiveExtractSession.clear()
                        }
                        LoginScreen()
                    }
                    // 退出登录后 currentClient 变空，根状态自动回到 LOGIN
                    AppState.MAIN -> PikoMainScaffold(onLogout = {}, videoPlayer = videoPlayer)
                }
            }
            // 解压的密码框放在网盘页之外：离开网盘页后，加密包仍要能问到密码
            if (currentClient != null) ArchiveExtractHost(services.archiveExtractSession)
            // 放在根状态之外：登录前后切换时弹窗不跟着重建
            platform.updater?.let { updater ->
                StartupUpdatePrompt(updater = updater, preferences = services.preferences)
            }
        }
    }
}

private enum class AppState {
    INITIALIZING,
    LOGIN,
    MAIN,
}
