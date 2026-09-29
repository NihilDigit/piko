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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.background
import dev.piko.ui.theme.FrameBottomRowHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.screens.settings.AccountCard
import dev.piko.ui.screens.settings.Avatar
import dev.piko.ui.screens.settings.AccountSwitcher
import dev.piko.ui.screens.settings.LogoutButton
import dev.piko.ui.screens.settings.rememberAccountSummary
import dev.piko.update.UpdateStatus

/**
 * 侧边栏左下角：账号与设置，照桌面应用的通行做法。手机上它们都在「我的」里；桌面侧边栏竖向够用，
 * 不必再收进一个二级页。整行是一个入口，打开设置页，账号是它的第一类（[AccountSettings]）：账号与齿轮
 * 去的是同一个地方，分成两个点击区只会让人以为它们不同。设置页开着时整行亮起，与侧边栏的其他项一样。
 * 齿轮在查到新版本时带一个红点，与「我的」页设置入口上的提示对应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SidebarAccountRow(selected: Boolean, onOpenSettings: () -> Unit, collapsed: Boolean = false) {
    val account = rememberAccountSummary()
    val saved = account.saved
    val availableUpdate = (LocalPikoPlatform.current.updater?.status as? UpdateStatus.Available)?.update
    val colors = MaterialTheme.colorScheme
    val shortcut = LocalPikoPlatform.current.shortcutModifier.label(",")
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(availableUpdate?.let { "账号与设置（发现新版本 ${it.version}）" } ?: "账号与设置 ($shortcut)") } },
        state = rememberTooltipState(),
    ) {
        // 收起成窄轨时只剩头像，名字、用量与齿轮都在悬停提示与设置里；有新版本时头像上挂一个点
        if (collapsed) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FrameBottomRowHeight)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(if (selected) colors.secondaryContainer else Color.Transparent)
                    .clickable(onClickLabel = "账号与设置", onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                BadgedBox(badge = { if (availableUpdate != null) Badge() }) {
                    Avatar(saved?.displayName, saved?.avatarUrl, size = 32.dp)
                }
            }
            return@TooltipBox
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 与传输页的底栏同高，两边的中线对齐
                .height(FrameBottomRowHeight)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(MaterialTheme.shapes.large)
                .background(if (selected) colors.secondaryContainer else Color.Transparent)
                .clickable(onClickLabel = "账号与设置", onClick = onOpenSettings)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(saved?.displayName, saved?.avatarUrl, size = 32.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    saved?.displayName ?: "PikPak 用户",
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
            BadgedBox(badge = { if (availableUpdate != null) Badge() }) {
                Icon(
                    if (selected) Icons.Filled.Settings else Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 设置页里「账号」一类的内容：账号卡片、切换与添加账号、退出登录。有整条侧边栏的宽窗口没有「我的」页，它们放在设置的最前；
 * 手机上它们在「我的」页。
 *
 * 账号卡片不放进侧边栏的下拉菜单：菜单按内容的固有尺寸定大小，卡片展开流量额度后的用量表格是
 * SubcomposeLayout，被问固有尺寸会直接抛异常。
 */
@Composable
internal fun ColumnScope.AccountSettings(onLogout: () -> Unit) {
    // 宽窗口没有下拉刷新，另给按钮
    AccountCard(rememberAccountSummary(), showRefresh = true)
    Spacer(Modifier.height(12.dp))
    AccountSwitcher()
    Spacer(Modifier.height(12.dp))
    LogoutButton(onLoggedOut = onLogout)
}
