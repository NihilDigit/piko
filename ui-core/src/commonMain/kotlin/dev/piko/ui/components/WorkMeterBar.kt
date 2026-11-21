package dev.piko.ui.components

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.piko.ui.WorkMeter
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible

/**
 * 按 [WorkMeter] 画 M3 的线性进度条：确定进度带值与末端的停止点，不定进度用往复的样式，[WorkMeter.None] 什么也不画。
 * [label] 是读屏念的那句（「归档「电影」」），progress-indicators.md 要求进度条说明在做什么。
 *
 * 轨道默认使用 `surfaceContainerHighest` 以在浅色与深色任务卡片上保持清晰对比；支持按业务语义传入
 * `color`（下载 `primary`、上传 `secondary`、云端/服务端 `tertiary`），并在减少动画或窗口不可见时
 * 将不定进度条收敛为静态条以停止后台帧循环。
 */
@Composable
fun WorkMeterBar(
    meter: WorkMeter,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val labelled = modifier.semantics { contentDescription = label }
    val staticMode = LocalPikoMotion.current.reduced || !LocalWindowVisible.current
    when (meter) {
        is WorkMeter.Determinate -> LinearProgressIndicator(
            progress = { meter.fraction },
            modifier = labelled,
            color = color,
            trackColor = trackColor,
        )
        WorkMeter.Indeterminate -> if (staticMode) {
            LinearProgressIndicator(
                progress = { 0.35f },
                modifier = labelled,
                color = color,
                trackColor = trackColor,
            )
        } else {
            LinearProgressIndicator(
                modifier = labelled,
                color = color,
                trackColor = trackColor,
            )
        }
        WorkMeter.None -> Unit
    }
}
