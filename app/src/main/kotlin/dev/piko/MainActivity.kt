package dev.piko

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areStatusBarsVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.piko.ui.adaptive.isHeightCompact
import dev.piko.ui.screens.player.findActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.download.DownloadStatus
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.TorrentMagnet
import dev.piko.shared.state.extractLinks
import dev.piko.shared.upload.UploadSelection
import dev.piko.ui.PikoApp
import dev.piko.ui.PikoServices
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.screens.player.MediampVideoPlayerScreen
import dev.piko.ui.theme.appearanceFlow
import dev.piko.ui.theme.isDark
import dev.piko.platform.installMotionScale
import dev.piko.util.PikPakAppLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
                initialStartMillis = screen.startMillis,
                onBackClick = onClose,
            )
        }

        // 设置里的「减少动画」经这一份缩放生效，见 PikoMotionScale
        installMotionScale(app.platform.motionScale, lifecycle)
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
            AskForNotificationsOnFirstWork(app.services)
            if (isHeightCompact()) HideStatusBarWhileShort()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_TRANSFERS, false)) {
            PikoApplication.instance.services.requestOpenTransfers()
            return
        }
        intent.dataString?.let(PikPakAppLink::parse)?.let { target ->
            when (target) {
                PikPakAppLink.Target.Drive -> PikoApplication.instance.driveRepository.requestOpenDrive()
            }
            return
        }
        torrentUri(intent)?.let { uri ->
            openTorrent(uri)
            return
        }
        val sharedFiles = extractSharedFiles(intent)
        if (sharedFiles.isNotEmpty()) {
            // 分享来的只有随 Intent 的临时授权，多数来源不许持久化；拿不到时这批任务进程被杀后无法续传
            sharedFiles.forEach { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            PikoApplication.instance.uploadManager.request(UploadSelection(files = sharedFiles.map(Uri::toString)))
            return
        }
        val link = extractShareText(intent) ?: extractMagnet(intent)
        if (!link.isNullOrBlank()) {
            // 名为磁力，实际收的是添加链接面板的输入：面板自己分辨磁力、下载地址与分享链接
            PikoApplication.instance.instantMagnetRepository.onIncomingMagnet(link)
        }
    }

    /** 打开的 .torrent 文件。按类型认而不按扩展名：content: URI 的路径多半不带文件名。 */
    private fun torrentUri(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } ?: return null
        return uri.takeIf { (intent.type ?: contentResolver.getType(uri)) == TORRENT_MIME_TYPE }
    }

    /** 与桌面端拖进种子相同，在本地换算成磁力链接。读取放在后台：文档提供者可能现从网络取内容。 */
    private fun openTorrent(uri: Uri) {
        val app = PikoApplication.instance
        app.appScope.launch(Dispatchers.IO) {
            val magnet = runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                .getOrNull()
                ?.let(TorrentMagnet::fromBytes)
            if (magnet != null) {
                app.instantMagnetRepository.onIncomingMagnet(magnet)
            } else {
                withContext(Dispatchers.Main) { Toast.makeText(app, "无法读取种子文件", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** 系统分享来的文件。文本分享也走 SEND，只有带 EXTRA_STREAM 才算要上传。 */
    private fun extractSharedFiles(intent: Intent): List<Uri> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        // AndroidPikoUploadSources 只认 content: URI
        return uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
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

/**
 * Android 13 起通知要在运行时授权，只在清单里声明的话，传输进度与任务结果的通知都发不出来。
 * 等第一次有工作要在后台跑时再问：这时说得清为什么要通知，一打开应用就问多半会被拒。
 * 每个进程只问一次；拒绝两次后系统自己也不再弹。
 */
@Composable
private fun AskForNotificationsOnFirstWork(services: PikoServices) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(services) {
        val serverWork = snapshotFlow {
            val scanning = services.duplicateSession.state?.phase.let {
                it == DuplicateFinderState.Phase.SCANNING || it == DuplicateFinderState.Phase.ANALYZING
            }
            services.archiveExtractSession.jobs.isNotEmpty() || scanning
        }
        combine(services.downloadManager.tasks, services.uploadManager.tasks, serverWork) { downloads, uploads, busy ->
            busy || downloads.values.any { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING } ||
                uploads.values.any { it.status.isActive }
        }.first { it }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val TORRENT_MIME_TYPE = "application/x-bittorrent"

/** 进行中的常驻通知点进来时带上，打开传输页看各项进度。 */
/**
 * 窗口高度 compact（横握的手机）时收起状态栏，下滑临时唤出；导航条不动。整个应用一个入口，按窗口高度生效。
 *
 * 信息流全屏（HideSystemBars）与播放器（ScreenOrientationController）各自收放整组系统栏，退出时一律放出来，
 * 状态栏也跟着出来。这里不和它们排先后，而是看着状态栏：高度仍 compact 时它一冒出来就再收起。
 * 下滑唤出的临时状态栏不改变插入区的可见性，不会被这里收回。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HideStatusBarWhileShort() {
    val window = LocalContext.current.findActivity()?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    val statusBarVisible = WindowInsets.areStatusBarsVisible
    LaunchedEffect(controller, statusBarVisible) {
        if (!statusBarVisible) return@LaunchedEffect
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    }
    DisposableEffect(controller) {
        onDispose {
            // 导航条也收着，是播放器的全屏或信息流在用整块屏幕（转成竖着的全屏时高度就不 compact 了），
            // 这时放出状态栏会压在它们的画面上
            val insets = ViewCompat.getRootWindowInsets(window.decorView)
            val othersImmersive = insets != null && !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            if (!othersImmersive) controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}

internal const val EXTRA_OPEN_TRANSFERS = "dev.piko.extra.OPEN_TRANSFERS"

// 与 androidx.activity 的 DefaultLightScrim、DefaultDarkScrim 相同，那两个是 internal
private val NavBarLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NavBarDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
