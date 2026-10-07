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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.ui.components.ActionTier
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

    /**
     * 键盘走一遍：打开时焦点在列表第一行（不在图标行，图标得了焦点会弹提示盖住下一行），下键一路走到
     * 面板里收进「更多」的项，上键回到图标行。
     */
    @Test
    fun `arrow keys walk the icon row and the whole list`() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalPikoPlatform provides platform) { PikoTheme {
                ContextMenuArea(actions = { sevenAndMore }) {
                    FileListItem(headline = "a.mkv", leading = { Icon(Icons.Outlined.Folder, null) }, onClick = {}, onMoreClick = {}, modifier = Modifier)
                }
            } }
        }
        // 菜单是另一层，按键要发给那一层里有焦点的节点；条目所在的主层自己也留着焦点
        fun press(key: Key) {
            onNode(isFocused() and !hasText("a.mkv")).performKeyInput { pressKey(key) }
            waitForIdle()
        }
        onNodeWithText("a.mkv").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("移动到").assertIsFocused()
        repeat(5) { press(Key.DirectionDown) }
        onNodeWithText("隐藏预览").assertIsFocused()
        repeat(5) { press(Key.DirectionUp) }
        onNodeWithText("移动到").assertIsFocused()
        press(Key.DirectionUp)
        onNodeWithContentDescription("下载").assertIsFocused()
    }

    /**
     * 桌面上按住的三条约定：鼠标左键按住不进多选；触屏按住、抬起后弹出与右键相同的菜单，条目不被点开；
     * 嵌在网格空白处那一层里时只弹条目的菜单。菜单里的「选择」进多选。
     */
    @Test
    fun `holding on the desktop opens the menu instead of selecting`() = runComposeUiTest {
        var clicks by mutableIntStateOf(0)
        var longClicks by mutableIntStateOf(0)
        var selects by mutableIntStateOf(0)
        setContent {
            CompositionLocalProvider(LocalPikoPlatform provides platform) { PikoTheme {
                ContextMenuArea(actions = { listOf(SheetAction(Icons.Outlined.Folder, "新建文件夹", {})) }) {
                    ContextMenuArea(
                        actions = { listOf(SheetAction(Icons.Outlined.Download, "下载", {})) },
                        onSelect = { selects++ },
                    ) {
                        FileListItem(
                            headline = "a.mkv",
                            leading = { Icon(Icons.Outlined.Folder, null) },
                            onClick = { clicks++ },
                            onMoreClick = {},
                            onLongClick = { longClicks++ },
                        )
                    }
                }
            } }
        }
        val item = onNodeWithText("a.mkv")
        item.performMouseInput {
            press()
            advanceEventTime(2_000)
            release()
        }
        waitForIdle()
        assertEquals(0, longClicks)
        onNodeWithText("下载").assertDoesNotExist()

        val clicksBefore = clicks
        item.performTouchInput { longClick(center) }
        waitForIdle()
        onNodeWithText("下载").assertExists()
        onNodeWithText("新建文件夹").assertDoesNotExist()
        assertEquals(0, longClicks)
        assertEquals(clicksBefore, clicks)

        onNodeWithText("选择").performClick()
        waitForIdle()
        assertEquals(1, selects)
        onNodeWithText("下载").assertDoesNotExist()
    }

    private val sevenAndMore = listOf(
        SheetAction(Icons.Outlined.Download, "下载", {}, tier = ActionTier.Quick),
        SheetAction(Icons.Outlined.Download, "移动到", {}),
        SheetAction(Icons.Outlined.Download, "复制到", {}),
        SheetAction(Icons.Outlined.Download, "重命名", {}),
        SheetAction(Icons.Outlined.Download, "查找重复", {}, tier = ActionTier.More),
        SheetAction(Icons.Outlined.Download, "归档", {}, tier = ActionTier.More),
        SheetAction(Icons.Outlined.Download, "隐藏预览", {}, tier = ActionTier.More),
        SheetAction(Icons.Outlined.Delete, "删除", {}, destructive = true),
    )

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
