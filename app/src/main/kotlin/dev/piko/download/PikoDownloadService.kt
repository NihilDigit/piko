package dev.piko.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.piko.MainActivity
import dev.piko.PikoApplication
import dev.piko.ui.components.toReadableSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground Service managing background downloading and lossless video segment extraction.
 *
 * Maintains a foreground service with continuous notification while tasks are DOWNLOADING,
 * displaying real-time aggregated throughput and progress. Gracefully steps down when idle.
 *
 * Documentation References:
 * - Android Foreground Services: android-docs-mirror/pages/develop/background-work/services/foreground-services.md
 * - Android Notifications: android-docs-mirror/pages/develop/ui/views/notifications/build-notification.md
 * - Kotlin Flow Collection: kotlin-docs-mirror/pages/docs/flow.md
 */
class PikoDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var notificationManager: NotificationManager
    private var observeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildNotification("正在准备下载...", 0, 0, 0L)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: RuntimeException) {
            // Android 12 起应用在后台时不能进入前台服务（ForegroundServiceStartNotAllowedException），
            // Android 15 起 dataSync 用满当日时长后也会被拒。下载协程本身不依赖服务，
            // 应用回到前台后下一次启动下载会重新拉起服务
            stopSelf()
            return START_NOT_STICKY
        }

        startObservingDownloadsIfNeeded()
        return START_NOT_STICKY
    }

    /**
     * Android 15 起 dataSync 类型 24 小时内累计只能运行 6 小时，到时系统回调这里，
     * 几秒内不停止服务应用就会崩溃。任务转为暂停而不是失败：文件长度即断点，
     * 用户回到应用可以原地继续。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        PikoApplication.instance.downloadManager.pauseAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startObservingDownloadsIfNeeded() {
        if (observeJob?.isActive == true) return

        observeJob = serviceScope.launch {
            val downloadManager = PikoApplication.instance.downloadManager
            // 用 collect 而非 collectLatest，每轮末尾等一秒：StateFlow 本身是合并的，等待期间的
            // 中间值直接跳过，通知更新被压到每秒一次。系统对单个应用的通知更新有频率上限，
            // 超出的直接丢弃，多发只是白白重建 Notification
            downloadManager.tasks.collect { tasksMap ->
                val activeTasks = tasksMap.values.filter { it.status == DownloadStatus.DOWNLOADING }
                if (activeTasks.isEmpty()) {
                    // 没有正在下载的任务，延迟 2 秒后若仍无任务则优雅退出前台。
                    // PENDING 也算活跃：新任务要先查一次本地长度才转为 DOWNLOADING
                    delay(2000)
                    val stillActive = downloadManager.tasks.value.values.any {
                        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
                    }
                    if (!stillActive) {
                        ServiceCompat.stopForeground(this@PikoDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                } else {
                    val totalSpeed = activeTasks.sumOf { it.speedBytesPerSec }
                    val totalDownloaded = activeTasks.sumOf { it.downloadedBytes }
                    val totalBytes = activeTasks.sumOf { it.totalBytes }
                    val progressPercent = if (totalBytes > 0) {
                        ((totalDownloaded.toDouble() / totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                    } else 0

                    val title = if (activeTasks.size == 1) {
                        activeTasks.first().fileName
                    } else {
                        "正在下载 ${activeTasks.size} 个任务"
                    }

                    val notification = buildNotification(
                        title = title,
                        progress = progressPercent,
                        total = 100,
                        speedBytesPerSec = totalSpeed,
                        downloadedBytes = totalDownloaded,
                        totalBytes = totalBytes,
                    )
                    notificationManager.notify(NOTIFICATION_ID, notification)
                    delay(NOTIFICATION_INTERVAL_MS)
                }
            }
        }
    }

    private fun buildNotification(
        title: String,
        progress: Int,
        total: Int,
        speedBytesPerSec: Long,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 速度放进 subText 这个独立字段，由系统排在通知头部，不在正文里拼分隔符
        val speedText = if (speedBytesPerSec > 0) "${speedBytesPerSec.toReadableSize()}/s" else "--/s"
        val contentText = if (totalBytes > 0) {
            "${downloadedBytes.toReadableSize()} / ${totalBytes.toReadableSize()}"
        } else {
            "传输中"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(speedText)
            .setProgress(total, progress, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件传输与下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示后台文件并发下载与无损切片实时进度"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        observeJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 9527
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        private const val CHANNEL_ID = "piko_download_channel"

        fun start(context: Context) {
            val intent = Intent(context, PikoDownloadService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // 处理后台启动限制回退
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PikoDownloadService::class.java))
        }
    }
}
