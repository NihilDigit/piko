package dev.piko

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.piko.data.auth.AndroidPikoSessionStore
import dev.piko.data.auth.SessionManager
import dev.piko.data.repository.DriveRepository
import dev.piko.download.AndroidPikoDownloadStorage
import dev.piko.download.AndroidPikoSegmentDownloader
import dev.piko.download.PikoDownloadService
import dev.piko.platform.AndroidPikoPlatform
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import dev.piko.shared.state.InstantSession
import dev.piko.ui.PikoServices
import dev.piko.update.AppUpdater
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val instantSession: InstantSession get() = services.instantSession

    /** 首次用到时才建：多数启动根本不检查更新，不必为它先建一个 HTTP 客户端。 */
    val appUpdater by lazy { AppUpdater(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        sessionManager = SessionManager(this)
        val clientManager = PikoClientManager(AndroidPikoSessionStore(this, sessionManager), appScope)
        val mediaRepository = PikoMediaRepository(clientManager, sessionManager)
        services = PikoServices(
            preferences = sessionManager,
            clientManager = clientManager,
            mediaRepository = mediaRepository,
            downloadManager = PikoDownloadCoordinator(
                clientProvider = clientManager,
                preferences = sessionManager,
                storage = AndroidPikoDownloadStorage(this, sessionManager, appScope),
                scope = appScope,
                segmentDownloader = AndroidPikoSegmentDownloader(this),
                mediaRepository = mediaRepository,
                onDownloadStarted = { PikoDownloadService.start(this) },
            ),
        )
        platform = AndroidPikoPlatform(this) { appUpdater }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
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
