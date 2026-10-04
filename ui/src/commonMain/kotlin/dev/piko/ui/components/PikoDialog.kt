package dev.piko.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * 应用里的对话框，参数与 AlertDialog 相同。标题比 M3 的基本对话框小一号（titleLarge），圆角收到 16dp。
 *
 * M3 的基本对话框按触屏设计：24sp 的大标题、28dp 的圆角、无底色的文字按钮，在宽窗口里像一张漂在中间的手机卡片，
 * 按钮看着像链接。执行动作的对话框用 [PikoDialogConfirm] 作确认，取消仍是文字按钮；只是告知、只有「知道了」
 * 一个按钮的，不必有底色，没有要执行的动作。两端一个样子：有底色的按钮在手机上同样成立，没有按平台分开的理由。
 */
@Composable
fun PikoDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title?.let { content -> { ProvideTextStyle(MaterialTheme.typography.titleLarge) { content() } } },
        text = text,
        shape = MaterialTheme.shapes.large,
        properties = properties,
    )
}

/**
 * 对话框的确认按钮，有底色。[destructive] 时换成错误色：删掉的找不回来，后果落在要按下去的那一处，
 * 文案不必另加「并删除」，也不在对话框里另起一行红字。
 */
@Composable
fun PikoDialogConfirm(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) { Text(label) }
}
