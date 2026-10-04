package dev.piko.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.shared.data.SavedAccount
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize
import kotlinx.coroutines.launch

/**
 * 账号卡片下面的账号管理：本机保存的其余账号（点一下切过去）与添加账号。退出在 [LogoutButton]。
 * 「我的」页与宽窗口的设置页共用。当前账号就是上面那张卡片，这里不再列它。
 */
@Composable
internal fun AccountSwitcher() {
    val clientManager = LocalPikoServices.current.clientManager
    val others = otherAccounts()
    val scope = rememberCoroutineScope()
    var switching by remember { mutableStateOf<String?>(null) }
    var expired by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf<SavedAccount?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            others.forEach { saved ->
                AccountRow(
                    leading = { Avatar(saved.displayName, saved.avatarUrl, size = 36.dp) },
                    title = saved.displayName,
                    supporting = if (expired == saved.account) "登录已失效，请重新登录" else accountLine(saved),
                    supportingIsError = expired == saved.account,
                    enabled = switching == null,
                    onClick = {
                        if (expired == saved.account) {
                            clientManager.beginAddingAccount(saved.account)
                            return@AccountRow
                        }
                        switching = saved.account
                        scope.launch {
                            clientManager.switchTo(saved.account).onFailure { expired = saved.account }
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
            AccountRow(
                leading = {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                title = "添加账号",
                supporting = if (others.isEmpty()) "可同时保存多个账号并随时切换" else null,
                enabled = switching == null,
                onClick = { clientManager.beginAddingAccount() },
            )
        }
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
private fun AccountRow(
    leading: @Composable () -> Unit,
    title: String,
    supporting: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    supportingIsError: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (supportingIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing() else Spacer(Modifier.size(40.dp))
    }
}
