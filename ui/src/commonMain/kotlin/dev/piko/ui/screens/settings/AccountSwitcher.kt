package dev.piko.ui.screens.settings

import dev.piko.shared.i18n.tr

import dev.piko.ui.resources.*
import dev.piko.ui.i18n.stringResource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.piko.ui.platform.LocalPikoPlatform
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
                supporting = if (isExpired) tr("需重新登录", "Sign in again", "再ログインが必要です", "다시 로그인 필요") else accountLine(saved),
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
                        TooltipIconButton(Icons.Outlined.Close, tr("移除", "Remove", "削除", "제거"), onClick = { forgetting = saved }, enabled = switching == null)
                    }
                },
            )
        }
        // 这一组的图标放进与头像同宽的格子里，各行标题才对得齐
        SettingsRow(
            title = tr("添加账号", "Add account", "アカウントを追加", "계정 추가"),
            leading = { AvatarSlot(Icons.Outlined.PersonAdd, MaterialTheme.colorScheme.onSurfaceVariant) },
            supporting = if (others.isEmpty()) tr("可同时保存多个账号并随时切换", "Save multiple accounts and switch anytime", "複数のアカウントを保存し、いつでも切り替えられます", "여러 계정을 저장하고 언제든 전환할 수 있습니다") else null,
            enabled = switching == null,
            onClick = { clientManager.beginAddingAccount() },
        )
        if (onLoggedOut != null) {
            SettingsRow(
                title = if (others.isEmpty()) tr("退出登录", "Sign out", "ログアウト", "로그아웃") else tr("退出此账号", "Sign out of this account", "このアカウントからログアウト", "이 계정에서 로그아웃"),
                leading = { AvatarSlot(Icons.AutoMirrored.Outlined.Logout, MaterialTheme.colorScheme.error) },
                titleColor = MaterialTheme.colorScheme.error,
                enabled = switching == null,
                onClick = { confirmLogout = true },
            )
        }
    }
    AppLinkPromptBanner()
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
            title = { Text(stringResource(Res.string.text_remove_account)) },
            text = { Text(tr("将清除本机保存的「${saved.displayName}」登录凭据，再次使用需重新登录。", "Remove saved credentials for “${saved.displayName}” from this device. Sign in again to use the account.", "この端末の「${saved.displayName}」のログイン情報を削除します。再度利用するにはログインが必要です。", "기기에 저장된 “${saved.displayName}”의 로그인 정보를 삭제합니다. 다시 사용하려면 로그인해야 합니다.")) },
            confirmButton = {
                PikoDialogConfirm(
                    label = tr("移除", "Remove", "削除", "제거"),
                    destructive = true,
                    onClick = {
                        clientManager.forget(saved.account)
                        forgetting = null
                    },
                )
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text(stringResource(Res.string.common_cancel)) } },
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
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.FilledTonalButton(
        onClick = { confirmLogout = true },
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = settingsStyle().groupShape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.errorContainer.copy(alpha = 0.24f),
            contentColor = colors.error,
        ),
    ) {
        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (others.isEmpty()) tr("退出登录", "Sign out", "ログアウト", "로그아웃") else tr("退出此账号", "Sign out of this account", "このアカウントからログアウト", "이 계정에서 로그아웃"))
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

/**
 * 「我的」页在「添加账号」下方显示的深链默认打开提醒卡片：
 * 当系统支持在「默认打开」中添加 PikPak 分享与跳转域名、且尚未添加、用户也未关闭提醒时展示；
 * 从系统设置返回应用（ON_RESUME）时自动重查，已添加则自动收起。
 */
@Composable
private fun AppLinkPromptBanner() {
    val platform = LocalPikoPlatform.current
    val openSettings = platform.openAppLinkSettings ?: return
    val scope = rememberCoroutineScope()
    var showPrompt by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, platform) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { showPrompt = platform.needsAppLinkPrompt() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(platform) {
        showPrompt = platform.needsAppLinkPrompt()
    }
    if (!showPrompt) return
    SettingsGroup(null) {
        SettingsRow(
            title = tr("用 Piko 直接打开分享链接", "Open share links in Piko", "共有リンクをPikoで直接開く", "Piko에서 공유 링크 바로 열기"),
            leading = { AvatarSlot(Icons.Outlined.Link, MaterialTheme.colorScheme.primary) },
            supporting = tr("在系统「默认打开」中勾选支持的链接域名，点击 PikPak 分享或跳转链接即可直接用 Piko 打开", "Enable supported domains in the system’s default app settings to open PikPak links in Piko", "システムの既定アプリ設定で対応ドメインを有効にすると、PikPakリンクをPikoで直接開けます", "시스템 기본 앱 설정에서 지원 도메인을 선택하면 PikPak 링크를 Piko에서 바로 열 수 있습니다"),
            onClick = openSettings,
            trailing = {
                TooltipIconButton(
                    icon = Icons.Outlined.Close,
                    label = tr("不再提示", "Do not show again", "今後表示しない", "다시 표시하지 않기"),
                    onClick = {
                        platform.dismissAppLinkPrompt()
                        showPrompt = false
                    },
                )
            },
        )
    }
}
