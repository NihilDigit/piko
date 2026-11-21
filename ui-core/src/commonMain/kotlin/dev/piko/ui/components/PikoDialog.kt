package dev.piko.ui.components

import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.piko.ui.adaptive.isDesktopLayout
import dev.piko.ui.platform.ModalCaptionBlocker
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk

/**
 * 应用里的对话框，参数与 AlertDialog 相同。遵循 M3 Expressive 对话框容器色（`surfaceContainerHigh`）
 * 与强调标题（`titleLargeEmphasized`），桌面端收紧圆角至 `largeIncreased`（20dp），移动端保持
 * M3E 标准的 `extraLarge`（28dp），同时维持零跳动定高与清晰的主次按钮层级。
 *
 * 执行动作的对话框用 [PikoDialogConfirm] 作确认，取消仍是文字按钮；只是告知、只有「知道了」
 * 一个按钮的，不必有底色，没有要执行的动作。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val shapes = MaterialTheme.shapes
    val dialogShape = if (isDesktopLayout()) shapes.largeIncreased else shapes.extraLarge
    val isEInk = LocalAppearance.current.isEInk
    ModalCaptionBlocker()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = if (isEInk) {
            modifier.border(1.dp, MaterialTheme.colorScheme.outline, dialogShape)
        } else {
            modifier
        },
        dismissButton = dismissButton,
        icon = icon,
        title = title?.let { content -> { ProvideTextStyle(MaterialTheme.typography.titleLargeEmphasized) { content() } } },
        text = text,
        shape = dialogShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (isEInk) 0.dp else androidx.compose.material3.AlertDialogDefaults.TonalElevation,
        properties = properties,
    )
}

/**
 * 对话框的确认按钮，有底色。[destructive] 时换成错误色：删掉的找不回来，后果落在要按下去的那一处，
 * 文案不必另加「并删除」，也不在对话框里另起一行红字。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PikoDialogConfirm(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) { Text(label) }
}
