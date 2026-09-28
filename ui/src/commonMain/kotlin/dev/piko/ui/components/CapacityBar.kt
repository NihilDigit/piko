package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.ui.LocalPikoServices

/**
 * 一条容量：标签、细条与读数，外框底栏用（网盘页与传输页）。实色是已用，浅色是 [incomingBytes]，
 * 即排队中的任务将要占的；两者加起来超过总量时整条与读数变红，一眼看出先撑满的是哪一边。具体数字在悬停提示里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapacityRow(label: String, usedBytes: Long, incomingBytes: Long, totalBytes: Long, readout: String, tooltip: String) {
    val colors = MaterialTheme.colorScheme
    val overflow = usedBytes + incomingBytes > totalBytes
    val usedColor = if (overflow) colors.error else colors.primary
    val usedFraction = (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val incomingFraction = (incomingBytes.toFloat() / totalBytes).coerceIn(0f, 1f - usedFraction)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            val track = colors.surfaceContainerHighest
            Box(
                Modifier
                    .width(CapacityBarWidth)
                    .height(6.dp)
                    .clip(CircleShape)
                    .drawBehind {
                        drawRect(track)
                        drawRect(usedColor, size = Size(size.width * usedFraction, size.height))
                        drawRect(
                            usedColor.copy(alpha = 0.4f),
                            topLeft = Offset(size.width * usedFraction, 0f),
                            size = Size(size.width * incomingFraction, size.height),
                        )
                    },
            )
            Text(
                readout,
                style = MaterialTheme.typography.labelMedium,
                color = if (overflow) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 104.dp),
            )
        }
    }
}

/** 网盘容量那一条。[incomingBytes] 是进行中的离线任务将要占的，网盘页没有这一项时为 0。 */
@Composable
fun CloudCapacityRow(incomingBytes: Long = 0L) {
    val driveRepository = LocalPikoServices.current.driveRepository
    val quota by driveRepository.quotaFlow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (quota == null) driveRepository.getQuota() }
    val cloud = quota?.quota?.takeIf { it.limitBytes > 0 } ?: return
    CapacityRow(
        label = "网盘",
        usedBytes = cloud.usageBytes,
        incomingBytes = incomingBytes,
        totalBytes = cloud.limitBytes,
        readout = "${cloud.usageBytes.toReadableSize()} / ${cloud.limitBytes.toReadableSize()}",
        tooltip = buildString {
            append("网盘已用 ${cloud.usageBytes.toReadableSize()}，共 ${cloud.limitBytes.toReadableSize()}")
            if (incomingBytes > 0) append("\n进行中的离线任务还将占用 ${incomingBytes.toReadableSize()}")
        },
    )
}

private val CapacityBarWidth = 160.dp
