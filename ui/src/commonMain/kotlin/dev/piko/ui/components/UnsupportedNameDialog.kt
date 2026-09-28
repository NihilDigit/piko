package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.piko.shared.data.DriveNames
import dev.piko.ui.LocalPikoServices
import kotlinx.coroutines.launch

/**
 * 网盘名称的提交关口：PikPak 不支持的名称先问用户是否改用去掉不支持部分后的名称，而不是等服务端拒绝后
 * 只报一句「重命名失败」。去掉后什么都不剩的名称没法替用户改，由输入框自己标错，见 [isUnfixableDriveName]。
 *
 * 用法：确认时调 [submitDriveName]，它在名称合规或开了自动修正时直接交出，否则把名称记进 pending；
 * pending 非空时显示本对话框。勾选「不再询问」并采用时，一并打开设置里的「自动修正名称」。
 */
@Composable
fun UnsupportedNameDialog(
    name: String,
    onUseCleaned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val preferences = LocalPikoServices.current.preferences
    val scope = rememberCoroutineScope()
    val cleaned = DriveNames.clean(name)
    var dontAskAgain by remember { mutableStateOf(false) }
    val removedStyle = SpanStyle(
        color = MaterialTheme.colorScheme.onErrorContainer,
        background = MaterialTheme.colorScheme.errorContainer,
        textDecoration = TextDecoration.LineThrough,
    )
    val original = remember(name, removedStyle) {
        val removed = DriveNames.removedIndices(name)
        buildAnnotatedString {
            name.forEachIndexed { index, char ->
                // 换行与制表符原样画出来看不见，换成可见的符号再标
                val shown = when (char) {
                    '\n', '\r' -> "↵"
                    '\t' -> "⇥"
                    else -> char.toString()
                }
                if (index in removed) withStyle(removedStyle) { append(shown) } else append(shown)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
        title = { Text("修正名称") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(
                    text = "PikPak 不支持名称中的${DriveNames.unsupportedParts(name).joinToString("、")}。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp),
                    ) {
                        LabeledName("原名称") { Text(original, style = MaterialTheme.typography.bodyLarge) }
                        LabeledName("改为") {
                            SelectionContainer { Text(cleaned, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .toggleable(value = dontAskAgain, role = Role.Checkbox, onValueChange = { dontAskAgain = it })
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(checked = dontAskAgain, onCheckedChange = null)
                    Text("以后自动修正，不再询问", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                // 先写偏好再交出名称：交出后对话框随即关闭，它的作用域一取消，还没写完的偏好就丢了
                onClick = {
                    scope.launch {
                        if (dontAskAgain) preferences.setAutoCleanNamesEnabled(true)
                        onUseCleaned(cleaned)
                    }
                },
            ) {
                Text("使用此名称")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("返回修改") }
        },
    )
}

@Composable
private fun LabeledName(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
}

/**
 * 名称合规时交给 [onValid]（去掉首尾空格），返回 null。不合规时，[autoClean]（设置里的「自动修正名称」）
 * 打开则交出修正后的名称；否则返回原名称，交给调用方存为 pending 并弹出 [UnsupportedNameDialog]。
 */
fun submitDriveName(name: String, autoClean: Boolean, onValid: (String) -> Unit): String? {
    when {
        DriveNames.unsupportedParts(name).isEmpty() -> onValid(name.trim())
        autoClean -> onValid(DriveNames.clean(name))
        else -> return name
    }
    return null
}

/**
 * 名称输入框下方那一行，始终有字。Android 的对话框是按内容定高、居中的独立窗口，这一行时有时无，
 * 键入一个字符整个对话框就上下跳半行。自动修正打开且名称要改时，只写去掉什么而不写修正后的全名：
 * 全名随输入变长、折成多行，照样撑高对话框。去掉后什么都不剩时写成错误，调用方据此标红。
 */
fun driveNameHint(name: String, autoClean: Boolean): String {
    val parts = DriveNames.unsupportedParts(name)
    return when {
        isUnfixableDriveName(name) -> "名称只含 PikPak 不支持的字符"
        autoClean && parts.isNotEmpty() -> "将去掉${parts.joinToString("、")}"
        else -> "不能含 \\ / : * ? \" < > |"
    }
}

/** 去掉不支持的部分后什么都不剩，没法自动修。 */
fun isUnfixableDriveName(name: String): Boolean =
    name.isNotBlank() && DriveNames.clean(name).isEmpty()
