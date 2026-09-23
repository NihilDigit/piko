package dev.piko

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.ui.PikoMainScaffold
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.screens.auth.LoginScreen
import dev.piko.ui.theme.PikoMotion
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.appearanceFlow
import dev.piko.ui.theme.isDark
import dev.piko.util.PikPakAppLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Main application entry activity using Single Activity architecture.
 *
 * Documentation References:
 * - Android Compose State: android-docs-mirror/pages/develop/ui/compose/state.md
 *   "Consuming flows safely in Jetpack Compose with collectAsStateWithLifecycle"
 * - Android Lifecycle: android-docs-mirror/pages/develop/ui/compose/lifecycle.md
 * - Material 3 Motion: m3-material-mirror/pages/styles/motion.md
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 重建时（进程被杀后从最近任务回来）系统会把最初的启动 Intent 再交一次，
        // 不判断的话同一条分享进来的磁力链会再弹一次秒传面板
        if (savedInstanceState == null) handleIntent(intent)

        val appearanceFlow = PikoApplication.instance.sessionManager.appearanceFlow()
        // 同步读一次再 setContent：异步给初值的话，首帧按系统取色画出来，随即跳成用户选的主题
        val initialAppearance = runBlocking { appearanceFlow.first() }

        setContent {
            val appearance by appearanceFlow.collectAsStateWithLifecycle(initialAppearance)
            val darkTheme = appearance.isDark()
            // 系统栏图标的深浅默认看系统的夜间模式。应用强制浅色而系统是深色时，状态栏会是
            // 浅色图标压在浅色背景上，所以改为按应用实际的深浅判断。导航栏遮罩沿用库的默认值
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(NavBarLightScrim, NavBarDarkScrim) { darkTheme },
                )
                onDispose {}
            }
            PikoTheme(appearance = appearance) {
                val clientManager = PikoApplication.instance.clientManager
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
                    modifier = Modifier.fillMaxSize(),
                ) { state ->
                    when (state) {
                        AppState.INITIALIZING -> {
                            FullScreenLoading()
                        }
                        // 登录成功后 currentClient 变为非空，根状态随之切到 MAIN，不需要回调
                        AppState.LOGIN -> {
                            // 未完成的添加链接属于上一个账号，保存目标也是那边的目录
                            LaunchedEffect(Unit) { PikoApplication.instance.instantSession.end() }
                            LoginScreen()
                        }
                        AppState.MAIN -> {
                            PikoMainScaffold(
                                onLogout = {
                                    // 退出登录后 StateFlow 会自动更新至 AppState.LOGIN
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        intent.dataString?.let(PikPakAppLink::parse)?.let { target ->
            when (target) {
                PikPakAppLink.Target.Drive -> PikoApplication.instance.driveRepository.requestOpenDrive()
            }
            return
        }
        val magnet = extractMagnet(intent)
        if (!magnet.isNullOrBlank()) {
            PikoApplication.instance.instantMagnetRepository.onIncomingMagnet(magnet)
        }
    }

    private fun extractMagnet(intent: Intent): String? {
        val dataUri = intent.dataString
        if (!dataUri.isNullOrBlank() && dataUri.startsWith("magnet:", ignoreCase = true)) {
            return dataUri
        }

        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!extraText.isNullOrBlank()) {
            val match = MAGNET_REGEX.find(extraText)?.value
            if (match != null) return match
            val trimmed = extraText.trim()
            if (trimmed.startsWith("magnet:", ignoreCase = true)) return trimmed
            if (trimmed.length == 40 && trimmed.all { it.isLetterOrDigit() }) {
                return "magnet:?xt=urn:btih:$trimmed"
            }
        }

        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) {
            val clipText = clip.getItemAt(0)?.text?.toString()
            if (!clipText.isNullOrBlank()) {
                val match = MAGNET_REGEX.find(clipText)?.value
                if (match != null) return match
            }
        }

        return null
    }

    companion object {
        private val MAGNET_REGEX = Regex("""magnet:\?[^\s"']+""", RegexOption.IGNORE_CASE)
    }

    private enum class AppState {
        INITIALIZING,
        LOGIN,
        MAIN,
    }
}

// 与 androidx.activity 的 DefaultLightScrim、DefaultDarkScrim 相同，那两个是 internal
private val NavBarLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NavBarDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
