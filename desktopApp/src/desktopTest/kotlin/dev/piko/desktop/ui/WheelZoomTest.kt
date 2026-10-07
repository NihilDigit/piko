package dev.piko.desktop.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.ui.components.zoomOnWheel
import dev.piko.ui.platform.LocalPikoPlatform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 主修饰键加滚轮缩放网盘的视图：截下的滚动不能再让里面的列表滚，否则每缩放一档列表还跳一截；
 * 触控板的零点几格攒满一格才走一档。不按修饰键时滚动照常交给列表。主修饰键在 Windows 上是 Ctrl。
 */
@OptIn(ExperimentalTestApi::class)
class WheelZoomTest {
    private val platform = DesktopPikoPlatform(DesktopSettingsStore(File.createTempFile("piko-settings", ".properties")))

    @Test
    fun `ctrl and the wheel zoom without scrolling the list`() = runComposeUiTest {
        val zooms = mutableListOf<Boolean>()
        val list = LazyListState()
        setContent {
            CompositionLocalProvider(LocalPikoPlatform provides platform) {
                LazyColumn(state = list, modifier = Modifier.size(300.dp).testTag("list").zoomOnWheel { zooms += it }) {
                    items(100) { Text("第 $it 项", Modifier.fillMaxWidth().height(40.dp)) }
                }
            }
        }
        val node = onNodeWithTag("list")
        node.performMultiModalInput { key { withKeyDown(Key.CtrlLeft) { mouse { scroll(-1f, ScrollWheel.Vertical) } } } }
        waitForIdle()
        node.performMultiModalInput { key { withKeyDown(Key.CtrlLeft) { mouse { repeat(3) { scroll(0.4f, ScrollWheel.Vertical) } } } } }
        waitForIdle()
        assertEquals(listOf(true, false), zooms)
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)

        node.performMouseInput { scroll(3f, ScrollWheel.Vertical) }
        waitForIdle()
        assertEquals(2, zooms.size)
        assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0)
    }
}
