package dev.piko.ui.screens.archive

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import dev.piko.shared.i18n.tr

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
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import dev.piko.ui.components.TooltipIconButton

/**
 * 解压时的密码框：输入框下列出解压成功过的密码，点一个填进输入框，由用户确认后才提交。
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
) = ArchivePasswordDialog(
    key = job.id,
    archiveName = job.file.name,
    incorrect = (job.status as? ArchiveJobStatus.NeedsPassword)?.incorrect == true,
    initialPassword = job.password,
    savedPasswords = savedPasswords,
    confirmLabel = tr("解压", "Extract", "解凍", "압축 해제"),
    dismissLabel = tr("跳过", "Skip", "スキップ", "건너뛰기"),
    onSubmit = onSubmit,
    onDismiss = onSkip,
)

/**
 * 进压缩包浏览时的密码框。存过的密码已经逐个试过（列目录只是一次查询，不像解压那样每试一次是一个任务），
 * 这里不再列出。不输就退出这个压缩包。
 */
@Composable
internal fun ArchiveBrowsePasswordDialog(
    archiveName: String,
    incorrect: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) = ArchivePasswordDialog(
    key = archiveName,
    archiveName = archiveName,
    incorrect = incorrect,
    initialPassword = "",
    savedPasswords = emptyList(),
    confirmLabel = tr("打开", "Open", "開く", "열기"),
    dismissLabel = tr("取消", "Cancel", "キャンセル", "취소"),
    onSubmit = onSubmit,
    onDismiss = onDismiss,
)

@Composable
private fun ArchivePasswordDialog(
    key: Any,
    archiveName: String,
    incorrect: Boolean,
    initialPassword: String,
    savedPasswords: List<String>,
    confirmLabel: String,
    dismissLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 输错后重新弹出时保留上次的输入，方便改错字
    var password by remember(key) { mutableStateOf(initialPassword) }
    var visible by remember(key) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // 有存过的密码时不自动聚焦：多半是点选其一，而弹出的键盘在横屏时会盖住底部按钮
    // （对话框窗口遇键盘只平移到输入框可见，不缩放）。手输时键盘上的完成键照样提交
    LaunchedEffect(key) { if (savedPasswords.isEmpty() || incorrect) focus.requestFocus() }
    val submit = { if (password.isNotEmpty()) onSubmit(password) }

    PikoDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        title = { Text(if (incorrect) tr("密码错误", "Incorrect password", "パスワードが違います", "비밀번호가 틀렸습니다") else tr("需要密码", "Password required", "パスワードが必要です", "비밀번호가 필요합니다")) },
        text = {
            // 整段可滚：横屏时键盘上方只剩四百来 dp，放不下密码表，不滚的话底部按钮会被截掉。
            // AlertDialog 的正文区可以收缩，正文能滚时按钮始终留在可见范围内
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = archiveName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(Res.string.text_password)) },
                    singleLine = true,
                    isError = incorrect,
                    supportingText = if (incorrect) {
                        { Text(stringResource(Res.string.text_the_server_rejected_this_password)) }
                    } else {
                        null
                    },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TooltipIconButton(
                            icon = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            label = if (visible) tr("隐藏密码", "Hide password", "パスワードを隠す", "비밀번호 숨기기") else tr("显示密码", "Show password", "パスワードを表示", "비밀번호 표시"),
                            onClick = { visible = !visible },
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (savedPasswords.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.text_saved_passwords),
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
            PikoDialogConfirm(confirmLabel, onClick = submit, enabled = password.isNotEmpty())
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
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
                { TooltipIconButton(icon = Icons.Outlined.Delete, label = tr("删除", "Delete", "削除", "삭제"), onClick = onDelete) }
            }
            selected -> {
                { Icon(Icons.Outlined.Check, contentDescription = tr("已选", "Selected", "選択済み", "선택됨"), tint = MaterialTheme.colorScheme.primary) }
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
    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        title = { Text(stringResource(Res.string.text_archive_password)) },
        text = {
            if (passwords.isEmpty()) {
                Text(stringResource(Res.string.text_no_saved_passwords_verified_archive_passwords_are_saved_here_for_reuse))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(Res.string.text_verified_passwords_most_recent_first_stored_on_this_device_only),
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
        // 删除当场生效，与设置里点选即生效的对话框一样只留「关闭」
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) }
        },
    )
}
