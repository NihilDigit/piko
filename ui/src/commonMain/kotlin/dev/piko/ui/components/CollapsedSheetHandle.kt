package dev.piko.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 面板收起后留在屏幕底部的把手，外观是一块只露出顶边的面板。点按或上拉重新展开，
 * 右侧的关闭才真正结束这次会话。添加链接与查找重复共用。
 */
@Composable
fun CollapsedSheetHandle(
    title: String,
    status: String?,
    closeLabel: String,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeEnabled: Boolean = true,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, dragAmount -> if (dragAmount < -8f) onExpand() }
            },
        onClick = onExpand,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        // 与底栏同色，看上去是从底栏里探出的一截。用面板本身的 surfaceContainerLow 时，
        // 它紧贴着更亮的 surfaceContainer 底栏，深色主题下像一条黑带；阴影也会在两者的
        // 接缝上画出一道线，所以不加
        color = NavigationBarDefaults.containerColor,
    ) {
        // 宽窗口用侧边导航栏，把手直接落在屏幕底边，内容要让出手势横条；底部有导航栏时 inset 已被它占用，这里为零
        Column(modifier = Modifier.navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BottomSheetDefaults.DragHandle(modifier = Modifier.padding(top = 4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onClose, enabled = closeEnabled) {
                    Icon(Icons.Outlined.Close, contentDescription = closeLabel)
                }
            }
        }
    }
}
