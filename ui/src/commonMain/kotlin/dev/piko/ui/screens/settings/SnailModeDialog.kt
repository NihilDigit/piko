package dev.piko.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoDialogConfirm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.SnailMode
import dev.piko.ui.components.toReadableSize

/** 蜗牛模式的上下行上限。设置页与状态栏共用；保存时不改开关，只改上限。 */
@Composable
fun SnailModeDialog(
    current: SnailMode,
    onSave: (SnailMode) -> Unit,
    onDismiss: () -> Unit,
) {
    var download by remember { mutableStateOf(current.downloadKiBps.toString()) }
    var upload by remember { mutableStateOf(current.uploadKiBps.toString()) }
    val downloadValue = download.toIntOrNull()?.takeIf { it > 0 }
    val uploadValue = upload.toIntOrNull()?.takeIf { it > 0 }

    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
        title = { Text("蜗牛模式") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LimitField("下载上限", download, downloadValue == null) { download = it }
                LimitField("上传上限", upload, uploadValue == null) { upload = it }
                Text(
                    text = "开启后下载与上传各自不超过这个速度，在线播放不受限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            PikoDialogConfirm(
                label = "保存",
                onClick = { onSave(current.copy(downloadKiBps = downloadValue!!, uploadKiBps = uploadValue!!)) },
                enabled = downloadValue != null && uploadValue != null,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun LimitField(label: String, value: String, invalid: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter(Char::isDigit).take(7)) },
        label = { Text(label) },
        suffix = { Text("KB/s") },
        singleLine = true,
        isError = invalid,
        // 提示常驻、出错时只变色：这一行时有时无，按内容定高的对话框每删一个字就跳半行
        supportingText = { Text("大于 0 的整数") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 设置页与提示里的一句话：「下载 1 MB/s，上传 512 KB/s」。 */
fun SnailMode.limitSummary(): String =
    "下载 ${(downloadKiBps * 1024L).toReadableSize()}/s，上传 ${(uploadKiBps * 1024L).toReadableSize()}/s"
