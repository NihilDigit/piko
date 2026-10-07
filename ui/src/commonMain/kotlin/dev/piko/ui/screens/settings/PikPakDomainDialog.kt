package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.piko.ui.components.TooltipIconButton
import io.github.nihildigit.pikpak.DomainProbe
import io.github.nihildigit.pikpak.PikPakDomain

/**
 * 选 PikPak API 的根域名：自动（登录后测速挑最快的）或固定一个。每个根域名后面是最近一次测得的延迟，
 * 即连接复用后一次请求的往返，见 PikPakDomainSelector。选中即生效，不必确认。
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
        title = "服务器域名",
        onDismiss = onDismiss,
        footer = "各域名指向同一组服务器，账号通用。某一域名受限时可换用其他域名。",
        // 测速放在列表上方：原来是底部与「完成」并排的第二个按钮，读起来像另一种提交方式（ux-review M2）
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (probing) "正在测速" else "延迟为最近一次测速结果",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TooltipIconButton(Icons.Outlined.Refresh, "重新测速", onProbeAgain, enabled = !probing)
            }
        },
    ) {
        SettingsChoiceOption(
            title = "自动选择",
            // 测速是异步的，结果回来才有域名；原先「按测速结果选用最快的域名，当前为 …」在窄屏上
            // 折成两行，整个对话框随之跳一下。有域名时只写域名，短到一行放得下
            supporting = active?.let { "测速选定 ${it.root}" } ?: "选用测速最快的域名",
            selected = choice.isEmpty(),
            onClick = { onChoose("") },
        )
        PikPakDomain.entries.forEach { domain ->
            SettingsChoiceOption(
                title = domain.root,
                supporting = probes[domain].latencyLabel(probing),
                selected = choice == domain.root,
                onClick = { onChoose(domain.root) },
            )
        }
    }
}

private fun DomainProbe?.latencyLabel(probing: Boolean): String = when {
    this == null -> if (probing) "测速中" else "未测速"
    !usable || warmRequest == null -> "不可用"
    else -> "延迟 ${warmRequest!!.inWholeMilliseconds} ms"
}

/** 设置页那一行的说明：「自动选择 mypikpak.net，延迟 92 ms」，固定时是「固定为 mypikpak.net，延迟 92 ms」。 */
fun domainSummary(choice: String, active: PikPakDomain?, probes: Map<PikPakDomain, DomainProbe>): String {
    val latency = active?.let { probes[it] }?.takeIf { it.usable }?.warmRequest?.let { "，延迟 ${it.inWholeMilliseconds} ms" }.orEmpty()
    val using = active?.root ?: choice.ifEmpty { null }
    return when {
        choice.isEmpty() && using == null -> "自动选择"
        choice.isEmpty() -> "自动选择 $using$latency"
        else -> "固定为 $choice$latency"
    }
}
