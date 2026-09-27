package dev.piko.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 被定位到的条目：一圈主色描边，出现时闪两下引开视线，之后淡淡留着，撤掉高亮时淡出。
 *
 * 不写字。原先是一枚「刚存入」角标，可定位早已不只用于秒传：星标、播放历史、传输页、随机片段都会
 * 跳到网盘里的某一项，写哪个词都有说错的时候，而要表达的只是「就是这一个」。
 * 与选中态分得开：选中是不动的描边加复选框。
 */
@Composable
fun Modifier.locateHighlight(active: Boolean, shape: Shape): Modifier {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            repeat(PULSES) {
                alpha.animateTo(1f, tween(PULSE_UP_MS))
                alpha.animateTo(PULSE_LOW, tween(PULSE_DOWN_MS))
            }
            alpha.animateTo(RESTING, tween(PULSE_UP_MS))
        } else {
            alpha.animateTo(0f, tween(FADE_OUT_MS))
        }
    }
    val color = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        val strength = alpha.value
        if (strength <= 0f) return@drawWithContent
        val width = RING_WIDTH.toPx()
        // 描边画在内侧：画在外侧会被网格的间距或列表的边缘裁掉一半
        val inset = width / 2
        val outline = shape.createOutline(size.copy(width = size.width - width, height = size.height - width), layoutDirection, this)
        drawContext.transform.translate(inset, inset)
        drawOutline(outline, color, alpha = strength, style = Stroke(width))
        drawContext.transform.translate(-inset, -inset)
    }
}

private const val PULSES = 2
private const val PULSE_UP_MS = 220
private const val PULSE_DOWN_MS = 320
private const val PULSE_LOW = 0.25f
private const val RESTING = 0.7f
private const val FADE_OUT_MS = 500
private val RING_WIDTH = 3.dp
