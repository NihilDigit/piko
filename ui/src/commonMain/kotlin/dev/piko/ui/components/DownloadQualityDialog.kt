package dev.piko.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
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
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.chooseDownloadQuality
import dev.piko.ui.LocalPikoServices
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException

/**
 * 点「下载」时选画质，入口见 [DownloadLauncher]。默认选中设置里的下载画质会挑的那一档，直接点「下载」与不询问时结果相同。
 * 单个视频列出原画与各档转码及其大小；几项一起（多选、文件夹）只列画质级别，每个视频各自按 downloadQualityOrder 挑，
 * 不逐个探测：文件夹里上千个视频，逐个查详情太慢。
 */
@Composable
internal fun DownloadQualityDialog(
    request: DownloadRequest,
    onConfirm: (choice: DownloadChoice, keep: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var keep by remember(request) { mutableStateOf(false) }
    val video = request.video
    if (video != null) {
        SingleVideoDialog(video, request.defaultCap, keep, { keep = it }, onConfirm, onDismiss)
    } else {
        var picked by remember(request) { mutableStateOf(request.defaultCap) }
        QualityDialogFrame(
            confirmEnabled = true,
            onConfirm = { onConfirm(DownloadChoice.Cap(picked), keep) },
            onDismiss = onDismiss,
        ) {
            Text("每个视频取不高于所选的最高一档，都高于所选时取最低一档。转码档存为 MP4。", style = MaterialTheme.typography.bodyMedium)
            Column(modifier = Modifier.selectableGroup()) {
                PlayerGestureDefaults.MaxHeightChoices.forEach { height ->
                    QualityRow(
                        label = if (height <= 0) "原画" else "${height}P",
                        detail = null,
                        selected = height == picked,
                        enabled = true,
                    ) { picked = height }
                }
            }
            KeepChoiceRow(keep) { keep = it }
        }
    }
}

private class QualityChoices(
    val options: List<DownloadQuality>? = null,
    /** 还在逐档探测大小。探完仍为 null 的大小写「大小未知」。 */
    val probing: Boolean = true,
    val failed: Boolean = false,
)

/** 用户点过的那一档，原画的 [name] 为 null。 */
private class Picked(val name: String?)

/** 单个视频：转码档的大小要逐档探测，先列出档位、大小随后补上。 */
@Composable
private fun SingleVideoDialog(
    file: FileStat,
    defaultCap: Int,
    keep: Boolean,
    onKeepChange: (Boolean) -> Unit,
    onConfirm: (choice: DownloadChoice, keep: Boolean) -> Unit,
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
    // 按档名记住点了哪一档：大小探到后列表换成新的对象，记对象的话选中就丢了
    var picked by remember(file.id) { mutableStateOf<Picked?>(null) }
    val options = choices.options
    val pickedOption = options?.let { all -> picked?.let { p -> all.firstOrNull { it.name == p.name } } }
    // 点过的档随后探出读不出：不替用户换一档，清掉选中、停用「下载」，并说明原因
    val pickedUnreadable = pickedOption?.unreadable == true
    val selected = if (pickedUnreadable) null else pickedOption ?: options?.let { chooseDownloadQuality(it, defaultCap) ?: it.first() }

    QualityDialogFrame(
        confirmEnabled = selected != null,
        onConfirm = { selected?.let { onConfirm(DownloadChoice.Exact(it), keep) } },
        onDismiss = onDismiss,
    ) {
        when {
            choices.failed -> Text("无法查询这个视频的画质，请稍后重试。", style = MaterialTheme.typography.bodyMedium)
            options == null -> QualityListSkeleton()
            else -> {
                Text(
                    if (options.size > 1) "转码档下载后存为 MP4。" else "这个视频没有转码档，只能下载原画。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    options.forEach { option ->
                        QualityRow(
                            label = option.name ?: "原画",
                            detail = when {
                                option.unreadable -> "PikPak 暂不可读"
                                else -> option.sizeBytes?.toReadableSize() ?: if (choices.probing) "正在查询大小" else "大小未知"
                            },
                            selected = option.name == selected?.name,
                            enabled = !option.unreadable,
                        ) { picked = Picked(option.name) }
                    }
                }
                if (pickedUnreadable) {
                    Text(
                        "PikPak 的 ${pickedOption?.name} 转码文件暂不可读，请改选其他画质。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                KeepChoiceRow(keep, onKeepChange)
            }
        }
    }
}

@Composable
private fun QualityDialogFrame(
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.HighQuality, contentDescription = null) },
        title = { Text("选择下载画质") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { content() } },
        confirmButton = {
            PikoDialogConfirm(label = "下载", onClick = onConfirm, enabled = confirmEnabled)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 勾上即以后不再弹出，按这一档（换算成画质上限）直接下载；在设置的「下载」里改回。 */
@Composable
private fun KeepChoiceRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text("以后按此画质直接下载", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 列出档位之前的骨架：说明一行与几行 [QualityRow]。行的尺寸照抄那一行：最小高 48dp，单选钮 20dp、
 * 自带 2dp 内边距、两侧各 12dp，档名一行 bodyLarge，大小一行 bodyMedium、右侧 12dp。
 * 档数事先不知道，取常见的原画加两档转码；对话框按内容定高，猜得接近才少跳一下。
 */
@Composable
private fun QualityListSkeleton(rows: Int = 3) {
    SkeletonGroup {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SkeletonText(MaterialTheme.typography.bodyMedium, 0.5f)
            Column {
                repeat(rows) { index ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SkeletonBlock(Modifier.padding(horizontal = 14.dp).size(20.dp), CircleShape)
                        SkeletonText(MaterialTheme.typography.bodyLarge, QualityNameWidths[index % QualityNameWidths.size], Modifier.weight(1f))
                        SkeletonBlock(Modifier.padding(end = 12.dp).size(width = 56.dp, height = 12.dp))
                    }
                }
            }
        }
    }
}

private val QualityNameWidths = listOf(0.3f, 0.4f, 0.35f)

/**
 * 读不出的档留在原位、停用，而不是从列表里拿掉：M3 的列表项与单选钮都有停用态，表示「在、但不能操作」；
 * 拿掉的话下面几行在指针底下往上跳，用户刚点的那一档也无从说起。
 */
@Composable
private fun QualityRow(label: String, detail: String?, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
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
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else disabledColor,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

private const val TAG = "Download"
