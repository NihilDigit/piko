package dev.piko.ui.screens.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import dev.piko.data.auth.SnailMode
import dev.piko.ui.components.PikoTextField
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

    SettingsInputDialog(
        icon = Icons.Outlined.Speed,
        title = "蜗牛模式",
        footer = "下载与上传各自不超过此速度，不影响在线播放。",
        onSave = { onSave(current.copy(downloadKiBps = downloadValue!!, uploadKiBps = uploadValue!!)) },
        saveEnabled = downloadValue != null && uploadValue != null,
        onDismiss = onDismiss,
    ) {
        LimitField("下载上限", download, downloadValue == null) { download = it }
        LimitField("上传上限", upload, uploadValue == null) { upload = it }
    }
}

@Composable
private fun LimitField(label: String, value: String, invalid: Boolean, onValueChange: (String) -> Unit) {
    PikoTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter(Char::isDigit).take(7)) },
        label = label,
        suffix = "KB/s",
        isError = invalid,
        supportingText = "大于 0 的整数",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** 设置页与提示里的一句话：「下载 1 MB/s，上传 512 KB/s」。 */
fun SnailMode.limitSummary(): String =
    "下载 ${(downloadKiBps * 1024L).toReadableSize()}/s，上传 ${(uploadKiBps * 1024L).toReadableSize()}/s"
