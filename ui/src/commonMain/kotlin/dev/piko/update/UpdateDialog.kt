package dev.piko.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.piko.download.DownloadStatus
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.components.toReadableSize
import dev.piko.ui.platform.LocalPikoPlatform
import kotlinx.coroutines.launch

/**
 * 新版本对话框：版本、更新说明、下载进度与安装。开屏提示与设置页共用。
 *
 * 按 M3 的基本对话框排，两个动作位：主动作随状态变（下载、重试、重启更新），另一个在开屏时是
 * 「忽略此版本」，在设置页是「在浏览器中查看」。关掉对话框靠点外面或返回。
 * 下载中不许关：关掉后下载就成了没有任何反馈的后台任务。
 *
 * 更新说明是 updateNotesOf 截出的 Markdown，由 ReleaseNotes 排版，可选中复制，限高滚动，免得把按钮顶出去。
 */
@Composable
fun UpdateDialog(
    updater: AppUpdateService,
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    /** 为 null 时不给「忽略此版本」，改给「在浏览器中查看」。 */
    onIgnore: (() -> Unit)? = null,
) {
    val platform = LocalPikoPlatform.current
    val downloadManager = LocalPikoServices.current.downloadManager
    val scope = rememberCoroutineScope()
    // 状态属于另一个版本时（例如设置页查到了更新的版本）按未开始处理
    val status = updater.status.takeIf { it.update?.version == update.version }
    val downloading = status is UpdateStatus.Downloading
    var confirmInterrupt by remember { mutableStateOf(false) }

    val restart: () -> Unit = {
        val hasActiveDownloads = downloadManager.tasks.value.values.any {
            it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING
        }
        if (hasActiveDownloads) {
            confirmInterrupt = true
        } else {
            scope.launch { updater.restartToInstall(update) }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        // 平台默认宽度是给一句话加两个按钮定的，更新说明按它排每行只剩十来个字
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Piko ${update.version}")
                Text(
                    text = "当前版本 ${platform.appVersion}，需下载 ${update.downloadSize.toReadableSize()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 带底色的一块：它是对话框里唯一会滚的区域，没有边界时滚到一半看不出哪里是末尾
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ReleaseNotes(
                        markdown = update.notes.ifBlank { "此版本没有更新说明" },
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }
                when (status) {
                    is UpdateStatus.Downloading -> {
                        LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "${(update.downloadSize * status.progress).toLong().toReadableSize()} / " +
                                update.downloadSize.toReadableSize(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateStatus.ReadyToRestart -> Text(
                        text = "已下载，Piko 将退出并在更新后重新打开",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is UpdateStatus.Failed -> Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> if (!update.canInstallInApp) {
                        Text(
                            text = "此版本无法在应用内安装，请从下载页获取",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                !update.canInstallInApp -> Button(onClick = { platform.openUrl(update.pageUrl) }) { Text("前往下载页") }
                status is UpdateStatus.Downloading -> Button(onClick = {}, enabled = false) {
                    Text("正在下载 ${(status.progress * 100).toInt()}%")
                }
                status is UpdateStatus.ReadyToRestart -> Button(onClick = restart) { Text("重启并更新") }
                status is UpdateStatus.Installing -> Button(onClick = {}, enabled = false) { Text("等待安装") }
                // 失败后主动作就是再来一次，与第一次下载没有区别
                status is UpdateStatus.Failed -> Button(onClick = { scope.launch { updater.downloadAndInstall(update) } }) {
                    Text("重试")
                }
                else -> Button(onClick = { scope.launch { updater.downloadAndInstall(update) } }) { Text("下载并安装") }
            }
        },
        dismissButton = {
            if (!downloading) {
                if (onIgnore != null) {
                    TextButton(onClick = onIgnore) { Text("忽略此版本") }
                } else if (update.canInstallInApp) {
                    TextButton(onClick = { platform.openUrl(update.pageUrl) }) { Text("在浏览器中查看") }
                }
            }
        },
    )

    if (confirmInterrupt) {
        AlertDialog(
            onDismissRequest = { confirmInterrupt = false },
            title = { Text("中断下载并更新？") },
            text = { Text("重启更新会中断正在进行的下载。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmInterrupt = false
                    scope.launch { updater.restartToInstall(update) }
                }) { Text("重启并更新") }
            },
            dismissButton = {
                TextButton(onClick = { confirmInterrupt = false }) { Text("取消") }
            },
        )
    }
}

private val UpdateStatus.update: AvailableUpdate?
    get() = when (this) {
        is UpdateStatus.Available -> update
        is UpdateStatus.Downloading -> update
        is UpdateStatus.ReadyToRestart -> update
        is UpdateStatus.Installing -> update
        is UpdateStatus.Failed -> update
        UpdateStatus.Idle, UpdateStatus.Checking, UpdateStatus.UpToDate -> null
    }
