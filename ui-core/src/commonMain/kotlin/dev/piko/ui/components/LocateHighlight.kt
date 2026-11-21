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
import kotlinx.coroutines.launch

/**
 * 被定位到的条目：一圈主色描边，出现时闪两下引开视线，之后淡淡留着，撤掉高亮时淡出。
 *
 * 闪的时候整块盖一层主色，不只是描边变亮：桌面上 3dp 只有三个像素，绕着一条又宽又扁的列表行，
 * 只靠描边明暗几乎看不出在闪（2026-10-06）。盖色压在内容上面而不是垫在底下，列表行自己的底色不透明，垫着就被遮住了。
 *
 * 不写字。原先是一枚「刚存入」角标，可定位早已不只用于秒传：星标、播放历史、传输页、随机片段都会
 * 跳到网盘里的某一项，写哪个词都有说错的时候，而要表达的只是「就是这一个」。
 * 与选中态分得开：选中是不动的描边加复选框。
 */
@Composable
fun Modifier.locateHighlight(active: Boolean, shape: Shape): Modifier {
    val alpha = remember { Animatable(0f) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            repeat(PULSES) {
                launch { flash.animateTo(1f, tween(PULSE_UP_MS)); flash.animateTo(0f, tween(PULSE_DOWN_MS)) }
                alpha.animateTo(1f, tween(PULSE_UP_MS))
                alpha.animateTo(PULSE_LOW, tween(PULSE_DOWN_MS))
            }
            alpha.animateTo(RESTING, tween(PULSE_UP_MS))
        } else {
            flash.snapTo(0f)
            alpha.animateTo(0f, tween(FADE_OUT_MS))
        }
    }
    val color = MaterialTheme.colorScheme.primary
    return drawWithContent {
        drawContent()
        val fill = flash.value * FLASH_ALPHA
        if (fill > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), color, alpha = fill)
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

// 盖在文字上面，深色主题里文字会在最亮的那一刻融掉。只亮零点几秒，那时没人在读字，要的就是一眼看出哪一行在闪；
// 0.28 时桌面上仍看不出来
private const val FLASH_ALPHA = 0.5f
private val RING_WIDTH = 3.dp
