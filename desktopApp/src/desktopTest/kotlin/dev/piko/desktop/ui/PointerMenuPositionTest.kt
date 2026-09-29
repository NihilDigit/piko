package dev.piko.desktop.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.piko.ui.components.pointerMenuPosition
import kotlin.test.Test
import kotlin.test.assertEquals

/** 右键菜单以指针为原点，放不下时朝反方向展开，而不是像下拉菜单那样整个翻到锚点上方。 */
class PointerMenuPositionTest {
    private val window = IntSize(1440, 900)
    private val menu = IntSize(280, 400)

    @Test
    fun `opens to the lower right of the pointer when it fits`() {
        assertEquals(IntOffset(300, 200), pointerMenuPosition(IntOffset(300, 200), menu, window))
    }

    @Test
    fun `near the bottom it opens upward with its bottom edge at the pointer`() {
        assertEquals(IntOffset(300, 800 - 400), pointerMenuPosition(IntOffset(300, 800), menu, window))
    }

    @Test
    fun `near the right edge it opens leftward with its right edge at the pointer`() {
        assertEquals(IntOffset(1300 - 280, 200), pointerMenuPosition(IntOffset(1300, 200), menu, window))
    }

    @Test
    fun `in the lower right corner it opens up and to the left`() {
        assertEquals(IntOffset(1400 - 280, 850 - 400), pointerMenuPosition(IntOffset(1400, 850), menu, window))
    }

    @Test
    fun `a menu that fits neither way hugs the window edge`() {
        val tall = IntSize(280, 700)
        // 上下各只有 450，向下放不下、向上也放不下：贴着窗口下沿，尽量完整露出
        assertEquals(IntOffset(300, 900 - 700), pointerMenuPosition(IntOffset(300, 450), tall, window))
        // 比窗口还高时贴上沿，菜单自己滚动
        assertEquals(IntOffset(300, 0), pointerMenuPosition(IntOffset(300, 450), IntSize(280, 1000), window))
    }
}
