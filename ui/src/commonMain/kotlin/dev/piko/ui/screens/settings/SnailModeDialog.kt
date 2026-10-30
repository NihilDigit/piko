package dev.piko.ui.screens.settings

import dev.piko.shared.i18n.tr

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
        title = tr("蜗牛模式", "Snail mode", "低速モード", "저속 모드", "蝸牛模式"),
        footer = tr("下载与上传各自不超过此速度，不影响在线播放。", "Limit download and upload speeds separately. Streaming is unaffected.", "ダウンロードとアップロードの速度を個別に制限します。ストリーミングには影響しません。", "다운로드 및 업로드 속도를 각각 제한합니다. 스트리밍에는 영향이 없습니다.", "下載與上傳各自不超過此速度，不影響線上播放。"),
        onSave = { onSave(current.copy(downloadKiBps = downloadValue!!, uploadKiBps = uploadValue!!)) },
        saveEnabled = downloadValue != null && uploadValue != null,
        onDismiss = onDismiss,
    ) {
        LimitField(tr("下载上限", "Download limit", "ダウンロード上限", "다운로드 한도", "下載上限"), download, downloadValue == null) { download = it }
        LimitField(tr("上传上限", "Upload limit", "アップロード上限", "업로드 한도", "上傳上限"), upload, uploadValue == null) { upload = it }
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
        supportingText = tr("大于 0 的整数", "A positive integer", "0より大きい整数", "0보다 큰 정수", "大於 0 的整數"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** 设置页与提示里的一句话：「下载 1 MB/s，上传 512 KB/s」。 */
fun SnailMode.limitSummary(): String =
    tr("下载 ${(downloadKiBps * 1024L).toReadableSize()}/s，上传 ${(uploadKiBps * 1024L).toReadableSize()}/s", "Download ${(downloadKiBps * 1024L).toReadableSize()}/s, upload ${(uploadKiBps * 1024L).toReadableSize()}/s", "ダウンロード ${(downloadKiBps * 1024L).toReadableSize()}/s、アップロード ${(uploadKiBps * 1024L).toReadableSize()}/s", "다운로드 ${(downloadKiBps * 1024L).toReadableSize()}/s, 업로드 ${(uploadKiBps * 1024L).toReadableSize()}/s", "下載 ${(downloadKiBps * 1024L).toReadableSize()}/s，上傳 ${(uploadKiBps * 1024L).toReadableSize()}/s")
