package dev.piko.ui.screens.transfers

import dev.piko.shared.i18n.enCount

import dev.piko.shared.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.piko.shared.state.TransfersState
import dev.piko.ui.components.toReadableSize

/** 上下行速度，一对读数，挨得比后面的蜗牛开关近。与蜗牛模式组合在 TaskRateBar。 */
@Composable
internal fun Rates(state: TransfersState, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Rate(Icons.Outlined.ArrowDownward, tr("下行", "Download", "下り", "다운로드", "下行"), state.downloadSpeed, compact)
        Rate(Icons.Outlined.ArrowUpward, tr("上行", "Upload", "上り", "업로드", "上行"), state.uploadSpeed, compact)
    }
}
/** 排队中的下载还要多久，没有在下的不显示。 */
@Composable
internal fun DownloadEta(state: TransfersState) {
    val seconds = state.downloadEtaSeconds ?: return
    Text(
        tr("下载剩余${formatRemaining(seconds)}", "Download: ${formatRemaining(seconds)} remaining", "ダウンロード残り ${formatRemaining(seconds)}", "다운로드 남은 시간 ${formatRemaining(seconds)}", "下載剩餘${formatRemaining(seconds)}"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/**
 * 一项速度，是读数不是按钮：箭头不垫底色（圆底加图标是图标按钮的样子，看着能点），紧挨着数字；
 * 有速度时箭头用主色、数字用正文色，停着时整项灰下去。数字大一号、加粗、用等宽数字，单位小一号跟在后面。
 * 整项定宽而不是只给数字定宽：数字一栏右对齐的话，停着时「0」离箭头一大截。
 */
@Composable
private fun Rate(icon: ImageVector, description: String, bytesPerSecond: Long, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    val active = bytesPerSecond > 0
    val readable = bytesPerSecond.toReadableSize()
    val number = readable.substringBeforeLast(' ')
    val unit = readable.substringAfterLast(' ')
    Row(modifier = Modifier.widthIn(min = if (compact) 72.dp else RateMinWidth), verticalAlignment = Alignment.CenterVertically) {
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
    seconds < 60 -> tr("不到 1 分钟", "Less than a minute", "1分未満", "1분 미만", "不到 1 分鐘")
    seconds < 3600 -> tr("约 ${seconds / 60} 分钟", "About ${enCount(seconds / 60, "minute", "minutes")}", "約 ${seconds / 60} 分", "약 ${seconds / 60}분", "約 ${seconds / 60} 分鐘")
    else -> {
        val minutes = (seconds % 3600) / 60
        if (minutes == 0L) tr("约 ${seconds / 3600} 小时", "About ${enCount(seconds / 3600, "hour", "hours")}", "約 ${seconds / 3600} 時間", "약 ${seconds / 3600}시간", "約 ${seconds / 3600} 小時") else tr("约 ${seconds / 3600} 小时 $minutes 分", "About ${seconds / 3600} hr $minutes min", "約 ${seconds / 3600} 時間 $minutes 分", "약 ${seconds / 3600}시간 ${minutes}분", "約 ${seconds / 3600} 小時 $minutes 分")
    }
}
