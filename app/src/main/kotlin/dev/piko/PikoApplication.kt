package dev.piko

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.piko.data.auth.SessionManager
import dev.piko.data.client.PikPakClientManager
import dev.piko.data.repository.DriveRepository
import dev.piko.data.repository.InstantMagnetRepository
import dev.piko.data.repository.MediaRepository
import dev.piko.data.repository.TaskRepository
import dev.piko.download.PikoDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class PikoApplication : Application(), SingletonImageLoader.Factory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var sessionManager: SessionManager
        private set

    lateinit var clientManager: PikPakClientManager
        private set

    lateinit var driveRepository: DriveRepository
        private set

    lateinit var instantMagnetRepository: InstantMagnetRepository
        private set

    lateinit var taskRepository: TaskRepository
        private set

    lateinit var mediaRepository: MediaRepository
        private set

    lateinit var downloadManager: PikoDownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        sessionManager = SessionManager(this)
        clientManager = PikPakClientManager(this, sessionManager, appScope)
        driveRepository = DriveRepository(this, clientManager)
        instantMagnetRepository = InstantMagnetRepository(clientManager)
        taskRepository = TaskRepository(clientManager)
        mediaRepository = MediaRepository(clientManager, sessionManager)
        downloadManager = PikoDownloadManager(this, clientManager, appScope)
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
