package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
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
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
        title = { Text("服务器域名") },
        text = {
            Column {
                Column(Modifier.selectableGroup()) {
                    DomainRow(
                        title = "自动",
                        supporting = active?.let { "登录后测速，挑最快的。现在用 ${it.root}" } ?: "登录后测速，挑最快的",
                        selected = choice.isEmpty(),
                        onSelect = { onChoose("") },
                    )
                    PikPakDomain.entries.forEach { domain ->
                        DomainRow(
                            title = domain.root,
                            supporting = probes[domain].latencyLabel(probing),
                            selected = choice == domain.root,
                            onSelect = { onChoose(domain.root) },
                        )
                    }
                }
                Text(
                    text = "四个域名通往同一组服务器，账号通用。网络对某个域名限速时，换一个可能更快。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = onProbeAgain, enabled = !probing) { Text(if (probing) "正在测速" else "重新测速") }
        },
    )
}

private fun DomainProbe?.latencyLabel(probing: Boolean): String = when {
    this == null -> if (probing) "正在测速" else "未测速"
    !usable || warmRequest == null -> "不可用"
    else -> "延迟 ${warmRequest!!.inWholeMilliseconds} ms"
}

@Composable
private fun DomainRow(title: String, supporting: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 设置页那一行的说明：「自动，现在用 mypikpak.net，延迟 92 ms」。 */
fun domainSummary(choice: String, active: PikPakDomain?, probes: Map<PikPakDomain, DomainProbe>): String {
    val latency = active?.let { probes[it] }?.takeIf { it.usable }?.warmRequest?.let { "，延迟 ${it.inWholeMilliseconds} ms" }.orEmpty()
    val using = active?.root ?: choice.ifEmpty { null }
    return when {
        choice.isEmpty() && using == null -> "自动"
        choice.isEmpty() -> "自动，现在用 $using$latency"
        else -> "$choice$latency"
    }
}
