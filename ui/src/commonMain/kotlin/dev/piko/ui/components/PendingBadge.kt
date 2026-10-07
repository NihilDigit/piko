package dev.piko.ui.components

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.Composable

/**
 * 入口图标右上角的小圆点（M3 small badge）：这件事收着、还没做完，点这个入口就接着做。
 * 挂起的信息流（命令栏「信息流」按钮）与收起的添加链接（命令栏的主操作、FAB 菜单里那一项）共用这一种样子。
 * 只挂在图标上：M3 的 badge 锚在图标的右上角，不挂在整个按钮或 FAB 上。
 */
@Composable
fun PendingBadge(shown: Boolean, icon: @Composable () -> Unit) {
    BadgedBox(badge = { if (shown) Badge() }) { icon() }
}
