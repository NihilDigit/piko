package dev.piko.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.piko.data.auth.PlayerGestureDefaults
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFile
import dev.piko.shared.media.DownloadQuality
import dev.piko.shared.media.chooseDownloadQuality
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.common_cancel
import dev.piko.ui.resources.text_choose_download_quality
import dev.piko.ui.resources.text_unable_to_get_video_qualities_try_again_later
import dev.piko.ui.resources.text_use_the_highest_quality_within_the_limit_or_the_lowest_if_none_fits_transcod
import dev.piko.ui.resources.text_use_this_quality_for_future_downloads
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.CancellationException

/**
 * 点「下载指定画质」时选择，入口见 [DownloadLauncher]；普通下载不打开画质对话框。
 * 单个视频列出原画与各档转码及其大小；几项一起（多选、文件夹）只列画质级别，每个视频各自按 downloadQualityOrder 挑，
 * 不逐个探测：文件夹里上千个视频，逐个查详情太慢。
 * 开启「每次下载前选择位置」时，在同对话框内嵌保存位置卡片，避免连续弹出两个模态框。
 */
@Composable
internal fun DownloadQualityDialog(
    request: DownloadRequest,
    onPickLocation: () -> Unit,
    onConfirm: (choice: DownloadChoice, keepQuality: Boolean, keepLocation: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var keepQuality by remember(request) { mutableStateOf(false) }
    var keepLocation by remember(request) { mutableStateOf(false) }
    val video = request.video
    if (video != null) {
        SingleVideoDialog(
            request = request,
            file = video,
            keepQuality = keepQuality,
            onKeepQualityChange = { keepQuality = it },
            keepLocation = keepLocation,
            onKeepLocationChange = { keepLocation = it },
            onPickLocation = onPickLocation,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    } else {
        var picked by remember(request) { mutableStateOf(ORIGINAL_MAX_HEIGHT) }
        QualityDialogFrame(
            focusKey = request,
            confirmEnabled = true,
            onConfirm = { onConfirm(DownloadChoice.Cap(picked), keepQuality, keepLocation) },
            onDismiss = onDismiss,
        ) {
            Text(stringResource(Res.string.text_use_the_highest_quality_within_the_limit_or_the_lowest_if_none_fits_transcod), style = MaterialTheme.typography.bodyMedium)
            Column(modifier = Modifier.selectableGroup()) {
                PlayerGestureDefaults.MaxHeightChoices.forEach { height ->
                    QualityRow(
                        label = if (height <= 0) tr("原画", "Original", "オリジナル", "원본", "原畫") else "${height}P",
                        detail = null,
                        selected = height == picked,
                        enabled = true,
                    ) { picked = height }
                }
            }
            KeepChoiceRow(keepQuality) { keepQuality = it }
            if (request.askLocation) {
                DownloadLocationCard(
                    targetDirectory = request.targetDirectory,
                    onPickLocation = onPickLocation,
                )
                RememberDownloadLocationRow(
                    checked = keepLocation,
                    onCheckedChange = { keepLocation = it },
                )
            }
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
    request: DownloadRequest,
    file: FileStat,
    keepQuality: Boolean,
    onKeepQualityChange: (Boolean) -> Unit,
    keepLocation: Boolean,
    onKeepLocationChange: (Boolean) -> Unit,
    onPickLocation: () -> Unit,
    onConfirm: (choice: DownloadChoice, keepQuality: Boolean, keepLocation: Boolean) -> Unit,
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
            PikoLog.w(TAG, "Failed to probe downloadable qualities for ${logFile(file.id, file.name)}", e)
            value = QualityChoices(probing = false, failed = true)
        }
    }
    // 按档名记住点了哪一档：大小探到后列表换成新的对象，记对象的话选中就丢了
    var picked by remember(file.id) { mutableStateOf<Picked?>(null) }
    val options = choices.options
    val pickedOption = options?.let { all -> picked?.let { p -> all.firstOrNull { it.name == p.name } } }
    // 点过的档随后探出读不出：不替用户换一档，清掉选中、停用「下载」，并说明原因
    val pickedUnreadable = pickedOption?.unreadable == true
    val selected = if (pickedUnreadable) null else pickedOption ?: options?.let { chooseDownloadQuality(it, ORIGINAL_MAX_HEIGHT) ?: it.first() }

    QualityDialogFrame(
        focusKey = request,
        confirmEnabled = selected != null,
        onConfirm = { selected?.let { onConfirm(DownloadChoice.Exact(it), keepQuality, keepLocation) } },
        onDismiss = onDismiss,
    ) {
        when {
            choices.failed -> Text(stringResource(Res.string.text_unable_to_get_video_qualities_try_again_later), style = MaterialTheme.typography.bodyMedium)
            options == null -> {
                QualityListSkeleton()
                if (request.askLocation) {
                    DownloadLocationCard(
                        targetDirectory = request.targetDirectory,
                        onPickLocation = onPickLocation,
                    )
                    RememberDownloadLocationRow(
                        checked = keepLocation,
                        onCheckedChange = onKeepLocationChange,
                    )
                }
            }
            else -> {
                Text(
                    if (options.size > 1) tr("转码视频将保存为 MP4 格式。", "Transcoded videos are saved as MP4.", "変換動画はMP4形式で保存されます。", "변환 동영상은 MP4 형식으로 저장됩니다.", "轉碼影片將儲存為 MP4 格式。") else tr("此视频无可用转码版本，仅可下载原画。", "No transcoded versions available. Only the original can be downloaded.", "利用可能な変換バージョンがないため、オリジナルのみダウンロードできます。", "사용 가능한 변환 버전이 없어 원본만 다운로드할 수 있습니다.", "此影片無可用轉碼版本，僅可下載原畫。"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    options.forEach { option ->
                        QualityRow(
                            label = option.name ?: tr("原画", "Original", "オリジナル", "원본", "原畫"),
                            detail = when {
                                option.unreadable -> tr("转码流暂不可用", "Temporarily unavailable", "一時的に利用不可", "일시적으로 사용 불가", "轉碼串流暫無法使用")
                                else -> option.sizeBytes?.toReadableSize() ?: if (choices.probing) tr("正在查询大小", "Checking size", "サイズを確認中", "크기 확인 중", "正在查詢大小") else tr("大小未知", "Unknown size", "サイズ不明", "크기 알 수 없음", "大小未知")
                            },
                            selected = option.name == selected?.name,
                            enabled = !option.unreadable,
                        ) { picked = Picked(option.name) }
                    }
                }
                if (pickedUnreadable) {
                    Text(
                        tr("${pickedOption?.name} 转码流暂不可用，请选择其他画质。", "${pickedOption?.name} transcoded stream is unavailable. Choose another quality.", "${pickedOption?.name} の変換ストリームを利用できません。別の画質を選択してください。", "${pickedOption?.name} 변환 스트림을 사용할 수 없습니다. 다른 화질을 선택해 주세요.", "${pickedOption?.name} 轉碼串流暫時無法使用，請選擇其他畫質。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                KeepChoiceRow(keepQuality, onKeepQualityChange)
                if (request.askLocation) {
                    DownloadLocationCard(
                        targetDirectory = request.targetDirectory,
                        onPickLocation = onPickLocation,
                    )
                    RememberDownloadLocationRow(
                        checked = keepLocation,
                        onCheckedChange = onKeepLocationChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun QualityDialogFrame(
    focusKey: Any,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusKey) {
        focusRequester.requestFocus()
    }
    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.HighQuality, contentDescription = null) },
        title = { Text(stringResource(Res.string.text_choose_download_quality)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .focusRequester(focusRequester)
                    .focusable()
                    .onDialogEnterConfirm(enabled = confirmEnabled, onConfirm = onConfirm),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
            }
        },
        confirmButton = {
            PikoDialogConfirm(label = tr("下载", "Download", "ダウンロード", "다운로드", "下載"), onClick = onConfirm, enabled = confirmEnabled)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** 勾上把本次选择存为普通下载的默认画质；显式指定画质始终询问。 */
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
        Text(stringResource(Res.string.text_use_this_quality_for_future_downloads), style = MaterialTheme.typography.bodyMedium)
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

private const val ORIGINAL_MAX_HEIGHT = 0
