package dev.piko.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 最近一次输入是不是键盘导航。Compose 自己的 InputModeManager 在桌面上被指针事件切到触摸后，
 * 代码里 moveFocus 挪焦点不会把它切回来，方向键走了几项也画不出描边，所以自己记：
 * 根上看到方向键或 Tab 记为键盘，看到指针按下记为鼠标，见 [trackInputModality]。
 */
private object InputModality {
    var keyboard by mutableStateOf(true)
}

/** 挂在界面的根上。 */
fun Modifier.trackInputModality(): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key in NavigationKeys) InputModality.keyboard = true
    false
}.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) InputModality.keyboard = false
        }
    }
}

private val NavigationKeys = setOf(Key.Tab, Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/**
 * 键盘焦点的描边，M3 的 focus indicator：焦点在这一项（或它里面）且是用键盘走过来的时候，
 * 沿边描一圈 3dp 的 secondary。列表项自带的焦点状态层只是一层很淡的底色，用方向键在几十项里
 * 走的时候看不出停在哪一项。鼠标点过去得到的焦点不描，那时指针就在它上面。
 */
@Composable
fun Modifier.keyboardFocusRing(shape: Shape): Modifier {
    var focused by remember { mutableStateOf(false) }
    val keyboard = InputModality.keyboard
    val color = MaterialTheme.colorScheme.secondary
    return onFocusChanged { focused = it.hasFocus }.drawWithContent {
        drawContent()
        if (focused && keyboard) {
            // 描在里侧：列表项紧挨着，下一项自带底色，画在外面的那一半会被它盖住
            val stroke = RingWidth.toPx()
            val outline = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
            translate(stroke / 2, stroke / 2) {
                drawOutline(outline, color, style = Stroke(stroke))
            }
        }
    }
}

private val RingWidth = 3.dp
