package dev.piko.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import dev.piko.desktop.DesktopPikoPlatform
import dev.piko.desktop.DesktopSettingsStore
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.instant.LinkInputField
import dev.piko.ui.theme.PikoTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LinkInputTest {
    private val platform = DesktopPikoPlatform(DesktopSettingsStore(File.createTempFile("piko-settings", ".properties")))

    @Test
    fun `collapsing saving restoring and refocusing resolved link input never crashes scroller orientation`() = runComposeUiTest {
        val magnet1 = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=" + "long_segment_".repeat(12)
        val magnet2 = "magnet:?xt=urn:btih:fedcba9876543210fedcba9876543210fedcba98&dn=" + "next_batch_".repeat(10)
        var text by mutableStateOf(magnet1)
        var hasResolution by mutableStateOf(false)
        var sheetOpen by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalPikoPlatform provides platform) {
                PikoTheme {
                    val holder = rememberSaveableStateHolder()
                    Column {
                        if (sheetOpen) {
                            holder.SaveableStateProvider("add-link-draft") {
                                LinkInputField(
                                    value = text,
                                    hasResolution = hasResolution,
                                    enabled = true,
                                    onValueChange = { text = it },
                                    onPaste = {},
                                )
                            }
                        }
                        TextButton(onClick = {}) { Text("移开焦点") }
                    }
                }
            }
        }
        waitForIdle()

        // 1. 首次解析完成：3 行态折叠为 1 行态
        hasResolution = true
        waitForIdle()

        // 2. 收起并重新打开添加链接面板（触发 SaveableStateHolder 保存与恢复 TextFieldScrollerPosition）
        sheetOpen = false
        waitForIdle()
        sheetOpen = true
        waitForIdle()

        // 3. 在恢复出的 1 行折叠态上点击聚焦展开为 3 行，并输入新链接（精确还原 23:57:16 崩溃现场）
        onNodeWithText(magnet1, substring = true).performClick()
        waitForIdle()
        onNodeWithText(magnet1, substring = true).assertIsFocused()
        onNodeWithText(magnet1, substring = true).performTextReplacement(magnet2)
        hasResolution = false
        waitForIdle()

        // 4. 第二条链接解析完成并移开焦点，再次折叠回 1 行，随后二次点击聚焦
        hasResolution = true
        onNodeWithText("移开焦点").performClick()
        waitForIdle()
        onNodeWithText(magnet2, substring = true).performClick()
        waitForIdle()
        assertTrue(text == magnet2)
    }
}
