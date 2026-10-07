package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.DownloadQuality
import dev.piko.ui.LocalPikoServices
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private class QualityChoices(
    val options: List<DownloadQuality>? = null,
    /** 还在逐档探测大小。探完仍为 null 的大小写「大小未知」。 */
    val probing: Boolean = true,
    val failed: Boolean = false,
)

/** 用户点过的那一档，原画的 [name] 为 null。 */
private class Picked(val name: String?)

/**
 * 「选择画质下载」：列出原画与各档转码及其大小，选一档下载。转码档的大小要逐档探测，先列出档位、大小随后补上。
 * 预选的是设置里的下载画质会挑的那一档，直接点「下载」与普通下载结果相同。
 */
@Composable
fun QualityDownloadDialog(
    file: FileStat,
    onDownload: (DownloadQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    val services = LocalPikoServices.current
    val choices by produceState(QualityChoices(), file.id) {
        try {
            services.mediaRepository.downloadQualities(file.id).collect { value = QualityChoices(it) }
            value = QualityChoices(value.options, probing = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            PikoLog.w(TAG, "查询可下载的画质失败：${logFile(file.id, file.name)}", e)
            value = QualityChoices(probing = false, failed = true)
        }
    }
    val defaultCap by produceState(0) { value = services.preferences.downloadMaxHeightFlow.first() }
    // 按档名记住点了哪一档：大小探到后列表换成新的对象，记对象的话选中就丢了
    var picked by remember(file.id) { mutableStateOf<Picked?>(null) }
    val options = choices.options
    val pickedOption = options?.let { all -> picked?.let { p -> all.firstOrNull { it.name == p.name } } }
    // 点过的档随后探出读不出：不替用户换一档，清掉选中、停用「下载」，并说明原因
    val pickedUnreadable = pickedOption?.unreadable == true
    val selected = if (pickedUnreadable) null else pickedOption ?: options?.let { defaultDownloadQuality(it, defaultCap) }

    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.HighQuality, contentDescription = null) },
        title = { Text("选择画质下载") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    choices.failed -> Text("无法查询这个视频的画质，请稍后重试。", style = MaterialTheme.typography.bodyMedium)
                    options == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else -> {
                        Text(
                            if (options.size > 1) "转码档下载后存为 MP4。" else "这个视频没有转码档，只能下载原画。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Column(modifier = Modifier.selectableGroup()) {
                            options.forEach { option ->
                                QualityRow(option, selected = option.name == selected?.name, probing = choices.probing) { picked = Picked(option.name) }
                            }
                        }
                        if (pickedUnreadable) {
                            Text(
                                "${pickedOption?.name} 的转码文件无法读取，请改选其他画质。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            PikoDialogConfirm(
                label = "下载",
                onClick = {
                    selected?.let(onDownload)
                    onDismiss()
                },
                enabled = selected != null,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 读不出的档留在原位、停用，而不是从列表里拿掉：M3 的列表项与单选钮都有停用态，表示「在、但不能操作」；
 * 拿掉的话下面几行在指针底下往上跳，用户刚点的那一档也无从说起。
 */
@Composable
private fun QualityRow(option: DownloadQuality, selected: Boolean, probing: Boolean, onSelect: () -> Unit) {
    val enabled = !option.unreadable
    // M3 停用态：内容取 onSurface 的 38%
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 单选钮不单独接点击，免得点钮和点行各触发一次
        RadioButton(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.padding(horizontal = 12.dp))
        Text(
            option.name ?: "原画",
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else disabledColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            when {
                option.unreadable -> "无法读取"
                else -> option.sizeBytes?.toReadableSize() ?: if (probing) "正在查询大小" else "大小未知"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

/** 设置里的下载画质会挑的那一档：不高于上限、读得出的最大转码，没有就是原画（列表第一项）。 */
internal fun defaultDownloadQuality(options: List<DownloadQuality>, cap: Int): DownloadQuality =
    options.filter { it.mediaId != null && !it.unreadable && cap > 0 && it.height in 1..cap }.maxByOrNull { it.height } ?: options.first()

private const val TAG = "Download"
