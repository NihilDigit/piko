package dev.piko.ui.workbench

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

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
import dev.piko.shared.presentation.needsAttention
import dev.piko.shared.state.InstantSaveExecution
import dev.piko.shared.data.DriveChangeJournal
import dev.piko.ui.LocalPikoServices
import kotlin.math.roundToInt
import io.github.nihildigit.pikpak.TaskPhase
import kotlinx.coroutines.delay

internal class TransferActivity(val downloadSpeed: Long, val uploadSpeed: Long, val count: Int, val attentionCount: Int = 0) {
    val hasReadout: Boolean get() = downloadSpeed > 0 || uploadSpeed > 0 || count > 0 || attentionCount > 0
}

internal fun untrackedImportCount(executions: List<InstantSaveExecution>): Int =
    executions.count { !it.trackedInstantAttempt && !it.trackedOffline }

@Composable
internal fun rememberTransferActivity(
    pollCloud: Boolean = true,
): TransferActivity {
    val services = LocalPikoServices.current
    val client by services.clientManager.currentClient.collectAsStateWithLifecycle()
    if (client == null) return TransferActivity(0, 0, 0)
    val state = services.taskCenter
    androidx.compose.runtime.LaunchedEffect(state, pollCloud) {
        if (pollCloud) state.whileVisible()
    }
    val summary = state.activity
    return TransferActivity(summary.downloadSpeed, summary.uploadSpeed, summary.active.size, summary.needsAttention.size)

}

@Composable
internal fun SidebarTransferReadout(activity: TransferActivity, checked: Boolean, compact: Boolean = false) {
    val preferences = LocalPikoServices.current.preferences
    val snail by preferences.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    val color = if (snail.enabled && !checked) MaterialTheme.colorScheme.tertiary else LocalContentColor.current
    val speed = maxOf(activity.downloadSpeed, activity.uploadSpeed)
    val text = when {
        activity.attentionCount > 0 -> tr("${activity.attentionCount} 项需要处理", "${activity.attentionCount} need attention", "${activity.attentionCount} 件の対応が必要", "${activity.attentionCount}개 조치 필요", "${activity.attentionCount} 項需要處理")
        speed > 0 -> "${if (activity.downloadSpeed >= activity.uploadSpeed) "↓" else "↑"} ${compactRate(speed)}"
        else -> tr("${activity.count} 项", "${enCount(activity.count, "item", "items")}", "${activity.count} 項目", "${activity.count}개 항목", "${activity.count} 項")
    }
    Text(text, color = color, maxLines = 1, style = (if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge).copy(fontFeatureSettings = "tnum"))
}

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
