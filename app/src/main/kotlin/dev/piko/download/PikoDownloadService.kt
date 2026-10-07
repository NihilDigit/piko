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
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.piko.EXTRA_OPEN_TRANSFERS
import dev.piko.MainActivity
import dev.piko.PikoApplication
import dev.piko.shared.log.PikoLog
import dev.piko.ui.WorkMeter
import dev.piko.ui.WorkNotificationContent
import dev.piko.ui.hasBackgroundWork
import dev.piko.ui.runningWork
import dev.piko.ui.workNotificationContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * 应用退到后台时让下载、上传、解压、归档与查重继续的前台服务，挂着那条进度通知。
 *
 * 只在应用不在前台时运行：由 WorkResultNotifier 在退到后台而仍有工作时拉起，回到前台或没有工作时自己退下并撤掉通知。
 * 前台时界面上就有进度，系统规定前台服务必须挂一条通知，所以前台时干脆不开它。
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
        // 第一条就写眼下在做的事：观察循环的第一次更新之前工作可能已经结束（小目录的查找重复只要几秒），
        // 写死成「传输」的话，查找重复时看到的就一直是它
        val initialNotification = buildNotification(currentContent())
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
            // 下一次退到后台时 WorkResultNotifier 会再试
            PikoLog.w(TAG, "进入前台服务被拒，传输在无通知的情况下继续", e)
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
        PikoLog.w(TAG, "前台服务到达系统时长上限，暂停全部传输")
        PikoApplication.instance.downloadManager.pauseAll()
        PikoApplication.instance.uploadManager.pauseAll()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startObservingDownloadsIfNeeded() {
        if (observeJob?.isActive == true) return

        observeJob = serviceScope.launch {
            val services = PikoApplication.instance.services
            // 归档、解压、查重是 Compose 状态，下载与上传是 StateFlow，合在一起只当作「有变化」的信号，
            // 每次都从 services 重新取一遍
            val serverWork = snapshotFlow { services.runningWork() }
            // 用 collect 而非 collectLatest，每轮末尾等一秒：conflate 让等待期间的中间值直接跳过，
            // 通知更新被压到每秒一次。系统对单个应用的通知更新有频率上限，超出的直接丢弃，
            // 多发只是白白重建 Notification。combine 自身不合并，要显式 conflate
            combine(services.downloadManager.tasks, services.uploadManager.tasks, serverWork, AppVisibility.visible) { _, _, _, visible ->
                visible
            }.conflate().collect { visible ->
                when {
                    // 回到前台：界面上就有进度，撤掉通知、退出前台服务，再退到后台时由 WorkResultNotifier 重新拉起
                    visible -> stepDown()
                    !services.hasBackgroundWork() -> {
                        // 没有进行中的任务，延迟 2 秒后若仍无任务则优雅退出前台
                        delay(2000)
                        if (!services.hasBackgroundWork()) stepDown()
                    }
                    else -> {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(currentContent()))
                        delay(NOTIFICATION_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private fun stepDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun currentContent(): WorkNotificationContent =
        workNotificationContent(PikoApplication.instance.services.runningWork())

    private fun buildNotification(content: WorkNotificationContent): Notification {
        // 下载、上传、解压与归档的进度都在传输页，点通知直接去那里
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TRANSFERS, true)
        }
        // 请求码不能与结局通知的 0 相同：PendingIntent 比较 Intent 时不看 extras，同码就是同一个，
        // 后建的那个以 FLAG_UPDATE_CURRENT 把这里的 extras 覆盖掉
        val pendingIntent = PendingIntent.getActivity(
            this,
            OPEN_TRANSFERS_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 文案与进度值都来自 WorkProgress，与传输页的行同一份。次要说明（速度、当前项）放进 subText，
        // 由系统排在通知头部，不在正文里拼分隔符
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (content.uploadOnly) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
        when (val meter = content.meter) {
            is WorkMeter.Determinate -> builder.setProgress(PROGRESS_MAX, (meter.fraction * PROGRESS_MAX).toInt(), false)
            WorkMeter.Indeterminate -> builder.setProgress(0, 0, true)
            WorkMeter.None -> Unit
        }
        if (content.lines.isNotEmpty()) {
            builder.setStyle(NotificationCompat.InboxStyle().also { style -> content.lines.forEach(style::addLine) })
        }
        return builder
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Android 12 起前台服务的通知默认延迟至多 10 秒才显示，好让很快结束的服务不出通知；
            // 这里的工作都是用户刚发起、要跑一阵的，延迟只会让人以为没开始
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件传输",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "应用在后台时下载、上传、解压、归档与查找重复的进度"
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
        private const val TAG = "DownloadService"
        private const val NOTIFICATION_ID = 9527
        private const val OPEN_TRANSFERS_REQUEST_CODE = 1
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
        // 千分位：大文件夹归档时百分位一格要几十个文件，进度条看着不动
        private const val PROGRESS_MAX = 1_000
        private const val CHANNEL_ID = "piko_download_channel"

        fun start(context: Context) {
            val intent = Intent(context, PikoDownloadService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // 处理后台启动限制回退
                PikoLog.w(TAG, "启动传输服务被拒", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PikoDownloadService::class.java))
        }
    }
}
