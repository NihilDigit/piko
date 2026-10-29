package dev.piko.ui.screens.settings

import dev.piko.shared.i18n.tr

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
        title = tr("网络代理", "Network proxy", "ネットワークプロキシ", "네트워크 프록시"),
        // 已建立的连接沿用原来的路线，空闲一阵后关闭，之后才换
        footer = tr("对新建连接生效。视频直链与转码流不经代理。", "Applies to new connections. Direct video and transcoded streams bypass the proxy.", "新しい接続に適用します。動画の直リンクと変換ストリームはプロキシを経由しません。", "새 연결에 적용됩니다. 동영상 직접 링크와 변환 스트림은 프록시를 거치지 않습니다."),
        onSave = { onSave(draft) },
        saveEnabled = canSave,
        onDismiss = onDismiss,
    ) {
        Column(Modifier.selectableGroup()) {
            SettingsChoiceOption(tr("跟随系统", "System", "システムに従う", "시스템 설정 따르기"), mode == ProxyMode.SYSTEM, { mode = ProxyMode.SYSTEM }, supporting = tr("使用系统设置中的代理", "Use system proxy settings", "システムのプロキシ設定を使用", "시스템 프록시 설정 사용"))
            SettingsChoiceOption(tr("不使用代理", "No proxy", "プロキシを使用しない", "프록시 사용 안 함"), mode == ProxyMode.NONE, { mode = ProxyMode.NONE }, supporting = tr("始终直接连接", "Always connect directly", "常に直接接続", "항상 직접 연결"))
            SettingsChoiceOption(tr("手动", "Manual", "手動", "수동"), mode == ProxyMode.MANUAL, { mode = ProxyMode.MANUAL }, supporting = tr("指定 HTTP 或 SOCKS5 代理", "Specify an HTTP or SOCKS5 proxy", "HTTPまたはSOCKS5プロキシを指定", "HTTP 또는 SOCKS5 프록시 지정"))
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
                label = tr("地址", "Address", "アドレス", "주소"),
                placeholder = "127.0.0.1",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            PikoTextField(
                value = port,
                onValueChange = { value -> port = value.filter(Char::isDigit).take(5) },
                label = tr("端口", "Port", "ポート", "포트"),
                placeholder = "7890",
                isError = portInvalid,
                supportingText = tr("1 至 65535", "1 to 65535", "1 ～ 65535", "1~65535"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}
