package dev.piko.ui.screens.settings

import dev.piko.shared.i18n.tr

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
        title = tr("服务器域名", "Server domain", "サーバードメイン", "서버 도메인", "伺服器網域"),
        onDismiss = onDismiss,
        footer = tr("各域名指向同一组服务器，账号通用。某一域名受限时可换用其他域名。", "Domains share the same servers and accounts. Switch domains if one is blocked.", "各ドメインは同じサーバーに接続し、アカウントも共通です。接続できない場合は別のドメインに切り替えられます。", "모든 도메인은 같은 서버와 계정을 사용합니다. 특정 도메인이 차단되면 다른 도메인으로 전환하세요.", "各網域指向同一組伺服器，帳號通用。某個網域受限時可改用其他網域。"),
        // 测速放在列表上方：原来是底部与「完成」并排的第二个按钮，读起来像另一种提交方式（ux-review M2）
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (probing) tr("正在测速", "Testing latency", "応答時間を測定中", "응답 시간 측정 중", "正在測速") else tr("延迟为最近一次测速结果", "Latency from the most recent test", "直近の測定結果を表示", "최근 측정 결과의 응답 시간", "延遲為最近一次測速結果"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TooltipIconButton(Icons.Outlined.Refresh, tr("重新测速", "Test again", "再測定", "다시 측정", "重新測速"), onProbeAgain, enabled = !probing)
            }
        },
    ) {
        SettingsChoiceOption(
            title = tr("自动选择", "Automatic", "自動選択", "자동 선택", "自動選擇"),
            // 测速是异步的，结果回来才有域名；原先「按测速结果选用最快的域名，当前为 …」在窄屏上
            // 折成两行，整个对话框随之跳一下。有域名时只写域名，短到一行放得下
            supporting = active?.let { tr("测速选定 ${it.root}", "Latency test selected ${it.root}", "測定結果から ${it.root} を選択", "측정 결과 ${it.root} 선택", "測速選定 ${it.root}") } ?: tr("选用测速最快的域名", "Use the domain with the lowest latency", "応答時間が最短のドメインを使用", "응답이 가장 빠른 도메인 사용", "選用測速最快的網域"),
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
    this == null -> if (probing) tr("测速中", "Testing", "測定中", "측정 중", "測速中") else tr("未测速", "Not tested", "未測定", "미측정", "未測速")
    !usable || warmRequest == null -> tr("不可用", "Unavailable", "利用不可", "사용 불가", "無法使用")
    else -> tr("延迟 ${warmRequest!!.inWholeMilliseconds} ms", "Latency ${warmRequest!!.inWholeMilliseconds} ms", "応答時間 ${warmRequest!!.inWholeMilliseconds} ms", "응답 시간 ${warmRequest!!.inWholeMilliseconds} ms", "延遲 ${warmRequest!!.inWholeMilliseconds} ms")
}

/** 设置页那一行的说明：「自动选择 mypikpak.net，延迟 92 ms」，固定时是「固定为 mypikpak.net，延迟 92 ms」。 */
fun domainSummary(choice: String, active: PikPakDomain?, probes: Map<PikPakDomain, DomainProbe>): String {
    val latency = active?.let { probes[it] }?.takeIf { it.usable }?.warmRequest?.let { tr("，延迟 ${it.inWholeMilliseconds} ms", ", latency ${it.inWholeMilliseconds} ms", "、応答時間 ${it.inWholeMilliseconds} ms", ", 응답 시간 ${it.inWholeMilliseconds} ms", "，延遲 ${it.inWholeMilliseconds} ms") }.orEmpty()
    val using = active?.root ?: choice.ifEmpty { null }
    return when {
        choice.isEmpty() && using == null -> tr("自动选择", "Automatic", "自動選択", "자동 선택", "自動選擇")
        choice.isEmpty() -> tr("自动选择 $using$latency", "Automatic: $using$latency", "自動選択 $using$latency", "자동 선택 $using$latency", "自動選擇 $using$latency")
        else -> tr("固定为 $choice$latency", "Fixed: $choice$latency", "固定 $choice$latency", "고정 $choice$latency", "固定為 $choice$latency")
    }
}
