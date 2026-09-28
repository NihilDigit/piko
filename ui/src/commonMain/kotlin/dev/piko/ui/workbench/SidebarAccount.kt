package dev.piko.ui.workbench

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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.settings.AccountCard
import dev.piko.ui.screens.settings.Avatar
import dev.piko.ui.screens.settings.LogoutDialog
import dev.piko.ui.screens.settings.SettingsScreen
import dev.piko.ui.screens.settings.rememberAccountSummary
import dev.piko.update.UpdateStatus

/**
 * 侧边栏左下角：账号与设置，照桌面应用的通行做法。手机上它们都在「我的」里；桌面侧边栏竖向够用，
 * 不必再收进一个二级页。点账号或齿轮都打开 [SettingsPanel]，账号卡片在它最前面。
 * 齿轮在查到新版本时带一个红点，与「我的」页设置入口上的提示对应。
 */
@Composable
internal fun SidebarAccountRow(panelOpen: Boolean, onOpenSettings: () -> Unit) {
    val account = rememberAccountSummary()
    val session = account.session
    val availableUpdate = (LocalPikoPlatform.current.updater?.status as? UpdateStatus.Available)?.update
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = "账号与设置", onClick = onOpenSettings)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(session?.username, session?.avatarUrl, size = 32.dp)
            Column {
                Text(
                    session?.username?.ifEmpty { null } ?: "PikPak 用户",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                account.quota?.let { quota ->
                    Text(
                        "${quota.usageBytes.toReadableSize()} / ${quota.limitBytes.toReadableSize()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        BadgedBox(badge = { if (availableUpdate != null) Badge() }) {
            TooltipIconButton(
                icon = Icons.Outlined.Settings,
                label = availableUpdate?.let { "设置（发现新版本 ${it.version}）" } ?: "设置",
                onClick = onOpenSettings,
                shortcut = LocalPikoPlatform.current.shortcutModifier.label(","),
                tint = if (panelOpen) colors.primary else Color.Unspecified,
            )
        }
    }
}

/**
 * 桌面宽窗口的设置：从右侧浮起的面板，标题与关闭在面板顶上，点外面、Esc 也关。不占内容区，关掉就回到原来看的地方，
 * 不像整页那样还得找返回。最前面是账号卡片与退出登录：它们在手机上属于「我的」页。
 *
 * 账号卡片不放进侧边栏的下拉菜单：菜单按内容的固有尺寸定大小，卡片展开流量额度后的用量表格是
 * SubcomposeLayout，被问固有尺寸会直接抛异常。
 */
@Composable
internal fun SettingsPanel(onDismiss: () -> Unit, onLogout: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    PikoSheet(onDismissRequest = onDismiss, sideSheetTitle = "设置") {
        SettingsScreen(
            onBackClick = null,
            inSidePanel = true,
            modifier = Modifier.weight(1f),
            header = {
                AccountCard(rememberAccountSummary())
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { confirmLogout = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("退出登录")
                }
                Spacer(Modifier.height(8.dp))
            },
        )
    }
    if (confirmLogout) {
        LogoutDialog(
            onDismiss = { confirmLogout = false },
            onLoggedOut = {
                onDismiss()
                onLogout()
            },
        )
    }
}
