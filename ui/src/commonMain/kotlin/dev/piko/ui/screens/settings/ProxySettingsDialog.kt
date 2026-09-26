package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.piko.shared.net.ProxyMode
import dev.piko.shared.net.ProxyProtocol
import dev.piko.shared.net.ProxySetting

/**
 * 网络代理：跟随系统、不使用或手动填写。登录页与设置页共用，登录页也要有，
 * 因为连不上 PikPak 的人往往就卡在登录这一步。
 */
@Composable
fun ProxySettingsDialog(
    current: ProxySetting,
    onSave: (ProxySetting) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(current.mode) }
    var protocol by remember { mutableStateOf(current.protocol) }
    var host by remember { mutableStateOf(current.host) }
    var port by remember { mutableStateOf(current.port.takeIf { it > 0 }?.toString().orEmpty()) }
    val draft = ProxySetting(mode, protocol, host.trim(), port.toIntOrNull() ?: 0)
    val portInvalid = port.isNotEmpty() && draft.port !in 1..65535
    val canSave = mode != ProxyMode.MANUAL || draft.isManualComplete

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
        title = { Text("网络代理") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.selectableGroup()) {
                    ProxyModeRow("跟随系统", "使用系统设置中的代理", mode == ProxyMode.SYSTEM) { mode = ProxyMode.SYSTEM }
                    ProxyModeRow("不使用代理", "始终直接连接", mode == ProxyMode.NONE) { mode = ProxyMode.NONE }
                    ProxyModeRow("手动", "指定 HTTP 或 SOCKS5 代理", mode == ProxyMode.MANUAL) { mode = ProxyMode.MANUAL }
                }
                if (mode == ProxyMode.MANUAL) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ProxyProtocol.entries.forEachIndexed { index, entry ->
                            SegmentedButton(
                                selected = protocol == entry,
                                onClick = { protocol = entry },
                                shape = SegmentedButtonDefaults.itemShape(index, ProxyProtocol.entries.size),
                                label = { Text(entry.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("地址") },
                        placeholder = { Text("127.0.0.1") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                        label = { Text("端口") },
                        placeholder = { Text("7890") },
                        singleLine = true,
                        isError = portInvalid,
                        supportingText = if (portInvalid) {
                            { Text("端口在 1 到 65535 之间") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    // 已建立的连接沿用原来的路线，空闲一阵后关闭，之后才换
                    text = "对新建立的连接生效。视频直链与转码流由播放器直接读取，不经代理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = canSave) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ProxyModeRow(title: String, supporting: String, selected: Boolean, onSelect: () -> Unit) {
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
