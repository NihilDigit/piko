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
import androidx.core.content.ContextCompat
import dev.piko.MainActivity
import dev.piko.PikoApplication
import dev.piko.ui.components.toReadableSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 负责 Android 前台下载生命周期的系统 Service。
 * 在任务处于 DOWNLOADING 状态时常驻前台，显示通知栏实时并发速率与进度；
 * 所有下载暂停或完成后优雅退出前台，防止系统低内存杀后台。
 */
class PikoDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildNotification("正在准备下载...", 0, 0, 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observeDownloads()
        return START_NOT_STICKY
    }

    private fun observeDownloads() {
        serviceScope.launch {
            val downloadManager = PikoApplication.instance.downloadManager
            downloadManager.tasks.collectLatest { tasksMap ->
                val activeTasks = tasksMap.values.filter { it.status == DownloadStatus.DOWNLOADING }
                if (activeTasks.isEmpty()) {
                    // 没有正在下载的任务，延迟 2 秒后若仍无任务则优雅退出前台
                    delay(2000)
                    val stillActive = downloadManager.tasks.value.values.any { it.status == DownloadStatus.DOWNLOADING }
                    if (!stillActive) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            @Suppress("DEPRECATION")
                            stopForeground(true)
                        }
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

        val speedText = if (speedBytesPerSec > 0) "${speedBytesPerSec.toReadableSize()}/s" else "--/s"
        val contentText = if (totalBytes > 0) {
            "${downloadedBytes.toReadableSize()} / ${totalBytes.toReadableSize()} • $speedText"
        } else {
            "传输中 • $speedText"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
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
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 9527
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
