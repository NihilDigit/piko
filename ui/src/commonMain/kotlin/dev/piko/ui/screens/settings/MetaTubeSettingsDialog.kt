package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.piko.shared.scrape.MetaTubeService
import dev.piko.ui.components.PikoTextField
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * MetaTube 的地址与访问令牌。Piko 不内置、不推荐任何实例，两项都由用户填写；地址留空即不用刮削。
 * 「测试连接」用框里还没保存的值试，试过不必先保存。
 */
@Composable
internal fun MetaTubeSettingsDialog(
    service: MetaTubeService,
    currentUrl: String,
    currentToken: String,
    onSave: (url: String, token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(currentUrl) }
    var token by remember { mutableStateOf(currentToken) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var testing by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val trimmedUrl = url.trim()
    val urlInvalid = trimmedUrl.isNotEmpty() && !trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")

    SettingsInputDialog(
        icon = Icons.Outlined.TravelExplore,
        title = "MetaTube",
        description = "按番号命名时从自建的 MetaTube 服务获取片名。地址留空即不使用。",
        onSave = { onSave(trimmedUrl, token.trim()) },
        saveEnabled = !urlInvalid,
        onDismiss = onDismiss,
    ) {
        PikoTextField(
            value = url,
            onValueChange = { url = it; result = null },
            label = "MetaTube 地址",
            placeholder = "http://192.168.1.2:8080",
            isError = urlInvalid,
            supportingText = "以 http:// 或 https:// 开头",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        PikoTextField(
            value = token,
            onValueChange = { token = it; result = null },
            label = "访问令牌",
            supportingText = "服务端未设置令牌时留空",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    testing?.cancel()
                    result = null
                    testing = scope.launch {
                        try {
                            result = service.check(trimmedUrl, token.trim()).fold(
                                onSuccess = { check ->
                                    if (check.tokenAccepted) "连接成功，${check.movieProviders} 个影片数据源" to false else "已连接，但访问令牌无效" to true
                                },
                                onFailure = { "连接失败：${it.message}" to true },
                            )
                        } finally {
                            testing = null
                        }
                    }
                },
                enabled = trimmedUrl.isNotEmpty() && !urlInvalid && testing == null,
            ) { Text(if (testing != null) "正在测试" else "测试连接") }
            result?.let { (message, isError) ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
