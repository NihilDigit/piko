package dev.piko.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * 一页里最主要的那件事（添加链接、清空回收站、选中查重建议移走的），用强调样式摆出来：宽窗口是命令栏右端
 * 有底色的按钮，窄窗口是扩展 FAB。两种形态同一份 SheetAction，页面只决定「这一页的主操作是什么」，不各自挑样式。
 * 清空这类找不回的（destructive）用错误色，与 PikoDialogConfirm 一致。
 *
 * 曾经各放各的：清空回收站在宽窗口收进「更多」、窄窗口是顶栏上的一个图标，查重的入口是一个文字按钮，
 * 都看不出是这一页要做的事。
 */

/** 宽窗口：命令栏右端有底色的按钮。 */
@Composable
fun PrimaryActionButton(action: SheetAction, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = action.onClick,
        colors = if (action.destructive) ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError) else ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(action.label)
    }
}

/** 窄窗口：扩展 FAB。 */
@Composable
fun PrimaryActionFab(action: SheetAction, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    ExtendedFloatingActionButton(
        onClick = action.onClick,
        icon = { Icon(action.icon, contentDescription = null) },
        text = { Text(action.label) },
        containerColor = if (action.destructive) colors.errorContainer else colors.primaryContainer,
        contentColor = if (action.destructive) colors.onErrorContainer else colors.onPrimaryContainer,
        modifier = modifier,
    )
}
