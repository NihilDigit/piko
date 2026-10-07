package dev.piko.ui.components

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.piko.ui.WorkMeter

/**
 * 按 [WorkMeter] 画 M3 的线性进度条：确定进度带值与末端的停止点，不定进度用往复的样式，[WorkMeter.None] 什么也不画。
 * [label] 是读屏念的那句（「归档「电影」」），progress-indicators.md 要求进度条说明在做什么。
 */
@Composable
fun WorkMeterBar(meter: WorkMeter, label: String, modifier: Modifier = Modifier) {
    val labelled = modifier.semantics { contentDescription = label }
    when (meter) {
        is WorkMeter.Determinate -> LinearProgressIndicator(progress = { meter.fraction }, modifier = labelled)
        WorkMeter.Indeterminate -> LinearProgressIndicator(modifier = labelled)
        WorkMeter.None -> Unit
    }
}
