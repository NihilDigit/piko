package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.SavedAccount
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize
import kotlinx.coroutines.launch

/**
 * 账号卡片下面的账号管理：本机保存的其余账号（点一下切过去）与添加账号。
 * 「我的」页与桌面的设置页共用。当前账号就是上面那张卡片，这里不再列它。
 *
 * [onLoggedOut] 不为 null 时组末加一行「退出登录」（桌面设置页）；「我的」页照惯例把退出放在页底（[LogoutButton]）。
 */
@Composable
internal fun AccountSwitcher(onLoggedOut: (() -> Unit)? = null) {
    val clientManager = LocalPikoServices.current.clientManager
    val others = otherAccounts()
    val scope = rememberCoroutineScope()
    var switching by remember { mutableStateOf<String?>(null) }
    val expired by clientManager.expiredAccounts.collectAsStateWithLifecycle()
    var forgetting by remember { mutableStateOf<SavedAccount?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    SettingsGroup(null) {
        others.forEach { saved ->
            val isExpired = saved.account in expired
            SettingsRow(
                title = saved.displayName,
                leading = { Avatar(saved.displayName, saved.avatarUrl, size = AccountAvatarSize) },
                supporting = if (isExpired) "需重新登录" else accountLine(saved),
                supportingColor = if (isExpired) MaterialTheme.colorScheme.error else Color.Unspecified,
                enabled = switching == null,
                onClick = {
                    if (isExpired) {
                        clientManager.beginAddingAccount(saved.account)
                        return@SettingsRow
                    }
                    switching = saved.account
                    scope.launch {
                        // 失败时 switchTo 已把它记进 expiredAccounts，这一行随之改成「需重新登录」
                        clientManager.switchTo(saved.account)
                        switching = null
                    }
                },
                trailing = {
                    if (switching == saved.account) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { InlineLoadingIndicator() }
                    } else {
                        TooltipIconButton(Icons.Outlined.Close, "移除", onClick = { forgetting = saved }, enabled = switching == null)
                    }
                },
            )
        }
        // 这一组的图标放进与头像同宽的格子里，各行标题才对得齐
        SettingsRow(
            title = "添加账号",
            leading = { AvatarSlot(Icons.Outlined.PersonAdd, MaterialTheme.colorScheme.onSurfaceVariant) },
            supporting = if (others.isEmpty()) "可同时保存多个账号并随时切换" else null,
            enabled = switching == null,
            onClick = { clientManager.beginAddingAccount() },
        )
        if (onLoggedOut != null) {
            SettingsRow(
                title = if (others.isEmpty()) "退出登录" else "退出此账号",
                leading = { AvatarSlot(Icons.AutoMirrored.Outlined.Logout, MaterialTheme.colorScheme.error) },
                titleColor = MaterialTheme.colorScheme.error,
                enabled = switching == null,
                onClick = { confirmLogout = true },
            )
        }
    }
    if (confirmLogout && onLoggedOut != null) {
        LogoutDialog(
            next = others.maxByOrNull { it.usedAt },
            onDismiss = { confirmLogout = false },
            onLoggedOut = onLoggedOut,
        )
    }
    forgetting?.let { saved ->
        PikoDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("移除账号") },
            text = { Text("将清除本机保存的「${saved.displayName}」登录凭据，再次使用需重新登录。") },
            confirmButton = {
                PikoDialogConfirm(
                    label = "移除",
                    destructive = true,
                    onClick = {
                        clientManager.forget(saved.account)
                        forgetting = null
                    },
                )
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun otherAccounts(): List<SavedAccount> {
    val clientManager = LocalPikoServices.current.clientManager
    val accounts by clientManager.accounts.collectAsStateWithLifecycle()
    val client by clientManager.currentClient.collectAsStateWithLifecycle()
    return accounts.accounts.filter { it.account != client?.account }
}

/**
 * 退出当前账号。与账号列表分开：手机上它照惯例在「我的」页最底下，账号列表紧跟账号卡片；
 * 宽窗口的设置页里两者挨着。还有别的账号时写「退出此账号」，确认框里说明随后切到哪个。
 */
@Composable
internal fun LogoutButton(onLoggedOut: () -> Unit) {
    val others = otherAccounts()
    var confirmLogout by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { confirmLogout = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (others.isEmpty()) "退出登录" else "退出此账号")
    }
    if (confirmLogout) {
        LogoutDialog(
            next = others.maxByOrNull { it.usedAt },
            onDismiss = { confirmLogout = false },
            onLoggedOut = onLoggedOut,
        )
    }
}

/** 账号行的第二行：邮箱与上次记下的空间用量，缺哪样略去哪样。 */
private fun accountLine(saved: SavedAccount): String? {
    val usage = saved.takeIf { it.limitBytes > 0 }?.let { "${it.usageBytes.toReadableSize()} / ${it.limitBytes.toReadableSize()}" }
    return listOfNotNull(saved.email.ifBlank { null }, usage).joinToString("，").ifEmpty { null }
}

@Composable
private fun AvatarSlot(icon: ImageVector, tint: Color) {
    Box(Modifier.size(AccountAvatarSize), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(settingsStyle().iconSize))
    }
}

// 比行图标大一号，仍放得进一行之内；与当前账号卡片的 48dp 头像分出主次
private val AccountAvatarSize = 32.dp
