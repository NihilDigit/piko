package dev.piko.ui.workbench

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.auth.SnailMode
import dev.piko.download.DownloadStatus
import dev.piko.shared.upload.UploadStatus
import dev.piko.ui.LocalPikoServices
import kotlin.math.roundToInt
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.delay

/** 眼下进行中的传输汇总：各方向的总速度与项数。云端离线任务只算项数，没有速度。 */
internal class TransferActivity(val downloadSpeed: Long, val uploadSpeed: Long, val count: Int) {
    /** 有没有可写在「传输」按钮上的读数：有速度，或者有任务在跑。 */
    val hasReadout: Boolean get() = downloadSpeed > 0 || uploadSpeed > 0 || count > 0
}

/**
 * 眼下进行中的传输：本机的下载与上传、解压与归档，加上云端的离线任务。离线任务不像下载那样有进程级的状态，
 * 要每 [CLOUD_POLL_MS] 取一次第一页，[pollCloud] 为假时不取、不计：手机上只为导航栏的一个数字常驻轮询不值得。
 * 暂停与失败的不算进行中。
 */
@Composable
internal fun rememberTransferActivity(
    pollCloud: Boolean = true,
    /** 解压与归档算不算。桌面不算：它们在右下角的浮动卡片里，不列进传输页，按钮上的数也就不该含它们。 */
    countsServerWork: Boolean = true,
): TransferActivity {
    val services = LocalPikoServices.current
    val downloads by services.downloadManager.tasks.collectAsStateWithLifecycle()
    val uploads by services.uploadManager.tasks.collectAsStateWithLifecycle()
    val cloud by produceState(services.taskRepository.cachedTasks().orEmpty(), services, pollCloud) {
        while (pollCloud) {
            services.taskRepository.getTasks().onSuccess { value = it.tasks }
            delay(CLOUD_POLL_MS)
        }
    }
    val activeDownloads = downloads.values.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING }
    val activeUploads = uploads.values.filter { it.status.isActive }
    val activeCloud = if (pollCloud) cloud.count { it.phase == TaskPhase.RUNNING || it.phase == TaskPhase.PENDING } else 0
    // 待输密码的压缩包也算：它停着等人，正该引人去看
    val serverWork = if (!countsServerWork) {
        0
    } else {
        services.archiveExtractSession.jobs.size + (if (services.folderVaultSession.isRunning) 1 else 0)
    }
    return TransferActivity(
        downloadSpeed = activeDownloads.sumOf { it.speedBytesPerSec },
        uploadSpeed = activeUploads.sumOf { it.speedBytesPerSec },
        // 文件夹下载一批算一项，与传输页一致；按文件数的话一个文件夹就是上千项
        count = activeDownloads.distinctBy { it.batch?.id ?: it.taskId }.size + activeUploads.size + activeCloud + serverWork,
    )
}

/**
 * 侧边栏「传输」按钮上代替「传输」二字的读数：上行与下行里快的那一边，「↓ 6.2 MB/s」；没有速度但有任务在排队
 * 或在云端跑时写项数。蜗牛模式开着时用强调色，一眼看得出这个速度是被压着的；按钮选中时底色已是主色，
 * 强调色压在上面读不清，沿用按钮自己的文字色。
 *
 * 取代了原来窗口底部的状态栏与侧边栏的小卡：人找传输的地方就在这个按钮，读数放在它身上，不另占一块。
 */
@Composable
internal fun SidebarTransferReadout(activity: TransferActivity, checked: Boolean) {
    val preferences = LocalPikoServices.current.preferences
    val snail by preferences.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    val color = if (snail.enabled && !checked) MaterialTheme.colorScheme.tertiary else LocalContentColor.current
    val speed = maxOf(activity.downloadSpeed, activity.uploadSpeed)
    val text = when {
        speed > 0 -> "${if (activity.downloadSpeed >= activity.uploadSpeed) "↓" else "↑"} ${compactRate(speed)}"
        else -> "${activity.count} 项"
    }
    Text(text, color = color, maxLines = 1, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
}

/**
 * 按钮里的速度，最多三位数字：「1022.4 KB/s」在侧边栏半个按钮里放不下。满 1000 就进一级单位，
 * 不等到 1024，否则「1022 KB/s」这样的四位数照样出现；十以内留一位小数，不然 1 到 2 MB/s 之间都显示成 1。
 */
private fun compactRate(bytesPerSecond: Long): String {
    var value = bytesPerSecond.toDouble()
    var unit = 0
    while (value >= 1000 && unit < RATE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    val number = if (value < 10 && unit > 0) {
        val tenths = (value * 10).roundToInt()
        "${tenths / 10}.${tenths % 10}"
    } else {
        value.roundToInt().toString()
    }
    return "$number ${RATE_UNITS[unit]}/s"
}

private val RATE_UNITS = listOf("B", "KB", "MB", "GB")

private const val CLOUD_POLL_MS = 15_000L
