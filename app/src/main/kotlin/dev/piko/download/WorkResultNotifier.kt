package dev.piko.download

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.piko.MainActivity
import dev.piko.shared.state.DuplicateFinderState
import dev.piko.ui.PikoServices
import dev.piko.ui.WorkNotice
import dev.piko.ui.workNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 后台工作的结局发成系统通知：下载、上传、解压、查找重复，汇总见 workNotices。应用在前台时不发，
 * 列表与 Snackbar 已经说明了。渠道与传输进度分开：进度是不出声的常驻条，结局要能提醒到人。
 *
 * 另外在解压或查找重复开始时拉起前台服务：两者都是长时间的网络轮询，切到后台后没有前台服务，
 * 进程随时会被回收。下载与上传各自在入队时拉起，不经这里。
 */
internal class WorkResultNotifier(
    private val app: Application,
    private val services: PikoServices,
    private val scope: CoroutineScope,
) : Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0
    private var nextNotificationId = FIRST_NOTIFICATION_ID

    fun start() {
        app.registerActivityLifecycleCallbacks(this)
        createChannel()
        // workNotices 与下面的判断都读 Compose 状态，在主线程上收
        scope.launch(Dispatchers.Main) {
            services.workNotices().collect { notice -> if (startedActivities == 0) post(notice) }
        }
        scope.launch(Dispatchers.Main) {
            snapshotFlow { hasLongRunningWork() }.collect { busy -> if (busy) PikoDownloadService.start(app) }
        }
    }

    private fun hasLongRunningWork(): Boolean {
        val scanning = services.duplicateSession.state?.phase.let {
            it == DuplicateFinderState.Phase.SCANNING || it == DuplicateFinderState.Phase.ANALYZING
        }
        return services.archiveExtractSession.jobs.isNotEmpty() || scanning
    }

    private fun post(notice: WorkNotice) {
        val manager = NotificationManagerCompat.from(app)
        // Android 13 起要用户授予通知权限；没给就不发，不去弹权限框打断
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(notice.title)
            .setContentText(notice.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.message))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(nextNotificationId++, notification) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "任务结果", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "下载、上传、解压与查找重复完成或失败时提醒"
        }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private companion object {
        const val CHANNEL_ID = "piko_work_results"
        // 与传输进度的常驻通知（9527）错开
        const val FIRST_NOTIFICATION_ID = 20_000
    }
}
