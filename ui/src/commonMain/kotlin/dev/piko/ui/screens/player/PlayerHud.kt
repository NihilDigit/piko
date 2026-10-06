package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 音量、亮度与双击进退的读数：画面上方居中的一枚半透明胶囊，看得清又不挡画面中央。
 * 白字垫半透明黑底而不用主题色：底下是任意画面，主题色在亮画面上看不清，实心又挡内容。
 * 拖动进度不在这里，露出底栏的进度条；倍速、旋转这类一下子的状态在播放键上。
 */
@Composable
internal fun PlayerHud(indicator: CenterIndicator?, modifier: Modifier = Modifier) {
    // 淡出期间 indicator 已是 null，内容按最后一份画完，不淡出一个空胶囊
    val retained = remember { mutableStateOf<CenterIndicator?>(null) }
    if (indicator != null) SideEffect { retained.value = indicator }
    val shown = indicator ?: retained.value

    val style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    AnimatedVisibility(
        visible = indicator != null,
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        modifier = modifier,
    ) {
        val content = shown ?: return@AnimatedVisibility
        // 按模板定宽、数字靠右：音量从 9 拖到 10 时胶囊不跟着伸缩
        val textWidth = with(density) {
            val measured = textMeasurer.measure(content.text, style).size.width
            val reserved = content.textTemplate?.let { textMeasurer.measure(it, style).size.width } ?: 0
            maxOf(measured, reserved).toDp()
        }
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = HUD_SCRIM_ALPHA), CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = content.description
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(content.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            content.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.width(HUD_BAR_WIDTH),
                )
            }
            Text(
                text = content.text,
                style = style,
                color = Color.White,
                textAlign = TextAlign.End,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.width(textWidth),
            )
        }
    }
}

private const val HUD_SCRIM_ALPHA = 0.45f
private val HUD_BAR_WIDTH = 96.dp
