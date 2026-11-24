package dev.piko

import java.util.Locale
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.data.auth.AppLanguage

import android.Manifest
import android.app.KeyguardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areStatusBarsVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.piko.ui.theme.PikoTheme
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.piko.ui.adaptive.isHeightCompact
import dev.piko.ui.screens.player.findActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.InstantSheetState
import dev.piko.shared.state.TorrentMagnet
import dev.piko.shared.state.extractLinks
import dev.piko.shared.upload.UploadSelection
import dev.piko.ui.PikoApp
import dev.piko.ui.PikoServices
import dev.piko.ui.hasBackgroundWork
import dev.piko.ui.fileWorkChanges
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.screens.player.MediampVideoPlayerScreen
import dev.piko.ui.theme.Appearance
import dev.piko.ui.theme.SeedTheme
import dev.piko.ui.theme.ThemeMode
import dev.piko.ui.theme.appearanceFlow
import dev.piko.ui.theme.isDark
import dev.piko.platform.installMotionScale
import dev.piko.util.PikPakAppLink
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

    private var openTorrentJob: Job? = null
    private var pendingLockedIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemLocale = android.content.res.Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()
        PikoLocale.bindSystemLocale(systemLocale.toLanguageTag())
        super.onCreate(savedInstanceState)
        val initialLanguage = PikoApplication.instance.sessionManager.currentAppLanguage()
        applyLanguage(PikoLocale.resolve(initialLanguage))
        PikoLocale.apply(initialLanguage)
        enableEdgeToEdge()
        // 重建时（进程被杀后从最近任务回来）系统会把最初的启动 Intent 再交一次，
        // 不判断的话同一条分享进来的磁力链会再弹一次秒传面板
        if (savedInstanceState == null) handleIntent(intent)

        val app = PikoApplication.instance
        val appearanceFlow = app.sessionManager.appearanceFlow(app.platform.supportsDynamicColor)
        // 从预加载的无锁偏好快照同步构造初始外观，既避免主线程 runBlocking 又避免首帧闪回默认主题
        val initialSnapshot = app.sessionManager.currentSnapshot
        val initialAppearance = Appearance(
            mode = ThemeMode.entries.find { it.name.equals(initialSnapshot.themeMode, ignoreCase = true) } ?: ThemeMode.SYSTEM,
            seed = SeedTheme.colorEntries.find { it.name == initialSnapshot.themeSeed },
            dynamicColorAvailable = app.platform.supportsDynamicColor,
            pureBlack = initialSnapshot.pureBlack,
            einkMode = initialSnapshot.einkMode,
            motionPreference = initialSnapshot.motionPreference,
        )
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
            val appSecurity = app.platform.appSecurity
            val hideInRecents by appSecurity.hideInRecents.collectAsStateWithLifecycle()
            val appLockEnabled by appSecurity.appLockEnabled.collectAsStateWithLifecycle()
            val isLocked by appSecurity.locked.collectAsStateWithLifecycle()
            // 系统栏图标的深浅默认看系统的夜间模式。应用强制浅色而系统是深色时，状态栏会是
            // 浅色图标压在浅色背景上，所以改为按应用实际的深浅判断。导航栏遮罩沿用库的默认值
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(NavBarLightScrim, NavBarDarkScrim) { darkTheme },
                )
                onDispose {}
            }
            LaunchedEffect(hideInRecents, appLockEnabled) {
                appSecurity.applyWindowSecurity(this@MainActivity)
            }
            LaunchedEffect(isLocked) {
                if (!isLocked) {
                    pendingLockedIntent?.let { deferred ->
                        pendingLockedIntent = null
                        handleIntentNow(deferred)
                    }
                }
            }
            if (isLocked) {
                PikoTheme(appearance = appearance) {
                    AppLockOverlay(
                        activity = this@MainActivity,
                        appSecurity = appSecurity,
                    )
                }
            } else {
                PikoApp(
                    onLanguageChanged = ::applyLanguage,
                    services = app.services,
                    platform = app.platform,
                    appearance = appearance,
                    videoPlayer = videoPlayer,
                )
                AskForNotificationsOnFirstWork(app.services)
                if (isHeightCompact()) HideStatusBarWhileShort()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PikoApplication.instance.platform.appSecurity.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            PikoApplication.instance.platform.appSecurity.onAppBackgrounded()
        }
    }

    private fun applyLanguage(language: AppLanguage) {
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val configuration = android.content.res.Configuration(resources.configuration)
        configuration.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
        @Suppress("DEPRECATION")
        applicationContext.resources.updateConfiguration(configuration, applicationContext.resources.displayMetrics)
    }

    override fun onResume() {
        super.onResume()
        PikoApplication.instance.restoreTransportMemoryBudget()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (PikoApplication.instance.platform.appSecurity.locked.value) {
            pendingLockedIntent = intent
            return
        }
        handleIntentNow(intent)
    }

    private fun handleIntentNow(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_TRANSFERS, false)) {
            PikoApplication.instance.services.requestOpenTransfers()
            return
        }
        intent.dataString?.let(PikPakAppLink::parse)?.let { target ->
            when (target) {
                PikPakAppLink.Target.Drive -> PikoApplication.instance.services.requestOpenDrive()
                is PikPakAppLink.Target.Import -> PikoApplication.instance.instantMagnetRepository.onIncomingMagnet(target.text)
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

    private fun isOwnFileProviderUri(uri: Uri): Boolean =
        uri.authority.equals("${packageName}.fileprovider", ignoreCase = true)

    /** 打开的 .torrent 文件。按类型认而不按扩展名：content: URI 的路径多半不带文件名。 */
    private fun torrentUri(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val uri = intent.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT && !isOwnFileProviderUri(it) } ?: return null
        val mimeType = intent.type ?: runCatching { contentResolver.getType(uri) }
            .onFailure { PikoLog.w("IncomingLink", "Failed to query MIME type for incoming URI (authority=${uri.authority})", it) }
            .getOrNull()
        return uri.takeIf { mimeType == TORRENT_MIME_TYPE }
    }

    /** 与桌面端拖进种子相同，在本地换算成磁力链接。读取放在后台：文档提供者可能现从网络取内容。 */
    private fun openTorrent(uri: Uri) {
        val app = PikoApplication.instance
        openTorrentJob?.cancel()
        openTorrentJob = app.appScope.launch {
            val magnet = try {
                withTimeout(TORRENT_READ_TIMEOUT_MS) {
                    val streamRef = AtomicReference<InputStream?>(null)
                    val readJob = async(Dispatchers.IO) {
                        runInterruptible {
                            app.contentResolver.openInputStream(uri)?.use { stream ->
                                streamRef.set(stream)
                                TorrentMagnet.readBoundedBytes(stream, TorrentMagnet.MAX_TORRENT_BYTES)
                            }
                        }
                    }
                    val bytes = try {
                        readJob.await()
                    } finally {
                        streamRef.getAndSet(null)?.let { stream -> runCatching { stream.close() } }
                        readJob.cancel()
                    }
                    when {
                        bytes == null -> {
                            PikoLog.w("IncomingLink", "Unavailable or oversized .torrent URI (authority=${uri.authority})")
                            null
                        }
                        else -> withContext(Dispatchers.Default) { TorrentMagnet.fromBytes(bytes) }.also { parsed ->
                            if (parsed == null) {
                                PikoLog.w("IncomingLink", "Failed to parse .torrent payload (${bytes.size} bytes)")
                            }
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                PikoLog.w("IncomingLink", "Timed out reading .torrent URI (authority=${uri.authority})", timeout)
                null
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                PikoLog.w("IncomingLink", "Failed to read or parse .torrent URI (authority=${uri.authority})", e)
                null
            }
            if (magnet != null) {
                app.instantMagnetRepository.onIncomingMagnet(magnet)
            } else {
                withContext(Dispatchers.Main) { app.services.postAppNotice(tr("无法读取种子文件", "Unable to read torrent file", "トレントファイルを読み込めません", "토렌트 파일을 읽을 수 없습니다", "無法讀取種子檔案")) }
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
        // AndroidPikoUploadSources 只认外部提供的 content: URI，拒绝自身 FileProvider URI 避免回环读取私有目录
        return uris.filter { it.scheme == ContentResolver.SCHEME_CONTENT && !isOwnFileProviderUri(it) }
    }

    /**
     * 含 PikPak 分享链接的输入：打开 mypikpak.com/s/ 链接，或分享来的一段文本。返回整段文本而不只是链接，
     * 转发的分享常把提取码写在链接后面，面板从同一段文本里把它认出来。
     */
    private fun sharedTexts(intent: Intent): List<String> = buildList {
        intent.dataString?.let(::add)
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(::add)
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                item.text?.toString()?.let(::add)
                item.uri?.toString()?.let(::add)
            }
        }
    }.map(String::trim).filter(String::isNotEmpty).distinct()

    private fun extractShareText(intent: Intent): String? {
        val texts = sharedTexts(intent)
        return texts.takeIf { candidates -> candidates.any { InstantSheetState.findShareLink(it) != null } }
            ?.joinToString("\n")
    }

    private fun extractMagnet(intent: Intent): String? {
        val allowAnyDownloadLink = intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE
        return sharedTexts(intent)
            .flatMap { extractLinks(it) }
            .filter { allowAnyDownloadLink || it.isMagnet || it.uri.startsWith("ed2k://", ignoreCase = true) }
            .distinctBy { it.key }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { it.uri }
    }

}

/**
 * Android 13 起通知要在运行时授权，只在清单里声明的话，传输进度与任务结果的通知都发不出来。
 * 等第一次有工作要在后台跑时再问：这时说得清为什么要通知，一打开应用就问多半会被拒。
 * 每次 Activity 组合生命周期内最多问一次（Activity 重建后若仍未授权会再次检查）；拒绝两次后系统自己也不再弹。
 */
@Composable
private fun AppLockOverlay(
    activity: ComponentActivity,
    appSecurity: dev.piko.platform.AndroidAppSecurity,
) {
    var errorText by remember { mutableStateOf<String?>(null) }
    val legacyCredentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            appSecurity.markUnlocked()
        }
    }

    fun requestUnlock() {
        errorText = null
        val handled = appSecurity.promptBiometricUnlock(
            activity = activity,
            onSuccess = { errorText = null },
            onError = { msg -> if (msg.isNotBlank()) errorText = msg },
        )
        if (!handled) {
            val km = activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            @Suppress("DEPRECATION")
            val intent = km?.createConfirmDeviceCredentialIntent(
                tr("解锁应用", "Unlock app", "ロックを解除", "앱 잠금 해제", "解鎖應用程式"),
                tr("请验证设备锁屏以继续访问", "Confirm device screen lock to continue", "続行するには端末のロックを解除してください", "계속하려면 기기 잠금을 해제하세요", "請驗證裝置螢幕鎖定以繼續存取"),
            )
            if (intent != null) {
                legacyCredentialLauncher.launch(intent)
            } else if (!appSecurity.canUseDeviceLock()) {
                // 设备锁屏密码已被用户在系统设置中关闭，自动停用应用锁避免永久锁死
                appSecurity.setAppLockEnabled(false)
            } else {
                errorText = tr(
                    "无法调起系统身份验证，请重试",
                    "Unable to launch system authentication. Try again.",
                    "システム認証を起動できませんでした。再試行してください。",
                    "시스템 인증을 시작할 수 없습니다. 다시 시도해 주세요.",
                    "無法啟動系統身分驗證，請重試",
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        requestUnlock()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = tr("应用已锁定", "App locked", "アプリはロックされています", "앱이 잠겨 있습니다", "應用程式已鎖定"),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!errorText.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = errorText.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = ::requestUnlock) {
                Text(tr("验证并解锁", "Unlock", "ロックを解除", "잠금 해제", "驗證並解鎖"))
            }
        }
    }
}

@Composable
private fun AskForNotificationsOnFirstWork(services: PikoServices) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(services) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return@LaunchedEffect
        }
        // 与拉起前台服务同一个判断（hasBackgroundWork）；任务表是 StateFlow，snapshotFlow 看不到，另外合进来
        combine(snapshotFlow { services.hasBackgroundWork() }, services.downloadManager.tasks, services.uploadManager.tasks,
            services.fileWorkChanges()) { _, _, _, _ ->
            services.hasBackgroundWork()
        }.first { it }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private const val TORRENT_MIME_TYPE = "application/x-bittorrent"
private const val TORRENT_READ_TIMEOUT_MS = 15_000L

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

/** 进行中的常驻通知点进来时带上，打开传输页看各项进度，见 WorkResultNotifier 与 PikoDownloadService。 */
internal const val EXTRA_OPEN_TRANSFERS = "dev.piko.extra.OPEN_TRANSFERS"

// 与 androidx.activity 的 DefaultLightScrim、DefaultDarkScrim 相同，那两个是 internal
private val NavBarLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NavBarDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
