package dev.piko

import android.app.Application
import android.os.Build
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.piko.data.auth.AndroidPikoSessionStore
import dev.piko.data.auth.SessionManager
import dev.piko.data.repository.DriveRepository
import dev.piko.download.AndroidPikoDownloadStorage
import dev.piko.download.AndroidPikoSegmentDownloader
import dev.piko.download.WorkResultNotifier
import dev.piko.platform.AndroidPikoPlatform
import dev.piko.platform.followSystemAnimatorScale
import dev.piko.shared.data.FilePikoCacheStore
import dev.piko.shared.sync.JvmSyncCipher
import dev.piko.shared.scrape.MetaTubeService
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoClientManager
import java.io.File
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.FileClipCache
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.net.PikoProxySelector
import dev.piko.shared.state.InstantSession
import dev.piko.shared.upload.PikoUploadCoordinator
import dev.piko.ui.PikoServices
import dev.piko.ui.theme.PikoMotionScale
import dev.piko.upload.AndroidPikoUploadSources
import dev.piko.update.AppUpdater
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

class PikoApplication : Application(), SingletonImageLoader.Factory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var sessionManager: SessionManager
        private set

    /** 共享界面用到的进程级对象，经 LocalPikoServices 交给界面。 */
    lateinit var services: PikoServices
        private set

    lateinit var platform: AndroidPikoPlatform
        private set

    // 以下几项是 services 的别名：播放器与下载服务还在 app 模块里，经这里取用
    val clientManager: PikoClientManager get() = services.clientManager
    val driveRepository: DriveRepository get() = services.driveRepository
    val instantMagnetRepository: InstantMagnetRepository get() = services.instantMagnetRepository
    val mediampMediaRepository: PikoMediaRepository get() = services.mediaRepository
    val downloadManager: PikoDownloadCoordinator get() = services.downloadManager
    val uploadManager: PikoUploadCoordinator get() = services.uploadManager
    val instantSession: InstantSession get() = services.instantSession

    /** 首次用到时才建：开屏检查要等界面第一次组合，不必在 onCreate 里就先建一个 HTTP 客户端。 */
    val appUpdater by lazy { AppUpdater(this) }

    private val baseTransportBudgetBytes: Long by lazy {
        (Runtime.getRuntime().maxMemory() / 6L).coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024)
    }

    fun restoreTransportMemoryBudget() {
        if (::services.isInitialized) {
            services.clientManager.setTransportMemoryBudgetBytes(baseTransportBudgetBytes)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::services.isInitialized) return
        @Suppress("DEPRECATION")
        val target = when {
            level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL ->
                (baseTransportBudgetBytes / 2L).coerceAtLeast(16L * 1024 * 1024)
            level == TRIM_MEMORY_UI_HIDDEN || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_MODERATE ->
                (baseTransportBudgetBytes * 3L / 4L).coerceAtLeast(16L * 1024 * 1024)
            else -> return
        }
        services.clientManager.setTransportMemoryBudgetBytes(target)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::services.isInitialized) {
            services.clientManager.setTransportMemoryBudgetBytes(16L * 1024 * 1024)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installLog()

        sessionManager = SessionManager(this)
        // 先于进程级服务与系统通知渠道初始化语言绑定，确保无 Activity 唤起或冷启动时通知渠道按用户语言注册
        val systemLocale = android.content.res.Resources.getSystem().configuration.locales.get(0) ?: java.util.Locale.getDefault()
        PikoLocale.bindSystemLocale(systemLocale.toLanguageTag())
        PikoLocale.apply(runBlocking { sessionManager.appLanguageFlow.first() })
        installProxy()
        val clientManager = PikoClientManager(
            sessionStore = AndroidPikoSessionStore(this),
            scope = appScope,
            transportMemoryBudgetBytes = baseTransportBudgetBytes,
        )
        val mediaRepository = PikoMediaRepository(
            clientManager,
            sessionManager,
            clipCache = FileClipCache(File(cacheDir, "piko/clip-cache")),
        )
        val cacheStore = FilePikoCacheStore(File(cacheDir, "piko").path)
        services = PikoServices(
            platformPreferences = sessionManager,
            clientManager = clientManager,
            mediaRepository = mediaRepository,
            downloadManager = PikoDownloadCoordinator(
                clientProvider = clientManager,
                preferences = sessionManager,
                storage = AndroidPikoDownloadStorage(this, sessionManager, appScope),
                scope = appScope,
                segmentDownloader = AndroidPikoSegmentDownloader(this),
                mediaRepository = mediaRepository,
            ),
            uploadSources = AndroidPikoUploadSources(this),
            cacheStore = cacheStore,
            syncCipher = JvmSyncCipher(),
            metaTube = MetaTubeService(sessionManager, cacheStore = cacheStore, scope = appScope) {
                HttpClient(OkHttp) {
                    engine {
                        config {
                            dispatcher(okhttp3.Dispatcher().apply {
                                maxRequests = 64
                                maxRequestsPerHost = 16
                            })
                        }
                    }
                }
            },
        )
        val motionScale = PikoMotionScale().apply { followSystemAnimatorScale(this@PikoApplication) }
        appScope.launch { sessionManager.reduceMotionFlow.collect { motionScale.appReduced = it } }
        platform = AndroidPikoPlatform(this, { appUpdater }, motionScale)
        val notifier = WorkResultNotifier(this, services, appScope).also { it.start() }
        appScope.launch {
            sessionManager.appLanguageFlow.collect { lang ->
                PikoLocale.apply(lang)
                notifier.refreshChannel()
            }
        }
    }

    /**
     * 日志放在私有的 files/logs，导出时另拷一份到缓存目录再分享。debug 包同时进 logcat；
     * release 包不进，省掉每条一次的 logcat 写入。
     */
    /**
     * 要赶在 SDK、图片加载与更新检查建出 OkHttpClient 之前：它们建客户端时取走默认的 ProxySelector。
     * 同步读一次设置，手动代理从第一个请求起就生效；之后改设置经 flow 跟上。
     */
    private fun installProxy() {
        PikoProxySelector.install(runBlocking { sessionManager.proxySettingFlow.first() })
        appScope.launch { sessionManager.proxySettingFlow.collect(PikoProxySelector::apply) }
    }

    private fun installLog() {
        val echo: ((LogLevel, String, String, Throwable?) -> Unit)? = if (BuildConfig.DEBUG) {
            { level, tag, message, error ->
                val priority = when (level) {
                    LogLevel.DEBUG -> Log.DEBUG
                    LogLevel.INFO -> Log.INFO
                    LogLevel.WARN -> Log.WARN
                    LogLevel.ERROR -> Log.ERROR
                }
                Log.println(priority, "Piko/$tag", if (error == null) message else "$message\n${Log.getStackTraceString(error)}")
            }
        } else {
            null
        }
        PikoLog.install(File(filesDir, "logs").path, echo)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            PikoLog.e("Crash", "线程 ${thread.name} 未捕获的异常", error)
            // 进程马上就没了，等写入协程把这条落盘；卡住也只等一秒，不耽误系统的崩溃处理
            runBlocking { withTimeoutOrNull(1_000) { PikoLog.flush() } }
            previous?.uncaughtException(thread, error)
        }
        PikoLog.i(
            "App",
            "启动 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}），Android ${Build.VERSION.RELEASE}，${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 96
                maxRequestsPerHost = 32
            })
            .connectionPool(okhttp3.ConnectionPool(32, 2, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .build()
    }

    companion object {
        lateinit var instance: PikoApplication
            private set
    }
}
