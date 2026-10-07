package dev.piko.ui.screens.rename

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.naming.av.canonicalAvNameOf
import dev.piko.shared.naming.parseMediaName
import dev.piko.shared.scrape.MetaTubeService
import dev.piko.ui.components.InlineLoadingIndicator
import kotlinx.coroutines.launch

/**
 * 单个文件按番号规范命名时，对话框里取片名的一行：点一下从 MetaTube 查，查到就把输入框换成带这个片名的规范名。
 * 不在打开对话框时自动查：多数时候原名里的片名就够用，查询要等外部站点，几秒到十几秒。
 * 状态文字常驻这一行，查询前后对话框高度不变（对话框里不做尺寸变化，见 ui/CLAUDE.md）。
 */
@Composable
internal fun MetaTubeTitleRow(fileName: String, service: MetaTubeService, onNameFound: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var querying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = !querying,
            onClick = {
                val info = parseMediaName(fileName).av ?: return@TextButton
                querying = true
                status = "正在查询"
                failed = false
                scope.launch {
                    val result = service.titles(listOf(info))
                    val title = result.titles[info.code]
                    querying = false
                    failed = result.failed > 0
                    status = when {
                        title != null -> {
                            canonicalAvNameOf(fileName, title)?.let(onNameFound)
                            "已填入查到的片名"
                        }
                        failed -> "查询失败"
                        else -> "未查到片名"
                    }
                }
            },
        ) { Text("从 MetaTube 取片名") }
        Spacer(Modifier.width(8.dp))
        if (querying) InlineLoadingIndicator()
        Text(
            text = status.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
