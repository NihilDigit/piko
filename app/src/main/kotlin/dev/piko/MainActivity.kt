package dev.piko

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.ui.PikoMainScaffold
import dev.piko.ui.components.FullScreenLoading
import dev.piko.ui.screens.auth.LoginScreen
import dev.piko.ui.theme.PikoMotion
import dev.piko.ui.theme.PikoTheme

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

        setContent {
            PikoTheme {
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
                        AppState.LOGIN -> {
                            LoginScreen(
                                onLoginSuccess = {
                                    // 登录成功后 StateFlow 会自动更新至 AppState.MAIN
                                },
                            )
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
