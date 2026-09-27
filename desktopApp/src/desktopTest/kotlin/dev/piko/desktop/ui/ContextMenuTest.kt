package dev.piko.desktop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.ui.components.ContextMenuArea
import dev.piko.ui.components.FileListItem
import dev.piko.ui.components.SheetAction
import dev.piko.ui.components.selectionClicks
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.theme.PikoTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 鼠标点选的两条约定：右键只弹菜单、不同时点开条目；按住主修饰键或 Shift 点选只选中、不点开。
 * 两者都靠在 Initial 阶段截下按下，条目自己的单击看不到它，这条路只有真实的指针事件序列走得到。
 * 主修饰键在 Windows 上是 Ctrl；桌面测试只在 Windows 上跑。
 */
@OptIn(ExperimentalTestApi::class)
class ContextMenuTest {
    private val platform = DesktopPikoPlatform(DesktopSettingsStore(File.createTempFile("piko-settings", ".properties")))

    @Test
    fun `a right click opens the menu without opening the item`() = runComposeUiTest {
        var clicks by mutableIntStateOf(0)
        setContent {
            CompositionLocalProvider(LocalPikoPlatform provides platform) { PikoTheme {
                ContextMenuArea(actions = {
                    listOf(
                        SheetAction(Icons.Outlined.Download, "下载", {}),
                        SheetAction(Icons.Outlined.Delete, "删除", {}, destructive = true),
                    )
                }) {
                    FileListItem(
                        headline = "a.mkv",
                        leading = { Icon(Icons.Outlined.Folder, null) },
                        onClick = { clicks++ },
                        onMoreClick = {},
                        modifier = Modifier,
                    )
                }
            } }
        }
        onNodeWithText("a.mkv").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("下载").assertExists()
        assertEquals(0, clicks)
    }

    @Test
    fun `ctrl and shift clicks select without opening the item`() = runComposeUiTest {
        var clicks by mutableIntStateOf(0)
        var toggles by mutableIntStateOf(0)
        var extends by mutableIntStateOf(0)
        setContent {
            CompositionLocalProvider(LocalPikoPlatform provides platform) { PikoTheme {
                FileListItem(
                    headline = "a.mkv",
                    leading = { Icon(Icons.Outlined.Folder, null) },
                    onClick = { clicks++ },
                    onMoreClick = {},
                    modifier = Modifier.selectionClicks(onToggle = { toggles++ }, onExtend = { extends++ }),
                )
            } }
        }
        val node = onNodeWithText("a.mkv")
        node.performMultiModalInput { key { withKeyDown(Key.CtrlLeft) { mouse { click(center) } } } }
        waitForIdle()
        node.performMultiModalInput { key { withKeyDown(Key.ShiftLeft) { mouse { click(center) } } } }
        waitForIdle()
        assertEquals(1, toggles)
        assertEquals(1, extends)
        assertEquals(0, clicks)
        node.performMouseInput { click(center) }
        waitForIdle()
        assertEquals(1, clicks)
    }
}
