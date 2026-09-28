package dev.piko.ui.screens.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.state.TransfersState
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.CapacityRow
import dev.piko.ui.components.CloudCapacityRow
import dev.piko.ui.components.SnailModeToggle
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.theme.FrameBottomRowHeight
import dev.piko.ui.platform.DiskSpace
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 传输页的底栏，照 FDM：左边是上下行总速度与蜗牛模式，右边是排队中的下载还要多久，以及网盘与本机两条容量。
 * 容量条的实色是已用，浅色是排队中的离线任务或下载将要占的，放不下时整条变红，一眼看出先撑满的是哪一边。
 *
 * compact 只有速度与蜗牛模式：速度靠左，开关贴右。手机宽三百多 dp 排不下剩余时间，它在各行里写着，底栏不再重复。
 *
 * 只在传输页有：窗口底部常驻一栏时，没有传输的大部分时间里它只显示两个 0 B/s。
 */
@Composable
internal fun TransfersFooter(state: TransfersState, sidePadding: Dp, compact: Boolean, wide: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 导航栏在下面时它已让开系统导航条；平板上没有导航栏（侧边栏形态）时要自己让，否则底栏压在手势条下
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .height(FrameBottomRowHeight)
            .padding(start = sidePadding + 12.dp, end = sidePadding + if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 上下行是一对读数，挨得比后面的蜗牛开关近。compact 里这一组带权重：带权重的最后量，
        // 开关先拿够自己的宽度，窄屏上被压缩的是速度后面的空白，不是开关上的字
        Row(
            modifier = if (compact) Modifier.weight(1f) else Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Rate(Icons.Outlined.ArrowDownward, "下行", state.downloadSpeed)
            Rate(Icons.Outlined.ArrowUpward, "上行", state.uploadSpeed)
        }
        SnailModeToggle()
        if (compact) return@Row
        Spacer(Modifier.weight(1f))
        state.downloadEtaSeconds?.let { seconds ->
            Text(
                "下载剩余${formatRemaining(seconds)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // 两条容量要一定的宽度才读得出，窄窗口只留速度与剩余时间
        if (wide) CapacityBars(state)
    }
}

@Composable
private fun CapacityBars(state: TransfersState) {
    val services = LocalPikoServices.current
    val platform = LocalPikoPlatform.current
    val downloadDir by services.preferences.downloadDirPathFlow.collectAsStateWithLifecycle(initialValue = "")
    // 磁盘剩余随下载在变，隔几秒查一次。一次查询只是一个系统调用，但仍放到后台线程，免得网络盘卡住界面
    val disk by produceState<DiskSpace?>(initialValue = null, downloadDir) {
        while (true) {
            value = withContext(Dispatchers.Default) { platform.downloadLocation.diskSpace(downloadDir) }
            delay(DISK_POLL_MS)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CloudCapacityRow(incomingBytes = state.pendingCloudBytes)
        disk?.let { space ->
            CapacityRow(
                label = "本机",
                usedBytes = space.totalBytes - space.freeBytes,
                incomingBytes = state.pendingDownloadBytes,
                totalBytes = space.totalBytes,
                readout = "剩 ${space.freeBytes.toReadableSize()}",
                tooltip = buildString {
                    append("下载位置所在磁盘剩余 ${space.freeBytes.toReadableSize()}，共 ${space.totalBytes.toReadableSize()}")
                    if (state.pendingDownloadBytes > 0) append("\n未下完的任务还需 ${state.pendingDownloadBytes.toReadableSize()}")
                },
            )
        }
    }
}

/**
 * 一项速度，是读数不是按钮：箭头不垫底色（圆底加图标是图标按钮的样子，看着能点），紧挨着数字；
 * 有速度时箭头用主色、数字用正文色，停着时整项灰下去。数字大一号、加粗、用等宽数字，单位小一号跟在后面。
 * 整项定宽而不是只给数字定宽：数字一栏右对齐的话，停着时「0」离箭头一大截。
 */
@Composable
private fun Rate(icon: ImageVector, description: String, bytesPerSecond: Long) {
    val colors = MaterialTheme.colorScheme
    val active = bytesPerSecond > 0
    val readable = bytesPerSecond.toReadableSize()
    val number = readable.substringBeforeLast(' ')
    val unit = readable.substringAfterLast(' ')
    Row(modifier = Modifier.widthIn(min = RateMinWidth), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            number,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = if (active) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            " $unit/s",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

// 放得下常见的「↓ 12.3 MB/s」，速度跳动时后面的东西不跟着晃。按最长的「999.9 MB/s」给的话，
// 停着时两个「0 B/s」之间空出一大截，读着像两件不相干的事
private val RateMinWidth = 92.dp

/** 「约 12 分钟」「约 1 小时 20 分」；不到一分钟的不写秒数，底栏的读数不必跳那么快。 */
private fun formatRemaining(seconds: Long): String = when {
    seconds < 60 -> "不到 1 分钟"
    seconds < 3600 -> "约 ${seconds / 60} 分钟"
    else -> {
        val minutes = (seconds % 3600) / 60
        if (minutes == 0L) "约 ${seconds / 3600} 小时" else "约 ${seconds / 3600} 小时 $minutes 分"
    }
}

private const val DISK_POLL_MS = 5_000L
