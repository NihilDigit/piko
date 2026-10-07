package dev.piko.download

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.piko.MainActivity
import dev.piko.ui.PikoServices
import dev.piko.ui.WorkNotice
import dev.piko.ui.hasBackgroundWork
import dev.piko.ui.workNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 后台工作的系统通知，只在应用不在前台时出现（[AppVisibility]）：前台时传输页的行、网盘页的 sheet 与 Snackbar 已经说明了。
 *
 * 进度：应用退到后台而还有工作在跑时拉起前台服务，它挂着那条进度通知；回到前台时服务自己退下、撤掉通知，见 [PikoDownloadService]。
 * 前台服务必须挂着一条通知，所以「前台不显示进度通知」只能做成「前台不开前台服务」：应用在前台时进程本就是前台优先级，
 * 用不着它。
 *
 * 结局（下载完成、归档完成等，汇总见 workNotices）：不在前台时发一条，渠道与进度分开，进度不出声，结局要能提醒到人。
 * 前台时不发，由页内的提示说明。
 */
internal class WorkResultNotifier(
    private val app: Application,
    private val services: PikoServices,
    private val scope: CoroutineScope,
) {
    private var nextNotificationId = FIRST_NOTIFICATION_ID

    fun start() {
        createChannel()
        // workNotices 与下面的判断都读 Compose 状态，在主线程上收
        scope.launch(Dispatchers.Main) {
            services.workNotices().collect { notice -> if (!AppVisibility.isVisible) post(notice) }
        }
        scope.launch(Dispatchers.Main) {
            // 下载与上传的任务表是 StateFlow，snapshotFlow 看不到它们的变化，另外合进来
            val busy = combine(
                snapshotFlow { services.hasBackgroundWork() },
                services.downloadManager.tasks,
                services.uploadManager.tasks,
            ) { _, _, _ -> services.hasBackgroundWork() }
            combine(AppVisibility.visible, busy) { visible, working -> !visible && working }
                .distinctUntilChanged()
                .collect { needed -> if (needed) PikoDownloadService.start(app) }
        }
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
            description = "下载、上传、解压、归档与查找重复完成或失败时提醒"
        }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "piko_work_results"
        // 与传输进度的常驻通知（9527）错开
        const val FIRST_NOTIFICATION_ID = 20_000
    }
}
