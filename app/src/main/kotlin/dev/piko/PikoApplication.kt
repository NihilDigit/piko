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
import dev.piko.shared.data.PikoAccountRepository
import dev.piko.shared.data.PikoClientManager
import dev.piko.shared.data.InstantMagnetRepository
import dev.piko.shared.data.TaskRepository
import dev.piko.shared.download.PikoDownloadCoordinator
import dev.piko.shared.media.PikoMediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class PikoApplication : Application(), SingletonImageLoader.Factory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var sessionManager: SessionManager
        private set

    lateinit var clientManager: PikoClientManager
        private set

    lateinit var driveRepository: DriveRepository
        private set

    lateinit var accountRepository: PikoAccountRepository
        private set

    lateinit var instantMagnetRepository: InstantMagnetRepository
        private set

    lateinit var taskRepository: TaskRepository
        private set

    lateinit var mediampMediaRepository: PikoMediaRepository
        private set

    lateinit var downloadManager: PikoDownloadCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        sessionManager = SessionManager(this)
        clientManager = PikoClientManager(AndroidPikoSessionStore(this, sessionManager), appScope)
        driveRepository = DriveRepository(clientManager, sessionManager)
        accountRepository = PikoAccountRepository(clientManager, sessionManager)
        instantMagnetRepository = InstantMagnetRepository(clientManager)
        taskRepository = TaskRepository(clientManager)
        mediampMediaRepository = PikoMediaRepository(clientManager, sessionManager)
        downloadManager = PikoDownloadCoordinator(
            clientProvider = clientManager,
            preferences = sessionManager,
            storage = AndroidPikoDownloadStorage(this, sessionManager, appScope),
            scope = appScope,
            segmentDownloader = AndroidPikoSegmentDownloader(this),
            onDownloadStarted = { PikoDownloadService.start(this) },
        )
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
