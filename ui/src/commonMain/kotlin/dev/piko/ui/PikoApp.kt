package dev.piko.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.components.trackPointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.archive.ArchiveExtractHost
import dev.piko.ui.screens.auth.LoginScreen
import dev.piko.ui.screens.upload.UploadRequestHost
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.LocalPikoMotion
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
    val pointerSource = remember { PointerSource() }
    CompositionLocalProvider(
        LocalPikoServices provides services,
        LocalPikoPlatform provides platform,
        LocalPointerSource provides pointerSource,
    ) {
        PikoTheme(appearance = appearance) {
            val clientManager = services.clientManager
            val currentClient by clientManager.currentClient.collectAsStateWithLifecycle()
            val isInitializing by clientManager.isInitializing.collectAsStateWithLifecycle()
            val addingAccount by clientManager.addingAccount.collectAsStateWithLifecycle()
            val account = currentClient?.account

            Crossfade(
                targetState = when {
                    isInitializing -> AppState.Initializing
                    account == null || addingAccount -> AppState.Login(adding = account != null)
                    else -> AppState.Main(account)
                },
                animationSpec = LocalPikoMotion.current.stateCrossfade,
                label = "app_root_state",
                modifier = modifier.fillMaxSize().trackPointerSource(pointerSource),
            ) { state ->
                when (state) {
                    AppState.Initializing -> FullScreenLoading()
                    // 登录成功后 currentClient 变为非空，根状态随之切到 Main，不需要回调。
                    // 加账号时当前账号不退出，取消即回到它
                    is AppState.Login -> LoginScreen(onCancel = if (state.adding) clientManager::cancelAddingAccount else null)
                    // 按账号区分：换号时整个主界面重建，各页的记忆状态（滚动、展开、选中）不带到另一个账号。
                    // 退出登录后 currentClient 变空，根状态自动回到 Login
                    is AppState.Main -> PikoMainScaffold(onLogout = {}, videoPlayer = videoPlayer)
                }
            }
            // 解压的密码框放在网盘页之外：离开网盘页后，加密包仍要能问到密码
            if (currentClient != null) {
                ArchiveExtractHost(services.archiveExtractSession)
                UploadRequestHost(services.uploadManager, services.driveRepository)
            }
            // 放在根状态之外：登录前后切换时弹窗不跟着重建
            platform.updater?.let { updater ->
                StartupUpdatePrompt(updater = updater, preferences = services.preferences)
            }
        }
    }
}

private sealed interface AppState {
    data object Initializing : AppState

    data class Login(val adding: Boolean) : AppState

    data class Main(val account: String) : AppState
}
