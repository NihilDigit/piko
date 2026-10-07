package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.piko.shared.net.ProxyMode
import dev.piko.shared.net.ProxyProtocol
import dev.piko.shared.net.ProxySetting
import dev.piko.ui.components.PikoTextField

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

    // 选了方式还要填地址才算数，所以是填写类的「取消」「保存」，不是点选即生效
    SettingsInputDialog(
        icon = Icons.Outlined.Public,
        title = "网络代理",
        // 已建立的连接沿用原来的路线，空闲一阵后关闭，之后才换
        footer = "对新建连接生效。视频直链与转码流不经代理。",
        onSave = { onSave(draft) },
        saveEnabled = canSave,
        onDismiss = onDismiss,
    ) {
        Column(Modifier.selectableGroup()) {
            SettingsChoiceOption("跟随系统", mode == ProxyMode.SYSTEM, { mode = ProxyMode.SYSTEM }, supporting = "使用系统设置中的代理")
            SettingsChoiceOption("不使用代理", mode == ProxyMode.NONE, { mode = ProxyMode.NONE }, supporting = "始终直接连接")
            SettingsChoiceOption("手动", mode == ProxyMode.MANUAL, { mode = ProxyMode.MANUAL }, supporting = "指定 HTTP 或 SOCKS5 代理")
        }
        if (mode == ProxyMode.MANUAL) {
            SettingsSegmentedChoice(
                options = ProxyProtocol.entries,
                selected = protocol,
                label = { it.label },
                onSelect = { protocol = it },
                fill = true,
            )
            PikoTextField(
                value = host,
                onValueChange = { host = it },
                label = "地址",
                placeholder = "127.0.0.1",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            PikoTextField(
                value = port,
                onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                label = "端口",
                placeholder = "7890",
                isError = portInvalid,
                supportingText = "1 至 65535",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}
