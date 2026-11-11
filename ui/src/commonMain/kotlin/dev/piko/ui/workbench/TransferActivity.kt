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
import dev.piko.ui.LocalPikoServices
import kotlin.math.roundToInt

internal class TransferActivity(val downloadSpeed: Long, val uploadSpeed: Long, val count: Int, val attentionCount: Int = 0) {
    val maxSpeed: Long get() = maxOf(downloadSpeed, uploadSpeed)
    val hasReadout: Boolean get() = downloadSpeed > 0 || uploadSpeed > 0 || count > 0 || attentionCount > 0

    fun summaryText(): String = when {
        attentionCount > 0 -> tr("$attentionCount 项需要处理", "$attentionCount need attention", "$attentionCount 件の対応が必要", "${attentionCount}개 조치 필요", "$attentionCount 項需要處理")
        maxSpeed > 0 -> "${if (downloadSpeed >= uploadSpeed) "↓" else "↑"} ${compactRate(maxSpeed)}"
        else -> tr("$count 项", "${enCount(count, "item", "items")}", "$count 項目", "${count}개 항목", "$count 項")
    }
}

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
internal fun SidebarTransferReadout(activity: TransferActivity, checked: Boolean, compact: Boolean = false, railCompact: Boolean = false) {
    val preferences = LocalPikoServices.current.preferences
    val snail by preferences.snailModeFlow.collectAsStateWithLifecycle(initialValue = SnailMode())
    val color = if (snail.enabled && !checked) MaterialTheme.colorScheme.tertiary else LocalContentColor.current
    val text = if (railCompact) {
        compactRailRate(activity.maxSpeed)
    } else {
        activity.summaryText()
    }
    Text(
        text,
        color = color,
        maxLines = 1,
        softWrap = false,
        style = (if (compact || railCompact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge).copy(fontFeatureSettings = "tnum"),
    )
}

private fun compactRailRate(bytesPerSecond: Long): String {
    var value = bytesPerSecond.toDouble()
    var unit = 0
    while (value >= 1000 && unit < RAIL_RATE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    val number = if (value < 10 && unit > 0) {
        val tenths = (value * 10).roundToInt()
        "${tenths / 10}.${tenths % 10}"
    } else {
        value.roundToInt().toString()
    }
    return "$number${RAIL_RATE_UNITS[unit]}"
}

private val RAIL_RATE_UNITS = listOf("B/s", "K/s", "M/s", "G/s")

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
