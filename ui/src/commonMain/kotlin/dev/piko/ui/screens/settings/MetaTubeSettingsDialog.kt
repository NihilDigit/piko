package dev.piko.ui.screens.settings

import dev.piko.shared.i18n.tr

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
        description = tr("按番号命名时从自建的 MetaTube 服务获取片名。地址留空即不使用。", "Get titles from your MetaTube server when naming by catalog code. Leave the URL empty to disable.", "作品番号で命名するときに、自分のMetaTubeサーバーから作品名を取得します。アドレスを空にすると無効になります。", "품번으로 이름 지정 시 자체 MetaTube 서버에서 제목을 가져옵니다. 주소를 비워 두면 사용하지 않습니다.", "按番號命名時從自建的 MetaTube 服務取得片名。位址留空即不使用。"),
        onSave = { onSave(trimmedUrl, token.trim()) },
        saveEnabled = !urlInvalid,
        onDismiss = onDismiss,
    ) {
        PikoTextField(
            value = url,
            onValueChange = { url = it; result = null },
            label = tr("MetaTube 地址", "MetaTube URL", "MetaTubeアドレス", "MetaTube 주소", "MetaTube 位址"),
            placeholder = "http://192.168.1.2:8080",
            isError = urlInvalid,
            supportingText = tr("以 http:// 或 https:// 开头", "Start with http:// or https://", "http:// または https:// で始めてください", "http:// 또는 https://로 시작", "以 http:// 或 https:// 開頭"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        PikoTextField(
            value = token,
            onValueChange = { token = it; result = null },
            label = tr("访问令牌", "Access token", "アクセストークン", "액세스 토큰", "存取權杖"),
            supportingText = tr("服务端未设置令牌时留空", "Leave empty if the server has no token", "サーバーにトークンが未設定の場合は空欄", "서버에 토큰이 없으면 비워 두세요", "伺服器未設定權杖時留空"),
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
                                    if (check.tokenAccepted) tr("连接成功，${check.movieProviders} 个影片数据源", "Connected: ${check.movieProviders} movie providers", "接続しました。${check.movieProviders} 個の作品データソース", "연결됨: 영화 데이터 제공자 ${check.movieProviders}개", "連線成功，${check.movieProviders} 個影片資料來源") to false else tr("已连接，但访问令牌无效", "Connected, but access token is invalid", "接続しましたが、アクセストークンが無効です", "연결되었지만 액세스 토큰이 유효하지 않습니다", "已連線，但存取權杖無效") to true
                                },
                                onFailure = { tr("连接失败：${it.message}", "Connection failed: ${it.message}", "接続に失敗しました：${it.message}", "연결 실패: ${it.message}", "連線失敗：${it.message}") to true },
                            )
                        } finally {
                            testing = null
                        }
                    }
                },
                enabled = trimmedUrl.isNotEmpty() && !urlInvalid && testing == null,
            ) { Text(if (testing != null) tr("正在测试", "Testing", "テスト中", "테스트 중", "正在測試") else tr("测试连接", "Test connection", "接続をテスト", "연결 테스트", "測試連線")) }
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
