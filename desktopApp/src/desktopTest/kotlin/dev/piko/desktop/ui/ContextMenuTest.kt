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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.rightClick
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
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
     * 「更多」原地换页，Esc 先回首页、再关掉菜单。Popup 自己也在返回事件上登记了关闭，
     * 子页的处理器要排在它前面，第一下 Esc 才不会把整个菜单关掉。
     */
    @Test
    fun `escape leaves the more page before it closes the menu`() = runComposeUiTest {
        // 测试环境里按键不经窗口的返回输入，Esc 到不了返回处理器；照窗口的做法挂一个输入，直接发返回
        val back = BackInput()
        setContent {
            val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(back)
                onDispose { dispatcher.removeInput(back) }
            }
            CompositionLocalProvider(LocalPikoPlatform provides platform) { PikoTheme {
                ContextMenuArea(actions = { sevenAndMore }) {
                    FileListItem(headline = "a.mkv", leading = { Icon(Icons.Outlined.Folder, null) }, onClick = {}, onMoreClick = {}, modifier = Modifier)
                }
            } }
        }
        onNodeWithText("a.mkv").performMouseInput { rightClick(center) }
        waitForIdle()
        onNodeWithText("归档").assertDoesNotExist()
        onNodeWithText("更多").performClick()
        waitForIdle()
        onNodeWithText("归档").assertExists()
        onNodeWithText("移动到").assertDoesNotExist()
        runOnIdle { back.press() }
        waitForIdle()
        onNodeWithText("移动到").assertExists()
        runOnIdle { back.press() }
        waitForIdle()
        onNodeWithText("移动到").assertDoesNotExist()
    }

    /**
     * 键盘走一遍：打开时焦点在列表第一行（不在图标行，图标得了焦点会弹提示盖住下一行），上键回到图标行，
     * 在「更多」上按右键进子页、焦点落在第一项操作上，左键回来、焦点回到「更多」。
     */
    @Test
    fun `arrow keys walk the icon row, the list and the more page`() = runComposeUiTest {
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
        repeat(3) { press(Key.DirectionDown) }
        onNodeWithText("更多").assertIsFocused()
        press(Key.DirectionRight)
        onNodeWithText("查找重复").assertIsFocused()
        press(Key.DirectionLeft)
        onNodeWithText("更多").assertIsFocused()
        repeat(3) { press(Key.DirectionUp) }
        onNodeWithText("移动到").assertIsFocused()
        press(Key.DirectionUp)
        onNodeWithContentDescription("下载").assertIsFocused()
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

    private class BackInput : NavigationEventInput() {
        fun press() = dispatchOnBackCompleted()
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
