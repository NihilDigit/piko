package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import io.github.nihildigit.pikpak.DomainProbe
import io.github.nihildigit.pikpak.PikPakDomain

/**
 * 选 PikPak API 的根域名：自动或固定一个。各域名行尾直接显示最近一次连接复用请求的往返延迟（如 `92 ms`），
 * 选中即生效。
 */
@Composable
fun PikPakDomainDialog(
    /** 用户的选择，空串是自动。 */
    choice: String,
    active: PikPakDomain?,
    probes: Map<PikPakDomain, DomainProbe>,
    probing: Boolean,
    onChoose: (String) -> Unit,
    onProbeAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsChoiceDialog(
        icon = Icons.Outlined.Dns,
        title = tr("服务器域名", "Server domain", "サーバードメイン", "서버 도메인", "伺服器網域"),
        onDismiss = onDismiss,
        dismissButton = {
            TextButton(onClick = onProbeAgain, enabled = !probing) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (probing) tr("测速中…", "Testing…", "測定中…", "측정 중…", "測速中…") else tr("重新测速", "Test again", "再測定", "다시 측정", "重新測速"))
            }
        },
    ) {
        SettingsChoiceOption(
            title = tr("自动", "Automatic", "自動", "자동", "自動"),
            leadingIcon = Icons.Outlined.AutoAwesome,
            trailingText = active?.root,
            selected = choice.isEmpty(),
            onClick = { onChoose("") },
        )
        PikPakDomain.entries.forEach { domain ->
            val probe = probes[domain]
            val unavailable = probe != null && (!probe.usable || probe.warmRequest == null)
            val hasLatency = probe != null && probe.usable && probe.warmRequest != null
            SettingsChoiceOption(
                title = domain.root,
                leadingIcon = Icons.Outlined.Public,
                trailingText = probe.latencyLabel(probing),
                trailingIcon = when {
                    unavailable -> Icons.Outlined.ErrorOutline
                    hasLatency -> Icons.Outlined.Speed
                    else -> null
                },
                trailingIconTint = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                selected = choice == domain.root,
                onClick = { onChoose(domain.root) },
            )
        }
    }
}

private fun DomainProbe?.latencyLabel(probing: Boolean): String? = when {
    this == null -> if (probing) tr("测速中", "Testing", "測定中", "측정 중", "測速中") else null
    !usable || warmRequest == null -> tr("不可用", "Unavailable", "利用不可", "사용 불가", "無法使用")
    else -> "${warmRequest!!.inWholeMilliseconds} ms"
}

/** 设置页那一行的摘要：自动时为「自动 mypikpak.com」，固定时直接写域名。 */
fun domainSummary(choice: String, active: PikPakDomain?, probes: Map<PikPakDomain, DomainProbe> = emptyMap()): String {
    val autoLabel = tr("自动", "Automatic", "自動", "자동", "自動")
    return when {
        choice.isNotEmpty() -> choice
        active != null -> "$autoLabel ${active.root}"
        else -> autoLabel
    }
}
