package dev.piko

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.extractLinks
import dev.piko.ui.PikoApp
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.screens.player.MediampVideoPlayerScreen
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

        val app = PikoApplication.instance
        val appearanceFlow = app.sessionManager.appearanceFlow(app.platform.supportsDynamicColor)
        // 同步读一次再 setContent：异步给初值的话，首帧按系统取色画出来，随即跳成用户选的主题
        val initialAppearance = runBlocking { appearanceFlow.first() }
        // 播放器仍在 app 模块，以应用内覆盖层的方式交给共享主界面
        val videoPlayer = VideoPlayerHost.InApp { screen, onClose ->
            MediampVideoPlayerScreen(
                initialFileId = screen.fileId,
                initialFileName = screen.fileName,
                initialLocalPath = screen.localPath,
                onBackClick = onClose,
            )
        }

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
            PikoApp(
                services = app.services,
                platform = app.platform,
                appearance = appearance,
                videoPlayer = videoPlayer,
            )
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
        val link = extractShareText(intent) ?: extractMagnet(intent)
        if (!link.isNullOrBlank()) {
            // 名为磁力，实际收的是添加链接面板的输入：面板自己分辨磁力、下载地址与分享链接
            PikoApplication.instance.instantMagnetRepository.onIncomingMagnet(link)
        }
    }

    /**
     * 含 PikPak 分享链接的输入：打开 mypikpak.com/s/ 链接，或分享来的一段文本。返回整段文本而不只是链接，
     * 转发的分享常把提取码写在链接后面，面板从同一段文本里把它认出来。
     */
    private fun extractShareText(intent: Intent): String? {
        val candidates = listOfNotNull(
            intent.dataString,
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString(),
        )
        return candidates.firstOrNull { InstantSheetState.findShareLink(it) != null }?.trim()
    }

    private fun extractMagnet(intent: Intent): String? {
        val dataUri = intent.dataString
        if (!dataUri.isNullOrBlank() && dataUri.startsWith("magnet:", ignoreCase = true)) {
            return dataUri
        }

        val texts = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString(),
        )
        // 只取磁力链：分享来的网页地址不该弹出离线下载面板。多条时一并交出去，面板列成批量清单
        val magnets = texts.firstNotNullOfOrNull { text -> extractLinks(text).filter { it.isMagnet }.takeIf { it.isNotEmpty() } }
        return magnets?.joinToString("\n") { it.uri }
    }
}

// 与 androidx.activity 的 DefaultLightScrim、DefaultDarkScrim 相同，那两个是 internal
private val NavBarLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NavBarDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
