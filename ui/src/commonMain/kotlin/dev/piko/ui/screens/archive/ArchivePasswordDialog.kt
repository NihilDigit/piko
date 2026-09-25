package dev.piko.ui.screens.archive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.piko.shared.state.ArchiveJob
import dev.piko.shared.state.ArchiveJobStatus
import dev.piko.ui.components.TooltipIconButton

/**
 * 加密压缩包的密码框：输入框下列出解压成功过的密码，点一个填进输入框，由用户确认后才提交。
 * 不自动逐个尝试，这是明确的取舍，见 ArchivePasswordVault。
 *
 * 返回键与 Esc 等同「跳过」。点对话框外不关闭：没有别的入口能重新打开它，误触就得重新发起解压。
 */
@Composable
internal fun ArchivePasswordDialog(
    job: ArchiveJob,
    savedPasswords: List<String>,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit,
) {
    // 输错后重新弹出时保留上次的输入，方便改错字
    var password by remember(job.id) { mutableStateOf(job.password) }
    var visible by remember(job.id) { mutableStateOf(false) }
    val incorrect = (job.status as? ArchiveJobStatus.NeedsPassword)?.incorrect == true
    val focus = remember { FocusRequester() }
    // 有存过的密码时不自动聚焦：多半是点选其一，而弹出的键盘在横屏时会盖住底部按钮
    // （对话框窗口遇键盘只平移到输入框可见，不缩放）。手输时键盘上的完成键照样提交
    LaunchedEffect(job.id) { if (savedPasswords.isEmpty() || incorrect) focus.requestFocus() }
    val submit = { if (password.isNotEmpty()) onSubmit(password) }

    AlertDialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        title = { Text(if (incorrect) "密码错误" else "需要密码") },
        text = {
            // 整段可滚：横屏时键盘上方只剩四百来 dp，放不下密码表，不滚的话底部按钮会被截掉。
            // AlertDialog 的正文区可以收缩，正文能滚时按钮始终留在可见范围内
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = job.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    isError = incorrect,
                    supportingText = if (incorrect) {
                        { Text("服务端拒绝了这个密码") }
                    } else {
                        null
                    },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TooltipIconButton(
                            icon = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            label = if (visible) "隐藏密码" else "显示密码",
                            onClick = { visible = !visible },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (savedPasswords.isNotEmpty()) {
                    Text(
                        text = "已保存的密码",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // 密码表最多 30 个，普通 Column 即可；放在可滚的正文里也不能再嵌一层懒加载列表
                    Column(modifier = Modifier.fillMaxWidth()) {
                        savedPasswords.forEach { saved ->
                            SavedPasswordRow(
                                password = saved,
                                selected = saved == password,
                                onClick = { password = saved },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = password.isNotEmpty()) { Text("解压") }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("跳过") }
        },
    )
}

@Composable
private fun SavedPasswordRow(
    password: String,
    selected: Boolean,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            // 等宽字体：l 与 1、O 与 0 这类密码里常见的混淆一眼能分清
            Text(password, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = when {
            onDelete != null -> {
                { TooltipIconButton(icon = Icons.Outlined.Delete, label = "删除", onClick = onDelete) }
            }
            selected -> {
                { Icon(Icons.Outlined.Check, contentDescription = "已选", tint = MaterialTheme.colorScheme.primary) }
            }
            else -> null
        },
    )
}

/** 设置页里管理已保存的解压密码，只能查看与删除；新密码只在服务端验证通过时记下。 */
@Composable
fun SavedArchivePasswordsDialog(
    passwords: List<String>,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        title = { Text("解压密码") },
        text = {
            if (passwords.isEmpty()) {
                Text("尚无保存的密码。解压加密压缩包时，验证通过的密码记在此处，供下次选用。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "验证通过的密码，最近用过的在前，仅存于本机。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(passwords, key = { it }) { saved ->
                            SavedPasswordRow(password = saved, selected = false, onDelete = { onDelete(saved) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
