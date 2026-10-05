package dev.piko.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.rememberLoadingVisible

/**
 * 「在网盘中显示」向服务端逐级查路径时盖住整个窗口的一层。查一级一次请求，慢的时候要好几秒，
 * 跳转在查完时才落下，这期间人在原处做的事会被它盖掉，所以点击与按键一律不往下传；返回（Esc）与「取消」放弃这次跳转。
 * 头 200ms 只拦输入、不画：路径多半查得快，一闪而过的遮罩比不画更扰人。
 */
@Composable
fun LocatingOverlay(onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    // 焦点拿过来，按键才落在这一层被吞掉，而不是落到底下的列表上
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler(onBack = onCancel)
    val visible = rememberLoadingVisible()
    val scrim = if (visible) Modifier.background(MaterialTheme.colorScheme.scrim.copy(alpha = ScrimAlpha)) else Modifier
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .then(scrim)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .focusRequester(focus)
            .focusable()
            // 按键一律吞掉，桌面端的 Esc 未必还走得到 BackHandler，这里直接接。取消只是丢掉一次查询，接到两次无妨
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) onCancel()
                true
            },
    ) {
        if (visible) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    InlineLoadingIndicator()
                    Text("正在定位", style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
        }
    }
}

/** M3 模态遮罩的 32%，与浮起的侧边栏相同。 */
private const val ScrimAlpha = 0.32f
