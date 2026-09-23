package dev.piko.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.piko.BuildConfig
import dev.piko.ui.components.toReadableSize
import dev.piko.update.AppUpdater
import dev.piko.update.AvailableUpdate
import dev.piko.update.UpdateStatus
import kotlinx.coroutines.launch

/**
 * 新版本面板：版本、更新说明、下载进度与安装。
 *
 * 更新说明是 Release 的 Markdown 原文，这里按纯文本显示、可选中复制；限高滚动，
 * 长说明不会把安装按钮顶出屏幕。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdateSheet(
    updater: AppUpdater,
    update: AvailableUpdate,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = updater.status
    val downloading = status as? UpdateStatus.Downloading
    val openReleasePage = {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.pageUrl))) }
        Unit
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Piko ${update.version}", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "当前版本 ${BuildConfig.VERSION_NAME}，安装包 ${update.apkSize.toReadableSize()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (update.notes.isNotBlank()) {
                SelectionContainer {
                    Text(
                        text = update.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            if (downloading != null) {
                LinearProgressIndicator(progress = { downloading.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "正在下载 ${(downloading.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            (status as? UpdateStatus.Failed)?.let {
                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (updater.canInstallInApp) {
                    OutlinedButton(onClick = openReleasePage) { Text("在浏览器中查看") }
                    Button(
                        onClick = { scope.launch { updater.downloadAndInstall(update) } },
                        enabled = downloading == null && status !is UpdateStatus.Installing,
                    ) {
                        Text(if (status is UpdateStatus.Installing) "等待安装" else "下载并安装")
                    }
                } else {
                    // debug 包的包名与 Release 不同，装上去是另一个应用，只给下载页
                    Button(onClick = openReleasePage) { Text("前往下载页") }
                }
            }
        }
    }
}
